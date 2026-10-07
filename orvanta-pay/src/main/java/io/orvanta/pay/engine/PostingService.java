package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Outcome;
import io.orvanta.core.flow.FlowContext;
import io.orvanta.core.flow.Registry;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.util.Set;

/**
 * Reverses the account posting of a payment that did not go through after all: rejected by the
 * receiving side, returned, cancelled, or rejected after it had been posted. The reversal itself
 * is a flow model (engine.reversalFlow) that calls the posting system; this class decides when
 * it runs and records the result. A reversal that fails is tried again by the recovery service,
 * and the posting system is given the same idempotency key each time, so it cannot reverse twice.
 */
public final class PostingService {

    static final Set<String> UNWOUND = Set.of(Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL,
            Status.CANCELLED, Status.RETURNED);

    private final Platform platform;
    private final String flowName;
    private final String frozenFlowName;

    public PostingService(Platform platform) {
        this.platform = platform;
        this.flowName = platform.config.get("engine.reversalFlow", "payments.flows.PostingReversal");
        this.frozenFlowName = platform.config.get("engine.frozenReversalFlow", "payments.flows.FrozenReversal");
    }

    public void start() {
        platform.bus.subscribe(Bus.TXN_UNWOUND, "post", m -> handle(m.str("id")));
    }

    public void handle(String txnId) {
        reverse(txnId, false);
    }

    /**
     * Reverses the posting of a transaction.
     *
     * @param whateverTheStatus true for a refund, where the money is taken back before the payment is sent back
     * @return true when the posting is reversed now
     */
    public boolean reverse(String txnId, boolean whateverTheStatus) {
        DocStore store = platform.store;
        Rec txn = store.get(DocStore.TXN, txnId);
        if (txn == null || !(whateverTheStatus || UNWOUND.contains(txn.str("status"))) || !"POSTED".equals(txn.str("posting.status"))) {
            return false;
        }
        Registry registry = platform.deployments.registry();
        if (!(registry.element(flowName) instanceof Flow flow)) {
            return false;
        }
        Rec scope = Rec.of("txn", txn);
        FlowContext ctx = new FlowContext(registry, platform.deployments::connector, platform.data);
        Outcome outcome = flow.run(scope, ctx);
        Object reversal = ((Rec) scope.get("txn")).at("posting.reversal");
        if (!"COMPLETED".equals(outcome.status()) || !(reversal instanceof Rec)) {
            platform.event(txnId, "REVERSAL_FAILED", outcome.code() + ": " + outcome.message(), Rec.of("trace", ctx.trace()), null);
            return false;
        }
        Rec changes = Rec.of("posting.status", "REVERSED", "posting.reversal", reversal, "posting.reversedAt", Platform.now());
        // a charge that was booked for the payment is reversed by the same flow
        Object chargeReversal = ((Rec) scope.get("txn")).at("charges.posting.reversal");
        if (chargeReversal instanceof Rec) {
            changes.put("charges.posting.reversal", chargeReversal);
        }
        if (store.updateIf(DocStore.TXN, txnId, Rec.of("posting.status", "POSTED"), changes)) {
            platform.event(txnId, "POSTING_REVERSED", "posting " + txn.str("posting.postingId") + " reversed", Rec.of("trace", ctx.trace()), null);
            return true;
        }
        return false;
    }

    /**
     * Funds of a payment that were booked to the seized-funds account on a sanctions hit go back to where they
     * came from, because a person decided the payment is returned. @return true when they are back, or were never frozen
     */
    public boolean releaseFrozen(String txnId) {
        DocStore store = platform.store;
        Rec txn = store.get(DocStore.TXN, txnId);
        if (txn == null || !"POSTED".equals(txn.str("frozen.status")) || txn.at("frozen.reversal") != null) {
            return true;
        }
        Registry registry = platform.deployments.registry();
        if (!(registry.element(frozenFlowName) instanceof Flow flow)) {
            return false;
        }
        Rec scope = Rec.of("txn", txn);
        FlowContext ctx = new FlowContext(registry, platform.deployments::connector, platform.data);
        Outcome outcome = flow.run(scope, ctx);
        Object reversal = ((Rec) scope.get("txn")).at("frozen.reversal");
        if (!"COMPLETED".equals(outcome.status()) || !(reversal instanceof Rec)) {
            platform.event(txnId, "REVERSAL_FAILED", outcome.code() + ": " + outcome.message(), Rec.of("trace", ctx.trace()), null);
            return false;
        }
        store.updateIf(DocStore.TXN, txnId, new Rec(), Rec.of("frozen.status", "REVERSED", "frozen.reversal", reversal, "frozen.reversedAt", Platform.now()));
        platform.event(txnId, "FUNDS_UNFROZEN", "the funds came off the seized-funds account (posting " + txn.str("frozen.postingId") + " reversed)", Rec.of("trace", ctx.trace()), null);
        return true;
    }
}
