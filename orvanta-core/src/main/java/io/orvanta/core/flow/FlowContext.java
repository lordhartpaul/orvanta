package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Connector;
import io.orvanta.core.flow.Elements.DataAccess;
import io.orvanta.core.flow.Elements.DecisionTable;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Elements.RuleSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * State of one flow execution: element lookup, connectors, data access, the outcome and a step
 * trace. The trace is what the console shows as the processing timeline and what Studio shows in
 * a test run.
 */
public final class FlowContext {

    private final Registry registry;
    private final Function<String, Connector> connectors;
    private final DataAccess data;
    private final List<Violation> violations = new ArrayList<>();
    private final List<Rec> trace = new ArrayList<>();
    /** steps being executed, innermost last: a forEach step is open while its nested steps run */
    private final Deque<Rec> open = new ArrayDeque<>();

    private String status;
    private String code;
    private String message;

    public FlowContext(Registry registry, Function<String, Connector> connectors) {
        this(registry, connectors, null);
    }

    public FlowContext(Registry registry, Function<String, Connector> connectors, DataAccess data) {
        this.registry = registry;
        this.connectors = connectors;
        this.data = data;
    }

    public RuleSet ruleSet(String name) {
        return registry.require(name, RuleSet.class);
    }

    public Mapping mapping(String name) {
        return registry.require(name, Mapping.class);
    }

    public DecisionTable decision(String name) {
        return registry.require(name, DecisionTable.class);
    }

    public Flow flow(String name) {
        return registry.require(name, Flow.class);
    }

    public Connector connector(String name) {
        Connector c = connectors == null ? null : connectors.apply(name);
        if (c == null) {
            throw new IllegalStateException("no connector named '" + name + "' is configured");
        }
        return c;
    }

    public DataAccess data() {
        if (data == null) {
            throw new IllegalStateException("data sets are not available in this run");
        }
        return data;
    }

    // ---- branches of a parallel step ----

    /** A context for one branch of a parallel step: the same models, connectors and data, its own trace and outcome. */
    public FlowContext fork() {
        return new FlowContext(registry, connectors, data);
    }

    /** Takes a finished branch into this context: its trace nested under the open step, its violations, and its outcome if it ended (the first branch to end decides). */
    public void join(FlowContext branch) {
        int depth = open.size();
        for (Rec step : branch.trace) {
            Rec copy = step.copy();
            copy.put("depth", depth + (copy.get("depth") instanceof Number n ? n.intValue() : 0));
            trace.add(copy);
        }
        violations.addAll(branch.violations);
        if (branch.ended() && !ended()) {
            end(branch.status, branch.code, branch.message);
        }
    }

    // ---- outcome ----

    public void end(String status, String code, String message) {
        if (this.status == null) {
            this.status = status;
            this.code = code;
            this.message = message;
            note("ended " + status + (code == null ? "" : " " + code));
        }
    }

    public boolean ended() {
        return status != null;
    }

    public String status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public void addViolations(List<Violation> found) {
        violations.addAll(found);
    }

    public List<Violation> violations() {
        return violations;
    }

    public Violation firstError(List<Violation> found) {
        for (Violation v : found) {
            if (v.isError()) {
                return v;
            }
        }
        return null;
    }

    // ---- trace ----

    public void enter(String stepId, String type) {
        Rec step = Rec.of("step", stepId, "type", type, "started", System.nanoTime());
        if (!open.isEmpty()) {
            step.put("depth", open.size());
        }
        open.addLast(step);
        trace.add(step);
    }

    public void skip() {
        if (!open.isEmpty()) {
            open.getLast().put("skipped", true);
        }
    }

    public void note(String text) {
        if (!open.isEmpty()) {
            Rec step = open.getLast();
            Object existing = step.get("note");
            step.put("note", existing == null ? text : existing + "; " + text);
        }
    }

    public void leave() {
        if (!open.isEmpty()) {
            Rec step = open.removeLast();
            step.put("micros", (System.nanoTime() - (Long) step.remove("started")) / 1000);
        }
    }

    public List<Rec> trace() {
        return trace;
    }
}
