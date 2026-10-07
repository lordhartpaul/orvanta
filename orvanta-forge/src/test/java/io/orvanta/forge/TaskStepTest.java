package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Outcome;
import io.orvanta.core.flow.FlowContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The task step of a flow: a payment handed to people in an operator queue, and the flow going on once they released it. */
class TaskStepTest {

    private static final String FLOW = """
            kind: Flow
            name: t.Tasks
            completeStatus: ROUTED
            steps:
              - id: bigOnes
                type: task
                when: txn.amount > 100000
                code: LARGE
                queue: Treasury desk
                message: A large payment needs a look
                instructions: "concat('Check the funding of ', txn.debtor.account, ' before release')"
              - id: after
                type: set
                values: {txn.done: "'yes'"}
            """;

    @Test
    void aPaymentIsHeldForAQueueWithInstructionsAndGoesOnOnceReleased() {
        Forge.Build build = Forge.build(List.of(ModelSource.parse("f.yaml", FLOW)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        Flow flow = build.registry().require("t.Tasks", Flow.class);

        Rec held = Rec.of("txn", Rec.of("id", "T1", "amount", 250000, "debtor", Rec.of("account", "4051122334")));
        Outcome out = flow.run(held, new FlowContext(build.registry(), name -> null));
        assertEquals("HOLD", out.status());
        assertEquals("LARGE", out.code());
        assertEquals("A large payment needs a look", out.message());
        assertEquals("Treasury desk", held.at("txn.hold.queue"));
        assertEquals("Check the funding of 4051122334 before release", held.at("txn.hold.instructions"));
        assertNull(held.at("txn.done"));

        // released by a person: the hold code is among the overrides, the task is done, the flow goes on
        Rec released = Rec.of("txn", Rec.of("id", "T1", "amount", 250000, "debtor", Rec.of("account", "4051122334"), "overrides", List.of("LARGE")));
        Outcome again = flow.run(released, new FlowContext(build.registry(), name -> null));
        assertFalse("HOLD".equals(again.status()), String.valueOf(again));
        assertEquals("yes", released.at("txn.done"));
        // a small payment never meets the task
        Rec small = Rec.of("txn", Rec.of("id", "T2", "amount", 5, "debtor", Rec.of("account", "4051122334")));
        flow.run(small, new FlowContext(build.registry(), name -> null));
        assertEquals("yes", small.at("txn.done"));
        assertNull(small.at("txn.hold"));
    }

    @Test
    void aTaskStepNeedsACodeAndAQueue() {
        assertFalse(Forge.build(List.of(ModelSource.parse("f.yaml", "kind: Flow\nname: t.T\nsteps:\n  - {id: t, type: task, queue: Desk}\n"))).ok());
        Forge.Build badQueue = Forge.build(List.of(ModelSource.parse("f.yaml", "kind: Flow\nname: t.T\nsteps:\n  - {id: t, type: task, code: X, queue: 'a/b'}\n")));
        assertFalse(badQueue.ok());
        assertTrue(badQueue.problems().get(0).message().contains("'queue'"), badQueue.problems().toString());
    }
}
