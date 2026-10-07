package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.expr.Time;
import io.orvanta.core.expr.Fn;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Account reports for customers (camt.052): everything this engine booked on one account in a period.
 * Payments the customer sent (a debit, and a credit when the payment came back), payments received,
 * direct debits collected, and their reversals. The report carries entries and their sums, no balances:
 * the balance of an account is kept by the account system, not here.
 */
public final class AccountReports {

    private static final Logger LOG = LoggerFactory.getLogger(AccountReports.class);
    private static volatile long lastDailyCheck;

    private AccountReports() {
    }

    /**
     * The bookings on an account from a moment (inclusive) to a moment (exclusive), oldest first.
     * @param from ISO instant @param to ISO instant
     */
    public static List<Rec> entries(Platform platform, String account, String from, String to) {
        DocStore store = platform.store;
        List<Rec> entries = new ArrayList<>();
        for (Rec txn : store.find(DocStore.TXN, Rec.of("creditor.account", account, "paymentType", Incoming.PAYMENT_TYPE), "id", false, 20000)) {
            String booked = txn.str("creditedAt");
            if (booked != null) {
                add(entries, txn, "CREDIT", true, false, false, "RCDT", booked, from, to);
                reversal(entries, txn, "CREDIT_REVERSAL", false, false, "RCDT", from, to);
            }
        }
        for (Rec txn : store.find(DocStore.TXN, Rec.of("debtor.account", account), "id", false, 20000)) {
            String type = txn.str("paymentType");
            if (Incoming.DEBIT_TYPE.equals(type)) {
                if (txn.str("debitedAt") != null) {
                    add(entries, txn, "DEBIT", false, false, true, "RDDT", txn.str("debitedAt"), from, to);
                    reversal(entries, txn, "DEBIT_REVERSAL", true, true, "RDDT", from, to);
                }
            } else if (type == null || "OUT".equals(type)) {
                // a payment the customer sent: booked when the account was debited
                String status = txn.str("posting.status");
                if ("POSTED".equals(status) || "REVERSED".equals(status)) {
                    add(entries, txn, "PAYMENT", false, false, false, "ICDT", Ops.str(Fn.coalesce(txn.str("posting.postedAt"), txn.str("createdAt"))), from, to);
                    reversal(entries, txn, "PAYMENT_REVERSAL", true, false, "ICDT", from, to);
                }
            }
        }
        entries.sort(Comparator.comparing((Rec e) -> e.str("bookedAt")).thenComparing(e -> e.str("reference")));
        return entries;
    }

    private static void reversal(List<Rec> entries, Rec txn, String kind, boolean credit, boolean collection, String family, String from, String to) {
        if ("REVERSED".equals(txn.str("posting.status"))) {
            add(entries, txn, kind, credit, true, collection, family, Ops.str(Fn.coalesce(txn.str("posting.reversedAt"), txn.str("updatedAt"))), from, to);
        }
    }

    private static void add(List<Rec> entries, Rec txn, String kind, boolean credit, boolean reversal, boolean collection, String family,
                            String bookedAt, String from, String to) {
        if (bookedAt == null || bookedAt.compareTo(from) < 0 || bookedAt.compareTo(to) >= 0) {
            return;
        }
        String channel = String.valueOf(Fn.coalesce(txn.str("channelIn"), txn.at("route.channel"), ""));
        entries.add(Rec.of("kind", kind, "creditDebit", credit ? "CRDT" : "DBIT", "reversal", reversal, "collection", collection,
                "family", family, "subFamily", collection ? "ESDD" : channel.startsWith("rails.swift") ? "XBCT" : "ESCT",
                "txn", txn, "reference", txn.str("id") + (reversal ? "-R" : ""), "bookedAt", bookedAt, "bookedOn", bookedAt.substring(0, 10),
                "amount", amountOf(txn, kind), "currency", currencyOf(txn, kind)));
    }

    /** What moved on the account: for a payment received the amount after conversion and our charge. */
    private static BigDecimal amountOf(Rec txn, String kind) {
        Object amount = kind.startsWith("PAYMENT") ? txn.get("amount") : Fn.coalesce(txn.at("charges.netAmount"), txn.at("fx.creditAmount"), txn.get("amount"));
        return Ops.num(amount);
    }

    private static String currencyOf(Rec txn, String kind) {
        return kind.startsWith("PAYMENT") ? txn.str("currency") : Ops.str(Fn.coalesce(txn.at("fx.creditCurrency"), txn.str("currency")));
    }

