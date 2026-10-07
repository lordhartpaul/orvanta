package io.orvanta.core.format;

import io.orvanta.core.data.Rec;
import io.orvanta.core.expr.Ops;

import java.util.ArrayList;
import java.util.List;

/** Format detection and the single entry points for reading and writing wire messages. */
public final class Messages {

    public static final String ISO20022 = "iso20022";
    public static final String SWIFT_MT = "swift.mt";
    public static final String JSON = "json";
    /** records of fixed width or delimited text, read and written by a Format model named on the channel (formatSpec) */
    public static final String FLAT = "flat";

    private Messages() {
    }

    /** One wire message after parsing. */
    public record Parsed(String format, String messageType, Rec tree, String raw) {
    }

    public static String detectFormat(String raw) {
        String s = raw.stripLeading();
        if (s.startsWith("﻿")) {
            s = s.substring(1).stripLeading();
        }
        if (s.startsWith("<")) {
            return ISO20022;
        }
        if (s.contains("{1:") && s.indexOf("{1:") < 20) {
            return SWIFT_MT;
        }
        if (s.startsWith("{") || s.startsWith("[")) {
            return JSON;
        }
        throw new IllegalArgumentException("unrecognised message format: expected ISO 20022 XML, a SWIFT MT message or JSON");
    }

    /**
     * Parses a payload for a channel: a flat file by the channel's Format model (the text alone does not say
     * what it is), anything else by what the text looks like.
     *
     * @param channel the channel the payload is for, or null when it is not known yet
     * @param configs the deployed configuration models by name, for the Format model
     */
    public static List<Parsed> parse(String raw, Rec channel, java.util.function.Function<String, Rec> configs) {
        if (channel != null && FLAT.equals(channel.str("format"))) {
            Rec spec = channel.str("formatSpec") == null ? null : configs.apply(channel.str("formatSpec"));
            if (spec == null) {
                throw new IllegalArgumentException("channel " + channel.str("name") + " reads flat files but names no deployed Format (formatSpec)");
            }
            return List.of(parseFlat(raw, spec));
        }
        return parse(raw);
    }

    /** A flat file read by its Format model; the message type is the model's name. */
    public static Parsed parseFlat(String raw, Rec spec) {
        Rec tree = FlatFile.compile(spec).parse(raw);
        return new Parsed(FLAT, spec.str("name"), tree, raw);
    }

    /** Serialises for a channel: a flat file by the Format model given, anything else as {@link #write(String, Rec)}. */
    public static String write(String format, Rec mapped, Rec spec) {
        if (FLAT.equals(format)) {
            if (spec == null) {
                throw new IllegalArgumentException("a flat file needs the channel's Format model (formatSpec)");
            }
            return FlatFile.compile(spec).write(mapped);
        }
        return write(format, mapped);
    }

    /** Parses a payload; an MT file with several messages gives several results. */
    public static List<Parsed> parse(String raw) {
        String format = detectFormat(raw);
        List<Parsed> out = new ArrayList<>();
        if (ISO20022.equals(format)) {
            Rec tree = IsoXml.parse(raw);
            String type = IsoXml.messageType(tree);
            if (type == null) {
                throw new IllegalArgumentException("XML is not an ISO 20022 message: no urn:iso:std:iso:20022 namespace found");
            }
            out.add(new Parsed(format, type, tree, raw));
        } else if (JSON.equals(format)) {
            // a JSON message names its type in a top level "messageType" field; without one the type is "json"
            Object parsed = io.orvanta.core.json.Json.parseAny(raw);
            Rec tree = parsed instanceof Rec r ? r : Rec.of("items", parsed);
            out.add(new Parsed(format, tree.str("messageType") == null ? JSON : tree.str("messageType"), tree, raw));
        } else {
            int from = raw.indexOf("{1:");
            while (from >= 0) {
                int next = raw.indexOf("{1:", from + 3);
                String one = (next < 0 ? raw.substring(from) : raw.substring(from, next)).strip();
                if (SwiftMt.isAcknowledgement(one)) {
                    // the network's ACK or NAK, followed by a copy of our message: one thing, not two
                    int after = next < 0 ? -1 : raw.indexOf("{1:", next + 3);
                    String copy = next < 0 ? null : (after < 0 ? raw.substring(next) : raw.substring(next, after)).strip();
                    Rec ack = SwiftMt.parseAcknowledgement(one, copy);
                    out.add(new Parsed(format, ack.str("type"), ack, copy == null ? one : one + "\n" + copy));
                    from = after;
                    continue;
                }
                Rec msg = SwiftMt.parse(one);
                out.add(new Parsed(format, "MT" + msg.str("type"), msg, one));
                from = next;
            }
        }
        return out;
    }

    /**
     * Serialises the output of an outbound mapping. ISO 20022: the tree itself.
     * SWIFT MT: either one message or {messages:[...]}, written one after another.
     */
    public static String write(String format, Rec mapped) {
        if (ISO20022.equals(format)) {
            return IsoXml.write(mapped);
        }
        if (SWIFT_MT.equals(format)) {
            if (mapped.get("messages") == null) {
                return SwiftMt.write(mapped);
            }
            StringBuilder sb = new StringBuilder();
            for (Object m : Ops.list(mapped.get("messages"))) {
                if (sb.length() > 0) {
                    sb.append("\r\n");
                }
                sb.append(SwiftMt.write((Rec) m));
            }
            return sb.toString();
        }
        if (JSON.equals(format)) {
            return io.orvanta.core.json.Json.pretty(mapped);
        }
        if (FLAT.equals(format)) {
            throw new IllegalArgumentException("a flat file is written with its Format model: write(format, mapped, spec)");
        }
        throw new IllegalArgumentException("unknown message format '" + format + "'");
    }

    /** True when a channel's messageTypes entry (pain.001, pain.001.001.09, MT101) covers the type. */
    public static boolean typeMatches(String declared, String actual) {
        return actual != null && declared != null && (actual.equals(declared) || actual.startsWith(declared + "."));
    }
}
