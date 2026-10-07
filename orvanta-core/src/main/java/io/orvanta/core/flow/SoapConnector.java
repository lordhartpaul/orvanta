package io.orvanta.core.flow;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Connector;
import io.orvanta.core.format.IsoXml;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls one operation of a SOAP web service.
 *
 * <pre>
 * kind: Connector
 * type: soap
 * url: https://bank.example/services/AccountService
 * operation: GetAccountStatus                     # the element sent in the Body
 * namespace: http://bank.example/accounts         # the namespace of that element
 * action: http://bank.example/accounts/GetAccountStatus   # optional SOAPAction
 * version: "1.1"                                  # or "1.2"; default 1.1
 * timeoutMs: 5000
 * retries: 2                                      # on connection problems, timeouts and faults of the server
 * auth, headers                                   # as on an http connector
 * </pre>
 *
 * The fields of the request become child elements of the operation, in their order; a nested record
 * becomes nested elements. The answer is the first element of the response Body as a record, with
 * element names without namespace. A fault that blames the request (Client, Sender) is a refusal and
 * is not tried again; a fault of the server is an outage.
 */
public final class SoapConnector implements Connector {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";

    private final String name;
    private final String url;
    private final String operation;
    private final String namespace;
    private final String action;
    private final boolean soap12;
    private final Duration timeout;
    private final int retries;
    private final long retryDelayMs;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Rec oauth;
    private final CircuitBreaker breaker;
    private final HttpClient client;

    public SoapConnector(String name, Rec def) {
        this.name = name;
        this.url = def.str("url");
        this.operation = def.str("operation");
        this.namespace = def.str("namespace");
        this.action = def.str("action") == null ? "" : def.str("action");
        this.soap12 = "1.2".equals(Ops.str(def.get("version")));
        this.timeout = Duration.ofMillis(def.get("timeoutMs") == null ? 5000 : Ops.num(def.get("timeoutMs")).longValue());
        this.retries = def.get("retries") == null ? 0 : Ops.num(def.get("retries")).intValue();
        this.retryDelayMs = def.get("retryDelayMs") == null ? 200 : Ops.num(def.get("retryDelayMs")).longValue();
        headers.putAll(HttpConnector.headers(def, false));
        this.oauth = HttpConnector.oauthOf(def);
        this.breaker = CircuitBreaker.of(name, def);
        Rec tls = ClientTls.of(def);
        try {
            this.client = tls == null ? CLIENT : ClientTls.client(tls, Duration.ofSeconds(3));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("connector " + name + ": " + e.getMessage(), e);
        }
    }

    /** The envelope sent for a request; public so that a test can show a designer what goes over the wire. */
    public String envelope(Rec request) {
        Rec call = new Rec();
        call.put("@xmlns", namespace);
        for (Map.Entry<String, Object> e : request.entrySet()) {
            if (e.getValue() != null) {
                call.put(e.getKey(), e.getValue() instanceof Map<?, ?> || e.getValue() instanceof java.util.List<?> ? e.getValue() : Ops.str(e.getValue()));
            }
        }
        Rec body = new Rec();
        body.put(operation, call);
        Rec envelope = new Rec();
        envelope.put("@xmlns", soap12 ? SOAP12 : SOAP11);
        envelope.put("Body", body);
        Rec tree = new Rec();
        tree.put("Envelope", envelope);
        return IsoXml.write(tree);
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
            breaker.succeeded();
            throw refusal;
        } catch (Exception e) {
            breaker.failed();
            throw e;
        }
    }

    private Rec attempt(Rec request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        headers.forEach(builder::header);
        if (oauth != null) {
            builder.header("Authorization", "Bearer " + OAuth2Tokens.bearer(oauth));
        }
        if (soap12) {
            builder.header("Content-Type", "application/soap+xml; charset=utf-8" + (action.isEmpty() ? "" : "; action=\"" + action + "\""));
        } else {
            builder.header("Content-Type", "text/xml; charset=utf-8").header("SOAPAction", "\"" + action + "\"");
        }
        HttpRequest http = builder.POST(HttpRequest.BodyPublishers.ofString(envelope(request))).build();

        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                Thread.sleep(retryDelayMs << (attempt - 1));
            }
            try {
                HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
                Rec body = bodyOf(response.body());
                Object fault = body == null ? null : body.get("Fault");
                if (fault instanceof Rec f) {
                    String code = String.valueOf(soap12 ? f.at("Code.Value") : f.get("faultcode"));
                    String said = String.valueOf(soap12 ? text(f.at("Reason.Text")) : f.get("faultstring"));
                    String message = "connector " + name + " answered with a fault (" + code + "): " + said;
                    if (code.endsWith("Client") || code.endsWith("Sender")) {
                        // the service understood the request and refused it: asking again cannot help
                        throw new ConnectorRefusal(message, response.statusCode());
                    }
                    last = new IllegalStateException(message);
                    continue;
                }
                if (response.statusCode() / 100 != 2 || body == null) {
                    last = new IllegalStateException("connector " + name + " answered HTTP " + response.statusCode() + " without a SOAP body");
                    if (response.statusCode() / 100 == 4) {
                        throw new ConnectorRefusal(last.getMessage(), response.statusCode());
                    }
                    continue;
                }
                for (Map.Entry<String, Object> e : body.entrySet()) {
                    if (!e.getKey().startsWith("@")) {
                        return e.getValue() instanceof Rec r ? r : Rec.of("value", e.getValue());
                    }
                }
                return new Rec();
            } catch (IOException e) {
                last = new IOException("connector " + name + " is unreachable: " + e.getMessage(), e);
            }
        }
        throw last;
    }

    private static Object text(Object value) {
        return value instanceof Rec r ? r.get("#text") : value;
    }

    /** The Body of an envelope, or null when the answer is not a SOAP envelope. */
    private static Rec bodyOf(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            Object body = IsoXml.parse(xml).at("Envelope.Body");
            return body instanceof Rec r ? r : body == null ? null : new Rec();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
