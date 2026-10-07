package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Registry;
import io.orvanta.pay.kernel.Platform;

/**
 * Payments that arrive for our customers. An incoming payment is either credited to the customer
 * or sent back to where it came from; it is never just dropped. Sending back, whether because a
 * check failed or because a credited payment is refunded later, means routing the transaction to
 * the return channel that its inbound channel names. From there it is bulked and sent like any
 * outgoing payment, and ends as RETURNED.
 */
public final class Incoming {

    /** Value of txn.paymentType for a payment that came in for one of our customers. */
    public static final String PAYMENT_TYPE = "IN";
    /** Value of txn.paymentType for a direct debit collection that came in against the account of one of our customers. */
    public static final String DEBIT_TYPE = "DD_IN";

    /** Statuses in which the money of an incoming payment or collection has been booked on the customer's account. */
    public static final java.util.List<String> BOOKED = java.util.List.of(Status.CREDITED, Status.DEBITED);

    private Incoming() {
    }

    public static boolean is(Rec txn) {
        return PAYMENT_TYPE.equals(txn.str("paymentType")) || DEBIT_TYPE.equals(txn.str("paymentType"));
    }

    /**
     * Sets the reason and the route for sending the payment back. The caller stores the transaction
     * with status ROUTED.
     *
     * @return null when done, otherwise why it cannot be sent back
     */
    public static String routeBack(Registry registry, Rec txn, String reasonCode, String reasonText, String requestedBy) {
        Rec inbound = registry.config(String.valueOf(txn.str("channelIn")));
        // a payment that was already booked may go back another way than one that is turned down on arrival:
        // an instant payment is refused with a status report, but refunded with a return
        boolean booked = txn.get("creditedAt") != null || txn.get("debitedAt") != null;
        String name = inbound == null ? null : booked && inbound.str("refundChannel") != null ? inbound.str("refundChannel") : inbound.str("returnChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null || !"outbound".equals(channel.str("direction"))) {
            return "the payment has to be sent back, but channel " + txn.str("channelIn") + " names no return channel";
        }
        txn.put("return", Rec.of("reasonCode", reasonCode == null ? "NARR" : reasonCode, "reasonText", reasonText,
                "requestedBy", requestedBy, "requestedAt", Platform.now()));
        txn.put("route", Rec.of("channel", name, "scheme", channel.str("properties.scheme") == null ? "RETURN" : channel.str("properties.scheme"),
                "methodOfPayment", "RETURN"));
        txn.put("routedAt", Platform.now());
        return null;
    }

    /**
     * Sends a credited payment back: the credit to the account is reversed first, then the payment is
     * routed to the return channel. Called when a second person has approved the refund.
     *
     * @throws IllegalStateException when the payment is not credited any more, or the credit cannot be reversed
     */
    public static void refund(Platform platform, String id, String reasonCode, String reasonText, String actor) {
        io.orvanta.pay.kernel.DocStore store = platform.store;
        Rec txn = store.get(io.orvanta.pay.kernel.DocStore.TXN, id);
        if (txn == null || !BOOKED.contains(txn.str("status"))) {
            throw new IllegalStateException("the payment is no longer booked on the account, so it cannot be refunded");
        }
        String booked = txn.str("status");
        // the money is taken back from the customer's account first; only then is the payment sent back
        if ("POSTED".equals(txn.str("posting.status")) && !new PostingService(platform).reverse(id, true)) {
            throw new IllegalStateException("the booking on the account could not be reversed, so nothing was sent back");
        }
        txn = store.get(io.orvanta.pay.kernel.DocStore.TXN, id);
        String problem = routeBack(platform.deployments.registry(), txn, reasonCode, reasonText, actor);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        Rec changes = Rec.of("status", Status.ROUTED, "return", txn.get("return"), "route", txn.get("route"), "routedAt", txn.get("routedAt"),
                "reasonCode", reasonCode, "reasonText", reasonText == null ? "Refunded" : reasonText, "updatedAt", Platform.now());
        if (txn.get("recall") instanceof Rec recall && "OPEN".equals(recall.str("status"))) {
            // a recall that was waiting is answered by this return
            changes.put("recall.status", "ACCEPTED");
            changes.put("recall.decidedBy", actor);
        }
        if (!store.updateIf(io.orvanta.pay.kernel.DocStore.TXN, id, Rec.of("status", booked), changes)) {
            throw new IllegalStateException("the payment changed while the refund was being prepared; request it again");
        }
        platform.event(id, Status.ROUTED, "refund: " + reasonCode + " " + (reasonText == null ? "" : reasonText), null, actor);
        platform.bus.publish(io.orvanta.pay.kernel.Bus.TXN_ROUTED, Rec.of("id", id, "channel", txn.str("route.channel")));
    }

