package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.util.Set;

/** Steps shared by the services that handle inbound lifecycle messages. */
public final class Lifecycle {

    /** Statuses after which a transaction changes only through a later lifecycle event (cancellation, return). */
    public static final Set<String> FINAL = Set.of(Status.ACCEPTED, Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL,
            Status.CANCELLED, Status.RETURNED, Status.CREDITED, Status.DEBITED);

    private Lifecycle() {
    }

    /** Takes a received message for processing; false when another instance already has it. */
    static boolean claim(Platform platform, String messageId) {
        return platform.store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.RECEIVED),
                Rec.of("status", Status.PROCESSING, "claimedAt", Platform.now()));
    }

    static void finish(Platform platform, String messageId, Rec fields, String summary) {
        fields.put("status", Status.PROCESSED);
        fields.put("processedAt", Platform.now());
        platform.store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.PROCESSING), fields);
        platform.event(messageId, Status.PROCESSED, summary, null, null);
    }

    static void fail(Platform platform, String messageId, RuntimeException e) {
        platform.store.updateIf(DocStore.MESSAGE, messageId, Rec.of("status", Status.PROCESSING),
                Rec.of("status", Status.REJECTED, "reasonCode", "PROCESSING_ERROR", "reasonText", String.valueOf(e.getMessage())));
        platform.event(messageId, Status.REJECTED, "PROCESSING_ERROR: " + e.getMessage(), null, null);
    }

    /** The payment did not go through after all; whatever was posted for it has to be reversed. */
    static void unwound(Platform platform, String txnId) {
        platform.bus.publish(io.orvanta.pay.kernel.Bus.TXN_UNWOUND, Rec.of("id", txnId));
    }

    /** A transaction of the instruction changed after it was reported: the customer is told again. */
    static void reopenReport(Platform platform, String instructionId) {
        if (instructionId != null) {
            Rec instruction = platform.store.get(DocStore.MESSAGE, instructionId);
            if (instruction != null && instruction.str("reportState") != null) {
                platform.store.updateIf(DocStore.MESSAGE, instructionId, new Rec(), Rec.of("reportState", "OPEN"));
            }
        }
    }
}
