package io.orvanta.core.format;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records of fixed width or delimited text, described by a Format model, read into a record and written
 * from one. A file is lines; every line is a record of one of the kinds the model lists, told apart by the
 * record type found at a position (fixed) or in a column (delimited). Reading gives
 * {@code {format, records: [{record: <kind name>, <field>: <value>, ...}], <kind name>: {...} or [...]}}:
 * a kind that repeats is a list under its name, one that does not is a single record. Writing takes the
 * same shape back ({@code records} in order, or the kinds by name) and makes the lines.
 *
 * <pre>
 * kind: Format
 * name: formats.PaymentsFixed
 * type: fixed                       # fixed | delimited
 * recordType: {start: 1, length: 1} # fixed: 1-based position and length; delimited: {index: 0}
 * delimiter: ","                    # delimited only; quote: '"' optional
 * records:
 *   - type: "H"
 *     name: header
 *     fields:
 *       - {name: fileRef, start: 2, length: 16}
 *       - {name: created, start: 18, length: 8}
 *   - type: "D"
 *     name: payment
 *     repeats: true
 *     fields:
 *       - {name: reference, start: 2, length: 16}
 *       - {name: amount, start: 18, length: 12, kind: amount, decimals: 2}   # digits with implied decimals
 *       - {name: account, start: 30, length: 20, pad: zero}                 # zero-filled on the left when written
 * </pre>
 *
 * Field kinds: text (default: trimmed when read, space-padded on the right when written), amount (digits with
 * the given implied decimals, zero-padded on the left) and number (digits, zero-padded on the left).
 */
public final class FlatFile {
    private final String name;
    private final boolean fixed;
    private final String delimiter;
    private final String quote;
    private final Rec typeAt;
    private final List<Rec> records;
    private final Map<String, Rec> byType = new LinkedHashMap<>();

    private FlatFile(Rec def) {
        this.name = def.str("name");
        this.fixed = !"delimited".equals(def.str("type"));
        this.delimiter = def.str("delimiter") == null ? "," : def.str("delimiter");
        this.quote = def.str("quote");
        this.typeAt = def.get("recordType") instanceof Map<?, ?> m ? Rec.from(m) : null;
        this.records = new ArrayList<>();
        for (Object r : Ops.list(def.get("records"))) {
            if (r instanceof Rec record) {
                records.add(record);
                byType.put(String.valueOf(record.str("type")), record);
            }
        }
    }

