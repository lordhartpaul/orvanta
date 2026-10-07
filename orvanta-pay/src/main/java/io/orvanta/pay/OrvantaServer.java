package io.orvanta.pay;

import io.orvanta.pay.api.ApiServer;
import io.orvanta.pay.engine.AcknowledgementService;
import io.orvanta.pay.engine.BulkingService;
import io.orvanta.pay.engine.CancellationService;
import io.orvanta.pay.engine.RecoveryService;
import io.orvanta.pay.engine.ReturnService;
import io.orvanta.pay.engine.StatusReportService;
import io.orvanta.pay.engine.DebulkService;
import io.orvanta.pay.engine.DispatchService;
import io.orvanta.pay.engine.IngestService;
import io.orvanta.pay.engine.ProcessingService;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.kernel.MongoDocStore;
import io.orvanta.pay.kernel.Platform;
import io.orvanta.pay.kernel.RabbitBus;
import io.orvanta.pay.security.ApprovalService;
import io.orvanta.pay.security.AuthService;
import io.orvanta.pay.sim.Simulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Starts Orvanta Pay. One jar holds every service; the "units" setting chooses which of them
 * this process runs, so the same build is a single all-in-one process on a laptop and a set of
 * independently scaled services (sharing MongoDB and RabbitMQ) in production.
 *
 * <pre>
 *   java -jar orvanta-pay-all.jar                                   everything, as configured
 *   java -jar orvanta-pay-all.jar --units=api --server.port=8480    API and console only
 *   java -jar orvanta-pay-all.jar --units=debulk,process            two engine services
 *
 * Units: api, ingest, debulk, process, bulk, dispatch, acknowledge, cancel, return, report, recover, callback, post, reconcile
 * </pre>
 */
public final class OrvantaServer {

    private static final Logger LOG = LoggerFactory.getLogger(OrvantaServer.class);
    public static final List<String> UNITS = List.of("api", "ingest", "debulk", "process", "bulk", "dispatch", "acknowledge",
            "cancel", "return", "report", "recover", "callback", "post", "reconcile", "recall", "notify");

    public final Platform platform;
    public final IngestService ingest;
    public final DebulkService debulk;
    public final ProcessingService processing;
    public final BulkingService bulking;
    public final DispatchService dispatch;
    public final AcknowledgementService acknowledgement;
    public final CancellationService cancellation;
    public final ReturnService returns;
    public final io.orvanta.pay.engine.RecallService recalls;
    public final io.orvanta.pay.engine.StatusEnquiryService enquiries;
    public final io.orvanta.pay.engine.DeliveryService deliveries;
    public final io.orvanta.pay.engine.InvestigationService investigations;
    public final io.orvanta.pay.engine.RequestToPayService requestsToPay;
    public final io.orvanta.pay.engine.MandateService mandates;
    public final io.orvanta.pay.engine.NotificationService notifications;
    public final StatusReportService statusReports;
    public final RecoveryService recovery;
    public final io.orvanta.pay.engine.ScheduleService schedules;
    public final io.orvanta.pay.engine.ReviewService review;
    public final io.orvanta.pay.engine.CallbackService callbacks;
    public final io.orvanta.pay.engine.PostingService postings;
    public final io.orvanta.pay.engine.StatementService statements;
    public Simulator simulator;
    public final io.orvanta.pay.transport.TransportService transports;
    public final AuthService auth;
    public final ApprovalService approvals;
    public final io.orvanta.pay.engine.Alerts alerts;
    private ApiServer api;
    private com.sun.net.httpserver.HttpServer health;

    /** The version of this build, from the jar manifest; "dev" when run from classes. */
    public static final String BUILD = OrvantaServer.class.getPackage().getImplementationVersion() == null
            ? "dev" : OrvantaServer.class.getPackage().getImplementationVersion();

