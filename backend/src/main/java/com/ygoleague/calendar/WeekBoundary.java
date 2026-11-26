package com.ygoleague.calendar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * When a league's weeks start: a day of the week and a local time in the league's zone, e.g.
 * Monday 00:00 in America/Chicago. Spring-free.
 *
 * <p>Weeks are anchored to local wall-clock time, so they follow daylight saving: a week that
 * crosses a DST change is 167 or 169 hours long, but always starts at the same local time. If the
 * start time doesn't exist on a given day (e.g. 02:30 when clocks jump from 02:00 to 03:00), the
 * week starts at the first valid moment after it, as {@link ZonedDateTime#of} resolves it.
 */
public record WeekBoundary(ZoneId zone, DayOfWeek startDay, LocalTime startTime) {

    /** One week: from {@code start} (inclusive) to {@code end} (exclusive). */
    public record Week(ZonedDateTime start, ZonedDateTime end) {
        public boolean contains(Instant instant) {
            return !instant.isBefore(start.toInstant()) && instant.isBefore(end.toInstant());
        }
    }

    /** The week that contains {@code instant}. */
    public Week weekContaining(Instant instant) {
        ZonedDateTime local = instant.atZone(zone);
        LocalDate day = local.toLocalDate().with(TemporalAdjusters.previousOrSame(startDay));
        ZonedDateTime start = ZonedDateTime.of(day, startTime, zone);
        if (start.toInstant().isAfter(instant)) {
            // Same weekday as the start but before the start time: still last week.
            start = ZonedDateTime.of(day.minusWeeks(1), startTime, zone);
        }
        return new Week(start, ZonedDateTime.of(start.toLocalDate().plusWeeks(1), startTime, zone));
    }
}
