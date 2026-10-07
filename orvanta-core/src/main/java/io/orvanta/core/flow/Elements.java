package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;

import java.util.List;

/**
 * Base types of the classes Forge generates. A scope is a record of named variables
 * (for example {txn: ..., instr: ...}) that every element reads and writes.
 */
public final class Elements {

    private Elements() {
    }

    public interface Element {
        String name();

        String kind();
    }

    public abstract static class RuleSet implements Element {
        public String kind() {
            return "RuleSet";
        }

        public abstract List<Violation> check(Rec scope);
    }

    public abstract static class Mapping implements Element {
        public String kind() {
            return "Mapping";
        }

        /** Returns the mapped target: a new record, or the scope variable named as target in the model. */
        public abstract Rec apply(Rec scope);
    }

    public abstract static class DecisionTable implements Element {
        public String kind() {
            return "DecisionTable";
        }

        /** Applies the first matching row and returns its id, or null when no row matched. */
        public abstract String decide(Rec scope);
    }

    public abstract static class Flow implements Element {
        public String kind() {
            return "Flow";
        }

        /** Status reported when no step ended the flow explicitly. */
        public String completeStatus() {
            return "COMPLETED";
        }

        protected abstract void execute(Rec scope, FlowContext ctx) throws Exception;

        /** Runs as a step of another flow: same scope, same context, and ending here ends the calling flow. */
        public void runWithin(Rec scope, FlowContext ctx) throws Exception {
            execute(scope, ctx);
        }

        public Outcome run(Rec scope, FlowContext ctx) {
            try {
                execute(scope, ctx);
            } catch (Exception e) {
                ctx.end("ERROR", "FLOW_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return new Outcome(ctx.status() == null ? completeStatus() : ctx.status(), ctx.code(), ctx.message());
        }
    }

    /** Compiled expectations of a TestCase: returns the text of every expectation that did not hold. */
    public abstract static class Checks implements Element {
        public String kind() {
            return "TestCase";
        }

        public abstract List<String> failed(Rec scope);
    }

    public record Outcome(String status, String code, String message) {
        public Rec toRec() {
            return Rec.of("status", status, "code", code, "message", message);
        }
    }

    /**
     * Stored data a flow may read and change, limited to the DataSet models of the deployment.
     * A data set names its key field; a record is identified by the value of that field.
     */
    public interface DataAccess {
        List<Rec> find(String dataset, Rec where, String sortField, boolean descending, int limit);

        /** @return false when onlyIfAbsent was asked and a record with the same key already exists */
        boolean save(String dataset, Rec record, boolean onlyIfAbsent);

        void remove(String dataset, Object key);
    }

    /** A call to an external system. */
    public interface Connector {
        Rec call(Rec request) throws Exception;
    }
}