    public OrvantaServer(Config config, DocStore store, Bus bus) {
        platform = new Platform(config, store, bus);
        ingest = new IngestService(platform);
        debulk = new DebulkService(platform);
        processing = new ProcessingService(platform);
        bulking = new BulkingService(platform);
        dispatch = new DispatchService(platform);
        acknowledgement = new AcknowledgementService(platform);
        cancellation = new CancellationService(platform);
        returns = new ReturnService(platform);
        recalls = new io.orvanta.pay.engine.RecallService(platform);
        enquiries = new io.orvanta.pay.engine.StatusEnquiryService(platform);
        deliveries = new io.orvanta.pay.engine.DeliveryService(platform);
        investigations = new io.orvanta.pay.engine.InvestigationService(platform);
        requestsToPay = new io.orvanta.pay.engine.RequestToPayService(platform);
        mandates = new io.orvanta.pay.engine.MandateService(platform);
        notifications = new io.orvanta.pay.engine.NotificationService(platform);
        statusReports = new StatusReportService(platform);
        recovery = new RecoveryService(platform, processing);
        schedules = new io.orvanta.pay.engine.ScheduleService(platform);
        review = new io.orvanta.pay.engine.ReviewService(platform);
        callbacks = new io.orvanta.pay.engine.CallbackService(platform);
        postings = new io.orvanta.pay.engine.PostingService(platform);
        statements = new io.orvanta.pay.engine.StatementService(platform);
        transports = new io.orvanta.pay.transport.TransportService(platform, ingest);
        auth = new AuthService(platform);
        approvals = new ApprovalService(platform);
        alerts = new io.orvanta.pay.engine.Alerts(platform);
    }

