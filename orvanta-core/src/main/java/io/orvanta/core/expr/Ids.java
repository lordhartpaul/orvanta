package io.orvanta.core.expr;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The message id of an outbound file as a receiver wants it, from a template on the channel
 * ({@code messageIdTemplate}): literal text with placeholders in braces. {seq:N} is the file's sequence
 * number zero-filled to N digits, {channel} the channel's short name, and anything else a date pattern
 * (yyyyMMdd, yyMMdd, HHmmss, ...) of the moment the file is built. Without a template the file's own id
 * is the message id.
 */
public final class Ids {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private Ids() {
    }

    /** @throws IllegalArgumentException when a placeholder is not understood; for the model compiler and the builder alike */
    public static String render(String template, long sequence, String channel, ZonedDateTime now) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String value;
            if (token.startsWith("seq")) {
                int digits = token.contains(":") ? Integer.parseInt(token.substring(token.indexOf(':') + 1).trim()) : 8;
                String number = Long.toString(sequence % (long) Math.pow(10, digits));
                value = "0".repeat(Math.max(0, digits - number.length())) + number;
            } else if (token.equals("channel")) {
                value = channel == null ? "" : channel.replace("channels.", "").replaceAll("[^A-Za-z0-9]", "");
            } else {
                try {
                    value = now.format(DateTimeFormatter.ofPattern(token, Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("'{" + token + "}' is not a placeholder: use {seq:N}, {channel} or a date pattern such as {yyyyMMdd}");
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        String id = out.toString();
        if (id.isBlank() || id.length() > 35 || !id.matches("[A-Za-z0-9+?/:().,'\\- ]+")) {
            throw new IllegalArgumentException("a message id is 1 to 35 characters of the SWIFT character set; the template gives '" + id + "'");
        }
        return id;
    }

    /** The number inside an id of the platform's own (ORVOUT0000000028 gives 28). */
    public static long sequenceOf(String id) {
        String digits = id.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits.length() > 15 ? digits.substring(digits.length() - 15) : digits);
    }
}
