package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kafka ingress and egress against a real broker. Runs only when one is named:
 *   mvn test -Dorvanta.it.kafka=localhost:9092
 */
class KafkaTransportLiveTest {

    @TempDir
    Path temp;

    @Test
    void anInstructionArrivesOnATopicAndItsStatusReportLeavesOnATopic() throws Exception {
        String brokers = System.getProperty("orvanta.it.kafka");
        Assumptions.assumeTrue(brokers != null && !brokers.isBlank(), "orvanta.it.kafka not set, skipping");
        String suffix = String.valueOf(System.currentTimeMillis());
        String inTopic = "orvanta.test.in." + suffix;
        String outTopic = "orvanta.test.status." + suffix;

        Path source = Path.of("..", "workspace").toAbsolutePath().normalize();
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
        Path inbound = workspace.resolve("channels/CorporateIsoInbound.yaml");
        String text = Files.readString(inbound);
        assertTrue(text.contains("transport:\n"), "the channel model changed; adjust this test");
        Files.writeString(inbound, text.replace("transport:\n", "transport:\n  - type: kafka\n    bootstrapServers: " + brokers + "\n    topic: " + inTopic
                + "\n    groupId: orvanta-test-" + suffix + "\n"));
        Path status = workspace.resolve("channels/CustomerStatusOutbound.yaml");
        String statusText = Files.readString(status);
        String folder = "destination:\n  type: folder\n  path: outbound/customer-status\n  extension: xml";
        assertTrue(statusText.contains(folder), "the status channel model changed; adjust this test");
        Files.writeString(status, statusText.replace(folder, "destination:\n  type: kafka\n  bootstrapServers: " + brokers + "\n  topic: " + outTopic));

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        System.setProperty("ORVANTA_SIM_URL", "http://localhost:" + port + "/sim");
        DocStore store = new MemoryDocStore();
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "all",
                "server", Rec.of("port", String.valueOf(port)),
                "security", Rec.of("jwtSecret", "a-kafka-test-secret-that-is-long-enough", "seedFile", "no-such-directory/seed.yaml"),
                "workspace", Rec.of("dir", workspace.toString()),
                "data", Rec.of("dir", temp.resolve("data").toString()),
                "simulator", Rec.of("enabled", "true"))), store, new MemoryBus());
        Properties client = new Properties();
        client.put("bootstrap.servers", brokers);
        Properties reader = new Properties();
        reader.putAll(client);
        reader.put("group.id", "orvanta-test-reader-" + suffix);
        reader.put("auto.offset.reset", "earliest");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(client, new StringSerializer(), new StringSerializer());
                KafkaConsumer<String, String> consumer = new KafkaConsumer<>(reader, new StringDeserializer(), new StringDeserializer())) {
            String msgId = "KAFKA-" + suffix;
            String raw = Files.readString(workspace.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", msgId);
            producer.send(new ProducerRecord<>(inTopic, raw)).get(20, TimeUnit.SECONDS);
            server.start();

            long deadline = System.currentTimeMillis() + 40_000;
            while (store.count(DocStore.MESSAGE, Rec.of("msgId", msgId)) < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
            }
            List<Rec> received = store.find(DocStore.MESSAGE, Rec.of("msgId", msgId), "id", false, 5);
            assertEquals(1, received.size(), "the instruction was taken from the topic");
            assertEquals("kafka:" + inTopic, received.get(0).str("receivedBy"));
            assertEquals("channels.CorporateIsoInbound", received.get(0).str("channel"));

            consumer.subscribe(List.of(outTopic));
            ConsumerRecord<String, String> report = null;
            deadline = System.currentTimeMillis() + 60_000;
            while (report == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    report = record;
                }
            }
            assertNotNull(report, "a status report arrived on the topic");
            assertTrue(report.value().contains("pain.002.001.10") && report.value().contains(msgId), report.value());
            assertEquals("pain.002.001.10", new String(report.headers().lastHeader("messageType").value(), java.nio.charset.StandardCharsets.UTF_8));
            Rec sent = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport", "status", "SENT"), "id", false, 1).get(0);
            assertEquals("kafka:" + outTopic, sent.str("location"));
            assertEquals(report.key(), sent.str("id"));
        } finally {
            server.stop();
            System.clearProperty("ORVANTA_SIM_URL");
        }
    }
}
