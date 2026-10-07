package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.expr.Time;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform's own ledger: accounts, a journal of double-entry postings, and balances.
 *
 * The journal is the only thing written for a posting, one document with the account debited and the
 * account credited, so a posting is there completely or not at all. A balance is not stored with the
 * account: it is the last closed day plus the entries since, credits minus debits. For a customer
 * account a positive balance is money the customer has; for the mirror of an account we hold at another
 * bank a negative balance is money we hold there.
 *
 * An account has one currency. A posting between two accounts of one currency moves one amount. A
 * posting between accounts of different currencies names two amounts and goes through the bank's
 * position accounts (type POSITION, one per currency): the debit currency is credited to its position
 * account and the credit currency is debited from its own, so every currency balances by itself. It is
 * still one journal entry, with four sides instead of two.
 */
public final class Ledger {

    public static final String ACCOUNT = "orv_ledger_account";
    public static final String ENTRY = "orv_ledger_entry";
    public static final String BALANCE = "orv_ledger_balance";
    public static final List<String> TYPES = List.of("CUSTOMER", "NOSTRO", "VOSTRO", "LORO", "SUSPENSE", "FEE", "POSITION");
    public static final List<String> STATES = List.of("ACTIVE", "BLOCKED", "CLOSED");

    /** One process posts to an account one at a time, so two debits cannot both spend the same money. */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final Platform platform;

    public Ledger(Platform platform) {
        this.platform = platform;
    }

    public Rec account(String id) {
        return id == null ? null : platform.store.get(ACCOUNT, id);
    }

