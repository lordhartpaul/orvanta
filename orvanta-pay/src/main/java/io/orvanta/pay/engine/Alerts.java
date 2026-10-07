package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incidents: conditions that need a person, watched on a timer. When a condition first holds an incident
 * is opened, kept in the store and told once to the configured webhook; when it stops holding the
 * incident is closed and told once more. Nothing is told twice, and nothing in it is payment data.
 *
 * <pre>
 * alerts:
 *   intervalSeconds: 60
 *   webhook: https://alerts.example/orvanta        # POST, JSON; optional: without it incidents are only kept and shown
 *   token: ${env.ALERT_TOKEN}                     # sent as Authorization: Bearer
 *   approvalMinutes: 60        # a request waiting longer than this for a second person
 *   heldMinutes: 120           # a payment held for review longer than this
 *   repairMinutes: 30          # a payment parked for an operator longer than this
 * </pre>
 */
public final class Alerts {

    private static final Logger LOG = LoggerFactory.getLogger(Alerts.class);
    public static final String INCIDENT = "orv_incident";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final Platform platform;
    private ScheduledExecutorService timer;

    public Alerts(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        int every = Math.max(5, platform.config.getInt("alerts.intervalSeconds", 60));
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-alerts");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                check();
            } catch (RuntimeException e) {
                LOG.error("alert check failed", e);
            }
        }, every, every, TimeUnit.SECONDS);
    }

    public void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** The conditions, each with the number it found: more than zero means the incident holds. */
    Map<String, Object[]> conditions() {
        DocStore store = platform.store;
        String approvalsBefore = Instant.now().minusSeconds(60L * platform.config.getInt("alerts.approvalMinutes", 60)).toString();
        String heldBefore = Instant.now().minusSeconds(60L * platform.config.getInt("alerts.heldMinutes", 120)).toString();
        String repairBefore = Instant.now().minusSeconds(60L * platform.config.getInt("alerts.repairMinutes", 30)).toString();
        Map<String, Object[]> found = new LinkedHashMap<>();
        found.put("store", new Object[] {store.ping() ? 0L : 1L, "the document store does not answer"});
        found.put("deadletters", new Object[] {store.count(DocStore.DEAD_LETTER, Rec.of("status", "OPEN")), "internal events failed and were not requeued"});
        found.put("answersOverdue", new Object[] {store.count(DocStore.TXN, Rec.of("status", Status.SENT, "answerOverdue", true)), "sent payments have no answer in the time their rail allows"});
        found.put("notificationsFailed", new Object[] {store.count(DocStore.TXN, Rec.of("notify.state", "FAILED")), "customer notifications could not be built"});
        found.put("outboundFailed", new Object[] {store.count(DocStore.OUTBOUND, Rec.of("status", Status.FAILED)), "outbound files could not be delivered"});
        found.put("approvalsWaiting", new Object[] {older(DocStore.APPROVAL, Rec.of("status", "PENDING"), "requestedAt", approvalsBefore), "requests wait too long for a second person"});
        found.put("paymentsHeld", new Object[] {older(DocStore.TXN, Rec.of("status", Status.HELD), "updatedAt", heldBefore), "payments are held for review too long"});
        found.put("paymentsInRepair", new Object[] {older(DocStore.TXN, Rec.of("status", Status.REPAIR), "updatedAt", repairBefore), "payments are parked for an operator too long"});
        return found;
    }

    private long older(String collection, Rec filter, String field, String before) {
        return platform.store.countMatching(collection, new DocStore.Query(filter, null, List.of(), Map.of(field, new Object[] {null, before}), null, false, 0, 0));
    }

    /** One round: opens, updates and closes incidents. @return incidents opened or closed in this round */
    public synchronized int check() {
        int changed = 0;
        for (Map.Entry<String, Object[]> e : conditions().entrySet()) {
            String key = e.getKey();
            long count = ((Number) e.getValue()[0]).longValue();
            String text = (String) e.getValue()[1];
            Rec open = platform.store.get(INCIDENT, key);
            boolean isOpen = open != null && "OPEN".equals(open.str("status"));
            if (count > 0 && !isOpen) {
                Rec incident = Rec.of("id", key, "status", "OPEN", "text", text, "count", count, "openedAt", Platform.now(), "updatedAt", Platform.now());
                platform.store.save(INCIDENT, incident);
                tell("opened", incident);
                changed++;
            } else if (count > 0) {
                if (count != ((Number) open.get("count")).longValue()) {
                    platform.store.updateIf(INCIDENT, key, new Rec(), Rec.of("count", count, "updatedAt", Platform.now()));
                }
            } else if (isOpen) {
                platform.store.updateIf(INCIDENT, key, Rec.of("status", "OPEN"), Rec.of("status", "CLOSED", "count", 0, "closedAt", Platform.now(), "updatedAt", Platform.now()));
                tell("closed", platform.store.get(INCIDENT, key));
                changed++;
            }
        }
        if (changed > 0) {
            platform.changed("incidents");
        }
        return changed;
    }

    public List<Rec> open() {
        return new ArrayList<>(platform.store.find(INCIDENT, Rec.of("status", "OPEN"), "openedAt", true, 100));
    }

    /** Tells the webhook; a webhook that fails is logged, and the incident is told again only when it changes state. */
    private void tell(String event, Rec incident) {
        LOG.warn("incident {}: {} ({}: {})", event, incident.str("id"), incident.get("count"), incident.str("text"));
        String webhook = platform.config.get("alerts.webhook", "");
        if (webhook.isBlank()) {
            return;
        }
        try {
            Rec body = Rec.of("event", event, "incident", incident.str("id"), "text", incident.str("text"), "count", incident.get("count"),
                    "at", Platform.now(), "deployment", platform.deployments.activeId(), "source", "orvanta");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            String token = platform.config.get("alerts.token", "");
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOG.error("the alert webhook answered HTTP {} for incident {}", response.statusCode(), incident.str("id"));
            }
        } catch (Exception e) {
            LOG.error("the alert webhook could not be reached for incident {}: {}", incident.str("id"), e.toString());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
