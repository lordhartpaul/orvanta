package io.orvanta.pay.security;

import io.orvanta.core.data.Rec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hides what a user without the permission 'data.unmasked' has no need to read in full: account numbers
 * keep their last four characters, names their first letter, free text is replaced. Applied to every
 * answer of the API for such a user, so the Console, exports and model-defined APIs all show the same.
 *
 * <pre>
 * masking:
 *   accounts: [debtor.account, creditor.account, account, debtorAccounts]   # last four characters stay
 *   names: [debtor.name, creditor.name, owner, holder, name]               # first letter stays
 *   texts: [remittance, reasonText]                                        # replaced by a marker
 * </pre>
 *
 * Field names are matched by their last part: 'account' masks 'debtor.account' and 'posting.debitAccount'
 * is matched by 'debitAccount'. Ids, amounts, statuses and dates are never masked: they carry no personal data.
 */
public final class Masking {

    public static final String PERMISSION = "data.unmasked";
    private static final List<String> ACCOUNTS = List.of("account", "debitAccount", "creditAccount", "otherAccount", "iban", "debtorAccounts");
    private static final List<String> NAMES = List.of("name", "owner", "holder", "creditorName", "debtorName", "displayName");
    private static final List<String> TEXTS = List.of("remittance", "reasonText", "text", "note");

    private final List<String> accounts;
    private final List<String> names;
    private final List<String> texts;

    public Masking(List<String> accounts, List<String> names, List<String> texts) {
        this.accounts = accounts == null || accounts.isEmpty() ? ACCOUNTS : accounts;
        this.names = names == null || names.isEmpty() ? NAMES : names;
        this.texts = texts == null || texts.isEmpty() ? TEXTS : texts;
    }

    public static Masking defaults() {
        return new Masking(null, null, null);
    }

    /** A masked copy; the original is not touched. */
    public Object mask(Object value) {
        return walk(value, null);
    }

    private Object walk(Object value, String key) {
        if (value instanceof Map<?, ?> m) {
            Rec out = new Rec();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                // the user's own record and the people of an approval are not payment data
                out.put(k, k.equals("user") || k.equals("maker") || k.equals("checker") ? e.getValue() : walk(e.getValue(), k));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(walk(o, key));
            }
            return out;
        }
        if (!(value instanceof String s) || key == null) {
            return value;
        }
        if (accounts.contains(key)) {
            return s.length() <= 4 ? "****" : "*".repeat(Math.min(s.length() - 4, 12)) + s.substring(s.length() - 4);
        }
        if (names.contains(key)) {
            return s.isEmpty() ? s : s.substring(0, 1) + "." + (s.contains(" ") ? " " + s.substring(s.lastIndexOf(' ') + 1, s.lastIndexOf(' ') + 2) + "." : "");
        }
        if (texts.contains(key)) {
            return "[hidden]";
        }
        return value;
    }
}
