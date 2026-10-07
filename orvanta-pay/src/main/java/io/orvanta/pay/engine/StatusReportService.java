package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tells the customer what happened to an instruction (pain.002 on the status report channel named
 * by the inbound channel). A rejected file is reported at once. Otherwise the first report goes
 * out when every transaction has a final status, or after the channel's maxWaitSeconds with the
 * unfinished ones marked pending. A transaction that changes later (accepted then returned,
 * pending then accepted) is reported again; nothing is reported twice with the same status.
 */
public final class StatusReportService {

    private static final Logger LOG = LoggerFactory.getLogger(StatusReportService.class);

    private final Platform platform;
    private ScheduledExecutorService scheduler;

    public StatusReportService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-status-report");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                LOG.error("status report sweep failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** @return number of reports created */
    public synchronized int sweep() {
        int created = 0;
        DocStore store = platform.store;
        for (Rec instruction : store.find(DocStore.MESSAGE, Rec.of("reportState", "OPEN"), "receivedAt", false, 200)) {
            String id = instruction.str("id");
            // claim: with several instances only one reports an instruction at a time
            if (!store.updateIf(DocStore.MESSAGE, id, Rec.of("reportState", "OPEN"),
                    Rec.of("reportState", "REPORTING", "reportClaimedAt", Platform.now()))) {
                continue;
            }
            boolean complete = false;
            try {
                Registry registry = platform.deployments.registry();
                Rec inbound = registry.config(String.valueOf(instruction.str("channel")));
                Rec channel = inbound == null || inbound.str("statusReport") == null ? null : registry.config(inbound.str("statusReport"));
                if (channel == null) {
                    complete = true;
                } else if (Status.REJECTED.equals(instruction.str("status"))) {
                    send(registry, channel, instruction, List.of(), "RJCT");
                    created++;
                    complete = true;
                } else if (Status.DEBULKED.equals(instruction.str("status"))) {
                    long maxWait = channel.at("reporting.maxWaitSeconds") == null ? 30 : Ops.num(channel.at("reporting.maxWaitSeconds")).longValue();
                    boolean aged = Instant.parse(instruction.str("receivedAt")).plusSeconds(maxWait).isBefore(Instant.now());
                    boolean allFinal = true;
                    List<Rec> due = new ArrayList<>();
                    for (Rec txn : store.find(DocStore.TXN, Rec.of("instructionId", id), "id", false, 0)) {
                        boolean isFinal = Lifecycle.FINAL.contains(txn.str("status"));
                        allFinal = allFinal && isFinal;
                        String state = isFinal ? txn.str("status") : "PENDING";
                        if (!state.equals(txn.str("reportedStatus")) && (isFinal || aged)) {
                            txn.put("reportState", state);
                            due.add(txn);
                        }
                    }
                    if (!due.isEmpty() && (allFinal || aged)) {
                        send(registry, channel, instruction, due, null);
                        created++;
                        for (Rec txn : due) {
                            store.updateIf(DocStore.TXN, txn.str("id"), new Rec(), Rec.of("reportedStatus", txn.str("reportState")));
                        }
                    }
                    complete = allFinal;
                }
            } catch (RuntimeException e) {
                LOG.error("status report for {} failed", id, e);
                platform.event(id, "STATUS_REPORT_FAILED", String.valueOf(e.getMessage()), null, null);
            }
            // a transaction that changed meanwhile has set the state back to OPEN; then this leaves it open
            store.updateIf(DocStore.MESSAGE, id, Rec.of("reportState", "REPORTING"), Rec.of("reportState", complete ? "COMPLETE" : "OPEN"));
        }
        return created;
    }

    /** The BIC of the customer who sent an MT instruction: what the mapping kept, or else the sender in the header of the message as received. */
    private static String senderOf(Rec instruction) {
        for (String field : List.of("senderBic", "initiatingParty")) {
            String value = instruction.str(field);
            if (value != null && value.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?")) {
                return value;
            }
        }
        java.util.regex.Matcher header = java.util.regex.Pattern.compile("[{]1:[A-Z][0-9]{2}([A-Z]{6}[A-Z0-9]{2})").matcher(String.valueOf(instruction.str("raw")));
        return header.find() ? header.group(1) : null;
    }

    private void send(Registry registry, Rec channel, Rec instruction, List<Rec> txns, String groupStatus) {
        String outboundId = platform.newId("ORVOUT");
        Rec report = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z",
                "originalMsgId", instruction.str("msgId") == null ? instruction.str("id") : instruction.str("msgId"),
                "originalMessageType", instruction.str("messageType"), "instructionId", instruction.str("id"),
                "to", senderOf(instruction), "groupStatus", groupStatus, "reasonCode", groupStatus == null ? null : instruction.str("reasonCode"),
                "reasonText", groupStatus == null ? null : instruction.str("reasonText"));
        if (channel.get("properties") instanceof Map<?, ?> properties) {
            report.putAll(Rec.from(properties));
        }
        Map<String, Rec> batches = new LinkedHashMap<>();
        List<Object> ids = new ArrayList<>();
        for (Rec txn : txns) {
            String ref = txn.str("batchRef") == null ? String.valueOf(txn.str("batchId")) : txn.str("batchRef");
            batches.computeIfAbsent(ref, k -> Rec.of("batchRef", k)).list("txns").add(txn);
            ids.add(txn.str("id"));
        }
        Rec mapped = registry.require(channel.str("mapping"), Mapping.class)
                .apply(Rec.of("report", report, "batches", new ArrayList<Object>(batches.values())));
        String payload = Messages.write(channel.str("format"), mapped);
        // an invalid report is not sent; the event fails and shows under Failed events with the field named
        platform.checks().requireValidOutbound(channel, payload);
        platform.store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "statusReport", "channel", channel.str("name"),
                "format", channel.str("format"), "messageType", channel.str("messageType"), "instructionId", instruction.str("id"),
                "transactionCount", txns.size(), "transactionIds", ids, "status", Status.CREATED,
                "createdAt", Platform.now(), "payload", payload));
        platform.event(instruction.str("id"), "STATUS_REPORTED", "status report " + outboundId + " for "
                + (groupStatus == null ? txns.size() + " transaction(s)" : "the rejected file"), null, null);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
    }
}
