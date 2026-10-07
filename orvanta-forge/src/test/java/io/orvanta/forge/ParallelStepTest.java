package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Connector;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Outcome;
import io.orvanta.core.flow.FlowContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parallel step of a flow: several external calls at once, their answers together in the scope. */
class ParallelStepTest {

    private static final String FLOW = """
            kind: Flow
            name: t.Checks
            steps:
              - id: both
                type: parallel
                steps:
                  - id: screen
                    type: call
                    connector: connectors.Screening
                    onError: reject
                    request: {name: txn.creditor.name}
                    into: txn.screening
                  - id: fraud
                    type: call
                    connector: connectors.Fraud
                    request: {amount: txn.amount}
                    into: txn.checks.fraud
                  - id: mark
                    type: set
                    values: {txn.checks.marked: "true"}
              - id: after
                type: set
                values: {txn.done: "'yes'"}
            """;
    private static final List<ModelSource> CONNECTORS = List.of(
            ModelSource.parse("c1.yaml", "kind: Connector\nname: connectors.Screening\ntype: mock\n"),
            ModelSource.parse("c2.yaml", "kind: Connector\nname: connectors.Fraud\ntype: mock\n"));

    private static Forge.Build build(String flow) {
        java.util.List<ModelSource> models = new java.util.ArrayList<>(CONNECTORS);
        models.add(ModelSource.parse("f.yaml", flow));
        Forge.Build build = Forge.build(models);
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        return build;
    }

    @Test
    void branchesRunAtOnceAndTheirResultsMeetInTheScope() throws Exception {
        Forge.Build build = build(FLOW);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        Function<String, Connector> connectors = name -> request -> {
            threads.add(Thread.currentThread().getName() + "/" + name);
            Thread.sleep(300);
            return Rec.of("status", "CLEAR", "asked", request.copy());
        };
        Rec scope = Rec.of("txn", Rec.of("amount", 5, "creditor", Rec.of("name", "Thandiwe Mokoena"), "checks", Rec.of("account", "OPEN")));
        FlowContext ctx = new FlowContext(build.registry(), connectors);
        long started = System.nanoTime();
        Outcome out = build.registry().require("t.Checks", Flow.class).run(scope, ctx);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(millis < 550, "two 300 ms calls took " + millis + " ms, so they did not run at once");
        assertFalse("REJECTED".equals(out.status()), String.valueOf(out));
        assertEquals("CLEAR", scope.at("txn.screening.status"));
        assertEquals("Thandiwe Mokoena", scope.at("txn.screening.asked.name"));
        assertEquals("CLEAR", scope.at("txn.checks.fraud.status"));
        assertEquals(Boolean.TRUE, scope.at("txn.checks.marked"));
        assertEquals("OPEN", scope.at("txn.checks.account"), "what no branch touched stays");
        assertEquals("yes", scope.at("txn.done"), "the steps after the parallel step run with everything in place");
        assertEquals(2, threads.size(), threads.toString());
        // the trace shows the branches nested under the parallel step
        List<String> steps = ctx.trace().stream().map(s -> s.str("step") + "@" + (s.get("depth") == null ? 0 : s.get("depth"))).toList();
        assertTrue(steps.contains("both@0") && steps.contains("screen@1") && steps.contains("fraud@1") && steps.contains("mark@1") && steps.contains("after@0"), steps.toString());
        assertTrue(ctx.trace().stream().anyMatch(s -> "both".equals(s.str("step")) && "3 branches".equals(s.str("note"))), ctx.trace().toString());
    }

    @Test
    void aBranchThatEndsTheFlowEndsIt() throws Exception {
        Forge.Build build = build(FLOW);
        Function<String, Connector> connectors = name -> request -> {
            if (name.endsWith("Screening")) {
                throw new java.io.IOException("the screening system is down");
            }
            return Rec.of("status", "CLEAR");
        };
        Rec scope = Rec.of("txn", Rec.of("amount", 5, "creditor", Rec.of("name", "X"), "checks", new Rec()));
        FlowContext ctx = new FlowContext(build.registry(), connectors);
        Outcome out = build.registry().require("t.Checks", Flow.class).run(scope, ctx);
        assertEquals("REJECTED", out.status(), String.valueOf(out));
        assertEquals("CONNECTOR_ERROR", out.code());
        assertEquals("CLEAR", scope.at("txn.checks.fraud.status"), "the other branches' answers are kept");
        assertNull(scope.at("txn.done"), "the steps after it do not run");
    }

    @Test
    void aParallelStepNeedsTwoBranches() {
        Forge.Build build = Forge.build(List.of(ModelSource.parse("f.yaml", """
                kind: Flow
                name: t.One
                steps:
                  - id: only
                    type: parallel
                    steps:
                      - id: a
                        type: set
                        values: {x: "1"}
                """)));
        assertFalse(build.ok());
        assertTrue(build.problems().get(0).message().contains("at least two nested 'steps'"), build.problems().toString());
    }
}
