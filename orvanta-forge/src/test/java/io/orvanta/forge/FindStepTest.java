package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.FlowContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The find step of a flow: conditions besides equality, and pages. */
class FindStepTest {

    private static final ModelSource DATASET = ModelSource.parse("d.yaml", "kind: DataSet\nname: data.Limits\ncollection: limits\nkey: account\n");

    private static List<Rec> rows(Rec scope, String key) {
        return Ops.list(scope.get(key)).stream().map(r -> (Rec) r).toList();
    }

    @Test
    void conditionsAreRangesSetsAndPagesInAFindStep() {
        Forge.Build build = Forge.build(List.of(DATASET, ModelSource.parse("f.yaml", """
                kind: Flow
                name: t.Limits
                steps:
                  - id: big
                    type: find
                    dataset: data.Limits
                    where: {perTransaction: {gte: txn.floor, lt: "500"}}
                    sort: perTransaction
                    into: big
                  - id: some
                    type: find
                    dataset: data.Limits
                    where: {account: {in: txn.accounts}, perTransaction: {ne: "100"}}
                    into: some
                  - id: page2
                    type: find
                    dataset: data.Limits
                    sort: account
                    limit: 2
                    offset: 2
                    into: page2
                  - id: unanswerable
                    type: find
                    dataset: data.Limits
                    where: {perTransaction: {gt: txn.missing}}
                    into: none
                """)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        Rec seed = Rec.of("data.Limits", List.of(
                Rec.of("account", "A1", "perTransaction", 100), Rec.of("account", "A2", "perTransaction", 200), Rec.of("account", "A3", "perTransaction", 300),
                Rec.of("account", "A4", "perTransaction", 400), Rec.of("account", "A5", "perTransaction", 500)));
        MemoryDataAccess data = new MemoryDataAccess(build.registry(), seed);
        Rec scope = Rec.of("txn", Rec.of("floor", 300, "accounts", List.of("A1", "A5")));
        build.registry().require("t.Limits", Flow.class).run(scope, new FlowContext(build.registry(), name -> null, data));
        assertEquals(List.of("A3", "A4"), rows(scope, "big").stream().map(r -> r.str("account")).toList(), "300 <= limit < 500, in order");
        assertEquals(List.of("A5"), rows(scope, "some").stream().map(r -> r.str("account")).toList(), "one of the accounts, not the 100 one");
        assertEquals(List.of("A3", "A4"), rows(scope, "page2").stream().map(r -> r.str("account")).toList(), "the second page of two");
        assertEquals(List.of(), rows(scope, "none"), "a condition without a value finds nothing rather than everything");
    }

    @Test
    void anUnknownConditionIsAProblemOfTheModel() {
        Forge.Build build = Forge.build(List.of(DATASET, ModelSource.parse("f.yaml", """
                kind: Flow
                name: t.Bad
                steps:
                  - id: find
                    type: find
                    dataset: data.Limits
                    where: {perTransaction: {between: txn.floor}}
                    into: x
                """)));
        assertFalse(build.ok());
        assertTrue(build.problems().get(0).message().contains("gt, gte, lt, lte, ne or in"), build.problems().toString());
    }
}
