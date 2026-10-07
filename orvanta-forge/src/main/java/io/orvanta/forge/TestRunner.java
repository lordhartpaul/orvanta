package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Checks;
import io.orvanta.core.flow.Elements.Connector;
import io.orvanta.core.flow.Elements.DecisionTable;
import io.orvanta.core.flow.Elements.Element;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Elements.Outcome;
import io.orvanta.core.flow.Elements.RuleSet;
import io.orvanta.core.flow.FlowContext;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.flow.Violation;
import io.orvanta.core.format.Messages;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs elements against given data. Used for TestCase models (forge test, CI) and for
 * the "run" button of Studio, which shows the result and the step trace.
 */
public final class TestRunner {

    public record TestResult(String name, String target, boolean passed, List<String> failures) {
        public Rec toRec() {
            return Rec.of("name", name, "target", target, "passed", passed, "failures", failures);
        }
    }

    private TestRunner() {
    }

    /**
     * Executes any element and stores what it produced in scope variable "result".
     * RuleSet: {count, codes, violations}; Mapping: the mapped record; DecisionTable: {row};
     * Flow: {status, code, message, codes, trace}.
     */
    public static Rec execute(Registry registry, String target, Rec scope, Function<String, Connector> connectors) {
        return execute(registry, target, scope, connectors, new MemoryDataAccess(registry, null));
    }

    public static Rec execute(Registry registry, String target, Rec scope, Function<String, Connector> connectors,
                              io.orvanta.core.flow.Elements.DataAccess data) {
        // reference tables are read from the registry being run, not from the active deployment
        return io.orvanta.core.flow.RefData.with(registry, () -> executeIn(registry, target, scope, connectors, data));
    }

    private static Rec executeIn(Registry registry, String target, Rec scope, Function<String, Connector> connectors,
                                 io.orvanta.core.flow.Elements.DataAccess data) {
        Element element = registry.element(target);
        if (element == null) {
            throw new IllegalArgumentException("no element named '" + target + "' is deployed");
        }
        Rec result;
        if (element instanceof RuleSet rules) {
            result = violationsRec(rules.check(scope));
        } else if (element instanceof Mapping mapping) {
            result = mapping.apply(scope);
        } else if (element instanceof DecisionTable table) {
            result = Rec.of("row", table.decide(scope));
        } else if (element instanceof Flow flow) {
            FlowContext ctx = new FlowContext(registry, connectors, data);
            Outcome outcome = flow.run(scope, ctx);
            result = outcome.toRec();
            result.putAll(violationsRec(ctx.violations()));
            result.put("trace", ctx.trace());
        } else {
            throw new IllegalArgumentException("'" + target + "' is a " + element.kind() + " and cannot be run");
        }
        scope.put("result", result);
        return result;
    }

    private static Rec violationsRec(List<Violation> violations) {
        List<Object> list = new ArrayList<>();
        List<Object> codes = new ArrayList<>();
        for (Violation v : violations) {
            list.add(v.toRec());
            codes.add(v.code());
        }
        return Rec.of("count", violations.size(), "codes", codes, "violations", list);
    }

    /** Runs every TestCase of the registry. Files named in givenMessage are resolved against baseDir. */
    public static List<TestResult> runAll(Registry registry, Path baseDir) {
        List<TestResult> results = new ArrayList<>();
        for (Rec test : registry.configs("TestCase")) {
            results.add(run(registry, test, baseDir));
        }
        return results;
    }

    public static TestResult run(Registry registry, Rec test, Path baseDir) {
        String name = test.str("name");
        String target = test.str("target");
        List<String> failures = new ArrayList<>();
        try {
            Rec scope = test.get("given") instanceof Map<?, ?> g ? Rec.from(g) : new Rec();
            if (test.get("givenMessage") instanceof Map<?, ?> gm) {
                Rec message = Rec.from(gm);
                Path file = baseDir.resolve(message.str("file"));
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                scope.put(message.str("var") == null ? "src" : message.str("var"), Messages.parse(raw).get(0).tree());
            }
            Rec mocks = test.get("mocks") instanceof Map<?, ?> mk ? Rec.from(mk) : new Rec();
            // 'data' seeds the data sets for the run; what the run left in them is readable as 'stored'
            MemoryDataAccess data = new MemoryDataAccess(registry, test.get("data") instanceof Map<?, ?> d ? Rec.from(d) : null);
            // 'clock' fixes now(), today() and the calendar functions for the run and for the expectations
            java.time.Instant clock = test.str("clock") == null ? null : java.time.Instant.parse(test.str("clock"));
            failures.addAll(io.orvanta.core.expr.Time.with(clock, () -> {
                execute(registry, target, scope, connectorName -> {
                    // a connector not mocked by the test answers with the testReply of its own model, if it has one
                    Connector mock = mockConnector(mocks, connectorName);
                    Rec def = registry.config(connectorName);
                    return mock != null || def == null || !(def.get("testReply") instanceof Rec reply) ? mock : request -> reply.copy();
                }, data);
                for (Map.Entry<String, Object> e : data.contents().entrySet()) {
                    scope.set("stored." + e.getKey(), e.getValue());
                }
                return io.orvanta.core.flow.RefData.with(registry, () -> ((Checks) registry.element(name)).failed(scope));
            }));
        } catch (Exception e) {
            failures.add("test could not run: " + e.getMessage());
        }
        return new TestResult(name, target, failures.isEmpty(), failures);
    }

    /** mocks: {connectorName: {reply fields}} or {connectorName: {error: 'text'}} to simulate an outage. */
    public static Connector mockConnector(Rec mocks, String name) {
        Object reply = mocks.get(name);
        if (!(reply instanceof Rec r)) {
            return null;
        }
        return request -> {
            Object error = Ops.get(r, "error");
            if (error != null) {
                throw new IllegalStateException(Ops.str(error));
            }
            return r.copy();
        };
    }
}
