package io.orvanta.pay.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing. Failed sign-ins are counted per caller address and per user name;
 * after the limit within the window, further attempts for that address or name are refused until
 * old failures leave the window. Counted in this process only: with several API processes each
 * keeps its own count, so the effective limit is the limit times the number of processes.
 */
public final class LoginThrottle {

    private final int limit;
    private final long windowMillis;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(int limit, int windowSeconds) {
        this.limit = limit;
        this.windowMillis = windowSeconds * 1000L;
    }

    /** @return seconds until another attempt is allowed, or 0 when one is allowed now */
    public long retryAfterSeconds(String address, String username) {
        return Math.max(blockedFor("a:" + address), blockedFor("u:" + username));
    }

    public void failed(String address, String username) {
        add("a:" + address);
        add("u:" + username);
    }

    public void succeeded(String address, String username) {
        failures.remove("u:" + username);
    }

    private void add(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
        }
        // the table cannot grow without bound when an attacker varies the user name
        if (failures.size() > 50_000) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast() < now - windowMillis;
                }
            });
        }
    }

    private long blockedFor(String key) {
        Deque<Long> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < now - windowMillis) {
                times.removeFirst();
            }
            if (times.size() < limit) {
                return 0;
            }
            return Math.max(1, (times.peekFirst() + windowMillis - now) / 1000);
        }
    }
}
