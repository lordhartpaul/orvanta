package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Outcome;
import io.orvanta.core.flow.FlowContext;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.flow.Violation;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the processing flow (validation, enrichment, external calls, routing) for one transaction
 * and records the outcome. All business logic is in the flow model; this class only maps the
 * status the flow ended with onto the transaction:
 *
 * <pre>
 *   ROUTED    ready for bulking            REJECTED  rejected by the application
 *   HOLD      held for a person to review  WAIT      waiting for a late answer or for a retry time
 *   anything else, or an error: REPAIR
 * </pre>
 *
 * The flow is run again from the start after a hold is released, a late answer arrives or a
 * failed call is retried. Steps marked {@code once} reuse the result already stored on the
 * transaction, so an external system is not asked the same question twice.
 */
public final class ProcessingService {

    private final Platform platform;
    private final String flowName;
    private final int maxRetries;
    private final long retryBaseSeconds;

    public ProcessingService(Platform platform) {
        this.platform = platform;
        this.flowName = platform.config.get("engine.processingFlow", "payments.flows.TransactionProcessing");
        this.maxRetries = platform.config.getInt("recovery.maxRetries", 5);
        this.retryBaseSeconds = platform.config.getInt("recovery.retryBaseSeconds", 5);
    }

    public void start() {
        platform.bus.subscribe(Bus.TXN_CREATED, "process", m -> handle(m.str("id"), Status.CREATED, null));
    }

