package io.orvanta.pay;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.engine.Status;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every file under workspace/tests/messages/negative is refused with the reason expected.yml names. */
class NegativeSamplesTest {

    private static final Path WORKSPACE = Path.of("..", "workspace").toAbsolutePath().normalize();
    private static final Path NEGATIVE = WORKSPACE.resolve("tests/messages/negative");
    private static OrvantaServer server;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new OrvantaServer(Config.of(Rec.of(
                "units", "ingest",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "a-negative-samples-secret-that-is-long", "seedFile", "no-such-directory/seed.yaml"),
                "workspace", Rec.of("dir", WORKSPACE.toString()),
                "data", Rec.of("dir", Files.createTempDirectory("orvanta-negative").toString()),
                "simulator", Rec.of("enabled", "false"))), new MemoryDocStore(), new MemoryBus());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void everyNegativeSampleIsRefusedWithTheExpectedReason() throws Exception {
        Object loaded = new Yaml().load(Files.readString(NEGATIVE.resolve("expected.yml")));
        List<?> expectations = (List<?>) loaded;
        assertFalse(expectations.isEmpty());
        List<String> files = Files.list(NEGATIVE).map(p -> p.getFileName().toString()).filter(n -> !n.equals("expected.yml")).sorted().toList();
        assertEquals(files, expectations.stream().map(e -> String.valueOf(((java.util.Map<?, ?>) e).get("file"))).sorted().toList(),
                "every file in the folder has a line in expected.yml and the other way round");
        StringBuilder report = new StringBuilder();
        for (Object o : expectations) {
            Rec expected = Rec.from((java.util.Map<?, ?>) o);
            String raw = Files.readString(NEGATIVE.resolve(expected.str("file")));
            Rec stored = server.ingest.receive(raw, expected.str("file"), expected.str("channel"), "negative-samples").get(0);
            boolean ok = Status.REJECTED.equals(stored.str("status")) && expected.str("code").equals(stored.str("reasonCode"))
                    && (expected.str("text") == null || String.valueOf(stored.str("reasonText")).contains(expected.str("text")));
            if (!ok) {
                report.append(expected.str("file")).append(": expected ").append(expected.str("code")).append(" ").append(expected.str("text") == null ? "" : expected.str("text"))
                        .append(", got ").append(stored.str("status")).append(" ").append(stored.str("reasonCode")).append(": ").append(stored.str("reasonText")).append("\n");
            }
        }
        assertEquals("", report.toString());
    }
}
