package com.ygoleague.draft;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Pure domain logic: when a pick's time runs out, given when it came on the clock, the time per
 * pick and an optional daily pause (e.g. 23:00 to 08:00 in the league's zone) during which the clock
 * doesn't run. A pause may wrap midnight. Daylight-saving changes are handled by working in
 * zoned date-times: a pause is a wall-clock window on each local date.
 *
 * <p>Holds are commissioner pauses (YGO-19): stretches when the clock doesn't run either, on top of
 * the daily pause. Time used before a hold is kept, so after it the pick gets what it had left.
 */
public final class PickClock {

    /** A commissioner pause; {@code to} is null while it's still on. */
    public record Hold(Instant from, Instant to) {}

    private PickClock() {}

    /**
     * The deadline with holds: the clock also stands still during each hold. Returns null while a
     * hold is on (there's no deadline until it ends).
     */
    public static Instant deadline(Instant onClockSince, Duration perPick, ZoneId zone, LocalTime pauseFrom, LocalTime pauseTo,
                                   List<Hold> holds) {
        Instant start = onClockSince;
        Duration remaining = perPick;
        for (Hold hold : holds.stream().sorted(Comparator.comparing(Hold::from)).toList()) {
            if (hold.to() != null && !hold.to().isAfter(start)) continue;   // over before this pick came up
            Instant holdStart = hold.from().isAfter(start) ? hold.from() : start;
            Instant runsOut = deadline(start, remaining, zone, pauseFrom, pauseTo);
            if (!runsOut.isAfter(holdStart)) return runsOut;                  // ran out before the hold
            remaining = remaining.minus(runningTime(start, holdStart, zone, pauseFrom, pauseTo));
            if (hold.to() == null) return null;                               // on hold now
            start = hold.to();
        }
        return deadline(start, remaining, zone, pauseFrom, pauseTo);
    }

    /** How long the clock runs between two instants, leaving out the daily pause. */
    static Duration runningTime(Instant from, Instant to, ZoneId zone, LocalTime pauseFrom, LocalTime pauseTo) {
        if (!to.isAfter(from)) return Duration.ZERO;
        if (pauseFrom == null) return Duration.between(from, to);
        Duration total = Duration.ZERO;
        Instant t = pausedUntil(from, zone, pauseFrom, pauseTo);
        while (t.isBefore(to)) {
            Instant nextPause = nextPauseStart(t, zone, pauseFrom);
            Instant end = nextPause.isBefore(to) ? nextPause : to;
            total = total.plus(Duration.between(t, end));
            t = pausedUntil(nextPause, zone, pauseFrom, pauseTo);
        }
        return total;
    }

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
