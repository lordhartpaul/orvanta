package io.orvanta.pay.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Connector;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.json.Json;
import io.orvanta.forge.Forge;
import io.orvanta.core.format.IsoXml;
import io.orvanta.core.format.Messages;
import io.orvanta.forge.ModelException;
import io.orvanta.forge.ModelSource;
import io.orvanta.forge.Problem;
import io.orvanta.forge.TestRunner;
import io.orvanta.pay.engine.BulkingService;
import io.orvanta.pay.engine.CancellationService;
import io.orvanta.pay.engine.DispatchService;
import io.orvanta.pay.engine.IngestService;
import io.orvanta.pay.engine.ProcessingService;
import io.orvanta.pay.engine.Status;
import io.orvanta.pay.kernel.Deployments;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import io.orvanta.pay.security.ApprovalService;
import io.orvanta.pay.security.AuthService;
import io.orvanta.pay.security.AuthService.Principal;
import io.orvanta.pay.security.AuthService.Scope;
import io.orvanta.pay.security.Crypto;
import io.orvanta.pay.sim.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** REST API of Orvanta Pay and host of the Orvanta Console (static files under /). */
public final class ApiServer {

    private static final class ApiError extends RuntimeException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Platform platform;
    private final AuthService auth;
    private final ApprovalService approvals;
    private final IngestService ingest;
    private final ProcessingService processing;
    private final DispatchService dispatch;
    private final BulkingService bulking;
    private final CancellationService cancellation;
    private final io.orvanta.pay.transport.TransportService transports;
    private final io.orvanta.pay.engine.ReviewService review;
    private final Simulator simulator;
    private final io.orvanta.pay.security.SecurityLog security;
    private final Metrics metrics;
    private final io.orvanta.pay.engine.Alerts alerts;
    private final io.orvanta.pay.engine.InvestigationService investigations;
    private final io.orvanta.pay.engine.StatementService statements;
    private final io.orvanta.pay.engine.RequestToPayService requestsToPay;
    private final io.orvanta.pay.security.LoginThrottle throttle;
    private Javalin app;
    private io.javalin.config.RoutesConfig routes;

    public ApiServer(Platform platform, AuthService auth, ApprovalService approvals, IngestService ingest,
                     ProcessingService processing, DispatchService dispatch, BulkingService bulking,
                     CancellationService cancellation, io.orvanta.pay.transport.TransportService transports,
                     io.orvanta.pay.engine.ReviewService review, Simulator simulator, io.orvanta.pay.engine.Alerts alerts,
                     io.orvanta.pay.engine.InvestigationService investigations, io.orvanta.pay.engine.StatementService statements,
                     io.orvanta.pay.engine.RequestToPayService requestsToPay, io.orvanta.pay.engine.ScheduleService schedules) {
        this.schedules = schedules;
        this.sso = io.orvanta.pay.security.OpenIdConnect.fromConfig(platform.config);
        this.investigations = investigations;
        this.statements = statements;
        this.requestsToPay = requestsToPay;
        this.alerts = alerts;
        this.platform = platform;
        this.auth = auth;
        this.approvals = approvals;
        this.ingest = ingest;
        this.processing = processing;
        this.dispatch = dispatch;
        this.bulking = bulking;
        this.cancellation = cancellation;
        this.transports = transports;
        this.review = review;
        this.simulator = simulator;
        this.security = new io.orvanta.pay.security.SecurityLog(platform);
        this.metrics = new Metrics(platform);
        this.throttle = new io.orvanta.pay.security.LoginThrottle(platform.config.getInt("security.loginAttempts", 10),
                platform.config.getInt("security.loginWindowSeconds", 300));
        registerApprovalActions();
    }

