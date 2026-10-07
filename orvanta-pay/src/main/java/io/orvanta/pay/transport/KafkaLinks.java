package io.orvanta.pay.transport;

import io.orvanta.core.data.Rec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka for channels: a topic that messages arrive on, and a topic that outbound files are sent to.
 *
 * <pre>
 *   type: kafka
 *   bootstrapServers: broker1:9092,broker2:9092
 *   topic: payments.pain001
 *   groupId: orvanta                # inbound only; instances with the same group share the topic
 *   properties:                     # optional: any Kafka client setting, for example security.protocol and sasl.*
 *     security.protocol: SASL_SSL
 * </pre>
 *
 * A record's value is one message. Inbound, the position in the topic is committed only after the
 * message is stored, so a crash before that makes the broker hand the record out again, and the
 * duplicate check refuses it. Outbound, a file counts as sent when all in-sync replicas have it.
 */
public final class KafkaLinks {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaLinks.class);

    private final Map<String, KafkaProducer<String, String>> producers = new ConcurrentHashMap<>();

    private static Properties settings(Rec model) {
        Properties p = new Properties();
        p.put("bootstrap.servers", model.str("bootstrapServers"));
        if (model.get("properties") instanceof Map<?, ?> extra) {
            extra.forEach((k, v) -> p.put(String.valueOf(k), String.valueOf(v)));
        }
        return p;
    }

    /** Sends one message and waits until the broker confirms it. */
    public void send(Rec destination, String key, String payload, Map<String, String> headers) throws Exception {
        KafkaProducer<String, String> producer = producers.computeIfAbsent(String.valueOf(destination), k -> {
            Properties p = settings(destination);
            p.put("acks", "all");
            p.put("enable.idempotence", "true");
            p.put("max.block.ms", "10000");
            p.put("delivery.timeout.ms", "15000");
            p.put("request.timeout.ms", "10000");
            return new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
        });
        ProducerRecord<String, String> record = new ProducerRecord<>(destination.str("topic"), key, payload);
        headers.forEach((name, value) -> {
            if (value != null) {
                record.headers().add(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        producer.send(record).get(20, TimeUnit.SECONDS);
    }

    /**
     * Reads a topic on a thread of its own until stopped.
     * @param handler given the name for the message (topic-partition-offset) and its value; when it throws, the record is read again
     * @return what stops the reading
     */
    public Runnable listen(Rec transport, BiConsumer<String, String> handler) {
        Properties p = settings(transport);
        p.put("group.id", transport.str("groupId") == null ? "orvanta" : transport.str("groupId"));
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "earliest");
        p.put("max.poll.records", "50");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
        AtomicBoolean stopped = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                consumer.subscribe(List.of(transport.str("topic")));
                while (!stopped.get()) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                        try {
                            handler.accept(record.topic() + "-" + record.partition() + "-" + record.offset(), record.value());
                        } catch (RuntimeException e) {
                            // not stored: go back to this record and try again after a pause
                            LOG.error("record {} of {} could not be stored and is read again", record.offset(), record.topic(), e);
                            consumer.seek(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()), record.offset());
                            Thread.sleep(2000);
                            break;
                        }
                        consumer.commitSync(Map.of(new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()),
                                new org.apache.kafka.clients.consumer.OffsetAndMetadata(record.offset() + 1)));
                    }
                }
            } catch (WakeupException e) {
                // stop() woke the poll up
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.error("reading {} stopped: {}", transport.str("topic"), e.toString());
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }, "orv-kafka-" + transport.str("topic"));
        thread.setDaemon(true);
        thread.start();
        return () -> {
            stopped.set(true);
            consumer.wakeup();
        };
    }

    public void close() {
        producers.values().forEach(producer -> producer.close(Duration.ofSeconds(5)));
        producers.clear();
    }
}
