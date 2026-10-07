package io.orvanta.pay;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.flow.ConnectorRefusal;
import io.orvanta.core.flow.HttpConnector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A connector with a circuit breaker: a system that keeps failing is left alone for a while, then tried once. */
class CircuitBreakerTest {

    @Test
    void aFailingSystemIsLeftAloneForAWhileAndTriedOnceWhenTheTimeIsUp() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<Integer> status = new AtomicReference<>(500);
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/rates", x -> {
            hits.incrementAndGet();
            byte[] body = (status.get() == 200 ? "{\"rate\":\"18.2\"}" : status.get() == 400 ? "{\"error\":\"no such currency\"}" : "{\"error\":\"down\"}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status.get(), body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        service.start();
        try {
            HttpConnector connector = new HttpConnector("connectors.FxRates", Rec.of("url", "http://127.0.0.1:" + service.getAddress().getPort() + "/rates",
                    "method", "GET", "retries", 0, "circuitBreaker", Rec.of("failures", 2, "openSeconds", 1)));
            assertThrows(Exception.class, () -> connector.call(Rec.of("from", "ZAR")));
            assertThrows(Exception.class, () -> connector.call(Rec.of("from", "ZAR")));
            assertEquals(2, hits.get());
            // the circuit is open: the system is not called, the failure is immediate and says so
            IOException open = assertThrows(IOException.class, () -> connector.call(Rec.of("from", "ZAR")));
            assertTrue(open.getMessage().contains("circuit is open after 2 failure(s)"), open.getMessage());
            assertEquals(2, hits.get(), "not called while open");
            // when the time is up, one trial call goes through; it fails, so the circuit opens again
            Thread.sleep(1100);
            assertThrows(IllegalStateException.class, () -> connector.call(Rec.of("from", "ZAR")));
            assertEquals(3, hits.get());
            assertTrue(assertThrows(IOException.class, () -> connector.call(Rec.of("from", "ZAR"))).getMessage().contains("circuit is open"));
            assertEquals(3, hits.get());
            // the system is back: the trial succeeds and the circuit closes
            status.set(200);
            Thread.sleep(1100);
            assertEquals("18.2", connector.call(Rec.of("from", "ZAR")).str("rate"));
            assertEquals("18.2", connector.call(Rec.of("from", "ZAR")).str("rate"));
            assertEquals(5, hits.get());
            // a refusal by the system (it is up and says no) does not count as a failure
            status.set(400);
            assertThrows(ConnectorRefusal.class, () -> connector.call(Rec.of("from", "XXX")));
            assertThrows(ConnectorRefusal.class, () -> connector.call(Rec.of("from", "XXX")));
            assertThrows(ConnectorRefusal.class, () -> connector.call(Rec.of("from", "XXX")));
            assertEquals(8, hits.get(), "still called: refusals do not open the circuit");
        } finally {
            service.stop(0);
        }
        assertThrows(IllegalArgumentException.class, () -> new HttpConnector("connectors.Bad", Rec.of("url", "http://x/", "circuitBreaker", Rec.of("failures", 0))));
    }
}
