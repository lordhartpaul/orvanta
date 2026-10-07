package io.orvanta.pay.kernel;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.orvanta.core.data.Rec;
import io.orvanta.core.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * RabbitMQ bus: one durable topic exchange, one durable queue per (group, topic).
 * A message is acknowledged after its handler returns. A handler that keeps failing is retried
 * three times and then handed to the failure handler (the dead-letter list); handlers are idempotent (status
 * transitions are compare-and-set), so redelivery after a crash is safe.
 */
public final class RabbitBus implements Bus {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitBus.class);
    private static final String EXCHANGE = "orvanta.events";

    private final Connection connection;
    private final Channel publisher;
    private volatile FailureHandler failureHandler;
    private final int consumers;

    public RabbitBus(String uri) throws Exception {
        this(uri, 4);
    }

    /** @param consumers how many messages of one service this process handles at the same time */
    public RabbitBus(String uri, int consumers) throws Exception {
        this.consumers = Math.max(1, consumers);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        factory.setAutomaticRecoveryEnabled(true);
        connection = connect(factory, uri);
        publisher = connection.createChannel();
        publisher.exchangeDeclare(EXCHANGE, "topic", true);
    }

    @Override
    public void declare(String topic, String group) {
        try {
            synchronized (publisher) {
                String queue = "orvanta." + group + "." + topic;
                publisher.queueDeclare(queue, true, false, false, null);
                publisher.queueBind(queue, EXCHANGE, topic);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot declare the queue of " + group + " for " + topic + ": " + e.getMessage(), e);
        }
    }

    private static Connection connect(ConnectionFactory factory, String uri) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (true) {
            try {
                return factory.newConnection("orvanta");
            } catch (java.io.IOException | java.util.concurrent.TimeoutException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("RabbitMQ at " + uri.replaceAll("//[^@/]*@", "//") + " is not reachable: " + e, e);
                }
                LOG.warn("RabbitMQ is not reachable yet ({}); trying again", e.toString());
                Thread.sleep(2000);
            }
        }
    }

    @Override
    public void publish(String topic, Rec message) {
        try {
            synchronized (publisher) {
                publisher.basicPublish(EXCHANGE, topic,
                        new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(),
                        Json.write(message).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot publish to " + topic + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void onFailure(FailureHandler handler) {
        this.failureHandler = handler;
    }

    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.List<Channel> consuming = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void pause() {
        // the consuming channels are closed: the broker keeps what was not acknowledged and hands it to another instance
        for (Channel channel : consuming) {
            try {
                channel.close();
            } catch (Exception ignored) {
                // the connection is closed at the end either way
            }
        }
        consuming.clear();
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void subscribe(String topic, String group, Consumer<Rec> handler) {
        // each consumer has a channel of its own; the broker hands every message to exactly one of them
        for (int i = 0; i < consumers; i++) {
            consume(topic, "orvanta." + group + "." + topic, true, handler);
        }
    }

    @Override
    public void subscribeAll(String topic, Consumer<Rec> handler) {
        consume(topic, null, false, handler);
    }

    private void consume(String topic, String queueName, boolean shared, Consumer<Rec> handler) {
        try {
            Channel channel = connection.createChannel();
            channel.basicQos(8);
            String queue = shared
                    ? channel.queueDeclare(queueName, true, false, false, null).getQueue()
                    : channel.queueDeclare("", false, true, true, null).getQueue();
            channel.queueBind(queue, EXCHANGE, topic);
            channel.basicConsume(queue, false, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    Rec message = Json.parse(new String(body, StandardCharsets.UTF_8));
                    inFlight.incrementAndGet();
                    try {
                        handle(message);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    channel.basicAck(envelope.getDeliveryTag(), false);
                }

                private void handle(Rec message) {
                    RuntimeException last = null;
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        try {
                            handler.accept(message);
                            last = null;
                            break;
                        } catch (RuntimeException e) {
                            last = e;
                            LOG.error("handler of {} failed (attempt {}/3) for {}: {}", topic, attempt, message, e.toString());
                        }
                    }
                    if (last != null && failureHandler != null) {
                        failureHandler.failed(topic, message, last);
                    }
                }
            });
            consuming.add(channel);
        } catch (IOException e) {
            throw new IllegalStateException("cannot subscribe to " + topic + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (IOException e) {
            LOG.warn("closing RabbitMQ connection: {}", e.getMessage());
        }
    }
}
