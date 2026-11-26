package com.ygoleague.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A season's weeks: week 1 begins at the first week start ({@link WeekBoundary}) on or after the
 * start date, and each week runs to the same local moment a week later. Week 0 is the pre-season
 * (setup and draft). Spring-free.
 *
 * <p>Only regular weeks exist so far; off weeks (Phase 4) will be entries in the season's week
 * list that take up a slot here without matches.
 */
public record SeasonCalendar(WeekBoundary boundary, LocalDate startDate, int weekCount) {

    public SeasonCalendar {
        if (weekCount < 1) throw new IllegalArgumentException("weekCount must be at least 1");
    }

    /** A numbered week of the season. */
    public record SeasonWeek(int number, ZonedDateTime start, ZonedDateTime end) {}

    public ZonedDateTime firstWeekStart() {
        ZonedDateTime midnight = startDate.atStartOfDay(boundary.zone());
        WeekBoundary.Week containing = boundary.weekContaining(midnight.toInstant());
        return containing.start().toInstant().equals(midnight.toInstant()) ? containing.start() : containing.end();
    }

    public List<SeasonWeek> weeks() {
        ZonedDateTime first = firstWeekStart();
        List<SeasonWeek> weeks = new ArrayList<>(weekCount);
        for (int n = 1; n <= weekCount; n++) {
            weeks.add(new SeasonWeek(n, startOfWeek(first, n), startOfWeek(first, n + 1)));
        }
        return weeks;
    }

    /** 0 before week 1 (pre-season), 1..weekCount during the season, weekCount + 1 once it's over. */
    public int weekNumberAt(Instant instant) {
        ZonedDateTime first = firstWeekStart();
        if (instant.isBefore(first.toInstant())) return 0;
        for (int n = 1; n <= weekCount; n++) {
            if (instant.isBefore(startOfWeek(first, n + 1).toInstant())) return n;
        }
        return weekCount + 1;
    }

    /** Anchored to local wall-clock time, like WeekBoundary, so weeks follow daylight saving. */
    private ZonedDateTime startOfWeek(ZonedDateTime first, int n) {
        return ZonedDateTime.of(first.toLocalDate().plusWeeks(n - 1L), boundary.startTime(), boundary.zone());
    }
}
