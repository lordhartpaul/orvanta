package io.orvanta.pay;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.orvanta.core.data.Rec;
import io.orvanta.pay.kernel.DocStore;
import io.orvanta.pay.kernel.MemoryDocStore;
import io.orvanta.pay.kernel.MongoDocStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the lists of the Console ask of a store: search, ranges, order, pages and counts. The same
 * questions are put to the in-memory store and to MongoDB, and both must give the same answers.
 * The MongoDB half runs only when a server is named:
 *   mvn test -Dorvanta.it.mongo=mongodb://localhost:27017
 * It works in a database of its own, which it drops at the end.
 */
class StoreContractTest {

    @Test
    void theMemoryStoreAnswersTheQuestionsOfTheLists() {
        answers(new MemoryDocStore());
    }

    @Test
    void mongoDbAnswersTheQuestionsOfTheListsTheSameWay() {
        String uri = System.getProperty("orvanta.it.mongo");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "orvanta.it.mongo not set, skipping");
        String database = "orvanta_it_" + System.currentTimeMillis();
        MongoDocStore store = new MongoDocStore(uri, database);
        try {
            answers(store);
        } finally {
            store.close();
            try (MongoClient client = MongoClients.create(uri)) {
                client.getDatabase(database).drop();
            }
        }
    }

    private static Rec txn(int n, String endToEndId, String creditor, String account, String amount, String currency, String status,
                           String scheme, String createdAt, String remittance) {
        Rec t = Rec.of("id", "TX" + String.format("%010d", n), "endToEndId", endToEndId, "amount", new BigDecimal(amount), "currency", currency,
                "status", status, "createdAt", createdAt, "updatedAt", createdAt, "remittance", remittance,
                "debtor", Rec.of("name", "Karoo Mining Supplies (Pty) Ltd", "account", "4051122334"),
                "creditor", Rec.of("name", creditor, "account", account));
        if (scheme != null) {
            t.put("route", Rec.of("scheme", scheme));
        }
        return t;
    }

    private static DocStore.Query query(Rec equals, String search, Map<String, Object[]> ranges, String sort, boolean descending, int skip, int limit) {
        return new DocStore.Query(equals, search, List.of("id", "endToEndId", "creditor.name", "debtor.name", "creditor.account", "debtor.account", "remittance"),
                ranges, sort, descending, skip, limit);
    }

    private static List<Integer> numbers(List<Rec> docs) {
        List<Integer> out = new ArrayList<>();
        docs.forEach(d -> out.add(Integer.parseInt(d.str("id").substring(2))));
        return out;
    }

    private static Set<Integer> found(DocStore store, Rec equals, String search) {
        DocStore.Page page = store.page(DocStore.TXN, query(equals, search, null, "id", false, 0, 0));
        assertEquals(page.total(), page.items().size(), "without a limit the page is the whole result");
        assertEquals(page.total(), store.countMatching(DocStore.TXN, query(equals, search, null, null, false, 0, 0)), "the count agrees with the page");
        return new TreeSet<>(numbers(page.items()));
    }

    private static void answers(DocStore store) {
        store.insert(DocStore.TXN, txn(1, "SAL-0001", "Thandiwe Mokoena", "62011223344", "18250.00", "ZAR", "ACCEPTED", "ZA-RTC", "2026-10-01T08:00:00.000Z", "Salary October"));
        store.insert(DocStore.TXN, txn(2, "SAL-0002", "Pieter van der Merwe", "62055667788", "21300.50", "ZAR", "ACCEPTED", "ZA-RTC", "2026-10-01T09:30:00.000Z", "Salary October"));
        store.insert(DocStore.TXN, txn(3, "SAL-0003", "Zero Amount Example", "62099887766", "0.00", "ZAR", "REJECTED_BY_APPLICATION", null, "2026-10-02T07:15:00.000Z", "Nothing"));
        store.insert(DocStore.TXN, txn(4, "TRD-9", "Mokoena Trading (Pty) Ltd", "DE89370400440532013000", "1500.00", "EUR", "SENT", "SEPA-SCT", "2026-10-02T16:45:00.000Z", "Invoice 4711"));
        store.insert(DocStore.TXN, txn(5, "INV-55071", "Rheinland Pumpen GmbH", "DE02120300000000202051", "8300.40", "EUR", "ACCEPTED", "SEPA-SCT", "2026-10-03T10:00:00.000Z", "Invoice 55071"));
        store.insert(DocStore.TXN, txn(6, "USD-1", "Ünal Şahin", "000123456789", "99.99", "USD", "HELD", null, "2026-10-03T23:59:59.500Z", "Größe 42"));

        // pages, newest first by id, with the size of the whole result
        DocStore.Page first = store.page(DocStore.TXN, query(null, null, null, "id", true, 0, 4));
        assertEquals(List.of(6, 5, 4, 3), numbers(first.items()));
        assertEquals(6, first.total());
        assertEquals(List.of(2, 1), numbers(store.page(DocStore.TXN, query(null, null, null, "id", true, 4, 4)).items()));
        assertEquals(List.of(), numbers(store.page(DocStore.TXN, query(null, null, null, "id", true, 60, 4)).items()));
        assertEquals(List.of(6, 5), numbers(store.find(DocStore.TXN, null, "id", true, 2)));

        // conditions besides equality: a range, "not", and one of several values
        assertEquals(List.of(5, 2, 1), numbers(store.find(DocStore.TXN, Rec.of("amount", Rec.of("gte", new java.math.BigDecimal("8000"))), "id", true, 10)));
        assertEquals(List.of(5, 4, 1), numbers(store.find(DocStore.TXN, Rec.of("amount", Rec.of("gt", new java.math.BigDecimal("100"), "lt", new java.math.BigDecimal("20000"))), "id", true, 10)));
        assertEquals(List.of(6, 4, 3), numbers(store.find(DocStore.TXN, Rec.of("status", Rec.of("ne", "ACCEPTED")), "id", true, 10)));
        assertEquals(List.of(6, 4), numbers(store.find(DocStore.TXN, Rec.of("currency", List.of("EUR", "USD"), "status", Rec.of("in", List.of("SENT", "HELD"))), "id", true, 10)));

        // order by a number and by a time
        assertEquals(List.of(3, 6, 4, 5, 1, 2), numbers(store.page(DocStore.TXN, query(null, null, null, "amount", false, 0, 0)).items()));
        assertEquals(List.of(2, 1, 5), numbers(store.page(DocStore.TXN, query(null, null, null, "amount", true, 0, 3)).items()));
        assertEquals(List.of(6, 5, 4), numbers(store.page(DocStore.TXN, query(null, null, null, "createdAt", true, 0, 3)).items()));

        // search: whole words, every word, any field, any case, nothing read as a pattern
        assertEquals(Set.of(1, 4), found(store, null, "mokoena"));
        assertEquals(Set.of(1), found(store, null, "MOKOENA thandiwe"));
        assertEquals(Set.of(), found(store, null, "moko"));
        assertEquals(Set.of(), found(store, null, "mokoena pieter"));
        assertEquals(Set.of(1), found(store, null, "62011223344"));
        assertEquals(Set.of(1), found(store, null, "sal-0001"));
        assertEquals(Set.of(5), found(store, null, "INV-55071"));
        assertEquals(Set.of(4, 5), found(store, null, "invoice"));
        assertEquals(Set.of(6), found(store, null, "größe"));
        assertEquals(Set.of(4), found(store, null, "TX0000000004"));
        assertEquals(Set.of(), found(store, null, ".*"));
        assertEquals(Set.of(), found(store, null, "sal.*"));
        assertEquals(Set.of(1, 2, 3, 4, 5, 6), found(store, null, "karoo mining"));

        // exact values, alone and with search; a list means any of them
        assertEquals(Set.of(1, 2, 5), found(store, Rec.of("status", "ACCEPTED"), null));
        assertEquals(Set.of(1, 2, 4, 5), found(store, Rec.of("status", List.of("ACCEPTED", "SENT")), null));
        assertEquals(Set.of(4, 5), found(store, Rec.of("route.scheme", "SEPA-SCT"), null));
        assertEquals(Set.of(1), found(store, Rec.of("status", "ACCEPTED"), "mokoena"));
        assertEquals(Set.of(), found(store, Rec.of("status", "HELD"), "mokoena"));

        // ranges, with either end open
        Map<String, Object[]> secondDay = Map.of("createdAt", new Object[] {"2026-10-02T00:00:00", "2026-10-02T23:59:59.999999999Z"});
        assertEquals(List.of(3, 4), numbers(store.page(DocStore.TXN, query(null, null, secondDay, "id", false, 0, 0)).items()));
        Map<String, Object[]> lastMoment = Map.of("createdAt", new Object[] {"2026-10-03T00:00:00", "2026-10-03T23:59:59.999999999Z"});
        assertEquals(List.of(5, 6), numbers(store.page(DocStore.TXN, query(null, null, lastMoment, "id", false, 0, 0)).items()), "the last second of a day belongs to it");
        Map<String, Object[]> middle = Map.of("amount", new Object[] {new BigDecimal("1000"), new BigDecimal("20000")});
        assertEquals(List.of(1, 4, 5), numbers(store.page(DocStore.TXN, query(null, null, middle, "id", false, 0, 0)).items()));
        Map<String, Object[]> large = Map.of("amount", new Object[] {new BigDecimal("18250.00"), null});
        assertEquals(List.of(1, 2), numbers(store.page(DocStore.TXN, query(null, null, large, "id", false, 0, 0)).items()), "the ends of a range belong to it");
        assertEquals(2, store.countMatching(DocStore.TXN, query(Rec.of("status", "ACCEPTED"), null, middle, null, false, 0, 0)));

        // counts per value, and per day
        Map<String, Long> byStatus = store.counts(DocStore.TXN, query(null, null, null, null, false, 0, 0), "status");
        assertEquals(Map.of("ACCEPTED", 3L, "REJECTED_BY_APPLICATION", 1L, "SENT", 1L, "HELD", 1L), new java.util.HashMap<>(byStatus));
        Map<String, Long> byDay = store.counts(DocStore.TXN, query(null, null, null, null, false, 0, 0), "createdAt:10", "status");
        assertEquals(2L, byDay.get("2026-10-01\tACCEPTED"));
        assertEquals(1L, byDay.get("2026-10-03\tHELD"));
        assertEquals(5, byDay.size());
        assertEquals(Map.of("EUR", 2L), new java.util.HashMap<>(store.counts(DocStore.TXN, query(Rec.of("route.scheme", "SEPA-SCT"), null, null, null, false, 0, 0), "currency")));

        // a collection without a word index is searched the same way
        store.insert(DocStore.OUTBOUND, Rec.of("id", "OUT1", "channel", "channels.ZaRtcOutbound", "messageType", "pacs.008.001.08", "createdAt", "2026-10-01T08:05:00.000Z"));
        store.insert(DocStore.OUTBOUND, Rec.of("id", "OUT2", "channel", "channels.SwiftMtOutbound", "messageType", "MT103", "createdAt", "2026-10-02T08:05:00.000Z"));
        List<String> fields = List.of("id", "channel", "messageType");
        assertEquals(1, store.page(DocStore.OUTBOUND, new DocStore.Query(null, "pacs.008.001.08", fields, null, "id", true, 0, 0)).total());
        assertEquals(1, store.page(DocStore.OUTBOUND, new DocStore.Query(null, "zartcoutbound", fields, null, "id", true, 0, 0)).total());
        assertEquals(2, store.page(DocStore.OUTBOUND, new DocStore.Query(null, "channels", fields, null, "id", true, 0, 0)).total());
        assertEquals(0, store.page(DocStore.OUTBOUND, new DocStore.Query(null, "pac", fields, null, "id", true, 0, 0)).total());
        assertEquals(0, store.page(DocStore.OUTBOUND, new DocStore.Query(null, "mt103 zartcoutbound", fields, null, "id", true, 0, 0)).total());
        assertEquals("OUT2", store.page(DocStore.OUTBOUND, new DocStore.Query(null, "MT103", fields, null, "id", true, 0, 0)).items().get(0).str("id"));
    }
}
