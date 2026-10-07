package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.expr.Time;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Account statements for customers (camt.053) from the platform's own ledger: the balance an account
 * opened a day with, every entry of the day, and the balance it closed with. Unlike an account report
 * a statement needs the ledger, because only the ledger knows balances.
 */
public final class AccountStatements {

    private AccountStatements() {
    }

    /** The id a statement of an account for a day has: there is one statement per account and day. */
    public static String idOf(String account, String date) {
        return "ORVSTM" + date.replace("-", "") + account;
    }

    /**
     * Writes the statement of an account for a day that is over and hands it to dispatch.
     * @return the id of the outbound file; the same id when the statement was written before
     */
    public static String create(Platform platform, String channelName, String account, String date, String requestedBy) {
        Ledger ledger = new Ledger(platform);
        Rec held = ledger.account(account);
        if (held == null) {
            throw new IllegalStateException("the ledger has no account " + account + "; a statement needs the ledger");
        }
        if (date.compareTo(LocalDate.ofInstant(Time.now(), ZoneOffset.UTC).toString()) >= 0) {
            throw new IllegalStateException("a statement is written for a day that is over");
        }
        Rec channel = platform.deployments.registry().config(channelName);
        if (channel == null || !"outbound".equals(channel.str("direction"))) {
            throw new IllegalStateException("'" + channelName + "' is not an outbound channel");
        }
        // one statement per account, day and format: the MT statement of a day is another file than the ISO 20022 one
        String id = idOf(account, date) + ("swift.mt".equals(channel.str("format")) ? "M" : "");
        if (platform.store.get(DocStore.OUTBOUND, id) != null) {
            return id;
        }
        String yymmdd = date.substring(2).replace("-", "");
        BigDecimal opening = ledger.balanceAtEndOf(account, LocalDate.parse(date).minusDays(1).toString());
        BigDecimal closing = opening;
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        int creditCount = 0;
        List<Object> entries = new ArrayList<>();
        List<Object> ids = new ArrayList<>();
        for (Rec e : ledger.entries(account, date, date)) {
            BigDecimal effect = Ledger.effect(e, account);
            boolean credit = effect.signum() > 0;
            BigDecimal amount = effect.abs();
            closing = closing.add(effect);
            credits = credit ? credits.add(amount) : credits;
            debits = credit ? debits : debits.add(amount);
            creditCount += credit ? 1 : 0;
            // an entry made for a payment carries the payment's details; any other entry only its own text
            Rec txn = e.str("reference") == null ? null : platform.store.get(DocStore.TXN, e.str("reference"));
            boolean reversal = e.str("reversalOf") != null;
            Rec entry = Rec.of("creditDebit", credit ? "CRDT" : "DBIT", "reversal", reversal, "reference", e.str("id"), "bookedOn", e.str("bookDate"),
                    "amount", amount, "currency", held.str("currency"), "text", e.str("text"),
                    "yymmdd", e.str("bookDate").substring(2).replace("-", ""));
            if (txn != null) {
                boolean collection = Incoming.DEBIT_TYPE.equals(txn.str("paymentType"));
                String route = String.valueOf(txn.str("channelIn") != null && txn.str("paymentType") != null ? txn.str("channelIn") : txn.at("route.channel"));
                entry.put("txn", txn);
                entry.put("collection", collection);
                entry.put("domain", "PMNT");
                entry.put("family", collection ? "RDDT" : Incoming.PAYMENT_TYPE.equals(txn.str("paymentType")) ? "RCDT" : "ICDT");
                entry.put("subFamily", collection ? "ESDD" : route.startsWith("rails.swift") ? "XBCT" : "ESCT");
                if (!ids.contains(txn.str("id"))) {
                    ids.add(txn.str("id"));
                }
            } else {
                entry.put("collection", false);
                entry.put("domain", "ACMT");
                entry.put("family", credit ? "MCOP" : "MDOP");
                entry.put("subFamily", "OTHR");
            }
            entries.add(entry);
        }
        Rec statement = Rec.of("id", id, "createdAt", Platform.now().substring(0, 19) + "Z", "account", account, "owner", held.str("name"), "currency", held.str("currency"),
                "date", date, "reference16", "S" + yymmdd + (account.length() > 9 ? account.substring(account.length() - 9) : account),
                "number", LocalDate.parse(date).getDayOfYear(), "from", date + "T00:00:00Z", "to", LocalDate.parse(date).plusDays(1) + "T00:00:00Z", "count", entries.size(),
                "creditCount", creditCount, "creditSum", credits, "debitCount", entries.size() - creditCount, "debitSum", debits);
        if (channel.get("properties") instanceof Map<?, ?> properties) {
            statement.putAll(Rec.from(properties));
        }
        List<Object> balances = new ArrayList<>();
        balances.add(balance("OPBD", opening, held.str("currency"), date));
        balances.add(balance("CLBD", closing, held.str("currency"), date));
        Rec mapped = platform.deployments.registry().require(channel.str("mapping"), Mapping.class)
                .apply(Rec.of("report", statement, "balances", balances, "entries", entries));
        String payload = Messages.write(channel.str("format"), mapped);
        platform.checks().requireValidOutbound(channel, payload);
        if (platform.store.insertIfAbsent(DocStore.OUTBOUND, Rec.of("id", id, "kind", "accountStatement", "channel", channelName, "format", channel.str("format"),
                "messageType", channel.str("messageType"), "account", account, "date", date, "entryCount", entries.size(), "reportedIds", ids,
                "opening", opening, "closing", closing, "transactionCount", ids.size(), "requestedBy", requestedBy, "status", Status.CREATED,
                "createdAt", Platform.now(), "payload", payload))) {
            platform.event(id, "CREATED", "statement of " + account + " for " + date + ": " + entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies")
                    + ", closing balance " + closing + " " + held.str("currency"), null, requestedBy);
            platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", id));
        }
        return id;
    }

    private static Rec balance(String code, BigDecimal amount, String currency, String date) {
        return Rec.of("code", code, "amount", amount.abs(), "currency", currency, "creditDebit", amount.signum() < 0 ? "DBIT" : "CRDT", "date", date,
                "yymmdd", date.substring(2).replace("-", ""));
    }
}