    /** Opens an account, or changes the name, state or overdraft limit of one that exists. Type and currency stay as opened. */
    public Rec open(Rec wanted, String by) {
        String id = wanted.str("id");
        if (id == null || !id.matches("[A-Za-z0-9]{5,34}")) {
            throw new IllegalArgumentException("an account number is 5 to 34 letters or digits");
        }
        Rec existing = account(id);
        String status = wanted.str("status") == null ? "ACTIVE" : wanted.str("status");
        if (!STATES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + STATES);
        }
        BigDecimal overdraft = wanted.get("overdraftLimit") == null ? BigDecimal.ZERO : Ops.num(wanted.get("overdraftLimit"));
        if (overdraft.signum() < 0) {
            throw new IllegalArgumentException("the overdraft limit cannot be negative");
        }
        if (existing == null) {
            if (!TYPES.contains(String.valueOf(wanted.str("type")))) {
                throw new IllegalArgumentException("type must be one of " + TYPES);
            }
            if (wanted.str("currency") == null || !wanted.str("currency").matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be three capital letters");
            }
            if (wanted.str("name") == null || wanted.str("name").isBlank()) {
                throw new IllegalArgumentException("an account has a name");
            }
            platform.store.insertIfAbsent(ACCOUNT, Rec.of("id", id, "name", wanted.str("name"), "type", wanted.str("type"), "currency", wanted.str("currency"),
                    "status", status, "overdraftLimit", overdraft, "openedAt", Platform.now(), "openedBy", by, "updatedAt", Platform.now()));
        } else {
            if (wanted.str("type") != null && !wanted.str("type").equals(existing.str("type"))
                    || wanted.str("currency") != null && !wanted.str("currency").equals(existing.str("currency"))) {
                throw new IllegalArgumentException("the type and the currency of an account cannot be changed");
            }
            if ("CLOSED".equals(status) && balance(id).signum() != 0) {
                throw new IllegalArgumentException("an account with a balance cannot be closed");
            }
            platform.store.updateIf(ACCOUNT, id, new Rec(), Rec.of("name", wanted.str("name") == null ? existing.str("name") : wanted.str("name"),
                    "status", status, "overdraftLimit", overdraft, "updatedAt", Platform.now(), "updatedBy", by));
        }
        return account(id);
    }

    /** What a flow asks before using an account: the same answer an account system gives. */
    public Rec lookup(String id) {
        Rec account = account(id);
        if (account == null) {
            return Rec.of("account", id, "status", "UNKNOWN");
        }
        return Rec.of("account", id, "status", account.str("status"), "currency", account.str("currency"), "type", account.str("type"), "name", account.str("name"));
    }

    /**
     * Books one amount from one account to another. The idempotency key names the posting: asked again
     * with the same key, the answer is the posting that was made.
     * @return status POSTED with postingId, or status REFUSED with a reason
     */
    public Rec post(Rec request) {
        String key = required(request, "idempotencyKey");
        String id = "LP-" + key;
        String debit = required(request, "debitAccount");
        String credit = required(request, "creditAccount");
        BigDecimal amount = Ops.num(request.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("the amount of a posting is more than zero");
        }
        String currency = required(request, "currency");
        synchronized (LOCKS.computeIfAbsent(debit, k -> new Object())) {
            Rec made = platform.store.get(ENTRY, id);
            if (made != null) {
                return posted(made);
            }
            Rec from = account(debit);
            Rec to = account(credit);
            if (from == null || to == null) {
                return refused("UNKNOWN_ACCOUNT", from == null ? debit : credit);
            }
            if (debit.equals(credit)) {
                return refused("SAME_ACCOUNT", debit);
            }
            if (!"ACTIVE".equals(from.str("status"))) {
                return refused("ACCOUNT_" + from.str("status"), debit);
            }
            if ("CLOSED".equals(to.str("status"))) {
                return refused("ACCOUNT_CLOSED", credit);
            }
            if (!currency.equals(from.str("currency"))) {
                return refused("CURRENCY_MISMATCH", debit);
            }
            // two currencies: the amount credited is named too, and the bank's position accounts take the other sides
            String creditCurrency = request.str("creditCurrency") == null ? currency : request.str("creditCurrency");
            BigDecimal creditAmount = currency.equals(creditCurrency) ? amount : request.get("creditAmount") == null ? null : Ops.num(request.get("creditAmount"));
            if (!creditCurrency.equals(to.str("currency")) || creditAmount == null || creditAmount.signum() <= 0) {
                return refused("CURRENCY_MISMATCH", credit);
            }
            String positionIn = null;
            String positionOut = null;
            if (!currency.equals(creditCurrency)) {
                positionIn = position(currency);
                positionOut = position(creditCurrency);
                if (positionIn == null || positionOut == null) {
                    return refused("NO_POSITION_ACCOUNT", positionIn == null ? currency : creditCurrency);
                }
            }
            // a fee charged with the payment: debited from the same account, credited to a fee account, in the account's currency
            BigDecimal fee = request.get("feeAmount") == null ? null : Ops.num(request.get("feeAmount"));
            String feeAccount = request.str("feeAccount");
            if (fee != null && fee.signum() <= 0) {
                fee = null;
            }
            if (fee != null) {
                Rec fees = feeAccount == null ? null : account(feeAccount);
                if (fees == null || !"FEE".equals(fees.str("type")) || !"ACTIVE".equals(fees.str("status")) || !currency.equals(fees.str("currency"))) {
                    return refused("NO_FEE_ACCOUNT", feeAccount == null ? "(none)" : feeAccount);
                }
            }
            // the value date: the day the amount counts from; the booking day unless the posting says otherwise
            String valueDate = request.str("valueDate") == null ? today() : request.str("valueDate");
            try {
                LocalDate.parse(valueDate);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("'valueDate' is a date like 2026-10-07");
            }
            // only a customer account is held to what is on it; the bank's own accounts may go either way
            BigDecimal needed = fee == null ? amount : amount.add(fee);
            if ("CUSTOMER".equals(from.str("type")) && balance(debit).add(Ops.num(from.get("overdraftLimit"))).compareTo(needed) < 0) {
                return refused("INSUFFICIENT_FUNDS", debit);
            }
            Rec entry = Rec.of("id", id, "key", key, "at", Platform.now(), "bookDate", today(), "valueDate", valueDate, "reference", request.str("reference"),
                    "text", request.str("text"), "debitAccount", debit, "creditAccount", credit, "amount", amount, "currency", currency);
            if (positionIn != null) {
                entry.put("creditAmount", creditAmount);
                entry.put("creditCurrency", creditCurrency);
                entry.put("positionCreditAccount", positionIn);
                entry.put("positionDebitAccount", positionOut);
            }
            if (fee != null) {
                entry.put("feeEntry", id + "-FEE");
            }
            platform.store.insertIfAbsent(ENTRY, entry);
            if (fee != null) {
                // the fee is an entry of its own, so statements and the fee account show it as what it is
                platform.store.insertIfAbsent(ENTRY, Rec.of("id", id + "-FEE", "key", key + "-FEE", "at", Platform.now(), "bookDate", today(), "valueDate", valueDate,
                        "reference", request.str("reference"), "text", request.str("feeText") == null ? "Fee" : request.str("feeText"),
                        "debitAccount", debit, "creditAccount", feeAccount, "amount", fee, "currency", currency, "feeOf", id));
            }
            platform.changed("ledger");
            return posted(platform.store.get(ENTRY, id));
        }
    }

    /** Takes a posting back with an entry the other way round. Asked again with the same key, nothing more is booked. */
    public Rec reverse(Rec request) {
        String key = required(request, "idempotencyKey");
        String id = "LP-" + key;
        Rec made = platform.store.get(ENTRY, id);
        if (made == null) {
            Rec original = platform.store.get(ENTRY, required(request, "postingId"));
            if (original == null) {
                throw new IllegalArgumentException("the ledger has no posting " + request.str("postingId"));
            }
            if (original.str("reversedBy") != null) {
                throw new IllegalArgumentException("posting " + original.str("id") + " was reversed already, by " + original.str("reversedBy"));
            }
            Rec back = Rec.of("id", id, "key", key, "at", Platform.now(), "bookDate", today(), "valueDate", original.str("valueDate") == null ? today() : original.str("valueDate"),
                    "reference", request.str("reference"),
                    "text", request.str("reason"), "debitAccount", original.str("creditAccount"), "creditAccount", original.str("debitAccount"),
                    "amount", original.get("amount"), "currency", original.str("currency"), "reversalOf", original.str("id"));
            if (original.str("positionCreditAccount") != null) {
                // every side the other way round: what was debited is credited with the same amount in the same currency
                back.put("amount", original.get("creditAmount"));
                back.put("currency", original.str("creditCurrency"));
                back.put("creditAmount", original.get("amount"));
                back.put("creditCurrency", original.str("currency"));
                back.put("positionCreditAccount", original.str("positionDebitAccount"));
                back.put("positionDebitAccount", original.str("positionCreditAccount"));
            }
            platform.store.insertIfAbsent(ENTRY, back);
            platform.store.updateIf(ENTRY, original.str("id"), new Rec(), Rec.of("reversedBy", id));
            // the fee charged with the posting goes back as well
            Rec fee = original.str("feeEntry") == null ? null : platform.store.get(ENTRY, original.str("feeEntry"));
            if (fee != null && fee.str("reversedBy") == null) {
                platform.store.insertIfAbsent(ENTRY, Rec.of("id", id + "-FEE", "key", key + "-FEE", "at", Platform.now(), "bookDate", today(),
                        "valueDate", fee.str("valueDate") == null ? today() : fee.str("valueDate"), "reference", request.str("reference"), "text", "Fee returned",
                        "debitAccount", fee.str("creditAccount"), "creditAccount", fee.str("debitAccount"), "amount", fee.get("amount"), "currency", fee.str("currency"),
                        "reversalOf", fee.str("id")));
                platform.store.updateIf(ENTRY, fee.str("id"), new Rec(), Rec.of("reversedBy", id + "-FEE"));
            }
            platform.changed("ledger");
            made = platform.store.get(ENTRY, id);
        }
        return Rec.of("status", "REVERSED", "reversalId", made.str("id"), "postingId", made.str("reversalOf"));
    }

    private static Rec posted(Rec entry) {
        Rec answer = Rec.of("status", "POSTED", "postingId", entry.str("id"), "debitAccount", entry.str("debitAccount"), "creditAccount", entry.str("creditAccount"),
                "amount", entry.get("amount"), "currency", entry.str("currency"), "postedAt", entry.str("at"));
        if (entry.get("creditAmount") != null) {
            answer.put("creditAmount", entry.get("creditAmount"));
            answer.put("creditCurrency", entry.str("creditCurrency"));
        }
        answer.put("valueDate", entry.str("valueDate"));
        if (entry.str("feeEntry") != null) {
            answer.put("feeEntry", entry.str("feeEntry"));
        }
        return answer;
    }

    /** The bank's position account for a currency: the active account of type POSITION in it, or null. */
    private String position(String currency) {
        List<Rec> found = platform.store.find(ACCOUNT, Rec.of("type", "POSITION", "currency", currency, "status", "ACTIVE"), "id", false, 1);
        return found.isEmpty() ? null : found.get(0).str("id");
    }

    /**
     * What an entry does to an account: plus for a credit, minus for a debit, in the account's currency.
     * An entry in two currencies has four sides; an account is on one of them.
     */
    public static BigDecimal effect(Rec e, String account) {
        BigDecimal amount = Ops.num(e.get("amount"));
        BigDecimal credited = e.get("creditAmount") == null ? amount : Ops.num(e.get("creditAmount"));
        BigDecimal effect = BigDecimal.ZERO;
        if (account.equals(e.str("debitAccount"))) {
            effect = effect.subtract(amount);
        }
        if (account.equals(e.str("positionCreditAccount"))) {
            effect = effect.add(amount);
        }
        if (account.equals(e.str("positionDebitAccount"))) {
            effect = effect.subtract(credited);
        }
        if (account.equals(e.str("creditAccount"))) {
            effect = effect.add(credited);
        }
        return effect;
    }

    /** The account on the other side of an entry, as an account sees it. */
    public static String other(Rec e, String account) {
        if (account.equals(e.str("debitAccount"))) {
            return e.str("positionCreditAccount") != null ? e.str("positionCreditAccount") : e.str("creditAccount");
        }
        if (account.equals(e.str("creditAccount"))) {
            return e.str("positionDebitAccount") != null ? e.str("positionDebitAccount") : e.str("debitAccount");
        }
        return account.equals(e.str("positionCreditAccount")) ? e.str("debitAccount") : e.str("creditAccount");
    }

    private static Rec refused(String reason, String account) {
        return Rec.of("status", "REFUSED", "reason", reason, "account", account);
    }

    private static String required(Rec request, String field) {
        String value = request.str(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' is required");
        }
        return value;
    }

    private static String today() {
        return LocalDate.ofInstant(Time.now(), ZoneOffset.UTC).toString();
    }

    /** The balance now. */
    public BigDecimal balance(String account) {
        return balanceAtEndOf(account, "9999-12-31");
    }

    /** The balance when a day was over: the last closed day up to it, and the entries booked after that day up to this one. */
    public BigDecimal balanceAtEndOf(String account, String date) {
        BigDecimal balance = BigDecimal.ZERO;
        String after = "";
        for (Rec closed : platform.store.find(BALANCE, Rec.of("account", account), "date", true, 4000)) {
            if (closed.str("date").compareTo(date) <= 0) {
                balance = Ops.num(closed.get("closing"));
                after = closed.str("date");
                break;
            }
        }
        for (Rec e : entries(account, after.isEmpty() ? null : LocalDate.parse(after).plusDays(1).toString(), date)) {
            balance = balance.add(effect(e, account));
        }
        return balance;
    }

    /** The balance by value date: every entry whose value date is on or before the day, whenever it was booked. */
    public BigDecimal valueBalance(String account, String valueDate) {
        BigDecimal balance = BigDecimal.ZERO;
        for (Rec e : entries(account, null, null)) {
            String v = e.str("valueDate") == null ? e.str("bookDate") : e.str("valueDate");
            if (v.compareTo(valueDate) <= 0) {
                balance = balance.add(effect(e, account));
            }
        }
        return balance;
    }

    /** Every entry booked on a day, oldest first: the day's journal for the general ledger. */
    public List<Rec> journal(String bookDate) {
        List<Rec> all = new ArrayList<>(platform.store.find(ENTRY, Rec.of("bookDate", bookDate), "at", false, 200_000));
        all.sort(Comparator.comparing((Rec e) -> e.str("at")).thenComparing(e -> e.str("id")));
        return all;
    }

    /** The entries of an account booked from a day to a day (both included; null for no limit), oldest first. */
    public List<Rec> entries(String account, String fromDate, String toDate) {
        Map<String, Object[]> ranges = new LinkedHashMap<>();
        if (fromDate != null || toDate != null) {
            ranges.put("bookDate", new Object[] {fromDate, toDate});
        }
        List<Rec> all = new ArrayList<>();
        for (String side : List.of("debitAccount", "creditAccount", "positionCreditAccount", "positionDebitAccount")) {
            all.addAll(platform.store.page(ENTRY, new DocStore.Query(Rec.of(side, account), null, null, ranges, "at", false, 0, 200_000)).items());
        }
        all.sort(Comparator.comparing((Rec e) -> e.str("at")).thenComparing(e -> e.str("id")));
        return all;
    }

    /**
     * Closes a day that is over: writes, for every account, the balance it opened and closed the day with.
     * Later balances start from there instead of from the first entry ever. A day that is closed stays as it is.
     * @return how many accounts were closed and how many of them moved that day
     */
    public Rec closeDay(String date) {
        if (date.compareTo(today()) >= 0) {
            throw new IllegalArgumentException("only a day that is over can be closed");
        }
        int closed = 0;
        int moved = 0;
        for (Rec account : platform.store.find(ACCOUNT, new Rec(), "id", false, 200_000)) {
            String id = account.str("id");
            if (platform.store.get(BALANCE, id + "|" + date) != null) {
                continue;
            }
            BigDecimal opening = balanceAtEndOf(id, LocalDate.parse(date).minusDays(1).toString());
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            List<Rec> entries = entries(id, date, date);
            for (Rec e : entries) {
                BigDecimal effect = effect(e, id);
                credits = effect.signum() > 0 ? credits.add(effect) : credits;
                debits = effect.signum() > 0 ? debits : debits.add(effect.negate());
            }
            if (platform.store.insertIfAbsent(BALANCE, Rec.of("id", id + "|" + date, "account", id, "date", date, "currency", account.str("currency"),
                    "opening", opening, "debits", debits, "credits", credits, "closing", opening.subtract(debits).add(credits),
                    "entryCount", entries.size(), "closedAt", Platform.now()))) {
                closed++;
                moved += entries.isEmpty() ? 0 : 1;
            }
        }
        return Rec.of("date", date, "accounts", closed, "accountsWithEntries", moved);
    }
}
