package io.orvanta.core.expr;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A cron expression of five fields: minute, hour, day of month, month, day of week (0 or 7 is Sunday).
 * Each field is "*", a value, a range "a-b", a list "a,b,c" or a step "* /n" and "a-b/n". As in cron, a time
 * matches when the day of month or the day of week matches once either is restricted, and both when both are.
 */
public final class Cron {
    private final String text;
    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] days = new boolean[32];
    private final boolean[] months = new boolean[13];
    private final boolean[] weekdays = new boolean[8];
    private final boolean anyDay;
    private final boolean anyWeekday;

    private Cron(String text) {
        this.text = text.trim();
        String[] fields = this.text.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("a cron expression has five fields (minute hour day month weekday), not " + fields.length);
        }
        fill(minutes, fields[0], 0, 59, "minute");
        fill(hours, fields[1], 0, 23, "hour");
        fill(days, fields[2], 1, 31, "day of month");
        fill(months, fields[3], 1, 12, "month");
        fill(weekdays, fields[4], 0, 7, "day of week");
        if (weekdays[7]) {
            weekdays[0] = true;
        }
        anyDay = fields[2].equals("*");
        anyWeekday = fields[4].equals("*");
    }

    /** @throws IllegalArgumentException when the expression is not well formed; the message names the field */
    public static Cron parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a cron expression is needed");
        }
        return new Cron(text);
    }

    private static void fill(boolean[] set, String field, int min, int max, String name) {
        for (String part : field.split(",")) {
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), name);
                if (step < 1) {
                    throw new IllegalArgumentException(name + ": a step is one or more");
                }
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] ends = range.split("-", 2);
                from = number(ends[0], name);
                to = number(ends[1], name);
            } else {
                from = number(range, name);
                to = slash >= 0 ? max : from;
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException(name + ": '" + part + "' is outside " + min + "-" + max);
            }
            for (int v = from; v <= to; v += step) {
                set[v] = true;
            }
        }
    }

    private static int number(String s, String name) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": '" + s + "' is not a number");
        }
    }

    /** Whether the minute of this time is one the expression names. */
    public boolean matches(ZonedDateTime time) {
        if (!minutes[time.getMinute()] || !hours[time.getHour()] || !months[time.getMonthValue()]) {
            return false;
        }
        boolean dayOk = days[time.getDayOfMonth()];
        boolean weekdayOk = weekdays[time.getDayOfWeek().getValue() % 7];
        if (anyDay && anyWeekday) {
            return true;
        }
        if (anyDay) {
            return weekdayOk;
        }
        if (anyWeekday) {
            return dayOk;
        }
        return dayOk || weekdayOk;
    }

    /** The first minute after the given time that matches, within a year; null when there is none. */
    public ZonedDateTime next(ZonedDateTime after) {
        ZonedDateTime t = after.withSecond(0).withNano(0).plusMinutes(1);
        ZonedDateTime limit = t.plusYears(1);
        while (t.isBefore(limit)) {
            if (!months[t.getMonthValue()]) {
                t = t.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            if (!matchesDay(t)) {
                t = t.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!hours[t.getHour()]) {
                t = t.plusHours(1).withMinute(0);
                continue;
            }
            if (!minutes[t.getMinute()]) {
                t = t.plusMinutes(1);
                continue;
            }
            return t;
        }
        return null;
    }

    private boolean matchesDay(ZonedDateTime t) {
        boolean dayOk = days[t.getDayOfMonth()];
        boolean weekdayOk = weekdays[t.getDayOfWeek().getValue() % 7];
        return anyDay && anyWeekday || (anyDay ? weekdayOk : anyWeekday ? dayOk : dayOk || weekdayOk);
    }

    /** The values a field names, for a reader: "every 10 minutes", "18:00", "weekdays"; a plain rendering of the fields. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add(text);
        return out;
    }

    @Override
    public String toString() {
        return text;
    }
}
