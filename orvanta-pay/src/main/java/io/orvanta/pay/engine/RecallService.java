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
 * Recall requests from the bank that sent us a payment (camt.056 on a channel with purpose "recall"):
 * it asks for a payment back, for example because it was sent twice or by fraud.
 * <ul>
 * <li>A payment that has not been credited yet (held, waiting, in repair) is sent back at once.</li>
 * <li>A payment that was credited needs a decision: the customer has the money. The request is kept on the
 *     transaction as open, and people accept it (the payment is refunded) or refuse it (the sender is told why).</li>
 * <li>A payment that was already sent back, or that we never received, is answered with the reason.</li>
 * </ul>
 * Accepting is answered by the return itself; only a refusal gets an answer message of its own.
 */
public final class RecallService {

    private final Platform platform;

    public RecallService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic("recall"), "recall", m -> handle(m.str("id")));
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
            Rec recall = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int returned = 0;
            int open = 0;
            int refused = 0;
            for (Object r : Ops.list(recall.get("requests"))) {
                Rec request = ((Rec) r).copy();
                request.put("messageId", messageId);
                request.put("channel", message.str("channel"));
                request.set("caseId", recall.str("caseId"));
                request.set("assignerBic", recall.str("assignerBic"));
                request.put("receivedAt", Platform.now());
                // the day by which the scheme wants an answer, counted in business days of the channel's calendar
                if (channel.get("answerWithinBusinessDays") != null) {
                    Object day = io.orvanta.core.expr.Fn.today();
                    for (int n = Ops.num(channel.get("answerWithinBusinessDays")).intValue(); n > 0; n--) {
                        day = io.orvanta.core.expr.Fn.nextBusinessDay(channel.str("calendar"), day);
                    }
                    request.put("dueBy", String.valueOf(day));
                }
                String asked = "recall " + (request.str("reasonCode") == null ? "" : request.str("reasonCode") + " ")
                        + (request.str("reasonText") == null ? "" : request.str("reasonText"));

                Rec txn = find(request);
                if (txn == null) {
                    Incoming.answerRecall(platform, request, null, "NOOR", "No payment with these references was received", "application");
                    refused++;
                    continue;
                }
                String id = txn.str("id");
                String status = txn.str("status");
                if (Status.RETURNED.equals(status)) {
                    Incoming.answerRecall(platform, request, txn, "ARDT", "The payment was already sent back", "application");
                    platform.event(id, "RECALL_REFUSED", asked.trim() + ": already sent back", null, null);
                    refused++;
                } else if (txn.get("return") != null) {
                    // it is already on its way back for a reason of our own; that return answers the recall
                    request.put("status", "ACCEPTED");
                    store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("recall", request, "updatedAt", Platform.now()));
                    platform.event(id, "RECALL_REQUESTED", asked.trim() + ": the payment is already being sent back", null, null);
                    returned++;
                } else if (List.of(Status.HELD, Status.WAITING, Status.WAREHOUSED, Status.REPAIR).contains(status)) {
                    // nothing was credited: the payment simply goes back
                    request.put("status", "ACCEPTED");
                    String problem = Incoming.routeBack(registry, txn, "FOCR", "Following the cancellation request of the sender: " + asked.trim(), "application");
                    if (problem != null) {
                        throw new IllegalStateException(problem);
                    }
                    if (store.updateIf(DocStore.TXN, id, Rec.of("status", status), Rec.of("status", Status.ROUTED, "recall", request, "return", txn.get("return"),
                            "route", txn.get("route"), "routedAt", txn.get("routedAt"), "reasonCode", "FOCR", "reasonText", "Recalled by the sender", "updatedAt", Platform.now()))) {
                        platform.event(id, Status.ROUTED, asked.trim() + ": not credited yet, so the payment is sent back", null, null);
                        platform.bus.publish(Bus.TXN_ROUTED, Rec.of("id", id, "channel", txn.str("route.channel")));
                        returned++;
                    } else {
                        open += keepOpen(id, request, asked);
                    }
                } else {
                    // credited, or on its way to being credited: people decide
                    open += keepOpen(id, request, asked);
                }
            }
            Lifecycle.finish(platform, messageId, Rec.of("msgId", recall.str("msgId"), "returnedCount", returned, "openCount", open, "refusedCount", refused),
                    returned + " sent back, " + open + " waiting for a decision, " + refused + " refused");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }

    private int keepOpen(String id, Rec request, String asked) {
        request.put("status", "OPEN");
        platform.store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("recall", request, "updatedAt", Platform.now()));
        platform.event(id, "RECALL_REQUESTED", asked.trim() + ": waits for a decision", null, null);
        return 1;
    }

    /** The incoming payment a request means: by the message and transaction id the sender gave it, else by the end-to-end id. */
    private Rec find(Rec request) {
        List<Rec> found = new ArrayList<>();
        if (request.str("originalTxId") != null) {
            Rec filter = Rec.of("paymentType", Incoming.PAYMENT_TYPE, "originalTxId", request.str("originalTxId"));
            if (request.str("originalMsgId") != null) {
                filter.put("originalMsgId", request.str("originalMsgId"));
            }
            found = platform.store.find(DocStore.TXN, filter, "id", true, 5);
        }
        if (found.isEmpty() && request.str("originalEndToEndId") != null && request.str("originalMsgId") != null) {
            found = platform.store.find(DocStore.TXN, Rec.of("paymentType", Incoming.PAYMENT_TYPE, "originalMsgId", request.str("originalMsgId"),
                    "endToEndId", request.str("originalEndToEndId")), "id", true, 5);
        }
        // the same ids may have come in twice; the copy that was not sent back is the one meant
        for (Rec txn : found) {
            if (!Status.RETURNED.equals(txn.str("status"))) {
                return txn;
            }
        }
        return found.isEmpty() ? null : found.get(0);
    }
}
