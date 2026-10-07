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

/** The wait step of a flow: the flow stops for an answer or a time, and goes on when the answer is there. */
class WaitStepTest {

    private static final String FLOW = """
            kind: Flow
            name: t.Waits
            completeStatus: ROUTED
            steps:
              - id: answer
                type: wait
                for: txn.checks.fraud
                code: FRAUD
                message: Waiting for the fraud result
                retrySeconds: 30
                timeoutSeconds: 600
              - id: after
                type: set
                values: {txn.done: "'yes'"}
            """;

    @Test
    void theFlowStopsUntilTheAnswerIsThereAndGoesOnWhenItIs() {
        Forge.Build build = Forge.build(List.of(ModelSource.parse("f.yaml", FLOW)));
        assertTrue(build.ok(), () -> String.valueOf(build.problems()));
        Flow flow = build.registry().require("t.Waits", Flow.class);

        Rec waiting = Rec.of("txn", Rec.of("id", "T1", "checks", new Rec()));
        Outcome out = flow.run(waiting, new FlowContext(build.registry(), name -> null));
        assertEquals("WAIT", out.status());
        assertEquals("FRAUD", out.code());
        assertEquals("Waiting for the fraud result", out.message());
        assertEquals(30L, waiting.at("txn.wait.retrySeconds"), "the engine runs the flow again after this");
        assertEquals(600L, waiting.at("txn.wait.timeoutSeconds"), "and gives up after this");
        assertNull(waiting.at("txn.done"), "the steps after the wait do not run");

        // run again once the answer has been filled in: nothing to wait for
        Rec answered = Rec.of("txn", Rec.of("id", "T1", "checks", Rec.of("fraud", Rec.of("status", "CLEAR"))));
        Outcome again = flow.run(answered, new FlowContext(build.registry(), name -> null));
        assertFalse("WAIT".equals(again.status()), String.valueOf(again));
        assertEquals("yes", answered.at("txn.done"));
        assertNull(answered.at("txn.wait"), "no wait record when nothing was waited for");
    }

    @Test
    void aWaitStepNeedsACodeAndSensibleTimes() {
        Forge.Build noCode = Forge.build(List.of(ModelSource.parse("f.yaml", "kind: Flow\nname: t.W\nsteps:\n  - {id: w, type: wait}\n")));
        assertFalse(noCode.ok());
        Forge.Build badTime = Forge.build(List.of(ModelSource.parse("f.yaml", "kind: Flow\nname: t.W\nsteps:\n  - {id: w, type: wait, code: X, retrySeconds: 0}\n")));
        assertFalse(badTime.ok());
        assertTrue(badTime.problems().get(0).message().contains("above zero"), badTime.problems().toString());
    }
}
