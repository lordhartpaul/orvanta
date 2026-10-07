package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An inbound channel that fetches its messages from a remote endpoint (transport type http). */
class PullTransportTest {

    @TempDir
    Path temp;

    @Test
    void messagesAreFetchedFromAnEndpointStoredAndThenAcknowledged() throws Exception {
        Path source = Path.of("..", "workspace").toAbsolutePath().normalize();
        String sample = Files.readString(source.resolve("tests/messages/pain001-salaries.xml"));
        // the partner's outbox: hands out the oldest message until it is acknowledged; the first request fails
        ConcurrentLinkedDeque<String[]> outbox = new ConcurrentLinkedDeque<>();
        outbox.add(new String[] {"PULL/1", sample.replace("SALARY-2026-10-001", "PULLED-1")});
        outbox.add(new String[] {"PULL/2", sample.replace("SALARY-2026-10-001", "PULLED-2")});
        AtomicInteger asked = new AtomicInteger();
        List<String> acknowledged = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        HttpServer partner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partner.createContext("/outbox/next", exchange -> {
            tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String[] next = outbox.peekFirst();
            if (asked.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else if (next == null) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] body = next[1].getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-Message-Id", next[0]);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        partner.createContext("/outbox/done/", exchange -> {
            String id = java.net.URLDecoder.decode(exchange.getRequestURI().getRawPath().substring("/outbox/done/".length()), StandardCharsets.UTF_8);
            String[] next = outbox.peekFirst();
            if ("DELETE".equals(exchange.getRequestMethod()) && next != null && next[0].equals(id)) {
                outbox.pollFirst();
                synchronized (acknowledged) {
                    acknowledged.add(id);
                }
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(409, -1);
            }
            exchange.close();
        });
        partner.start();
        String at = "http://127.0.0.1:" + partner.getAddress().getPort();

        Path workspace = temp.resolve("workspace");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = workspace.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        Path channel = workspace.resolve("channels/CorporateIsoInbound.yaml");
        String text = Files.readString(channel);
        assertTrue(text.contains("transport:\n"), "the channel model changed; adjust this test");
        Files.writeString(channel, text.replace("transport:\n", "transport:\n  - type: http\n    url: " + at + "/outbox/next\n    intervalSeconds: 1\n"
                + "    auth: {type: bearer, token: partner-token-for-this-test}\n    acknowledge:\n      method: DELETE\n      url: " + at + "/outbox/done/{id}\n"));

        DocStore store = new MemoryDocStore();
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "ingest",
                "workspace", Rec.of("dir", workspace.toString()),
                "data", Rec.of("dir", temp.resolve("data").toString()))), store, new MemoryBus());
        try {
            server.start();
            long deadline = System.currentTimeMillis() + 30_000;
            while (store.count(DocStore.MESSAGE, new Rec()) < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            List<Rec> messages = store.find(DocStore.MESSAGE, new Rec(), "id", false, 10);
            assertEquals(2, messages.size(), "both messages, although the endpoint failed at first");
            for (Rec m : messages) {
                assertEquals("channels.CorporateIsoInbound", m.str("channel"));
                assertEquals("http:127.0.0.1", m.str("receivedBy"));
                assertTrue(m.str("fileName").startsWith("PULL_"), m.str("fileName"));
            }
            // each was acknowledged after it was stored, with the id the endpoint gave, and nothing is fetched twice
            Thread.sleep(2500);
            assertEquals(List.of("PULL/1", "PULL/2"), acknowledged);
            assertEquals(2, store.count(DocStore.MESSAGE, new Rec()));
            assertTrue(tokens.stream().allMatch("Bearer partner-token-for-this-test"::equals), "every request carries the configured authentication");
        } finally {
            server.stop();
            partner.stop(0);
        }
    }
}
