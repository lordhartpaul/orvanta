package io.orvanta.core.expr;

import io.orvanta.core.data.Rec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Function library of the expression language. Every public static method here is callable
 * from a model by its name; Forge checks names and argument counts by reflection, so adding
 * a function means adding a method.
 */
public final class Fn {

    private Fn() {
    }

    // ---- text ----

    public static Object len(Object v) {
        v = Ops.unwrap(v);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof List<?> l) {
            return BigDecimal.valueOf(l.size());
        }
        return BigDecimal.valueOf(Ops.str(v).length());
    }

    public static Object upper(Object v) {
        String s = Ops.str(v);
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
    }

    public static Object lower(Object v) {
        String s = Ops.str(v);
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    public static Object trim(Object v) {
        String s = Ops.str(v);
        return s == null ? null : s.trim();
    }

    public static Object concat(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            String s = Ops.str(p);
            if (s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** Joins the non-blank parts with a separator: join(' ', a, b, c). */
    public static Object join(Object separator, Object... parts) {
        List<String> kept = new ArrayList<>();
        for (Object p : parts) {
            for (Object item : Ops.list(p)) {
                if (!Ops.blank(item)) {
                    kept.add(Ops.str(item));
                }
            }
        }
        return String.join(Ops.str(separator), kept);
    }

    public static Object substr(Object v, Object start) {
        String s = Ops.str(v);
        if (s == null) {
            return null;
        }
        int from = Math.min(Math.max(Ops.num(start).intValue(), 0), s.length());
        return s.substring(from);
    }

    public static Object substr(Object v, Object start, Object end) {
        String s = Ops.str(v);
        if (s == null) {
            return null;
        }
        int from = Math.min(Math.max(Ops.num(start).intValue(), 0), s.length());
        int to = Math.min(Math.max(Ops.num(end).intValue(), from), s.length());
        return s.substring(from, to);
    }

    public static Object left(Object v, Object count) {
        return substr(v, BigDecimal.ZERO, count);
    }

    /** The last characters of a text: right('LP-ORVTXN0000000012-DEBIT', 5) is 'DEBIT'. */
    public static Object right(Object v, Object count) {
        String s = Ops.str(v);
        if (s == null) {
            return null;
        }
        int n = Math.max(0, Ops.num(count).intValue());
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    public static Object pad(Object v, Object length, Object fill) {
        String s = Ops.str(v) == null ? "" : Ops.str(v);
        int n = Ops.num(length).intValue();
        String f = Ops.str(fill);
        StringBuilder sb = new StringBuilder();
        while (sb.length() + s.length() < n) {
            sb.append(f);
        }
        return sb.append(s).toString();
    }

    public static Object replace(Object v, Object target, Object replacement) {
        String s = Ops.str(v);
        return s == null ? null : s.replace(Ops.str(target), Ops.str(replacement));
    }

    public static Object startsWith(Object v, Object prefix) {
        String s = Ops.str(v);
        return s != null && s.startsWith(Ops.str(prefix));
    }

    public static Object endsWith(Object v, Object suffix) {
        String s = Ops.str(v);
        return s != null && s.endsWith(Ops.str(suffix));
    }

    public static Object contains(Object v, Object part) {
        v = Ops.unwrap(v);
        if (v instanceof List<?>) {
            return Ops.in(part, v);
        }
        String s = Ops.str(v);
        return s != null && s.contains(Ops.str(part));
    }

    public static Object matches(Object v, Object regex) {
        String s = Ops.str(v);
        return s != null && Pattern.matches(Ops.str(regex), s);
    }

    public static Object str(Object v) {
        return Ops.str(v);
    }

    public static Object uuid() {
        return UUID.randomUUID().toString();
    }

    // ---- presence ----

    public static Object exists(Object v) {
        return !Ops.blank(v);
    }

    public static Object empty(Object v) {
        return Ops.blank(v);
    }

    public static Object coalesce(Object... candidates) {
        for (Object c : candidates) {
            if (!Ops.blank(c)) {
                return Ops.unwrap(c);
            }
        }
        return null;
    }

    // ---- numbers ----

    /** Number or null when the value is missing. */
    public static Object num(Object v) {
        return Ops.blank(v) ? null : Ops.num(v);
    }

    public static Object round(Object v, Object scale) {
        return Ops.num(v).setScale(Ops.num(scale).intValue(), RoundingMode.HALF_EVEN);
    }

    public static Object abs(Object v) {
        return Ops.num(v).abs();
    }

    // ---- lists ----

    public static Object count(Object list) {
        return BigDecimal.valueOf(Ops.list(list).size());
    }

    /** Flattens one level: collect(batches, 'txns') gives every transaction of every batch. */
    public static Object collect(Object list, Object key) {
        List<Object> out = new ArrayList<>();
        for (Object item : Ops.list(list)) {
            out.addAll(Ops.list(Ops.get(item, Ops.str(key))));
        }
        return out;
    }

    public static Object sumOf(Object list, Object key) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object item : Ops.list(list)) {
            Object v = Ops.get(item, Ops.str(key));
            if (!Ops.blank(v)) {
                total = total.add(Ops.num(v));
            }
        }
        return total;
    }

    /** The items of a list whose field is one of the values: where(txns, 'status', ['ROUTED', 'SENT']). */
    public static Object where(Object list, Object path, Object values) {
        List<Object> out = new ArrayList<>();
        for (Object item : Ops.list(list)) {
            if (Ops.in(Ops.get(item, Ops.str(path)), values)) {
                out.add(item);
            }
        }
        return out;
    }

    /** The items of a list whose field is none of the values: without(txns, 'status', ['REJECTED_BY_APPLICATION']). */
    public static Object without(Object list, Object path, Object values) {
        List<Object> out = new ArrayList<>();
        for (Object item : Ops.list(list)) {
            if (!Ops.in(Ops.get(item, Ops.str(path)), values)) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * What reference.CorridorRestrictions says about a payment from one country to another in a currency: the
     * action of the most specific row ("<debtor>|<creditor>|<currency>", then with '*' for the parts not
     * restricted), or null when the corridor is open.
     */
    public static Object corridorRestriction(Object debtorCountry, Object creditorCountry, Object currency) {
        String d = Ops.blank(debtorCountry) ? "*" : Ops.str(debtorCountry);
        String c = Ops.blank(creditorCountry) ? "*" : Ops.str(creditorCountry);
        String ccy = Ops.blank(currency) ? "*" : Ops.str(currency);
        String[] keys = {d + "|" + c + "|" + ccy, d + "|" + c + "|*", "*|" + c + "|" + ccy, "*|" + c + "|*",
            d + "|*|" + ccy, d + "|*|*", "*|*|" + ccy};
        for (String key : keys) {
            if (key.equals("*|*|*")) {
                continue;
            }
            Rec row = io.orvanta.core.flow.RefData.lookup("reference.CorridorRestrictions", key);
            if (row != null) {
                return row.str("action");
            }
        }
        return null;
    }

    public static Object first(Object list) {
        List<?> l = Ops.list(list);
        return l.isEmpty() ? null : l.get(0);
    }

    // ---- reference data ----

    /** Row of a ReferenceTable: lookup('reference.BicDirectory', txn.creditor.agentBic).name */
    public static Object lookup(Object table, Object key) {
        return io.orvanta.core.flow.RefData.lookup(Ops.str(table), key);
    }

    /**
     * What a reason code means in a language, from reference.ReasonTexts: text('AC04', 'de'). The text in that
     * language, or else in English, or else the code itself, so a customer always gets something to read.
     */
    public static Object text(Object code, Object language) {
        return text("reference.ReasonTexts", code, language);
    }

    /** The same from a table of one's own, with rows {id: CODE-lang, text}. */
    public static Object text(Object table, Object code, Object language) {
        String c = Ops.str(code);
        if (c == null || c.isBlank()) {
            return null;
        }
        String lang = Ops.str(language) == null ? "en" : Ops.str(language).toLowerCase(java.util.Locale.ROOT);
        for (String candidate : lang.equals("en") ? List.of(lang) : List.of(lang, lang.length() > 2 ? lang.substring(0, 2) : lang, "en")) {
            Object row = io.orvanta.core.flow.RefData.lookup(Ops.str(table), c + "-" + candidate);
            if (row instanceof java.util.Map<?, ?> m && m.get("text") != null) {
                return m.get("text");
            }
        }
        return c;
    }

    // ---- dates (ISO text in, ISO text out) ----

    public static Object now() {
        return Time.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    public static Object today() {
        return LocalDate.ofInstant(Time.now(), ZoneOffset.UTC).toString();
    }

    /** Seconds from a moment (an ISO instant) until now; null when there is no such moment: secondsSince(txn.createdAt) > 9. */
    public static Object secondsSince(Object instant) {
        String s = Ops.str(instant);
        if (s == null || s.isBlank()) {
            return null;
        }
        return BigDecimal.valueOf(java.time.Duration.between(java.time.Instant.parse(s), Time.now()).toMillis()).movePointLeft(3);
    }

    /** Today's date in a time zone: todayIn('Europe/Berlin'). */
    public static Object todayIn(Object zone) {
        return LocalDate.ofInstant(Time.now(), java.time.ZoneId.of(Ops.str(zone))).toString();
    }

    /** The time of day in a time zone as HH:mm, which compares correctly as text: timeIn('Europe/Berlin') >= '15:00'. */
    public static Object timeIn(Object zone) {
        return java.time.LocalTime.ofInstant(Time.now(), java.time.ZoneId.of(Ops.str(zone))).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** The moment a date and a time of day occur in a time zone, as an ISO instant. */
    public static Object instantOf(Object date, Object time, Object zone) {
        return java.time.ZonedDateTime.of(LocalDate.parse(Ops.str(date).substring(0, 10)), java.time.LocalTime.parse(Ops.str(time)),
                java.time.ZoneId.of(Ops.str(zone))).toInstant().toString();
    }

    // ---- business calendars (reference table reference.Holidays, key "<calendar>|<date>"; Saturday and Sunday are closed) ----

    public static Object isBusinessDay(Object calendar, Object date) {
        LocalDate d = LocalDate.parse(Ops.str(date).substring(0, 10));
        if (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            return false;
        }
        return io.orvanta.core.flow.RefData.lookup("reference.Holidays", Ops.str(calendar) + "|" + d) == null;
    }

    /** The first business day strictly after the date. */
    public static Object nextBusinessDay(Object calendar, Object date) {
        LocalDate d = LocalDate.parse(Ops.str(date).substring(0, 10));
        for (int i = 0; i < 60; i++) {
            d = d.plusDays(1);
            if ((Boolean) isBusinessDay(calendar, d.toString())) {
                return d.toString();
            }
        }
        throw new IllegalStateException("calendar " + Ops.str(calendar) + " has no business day within 60 days of " + Ops.str(date));
    }

    /** The last business day strictly before the date. */
    public static Object previousBusinessDay(Object calendar, Object date) {
        LocalDate d = LocalDate.parse(Ops.str(date).substring(0, 10));
        for (int i = 0; i < 60; i++) {
            d = d.minusDays(1);
            if ((Boolean) isBusinessDay(calendar, d.toString())) {
                return d.toString();
            }
        }
        throw new IllegalStateException("calendar " + Ops.str(calendar) + " has no business day within 60 days before " + Ops.str(date));
    }

    /** The date itself when it is a business day, otherwise the next one. */
    public static Object businessDayOnOrAfter(Object calendar, Object date) {
        return (Boolean) isBusinessDay(calendar, date) ? Ops.str(date).substring(0, 10) : nextBusinessDay(calendar, date);
    }

    /** Formats an ISO date or date-time: fmtDate(txn.valueDate, 'yyMMdd'). */
    public static Object fmtDate(Object v, Object pattern) {
        String s = Ops.str(v);
        if (s == null) {
            return null;
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern(Ops.str(pattern));
        if (s.length() <= 10) {
            return LocalDate.parse(s).format(f);
        }
        if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:\\d\\d$")) {
            return LocalDateTime.ofInstant(java.time.OffsetDateTime.parse(s).toInstant(), ZoneOffset.UTC).format(f);
        }
        return LocalDateTime.parse(s).format(f);
    }

    public static Object isDate(Object v) {
        String s = Ops.str(v);
        if (s == null) {
            return false;
        }
        try {
            LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Object addDays(Object date, Object days) {
        return LocalDate.parse(Ops.str(date).substring(0, 10)).plusDays(Ops.num(days).longValue()).toString();
    }

    // ---- financial identifiers ----

    private static final Pattern BIC = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

    public static Object isBic(Object v) {
        String s = Ops.str(v);
        return s != null && BIC.matcher(s).matches();
    }

    /** Structure and ISO 7064 mod-97 check digits. */
    public static Object isIban(Object v) {
        String s = Ops.str(v);
        if (s == null || !IBAN.matcher(s).matches()) {
            return false;
        }
        String rearranged = s.substring(4) + s.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /**
     * What is wrong with an IBAN for its country, from reference.IbanStructures: the length that country uses,
     * or that the country has no structure here; null when the IBAN fits (its check digits are isIban()'s business).
     */
    public static Object ibanProblem(Object v) {
        String s = Ops.str(v);
        if (s == null || s.length() < 4) {
            return "not an IBAN";
        }
        Rec structure = io.orvanta.core.flow.RefData.lookup("reference.IbanStructures", s.substring(0, 2));
        if (structure == null) {
            return "no IBAN structure is known for country " + s.substring(0, 2);
        }
        int length = Ops.num(structure.get("length")).intValue();
        if (s.length() != length) {
            return "an IBAN of " + s.substring(0, 2) + " has " + length + " characters, this one has " + s.length();
        }
        return null;
    }

    /** The bank identifier inside an IBAN, cut out with reference.IbanStructures; null when the country is not known. */
    public static Object ibanBankCode(Object v) {
        String s = Ops.str(v);
        if (s == null || s.length() < 4) {
            return null;
        }
        Rec structure = io.orvanta.core.flow.RefData.lookup("reference.IbanStructures", s.substring(0, 2));
        if (structure == null) {
            return null;
        }
        int start = Ops.num(structure.get("bankCodeStart")).intValue();
        int length = Ops.num(structure.get("bankCodeLength")).intValue();
        return s.length() >= start - 1 + length ? s.substring(start - 1, start - 1 + length) : null;
    }

    /** The BIC of the bank an IBAN belongs to, from reference.IbanBankCodes by "<country>|<bank code>"; null when not listed. */
    public static Object bicFromIban(Object v) {
        Object code = ibanBankCode(v);
        if (code == null) {
            return null;
        }
        Rec bank = io.orvanta.core.flow.RefData.lookup("reference.IbanBankCodes", Ops.str(v).substring(0, 2) + "|" + code);
        return bank == null ? null : bank.str("bic");
    }

    /**
     * Whether a relationship table allows a message type with a bank today: the row exists, its messageTypes
     * (when given) name the type, and today is within validFrom and validTo (when given). For SWIFT RMA.
     */
    public static Object rmaAllows(Object table, Object bic, Object messageType) {
        Rec row = io.orvanta.core.flow.RefData.lookup(Ops.str(table), bic);
        if (row == null) {
            return false;
        }
        if (row.get("messageTypes") instanceof java.util.List<?> types && !types.isEmpty() && !Ops.in(messageType, types)) {
            return false;
        }
        String today = Ops.str(today());
        if (row.str("validFrom") != null && today.compareTo(row.str("validFrom")) < 0) {
            return false;
        }
        return row.str("validTo") == null || today.compareTo(row.str("validTo")) <= 0;
    }

    /**
     * Text made to fit a field of the given length: cut and marked with a trailing '+' when it was longer, as
     * SWIFT usage guidelines have it for text truncated on the way from MX to MT. Null stays null.
     */
    public static Object fit(Object text, Object length) {
        String s = Ops.str(text);
        if (s == null) {
            return null;
        }
        int n = Ops.num(length).intValue();
        if (n < 1) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "+";
    }

    // ---- how a country identifies a bank account, and national bank codes ----

    /**
     * What is wrong with the way a creditor account is given for its country, from reference.CountryRouting:
     * the country needs an IBAN, or a bank code of a certain form, or a BIC. Null when it fits or the country is
     * not in the table (then nothing is required).
     */
    public static Object accountRoutingProblem(Object country, Object iban, Object bankCode, Object bic) {
        Rec row = io.orvanta.core.flow.RefData.lookup("reference.CountryRouting", country);
        if (row == null) {
            return null;
        }
        String need = row.str("identifier");
        String code = Ops.str(bankCode);
        if ("IBAN".equals(need) && Ops.blank(iban)) {
            return "a payment to " + Ops.str(country) + " needs the creditor's IBAN";
        }
        if ("BANK_CODE".equals(need) && Ops.blank(code) && Ops.blank(iban)) {
            return "a payment to " + Ops.str(country) + " needs the creditor bank's " + row.str("bankCodeName");
        }
        if ("BIC".equals(need) && Ops.blank(bic) && Ops.blank(iban)) {
            return "a payment to " + Ops.str(country) + " needs the creditor bank's BIC";
        }
        if (!Ops.blank(code) && row.str("bankCodeFormat") != null && !code.matches(row.str("bankCodeFormat"))) {
            return "'" + code + "' is not a " + row.str("bankCodeName") + " of " + Ops.str(country) + " (" + row.str("bankCodeFormat") + ")";
        }
        return null;
    }

    /** The BIC of a bank by its national code, from reference.ClearingCodes ("<country>|<code>"); null when not listed. */
    public static Object bicFromClearingCode(Object country, Object code) {
        if (Ops.blank(country) || Ops.blank(code)) {
            return null;
        }
        Rec row = io.orvanta.core.flow.RefData.lookup("reference.ClearingCodes", Ops.str(country) + "|" + Ops.str(code).trim());
        return row == null ? null : row.str("bic");
    }

    /** The national bank code of a bank by its BIC, from reference.BicClearingCodes ("<bic>|<country>"); the 8-character BIC is tried too. */
    public static Object clearingCodeFromBic(Object bic, Object country) {
        String b = Ops.str(bic);
        if (b == null || Ops.blank(country)) {
            return null;
        }
        Rec row = io.orvanta.core.flow.RefData.lookup("reference.BicClearingCodes", b + "|" + Ops.str(country));
        if (row == null && b.length() == 11) {
            row = io.orvanta.core.flow.RefData.lookup("reference.BicClearingCodes", b.substring(0, 8) + "|" + Ops.str(country));
        }
        return row == null ? null : row.str("code");
    }

    // ---- taking an IBAN apart and putting one together ----

    /**
     * The parts of an IBAN by its country's structure (reference.IbanStructures: bank code position and length,
     * and when given accountStart/accountLength and branchStart/branchLength): {country, checkDigits, bankCode,
     * branchCode, account}; null when the country is not known.
     */
    public static Object decomposeIban(Object v) {
        String s = Ops.str(v);
        if (s == null || s.length() < 5) {
            return null;
        }
        Rec structure = io.orvanta.core.flow.RefData.lookup("reference.IbanStructures", s.substring(0, 2));
        if (structure == null) {
            return null;
        }
        Rec out = Rec.of("country", s.substring(0, 2), "checkDigits", s.substring(2, 4));
        out.put("bankCode", slice(s, structure, "bankCodeStart", "bankCodeLength"));
        out.put("branchCode", slice(s, structure, "branchStart", "branchLength"));
        String account = slice(s, structure, "accountStart", "accountLength");
        if (account == null) {
            // without an account position the account is what follows the bank (and branch) code
            int from = Ops.num(structure.get("bankCodeStart")).intValue() - 1 + Ops.num(structure.get("bankCodeLength")).intValue();
            if (structure.get("branchLength") != null) {
                from += Ops.num(structure.get("branchLength")).intValue();
            }
            account = from < s.length() ? s.substring(from) : null;
        }
        out.put("account", account);
        return out;
    }

    private static String slice(String s, Rec structure, String startKey, String lengthKey) {
        if (structure.get(startKey) == null || structure.get(lengthKey) == null) {
            return null;
        }
        int start = Ops.num(structure.get(startKey)).intValue() - 1;
        int length = Ops.num(structure.get(lengthKey)).intValue();
        return start >= 0 && start + length <= s.length() ? s.substring(start, start + length) : null;
    }

    /**
     * An IBAN from its parts for a country whose structure is known: the bank code (and branch code when the
     * country has one) and the account number, zero-filled on the left to their lengths, with the check digits
     * computed. Null when the country is not known or the parts do not fit.
     */
    public static Object composeIban(Object country, Object bankCode, Object branchCode, Object account) {
        String cc = Ops.str(country);
        Rec structure = cc == null ? null : io.orvanta.core.flow.RefData.lookup("reference.IbanStructures", cc);
        if (structure == null || Ops.blank(bankCode) || Ops.blank(account)) {
            return null;
        }
        int total = Ops.num(structure.get("length")).intValue();
        String bank = fill(Ops.str(bankCode), Ops.num(structure.get("bankCodeLength")).intValue());
        String branch = structure.get("branchLength") == null ? "" : fill(Ops.str(branchCode) == null ? "" : Ops.str(branchCode), Ops.num(structure.get("branchLength")).intValue());
        if (bank == null || branch == null) {
            return null;
        }
        int accountLength = total - 4 - bank.length() - branch.length();
        if (accountLength < 1) {
            return null;
        }
        String acct = fill(Ops.str(account).replace(" ", "").replace("-", ""), accountLength);
        if (acct == null) {
            return null;
        }
        String bban = bank + branch + acct;
        String digits = toDigits(bban + cc + "00");
        int check = 98 - new BigInteger(digits).mod(BigInteger.valueOf(97)).intValue();
        return cc + (check < 10 ? "0" + check : String.valueOf(check)) + bban;
    }

    private static String fill(String value, int length) {
        if (value == null || value.length() > length) {
            return null;
        }
        if (value.length() == length) {
            return value;
        }
        return value.matches("[0-9]*") ? "0".repeat(length - value.length()) + value : null;
    }

    private static String toDigits(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return digits.toString();
    }

    // ---- national account number checks ----

    /** The Dutch 11-test on a 9 or 10 digit account number (the account part of an NL IBAN): the weighted sum is a multiple of 11. */
    public static Object nlAccountValid(Object v) {
        String s = Ops.str(v) == null ? "" : Ops.str(v).replaceAll("[^0-9]", "");
        if (s.length() < 9 || s.length() > 10) {
            return false;
        }
        if (s.length() == 9) {
            s = "0" + s;
        }
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += (s.charAt(i) - '0') * (10 - i);
        }
        return sum % 11 == 0;
    }

    /**
     * The Hungarian check digits: the 8-digit bank branch code and the 8 or 16 digit account part each end in a
     * check digit, with the weights 9, 7, 3, 1 repeating over the digits before it and the total a multiple of 10.
     */
    public static Object huAccountValid(Object bankBranchCode, Object account) {
        String branch = Ops.str(bankBranchCode) == null ? "" : Ops.str(bankBranchCode).replaceAll("[^0-9]", "");
        String acct = Ops.str(account) == null ? "" : Ops.str(account).replaceAll("[^0-9]", "");
        if (branch.length() != 8 || (acct.length() != 8 && acct.length() != 16)) {
            return false;
        }
        return huPart(branch) && huPart(acct);
    }

    private static boolean huPart(String digits) {
        int[] weights = {9, 7, 3, 1};
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * weights[i % 4];
        }
        return sum % 10 == 0;
    }

    /**
     * The UK modulus check of a sort code and account number against the weights loaded into
     * reference.UkModulusWeights (the published Vocalink table): null when it passes, when the table holds no range
     * for the sort code, or when the range carries an exception this check does not implement (then it is not
     * checked); otherwise what failed. Rows: from, to, method (DBLAL | MOD10 | MOD11), u v w x y z a b c d e f g h, exception.
     */
    public static Object ukAccountProblem(Object sortCode, Object account) {
        String sort = Ops.str(sortCode) == null ? "" : Ops.str(sortCode).replaceAll("[^0-9]", "");
        String acct = Ops.str(account) == null ? "" : Ops.str(account).replaceAll("[^0-9]", "");
        if (sort.length() != 6 || acct.length() != 8) {
            return "a UK account is a 6-digit sort code and an 8-digit account number";
        }
        java.util.List<Rec> ranges = io.orvanta.core.flow.RefData.rows("reference.UkModulusWeights");
        if (ranges.isEmpty()) {
            return null;
        }
        for (Rec range : ranges) {
            String from = Ops.str(range.get("from"));
            String to = Ops.str(range.get("to"));
            if (from == null || to == null || sort.compareTo(from) < 0 || sort.compareTo(to) > 0) {
                continue;
            }
            if (range.get("exception") != null && Ops.num(range.get("exception")).intValue() != 0) {
                return null;
            }
            String digits = sort + acct;
            String[] names = {"u", "v", "w", "x", "y", "z", "a", "b", "c", "d", "e", "f", "g", "h"};
            int total = 0;
            for (int i = 0; i < 14; i++) {
                int weight = range.get(names[i]) == null ? 0 : Ops.num(range.get(names[i])).intValue();
                int product = (digits.charAt(i) - '0') * weight;
                if ("DBLAL".equals(range.str("method"))) {
                    // double alternate: the digits of each product are added, not the product
                    total += product / 10 + product % 10;
                } else {
                    total += product;
                }
            }
            int modulus = "MOD11".equals(range.str("method")) ? 11 : 10;
            if (total % modulus != 0) {
                return "the account number does not pass the " + range.str("method") + " check of sort code " + sort;
            }
        }
        return null;
    }

    /**
     * A fee from a schedule: a fixed part plus a percentage of the amount, kept between a minimum and a maximum
     * (either may be missing), rounded to two decimals: fee(1000, 7.50, 0.1, 10, 50) is 10.
     */
    public static Object fee(Object amount, Object fixed, Object percent, Object min, Object max) {
        BigDecimal base = Ops.blank(amount) ? BigDecimal.ZERO : Ops.num(amount);
        BigDecimal total = Ops.blank(fixed) ? BigDecimal.ZERO : Ops.num(fixed);
        if (!Ops.blank(percent)) {
            total = total.add(base.multiply(Ops.num(percent)).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        }
        if (!Ops.blank(min) && total.compareTo(Ops.num(min)) < 0) {
            total = Ops.num(min);
        }
        if (!Ops.blank(max) && total.compareTo(Ops.num(max)) > 0) {
            total = Ops.num(max);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The settlement account of a scheme in a currency, from reference.SettlementAccounts: the row keyed
     * "<scheme>|<currency>" when the scheme settles in several currencies, else the row of the scheme alone.
     */
    public static Object settlementAccount(Object scheme, Object currency) {
        if (Ops.blank(scheme)) {
            return null;
        }
        Rec row = Ops.blank(currency) ? null : io.orvanta.core.flow.RefData.lookup("reference.SettlementAccounts", Ops.str(scheme) + "|" + Ops.str(currency));
        return row != null ? row : io.orvanta.core.flow.RefData.lookup("reference.SettlementAccounts", scheme);
    }

    public static Object isCurrency(Object v) {
        String s = Ops.str(v);
        if (s == null) {
            return false;
        }
        try {
            Currency.getInstance(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- SEPA character set (EPC: Latin letters, digits, space and / - ? : ( ) . , ' +) ----

    private static final Pattern SEPA_TEXT = Pattern.compile("[A-Za-z0-9/\\-?:().,'+ ]*");

    /** True when the text is missing or uses only the SEPA basic character set. */
    public static Object isSepaText(Object v) {
        String s = Ops.str(v);
        return s == null || SEPA_TEXT.matcher(s).matches();
    }

    /** Transliterates to the SEPA character set: accents are dropped, anything else unknown becomes a dot. */
    public static Object toSepaText(Object v) {
        String s = Ops.str(v);
        if (s == null) {
            return null;
        }
        String plain = java.text.Normalizer.normalize(s.replace("\u00df", "ss").replace("\u00e6", "ae").replace("\u00f8", "o"),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[^A-Za-z0-9/\\-?:().,'+ ]", ".");
    }

    // ---- SWIFT MT helpers (a parsed message is {type, sender, receiver, fields:[{tag, value}]}) ----

    /** Value of the first field with the tag, in a message or in a group returned by mtGroups/mtHead. */
    public static Object mtField(Object message, Object tag) {
        String t = Ops.str(tag);
        for (Object f : Ops.list(Ops.get(message, "fields"))) {
            if (t.equals(Ops.str(Ops.get(f, "tag")))) {
                return Ops.get(f, "value");
            }
        }
        return null;
    }

    /** Fields before the first occurrence of the tag (the header sequence). */
    public static Object mtHead(Object message, Object startTag) {
        String t = Ops.str(startTag);
        List<Object> head = new ArrayList<>();
        for (Object f : Ops.list(Ops.get(message, "fields"))) {
            if (t.equals(Ops.str(Ops.get(f, "tag")))) {
                break;
            }
            head.add(f);
        }
        return Rec.of("fields", head);
    }

    /** Splits the fields into repeating sequences, each starting at the tag (MT101 sequence B starts at 21). */
    public static Object mtGroups(Object message, Object startTag) {
        String t = Ops.str(startTag);
        List<Object> groups = new ArrayList<>();
        List<Object> current = null;
        for (Object f : Ops.list(Ops.get(message, "fields"))) {
            if (t.equals(Ops.str(Ops.get(f, "tag")))) {
                current = new ArrayList<>();
                groups.add(Rec.of("fields", current));
            }
            if (current != null) {
                current.add(f);
            }
        }
        return groups;
    }

    /** Line n (1-based) of a multi-line field value. */
    public static Object mtLine(Object value, Object n) {
        String s = Ops.str(value);
        if (s == null) {
            return null;
        }
        String[] lines = s.split("\\r?\\n");
        int i = Ops.num(n).intValue() - 1;
        return i >= 0 && i < lines.length ? lines[i] : null;
    }

    /** Account of a party field (50K, 59): the first line when it starts with a slash. */
    public static Object mtAccount(Object value) {
        String first = (String) mtLine(value, BigDecimal.ONE);
        return first != null && first.startsWith("/") ? first.substring(1) : null;
    }

    /** Name of a party field: the first line that is not an account line. */
    public static Object mtName(Object value) {
        String s = Ops.str(value);
        if (s == null) {
            return null;
        }
        for (String line : s.split("\\r?\\n")) {
            if (!line.startsWith("/")) {
                return line;
            }
        }
        return null;
    }

    /** Currency of an amount field such as 32A (250115ZAR1234,56) or 32B (ZAR1234,56). */
    public static Object mtCcy(Object value) {
        String s = Ops.str(value);
        if (s == null) {
            return null;
        }
        java.util.regex.Matcher m = Pattern.compile("[A-Z]{3}").matcher(s);
        return m.find() ? m.group() : null;
    }

    public static Object mtAmt(Object value) {
        String s = Ops.str(value);
        if (s == null) {
            return null;
        }
        java.util.regex.Matcher m = Pattern.compile("[A-Z]{3}([0-9]+,[0-9]*)").matcher(s);
        if (!m.find()) {
            return null;
        }
        String n = m.group(1).replace(',', '.');
        return new BigDecimal(n.endsWith(".") ? n + "0" : n);
    }

    /** Leading YYMMDD of a field as an ISO date. */
    public static Object mtDate(Object value) {
        String s = Ops.str(value);
        if (s == null || s.length() < 6) {
            return null;
        }
        return LocalDate.parse(s.substring(0, 6), DateTimeFormatter.ofPattern("yyMMdd")).toString();
    }

    private static final Pattern MT_BALANCE = Pattern.compile("([CD])(\\d{6})([A-Z]{3})(\\d+,\\d*)");
    private static final Pattern MT_LINE = Pattern.compile("(\\d{6})(\\d{4})?(R?[CD])([A-Z])?(\\d+,\\d*)([A-Z][A-Z0-9]{3})([^/\\n]*)(?://([^\\n]*))?.*", Pattern.DOTALL);

    private static BigDecimal mtNumber(String s) {
        String n = s.replace(',', '.');
        return new BigDecimal(n.endsWith(".") ? n + "0" : n);
    }

    /** A balance field of a statement (60F, 62F, 64): {creditDebit, date, currency, amount}; the amount is negative for a debit balance. */
    public static Object mtBalance(Object value) {
        String s = Ops.str(value);
        java.util.regex.Matcher m = s == null ? null : MT_BALANCE.matcher(s.trim());
        if (m == null || !m.matches()) {
            return null;
        }
        BigDecimal amount = mtNumber(m.group(4));
        return Rec.of("creditDebit", m.group(1).equals("D") ? "DBIT" : "CRDT", "date", mtDate(m.group(2)), "currency", m.group(3),
                "amount", m.group(1).equals("D") ? amount.negate() : amount);
    }

    /**
     * A statement line (field 61): {valueDate, creditDebit, amount, type, reference, bankReference}.
     * A reversal (RC, RD) is reported with the direction it has on the account: RD is a credit, RC a debit.
     */
    public static Object mt61(Object value) {
        String s = Ops.str(value);
        java.util.regex.Matcher m = s == null ? null : MT_LINE.matcher(s.trim());
        if (m == null || !m.matches()) {
            return null;
        }
        String mark = m.group(3);
        boolean debit = mark.equals("D") || mark.equals("RC");
        return Rec.of("valueDate", mtDate(m.group(1)), "creditDebit", debit ? "DBIT" : "CRDT", "reversal", mark.startsWith("R") ? Boolean.TRUE : null,
                "amount", mtNumber(m.group(5)), "type", m.group(6), "reference", m.group(7).trim(),
                "bankReference", m.group(8) == null ? null : m.group(8).trim());
    }

    /** Amount in MT notation: decimal comma, no thousands separator (1234.5 becomes 1234,5). */
    public static Object mtAmount(Object amount) {
        String s = Ops.num(amount).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s.replace('.', ',') : s + ",";
    }
}
