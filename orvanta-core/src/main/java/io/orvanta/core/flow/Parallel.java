package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The branches of a flow's parallel step: each runs on its own copy of the scope with its own context, at the
 * same time as the others; when all are done, what each changed is written back into the scope.
 */
public final class Parallel {
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orvanta-parallel");
        t.setDaemon(true);
        return t;
    });

    private Parallel() {
    }

    /** Runs every branch, waits for all of them, and rethrows the first failure after the others have finished too. */
    public static <T> List<T> run(List<Callable<T>> branches) throws Exception {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> branch : branches) {
            futures.add(POOL.submit(branch));
        }
        List<T> out = new ArrayList<>();
        Exception first = null;
        for (Future<T> future : futures) {
            try {
                out.add(future.get());
            } catch (ExecutionException e) {
                if (first == null) {
                    first = e.getCause() instanceof Exception x ? x : new IllegalStateException(e.getCause());
                }
            }
        }
        if (first != null) {
            throw first;
        }
        return out;
    }

    /**
     * Writes into the scope what a branch changed: every value that differs from what the branch started with,
     * record by record, so that two branches writing different fields of the same record both land. A key a
     * branch removed is removed when nobody else has changed it.
     */
    public static void merge(Rec target, Rec branch, Rec before) {
        for (Map.Entry<String, Object> e : branch.entrySet()) {
            Object was = before.get(e.getKey());
            Object now = e.getValue();
            Object current = target.get(e.getKey());
            if (now instanceof Rec nowRec && was instanceof Rec wasRec && current instanceof Rec currentRec) {
                merge(currentRec, nowRec, wasRec);
            } else if (!Objects.equals(now, was)) {
                target.put(e.getKey(), now);
            }
        }
        for (String key : new ArrayList<>(before.keySet())) {
            if (!branch.containsKey(key) && Objects.equals(target.get(key), before.get(key))) {
                target.remove(key);
            }
        }
    }
}
