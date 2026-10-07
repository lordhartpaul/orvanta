package io.orvanta.pay.transport;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.orvanta.core.data.Rec;
import java.util.List;
import java.util.ArrayList;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.json.Json;
import io.orvanta.pay.engine.IngestService;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Listens where the inbound Channel models say messages arrive:
 *
 * <pre>
 * transport:
 *   type: folder               # a directory of its own, relative to the data directory
 *   path: inbound/corporate
 *
 * transport:
 *   type: rabbitmq             # a queue
 *   uri: amqp://user:password@host:5672
 *   queue: corporate.pain001
 *   enabled: true              # optional; false keeps the model deployed without listening
 *
 * transport:
 *   type: http                 # fetched from a remote endpoint: GET until it has nothing more
 *   url: https://partner.example/outbox/next
 *   intervalSeconds: 30        # how often to ask; default 30
 *   auth: {type: bearer, token: ${env.PARTNER_TOKEN}}    # as on a Connector; also 'headers'
 *   idHeader: X-Message-Id     # the response header that names the message; default X-Message-Id
 *   acknowledge:               # optional: told to the endpoint after the message is stored here
 *     method: DELETE           # or POST
 *     url: https://partner.example/outbox/{id}
 * </pre>
 *
 * transport:
 *   type: sftp                 # files fetched from a directory on an SFTP server; settings as in {@link SftpLink}
 *   host: files.partner.example
 *   username: orvanta
 *   password: ${env.PARTNER_SFTP_PASSWORD}
 *   hostKey: SHA256:...
 *   path: /outbox
 *   archive: /outbox/done      # optional: where a fetched file is moved; without it the file is removed
 *   intervalSeconds: 30
 * </pre>
 *
 * <pre>
 * An http transport takes HTTP 200 with a body as one message and 204, 404 or an empty body as "nothing
 * waiting". With 'acknowledge' the endpoint may hand a message out again until it is acknowledged;
 * without it the endpoint must hand each message out once.
 *
 * A message arriving on a channel's transport is handed to that channel only. Listeners follow the
 * active deployment: an approved change to a channel starts, stops or moves its listener without a
 * restart. A broker that is unreachable does not stop the platform; it is tried again every 15 seconds.
 */
public final class TransportService {

    private static final Logger LOG = LoggerFactory.getLogger(TransportService.class);

    private interface Listener {
        void stop();
    }

    private final Platform platform;
    private final IngestService ingest;
    private final Map<String, Listener> listeners = new HashMap<>();
    private ScheduledExecutorService scheduler;

    public TransportService(Platform platform, IngestService ingest) {
        this.platform = platform;
        this.ingest = ingest;
    }

