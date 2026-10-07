package io.orvanta.pay.api;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.engine.Status;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What a monitoring system scrapes at /metrics, in the Prometheus text format: how many payments are
 * in each state, what is waiting for people, what failed, how the API is doing and how the process is.
 * Numbers only, never payment data.
 */
public final class Metrics {

    /** Requests per route and status class, and their time: a histogram with the buckets Prometheus expects. */
    private static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    private final Platform platform;
    private final long startedAt = System.currentTimeMillis();
    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder[]> durations = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> durationSums = new ConcurrentHashMap<>();

    public Metrics(Platform platform) {
        this.platform = platform;
    }

    /** The route a path belongs to: a segment with a digit in it is an id or a number and becomes {id}, so routes stay few and hold no data. */
    public static String routeOf(String path) {
        StringBuilder route = new StringBuilder();
        String[] segments = path.split("/");
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            route.append('/').append(i > 1 && segment.matches(".*[0-9].*") ? "{id}" : segment.replaceAll("[^A-Za-z0-9._{}-]", "_"));
        }
        return route.length() == 0 ? "/" : route.toString();
    }

    /** Records one API request: the route pattern (not the path, which holds ids), the status class and the time it took. */
    public void request(String route, int status, long millis) {
        String key = route + "\t" + (status / 100) + "xx";
        requests.computeIfAbsent(key, k -> new LongAdder()).increment();
        LongAdder[] buckets = durations.computeIfAbsent(route, k -> {
            LongAdder[] b = new LongAdder[BUCKETS.length + 1];
            for (int i = 0; i < b.length; i++) {
                b[i] = new LongAdder();
            }
            return b;
        });
        double seconds = millis / 1000.0;
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                buckets[i].increment();
            }
        }
        buckets[BUCKETS.length].increment();
        durationSums.computeIfAbsent(route, k -> new AtomicLong()).addAndGet(millis);
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        DocStore store = platform.store;
        gauge(out, "orvanta_up", "Whether the document store answers", store.ping() ? 1 : 0);
        gauge(out, "orvanta_uptime_seconds", "Seconds since this process started", (System.currentTimeMillis() - startedAt) / 1000.0);
        gauge(out, "orvanta_deployment_version", "The version of the deployed models", platform.deployments.activeVersion());

        out.append("# HELP orvanta_transactions Payments by status\n# TYPE orvanta_transactions gauge\n");
        Map<String, Long> byStatus = store.counts(DocStore.TXN, new DocStore.Query(new Rec(), null, List.of(), null, null, false, 0, 0), "status");
        for (String status : Status.TRANSACTION) {
            out.append("orvanta_transactions{status=\"").append(status).append("\"} ").append(byStatus.getOrDefault(status, 0L)).append('\n');
        }
        out.append("# HELP orvanta_messages Messages received, by status\n# TYPE orvanta_messages gauge\n");
        store.counts(DocStore.MESSAGE, new DocStore.Query(new Rec(), null, List.of(), null, null, false, 0, 0), "status")
                .forEach((status, n) -> out.append("orvanta_messages{status=\"").append(status).append("\"} ").append(n).append('\n'));
        out.append("# HELP orvanta_outbound Outbound files, by status\n# TYPE orvanta_outbound gauge\n");
        store.counts(DocStore.OUTBOUND, new DocStore.Query(new Rec(), null, List.of(), null, null, false, 0, 0), "status")
                .forEach((status, n) -> out.append("orvanta_outbound{status=\"").append(status).append("\"} ").append(n).append('\n'));

        // what waits for a person: the numbers an operations team pages on
        gauge(out, "orvanta_approvals_pending", "Requests waiting for a second person", store.count(DocStore.APPROVAL, Rec.of("status", "PENDING")));
        gauge(out, "orvanta_deadletters_open", "Failed internal events not yet requeued", store.count(DocStore.DEAD_LETTER, Rec.of("status", "OPEN")));
        gauge(out, "orvanta_payments_held", "Payments held for review", byStatus.getOrDefault(Status.HELD, 0L));
        gauge(out, "orvanta_payments_in_repair", "Payments parked for an operator", byStatus.getOrDefault(Status.REPAIR, 0L));
        gauge(out, "orvanta_answers_overdue", "Sent payments without an answer in the time their rail allows", store.count(DocStore.TXN, Rec.of("status", Status.SENT, "answerOverdue", true)));
        gauge(out, "orvanta_recalls_open", "Recalls from other banks waiting for a decision", store.count(DocStore.TXN, Rec.of("recall.status", "OPEN")));
        gauge(out, "orvanta_notifications_failed", "Customer notifications that could not be built", store.count(DocStore.TXN, Rec.of("notify.state", "FAILED")));
        gauge(out, "orvanta_users_locked", "Users locked after failed sign-ins", store.count(DocStore.USER, Rec.of("status", "LOCKED")));

        out.append("# HELP orvanta_http_requests_total API requests by route and status class\n# TYPE orvanta_http_requests_total counter\n");
        requests.forEach((key, n) -> {
            String[] parts = key.split("\t");
            out.append("orvanta_http_requests_total{route=\"").append(parts[0]).append("\",status=\"").append(parts[1]).append("\"} ").append(n.sum()).append('\n');
        });
        out.append("# HELP orvanta_http_request_seconds Time an API request took\n# TYPE orvanta_http_request_seconds histogram\n");
        durations.forEach((route, buckets) -> {
            for (int i = 0; i < BUCKETS.length; i++) {
                out.append("orvanta_http_request_seconds_bucket{route=\"").append(route).append("\",le=\"").append(BUCKETS[i]).append("\"} ").append(buckets[i].sum()).append('\n');
            }
            out.append("orvanta_http_request_seconds_bucket{route=\"").append(route).append("\",le=\"+Inf\"} ").append(buckets[BUCKETS.length].sum()).append('\n');
            out.append("orvanta_http_request_seconds_sum{route=\"").append(route).append("\"} ").append(durationSums.get(route).get() / 1000.0).append('\n');
            out.append("orvanta_http_request_seconds_count{route=\"").append(route).append("\"} ").append(buckets[BUCKETS.length].sum()).append('\n');
        });

        Runtime runtime = Runtime.getRuntime();
        gauge(out, "process_memory_used_bytes", "Heap in use", runtime.totalMemory() - runtime.freeMemory());
        gauge(out, "process_memory_max_bytes", "Heap the process may grow to", runtime.maxMemory());
        gauge(out, "process_threads", "Live threads", ManagementFactory.getThreadMXBean().getThreadCount());
        gauge(out, "process_cpu_load", "CPU load of this process (0 to 1), -1 when unknown",
                ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuLoad() : -1);
        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, double value) {
        out.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.valueOf(value)).append('\n');
    }
}
