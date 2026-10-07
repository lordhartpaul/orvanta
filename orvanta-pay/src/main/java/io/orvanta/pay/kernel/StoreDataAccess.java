package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.DataAccess;

import java.util.List;
import java.util.Map;

/**
 * Data sets on the document store. A DataSet model with a {@code collection} owns a collection
 * of its own (stored with the prefix orvd_, so models can never reach engine or security data by
 * naming a collection). A DataSet with a {@code source} is a read-only view of engine data. One with a
 * {@code table} and a {@code datasource} lives in a relational database (JdbcDataAccess).
 */
public final class StoreDataAccess implements DataAccess {

    private static final Map<String, String> SOURCES = Map.of(
            "engine.transactions", DocStore.TXN, "engine.messages", DocStore.MESSAGE,
            "engine.batches", DocStore.BATCH, "engine.outbound", DocStore.OUTBOUND);

    private final Platform platform;
    private final JdbcDataAccess jdbc;

    StoreDataAccess(Platform platform) {
        this.platform = platform;
        this.jdbc = new JdbcDataAccess(platform.config);
    }

    private Rec definition(String dataset) {
        Rec def = platform.deployments.registry().config(dataset);
        if (def == null || !"DataSet".equals(def.str("kind"))) {
            throw new IllegalStateException("no data set named '" + dataset + "' is deployed");
        }
        return def;
    }

    private static String collection(Rec def) {
        return def.str("source") != null ? SOURCES.get(def.str("source")) : "orvd_" + def.str("collection");
    }

    private static void writable(String dataset, Rec def) {
        if (def.str("source") != null) {
            throw new IllegalStateException("data set " + dataset + " is a read-only view of engine data");
        }
    }

    @Override
    public List<Rec> find(String dataset, Rec where, String sortField, boolean descending, int limit) {
        Rec def = definition(dataset);
        if (def.str("table") != null) {
            return jdbc.find(def, where, sortField, descending, limit);
        }
        List<Rec> rows = platform.store.find(collection(def), where, sortField, descending, limit);
        if (def.str("source") != null) {
            // message payloads are never handed to models through a view
            for (Rec row : rows) {
                row.remove("raw");
                row.remove("payload");
            }
        }
        return rows;
    }

    @Override
    public boolean save(String dataset, Rec record, boolean onlyIfAbsent) {
        Rec def = definition(dataset);
        writable(dataset, def);
        if (def.str("table") != null) {
            return jdbc.save(def, record, onlyIfAbsent);
        }
        String key = record.str(def.str("key"));
        if (key == null) {
            throw new IllegalArgumentException("a record of " + dataset + " needs its key field '" + def.str("key") + "'");
        }
        Rec doc = record.copy();
        doc.put("id", key);
        if (onlyIfAbsent) {
            return platform.store.insertIfAbsent(collection(def), doc);
        }
        platform.store.save(collection(def), doc);
        return true;
    }

    @Override
    public void remove(String dataset, Object key) {
        Rec def = definition(dataset);
        writable(dataset, def);
        if (def.str("table") != null) {
            jdbc.remove(def, key);
            return;
        }
        platform.store.delete(collection(def), Ops.str(key));
    }
}
