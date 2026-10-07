package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Account statements from correspondents and clearing systems (camt.053, MT940 on a channel of
 * purpose "statement") and their reconciliation against the payments we sent.
 *
 * The channel's mapping yields statements with entries {reference, amount, currency, creditDebit,
 * valueDate}. Each entry is matched to the transaction whose id is its reference:
 * <ul>
 * <li>MATCHED: a debit of the same amount and currency. The transaction is marked reconciled; on a
 *     channel with {@code settles: true} a payment still waiting as SENT becomes ACCEPTED, because the
 *     debit on our account is the proof that it went through.</li>
 * <li>MISMATCH: the transaction exists but amount, currency or direction differ.</li>
 * <li>UNMATCHED: no transaction with that reference; the entry is something else (charges, incoming funds).</li>
 * </ul>
 * The statement's own arithmetic is checked too: opening balance plus entries must give the closing balance.
 */
public final class StatementService {

    private final Platform platform;

    public StatementService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.inboundTopic("statement"), "reconcile", m -> handle(m.str("id")));
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
            boolean settles = "true".equalsIgnoreCase(String.valueOf(channel.get("settles")));
            Rec tree = Messages.parse(message.str("raw")).get(0).tree();
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("src", tree));
            int matched = 0;
            int mismatched = 0;
            int unmatched = 0;
            int number = 0;
            for (Object s : Ops.list(mapped.get("statements"))) {
                Rec statement = ((Rec) s).copy();
                String statementDocId = messageId + "-" + ++number;
                BigDecimal movement = BigDecimal.ZERO;
                List<Object> entries = new ArrayList<>();
                for (Object e : Ops.list(statement.get("entries"))) {
                    Rec entry = (Rec) e;
                    BigDecimal amount = Ops.blank(entry.get("amount")) ? BigDecimal.ZERO : Ops.num(entry.get("amount"));
                    boolean debit = "DBIT".equals(entry.str("creditDebit"));
                    movement = debit ? movement.subtract(amount) : movement.add(amount);

                    String reference = entry.str("reference");
                    Rec txn = reference == null ? null : store.get(DocStore.TXN, reference.trim());
                    if (txn == null) {
                        entry.put("status", "UNMATCHED");
                        unmatched++;
                    } else if (!debit || Ops.blank(txn.get("amount")) || amount.compareTo(Ops.num(txn.get("amount"))) != 0
                            || !String.valueOf(txn.str("currency")).equals(String.valueOf(entry.str("currency") == null ? statement.str("currency") : entry.str("currency")))) {
                        entry.put("status", "MISMATCH");
                        entry.put("transactionId", txn.str("id"));
                        entry.put("problem", "the payment is a debit of " + Ops.str(txn.get("amount")) + " " + txn.str("currency")
                                + "; the statement shows a " + (debit ? "debit" : "credit") + " of " + amount.toPlainString());
                        mismatched++;
                        platform.event(txn.str("id"), "RECONCILIATION_MISMATCH", "statement " + statementDocId + ": " + entry.str("problem"), null, null);
                    } else {
                        entry.put("status", "MATCHED");
                        entry.put("transactionId", txn.str("id"));
                        matched++;
                        store.updateIf(DocStore.TXN, txn.str("id"), new Rec(), Rec.of("reconciliation", Rec.of("status", "MATCHED",
                                "statementId", statementDocId, "valueDate", entry.str("valueDate"), "at", Platform.now())));
                        platform.event(txn.str("id"), "RECONCILED", "debited on " + statement.str("account") + " in statement " + statementDocId, null, null);
                        if (settles && store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.SENT), Rec.of("status", Status.ACCEPTED,
                                "externalStatus", "STATEMENT", "acknowledgementId", messageId, "updatedAt", Platform.now()))) {
                            platform.event(txn.str("id"), Status.ACCEPTED, "settled: debit confirmed by statement " + statementDocId, null, null);
                            Lifecycle.reopenReport(platform, txn.str("instructionId"));
                        }
                    }
                    entries.add(entry);
                }
                // opening balance plus the entries must give the closing balance
                String balanceCheck = "NOT_CHECKED";
                if (!Ops.blank(statement.get("openingBalance")) && !Ops.blank(statement.get("closingBalance"))) {
                    balanceCheck = Ops.num(statement.get("openingBalance")).add(movement).compareTo(Ops.num(statement.get("closingBalance"))) == 0
                            ? "OK" : "FAILED";
                }
                statement.put("id", statementDocId);
                statement.put("messageId", messageId);
                statement.put("channel", message.str("channel"));
                statement.put("entries", entries);
                statement.put("entryCount", entries.size());
                statement.put("movement", movement);
                statement.put("balanceCheck", balanceCheck);
                statement.put("receivedAt", message.str("receivedAt"));
                store.save(DocStore.STATEMENT, statement);
            }
            Lifecycle.finish(platform, messageId, Rec.of("statementCount", number, "matchedCount", matched,
                    "mismatchedCount", mismatched, "unmatchedCount", unmatched),
                    number + " statement(s): " + matched + " matched, " + mismatched + " mismatched, " + unmatched + " unmatched");
        } catch (RuntimeException e) {
            Lifecycle.fail(platform, messageId, e);
        }
    }

    /**
     * A person matches an entry the engine could not: an entry whose reference was not a payment's id, or
     * whose amount differed (charges taken off, a rounding). The payment is marked reconciled with the
     * statement, and on a settling channel a payment still SENT becomes ACCEPTED, as an automatic match does.
     * @param index the entry's position in the statement, from 0
     */
    public Rec matchByHand(String statementId, int index, String txnId, String actor, String note) {
        DocStore store = platform.store;
        Rec statement = store.get(DocStore.STATEMENT, statementId);
        if (statement == null) {
            throw new IllegalStateException("no statement " + statementId);
        }
        List<Object> entries = new ArrayList<>(Ops.list(statement.get("entries")));
        if (index < 0 || index >= entries.size()) {
            throw new IllegalStateException("statement " + statementId + " has no entry " + index);
        }
        Rec entry = ((Rec) entries.get(index)).copy();
        if ("MATCHED".equals(entry.str("status"))) {
            throw new IllegalStateException("entry " + index + " is matched already, to " + entry.str("transactionId"));
        }
        Rec txn = store.get(DocStore.TXN, txnId);
        if (txn == null) {
            throw new IllegalStateException("no payment " + txnId);
        }
        if (txn.at("reconciliation.status") != null && "MATCHED".equals(txn.str("reconciliation.status"))) {
            throw new IllegalStateException("payment " + txnId + " is reconciled already, with statement " + txn.str("reconciliation.statementId"));
        }
        entry.put("status", "MATCHED");
        entry.put("transactionId", txnId);
        entry.put("matchedBy", actor);
        entry.put("matchedAt", Platform.now());
        entry.set("note", note);
        entry.remove("problem");
        entries.set(index, entry);
        store.updateIf(DocStore.STATEMENT, statementId, new Rec(), Rec.of("entries", entries, "updatedAt", Platform.now()));
        store.updateIf(DocStore.TXN, txnId, new Rec(), Rec.of("reconciliation", Rec.of("status", "MATCHED", "statementId", statementId,
                "valueDate", entry.str("valueDate"), "at", Platform.now(), "by", actor, "byHand", true)));
        platform.event(txnId, "RECONCILED", "matched by hand to entry " + index + " of statement " + statementId + (note == null ? "" : ": " + note), null, actor);
        Rec channel = platform.deployments.registry().config(String.valueOf(statement.str("channel")));
        boolean settles = channel != null && "true".equalsIgnoreCase(String.valueOf(channel.get("settles")));
        if (settles && store.updateIf(DocStore.TXN, txnId, Rec.of("status", Status.SENT), Rec.of("status", Status.ACCEPTED,
                "externalStatus", "STATEMENT", "acknowledgementId", statement.str("messageId"), "updatedAt", Platform.now()))) {
            platform.event(txnId, Status.ACCEPTED, "settled: debit confirmed by statement " + statementId + ", matched by hand", null, actor);
            Lifecycle.reopenReport(platform, txn.str("instructionId"));
        }
        platform.changed("payments");
        return Rec.of("statementId", statementId, "entry", index, "transactionId", txnId);
    }
}