    /** Compiles and checks a Format definition; every problem names the place. */
    public static FlatFile compile(Rec def) {
        String type = def.str("type");
        if (type == null || !(type.equals("fixed") || type.equals("delimited"))) {
            throw new IllegalArgumentException("type: must be fixed or delimited");
        }
        if (!(def.get("recordType") instanceof Map<?, ?> rt)) {
            throw new IllegalArgumentException("recordType: needed, {start, length} for fixed or {index} for delimited");
        }
        Rec typeAt = Rec.from(rt);
        if (type.equals("fixed") && (number(typeAt, "start") < 1 || number(typeAt, "length") < 1)) {
            throw new IllegalArgumentException("recordType: start and length of one or more");
        }
        if (type.equals("delimited") && number(typeAt, "index") < 0) {
            throw new IllegalArgumentException("recordType: index of zero or more");
        }
        List<?> records = Ops.list(def.get("records"));
        if (records.isEmpty()) {
            throw new IllegalArgumentException("records: at least one record kind");
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> types = new java.util.HashSet<>();
        int n = 0;
        for (Object r : records) {
            String at = "records[" + n++ + "]";
            if (!(r instanceof Rec record) || record.str("type") == null || record.str("name") == null) {
                throw new IllegalArgumentException(at + ": a record kind has 'type' (the code in the line) and 'name'");
            }
            if (!types.add(record.str("type")) || !names.add(record.str("name"))) {
                throw new IllegalArgumentException(at + ": type and name must be unique among the record kinds");
            }
            if (!record.str("name").matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(at + ": the name is a word (letters, digits, underscore)");
            }
            List<?> fields = Ops.list(record.get("fields"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException(at + ": at least one field");
            }
            int f = 0;
            for (Object o : fields) {
                String where = at + ".fields[" + f++ + "]";
                if (!(o instanceof Rec field) || field.str("name") == null) {
                    throw new IllegalArgumentException(where + ": a field has a name");
                }
                if (type.equals("fixed") && (number(field, "start") < 1 || number(field, "length") < 1)) {
                    throw new IllegalArgumentException(where + ": start and length of one or more");
                }
                if (type.equals("delimited") && number(field, "index") < 0) {
                    throw new IllegalArgumentException(where + ": index of zero or more");
                }
                String kind = field.str("kind");
                if (kind != null && !List.of("text", "amount", "number").contains(kind)) {
                    throw new IllegalArgumentException(where + ": kind is text, amount or number");
                }
            }
        }
        return new FlatFile(def);
    }

    private static int number(Rec rec, String key) {
        return rec.get(key) == null ? -1 : Ops.num(rec.get(key)).intValue();
    }

    public String name() {
        return name;
    }

    /** Reads the lines of a file into records; a line whose record type the model does not know is an error that names the line. */
    public Rec parse(String text) {
        Rec out = new Rec();
        out.put("format", name);
        List<Object> all = new ArrayList<>();
        int lineNumber = 0;
        for (String line : text.split("\\r?\\n")) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = fixed ? null : split(line);
            String type = fixed ? slice(line, number(typeAt, "start"), number(typeAt, "length")).trim() : cell(cells, number(typeAt, "index"));
            Rec kind = byType.get(type);
            if (kind == null) {
                throw new IllegalArgumentException("line " + lineNumber + ": record type '" + type + "' is not one of " + byType.keySet() + " in " + name);
            }
            Rec record = new Rec();
            record.put("record", kind.str("name"));
            for (Object o : Ops.list(kind.get("fields"))) {
                Rec field = (Rec) o;
                String rawValue = fixed ? slice(line, number(field, "start"), number(field, "length")) : cell(cells, number(field, "index"));
                record.put(field.str("name"), value(field, rawValue, lineNumber));
            }
            all.add(record);
            if (Boolean.TRUE.equals(kind.get("repeats"))) {
                Object list = out.get(kind.str("name"));
                if (!(list instanceof List<?>)) {
                    list = new ArrayList<Object>();
                    out.put(kind.str("name"), list);
                }
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) list;
                l.add(record);
            } else {
                out.put(kind.str("name"), record);
            }
        }
        out.put("records", all);
        return out;
    }

    private static Object value(Rec field, String raw, int lineNumber) {
        String kind = field.str("kind") == null ? "text" : field.str("kind");
        String s = raw == null ? "" : raw.trim();
        if (kind.equals("text")) {
            return s.isEmpty() ? null : s;
        }
        if (s.isEmpty()) {
            return null;
        }
        if (!s.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("line " + lineNumber + ": field " + field.str("name") + " is not digits: '" + s + "'");
        }
        if (kind.equals("number")) {
            return new java.math.BigDecimal(s).toBigInteger();
        }
        int decimals = field.get("decimals") == null ? 2 : Ops.num(field.get("decimals")).intValue();
        return new java.math.BigDecimal(new java.math.BigInteger(s), decimals);
    }

    private static String slice(String line, int start, int length) {
        int from = Math.min(Math.max(start - 1, 0), line.length());
        int to = Math.min(from + length, line.length());
        return line.substring(from, to);
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    private List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != null && !quote.isEmpty() && ch == quote.charAt(0)) {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == ch) {
                    cell.append(ch);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && line.startsWith(delimiter, i)) {
                out.add(cell.toString());
                cell.setLength(0);
                i += delimiter.length() - 1;
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out;
    }

