package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Decisions on transactions held for review (a possible sanctions match, a fraud alert, a missing
 * document). Releasing records the hold code as overridden and runs the flow again, which then
 * passes the check that held it; every other check still applies. Rejecting ends the payment.
 */
public final class ReviewService {

    private final Platform platform;

    public ReviewService(Platform platform) {
        this.platform = platform;
    }

    /** @return false when the transaction is not held any more */
    public boolean release(String txnId, String actor, String comment) {
        Rec txn = platform.store.get(DocStore.TXN, txnId);
        if (txn == null || !Status.HELD.equals(txn.str("status"))) {
            return false;
        }
        String code = String.valueOf(txn.str("hold.code"));
        List<Object> overrides = new ArrayList<>(Ops.list(txn.get("overrides")));
        if (!overrides.contains(code)) {
            overrides.add(code);
        }
        if (!platform.store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.HELD),
                Rec.of("status", Status.CREATED, "overrides", overrides, "updatedAt", Platform.now()))) {
            return false;
        }
        platform.event(txnId, "RELEASED", "hold " + code + " released" + (comment == null || comment.isBlank() ? "" : ": " + comment), null, actor);
        platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txnId));
        return true;
    }

    public boolean reject(String txnId, String actor, String comment) {
        Rec txn = platform.store.get(DocStore.TXN, txnId);
        if (txn == null || !Status.HELD.equals(txn.str("status"))) {
            return false;
        }
        String code = String.valueOf(txn.str("hold.code"));
        String text = "Rejected after review" + (comment == null || comment.isBlank() ? "" : ": " + comment);
        if (Incoming.is(txn)) {
            // a held incoming payment that is turned down goes back to the sender; frozen funds come off the seized-funds account first
            if (!new PostingService(platform).releaseFrozen(txnId)) {
                throw new IllegalStateException("the frozen funds of " + txnId + " could not be released; the payment stays held");
            }
            String problem = Incoming.routeBack(platform.deployments.registry(), txn, code, text, actor);
            if (problem != null) {
                throw new IllegalStateException(problem);
            }
            if (!platform.store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.HELD), Rec.of("status", Status.ROUTED, "return", txn.get("return"),
                    "route", txn.get("route"), "routedAt", txn.get("routedAt"), "reasonCode", code, "reasonText", text, "updatedAt", Platform.now()))) {
                return false;
            }
            platform.event(txnId, Status.ROUTED, code + ": " + text + "; the payment is sent back", null, actor);
            platform.bus.publish(Bus.TXN_ROUTED, Rec.of("id", txnId, "channel", txn.str("route.channel")));
            return true;
        }
        if (!platform.store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.HELD),
                Rec.of("status", Status.REJECTED_BY_APPLICATION, "reasonCode", code, "reasonText", text, "updatedAt", Platform.now()))) {
            return false;
        }
        platform.event(txnId, Status.REJECTED_BY_APPLICATION, code + ": " + text, null, actor);
        Lifecycle.reopenReport(platform, txn.str("instructionId"));
        Lifecycle.unwound(platform, txnId);
        return true;
    }
}
