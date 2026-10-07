package io.orvanta.pay.transport;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IBM MQ for channels: a queue messages arrive on, and a queue outbound files are put on. The
 * connection is a client connection to a queue manager over a server-connection channel.
 *
 * <pre>
 *   type: ibmmq
 *   host: 10.50.1.104
 *   port: 1600
 *   channel: SBG609SN.CH                 # the MQ server-connection channel (not an Orvanta channel)
 *   queueManager: CMQPSB6093XDQM
 *   queue: SBGQ1DEVH2H
 *   username: ${env.SBG_MQ_USER}
 *   password: ${env.SBG_MQ_PASSWORD}
 *   enabled: ${env.ORVANTA_SBG_MQ_ENABLED:-false}   # a transport or destination that is off is kept, not used
 *   waitSeconds: 5                        # inbound: how long one get waits for a message
 *   ccsid: 1208                           # outbound: the character set the message is put in (UTF-8)
 * </pre>
 *
 * Inbound, a message is taken under syncpoint and committed only after it is stored here: a crash
 * before that leaves it on the queue. Outbound, a put is committed before the file counts as sent.
 * A queue manager that cannot be reached is tried again every 15 seconds; nothing else stops.
 */
public final class IbmMqLinks {

    private static final Logger LOG = LoggerFactory.getLogger(IbmMqLinks.class);

