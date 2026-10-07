package io.orvanta.forge;

import io.orvanta.core.expr.Cron;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cron expressions as Schedule models use them. */
class CronTest {

    private static ZonedDateTime at(String iso) {
        return ZonedDateTime.parse(iso);
    }

    @Test
    void minutesHoursDaysMonthsAndWeekdaysAreMatched() {
        Cron weekdaysAtSix = Cron.parse("0 18 * * 1-5");
        assertTrue(weekdaysAtSix.matches(at("2026-10-07T18:00:00+02:00[Africa/Johannesburg]")), "a Wednesday");
        assertFalse(weekdaysAtSix.matches(at("2026-10-07T18:01:00+02:00[Africa/Johannesburg]")));
        assertFalse(weekdaysAtSix.matches(at("2026-10-10T18:00:00+02:00[Africa/Johannesburg]")), "a Saturday");
        assertEquals(at("2026-10-08T18:00:00+02:00[Africa/Johannesburg]"), weekdaysAtSix.next(at("2026-10-07T18:00:00+02:00[Africa/Johannesburg]")));
        assertEquals(at("2026-10-12T18:00:00+02:00[Africa/Johannesburg]"), weekdaysAtSix.next(at("2026-10-09T18:00:00+02:00[Africa/Johannesburg]")), "Friday evening: Monday next");

        Cron everyTen = Cron.parse("*/10 * * * *");
        assertTrue(everyTen.matches(at("2026-10-07T09:30:00Z")));
        assertFalse(everyTen.matches(at("2026-10-07T09:35:00Z")));
        assertEquals(at("2026-10-07T09:40:00Z"), everyTen.next(at("2026-10-07T09:30:00Z")));

        Cron monthEnds = Cron.parse("30 23 1,15 * *");
        assertTrue(monthEnds.matches(at("2026-10-15T23:30:00Z")));
        assertFalse(monthEnds.matches(at("2026-10-16T23:30:00Z")));
        // as in cron: with both the day and the weekday restricted, either matching is enough
        Cron either = Cron.parse("0 9 13 * 5");
        assertTrue(either.matches(at("2026-11-13T09:00:00Z")), "the 13th");
        assertTrue(either.matches(at("2026-10-09T09:00:00Z")), "a Friday");
        assertFalse(either.matches(at("2026-10-12T09:00:00Z")), "a Monday that is not the 13th");
        assertTrue(Cron.parse("0 0 * * 7").matches(at("2026-10-11T00:00:00Z")), "7 is Sunday too");
    }

    @Test
    void aMalformedExpressionNamesItsField() {
        for (String bad : List.of("", "0 18 * *", "60 * * * *", "0 25 * * *", "0 0 32 * *", "0 0 * 13 *", "0 0 * * 8", "a * * * *", "*/0 * * * *", "5-3 * * * *")) {
            assertThrows(IllegalArgumentException.class, () -> Cron.parse(bad), bad);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Cron.parse("0 25 * * *")).getMessage().startsWith("hour"));
        assertFalse(Forge.build(List.of(ModelSource.parse("s.yaml", "kind: Schedule\nname: schedules.Bad\ncron: \"0 25 * * *\"\njob: closeDay\n"))).ok());
        assertFalse(Forge.build(List.of(ModelSource.parse("s.yaml", "kind: Schedule\nname: schedules.Bad\ncron: \"0 1 * * *\"\njob: dance\n"))).ok());
        assertFalse(Forge.build(List.of(ModelSource.parse("s.yaml", "kind: Schedule\nname: schedules.Bad\ncron: \"0 1 * * *\"\ntimezone: Mars/Olympus\njob: closeDay\n"))).ok());
        assertTrue(Forge.build(List.of(ModelSource.parse("s.yaml", "kind: Schedule\nname: schedules.Ok\ncron: \"0 1 * * *\"\ntimezone: Europe/Berlin\njob: closeDay\nday: yesterday\n"))).ok());
    }
}
