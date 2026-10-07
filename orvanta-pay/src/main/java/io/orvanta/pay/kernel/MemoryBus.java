package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** In-process bus. Delivery is asynchronous on a small worker pool, like a real broker. */
public final class MemoryBus implements Bus {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryBus.class);

    private final Map<String, List<Consumer<Rec>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "orv-bus");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile FailureHandler failureHandler;

    @Override
    public void publish(String topic, Rec message) {
        for (Consumer<Rec> handler : subscribers.getOrDefault(topic, List.of())) {
            Rec copy = message.copy();
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    deliver(topic, copy, handler);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    private void deliver(String topic, Rec message, Consumer<Rec> handler) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                handler.accept(message);
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.error("handler of {} failed (attempt {}/3) for {}: {}", topic, attempt, message, e.toString());
            }
        }
        if (failureHandler != null) {
            failureHandler.failed(topic, message, last);
        }
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void onFailure(FailureHandler handler) {
        this.failureHandler = handler;
    }

    @Override
    public void subscribe(String topic, String group, Consumer<Rec> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void subscribeAll(String topic, Consumer<Rec> handler) {
        subscribe(topic, "all", handler);
    }

    /** True when no message is queued or being handled; used by tests to wait for quiescence. */
    public boolean idle() {
        return inFlight.get() == 0;
    }

    @Override
    public void close() {
        workers.shutdown();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
