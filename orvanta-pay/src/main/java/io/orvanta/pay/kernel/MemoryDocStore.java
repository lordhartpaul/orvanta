package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory store for tests and for trying the platform without a database. Not durable. */
public final class MemoryDocStore implements DocStore {

    private final Map<String, Map<String, Rec>> collections = new HashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();

    private Map<String, Rec> coll(String name) {
        return collections.computeIfAbsent(name, k -> new LinkedHashMap<>());
    }

    @Override
    public synchronized void insert(String collection, Rec doc) {
        if (!insertIfAbsent(collection, doc)) {
            throw new IllegalStateException("duplicate id " + doc.str("id") + " in " + collection);
        }
    }

    @Override
    public synchronized boolean insertIfAbsent(String collection, Rec doc) {
        return coll(collection).putIfAbsent(doc.str("id"), doc.copy()) == null;
    }

    @Override
    public synchronized Rec get(String collection, String id) {
        Rec doc = coll(collection).get(id);
        return doc == null ? null : doc.copy();
    }

    @Override
    public synchronized void delete(String collection, String id) {
        coll(collection).remove(id);
    }

    @Override
    public synchronized void save(String collection, Rec doc) {
        coll(collection).put(doc.str("id"), doc.copy());
    }

    @Override
    public synchronized boolean updateIf(String collection, String id, Rec expected, Rec changes) {
        Rec doc = coll(collection).get(id);
        if (doc == null || !matches(doc, expected)) {
            return false;
        }
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            doc.set(e.getKey(), Rec.deep(e.getValue()));
        }
        return true;
    }

    @Override
    public synchronized List<Rec> find(String collection, Rec filter, String sortField, boolean descending, int limit) {
        List<Rec> out = new ArrayList<>();
        for (Rec doc : coll(collection).values()) {
            if (matches(doc, filter)) {
                out.add(doc.copy());
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

    @Override
    public synchronized Page page(String collection, Query q) {
        List<Rec> matching = matching(collection, q);
        if (q.sortField() != null) {
            matching.sort((a, b) -> {
                Object x = a.at(q.sortField());
                Object y = b.at(q.sortField());
                int c = x == null ? (y == null ? 0 : -1) : y == null ? 1 : Ops.cmp(x, y);
                return q.descending() ? -c : c;
            });
        }
        List<Rec> items = new ArrayList<>();
        for (int i = Math.max(0, q.skip()); i < matching.size() && (q.limit() <= 0 || items.size() < q.limit()); i++) {
            items.add(matching.get(i).copy());
        }
        return new Page(items, matching.size());
    }

    @Override
    public synchronized long countMatching(String collection, Query q) {
        return matching(collection, q).size();
    }

    @Override
    public synchronized Map<String, Long> counts(String collection, Query q, String... fields) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Rec doc : matching(collection, q)) {
            StringBuilder key = new StringBuilder();
            for (String field : fields) {
                int cut = field.indexOf(':');
                Object value = doc.at(cut < 0 ? field : field.substring(0, cut));
                String text = value == null ? "" : String.valueOf(value);
                if (cut >= 0) {
                    text = text.substring(0, Math.min(text.length(), Integer.parseInt(field.substring(cut + 1))));
                }
                key.append(key.length() == 0 ? "" : "\t").append(text);
            }
            out.merge(key.toString(), 1L, Long::sum);
        }
        return out;
    }

    /** The stored documents a query matches, not copied. */
    private List<Rec> matching(String collection, Query q) {
        List<java.util.regex.Pattern> words = new ArrayList<>();
        if (q.search() != null) {
            for (String word : q.search().trim().split("\\s+")) {
                if (!word.isEmpty()) {
                    // a whole word: not continued by a letter or digit on either side
                    words.add(java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
                            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE));
                }
            }
        }
        List<Rec> matching = new ArrayList<>();
        for (Rec doc : coll(collection).values()) {
            if (!matches(doc, q.equals())) {
                continue;
            }
            boolean found = true;
            for (java.util.regex.Pattern word : words) {
                if (q.searchFields().stream().noneMatch(f -> doc.at(f) != null && word.matcher(String.valueOf(doc.at(f))).find())) {
                    found = false;
                    break;
                }
            }
            if (!found) {
                continue;
            }
            boolean inRange = true;
            for (Map.Entry<String, Object[]> range : (q.ranges() == null ? Map.<String, Object[]>of() : q.ranges()).entrySet()) {
                Object value = doc.at(range.getKey());
                Object from = range.getValue()[0];
                Object to = range.getValue()[1];
                if (value == null || (from != null && Ops.cmp(value, from) < 0) || (to != null && Ops.cmp(value, to) > 0)) {
                    inRange = false;
                    break;
                }
            }
            if (inRange) {
                matching.add(doc);
            }
        }
        return matching;
    }

    @Override
    public synchronized long count(String collection, Rec filter) {
        long n = 0;
        for (Rec doc : coll(collection).values()) {
            if (matches(doc, filter)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized long nextSequence(String name) {
        return sequences.merge(name, 1L, Long::sum);
    }

    @Override
    public boolean ping() {
        return true;
    }

    private static boolean matches(Rec doc, Rec filter) {
        if (filter == null) {
            return true;
        }
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            Object actual = doc.at(e.getKey());
            if (!io.orvanta.core.data.Conditions.matches(actual, e.getValue())) {
                return false;
            }
        }
        return true;
    }
}
