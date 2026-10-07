package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Late answers from external systems (a channel of purpose "callback"). The channel's mapping
 * yields entries {transactionId, field, value}; the value is stored on the waiting transaction
 * under that field and processing resumes. A channel may only fill the fields it lists as
 * {@code allowedFields}, so a callback can never overwrite amounts, parties or the status.
 */
public final class CallbackService {

    private final Platform platform;

    public CallbackService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic("callback"), "callback", m -> handle(m.str("id")));
    }

    public void handle(String messageId) {
        DocStore store = platform.store;
        if (!Lifecycle.claim(platform, messageId)) {
            return;
        }
        Rec message = store.get(DocStore.MESSAGE, messageId);
        try {
            Registry registry = platform.deployments.registry();
            Rec channel = registry.config(message.str("channel"));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec answer = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int resumed = 0;
            List<Object> unmatched = new ArrayList<>();
            for (Object e : Ops.list(answer.get("entries"))) {
                Rec entry = (Rec) e;
                String txnId = entry.str("transactionId");
                String field = entry.str("field");
                if (field == null || !Ops.in(field, channel.get("allowedFields")) || !(entry.get("value") instanceof Rec value)) {
                    unmatched.add(Rec.of("transactionId", txnId, "problem", "field '" + field + "' may not be set by this channel, or the value is missing"));
                    continue;
                }
                if (txnId == null || !store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.WAITING),
                        Rec.of(field, value, "status", Status.CREATED, "updatedAt", Platform.now()))) {
                    Rec txn = txnId == null ? null : store.get(DocStore.TXN, txnId);
                    unmatched.add(Rec.of("transactionId", txnId, "problem",
                            txn == null ? "no such transaction" : "transaction is " + txn.str("status") + ", not waiting for an answer"));
                    continue;
                }
                resumed++;
                platform.event(txnId, "ANSWER_RECEIVED", field + " answered in " + messageId, null, null);
                platform.bus.publish(Bus.TXN_CREATED, Rec.of("id", txnId));
            }
            Lifecycle.finish(platform, messageId, Rec.of("resumedCount", resumed, "unmatched", unmatched),
                    resumed + " resumed, " + unmatched.size() + " unmatched");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }
}
