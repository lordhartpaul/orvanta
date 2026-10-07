package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import io.orvanta.pay.engine.Alerts;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Incidents: a condition that needs a person opens one, told once to the webhook; when the cause is gone it is closed, told once more. */
class AlertsTest {

    @Test
    void anIncidentIsOpenedOnceToldOnceAndClosedWhenTheCauseIsGone() throws Exception {
        List<Rec> told = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/alerts", exchange -> {
            synchronized (told) {
                told.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        hook.start();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        DocStore store = new MemoryDocStore();
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "api",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "an-alerts-test-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml"),
                "workspace", Rec.of("dir", Path.of("..", "workspace").toAbsolutePath().normalize().toString()),
                "alerts", Rec.of("webhook", "http://127.0.0.1:" + hook.getAddress().getPort() + "/alerts", "token", "alert-test-token", "intervalSeconds", "3600"))),
                store, new MemoryBus());
        try {
            server.start();
            Alerts alerts = server.alerts;
            assertEquals(0, alerts.check(), "nothing is wrong on an empty platform");
            assertEquals(List.of(), alerts.open());

            // an internal event fails for good: an incident, told once, counted while it lasts
            store.insert(DocStore.DEAD_LETTER, Rec.of("id", "dl-1", "topic", "orv.test", "status", "OPEN", "error", "boom", "at", io.orvanta.pay.kernel.Platform.now()));
            assertEquals(1, alerts.check());
            assertEquals(0, alerts.check(), "the same incident is not opened twice");
            store.insert(DocStore.DEAD_LETTER, Rec.of("id", "dl-2", "topic", "orv.test", "status", "OPEN", "error", "boom", "at", io.orvanta.pay.kernel.Platform.now()));
            assertEquals(0, alerts.check(), "a second failed event changes the count, not the incident");
            Rec open = alerts.open().get(0);
            assertEquals("deadletters", open.str("id"));
            assertEquals(2L, ((Number) open.get("count")).longValue());
            assertEquals(1, told.size(), told.toString());
            assertEquals("opened", told.get(0).str("event"));
            assertEquals("deadletters", told.get(0).str("incident"));
            assertEquals("Bearer alert-test-token", tokens.get(0));
            assertTrue(!Json.write(told.get(0)).contains("boom"), "the webhook gets the kind and the count, not the error text of an event");

            // requeued: the cause is gone, the incident is closed and told once more
            store.updateIf(DocStore.DEAD_LETTER, "dl-1", new Rec(), Rec.of("status", "REQUEUED"));
            store.updateIf(DocStore.DEAD_LETTER, "dl-2", new Rec(), Rec.of("status", "REQUEUED"));
            assertEquals(1, alerts.check());
            assertEquals(List.of(), alerts.open());
            assertEquals(2, told.size());
            assertEquals("closed", told.get(1).str("event"));
            assertEquals("CLOSED", store.get(Alerts.INCIDENT, "deadletters").str("status"));

            // a request that waits too long for a second person is one as well; the limit is a setting
            store.insert(DocStore.APPROVAL, Rec.of("id", "ORVAPR-OLD", "type", "USER_CHANGE", "status", "PENDING", "maker", "x",
                    "requestedAt", java.time.Instant.now().minusSeconds(2 * 3600).toString()));
            store.insert(DocStore.APPROVAL, Rec.of("id", "ORVAPR-NEW", "type", "USER_CHANGE", "status", "PENDING", "maker", "x", "requestedAt", io.orvanta.pay.kernel.Platform.now()));
            assertEquals(1, alerts.check());
            Rec waiting = alerts.open().get(0);
            assertEquals("approvalsWaiting", waiting.str("id"));
            assertEquals(1L, ((Number) waiting.get("count")).longValue(), "only the one older than an hour");
        } finally {
            server.stop();
            hook.stop(0);
        }
    }
}