    /**
     * Tells the sender's bank that a recall is refused, and why, on the answer channel that the recall
     * channel names. The message is built by that channel's mapping from {answer, txn}.
     *
     * @param request the recall as it was read, with the channel it came in on
     * @param txn     the payment, or null when none was found
     * @return the id of the answer
     */
    public static String answerRecall(Platform platform, Rec request, Rec txn, String reasonCode, String reasonText, String actor) {
        Registry registry = platform.deployments.registry();
        Rec inbound = registry.config(String.valueOf(request.str("channel")));
        String name = inbound == null ? null : inbound.str("answerChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null || !"outbound".equals(channel.str("direction"))) {
            throw new IllegalStateException("the recall has to be answered, but channel " + request.str("channel") + " names no answer channel");
        }
        String outboundId = platform.newId("ORVOUT");
        Rec answer = request.copy();
        answer.put("id", outboundId);
        answer.put("createdAt", Platform.now().substring(0, 19) + "Z");
        answer.put("status", "RJCR");
        answer.put("reasonCode", reasonCode);
        answer.set("reasonText", reasonText);
        answer.set("recallReasonCode", request.str("reasonCode"));
        if (channel.get("properties") instanceof java.util.Map<?, ?> properties) {
            answer.putAll(Rec.from(properties));
        }
        Rec scope = Rec.of("answer", answer);
        scope.put("txn", txn == null ? new Rec() : txn);
        Rec mapped = registry.require(channel.str("mapping"), io.orvanta.core.flow.Elements.Mapping.class).apply(scope);
        String payload = io.orvanta.core.format.Messages.write(channel.str("format"), mapped);
        platform.checks().requireValidOutbound(channel, payload);
        java.util.List<Object> ids = new java.util.ArrayList<>();
        if (txn != null) {
            ids.add(txn.str("id"));
        }
        platform.store.insert(io.orvanta.pay.kernel.DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "recallAnswer", "channel", name,
                "format", channel.str("format"), "messageType", channel.str("messageType"), "transactionCount", ids.size(), "transactionIds", ids,
                "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
        platform.event(outboundId, "CREATED", "recall " + request.str("cancellationId") + " refused: " + reasonCode + " " + (reasonText == null ? "" : reasonText), null, actor);
        platform.bus.publish(io.orvanta.pay.kernel.Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return outboundId;
    }

    /**
     * Tells the sender at once that the payment was booked, when the inbound channel names a channel for
     * that (an instant payment is confirmed, not just kept). The message is built by that channel's
     * mapping from the same {bulk, txns} a payment file is built from.
     */
    static void confirm(Platform platform, Registry registry, Rec txn) {
        Rec inbound = registry.config(String.valueOf(txn.str("channelIn")));
        String name = inbound == null ? null : inbound.str("confirmationChannel");
        if (name == null) {
            return;
        }
        String id = txn.str("id");
        String outboundId = statusReport(platform, registry, name, txn, "confirmation");
        if (outboundId == null) {
            return;
        }
        long millis = java.time.Duration.between(java.time.Instant.parse(txn.str("createdAt")), java.time.Instant.now()).toMillis();
        platform.store.updateIf(io.orvanta.pay.kernel.DocStore.TXN, id, new Rec(),
                Rec.of("confirmation", Rec.of("outboundId", outboundId, "at", Platform.now(), "millisAfterReceipt", millis)));
        platform.event(id, "CONFIRMED", "the sender was told in " + outboundId + ", " + millis + " ms after the payment arrived", null, null);
    }

    /**
     * A status report (pacs.002) about one payment we received, built by the channel's mapping and handed to
     * dispatch: the confirmation of an instant payment, or the answer to a status enquiry.
     * @param kind what the outbound file is called: confirmation or statusAnswer
     * @return the id of the outbound file, or null when it could not be built (an event on the payment says why)
     */
    static String statusReport(Platform platform, Registry registry, String channelName, Rec txn, String kind) {
        Rec channel = channelName == null ? null : registry.config(channelName);
        if (channel == null) {
            return null;
        }
        String id = txn.str("id");
        try {
            String outboundId = platform.newId("ORVOUT");
            Rec bulk = Rec.of("id", outboundId, "msgId", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "count", 1,
                    "total", txn.get("amount"), "currency", txn.str("currency"), "channel", channelName);
            if (channel.get("properties") instanceof java.util.Map<?, ?> properties) {
                bulk.putAll(Rec.from(properties));
            }
            java.util.List<Object> txns = new java.util.ArrayList<>();
            txns.add(txn);
            Rec mapped = registry.require(channel.str("mapping"), io.orvanta.core.flow.Elements.Mapping.class).apply(Rec.of("bulk", bulk, "txns", txns));
            String payload = io.orvanta.core.format.Messages.write(channel.str("format"), mapped);
            platform.checks().requireValidOutbound(channel, payload);
            java.util.List<Object> ids = new java.util.ArrayList<>();
            if (!"NONE".equals(id)) {
                ids.add(id);
            }
            platform.store.insert(io.orvanta.pay.kernel.DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", kind, "channel", channelName,
                    "format", channel.str("format"), "messageType", channel.str("messageType"), "transactionCount", ids.size(), "reportedIds", ids,
                    "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
            platform.bus.publish(io.orvanta.pay.kernel.Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
            return outboundId;
        } catch (RuntimeException e) {
            // the payment is booked all the same; the missing report is visible on it
            if (!"NONE".equals(id)) {
                platform.event(id, kind.toUpperCase(java.util.Locale.ROOT) + "_FAILED", String.valueOf(e.getMessage()), null, null);
            }
            return null;
        }
    }
}
