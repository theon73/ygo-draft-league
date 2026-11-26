package com.ygoleague.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class WeekBoundaryTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    static Instant at(String localDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), CHICAGO).toInstant();
    }

    @Test
    void weeksRunFromTheStartDayAndTimeToTheSameTimeAWeekLater() {
        WeekBoundary monday = new WeekBoundary(CHICAGO, DayOfWeek.MONDAY, LocalTime.MIDNIGHT);
        WeekBoundary.Week week = monday.weekContaining(at("2026-11-26T15:00"));   // a Thursday
        assertEquals("2026-11-23T00:00-06:00[America/Chicago]", week.start().toString());
        assertEquals("2026-11-30T00:00-06:00[America/Chicago]", week.end().toString());
        assertTrue(week.contains(at("2026-11-23T00:00")));     // start is inclusive
        assertFalse(week.contains(at("2026-11-30T00:00")));    // end is exclusive
    }

    @Test
    void theStartDayBeforeTheStartTimeBelongsToTheWeekBefore() {
        WeekBoundary tuesdayEvening = new WeekBoundary(CHICAGO, DayOfWeek.TUESDAY, LocalTime.of(20, 0));
        assertEquals("2026-11-17T20:00-06:00[America/Chicago]",
                tuesdayEvening.weekContaining(at("2026-11-24T19:59")).start().toString());
        assertEquals("2026-11-24T20:00-06:00[America/Chicago]",
                tuesdayEvening.weekContaining(at("2026-11-24T20:00")).start().toString());
    }

    @Test
    void weeksFollowDaylightSavingAndSurviveMissingStartTimes() {
        WeekBoundary sunday = new WeekBoundary(CHICAGO, DayOfWeek.SUNDAY, LocalTime.of(2, 30));
        // 2027-03-14: clocks jump 02:00 -> 03:00 in Chicago, so 02:30 doesn't exist that day.
        WeekBoundary.Week before = sunday.weekContaining(at("2027-03-10T12:00"));
        assertEquals("2027-03-07T02:30-06:00[America/Chicago]", before.start().toString());
        assertEquals("2027-03-14T03:30-05:00[America/Chicago]", before.end().toString());   // shifted past the gap
        assertEquals(Duration.ofHours(168), Duration.between(before.start(), before.end()));
        // The next week starts where this one ended, so there's no gap and no overlap.
        assertEquals(before.end(), sunday.weekContaining(before.end().toInstant()).start());
    }
}
