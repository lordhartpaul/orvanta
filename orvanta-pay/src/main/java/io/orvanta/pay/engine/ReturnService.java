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
 * Return of funds (pacs.004 on a return channel): the receiving side sends back a payment it had
 * accepted, for example because the account turned out to be closed. The transaction becomes
 * RETURNED with the reason given, and the customer is told in the next status report.
 */
public final class ReturnService {

    private final Platform platform;

    public ReturnService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.RETURN_RECEIVED, "return", m -> handle(m.str("id")));
        platform.bus.subscribe(Bus.inboundTopic("reversal"), "reversal", m -> handleReversal(m.str("id")));
    }

    public void handle(String messageId) {
        DocStore store = platform.store;
        if (!Lifecycle.claim(platform, messageId)) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec returned = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int count = 0;
            List<Object> unmatched = new ArrayList<>();
            for (Object e : Ops.list(returned.get("entries"))) {
                Rec entry = (Rec) e;
                String txnId = entry.str("transactionId");
                Rec txn = txnId == null ? null : store.get(DocStore.TXN, txnId);
                if (txn == null) {
                    unmatched.add(Rec.of("transactionId", txnId, "problem", "no such transaction"));
                    continue;
                }
                String code = entry.str("reasonCode") == null ? "NARR" : entry.str("reasonCode");
                String text = entry.str("reasonText") == null ? "Returned by the receiving side" : entry.str("reasonText");
                Rec detail = Rec.of("messageId", messageId, "returnId", entry.str("returnId"), "amount", entry.get("amount"),
                        "currency", entry.str("currency"), "at", Platform.now());
                boolean done = false;
                for (String from : List.of(Status.ACCEPTED, Status.SENT)) {
                    done = done || store.updateIf(DocStore.TXN, txnId, Rec.of("status", from), Rec.of("status", Status.RETURNED,
                            "reasonCode", code, "reasonText", text, "return", detail, "updatedAt", Platform.now()));
                }
                if (!done) {
                    unmatched.add(Rec.of("transactionId", txnId, "problem", "transaction is " + txn.str("status") + " and cannot be returned"));
                    continue;
                }
                count++;
                platform.event(txnId, Status.RETURNED, "returned in " + messageId + ": " + code + " " + text, null, null);
                Lifecycle.reopenReport(platform, txn.str("instructionId"));
                Lifecycle.unwound(platform, txnId);
            }
            Lifecycle.finish(platform, messageId, Rec.of("msgId", returned.str("msgId"), "returnedCount", count, "unmatched", unmatched),
                    count + " returned, " + unmatched.size() + " unmatched");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }

    /**
     * A reversal from the bank of the creditor (pacs.007 on a channel with purpose "reversal"): a direct
     * debit collection that was debited from our customer is taken back by the side that collected it,
     * for example because it was collected twice. The debit is reversed on the account, the collection
     * ends as RETURNED, and the customer is told. Nothing is sent back: the money comes to us.
     */
    public void handleReversal(String messageId) {
        DocStore store = platform.store;
        if (!Lifecycle.claim(platform, messageId)) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec reversal = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int reversed = 0;
            List<Object> unmatched = new ArrayList<>();
            PostingService posting = new PostingService(platform);
            for (Object e : Ops.list(reversal.get("entries"))) {
                Rec entry = (Rec) e;
                Rec filter = Rec.of("paymentType", Incoming.DEBIT_TYPE, "originalTxId", String.valueOf(entry.str("originalTxId")));
                if (entry.str("originalMsgId") != null) {
                    filter.put("originalMsgId", entry.str("originalMsgId"));
                }
                Rec txn = null;
                for (Rec candidate : store.find(DocStore.TXN, filter, "id", true, 5)) {
                    txn = txn == null || Status.DEBITED.equals(candidate.str("status")) ? candidate : txn;
                }
                if (txn == null) {
                    unmatched.add(Rec.of("originalTxId", entry.str("originalTxId"), "problem", "no collection with these references was received"));
                    continue;
                }
                String id = txn.str("id");
                if (!Status.DEBITED.equals(txn.str("status"))) {
                    unmatched.add(Rec.of("transactionId", id, "problem", "the collection is " + txn.str("status") + ", not debited, so there is nothing to reverse"));
                    continue;
                }
                // the customer gets the money back first; only then is the collection marked as reversed
                if ("POSTED".equals(txn.str("posting.status")) && !posting.reverse(id, true)) {
                    unmatched.add(Rec.of("transactionId", id, "problem", "the debit could not be reversed on the account"));
                    platform.event(id, "REVERSAL_FAILED", "reversal " + messageId + " could not be booked", null, null);
                    continue;
                }
                String code = entry.str("reasonCode") == null ? "NARR" : entry.str("reasonCode");
                String text = entry.str("reasonText") == null ? "Reversed by the creditor" : entry.str("reasonText");
                if (store.updateIf(DocStore.TXN, id, Rec.of("status", Status.DEBITED), Rec.of("status", Status.RETURNED, "reasonCode", code, "reasonText", text,
                        "return", Rec.of("reasonCode", code, "reasonText", text, "requestedBy", "the creditor's bank", "requestedAt", Platform.now(),
                                "messageId", messageId, "reversalId", entry.str("reversalId")), "updatedAt", Platform.now()))) {
                    reversed++;
                    platform.event(id, Status.RETURNED, "reversed by the creditor's bank in " + messageId + ": " + code + " " + text, null, null);
                    NotificationService.reversed(platform, id);
                }
            }
            Lifecycle.finish(platform, messageId, Rec.of("msgId", reversal.str("msgId"), "returnedCount", reversed, "unmatched", unmatched),
                    reversed + " reversed, " + unmatched.size() + " unmatched");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }
}
