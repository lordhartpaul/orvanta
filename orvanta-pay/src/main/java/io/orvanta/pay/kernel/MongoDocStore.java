package io.orvanta.pay.kernel;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.orvanta.core.data.Rec;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** MongoDB store. The record id is the document _id; decimals are stored as Decimal128. */
public final class MongoDocStore implements DocStore {

    private final MongoClient client;
    private final MongoDatabase db;
    private final java.util.Set<String> textIndexed = new java.util.HashSet<>();

    public MongoDocStore(String uri, String database) {
        this.client = MongoClients.create(uri);
        this.db = client.getDatabase(database);
        index(TXN, "status", "route.channel");
        index(TXN, "instructionId");
        index(TXN, "outboundId");
        index(MESSAGE, "purpose", "receivedAt");
        index(EVENT, "refId", "at");
        index(APPROVAL, "status");
        index(MESSAGE, "purpose", "msgId");
        index(MESSAGE, "reportState");
        index(TXN, "instructionId", "endToEndId");
        index(TXN, "createdAt");
        index(TXN, "status", "createdAt");
        index(TXN, "updatedAt");
        index(TXN, "endToEndId");
        index(TXN, "amount");
        index(MESSAGE, "receivedAt");
        index(TXN, "notify.state");
        index(TXN, "charges.claimStatus");
        index(TXN, "recall.status");
        index(SECURITY, "at");
        index(OUTBOUND, "createdAt");
        index(APPROVAL, "requestedAt");
        index(TXN, "status", "route.scheme");
        // the words of what people search payments by; no language rules, so nothing is left out or cut down to a stem
        db.getCollection(TXN).createIndex(Indexes.compoundIndex(Indexes.text("id"), Indexes.text("endToEndId"), Indexes.text("creditor.name"),
                Indexes.text("debtor.name"), Indexes.text("creditor.account"), Indexes.text("debtor.account"), Indexes.text("remittance")),
                new IndexOptions().background(true).defaultLanguage("none").name("payment_words"));
        textIndexed.add(TXN);
    }

    private void index(String collection, String... fields) {
        db.getCollection(collection).createIndex(Indexes.ascending(fields), new IndexOptions().background(true));
    }

    @Override
    public void insert(String collection, Rec doc) {
        db.getCollection(collection).insertOne(toDocument(doc));
    }