    /** The settings of a model as the IBM client wants them. The password never appears in logs: it is only put in this table. */
    static Hashtable<String, Object> properties(Rec settings) {
        Hashtable<String, Object> props = new Hashtable<>();
        props.put(CMQC.HOST_NAME_PROPERTY, String.valueOf(settings.str("host")));
        props.put(CMQC.PORT_PROPERTY, settings.get("port") == null ? 1414 : Ops.num(settings.get("port")).intValue());
        props.put(CMQC.CHANNEL_PROPERTY, String.valueOf(settings.str("channel")));
        props.put(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);
        if (settings.str("username") != null && !settings.str("username").isBlank()) {
            props.put(CMQC.USER_ID_PROPERTY, settings.str("username"));
            props.put(CMQC.PASSWORD_PROPERTY, settings.str("password") == null ? "" : settings.str("password"));
            props.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, Boolean.TRUE);
        }
        if (settings.str("cipherSuite") != null) {
            props.put(CMQC.SSL_CIPHER_SUITE_PROPERTY, settings.str("cipherSuite"));
        }
        return props;
    }

    /** Connects, looks at the queue and disconnects: what a check of the settings needs. @return queue manager, queue and its current depth */
    public static Rec probe(Rec settings) throws MQException {
        MQQueueManager manager = new MQQueueManager(String.valueOf(settings.str("queueManager")), properties(settings));
        try {
            MQQueue queue = manager.accessQueue(String.valueOf(settings.str("queue")), CMQC.MQOO_INQUIRE | CMQC.MQOO_FAIL_IF_QUIESCING);
            try {
                return Rec.of("connected", true, "queueManager", settings.str("queueManager"), "queue", settings.str("queue"),
                        "depth", queue.getCurrentDepth(), "maxDepth", queue.getMaximumDepth(), "host", settings.str("host"), "port", settings.get("port"));
            } finally {
                queue.close();
            }
        } finally {
            manager.disconnect();
        }
    }

    /** Puts one message and commits. @return the message id the queue manager gave, in hex */
    public static String send(Rec destination, String payload, Map<String, String> headers) throws MQException, IOException {
        MQQueueManager manager = new MQQueueManager(String.valueOf(destination.str("queueManager")), properties(destination));
        try {
            MQQueue queue = manager.accessQueue(String.valueOf(destination.str("queue")), CMQC.MQOO_OUTPUT | CMQC.MQOO_FAIL_IF_QUIESCING);
            try {
                MQMessage message = new MQMessage();
                message.format = CMQC.MQFMT_STRING;
                message.characterSet = destination.get("ccsid") == null ? 1208 : Ops.num(destination.get("ccsid")).intValue();
                message.persistence = CMQC.MQPER_PERSISTENT;
                if (headers.get("messageId") != null) {
                    // our file id travels as the correlation id, so the other side can refer to it
                    byte[] correlation = new byte[24];
                    byte[] id = headers.get("messageId").getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(id, 0, correlation, 0, Math.min(id.length, 24));
                    message.correlationId = correlation;
                }
                if (headers.get("messageType") != null) {
                    message.applicationIdData = headers.get("messageType").length() > 32 ? headers.get("messageType").substring(0, 32) : headers.get("messageType");
                }
                message.writeString(payload);
                MQPutMessageOptions options = new MQPutMessageOptions();
                options.options = CMQC.MQPMO_SYNCPOINT | CMQC.MQPMO_FAIL_IF_QUIESCING | CMQC.MQPMO_NEW_MSG_ID;
                queue.put(message, options);
                manager.commit();
                return hex(message.messageId);
            } finally {
                queue.close();
            }
        } finally {
            manager.disconnect();
        }
    }

    /**
     * Reads a queue on a thread of its own until stopped; each message is handed over and committed when
     * the handler returns, backed out when it throws. @return what stops the reading
     */
    public static Runnable listen(Rec transport, BiConsumer<String, String> handler) {
        AtomicBoolean stopped = new AtomicBoolean();
        int wait = transport.get("waitSeconds") == null ? 5 : Math.max(1, Ops.num(transport.get("waitSeconds")).intValue());
        String where = transport.str("queueManager") + "/" + transport.str("queue") + "@" + transport.str("host") + ":" + transport.get("port");
        Thread thread = new Thread(() -> {
            while (!stopped.get()) {
                MQQueueManager manager = null;
                MQQueue queue = null;
                try {
                    manager = new MQQueueManager(String.valueOf(transport.str("queueManager")), properties(transport));
                    queue = manager.accessQueue(String.valueOf(transport.str("queue")), CMQC.MQOO_INPUT_AS_Q_DEF | CMQC.MQOO_FAIL_IF_QUIESCING);
                    LOG.info("IBM MQ: reading {}", where);
                    while (!stopped.get()) {
                        MQMessage message = new MQMessage();
                        MQGetMessageOptions options = new MQGetMessageOptions();
                        options.options = CMQC.MQGMO_WAIT | CMQC.MQGMO_SYNCPOINT | CMQC.MQGMO_FAIL_IF_QUIESCING | CMQC.MQGMO_CONVERT;
                        options.waitInterval = wait * 1000;
                        try {
                            queue.get(message, options);
                        } catch (MQException e) {
                            if (e.reasonCode == CMQC.MQRC_NO_MSG_AVAILABLE) {
                                continue;
                            }
                            throw e;
                        }
                        String text = message.readStringOfByteLength(message.getMessageLength());
                        try {
                            handler.accept("mq-" + hex(message.messageId), text);
                            manager.commit();
                        } catch (RuntimeException e) {
                            // not stored: the message goes back on the queue and is read again after a pause
                            LOG.error("message from {} could not be stored and goes back on the queue", where, e);
                            manager.backout();
                            Thread.sleep(2000);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (!stopped.get()) {
                        LOG.warn("IBM MQ {}: {}; trying again in 15 seconds", where, e.toString());
                        try {
                            Thread.sleep(15_000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } finally {
                    close(queue, manager);
                }
            }
        }, "orv-ibmmq-" + transport.str("queue"));
        thread.setDaemon(true);
        thread.start();
        return () -> {
            stopped.set(true);
            thread.interrupt();
        };
    }

    private static void close(MQQueue queue, MQQueueManager manager) {
        try {
            if (queue != null) {
                queue.close();
            }
        } catch (MQException ignored) {
            // the connection is dropped next either way
        }
        try {
            if (manager != null) {
                manager.disconnect();
            }
        } catch (MQException ignored) {
            // nothing more to do with it
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes == null ? new byte[0] : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
