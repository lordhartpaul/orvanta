package io.orvanta.core.expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Operators of the Orvanta expression language. Forge compiles every expression into
 * plain Java calls on this class, so values stay dynamic (Object) while the code is compiled.
 */
public final class Ops {

    private Ops() {
    }

    /** Member access. A list is transparently read through its first element (XML elements may repeat). */
    public static Object get(Object base, String key) {
        if (base instanceof List<?> l) {
            base = l.isEmpty() ? null : l.get(0);
        }
        if (base instanceof Map<?, ?> m) {
            return m.get(key);
        }
        return null;
    }

    public static Object idx(Object base, Object index) {
        int n = num(index).intValue();
        if (base instanceof List<?> l) {
            return n >= 0 && n < l.size() ? l.get(n) : null;
        }
        return n == 0 ? base : null;
    }

    /** Always a list: null becomes empty, a single value becomes a one-element list. */
    public static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> l) {
            return l;
        }
        return List.of(value);
    }

    /** An XML element with attributes is a record whose text is under "#text"; operators read through it. */
    public static Object unwrap(Object value) {
        if (value instanceof Map<?, ?> m && m.containsKey("#text")) {
            return m.get("#text");
        }
        return value;
    }

    public static boolean blank(Object value) {
        value = unwrap(value);
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    public static boolean truthy(Object value) {
        value = unwrap(value);
        if (value instanceof Boolean b) {
            return b;
        }
        return !blank(value);
    }

    public static String str(Object value) {
        value = unwrap(value);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return String.valueOf(value);
    }

    public static BigDecimal num(Object value) {
        value = unwrap(value);
        if (value == null) {
            throw new IllegalArgumentException("a number is required but the value is missing");
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(value).trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a number");
        }
    }

    private static boolean numeric(Object value) {
        return unwrap(value) instanceof Number;
    }

    public static Object add(Object a, Object b) {
        if (numeric(a) && numeric(b)) {
            return num(a).add(num(b));
        }
        String left = str(a);
        String right = str(b);
        return (left == null ? "" : left) + (right == null ? "" : right);
    }

    public static Object sub(Object a, Object b) {
        return num(a).subtract(num(b));
    }

    public static Object mul(Object a, Object b) {
        return num(a).multiply(num(b));
    }

    public static Object div(Object a, Object b) {
        return num(a).divide(num(b), 10, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    public static Object neg(Object a) {
        return num(a).negate();
    }

    public static boolean eq(Object a, Object b) {
        a = unwrap(a);
        b = unwrap(b);
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number || b instanceof Number) {
            try {
                return num(a).compareTo(num(b)) == 0;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.equals(b);
        }
        return str(a).equals(str(b));
    }

    /** Numeric when either side is a number, otherwise text order. A missing side makes every comparison false. */
    public static int cmp(Object a, Object b) {
        a = unwrap(a);
        b = unwrap(b);
        if (a instanceof Number || b instanceof Number) {
            return num(a).compareTo(num(b));
        }
        return str(a).compareTo(str(b));
    }

    public static boolean lt(Object a, Object b) {
        return present(a, b) && cmp(a, b) < 0;
    }

    public static boolean le(Object a, Object b) {
        return present(a, b) && cmp(a, b) <= 0;
    }

    public static boolean gt(Object a, Object b) {
        return present(a, b) && cmp(a, b) > 0;
    }

    public static boolean ge(Object a, Object b) {
        return present(a, b) && cmp(a, b) >= 0;
    }

    private static boolean present(Object a, Object b) {
        return unwrap(a) != null && unwrap(b) != null;
    }

    public static boolean in(Object value, Object candidates) {
        for (Object c : list(candidates)) {
            if (eq(value, c)) {
                return true;
            }
        }
        return false;
    }
}
