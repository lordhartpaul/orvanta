package io.orvanta.pay;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.security.Crypto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single sign-on against a stand-in OpenID Connect provider: discovery, authorization, token endpoint and
 * published keys run in this test. The ID tokens are signed with an RSA key made here.
 */
class SsoTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static HttpServer idp;
    private static String issuer;
    private static KeyPair keyPair;
    private static OrvantaServer server;
    private static MemoryDocStore store;
    private static String base;
    /** code -> the claims the token for it carries; "tamper" codes make a token that must be refused */
    private static final Map<String, Rec> CODES = new ConcurrentHashMap<>();
    private static volatile String lastNonce;

    @BeforeAll
    static void start() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + idp.getAddress().getPort() + "/realms/bank";
        idp.createContext("/realms/bank/.well-known/openid-configuration", x -> answer(x, 200, Json.write(Rec.of("issuer", issuer,
                "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token", "jwks_uri", issuer + "/keys"))));
        idp.createContext("/realms/bank/keys", x -> {
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
            answer(x, 200, Json.write(Rec.of("keys", List.of(Rec.of("kty", "RSA", "kid", "bank-key-1", "alg", "RS256", "use", "sig",
                    "n", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(pub.getModulus().toByteArray())),
                    "e", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(pub.getPublicExponent().toByteArray())))))));
        });
        // the person "signs in" at once: the provider sends the browser back with a code for whoever the test says
        idp.createContext("/realms/bank/authorize", x -> {
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            lastNonce = q.get("nonce");
            String code = "code-" + Crypto.randomSecret().substring(0, 8);
            Rec who = Rec.from(Map.of("preferred_username", q.getOrDefault("login_hint", "sso.person"), "nonce", q.get("nonce")));
            CODES.put(code, who);
            x.getResponseHeaders().add("Location", q.get("redirect_uri") + "?code=" + code + "&state=" + q.get("state"));
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        idp.createContext("/realms/bank/token", x -> {
            String basic = x.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + Base64.getEncoder().encodeToString("orvanta-console:console-secret-for-the-test".getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(basic)) {
                answer(x, 401, "{\"error\":\"invalid_client\"}");
                return;
            }
            Map<String, String> form = query(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Rec who = CODES.remove(form.get("code"));
            if (who == null || !"authorization_code".equals(form.get("grant_type"))) {
                answer(x, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            answer(x, 200, Json.write(Rec.of("access_token", "opaque", "token_type", "Bearer", "id_token", idToken(who))));
        });
        idp.start();

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://localhost:" + port;
        Path data = Files.createTempDirectory("orvanta-sso");
        store = new MemoryDocStore();
        server = new OrvantaServer(Config.of(Rec.of(
                "units", "api",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "an-sso-test-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml"),
                "sso", Rec.of("enabled", "true", "issuer", issuer, "clientId", "orvanta-console", "clientSecret", "console-secret-for-the-test",
                        "redirectUri", base + "/api/auth/sso/callback", "label", "Sign in with the bank's account"),
                "workspace", Rec.of("dir", Path.of("..", "workspace").toAbsolutePath().normalize().toString()),
                "data", Rec.of("dir", data.toString()),
                "simulator", Rec.of("enabled", "false"))), store, new MemoryBus());
        server.start();
        store.save(DocStore.USER, Rec.of("id", "sso.person", "displayName", "Signed In Elsewhere", "roles", List.of("OPERATOR"), "status", "ACTIVE",
                "passwordHash", Crypto.hashPassword("a-password-that-is-not-used-here-1")));
        store.save(DocStore.USER, Rec.of("id", "sso.gone", "displayName", "Left The Bank", "roles", List.of("OPERATOR"), "status", "DISABLED",
                "passwordHash", Crypto.hashPassword("a-password-that-is-not-used-here-2")));
    }

    @AfterAll
    static void stop() {
        server.stop();
        idp.stop(0);
    }

    @Test
    void aPersonSignsInThroughTheProviderAndOnlyAKnownActiveUserGetsASession() throws Exception {
        // the sign-in page is told that single sign-on is offered, and how it is called
        Rec session = Json.parse(get(base + "/api/auth/session", null).body());
        assertEquals(true, session.get("sso"));
        assertEquals("Sign in with the bank's account", session.str("ssoLabel"));

        // start: to the provider, with a state and a nonce
        HttpResponse<String> start = get(base + "/api/auth/sso/start", null);
        assertEquals(302, start.statusCode());
        String toProvider = start.headers().firstValue("Location").orElseThrow();
        assertTrue(toProvider.startsWith(issuer + "/authorize?"), toProvider);
        Map<String, String> q = query(URI.create(toProvider).getRawQuery());
        assertEquals("code", q.get("response_type"));
        assertEquals("orvanta-console", q.get("client_id"));
        assertEquals(base + "/api/auth/sso/callback", q.get("redirect_uri"));
        assertNotNull(q.get("state"));
        assertNotNull(q.get("nonce"));

        // the provider sends the browser back; the callback turns the code into a session cookie and goes to the Console
        HttpResponse<String> back = get(toProvider, null);
        assertEquals(302, back.statusCode());
        String callback = back.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> done = get(callback, null);
        assertEquals(302, done.statusCode(), done.body());
        assertEquals("/", done.headers().firstValue("Location").orElseThrow());
        String cookie = done.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith("orv_session=") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
        Rec me = Json.parse(get(base + "/api/me", cookie.substring(0, cookie.indexOf(';'))).body());
        assertEquals("sso.person", me.str("username"));
        assertTrue(((List<?>) me.get("roles")).contains("OPERATOR"), "roles are Orvanta's");
        assertEquals("sso", store.get(DocStore.USER, "sso.person").str("lastLoginVia"));

        // the same code a second time is worthless, and so is the state
        HttpResponse<String> replay = get(callback, null);
        assertEquals(302, replay.statusCode());
        assertEquals("/?sso=failed", replay.headers().firstValue("Location").orElseThrow());
        assertTrue(replay.headers().firstValue("Set-Cookie").isEmpty());

        // a person the provider knows but Orvanta does not, or one whose user is disabled, gets no session
        for (String who : List.of("nobody.here", "sso.gone")) {
            String ask = toProvider + "&login_hint=" + who;
            HttpResponse<String> refused = get(get(ask, null).headers().firstValue("Location").orElseThrow(), null);
            assertEquals("/?sso=unknown", refused.headers().firstValue("Location").orElseThrow(), who);
            assertTrue(refused.headers().firstValue("Set-Cookie").isEmpty(), who);
        }
        // every outcome is in the security log
        List<Rec> events = store.find(DocStore.SECURITY, null, "at", true, 50);
        assertTrue(events.stream().anyMatch(e -> "LOGIN_OK".equals(e.str("type")) && "sso.person".equals(e.str("username")) && String.valueOf(e.str("detail")).contains("single sign-on")), events.toString());
        assertTrue(events.stream().anyMatch(e -> "LOGIN_FAILED".equals(e.str("type")) && "nobody.here".equals(e.str("username"))), events.toString());
        assertTrue(events.stream().anyMatch(e -> "LOGIN_FAILED".equals(e.str("type")) && "sso.gone".equals(e.str("username"))), events.toString());
    }

    @Test
    void aTokenThatDoesNotHoldUpIsRefused() throws Exception {
        HttpResponse<String> start = get(base + "/api/auth/sso/start", null);
        String toProvider = start.headers().firstValue("Location").orElseThrow();
        String state = query(URI.create(toProvider).getRawQuery()).get("state");

        // an answer whose state this process did not sign is not even looked at
        HttpResponse<String> forged = get(base + "/api/auth/sso/callback?code=whatever&state=" + state + "x", null);
        assertEquals(400, forged.statusCode());
        // the provider refusing the person
        HttpResponse<String> denied = get(base + "/api/auth/sso/callback?error=access_denied&state=" + state, null);
        assertEquals("/?sso=refused", denied.headers().firstValue("Location").orElseThrow());

        // a token for another sign-in (wrong nonce), for another client, from another issuer, expired, or signed with another key
        for (Rec bad : List.of(
                Rec.of("preferred_username", "sso.person", "nonce", "another-nonce"),
                Rec.of("preferred_username", "sso.person", "aud", "another-client"),
                Rec.of("preferred_username", "sso.person", "iss", "https://somewhere.else/realms/x"),
                Rec.of("preferred_username", "sso.person", "exp", 1000L),
                Rec.of("preferred_username", "sso.person", "otherKey", true),
                Rec.of("preferred_username", "sso.person", "alg", "none"))) {
            HttpResponse<String> fresh = get(base + "/api/auth/sso/start", null);
            String at = fresh.headers().firstValue("Location").orElseThrow();
            HttpResponse<String> back = get(at, null);
            String callback = back.headers().firstValue("Location").orElseThrow();
            String code = query(URI.create(callback).getRawQuery()).get("code");
            Rec claims = CODES.get(code);
            if (!bad.containsKey("nonce")) {
                bad.put("nonce", claims.str("nonce"));
            }
            CODES.put(code, bad);
            HttpResponse<String> refused = get(callback, null);
            assertEquals(302, refused.statusCode(), bad.toString());
            assertEquals("/?sso=failed", refused.headers().firstValue("Location").orElseThrow(), bad.toString());
            assertTrue(refused.headers().firstValue("Set-Cookie").isEmpty(), bad.toString());
        }
        List<Rec> events = store.find(DocStore.SECURITY, Rec.of("type", "LOGIN_FAILED"), "at", true, 50);
        assertTrue(events.stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("does not belong to this sign-in")), events.toString());
        assertTrue(events.stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("signature is wrong")), events.toString());
        assertTrue(events.stream().anyMatch(e -> String.valueOf(e.str("detail")).contains("only RS256")), events.toString());
        assertFalse(events.isEmpty());
    }

    // ---- the stand-in provider ----

    private static String idToken(Rec who) {
        try {
            boolean otherKey = Boolean.TRUE.equals(who.remove("otherKey"));
            String alg = who.str("alg") == null ? "RS256" : who.str("alg");
            who.remove("alg");
            Rec claims = Rec.of("iss", who.str("iss") == null ? issuer : who.str("iss"), "sub", "person-42",
                    "aud", who.get("aud") == null ? "orvanta-console" : who.get("aud"),
                    "exp", who.get("exp") == null ? System.currentTimeMillis() / 1000 + 300 : who.get("exp"),
                    "iat", System.currentTimeMillis() / 1000, "nonce", who.str("nonce"), "preferred_username", who.str("preferred_username"));
            String head = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"alg\":\"" + alg + "\",\"kid\":\"bank-key-1\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
            KeyPair signer = otherKey ? KeyPairGenerator.getInstance("RSA").generateKeyPair() : keyPair;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
            return head + "." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(com.sun.net.httpserver.HttpExchange x, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static HttpResponse<String> get(String url, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
