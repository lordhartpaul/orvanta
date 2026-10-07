package io.orvanta.pay.kernel;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A data set on a relational table: rows written, found by conditions, changed and removed; names checked. */
class JdbcDataAccessTest {

    private static final String URL = "jdbc:h2:mem:orvanta_jdbc_test;DB_CLOSE_DELAY=-1";
    private static final Rec DEF = Rec.of("kind", "DataSet", "name", "data.Customers", "table", "customers", "datasource", "crm", "key", "account");
    private static JdbcDataAccess jdbc;

    @BeforeAll
    static void table() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS customers");
            s.execute("CREATE TABLE customers (account VARCHAR(34) PRIMARY KEY, name VARCHAR(140), segment VARCHAR(20), balance DECIMAL(18,2), active BOOLEAN, tags VARCHAR(400))");
        }
        Rec values = new Rec();
        values.set("datasources.crm.url", URL);
        values.set("datasources.crm.username", "sa");
        values.set("datasources.crm.password", "");
        jdbc = new JdbcDataAccess(Config.of(values));
    }

    @Test
    void rowsAreWrittenFoundByConditionsChangedAndRemovedOnATable() {
        assertTrue(jdbc.save(DEF, Rec.of("account", "4051122334", "name", "Karoo Mining Supplies", "segment", "CORP", "balance", 1500.50, "active", true), false));
        assertTrue(jdbc.save(DEF, Rec.of("account", "7700112233", "name", "Thandiwe Mokoena", "segment", "RETAIL", "balance", 20, "active", true,
                "tags", List.of("vip", "new")), false));
        assertTrue(jdbc.save(DEF, Rec.of("account", "9999999999", "name", "Closed One", "segment", "RETAIL", "balance", 0, "active", false), false));
        // the same key again: not absent, so refused when asked; replaced otherwise
        assertFalse(jdbc.save(DEF, Rec.of("account", "9999999999", "name", "Someone Else"), true));
        assertTrue(jdbc.save(DEF, Rec.of("account", "9999999999", "name", "Closed One", "segment", "DORMANT", "balance", 0, "active", false), false));

        List<Rec> one = jdbc.find(DEF, Rec.of("account", "4051122334"), null, false, 0);
        assertEquals(1, one.size());
        assertEquals("Karoo Mining Supplies", one.get(0).str("name"));
        assertEquals(0, Ops.num(one.get(0).get("balance")).compareTo(new java.math.BigDecimal("1500.50")));
        assertEquals(Boolean.TRUE, one.get(0).get("active"));
        // a nested value went in as JSON text
        assertEquals("[\"vip\",\"new\"]", jdbc.find(DEF, Rec.of("account", "7700112233"), null, false, 0).get(0).str("tags"));

        List<Rec> retail = jdbc.find(DEF, Rec.of("segment", Rec.of("in", List.of("RETAIL", "DORMANT"))), "balance", true, 0);
        assertEquals(2, retail.size());
        assertEquals("7700112233", retail.get(0).str("account"));
        assertEquals(1, jdbc.find(DEF, Rec.of("balance", Rec.of("gte", 100)), null, false, 0).size());
        assertEquals(2, jdbc.find(DEF, Rec.of("account", Rec.of("ne", "9999999999")), "account", false, 5).size());
        assertEquals(1, jdbc.find(DEF, new Rec(), "account", false, 1).size());
        // a condition without a value matches nothing, as on the document store
        Rec missing = new Rec();
        missing.put("segment", null);
        assertEquals(0, jdbc.find(DEF, missing, null, false, 0).size());

        jdbc.remove(DEF, "9999999999");
        assertEquals(2, jdbc.find(DEF, new Rec(), null, false, 0).size());
    }

    @Test
    void namesThatAreNotPlainSqlNamesAndUnknownDataSourcesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> jdbc.find(DEF, Rec.of("name; DROP TABLE customers", "x"), null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> jdbc.find(DEF, new Rec(), "balance DESC; --", false, 0));
        Rec other = Rec.of("kind", "DataSet", "name", "data.Elsewhere", "table", "customers", "datasource", "nowhere", "key", "account");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> jdbc.find(other, new Rec(), null, false, 0));
        assertTrue(missing.getMessage().contains("datasources.nowhere.url"), missing.getMessage());
    }
}
