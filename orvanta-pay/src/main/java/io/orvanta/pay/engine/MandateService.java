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
 * Mandate messages from the creditor's side: a new mandate (pain.009), an amendment (pain.010), a
 * cancellation (pain.011). Each is applied to the mandate register ({@code data.Mandates}) and answered
 * with an acceptance report (pain.012): accepted, or refused with the reason when the mandate is unknown,
 * the debtor has blocked the creditor, or the message does not say which mandate it means.
 */
public final class MandateService {

    private static final Logger LOG = LoggerFactory.getLogger(MandateService.class);
    public static final String PURPOSE = "mandate";

    private final Platform platform;

    public MandateService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic(PURPOSE), "mandate", m -> handle(m.str("id")));
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
            String dataset = channel.str("mandates") == null ? "data.Mandates" : channel.str("mandates");
            String blocks = channel.str("debitBlocks") == null ? "data.DebitBlocks" : channel.str("debitBlocks");
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int accepted = 0;
            int refused = 0;
            List<Object> results = new ArrayList<>();
            for (Object m : Ops.list(mapped.get("mandates"))) {
                Rec wanted = (Rec) m;
                String kind = mapped.str("kind");
                String mandateId = wanted.str("mandateId");
                String refusal = null;
                Rec existing = mandateId == null ? null : first(platform.data.find(dataset, Rec.of("mandateId", mandateId), null, false, 1));
                if (mandateId == null) {
                    refusal = "the message does not say which mandate it means";
                } else if ("INITIATE".equals(kind)) {
                    if (existing != null && !"CANCELLED".equals(existing.str("status"))) {
                        refusal = "a mandate with this reference exists already";
                    } else if (blocked(blocks, wanted)) {
                        refusal = "the debtor has blocked collections by this creditor";
                    }
                } else if (existing == null) {
                    refusal = "no mandate with this reference is registered";
                } else if ("AMEND".equals(kind) && blocked(blocks, wanted)) {
                    refusal = "the debtor has blocked collections by this creditor";
                }
                if (refusal == null) {
                    Rec row = existing == null ? new Rec() : existing.copy();
                    if (!"CANCEL".equals(kind)) {
                        for (String field : List.of("mandateId", "creditorId", "creditorName", "creditorAccount", "creditorAgentBic", "debtorName", "debtorAccount",
                                "debtorAgentBic", "type", "signedOn", "frequency")) {
                            if (wanted.get(field) != null) {
                                row.put(field, wanted.get(field));
                            }
                        }
                    }
                    row.put("status", "CANCEL".equals(kind) ? "CANCELLED" : "AMEND".equals(kind) ? "AMENDED" : "ACTIVE");
                    row.put("collections", existing == null ? 0 : existing.get("collections"));
                    row.put("source", "MESSAGE");
                    row.put("lastMessageId", messageId);
                    row.put("lastMessageKind", kind);
                    row.set("reason", wanted.str("reason"));
                    row.put("registeredBy", mapped.str("senderBic") == null ? "message" : mapped.str("senderBic"));
                    row.put("registeredAt", Platform.now());
                    platform.data.save(dataset, row, false);
                    accepted++;
                    platform.event(mandateId, "INITIATE".equals(kind) ? "MANDATE_REGISTERED" : "AMEND".equals(kind) ? "MANDATE_AMENDED" : "MANDATE_CANCELLED",
                            kind.toLowerCase(java.util.Locale.ROOT) + " by " + mapped.str("senderBic") + " in " + messageId, null, null);
                } else {
                    refused++;
                    platform.event(mandateId == null ? messageId : mandateId, "MANDATE_REFUSED", kind + ": " + refusal, null, null);
                }
                String outboundId = report(channel, mapped, wanted, refusal);
                results.add(Rec.of("mandateId", mandateId, "accepted", refusal == null, "reason", refusal, "outboundId", outboundId));
            }
            platform.changed("payments");
            Lifecycle.finish(platform, messageId, Rec.of("msgId", mapped.str("msgId"), "kind", mapped.str("kind"), "acceptedCount", accepted, "refusedCount", refused, "results", results),
                    mapped.str("kind") + ": " + accepted + " accepted, " + refused + " refused");
        } catch (RuntimeException e) {
            LOG.error("mandate message {} failed", messageId, e);
            Lifecycle.fail(platform, messageId, e);
        }
    }

    private boolean blocked(String blocks, Rec wanted) {
        if (wanted.str("debtorAccount") == null || wanted.str("creditorId") == null) {
            return false;
        }
        try {
            Rec block = first(platform.data.find(blocks, Rec.of("id", wanted.str("debtorAccount") + ":" + wanted.str("creditorId")), null, false, 1));
            return block != null && block.get("maxAmount") == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Rec first(List<Rec> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String report(Rec inbound, Rec mapped, Rec wanted, String refusal) {
        Registry registry = platform.deployments.registry();
        String name = inbound.str("answerChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null) {
            throw new IllegalStateException("the mandate message has to be answered, but channel " + inbound.str("name") + " names no answer channel");
        }
        String outboundId = platform.newId("ORVOUT");
        Rec answer = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "accepted", refusal == null, "reason", refusal,
                "originalMsgId", mapped.str("msgId"), "originalMessageType", mapped.str("messageType"), "kind", mapped.str("kind"));
        if (channel.get("properties") instanceof java.util.Map<?, ?> properties) {
            answer.putAll(Rec.from(properties));
        }
        Rec result = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("answer", answer, "mandate", wanted));
        String payload = Messages.write(channel.str("format"), result);
        platform.checks().requireValidOutbound(channel, payload);
        platform.store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "mandateAcceptance", "channel", name, "format", channel.str("format"),
                "messageType", channel.str("messageType"), "transactionCount", 0, "mandateId", wanted.str("mandateId"), "status", Status.CREATED,
                "createdAt", Platform.now(), "payload", payload));
        platform.event(outboundId, "CREATED", (refusal == null ? "accepted " : "refused ") + mapped.str("kind") + " of mandate " + wanted.str("mandateId"), null, null);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return outboundId;
    }
}
