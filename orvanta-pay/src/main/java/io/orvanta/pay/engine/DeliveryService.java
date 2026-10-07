package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the SWIFT network says about a message we sent: ACK, it was taken and will be delivered; NAK, it
 * was refused with an error code and never left. An ACK is recorded on the payment (DELIVERED); a NAK
 * puts the payment in repair with the code, for an operator to correct and send again. A channel with
 * purpose "deliveryNotification" receives them.
 */
public final class DeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryService.class);
    public static final String PURPOSE = "deliveryNotification";

    private final Platform platform;

    public DeliveryService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic(PURPOSE), "delivery", m -> handle(m.str("id")));
    }

    public void handle(String messageId) {
        DocStore store = platform.store;
        if (!Lifecycle.claim(platform, messageId)) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Rec channel = platform.deployments.registry().config(message.str("channel"));
            Rec ack = platform.deployments.registry().require(channel.str("mapping"), io.orvanta.core.flow.Elements.Mapping.class)
                    .apply(Rec.of("src", Messages.parse(message.str("raw")).get(0).tree()));
            String reference = ack.str("reference");
            Rec txn = reference == null ? null : store.get(DocStore.TXN, reference);
            if (txn == null) {
                Lifecycle.finish(platform, messageId, Rec.of("reference", reference, "accepted", ack.get("accepted"), "matched", false),
                        "no payment with reference " + reference + "; the acknowledgement is kept for the record");
                return;
            }
            String id = txn.str("id");
            boolean accepted = Boolean.TRUE.equals(ack.get("accepted"));
            Rec delivery = Rec.of("status", accepted ? "ACKED" : "NAKED", "code", ack.str("code"), "at", Platform.now(), "messageId", messageId, "networkTime", ack.str("at"));
            if (accepted) {
                store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("delivery", delivery, "updatedAt", Platform.now()));
                platform.event(id, "DELIVERED", "the SWIFT network took the message" + (ack.str("at") == null ? "" : " at " + ack.str("at")), null, null);
            } else {
                // never left: the payment is for an operator, who corrects and sends it again
                String reason = "NAK " + (ack.str("code") == null ? "" : ack.str("code")) + ": the SWIFT network refused the message";
                boolean parked = store.updateIf(DocStore.TXN, id, Rec.of("status", Status.SENT),
                        Rec.of("status", Status.REPAIR, "delivery", delivery, "reasonCode", "NAK", "reasonText", reason.trim(), "updatedAt", Platform.now()));
                if (!parked) {
                    store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("delivery", delivery, "updatedAt", Platform.now()));
                }
                platform.event(id, parked ? Status.REPAIR : "NAK", reason.trim(), null, null);
                if (parked) {
                    Lifecycle.reopenReport(platform, txn.str("instructionId"));
                }
            }
            platform.changed("payments");
            Lifecycle.finish(platform, messageId, Rec.of("reference", reference, "accepted", accepted, "code", ack.str("code"), "transactionId", id, "matched", true),
                    (accepted ? "ACK" : "NAK") + " for " + id);
        } catch (RuntimeException e) {
            LOG.error("delivery notification {} failed", messageId, e);
            Lifecycle.fail(platform, messageId, e);
        }
    }
}
