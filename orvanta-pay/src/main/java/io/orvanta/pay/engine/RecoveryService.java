package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Finds work that stopped half way (a process died, an event was lost) and puts it back on its
 * path, and retries transactions parked in REPAIR by a failed external call.
 *
 * Every step of the engine is a compare-and-set on a status, so anything found in an in-between
 * status for longer than recovery.stuckSeconds has no owner any more. Putting it back to the
 * status before and announcing it again is safe: whoever picks it up claims it the same way.
 */
public final class RecoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryService.class);

    private final Platform platform;
    private final ProcessingService processing;
    private final long stuckSeconds;
    private ScheduledExecutorService scheduler;

    public RecoveryService(Platform platform, ProcessingService processing) {
        this.platform = platform;
        this.processing = processing;
        this.stuckSeconds = platform.config.getInt("recovery.stuckSeconds", 60);
    }

    public void start() {
        int interval = platform.config.getInt("recovery.intervalSeconds", 10);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-recovery");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                LOG.error("recovery sweep failed", e);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** @return number of items put back on their path */
    public synchronized int sweep() {
        return messages() + transactions() + outbound() + retries() + unanswered() + expired() + new RequestToPayService(platform).expire();
    }

    private boolean stale(Rec doc, String... timeFields) {
        for (String field : timeFields) {
            String at = doc.str(field);
            if (at != null) {
                return Instant.parse(at).plusSeconds(stuckSeconds).isBefore(Instant.now());
            }
        }
        return false;
    }

    private int messages() {
        DocStore store = platform.store;
        int n = 0;
        // received but never announced, or the announcement was lost
        for (Rec m : store.find(DocStore.MESSAGE, Rec.of("status", Status.RECEIVED), "receivedAt", false, 200)) {
            if (stale(m, "receivedAt")) {
                platform.bus.publish(Bus.inboundTopic(m.str("purpose")), Rec.of("id", m.str("id")));
                n++;
            }
        }
        // debulking was interrupted: remove what it had staged and start the file again
        for (Rec m : store.find(DocStore.MESSAGE, Rec.of("status", Status.DEBULKING), "receivedAt", false, 200)) {
            if (!stale(m, "claimedAt", "receivedAt")) {
                continue;
            }
            for (Rec txn : store.find(DocStore.TXN, Rec.of("instructionId", m.str("id"), "status", Status.STAGED), null, false, 0)) {
                store.delete(DocStore.TXN, txn.str("id"));
            }
            for (Rec batch : store.find(DocStore.BATCH, Rec.of("instructionId", m.str("id")), null, false, 0)) {
                store.delete(DocStore.BATCH, batch.str("id"));
            }
            if (store.updateIf(DocStore.MESSAGE, m.str("id"), Rec.of("status", Status.DEBULKING), Rec.of("status", Status.RECEIVED))) {
                recovered(m.str("id"), "debulking was interrupted; started again");
                platform.bus.publish(Bus.INSTRUCTION_RECEIVED, Rec.of("id", m.str("id")));
                n++;
            }
        }
        // acknowledgements, cancellations, resolutions and returns apply each entry with its own compare-and-set
        for (Rec m : store.find(DocStore.MESSAGE, Rec.of("status", Status.PROCESSING), "receivedAt", false, 200)) {
            if (stale(m, "claimedAt", "receivedAt")
                    && store.updateIf(DocStore.MESSAGE, m.str("id"), Rec.of("status", Status.PROCESSING), Rec.of("status", Status.RECEIVED))) {
                recovered(m.str("id"), "processing was interrupted; started again");
                platform.bus.publish(Bus.inboundTopic(m.str("purpose")), Rec.of("id", m.str("id")));
                n++;
            }
        }
        for (Rec m : store.find(DocStore.MESSAGE, Rec.of("reportState", "REPORTING"), "receivedAt", false, 200)) {
            if (stale(m, "reportClaimedAt") && store.updateIf(DocStore.MESSAGE, m.str("id"), Rec.of("reportState", "REPORTING"), Rec.of("reportState", "OPEN"))) {
                n++;
            }
        }
        return n;
    }

    private int transactions() {
        DocStore store = platform.store;
        int n = 0;
        // staged by a debulk that completed but died before releasing them
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.STAGED), "id", false, 500)) {
            Rec instruction = store.get(DocStore.MESSAGE, txn.str("instructionId"));
            if (stale(txn, "createdAt") && instruction != null && Status.DEBULKED.equals(instruction.str("status"))
                    && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.STAGED), Rec.of("status", Status.CREATED, "updatedAt", Platform.now()))) {
                platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txn.str("id")));
                n++;
            }
        }
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.CREATED), "id", false, 500)) {
            if (stale(txn, "updatedAt")) {
                platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txn.str("id")));
                n++;
            }
        }
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.PROCESSING), "id", false, 500)) {
            if (stale(txn, "updatedAt") && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.PROCESSING),
                    Rec.of("status", Status.CREATED, "updatedAt", Platform.now()))) {
                recovered(txn.str("id"), "processing was interrupted; started again");
                platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txn.str("id")));
                n++;
            }
        }
        // waiting: run again when the flow asked for a retry, give up when no answer came in time
        long waitTimeout = platform.config.getInt("engine.waitTimeoutSeconds", 3600);
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.WAITING), "id", false, 500)) {
            String retryAt = txn.str("wait.retryAt");
            String since = txn.str("wait.since");
            String until = txn.str("wait.until");
            boolean overdue = until != null ? Instant.parse(until).isBefore(Instant.now())
                    : since != null && Instant.parse(since).plusSeconds(waitTimeout).isBefore(Instant.now());
            if (overdue) {
                if (store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.WAITING), Rec.of("status", Status.REPAIR,
                        "reasonCode", "WAIT_TIMEOUT", "reasonText", "No answer for " + txn.str("wait.code") + (until != null ? " by " + until : " within " + waitTimeout + " seconds"),
                        "updatedAt", Platform.now()))) {
                    platform.event(txn.str("id"), Status.REPAIR, "WAIT_TIMEOUT: gave up waiting for " + txn.str("wait.code"), null, "recovery");
                    n++;
                }
            } else if (retryAt != null && Instant.parse(retryAt).isBefore(Instant.now())
                    && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.WAITING), Rec.of("status", Status.CREATED, "updatedAt", Platform.now()))) {
                platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txn.str("id")));
                n++;
            }
        }
        // warehoused: released when its date or time has come (the model clock, so tests can move it)
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.WAREHOUSED), "id", false, 1000)) {
            String until = txn.str("warehouse.until");
            if (until != null && !Instant.parse(until).isAfter(io.orvanta.core.expr.Time.now())
                    && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.WAREHOUSED), Rec.of("status", Status.CREATED, "updatedAt", Platform.now()))) {
                platform.event(txn.str("id"), "RELEASED", "released from the warehouse (" + txn.str("warehouse.code") + ")", null, "recovery");
                platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txn.str("id")));
                n++;
            }
        }
        // a posting that should have been reversed and was not
        for (String status : List.of(Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL, Status.CANCELLED, Status.RETURNED)) {
            for (Rec txn : store.find(DocStore.TXN, Rec.of("status", status, "posting.status", "POSTED"), "id", false, 200)) {
                if (stale(txn, "updatedAt")) {
                    platform.bus.publish(Bus.TXN_UNWOUND, Rec.of("id", txn.str("id")));
                    n++;
                }
            }
        }
        // claimed for a bulk that was never stored
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.BULKED), "id", false, 500)) {
            if (stale(txn, "updatedAt") && store.get(DocStore.OUTBOUND, String.valueOf(txn.str("outboundId"))) == null
                    && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.BULKED), Rec.of("status", Status.ROUTED, "updatedAt", Platform.now()))) {
                recovered(txn.str("id"), "bulking was interrupted; waiting for the next bulk");
                n++;
            }
        }
        return n;
    }

    private int outbound() {
        DocStore store = platform.store;
        int n = 0;
        for (Rec o : store.find(DocStore.OUTBOUND, Rec.of("status", Status.CREATED), "id", false, 200)) {
            if (stale(o, "createdAt")) {
                platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", o.str("id")));
                n++;
            }
        }
        // delivery is repeated: a folder destination overwrites the same file; an HTTP destination must tolerate a repeat
        for (Rec o : store.find(DocStore.OUTBOUND, Rec.of("status", Status.DISPATCHING), "id", false, 200)) {
            if (stale(o, "claimedAt", "createdAt")
                    && store.updateIf(DocStore.OUTBOUND, o.str("id"), Rec.of("status", Status.DISPATCHING), Rec.of("status", Status.CREATED))) {
                recovered(o.str("id"), "delivery was interrupted; started again");
                platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", o.str("id")));
                n++;
            }
        }
        return n;
    }

    /** Transactions parked by a failed external call are tried again when their back-off time has passed. */
    /**
     * Payments sent on a channel that expects its answer within a time ('answerWithinSeconds', for an
     * instant rail) and has not had one: marked once, so that people see them and ask the clearing what
     * became of them. Nothing is decided here; only the answer decides.
     */
    private int unanswered() {
        DocStore store = platform.store;
        int n = 0;
        for (Rec channel : platform.deployments.registry().configs("Channel")) {
            if (!"outbound".equals(channel.str("direction")) || channel.get("answerWithinSeconds") == null) {
                continue;
            }
            String before = java.time.Instant.now().minusSeconds(Ops.num(channel.get("answerWithinSeconds")).longValue()).toString();
            for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.SENT, "route.channel", channel.str("name")), "id", false, 500)) {
                if (txn.get("answerOverdue") == null && txn.str("sentAt") != null && txn.str("sentAt").compareTo(before) < 0
                        && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.SENT), Rec.of("answerOverdue", true, "answerOverdueAt", Platform.now()))) {
                    platform.event(txn.str("id"), "ANSWER_OVERDUE", "no answer within the " + channel.get("answerWithinSeconds")
                            + " seconds that " + channel.str("name") + " allows", null, null);
                    // and the rail is asked what became of it, when the channel names where to ask
                    StatusEnquiryService.enquire(platform, store.get(DocStore.TXN, txn.str("id")), channel);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * A payment held for a person longer than its inbound channel allows ('heldExpiryDays') is turned down
     * the way that person would turn it down: an incoming payment goes back to its sender, an outgoing one
     * is rejected, each with its hold code and a reason that says it expired. Nothing is ever released by
     * time alone. A channel without the setting keeps its payments held until someone decides.
     */
    private int expired() {
        DocStore store = platform.store;
        int n = 0;
        java.util.Map<String, Integer> days = new java.util.HashMap<>();
        for (Rec channel : platform.deployments.registry().configs("Channel")) {
            if ("inbound".equals(channel.str("direction")) && channel.get("heldExpiryDays") != null) {
                days.put(channel.str("name"), Ops.num(channel.get("heldExpiryDays")).intValue());
            }
        }
        if (days.isEmpty()) {
            return 0;
        }
        for (Rec txn : store.find(DocStore.TXN, Rec.of("status", Status.HELD), "id", false, 1000)) {
            Integer limit = days.get(String.valueOf(txn.str("channelIn")));
            String since = txn.str("hold.since");
            if (limit == null || since == null || !Instant.parse(since).plusSeconds(86400L * limit).isBefore(io.orvanta.core.expr.Time.now())) {
                continue;
            }
            try {
                String comment = "no decision within " + limit + " day(s); expired";
                platform.event(txn.str("id"), "HOLD_EXPIRED", comment, null, "system");
                if (new ReviewService(platform).reject(txn.str("id"), "system", comment)) {
                    n++;
                }
            } catch (RuntimeException e) {
                LOG.error("expiring the held payment {} failed", txn.str("id"), e);
            }
        }
        return n;
    }

    private int retries() {
        int n = 0;
        for (Rec txn : platform.store.find(DocStore.TXN, Rec.of("status", Status.REPAIR), "id", false, 500)) {
            String next = txn.str("retry.nextAt");
            if (next != null && Instant.parse(next).isBefore(Instant.now()) && processing.handle(txn.str("id"), Status.REPAIR, "auto-retry")) {
                n++;
            }
        }
        return n;
    }

    private void recovered(String refId, String text) {
        LOG.warn("recovered {}: {}", refId, text);
        platform.event(refId, "RECOVERED", text, null, "recovery");
    }
}
