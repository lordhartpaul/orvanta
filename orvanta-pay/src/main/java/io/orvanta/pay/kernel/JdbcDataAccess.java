package io.orvanta.pay.kernel;

import io.orvanta.core.data.Conditions;
import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;
import io.orvanta.core.json.Json;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Data sets on a table of a relational database: a DataSet model with {@code table} and {@code datasource}.
 * The data source is named in the configuration ({@code datasources.<name>.url}, {@code .username},
 * {@code .password}; the password may come from the environment as ORVANTA_DATASOURCES_<NAME>_PASSWORD) and
 * its JDBC driver is on the classpath. Rows are flat: a column per field; a nested value is stored as JSON
 * text. Table and column names are plain SQL names checked here, values always travel as parameters.
 * A connection is opened per call; a pool in front of the database is the operator's choice.
 */
public final class JdbcDataAccess {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");

    private final Config config;

    public JdbcDataAccess(Config config) {
        this.config = config;
    }

    private String url(String datasource) {
        String url = config.get("datasources." + datasource + ".url", null);
        if (url == null) {
            throw new IllegalStateException("data source '" + datasource + "' is not configured: set datasources." + datasource
                    + ".url (and username, password) in the configuration, and put its JDBC driver on the classpath");
        }
        return url;
    }

    private Connection open(String datasource) throws SQLException {
        String url = url(datasource);
        String user = config.get("datasources." + datasource + ".username", null);
        String password = config.get("datasources." + datasource + ".password", null);
        return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
    }

    private static String name(String n, String what) {
        if (n == null || !NAME.matcher(n).matches()) {
            throw new IllegalArgumentException(what + " '" + n + "' is not a plain SQL name (letters, digits and underscores)");
        }
        return n;
    }

    private static Object value(Object v) {
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return Json.write(v);
        }
        if (v instanceof BigDecimal || v instanceof Integer || v instanceof Long || v instanceof Boolean || v instanceof String) {
            return v;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return v == null ? null : String.valueOf(v);
    }

    private static String limitClause(String url, int limit) {
        if (limit <= 0) {
            return "";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        boolean limitWord = lower.startsWith("jdbc:mysql") || lower.startsWith("jdbc:mariadb") || lower.startsWith("jdbc:postgresql") || lower.startsWith("jdbc:sqlite");
        return limitWord ? " LIMIT " + limit : " FETCH FIRST " + limit + " ROWS ONLY";
    }

    public List<Rec> find(Rec def, Rec where, String sortField, boolean descending, int limit) {
        String table = name(def.str("table"), "table");
        String datasource = def.str("datasource");
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (where != null) {
            for (Map.Entry<String, Object> e : where.entrySet()) {
                String column = name(e.getKey(), "column");
                Object v = e.getValue();
                if (Conditions.isCondition(v)) {
                    for (Map.Entry<?, ?> op : ((Map<?, ?>) v).entrySet()) {
                        condition(clauses, params, column, String.valueOf(op.getKey()), op.getValue());
                    }
                } else if (v instanceof List<?>) {
                    condition(clauses, params, column, "in", v);
                } else if (v == null) {
                    // a condition whose value is missing matches nothing, as on the document store
                    clauses.add("1 = 0");
                } else {
                    clauses.add(column + " = ?");
                    params.add(value(v));
                }
            }
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table);
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        if (sortField != null) {
            sql.append(" ORDER BY ").append(name(sortField, "sort field")).append(descending ? " DESC" : " ASC");
        }
        sql.append(limitClause(url(datasource), limit));
        List<Rec> out = new ArrayList<>();
        try (Connection c = open(datasource); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Rec row = new Rec();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object v = read(rs.getObject(i));
                        if (v != null) {
                            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), v);
                        }
                    }
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading " + def.str("name") + " from " + datasource + " failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static void condition(List<String> clauses, List<Object> params, String column, String operator, Object v) {
        switch (operator) {
            case "gt" -> { clauses.add(column + " > ?"); params.add(value(v)); }
            case "gte" -> { clauses.add(column + " >= ?"); params.add(value(v)); }
            case "lt" -> { clauses.add(column + " < ?"); params.add(value(v)); }
            case "lte" -> { clauses.add(column + " <= ?"); params.add(value(v)); }
            case "ne" -> { clauses.add(column + " <> ?"); params.add(value(v)); }
            case "in" -> {
                List<?> any = Ops.list(v);
                if (any.isEmpty()) {
                    clauses.add("1 = 0");
                } else {
                    clauses.add(column + " IN (" + String.join(", ", Collections.nCopies(any.size(), "?")) + ")");
                    for (Object x : any) {
                        params.add(value(x));
                    }
                }
            }
            default -> throw new IllegalArgumentException("'" + operator + "' is not a condition (gt, gte, lt, lte, ne or in)");
        }
    }

    private static Object read(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal || v instanceof String || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor) {
            return v.toString();
        }
        if (v instanceof java.sql.Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /** @return false when onlyIfAbsent was asked and a row with the key exists */
    public boolean save(Rec def, Rec record, boolean onlyIfAbsent) {
        String table = name(def.str("table"), "table");
        String keyField = name(def.str("key"), "key field");
        String key = record.str(keyField);
        if (key == null) {
            throw new IllegalArgumentException("a record of " + def.str("name") + " needs its key field '" + keyField + "'");
        }
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> e : record.entrySet()) {
            columns.add(name(e.getKey(), "column"));
            values.add(value(e.getValue()));
        }
        try (Connection c = open(def.str("datasource"))) {
            c.setAutoCommit(false);
            try {
                boolean exists;
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE " + keyField + " = ?")) {
                    ps.setObject(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                }
                if (exists && onlyIfAbsent) {
                    c.rollback();
                    return false;
                }
                if (exists) {
                    List<String> sets = new ArrayList<>();
                    for (String column : columns) {
                        sets.add(column + " = ?");
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET " + String.join(", ", sets) + " WHERE " + keyField + " = ?")) {
                        bind(ps, values);
                        ps.setObject(values.size() + 1, key);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")")) {
                        bind(ps, values);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("writing " + def.str("name") + " to " + def.str("datasource") + " failed: " + e.getMessage(), e);
        }
    }

    public void remove(Rec def, Object key) {
        String table = name(def.str("table"), "table");
        String keyField = name(def.str("key"), "key field");
        try (Connection c = open(def.str("datasource")); PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE " + keyField + " = ?")) {
            ps.setObject(1, Ops.str(key));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("removing from " + def.str("name") + " in " + def.str("datasource") + " failed: " + e.getMessage(), e);
        }
    }
}
