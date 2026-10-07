package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** What every service needs: configuration, storage, bus, the active deployment, ids and the audit trail. */
public final class Platform {

    public final Config config;
    public final DocStore store;
    public final Bus bus;
    public final Deployments deployments;
    public final Path dataDir;
    public final Path workspaceDir;
    public final io.orvanta.core.flow.Elements.DataAccess data;
    public final io.orvanta.pay.transport.RabbitLinks rabbit = new io.orvanta.pay.transport.RabbitLinks();
    public final io.orvanta.pay.transport.KafkaLinks kafka = new io.orvanta.pay.transport.KafkaLinks();
    /** Set while the process stops: health answers STOPPING so that no new work is sent its way. */
    public volatile boolean stopping;

    public Platform(Config config, DocStore store, Bus bus) {
        this.config = config;
        this.store = store;
        this.bus = bus;
        this.deployments = new Deployments(store, bus);
        this.dataDir = config.dir("data.dir", "data");
        this.workspaceDir = config.dir("workspace.dir", "workspace");
        this.data = new StoreDataAccess(this);
        String hosts = config.get("security.connectorHosts", "");
        this.deployments.secure(hosts.isBlank() ? java.util.List.of() : java.util.List.of(hosts.split("\\s*,\\s*")),
                config.get("security.integrityKey", null));
        // an event whose handler keeps failing is kept in the dead-letter list, where an operator can requeue it
        bus.onFailure((topic, message, error) -> store.insert(DocStore.DEAD_LETTER, Rec.of(
                "id", UUID.randomUUID().toString(), "topic", topic, "message", message, "refId", message.str("id"),
                "error", String.valueOf(error), "status", "OPEN", "at", now())));
    }

    private io.orvanta.pay.engine.MessageChecks checks;

    /** The checks of received and built messages; one per process, so that a schema is compiled once. */
    public synchronized io.orvanta.pay.engine.MessageChecks checks() {
        if (checks == null) {
            checks = new io.orvanta.pay.engine.MessageChecks(this);
        }
        return checks;
    }

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    /** Identifiers are a fixed prefix and a ten digit sequence: ORVINS0000000042 (16 characters, fits MT field 20). */
    public String newId(String prefix) {
        return String.format("%s%010d", prefix, store.nextSequence(prefix));
    }

    /** Topic on which every process hears that something a list or the dashboard shows has changed. */
    public static final String CHANGED = "orv.ui.changed";

    private final java.util.Set<String> changedKinds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ScheduledExecutorService changeNotices;

    /** Notes that something of a kind ("payments", "approvals", "models") changed; told to the Console shortly after. */
    public void changed(String kind) {
        changedKinds.add(kind);
    }

    /**
     * Starts telling the other processes what changed here. Changes are collected and announced at most
     * once a second, as the kinds that changed and nothing else, so a busy engine sends one small notice
     * a second however many payments it moves.
     */
    public synchronized void startChangeNotices() {
        if (changeNotices != null) {
            return;
        }
        changeNotices = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-changes");
            t.setDaemon(true);
            return t;
        });
        changeNotices.scheduleWithFixedDelay(() -> {
            if (changedKinds.isEmpty()) {
                return;
            }
            java.util.List<Object> kinds = new java.util.ArrayList<>(changedKinds);
            changedKinds.removeAll(kinds);
            try {
                bus.publish(CHANGED, Rec.of("kinds", kinds));
            } catch (RuntimeException e) {
                // the notice is a convenience; the lists also refresh without it
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    public synchronized void stopChangeNotices() {
        if (changeNotices != null) {
            changeNotices.shutdownNow();
            changeNotices = null;
        }
    }

    /** Appends to the audit trail of an instruction, transaction, outbound file or approval. */
    public void event(String refId, String type, String text, Rec detail, String actor) {
        changed(refId != null && refId.startsWith("ORVAPR") ? "approvals" : "payments");
        store.insert(DocStore.EVENT, Rec.of("id", UUID.randomUUID().toString(), "refId", refId, "type", type,
                "text", text, "detail", detail, "actor", actor == null ? "system" : actor, "at", now()));
    }
}