    /**
     * Writes the report for an account and a period and hands it to dispatch.
     * @param id the id of the outbound file; a report with that id that exists already is not written again
     * @return the id, or null when a report with that id was there already
     */
    public static String create(Platform platform, String channelName, String account, String from, String to, String id, String requestedBy) {
        Rec channel = platform.deployments.registry().config(channelName);
        if (channel == null || !"outbound".equals(channel.str("direction"))) {
            throw new IllegalStateException("'" + channelName + "' is not an outbound channel");
        }
        List<Rec> entries = entries(platform, account, from, to);
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal debits = BigDecimal.ZERO;
        int creditCount = 0;
        String owner = null;
        String currency = null;
        for (Rec e : entries) {
            boolean credit = "CRDT".equals(e.str("creditDebit"));
            credits = credit ? credits.add(Ops.num(e.get("amount"))) : credits;
            debits = credit ? debits : debits.add(Ops.num(e.get("amount")));
            creditCount += credit ? 1 : 0;
            Rec txn = e.rec("txn");
            owner = Incoming.PAYMENT_TYPE.equals(txn.str("paymentType")) ? txn.str("creditor.name") : txn.str("debtor.name");
            currency = e.str("currency");
        }
        Rec report = Rec.of("id", id, "createdAt", Platform.now().substring(0, 19) + "Z", "account", account, "owner", owner, "currency", currency,
                "from", from.substring(0, 19) + "Z", "to", to.substring(0, 19) + "Z", "count", entries.size(),
                "creditCount", creditCount, "creditSum", credits, "debitCount", entries.size() - creditCount, "debitSum", debits);
        if (channel.get("properties") instanceof Map<?, ?> properties) {
            report.putAll(Rec.from(properties));
        }
        Rec mapped = platform.deployments.registry().require(channel.str("mapping"), Mapping.class).apply(Rec.of("report", report, "entries", new ArrayList<Object>(entries)));
        String payload = Messages.write(channel.str("format"), mapped);
        platform.checks().requireValidOutbound(channel, payload);
        List<Object> ids = new ArrayList<>();
        for (Rec e : entries) {
            if (!ids.contains(e.rec("txn").str("id"))) {
                ids.add(e.rec("txn").str("id"));
            }
        }
        // the transactions are named for looking them up, not under 'transactionIds': sending a report changes none of them
        if (!platform.store.insertIfAbsent(DocStore.OUTBOUND, Rec.of("id", id, "kind", "accountReport", "channel", channelName, "format", channel.str("format"),
                "messageType", channel.str("messageType"), "account", account, "from", from, "to", to, "entryCount", entries.size(), "reportedIds", ids,
                "transactionCount", ids.size(), "requestedBy", requestedBy, "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload))) {
            return null;
        }
        platform.event(id, "CREATED", "account report for " + account + ", " + from.substring(0, 10) + " to " + to.substring(0, 10) + ": "
                + entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies"), null, requestedBy);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", id));
        return id;
    }

    private static volatile String lastClosed = "";

    /** When a day is over the ledger closes it, and the customers who asked for a statement a day get it. */
    private static void endOfDay(Platform platform, String day) {
        if (day.equals(lastClosed) || !platform.config.getBool("ledger.enabled", true)) {
            return;
        }
        try {
            if (platform.store.count(Ledger.ACCOUNT, new Rec()) > 0) {
                Rec closed = new Ledger(platform).closeDay(day);
                if (Ops.num(closed.get("accounts")).intValue() > 0) {
                    LOG.info("ledger day {} closed: {}", day, closed);
                }
                String channel = platform.config.get("accountStatements.channel", "channels.CustomerStatementOutbound");
                String mtChannel = platform.config.get("accountStatements.mtChannel", "channels.CustomerMtStatementOutbound");
                for (String choice : java.util.List.of("STATEMENT", "STATEMENT_MT")) {
                    for (Rec row : platform.data.find(platform.config.get("notify.preferences", "data.NotificationPreferences"), Rec.of("accountReport", choice), null, false, 5000)) {
                        try {
                            AccountStatements.create(platform, choice.endsWith("_MT") ? mtChannel : channel, row.str("account"), day, "daily statement");
                        } catch (RuntimeException e) {
                            LOG.error("daily statement for {} could not be written", row.str("account"), e);
                        }
                    }
                }
            }
            lastClosed = day;
        } catch (RuntimeException e) {
            LOG.error("end of day {} failed", day, e);
        }
    }

    /**
     * The report of yesterday for every account whose customer asked for one a day (accountReport DAILY in
     * the notification preferences). Looked at once a minute; a report that was written is not written again.
     * @return number of reports written
     */
    public static int daily(Platform platform) {
        long every = Math.max(1, platform.config.getInt("accountReports.checkSeconds", 60)) * 1000L;
        if (System.currentTimeMillis() - lastDailyCheck < every) {
            return 0;
        }
        lastDailyCheck = System.currentTimeMillis();
        List<Rec> wanted;
        try {
            wanted = platform.data.find(platform.config.get("notify.preferences", "data.NotificationPreferences"), Rec.of("accountReport", "DAILY"), null, false, 5000);
        } catch (RuntimeException e) {
            return 0;
        }
        LocalDate today = LocalDate.ofInstant(Time.now(), ZoneOffset.UTC);
        endOfDay(platform, today.minusDays(1).toString());
        String from = today.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        String to = today.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        int written = 0;
        for (Rec row : wanted) {
            String account = row.str("account");
            String id = "ORVRPT" + today.minusDays(1).toString().replace("-", "") + account;
            if (platform.store.get(DocStore.OUTBOUND, id) != null) {
                continue;
            }
            try {
                String channel = platform.config.get("accountReports.channel", "channels.CustomerAccountReportOutbound");
                if (create(platform, channel, account, from, to, id, "daily report") != null) {
                    written++;
                }
            } catch (RuntimeException e) {
                LOG.error("daily account report for {} could not be written", account, e);
            }
        }
        return written;
    }
}
