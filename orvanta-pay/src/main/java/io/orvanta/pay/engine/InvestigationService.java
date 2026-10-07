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
 * Investigations: another bank asks about a payment. A request for information (camt.026, "unable to
 * apply"), a claim that a payment was not received (camt.027) or a request to modify a payment (camt.087)
 * opens a case on the payment it names; a person answers it. The answer to a request for information is
 * a camt.028 with what was asked; the answer to a claim or a modification request is a camt.029 with a
 * confirmation code. A case about a payment that cannot be found is answered at once with a camt.029.
 */
public final class InvestigationService {

    private static final Logger LOG = LoggerFactory.getLogger(InvestigationService.class);
    public static final String PURPOSE = "investigation";

    private final Platform platform;

    public InvestigationService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic(PURPOSE), "investigate", m -> handle(m.str("id")));
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
            Rec request = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            request.put("messageId", messageId);
            request.put("channel", message.str("channel"));
            request.put("receivedAt", Platform.now());
            request.put("status", "OPEN");
            Rec txn = find(request);
            if (txn == null) {
                // nothing with these references: said so at once, no case is left open
                String outboundId = answer(request, null, "RJNR", "No payment with these references is known here", "application");
                Lifecycle.finish(platform, messageId, Rec.of("msgId", request.str("msgId"), "caseId", request.str("caseId"), "kind", request.str("kind"),
                        "matched", false, "answerId", outboundId), request.str("kind") + " about an unknown payment, answered RJNR");
                return;
            }
            String id = txn.str("id");
            List<Object> cases = new ArrayList<>(Ops.list(txn.get("investigations")));
            cases.add(request);
            store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("investigations", cases, "investigationOpen", true, "updatedAt", Platform.now()));
            platform.event(id, "INVESTIGATION_OPENED", request.str("kind") + " from " + request.str("assignerBic") + ", case " + request.str("caseId")
                    + (request.str("text") == null ? "" : ": " + request.str("text")), null, null);
            platform.changed("payments");
            Lifecycle.finish(platform, messageId, Rec.of("msgId", request.str("msgId"), "caseId", request.str("caseId"), "kind", request.str("kind"), "matched", true,
                    "transactionId", id), request.str("kind") + " about " + id + ", waiting for an answer");
        } catch (RuntimeException e) {
            LOG.error("investigation {} failed", messageId, e);
            Lifecycle.fail(platform, messageId, e);
        }
    }

    /** A person's answer to an open case: camt.028 for a request for information, camt.029 for a claim or a modification request. */
    public Rec answerCase(String txnId, String caseId, String confirmation, String text, String actor) {
        DocStore store = platform.store;
        Rec txn = store.get(DocStore.TXN, txnId);
        List<?> cases = txn == null ? List.of() : Ops.list(txn.get("investigations"));
        Rec found = null;
        for (Object c : cases) {
            if (c instanceof Rec r && caseId.equals(r.str("caseId")) && "OPEN".equals(r.str("status"))) {
                found = r;
            }
        }
        if (found == null) {
            throw new IllegalStateException("payment " + txnId + " has no open investigation " + caseId);
        }
        String outboundId = answer(found, txn, confirmation, text, actor);
        List<Object> updated = new ArrayList<>();
        boolean anyOpen = false;
        for (Object c : cases) {
            Rec r = ((Rec) c).copy();
            if (r == found || caseId.equals(r.str("caseId")) && "OPEN".equals(r.str("status"))) {
                r.put("status", "ANSWERED");
                r.put("answer", Rec.of("outboundId", outboundId, "at", Platform.now(), "confirmation", confirmation, "text", text, "by", actor));
            }
            anyOpen = anyOpen || "OPEN".equals(r.str("status"));
            updated.add(r);
        }
        store.updateIf(DocStore.TXN, txnId, new Rec(), Rec.of("investigations", updated, "investigationOpen", anyOpen, "updatedAt", Platform.now()));
        platform.event(txnId, "INVESTIGATION_ANSWERED", "case " + caseId + " answered in " + outboundId + (confirmation == null ? "" : " (" + confirmation + ")")
                + (text == null ? "" : ": " + text), null, actor);
        platform.changed("payments");
        return Rec.of("transactionId", txnId, "caseId", caseId, "outboundId", outboundId);
    }

    /** Builds the answer on the channel the request's channel names: 'infoChannel' for information, 'answerChannel' for a resolution. */
    private String answer(Rec request, Rec txn, String confirmation, String text, String actor) {
        Registry registry = platform.deployments.registry();
        Rec inbound = registry.config(String.valueOf(request.str("channel")));
        boolean information = "RFI".equals(request.str("kind")) && txn != null;
        String name = inbound == null ? null : information ? inbound.str("infoChannel") : inbound.str("answerChannel");
        Rec channel = name == null ? null : registry.config(name);
        if (channel == null) {
            throw new IllegalStateException("the investigation has to be answered, but channel " + request.str("channel") + " names no "
                    + (information ? "infoChannel" : "answerChannel"));
        }
        String outboundId = platform.newId("ORVOUT");
        Rec answer = request.copy();
        answer.put("id", outboundId);
        answer.put("createdAt", Platform.now().substring(0, 19) + "Z");
        answer.set("confirmation", confirmation);
        answer.set("text", text);
        if (channel.get("properties") instanceof java.util.Map<?, ?> properties) {
            answer.putAll(Rec.from(properties));
        }
        Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("answer", answer, "txn", txn == null ? new Rec() : txn));
        String payload = Messages.write(channel.str("format"), mapped);
        platform.checks().requireValidOutbound(channel, payload);
        List<Object> ids = new ArrayList<>();
        if (txn != null) {
            ids.add(txn.str("id"));
        }
        platform.store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "investigationAnswer", "channel", name, "format", channel.str("format"),
                "messageType", channel.str("messageType"), "transactionCount", ids.size(), "reportedIds", ids, "caseId", request.str("caseId"),
                "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
        platform.event(outboundId, "CREATED", "answer to case " + request.str("caseId") + (confirmation == null ? "" : ": " + confirmation), null, actor);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return outboundId;
    }

    /** The payment a case is about: one we sent (by our ids) or one we received (by the sender's ids). */
    private Rec find(Rec request) {
        List<Rec> found = new ArrayList<>();
        if (request.str("uetr") != null) {
            found = platform.store.find(DocStore.TXN, Rec.of("uetr", request.str("uetr")), "id", true, 5);
        }
        if (found.isEmpty() && request.str("originalTxId") != null) {
            Rec ours = platform.store.get(DocStore.TXN, request.str("originalTxId"));
            if (ours != null) {
                found = List.of(ours);
            } else {
                found = platform.store.find(DocStore.TXN, Rec.of("originalTxId", request.str("originalTxId")), "id", true, 5);
            }
        }
        if (found.isEmpty() && request.str("originalEndToEndId") != null) {
            found = platform.store.find(DocStore.TXN, Rec.of("endToEndId", request.str("originalEndToEndId")), "id", true, 5);
        }
        return found.isEmpty() ? null : found.get(0);
    }
}