    public void start(int port) {
        String keystore = platform.config.get("server.tls.keystore", null);
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.startup.showOldJavalinVersionWarning = false;
            cfg.http.maxRequestSize = 64L * 1024 * 1024;
            // do not tell callers which server software and version this is
            cfg.jetty.modifyHttpConfiguration(http -> http.setSendServerVersion(false));
            if (keystore != null) {
                // HTTPS on the server port, from a PKCS12 or JKS keystore; without it, TLS must end at a proxy in front
                cfg.jetty.addConnector((server, http) -> {
                    org.eclipse.jetty.util.ssl.SslContextFactory.Server tls = new org.eclipse.jetty.util.ssl.SslContextFactory.Server();
                    tls.setKeyStorePath(platform.config.baseDir().resolve(keystore).toUri().toString());
                    tls.setKeyStorePassword(platform.config.get("server.tls.password", ""));
                    tls.setExcludeProtocols("SSLv3", "TLSv1", "TLSv1.1");
                    org.eclipse.jetty.server.HttpConfiguration https = new org.eclipse.jetty.server.HttpConfiguration(http);
                    https.setSendServerVersion(false);
                    https.addCustomizer(new org.eclipse.jetty.server.SecureRequestCustomizer());
                    org.eclipse.jetty.server.ServerConnector connector = new org.eclipse.jetty.server.ServerConnector(server,
                            new org.eclipse.jetty.server.SslConnectionFactory(tls, "http/1.1"),
                            new org.eclipse.jetty.server.HttpConnectionFactory(https));
                    connector.setPort(port);
                    return connector;
                });
            }
            cfg.staticFiles.add(s -> {
                s.hostedPath = "/";
                s.directory = "/console";
                s.location = Location.CLASSPATH;
            });
            routes = cfg.routes;
            declareRoutes(keystore != null || platform.config.getBool("server.behindTlsProxy", false));
        });
        app.start(port);
        startStreams();
    }

    private void declareRoutes(boolean secureTransport) {
        routes.before(ctx -> {
            // the console loads only its own scripts and styles, cannot be framed, and sends no referrer
            ctx.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; "
                    + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "no-referrer");
            ctx.header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
            ctx.header("Cross-Origin-Opener-Policy", "same-origin");
            ctx.header("Cross-Origin-Resource-Policy", "same-origin");
            ctx.header("Cross-Origin-Embedder-Policy", "require-corp");
            if (secureTransport) {
                ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
        });
        routes.before("/api/*", ctx -> ctx.attribute("startedAt", System.nanoTime()));
        routes.after("/api/*", ctx -> {
            // the route, not the path: ids stay out of the metric names
            Long started = ctx.attribute("startedAt");
            if (started != null) {
                metrics.request(Metrics.routeOf(ctx.path()), ctx.statusCode(), (System.nanoTime() - started) / 1_000_000);
            }
        });
        // numbers for a monitoring system: answered to this machine, or to anyone showing the configured token
        String metricsToken = platform.config.get("metrics.token", "");
        routes.get("/metrics", ctx -> {
            if (!platform.config.getBool("metrics.enabled", true)) {
                throw new ApiError(404, "metrics are off");
            }
            String bearer = ctx.header("Authorization");
            boolean allowed = metricsToken.isBlank() ? java.net.InetAddress.getByName(ctx.ip()).isLoopbackAddress()
                    : bearer != null && java.security.MessageDigest.isEqual(("Bearer " + metricsToken).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            bearer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!allowed) {
                throw new ApiError(403, metricsToken.isBlank() ? "metrics are answered only to this machine unless metrics.token is set" : "the metrics token is missing or wrong");
            }
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8").result(metrics.render());
        });
        routes.before("/api/*", ctx -> {
            ctx.header("Cache-Control", "no-store");
            String path = ctx.path();
            if (path.equals("/api/auth/login") || path.equals("/api/auth/session") || path.equals("/api/health")
                    || path.equals("/api/auth/sso/start") || path.equals("/api/auth/sso/callback")) {
                // single sign-on starts and ends before there is a session
                return;
            }
            String header = ctx.header("Authorization");
            Principal principal = header != null && header.startsWith("Bearer ") ? auth.authenticate(header.substring(7)) : null;
            if (principal == null && header == null && ctx.header("X-Api-Key") != null) {
                // a system calling with its key: no session, no cookie, what its roles allow
                principal = auth.authenticateApiKey(ctx.header("X-Api-Key"));
                if (principal == null) {
                    security.record("DENIED", null, address(ctx), "API key refused for " + ctx.method() + " " + path);
                    throw new ApiError(401, "the API key is unknown, disabled or wrong");
                }
            }
            if (principal == null && header == null && ctx.cookie(SESSION_COOKIE) != null) {
                // the Console's session: a cookie that scripts cannot read. A browser sends it by itself, so a request
                // that changes something must also carry a header that only the Console's own script can add.
                principal = auth.authenticate(ctx.cookie(SESSION_COOKIE));
                boolean reads = ctx.method() == io.javalin.http.HandlerType.GET || ctx.method() == io.javalin.http.HandlerType.HEAD;
                if (principal != null && !reads && !"1".equals(ctx.header("X-Orvanta-Console"))) {
                    throw new ApiError(403, "this request did not come from the Console");
                }
            }
            if (principal == null) {
                throw new ApiError(401, "sign in required");
            }
            ctx.attribute("principal", principal);
        });
        routes.exception(ApiError.class, (e, ctx) -> {
            if (e.status == 403) {
                Principal who = ctx.attribute("principal");
                security.record("DENIED", who == null ? null : who.username(), address(ctx), ctx.method() + " " + ctx.path() + ": " + e.getMessage());
            }
            error(ctx, e.status, e.getMessage());
        });
        routes.exception(SecurityException.class, (e, ctx) -> {
            Principal who = ctx.attribute("principal");
            security.record("DENIED", who == null ? null : who.username(), address(ctx), ctx.method() + " " + ctx.path() + ": " + e.getMessage());
            error(ctx, 403, e.getMessage());
        });
        // anything unexpected: the caller gets a reference, the log gets the details
        routes.exception(Exception.class, (e, ctx) -> {
            String reference = java.util.UUID.randomUUID().toString().substring(0, 8);
            org.slf4j.LoggerFactory.getLogger(ApiServer.class).error("request {} {} failed (reference {})", ctx.method(), ctx.path().replaceAll("[\\r\\n\\t]", "_"), reference, e);
            error(ctx, 500, "the request could not be completed (reference " + reference + ")");
        });
        routes.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        routes.exception(IllegalStateException.class, (e, ctx) -> error(ctx, 409, e.getMessage()));
        routes.exception(ModelException.class, (e, ctx) -> error(ctx, 400, (e.where().isEmpty() ? "" : e.where() + ": ") + e.getMessage()));

        routes.get("/api/health", ctx -> {
            // while the process stops it says so with 503, so a load balancer sends nothing more
            boolean stopping = platform.stopping;
            if (stopping) {
                ctx.status(503);
            }
            json(ctx, Rec.of("status", stopping ? "STOPPING" : platform.store.ping() ? "UP" : "DEGRADED",
                    "deployment", platform.deployments.activeId(), "version", platform.deployments.activeVersion(),
                    "build", io.orvanta.pay.OrvantaServer.BUILD));
        });
        // incidents: conditions that need a person, opened and closed by the alert check
        routes.get("/api/incidents", ctx -> {
            unscoped(need(ctx, "payments.view"));
            json(ctx, Rec.of("items", "true".equals(ctx.queryParam("all")) ? platform.store.find(io.orvanta.pay.engine.Alerts.INCIDENT, new Rec(), "updatedAt", true, 200) : alerts.open()));
        });
        routes.post("/api/incidents/check", ctx -> {
            unscoped(need(ctx, "payments.repair"));
            json(ctx, Rec.of("changed", alerts.check(), "items", alerts.open()));
        });
        // the settings of an IBM MQ transport or destination are tried: connect, look at the queue, disconnect. Nothing is read or put.
        routes.post("/api/channels/{name}/mq-check", ctx -> {
            unscoped(need(ctx, "payments.repair"));
            Rec channel = platform.deployments.registry().config(ctx.pathParam("name"));
            if (channel == null || !"Channel".equals(channel.str("kind"))) {
                throw new ApiError(404, "'" + ctx.pathParam("name") + "' is not a channel");
            }
            List<Rec> settings = new ArrayList<>();
            if ("ibmmq".equals(String.valueOf(channel.at("destination.type")))) {
                settings.add(channel.rec("destination"));
            }
            for (Object t : Ops.list(channel.get("transport"))) {
                if (t instanceof Rec transport && "ibmmq".equals(transport.str("type"))) {
                    settings.add(transport);
                }
            }
            if (settings.isEmpty()) {
                throw new ApiError(409, "the channel has no IBM MQ transport or destination");
            }
            List<Object> results = new ArrayList<>();
            for (Rec s : settings) {
                try {
                    results.add(io.orvanta.pay.transport.IbmMqLinks.probe(s));
                } catch (Exception e) {
                    results.add(Rec.of("connected", false, "queueManager", s.str("queueManager"), "queue", s.str("queue"), "host", s.str("host"), "port", s.get("port"),
                            "problem", e instanceof com.ibm.mq.MQException mq ? "MQ reason " + mq.reasonCode + " (" + mq.getMessage() + ")" : String.valueOf(e.getMessage())));
                }
            }
            json(ctx, Rec.of("channel", channel.str("name"), "results", results));
        });
        routes.get("/api/transports", ctx -> {
            need(ctx, "payments.view");
            json(ctx, Rec.of("listening", Rec.from(transports.listening())));
        });
        // system-to-system push: no user session, the caller proves itself with the key of the channel
        routes.post("/in/{channel}", ctx -> {
            Rec channel = platform.deployments.registry().config(ctx.pathParam("channel"));
            String key = null;
            if (channel != null && "Channel".equals(channel.str("kind")) && "inbound".equals(channel.str("direction"))) {
                for (Object t : Ops.list(channel.get("transport"))) {
                    if (t instanceof Rec transport && "rest".equals(transport.str("type")) && transport.str("apiKey") != null
                            && transport.str("apiKey").length() >= 16) {
                        key = transport.str("apiKey");
                    }
                }
            }
            String given = ctx.header("X-Api-Key");
            String actor = "api-key:" + (channel == null ? "?" : channel.str("name"));
            // a system's own key (issued under Users and roles, rotated without a redeployment) is taken as well: it must be allowed
            // to submit, and to this channel when it is limited to channels
            boolean byOwnKey = false;
            if (channel != null && "Channel".equals(channel.str("kind")) && "inbound".equals(channel.str("direction")) && given != null && given.startsWith("orv_")) {
                Principal system = auth.authenticateApiKey(given);
                Rec record = system == null ? null : platform.store.get(DocStore.API_KEY, system.username().substring("key:".length()));
                List<?> channels = record == null ? List.of() : Ops.list(record.get("channels"));
                byOwnKey = system != null && system.can("payments.submit") && (channels.isEmpty() || channels.contains(channel.str("name")));
                if (byOwnKey) {
                    actor = system.username();
                }
            }
            // the same answer for an unknown channel, a channel without a key and a wrong key
            if (!byOwnKey && (key == null || given == null || !java.security.MessageDigest.isEqual(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8), given.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                security.record("DENIED", null, address(ctx), "POST /in/" + ctx.pathParam("channel") + ": the key is not accepted");
                throw new ApiError(403, "the channel does not accept this request");
            }
            if (ctx.body().isBlank()) {
                throw new ApiError(400, "the request body is empty: post the message as the body");
            }
            ctx.status(202);
            json(ctx, Rec.of("messages", ingest.receive(ctx.body(), ctx.queryParam("fileName"), channel.str("name"), actor)));
        });
        // system-to-system push as a SOAP web service: the message is the first element of the Body; the answer is a SOAP envelope
        // with a receipt per message stored, or a fault when the request is refused. The caller proves itself as on /in/<channel>.
        routes.post("/in/soap/{channel}", ctx -> {
            Rec channel = platform.deployments.registry().config(ctx.pathParam("channel"));
            String given = ctx.header("X-Api-Key");
            String staticKey = null;
            if (channel != null && "Channel".equals(channel.str("kind")) && "inbound".equals(channel.str("direction"))) {
                for (Object t : Ops.list(channel.get("transport"))) {
                    if (t instanceof Rec transport && "rest".equals(transport.str("type")) && transport.str("apiKey") != null && transport.str("apiKey").length() >= 16) {
                        staticKey = transport.str("apiKey");
                    }
                }
            }
            String actor = "soap:" + (channel == null ? "?" : channel.str("name"));
            boolean allowed = false;
            if (channel != null && given != null && given.startsWith("orv_")) {
                Principal system = auth.authenticateApiKey(given);
                Rec record = system == null ? null : platform.store.get(DocStore.API_KEY, system.username().substring("key:".length()));
                List<?> channels = record == null ? List.of() : Ops.list(record.get("channels"));
                allowed = system != null && system.can("payments.submit") && (channels.isEmpty() || channels.contains(channel.str("name")));
                if (allowed) {
                    actor = system.username();
                }
            }
            if (!allowed && staticKey != null && given != null && java.security.MessageDigest.isEqual(
                    staticKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), given.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                allowed = true;
            }
            ctx.contentType("text/xml; charset=utf-8");
            if (!allowed) {
                security.record("DENIED", null, address(ctx), "POST /in/soap/" + ctx.pathParam("channel") + ": the key is not accepted");
                ctx.status(403).result(soapFault("Client", "the channel does not accept this request"));
                return;
            }
            String message = soapBodyContent(ctx.body());
            if (message == null) {
                ctx.status(400).result(soapFault("Client", "the request is not a SOAP envelope with one message in its Body"));
                return;
            }
            List<Rec> stored = ingest.receive(message, ctx.queryParam("fileName"), channel.str("name"), actor);
            Rec receipts = new Rec();
            List<Object> items = new ArrayList<>();
            for (Rec m : stored) {
                items.add(Rec.of("messageId", m.str("id"), "messageType", m.str("messageType"), "status", m.str("status"),
                        "reasonCode", m.str("reasonCode"), "reasonText", m.str("reasonText")));
            }
            receipts.put("@xmlns", "urn:orvanta:receipt");
            receipts.put("receipt", items);
            Rec body = new Rec();
            body.put("Receipts", receipts);
            Rec envelope = new Rec();
            envelope.put("@xmlns", "http://schemas.xmlsoap.org/soap/envelope/");
            envelope.put("Body", body);
            Rec tree = new Rec();
            tree.put("Envelope", envelope);
            ctx.status(stored.stream().allMatch(m -> Status.REJECTED.equals(m.str("status"))) ? 422 : 202).result(IsoXml.write(tree));
        });
        routes.post("/api/auth/login", ctx -> {
            Rec body = Json.parse(ctx.body());
            String username = body.str("username") == null ? "" : body.str("username").trim().toLowerCase(java.util.Locale.ROOT);
            String address = address(ctx);
            long wait = throttle.retryAfterSeconds(address, username);
            if (wait > 0) {
                security.record("LOGIN_THROTTLED", username, address, "too many failed sign-ins");
                ctx.header("Retry-After", String.valueOf(wait));
                throw new ApiError(429, "too many failed sign-ins; try again in " + wait + " seconds");
            }
            Rec session = auth.login(username, body.str("password"), body.str("code"));
            if (session != null && Boolean.TRUE.equals(session.get("mfaRequired"))) {
                // the password was right; the session starts only with the code
                ctx.status(401);
                json(ctx, Rec.of("error", "enter the code from your authenticator app", "mfaRequired", true));
                return;
            }
            if (session == null) {
                throttle.failed(address, username);
                security.record("LOGIN_FAILED", username, address, null);
                String locked = auth.takeLastLocked();
                if (locked != null) {
                    security.record("USER_LOCKED", locked, address, "too many wrong passwords");
                }
                // one answer for every reason, so it does not reveal which user names exist
                throw new ApiError(401, "user name or password is wrong, or the user is locked");
            }
            throttle.succeeded(address, username);
            security.record("LOGIN_OK", username, address, Boolean.TRUE.equals(session.get("recoveryCodeUsed")) ? "with a recovery code; " + session.get("recoveryCodesLeft") + " left" : null);
            if ("cookie".equals(body.str("session"))) {
                // the Console asks for its token in a cookie: it never reaches a script, and it is not in the answer
                ctx.header("Set-Cookie", sessionCookie(String.valueOf(session.remove("token")), secureTransport, false));
            }
            json(ctx, session);
        });
        // whether the browser's cookie stands for a session: asked by the Console when a page loads, answered to anyone, says nothing else
        // (besides whether single sign-on is offered, which the sign-in page needs before anyone is signed in)
        routes.get("/api/auth/session", ctx -> json(ctx, Rec.of("signedIn",
                ctx.cookie(SESSION_COOKIE) != null && auth.authenticate(ctx.cookie(SESSION_COOKIE)) != null,
                "sso", sso != null, "ssoLabel", sso == null ? null : sso.label())));
        // ---- single sign-on: the browser goes to the provider and comes back with a code; the code becomes a session ----
        routes.get("/api/auth/sso/start", ctx -> {
            if (sso == null) {
                throw new ApiError(404, "single sign-on is not set up");
            }
            String nonce = Crypto.randomSecret();
            // the state is signed by this process: the answer from the provider is taken only for a sign-in this process started, within ten minutes
            String state = auth.signState(Rec.of("nonce", nonce, "address", address(ctx)), 600);
            ctx.redirect(sso.authorizationUrl(state, nonce), io.javalin.http.HttpStatus.FOUND);
        });
        routes.get("/api/auth/sso/callback", ctx -> {
            if (sso == null) {
                throw new ApiError(404, "single sign-on is not set up");
            }
            String address = address(ctx);
            Rec state = auth.verifyState(ctx.queryParam("state"));
            if (state == null) {
                security.record("LOGIN_FAILED", null, address, "single sign-on: the state is missing, altered or older than ten minutes");
                throw new ApiError(400, "this sign-in was not started here, or it took too long; start again");
            }
            if (ctx.queryParam("error") != null || ctx.queryParam("code") == null) {
                security.record("LOGIN_FAILED", null, address, "single sign-on: the provider answered " + ctx.queryParam("error") + " " + ctx.queryParam("error_description"));
                ctx.redirect("/?sso=refused", io.javalin.http.HttpStatus.FOUND);
                return;
            }
            String username;
            try {
                username = sso.signIn(ctx.queryParam("code"), state.str("nonce"));
            } catch (IllegalArgumentException e) {
                security.record("LOGIN_FAILED", null, address, "single sign-on: " + e.getMessage());
                ctx.redirect("/?sso=failed", io.javalin.http.HttpStatus.FOUND);
                return;
            }
            Rec session = auth.loginExternal(username);
            if (session == null) {
                // the provider knows the person, Orvanta does not: nobody gets in without a user here
                security.record("LOGIN_FAILED", username, address, "single sign-on: no active user by that name");
                ctx.redirect("/?sso=unknown", io.javalin.http.HttpStatus.FOUND);
                return;
            }
            security.record("LOGIN_OK", username, address, "single sign-on via " + sso.issuer());
            ctx.header("Set-Cookie", sessionCookie(String.valueOf(session.remove("token")), secureTransport, false));
            ctx.redirect("/", io.javalin.http.HttpStatus.FOUND);
        });
        routes.post("/api/auth/logout", ctx -> {
            Principal p = principal(ctx);
            auth.revokeTokens(p.username());
            security.record("LOGOUT", p.username(), address(ctx), null);
            ctx.header("Set-Cookie", sessionCookie("", secureTransport, true));
            json(ctx, Rec.of("ok", true));
        });
        // ---- keys of systems that call the API without a person signing in ----
        routes.get("/api/keys", ctx -> {
            need(ctx, "admin.view");
            json(ctx, Rec.of("items", without(platform.store.find(DocStore.API_KEY, null, "id", false, 500), "keyHash")));
        });
        routes.post("/api/keys", ctx -> {
            Principal p = person(need(ctx, "admin.edit"), "keys are issued by people, not by a system's key");
            Rec body = Json.parse(ctx.body());
            String id = required(body, "id");
            if (!id.matches("[a-z][a-z0-9-]{2,31}")) {
                throw new ApiError(400, "a key name is 3 to 32 lower case letters, digits or dashes");
            }
            List<Object> roles = new ArrayList<>(Ops.list(body.get("roles")));
            if (roles.isEmpty()) {
                throw new ApiError(400, "a key needs at least one role");
            }
            for (Object role : roles) {
                Rec r = platform.store.get(DocStore.ROLE, Ops.str(role));
                if (r == null) {
                    throw new ApiError(400, "no role named " + role);
                }
                // a system may do what an operator does; deciding and administering stay with people
                for (Object perm : Ops.list(r.get("permissions"))) {
                    String s = Ops.str(perm);
                    if (s.equals("*") || s.endsWith(".approve") || s.startsWith("admin.")) {
                        throw new ApiError(400, "role " + role + " has '" + s + "'; a system's key may not approve or administer");
                    }
                }
            }
            // the inbound channels the key may push to with POST /in/<channel>; none named means any
            List<Object> channels = new ArrayList<>(Ops.list(body.get("channels")));
            for (Object c : channels) {
                Rec config = platform.deployments.registry().config(Ops.str(c));
                if (config == null || !"Channel".equals(config.str("kind")) || !"inbound".equals(config.str("direction"))) {
                    throw new ApiError(400, "'" + c + "' is not an inbound channel");
                }
            }
            Rec existing = platform.store.get(DocStore.API_KEY, id);
            boolean rotate = existing == null || Boolean.TRUE.equals(body.get("rotate"));
            // the secret is made now and shown once to the person asking; it works only once the request is approved
            String secret = rotate ? Crypto.randomSecret().replace('-', 'x').replace('_', 'y') : null;
            Rec payload = Rec.of("id", id, "description", body.str("description"), "roles", roles, "status", body.str("status") == null ? "ACTIVE" : body.str("status"),
                    "channels", channels);
            if (secret != null) {
                payload.put("keyHash", AuthService.hashKey(secret));
            }
            Rec approval = approvals.request("API_KEY_CHANGE", (existing == null ? "Create API key " : rotate ? "Rotate API key " : "Change API key ") + id
                    + " (" + payload.str("status") + ") with roles " + roles, payload, p.username(), body.str("comment"));
            security.record("API_KEY_REQUESTED", p.username(), address(ctx), id + (rotate ? " with a new secret" : ""));
            Rec out = approval.copy();
            out.put("payload", approval.rec("payload").copy());
            out.rec("payload").remove("keyHash");
            if (secret != null) {
                out.put("key", "orv_" + id + "_" + secret);
            }
            json(ctx, out);
        });
        // ---- scheduled jobs: what Schedule models say, their last runs, and running one now ----
        routes.get("/api/schedules", ctx -> {
            need(ctx, "payments.view");
            json(ctx, Rec.of("items", schedules.list()));
        });
        routes.post("/api/schedules/{name}/run", ctx -> {
            Principal p = person(need(ctx, "payments.repair"), "a schedule is run by a person");
            Rec def = platform.deployments.registry().config(ctx.pathParam("name"));
            if (def == null || !"Schedule".equals(def.str("kind"))) {
                throw new ApiError(404, "no schedule named " + ctx.pathParam("name"));
            }
            security.record("SCHEDULE_RUN", p.username(), address(ctx), def.str("name"));
            json(ctx, schedules.run(def, p.username()));
        });
        routes.get("/api/security/events", ctx -> {
            need(ctx, "admin.view");
            list(ctx, listPage(ctx, DocStore.SECURITY, filter(ctx, "type", "username"), List.of("username", "address", "detail", "type"),
                    List.of("at", "type", "username"), "at", "at"));
        });
        routes.get("/api/me", ctx -> {
            Rec me = principal(ctx).toRec();
            me.put("mfa", auth.mfaState(principal(ctx).username()));
            json(ctx, me);
        });
        // the second step of sign-in is the user's own to set up; turning it off needs a current code
        routes.post("/api/me/mfa/enroll", ctx -> {
            Principal p = principal(ctx);
            try {
                Rec enrolment = auth.mfaEnroll(p.username());
                security.record("MFA_ENROLL", p.username(), address(ctx), "secret issued");
                json(ctx, enrolment);
            } catch (IllegalStateException e) {
                throw new ApiError(409, e.getMessage());
            }
        });
        routes.post("/api/me/mfa/confirm", ctx -> {
            Principal p = principal(ctx);
            List<String> recoveryCodes = auth.mfaConfirm(p.username(), Json.parse(ctx.body()).str("code"));
            if (recoveryCodes == null) {
                security.record("MFA_FAILED", p.username(), address(ctx), "wrong code at set-up");
                throw new ApiError(422, "that is not the current code; check the time on the device and try the next code");
            }
            security.record("MFA_ON", p.username(), address(ctx), null);
            Rec state = auth.mfaState(p.username());
            state.put("recoveryCodes", recoveryCodes);
            json(ctx, state);
        });
        // new recovery codes, for a user who has used some or lost the list; the old ones stop working
        routes.post("/api/me/mfa/recovery", ctx -> {
            Principal p = principal(ctx);
            List<String> recoveryCodes = auth.mfaRecoveryCodes(p.username(), Json.parse(ctx.body()).str("code"));
            if (recoveryCodes == null) {
                security.record("MFA_FAILED", p.username(), address(ctx), "wrong code at new recovery codes");
                throw new ApiError(422, "that is not the current code, or the second step is not on");
            }
            security.record("MFA_RECOVERY_NEW", p.username(), address(ctx), null);
            Rec state = auth.mfaState(p.username());
            state.put("recoveryCodes", recoveryCodes);
            json(ctx, state);
        });
        routes.post("/api/me/mfa/disable", ctx -> {
            Principal p = principal(ctx);
            if (auth.mfaRequired()) {
                throw new ApiError(409, "this installation requires the second step; it cannot be turned off");
            }
            if (!auth.mfaDisable(p.username(), Json.parse(ctx.body()).str("code"))) {
                security.record("MFA_FAILED", p.username(), address(ctx), "wrong code at turning off");
                throw new ApiError(422, "that is not the current code");
            }
            security.record("MFA_OFF", p.username(), address(ctx), null);
            json(ctx, auth.mfaState(p.username()));
        });
        // the Console keeps this open and is told when something changed, so that it can fetch the page again.
        // Only the kind of change is sent ("payments", "approvals", "models"), never data: what the user may see is decided by the request that follows.
        routes.sse("/api/stream", client -> {
            Principal p = principal(client.ctx());
            long open = streams.values().stream().filter(p.username()::equals).count();
            if (open >= MAX_STREAMS_PER_USER) {
                client.sendEvent("refused", "too many open pages");
                client.close();
                return;
            }
            client.keepAlive();
            streams.put(client, p.username());
            client.onClose(() -> streams.remove(client));
            client.sendEvent("hello", "{}");
        });

        payments();
        dataSets();
        studio();
        administration();
        // endpoints defined by Api models; resolved per request, so a new deployment changes them without a restart
        // the model-defined APIs described for tools and partners (OpenAPI 3.0), from the Api models of the active deployment
        routes.get("/api/openapi.json", ctx -> {
            principal(ctx);
            Rec paths = new Rec();
            List<Rec> apis = new ArrayList<>(platform.deployments.registry().configs("Api"));
            apis.sort(java.util.Comparator.comparing((Rec a) -> a.str("path")).thenComparing(a -> a.str("method")));
            for (Rec a : apis) {
                String method = a.str("method").toLowerCase(java.util.Locale.ROOT);
                List<Object> parameters = new ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}").matcher(a.str("path"));
                while (m.find()) {
                    parameters.add(Rec.of("name", m.group(1), "in", "path", "required", true, "schema", Rec.of("type", "string")));
                }
                Rec operation = Rec.of("operationId", a.str("name"), "summary", a.str("description") == null ? a.str("name") : a.str("description").trim(),
                        "x-orvanta-permission", a.str("permission"), "x-orvanta-target", a.str("target"), "x-orvanta-approval", Boolean.TRUE.equals(a.get("approval")), "parameters", parameters,
                        "security", List.of(Rec.of("bearer", List.of()), Rec.of("apiKey", List.of()), Rec.of("cookie", List.of())));
                if (!method.equals("get") && !method.equals("delete")) {
                    Rec schema = Rec.of("type", "object", "additionalProperties", true);
                    if (a.get("request") instanceof Map<?, ?> example) {
                        schema.put("example", example);
                    }
                    operation.put("requestBody", Rec.of("required", true, "content", Rec.of("application/json", Rec.of("schema", schema))));
                }
                Rec ok = Rec.of("type", "object", "additionalProperties", true);
                if (a.get("response") instanceof Map<?, ?> example) {
                    ok.put("example", example);
                }
                operation.put("responses", Rec.of(
                        "200", Rec.of("description", "What the target model produced", "content", Rec.of("application/json", Rec.of("schema", ok))),
                        "202", Rec.of("description", Boolean.TRUE.equals(a.get("approval")) && !method.equals("get")
                                ? "The call was recorded as a request (its id and status in the body) and runs when a second person approves it"
                                : "Not used by this operation"),
                        "400", Rec.of("description", "The request is not what the model expects; 'error' says what is wrong"),
                        "401", Rec.of("description", "No session, token or API key"),
                        "403", Rec.of("description", "The caller lacks the permission " + a.str("permission")),
                        "404", Rec.of("description", "Nothing at this path"),
                        "422", Rec.of("description", "The model refused the request; 'error' says why")));
                Rec item = paths.get(a.str("path")) instanceof Rec existing ? existing : new Rec();
                item.put(method, operation);
                paths.put(a.str("path"), item);
            }
            Rec document = Rec.of("openapi", "3.0.3",
                    "info", Rec.of("title", "Orvanta model-defined APIs", "version", String.valueOf(platform.deployments.activeVersion()),
                            "description", "The REST endpoints defined by Api models of deployment " + platform.deployments.activeId()
                                    + ". Each serves a flow, mapping, rule set or decision table under /api/x, to callers with the named permission."),
                    "servers", List.of(Rec.of("url", "/api/x")),
                    "paths", paths,
                    "components", Rec.of("securitySchemes", Rec.of(
                            "bearer", Rec.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT", "description", "A token from POST /api/auth/login"),
                            "apiKey", Rec.of("type", "apiKey", "in", "header", "name", "X-Api-Key", "description", "A system's key issued under Users and roles"),
                            "cookie", Rec.of("type", "apiKey", "in", "cookie", "name", "orv_session", "description", "The Console's session; writes also need the header X-Orvanta-Console: 1"))));
            ctx.header("Cache-Control", "no-store");
            json(ctx, document);
        });
        // ---- the same APIs as SOAP operations: one endpoint, the operation named by the element in the Body ----
        routes.get("/api/soap", ctx -> {
            unscoped(principal(ctx));
            if (ctx.queryParam("wsdl") == null) {
                throw new ApiError(400, "GET /api/soap?wsdl gives the service description; operations are POSTed as SOAP envelopes");
            }
            ctx.header("Cache-Control", "no-store");
            ctx.contentType("text/xml").result(wsdl(platform.deployments.registry()));
        });
        routes.post("/api/soap", this::soapApi);
        routes.get("/api/x/*", this::modelApi);
        routes.post("/api/x/*", this::modelApi);
        routes.put("/api/x/*", this::modelApi);
        routes.delete("/api/x/*", this::modelApi);

        declareLedger(routes);
        if (simulator != null) {
            // the simulated external systems have no authentication, so they answer only this machine
            // unless simulator.allowRemote is set (containers calling each other)
            boolean allowRemote = platform.config.getBool("simulator.allowRemote", false);
            routes.before("/sim/*", ctx -> {
                if (!allowRemote && !java.net.InetAddress.getByName(ctx.ip()).isLoopbackAddress()) {
                    throw new ApiError(403, "the simulator answers only requests from this machine");
                }
            });
            routes.get("/sim/*", ctx -> json(ctx, simulator.handle("GET", ctx.path().substring(4), new Rec())));
            routes.post("/sim/*", ctx -> json(ctx, simulator.handle("POST", ctx.path().substring(4),
                    ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body()))));
        }
    }

    public void stop() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        streams.keySet().forEach(io.javalin.http.sse.SseClient::close);
        if (app != null) {
            app.stop();
        }
    }

    private static final int MAX_STREAMS_PER_USER = 8;
    private final java.util.Map<io.javalin.http.sse.SseClient, String> streams = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService heartbeat;

    private void tellStreams(java.util.function.Consumer<io.javalin.http.sse.SseClient> send) {
        for (io.javalin.http.sse.SseClient client : streams.keySet()) {
            try {
                if (client.terminated()) {
                    streams.remove(client);
                } else {
                    send.accept(client);
                }
            } catch (RuntimeException e) {
                // the browser went away without saying so
                streams.remove(client);
            }
        }
    }

    private void startStreams() {
        platform.bus.subscribeAll(Platform.CHANGED, notice -> tellStreams(client -> client.sendEvent("changed", Json.write(Rec.of("kinds", notice.get("kinds"))))));
        platform.deployments.onActivate(() -> platform.changed("models"));
        heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-streams");
            t.setDaemon(true);
            return t;
        });
        // a line every 20 seconds keeps proxies from closing a quiet connection and finds the ones that are gone
        heartbeat.scheduleWithFixedDelay(() -> tellStreams(client -> client.sendComment("alive")), 20, 20, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ---- payments ----

    private void payments() {
        routes.get("/api/dashboard", ctx -> {
            Scope scope = need(ctx, "payments.view").scope();
            Rec byStatus = new Rec();
            for (String status : Status.TRANSACTION) {
                byStatus.put(status, 0L);
            }
            List<Object> days = new ArrayList<>();
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            Rec inScope = new Rec();
            if (scope.narrowTransactions(inScope)) {
                // by status: one grouped count instead of a count per status
                platform.store.counts(DocStore.TXN, new DocStore.Query(inScope, null, List.of(), null, null, false, 0, 0), "status")
                        .forEach((status, n) -> byStatus.put(status, n));
                // per day: a count each for all, accepted and rejected, every one answered from an index on status and time
                for (int back = 6; back >= 0; back--) {
                    String day = today.minusDays(back).toString();
                    java.util.Map<String, Object[]> thatDay = java.util.Map.of("createdAt", new Object[] {day + "T00:00:00", day + "T23:59:59.999999999Z"});
                    Rec entry = Rec.of("date", day);
                    for (String[] series : new String[][] {{"received", null}, {"accepted", Status.ACCEPTED},
                            {"rejected", Status.REJECTED_BY_APPLICATION}, {"rejectedOutside", Status.REJECTED_BY_EXTERNAL}}) {
                        Rec filter = inScope.copy();
                        if (series[1] != null) {
                            filter.put("status", series[1]);
                        }
                        entry.put(series[0], platform.store.countMatching(DocStore.TXN, new DocStore.Query(filter, null, List.of(), thatDay, null, false, 0, 0)));
                    }
                    // an incoming payment that was credited went through, like an outgoing one that was accepted
                    Rec credited = inScope.copy();
                    credited.put("status", List.of(Status.CREDITED, Status.DEBITED));
                    entry.put("accepted", Ops.num(entry.get("accepted")).longValue()
                            + platform.store.countMatching(DocStore.TXN, new DocStore.Query(credited, null, List.of(), thatDay, null, false, 0, 0)));
                    days.add(entry);
                }
            }
            if (scope.restricted()) {
                // a user with a data scope gets the counts of that scope, and none of the figures that cover everybody's payments
                Rec instructions = Rec.of("purpose", "instruction");
                json(ctx, Rec.of("transactions", byStatus, "scoped", true, "days", days,
                        "instructions", scope.narrowMessages(instructions) ? platform.store.count(DocStore.MESSAGE, instructions) : 0,
                        "deployment", platform.deployments.activeId(), "version", platform.deployments.activeVersion()));
                return;
            }
            json(ctx, Rec.of("transactions", byStatus, "days", days,
                    "instructions", platform.store.count(DocStore.MESSAGE, Rec.of("purpose", "instruction")),
                    "instructionsRejected", platform.store.count(DocStore.MESSAGE, Rec.of("status", Status.REJECTED)),
                    "acknowledgements", platform.store.count(DocStore.MESSAGE, Rec.of("purpose", "acknowledgement")),
                    "outboundFiles", platform.store.count(DocStore.OUTBOUND, null),
                    "deadLetters", platform.store.count(DocStore.DEAD_LETTER, Rec.of("status", "OPEN")),
                    "pendingApprovals", platform.store.count(DocStore.APPROVAL, Rec.of("status", ApprovalService.PENDING)),
                    "deployment", platform.deployments.activeId(), "version", platform.deployments.activeVersion()));
        });
        routes.post("/api/inbound", ctx -> {
            Principal p = need(ctx, "payments.submit");
            String raw = ctx.body();
            if (raw.isBlank()) {
                throw new ApiError(400, "the request body is empty: post the message as the body");
            }
            String channel = ctx.queryParam("channel");
            Scope scope = p.scope();
            if (scope.byContent()) {
                throw new ApiError(403, "a user limited to certain accounts, currencies or amounts cannot submit files: a file may hold any payment");
            }
            if (channel != null && platform.deployments.registry().config(channel) != null
                    && Boolean.TRUE.equals(platform.deployments.registry().config(channel).get("consoleOnly"))) {
                throw new ApiError(403, "payments on " + channel + " are initiated from the Console form and approved by a second person; a file cannot be posted to it");
            }
            if (!scope.channels().isEmpty()) {
                // the message is tied to a channel of the scope before it is read, so it cannot land on another one
                if (channel == null && scope.channels().size() == 1) {
                    channel = scope.channels().get(0);
                } else if (channel == null || !scope.channels().contains(channel)) {
                    throw new ApiError(403, "name one of your channels with ?channel=: " + scope.channels());
                }
            }
            json(ctx, Rec.of("messages", ingest.receive(raw, ctx.queryParam("fileName"), channel, p.username())));
        });
        // ---- payments initiated from the Console form, with templates ----
        routes.get("/api/payments/templates", ctx -> {
            need(ctx, "payments.submit");
            json(ctx, Rec.of("items", platform.store.find(DocStore.TEMPLATE, null, "name", false, 500)));
        });
        routes.post("/api/payments/templates", ctx -> {
            Principal p = need(ctx, "payments.submit");
            Rec body = Json.parse(ctx.body());
            String name = required(body, "name").trim();
            if (name.length() > 80) {
                throw new ApiError(422, "a template name has up to 80 characters");
            }
            Rec fields = initiationFields(body.get("fields") instanceof Rec f ? f : new Rec(), p, false);
            String id = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (id.isEmpty()) {
                throw new ApiError(422, "a template name has letters or digits");
            }
            Rec template = Rec.of("id", id, "name", name, "fields", fields, "savedBy", p.username(), "savedAt", Platform.now());
            platform.store.save(DocStore.TEMPLATE, template);
            security.record("TEMPLATE_SAVED", p.username(), address(ctx), name);
            json(ctx, template);
        });
        routes.delete("/api/payments/templates/{id}", ctx -> {
            Principal p = need(ctx, "payments.submit");
            Rec template = found(platform.store.get(DocStore.TEMPLATE, ctx.pathParam("id")), ctx.pathParam("id"));
            platform.store.delete(DocStore.TEMPLATE, template.str("id"));
            security.record("TEMPLATE_REMOVED", p.username(), address(ctx), template.str("name"));
            json(ctx, Rec.of("removed", template.str("id")));
        });
        // one payment from the form: a request a second person approves; only then does it enter the engine, on the Console channel
        routes.post("/api/payments/initiations", ctx -> {
            Principal p = need(ctx, "payments.submit");
            Rec body = Json.parse(ctx.body());
            Rec given = new Rec();
            if (body.str("template") != null) {
                Rec template = platform.store.get(DocStore.TEMPLATE, body.str("template"));
                if (template == null) {
                    throw new ApiError(404, "no template " + body.str("template"));
                }
                given.putAll(template.rec("fields"));
            }
            for (java.util.Map.Entry<String, Object> e : body.entrySet()) {
                if (!List.of("template", "comment").contains(e.getKey()) && e.getValue() != null && !String.valueOf(e.getValue()).isBlank()) {
                    given.put(e.getKey(), e.getValue());
                }
            }
            Rec fields = initiationFields(given, p, true);
            Scope scope = p.scope();
            if (!scope.channels().isEmpty() && !scope.channels().contains(CONSOLE_CHANNEL)) {
                throw new ApiError(403, "your channels do not include " + CONSOLE_CHANNEL + ", so you cannot initiate a payment from the form");
            }
            if (!scope.debtorAccounts().isEmpty() && !scope.debtorAccounts().contains(Ops.str(fields.get("debtor.account")))) {
                throw new ApiError(403, "account " + fields.get("debtor.account") + " is not one of yours");
            }
            if (!scope.currencies().isEmpty() && !scope.currencies().contains(Ops.str(fields.get("currency")))) {
                throw new ApiError(403, "currency " + fields.get("currency") + " is not one of yours: " + scope.currencies());
            }
            if (scope.maxAmount() != null && fields.get("amount") != null && Ops.num(fields.get("amount")).compareTo(scope.maxAmount()) > 0) {
                throw new ApiError(403, "the amount is above " + scope.maxAmount().toPlainString() + ", the most your access allows");
            }
            if (platform.deployments.registry().config(CONSOLE_CHANNEL) == null) {
                throw new ApiError(409, "no channel " + CONSOLE_CHANNEL + " is deployed, so payments cannot be initiated from the form");
            }
            String msgId = platform.newId("ORVCON");
            // the fields are flat ("debtor.account"); the message the mapping reads is nested
            Rec message = new Rec();
            for (java.util.Map.Entry<String, Object> e : fields.entrySet()) {
                message.set(e.getKey(), e.getValue());
            }
            message.put("messageType", "payment.initiation");
            message.put("msgId", msgId);
            message.put("createdAt", Platform.now());
            message.put("initiatedBy", p.username());
            if (message.str("endToEndId") == null) {
                message.put("endToEndId", msgId);
            }
            if (message.str("requestedDate") == null) {
                message.put("requestedDate", Platform.now().substring(0, 10));
            }
            json(ctx, approvals.request("PAYMENT_ACTION", "Initiate a payment of " + fields.get("amount") + " " + fields.get("currency") + " from " + fields.get("debtor.account")
                    + " to " + fields.get("creditor.name"), Rec.of("action", "INITIATE", "message", message, "amount", fields.get("amount"), "currency", fields.get("currency")),
                    p.username(), body.str("comment")));
        });
        routes.get("/api/messages", ctx -> {
            Scope scope = need(ctx, "payments.view").scope();
            Rec filter = filter(ctx, "purpose", "status", "channel", "messageType");
            if (!scope.narrowMessages(filter)) {
                json(ctx, Rec.of("items", List.of(), "total", 0, "offset", 0));
                return;
            }
            Rec page = listPage(ctx, DocStore.MESSAGE, filter, List.of("id", "msgId", "fileName", "messageType", "channel", "reasonCode", "reasonText"),
                    List.of("receivedAt", "id", "status", "messageType", "transactionCount", "totalAmount"), "receivedAt", "receivedAt");
            for (Object item : Ops.list(page.get("items"))) {
                ((Rec) item).remove("raw");
            }
            list(ctx, page);
        });
        routes.get("/api/messages/{id}", ctx -> {
            Rec message = visibleMessage(need(ctx, "payments.view"), ctx.pathParam("id"));
            message.put("events", events(message.str("id")));
            message.put("batches", platform.store.find(DocStore.BATCH, Rec.of("instructionId", message.str("id")), "id", false, 500));
            json(ctx, message);
        });
        routes.get("/api/transactions", ctx -> {
            Scope scope = need(ctx, "payments.view").scope();
            Rec filter = filter(ctx, "status", "instructionId", "batchId", "outboundId", "endToEndId", "currency", "channelIn");
            if (ctx.queryParam("recall") != null && !ctx.queryParam("recall").isBlank()) {
                filter.put("recall.status", ctx.queryParam("recall"));
            }
            if (ctx.queryParam("claim") != null && !ctx.queryParam("claim").isBlank()) {
                filter.put("charges.claimStatus", ctx.queryParam("claim").contains(",") ? new ArrayList<Object>(List.of(ctx.queryParam("claim").split(","))) : ctx.queryParam("claim"));
            }
            if ("true".equals(ctx.queryParam("overdue"))) {
                filter.put("answerOverdue", true);
            }
            if (ctx.queryParam("queue") != null && !ctx.queryParam("queue").isBlank()) {
                // the operator queue a task step put the payment in
                filter.put("hold.queue", ctx.queryParam("queue"));
            }
            if ("true".equals(ctx.queryParam("investigation"))) {
                filter.put("investigationOpen", true);
            }
            if (ctx.queryParam("paymentType") != null && !ctx.queryParam("paymentType").isBlank()) {
                filter.put("paymentType", ctx.queryParam("paymentType"));
            }
            if (ctx.queryParam("scheme") != null && !ctx.queryParam("scheme").isBlank()) {
                filter.put("route.scheme", ctx.queryParam("scheme"));
            }
            // search, ranges, order and paging are done by the store, so a page costs the same however many payments there are
            java.util.Map<String, Object[]> ranges = new java.util.LinkedHashMap<>();
            range(ranges, "createdAt", dayStart(ctx, "from"), dayEnd(ctx, "to"));
            range(ranges, "amount", amount(ctx, "minAmount"), amount(ctx, "maxAmount"));
            String sort = ctx.queryParam("sort") == null ? "id" : ctx.queryParam("sort");
            if (!TXN_SORTS.contains(sort)) {
                throw new ApiError(400, "sort must be one of " + TXN_SORTS);
            }
            int offset = number(ctx, "offset", 0, 0, 10_000_000);
            if (!scope.narrowTransactions(filter)) {
                json(ctx, Rec.of("items", List.of(), "total", 0, "offset", offset));
                return;
            }
            DocStore.Page page = platform.store.page(DocStore.TXN, new DocStore.Query(filter, ctx.queryParam("q"), TXN_SEARCH, ranges, sort,
                    !"asc".equals(ctx.queryParam("dir")), offset, limit(ctx)));
            list(ctx, Rec.of("items", page.items(), "total", page.total(), "offset", offset));
        });
        routes.get("/api/transactions/{id}", ctx -> {
            Rec txn = visibleTxn(need(ctx, "payments.view"), ctx.pathParam("id"));
            txn.put("events", events(txn.str("id")));
            json(ctx, txn);
        });
        // payment actions are requests: they take effect when a second person approves them
        // a note on a payment: what a person found out or decided, for the next person who looks at it
        routes.post("/api/transactions/{id}/notes", ctx -> {
            Principal p = need(ctx, "payments.view");
            String id = ctx.pathParam("id");
            visibleTxn(p, id);
            String text = Json.parse(ctx.body()).str("text");
            if (text == null || text.isBlank() || text.length() > 2000) {
                throw new ApiError(422, "a note has text, up to 2000 characters");
            }
            Rec txn = platform.store.get(DocStore.TXN, id);
            List<Object> notes = new ArrayList<>(Ops.list(txn.get("notes")));
            Rec note = Rec.of("at", Platform.now(), "by", p.username(), "text", text.trim());
            notes.add(note);
            platform.store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("notes", notes, "updatedAt", Platform.now()));
            platform.event(id, "NOTE", text.trim(), null, p.username());
            platform.changed("payments");
            json(ctx, Rec.of("transactionId", id, "notes", notes));
        });
        // a warehoused payment goes now instead of at its time: a request a second person approves
        routes.post("/api/transactions/{id}/release-now", ctx -> {
            Principal p = need(ctx, "payments.repair");
            String id = ctx.pathParam("id");
            Rec txn = visibleTxn(p, id);
            if (!Status.WAREHOUSED.equals(txn.str("status"))) {
                throw new ApiError(409, "only a warehoused payment can be released before its time");
            }
            String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
            json(ctx, approvals.request("PAYMENT_ACTION", "Release " + id + " from the warehouse now (was waiting until " + txn.str("warehouse.until") + ")",
                    Rec.of("action", "RELEASE_NOW", "transactionId", id), p.username(), comment));
        });
        routes.post("/api/transactions/{id}/resubmit", ctx -> {
            Principal p = need(ctx, "payments.repair");
            String id = ctx.pathParam("id");
            Rec txn = visibleTxn(p, id);
            if (!Status.REPAIR.equals(txn.str("status"))) {
                throw new ApiError(409, "only a transaction in REPAIR can be resubmitted");
            }
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            // a repair may change what was wrong: the fields an operator may touch, each with what it is now and what it becomes
            Rec changes = new Rec();
            if (body.get("changes") instanceof java.util.Map<?, ?> wanted) {
                for (java.util.Map.Entry<?, ?> e : wanted.entrySet()) {
                    String field = String.valueOf(e.getKey());
                    if (!REPAIRABLE.contains(field)) {
                        throw new ApiError(422, "'" + field + "' cannot be changed in a repair; the fields are " + REPAIRABLE);
                    }
                    Object to = e.getValue();
                    if (to == null || String.valueOf(to).isBlank()) {
                        throw new ApiError(422, "a repair gives '" + field + "' a value");
                    }
                    if (field.equals("amount") && (Ops.num(to) == null || Ops.num(to).signum() <= 0)) {
                        throw new ApiError(422, "the amount of a repair is more than zero");
                    }
                    changes.put(field, Rec.of("from", txn.at(field), "to", field.equals("amount") ? Ops.num(to) : String.valueOf(to).trim()));
                }
            }
            json(ctx, approvals.request("PAYMENT_ACTION", (changes.isEmpty() ? "Resubmit " : "Repair and resubmit ") + id
                    + (changes.isEmpty() ? "" : " (" + String.join(", ", changes.keySet()) + ")"),
                    Rec.of("action", "RESUBMIT", "transactionId", id, "changes", changes), p.username(), body.str("comment")));
        });
        routes.post("/api/transactions/{id}/cancel", ctx -> {
            Principal p = need(ctx, "payments.cancel");
            String id = ctx.pathParam("id");
            Rec txn = visibleTxn(p, id);
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            if (io.orvanta.pay.engine.Incoming.is(txn)) {
                throw new ApiError(409, "an incoming payment is not cancelled; once it is credited it can be refunded");
            }
            if (List.of(Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL, Status.CANCELLED, Status.RETURNED).contains(txn.str("status"))) {
                throw new ApiError(409, "a transaction that is " + txn.str("status") + " cannot be cancelled");
            }
            json(ctx, approvals.request("PAYMENT_ACTION", "Cancel " + id + " (" + txn.str("status") + ")",
                    Rec.of("action", "CANCEL", "transactionId", id, "reasonCode", body.str("reasonCode"), "reasonText", body.str("reasonText")),
                    p.username(), body.str("comment")));
        });
        // a credited incoming payment is sent back to where it came from: the credit is reversed, then a return is sent
        routes.post("/api/transactions/{id}/refund", ctx -> {
            Principal p = need(ctx, "payments.cancel");
            String id = ctx.pathParam("id");
            Rec txn = visibleTxn(p, id);
            if (!io.orvanta.pay.engine.Incoming.is(txn) || !io.orvanta.pay.engine.Incoming.BOOKED.contains(txn.str("status"))) {
                throw new ApiError(409, "only an incoming payment that was credited, or a collection that was debited, can be refunded");
            }
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            // a customer who disputes a collection gets the money back: MD06, refund request by the end customer
            boolean collection = io.orvanta.pay.engine.Incoming.DEBIT_TYPE.equals(txn.str("paymentType"));
            String code = body.str("reasonCode") == null || body.str("reasonCode").isBlank() ? (collection ? "MD06" : "CUST") : body.str("reasonCode");
            if (!code.matches("[A-Z0-9]{4}")) {
                throw new ApiError(400, "the reason code is four letters or digits, for example CUST, FOCR or AC04");
            }
            // a scheme may limit how long after the booking a refund can be asked for; the inbound channel says so
            Rec limits = platform.deployments.registry().config(String.valueOf(txn.str("channelIn")));
            String bookedAt = txn.str("debitedAt") != null ? txn.str("debitedAt") : txn.str("creditedAt");
            if (limits != null && limits.get("refund") instanceof Rec refund && bookedAt != null) {
                long days = java.time.Duration.between(java.time.Instant.parse(bookedAt), io.orvanta.core.expr.Time.now()).toDays();
                Object within = refund.get("withinDays");
                Object unauthorised = refund.get("unauthorisedWithinDays");
                if (within != null && days > Ops.num(within).longValue()) {
                    boolean stillOpen = unauthorised != null && days <= Ops.num(unauthorised).longValue();
                    if (!stillOpen) {
                        throw new ApiError(409, "the booking is " + days + " days old; a refund can no longer be asked for"
                                + (unauthorised == null ? " after " + within + " days" : " after " + unauthorised + " days"));
                    }
                    if (!"MD01".equals(code)) {
                        throw new ApiError(409, "the booking is " + days + " days old: after " + within + " days a refund is only possible for a collection"
                                + " the customer never authorised, with reason code MD01");
                    }
                }
            }
            json(ctx, approvals.request("PAYMENT_ACTION", "Refund " + id + " (" + txn.get("amount") + " " + txn.str("currency")
                            + (collection ? " back to " + txn.str("debtor.name") + ", collected by " + txn.str("creditor.name") : " to " + txn.str("debtor.name")) + ")",
                    Rec.of("action", "REFUND", "transactionId", id, "reasonCode", code, "reasonText", body.str("reasonText")), p.username(), body.str("comment")));
        });
        // a recall of the sender's bank that waits on a credited payment: sent back, or refused with a reason
        routes.post("/api/transactions/{id}/recall/{decision}", ctx -> {
            Principal p = need(ctx, "payments.cancel");
            String id = ctx.pathParam("id");
            String decision = ctx.pathParam("decision");
            if (!List.of("accept", "refuse").contains(decision)) {
                throw new ApiError(404, "a recall is accepted or refused");
            }
            Rec txn = visibleTxn(p, id);
            if (!"OPEN".equals(txn.str("recall.status"))) {
                throw new ApiError(409, "no recall is waiting for a decision on this payment");
            }
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            if (decision.equals("accept")) {
                if (!Status.CREDITED.equals(txn.str("status"))) {
                    throw new ApiError(409, "the payment is " + txn.str("status") + "; a recall is accepted once the payment is credited");
                }
                json(ctx, approvals.request("PAYMENT_ACTION", "Accept the recall of " + id + " and send " + txn.get("amount") + " " + txn.str("currency") + " back",
                        Rec.of("action", "RECALL_ACCEPT", "transactionId", id), p.username(), body.str("comment")));
                return;
            }
            String code = body.str("reasonCode") == null || body.str("reasonCode").isBlank() ? "CUST" : body.str("reasonCode");
            if (!code.matches("[A-Z0-9]{4}")) {
                throw new ApiError(400, "the reason code is four letters or digits, for example CUST, LEGL, AM04 or NOAS");
            }
            json(ctx, approvals.request("PAYMENT_ACTION", "Refuse the recall of " + id + " (" + code + ")",
                    Rec.of("action", "RECALL_REFUSE", "transactionId", id, "reasonCode", code, "reasonText", body.str("reasonText")), p.username(), body.str("comment")));
        });
        // a charge claimed from another bank has been paid: marked by one person, confirmed by a second
        // an investigation from another bank is answered by a person; a second person approves the answer
        routes.post("/api/transactions/{id}/investigations/{caseId}/answer", ctx -> {
            Principal p = need(ctx, "payments.repair");
            String id = ctx.pathParam("id");
            Rec txn = visibleTxn(p, id);
            String caseId = ctx.pathParam("caseId");
            Rec open = null;
            for (Object c : Ops.list(txn.get("investigations"))) {
                if (c instanceof Rec r && caseId.equals(r.str("caseId")) && "OPEN".equals(r.str("status"))) {
                    open = r;
                }
            }
            if (open == null) {
                throw new ApiError(409, "this payment has no open investigation " + caseId);
            }
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            boolean information = "RFI".equals(open.str("kind"));
            String confirmation = body.str("confirmation");
            if (!information && (confirmation == null || !confirmation.matches("[A-Z]{4}"))) {
                throw new ApiError(422, "a resolution needs a four-letter confirmation code (for example MODI, IPAY, RJCR, RJNR)");
            }
            if (information && (body.str("text") == null || body.str("text").isBlank())) {
                throw new ApiError(422, "a request for information is answered with text");
            }
            json(ctx, approvals.request("PAYMENT_ACTION", "Answer investigation " + caseId + " on " + id + (confirmation == null ? "" : " with " + confirmation),
                    Rec.of("action", "INVESTIGATION_ANSWER", "transactionId", id, "caseId", caseId, "confirmation", confirmation, "text", body.str("text")),
                    p.username(), body.str("comment")));
        });
        // requests to pay from creditors' banks: the customer accepts or refuses, a second person approves
        routes.get("/api/requests-to-pay", ctx -> {
            unscoped(need(ctx, "payments.view"));
            list(ctx, listPage(ctx, io.orvanta.pay.engine.RequestToPayService.COLLECTION, filter(ctx, "status", "channel"),
                    List.of("id", "endToEndId", "creditor.name", "debtor.name", "debtor.account", "remittance"), List.of("receivedAt", "id", "amount", "expiryDate", "status"), "receivedAt", "receivedAt"));
        });
        routes.get("/api/requests-to-pay/{id}", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec request = found(platform.store.get(io.orvanta.pay.engine.RequestToPayService.COLLECTION, ctx.pathParam("id")), ctx.pathParam("id")).copy();
            request.put("events", events(request.str("id")));
            json(ctx, request);
        });
        routes.post("/api/requests-to-pay/{id}/accept", ctx -> requestToPayAnswer(ctx, true));
        routes.post("/api/requests-to-pay/{id}/refuse", ctx -> requestToPayAnswer(ctx, false));
        routes.post("/api/transactions/{id}/charge-claim/paid", ctx -> {
            Principal p = need(ctx, "payments.repair");
            String id = ctx.pathParam("id");
            Rec txn = visibleTxn(p, id);
            if (!List.of("REQUESTED", "OPEN", "FAILED").contains(String.valueOf(txn.str("charges.claimStatus")))) {
                throw new ApiError(409, "this payment has no charge claim that is waiting to be paid");
            }
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            json(ctx, approvals.request("PAYMENT_ACTION", "Charge claim of " + id + " (" + txn.at("charges.claim") + " " + txn.str("charges.currency")
                    + " from " + txn.str("charges.claimFrom") + ") was paid", Rec.of("action", "CLAIM_PAID", "transactionId", id, "reference", body.str("reference")),
                    p.username(), body.str("comment")));
        });
        // what was booked on an account in a period: looked at, or sent to the customer as an account report
        routes.get("/api/account-reports/entries", ctx -> {
            Principal p = need(ctx, "payments.view");
            String[] period = reportPeriod(p, ctx.queryParam("account"), ctx.queryParam("from"), ctx.queryParam("to"));
            List<Rec> entries = io.orvanta.pay.engine.AccountReports.entries(platform, ctx.queryParam("account"), period[0], period[1]);
            List<Rec> items = new ArrayList<>();
            for (Rec e : entries) {
                Rec txn = e.rec("txn");
                items.add(Rec.of("transactionId", txn.str("id"), "kind", e.str("kind"), "creditDebit", e.str("creditDebit"), "reversal", e.get("reversal"),
                        "bookedAt", e.str("bookedAt"), "amount", e.get("amount"), "currency", e.str("currency"), "endToEndId", txn.str("endToEndId"),
                        "counterparty", "CRDT".equals(e.str("creditDebit")) == Boolean.TRUE.equals(e.get("reversal")) ? txn.str("creditor.name") : txn.str("debtor.name"),
                        "remittance", txn.str("remittance")));
            }
            json(ctx, Rec.of("account", ctx.queryParam("account"), "from", period[0], "to", period[1], "items", items));
        });
        routes.post("/api/account-reports", ctx -> {
            Principal p = need(ctx, "payments.repair");
            Rec body = Json.parse(ctx.body());
            String[] period = reportPeriod(p, body.str("account"), body.str("from"), body.str("to"));
            String channel = body.str("channel") != null ? body.str("channel") : platform.config.get("accountReports.channel", "channels.CustomerAccountReportOutbound");
            String id;
            try {
                id = io.orvanta.pay.engine.AccountReports.create(platform, channel, body.str("account"), period[0], period[1], platform.newId("ORVRPT"), p.username());
            } catch (IllegalStateException e) {
                throw new ApiError(422, e.getMessage());
            }
            json(ctx, Rec.of("id", id));
        });
        // the statement of an account for a day: balances and entries from the platform's own ledger
        routes.post("/api/account-statements", ctx -> {
            Principal p = need(ctx, "payments.repair");
            Rec body = Json.parse(ctx.body());
            String[] period = reportPeriod(p, body.str("account"), body.str("date"), body.str("date"));
            String channel = body.str("channel") != null ? body.str("channel") : platform.config.get("accountStatements.channel", "channels.CustomerStatementOutbound");
            try {
                json(ctx, Rec.of("id", io.orvanta.pay.engine.AccountStatements.create(platform, channel, body.str("account"), period[0].substring(0, 10), p.username())));
            } catch (IllegalStateException e) {
                throw new ApiError(422, e.getMessage());
            }
        });
        routes.post("/api/messages/{id}/replay", ctx -> {
            Principal p = need(ctx, "payments.repair");
            String id = ctx.pathParam("id");
            Rec message = visibleMessage(p, id);
            if (!Status.REJECTED.equals(message.str("status")) || message.str("replayedAs") != null) {
                throw new ApiError(409, "only a rejected message that was not replayed before can be replayed");
            }
            String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
            json(ctx, approvals.request("PAYMENT_ACTION", "Replay " + id + " (" + message.str("reasonCode") + ")",
                    Rec.of("action", "REPLAY", "messageId", id), p.username(), comment));
        });
        routes.post("/api/transactions/{id}/release", ctx -> reviewRequest(ctx, "RELEASE"));
        routes.post("/api/transactions/{id}/reject", ctx -> reviewRequest(ctx, "REJECT"));
        routes.get("/api/deadletters", ctx -> {
            unscoped(need(ctx, "payments.repair"));
            list(ctx, listPage(ctx, DocStore.DEAD_LETTER, filter(ctx, "status", "topic"), List.of("topic", "refId", "error"),
                    List.of("at", "topic", "status", "refId"), "at", "at"));
        });
        routes.post("/api/deadletters/{id}/requeue", ctx -> {
            Principal p = unscoped(need(ctx, "payments.repair"));
            String id = ctx.pathParam("id");
            Rec letter = found(platform.store.get(DocStore.DEAD_LETTER, id), id);
            if (!platform.store.updateIf(DocStore.DEAD_LETTER, id, Rec.of("status", "OPEN"),
                    Rec.of("status", "REQUEUED", "requeuedBy", p.username(), "requeuedAt", Platform.now()))) {
                throw new ApiError(409, "this entry was already requeued");
            }
            platform.bus.publish(letter.str("topic"), letter.rec("message"));
            json(ctx, platform.store.get(DocStore.DEAD_LETTER, id));
        });
        routes.get("/api/statements", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec page = listPage(ctx, DocStore.STATEMENT, filter(ctx, "account", "balanceCheck", "currency"), List.of("id", "account", "statementId", "messageId"),
                    List.of("receivedAt", "id", "account", "entryCount", "closingBalance"), "receivedAt", "receivedAt");
            for (Object item : Ops.list(page.get("items"))) {
                ((Rec) item).remove("entries");
            }
            list(ctx, page);
        });
        // an entry the engine could not match is matched by a person, with a second person approving
        // payments that could be the entry: same currency and amount, around the entry's value date, not reconciled yet
        routes.get("/api/statements/{id}/entries/{index}/candidates", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec statement = found(platform.store.get(DocStore.STATEMENT, ctx.pathParam("id")), ctx.pathParam("id"));
            List<?> entries = Ops.list(statement.get("entries"));
            int index = Integer.parseInt(ctx.pathParam("index"));
            if (index < 0 || index >= entries.size()) {
                throw new ApiError(404, "the statement has no entry " + index);
            }
            Rec entry = (Rec) entries.get(index);
            String currency = entry.str("currency") != null ? entry.str("currency") : statement.str("currency");
            java.math.BigDecimal amount = Ops.num(entry.get("amount"));
            List<Object> out = new ArrayList<>();
            if (amount != null) {
                java.time.LocalDate valueDate = entry.str("valueDate") == null ? null : java.time.LocalDate.parse(entry.str("valueDate"));
                for (Rec t : platform.store.find(DocStore.TXN, Rec.of("currency", currency, "amount", amount), "createdAt", true, 200)) {
                    if (t.get("reconciliation") != null || !List.of(Status.SENT, Status.ACKNOWLEDGED, Status.ACCEPTED, Status.BULKED).contains(t.str("status"))) {
                        continue;
                    }
                    long daysOff = valueDate == null || t.str("createdAt") == null ? 0
                            : Math.abs(java.time.temporal.ChronoUnit.DAYS.between(valueDate, java.time.LocalDate.parse(t.str("createdAt").substring(0, 10))));
                    if (daysOff > 5) {
                        continue;
                    }
                    out.add(Rec.of("id", t.str("id"), "endToEndId", t.str("endToEndId"), "creditor", t.at("creditor.name"), "amount", t.get("amount"), "currency", t.str("currency"),
                            "status", t.str("status"), "createdAt", t.str("createdAt"), "channel", t.at("route.channel"), "daysOff", daysOff,
                            "why", "same amount and currency" + (daysOff == 0 ? ", same day" : ", " + daysOff + " day(s) apart")));
                }
                out.sort(java.util.Comparator.comparingLong(a -> Ops.num(((Rec) a).get("daysOff")).longValue()));
            }
            json(ctx, Rec.of("items", out));
        });
        routes.post("/api/statements/{id}/entries/{index}/match", ctx -> {
            Principal p = unscoped(need(ctx, "payments.repair"));
            String id = ctx.pathParam("id");
            Rec statement = found(platform.store.get(DocStore.STATEMENT, id), id);
            int index = pathNumber(ctx, "index", -1);
            List<?> entries = Ops.list(statement.get("entries"));
            if (index < 0 || index >= entries.size()) {
                throw new ApiError(404, "statement " + id + " has no entry " + ctx.pathParam("index"));
            }
            Rec body = Json.parse(ctx.body());
            String txnId = body.str("transactionId");
            if (txnId == null || platform.store.get(DocStore.TXN, txnId) == null) {
                throw new ApiError(422, "transactionId must name a payment");
            }
            if ("MATCHED".equals(((Rec) entries.get(index)).str("status"))) {
                throw new ApiError(409, "the entry is matched already");
            }
            json(ctx, approvals.request("PAYMENT_ACTION", "Match entry " + index + " of statement " + id + " to " + txnId,
                    Rec.of("action", "STATEMENT_MATCH", "statementId", id, "entry", index, "transactionId", txnId, "note", body.str("note")), p.username(), body.str("comment")));
        });
        // what went in and out on one day, per channel: the figures to put next to the clearing's own
        routes.get("/api/reports/daily", ctx -> {
            unscoped(need(ctx, "payments.view"));
            String day = day(ctx, "date") == null ? java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString() : day(ctx, "date");
            list(ctx, Rec.of("date", day, "items", dailyReport(day), "total", dailyReport(day).size(), "offset", 0));
        });
        routes.get("/api/statements/{id}", ctx -> {
            unscoped(need(ctx, "payments.view"));
            json(ctx, found(platform.store.get(DocStore.STATEMENT, ctx.pathParam("id")), ctx.pathParam("id")));
        });
        routes.get("/api/outbound", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec page = listPage(ctx, DocStore.OUTBOUND, filter(ctx, "status", "channel", "kind", "messageType"),
                    List.of("id", "channel", "messageType", "location", "instructionId"),
                    List.of("id", "createdAt", "status", "transactionCount", "totalAmount"), "id", "createdAt");
            for (Object item : Ops.list(page.get("items"))) {
                ((Rec) item).remove("payload");
            }
            list(ctx, page);
        });
        routes.get("/api/outbound/{id}", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec outbound = found(platform.store.get(DocStore.OUTBOUND, ctx.pathParam("id")), ctx.pathParam("id"));
            outbound.put("events", events(outbound.str("id")));
            json(ctx, outbound);
        });
        routes.post("/api/outbound/{id}/retry", ctx -> {
            unscoped(need(ctx, "payments.repair"));
            if (!dispatch.handle(ctx.pathParam("id"), Status.FAILED)) {
                throw new ApiError(409, "only a FAILED outbound file can be retried, and the retry must succeed");
            }
            json(ctx, platform.store.get(DocStore.OUTBOUND, ctx.pathParam("id")));
        });
        // a file that was sent is sent again, for a partner that lost it: a request a second person approves
        routes.post("/api/outbound/{id}/resend", ctx -> {
            Principal p = unscoped(need(ctx, "payments.repair"));
            String id = ctx.pathParam("id");
            Rec outbound = found(platform.store.get(DocStore.OUTBOUND, id), id);
            if (!List.of(Status.SENT, Status.ACKNOWLEDGED).contains(outbound.str("status"))) {
                throw new ApiError(409, "only a file that was sent can be sent again");
            }
            String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
            json(ctx, approvals.request("PAYMENT_ACTION", "Send " + id + " again (" + outbound.str("channel") + ")",
                    Rec.of("action", "RESEND", "outboundId", id), p.username(), comment));
        });
        // an operator stops a channel from sending (a partner asks for it, a clearing is down): files wait until it is resumed
        routes.get("/api/channels/paused", ctx -> {
            unscoped(need(ctx, "payments.view"));
            List<Rec> paused = new ArrayList<>();
            for (Rec setting : platform.store.find(DocStore.SETTING, Rec.of("paused", true), "id", false, 200)) {
                paused.add(setting);
            }
            json(ctx, Rec.of("items", paused));
        });
        // every outbound channel at once: a kill switch with a second person, and the way back
        routes.post("/api/channels/stop-all", ctx -> {
            Principal p = person(unscoped(need(ctx, "payments.repair")), "sending is stopped by a person");
            String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
            json(ctx, approvals.request("CHANNEL_CONTROL", "Stop sending on every outbound channel", Rec.of("channel", "*", "paused", true), p.username(), comment));
        });
        routes.post("/api/channels/resume-all", ctx -> {
            Principal p = person(unscoped(need(ctx, "payments.repair")), "sending is resumed by a person");
            String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
            json(ctx, approvals.request("CHANNEL_CONTROL", "Resume sending on every outbound channel", Rec.of("channel", "*", "paused", false), p.username(), comment));
        });
        routes.post("/api/channels/{name}/pause", ctx -> channelControl(ctx, true));
        routes.post("/api/channels/{name}/resume", ctx -> channelControl(ctx, false));
        // every transaction of an instruction that can still be cancelled is cancelled: the red button for a file
        routes.post("/api/messages/{id}/cancel", ctx -> {
            Principal p = need(ctx, "payments.cancel");
            String id = ctx.pathParam("id");
            Rec message = visibleMessage(p, id);
            if (!"instruction".equals(message.str("purpose"))) {
                throw new ApiError(409, "only an instruction (a file of payments) can be cancelled as a whole");
            }
            Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
            long open = platform.store.count(DocStore.TXN, Rec.of("instructionId", id));
            json(ctx, approvals.request("PAYMENT_ACTION", "Cancel every payment of " + id + " that can still be cancelled (" + open + " in the file)",
                    Rec.of("action", "CANCEL_INSTRUCTION", "messageId", id, "reasonCode", body.str("reasonCode"), "reasonText", body.str("reasonText")),
                    p.username(), body.str("comment")));
        });
        routes.post("/api/bulking/close", ctx -> {
            unscoped(need(ctx, "payments.repair"));
            json(ctx, Rec.of("bulksCreated", bulking.sweep(true)));
        });
    }

    // ---- studio ----

    private void studio() {
        routes.get("/api/studio/models", ctx -> {
            need(ctx, "studio.view");
            json(ctx, Rec.of("deployment", platform.deployments.activeId(), "version", platform.deployments.activeVersion(),
                    "items", without(platform.deployments.activeModels(), "text")));
        });
        routes.get("/api/studio/models/{name}", ctx -> {
            need(ctx, "studio.view");
            String name = ctx.pathParam("name");
            for (Rec model : platform.deployments.activeModels()) {
                if (model.str("name").equals(name)) {
                    model.set("generated", platform.deployments.registry().generatedSource(name));
                    model.put("definition", ModelSource.parse(model.str("path"), model.str("text")).def());
                    json(ctx, model);
                    return;
                }
            }
            throw new ApiError(404, "no model named " + name);
        });
        routes.post("/api/studio/validate", ctx -> {
            need(ctx, "studio.view");
            Rec body = Json.parse(ctx.body());
            json(ctx, buildResult(overlayBuild(body)));
        });
        // the visual designer works on the model as data: text to data when it opens, data to text on every change
        routes.post("/api/studio/parse", ctx -> {
            need(ctx, "studio.view");
            try {
                json(ctx, Rec.of("definition", ModelSource.definition(required(Json.parse(ctx.body()), "text"))));
            } catch (ModelException e) {
                throw new ApiError(422, e.getMessage());
            }
        });
        routes.post("/api/studio/render", ctx -> {
            need(ctx, "studio.view");
            if (!(Json.parse(ctx.body()).get("definition") instanceof Map<?, ?> definition)) {
                throw new ApiError(400, "definition is required");
            }
            json(ctx, Rec.of("text", ModelSource.toText(definition)));
        });
        routes.post("/api/studio/run", ctx -> {
            need(ctx, "studio.view");
            Rec body = Json.parse(ctx.body());
            Registry registry;
            if (body.str("text") == null) {
                registry = platform.deployments.registry();
            } else {
                Forge.Build build = overlayBuild(body);
                if (!build.ok()) {
                    json(ctx, buildResult(build));
                    return;
                }
                registry = build.registry();
            }
            Rec scope = body.get("scope") instanceof Map<?, ?> m ? Rec.from(m) : new Rec();
            Rec mocks = body.get("mocks") instanceof Map<?, ?> m ? Rec.from(m) : new Rec();
            // connectors named in mocks answer with the given reply; all others are called for real.
            // data sets are an in-memory copy seeded from 'data': a Studio run never changes stored data
            io.orvanta.forge.MemoryDataAccess data = new io.orvanta.forge.MemoryDataAccess(registry,
                    body.get("data") instanceof Map<?, ?> m ? Rec.from(m) : null);
            Rec result = TestRunner.execute(registry, required(body, "target"), scope, name -> {
                Connector mock = TestRunner.mockConnector(mocks, name);
                return mock != null ? mock : platform.deployments.connector(name);
            }, data);
            json(ctx, Rec.of("ok", true, "result", result, "scope", scope, "stored", data.contents()));
        });
        routes.post("/api/studio/tests", ctx -> {
            need(ctx, "studio.view");
            List<Object> results = new ArrayList<>();
            int failed = 0;
            for (TestRunner.TestResult r : TestRunner.runAll(platform.deployments.registry(), platform.workspaceDir)) {
                results.add(r.toRec());
                failed += r.passed() ? 0 : 1;
            }
            json(ctx, Rec.of("total", results.size(), "failed", failed, "results", results));
        });
        routes.post("/api/studio/changes", ctx -> {
            Principal p = need(ctx, "studio.edit");
            Rec body = Json.parse(ctx.body());
            String text = required(body, "text");
            ModelSource source = ModelSource.parse("draft", text);
            Forge.Build build = overlayBuild(body);
            if (!build.ok()) {
                ctx.status(422);
                json(ctx, buildResult(build));
                return;
            }
            Rec approval = approvals.request("MODEL_CHANGE", source.kind() + " " + source.name(),
                    Rec.of("name", source.name(), "kind", source.kind(), "path", pathOf(source.name()), "text", text,
                            "baseDeployment", platform.deployments.activeId()), p.username(), body.str("comment"));
            json(ctx, approval);
        });
        // the rows of a reference table as a spreadsheet file, to edit and upload again
        routes.get("/api/studio/reference-tables/{name}/rows.csv", ctx -> {
            Principal p = need(ctx, "studio.view");
            Rec def = referenceTable(ctx.pathParam("name"));
            List<String> columns = new ArrayList<>();
            List<Rec> rows = new ArrayList<>();
            for (Object row : Ops.list(def.get("rows"))) {
                if (row instanceof Rec r) {
                    rows.add(r);
                    for (String column : r.keySet()) {
                        if (!columns.contains(column)) {
                            columns.add(column);
                        }
                    }
                }
            }
            StringBuilder csv = new StringBuilder("\uFEFF");
            csv.append(String.join(",", columns.stream().map(ApiServer::csvCell).toList())).append("\r\n");
            for (Rec row : rows) {
                csv.append(String.join(",", columns.stream().map(c -> csvCell(row.get(c) == null ? null : String.valueOf(row.get(c)))).toList())).append("\r\n");
            }
            security.record("EXPORT", p.username(), address(ctx), def.str("name") + ": " + rows.size() + " row(s)");
            ctx.header("Content-Disposition", "attachment; filename=\"" + def.str("name") + ".csv\"");
            ctx.contentType("text/csv; charset=utf-8").result(csv.toString());
        });
        // rows for a reference table from a spreadsheet file: the whole table, or the given rows merged into it; a change request like any other
        routes.post("/api/studio/reference-tables/{name}/imports", ctx -> {
            Principal p = need(ctx, "studio.edit");
            Rec body = Json.parse(ctx.body());
            Rec def = referenceTable(ctx.pathParam("name")).copy();
            String mode = body.str("mode") == null ? "merge" : body.str("mode");
            if (!mode.equals("merge") && !mode.equals("replace")) {
                throw new ApiError(422, "mode is merge (the file's rows added to or changed in the table) or replace (the file is the whole table)");
            }
            List<List<String>> lines = csvRows(required(body, "csv"));
            String key = def.str("key");
            if (lines.size() < 2) {
                throw new ApiError(422, "the file needs a header line and at least one row");
            }
            List<String> header = lines.get(0).stream().map(String::trim).toList();
            if (!header.contains(key)) {
                throw new ApiError(422, "the header line needs the key column '" + key + "'; it has " + header);
            }
            // a column whose rows are all numbers or all yes/no stays that way, so a rule reading it keeps working
            Map<String, String> types = new java.util.HashMap<>();
            for (Object row : Ops.list(def.get("rows"))) {
                if (row instanceof Rec r) {
                    r.forEach((column, value) -> types.merge(column, value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : "text", (a, b) -> a.equals(b) ? a : "text"));
                }
            }
            java.util.LinkedHashMap<String, Rec> original = new java.util.LinkedHashMap<>();
            for (Object row : Ops.list(def.get("rows"))) {
                if (row instanceof Rec r) {
                    original.put(String.valueOf(r.get(key)), r);
                }
            }
            java.util.LinkedHashMap<String, Rec> rows = mode.equals("merge") ? new java.util.LinkedHashMap<>(original) : new java.util.LinkedHashMap<>();
            int added = 0, changed = 0;
            for (int n = 1; n < lines.size(); n++) {
                List<String> cells = lines.get(n);
                if (cells.size() == 1 && cells.get(0).isBlank()) {
                    continue;
                }
                if (cells.size() != header.size()) {
                    throw new ApiError(422, "line " + (n + 1) + " has " + cells.size() + " cell(s), the header has " + header.size());
                }
                Rec row = new Rec();
                for (int c = 0; c < header.size(); c++) {
                    String cell = cells.get(c).trim();
                    if (cell.isEmpty()) {
                        continue;
                    }
                    String type = types.getOrDefault(header.get(c), "text");
                    Object value = cell;
                    if (type.equals("number") && cell.matches("-?[0-9.]+")) {
                        try {
                            value = new java.math.BigDecimal(cell);
                        } catch (NumberFormatException keepText) {
                            value = cell;
                        }
                    } else if (type.equals("boolean") && (cell.equalsIgnoreCase("true") || cell.equalsIgnoreCase("false"))) {
                        value = Boolean.parseBoolean(cell);
                    }
                    row.put(header.get(c), value);
                }
                if (row.get(key) == null) {
                    throw new ApiError(422, "line " + (n + 1) + " has no value in the key column '" + key + "'");
                }
                Rec before = original.get(String.valueOf(row.get(key)));
                rows.put(String.valueOf(row.get(key)), row);
                if (before == null) {
                    added++;
                } else if (!before.equals(row)) {
                    changed++;
                }
            }
            int removed = (int) original.keySet().stream().filter(k -> !rows.containsKey(k)).count();
            def.put("rows", new ArrayList<Object>(rows.values()));
            String text = ModelSource.toText(def);
            Forge.Build build = overlayBuild(Rec.of("text", text));
            if (!build.ok()) {
                ctx.status(422);
                json(ctx, buildResult(build));
                return;
            }
            Rec approval = approvals.request("MODEL_CHANGE", "ReferenceTable " + def.str("name") + " from a file: " + rows.size() + " row(s), "
                    + added + " added, " + changed + " changed, " + removed + " removed",
                    Rec.of("name", def.str("name"), "kind", "ReferenceTable", "path", pathOf(def.str("name")), "text", text,
                            "baseDeployment", platform.deployments.activeId(), "import", Rec.of("mode", mode, "rows", rows.size(), "added", added, "changed", changed, "removed", removed)),
                    p.username(), body.str("comment"));
            json(ctx, approval);
        });
        routes.get("/api/studio/deployments", ctx -> {
            need(ctx, "studio.view");
            json(ctx, Rec.of("active", platform.deployments.activeId(),
                    "items", without(platform.store.find(DocStore.DEPLOYMENT, null, "version", true, 50), "models")));
        });
        // one deployment: what it changed compared with the one before, and what going back to it would change now
        routes.get("/api/studio/deployments/{id}", ctx -> {
            need(ctx, "studio.view");
            Rec deployment = storedDeployment(ctx.pathParam("id"));
            Rec previous = platform.deployments.previous(deployment);
            Rec active = platform.deployments.stored(platform.deployments.activeId());
            boolean isActive = deployment.str("id").equals(platform.deployments.activeId());
            Rec out = deployment.copy();
            out.remove("models");
            out.remove("seal");
            out.put("active", isActive);
            out.put("previous", previous == null ? null : previous.str("id"));
            out.put("changes", Deployments.changes(previous, deployment));
            out.put("rollback", isActive ? List.of() : Deployments.changes(active, deployment));
            json(ctx, out);
        });
        routes.post("/api/studio/rollbacks", ctx -> {
            Principal p = need(ctx, "studio.edit");
            Rec body = Json.parse(ctx.body());
            Rec target = storedDeployment(required(body, "deploymentId"));
            if (target.str("id").equals(platform.deployments.activeId())) {
                throw new ApiError(409, "deployment " + target.str("id") + " is the active one");
            }
            List<Rec> changes = Deployments.changes(platform.deployments.stored(platform.deployments.activeId()), target);
            if (changes.isEmpty()) {
                throw new ApiError(409, "the active deployment already has the same models as " + target.str("id"));
            }
            // models that were valid when they were deployed may not be any more, for example after a connector host was disallowed
            Forge.Build build = platform.deployments.build(Deployments.modelsOf(target));
            if (!build.ok()) {
                ctx.status(422);
                json(ctx, buildResult(build));
                return;
            }
            json(ctx, approvals.request("MODEL_ROLLBACK", "Rollback to " + target.str("id") + " (version " + target.get("version") + ")",
                    Rec.of("deploymentId", target.str("id"), "version", target.get("version"), "baseDeployment", platform.deployments.activeId(),
                            "models", changes.stream().map(c -> c.str("change") + " " + c.str("name")).toList()),
                    p.username(), body.str("comment")));
        });
        routes.post("/api/studio/removals", ctx -> {
            Principal p = need(ctx, "studio.edit");
            Rec body = Json.parse(ctx.body());
            String name = required(body, "name");
            Rec model = platform.deployments.activeModels().stream().filter(m -> m.str("name").equals(name)).findFirst()
                    .orElseThrow(() -> new ApiError(404, "no model named " + name));
            // whatever still uses the model is named by the build of the remaining ones
            Forge.Build build = platform.deployments.build(platform.deployments.without(name));
            if (!build.ok()) {
                ctx.status(422);
                json(ctx, buildResult(build));
                return;
            }
            json(ctx, approvals.request("MODEL_REMOVE", "Remove " + model.str("kind") + " " + name,
                    Rec.of("name", name, "kind", model.str("kind"), "path", model.str("path"), "baseDeployment", platform.deployments.activeId()),
                    p.username(), body.str("comment")));
        });
    }

    private void reviewRequest(Context ctx, String action) {
        Principal p = need(ctx, "payments.repair");
        String id = ctx.pathParam("id");
        Rec txn = visibleTxn(p, id);
        if (!Status.HELD.equals(txn.str("status"))) {
            throw new ApiError(409, "only a held transaction can be released or rejected");
        }
        String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
        json(ctx, approvals.request("PAYMENT_ACTION", (action.equals("RELEASE") ? "Release " : "Reject ") + id + " held for " + txn.str("hold.code"),
                Rec.of("action", action, "transactionId", id, "holdCode", txn.str("hold.code"), "comment", comment), p.username(), comment));
    }

    /** An Api model that a request meets, with the values of the placeholders in its path. */
    private record ApiMatch(Rec api, Rec pathValues) {
    }

    /** @return the Api model for the method and path (without the /api/x prefix); 404 or 405 when there is none */
    private ApiMatch matchApi(Registry registry, String method, String path) {
        String[] actual = path.split("/");
        boolean pathKnown = false;
        for (Rec api : registry.configs("Api")) {
            String[] template = api.str("path").split("/");
            Rec pathValues = new Rec();
            boolean match = template.length == actual.length;
            for (int i = 0; match && i < template.length; i++) {
                if (template[i].startsWith("{")) {
                    pathValues.put(template[i].substring(1, template[i].length() - 1), actual[i]);
                } else {
                    match = template[i].equals(actual[i]);
                }
            }
            if (!match) {
                continue;
            }
            pathKnown = true;
            if (api.str("method").equalsIgnoreCase(method)) {
                return new ApiMatch(api, pathValues);
            }
        }
        throw new ApiError(pathKnown ? 405 : 404, pathKnown ? "this path does not support " + method : "no API is deployed at /api/x" + path);
    }

    /** What running the flow behind an Api gave: the HTTP status it stands for and the answer. */
    private record ApiResult(int http, Object answer, Rec outcome) {
    }

    private ApiResult runApi(Registry registry, Rec api, Rec request, io.orvanta.core.flow.Elements.DataAccess data,
                             java.util.function.Function<String, Connector> connectors) {
        Rec scope = Rec.of("request", request);
        Rec result = TestRunner.execute(registry, api.str("target"), scope, connectors, data);
        result.remove("trace");
        String status = result.str("status");
        Object custom = status == null ? null : api.at("statusCodes." + status);
        int http = custom != null ? Ops.num(custom).intValue()
                : status == null || status.equals("COMPLETED") ? 200
                : status.equals("NOT_FOUND") ? 404 : status.equals("REJECTED") ? 422 : 500;
        // a flow answers with the scope variable 'response' when it set one; otherwise with its outcome
        Object answer = http < 300 && scope.get("response") != null ? scope.get("response") : http < 300 ? result
                : Rec.of("error", result.str("message") == null ? status : result.str("message"), "code", result.str("code"),
                        "violations", result.get("violations"));
        return new ApiResult(http, answer, result);
    }

    private void modelApi(Context ctx) {
        // a flow behind an API reads whatever its model says, so it cannot honour a data scope
        unscoped(principal(ctx));
        Registry registry = platform.deployments.registry();
        ApiMatch match = matchApi(registry, ctx.method().name(), ctx.path().substring("/api/x".length()));
        Principal p = need(ctx, match.api().str("permission"));
        Rec query = new Rec();
        ctx.queryParamMap().forEach((k, v) -> query.put(k, v.isEmpty() ? null : v.get(0)));
        Rec request = Rec.of("path", match.pathValues(), "query", query, "user", p.username());
        if (!ctx.body().isBlank()) {
            request.put("body", Json.parseAny(ctx.body()));
        }
        if (Boolean.TRUE.equals(match.api().get("approval")) && ctx.method() != io.javalin.http.HandlerType.GET) {
            // an API that changes data and asks for it: the call is a request a second person approves, and runs then
            Rec approval = approvals.request("API_CALL", ctx.method().name() + " /api/x" + ctx.path().substring("/api/x".length()) + " through " + match.api().str("name"),
                    Rec.of("api", match.api().str("name"), "method", ctx.method().name(), "path", match.pathValues(), "query", query, "body", request.get("body")),
                    p.username(), query.str("comment"));
            ctx.status(202);
            json(ctx, approval);
            return;
        }
        ApiResult result = runApi(registry, match.api(), request, platform.data, platform.deployments::connector);
        ctx.status(result.http());
        json(ctx, result.answer());
    }

    public static final String SOAP_NAMESPACE = "urn:orvanta:api";

    /**
     * An Api model with {@code soap: {operation: Name}} is also a SOAP operation: the Body holds one element of that
     * name (document/literal, wrapped); its child elements named like the REST path's placeholders are the path
     * values, the others the request body. The answer is {@code <NameResponse>} with the flow's response, a fault
     * otherwise. Callers authenticate as for the REST form (bearer token or X-Api-Key) and need the same permission.
     */
    private void soapApi(Context ctx) {
        unscoped(principal(ctx));
        Registry registry = platform.deployments.registry();
        String content = soapBodyContent(ctx.body());
        if (content == null) {
            fault(ctx, 400, "Client", "the request is not a SOAP envelope with one operation element in its Body", null);
            return;
        }
        Rec tree;
        try {
            tree = io.orvanta.core.format.IsoXml.parse(content);
        } catch (IllegalArgumentException e) {
            fault(ctx, 400, "Client", e.getMessage(), null);
            return;
        }
        String operation = tree.keySet().iterator().next();
        Rec api = null;
        for (Rec a : registry.configs("Api")) {
            if (operation.equals(a.at("soap.operation"))) {
                api = a;
            }
        }
        if (api == null) {
            fault(ctx, 400, "Client", "no operation named " + operation + "; GET /api/soap?wsdl lists them", null);
            return;
        }
        Principal p = need(ctx, api.str("permission"));
        Rec given = tree.get(operation) instanceof Rec r ? r : new Rec();
        java.util.Set<String> placeholders = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}").matcher(api.str("path"));
        while (m.find()) {
            placeholders.add(m.group(1));
        }
        Rec pathValues = new Rec();
        Rec body = new Rec();
        for (Map.Entry<String, Object> e : given.entrySet()) {
            if (e.getKey().startsWith("@") || e.getKey().equals("#text")) {
                continue;
            }
            if (placeholders.contains(e.getKey())) {
                pathValues.put(e.getKey(), Ops.str(e.getValue()));
            } else {
                body.put(e.getKey(), e.getValue());
            }
        }
        for (String placeholder : placeholders) {
            if (pathValues.get(placeholder) == null) {
                fault(ctx, 400, "Client", operation + " needs an element <" + placeholder + ">", null);
                return;
            }
        }
        Rec request = Rec.of("path", pathValues, "query", new Rec(), "user", p.username());
        if (!body.isEmpty()) {
            request.put("body", body);
        }
        if (Boolean.TRUE.equals(api.get("approval")) && !"GET".equalsIgnoreCase(api.str("method"))) {
            Rec approval = approvals.request("API_CALL", api.str("method") + " /api/x" + api.str("path") + " through " + api.str("name") + " (SOAP " + operation + ")",
                    Rec.of("api", api.str("name"), "method", api.str("method"), "path", pathValues, "query", new Rec(), "body", request.get("body")),
                    p.username(), null);
            ctx.status(202).contentType("text/xml").result(envelope(operation + "Response", Rec.of("approvalId", approval.str("id"), "status", approval.str("status"))));
            return;
        }
        ApiResult result = runApi(registry, api, request, platform.data, platform.deployments::connector);
        if (result.http() >= 300) {
            Rec detail = result.answer() instanceof Rec r ? r : null;
            fault(ctx, result.http(), result.http() >= 500 ? "Server" : "Client",
                    detail != null && detail.str("error") != null ? detail.str("error") : "the operation did not complete", detail);
            return;
        }
        Rec answer = result.answer() instanceof Rec r ? r.copy() : Rec.of("value", result.answer());
        ctx.status(200).contentType("text/xml").result(envelope(operation + "Response", answer));
    }

    private static String elementXml(String name, Rec value) {
        Rec element = value.copy();
        element.put("@xmlns", SOAP_NAMESPACE);
        String xml = io.orvanta.core.format.IsoXml.write(Rec.of(name, element));
        return xml.substring(xml.indexOf("?>") + 2).trim();
    }

    private static String envelope(String name, Rec value) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + elementXml(name, value) + "</soap:Body></soap:Envelope>";
    }

    private static void fault(Context ctx, int status, String code, String text, Rec detail) {
        String body = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault><faultcode>soap:" + code
                + "</faultcode><faultstring>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</faultstring>"
                + (detail == null ? "" : "<detail>" + elementXml("problem", detail) + "</detail>") + "</soap:Fault></soap:Body></soap:Envelope>";
        ctx.status(status).contentType("text/xml").result(body);
    }

    /** WSDL 1.1, document/literal wrapped: one operation per Api model with a soap operation name; the elements take any content. */
    static String wsdl(Registry registry) {
        StringBuilder types = new StringBuilder();
        StringBuilder messages = new StringBuilder();
        StringBuilder port = new StringBuilder();
        StringBuilder binding = new StringBuilder();
        for (Rec api : registry.configs("Api")) {
            String op = Ops.str(api.at("soap.operation"));
            if (op == null) {
                continue;
            }
            for (String element : List.of(op, op + "Response")) {
                types.append("<xs:element name=\"").append(element).append("\"><xs:complexType><xs:sequence><xs:any minOccurs=\"0\" maxOccurs=\"unbounded\" processContents=\"lax\"/></xs:sequence></xs:complexType></xs:element>");
            }
            messages.append("<wsdl:message name=\"").append(op).append("Request\"><wsdl:part name=\"parameters\" element=\"tns:").append(op).append("\"/></wsdl:message>")
                    .append("<wsdl:message name=\"").append(op).append("Response\"><wsdl:part name=\"parameters\" element=\"tns:").append(op).append("Response\"/></wsdl:message>");
            String doc = api.str("description") == null ? "" : "<wsdl:documentation>" + api.str("description").trim().replace("&", "&amp;").replace("<", "&lt;") + "</wsdl:documentation>";
            port.append("<wsdl:operation name=\"").append(op).append("\">").append(doc)
                    .append("<wsdl:input message=\"tns:").append(op).append("Request\"/><wsdl:output message=\"tns:").append(op).append("Response\"/></wsdl:operation>");
            binding.append("<wsdl:operation name=\"").append(op).append("\"><soap:operation soapAction=\"").append(SOAP_NAMESPACE).append('/').append(op).append("\"/>")
                    .append("<wsdl:input><soap:body use=\"literal\"/></wsdl:input><wsdl:output><soap:body use=\"literal\"/></wsdl:output></wsdl:operation>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<wsdl:definitions name=\"OrvantaApi\" targetNamespace=\"" + SOAP_NAMESPACE + "\" xmlns:tns=\"" + SOAP_NAMESPACE + "\""
                + " xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
                + "<wsdl:types><xs:schema targetNamespace=\"" + SOAP_NAMESPACE + "\" elementFormDefault=\"qualified\">" + types + "</xs:schema></wsdl:types>"
                + messages
                + "<wsdl:portType name=\"OrvantaApiPortType\">" + port + "</wsdl:portType>"
                + "<wsdl:binding name=\"OrvantaApiBinding\" type=\"tns:OrvantaApiPortType\"><soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>" + binding + "</wsdl:binding>"
                + "<wsdl:service name=\"OrvantaApi\"><wsdl:port name=\"OrvantaApiPort\" binding=\"tns:OrvantaApiBinding\"><soap:address location=\"/api/soap\"/></wsdl:port></wsdl:service>"
                + "</wsdl:definitions>";
    }

    // ---- data sets in the Console: rows are read here, and changed through a request that a second person approves ----

    /** The DataSet model of that name that has rows of its own and is offered in the Console. */
    private Rec consoleDataSet(String name) {
        Rec def = platform.deployments.registry().config(name);
        if (def == null || !"DataSet".equals(def.str("kind")) || def.str("collection") == null || !(def.get("console") instanceof Map<?, ?>)) {
            throw new ApiError(404, "no data set " + name + " is offered in the Console");
        }
        return def;
    }

    private void dataSets() {
        routes.get("/api/datasets", ctx -> {
            unscoped(need(ctx, "payments.view"));
            List<Object> out = new ArrayList<>();
            for (Rec def : platform.deployments.registry().configs("DataSet")) {
                if (def.str("collection") != null && def.get("console") instanceof Map<?, ?>) {
                    out.add(Rec.of("name", def.str("name"), "description", def.str("description"), "key", def.str("key"), "console", def.get("console"),
                            "rows", platform.store.count("orvd_" + def.str("collection"), null)));
                }
            }
            json(ctx, Rec.of("items", out));
        });
        routes.get("/api/datasets/{name}/rows", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec def = consoleDataSet(ctx.pathParam("name"));
            List<String> search = new ArrayList<>();
            Ops.list(def.at("console.search")).forEach(f -> search.add(String.valueOf(f)));
            if (search.isEmpty()) {
                search.add(def.str("key"));
            }
            List<String> sorts = new ArrayList<>(List.of(def.str("key"), "updatedAt"));
            Ops.list(def.at("console.columns")).forEach(c -> sorts.add(String.valueOf(c)));
            list(ctx, listPage(ctx, "orvd_" + def.str("collection"), new Rec(), search, sorts, def.str("key"), "updatedAt"));
        });
        routes.post("/api/datasets/{name}/requests", ctx -> {
            Principal p = unscoped(need(ctx, "payments.repair"));
            Rec def = consoleDataSet(ctx.pathParam("name"));
            Rec body = Json.parse(ctx.body());
            String action = required(body, "action");
            if (!List.of("write", "remove").contains(action)) {
                throw new ApiError(400, "action is write or remove");
            }
            Registry registry = platform.deployments.registry();
            Rec api = registry.config(String.valueOf(def.at("console." + action + ".api")));
            if (api == null || !"Api".equals(api.str("kind"))) {
                throw new ApiError(409, "data set " + def.str("name") + " cannot be changed this way from the Console");
            }
            // the path of the API is filled from the values given; each becomes one segment, so none may hold a slash
            Rec values = body.get("path") instanceof Map<?, ?> m ? Rec.from(m) : new Rec();
            Rec pathValues = new Rec();
            for (String segment : api.str("path").split("/")) {
                if (segment.startsWith("{")) {
                    String name = segment.substring(1, segment.length() - 1);
                    String value = values.str(name) == null ? "" : values.str(name).trim();
                    if (value.isEmpty() || !value.matches("[A-Za-z0-9+?:().,_-]{1,64}")) {
                        throw new ApiError(400, name + " is required: letters, digits and + ? : ( ) . , _ - only");
                    }
                    pathValues.put(name, value);
                }
            }
            Rec content = body.get("body") instanceof Map<?, ?> m ? Rec.from(m) : new Rec();
            content.entrySet().removeIf(e -> e.getValue() == null || String.valueOf(e.getValue()).isBlank());
            // tried on a copy of the rows first, so that a request the rules refuse never reaches an approver
            Rec request = Rec.of("path", pathValues, "query", new Rec(), "user", p.username(), "body", content.copy());
            io.orvanta.forge.MemoryDataAccess copy = new io.orvanta.forge.MemoryDataAccess(registry,
                    Rec.of(def.str("name"), new ArrayList<Object>(platform.store.find("orvd_" + def.str("collection"), null, null, false, 5000))));
            ApiResult trial = runApi(registry, api, request, copy, name -> {
                throw new IllegalStateException("external systems are not called while a request is checked");
            });
            if (trial.http() == 422 || trial.http() == 404) {
                ctx.status(trial.http());
                json(ctx, trial.answer());
                return;
            }
            String what = String.join(" ", pathValues.values().stream().map(String::valueOf).toList());
            json(ctx, approvals.request("DATA_CHANGE", (action.equals("write") ? "Set " : "Remove ") + def.at("console.title") + ": " + what,
                    Rec.of("dataset", def.str("name"), "action", action, "api", api.str("name"), "path", pathValues, "body", content), p.username(), body.str("comment")));
        });
    }

    private Rec storedDeployment(String id) {
        Rec deployment;
        try {
            deployment = platform.deployments.stored(id);
        } catch (IllegalStateException e) {
            throw new ApiError(409, e.getMessage());
        }
        if (deployment == null) {
            throw new ApiError(404, "no deployment " + id);
        }
        return deployment;
    }

    private Forge.Build overlayBuild(Rec body) {
        String text = required(body, "text");
        try {
            ModelSource source = ModelSource.parse("draft", text);
            return platform.deployments.build(platform.deployments.overlay(pathOf(source.name()), text));
        } catch (ModelException e) {
            return new Forge.Build(null, List.of(new Problem("model", e.where(), e.getMessage())));
        }
    }

    /** The deployed definition of a reference table, or 404 / 409 when there is none by that name. */
    private Rec referenceTable(String name) {
        for (Rec model : platform.deployments.activeModels()) {
            if (model.str("name").equals(name)) {
                Rec def = ModelSource.parse(model.str("path"), model.str("text")).def();
                if (!"ReferenceTable".equals(def.str("kind"))) {
                    throw new ApiError(409, name + " is a " + def.str("kind") + ", not a reference table");
                }
                return def;
            }
        }
        throw new ApiError(404, "no model named " + name);
    }

    /** Lines of a CSV text as cells: commas separate, quotes enclose (a doubled quote inside is one quote), CRLF or LF ends a line. */
    static List<List<String>> csvRows(String text) {
        List<List<String>> lines = new ArrayList<>();
        List<String> line = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String s = text.startsWith("\uFEFF") ? text.substring(1) : text;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                line.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                line.add(cell.toString());
                lines.add(line);
                line = new ArrayList<>();
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        if (quoted) {
            throw new ApiError(422, "the file ends inside a quoted cell");
        }
        if (cell.length() > 0 || !line.isEmpty()) {
            line.add(cell.toString());
            lines.add(line);
        }
        return lines;
    }

    /** The channel on which payments initiated from the Console form enter the engine. */
    static final String CONSOLE_CHANNEL = "channels.ConsoleInitiation";

    /** The fields a payment is initiated with; checked for shape, and for completeness when it is a payment rather than a template. */
    private static final List<String> INITIATION_FIELDS = List.of("debtor.name", "debtor.account", "debtor.agentBic", "creditor.name", "creditor.account", "creditor.agentBic",
            "amount", "currency", "requestedDate", "remittance", "chargeBearer", "purposeCode", "priority", "endToEndId");

    private static Rec initiationFields(Rec given, Principal by, boolean complete) {
        Rec out = new Rec();
        for (java.util.Map.Entry<String, Object> e : given.entrySet()) {
            if (!INITIATION_FIELDS.contains(e.getKey())) {
                throw new ApiError(422, "'" + e.getKey() + "' is not a field of a payment; the fields are " + INITIATION_FIELDS);
            }
            if (e.getValue() == null || String.valueOf(e.getValue()).isBlank()) {
                continue;
            }
            String value = String.valueOf(e.getValue()).trim();
            switch (e.getKey()) {
                case "amount" -> {
                    java.math.BigDecimal amount = Ops.num(e.getValue());
                    if (amount == null || amount.signum() <= 0 || amount.scale() > 5) {
                        throw new ApiError(422, "the amount is a number above zero");
                    }
                    out.put("amount", amount);
                    continue;
                }
                case "currency" -> {
                    if (!value.matches("[A-Z]{3}")) {
                        throw new ApiError(422, "the currency is a three-letter code such as ZAR");
                    }
                }
                case "debtor.agentBic", "creditor.agentBic" -> {
                    if (!value.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?")) {
                        throw new ApiError(422, "'" + e.getKey() + "' is a BIC of 8 or 11 characters");
                    }
                }
                case "requestedDate" -> {
                    try {
                        java.time.LocalDate.parse(value);
                    } catch (java.time.format.DateTimeParseException x) {
                        throw new ApiError(422, "the requested date is a date such as 2026-10-06");
                    }
                }
                case "chargeBearer" -> {
                    if (!List.of("DEBT", "CRED", "SHAR", "SLEV").contains(value)) {
                        throw new ApiError(422, "the charge bearer is DEBT, CRED, SHAR or SLEV");
                    }
                }
                case "priority" -> {
                    if (!List.of("HIGH", "NORM").contains(value)) {
                        throw new ApiError(422, "the priority is HIGH or NORM");
                    }
                }
                default -> {
                    if (value.length() > 140) {
                        throw new ApiError(422, "'" + e.getKey() + "' has up to 140 characters");
                    }
                }
            }
            out.put(e.getKey(), value);
        }
        if (complete) {
            for (String field : List.of("debtor.account", "creditor.name", "creditor.account", "amount", "currency")) {
                if (out.get(field) == null) {
                    throw new ApiError(422, "a payment needs '" + field + "'");
                }
            }
        }
        return out;
    }

    /** Some things only a signed-in person does, never a system calling with its key. */
    private static Principal person(Principal p, String why) {
        if (p.username().startsWith("key:")) {
            throw new ApiError(403, why);
        }
        return p;
    }

    /** The message inside a SOAP request: the text of the first element of the Body (SOAP 1.1 or 1.2), or null when there is none. */
    static String soapBodyContent(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        // the Body's content is cut out of the text as it is, so the message keeps its own namespaces and is not rewritten
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:[A-Za-z0-9_.-]+:)?Body(?:\\s[^>]*)?>(.*)</(?:[A-Za-z0-9_.-]+:)?Body>", java.util.regex.Pattern.DOTALL).matcher(xml);
        if (!m.find()) {
            return null;
        }
        String content = m.group(1).trim();
        return content.startsWith("<") ? content : null;
    }

    private static String soapFault(String code, String text) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault><faultcode>soap:" + code
                + "</faultcode><faultstring>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</faultstring></soap:Fault></soap:Body></soap:Envelope>";
    }

    private static String pathOf(String modelName) {
        return modelName.replace('.', '/') + ".yaml";
    }

    private static Rec buildResult(Forge.Build build) {
        List<Object> problems = new ArrayList<>();
        for (Problem p : build.problems()) {
            problems.add(p.toRec());
        }
        return Rec.of("ok", build.ok(), "problems", problems);
    }

    // ---- approvals, users, roles ----

    private void administration() {
        routes.get("/api/approvals", ctx -> {
            Principal p = principal(ctx);
            Rec page;
            if (p.scope().restricted()) {
                // which requests a limited user may see depends on the payment behind each, so they are looked at one by one
                List<Rec> items = new ArrayList<>(platform.store.find(DocStore.APPROVAL, filter(ctx, "status", "type"), "id", true, 2000));
                items.removeIf(a -> !paymentInScope(p, a));
                int offset = number(ctx, "offset", 0, 0, 10_000_000);
                page = Rec.of("items", new ArrayList<>(items.subList(Math.min(offset, items.size()), Math.min(items.size(), offset + limit(ctx)))),
                        "total", items.size(), "offset", offset);
            } else {
                page = listPage(ctx, DocStore.APPROVAL, filter(ctx, "status", "type", "maker"), List.of("id", "summary", "maker", "checker", "type"),
                        List.of("id", "status", "maker", "requestedAt"), "id", "requestedAt");
            }
            for (Object item : Ops.list(page.get("items"))) {
                ((Rec) item).remove("payload");
            }
            list(ctx, page);
        });
        routes.get("/api/approvals/{id}", ctx -> {
            Rec approval = found(platform.store.get(DocStore.APPROVAL, ctx.pathParam("id")), ctx.pathParam("id"));
            if (!paymentInScope(principal(ctx), approval)) {
                throw new ApiError(404, "no document " + ctx.pathParam("id"));
            }
            Rec payload = approval.rec("payload");
            payload.remove("passwordHash");
            payload.remove("keyHash");
            if ("MODEL_ROLLBACK".equals(approval.str("type")) && ApprovalService.PENDING.equals(approval.str("status"))) {
                // what approving would change right now, model by model
                try {
                    approval.put("changes", Deployments.changes(platform.deployments.stored(platform.deployments.activeId()),
                            platform.deployments.stored(payload.str("deploymentId"))));
                } catch (IllegalStateException e) {
                    approval.put("changesProblem", e.getMessage());
                }
            }
            if ("MODEL_CHANGE".equals(approval.str("type")) || "MODEL_REMOVE".equals(approval.str("type"))) {
                for (Rec model : platform.deployments.activeModels()) {
                    if (model.str("name").equals(payload.str("name"))) {
                        approval.put("currentText", model.str("text"));
                    }
                }
            }
            approval.put("approvePermission", approvals.approvePermission(approval.str("type")));
            approval.put("events", events(approval.str("id")));
            json(ctx, approval);
        });
        routes.post("/api/approvals/{id}/approve", ctx -> decide(ctx, true));
        routes.post("/api/approvals/{id}/decline", ctx -> decide(ctx, false));

        // ---- message checks: imported ISO 20022 schemas, and trying a message against what is installed ----
        routes.get("/api/schemas", ctx -> {
            need(ctx, "studio.view");
            List<Object> specs = new ArrayList<>();
            for (Rec spec : platform.deployments.registry().configs("MessageSpec")) {
                specs.add(Rec.of("messageType", spec.str("messageType"), "name", spec.str("name"), "description", spec.str("description")));
            }
            json(ctx, Rec.of("schemas", without(platform.store.find(DocStore.SCHEMA, null, "id", false, 500), "text"), "specs", specs));
        });
        routes.post("/api/schemas", ctx -> {
            Principal p = need(ctx, "studio.edit");
            Rec body = Json.parse(ctx.body());
            String text = required(body, "text");
            if (text.length() > 4_000_000) {
                throw new ApiError(413, "a schema may be at most 4 million characters");
            }
            String type;
            try {
                type = IsoXml.schemaMessageType(text);
                if (type == null) {
                    throw new ApiError(422, "this is not an ISO 20022 message schema: the target namespace must be urn:iso:std:iso:20022:tech:xsd:<message type>");
                }
                IsoXml.compileSchema(text);
            } catch (IllegalArgumentException e) {
                throw new ApiError(422, e.getMessage());
            }
            json(ctx, approvals.request("SCHEMA_IMPORT", (platform.store.get(DocStore.SCHEMA, type) == null ? "Import schema " : "Replace schema ") + type,
                    Rec.of("messageType", type, "fileName", body.str("fileName"), "characters", text.length(), "text", text), p.username(), body.str("comment")));
        });
        routes.post("/api/schemas/{type}/removal", ctx -> {
            Principal p = need(ctx, "studio.edit");
            String type = ctx.pathParam("type");
            if (platform.store.get(DocStore.SCHEMA, type) == null) {
                throw new ApiError(404, "no schema is imported for " + type);
            }
            json(ctx, approvals.request("SCHEMA_REMOVE", "Remove schema " + type, Rec.of("messageType", type), p.username(), Json.parse(ctx.body()).str("comment")));
        });
        // tries a message the way ingest would, without storing it: which check applies and what it finds
        routes.post("/api/studio/check-message", ctx -> {
            need(ctx, "studio.view");
            String raw = required(Json.parse(ctx.body()), "raw");
            List<Object> results = new ArrayList<>();
            try {
                for (Messages.Parsed parsed : Messages.parse(raw)) {
                    io.orvanta.pay.engine.MessageChecks.Result r = ingest.checks().check(parsed);
                    results.add(Rec.of("format", parsed.format(), "messageType", parsed.messageType(), "checkedAgainst", r.checkedAgainst(), "problems", r.problems()));
                }
            } catch (RuntimeException e) {
                throw new ApiError(422, "the message cannot be read: " + e.getMessage());
            }
            json(ctx, Rec.of("messages", results));
        });

        routes.get("/api/users", ctx -> {
            need(ctx, "admin.view");
            List<Object> inbound = new ArrayList<>();
            for (Rec channel : platform.deployments.registry().configs("Channel")) {
                if ("inbound".equals(channel.str("direction"))) {
                    inbound.add(channel.str("name"));
                }
            }
            List<Rec> users = without(platform.store.find(DocStore.USER, null, "id", false, 500), "passwordHash");
            for (Rec user : users) {
                // the secret of the second step never leaves the server; only whether it is on
                user.put("mfa", Rec.of("enabled", Boolean.TRUE.equals(user.at("mfa.enabled"))));
            }
            json(ctx, Rec.of("items", users, "inboundChannels", inbound));
        });
        routes.get("/api/roles", ctx -> {
            need(ctx, "admin.view");
            json(ctx, Rec.of("items", platform.store.find(DocStore.ROLE, null, "id", false, 200), "permissions", AuthService.PERMISSIONS));
        });
        routes.post("/api/users", ctx -> {
            Principal p = need(ctx, "admin.edit");
            Rec body = Json.parse(ctx.body());
            String username = required(body, "username");
            if (!username.matches("[a-z][a-z0-9._-]{2,31}")) {
                throw new ApiError(400, "user name must be 3 to 32 lower case letters, digits, dot, dash or underscore");
            }
            Rec existing = platform.store.get(DocStore.USER, username);
            List<Object> roles = new ArrayList<>(Ops.list(body.get("roles")));
            for (Object role : roles) {
                if (platform.store.get(DocStore.ROLE, Ops.str(role)) == null) {
                    throw new ApiError(400, "no role named " + role);
                }
            }
            Rec payload = Rec.of("username", username, "displayName", body.str("displayName"), "roles", roles,
                    "status", body.str("status") == null ? "ACTIVE" : body.str("status"));
            if (Boolean.TRUE.equals(body.get("resetMfa"))) {
                payload.put("resetMfa", true);
            }
            Scope scope = Scope.of(body.get("scope"));
            for (String channel : scope.channels()) {
                Rec config = platform.deployments.registry().config(channel);
                if (config == null || !"Channel".equals(config.str("kind")) || !"inbound".equals(config.str("direction"))) {
                    throw new ApiError(400, "'" + channel + "' is not an inbound channel");
                }
            }
            for (String account : scope.debtorAccounts()) {
                if (!account.matches("[A-Za-z0-9]{1,34}")) {
                    throw new ApiError(400, "'" + account + "' is not an account number: up to 34 letters and digits, without spaces");
                }
            }
            for (String currency : scope.currencies()) {
                if (!currency.matches("[A-Z]{3}")) {
                    throw new ApiError(400, "'" + currency + "' is not a currency code (three letters)");
                }
            }
            if (scope.maxAmount() != null && scope.maxAmount().signum() <= 0) {
                throw new ApiError(400, "the amount a user is limited to must be greater than zero");
            }
            payload.put("scope", scope.restricted() ? scope.toRec() : null);
            String password = body.str("password");
            if (password != null && !password.isEmpty()) {
                String problem = io.orvanta.pay.security.Policies.passwordProblem(username, password);
                if (problem != null) {
                    throw new ApiError(400, problem);
                }
                // only the hash is kept in the request; the password itself is never stored
                payload.put("passwordHash", Crypto.hashPassword(password));
            } else if (existing == null) {
                throw new ApiError(400, "a new user needs a password");
            }
            json(ctx, approvals.request("USER_CHANGE", (existing == null ? "Create user " : "Change user ") + username
                    + " with roles " + roles + (scope.restricted() ? ", limited to " + describe(scope) : ""), payload, p.username(), body.str("comment")));
        });
        // roles of your own: a set of permissions under a name. The built-in roles stay as they are.
        routes.post("/api/roles", ctx -> {
            Principal p = need(ctx, "admin.edit");
            Rec body = Json.parse(ctx.body());
            String id = required(body, "id");
            if (!id.matches("[A-Z][A-Z0-9_]{2,31}")) {
                throw new ApiError(400, "a role name is 3 to 32 capital letters, digits or underscores, starting with a letter");
            }
            if (AuthService.BUILT_IN_ROLES.contains(id)) {
                throw new ApiError(409, id + " is a built-in role and cannot be changed");
            }
            List<Object> permissions = new ArrayList<>(new java.util.LinkedHashSet<>(Ops.list(body.get("permissions"))));
            if (permissions.isEmpty()) {
                throw new ApiError(400, "a role needs at least one permission");
            }
            for (Object permission : permissions) {
                if (!AuthService.PERMISSIONS.contains(String.valueOf(permission))) {
                    throw new ApiError(400, "'" + permission + "' is not a permission; the permissions are " + AuthService.PERMISSIONS);
                }
            }
            boolean exists = platform.store.get(DocStore.ROLE, id) != null;
            // approval limits: per currency, or "*" for any, the most a payment action may be approved for by this role
            Rec limits = new Rec();
            if (body.get("approvalLimits") instanceof java.util.Map<?, ?> wanted) {
                for (java.util.Map.Entry<?, ?> e : wanted.entrySet()) {
                    String currency = String.valueOf(e.getKey()).trim().toUpperCase(java.util.Locale.ROOT);
                    java.math.BigDecimal amount = Ops.num(e.getValue());
                    if (!currency.matches("[A-Z]{3}|\\*") || amount == null || amount.signum() <= 0) {
                        throw new ApiError(400, "approval limits are a currency (or *) and an amount above zero each");
                    }
                    limits.put(currency, amount);
                }
            }
            json(ctx, approvals.request("ROLE_CHANGE", (exists ? "Change role " : "Create role ") + id + " with " + permissions
                    + (limits.isEmpty() ? "" : ", approving up to " + limits),
                    Rec.of("id", id, "description", body.str("description"), "permissions", permissions, "approvalLimits", limits), p.username(), body.str("comment")));
        });
        routes.post("/api/roles/{id}/removal", ctx -> {
            Principal p = need(ctx, "admin.edit");
            String id = ctx.pathParam("id");
            if (AuthService.BUILT_IN_ROLES.contains(id)) {
                throw new ApiError(409, id + " is a built-in role and cannot be removed");
            }
            found(platform.store.get(DocStore.ROLE, id), id);
            List<String> holders = holders(id);
            if (!holders.isEmpty()) {
                throw new ApiError(409, "role " + id + " is still held by " + holders + "; take it from them first");
            }
            json(ctx, approvals.request("ROLE_REMOVE", "Remove role " + id, Rec.of("id", id), p.username(),
                    ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment")));
        });
    }

    private List<String> holders(String role) {
        List<String> out = new ArrayList<>();
        for (Rec user : platform.store.find(DocStore.USER, null, "id", false, 0)) {
            if (Ops.list(user.get("roles")).contains(role)) {
                out.add(user.str("id"));
            }
        }
        return out;
    }

    private static String describe(Scope scope) {
        List<String> parts = new ArrayList<>();
        if (!scope.channels().isEmpty()) {
            parts.add("channels " + scope.channels());
        }
        if (!scope.debtorAccounts().isEmpty()) {
            parts.add("debtor accounts " + scope.debtorAccounts());
        }
        if (!scope.currencies().isEmpty()) {
            parts.add("currencies " + scope.currencies());
        }
        if (scope.maxAmount() != null) {
            parts.add("amounts up to " + scope.maxAmount().toPlainString());
        }
        return String.join(" and ", parts);
    }

    private void decide(Context ctx, boolean approve) {
        Principal p = person(principal(ctx), "approvals are decided by people, not by a system's key");
        String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
        Rec pending = platform.store.get(DocStore.APPROVAL, ctx.pathParam("id"));
        if (pending != null && !paymentInScope(p, pending)) {
            throw new ApiError(404, "no document " + ctx.pathParam("id"));
        }
        // a payment action is approved only by someone whose role may approve that much: the payment's amount, or the amount of a payment being initiated
        if (approve && pending != null && "PAYMENT_ACTION".equals(pending.str("type"))) {
            Rec about = pending.rec("payload").str("transactionId") != null ? platform.store.get(DocStore.TXN, pending.rec("payload").str("transactionId")) : pending.rec("payload");
            if (about != null && about.get("amount") != null && !p.mayApprove(Ops.num(about.get("amount")), about.str("currency"))) {
                security.record("DENIED", p.username(), address(ctx), "approval of " + pending.str("id") + ": " + about.get("amount") + " " + about.str("currency") + " is above the approval limit");
                throw new ApiError(403, "this payment (" + about.get("amount") + " " + about.str("currency") + ") is above what your role may approve");
            }
        }
        Rec approval = approvals.decide(ctx.pathParam("id"), approve, p, comment);
        approval.rec("payload").remove("passwordHash");
        approval.rec("payload").remove("keyHash");
        json(ctx, approval);
    }

    private void registerApprovalActions() {
        approvals.register("MODEL_CHANGE", "studio.approve", approval -> {
            Rec payload = approval.rec("payload");
            List<Rec> models = platform.deployments.overlay(payload.str("path"), payload.str("text"));
            Rec deployment = platform.deployments.deploy(models, approval.str("maker"), approval.str("checker"),
                    approval.str("summary") + (approval.str("makerComment") == null ? "" : ": " + approval.str("makerComment")));
            if (platform.config.getBool("workspace.writeBack", false)) {
                for (Rec model : models) {
                    if (model.str("name").equals(payload.str("name"))) {
                        Deployments.writeBack(platform.workspaceDir, model.str("path"), model.str("text"));
                    }
                }
            }
            return Rec.of("deploymentId", deployment.str("id"), "version", deployment.get("version"));
        });
        approvals.register("SCHEMA_IMPORT", "studio.approve", approval -> {
            Rec payload = approval.rec("payload");
            String type = payload.str("messageType");
            IsoXml.compileSchema(payload.str("text"));
            platform.store.save(DocStore.SCHEMA, Rec.of("id", type, "messageType", type, "fileName", payload.str("fileName"),
                    "characters", payload.get("characters"), "text", payload.str("text"), "importedBy", approval.str("maker"),
                    "approvedBy", approval.str("checker"), "importedAt", Platform.now()));
            ingest.checks().schemaChanged(type);
            return Rec.of("messageType", type);
        });
        approvals.register("SCHEMA_REMOVE", "studio.approve", approval -> {
            String type = approval.rec("payload").str("messageType");
            platform.store.delete(DocStore.SCHEMA, type);
            ingest.checks().schemaChanged(type);
            return Rec.of("messageType", type);
        });
        approvals.register("MODEL_REMOVE", "studio.approve", approval -> {
            Rec payload = approval.rec("payload");
            Rec deployment = platform.deployments.deploy(platform.deployments.without(payload.str("name")), approval.str("maker"),
                    approval.str("checker"), approval.str("summary") + (approval.str("makerComment") == null ? "" : ": " + approval.str("makerComment")));
            if (platform.config.getBool("workspace.writeBack", false)) {
                Deployments.removeFromWorkspace(platform.workspaceDir, payload.str("path"));
            }
            return Rec.of("deploymentId", deployment.str("id"), "version", deployment.get("version"));
        });
        approvals.register("MODEL_ROLLBACK", "studio.approve", approval -> {
            Rec payload = approval.rec("payload");
            // the approver decided on the difference to one deployment; if another came in between, that difference is no longer true
            if (!platform.deployments.activeId().equals(payload.str("baseDeployment"))) {
                throw new IllegalStateException("the active deployment changed since the rollback was requested; request it again");
            }
            Rec active = platform.deployments.stored(platform.deployments.activeId());
            Rec target = platform.deployments.stored(payload.str("deploymentId"));
            if (target == null) {
                throw new IllegalStateException("deployment " + payload.str("deploymentId") + " no longer exists");
            }
            // history is never rewritten: the earlier models become a new deployment
            Rec deployment = platform.deployments.deploy(Deployments.modelsOf(target), approval.str("maker"), approval.str("checker"),
                    approval.str("summary") + (approval.str("makerComment") == null ? "" : ": " + approval.str("makerComment")));
            if (platform.config.getBool("workspace.writeBack", false)) {
                for (Rec change : Deployments.changes(active, target)) {
                    if ("REMOVED".equals(change.str("change"))) {
                        Deployments.removeFromWorkspace(platform.workspaceDir, change.str("path"));
                    } else {
                        Deployments.writeBack(platform.workspaceDir, change.str("path"), change.str("after"));
                    }
                }
            }
            return Rec.of("deploymentId", deployment.str("id"), "version", deployment.get("version"), "restored", target.str("id"));
        });
        approvals.register("PAYMENT_ACTION", "payments.approve", approval -> {
            Rec payload = approval.rec("payload");
            String id = payload.str("transactionId");
            String actor = approval.str("maker") + " (approved by " + approval.str("checker") + ")";
            if ("INITIATE".equals(payload.str("action"))) {
                // the approved payment enters the engine now, as a one-payment instruction on the Console channel
                Rec message = payload.rec("message");
                List<Rec> stored = ingest.receive(Json.pretty(message), "console-" + message.str("msgId") + ".json", CONSOLE_CHANNEL, actor);
                Rec instruction = stored.isEmpty() ? null : stored.get(0);
                if (instruction == null || Status.REJECTED.equals(instruction.str("status"))) {
                    throw new IllegalStateException(instruction == null ? "the payment was not stored" : instruction.str("reasonCode") + ": " + instruction.str("reasonText"));
                }
                return Rec.of("instructionId", instruction.str("id"), "endToEndId", message.str("endToEndId"), "outcome", "SUBMITTED");
            }
            if ("REFUND".equals(payload.str("action"))) {
                io.orvanta.pay.engine.Incoming.refund(platform, id, payload.str("reasonCode"), payload.str("reasonText"), actor);
                return Rec.of("transactionId", id, "outcome", "REFUND_STARTED");
            }
            if ("RECALL_ACCEPT".equals(payload.str("action")) || "RECALL_REFUSE".equals(payload.str("action"))) {
                Rec txn = platform.store.get(DocStore.TXN, id);
                if (txn == null || !"OPEN".equals(txn.str("recall.status"))) {
                    throw new IllegalStateException("the recall is no longer waiting for a decision");
                }
                Rec recall = txn.rec("recall");
                if ("RECALL_ACCEPT".equals(payload.str("action"))) {
                    io.orvanta.pay.engine.Incoming.refund(platform, id, "FOCR", "Following the cancellation request of the sender"
                            + (recall.str("reasonCode") == null ? "" : " (" + recall.str("reasonCode") + ")"), actor);
                    return Rec.of("transactionId", id, "outcome", "RECALL_ACCEPTED");
                }
                // the claim comes first, so that two approvals cannot send two answers
                if (!platform.store.updateIf(DocStore.TXN, id, Rec.of("recall.status", "OPEN"), Rec.of("recall.status", "REFUSED", "recall.decidedBy", actor,
                        "recall.answerCode", payload.str("reasonCode"), "recall.answerText", payload.str("reasonText"), "updatedAt", Platform.now()))) {
                    throw new IllegalStateException("the recall is no longer waiting for a decision");
                }
                String answerId = io.orvanta.pay.engine.Incoming.answerRecall(platform, recall, txn, payload.str("reasonCode"), payload.str("reasonText"), actor);
                platform.store.updateIf(DocStore.TXN, id, new Rec(), Rec.of("recall.answerId", answerId));
                platform.event(id, "RECALL_REFUSED", "the sender was told: " + payload.str("reasonCode") + " "
                        + (payload.str("reasonText") == null ? "" : payload.str("reasonText")) + " (" + answerId + ")", null, actor);
                return Rec.of("transactionId", id, "outcome", "RECALL_REFUSED", "answerId", answerId);
            }
            if ("CLAIM_PAID".equals(payload.str("action"))) {
                Rec txn = platform.store.get(DocStore.TXN, id);
                String was = txn == null ? null : txn.str("charges.claimStatus");
                if (was == null || !List.of("REQUESTED", "OPEN", "FAILED").contains(was)
                        || !platform.store.updateIf(DocStore.TXN, id, Rec.of("charges.claimStatus", was), Rec.of("charges.claimStatus", "PAID",
                                "charges.claimPaidAt", Platform.now(), "charges.claimPaidReference", payload.str("reference"), "updatedAt", Platform.now()))) {
                    throw new IllegalStateException("the claim is no longer waiting to be paid");
                }
                platform.event(id, "CHARGE_CLAIM_PAID", "the charge claim was paid" + (payload.str("reference") == null ? "" : ": " + payload.str("reference")), null, actor);
                return Rec.of("transactionId", id, "outcome", "CLAIM_PAID");
            }
            if ("REPLAY".equals(payload.str("action"))) {
                // the stored message is received again as a new message, for example after a mapping was corrected
                String messageId = payload.str("messageId");
                Rec message = platform.store.get(DocStore.MESSAGE, messageId);
                if (message == null || !Status.REJECTED.equals(message.str("status")) || message.str("replayedAs") != null) {
                    throw new IllegalStateException("message " + messageId + " can no longer be replayed");
                }
                List<Rec> again = ingest.receive(message.str("raw"), message.str("fileName"), null, actor);
                String newId = again.get(0).str("id");
                platform.store.updateIf(DocStore.MESSAGE, messageId, new Rec(), Rec.of("replayedAs", newId));
                platform.store.updateIf(DocStore.MESSAGE, newId, new Rec(), Rec.of("replayOf", messageId));
                platform.event(messageId, "REPLAYED", "received again as " + newId, null, actor);
                return Rec.of("messageId", newId, "status", again.get(0).str("status"));
            }
            if ("RELEASE".equals(payload.str("action")) || "REJECT".equals(payload.str("action"))) {
                boolean done = "RELEASE".equals(payload.str("action"))
                        ? review.release(id, actor, payload.str("comment")) : review.reject(id, actor, payload.str("comment"));
                if (!done) {
                    throw new IllegalStateException("transaction " + id + " is no longer held");
                }
                return Rec.of("transactionId", id, "decision", payload.str("action"));
            }
            if ("RTP_ACCEPT".equals(payload.str("action")) || "RTP_REFUSE".equals(payload.str("action"))) {
                return requestsToPay.answer(payload.str("requestId"), "RTP_ACCEPT".equals(payload.str("action")), payload.str("reasonCode"), payload.str("reasonText"), actor);
            }
            if ("STATEMENT_MATCH".equals(payload.str("action"))) {
                return statements.matchByHand(payload.str("statementId"), Ops.num(payload.get("entry")).intValue(), payload.str("transactionId"), actor, payload.str("note"));
            }
            if ("INVESTIGATION_ANSWER".equals(payload.str("action"))) {
                return investigations.answerCase(id, payload.str("caseId"), payload.str("confirmation"), payload.str("text"), actor);
            }
            if ("RELEASE_NOW".equals(payload.str("action"))) {
                if (!platform.store.updateIf(DocStore.TXN, id, Rec.of("status", Status.WAREHOUSED),
                        Rec.of("status", Status.CREATED, "warehouse.releasedEarlyBy", actor, "updatedAt", Platform.now()))) {
                    throw new IllegalStateException("transaction " + id + " is no longer warehoused");
                }
                platform.event(id, "RELEASED", "released from the warehouse before its time", null, actor);
                platform.bus.publish(io.orvanta.pay.kernel.Bus.TXN_CREATED, Rec.of("id", id));
                return Rec.of("transactionId", id, "outcome", "RELEASED");
            }
            if ("RESEND".equals(payload.str("action"))) {
                String outboundId = payload.str("outboundId");
                Rec current = platform.store.get(DocStore.OUTBOUND, outboundId);
                if (current == null || !dispatch.handle(outboundId, current.str("status"))) {
                    throw new IllegalStateException("the file " + outboundId + " could not be sent again");
                }
                platform.event(outboundId, "RESENT", "sent again at the request of " + approval.str("maker") + ", approved by " + approval.str("checker"), null, actor);
                return Rec.of("outboundId", outboundId, "outcome", "RESENT");
            }
            if ("CANCEL_INSTRUCTION".equals(payload.str("action"))) {
                int cancelled = 0;
                int refused = 0;
                List<Object> details = new ArrayList<>();
                for (Rec txn : platform.store.find(DocStore.TXN, Rec.of("instructionId", payload.str("messageId")), "id", false, 10_000)) {
                    // what is over is left alone; an accepted payment can still be asked back, so it is tried
                    if (List.of(Status.REJECTED_BY_APPLICATION, Status.REJECTED_BY_EXTERNAL, Status.CANCELLED, Status.RETURNED, Status.CREDITED, Status.DEBITED)
                            .contains(txn.str("status"))) {
                        continue;
                    }
                    Rec result = cancellation.requestCancel(txn.str("id"), payload.str("reasonCode"),
                            payload.str("reasonText") == null ? "Cancelled by the bank" : payload.str("reasonText"), actor, approval.str("id"));
                    boolean no = CancellationService.REFUSED.equals(result.str("outcome"));
                    cancelled += no ? 0 : 1;
                    refused += no ? 1 : 0;
                    details.add(Rec.of("transactionId", txn.str("id"), "outcome", result.str("outcome"), "detail", result.str("detail")));
                }
                platform.event(payload.str("messageId"), "INSTRUCTION_CANCELLED", cancelled + " payment(s) cancelled or asked to be cancelled, " + refused + " could not be", null, actor);
                return Rec.of("messageId", payload.str("messageId"), "cancelled", cancelled, "refused", refused, "payments", details);
            }
            if ("RESUBMIT".equals(payload.str("action"))) {
                if (payload.get("changes") instanceof Rec changes && !changes.isEmpty()) {
                    // the repair itself: applied right before the payment is processed again, and kept on it
                    Rec txn = platform.store.get(DocStore.TXN, id);
                    if (txn == null || !Status.REPAIR.equals(txn.str("status"))) {
                        throw new IllegalStateException("transaction " + id + " is no longer in REPAIR");
                    }
                    Rec applied = new Rec();
                    for (java.util.Map.Entry<String, Object> e : changes.entrySet()) {
                        applied.put(e.getKey(), ((Rec) e.getValue()).get("to"));
                    }
                    List<Object> repairs = new ArrayList<>(Ops.list(txn.get("repairs")));
                    repairs.add(Rec.of("at", Platform.now(), "by", actor, "changes", changes));
                    applied.put("repairs", repairs);
                    applied.put("updatedAt", Platform.now());
                    platform.store.updateIf(DocStore.TXN, id, Rec.of("status", Status.REPAIR), applied);
                    platform.event(id, "REPAIRED", "changed " + String.join(", ", changes.keySet()) + " before resubmission", Rec.of("changes", changes), actor);
                }
                if (!processing.handle(id, Status.REPAIR, actor)) {
                    throw new IllegalStateException("transaction " + id + " is no longer in REPAIR");
                }
                return Rec.of("transactionId", id, "status", platform.store.get(DocStore.TXN, id).str("status"));
            }
            Rec result = cancellation.requestCancel(id, payload.str("reasonCode"),
                    payload.str("reasonText") == null ? "Cancelled by the bank" : payload.str("reasonText"), actor, approval.str("id"));
            if (CancellationService.REFUSED.equals(result.str("outcome"))) {
                throw new IllegalStateException(result.str("detail"));
            }
            return result;
        });
        approvals.register("CHANNEL_CONTROL", "payments.approve", approval -> {
            Rec payload = approval.rec("payload");
            boolean pause = Boolean.TRUE.equals(payload.get("paused"));
            // "*" is every outbound channel of the active deployment: the kill switch
            List<String> names = new ArrayList<>();
            if ("*".equals(payload.str("channel"))) {
                for (Rec c : platform.deployments.registry().configs("Channel")) {
                    if ("outbound".equals(c.str("direction"))) {
                        names.add(c.str("name"));
                    }
                }
            } else {
                names.add(payload.str("channel"));
            }
            for (String name : names) {
                platform.store.save(DocStore.SETTING, Rec.of("id", "channel.paused." + name, "channel", name, "paused", pause,
                        "by", approval.str("maker") + " (approved by " + approval.str("checker") + ")", "at", Platform.now()));
                platform.event(name, pause ? "CHANNEL_PAUSED" : "CHANNEL_RESUMED", approval.str("summary"), null, approval.str("maker"));
                if (!pause) {
                    // what waited goes now
                    for (Rec o : platform.store.find(DocStore.OUTBOUND, Rec.of("status", Status.CREATED, "channel", name), "id", false, 1000)) {
                        platform.bus.publish(io.orvanta.pay.kernel.Bus.OUTBOUND_CREATED, Rec.of("id", o.str("id")));
                    }
                }
            }
            platform.changed("payments");
            return Rec.of("channels", names, "paused", pause);
        });
        // a call of a model-defined API that asks for approval: run as the maker, once a second person has approved
        approvals.register("API_CALL", "payments.approve", approval -> {
            Rec payload = approval.rec("payload");
            Registry registry = platform.deployments.registry();
            Rec api = registry.config(payload.str("api"));
            if (api == null || !"Api".equals(api.str("kind"))) {
                throw new IllegalStateException("the API " + payload.str("api") + " is no longer deployed");
            }
            Rec request = Rec.of("path", payload.rec("path"), "query", payload.get("query") instanceof Rec q ? q : new Rec(), "body", payload.get("body"),
                    "user", approval.str("maker") + " (approved by " + approval.str("checker") + ")");
            ApiResult result = runApi(registry, api, request, platform.data, platform.deployments::connector);
            if (result.http() >= 300) {
                throw new IllegalStateException("the call was refused: " + Json.write(result.answer()));
            }
            platform.changed("payments");
            return Rec.of("api", payload.str("api"), "http", result.http(), "answer", result.answer());
        });
        approvals.register("DATA_CHANGE", "payments.approve", approval -> {
            Rec payload = approval.rec("payload");
            Registry registry = platform.deployments.registry();
            Rec api = registry.config(payload.str("api"));
            if (api == null || !"Api".equals(api.str("kind"))) {
                throw new IllegalStateException("the API " + payload.str("api") + " is no longer deployed");
            }
            Rec request = Rec.of("path", payload.rec("path"), "query", new Rec(), "body", payload.rec("body"),
                    "user", approval.str("maker") + " (approved by " + approval.str("checker") + ")");
            ApiResult result = runApi(registry, api, request, platform.data, platform.deployments::connector);
            if (result.http() >= 300) {
                throw new IllegalStateException("the change was not made: " + Json.write(result.answer()));
            }
            platform.changed("payments");
            return Rec.of("dataset", payload.str("dataset"), "outcome", result.outcome().str("status"));
        });
        approvals.register("ROLE_CHANGE", "admin.approve", approval -> {
            Rec payload = approval.rec("payload");
            if (AuthService.BUILT_IN_ROLES.contains(payload.str("id"))) {
                throw new IllegalStateException(payload.str("id") + " is a built-in role");
            }
            // roles are read on every request, so the change reaches signed-in users at once
            platform.store.save(DocStore.ROLE, Rec.of("id", payload.str("id"), "description", payload.str("description"),
                    "permissions", Ops.list(payload.get("permissions")), "approvalLimits", payload.get("approvalLimits"), "builtIn", false, "changedBy", approval.str("maker"),
                    "approvedBy", approval.str("checker"), "changedAt", Platform.now()));
            return Rec.of("role", payload.str("id"));
        });
        approvals.register("ROLE_REMOVE", "admin.approve", approval -> {
            String id = approval.rec("payload").str("id");
            List<String> holders = holders(id);
            if (AuthService.BUILT_IN_ROLES.contains(id) || !holders.isEmpty()) {
                throw new IllegalStateException("role " + id + " cannot be removed" + (holders.isEmpty() ? "" : ": it is held by " + holders));
            }
            platform.store.delete(DocStore.ROLE, id);
            return Rec.of("role", id);
        });
        approvals.register("API_KEY_CHANGE", "admin.approve", approval -> {
            Rec payload = approval.rec("payload");
            Rec key = platform.store.get(DocStore.API_KEY, payload.str("id"));
            if (key == null) {
                key = Rec.of("id", payload.str("id"), "createdAt", Platform.now(), "createdBy", approval.str("maker"));
            }
            key.set("description", payload.str("description"));
            key.put("roles", Ops.list(payload.get("roles")));
            key.put("channels", Ops.list(payload.get("channels")));
            key.put("status", payload.str("status"));
            if (payload.str("keyHash") != null) {
                key.put("keyHash", payload.str("keyHash"));
                key.put("rotatedAt", Platform.now());
            }
            key.put("changedBy", approval.str("maker") + " (approved by " + approval.str("checker") + ")");
            platform.store.save(DocStore.API_KEY, key);
            return Rec.of("id", key.str("id"), "status", key.str("status"));
        });
        approvals.register("USER_CHANGE", "admin.approve", approval -> {
            Rec payload = approval.rec("payload");
            String username = payload.str("username");
            Rec user = platform.store.get(DocStore.USER, username);
            if (user == null) {
                user = Rec.of("id", username, "createdAt", Platform.now(), "createdBy", approval.str("maker"), "failedLogins", 0);
            }
            user.set("displayName", payload.str("displayName"));
            user.put("roles", Ops.list(payload.get("roles")));
            user.put("status", payload.str("status"));
            if (payload.get("scope") instanceof Map<?, ?>) {
                user.put("scope", payload.get("scope"));
            } else {
                user.remove("scope");
            }
            if (payload.str("passwordHash") != null) {
                user.put("passwordHash", payload.str("passwordHash"));
            }
            // a user who lost the device: the second step is taken off, to be set up again
            if (Boolean.TRUE.equals(payload.get("resetMfa"))) {
                user.remove("mfa");
            }
            // a changed password, role set or status ends the sessions the user has open
            user.put("tokenVersion", (user.get("tokenVersion") == null ? 0 : Ops.num(user.get("tokenVersion")).longValue()) + 1);
            user.remove("lockedUntil");
            if ("ACTIVE".equals(payload.str("status"))) {
                user.put("failedLogins", 0);
            }
            user.put("updatedAt", Platform.now());
            user.put("approvedBy", approval.str("checker"));
            platform.store.save(DocStore.USER, user);
            return Rec.of("username", username);
        });
    }

    // ---- helpers ----

    /** The caller's address; the forwarded one only when a proxy we trust is in front. */
    private String address(Context ctx) {
        if (platform.config.getBool("server.behindTlsProxy", false)) {
            String forwarded = ctx.header("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return ctx.ip();
    }

    private static Principal principal(Context ctx) {
        return ctx.attribute("principal");
    }

    private static final String SESSION_COOKIE = "orv_session";
    private final io.orvanta.pay.engine.ScheduleService schedules;

    /** The single sign-on provider, or null when sso.enabled is not true. */
    private final io.orvanta.pay.security.OpenIdConnect sso;

    /** HttpOnly: no script reads it. SameSite=Strict: no other site makes the browser send it. Path=/api: nothing else gets it. */
    private static String sessionCookie(String token, boolean secure, boolean expire) {
        return SESSION_COOKIE + "=" + token + "; Path=/api; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "") + (expire ? "; Max-Age=0" : "");
    }

    /**
     * The ledger. Under /ledger it answers flows the way an account system does (the connectors for
     * account lookup, posting and reversal can point at it); under /api/ledger people look at accounts
     * and ask for accounts and postings, which a second person approves.
     */
    private void declareLedger(io.javalin.config.RoutesConfig routes) {
        if (!platform.config.getBool("ledger.enabled", true)) {
            return;
        }
        io.orvanta.pay.engine.Ledger ledger = new io.orvanta.pay.engine.Ledger(platform);
        String key = platform.config.get("ledger.apiKey", "");
        routes.before("/ledger/*", ctx -> {
            // with a key configured the caller shows it; without one only this machine is answered
            if (key.isBlank() ? !java.net.InetAddress.getByName(ctx.ip()).isLoopbackAddress()
                    : !java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), String.valueOf(ctx.header("X-Ledger-Key")).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new ApiError(403, key.isBlank() ? "the ledger answers only requests from this machine unless ledger.apiKey is set" : "the ledger key is missing or wrong");
            }
        });
        routes.get("/ledger/accounts/{id}", ctx -> json(ctx, ledger.lookup(ctx.pathParam("id"))));
        routes.post("/ledger/postings", ctx -> json(ctx, ledgerCall(() -> ledger.post(Json.parse(ctx.body())))));
        routes.post("/ledger/postings/reverse", ctx -> json(ctx, ledgerCall(() -> ledger.reverse(Json.parse(ctx.body())))));

        // the day's journal as a file for the general ledger: one line per entry, every side named
        routes.get("/api/ledger/days/{date}/entries.csv", ctx -> {
            unscoped(need(ctx, "payments.view"));
            String date;
            try {
                date = java.time.LocalDate.parse(ctx.pathParam("date")).toString();
            } catch (java.time.format.DateTimeParseException e) {
                throw new ApiError(422, "the day is a date like 2026-10-07");
            }
            StringBuilder csv = new StringBuilder("id,bookedAt,bookDate,valueDate,debitAccount,creditAccount,amount,currency,creditAmount,creditCurrency,"
                    + "positionCreditAccount,positionDebitAccount,reference,text,reversalOf,feeOf\n");
            for (Rec e : ledger.journal(date)) {
                for (String field : List.of("id", "at", "bookDate", "valueDate", "debitAccount", "creditAccount", "amount", "currency", "creditAmount", "creditCurrency",
                        "positionCreditAccount", "positionDebitAccount", "reference", "text", "reversalOf", "feeOf")) {
                    Object v = e.get(field);
                    String s = v == null ? "" : v instanceof java.math.BigDecimal d ? d.toPlainString() : String.valueOf(v);
                    csv.append(s.matches("[A-Za-z0-9 .:_|/-]*") ? s : "\"" + s.replace("\"", "\"\"") + "\"").append(field.equals("feeOf") ? "\n" : ",");
                }
            }
            ctx.header("Content-Disposition", "attachment; filename=\"ledger-" + date + ".csv\"");
            ctx.header("Cache-Control", "no-store");
            ctx.contentType("text/csv").result(csv.toString());
        });
        routes.get("/api/ledger/accounts", ctx -> {
            unscoped(need(ctx, "payments.view"));
            Rec page = listPage(ctx, io.orvanta.pay.engine.Ledger.ACCOUNT, filter(ctx, "type", "status", "currency"), List.of("id", "name"),
                    List.of("id", "name", "type", "currency", "status"), "id", "openedAt");
            for (Object o : (List<?>) page.get("items")) {
                ((Rec) o).put("balance", ledger.balance(((Rec) o).str("id")));
            }
            list(ctx, page);
        });
        routes.get("/api/ledger/accounts/{id}", ctx -> {
            unscoped(need(ctx, "payments.view"));
            String id = ctx.pathParam("id");
            Rec account = found(ledger.account(id), id).copy();
            account.put("balance", ledger.balance(id));
            if (day(ctx, "valueDate") != null) {
                account.put("valueDate", day(ctx, "valueDate"));
                account.put("valueBalance", ledger.valueBalance(id, day(ctx, "valueDate")));
            }
            String from = day(ctx, "from") == null ? java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30).toString() : day(ctx, "from");
            List<Rec> entries = new ArrayList<>();
            java.math.BigDecimal running = ledger.balanceAtEndOf(id, java.time.LocalDate.parse(from).minusDays(1).toString());
            account.put("openingBalance", running);
            for (Rec e : ledger.entries(id, from, day(ctx, "to"))) {
                java.math.BigDecimal effect = io.orvanta.pay.engine.Ledger.effect(e, id);
                boolean credit = effect.signum() > 0;
                running = running.add(effect);
                entries.add(Rec.of("id", e.str("id"), "at", e.str("at"), "bookDate", e.str("bookDate"), "valueDate", e.str("valueDate"), "feeOf", e.str("feeOf"), "creditDebit", credit ? "CRDT" : "DBIT",
                        "amount", effect.abs(), "currency", account.str("currency"), "otherAccount", io.orvanta.pay.engine.Ledger.other(e, id),
                        "reference", e.str("reference"), "text", e.str("text"), "reversalOf", e.str("reversalOf"), "reversedBy", e.str("reversedBy"), "balance", running));
            }
            java.util.Collections.reverse(entries);
            account.put("from", from);
            account.put("entryCount", entries.size());
            account.put("entries", entries.size() > 500 ? new ArrayList<>(entries.subList(0, 500)) : entries);
            json(ctx, account);
        });
        routes.post("/api/ledger/accounts", ctx -> {
            Principal p = unscoped(need(ctx, "payments.repair"));
            Rec body = Json.parse(ctx.body());
            Rec existing = ledger.account(body.str("id"));
            Rec wanted = Rec.of("id", body.str("id"), "name", body.str("name"), "type", body.str("type"), "currency", body.str("currency"),
                    "status", body.str("status"), "overdraftLimit", body.get("overdraftLimit"));
            if (body.str("id") == null || !body.str("id").matches("[A-Za-z0-9]{5,34}")) {
                throw new ApiError(422, "an account number is 5 to 34 letters or digits");
            }
            if (existing == null && (!io.orvanta.pay.engine.Ledger.TYPES.contains(String.valueOf(body.str("type"))) || !String.valueOf(body.str("currency")).matches("[A-Z]{3}")
                    || body.str("name") == null || body.str("name").isBlank())) {
                throw new ApiError(422, "a new account needs a name, a type out of " + io.orvanta.pay.engine.Ledger.TYPES + " and a currency");
            }
            json(ctx, approvals.request("LEDGER_ACCOUNT", (existing == null ? "Open " + body.str("type") + " account " : "Change account ") + body.str("id")
                    + (body.str("name") == null ? "" : " (" + body.str("name") + ")") + (body.str("status") == null ? "" : ", " + body.str("status")),
                    wanted, p.username(), body.str("comment")));
        });
        routes.post("/api/ledger/postings", ctx -> {
            Principal p = unscoped(need(ctx, "payments.repair"));
            Rec body = Json.parse(ctx.body());
            for (String field : List.of("debitAccount", "creditAccount")) {
                if (ledger.account(body.str(field)) == null) {
                    throw new ApiError(422, "the ledger has no account " + body.str(field));
                }
            }
            if (body.get("amount") == null || Ops.num(body.get("amount")).signum() <= 0 || body.str("text") == null || body.str("text").isBlank()) {
                throw new ApiError(422, "a posting needs an amount above zero and a text that says what it is for");
            }
            json(ctx, approvals.request("LEDGER_POSTING", "Book " + body.get("amount") + " " + body.str("currency") + " from " + body.str("debitAccount") + " to "
                    + body.str("creditAccount") + ": " + body.str("text"), Rec.of("debitAccount", body.str("debitAccount"), "creditAccount", body.str("creditAccount"),
                    "amount", body.get("amount"), "currency", body.str("currency"), "text", body.str("text")), p.username(), body.str("comment")));
        });
        routes.post("/api/ledger/close-day", ctx -> {
            unscoped(need(ctx, "payments.repair"));
            Rec body = Json.parse(ctx.body());
            try {
                json(ctx, ledger.closeDay(java.time.LocalDate.parse(String.valueOf(body.str("date"))).toString()));
            } catch (java.time.format.DateTimeParseException | IllegalArgumentException e) {
                throw new ApiError(422, e instanceof IllegalArgumentException ? e.getMessage() : "date must be a day like 2026-10-06");
            }
        });
        approvals.register("LEDGER_ACCOUNT", "payments.approve", approval -> {
            Rec account = ledger.open(approval.rec("payload"), approval.str("maker") + " (approved by " + approval.str("checker") + ")");
            platform.changed("ledger");
            return Rec.of("account", account.str("id"), "status", account.str("status"));
        });
        approvals.register("LEDGER_POSTING", "payments.approve", approval -> {
            Rec payload = approval.rec("payload").copy();
            payload.put("idempotencyKey", "MANUAL-" + approval.str("id"));
            payload.put("reference", approval.str("id"));
            Rec answer = ledger.post(payload);
            if (!"POSTED".equals(answer.str("status"))) {
                throw new IllegalStateException("the posting was refused: " + answer.str("reason") + " (" + answer.str("account") + ")");
            }
            return answer;
        });
    }

    private static Rec ledgerCall(java.util.function.Supplier<Rec> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new ApiError(422, e.getMessage());
        }
    }

    /**
     * The period of an account report as two instants: from the start of the first day to the end of the last.
     * A user whose access is limited to certain accounts gets reports of those accounts only.
     */
    private static String[] reportPeriod(Principal p, String account, String from, String to) {
        if (account == null || !account.matches("[A-Za-z0-9]{5,34}")) {
            throw new ApiError(422, "account must be 5 to 34 letters or digits");
        }
        if (p.scope().restricted() && !p.scope().debtorAccounts().contains(account)) {
            throw new ApiError(403, "this account is outside the accounts your access is limited to");
        }
        java.time.LocalDate first;
        java.time.LocalDate last;
        try {
            first = java.time.LocalDate.parse(String.valueOf(from));
            last = java.time.LocalDate.parse(String.valueOf(to == null || to.isBlank() ? from : to));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiError(422, "from and to must be dates like 2026-10-06");
        }
        if (last.isBefore(first) || first.plusDays(31).isBefore(last)) {
            throw new ApiError(422, "a report covers one to 31 days");
        }
        return new String[] {first.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString(),
                last.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString()};
    }

    private void requestToPayAnswer(Context ctx, boolean accept) {
        // answering for the customer is like submitting a payment for them: the operator's job, with a second person approving
        Principal p = unscoped(need(ctx, "payments.submit"));
        String id = ctx.pathParam("id");
        Rec request = found(platform.store.get(io.orvanta.pay.engine.RequestToPayService.COLLECTION, id), id);
        if (!"PENDING".equals(request.str("status"))) {
            throw new ApiError(409, "this request is not waiting for an answer: it is " + request.str("status"));
        }
        Rec body = ctx.body().isBlank() ? new Rec() : Json.parse(ctx.body());
        String reasonCode = accept ? null : body.str("reasonCode") == null ? "CUST" : body.str("reasonCode");
        json(ctx, approvals.request("PAYMENT_ACTION", (accept ? "Accept" : "Refuse") + " request to pay " + id + " (" + request.get("amount") + " " + request.str("currency")
                + " to " + request.str("creditor.name") + ")", Rec.of("action", accept ? "RTP_ACCEPT" : "RTP_REFUSE", "requestId", id, "reasonCode", reasonCode,
                "reasonText", body.str("reasonText")), p.username(), body.str("comment")));
    }

    /** Pausing or resuming a channel's dispatch: a request a second person approves. */
    private void channelControl(Context ctx, boolean pause) {
        Principal p = unscoped(need(ctx, "payments.repair"));
        String name = ctx.pathParam("name");
        Rec channel = platform.deployments.registry().config(name);
        if (channel == null || !"Channel".equals(channel.str("kind")) || !"outbound".equals(channel.str("direction"))) {
            throw new ApiError(404, "'" + name + "' is not an outbound channel");
        }
        if (pause == dispatch.paused(name)) {
            throw new ApiError(409, "the channel is " + (pause ? "paused" : "sending") + " already");
        }
        String comment = ctx.body().isBlank() ? null : Json.parse(ctx.body()).str("comment");
        json(ctx, approvals.request("CHANNEL_CONTROL", (pause ? "Pause sending on " : "Resume sending on ") + name,
                Rec.of("channel", name, "paused", pause), p.username(), comment));
    }

    /**
     * One row per inbound channel (what arrived: messages, payments by outcome, amounts) and per outbound
     * channel (what left: files, payments, amounts) for a day, counted from the store with grouped counts.
     */
    private List<Rec> dailyReport(String day) {
        java.util.Map<String, Object[]> thatDay = java.util.Map.of("createdAt", new Object[] {day + "T00:00:00", day + "T23:59:59.999999999Z"});
        List<Rec> rows = new ArrayList<>();
        for (Rec channel : platform.deployments.registry().configs("Channel")) {
            String name = channel.str("name");
            Rec row = Rec.of("channel", name, "direction", channel.str("direction"), "date", day);
            if ("inbound".equals(channel.str("direction"))) {
                row.put("messages", platform.store.countMatching(DocStore.MESSAGE, new DocStore.Query(Rec.of("channel", name), null, List.of(), Map.of("receivedAt", thatDay.get("createdAt")), null, false, 0, 0)));
                row.put("messagesRejected", platform.store.countMatching(DocStore.MESSAGE, new DocStore.Query(Rec.of("channel", name, "status", Status.REJECTED), null, List.of(), Map.of("receivedAt", thatDay.get("createdAt")), null, false, 0, 0)));
                java.util.Map<String, Long> byStatus = platform.store.counts(DocStore.TXN, new DocStore.Query(Rec.of("channelIn", name), null, List.of(), thatDay, null, false, 0, 0), "status");
                long payments = 0;
                for (long n : byStatus.values()) {
                    payments += n;
                }
                row.put("payments", payments);
                row.put("paymentsByStatus", Rec.from(byStatus));
                row.put("amount", sumOf(DocStore.TXN, Rec.of("channelIn", name), thatDay));
            } else {
                row.put("files", platform.store.countMatching(DocStore.OUTBOUND, new DocStore.Query(Rec.of("channel", name), null, List.of(), thatDay, null, false, 0, 0)));
                row.put("filesSent", platform.store.countMatching(DocStore.OUTBOUND, new DocStore.Query(Rec.of("channel", name, "status", List.of(Status.SENT, Status.ACKNOWLEDGED)), null, List.of(), thatDay, null, false, 0, 0)));
                row.put("filesFailed", platform.store.countMatching(DocStore.OUTBOUND, new DocStore.Query(Rec.of("channel", name, "status", Status.FAILED), null, List.of(), thatDay, null, false, 0, 0)));
                java.util.Map<String, Long> byStatus = platform.store.counts(DocStore.TXN, new DocStore.Query(Rec.of("route.channel", name), null, List.of(), thatDay, null, false, 0, 0), "status");
                long payments = 0;
                for (long n : byStatus.values()) {
                    payments += n;
                }
                row.put("payments", payments);
                row.put("paymentsByStatus", Rec.from(byStatus));
                row.put("amount", sumOf(DocStore.TXN, Rec.of("route.channel", name), thatDay));
                // how long the day's payments took from arrival to the route (turnaround), on average and for 95 of 100
                List<Long> seconds = new ArrayList<>();
                for (Rec t : platform.store.page(DocStore.TXN, new DocStore.Query(Rec.of("route.channel", name), null, List.of(), thatDay, "createdAt", false, 0, 2000)).items()) {
                    if (t.str("routedAt") != null && t.str("createdAt") != null) {
                        seconds.add(java.time.Duration.between(java.time.Instant.parse(t.str("createdAt")), java.time.Instant.parse(t.str("routedAt"))).getSeconds());
                    }
                }
                if (!seconds.isEmpty()) {
                    java.util.Collections.sort(seconds);
                    long sum = 0;
                    for (long s : seconds) {
                        sum += s;
                    }
                    row.put("processed", seconds.size());
                    row.put("secondsToRouteAverage", sum / seconds.size());
                    row.put("secondsToRouteP95", seconds.get(Math.min(seconds.size() - 1, (int) Math.ceil(seconds.size() * 0.95) - 1)));
                }
            }
            if (Ops.num(row.get("payments")).longValue() > 0 || row.get("messages") != null && Ops.num(row.get("messages")).longValue() > 0
                    || row.get("files") != null && Ops.num(row.get("files")).longValue() > 0) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** The sum of the amounts of the payments a filter and a day select; read in pages, which is fine for a day's figures. */
    private java.math.BigDecimal sumOf(String collection, Rec filter, java.util.Map<String, Object[]> ranges) {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        int offset = 0;
        while (true) {
            DocStore.Page page = platform.store.page(collection, new DocStore.Query(filter, null, List.of(), ranges, "id", false, offset, 1000));
            for (Rec txn : page.items()) {
                if (txn.get("amount") != null) {
                    sum = sum.add(Ops.num(txn.get("amount")));
                }
            }
            offset += page.items().size();
            if (page.items().isEmpty() || offset >= page.total()) {
                return sum;
            }
        }
    }

    /** A transaction outside the user's data scope does not exist for that user. */
    private Rec visibleTxn(Principal p, String id) {
        Rec txn = found(platform.store.get(DocStore.TXN, id), id);
        if (!p.scope().allowsTransaction(txn)) {
            throw new ApiError(404, "no document " + id);
        }
        return txn;
    }

    private Rec visibleMessage(Principal p, String id) {
        Rec message = found(platform.store.get(DocStore.MESSAGE, id), id);
        if (!p.scope().allowsMessage(message)) {
            throw new ApiError(404, "no document " + id);
        }
        return message;
    }

    /** For what covers the payments of everybody: outbound files, statements, failed events, model APIs. */
    private static Principal unscoped(Principal p) {
        if (p.scope().restricted()) {
            throw new ApiError(403, "this is not available to a user whose access is limited to certain channels or accounts");
        }
        return p;
    }

    /** Whether the user may see the payment a payment action is about. */
    private boolean paymentInScope(Principal p, Rec approval) {
        if (!p.scope().restricted() || !"PAYMENT_ACTION".equals(approval.str("type"))) {
            return true;
        }
        Rec payload = approval.rec("payload");
        if (payload.str("transactionId") != null) {
            Rec txn = platform.store.get(DocStore.TXN, payload.str("transactionId"));
            return txn != null && p.scope().allowsTransaction(txn);
        }
        Rec message = payload.str("messageId") == null ? null : platform.store.get(DocStore.MESSAGE, payload.str("messageId"));
        return message != null && p.scope().allowsMessage(message);
    }

    private static Principal need(Context ctx, String permission) {
        Principal p = principal(ctx);
        if (!p.can(permission)) {
            throw new ApiError(403, "permission " + permission + " is required");
        }
        return p;
    }

    private static final io.orvanta.pay.security.Masking MASKING = io.orvanta.pay.security.Masking.defaults();

    /** Every JSON answer passes here: a user without the permission to read data in full gets it masked. */
    private static void json(Context ctx, Object value) {
        ctx.contentType("application/json").result(Json.write(masked(ctx, value)));
    }

    private static Object masked(Context ctx, Object value) {
        Principal p = ctx.attribute("principal");
        return p == null || p.can(io.orvanta.pay.security.Masking.PERMISSION) ? value : MASKING.mask(value);
    }

    private static void error(Context ctx, int status, String message) {
        ctx.status(status);
        json(ctx, Rec.of("error", message == null ? "request failed" : message));
    }

    private static String required(Rec body, String key) {
        String v = body.str(key);
        if (v == null || v.isBlank()) {
            throw new ApiError(400, "'" + key + "' is required");
        }
        return v;
    }

    /**
     * Answers a list page as JSON or, with format=csv, as a spreadsheet file of the same rows: every scalar
     * field of the rows as a column, nested fields with dotted names, lists and payloads left out. An
     * export is written to the security log, because it takes data out of the system.
     */
    private void list(Context ctx, Rec page) {
        if (!"csv".equals(ctx.queryParam("format"))) {
            json(ctx, page);
            return;
        }
        List<?> items = Ops.list(((Rec) masked(ctx, page)).get("items"));
        List<String> columns = new ArrayList<>();
        List<java.util.Map<String, String>> rows = new ArrayList<>();
        for (Object o : items) {
            java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
            flatten("", o, row);
            for (String column : row.keySet()) {
                if (!columns.contains(column)) {
                    columns.add(column);
                }
            }
            rows.add(row);
        }
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(String.join(",", columns.stream().map(ApiServer::csvCell).toList())).append("\r\n");
        for (java.util.Map<String, String> row : rows) {
            csv.append(String.join(",", columns.stream().map(c -> csvCell(row.get(c))).toList())).append("\r\n");
        }
        Principal p = ctx.attribute("principal");
        String name = Metrics.routeOf(ctx.path()).replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        security.record("EXPORT", p == null ? null : p.username(), address(ctx), name + ": " + rows.size() + " row(s)" + (ctx.queryString() == null ? "" : " for " + ctx.queryString()));
        ctx.header("Content-Disposition", "attachment; filename=\"orvanta-" + name + "-" + Platform.now().substring(0, 10) + ".csv\"");
        ctx.contentType("text/csv; charset=utf-8").result(csv.toString());
    }

    private static void flatten(String prefix, Object value, java.util.Map<String, String> row) {
        if (value instanceof java.util.Map<?, ?> m) {
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                // what is not a field of the row: the message text, events, the lists inside a document
                if (key.equals("payload") || key.equals("raw") || key.equals("events") || key.equals("passwordHash") || key.equals("mfa")) {
                    continue;
                }
                flatten(prefix.isEmpty() ? key : prefix + "." + key, e.getValue(), row);
            }
        } else if (value instanceof List<?> l) {
            // a list of plain values is joined; a list of records is not a column
            if (l.stream().noneMatch(x -> x instanceof java.util.Map || x instanceof List)) {
                row.put(prefix, String.join("; ", l.stream().map(x -> String.valueOf(Ops.str(x))).toList()));
            }
        } else if (value != null) {
            row.put(prefix, String.valueOf(Ops.str(value)));
        }
    }

    /** A CSV cell: quoted when it has to be, and a leading formula character neutralised so a spreadsheet does not run it. */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String s = value;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?[0-9][0-9.,]*")) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith("'") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static Rec found(Rec doc, String id) {
        if (doc == null) {
            throw new ApiError(404, "nothing found with id " + id);
        }
        return doc;
    }

    private static Rec filter(Context ctx, String... fields) {
        Rec filter = new Rec();
        for (String f : fields) {
            String v = ctx.queryParam(f);
            if (v != null && !v.isBlank()) {
                filter.put(f, v);
            }
        }
        return filter;
    }

    /**
     * One page of a list, worked out by the store. The request may give q (words that must all occur),
     * from and to (days), sort with dir, offset and limit. A filter value with commas means any of them.
     */
    private Rec listPage(Context ctx, String collection, Rec equals, List<String> searchFields, List<String> sorts, String defaultSort, String dateField) {
        for (java.util.Map.Entry<String, Object> e : new ArrayList<>(equals.entrySet())) {
            if (e.getValue() instanceof String text && text.contains(",")) {
                equals.put(e.getKey(), new ArrayList<Object>(List.of(text.split(","))));
            }
        }
        java.util.Map<String, Object[]> ranges = new java.util.LinkedHashMap<>();
        range(ranges, dateField, dayStart(ctx, "from"), dayEnd(ctx, "to"));
        String sort = ctx.queryParam("sort") == null ? defaultSort : ctx.queryParam("sort");
        if (!sorts.contains(sort)) {
            throw new ApiError(400, "sort must be one of " + sorts);
        }
        int offset = number(ctx, "offset", 0, 0, 10_000_000);
        DocStore.Page page = platform.store.page(collection, new DocStore.Query(equals, ctx.queryParam("q"), searchFields, ranges, sort,
                !"asc".equals(ctx.queryParam("dir")), offset, limit(ctx)));
        return Rec.of("items", page.items(), "total", page.total(), "offset", offset);
    }

    /** What a repair may change: what the customer or the operator could have got wrong, never the ids or what the engine decided. */
    private static final List<String> REPAIRABLE = List.of("creditor.name", "creditor.account", "creditor.agentBic", "creditor.country", "debtor.name",
            "remittance", "requestedDate", "amount", "currency", "purposeCode", "chargeBearer");

    private static final List<String> TXN_SORTS = List.of("id", "amount", "updatedAt", "createdAt", "endToEndId", "status");
    private static final List<String> TXN_SEARCH = List.of("id", "endToEndId", "creditor.name", "debtor.name", "creditor.account", "debtor.account", "remittance");

    private static void range(java.util.Map<String, Object[]> ranges, String field, Object from, Object to) {
        if (from != null || to != null) {
            ranges.put(field, new Object[] {from, to});
        }
    }

    /** A day given as yyyy-mm-dd, as the first instant of that day; times in the store are UTC. */
    private static String dayStart(Context ctx, String name) {
        String day = day(ctx, name);
        return day == null ? null : day + "T00:00:00";
    }

    private static String dayEnd(Context ctx, String name) {
        String day = day(ctx, name);
        return day == null ? null : day + "T23:59:59.999999999Z";
    }

    private static String day(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(v).toString();
        } catch (java.time.DateTimeException e) {
            throw new ApiError(400, name + " must be a date as yyyy-mm-dd");
        }
    }

    private static java.math.BigDecimal amount(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new ApiError(400, name + " must be a number");
        }
    }

    /** A path parameter that is a whole number, or the fallback. */
    private static int pathNumber(Context ctx, String name, int fallback) {
        try {
            return Integer.parseInt(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int number(Context ctx, String name, int fallback, int min, int max) {
        String v = ctx.queryParam(name);
        try {
            return v == null || v.isBlank() ? fallback : Math.max(min, Math.min(max, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            throw new ApiError(400, name + " must be a number");
        }
    }

    private static int limit(Context ctx) {
        String v = ctx.queryParam("limit");
        // an export takes a whole result, up to a size a spreadsheet still opens
        boolean export = "csv".equals(ctx.queryParam("format"));
        try {
            return v == null ? (export ? 10_000 : 200) : Math.max(1, Math.min(export ? 50_000 : 1000, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            throw new ApiError(400, "limit must be a number");
        }
    }

    private List<Rec> events(String refId) {
        return platform.store.find(DocStore.EVENT, Rec.of("refId", refId), "at", false, 500);
    }

    private static List<Rec> without(List<Rec> docs, String field) {
        for (Rec d : docs) {
            d.remove(field);
        }
        return docs;
    }
}