    public void start() throws Exception {
        platform.startChangeNotices();
        Config config = platform.config;
        platform.deployments.start(platform.workspaceDir, config.getBool("workspace.syncOnStart", false));

        Set<String> units = new LinkedHashSet<>(Arrays.asList(config.get("units", "all").split("\\s*,\\s*")));
        if (units.contains("all")) {
            units = new LinkedHashSet<>(UNITS);
        }
        for (String unit : units) {
            if (!UNITS.contains(unit)) {
                throw new IllegalArgumentException("unknown unit '" + unit + "'. Known units: " + UNITS);
            }
        }
        // every process declares the queue of every service, so that an event never arrives before its queue:
        // the console may receive a file while the engine is still starting, or not yet deployed at all
        String[][] queues = {
                {Bus.INSTRUCTION_RECEIVED, "debulk"}, {Bus.TXN_CREATED, "process"}, {Bus.TXN_ROUTED, "bulk"},
                {Bus.OUTBOUND_CREATED, "dispatch"}, {Bus.ACK_RECEIVED, "acknowledge"}, {Bus.CANCELLATION_RECEIVED, "cancel"},
                {Bus.RESOLUTION_RECEIVED, "resolve"}, {Bus.RETURN_RECEIVED, "return"}, {Bus.inboundTopic("callback"), "callback"},
                {Bus.TXN_UNWOUND, "post"}, {Bus.inboundTopic("statement"), "reconcile"}, {Bus.inboundTopic("recall"), "recall"}, {Bus.inboundTopic("reversal"), "reversal"},
                {Bus.inboundTopic(io.orvanta.pay.engine.StatusEnquiryService.PURPOSE), "enquiry"},
                {Bus.inboundTopic(io.orvanta.pay.engine.DeliveryService.PURPOSE), "delivery"},
                {Bus.inboundTopic(io.orvanta.pay.engine.InvestigationService.PURPOSE), "investigate"},
                {Bus.inboundTopic(io.orvanta.pay.engine.RequestToPayService.PURPOSE), "rtp"},
                {Bus.inboundTopic(io.orvanta.pay.engine.MandateService.PURPOSE), "mandate"}};
        for (String[] queue : queues) {
            platform.bus.declare(queue[0], queue[1]);
        }
        boolean simulator = config.getBool("simulator.enabled", false);
        if (simulator) {
            this.simulator = new Simulator(platform);
        }
        if (units.contains("callback")) {
            callbacks.start();
        }
        if (units.contains("post")) {
            postings.start();
        }
        if (units.contains("reconcile")) {
            statements.start();
        }
        if (units.contains("debulk")) {
            // requests to pay and mandate messages arrive like instructions and are taken in by the same unit
            requestsToPay.start();
            mandates.start();
            debulk.start();
        }
        if (units.contains("process")) {
            processing.start();
        }
        if (units.contains("bulk")) {
            bulking.start();
        }
        if (units.contains("dispatch")) {
            dispatch.start();
        }
        if (units.contains("acknowledge")) {
            acknowledgement.start();
            // what the SWIFT network says about our messages is handled with the other acknowledgements
            deliveries.start();
        }
        if (units.contains("cancel")) {
            cancellation.start();
        }
        if (units.contains("return")) {
            returns.start();
        }
        if (units.contains("recall")) {
            recalls.start();
            // status enquiries and investigations from other banks are handled by the same unit as their recalls
            enquiries.start();
            investigations.start();
        }
        if (units.contains("notify")) {
            notifications.start();
        }
        if (units.contains("report")) {
            statusReports.start();
        }
        if (units.contains("recover")) {
            recovery.start();
            // jobs at the times Schedule models name; every recover process looks, one of them runs each job
            schedules.start();
        }
        if (units.contains("ingest")) {
            ingest.startFolderPolling();
            transports.start();
            if (simulator) {
                this.simulator.start();
            }
        }
        if (units.contains("api")) {
            auth.seed(config.baseDir().resolve(config.get("security.seedFile", "config/seed-users.yaml")));
            api = new ApiServer(platform, auth, approvals, ingest, processing, dispatch, bulking, cancellation, transports, review, this.simulator, alerts, investigations, statements, requestsToPay, schedules);
            // incidents are watched where the Console is: one process per installation, not one per engine unit
            if (config.getBool("alerts.enabled", true)) {
                alerts.start();
            }
            int port = config.getInt("server.port", 8480);
            api.start(port);
            LOG.info("Orvanta Console and API: http://localhost:{}/", port);
        }
        int healthPort = config.getInt("health.port", 0);
        if (!units.contains("api") && healthPort > 0) {
            Set<String> running = units;
            health = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(healthPort), 0);
            health.createContext("/health", exchange -> {
                boolean up = platform.store.ping() && !platform.stopping;
                byte[] body = io.orvanta.core.json.Json.write(io.orvanta.core.data.Rec.of("status", platform.stopping ? "STOPPING" : up ? "UP" : "DEGRADED",
                        "units", new java.util.ArrayList<>(running), "deployment", platform.deployments.activeId(), "build", BUILD))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(up ? 200 : 503, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            health.start();
            LOG.info("health endpoint: http://localhost:{}/health", healthPort);
        }
        LOG.info("Orvanta Pay {} started. units={} store={} bus={} deployment={}", BUILD, units,
                platform.store.getClass().getSimpleName(), platform.bus.getClass().getSimpleName(), platform.deployments.activeId());
    }

    /**
     * Stops in an order that loses nothing: first no new work is taken (health says STOPPING, listeners stop,
     * the bus stops taking messages), then what is being handled is given up to shutdown.graceSeconds (30)
     * to finish, then the rest is closed. What was not finished stays in the queues for the next start.
     */
    public void stop() {
        platform.stopping = true;
        platform.stopChangeNotices();
        ingest.stop();
        transports.stop();
        platform.bus.pause();
        bulking.stop();
        statusReports.stop();
        notifications.stop();
        alerts.stop();
        schedules.stop();
        recovery.stop();
        long deadline = System.currentTimeMillis() + 1000L * Math.max(0, platform.config.getInt("shutdown.graceSeconds", 30));
        while (platform.bus.inFlight() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (platform.bus.inFlight() > 0) {
            LOG.warn("stopping with {} message(s) still being handled; they will be picked up again", platform.bus.inFlight());
        } else {
            LOG.info("stopped cleanly: nothing was being handled");
        }
        if (api != null) {
            api.stop();
        }
        if (health != null) {
            health.stop(0);
        }
        platform.rabbit.close();
        platform.kafka.close();
        platform.bus.close();
        platform.store.close();
    }

    public static void main(String[] args) {
        try {
            launch(args);
        } catch (Throwable e) {
            LOG.error("Orvanta Pay could not start", e);
            System.exit(1);
        }
    }

    private static void launch(String[] args) throws Exception {
        String configPath = "config/orvanta.yaml";
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
            }
        }
        Config config = Config.load(Path.of(configPath), args);
        DocStore store = "memory".equals(config.get("store.type", "mongo"))
                ? new MemoryDocStore()
                : new MongoDocStore(config.get("store.uri", "mongodb://localhost:27017"), config.get("store.database", "orvanta"));
        Bus bus = "rabbit".equals(config.get("bus.type", "memory"))
                ? new RabbitBus(config.get("bus.uri", "amqp://localhost:5672"), config.getInt("bus.consumers", 4))
                : new MemoryBus();
        OrvantaServer server = new OrvantaServer(config, store, bus);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "orv-shutdown"));
        server.start();
    }
}
