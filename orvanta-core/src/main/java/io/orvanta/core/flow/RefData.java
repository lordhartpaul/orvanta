package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;

import java.util.function.Supplier;

/**
 * Where expressions find reference tables. Normally that is the active deployment; a build that
 * is only being tried out (a Studio run of an unsaved edit, a test run) is used for the duration
 * of that run on the calling thread.
 */
public final class RefData {

    private static volatile Registry active;
    private static final ThreadLocal<Registry> SCOPED = new ThreadLocal<>();

    private RefData() {
    }

    public static void activate(Registry registry) {
        active = registry;
    }

    public static <T> T with(Registry registry, Supplier<T> body) {
        Registry previous = SCOPED.get();
        SCOPED.set(registry);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SCOPED.remove();
            } else {
                SCOPED.set(previous);
            }
        }
    }

    /** Every row of a reference table, in order; empty when there is no such table. */
    public static java.util.List<Rec> rows(String table) {
        Registry registry = SCOPED.get() != null ? SCOPED.get() : active;
        if (registry == null) {
            return java.util.List.of();
        }
        Rec def = registry.config(table);
        if (def == null || !"ReferenceTable".equals(def.str("kind"))) {
            return java.util.List.of();
        }
        java.util.List<Rec> out = new java.util.ArrayList<>();
        for (Object row : io.orvanta.core.expr.Ops.list(def.get("rows"))) {
            if (row instanceof Rec r) {
                out.add(r);
            }
        }
        return out;
    }

    public static Rec lookup(String table, Object key) {
        Registry registry = SCOPED.get() != null ? SCOPED.get() : active;
        if (registry == null) {
            throw new IllegalStateException("reference table '" + table + "' cannot be read: no deployment is active");
        }
        return registry.lookup(table, key);
    }
}
