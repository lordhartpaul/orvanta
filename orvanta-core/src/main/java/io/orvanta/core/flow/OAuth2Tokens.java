package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access tokens for connectors and transports that authenticate with OAuth2 client credentials
 * (RFC 6749 section 4.4): the token is fetched from the token URL with the client id and secret,
 * kept until shortly before it expires, and shared by everything that uses the same client. Settings
 * on a connector or transport:
 *
 * <pre>
 * auth:
 *   type: oauth2
 *   tokenUrl: https://login.bank.example/oauth2/token
 *   clientId: orvanta
 *   clientSecret: ${env.BANK_API_SECRET:-}
 *   scope: accounts.read payments.write      # optional
 *   audience: https://api.bank.example       # optional, for providers that want one
 *   credentials: basic | body                # how the client authenticates at the token URL; basic is the default
 * </pre>
 */
public final class OAuth2Tokens {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** seconds before the expiry at which a token is fetched again, so a call never leaves with one about to expire */
    private static final long EARLY_SECONDS = 30;
    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    private static final Object FETCHING = new Object();

    private record Token(String value, long expiresAtEpochSeconds) {
        boolean fresh() {
            return System.currentTimeMillis() / 1000 < expiresAtEpochSeconds - EARLY_SECONDS;
        }
    }

    private OAuth2Tokens() {
    }

    /** The settings a model must give; for the model compiler. */
    public static void check(Rec auth) {
        for (String key : new String[] {"tokenUrl", "clientId", "clientSecret"}) {
            if (auth.str(key) == null || auth.str(key).isBlank()) {
                throw new IllegalArgumentException("auth of type oauth2 needs '" + key + "'");
            }
        }
        String credentials = auth.str("credentials");
        if (credentials != null && !credentials.equals("basic") && !credentials.equals("body")) {
            throw new IllegalArgumentException("auth.credentials is basic or body");
        }
    }

    /** A current access token for these settings, fetched when there is none or the one kept is about to expire. */
    public static String bearer(Rec auth) throws IOException, InterruptedException {
        String key = key(auth);
        Token token = TOKENS.get(key);
        if (token != null && token.fresh()) {
            return token.value();
        }
        synchronized (FETCHING) {
            token = TOKENS.get(key);
            if (token != null && token.fresh()) {
                return token.value();
            }
            token = fetch(auth);
            TOKENS.put(key, token);
            return token.value();
        }
    }

    /** Forgets the token kept for these settings: called when the other system refused it, so the next call fetches a new one. */
    public static void forget(Rec auth) {
        TOKENS.remove(key(auth));
    }

    private static Token fetch(Rec auth) throws IOException, InterruptedException {
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (auth.str("scope") != null) {
            form.append("&scope=").append(enc(auth.str("scope")));
        }
        if (auth.str("audience") != null) {
            form.append("&audience=").append(enc(auth.str("audience")));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(auth.str("tokenUrl"))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json");
        if ("body".equals(auth.str("credentials"))) {
            form.append("&client_id=").append(enc(auth.str("clientId"))).append("&client_secret=").append(enc(auth.str("clientSecret")));
        } else {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (enc(auth.str("clientId")) + ":" + enc(auth.str("clientSecret"))).getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<String> response = CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(form.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("the token URL " + auth.str("tokenUrl") + " answered HTTP " + response.statusCode() + " for client " + auth.str("clientId"));
        }
        Rec answer;
        try {
            answer = Json.parse(response.body());
        } catch (IllegalArgumentException e) {
            throw new IOException("the token URL " + auth.str("tokenUrl") + " did not answer with JSON");
        }
        String token = answer.str("access_token");
        if (token == null || token.isBlank()) {
            throw new IOException("the token URL " + auth.str("tokenUrl") + " answered without an access_token");
        }
        long expiresIn = answer.get("expires_in") == null ? 300 : Ops.num(answer.get("expires_in")).longValue();
        return new Token(token, System.currentTimeMillis() / 1000 + Math.max(expiresIn, EARLY_SECONDS + 1));
    }

    private static String key(Rec auth) {
        return auth.str("tokenUrl") + "|" + auth.str("clientId") + "|" + auth.str("scope") + "|" + auth.str("audience");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
