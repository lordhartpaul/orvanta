package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Elements.RuleSet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeTest {

    private static final Path WORKSPACE = Path.of("..", "workspace");

    @Test
    void workspaceBuildsAndEveryModelTestPasses() {
        Forge.Build build = Forge.buildWorkspace(WORKSPACE);
        assertTrue(build.ok(), () -> "build problems: " + build.problems());
        List<TestRunner.TestResult> results = TestRunner.runAll(build.registry(), WORKSPACE);
        assertFalse(results.isEmpty());
        for (TestRunner.TestResult r : results) {
            assertTrue(r.passed(), () -> r.name() + " failed: " + r.failures());
        }
    }

    @Test
    void aModelWrittenFromItsDefinitionMeansTheSame() {
        // the visual designer reads a model as data and writes it back as text: nothing may change on the way
        List<Problem> problems = new java.util.ArrayList<>();
        List<ModelSource> rewritten = new java.util.ArrayList<>();
        for (ModelSource model : ModelSource.loadWorkspace(WORKSPACE, problems)) {
            Rec definition = ModelSource.definition(model.text());
            String text = ModelSource.toText(definition);
            assertEquals(definition, ModelSource.definition(text), model.name());
            rewritten.add(ModelSource.parse(model.path(), text));
        }
        assertTrue(problems.isEmpty(), problems::toString);
        Forge.Build build = Forge.build(rewritten);
        assertTrue(build.ok(), () -> "build problems: " + build.problems());
        assertThrows(ModelException.class, () -> ModelSource.definition("[not, a, mapping]"));
    }

    private static io.orvanta.core.format.MtSpec spec(String type) throws Exception {
        return io.orvanta.core.format.MtSpec.compile(ModelSource.parse("s.yaml",
                java.nio.file.Files.readString(WORKSPACE.resolve("specs/swift/" + type + ".yaml"))).def());
    }

    private static List<String> problems(String type, String raw) throws Exception {
        return spec(type).check(io.orvanta.core.format.SwiftMt.parse(raw));
    }

    @Test
    void anMtMessageIsCheckedFieldByFieldAgainstItsSpecification() throws Exception {
        String mt101 = java.nio.file.Files.readString(WORKSPACE.resolve("tests/messages/mt101-suppliers.fin"));
        String mt940 = java.nio.file.Files.readString(WORKSPACE.resolve("tests/messages/mt940-nostro.fin"));
        assertEquals(List.of(), problems("MT101", mt101));
        assertEquals(List.of(), problems("MT940", mt940));

        // a wrong format names the field, which occurrence of it, and the format it should have
        List<String> amount = problems("MT101", mt101.replace(":32B:EUR8300,40", ":32B:EUR8300.40"));
        assertEquals(1, amount.size(), amount.toString());
        assertTrue(amount.get(0).contains(":32B:") && amount.get(0).contains("occurrence 2") && amount.get(0).contains("sequence B (occurrence 2)")
                && amount.get(0).contains("3!a15d"), amount.get(0));
        // a format that fits but a value that cannot be right
        assertTrue(problems("MT101", mt101.replace(":30:261005", ":30:261345")).get(0).contains("is not a date"));
        assertTrue(problems("MT101", mt101.replace(":32B:USD12500,00", ":32B:XQZ12500,00")).get(0).contains("'XQZ' is not a currency code"));
        // a mandatory field that is missing, in the sequence where it is missing
        List<String> missing = problems("MT101", mt101.replace(":71A:OUR\n", "").replace(":71A:OUR\r\n", ""));
        assertTrue(missing.get(0).contains(":71A:") && missing.get(0).contains("is mandatory in sequence B (occurrence 2) and is missing"), missing.toString());
        assertTrue(problems("MT101", mt101.replace(":28D:1/1", ":28X:1/1")).toString().contains(":28D:"));
        // a field out of order, and a field the message type does not have
        assertTrue(problems("MT101", mt101.replace(":30:261005", ":99Z:WHAT")).toString().contains("MT101 has no such field"));
        List<String> order = problems("MT940", mt940.replace(":28C:278/1", ":28C:278/1\n:20:AGAIN"));
        assertTrue(order.toString().contains(":20:") && order.toString().contains("out of order"), order.toString());
        // too many lines, and a line that is too long
        assertTrue(problems("MT101", mt101.replace(":70:Invoice 88120", ":70:1\n2\n3\n4\n5")).get(0).contains("4*35x"));
        assertTrue(problems("MT101", mt101.replace(":21:INV-88120", ":21:INV-88120-AND-MUCH-TOO-LONG")).get(0).contains("16x"));

        // optional lines: an account line in front of the name, or none
        String mt103 = "{1:F01ORVAZAJJAXXX0000000000}{2:I103CHASUS33XXXXN}{4:\n:20:REF1\n:23B:CRED\n:32A:261005USD12500,00\n"
                + ":50K:/4051122334\nKaroo Mining Supplies\n:57A:CHASUS33\n:59:Atlas Drilling Equipment Inc\n:71A:SHA\n-}";
        assertEquals(List.of(), problems("MT103", mt103));
        assertEquals(List.of(), problems("MT103", mt103.replace(":50K:/4051122334\n", ":50K:")));
        assertTrue(problems("MT103", mt103.replace(":57A:CHASUS33", ":57A:chasus33")).get(0).contains(":57A:"));
        // the cover message: sequence B is optional, but once it starts its mandatory fields are needed
        String mt202 = "{1:F01ORVAZAJJAXXX0000000000}{2:I202CHASUS33XXXXN}{4:\n:20:COV1\n:21:REF1\n:32A:261005USD12500,00\n:58A:DEUTDEFF\n-}";
        assertEquals(List.of(), problems("MT202", mt202));
        assertEquals(List.of(), problems("MT202", mt202.replace("-}", ":50K:/4051122334\nKaroo Mining Supplies\n:59:/DE89370400440532013000\nRheinland Pumpen GmbH\n-}")));
        assertTrue(problems("MT202", mt202.replace("-}", ":50K:Karoo Mining Supplies\n-}")).get(0).contains(":59a"));
    }

    @Test
    void aMessageSpecificationWithAMistakeIsReportedWhenTheModelsAreBuilt() {
        String head = "kind: MessageSpec\nname: t.Spec\nformat: swift.mt\nmessageType: MT199\nfields:\n";
        for (String[] bad : new String[][] {
                {"  - {tag: \"20\", format: \"16q\"}\n", "'q' is not a character type"},
                {"  - {tag: \"20\", format: \"[16x\"}\n", "'[' is not closed"},
                {"  - {tag: \"2\", format: \"16x\"}\n", "tag must be two digits"},
                {"  - {tag: \"20\"}\n", "'format' or 'options' is required"},
                {"  - {tag: \"20\", format: \"16x\", is: nonsense}\n", "'is' must be date, bic or currencyAmount"}}) {
            Forge.Build build = Forge.build(List.of(ModelSource.parse("s.yaml", head + bad[0])));
            assertFalse(build.ok());
            assertTrue(build.problems().toString().contains(bad[1]), build.problems().toString());
        }
        assertTrue(Forge.build(List.of(ModelSource.parse("s.yaml", head + "  - {tag: \"20\", format: \"16x\"}\n"))).ok());
        assertFalse(Forge.build(List.of(ModelSource.parse("s.yaml", head.replace("swift.mt", "iso20022") + "  - {tag: \"20\", format: \"16x\"}\n"))).ok());
    }

    @Test
    void expressionPrecedenceAndFunctions() {
        Forge.Build build = Forge.build(List.of(ModelSource.parse("m.yaml", """
                kind: Mapping
                name: t.Expr
                rules:
                  - {set: a, value: 1 + 2 * 3}
                  - {set: b, value: (1 + 2) * 3}
                  - {set: c, value: "x.n > 5 and not (x.s == 'no') ? 'big' : 'small'"}
                  - {set: d, value: "x.s in ['yes', 'maybe']"}
                  - {set: e, value: "upper(concat(x.s, '-', x.n))"}
                  - {set: f, value: x.missing.deeper}
                  - {set: g, value: 10 / 4}
                  - {set: h, value: "-x.n + 1"}
                """)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        Rec out = build.registry().require("t.Expr", Mapping.class).apply(Rec.of("x", Rec.of("n", 7, "s", "yes")));
        assertEquals("7", out.str("a"));
        assertEquals("9", out.str("b"));
        assertEquals("big", out.str("c"));
        assertEquals(Boolean.TRUE, out.get("d"));
        assertEquals("YES-7", out.str("e"));
        assertFalse(out.containsKey("f"), "a missing value sets nothing");
        assertEquals("2.5", out.str("g"));
        assertEquals("-6", out.str("h"));
    }

    @Test
    void problemsNameTheModelAndThePlace() {
        Forge.Build build = Forge.build(List.of(
                ModelSource.parse("a.yaml", """
                        kind: RuleSet
                        name: t.Broken
                        rules:
                          - {id: R1, assert: "txn.amount >"}
                          """),
                ModelSource.parse("b.yaml", """
                        kind: Flow
                        name: t.Flow
                        steps:
                          - {id: s1, type: rules, ref: t.DoesNotExist}
                          """),
                ModelSource.parse("c.yaml", """
                        kind: Mapping
                        name: t.Map
                        rules:
                          - {set: a, value: nosuchfunction(1)}
                          """)));
        assertFalse(build.ok());
        assertEquals(3, build.problems().size(), () -> String.valueOf(build.problems()));
        assertEquals("t.Broken", build.problems().get(0).element());
        assertTrue(build.problems().get(0).where().contains("rule R1"));
        assertTrue(build.problems().get(1).message().contains("t.DoesNotExist"));
        assertTrue(build.problems().get(2).message().contains("unknown function"));
    }

    @Test
    void aFailingRuleExpressionBecomesAViolationNotACrash() {
        Forge.Build build = Forge.build(List.of(ModelSource.parse("a.yaml", """
                kind: RuleSet
                name: t.Rules
                rules:
                  - {id: NUMERIC, assert: txn.amount + 0 > 1 and txn.amount * 2 > 1, code: X1, message: bad amount}
                """)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        RuleSet rules = build.registry().require("t.Rules", RuleSet.class);
        assertEquals(1, rules.check(Rec.of("txn", Rec.of("amount", "abc"))).size());
        assertEquals(0, rules.check(Rec.of("txn", Rec.of("amount", 5))).size());
    }

    @Test
    void forEachRunsNestedStepsPerItemAndStopsWhenTheFlowEnds() {
        Forge.Build build = Forge.build(List.of(
                ModelSource.parse("d.yaml", "kind: DataSet\nname: t.Seen\ncollection: seen\nkey: ref\n"),
                ModelSource.parse("f.yaml", """
                        kind: Flow
                        name: t.Loop
                        steps:
                          - id: each
                            type: forEach
                            in: order.lines
                            as: line
                            steps:
                              - {id: tooBig, type: end, when: line.qty > 100, status: REJECTED, code: QTY, message: too many}
                              - id: remember
                                type: save
                                dataset: t.Seen
                                values: {ref: line.ref, qty: line.qty}
                          - {id: after, type: set, values: {order.done: "true"}}
                        """)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        MemoryDataAccess data = new MemoryDataAccess(build.registry(), null);
        Rec scope = Rec.of("order", Rec.of("lines", List.of(Rec.of("ref", "A", "qty", 1), Rec.of("ref", "B", "qty", 2))));
        Rec result = TestRunner.execute(build.registry(), "t.Loop", scope, n -> null, data);
        assertEquals("COMPLETED", result.str("status"));
        assertEquals(2, io.orvanta.core.expr.Ops.list(data.contents().get("t.Seen")).size());
        assertEquals(Boolean.TRUE, scope.at("order.done"));

        MemoryDataAccess second = new MemoryDataAccess(build.registry(), null);
        Rec stops = Rec.of("order", Rec.of("lines", List.of(Rec.of("ref", "A", "qty", 1), Rec.of("ref", "B", "qty", 500), Rec.of("ref", "C", "qty", 1))));
        Rec rejected = TestRunner.execute(build.registry(), "t.Loop", stops, n -> null, second);
        assertEquals("REJECTED", rejected.str("status"));
        assertEquals(1, io.orvanta.core.expr.Ops.list(second.contents().get("t.Seen")).size(), "nothing after the item that ended the flow");
        assertEquals(null, stops.at("order.done"));
    }

    @Test
    void dataSetAndApiModelsAreChecked() {
        Forge.Build build = Forge.build(List.of(
                ModelSource.parse("a.yaml", "kind: DataSet\nname: t.Users\ncollection: orv_user\nsource: engine.transactions\nkey: id\n"),
                ModelSource.parse("b.yaml", "kind: DataSet\nname: t.Secrets\nsource: engine.users\nkey: id\n"),
                ModelSource.parse("c.yaml", "kind: Api\nname: t.Api\nmethod: GET\npath: /x/{id}\npermission: p\ntarget: t.Missing\n"),
                ModelSource.parse("d.yaml", "kind: Flow\nname: t.F\nsteps:\n  - {id: s, type: find, dataset: t.Nope, into: x}\n")));
        assertFalse(build.ok());
        assertEquals(4, build.problems().size(), () -> String.valueOf(build.problems()));
    }

    @Test
    void modelTextCannotBreakOutOfTheGeneratedCode() {
        // every one of these would inject Java if model text were pasted into the source unescaped
        String hostile = "\"); System.exit(1); // \\u0022); Runtime.getRuntime().halt(1); /* */ \n line2 ${x}";
        Rec def = Rec.of("kind", "RuleSet", "name", "t.Hostile", "rules", List.of(
                Rec.of("id", hostile, "assert", "txn.note == '" + hostile.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'",
                        "code", hostile, "message", hostile)));
        Forge.Build build = Forge.build(List.of(new ModelSource("RuleSet", "t.Hostile", "h.yaml", "", def),
                ModelSource.parse("f.yaml", """
                        kind: Flow
                        name: t.HostileFlow
                        steps:
                          - {id: 'a"); System.exit(1); //', type: end, status: 'X"; System.exit(1); //', code: "\\u0022+System.exit(1)+\\u0022", message: "*/ System.exit(1); /*"}
                        """)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        RuleSet rules = build.registry().require("t.Hostile", RuleSet.class);
        // the text comes back as text: the rule compares against it and reports it, nothing was executed
        assertEquals(0, rules.check(Rec.of("txn", Rec.of("note", hostile))).size());
        assertEquals(hostile, rules.check(Rec.of("txn", Rec.of("note", "other"))).get(0).message());
        Rec result = TestRunner.execute(build.registry(), "t.HostileFlow", new Rec(), n -> null);
        assertEquals("X\"; System.exit(1); //", result.str("status"));
        // YAML turned the escape into real quotation marks; they arrive as text, not as code
        assertEquals("\"+System.exit(1)+\"", result.str("code"));
        // a name is the only model text that becomes an identifier, and it is refused unless it is one
        assertThrows(ModelException.class, () -> ModelSource.parse("x.yaml", "kind: Flow\nname: a.B; System.exit(1)\n"));
    }

    @Test
    void modelNamesAreValidated() {
        assertThrows(ModelException.class, () -> ModelSource.parse("x.yaml", "kind: Flow\nname: has spaces\n"));
        assertThrows(ModelException.class, () -> ModelSource.parse("x.yaml", "name: a.B\n"));
        assertEquals("a.B", ModelSource.parse("x.yaml", "kind: Flow\nname: a.B\n").name());
        assertEquals(Map.of(), Map.of());
    }
}
