package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Sends an outbound file to the destination of its channel: a folder, an HTTP endpoint, a queue or a directory on an SFTP server. */
public final class DispatchService {

    private final Platform platform;

    public DispatchService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.OUTBOUND_CREATED, "dispatch", m -> handle(m.str("id"), Status.CREATED));
    }

    /** @param fromStatus CREATED normally, FAILED when an operator retries */
    /** Whether a channel's dispatch is paused by an operator: its files wait as CREATED until it is resumed. */
    public boolean paused(String channelName) {
        Rec setting = platform.store.get(DocStore.SETTING, "channel.paused." + channelName);
        if (setting != null && Boolean.TRUE.equals(setting.get("paused"))) {
            return true;
        }
        // a destination switched off in its model ('enabled: false') holds its files the same way, until the model is changed
        Rec channel = platform.deployments.registry().config(channelName);
        return channel != null && channel.at("destination.enabled") != null && "false".equalsIgnoreCase(String.valueOf(channel.at("destination.enabled")));
    }

    public boolean handle(String outboundId, String fromStatus) {
        DocStore store = platform.store;
        Rec waiting = store.get(DocStore.OUTBOUND, outboundId);
        if (waiting != null && Status.CREATED.equals(fromStatus) && paused(waiting.str("channel"))) {
            // left as it is; the recovery sweep offers it again, and it goes when the channel is resumed
            return false;
        }
        if (!store.updateIf(DocStore.OUTBOUND, outboundId, Rec.of("status", fromStatus),
                Rec.of("status", Status.DISPATCHING, "claimedAt", Platform.now()))) {
            return false;
        }
        Rec outbound = store.get(DocStore.OUTBOUND, outboundId);
        Rec channel = platform.deployments.registry().config(outbound.str("channel"));
        try {
            String location = send(outbound, channel);
            store.updateIf(DocStore.OUTBOUND, outboundId, Rec.of("status", Status.DISPATCHING),
                    Rec.of("status", Status.SENT, "sentAt", Platform.now(), "location", location));
            // a channel that sends payments back is the end of the road for them: sent means returned
            boolean returns = Status.RETURNED.equals(channel.str("sentStatus"));
            for (Object id : Ops.list(outbound.get("transactionIds"))) {
                if (store.updateIf(DocStore.TXN, Ops.str(id), Rec.of("status", Status.BULKED),
                        Rec.of("status", returns ? Status.RETURNED : Status.SENT, "sentAt", Platform.now(), "updatedAt", Platform.now()))) {
                    platform.event(Ops.str(id), returns ? Status.RETURNED : Status.SENT, (returns ? "sent back in " : "sent in ") + outboundId, null, null);
                    if (returns) {
                        // whatever was credited for it is taken back
                        Lifecycle.unwound(platform, Ops.str(id));
                        // and the customer is told, when the money had been booked on the account
                        NotificationService.reversed(platform, Ops.str(id));
                    }
                }
            }
            platform.event(outboundId, Status.SENT, "delivered to " + location, null, null);
            platform.bus.publish(Bus.OUTBOUND_SENT, Rec.of("id", outboundId));
            return true;
        } catch (Exception e) {
            store.updateIf(DocStore.OUTBOUND, outboundId, Rec.of("status", Status.DISPATCHING),
                    Rec.of("status", Status.FAILED, "reasonText", String.valueOf(e.getMessage())));
            platform.event(outboundId, Status.FAILED, "delivery failed: " + e.getMessage(), null, null);
            return false;
        }
    }

    private String send(Rec outbound, Rec channel) throws Exception {
        String type = String.valueOf(channel.at("destination.type"));
        String payload = outbound.str("payload");
        // a file for a partner may go out encrypted to the partner's key and signed with ours
        Rec destination = channel.rec("destination");
        String extension = destination.get("extension") == null ? "txt" : destination.str("extension");
        byte[] content = payload.getBytes(StandardCharsets.UTF_8);
        if (io.orvanta.pay.transport.Pgp.wanted(destination)) {
            content = io.orvanta.pay.transport.Pgp.protect(destination, content, outbound.str("id") + "." + extension);
            extension = extension + (Boolean.TRUE.equals(destination.rec("pgp").get("armor")) ? ".asc" : ".gpg");
        }
        if ("folder".equals(type)) {
            Path dir = platform.dataDir.resolve(channel.str("destination.path")).normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(outbound.str("id") + "." + extension);
            // write under a temporary name first so a reader never sees a half written file
            Path tmp = dir.resolve(outbound.str("id") + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (io.orvanta.pay.transport.Checksums.wanted(destination)) {
                // the checksum file goes after the file, so a reader that waits for it never sees a half written file
                Files.writeString(dir.resolve(io.orvanta.pay.transport.Checksums.companionName(file.getFileName().toString())),
                        io.orvanta.pay.transport.Checksums.companionText(io.orvanta.pay.transport.Checksums.sha256Hex(content), file.getFileName().toString()));
            }
            return file.toString();
        }
        String contentType = "iso20022".equals(outbound.str("format")) ? "application/xml"
                : "json".equals(outbound.str("format")) ? "application/json" : "text/plain";
        if ("http".equals(type)) {
            String url = channel.str("destination.url");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", contentType).header("X-Orvanta-Message-Id", outbound.str("id"));
            // same headers and auth settings as a Connector model
            io.orvanta.core.flow.HttpConnector.headers(channel.rec("destination")).forEach(request::header);
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(url + " answered HTTP " + response.statusCode());
            }
            return url;
        }
        if ("ibmmq".equals(type)) {
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("messageId", outbound.str("id"));
            headers.put("messageType", outbound.str("messageType"));
            String messageId = io.orvanta.pay.transport.IbmMqLinks.send(destination, payload, headers);
            return "ibmmq:" + destination.str("queueManager") + "/" + destination.str("queue") + "#" + messageId;
        }
        if ("kafka".equals(type)) {
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("messageId", outbound.str("id"));
            headers.put("messageType", outbound.str("messageType"));
            headers.put("contentType", contentType);
            platform.kafka.send(destination, outbound.str("id"), payload, headers);
            return "kafka:" + destination.str("topic");
        }
        if ("sftp".equals(type)) {
            try (io.orvanta.pay.transport.SftpLink link = new io.orvanta.pay.transport.SftpLink(destination)) {
                link.write(destination.str("path"), outbound.str("id") + "." + extension, content);
                if (io.orvanta.pay.transport.Checksums.wanted(destination)) {
                    String name = outbound.str("id") + "." + extension;
                    link.write(destination.str("path"), io.orvanta.pay.transport.Checksums.companionName(name),
                            io.orvanta.pay.transport.Checksums.companionText(io.orvanta.pay.transport.Checksums.sha256Hex(content), name));
                }
            }
            return "sftp://" + destination.str("host") + destination.str("path") + "/" + outbound.str("id") + "." + extension;
        }
        if ("rabbitmq".equals(type)) {
            String uri = channel.at("destination.uri") == null ? "amqp://localhost:5672" : channel.str("destination.uri");
            String exchange = channel.at("destination.exchange") == null ? "" : channel.str("destination.exchange");
            String routingKey = channel.at("destination.queue") != null ? channel.str("destination.queue") : channel.str("destination.routingKey");
            com.rabbitmq.client.Channel amqp = platform.rabbit.connection(uri).createChannel();
            try {
                if (channel.at("destination.queue") != null) {
                    amqp.queueDeclare(routingKey, true, false, false, null);
                }
                // SENT means the broker has taken the message, not merely that it was written to a socket
                amqp.confirmSelect();
                amqp.basicPublish(exchange, routingKey, new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                        .contentType(contentType).deliveryMode(2).messageId(outbound.str("id")).type(outbound.str("messageType")).build(),
                        payload.getBytes(StandardCharsets.UTF_8));
                amqp.waitForConfirmsOrDie(10_000);
            } finally {
                amqp.close();
            }
            return "rabbitmq:" + (exchange.isEmpty() ? "" : exchange + "/") + routingKey;
        }
        throw new IllegalStateException("channel " + channel.str("name") + " has unknown destination type '" + type + "'");
    }
}
