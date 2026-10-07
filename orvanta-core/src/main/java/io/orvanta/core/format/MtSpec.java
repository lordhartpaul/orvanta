package io.orvanta.core.format;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Fn;
import io.orvanta.core.expr.Ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Field-level rules of one SWIFT MT message type, read from a MessageSpec model:
 * which fields a message has, in which order, which are mandatory, and the format of each,
 * written in the format notation of the SWIFT standards (16x, 6!n3!a15d, 4*35x, [/34x]).
 * A message is checked against it and every problem names the field.
 */
public final class MtSpec {

    private record Option(String tag, Pattern pattern, String notation, String is) {
    }

    private record Field(String label, Map<String, Option> options, boolean required, boolean repeat, String name) {
    }

    private record Sequence(String id, boolean required, boolean repeat, List<Field> fields) {
    }

    private final String messageType;
    private final List<Sequence> sequences = new ArrayList<>();

    private MtSpec(String messageType) {
        this.messageType = messageType;
    }

    public String messageType() {
        return messageType;
    }

    /** @throws IllegalArgumentException naming the place when the model is not a usable specification */
    public static MtSpec compile(Rec def) {
        String type = def.str("messageType");
        if (type == null || !type.matches("MT\\d{3}")) {
            throw new IllegalArgumentException("messageType must be MT and three digits, for example MT103");
        }
        MtSpec spec = new MtSpec(type);
        List<?> sequences = Ops.list(def.get("sequences"));
        if (sequences.isEmpty() && def.get("fields") != null) {
            // a message without sequences is written as a plain list of fields
            sequences = List.of(Rec.of("id", "A", "required", true, "fields", def.get("fields")));
        }
        if (sequences.isEmpty()) {
            throw new IllegalArgumentException("a message specification needs 'fields' or 'sequences'");
        }
        int n = 0;
        for (Object o : sequences) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("sequences[" + n + "] must be a map with id and fields");
            }
            Rec s = Rec.from(m);
            String id = s.str("id") == null ? String.valueOf((char) ('A' + n)) : s.str("id");
            List<Field> fields = new ArrayList<>();
            int f = 0;
            for (Object fo : Ops.list(s.get("fields"))) {
                String where = "sequence " + id + ", fields[" + f + "]";
                if (!(fo instanceof Map<?, ?> fm)) {
                    throw new IllegalArgumentException(where + " must be a map with tag and format");
                }
                fields.add(field(Rec.from(fm), where));
                f++;
            }
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("sequence " + id + " has no fields");
            }
            boolean repeat = truthy(s.get("repeat"));
            if (repeat && !fields.get(0).required()) {
                throw new IllegalArgumentException("sequence " + id + " repeats, so its first field must be required: it marks where each occurrence starts");
            }
            spec.sequences.add(new Sequence(id, s.get("required") == null || truthy(s.get("required")), repeat, fields));
            n++;
        }
        return spec;
    }

    private static Field field(Rec def, String where) {
        String tag = def.str("tag");
        if (tag == null || !tag.matches("\\d{2}[A-Z]?")) {
            throw new IllegalArgumentException(where + ": tag must be two digits and at most one letter, for example 32A; "
                    + "for a field with letter options write the digits and list the options");
        }
        String is = def.str("is");
        if (is != null && !List.of("date", "bic", "currencyAmount").contains(is)) {
            throw new IllegalArgumentException(where + " (" + tag + "): 'is' must be date, bic or currencyAmount");
        }
        Map<String, Option> options = new LinkedHashMap<>();
        if (def.get("options") instanceof Map<?, ?> map) {
            if (tag.length() != 2) {
                throw new IllegalArgumentException(where + " (" + tag + "): with options the tag is only the two digits");
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String letter = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
                if (!letter.matches("[A-Z]?")) {
                    throw new IllegalArgumentException(where + " (" + tag + "): an option is one letter, or empty for the field without a letter");
                }
                String full = tag + letter;
                options.put(full, new Option(full, pattern(e.getValue(), where + " (" + full + ")"), notation(e.getValue()), is));
            }
            if (options.isEmpty()) {
                throw new IllegalArgumentException(where + " (" + tag + "): options is empty");
            }
        } else {
            if (def.get("format") == null) {
                throw new IllegalArgumentException(where + " (" + tag + "): 'format' or 'options' is required");
            }
            options.put(tag, new Option(tag, pattern(def.get("format"), where + " (" + tag + ")"), notation(def.get("format")), is));
        }
        String label = options.size() == 1 ? options.keySet().iterator().next() : tag + "a (" + String.join(", ", options.keySet()) + ")";
        return new Field(label, options, truthy(def.get("required")), truthy(def.get("repeat")), def.str("name"));
    }

    private static boolean truthy(Object v) {
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }

    private static String notation(Object format) {
        if (format instanceof List<?> lines) {
            List<String> parts = new ArrayList<>();
            lines.forEach(l -> parts.add(String.valueOf(l)));
            return String.join(" + ", parts);
        }
        return String.valueOf(format);
    }

    // ---- the format notation ----

    /** A format is one line, or a list of lines; a line that may be empty is optional together with its line break. */
    private static Pattern pattern(Object format, String where) {
        List<Object> lines = format instanceof List<?> l ? new ArrayList<>(l) : List.of(format);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(where + ": the format is empty");
        }
        List<String> parts = new ArrayList<>();
        List<Boolean> optional = new ArrayList<>();
        for (Object line : lines) {
            String notation = String.valueOf(line).trim();
            int[] pos = {0};
            String regex;
            try {
                regex = items(notation, pos, false);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": format '" + notation + "': " + e.getMessage());
            }
            parts.add(regex);
            optional.add(Pattern.compile(regex).matcher("").matches());
        }
        int lastMandatory = optional.lastIndexOf(false);
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < parts.size(); i++) {
            if (!optional.get(i)) {
                sb.append(started ? "\\n" : "").append(parts.get(i));
                started = true;
            } else if (started) {
                // an optional line after another line: present with the line break in front of it, or not at all
                sb.append("(?:\\n(?=[^\\n])").append(parts.get(i)).append(")?");
            } else if (i < lastMandatory) {
                // an optional first line: present with the line break after it, or not at all
                sb.append("(?:(?=[^\\n])").append(parts.get(i)).append("\\n)?");
            } else {
                // every line is optional: the first one may be empty
                sb.append(parts.get(i));
                started = true;
            }
        }
        return Pattern.compile(sb.toString());
    }

    private static final String X = "[A-Za-z0-9/\\-?:().,'+ ]";
    private static final String Z = "[A-Za-z0-9.,\\-()/='+:?!\"%&*<>;{@#_ ]";

    private static String charset(char type) {
        return switch (type) {
            case 'n' -> "[0-9]";
            case 'a' -> "[A-Z]";
            case 'c' -> "[A-Z0-9]";
            case 'h' -> "[A-F0-9]";
            case 'x' -> X;
            case 'y' -> "[A-Z0-9.,\\-()/='+:?!\"%&*<>; ]";
            case 'z' -> Z;
            case 'e' -> " ";
            default -> null;
        };
    }

    private static String items(String s, int[] pos, boolean inGroup) {
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '[') {
                pos[0]++;
                String inner = items(s, pos, true);
                if (pos[0] >= s.length() || s.charAt(pos[0]) != ']') {
                    throw new IllegalArgumentException("'[' is not closed");
                }
                pos[0]++;
                sb.append("(?:").append(inner).append(")?");
            } else if (c == ']') {
                if (!inGroup) {
                    throw new IllegalArgumentException("']' without '['");
                }
                return sb.toString();
            } else if (Character.isDigit(c)) {
                int start = pos[0];
                while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                    pos[0]++;
                }
                int first = Integer.parseInt(s.substring(start, pos[0]));
                if (pos[0] >= s.length()) {
                    throw new IllegalArgumentException("a number must be followed by a character type such as n, a, c, x or d");
                }
                char next = s.charAt(pos[0]);
                int lines = 0;
                boolean exact = false;
                int min = 1;
                int length = first;
                if (next == '*') {
                    pos[0]++;
                    lines = first;
                    start = pos[0];
                    while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                        pos[0]++;
                    }
                    if (start == pos[0] || pos[0] >= s.length()) {
                        throw new IllegalArgumentException("after '*' a length and a character type are expected, as in 4*35x");
                    }
                    length = Integer.parseInt(s.substring(start, pos[0]));
                } else if (next == '!') {
                    pos[0]++;
                    exact = true;
                } else if (next == '-') {
                    pos[0]++;
                    start = pos[0];
                    while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                        pos[0]++;
                    }
                    if (start == pos[0]) {
                        throw new IllegalArgumentException("after '-' the largest length is expected, as in 1-3n");
                    }
                    min = first;
                    length = Integer.parseInt(s.substring(start, pos[0]));
                }
                if (pos[0] >= s.length()) {
                    throw new IllegalArgumentException("a character type is missing at the end");
                }
                char type = s.charAt(pos[0]++);
                if (length < 1 || min > length || first < 1) {
                    throw new IllegalArgumentException("lengths must be at least 1");
                }
                if (type == 'd') {
                    // an amount: digits, one decimal comma, at most 'length' characters with the comma
                    sb.append("(?=[0-9,]{1,").append(length).append("}(?![0-9,]))[0-9]+,[0-9]*");
                    continue;
                }
                String set = charset(type);
                if (set == null) {
                    throw new IllegalArgumentException("'" + type + "' is not a character type (n, a, c, h, x, y, z, e, d)");
                }
                String one = set + (exact ? "{" + length + "}" : "{" + min + "," + length + "}");
                if (lines > 0) {
                    sb.append(one).append("(?:\\n").append(one).append("){0,").append(lines - 1).append("}");
                } else {
                    sb.append(one);
                }
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                pos[0]++;
            }
        }
        if (inGroup) {
            throw new IllegalArgumentException("'[' is not closed");
        }
        return sb.toString();
    }

    // ---- checking a message ----

    /** Problems of a parsed MT message ({fields:[{tag, value}]}); empty when it follows the specification. */
    public List<String> check(Rec message) {
        List<String> problems = new ArrayList<>();
        List<?> fields = Ops.list(message.get("fields"));
        Map<String, Integer> seen = new LinkedHashMap<>();
        int[] pos = {0};
        for (Sequence sequence : sequences) {
            int occurrence = 0;
            while (true) {
                boolean starts = pos[0] < fields.size() && sequence.fields().get(0).options().containsKey(tag(fields.get(pos[0])));
                boolean firstOptional = !sequence.fields().get(0).required();
                if (occurrence > 0 && !starts) {
                    break;
                }
                if (occurrence == 0 && !starts && !sequence.required() && !(firstOptional && startsLater(sequence, fields, pos[0]))) {
                    break;
                }
                occurrence++;
                String place = sequences.size() == 1 ? "" : " in sequence " + sequence.id() + (sequence.repeat() ? " (occurrence " + occurrence + ")" : "");
                for (Field field : sequence.fields()) {
                    int count = 0;
                    while (pos[0] < fields.size() && field.options().containsKey(tag(fields.get(pos[0]))) && (count == 0 || field.repeat())) {
                        Option option = field.options().get(tag(fields.get(pos[0])));
                        int nth = seen.merge(option.tag(), 1, Integer::sum);
                        value(option, field, Ops.str(Ops.get(fields.get(pos[0]), "value")), nth, place, problems);
                        pos[0]++;
                        count++;
                    }
                    if (count == 0 && field.required()) {
                        problems.add("field :" + field.label() + ":" + (field.name() == null ? "" : " (" + field.name() + ")") + " is mandatory" + place
                                + " and is missing" + (pos[0] < fields.size() ? "; found :" + tag(fields.get(pos[0])) + ": where it was expected" : ""));
                    }
                }
                if (!sequence.repeat()) {
                    break;
                }
            }
        }
        if (pos[0] < fields.size()) {
            String tag = tag(fields.get(pos[0]));
            problems.add("field :" + tag + ": (number " + (pos[0] + 1) + " in the message) is not expected here: " + (known(tag)
                    ? "it is out of order or appears more often than " + messageType + " allows" : messageType + " has no such field"));
        }
        return problems;
    }

    /** An optional sequence whose first field is optional starts when any of its fields comes next. */
    private static boolean startsLater(Sequence sequence, List<?> fields, int pos) {
        if (pos >= fields.size()) {
            return false;
        }
        String tag = tag(fields.get(pos));
        return sequence.fields().stream().anyMatch(f -> f.options().containsKey(tag));
    }

    private boolean known(String tag) {
        return sequences.stream().anyMatch(s -> s.fields().stream().anyMatch(f -> f.options().containsKey(tag)));
    }

    private static String tag(Object field) {
        return Ops.str(Ops.get(field, "tag"));
    }

    private static void value(Option option, Field field, String raw, int nth, String place, List<String> problems) {
        String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        String which = "field :" + option.tag() + ":" + (field.name() == null ? "" : " (" + field.name() + ")")
                + (nth > 1 ? ", occurrence " + nth : "") + place;
        if (!option.pattern().matcher(value).matches()) {
            problems.add(which + ": '" + shorten(value) + "' does not have the format " + option.notation());
            return;
        }
        if (option.is() == null) {
            return;
        }
        String line = value.contains("\n") ? value.substring(value.lastIndexOf('\n') + 1) : value;
        switch (option.is()) {
            case "date" -> {
                // the first six digits in a row are a date: year, month, day
                java.util.regex.Matcher m = Pattern.compile("[0-9]{6}").matcher(value);
                if (m.find()) {
                    String ymd = m.group();
                    if (!Ops.truthy(Fn.isDate("20" + ymd.substring(0, 2) + "-" + ymd.substring(2, 4) + "-" + ymd.substring(4, 6)))) {
                        problems.add(which + ": '" + ymd + "' is not a date (year, month, day)");
                    }
                }
            }
            case "bic" -> {
                if (!Ops.truthy(Fn.isBic(line))) {
                    problems.add(which + ": '" + shorten(line) + "' is not a BIC");
                }
            }
            case "currencyAmount" -> {
                java.util.regex.Matcher m = Pattern.compile("([A-Z]{3})[0-9]+,[0-9]*$").matcher(value);
                if (m.find() && !Ops.truthy(Fn.isCurrency(m.group(1)))) {
                    problems.add(which + ": '" + m.group(1) + "' is not a currency code");
                }
            }
            default -> {
            }
        }
    }

    private static String shorten(String value) {
        String oneLine = value.replace("\n", "\\n");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "..." : oneLine;
    }
}
