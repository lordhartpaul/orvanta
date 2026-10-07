package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies an acknowledgement from an external system to the transactions it reports on.
 * A sent transaction becomes ACCEPTED or REJECTED_BY_EXTERNAL (with the reason the external
 * system gave); entries that match nothing, or a transaction not waiting for an answer, are
 * recorded on the acknowledgement instead of being ignored silently.
 */
public final class AcknowledgementService {

    private final Platform platform;

    public AcknowledgementService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.ACK_RECEIVED, "acknowledge", m -> handle(m.str("id")));
    }

    public void handle(String messageId) {
        DocStore store = platform.store;
        if (!store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.RECEIVED),
                Rec.of("status", Status.PROCESSING, "claimedAt", Platform.now()))) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec ack = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));

            int accepted = 0;
            int rejected = 0;
            List<Object> unmatched = new ArrayList<>();
            for (Object e : Ops.list(ack.get("entries"))) {
                Rec entry = (Rec) e;
                String reported = entry.str("status");
                String status = Ops.in(reported, channel.get("accepted")) ? Status.ACCEPTED
                        : Ops.in(reported, channel.get("rejected")) ? Status.REJECTED_BY_EXTERNAL : null;
                String txnId = entry.str("transactionId");
                Rec txn = txnId == null ? null : store.get(DocStore.TXN, txnId);
                if (txn == null || status == null) {
                    unmatched.add(Rec.of("transactionId", txnId, "status", reported,
                            "problem", txn == null ? "no such transaction" : "status code is not mapped by the channel"));
                    continue;
                }
                Rec changes = Rec.of("status", status, "acknowledgementId", messageId, "externalStatus", reported,
                        "updatedAt", Platform.now());
                if (status.equals(Status.REJECTED_BY_EXTERNAL)) {
                    changes.put("reasonCode", entry.str("reasonCode") == null ? "NARR" : entry.str("reasonCode"));
                    changes.put("reasonText", entry.str("reasonText") == null ? "Rejected by the external system" : entry.str("reasonText"));
                }
                if (!store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.SENT), changes)) {
                    unmatched.add(Rec.of("transactionId", txnId, "status", reported,
                            "problem", "transaction is " + txn.str("status") + ", not waiting for an acknowledgement"));
                    continue;
                }
                platform.event(txnId, status, reported + " in " + messageId
                        + (changes.get("reasonCode") == null ? "" : ": " + changes.get("reasonCode") + " " + changes.get("reasonText")), null, null);
                Lifecycle.reopenReport(platform, txn.str("instructionId"));
                if (status.equals(Status.REJECTED_BY_EXTERNAL)) {
                    Lifecycle.unwound(platform, txnId);
                }
                if (status.equals(Status.ACCEPTED)) {
                    accepted++;
                } else {
                    rejected++;
                }
            }
            String original = ack.str("originalMsgId");
            if (original != null && store.updateIf(DocStore.OUTBOUND, original, Rec.of("status", Status.SENT),
                    Rec.of("status", Status.ACKNOWLEDGED, "acknowledgementId", messageId))) {
                platform.event(original, Status.ACKNOWLEDGED, "acknowledged by " + messageId, null, null);
            }
            store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.PROCESSING), Rec.of("status", Status.PROCESSED,
                    "msgId", ack.str("msgId"), "originalMsgId", original, "acceptedCount", accepted, "rejectedCount", rejected,
                    "unmatched", unmatched, "processedAt", Platform.now()));
            platform.event(messageId, Status.PROCESSED, accepted + " accepted, " + rejected + " rejected, " + unmatched.size() + " unmatched", null, null);
        } catch (RuntimeException e) {
            store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.PROCESSING),
                    Rec.of("status", Status.REJECTED, "reasonCode", "PROCESSING_ERROR", "reasonText", String.valueOf(e.getMessage())));
            platform.event(messageId, Status.REJECTED, "PROCESSING_ERROR: " + e.getMessage(), null, null);
        }
    }
}
