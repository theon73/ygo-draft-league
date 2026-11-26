package com.ygoleague.draft;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure domain logic: when a pick's time runs out, given when it came on the clock, the time per
 * pick and an optional daily pause (e.g. 23:00 to 08:00 in the league's zone) during which the clock
 * doesn't run. A pause may wrap midnight. Daylight-saving changes are handled by working in
 * zoned date-times: a pause is a wall-clock window on each local date.
 */
public final class PickClock {

    private PickClock() {}

    /** @param pauseFrom/pauseTo both null for no pause, else both set and different */
    public static Instant deadline(Instant onClockSince, Duration perPick, ZoneId zone, LocalTime pauseFrom, LocalTime pauseTo) {
        if (perPick.isNegative() || perPick.isZero()) throw new IllegalArgumentException("Time per pick must be positive");
        if ((pauseFrom == null) != (pauseTo == null)) throw new IllegalArgumentException("A pause needs a start and an end");
        if (pauseFrom == null) return onClockSince.plus(perPick);
        if (pauseFrom.equals(pauseTo)) throw new IllegalArgumentException("A pause can't start and end at the same time");

        Instant t = pausedUntil(onClockSince, zone, pauseFrom, pauseTo);
        Duration remaining = perPick;
        // Each pass runs the clock up to the next pause (or the deadline, if that comes first).
        // A daily pause means at most one pass per day of remaining time, plus one.
        while (true) {
            Instant nextPause = nextPauseStart(t, zone, pauseFrom);
            Duration untilPause = Duration.between(t, nextPause);
            if (remaining.compareTo(untilPause) <= 0) return t.plus(remaining);
            remaining = remaining.minus(untilPause);
            t = pausedUntil(nextPause, zone, pauseFrom, pauseTo);
        }
    }

    /** Whether the clock is paused at {@code at}. */
    public static boolean paused(Instant at, ZoneId zone, LocalTime pauseFrom, LocalTime pauseTo) {
        return pauseFrom != null && !pausedUntil(at, zone, pauseFrom, pauseTo).equals(at);
    }

    /** {@code at} itself if the clock runs then, else the end of the pause it's in. */
    static Instant pausedUntil(Instant at, ZoneId zone, LocalTime from, LocalTime to) {
        LocalDate day = at.atZone(zone).toLocalDate();
        // The pause that started yesterday (if it wraps midnight) or today may cover `at`.
        for (LocalDate start : new LocalDate[] {day.minusDays(1), day}) {
            Instant begins = ZonedDateTime.of(start, from, zone).toInstant();
            LocalDate endDay = to.isAfter(from) ? start : start.plusDays(1);
            Instant ends = ZonedDateTime.of(endDay, to, zone).toInstant();
            if (!at.isBefore(begins) && at.isBefore(ends)) return ends;
        }
        return at;
    }

    /** The first pause start strictly after {@code at}. */
    static Instant nextPauseStart(Instant at, ZoneId zone, LocalTime from) {
        LocalDate day = at.atZone(zone).toLocalDate();
        Instant today = ZonedDateTime.of(day, from, zone).toInstant();
        return today.isAfter(at) ? today : ZonedDateTime.of(day.plusDays(1), from, zone).toInstant();
    }
}
