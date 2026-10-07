package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Connector;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST client configured by a Connector model:
 *
 * <pre>
 * type: http
 * method: GET                                  # GET, POST (default), PUT, PATCH, DELETE
 * url: https://host/accounts/{account}         # {name} is filled from the request record
 * headers: {X-Channel: ORVANTA}
 * auth: {type: bearer, token: ${env.ACCOUNT_API_TOKEN}}     # or basic {username, password}, or header {name, value}
 * timeoutMs: 3000
 * retries: 2                                   # on connection problems, timeouts and HTTP 5xx; a 4xx answer is a refusal (ConnectorRefusal)
 * retryDelayMs: 200                            # doubled after each attempt
 * </pre>
 *
 * Request fields not used in the URL become the JSON body, or the query string for GET and DELETE.
 * The JSON reply is returned as a record.
 */
public final class HttpConnector implements Connector {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private final String name;
    private final String url;
    private final String method;
    private final Duration timeout;
    private final int retries;
    private final long retryDelayMs;
    private final Map<String, String> headers = new LinkedHashMap<>();
    /** OAuth2 client credentials, when the model authenticates that way: the token is fetched when a call is made, not here. */
    private final Rec oauth;
    /** stops calling a system that keeps failing, when the model asks for it */
    private final CircuitBreaker breaker;
    /** the client to call with: the shared one, or one with our certificate and trust when the model asks for TLS settings */
    private final HttpClient client;

    public HttpConnector(String name, Rec def) {
        this.name = name;
        this.url = def.str("url");
        this.method = def.str("method") == null ? "POST" : def.str("method").toUpperCase(java.util.Locale.ROOT);
        this.timeout = Duration.ofMillis(number(def, "timeoutMs", 5000));
        this.retries = (int) number(def, "retries", 0);
        this.retryDelayMs = number(def, "retryDelayMs", 200);
        headers.putAll(headers(def, false));
        this.oauth = oauthOf(def);
        this.breaker = CircuitBreaker.of(name, def);
        Rec tls = ClientTls.of(def);
        try {
            this.client = tls == null ? CLIENT : ClientTls.client(tls, Duration.ofSeconds(3));
        } catch (IOException e) {
            throw new IllegalArgumentException("connector " + name + ": " + e.getMessage(), e);
        }
    }

    /** The OAuth2 settings of a model's 'auth', checked, or null when it authenticates another way. */
    public static Rec oauthOf(Rec def) {
        if (def.get("auth") instanceof Map<?, ?> a && "oauth2".equals(Ops.str(a.get("type")))) {
            Rec auth = Rec.from(a);
            OAuth2Tokens.check(auth);
            return auth;
        }
        return null;
    }

    /** Request headers a model asks for: its 'headers' map plus what its 'auth' setting adds, an OAuth2 token fetched if need be. */
    public static Map<String, String> headers(Rec def) {
        return headers(def, true);
    }

