package io.orvanta.core.expr;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The clock that models see through now(), today(), todayIn() and timeIn(), and that decides when
 * a warehoused payment is released. Normally the system clock. A model test fixes it for the run
 * with {@code clock:}, so rules about cut-off times and holidays give the same result every day.
 */
public final class Time {

    private static volatile Instant global;
    private static final ThreadLocal<Instant> SCOPED = new ThreadLocal<>();

    private Time() {
    }

    public static Instant now() {
        Instant scoped = SCOPED.get();
        if (scoped != null) {
            return scoped;
        }
        Instant fixed = global;
        return fixed != null ? fixed : Instant.now();
    }

    /** Fixes the clock for the whole process (tests and demonstrations); null returns to the system clock. */
    public static void setGlobal(Instant instant) {
        global = instant;
    }

    public static <T> T with(Instant instant, Supplier<T> body) {
        if (instant == null) {
            return body.get();
        }
        Instant previous = SCOPED.get();
        SCOPED.set(instant);
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
}
