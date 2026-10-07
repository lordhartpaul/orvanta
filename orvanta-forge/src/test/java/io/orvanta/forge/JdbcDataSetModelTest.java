package io.orvanta.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A DataSet model on a relational table names the table and the data source; nothing else is accepted with it. */
class JdbcDataSetModelTest {

    @Test
    void aTableDataSetNeedsItsDataSourceAndNothingElse() {
        assertTrue(Forge.build(List.of(ModelSource.parse("d.yaml", "kind: DataSet\nname: data.Customers\ntable: customers\ndatasource: crm\nkey: account\n"))).ok());
        Forge.Build noSource = Forge.build(List.of(ModelSource.parse("d.yaml", "kind: DataSet\nname: data.Customers\ntable: customers\nkey: account\n")));
        assertFalse(noSource.ok());
        assertTrue(noSource.problems().get(0).message().contains("datasource"), noSource.problems().toString());
        Forge.Build both = Forge.build(List.of(ModelSource.parse("d.yaml", "kind: DataSet\nname: data.Customers\ntable: customers\ndatasource: crm\ncollection: customers\nkey: account\n")));
        assertFalse(both.ok());
        assertTrue(both.problems().get(0).message().contains("exactly one"), both.problems().toString());
        Forge.Build odd = Forge.build(List.of(ModelSource.parse("d.yaml", "kind: DataSet\nname: data.Customers\ntable: \"customers; drop\"\ndatasource: crm\nkey: account\n")));
        assertFalse(odd.ok());
        assertTrue(odd.problems().get(0).message().contains("plain SQL name"), odd.problems().toString());
    }
}