    @Override
    public boolean insertIfAbsent(String collection, Rec doc) {
        try {
            insert(collection, doc);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Rec get(String collection, String id) {
        Document d = db.getCollection(collection).find(Filters.eq("_id", id)).first();
        return d == null ? null : toRec(d);
    }

    @Override
    public void delete(String collection, String id) {
        db.getCollection(collection).deleteOne(Filters.eq("_id", id));
    }

    @Override
    public void save(String collection, Rec doc) {
        db.getCollection(collection).replaceOne(Filters.eq("_id", doc.str("id")), toDocument(doc), new ReplaceOptions().upsert(true));
    }

    @Override
    public boolean updateIf(String collection, String id, Rec expected, Rec changes) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(Filters.eq("_id", id));
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            conditions.add(Filters.eq(e.getKey(), e.getValue()));
        }
        List<Bson> sets = new ArrayList<>();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            sets.add(Updates.set(e.getKey(), toBson(e.getValue())));
        }
        return db.getCollection(collection).updateOne(Filters.and(conditions), Updates.combine(sets)).getMatchedCount() == 1;
    }

    @Override
    public List<Rec> find(String collection, Rec filter, String sortField, boolean descending, int limit) {
        FindIterable<Document> it = db.getCollection(collection).find(toFilter(filter));
        if (sortField != null) {
            it = it.sort(descending ? Indexes.descending(sortField(sortField)) : Indexes.ascending(sortField(sortField)));
        }
        if (limit > 0) {
            it = it.limit(limit);
        }
        List<Rec> out = new ArrayList<>();
        for (Document d : it) {
            out.add(toRec(d));
        }
        return out;
    }

    @Override
    public long count(String collection, Rec filter) {
        return db.getCollection(collection).countDocuments(toFilter(filter));
    }

    @Override
    public Page page(String collection, Query q) {
        Bson filter = toFilter(collection, q);
        FindIterable<Document> it = db.getCollection(collection).find(filter);
        if (q.sortField() != null) {
            String field = sortField(q.sortField());
            it = it.sort(q.descending() ? Indexes.descending(field) : Indexes.ascending(field));
        }
        if (q.skip() > 0) {
            it = it.skip(q.skip());
        }
        if (q.limit() > 0) {
            it = it.limit(q.limit());
        }
        List<Rec> items = new ArrayList<>();
        for (Document d : it) {
            items.add(toRec(d));
        }
        // without a condition the number of documents is known without counting them
        long total = filter instanceof Document d && d.isEmpty() ? db.getCollection(collection).estimatedDocumentCount()
                : db.getCollection(collection).countDocuments(filter);
        return new Page(items, total);
    }

    @Override
    public long countMatching(String collection, Query q) {
        return db.getCollection(collection).countDocuments(toFilter(collection, q));
    }

    @Override
    public Map<String, Long> counts(String collection, Query q, String... fields) {
        Document id = new Document();
        for (int i = 0; i < fields.length; i++) {
            int cut = fields[i].indexOf(':');
            id.put("f" + i, cut < 0 ? "$" + fields[i]
                    : new Document("$substrCP", List.of("$" + fields[i].substring(0, cut), 0, Integer.parseInt(fields[i].substring(cut + 1)))));
        }
        List<Bson> pipeline = List.of(new Document("$match", toFilter(collection, q).toBsonDocument(Document.class, client.getDatabase("admin").getCodecRegistry())),
                new Document("$group", new Document("_id", id).append("n", new Document("$sum", 1))));
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Document d : db.getCollection(collection).aggregate(pipeline)) {
            Document key = (Document) d.get("_id");
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < fields.length; i++) {
                Object value = key.get("f" + i);
                joined.append(i == 0 ? "" : "\t").append(value == null ? "" : String.valueOf(value));
            }
            out.merge(joined.toString(), ((Number) d.get("n")).longValue(), Long::sum);
        }
        return out;
    }

    /** The id of a document is also its key in the collection, and only the key has an index of its own. */
    private static String sortField(String field) {
        return "id".equals(field) ? "_id" : field;
    }

    private Bson toFilter(String collection, Query q) {
        List<Bson> conditions = new ArrayList<>();
        if (q.equals() != null && !q.equals().isEmpty()) {
            conditions.add(toFilter(q.equals()));
        }
        if (q.search() != null && !q.search().isBlank()) {
            if (textIndexed.contains(collection)) {
                // every word as a phrase of its own: all of them must occur, each as a whole word
                StringBuilder phrases = new StringBuilder();
                for (String word : q.search().trim().split("\\s+")) {
                    phrases.append(phrases.length() == 0 ? "" : " ").append('"').append(word.replace("\"", "")).append('"');
                }
                conditions.add(Filters.text(phrases.toString()));
            } else {
                // no text index on this collection: each word is looked for field by field, taken literally
                for (String word : q.search().trim().split("\\s+")) {
                    java.util.regex.Pattern whole = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(word)
                            + "(?![\\p{L}\\p{N}])", java.util.regex.Pattern.CASE_INSENSITIVE);
                    List<Bson> any = new ArrayList<>();
                    for (String field : q.searchFields()) {
                        any.add(Filters.regex(field, whole));
                    }
                    conditions.add(Filters.or(any));
                }
            }
        }
        if (q.ranges() != null) {
            for (Map.Entry<String, Object[]> range : q.ranges().entrySet()) {
                if (range.getValue()[0] != null) {
                    conditions.add(Filters.gte(range.getKey(), range.getValue()[0]));
                }
                if (range.getValue()[1] != null) {
                    conditions.add(Filters.lte(range.getKey(), range.getValue()[1]));
                }
            }
        }
        return conditions.isEmpty() ? new Document() : Filters.and(conditions);
    }

    @Override
    public long nextSequence(String name) {
        Document d = db.getCollection("orv_sequence").findOneAndUpdate(Filters.eq("_id", name), Updates.inc("value", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return ((Number) d.get("value")).longValue();
    }

    @Override
    public boolean ping() {
        try {
            db.runCommand(new Document("ping", 1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static Bson toFilter(Rec filter) {
        if (filter == null || filter.isEmpty()) {
            return new Document();
        }
        List<Bson> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            if (io.orvanta.core.data.Conditions.isCondition(e.getValue())) {
                // a range, "not" or "one of": every operator must hold
                for (Map.Entry<?, ?> op : ((Map<?, ?>) e.getValue()).entrySet()) {
                    Object v = op.getValue();
                    // the list is handed over as an Iterable: the other overload would look for the list itself as one value
                    Iterable<Object> values = v instanceof List<?> any ? new ArrayList<Object>(any) : List.of(v);
                    conditions.add(switch (String.valueOf(op.getKey())) {
                        case "gt" -> Filters.gt(e.getKey(), v);
                        case "gte" -> Filters.gte(e.getKey(), v);
                        case "lt" -> Filters.lt(e.getKey(), v);
                        case "lte" -> Filters.lte(e.getKey(), v);
                        case "ne" -> Filters.ne(e.getKey(), v);
                        default -> Filters.in(e.getKey(), values);
                    });
                }
            } else {
                conditions.add(e.getValue() instanceof List<?> any ? Filters.in(e.getKey(), any) : Filters.eq(e.getKey(), e.getValue()));
            }
        }
        return Filters.and(conditions);
    }

    private static Document toDocument(Rec doc) {
        Document d = (Document) toBson(doc);
        d.put("_id", doc.str("id"));
        return d;
    }

    private static Object toBson(Object value) {
        if (value instanceof Map<?, ?> m) {
            Document d = new Document();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                d.put(String.valueOf(e.getKey()), toBson(e.getValue()));
            }
            return d;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(toBson(o));
            }
            return out;
        }
        return value;
    }

    private static Rec toRec(Document d) {
        Rec r = (Rec) fromBson(d);
        r.remove("_id");
        return r;
    }

    private static Object fromBson(Object value) {
        if (value instanceof Map<?, ?> m) {
            Rec r = new Rec();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                r.put(String.valueOf(e.getKey()), fromBson(e.getValue()));
            }
            return r;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(fromBson(o));
            }
            return out;
        }
        if (value instanceof Decimal128 dec) {
            return dec.bigDecimalValue();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return value;
    }
}
