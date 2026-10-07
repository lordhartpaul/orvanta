package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Charges that another bank owes us: a payment received with all charges borne by the payer is credited
 * in full, and our charge becomes a claim on the bank that sent it. A claim starts OPEN when the payment
 * is credited; here it is sent as a request for payment of charges (MT191 with the sample models) on the
 * channel that the inbound channel names, and becomes REQUESTED. People mark it PAID when the money arrives.
 */
final class ChargeClaims {

    private static final Logger LOG = LoggerFactory.getLogger(ChargeClaims.class);

    private ChargeClaims() {
    }

    /** @return number of claims sent */
    static int sweep(Platform platform) {
        DocStore store = platform.store;
        Registry registry = platform.deployments.registry();
        int sent = 0;
        for (Rec txn : store.find(DocStore.TXN, Rec.of("charges.claimStatus", "OPEN"), "id", false, 200)) {
            String id = txn.str("id");
            if (!Status.CREDITED.equals(txn.str("status"))) {
                continue;       // the claim is made once the customer has the money
            }
            Rec inbound = registry.config(String.valueOf(txn.str("channelIn")));
            String name = inbound == null ? null : inbound.str("chargeClaimChannel");
            Rec channel = name == null ? null : registry.config(name);
            if (channel == null || !"outbound".equals(channel.str("direction"))) {
                continue;       // no channel to claim on: the claim stays open and is listed for people
            }
            // claim first, so that two instances cannot both send it
            if (!store.updateIf(DocStore.TXN, id, Rec.of("charges.claimStatus", "OPEN"), Rec.of("charges.claimStatus", "SENDING"))) {
                continue;
            }
            String outboundId = platform.newId("ORVOUT");
            try {
                Rec claim = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "amount", txn.at("charges.claim"),
                        "currency", txn.str("charges.currency"), "from", txn.str("charges.claimFrom"));
                if (channel.get("properties") instanceof Map<?, ?> properties) {
                    claim.putAll(Rec.from(properties));
                }
                Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("claim", claim, "txn", txn));
                String payload = Messages.write(channel.str("format"), mapped);
                platform.checks().requireValidOutbound(channel, payload);
                List<Object> ids = new ArrayList<>();
                ids.add(id);
                store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "chargeClaim", "channel", name, "format", channel.str("format"),
                        "messageType", channel.str("messageType"), "transactionCount", 1, "transactionIds", ids, "totalAmount", txn.at("charges.claim"),
                        "currency", txn.str("charges.currency"), "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
                store.updateIf(DocStore.TXN, id, Rec.of("charges.claimStatus", "SENDING"),
                        Rec.of("charges.claimStatus", "REQUESTED", "charges.claimId", outboundId, "charges.claimedAt", Platform.now()));
                platform.event(id, "CHARGE_CLAIMED", "our charge of " + txn.at("charges.claim") + " " + txn.str("charges.currency") + " was claimed from "
                        + txn.str("charges.claimFrom") + " in " + outboundId, null, null);
                platform.event(outboundId, "CREATED", "claim for the charge on " + id, null, null);
                platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
                sent++;
            } catch (RuntimeException e) {
                LOG.error("the charge claim for {} could not be written", id, e);
                // left for a person: shown on the payment and in the list of claims
                store.updateIf(DocStore.TXN, id, Rec.of("charges.claimStatus", "SENDING"),
                        Rec.of("charges.claimStatus", "FAILED", "charges.claimProblem", String.valueOf(e.getMessage())));
                platform.event(id, "CHARGE_CLAIM_FAILED", String.valueOf(e.getMessage()), null, null);
            }
        }
        return sent;
    }
}