    /** @param fromStatus CREATED for new or resumed transactions, REPAIR when a failed one is tried again */
    public boolean handle(String txnId, String fromStatus, String actor) {
        DocStore store = platform.store;
        if (!store.updateIf(DocStore.TXN, txnId, Rec.of("status", fromStatus),
                Rec.of("status", Status.PROCESSING, "updatedAt", Platform.now()))) {
            return false;
        }
        Rec scope = Rec.of("txn", store.get(DocStore.TXN, txnId));
        Registry registry = platform.deployments.registry();
        FlowContext ctx = new FlowContext(registry, platform.deployments::connector, platform.data);
        Outcome outcome;
        // the inbound channel may have named a flow of its own, for example for direct debits
        String flowName = ((Rec) scope.get("txn")).str("processingFlow") == null ? this.flowName : ((Rec) scope.get("txn")).str("processingFlow");
        try {
            outcome = registry.require(flowName, Flow.class).run(scope, ctx);
        } catch (RuntimeException e) {
            outcome = new Outcome("ERROR", "FLOW_ERROR", e.getMessage());
        }
        Rec txn = (Rec) scope.get("txn");

        String status;
        boolean incoming = Incoming.is(txn);
        // an incoming payment that fails a check is not dropped: it goes back to where it came from, with the reason
        String back = incoming && List.of("REJECTED", "RETURN").contains(outcome.status())
                ? Incoming.routeBack(registry, txn, outcome.code(), outcome.message(), "application") : "not asked";
        if (back == null) {
            status = Status.ROUTED;
        } else if (incoming && !"not asked".equals(back)) {
            status = Status.REPAIR;
            outcome = new Outcome(status, "NO_CHANNEL", back);
        } else
        switch (outcome.status()) {
            case "CREDITED", "DEBITED" -> {
                boolean debit = outcome.status().equals("DEBITED");
                if (incoming && debit == Incoming.DEBIT_TYPE.equals(txn.str("paymentType"))) {
                    status = debit ? Status.DEBITED : Status.CREDITED;
                    txn.put(debit ? "debitedAt" : "creditedAt", Platform.now());
                    // the customer is told what was booked on the account
                    NotificationService.booked(registry, txn, debit ? NotificationService.DEBIT : NotificationService.CREDIT);
                } else {
                    status = Status.REPAIR;
                    outcome = new Outcome(status, "FLOW_ERROR", "the flow ended with " + outcome.status()
                            + ", which is only for incoming " + (debit ? "direct debit collections" : "credit transfers"));
                }
            }
            case "ROUTED" -> {
                Rec channel = registry.config(String.valueOf(txn.at("route.channel")));
                if (channel == null || !"outbound".equals(channel.str("direction"))) {
                    status = Status.REPAIR;
                    outcome = new Outcome(status, "NO_CHANNEL", "the flow routed to '" + txn.at("route.channel") + "', which is not an outbound channel");
                } else if (channel.get("allowedPurposeCodes") instanceof List<?> allowed && !allowed.isEmpty()
                        && (txn.str("purposeCode") != null ? !Ops.in(txn.str("purposeCode"), allowed) : Boolean.TRUE.equals(channel.get("requirePurposeCode")))) {
                    // the receiver takes only some category purposes: a person corrects the code before it leaves
                    status = Status.REPAIR;
                    outcome = new Outcome(status, "PURPOSE_NOT_ALLOWED", txn.str("purposeCode") == null
                            ? channel.str("name") + " needs a purpose code; one of " + allowed
                            : "purpose code " + txn.str("purposeCode") + " is not one " + channel.str("name") + " takes: " + allowed);
                } else if (channel.get("minAmount") != null && Ops.lt(txn.get("amount"), channel.get("minAmount"))
                        || channel.get("maxAmount") != null && Ops.gt(txn.get("amount"), channel.get("maxAmount"))) {
                    // the route takes amounts in a range (a clearing's limit): a person picks another route or splits the payment
                    status = Status.REPAIR;
                    outcome = new Outcome(status, "AMOUNT_OUT_OF_RANGE", channel.str("name") + " takes amounts "
                            + (channel.get("minAmount") != null ? "from " + Ops.str(channel.get("minAmount")) + " " : "")
                            + (channel.get("maxAmount") != null ? "up to " + Ops.str(channel.get("maxAmount")) : "") + "; this payment is " + Ops.str(txn.get("amount")));
                } else {
                    status = Status.ROUTED;
                    txn.put("routedAt", Platform.now());
                }
            }
            case "REJECTED" -> status = Status.REJECTED_BY_APPLICATION;
            case "HOLD" -> status = Status.HELD;
            case "WAIT" -> status = Status.WAITING;
            case "WAREHOUSE" -> {
                // kept until a date or a time; the flow says until when in txn.warehouse.until
                if (txn.str("warehouse.until") == null) {
                    status = Status.REPAIR;
                    outcome = new Outcome(status, "FLOW_ERROR", "the flow warehoused the payment without setting txn.warehouse.until");
                } else {
                    status = Status.WAREHOUSED;
                    txn.rec("warehouse").put("since", Platform.now());
                    txn.rec("warehouse").put("code", String.valueOf(outcome.code()));
                }
            }
            default -> status = Status.REPAIR;
        }

        // a failed external call is retried automatically with a growing delay, then left for an operator
        Rec retry = txn.rec("retry");
        retry.remove("nextAt");
        if (status.equals(Status.REPAIR) && "CONNECTOR_ERROR".equals(outcome.code())) {
            int count = retry.get("count") == null ? 1 : Ops.num(retry.get("count")).intValue() + 1;
            retry.put("count", count);
            if (count <= maxRetries) {
                retry.put("nextAt", Instant.now().plusSeconds(retryBaseSeconds * (long) Math.pow(3, count - 1)).toString());
            }
        }
        if (retry.isEmpty()) {
            txn.remove("retry");
        }

        if (status.equals(Status.HELD)) {
            // what the flow said about the hold (a task step's queue and instructions) stays with code, message and time
            Rec hold = txn.get("hold") instanceof Rec h ? h : new Rec();
            hold.put("code", outcome.code());
            hold.put("message", outcome.message());
            hold.put("since", Platform.now());
            txn.put("hold", hold);
        } else {
            txn.remove("hold");
        }
        if (status.equals(Status.WAITING)) {
            // the flow may ask to be run again after a while (wait.retrySeconds); otherwise an answer is awaited
            Rec wait = txn.rec("wait");
            wait.put("code", String.valueOf(outcome.code()));
            wait.putIfAbsent("since", Platform.now());
            Object seconds = wait.remove("retrySeconds");
            if (seconds == null) {
                wait.remove("retryAt");
            } else {
                wait.put("retryAt", Instant.now().plusSeconds(Ops.num(seconds).longValue()).toString());
            }
            // a wait step may give its own time limit (wait.timeoutSeconds); otherwise the installation's engine.waitTimeoutSeconds applies
            Object timeout = wait.remove("timeoutSeconds");
            if (timeout != null && wait.get("until") == null) {
                wait.put("until", Instant.now().plusSeconds(Ops.num(timeout).longValue()).toString());
            }
        } else {
            txn.remove("wait");
        }

        if (!status.equals(Status.WAREHOUSED)) {
            txn.remove("warehouse");
        }

        List<Object> violations = new ArrayList<>();
        for (Violation v : ctx.violations()) {
            violations.add(v.toRec());
        }
        txn.put("status", status);
        txn.put("violations", violations);
        txn.set("reasonCode", outcome.code());
        txn.set("reasonText", outcome.message());
        txn.put("deployment", platform.deployments.activeId());
        txn.put("updatedAt", Platform.now());
        store.save(DocStore.TXN, txn);
        platform.event(txnId, status, outcome.code() == null ? "processed by " + flowName : outcome.code() + ": " + outcome.message(),
                Rec.of("trace", ctx.trace()), actor);
        if (Lifecycle.FINAL.contains(status)) {
            Lifecycle.reopenReport(platform, txn.str("instructionId"));
            Lifecycle.unwound(platform, txnId);
        }
        if (status.equals(Status.CREDITED) || status.equals(Status.DEBITED)) {
            Incoming.confirm(platform, registry, txn);
        }
        if (status.equals(Status.ROUTED)) {
            // lets a channel that does not wait (an instant rail) send at once instead of at the next sweep
            platform.bus.publish(Bus.TXN_ROUTED, Rec.of("id", txnId, "channel", String.valueOf(txn.at("route.channel")), "priority", txn.str("priority")));
        }
        return true;
    }
}
