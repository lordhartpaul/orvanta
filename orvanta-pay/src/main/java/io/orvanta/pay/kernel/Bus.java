package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;

import java.util.function.Consumer;

/**
 * Event bus between services. With the memory bus all services run in one process; with the
 * RabbitMQ bus each service can run as its own process and scale out, because subscribers of
 * the same group share one queue.
 */
public interface Bus {

    String INSTRUCTION_RECEIVED = "orv.instruction.received";
    String ACK_RECEIVED = "orv.acknowledgement.received";
    String CANCELLATION_RECEIVED = "orv.cancellation.received";
    String RESOLUTION_RECEIVED = "orv.resolution.received";
    String RETURN_RECEIVED = "orv.return.received";
    String TXN_CREATED = "orv.transaction.created";
    String TXN_ROUTED = "orv.transaction.routed";
    /** a transaction ended without the payment going through: rejected, cancelled or returned */
    String TXN_UNWOUND = "orv.transaction.unwound";
    String OUTBOUND_CREATED = "orv.outbound.created";
    String OUTBOUND_SENT = "orv.outbound.sent";
    String DEPLOYMENT_ACTIVATED = "orv.deployment.activated";

    void publish(String topic, Rec message);

    /** Work queue: each message is handled by one subscriber of the group. */
    void subscribe(String topic, String group, Consumer<Rec> handler);

    /** Fan-out: every process that subscribed receives every message. */
    void subscribeAll(String topic, Consumer<Rec> handler);

    /** Topic an inbound message of a channel purpose is announced on: orv.[purpose].received */
    static String inboundTopic(String purpose) {
        return "orv." + purpose + ".received";
    }

    /** Called when a handler has failed on every attempt, so the message can be kept instead of lost. */
    interface FailureHandler {
        void failed(String topic, Rec message, Throwable error);
    }

    void onFailure(FailureHandler handler);

    /**
     * Makes sure the queue of a service exists, whether or not that service runs in this process.
     * Without it, an event published before the service has ever started would have nowhere to wait.
     */
    default void declare(String topic, String group) {
    }

    /** Stops taking new messages from the broker; what is being handled finishes. Published messages wait in the queues. */
    default void pause() {
    }

    /** How many messages handlers are working on right now. */
    default int inFlight() {
        return 0;
    }

    default void close() {
    }
}