    /** Writes records as lines: 'records' in their order, or the record kinds by name in the order the model lists them. */
    public String write(Rec data) {
        List<Rec> ordered = new ArrayList<>();
        if (data.get("records") instanceof List<?> given) {
            for (Object o : given) {
                if (o instanceof Rec r) {
                    ordered.add(r);
                }
            }
        } else {
            for (Rec kind : records) {
                Object v = data.get(kind.str("name"));
                if (v instanceof List<?> l) {
                    for (Object o : l) {
                        if (o instanceof Rec r) {
                            Rec copy = r.copy();
                            copy.put("record", kind.str("name"));
                            ordered.add(copy);
                        }
                    }
                } else if (v instanceof Rec r) {
                    Rec copy = r.copy();
                    copy.put("record", kind.str("name"));
                    ordered.add(copy);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Rec record : ordered) {
            Rec kind = null;
            for (Rec k : records) {
                if (k.str("name").equals(record.str("record"))) {
                    kind = k;
                }
            }
            if (kind == null) {
                throw new IllegalArgumentException("record kind '" + record.str("record") + "' is not one of the kinds of " + name);
            }
            sb.append(fixed ? fixedLine(kind, record) : delimitedLine(kind, record)).append("\r\n");
        }
        return sb.toString();
    }

    private String fixedLine(Rec kind, Rec record) {
        int width = number(typeAt, "start") - 1 + number(typeAt, "length");
        for (Object o : Ops.list(kind.get("fields"))) {
            width = Math.max(width, number((Rec) o, "start") - 1 + number((Rec) o, "length"));
        }
        char[] line = new char[width];
        java.util.Arrays.fill(line, ' ');
        place(line, number(typeAt, "start"), number(typeAt, "length"), kind.str("type"), false);
        for (Object o : Ops.list(kind.get("fields"))) {
            Rec field = (Rec) o;
            String text = text(field, record.get(field.str("name")));
            boolean zeros = "zero".equals(field.str("pad")) || (field.str("kind") != null && !field.str("kind").equals("text"));
            place(line, number(field, "start"), number(field, "length"), text, zeros);
        }
        return new String(line);
    }

    private static void place(char[] line, int start, int length, String text, boolean zerosOnTheLeft) {
        String s = text == null ? "" : text;
        if (s.length() > length) {
            throw new IllegalArgumentException("'" + s + "' does not fit in " + length + " character(s)");
        }
        String padded = zerosOnTheLeft ? "0".repeat(length - s.length()) + s : s + " ".repeat(length - s.length());
        for (int i = 0; i < length; i++) {
            line[start - 1 + i] = padded.charAt(i);
        }
    }

    private String delimitedLine(Rec kind, Rec record) {
        int width = number(typeAt, "index") + 1;
        for (Object o : Ops.list(kind.get("fields"))) {
            width = Math.max(width, number((Rec) o, "index") + 1);
        }
        String[] cells = new String[width];
        java.util.Arrays.fill(cells, "");
        cells[number(typeAt, "index")] = kind.str("type");
        for (Object o : Ops.list(kind.get("fields"))) {
            Rec field = (Rec) o;
            cells[number(field, "index")] = text(field, record.get(field.str("name")));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            String s = cells[i];
            boolean needsQuote = quote != null && !quote.isEmpty() && (s.contains(delimiter) || s.contains(quote));
            sb.append(needsQuote ? quote + s.replace(quote, quote + quote) + quote : s);
        }
        return sb.toString();
    }

    private static String text(Rec field, Object value) {
        if (value == null) {
            return "";
        }
        String kind = field.str("kind") == null ? "text" : field.str("kind");
        if (kind.equals("amount")) {
            int decimals = field.get("decimals") == null ? 2 : Ops.num(field.get("decimals")).intValue();
            return Ops.num(value).setScale(decimals, java.math.RoundingMode.HALF_UP).unscaledValue().toString();
        }
        if (kind.equals("number")) {
            return Ops.num(value).toBigInteger().toString();
        }
        return Ops.str(value);
    }
}
