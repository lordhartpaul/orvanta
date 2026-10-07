package io.orvanta.pay;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.HttpConnector;
import io.orvanta.core.flow.OAuth2Tokens;
import io.orvanta.core.json.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Connector that authenticates with OAuth2 client credentials, against a small token service and API. */
class OAuth2ConnectorTest {

    @Test
    void aTokenIsFetchedKeptAndRenewedWhenTheApiRefusesIt() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<String> tokenRequests = new ArrayList<>();
        List<String> apiAuth = new ArrayList<>();
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/oauth2/token", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenRequests.add(x.getRequestHeaders().getFirst("Authorization") + " | " + body);
            String expected = "Basic " + Base64.getEncoder().encodeToString("orvanta-client:the-client-secret-1".getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(x.getRequestHeaders().getFirst("Authorization")) || !body.contains("grant_type=client_credentials")) {
                answer(x, 401, "{\"error\":\"invalid_client\"}");
                return;
            }
            answer(x, 200, Json.write(Rec.of("access_token", "token-" + tokenCalls.incrementAndGet(), "token_type", "Bearer", "expires_in", 3600)));
        });
        service.createContext("/accounts", x -> {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            apiAuth.add(auth);
            // the first token is revoked after its first use, as a provider would after a key rotation
            if (auth == null || !auth.startsWith("Bearer token-") || ("Bearer token-1".equals(auth) && apiAuth.size() > 1)) {
                answer(x, 401, "{\"error\":\"invalid_token\"}");
                return;
            }
            answer(x, 200, "{\"status\":\"ACTIVE\"}");
        });
        service.start();
        try {
            String base = "http://127.0.0.1:" + service.getAddress().getPort();
            Rec auth = Rec.of("type", "oauth2", "tokenUrl", base + "/oauth2/token", "clientId", "orvanta-client", "clientSecret", "the-client-secret-1", "scope", "accounts.read");
            OAuth2Tokens.forget(auth);
            HttpConnector connector = new HttpConnector("connectors.Accounts", Rec.of("url", base + "/accounts/{account}", "method", "GET", "auth", auth));

            assertEquals("ACTIVE", connector.call(Rec.of("account", "4051122334")).str("status"));
            assertEquals(1, tokenCalls.get(), "one token for the first call");
            assertTrue(tokenRequests.get(0).contains("scope=accounts.read"), tokenRequests.get(0));
            assertEquals("Bearer token-1", apiAuth.get(0));

            // the API refuses the kept token: a new one is fetched and the call is made again, once
            assertEquals("ACTIVE", connector.call(Rec.of("account", "4051122334")).str("status"));
            assertEquals(2, tokenCalls.get());
            assertEquals(List.of("Bearer token-1", "Bearer token-1", "Bearer token-2"), apiAuth);
            // the new token is kept for the next call
            assertEquals("ACTIVE", connector.call(Rec.of("account", "4051122334")).str("status"));
            assertEquals(2, tokenCalls.get());

            // the token service refusing the client is reported as the system being unavailable, with the client named
            Rec wrong = Rec.of("type", "oauth2", "tokenUrl", base + "/oauth2/token", "clientId", "orvanta-client", "clientSecret", "not-the-secret");
            HttpConnector refused = new HttpConnector("connectors.Accounts", Rec.of("url", base + "/accounts/{account}", "method", "GET", "auth", wrong));
            Exception e = assertThrows(IOException.class, () -> refused.call(Rec.of("account", "4051122334")));
            assertTrue(e.getMessage().contains("HTTP 401") && e.getMessage().contains("orvanta-client"), e.getMessage());

            // the model compiler's check
            assertThrows(IllegalArgumentException.class, () -> OAuth2Tokens.check(Rec.of("type", "oauth2", "tokenUrl", base + "/oauth2/token", "clientId", "x")));
            assertThrows(IllegalArgumentException.class, () -> HttpConnector.headers(Rec.of("auth", Rec.of("type", "oauth2", "clientId", "x"))));
        } finally {
            service.stop(0);
        }
    }

    private static void answer(com.sun.net.httpserver.HttpExchange x, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }
}
