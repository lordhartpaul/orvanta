package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Stops calling a system that keeps failing: after the given number of failures in a row the circuit opens
 * and every call fails at once, without reaching the system, for the given time; then one call is let
 * through, and the circuit closes when it succeeds. A refusal by the system (a 4xx, a client fault) is not
 * a failure of the system and does not count. Settings on a connector:
 *
 * <pre>
 * circuitBreaker: {failures: 5, openSeconds: 30}
 * </pre>
 */
public final class CircuitBreaker {
    private final String name;
    private final int failures;
    private final long openMillis;
    private int consecutive;
    private long openUntil;
    private boolean trial;

    public CircuitBreaker(String name, int failures, long openSeconds) {
        this.name = name;
        this.failures = failures;
        this.openMillis = openSeconds * 1000;
    }

    /** @return the breaker a model asks for, or null when it asks for none */
    public static CircuitBreaker of(String name, Rec def) {
        if (!(def.get("circuitBreaker") instanceof Map<?, ?> m)) {
            return null;
        }
        Rec settings = Rec.from(m);
        int failures = settings.get("failures") == null ? 5 : Ops.num(settings.get("failures")).intValue();
        long openSeconds = settings.get("openSeconds") == null ? 30 : Ops.num(settings.get("openSeconds")).longValue();
        if (failures < 1 || openSeconds < 1) {
            throw new IllegalArgumentException("circuitBreaker needs 'failures' and 'openSeconds' of one or more");
        }
        return new CircuitBreaker(name, failures, openSeconds);
    }

    /** Called before a call: refuses it while the circuit is open, lets one trial through when the time is up. */
    public synchronized void beforeCall() throws IOException {
        long now = System.currentTimeMillis();
        if (openUntil > now) {
            throw new IOException("connector " + name + " is not called: its circuit is open after " + consecutive
                    + " failure(s) in a row, until " + Instant.ofEpochMilli(openUntil));
        }
        if (openUntil > 0 && !trial) {
            // the time is up: one call goes through to see whether the system is back
            trial = true;
        } else if (openUntil > 0) {
            throw new IOException("connector " + name + " is not called: one trial call is already on its way after the circuit opened");
        }
    }

    public synchronized void succeeded() {
        consecutive = 0;
        openUntil = 0;
        trial = false;
    }

    public synchronized void failed() {
        consecutive++;
        trial = false;
        if (consecutive >= failures) {
            openUntil = System.currentTimeMillis() + openMillis;
        }
    }

    public synchronized boolean isOpen() {
        return openUntil > System.currentTimeMillis();
    }
}
