package io.orvanta.forge;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.flow.Elements.DataAccess;
import io.orvanta.core.flow.Registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data sets held in memory for a test run or a Studio run, seeded with given rows.
 * A run never touches stored data; what the run left behind can be read back with {@link #contents()}.
 */
public final class MemoryDataAccess implements DataAccess {

    private final Registry registry;
    private final Map<String, Map<String, Rec>> sets = new LinkedHashMap<>();

    /** @param seed data set name to list of rows, or null */
    public MemoryDataAccess(Registry registry, Rec seed) {
        this.registry = registry;
        if (seed != null) {
            for (Map.Entry<String, Object> e : seed.entrySet()) {
                for (Object row : Ops.list(e.getValue())) {
                    if (row instanceof Rec r) {
                        // a test may describe what the engine holds (a read-only view) as well as a data set's own rows
                        seed(e.getKey(), r.copy());
                    }
                }
            }
        }
    }

    private Rec definition(String dataset) {
        Rec def = registry.config(dataset);
        if (def == null || !"DataSet".equals(def.str("kind"))) {
            throw new IllegalStateException("no data set named '" + dataset + "' is deployed");
        }
        return def;
    }

    @Override
    public List<Rec> find(String dataset, Rec where, String sortField, boolean descending, int limit) {
        definition(dataset);
        List<Rec> out = new ArrayList<>();
        for (Rec row : sets.getOrDefault(dataset, Map.of()).values()) {
            boolean match = true;
            for (Map.Entry<String, Object> c : where.entrySet()) {
                match = match && io.orvanta.core.data.Conditions.matches(row.at(c.getKey()), c.getValue());
            }
            if (match) {
                out.add(row.copy());
            }
        }
        if (sortField != null) {
            out.sort((a, b) -> {
                Object x = a.at(sortField);
                Object y = b.at(sortField);
                int c = x == null ? (y == null ? 0 : -1) : y == null ? 1 : Ops.cmp(x, y);
                return descending ? -c : c;
            });
        }
        return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private void seed(String dataset, Rec record) {
        Rec def = definition(dataset);
        String key = record.str(def.str("key"));
        if (key == null) {
            throw new IllegalArgumentException("a record of " + dataset + " needs its key field '" + def.str("key") + "'");
        }
        sets.computeIfAbsent(dataset, k -> new LinkedHashMap<>()).put(key, record.copy());
    }

    @Override
    public boolean save(String dataset, Rec record, boolean onlyIfAbsent) {
        Rec def = definition(dataset);
        if (def.str("source") != null) {
            throw new IllegalStateException("data set " + dataset + " is a read-only view of engine data");
        }
        String key = record.str(def.str("key"));
        if (key == null) {
            throw new IllegalArgumentException("a record of " + dataset + " needs its key field '" + def.str("key") + "'");
        }
        Map<String, Rec> rows = sets.computeIfAbsent(dataset, k -> new LinkedHashMap<>());
        if (onlyIfAbsent && rows.containsKey(key)) {
            return false;
        }
        rows.put(key, record.copy());
        return true;
    }

    @Override
    public void remove(String dataset, Object key) {
        Rec def = definition(dataset);
        if (def.str("source") != null) {
            throw new IllegalStateException("data set " + dataset + " is a read-only view of engine data");
        }
        sets.getOrDefault(dataset, new LinkedHashMap<>()).remove(Ops.str(key));
    }

    /** Data set name to its rows after the run. */
    public Rec contents() {
        Rec out = new Rec();
        for (Map.Entry<String, Map<String, Rec>> e : sets.entrySet()) {
            out.put(e.getKey(), new ArrayList<Object>(e.getValue().values()));
        }
        return out;
    }
}
