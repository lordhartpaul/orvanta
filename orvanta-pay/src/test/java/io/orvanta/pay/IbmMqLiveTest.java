package io.orvanta.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.orvanta.core.data.Rec;
import io.orvanta.pay.transport.IbmMqLinks;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * IBM MQ against a real queue manager. Runs only when one is named:
 *   mvn test -Dorvanta.it.ibmmq.host=10.50.1.104 -Dorvanta.it.ibmmq.port=1600 -Dorvanta.it.ibmmq.channel=SBG609SN.CH
 *            -Dorvanta.it.ibmmq.queueManager=CMQPSB6093XDQM -Dorvanta.it.ibmmq.queue=A.TEST.QUEUE
 *            -Dorvanta.it.ibmmq.user=... -Dorvanta.it.ibmmq.password=...
 * The probe only looks at the queue. The round trip puts a message and takes it back and needs a queue of
 * its own (-Dorvanta.it.ibmmq.roundTrip=true): on a shared queue another reader would take the test message.
 */
class IbmMqLiveTest {

    private static Rec settings() {
        String host = System.getProperty("orvanta.it.ibmmq.host");
        Assumptions.assumeTrue(host != null && !host.isBlank(), "orvanta.it.ibmmq.host not set, skipping");
        return Rec.of("type", "ibmmq", "host", host, "port", Integer.parseInt(System.getProperty("orvanta.it.ibmmq.port", "1414")),
                "channel", System.getProperty("orvanta.it.ibmmq.channel"), "queueManager", System.getProperty("orvanta.it.ibmmq.queueManager"),
                "queue", System.getProperty("orvanta.it.ibmmq.queue"), "username", System.getProperty("orvanta.it.ibmmq.user"),
                "password", System.getProperty("orvanta.it.ibmmq.password"), "waitSeconds", 2);
    }

    @Test
    void theQueueManagerAnswersAProbeAndAMessageGoesRoundTripOnAQueueOfItsOwn() throws Exception {
        Rec settings = settings();
        // the probe: connect, look at the queue, disconnect; nothing is read or put. A queue manager that cannot be
        // reached from this machine (connection refused, reason 2538/2539) is the network's doing, not the code's: the
        // test is then not proven rather than failed, like a live test without its settings
        Rec probe;
        try {
            probe = IbmMqLinks.probe(settings);
        } catch (com.ibm.mq.MQException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(e.reasonCode != 2538 && e.reasonCode != 2539 && e.reasonCode != 2059,
                    "the queue manager at " + settings.str("host") + ":" + settings.get("port") + " is not reachable from here (MQ reason " + e.reasonCode + "), skipping");
            throw e;
        }
        assertEquals(true, probe.get("connected"));
        assertNotNull(probe.get("depth"));
        assertTrue(((Number) probe.get("depth")).intValue() >= 0, probe.toString());
        if (!"true".equals(System.getProperty("orvanta.it.ibmmq.roundTrip"))) {
            // on a shared queue another reader would take the test message, so the round trip needs a queue named for it
            return;
        }
        String payload = "<Document>orvanta-ibmmq-test-" + System.nanoTime() + "</Document>";
        String messageId = IbmMqLinks.send(settings, payload, Map.of("messageId", "ORVOUT-TEST", "messageType", "test"));
        assertTrue(messageId.length() == 48, messageId);
        CountDownLatch read = new CountDownLatch(1);
        AtomicReference<String> got = new AtomicReference<>();
        Runnable stop = IbmMqLinks.listen(settings, (name, text) -> {
            if (text.contains("orvanta-ibmmq-test-")) {
                got.set(text);
                read.countDown();
            }
        });
        try {
            assertTrue(read.await(30, TimeUnit.SECONDS), "the message came back");
            assertEquals(payload, got.get());
        } finally {
            stop.run();
        }
    }
}
