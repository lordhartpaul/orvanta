package io.orvanta.core.format;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generic SWIFT MT (FIN) codec. Any MT type is read at block and tag level into
 * {type, direction, sender, receiver, priority, b3:{..}, fields:[{tag, value}], b5:{..}};
 * the meaning of individual fields is left to mappings (see the mt* functions).
 */
public final class SwiftMt {

    private SwiftMt() {
    }

    /** A file may carry several messages; each starts with a basic header block. */
    public static List<Rec> parseAll(String text) {
        List<Rec> messages = new ArrayList<>();
        int from = text.indexOf("{1:");
        if (from < 0) {
            throw new IllegalArgumentException("no SWIFT basic header block {1:} found");
        }
        while (from >= 0) {
            int next = text.indexOf("{1:", from + 3);
            messages.add(parse(next < 0 ? text.substring(from) : text.substring(from, next)));
            from = next;
        }
        return messages;
    }

    /** Whether a message is the network's acknowledgement of one of ours (a service message, basic header F21) rather than a message. */
    public static boolean isAcknowledgement(String text) {
        return text.stripLeading().startsWith("{1:F21");
    }

    /**
     * Reads the network's ACK or NAK of a message we sent: block 4 of the service message holds the time
     * (177), whether it was accepted (451: 0 yes, 1 no) and the error code of a NAK (405); the copy of
     * our message that follows gives the reference (field 20) it was about.
     * @param copy the copy of the original message that follows the acknowledgement, or null
     */
    public static Rec parseAcknowledgement(String ack, String copy) {
        Rec msg = new Rec();
        msg.put("format", "swift.mt");
        java.util.regex.Matcher accepted = java.util.regex.Pattern.compile("\\{451:([01])}").matcher(ack);
        if (!accepted.find()) {
            throw new IllegalArgumentException("a SWIFT acknowledgement carries field 451");
        }
        boolean ok = "0".equals(accepted.group(1));
        msg.put("type", ok ? "ACK" : "NAK");
        msg.put("accepted", ok);
        java.util.regex.Matcher code = java.util.regex.Pattern.compile("\\{405:([^}]*)}").matcher(ack);
        if (code.find()) {
            msg.put("code", code.group(1).trim());
        }
        java.util.regex.Matcher at = java.util.regex.Pattern.compile("\\{177:(\\d{10})}").matcher(ack);
        if (at.find()) {
            msg.put("at", at.group(1));
        }
        java.util.regex.Matcher header = java.util.regex.Pattern.compile("\\{1:F21([A-Z0-9]{12})").matcher(ack);
        if (header.find()) {
            msg.put("receiver", header.group(1).substring(0, 8) + header.group(1).substring(9));
        }
        if (copy != null && !copy.isBlank()) {
            try {
                Rec original = parse(copy);
                msg.put("copyType", "MT" + original.str("type"));
                for (Object f : (List<?>) original.get("fields")) {
                    if (f instanceof Rec field && "20".equals(field.str("tag"))) {
                        msg.put("reference", String.valueOf(field.get("value")).trim());
                    }
                }
            } catch (IllegalArgumentException e) {
                // a copy that cannot be read leaves the acknowledgement without a reference; it is still recorded
                msg.put("copyProblem", e.getMessage());
            }
        }
        msg.put("fields", new ArrayList<>());
        return msg;
    }

