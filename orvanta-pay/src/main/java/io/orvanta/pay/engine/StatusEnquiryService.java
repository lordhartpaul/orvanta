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
 * Payment status enquiries (pacs.028). Out: when a payment we sent has had no answer in the time its rail
 * allows, the rail is asked what became of it, once; the answer, when it comes, is the usual pacs.002.
 * In: another bank asks about a payment it sent us; it is answered with a pacs.002 that says where the
 * payment stands now, or that no such payment arrived.
 */
public final class StatusEnquiryService {

    private static final Logger LOG = LoggerFactory.getLogger(StatusEnquiryService.class);
    public static final String PURPOSE = "statusEnquiry";

    private final Platform platform;

    public StatusEnquiryService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic(PURPOSE), "enquiry", m -> handle(m.str("id")));
    }

    /** Asks the rail about a sent payment, on the 'enquiryChannel' of the channel it went out on. @return the id of the enquiry, or null when none was sent */
    public static String enquire(Platform platform, Rec txn, Rec outboundChannel) {
        Registry registry = platform.deployments.registry();
        String name = outboundChannel.str("enquiryChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null || txn.get("enquiry") != null) {
            return null;
        }
        String id = txn.str("id");
        try {
            String enquiryId = platform.newId("ORVOUT");
            Rec enquiry = Rec.of("id", enquiryId, "createdAt", Platform.now().substring(0, 19) + "Z", "originalMsgId", txn.str("outboundId"),
                    "originalMessageType", outboundChannel.str("messageType"));
            if (channel.get("properties") instanceof java.util.Map<?, ?> properties) {
                enquiry.putAll(Rec.from(properties));
            }
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("enquiry", enquiry, "txn", txn));
            String payload = Messages.write(channel.str("format"), mapped);
            platform.checks().requireValidOutbound(channel, payload);
            List<Object> ids = new ArrayList<>();
            ids.add(id);
            // the ids are named for looking the payment up, not under 'transactionIds': an enquiry changes nothing on it when sent
            platform.store.insert(DocStore.OUTBOUND, Rec.of("id", enquiryId, "kind", "statusEnquiry", "channel", name, "format", channel.str("format"),
                    "messageType", channel.str("messageType"), "transactionCount", 1, "reportedIds", ids, "status", Status.CREATED,
                    "createdAt", Platform.now(), "payload", payload));
            platform.store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("enquiry", Rec.of("outboundId", enquiryId, "at", Platform.now())));
            platform.event(id, "STATUS_ENQUIRED", "the rail was asked what became of the payment, in " + enquiryId, null, null);
            platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", enquiryId));
            return enquiryId;
        } catch (RuntimeException e) {
            platform.event(id, "STATUS_ENQUIRY_FAILED", String.valueOf(e.getMessage()), null, null);
            return null;
        }
    }

    /** An enquiry from another bank about a payment it sent us. */
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
            Rec enquiry = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            String answerChannel = channel.str("answerChannel");
            int answered = 0;
            int unknown = 0;
            List<Object> answers = new ArrayList<>();
            for (Object r : Ops.list(enquiry.get("requests"))) {
                Rec request = (Rec) r;
                Rec txn = find(request);
                Rec subject;
                if (txn == null) {
                    // nothing arrived with these references: said so, with NOOR, in a status report built from the request alone
                    subject = Rec.of("id", "NONE", "originalMsgId", request.str("originalMsgId"), "originalTxId", request.str("originalTxId"),
                            "endToEndId", request.str("originalEndToEndId"), "uetr", request.str("uetr"), "instructionRef", request.str("originalInstrId"),
                            "return", Rec.of("reasonCode", "NOOR", "reasonText", "No payment with these references was received"));
                    unknown++;
                } else {
                    subject = txn;
                    answered++;
                }
                String outboundId = Incoming.statusReport(platform, registry, answerChannel, subject, "statusAnswer");
                if (outboundId == null) {
                    throw new IllegalStateException("the enquiry could not be answered on " + answerChannel);
                }
                answers.add(Rec.of("transactionId", txn == null ? null : txn.str("id"), "outboundId", outboundId));
                if (txn != null) {
                    platform.event(txn.str("id"), "STATUS_ASKED", "the sender's bank asked what became of the payment; answered in " + outboundId, null, null);
                }
            }
            Lifecycle.finish(platform, messageId, Rec.of("msgId", enquiry.str("msgId"), "answeredCount", answered, "unknownCount", unknown, "answers", answers),
                    answered + " answered, " + unknown + " not known");
        } catch (RuntimeException e) {
            LOG.error("status enquiry {} failed", messageId, e);
            Lifecycle.fail(platform, messageId, e);
        }
    }

    private Rec find(Rec request) {
        List<Rec> found = new ArrayList<>();
        if (request.str("uetr") != null) {
            found = platform.store.find(DocStore.TXN, Rec.of("uetr", request.str("uetr")), "id", true, 5);
        }
        if (found.isEmpty() && request.str("originalTxId") != null) {
            Rec filter = Rec.of("originalTxId", request.str("originalTxId"));
            if (request.str("originalMsgId") != null) {
                filter.put("originalMsgId", request.str("originalMsgId"));
            }
            found = platform.store.find(DocStore.TXN, filter, "id", true, 5);
        }
        if (found.isEmpty() && request.str("originalEndToEndId") != null && request.str("originalMsgId") != null) {
            found = platform.store.find(DocStore.TXN, Rec.of("originalMsgId", request.str("originalMsgId"), "endToEndId", request.str("originalEndToEndId")), "id", true, 5);
        }
        return found.isEmpty() ? null : found.get(0);
    }
}
