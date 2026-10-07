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
import java.util.Map;

/**
 * Cancellation of a payment, requested by the customer (camt.056 on a cancellation channel) or by
 * an operator through the console.
 * <ul>
 * <li>Not yet sent: the transaction is cancelled here and now.</li>
 * <li>Already sent: only the receiving side can stop it. The request is forwarded on the
 *     cancellation channel of the outbound channel, and the transaction stays as it is until the
 *     resolution (camt.029) says cancelled or refused.</li>
 * </ul>
 */
public final class CancellationService {

    public static final String CANCELLED = "CANCELLED";
    public static final String FORWARDED = "FORWARDED";
    public static final String REFUSED = "REFUSED";

    private final Platform platform;

    public CancellationService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.CANCELLATION_RECEIVED, "cancel", m -> handleRequest(m.str("id")));
        platform.bus.subscribe(Bus.RESOLUTION_RECEIVED, "resolve", m -> handleResolution(m.str("id")));
    }

    /** @return {transactionId, outcome: CANCELLED | FORWARDED | REFUSED, detail} */
    public Rec requestCancel(String txnId, String reasonCode, String reasonText, String actor, String sourceId) {
        DocStore store = platform.store;
        Rec txn = txnId == null ? null : store.get(DocStore.TXN, txnId);
        if (txn == null) {
            return outcome(txnId, REFUSED, "no such transaction");
        }
        String code = reasonCode == null ? "CUST" : reasonCode;
        String text = reasonText == null ? "Cancelled at the request of the customer" : reasonText;
        Rec cancellation = Rec.of("reasonCode", code, "reasonText", text, "requestedBy", actor,
                "requestedAt", Platform.now(), "source", sourceId);

        for (String from : List.of(Status.STAGED, Status.CREATED, Status.HELD, Status.WAITING, Status.WAREHOUSED, Status.ROUTED, Status.REPAIR)) {
            Rec accepted = cancellation.copy();
            accepted.put("status", "ACCEPTED");
            if (store.updateIf(DocStore.TXN, txnId, Rec.of("status", from), Rec.of("status", Status.CANCELLED,
                    "reasonCode", code, "reasonText", text, "cancellation", accepted, "updatedAt", Platform.now()))) {
                platform.event(txnId, Status.CANCELLED, "cancelled before sending: " + code + " " + text, null, actor);
                Lifecycle.reopenReport(platform, txn.str("instructionId"));
                Lifecycle.unwound(platform, txnId);
                return outcome(txnId, CANCELLED, "cancelled before it was sent");
            }
        }

        txn = store.get(DocStore.TXN, txnId);
        String status = txn.str("status");
        if (!List.of(Status.BULKED, Status.SENT, Status.ACCEPTED).contains(status)) {
            return outcome(txnId, REFUSED, "transaction is " + status + (Status.PROCESSING.equals(status) ? "; try again in a moment" : ""));
        }
        if ("REQUESTED".equals(txn.str("cancellation.status"))) {
            return outcome(txnId, REFUSED, "a cancellation request is already waiting for an answer");
        }
        Registry registry = platform.deployments.registry();
        Rec route = registry.config(String.valueOf(txn.at("route.channel")));
        Rec channel = route == null || route.str("cancellationChannel") == null ? null : registry.config(route.str("cancellationChannel"));
        if (channel == null) {
            return outcome(txnId, REFUSED, "the payment was already sent on " + txn.at("route.channel")
                    + ", which has no cancellation channel");
        }
        String outboundId = platform.newId("ORVOUT");
        Rec request = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "reasonCode", code, "reasonText", text,
                "originalMsgId", txn.str("outboundId"), "originalMessageType", route.str("messageType"));
        if (channel.get("properties") instanceof Map<?, ?> properties) {
            request.putAll(Rec.from(properties));
        }
        String payload;
        try {
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("request", request, "txn", txn));
            payload = Messages.write(channel.str("format"), mapped);
            platform.checks().requireValidOutbound(channel, payload);
        } catch (RuntimeException e) {
            return outcome(txnId, REFUSED, "the cancellation request could not be built: " + e.getMessage());
        }
        cancellation.put("status", "REQUESTED");
        cancellation.put("outboundId", outboundId);
        if (!store.updateIf(DocStore.TXN, txnId, Rec.of("status", status), Rec.of("cancellation", cancellation, "updatedAt", Platform.now()))) {
            return outcome(txnId, REFUSED, "the transaction changed while the request was being prepared; try again");
        }
        List<Object> ids = new ArrayList<>();
        ids.add(txnId);
        store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "cancellation", "channel", channel.str("name"),
                "format", channel.str("format"), "messageType", channel.str("messageType"), "transactionCount", 1,
                "totalAmount", txn.get("amount"), "currency", txn.str("currency"), "transactionIds", ids,
                "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
        platform.event(txnId, "CANCELLATION_REQUESTED", "request " + outboundId + " sent to the receiving side: " + code + " " + text, null, actor);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return outcome(txnId, FORWARDED, "already sent; cancellation request " + outboundId + " forwarded");
    }

    private static Rec outcome(String txnId, String outcome, String detail) {
        return Rec.of("transactionId", txnId, "outcome", outcome, "detail", detail);
    }

    /** A cancellation request from the customer: each entry names the original message and the end-to-end id. */
    public void handleRequest(String messageId) {
        DocStore store = platform.store;
        if (!Lifecycle.claim(platform, messageId)) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec request = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            List<Object> results = new ArrayList<>();
            int cancelled = 0;
            int forwarded = 0;
            int refused = 0;
            for (Object e : Ops.list(request.get("entries"))) {
                Rec entry = (Rec) e;
                List<Rec> matches = new ArrayList<>();
                for (Rec instruction : store.find(DocStore.MESSAGE,
                        Rec.of("purpose", "instruction", "msgId", String.valueOf(entry.str("originalMsgId"))), null, false, 10)) {
                    // a request that names no transaction (an MT192 for a whole MT101) means every transaction of the message
                    Rec filter = Rec.of("instructionId", instruction.str("id"));
                    if (entry.str("endToEndId") != null) {
                        filter.put("endToEndId", entry.str("endToEndId"));
                    }
                    matches.addAll(store.find(DocStore.TXN, filter, null, false, 1000));
                }
                if (matches.isEmpty()) {
                    results.add(Rec.of("endToEndId", entry.str("endToEndId"), "originalMsgId", entry.str("originalMsgId"), "outcome", REFUSED, "reasonCode", "NOOR",
                            "detail", "no transaction " + (entry.str("endToEndId") == null ? "" : entry.str("endToEndId") + " ") + "in message " + entry.str("originalMsgId")));
                    refused++;
                }
                for (Rec txn : matches) {
                    Rec result = requestCancel(txn.str("id"), entry.str("reasonCode"), entry.str("reasonText"), message.str("receivedBy"), messageId);
                    result.set("endToEndId", txn.str("endToEndId"));
                    result.set("originalMsgId", entry.str("originalMsgId"));
                    results.add(result);
                    switch (result.str("outcome")) {
                        case CANCELLED -> cancelled++;
                        case FORWARDED -> forwarded++;
                        default -> refused++;
                    }
                }
            }
            // the customer is told what became of the request: cancelled, refused and why, or still with the receiving side
            Rec finished = Rec.of("msgId", request.str("msgId"), "senderBic", request.str("senderBic"), "cancelledCount", cancelled,
                    "forwardedCount", forwarded, "refusedCount", refused, "results", results);
            try {
                finished.set("answerId", answer(channel, request, messageId, results));
            } catch (RuntimeException e) {
                finished.put("answerProblem", String.valueOf(e.getMessage()));
                platform.event(messageId, "ANSWER_FAILED", "the customer could not be told: " + e.getMessage(), null, null);
            }
            Lifecycle.finish(platform, messageId, finished,
                    cancelled + " cancelled, " + forwarded + " forwarded, " + refused + " refused");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }

    /** The receiving side's answer to a forwarded request: CNCL cancelled, RJCR refused, anything else still pending. */
    public void handleResolution(String messageId) {
        DocStore store = platform.store;
        if (!Lifecycle.claim(platform, messageId)) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec resolution = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int cancelled = 0;
            int refused = 0;
            List<Object> unmatched = new ArrayList<>();
            for (Object e : Ops.list(resolution.get("entries"))) {
                Rec entry = (Rec) e;
                String txnId = entry.str("transactionId");
                Rec txn = txnId == null ? null : store.get(DocStore.TXN, txnId);
                if (txn == null || !"REQUESTED".equals(txn.str("cancellation.status"))) {
                    unmatched.add(Rec.of("transactionId", txnId, "status", entry.str("status"),
                            "problem", txn == null ? "no such transaction" : "no cancellation request is waiting for an answer"));
                    continue;
                }
                String answer = String.valueOf(entry.str("status"));
                if (Ops.in(answer, channel.get("cancelled"))) {
                    boolean done = false;
                    for (String from : List.of(Status.SENT, Status.ACCEPTED, Status.BULKED)) {
                        done = done || store.updateIf(DocStore.TXN, txnId, Rec.of("status", from, "cancellation.status", "REQUESTED"),
                                Rec.of("status", Status.CANCELLED, "cancellation.status", "ACCEPTED", "cancellation.resolutionId", messageId,
                                        "cancellation.resolvedAt", Platform.now(), "reasonCode", String.valueOf(txn.str("cancellation.reasonCode")),
                                        "reasonText", "Cancelled by the receiving side at our request", "updatedAt", Platform.now()));
                    }
                    if (done) {
                        cancelled++;
                        platform.event(txnId, Status.CANCELLED, "cancellation confirmed in " + messageId, null, null);
                        answerLater(txn, CANCELLED, null, null);
                        Lifecycle.reopenReport(platform, txn.str("instructionId"));
                        Lifecycle.unwound(platform, txnId);
                    }
                } else if (Ops.in(answer, channel.get("refused"))) {
                    if (store.updateIf(DocStore.TXN, txnId, Rec.of("cancellation.status", "REQUESTED"),
                            Rec.of("cancellation.status", "REJECTED", "cancellation.resolutionId", messageId,
                                    "cancellation.resolvedAt", Platform.now(),
                                    "cancellation.resolutionCode", String.valueOf(entry.str("reasonCode")), "updatedAt", Platform.now()))) {
                        refused++;
                        platform.event(txnId, "CANCELLATION_REFUSED", "the receiving side refused the cancellation in " + messageId
                                + (entry.str("reasonCode") == null ? "" : ": " + entry.str("reasonCode")), null, null);
                        answerLater(txn, REFUSED, entry.str("reasonCode") == null ? "NARR" : entry.str("reasonCode"),
                                entry.str("reasonText") == null ? "Refused by the receiving side" : entry.str("reasonText"));
                    }
                }
            }
            Lifecycle.finish(platform, messageId, Rec.of("msgId", resolution.str("msgId"), "cancelledCount", cancelled,
                    "refusedCount", refused, "unmatched", unmatched), cancelled + " cancelled, " + refused + " refused, " + unmatched.size() + " unmatched");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }

    /** ISO 20022 cancellation status of an outcome: cancelled, rejected, or pending with the receiving side. */
    private static String statusOf(String outcome) {
        return CANCELLED.equals(outcome) ? "CNCL" : FORWARDED.equals(outcome) ? "PDCR" : "RJCR";
    }

    /**
     * Builds the answer to a customer's cancellation request on the channel that the request channel names
     * ('answerChannel'), from {answer, entries}. Without such a channel nothing is sent.
     *
     * @return the id of the answer, or null when the channel names none
     */
    private String answer(Rec inbound, Rec request, String requestMessageId, List<Object> results) {
        Registry registry = platform.deployments.registry();
        String name = inbound == null ? null : inbound.str("answerChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null || results.isEmpty()) {
            return null;
        }
        List<Object> entries = new ArrayList<>();
        java.util.Set<String> statuses = new java.util.TreeSet<>();
        List<Object> ids = new ArrayList<>();
        for (Object r : results) {
            Rec result = (Rec) r;
            String status = statusOf(result.str("outcome"));
            statuses.add(status);
            Rec entry = Rec.of("status", status, "endToEndId", result.str("endToEndId"), "originalMsgId", result.str("originalMsgId"),
                    "transactionId", result.str("transactionId"));
            if (status.equals("RJCR")) {
                entry.put("reasonCode", result.str("reasonCode") == null ? "NARR" : result.str("reasonCode"));
                entry.set("reasonText", result.str("detail"));
            }
            entries.add(entry);
            if (result.str("transactionId") != null) {
                ids.add(result.str("transactionId"));
            }
        }
        // one status for the whole request: pending while anything is, partly done when cancelled and refused are mixed
        String overall = statuses.contains("PDCR") ? "PDCR" : statuses.size() == 1 ? statuses.iterator().next() : "PACR";
        String outboundId = platform.newId("ORVOUT");
        Rec answer = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "caseId", request.str("msgId"),
                "status", overall, "count", entries.size(), "to", request.str("senderBic"), "requestMessageId", requestMessageId);
        if (channel.get("properties") instanceof Map<?, ?> properties) {
            answer.putAll(Rec.from(properties));
        }
        Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("answer", answer, "entries", entries));
        String payload = Messages.write(channel.str("format"), mapped);
        platform.checks().requireValidOutbound(channel, payload);
        platform.store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "cancellationAnswer", "channel", name, "format", channel.str("format"),
                "messageType", channel.str("messageType"), "instructionId", requestMessageId, "transactionCount", ids.size(), "transactionIds", ids,
                "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
        platform.event(outboundId, "CREATED", "answer " + overall + " to cancellation request " + request.str("msgId"), null, null);
        platform.event(requestMessageId, "ANSWERED", "the customer was told " + overall + " in " + outboundId, null, null);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return outboundId;
    }

    /** The receiving side has answered a request that a customer made: the customer is told the final word. */
    private void answerLater(Rec txn, String outcome, String reasonCode, String reasonText) {
        String source = txn.str("cancellation.source");
        Rec requestMessage = source == null ? null : platform.store.get(DocStore.MESSAGE, source);
        if (requestMessage == null || !"cancellation".equals(requestMessage.str("purpose"))) {
            return;         // asked for by bank staff, not by a customer's message
        }
        try {
            Rec instruction = platform.store.get(DocStore.MESSAGE, String.valueOf(txn.str("instructionId")));
            Rec result = Rec.of("transactionId", txn.str("id"), "outcome", outcome, "endToEndId", txn.str("endToEndId"),
                    "originalMsgId", instruction == null ? null : instruction.str("msgId"), "reasonCode", reasonCode, "detail", reasonText);
            List<Object> results = new ArrayList<>();
            results.add(result);
            // the request as it was read, for the case id and for whom to answer
            Rec inbound = platform.deployments.registry().config(String.valueOf(requestMessage.str("channel")));
            Rec request = Rec.of("msgId", requestMessage.str("msgId"), "senderBic", requestMessage.str("senderBic"));
            String answerId = answer(inbound, request, source, results);
            if (answerId != null) {
                platform.event(txn.str("id"), "CANCELLATION_ANSWERED", "the customer was told " + statusOf(outcome) + " in " + answerId, null, null);
            }
        } catch (RuntimeException e) {
            platform.event(txn.str("id"), "ANSWER_FAILED", "the customer could not be told: " + e.getMessage(), null, null);
        }
    }
}
