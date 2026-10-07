package io.orvanta.pay.transport;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.HashMap;
import java.util.Map;

/** One shared RabbitMQ connection per broker URI, for channel transports and destinations. */
public final class RabbitLinks {

    private final Map<String, Connection> connections = new HashMap<>();

    public synchronized Connection connection(String uri) throws Exception {
        Connection existing = connections.get(uri);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setConnectionTimeout(5000);
        Connection created = factory.newConnection("orvanta-transport");
        connections.put(uri, created);
        return created;
    }

    public synchronized void close() {
        for (Connection c : connections.values()) {
            try {
                c.close();
            } catch (Exception ignored) {
                // closing at shutdown; nothing useful to do with a failure here
            }
        }
        connections.clear();
    }
}