    /**
     * @param resolveTokens whether an OAuth2 token is fetched now (false: the settings are checked, the token is left for the call)
     */
    public static Map<String, String> headers(Rec def, boolean resolveTokens) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (def.get("headers") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> headers.put(String.valueOf(k), Ops.str(v)));
        }
        if (def.get("auth") instanceof Map<?, ?> a) {
            Rec auth = Rec.from(a);
            switch (String.valueOf(auth.str("type"))) {
                case "basic" -> headers.put("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((auth.str("username") + ":" + auth.str("password")).getBytes(StandardCharsets.UTF_8)));
                case "bearer" -> headers.put("Authorization", "Bearer " + auth.str("token"));
                case "header" -> headers.put(auth.str("name"), auth.str("value"));
                case "oauth2" -> {
                    OAuth2Tokens.check(auth);
                    if (resolveTokens) {
                        try {
                            headers.put("Authorization", "Bearer " + OAuth2Tokens.bearer(auth));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted while fetching an OAuth2 token", e);
                        }
                    }
                }
                default -> throw new IllegalArgumentException("auth type must be basic, bearer, header or oauth2");
            }
        }
        return headers;
    }

    private static long number(Rec def, String key, long fallback) {
        return def.get(key) == null ? fallback : Ops.num(def.get(key)).longValue();
    }

    @Override
    public Rec call(Rec request) throws Exception {
        if (breaker == null) {
            return attempt(request);
        }
        breaker.beforeCall();
        try {
            Rec answer = attempt(request);
            breaker.succeeded();
            return answer;
        } catch (ConnectorRefusal refusal) {
            // the system is up and said no: not a failure of the system
            breaker.succeeded();
            throw refusal;
        } catch (Exception e) {
            breaker.failed();
            throw e;
        }
    }

    private Rec attempt(Rec request) throws Exception {
        Rec remaining = request.copy();
        Matcher m = PLACEHOLDER.matcher(url);
        StringBuilder target = new StringBuilder();
        while (m.find()) {
            String value = request.str(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("connector " + name + ": the request has no value for {" + m.group(1) + "}");
            }
            remaining.remove(m.group(1));
            m.appendReplacement(target, Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        m.appendTail(target);
        boolean bodyless = method.equals("GET") || method.equals("DELETE");
        if (bodyless) {
            char separator = target.indexOf("?") < 0 ? '?' : '&';
            for (Map.Entry<String, Object> e : remaining.entrySet()) {
                if (!(e.getValue() instanceof Map) && !(e.getValue() instanceof java.util.List)) {
                    target.append(separator).append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                            .append(URLEncoder.encode(String.valueOf(Ops.str(e.getValue())), StandardCharsets.UTF_8));
                    separator = '&';
                }
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.toString())).timeout(timeout)
                .header("Accept", "application/json");
        headers.forEach(builder::header);
        // lets the other system recognise a repeated request and answer it without acting twice
        if (request.str("idempotencyKey") != null) {
            builder.header("Idempotency-Key", request.str("idempotencyKey"));
        }
        if (bodyless) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(remaining)));
        }
        Exception last = null;
        boolean renewed = false;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                Thread.sleep(retryDelayMs << (attempt - 1));
            }
            try {
                // the OAuth2 token is added per attempt, so a renewed one is used after a refusal
                HttpRequest http = oauth == null ? builder.build() : builder.copy().header("Authorization", "Bearer " + OAuth2Tokens.bearer(oauth)).build();
                HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return reply(response.body());
                }
                if (status == 401 && oauth != null && !renewed) {
                    // the token was refused although it had not expired, as after a key rotation: once, a fresh one is tried
                    OAuth2Tokens.forget(oauth);
                    renewed = true;
                    attempt--;
                    continue;
                }
                if (status / 100 != 5) {
                    // the system understood the request and refused it: asking again cannot help, what it said is what a person needs
                    throw new ConnectorRefusal("connector " + name + " answered HTTP " + status + said(response.body()), status);
                }
                last = new IllegalStateException("connector " + name + " answered HTTP " + status);
            } catch (IOException e) {
                last = new IOException("connector " + name + " is unreachable: " + e.getMessage(), e);
            }
        }
        throw last;
    }

    /** What a refusing system said, shortened: the 'error' or 'message' of a JSON answer, or the start of the text. */
    private static String said(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        Rec answer = reply(body);
        Object text = answer.get("error") != null ? answer.get("error") : answer.get("message") != null ? answer.get("message") : body;
        String s = String.valueOf(text).replaceAll("\\s+", " ").trim();
        return ": " + (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }

    private static Rec reply(String body) {
        if (body == null || body.isBlank()) {
            return new Rec();
        }
        try {
            Object parsed = Json.parseAny(body);
            return parsed instanceof Rec r ? r : Rec.of("items", parsed);
        } catch (IllegalArgumentException e) {
            return Rec.of("body", body);
        }
    }
}
