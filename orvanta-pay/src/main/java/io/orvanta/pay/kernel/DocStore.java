package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;

import java.util.List;

/**
 * Document storage used by every service. Documents are records with a unique "id".
 * Filters are equality on (dotted) fields; a list value means "any of".
 * updateIf is the compare-and-set that makes status transitions safe when several
 * instances of a service consume the same events.
 */
public interface DocStore {

    String MESSAGE = "orv_message";
    String BATCH = "orv_batch";
    String TXN = "orv_transaction";
    String OUTBOUND = "orv_outbound";
    String EVENT = "orv_event";
    String DEDUPE = "orv_dedupe";
    String USER = "orv_user";
    String ROLE = "orv_role";
    String APPROVAL = "orv_approval";
    String DEPLOYMENT = "orv_deployment";
    String SETTING = "orv_setting";
    String DEAD_LETTER = "orv_deadletter";
    String STATEMENT = "orv_statement";
    String SECURITY = "orv_security";
    String SCHEMA = "orv_schema";
    /** Payment templates of the Console form. */
    String TEMPLATE = "orv_template";
    /** Keys of systems that call the API without a person signing in. */
    String API_KEY = "orv_apikey";
    /** The last run of every Schedule model, and the minutes claimed so a job runs once across processes. */
    String SCHEDULE = "orv_schedule";
    String SCHEDULE_RUN = "orv_schedule_run";

    void insert(String collection, Rec doc);

    /** @return false when a document with the same id already exists */
    boolean insertIfAbsent(String collection, Rec doc);

    Rec get(String collection, String id);

    void delete(String collection, String id);

    /** Inserts or replaces by id. */
    void save(String collection, Rec doc);

    /** Applies the changes only when the document still has the expected field values. */
    boolean updateIf(String collection, String id, Rec expected, Rec changes);

    List<Rec> find(String collection, Rec filter, String sortField, boolean descending, int limit);

    long count(String collection, Rec filter);

    /**
     * What a list page asks for: exact values (a list means any of them), search words, value ranges
     * with either end open, an order, and the part of the result wanted. Every search word must occur
     * as a whole word in one of the search fields, without regard to case: "mokoena 18250" finds the
     * payment that has both, "moko" finds nothing.
     */
    record Query(Rec equals, String search, List<String> searchFields, java.util.Map<String, Object[]> ranges,
                 String sortField, boolean descending, int skip, int limit) {
    }

    /** One page of a result and the number of documents the whole result has. */
    record Page(List<Rec> items, long total) {
    }

    Page page(String collection, Query query);

    /** How many documents a query matches; its order and paging are ignored. */
    long countMatching(String collection, Query query);

    /**
     * How many documents there are per combination of the given fields, among those the query matches
     * (its order and paging are ignored). A field written as "createdAt:10" is cut to its first 10
     * characters, which turns a time into its day. The key of the result is the values joined by a tab.
     */
    java.util.Map<String, Long> counts(String collection, Query query, String... fields);

    long nextSequence(String name);

    boolean ping();

    default void close() {
    }
}
