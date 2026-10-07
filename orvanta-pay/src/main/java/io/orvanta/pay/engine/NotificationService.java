package io.orvanta.pay.engine;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.Mapping;
import io.orvanta.core.flow.Registry;
import io.orvanta.core.format.Messages;
import io.orvanta.pay.kernel.Bus;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tells customers what was booked on their accounts: a credit for a payment received, a debit for a
 * direct debit collection, and the reversal of either when the payment is sent back afterwards.
 * <p>
 * A transaction carries what is still to be told in {@code notify.pending}. A sweep collects those
 * transactions, puts the entries of one account into one notification (camt.054 with the sample
 * models), built by the mapping of the notification channel that the inbound channel names, and
 * hands it to dispatch like any outbound file. Nothing is told twice: a transaction is claimed
 * before its entries are written, and what was told is recorded on it.
 */
public final class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";
    public static final String CREDIT_REVERSAL = "CREDIT_REVERSAL";
    public static final String DEBIT_REVERSAL = "DEBIT_REVERSAL";

    private final Platform platform;
    private final int intervalSeconds;
    private ScheduledExecutorService scheduler;

    public NotificationService(Platform platform) {
        this.platform = platform;
        this.intervalSeconds = Math.max(1, platform.config.getInt("notify.intervalSeconds", 3));
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-notify");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                LOG.error("notification sweep failed", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Notes on a transaction that is about to be stored that its booking has to be told to the customer.
     * Does nothing when the channel the payment came in on names no notification channel.
     */
    static void booked(Registry registry, Rec txn, String kind) {
        Rec inbound = registry.config(String.valueOf(txn.str("channelIn")));
        String channel = inbound == null ? null : inbound.str("notificationChannel");
        if (channel == null) {
            return;
        }
        Rec notify = txn.rec("notify");
        List<Object> pending = new ArrayList<>(Ops.list(notify.get("pending")));
        pending.add(kind);
        notify.put("pending", pending);
        notify.put("state", "OPEN");
        notify.put("channel", channel);
        notify.put("version", notify.get("version") == null ? 1 : Ops.num(notify.get("version")).intValue() + 1);
        txn.put("notify", notify);
    }

    /** Notes on a stored transaction that a booking the customer was told about, or will be, was taken back. */
    static void reversed(Platform platform, String txnId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Rec txn = platform.store.get(DocStore.TXN, txnId);
            if (txn == null || !(txn.get("notify") instanceof Rec notify) || (txn.get("creditedAt") == null && txn.get("debitedAt") == null)) {
                return;         // never booked, or nobody to tell
            }
            int version = notify.get("version") == null ? 0 : Ops.num(notify.get("version")).intValue();
            List<Object> pending = new ArrayList<>(Ops.list(notify.get("pending")));
            pending.add(txn.get("debitedAt") != null ? DEBIT_REVERSAL : CREDIT_REVERSAL);
            Rec expected = notify.get("version") == null ? new Rec() : Rec.of("notify.version", notify.get("version"));
            // while a notification is being written the state is left alone; the writer sees the longer list when it records what it told
            String state = "SENDING".equals(notify.str("state")) || "RECORDING".equals(notify.str("state")) ? notify.str("state") : "OPEN";
            if (platform.store.updateIf(DocStore.TXN, txnId, expected,
                    Rec.of("notify.pending", pending, "notify.state", state, "notify.version", version + 1))) {
                return;
            }
        }
        LOG.warn("the reversal of {} could not be noted for the customer notification", txnId);
    }

    /** @return number of notifications created */
    public synchronized int sweep() {
        DocStore store = platform.store;
        // the same round also sends what other banks owe us for payments received with charges OUR
        try {
            ChargeClaims.sweep(platform);
        } catch (RuntimeException e) {
            LOG.error("charge claim sweep failed", e);
        }
        try {
            AccountReports.daily(platform);
        } catch (RuntimeException e) {
            LOG.error("daily account reports failed", e);
        }
        recoverInterrupted();
        Map<String, List<Rec>> groups = new LinkedHashMap<>();
        for (Rec txn : store.find(DocStore.TXN, Rec.of("notify.state", "OPEN"), "id", false, 500)) {
            String account = accountOf(txn);
            if (account == null || txn.str("notify.channel") == null) {
                store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("notify.state", "OPEN"), Rec.of("notify.state", "FAILED", "notify.problem", "no account or channel"));
                continue;
            }
            groups.computeIfAbsent(txn.str("notify.channel") + "\t" + account, k -> new ArrayList<>()).add(txn);
        }
        int created = 0;
        for (Map.Entry<String, List<Rec>> group : groups.entrySet()) {
            String[] key = group.getKey().split("\t", 2);
            // what the customer asked for: no notifications at all, or another channel than the usual one
            Rec preference = preference(key[1]);
            if (preference != null && "NONE".equals(preference.str("notifications"))) {
                for (Rec txn : group.getValue()) {
                    if (store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("notify.state", "OPEN", "notify.version", txn.at("notify.version")),
                            Rec.of("notify.state", "SKIPPED", "notify.pending", new ArrayList<>()))) {
                        platform.event(txn.str("id"), "NOT_NOTIFIED", "the customer does not want notifications for account " + key[1], null, null);
                    }
                }
                continue;
            }
            String channel = preference != null && preference.str("channel") != null ? preference.str("channel") : key[0];
            if (notify(channel, key[1], group.getValue())) {
                created++;
            }
        }
        return created;
    }

    private boolean notify(String channelName, String account, List<Rec> txns) {
        DocStore store = platform.store;
        Registry registry = platform.deployments.registry();
        Rec channel = registry.config(channelName);
        String outboundId = platform.newId("ORVOUT");
        // claim: with several instances only one tells about a transaction, and only what was pending when it claimed
        List<Rec> claimed = new ArrayList<>();
        for (Rec txn : txns) {
            if (store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("notify.state", "OPEN", "notify.version", txn.at("notify.version")),
                    Rec.of("notify.state", "SENDING", "notify.claimedBy", outboundId, "notify.claimedAt", Platform.now()))) {
                claimed.add(txn);
            }
        }
        if (claimed.isEmpty()) {
            return false;
        }
        try {
            if (channel == null || !"outbound".equals(channel.str("direction"))) {
                throw new IllegalStateException("'" + channelName + "' is not an outbound channel");
            }
            List<Object> entries = new ArrayList<>();
            List<Object> ids = new ArrayList<>();
            String owner = null;
            for (Rec txn : claimed) {
                ids.add(txn.str("id"));
                boolean collection = Incoming.DEBIT_TYPE.equals(txn.str("paymentType"));
                owner = collection ? txn.str("debtor.name") : txn.str("creditor.name");
                for (Object kind : Ops.list(txn.at("notify.pending"))) {
                    boolean reversal = String.valueOf(kind).endsWith("_REVERSAL");
                    boolean credit = CREDIT.equals(kind) || DEBIT_REVERSAL.equals(kind);
                    Rec entry = Rec.of("kind", kind, "creditDebit", credit ? "CRDT" : "DBIT", "reversal", reversal, "collection", collection, "txn", txn,
                            "reference", txn.str("id") + (reversal ? "-R" : ""),
                            "bookedOn", String.valueOf(reversal ? txn.str("updatedAt") : collection ? txn.str("debitedAt") : txn.str("creditedAt")).substring(0, 10));
                    entries.add(entry);
                }
            }
            Rec notification = Rec.of("id", outboundId, "createdAt", Platform.now().substring(0, 19) + "Z", "account", account, "owner", owner,
                    "count", entries.size());
            if (channel.get("properties") instanceof Map<?, ?> properties) {
                notification.putAll(Rec.from(properties));
            }
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("notification", notification, "entries", entries));
            String payload = Messages.write(channel.str("format"), mapped);
            platform.checks().requireValidOutbound(channel, payload);
            store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "notification", "channel", channelName, "format", channel.str("format"),
                    "messageType", channel.str("messageType"), "account", account, "transactionCount", ids.size(), "transactionIds", ids,
                    "status", Status.CREATED, "createdAt", Platform.now(), "payload", payload));
            platform.event(outboundId, "CREATED", entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies") + " for account " + account, null, null);
            platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        } catch (RuntimeException e) {
            LOG.error("notification for account {} on {} could not be built", account, channelName, e);
            for (Rec txn : claimed) {
                int failures = txn.at("notify.failures") == null ? 1 : Ops.num(txn.at("notify.failures")).intValue() + 1;
                // tried again at the next sweeps; after five failures it is left for a person, visible on the payment
                store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("notify.claimedBy", outboundId), Rec.of("notify.state", failures >= 5 ? "FAILED" : "OPEN",
                        "notify.failures", failures, "notify.problem", String.valueOf(e.getMessage())));
                if (failures >= 5) {
                    platform.event(txn.str("id"), "NOTIFICATION_FAILED", String.valueOf(e.getMessage()), null, null);
                }
            }
            return false;
        }
        for (Rec txn : claimed) {
            told(txn.str("id"), outboundId, Ops.list(txn.at("notify.pending")));
        }
        return true;
    }

    /** Records what was told. Something may have been added to the pending list meanwhile; that stays to be told. */
    private void told(String txnId, String outboundId, List<?> kinds) {
        DocStore store = platform.store;
        for (int attempt = 0; attempt < 5; attempt++) {
            Rec txn = store.get(DocStore.TXN, txnId);
            Rec notify = txn.rec("notify");
            List<Object> pending = new ArrayList<>(Ops.list(notify.get("pending")));
            for (Object kind : kinds) {
                pending.remove(kind);
            }
            List<Object> sent = new ArrayList<>(Ops.list(notify.get("sent")));
            for (Object kind : kinds) {
                sent.add(Rec.of("kind", kind, "outboundId", outboundId, "at", Platform.now()));
            }
            if (store.updateIf(DocStore.TXN, txnId, Rec.of("notify.version", notify.get("version")),
                    Rec.of("notify.pending", pending, "notify.sent", sent, "notify.state", pending.isEmpty() ? "SENT" : "OPEN",
                            "notify.version", Ops.num(notify.get("version")).intValue() + 1, "notify.failures", 0))) {
                platform.event(txnId, "NOTIFIED", "the customer was told in " + outboundId + ": " + kinds, null, null);
                return;
            }
        }
        LOG.warn("what was told about {} in {} could not be recorded", txnId, outboundId);
    }

    /** A process that died between claiming and recording leaves transactions in SENDING; after a minute they are settled. */
    private void recoverInterrupted() {
        DocStore store = platform.store;
        String before = Instant.now().minusSeconds(60).toString();
        for (Rec txn : store.find(DocStore.TXN, Rec.of("notify.state", "SENDING"), "id", false, 200)) {
            if (String.valueOf(txn.str("notify.claimedAt")).compareTo(before) > 0) {
                continue;
            }
            String outboundId = txn.str("notify.claimedBy");
            if (outboundId != null && store.get(DocStore.OUTBOUND, outboundId) != null) {
                // the notification exists, so the customer is told; only the record was missing
                if (store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("notify.state", "SENDING", "notify.claimedBy", outboundId), Rec.of("notify.state", "RECORDING"))) {
                    told(txn.str("id"), outboundId, Ops.list(txn.at("notify.pending")));
                }
            } else {
                store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("notify.state", "SENDING"), Rec.of("notify.state", "OPEN"));
            }
        }
    }

    /** The row a customer has in the data set of notification preferences, or null. A deployment without that data set has none. */
    private Rec preference(String account) {
        String dataset = platform.config.get("notify.preferences", "data.NotificationPreferences");
        try {
            List<Rec> rows = platform.data.find(dataset, Rec.of("account", account), null, false, 1);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The account of our customer: the creditor of a payment received, the debtor of a collection. */
    private static String accountOf(Rec txn) {
        return Incoming.DEBIT_TYPE.equals(txn.str("paymentType")) ? txn.str("debtor.account") : txn.str("creditor.account");
    }
}
