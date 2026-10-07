package io.orvanta.pay;

import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.kernel.Platform;
import io.orvanta.pay.security.Crypto;
import io.orvanta.pay.security.Policies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The security behaviour of the API as a caller sees it over HTTP and HTTPS. */
class SecurityHttpTest {

    private static final String WORKSPACE = Path.of("..", "workspace").toAbsolutePath().normalize().toString();
    private static final String ADMIN_PASSWORD = "first-Start-Passphrase-26";

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static OrvantaServer start(DocStore store, int port, Rec extraServer, Rec extraSecurity) throws Exception {
        Rec server = Rec.of("port", String.valueOf(port));
        server.putAll(extraServer);
        Rec security = Rec.of("jwtSecret", "a-test-secret-that-is-long-enough", "bootstrapPassword", ADMIN_PASSWORD,
                "seedFile", "no-such-directory/seed-users.yaml");
        security.putAll(extraSecurity);
        OrvantaServer s = new OrvantaServer(Config.of(Rec.of("units", "api", "server", server, "security", security,
                "workspace", Rec.of("dir", WORKSPACE))), store, new MemoryBus());
        s.start();
        return s;
    }

    private static HttpResponse<String> call(HttpClient client, String method, String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String login(String user, String password) {
        return Json.write(Rec.of("username", user, "password", password));
    }

    @Test
    void headersSessionsThrottlingAndTheSecurityLog() throws Exception {
        DocStore store = new MemoryDocStore();
        int port = freePort();
        OrvantaServer server = start(store, port, new Rec(), Rec.of("loginAttempts", "6", "maxFailedLogins", "3", "lockMinutes", "1"));
        String base = "http://localhost:" + port;
        HttpClient http = HttpClient.newHttpClient();
        try {
            // every response carries the browser protections, and none names the server software
            HttpResponse<String> page = call(http, "GET", base + "/", null, null);
            assertEquals(200, page.statusCode());
            String csp = page.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'") && !csp.contains("script-src 'self' 'unsafe-inline'"), csp);
            assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
            assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));
            assertFalse(page.headers().firstValue("Server").orElse("").matches(".*\\d.*"), "no server version in the Server header");
            assertTrue(page.headers().firstValue("Strict-Transport-Security").isEmpty(), "no HSTS over plain HTTP");
            assertFalse(page.body().contains("<script>"), "the console page has no inline script");

            assertEquals(401, call(http, "GET", base + "/api/transactions", null, null).statusCode());
            assertEquals(401, call(http, "GET", base + "/api/transactions", "not.a.token", null).statusCode());
            assertEquals(404, call(http, "POST", base + "/sim/sanctions/screen", null, "{}").statusCode(), "the simulator is off unless enabled");

            // a session ends at sign-out: the token that worked a moment ago is refused
            HttpResponse<String> ok = call(http, "POST", base + "/api/auth/login", null, login("admin", ADMIN_PASSWORD));
            assertEquals(200, ok.statusCode());
            String token = Json.parse(ok.body()).str("token");
            assertEquals(200, call(http, "GET", base + "/api/me", token, null).statusCode());
            assertEquals(200, call(http, "POST", base + "/api/auth/logout", token, null).statusCode());
            assertEquals(401, call(http, "GET", base + "/api/me", token, null).statusCode());
            String admin = Json.parse(call(http, "POST", base + "/api/auth/login", null, login("admin", ADMIN_PASSWORD)).body()).str("token");

            // a user without the permission is refused, and that is recorded
            store.save(DocStore.USER, Rec.of("id", "viewer", "roles", List.of("OPERATOR"), "status", "ACTIVE",
                    "passwordHash", Crypto.hashPassword("viewer-Password-2026")));
            String viewer = Json.parse(call(http, "POST", base + "/api/auth/login", null, login("viewer", "viewer-Password-2026")).body()).str("token");
            assertEquals(403, call(http, "GET", base + "/api/users", viewer, null).statusCode());
            assertEquals(403, call(http, "GET", base + "/api/security/events", viewer, null).statusCode());

            // the same answer for an unknown user as for a wrong password
            HttpResponse<String> unknown = call(http, "POST", base + "/api/auth/login", null, login("nobody-here", "whatever-it-is-12"));
            HttpResponse<String> wrong = call(http, "POST", base + "/api/auth/login", null, login("viewer", "whatever-it-is-12"));
            assertEquals(401, unknown.statusCode());
            assertEquals(wrong.body(), unknown.body());

            // guessing: three wrong passwords lock the user; after six failures from one address nothing is checked any more,
            // not even the right password
            store.save(DocStore.USER, Rec.of("id", "victim", "roles", List.of("OPERATOR"), "status", "ACTIVE",
                    "passwordHash", Crypto.hashPassword("victim-Password-2026")));
            for (int i = 0; i < 4; i++) {
                assertEquals(401, call(http, "POST", base + "/api/auth/login", null, login("victim", "guess-number-" + i)).statusCode());
            }
            assertEquals("LOCKED", store.get(DocStore.USER, "victim").str("status"));
            assertNotNull(store.get(DocStore.USER, "victim").str("lockedUntil"));
            HttpResponse<String> throttled = call(http, "POST", base + "/api/auth/login", null, login("victim", "victim-Password-2026"));
            assertEquals(429, throttled.statusCode());
            assertTrue(throttled.headers().firstValue("Retry-After").isPresent());
            // a session opened before the throttle still works
            String events = call(http, "GET", base + "/api/security/events?limit=100", admin, null).body();
            for (String type : List.of("LOGIN_OK", "LOGIN_FAILED", "LOGOUT", "DENIED", "USER_LOCKED", "LOGIN_THROTTLED")) {
                assertTrue(events.contains("\"" + type + "\""), type + " missing in " + events);
            }
            assertFalse(events.contains("guess-number") || events.contains(ADMIN_PASSWORD), "no password ever reaches the security log");

            // a model may not make the server call a metadata address or a local file
            String connector = "kind: Connector\nname: connectors.Evil\ntype: http\nurl: %s\n";
            for (String url : List.of("http://169.254.169.254/latest/meta-data/", "file:///etc/passwd", "http://metadata.google.internal/x")) {
                Rec result = Json.parse(call(http, "POST", base + "/api/studio/validate", admin, Json.write(Rec.of("text", String.format(connector, url)))).body());
                assertEquals(false, result.get("ok"), url);
            }
            // a weak password is refused when a user is requested
            assertEquals(400, call(http, "POST", base + "/api/users", admin,
                    Json.write(Rec.of("username", "newuser", "roles", List.of("OPERATOR"), "password", "newuser-is-here"))).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void theServerSpeaksHttpsFromAKeystoreAndOnlyHttps(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair",
                "-alias", "orvanta", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "san=dns:localhost", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-keystore-pass")
                .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertEquals(0, keytool.waitFor());

        int port = freePort();
        OrvantaServer server = start(new MemoryDocStore(), port,
                Rec.of("tls", Rec.of("keystore", keystore.toString(), "password", "test-keystore-pass")), new Rec());
        try {
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                trusted.load(in, "test-keystore-pass".toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            HttpClient https = HttpClient.newBuilder().sslContext(context).build();

            HttpResponse<String> health = call(https, "GET", "https://localhost:" + port + "/api/health", null, null);
            assertEquals(200, health.statusCode());
            assertTrue(health.headers().firstValue("Strict-Transport-Security").orElse("").startsWith("max-age="));
            // the same port does not answer plain HTTP
            assertThrows(Exception.class, () -> {
                HttpResponse<String> plain = call(HttpClient.newHttpClient(), "GET", "http://localhost:" + port + "/api/health", null, null);
                if (plain.statusCode() == 200) {
                    return;
                }
                throw new IllegalStateException("refused with " + plain.statusCode());
            });
        } finally {
            server.stop();
        }
    }

    @Test
    void whereTheSecondStepIsRequiredAUserWithoutItCanOnlySetItUp() throws Exception {
        DocStore store = new MemoryDocStore();
        int port = freePort();
        OrvantaServer server = start(store, port, new Rec(), Rec.of("requireMfa", "true"));
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + port;
            String token = Json.parse(call(client, "POST", base + "/api/auth/login", null, login("admin", ADMIN_PASSWORD)).body()).str("token");
            // signed in, but with no permission: an administrator who cannot administer until the second step is on
            Rec me = Json.parse(call(client, "GET", base + "/api/me", token, null).body());
            assertEquals(true, me.at("mfa.required"));
            assertEquals(List.of(), me.get("permissions"));
            assertEquals(403, call(client, "GET", base + "/api/users", token, null).statusCode());
            assertEquals(403, call(client, "GET", base + "/api/transactions", token, null).statusCode());

            String secret = Json.parse(call(client, "POST", base + "/api/me/mfa/enroll", token, null).body()).str("secret");
            long step = System.currentTimeMillis() / 1000 / 30;
            assertEquals(200, call(client, "POST", base + "/api/me/mfa/confirm", token, Json.write(Rec.of("code", Crypto.totp(secret, step)))).statusCode());
            // with it on, the same session has its permissions, and the step cannot be turned off again
            assertEquals(200, call(client, "GET", base + "/api/users", token, null).statusCode());
            assertEquals(409, call(client, "POST", base + "/api/me/mfa/disable", token, Json.write(Rec.of("code", Crypto.totp(secret, step + 1)))).statusCode());
            // and the next sign-in takes password and code
            assertEquals(401, call(client, "POST", base + "/api/auth/login", null, login("admin", ADMIN_PASSWORD)).statusCode());
            assertEquals(200, call(client, "POST", base + "/api/auth/login", null,
                    Json.write(Rec.of("username", "admin", "password", ADMIN_PASSWORD, "code", Crypto.totp(secret, step + 1)))).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void passwordAndUrlPolicies() {
        assertNull(Policies.passwordProblem("maria", "correct horse battery staple"));
        assertNotNull(Policies.passwordProblem("maria", "short"));
        assertNotNull(Policies.passwordProblem("maria", "xx-maria-2026-xx"));
        assertNotNull(Policies.passwordProblem("maria", "aaaaaaaaaaaaaaaa"));
        assertNotNull(Policies.passwordProblem("maria", "Password1234"));

        assertNull(Policies.urlProblem("https://api.bank.example/accounts/{account}", List.of()));
        assertNotNull(Policies.urlProblem("ftp://files.example/x", List.of()));
        assertNotNull(Policies.urlProblem("http://169.254.169.254/latest", List.of()));
        assertNotNull(Policies.urlProblem("http://[fe80::1]/x", List.of()));
        assertNull(Policies.urlProblem("https://screening.partner.example/check", List.of("*.partner.example", "localhost")));
        assertNotNull(Policies.urlProblem("https://evil.example/check", List.of("*.partner.example", "localhost")));
        assertNotNull(Policies.urlProblem("https://partner.example.evil.example/check", List.of("*.partner.example")));
    }

    @Test
    void aDeploymentChangedInTheDatabaseIsNotRun() throws Exception {
        DocStore store = new MemoryDocStore();
        Rec security = Rec.of("integrityKey", "a-key-only-the-servers-know");
        Platform first = new Platform(Config.of(Rec.of("security", security, "workspace", Rec.of("dir", WORKSPACE))), store, new MemoryBus());
        first.deployments.start(Path.of(WORKSPACE), false);
        String id = first.deployments.activeId();
        Rec deployment = store.get(DocStore.DEPLOYMENT, id);
        assertNotNull(deployment.str("seal"));

        // someone edits a rule straight in the database, bypassing approval
        for (Object m : (List<?>) deployment.get("models")) {
            Rec model = (Rec) m;
            if ("payments.rules.TransactionValidation".equals(model.str("name"))) {
                model.put("text", model.str("text").replace("txn.amount <= 999999999.99", "txn.amount <= 999999999999.99"));
            }
        }
        store.save(DocStore.DEPLOYMENT, deployment);
        Platform second = new Platform(Config.of(Rec.of("security", security, "workspace", Rec.of("dir", WORKSPACE))), store, new MemoryBus());
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> second.deployments.start(Path.of(WORKSPACE), false));
        assertTrue(refused.getMessage().contains("integrity"), refused.getMessage());
    }
}
