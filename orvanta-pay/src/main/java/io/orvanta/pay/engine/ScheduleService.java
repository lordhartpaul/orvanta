package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Cron;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Flow;
import io.orvanta.core.flow.Elements.Outcome;
import io.orvanta.core.flow.FlowContext;
import io.orvanta.core.flow.Registry;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Jobs that run at times a model names: a Schedule model has a cron expression, a time zone and a job.
 * Every process with the recover unit looks every thirty seconds for schedules whose minute has come; the
 * first to claim a minute (a record per schedule and minute) runs the job, so a job runs once however many
 * processes there are. The last run of every schedule is kept for the Console.
 *
 * <pre>
 * kind: Schedule
 * name: schedules.CloseDay
 * cron: "0 18 * * 1-5"            # minute hour day month weekday
 * timezone: Africa/Johannesburg
 * job: closeDay                   # closeDay | statement | accountReports | flow
 * day: today                      # closeDay, statement: today | yesterday (default today)
 * channel: channels.CustomerStatementOutbound   # statement: the channel the statement goes out on
 * account: "4051122334"                         # statement: the account
 * flow: payments.flows.EndOfDay   # flow: the flow to run, with 'scope' as its data
 * scope: {task: endOfDay}
 * enabled: true
 * </pre>
 */
public final class ScheduleService {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduleService.class);
    public static final List<String> JOBS = List.of("closeDay", "statement", "accountReports", "flow");

    private final Platform platform;
    private ScheduledExecutorService timer;

    public ScheduleService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-schedule");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick(Instant.now());
            } catch (RuntimeException e) {
                LOG.error("schedules failed", e);
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** The schedules of the active deployment, each with its last run and the next time it is due. */
    public List<Rec> list() {
        List<Rec> out = new ArrayList<>();
        for (Rec def : platform.deployments.registry().configs("Schedule")) {
            Rec item = Rec.of("name", def.str("name"), "description", def.str("description"), "cron", def.str("cron"), "timezone", zone(def).getId(),
                    "job", def.str("job"), "enabled", enabled(def));
            try {
                ZonedDateTime next = Cron.parse(def.str("cron")).next(ZonedDateTime.now(zone(def)));
                item.put("nextAt", next == null ? null : next.toInstant().toString());
            } catch (IllegalArgumentException e) {
                item.put("problem", e.getMessage());
            }
            Rec last = platform.store.get(DocStore.SCHEDULE, def.str("name"));
            if (last != null) {
                item.put("lastRunAt", last.str("lastRunAt"));
                item.put("lastOutcome", last.str("lastOutcome"));
                item.put("lastProblem", last.str("lastProblem"));
                item.put("runs", last.get("runs"));
            }
            out.add(item);
        }
        return out;
    }

    /** Runs every enabled schedule whose minute this is, once across processes. @return how many ran here */
    public int tick(Instant now) {
        int ran = 0;
        for (Rec def : platform.deployments.registry().configs("Schedule")) {
            if (!enabled(def)) {
                continue;
            }
            ZonedDateTime local = now.atZone(zone(def)).withSecond(0).withNano(0);
            Cron cron;
            try {
                cron = Cron.parse(def.str("cron"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!cron.matches(local)) {
                continue;
            }
            // the minute is claimed with a record of its own: the first process to insert it runs the job
            String claim = def.str("name") + "|" + local.toInstant().toString().substring(0, 16);
            if (!platform.store.insertIfAbsent(DocStore.SCHEDULE_RUN, Rec.of("id", claim, "schedule", def.str("name"), "minute", local.toInstant().toString(), "claimedAt", Platform.now()))) {
                continue;
            }
            run(def, "schedule");
            ran++;
        }
        return ran;
    }

    /** Runs a schedule's job now, whoever asks; the outcome is kept as the last run. */
    public Rec run(Rec def, String actor) {
        String name = def.str("name");
        Rec last = platform.store.get(DocStore.SCHEDULE, name);
        if (last == null) {
            last = Rec.of("id", name);
        }
        Rec result;
        try {
            result = job(def);
            last.put("lastOutcome", "DONE");
            last.remove("lastProblem");
        } catch (RuntimeException e) {
            LOG.warn("schedule {} failed: {}", name, e.toString());
            result = Rec.of("problem", String.valueOf(e.getMessage()));
            last.put("lastOutcome", "FAILED");
            last.put("lastProblem", String.valueOf(e.getMessage()));
        }
        last.put("lastRunAt", Platform.now());
        last.put("lastBy", actor);
        last.put("lastResult", result);
        last.put("runs", last.get("runs") == null ? 1 : Ops.num(last.get("runs")).intValue() + 1);
        platform.store.save(DocStore.SCHEDULE, last);
        platform.changed("schedules");
        return last;
    }

    private Rec job(Rec def) {
        String job = String.valueOf(def.str("job"));
        String day = "yesterday".equals(def.str("day")) ? java.time.LocalDate.now(zone(def)).minusDays(1).toString() : java.time.LocalDate.now(zone(def)).toString();
        switch (job) {
            case "closeDay" -> {
                return new Ledger(platform).closeDay(day);
            }
            case "statement" -> {
                String id = AccountStatements.create(platform, def.str("channel"), def.str("account"), day, "schedule " + def.str("name"));
                return Rec.of("statement", id, "account", def.str("account"), "date", day);
            }
            case "accountReports" -> {
                return Rec.of("reports", AccountReports.daily(platform));
            }
            case "flow" -> {
                Registry registry = platform.deployments.registry();
                Rec scope = def.get("scope") instanceof Map<?, ?> m ? Rec.from(m) : new Rec();
                scope.put("schedule", Rec.of("name", def.str("name"), "day", day, "at", Platform.now()));
                Outcome outcome = registry.require(def.str("flow"), Flow.class).run(scope, new FlowContext(registry, platform.deployments::connector, platform.data));
                if (outcome != null && "REJECTED".equals(outcome.status())) {
                    throw new IllegalStateException("the flow ended REJECTED: " + outcome.code() + " " + outcome.message());
                }
                return Rec.of("flow", def.str("flow"), "status", outcome == null ? null : outcome.status(), "code", outcome == null ? null : outcome.code());
            }
            default -> throw new IllegalStateException("unknown job '" + job + "'");
        }
    }

    private static boolean enabled(Rec def) {
        Object v = def.get("enabled");
        return v == null || Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static ZoneId zone(Rec def) {
        return def.str("timezone") == null ? ZoneId.systemDefault() : ZoneId.of(def.str("timezone"));
    }
}