    public synchronized void start() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "orv-transport");
            t.setDaemon(true);
            return t;
        });
        reconcile();
        platform.deployments.onActivate(this::reconcile);
        scheduler.scheduleWithFixedDelay(this::reconcile, 15, 15, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        listeners.values().forEach(Listener::stop);
        listeners.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Names of the transports currently listening, for the health endpoint. */
    public synchronized Map<String, String> listening() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : listeners.keySet()) {
            out.put(key.substring(0, key.indexOf('|')), key.substring(key.indexOf('|') + 1));
        }
        return out;
    }

    private synchronized void reconcile() {
        Map<String, Rec[]> wanted = new LinkedHashMap<>();
        try {
            for (Rec channel : platform.deployments.registry().configs("Channel")) {
                if (!"inbound".equals(channel.str("direction"))) {
                    continue;
                }
                for (Object t : Ops.list(channel.get("transport"))) {
                    if (t instanceof Rec transport && !"false".equalsIgnoreCase(String.valueOf(transport.get("enabled")))
                            && !"rest".equals(transport.str("type"))) {
                        // the key carries the whole setting, so a changed setting replaces the listener
                        wanted.put(channel.str("name") + "|" + Json.write(transport), new Rec[]{channel, transport});
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.error("cannot read channel transports", e);
            return;
        }
        listeners.entrySet().removeIf(e -> {
            if (!wanted.containsKey(e.getKey())) {
                e.getValue().stop();
                LOG.info("transport stopped: {}", e.getKey());
                return true;
            }
            return false;
        });
        for (Map.Entry<String, Rec[]> e : wanted.entrySet()) {
            if (listeners.containsKey(e.getKey())) {
                continue;
            }
            String channel = e.getValue()[0].str("name");
            Rec transport = e.getValue()[1];
            try {
                listeners.put(e.getKey(), "rabbitmq".equals(transport.str("type")) ? queue(channel, transport)
                        : "http".equals(transport.str("type")) ? pull(channel, transport)
                        : "sftp".equals(transport.str("type")) ? sftp(channel, transport)
                        : "kafka".equals(transport.str("type")) ? kafka(channel, transport)
                        : "ibmmq".equals(transport.str("type")) ? ibmMq(channel, transport) : folder(channel, transport));
                LOG.info("transport listening: {}", e.getKey());
            } catch (Exception ex) {
                LOG.error("transport of {} could not start and will be tried again: {}", channel, ex.toString());
            }
        }
    }

    private Listener folder(String channel, Rec transport) throws IOException {
        Path dir = platform.dataDir.resolve(transport.str("path")).normalize();
        IngestService.prepareFolder(dir);
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
                () -> ingest.pollFolder(dir, channel, "folder:" + transport.str("path"), transport), 1, 1, TimeUnit.SECONDS);
        return () -> task.cancel(false);
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

    private Listener pull(String channel, Rec transport) {
        long every = transport.get("intervalSeconds") == null ? 30 : Math.max(1, Ops.num(transport.get("intervalSeconds")).longValue());
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                int fetched = fetch(channel, transport);
                if (fetched > 0) {
                    LOG.info("{} message(s) fetched for {} from {}", fetched, channel, transport.str("url"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // an endpoint that is down or answers with an error is asked again at the next interval
                LOG.warn("fetching for {} from {} failed: {}", channel, transport.str("url"), e.toString());
            }
        }, 1, every, TimeUnit.SECONDS);
        return () -> task.cancel(false);
    }

    /** An IBM MQ queue; settings as in {@link IbmMqLinks}. With 'receipt: {queue}' a receipt per message stored goes to that queue of the same queue manager. */
    private Listener ibmMq(String channel, Rec transport) {
        Runnable stop = IbmMqLinks.listen(transport, (name, value) -> {
            List<Rec> stored = ingest.receive(value, name, channel, "ibmmq:" + transport.str("queue"));
            if (transport.at("receipt.queue") != null) {
                Rec to = transport.copy();
                to.put("queue", transport.str("receipt.queue"));
                for (Rec receipt : receipts(stored, name, channel)) {
                    try {
                        IbmMqLinks.send(to, Json.write(receipt), java.util.Map.of("messageId", receipt.str("messageId"), "messageType", "orvanta.receipt"));
                    } catch (Exception e) {
                        LOG.warn("receipt for {} could not be put on {}: {}", receipt.str("messageId"), to.str("queue"), e.toString());
                    }
                }
            }
        });
        return stop::run;
    }

    /** A topic; settings as in {@link KafkaLinks}. A reader that stopped by itself is started again when the model changes or the server restarts. With 'receipt: {topic}' a receipt per message stored goes to that topic. */
    private Listener kafka(String channel, Rec transport) {
        Runnable stop = platform.kafka.listen(transport, (name, value) -> {
            List<Rec> stored = ingest.receive(value, name, channel, "kafka:" + transport.str("topic"));
            if (transport.at("receipt.topic") != null) {
                Rec to = transport.copy();
                to.put("topic", transport.str("receipt.topic"));
                for (Rec receipt : receipts(stored, name, channel)) {
                    try {
                        platform.kafka.send(to, receipt.str("messageId"), Json.write(receipt), java.util.Map.of("messageId", receipt.str("messageId"), "messageType", "orvanta.receipt", "contentType", "application/json"));
                    } catch (Exception e) {
                        LOG.warn("receipt for {} could not be sent to {}: {}", receipt.str("messageId"), to.str("topic"), e.toString());
                    }
                }
            }
        });
        return stop::run;
    }

    /** What the sender is told about each message stored from what it put on the queue: the id here, the outcome of reading it, the reason when refused. */
    static List<Rec> receipts(List<Rec> stored, String name, String channel) {
        List<Rec> out = new ArrayList<>();
        for (Rec m : stored) {
            out.add(Rec.of("receipt", "orvanta", "messageId", m.str("id"), "fileName", name, "channel", channel, "messageType", m.str("messageType"),
                    "status", m.str("status"), "reasonCode", m.str("reasonCode"), "reasonText", m.str("reasonText"), "receivedAt", m.str("receivedAt")));
        }
        return out;
    }

    private Listener sftp(String channel, Rec transport) {
        long every = transport.get("intervalSeconds") == null ? 30 : Math.max(1, Ops.num(transport.get("intervalSeconds")).longValue());
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                int fetched = fetchFiles(channel, transport);
                if (fetched > 0) {
                    LOG.info("{} file(s) fetched for {} from sftp://{}{}", fetched, channel, transport.str("host"), transport.str("path"));
                }
            } catch (Exception e) {
                // a server that is down or refuses us is asked again at the next interval
                LOG.warn("fetching for {} from sftp://{} failed: {}", channel, transport.str("host"), e.toString());
            }
        }, 1, every, TimeUnit.SECONDS);
        return () -> task.cancel(false);
    }

    /** Takes every file of the remote directory: stored here first, then moved to the archive directory or removed. @return files stored */
    int fetchFiles(String channel, Rec transport) throws IOException {
        String dir = transport.str("path");
        Rec model = platform.deployments.registry().config(channel);
        long maxBytes = model == null || model.get("maxBytes") == null ? 20_000_000L : Ops.num(model.get("maxBytes")).longValue();
        int fetched = 0;
        boolean sums = Checksums.wanted(transport);
        try (SftpLink link = new SftpLink(transport)) {
            java.util.List<String> names = link.files(dir);
            for (String name : names) {
                if (fetched >= 200) {
                    break;
                }
                if (Checksums.isCompanion(name)) {
                    // taken with its file
                    continue;
                }
                String actor = "sftp:" + transport.str("host");
                String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
                String companion = Checksums.companionName(name);
                if (sums && !names.contains(companion)) {
                    // the partner has not put the checksum file yet: the file stays on the server until it does
                    continue;
                }
                byte[] bytes = link.readBytes(dir + "/" + name, maxBytes);
                String problem = sums ? Checksums.verify(bytes, link.read(dir + "/" + companion, 4096)) : null;
                if (problem != null) {
                    ingest.rejectFile(safeName, "CHECKSUM_MISMATCH", problem, actor);
                } else {
                    ingest.receive(ingest.unwrapped(bytes, safeName, channel, actor, transport), safeName, channel, actor);
                }
                fetched++;
                // a crash between storing and this leaves the file to be fetched again, and the duplicate check refuses it
                for (String taken : sums ? java.util.List.of(name, companion) : java.util.List.of(name)) {
                    if (transport.str("archive") != null) {
                        link.move(dir + "/" + taken, transport.str("archive") + "/" + taken);
                    } else {
                        link.remove(dir + "/" + taken);
                    }
                }
            }
        }
        return fetched;
    }

    /** Asks the endpoint until it has nothing more, at most 200 messages in one round. @return messages stored */
    int fetch(String channel, Rec transport) throws IOException, InterruptedException {
        Map<String, String> headers = io.orvanta.core.flow.HttpConnector.headers(transport);
        String idHeader = transport.str("idHeader") == null ? "X-Message-Id" : transport.str("idHeader");
        int fetched = 0;
        String previous = null;
        while (fetched < 200) {
            java.net.http.HttpRequest.Builder get = java.net.http.HttpRequest.newBuilder(java.net.URI.create(transport.str("url")))
                    .timeout(java.time.Duration.ofSeconds(30)).GET();
            headers.forEach(get::header);
            java.net.http.HttpResponse<String> response = HTTP.send(get.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204 || response.statusCode() == 404 || (response.statusCode() == 200 && response.body().isBlank())) {
                break;
            }
            if (response.statusCode() != 200) {
                throw new IOException("the endpoint answered HTTP " + response.statusCode());
            }
            String id = response.headers().firstValue(idHeader).orElse(null);
            Rec acknowledge = transport.get("acknowledge") instanceof Rec a ? a : null;
            if (acknowledge == null && response.body().equals(previous)) {
                // without acknowledgement the endpoint has to move on by itself; one that repeats itself is left until the next round
                break;
            }
            previous = response.body();
            String name = id == null ? "http-" + System.currentTimeMillis() + "-" + fetched : id.replaceAll("[^A-Za-z0-9._-]", "_");
            ingest.receive(response.body(), name, channel, "http:" + java.net.URI.create(transport.str("url")).getHost());
            fetched++;
            if (acknowledge != null) {
                if (id == null) {
                    throw new IOException("the endpoint did not name the message in the header " + idHeader + ", so it cannot be acknowledged");
                }
                // stored first, acknowledged after: a crash in between makes the endpoint hand the message out again, and the duplicate check refuses it
                java.net.URI at = java.net.URI.create(acknowledge.str("url").replace("{id}", java.net.URLEncoder.encode(id, StandardCharsets.UTF_8)));
                java.net.http.HttpRequest.Builder ack = java.net.http.HttpRequest.newBuilder(at).timeout(java.time.Duration.ofSeconds(30))
                        .method("POST".equalsIgnoreCase(acknowledge.str("method")) ? "POST" : "DELETE", java.net.http.HttpRequest.BodyPublishers.noBody());
                headers.forEach(ack::header);
                int answered = HTTP.send(ack.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
                if (answered / 100 != 2) {
                    throw new IOException("the endpoint answered HTTP " + answered + " to the acknowledgement of " + id);
                }
            }
        }
        return fetched;
    }

    private Listener queue(String channel, Rec transport) throws Exception {
        String queue = transport.str("queue");
        Channel amqp = platform.rabbit.connection(transport.str("uri") == null ? "amqp://localhost:5672" : transport.str("uri")).createChannel();
        amqp.queueDeclare(queue, true, false, false, null);
        String receiptQueue = transport.str("receipt.queue");
        if (receiptQueue != null) {
            amqp.queueDeclare(receiptQueue, true, false, false, null);
        }
        amqp.basicQos(4);
        amqp.basicConsume(queue, false, new DefaultConsumer(amqp) {
            @Override
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                try {
                    String name = properties.getMessageId() == null ? "mq-" + envelope.getDeliveryTag() : properties.getMessageId();
                    List<Rec> stored = ingest.receive(new String(body, StandardCharsets.UTF_8), name, channel, "mq:" + queue);
                    if (receiptQueue != null) {
                        // a receipt per message stored, so the sender knows what became of what it put on the queue
                        for (Rec receipt : receipts(stored, name, channel)) {
                            amqp.basicPublish("", receiptQueue, new AMQP.BasicProperties.Builder().contentType("application/json").type("orvanta.receipt")
                                    .messageId(receipt.str("messageId")).correlationId(name).deliveryMode(2).build(), Json.write(receipt).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    // acknowledged only after the message is stored, so a crash before this point redelivers it
                    amqp.basicAck(envelope.getDeliveryTag(), false);
                } catch (RuntimeException e) {
                    LOG.error("message from queue {} could not be stored and goes back to the queue", queue, e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    amqp.basicNack(envelope.getDeliveryTag(), false, true);
                }
            }
        });
        return () -> {
            try {
                amqp.close();
            } catch (Exception ignored) {
                // the connection may already be gone
            }
        };
    }
}
