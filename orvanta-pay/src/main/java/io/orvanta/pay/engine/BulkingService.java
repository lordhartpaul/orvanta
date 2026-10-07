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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects routed transactions per outbound channel and currency into bulks. A bulk is closed
 * when the channel's maxTransactions is reached or its oldest transaction has waited
 * maxWaitSeconds. The channel's mapping builds the wire message for the bulk.
 */
public final class BulkingService {

    private static final Logger LOG = LoggerFactory.getLogger(BulkingService.class);

    private final Platform platform;
    private ScheduledExecutorService scheduler;

    public BulkingService(Platform platform) {
        this.platform = platform;
    }

    public void start() {
        platform.bus.subscribe(Bus.TXN_ROUTED, "bulk", m -> {
            Rec channel = platform.deployments.registry().config(String.valueOf(m.str("channel")));
            // a channel that sends at once, or a payment the customer marked HIGH: the bulk closes now
            if ("HIGH".equals(m.str("priority"))
                    || channel != null && channel.at("bulking.maxWaitSeconds") != null && Ops.num(channel.at("bulking.maxWaitSeconds")).signum() == 0) {
                sweep(false);
            }
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orv-bulking");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep(false);
            } catch (RuntimeException e) {
                LOG.error("bulking sweep failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** @param closeNow close every open bulk regardless of size and waiting time @return bulks created */
    public synchronized int sweep(boolean closeNow) {
        int created = 0;
        Registry registry = platform.deployments.registry();
        for (Rec channel : registry.configs("Channel")) {
            if (!"outbound".equals(channel.str("direction"))) {
                continue;
            }
            List<Rec> routed = platform.store.find(DocStore.TXN,
                    Rec.of("status", Status.ROUTED, "route.channel", channel.str("name")), "routedAt", false, 1000);
            // one bulk per currency, and per the values of the fields bulking.groupBy names (for example one file per instruction)
            List<?> groupBy = Ops.list(channel.at("bulking.groupBy"));
            Map<String, List<Rec>> byCurrency = new LinkedHashMap<>();
            for (Rec txn : routed) {
                StringBuilder key = new StringBuilder(String.valueOf(txn.str("currency")));
                for (Object field : groupBy) {
                    key.append('|').append(Ops.str(txn.at(Ops.str(field))));
                }
                byCurrency.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(txn);
            }
            int max = channel.at("bulking.maxTransactions") == null ? 100 : Ops.num(channel.at("bulking.maxTransactions")).intValue();
            long waitSeconds = channel.at("bulking.maxWaitSeconds") == null ? 5 : Ops.num(channel.at("bulking.maxWaitSeconds")).longValue();
            // a bulk also closes when its amount would go over bulking.maxAmount (what a clearing takes in one file)
            java.math.BigDecimal maxAmount = channel.at("bulking.maxAmount") == null ? null : Ops.num(channel.at("bulking.maxAmount"));
            for (List<Rec> group : byCurrency.values()) {
                int from = 0;
                while (from < group.size()) {
                    int to = Math.min(group.size(), from + max);
                    if (maxAmount != null) {
                        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                        int fit = from;
                        while (fit < to && (fit == from || sum.add(Ops.num(group.get(fit).get("amount"))).compareTo(maxAmount) <= 0)) {
                            sum = sum.add(Ops.num(group.get(fit).get("amount")));
                            fit++;
                        }
                        to = fit;
                    }
                    boolean urgent = group.subList(from, to).stream().anyMatch(t -> "HIGH".equals(t.str("priority")));
                    boolean full = to - from >= max || (maxAmount != null && to < group.size()) || urgent;
                    if (!full) {
                        break;
                    }
                    created += bulk(registry, channel, group.subList(from, to)) ? 1 : 0;
                    from = to;
                }
                if (from < group.size()) {
                    Instant oldest = Instant.parse(group.get(from).str("routedAt"));
                    if (closeNow || oldest.plusSeconds(waitSeconds).isBefore(Instant.now())) {
                        created += bulk(registry, channel, group.subList(from, group.size())) ? 1 : 0;
                    }
                }
            }
        }
        return created;
    }

    private boolean bulk(Registry registry, Rec channel, List<Rec> candidates) {
        DocStore store = platform.store;
        String outboundId = platform.newId("ORVOUT");
        // claim: another bulking instance may be looking at the same transactions
        List<Rec> claimed = new ArrayList<>();
        for (Rec txn : candidates) {
            if (store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.ROUTED),
                    Rec.of("status", Status.BULKED, "outboundId", outboundId, "updatedAt", Platform.now()))) {
                txn.put("status", Status.BULKED);
                txn.put("outboundId", outboundId);
                claimed.add(txn);
            }
        }
        if (claimed.isEmpty()) {
            return false;
        }
        BigDecimal total = BigDecimal.ZERO;
        List<Object> txnIds = new ArrayList<>();
        for (Rec txn : claimed) {
            total = total.add(Ops.num(txn.get("amount")));
            txnIds.add(txn.str("id"));
        }
        // the message id the receiver sees: the file's own id, or the channel's template rendered
        String msgId = channel.str("messageIdTemplate") == null ? outboundId
                : io.orvanta.core.expr.Ids.render(channel.str("messageIdTemplate"), io.orvanta.core.expr.Ids.sequenceOf(outboundId), channel.str("name"), java.time.ZonedDateTime.now());
        Rec bulk = Rec.of("id", outboundId, "msgId", msgId, "createdAt", Instant.now().toString().substring(0, 19) + "Z",
                "count", claimed.size(), "total", total, "currency", claimed.get(0).str("currency"), "channel", channel.str("name"));
        if (channel.get("properties") instanceof Map<?, ?> properties) {
            bulk.putAll(Rec.from(properties));
        }
        String payload;
        try {
            Rec mapped = registry.require(channel.str("mapping"), Mapping.class).apply(Rec.of("bulk", bulk, "txns", new ArrayList<Object>(claimed)));
            payload = Messages.write(channel.str("format"), mapped, channel.str("formatSpec") == null ? null : registry.config(channel.str("formatSpec")));
            // nothing leaves that the receiver would refuse: the message is checked like a received one
            platform.checks().requireValidOutbound(channel, payload);
        } catch (RuntimeException e) {
            String code = e instanceof MessageChecks.OutboundInvalid ? "OUTBOUND_INVALID" : "BULK_ERROR";
            for (Rec txn : claimed) {
                store.updateIf(DocStore.TXN, txn.str("id"), Rec.of("status", Status.BULKED), Rec.of("status", Status.REPAIR,
                        "reasonCode", code, "reasonText", String.valueOf(e.getMessage()), "updatedAt", Platform.now()));
                platform.event(txn.str("id"), Status.REPAIR, code + ": " + e.getMessage(), null, null);
            }
            LOG.error("building {} for {} failed", outboundId, channel.str("name"), e);
            return false;
        }
        store.insert(DocStore.OUTBOUND, Rec.of("id", outboundId, "kind", "payment", "channel", channel.str("name"), "format", channel.str("format"),
                "messageType", channel.str("messageType"), "transactionCount", claimed.size(), "totalAmount", total,
                "currency", bulk.str("currency"), "transactionIds", txnIds, "status", Status.CREATED,
                "createdAt", Platform.now(), "payload", payload));
        for (Rec txn : claimed) {
            platform.event(txn.str("id"), Status.BULKED, "bulked into " + outboundId, null, null);
        }
        platform.event(outboundId, "CREATED", claimed.size() + " transaction(s) for " + channel.str("name"), null, null);
        platform.bus.publish(Bus.OUTBOUND_CREATED, Rec.of("id", outboundId));
        return true;
    }
}
