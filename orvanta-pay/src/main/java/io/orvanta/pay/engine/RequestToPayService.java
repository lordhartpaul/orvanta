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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requests to pay. A creditor's bank asks our customer to pay (pain.013); the request waits for the
 * customer's answer. Accepted, a payment is created from it and processed like any instruction, and the
 * creditor's bank is told ACCP (pain.014); refused, or past its expiry date, it is told RJCT. The
 * customer answers through the Console or the API; both go through a second person.
 */
public final class RequestToPayService {

    private static final Logger LOG = LoggerFactory.getLogger(RequestToPayService.class);
    public static final String PURPOSE = "requestToPay";
    public static final String COLLECTION = "orv_rtp";

    private final Platform platform;

    public RequestToPayService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic(PURPOSE), "rtp", m -> handle(m.str("id")));
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
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            List<Object> ids = new ArrayList<>();
            for (Object r : Ops.list(mapped.get("requests"))) {
                Rec request = ((Rec) r).copy();
                String id = platform.newId("ORVRTP");
                request.put("id", id);
                request.put("messageId", messageId);
                request.put("channel", message.str("channel"));
                request.put("originalMsgId", mapped.str("msgId"));
                request.put("status", "PENDING");
                request.put("receivedAt", Platform.now());
                store.insert(COLLECTION, request);
                platform.event(id, "REQUESTED", request.str("creditor.name") + " asks " + request.str("debtor.name") + " to pay " + request.get("amount") + " " + request.str("currency")
                        + (request.str("expiryDate") == null ? "" : ", until " + request.str("expiryDate")), null, null);
                ids.add(id);
            }
            platform.changed("payments");
            Lifecycle.finish(platform, messageId, Rec.of("msgId", mapped.str("msgId"), "requestCount", ids.size(), "requestIds", ids), ids.size() + " request(s) to pay, waiting for the customer");
        } catch (RuntimeException e) {
            LOG.error("request to pay {} failed", messageId, e);
            Lifecycle.fail(platform, messageId, e);
        }
    }

    /** The customer's answer. Accepted: a payment is created and the creditor's bank told ACCP; refused: told RJCT with the reason. */
    public Rec answer(String requestId, boolean accept, String reasonCode, String reasonText, String actor) {
        DocStore store = platform.store;
        Rec request = store.get(COLLECTION, requestId);
        if (request == null || !"PENDING".equals(request.str("status"))) {
            throw new IllegalStateException("request " + requestId + " is not waiting for an answer");
        }
        String txnId = null;
        if (accept) {
            txnId = platform.newId("ORVTXN");
            Rec txn = Rec.of("id", txnId, "status", Status.CREATED, "instructionId", request.str("messageId"), "channelIn", request.str("channel"),
                    "requestToPayId", requestId, "endToEndId", request.str("endToEndId"), "amount", request.get("amount"), "currency", request.str("currency"),
                    "requestedDate", request.str("requestedExecutionDate"), "debtor", request.get("debtor"), "creditor", request.get("creditor"),
                    "remittance", request.str("remittance"), "chargeBearer", "SLEV", "createdAt", Platform.now(), "updatedAt", Platform.now());
            store.insert(DocStore.TXN, txn);
            platform.event(txnId, Status.CREATED, "created from request to pay " + requestId + ", accepted by the customer", null, actor);
            platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txnId));
        }
        String outboundId = report(request, accept ? "ACCP" : "RJCT", reasonCode, reasonText, actor);
        store.updateIf(COLLECTION, requestId, Rec.of("status", "PENDING"), Rec.of("status", accept ? "ACCEPTED" : "REFUSED", "transactionId", txnId,
                "answer", Rec.of("outboundId", outboundId, "at", Platform.now(), "by", actor, "reasonCode", reasonCode, "reasonText", reasonText), "updatedAt", Platform.now()));
        platform.event(requestId, accept ? "ACCEPTED" : "REFUSED", (accept ? "accepted; payment " + txnId : "refused: " + reasonCode + " " + (reasonText == null ? "" : reasonText))
                + "; the creditor's bank is told in " + outboundId, null, actor);
        platform.changed("payments");
        return Rec.of("requestId", requestId, "status", accept ? "ACCEPTED" : "REFUSED", "transactionId", txnId, "outboundId", outboundId);
    }

    /** Requests whose expiry date has passed without an answer are refused for the customer, and the creditor's bank told. */
    public int expire() {
        int n = 0;
        String today = io.orvanta.core.expr.Fn.today().toString();
        for (Rec request : platform.store.find(COLLECTION, Rec.of("status", "PENDING"), "id", false, 1000)) {
            String expiry = request.str("expiryDate");
            if (expiry != null && expiry.compareTo(today) < 0) {
                try {
                    answer(request.str("id"), false, "AB07", "The request expired on " + expiry + " without an answer", "system");
                    n++;
                } catch (RuntimeException e) {
                    LOG.error("expiring request to pay {} failed", request.str("id"), e);
                }
            }
        }
        return n;
    }

    private String report(Rec request, String status, String reasonCode, String reasonText, String actor) {
        Registry registry = platform.deployments.registry();
        Rec inbound = registry.config(String.valueOf(request.str("channel")));
        String name = inbound == null ? null : inbound.str("answerChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null) {
            throw new IllegalStateException("the request has to be answered, but channel " + request.str("channel") + " names no answer channel");
        }
        String outboundId = platform.newId("ORVOUT");
        Rec answer = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "status", status, "reasonCode", reasonCode, "reasonText", reasonText);
        if (channel.get("properties") instanceof java.util.Map<?, ?> properties) {
            answer.putAll(Rec.from(properties));
        }
        Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("answer", answer, "request", request));
        String payload = Messages.write(channel.str("format"), mapped);
        platform.checks().requireValidOutbound(channel, payload);
        platform.store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "requestToPayAnswer", "channel", name, "format", channel.str("format"),
                "messageType", channel.str("messageType"), "transactionCount", 0, "requestId", request.str("id"), "status", Status.CREATED,
                "createdAt", Platform.now(), "payload", payload));
        platform.event(outboundId, "CREATED", status + " for request to pay " + request.str("id"), null, actor);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return outboundId;
    }
}
