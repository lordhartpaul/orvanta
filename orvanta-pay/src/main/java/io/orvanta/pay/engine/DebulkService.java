package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Elements.RuleSet;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.flow.Violation;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a received instruction into batches and transactions. The channel's mapping produces the
 * canonical instruction, the channel's rule set validates the file as a whole, a duplicate check
 * guards against the same file arriving twice, then one document per batch and transaction is created.
 */
public final class DebulkService {

    private final Platform platform;

    public DebulkService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.INSTRUCTION_RECEIVED, "debulk", m -> handle(m.str("id")));
    }

    public void handle(String messageId) {
        DocStore store = platform.store;
        if (!store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.RECEIVED),
                Rec.of("status", Status.DEBULKING, "claimedAt", Platform.now()))) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw"), channel, registry::config).get(0).tree();
            Rec instr = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));

            if (channel.str("validation") != null) {
                List<Violation> violations = registry.require(channel.str("validation"), RuleSet.class).check(Rec.of("instr", instr));
                for (Violation v : violations) {
                    if (v.isError()) {
                        reject(messageId, v.code(), v.message(), violations);
                        return;
                    }
                }
            }
            String dedupeKey = message.str("channel") + "|" + instr.str("msgId");
            if (!store.insertIfAbsent(DocStore.DEDUPE, Rec.of("id", dedupeKey, "messageId", messageId, "at", Platform.now()))) {
                Rec first = store.get(DocStore.DEDUPE, dedupeKey);
                // the key is this file's own when its debulking was interrupted and is being repeated
                if (first == null || !messageId.equals(first.str("messageId"))) {
                reject(messageId, "AM05", "Duplicate of " + (first == null ? "an earlier message" : first.str("messageId"))
                        + ": message id " + instr.str("msgId")
                        + " was already received on this channel", List.of());
                return;
                }
            }

            int batchCount = 0;
            int txnCount = 0;
            BigDecimal total = BigDecimal.ZERO;
            List<String> txnIds = new ArrayList<>();
            for (Object b : Ops.list(instr.get("batches"))) {
                Rec batch = ((Rec) b).copy();
                List<?> txns = Ops.list(batch.remove("txns"));
                String batchId = platform.newId("ORVBAT");
                BigDecimal batchTotal = BigDecimal.ZERO;
                for (Object t : txns) {
                    Rec txn = ((Rec) t).copy();
                    String txnId = platform.newId("ORVTXN");
                    txn.put("id", txnId);
                    txn.put("instructionId", messageId);
                    txn.put("batchId", batchId);
                    txn.put("channelIn", message.str("channel"));
                    txn.set("batchRef", batch.str("batchRef"));
                    // batch level values apply to every transaction that does not carry its own;
                    // a channel names them when its message keeps other things at batch level (a direct debit keeps the creditor there)
                    List<?> inherit = channel.get("inherit") == null ? List.of("requestedDate", "debtor") : Ops.list(channel.get("inherit"));
                    for (Object name : inherit) {
                        String inherited = Ops.str(name);
                        if (txn.get(inherited) == null && batch.get(inherited) != null) {
                            txn.put(inherited, Rec.deep(batch.get(inherited)));
                        }
                    }
                    txn.set("processingFlow", channel.str("processingFlow"));
                    txn.set("paymentType", channel.str("paymentType"));
                    // what the channel wants done with a possible duplicate: REJECT (default) or HOLD for a person
                    txn.set("duplicates", channel.str("duplicates"));
                    // what an incoming channel wants done with the funds on a sanctions hit: RETURN (default) or FREEZE them while a person decides
                    txn.set("sanctionsHit", channel.str("sanctionsHit"));
                    // staged until the whole file is debulked, so an interrupted debulk leaves nothing to process
                    txn.put("status", Status.STAGED);
                    txn.put("createdAt", Platform.now());
                    txn.put("updatedAt", Platform.now());
                    store.insert(DocStore.TXN, txn);
                    if (!Ops.blank(txn.get("amount"))) {
                        try {
                            batchTotal = batchTotal.add(Ops.num(txn.get("amount")));
                        } catch (IllegalArgumentException ignored) {
                            // a non-numeric amount is reported by transaction validation
                        }
                    }
                    txnIds.add(txnId);
                }
                batch.put("id", batchId);
                batch.put("instructionId", messageId);
                batch.put("transactionCount", txns.size());
                batch.put("totalAmount", batchTotal);
                batch.put("createdAt", Platform.now());
                store.insert(DocStore.BATCH, batch);
                batchCount++;
                txnCount += txns.size();
                total = total.add(batchTotal);
            }
            store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.DEBULKING), Rec.of("status", Status.DEBULKED,
                    "msgId", instr.str("msgId"), "initiatingParty", instr.str("initiatingParty"), "batchCount", batchCount,
                    "transactionCount", txnCount, "totalAmount", total, "debulkedAt", Platform.now(),
                    "reportState", channel.str("statusReport") == null ? null : "OPEN"));
            platform.event(messageId, "DEBULKED", batchCount + " batch(es), " + txnCount + " transaction(s)", null, null);
            for (String txnId : txnIds) {
                if (store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.STAGED), Rec.of("status", Status.CREATED, "updatedAt", Platform.now()))) {
                    platform.event(txnId, Status.CREATED, "created from " + messageId, null, null);
                    platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txnId));
                }
            }
        } catch (RuntimeException e) {
            reject(messageId, "PROCESSING_ERROR", e.getMessage(), List.of());
        }
    }

    private void reject(String messageId, String code, String reason, List<Violation> violations) {
        List<Object> list = new ArrayList<>();
        for (Violation v : violations) {
            list.add(v.toRec());
        }
        Rec message = platform.store.get(DocStore.MESSAGE, messageId);
        Rec channel = platform.deployments.registry().config(String.valueOf(message.str("channel")));
        platform.store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.DEBULKING),
                Rec.of("status", Status.REJECTED, "reasonCode", code, "reasonText", String.valueOf(reason), "violations", list,
                        "reportState", channel == null || channel.str("statusReport") == null ? null : "OPEN"));
        // transactions staged before the failure never become payments
        for (Rec staged : platform.store.find(DocStore.TXN, Rec.of("instructionId", messageId, "status", Status.STAGED), null, false, 0)) {
            platform.store.delete(DocStore.TXN, staged.str("id"));
        }
        platform.event(messageId, "REJECTED", code + ": " + reason, null, null);
    }
}