    public static Rec parse(String text) {
        Rec msg = new Rec();
        msg.put("format", "swift.mt");
        int pos = 0;
        while (pos < text.length()) {
            int open = text.indexOf('{', pos);
            if (open < 0) {
                break;
            }
            int close = matchingBrace(text, open);
            String block = text.substring(open + 1, close);
            int colon = block.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("malformed block near position " + open);
            }
            String id = block.substring(0, colon);
            String body = block.substring(colon + 1);
            switch (id) {
                case "1" -> readBasicHeader(msg, body);
                case "2" -> readApplicationHeader(msg, body);
                case "3" -> msg.put("b3", readTagBlock(body));
                case "4" -> msg.put("fields", readTextBlock(body));
                case "5" -> msg.put("b5", readTagBlock(body));
                default -> msg.put("b" + id, body);
            }
            pos = close + 1;
        }
        if (msg.get("type") == null) {
            throw new IllegalArgumentException("no application header block {2:} found");
        }
        if (msg.get("fields") == null) {
            msg.put("fields", new ArrayList<>());
        }
        return msg;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("unbalanced braces: block starting at position " + open + " is not closed");
    }

    private static void readBasicHeader(Rec msg, String body) {
        msg.put("b1", body);
        if (body.length() >= 15) {
            msg.put("logicalTerminal", body.substring(3, 15));
        }
    }

    private static void readApplicationHeader(Rec msg, String body) {
        msg.put("b2", body);
        if (body.length() < 4) {
            throw new IllegalArgumentException("application header block {2:} is too short");
        }
        String direction = body.substring(0, 1);
        msg.put("direction", direction);
        msg.put("type", body.substring(1, 4));
        String own = bicOf(msg.str("logicalTerminal"));
        if ("I".equals(direction)) {
            // input to the network: block 1 is the sender, block 2 names the receiver
            String receiver = body.length() >= 16 ? bicOf(body.substring(4, 16)) : null;
            msg.set("sender", own);
            msg.set("receiver", receiver);
            if (body.length() >= 17) {
                msg.put("priority", body.substring(16, 17));
            }
        } else {
            // output from the network: block 2 carries the sender inside the MIR, block 1 is the receiver
            String sender = body.length() >= 26 ? bicOf(body.substring(14, 26)) : null;
            msg.set("sender", sender);
            msg.set("receiver", own);
        }
    }

    /** BIC of a 12-character logical terminal address (BIC8 + terminal letter + branch). */
    private static String bicOf(String lt) {
        if (lt == null || lt.length() < 12) {
            return lt;
        }
        String branch = lt.substring(9, 12);
        return lt.substring(0, 8) + ("XXX".equals(branch) ? "" : branch);
    }

    private static String ltOf(String bic) {
        if (bic == null || bic.length() < 8) {
            throw new IllegalArgumentException("a BIC is required to write an MT header, got '" + bic + "'");
        }
        return bic.substring(0, 8) + "A" + (bic.length() >= 11 ? bic.substring(8, 11) : "XXX");
    }

    private static Rec readTagBlock(String body) {
        Rec tags = new Rec();
        int pos = 0;
        while (pos < body.length()) {
            int open = body.indexOf('{', pos);
            if (open < 0) {
                break;
            }
            int close = matchingBrace(body, open);
            String inner = body.substring(open + 1, close);
            int colon = inner.indexOf(':');
            if (colon > 0) {
                tags.put(inner.substring(0, colon), inner.substring(colon + 1));
            }
            pos = close + 1;
        }
        return tags;
    }

    private static List<Object> readTextBlock(String body) {
        List<Object> fields = new ArrayList<>();
        String tag = null;
        StringBuilder value = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            if (line.equals("-")) {
                break;
            }
            if (line.startsWith(":")) {
                int end = line.indexOf(':', 1);
                if (end > 1 && end <= 4) {
                    if (tag != null) {
                        fields.add(Rec.of("tag", tag, "value", value.toString()));
                    }
                    tag = line.substring(1, end);
                    value = new StringBuilder(line.substring(end + 1));
                    continue;
                }
            }
            if (tag != null) {
                value.append('\n').append(line);
            }
        }
        if (tag != null) {
            fields.add(Rec.of("tag", tag, "value", value.toString()));
        }
        return fields;
    }

    /** Writes an input message {type, sender, receiver, priority?, b3?, fields:[{tag, value}]}. */
    public static String write(Rec msg) {
        String type = msg.str("type");
        if (type == null || type.length() != 3) {
            throw new IllegalArgumentException("MT type must be three digits, got '" + type + "'");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{1:F01").append(ltOf(msg.str("sender"))).append("0000000000}");
        sb.append("{2:I").append(type).append(ltOf(msg.str("receiver")))
                .append(msg.str("priority") == null ? "N" : msg.str("priority")).append('}');
        Object b3 = msg.get("b3");
        if (b3 instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("{3:");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append('{').append(e.getKey()).append(':').append(Ops.str(e.getValue())).append('}');
            }
            sb.append('}');
        }
        sb.append("{4:\r\n");
        for (Object f : Ops.list(msg.get("fields"))) {
            String value = Ops.str(Ops.get(f, "value"));
            if (value == null || value.isBlank()) {
                continue;
            }
            sb.append(':').append(Ops.str(Ops.get(f, "tag"))).append(':')
                    .append(value.replace("\r\n", "\n").replace("\n", "\r\n")).append("\r\n");
        }
        sb.append("-}");
        return sb.toString();
    }
}
