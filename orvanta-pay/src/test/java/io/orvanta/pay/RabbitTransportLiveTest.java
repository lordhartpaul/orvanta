package io.orvanta.pay;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.sun.net.httpserver.HttpServer;
import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import io.orvanta.pay.engine.Status;
import io.orvanta.pay.kernel.Config;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryBus;
import io.orvanta.pay.kernel.MemoryDocStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Message queue ingress and egress against a real broker. Runs only when one is named:
 *   mvn test -Dorvanta.it.rabbit=amqp://guest:guest@localhost:5672
 */
class RabbitTransportLiveTest {

    @TempDir
    Path temp;

    @Test
    void anInstructionArrivesOnAQueueAndItsStatusReportLeavesOnAQueue() throws Exception {
        String uri = System.getProperty("orvanta.it.rabbit");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "orvanta.it.rabbit not set, skipping");
        String suffix = String.valueOf(System.currentTimeMillis());
        String outQueue = "orvanta.test.status." + suffix;
        String inQueue = "orvanta.in.corporate.pain001";

        // a copy of the workspace whose status report channel delivers to a queue instead of a folder
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
        Path statusChannel = workspace.resolve("channels/CustomerStatusOutbound.yaml");
        String text = Files.readString(statusChannel);
        String folder = "destination:\n  type: folder\n  path: outbound/customer-status\n  extension: xml";
        assertTrue(text.contains(folder), "the status channel model changed; adjust this test");
        Files.writeString(statusChannel, text.replace(folder, "destination:\n  type: rabbitmq\n  uri: " + uri + "\n  queue: " + outQueue));
        // and whose inbound queue sends a receipt per message stored
        String receiptQueue = "orvanta.test.receipt." + suffix;
        Path inChannel = workspace.resolve("channels/CorporateIsoInbound.yaml");
        String inText = Files.readString(inChannel);
        String queueBlock = "    queue: orvanta.in.corporate.pain001\n";
        assertTrue(inText.contains(queueBlock), "the corporate channel model changed; adjust this test");
        Files.writeString(inChannel, inText.replace(queueBlock, queueBlock + "    receipt:\n      queue: " + receiptQueue + "\n"));

        HttpServer screening = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        OrvantaServer[] holder = new OrvantaServer[1];
        screening.createContext("/sim", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] reply = Json.write(holder[0].simulator.handle(exchange.getRequestMethod(), exchange.getRequestURI().getPath().substring(4),
                    body.isBlank() ? new Rec() : Json.parse(body))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        screening.start();
        System.setProperty("ORVANTA_SIM_URL", "http://127.0.0.1:" + screening.getAddress().getPort() + "/sim");
        System.setProperty("ORVANTA_MQ_ENABLED", "true");
        System.setProperty("ORVANTA_MQ_URI", uri);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        DocStore store = new MemoryDocStore();
        OrvantaServer server = new OrvantaServer(Config.of(Rec.of(
                "units", "ingest,debulk,process,bulk,dispatch,acknowledge,report",
                "workspace", Rec.of("dir", workspace.toString()),
                "data", Rec.of("dir", temp.resolve("data").toString()),
                "simulator", Rec.of("enabled", "true"))), store, new MemoryBus());
        holder[0] = server;
        try (Connection connection = factory.newConnection(); Channel amqp = connection.createChannel()) {
            amqp.queueDeclare(inQueue, true, false, false, null);
            amqp.queuePurge(inQueue);
            amqp.queueDeclare(outQueue, true, false, false, null);
            server.start();
            assertTrue(server.transports.listening().containsKey("channels.CorporateIsoInbound"), server.transports.listening().toString());

            String msgId = "MQ-" + suffix;
            String raw = Files.readString(workspace.resolve("tests/messages/pain001-salaries.xml")).replace("SALARY-2026-10-001", msgId);
            amqp.basicPublish("", inQueue, null, raw.getBytes(StandardCharsets.UTF_8));

            // the receipt: what the message became here
            GetResponse receipt = null;
            long receiptDeadline = System.currentTimeMillis() + 40_000;
            while (receipt == null && System.currentTimeMillis() < receiptDeadline) {
                receipt = amqp.basicGet(receiptQueue, true);
                if (receipt == null) {
                    Thread.sleep(250);
                }
            }
            assertNotNull(receipt, "a receipt on " + receiptQueue);
            assertEquals("orvanta.receipt", receipt.getProps().getType());
            Rec told = Json.parse(new String(receipt.getBody(), StandardCharsets.UTF_8));
            assertTrue(String.valueOf(told.str("messageId")).startsWith("ORVINS"), told.toString());
            assertEquals("channels.CorporateIsoInbound", told.str("channel"));
            assertEquals("RECEIVED", told.str("status"));

            String report = null;
            long deadline = System.currentTimeMillis() + 40_000;
            while (report == null && System.currentTimeMillis() < deadline) {
                GetResponse got = amqp.basicGet(outQueue, true);
                if (got == null) {
                    Thread.sleep(250);
                } else {
                    report = new String(got.getBody(), StandardCharsets.UTF_8);
                    assertEquals("pain.002.001.10", got.getProps().getType());
                    assertNotNull(got.getProps().getMessageId());
                }
            }
            assertNotNull(report, "no status report arrived on " + outQueue);
            assertTrue(report.contains("<OrgnlMsgId>" + msgId + "</OrgnlMsgId>"), report);
            assertTrue(report.contains("<TxSts>ACCP</TxSts>") && report.contains("<TxSts>RJCT</TxSts>"), report);

            List<Rec> received = store.find(DocStore.MESSAGE, Rec.of("purpose", "instruction", "msgId", msgId), null, false, 0);
            assertEquals(1, received.size());
            assertEquals("mq:" + inQueue, received.get(0).str("receivedBy"));
            assertEquals(Status.DEBULKED, received.get(0).str("status"));
            // the broker has the message a moment before the outbound record says so
            long recorded = System.currentTimeMillis() + 10_000;
            while (store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport"), null, false, 0).get(0).str("location") == null
                    && System.currentTimeMillis() < recorded) {
                Thread.sleep(100);
            }
            Rec sent = store.find(DocStore.OUTBOUND, Rec.of("kind", "statusReport"), null, false, 0).get(0);
            assertEquals("rabbitmq:" + outQueue, sent.str("location"));
            amqp.queueDelete(outQueue);
        } finally {
            server.stop();
            screening.stop(0);
            System.clearProperty("ORVANTA_SIM_URL");
            System.clearProperty("ORVANTA_MQ_ENABLED");
            System.clearProperty("ORVANTA_MQ_URI");
        }
    }
}
