package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Stopping waits for the work in hand and tells the health check first, so nothing is lost and nothing new arrives. */
class GracefulStopTest {

    @Test
    void stoppingWaitsForWorkInHandAndSaysSoOnTheHealthCheck() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        MemoryBus bus = new MemoryBus();
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "api",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "a-stop-test-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml"),
                "workspace", Rec.of("dir", Path.of("..", "workspace").toAbsolutePath().normalize().toString()),
                "shutdown", Rec.of("graceSeconds", "10"))), new MemoryDocStore(), bus);
        server.start();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> healthy = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healthy.statusCode());

        // a handler that takes two seconds: the stop must not cut it short
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        bus.subscribe("orv.test.slow", "test", m -> {
            started.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
        });
        bus.publish("orv.test.slow", Rec.of("id", "slow-1"));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        long begun = System.currentTimeMillis();
        Thread stopping = new Thread(server::stop, "test-stop");
        stopping.start();
        // while it stops, the health check says so and answers 503, so nothing new is sent this way
        HttpResponse<String> during = null;
        for (int i = 0; i < 20 && (during == null || during.statusCode() != 503); i++) {
            Thread.sleep(50);
            try {
                during = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException alreadyClosed) {
                break;
            }
        }
        assertTrue(during != null && during.statusCode() == 503 && during.body().contains("STOPPING"), String.valueOf(during == null ? null : during.body()));
        stopping.join(15_000);
        assertTrue(finished.get(), "the handler was given the time to finish");
        assertTrue(System.currentTimeMillis() - begun >= 1500, "the stop waited for it");
        assertEquals(0, bus.inFlight());
    }
}
