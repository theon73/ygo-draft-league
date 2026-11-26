package com.ygoleague.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PickClockTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalTime NIGHT = LocalTime.of(23, 0);
    private static final LocalTime MORNING = LocalTime.of(8, 0);

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(CHICAGO).toInstant();
    }

    @Test
    void withoutAPauseItsJustTheTimePerPick() {
        assertEquals(at("2030-01-07T20:01:30"), PickClock.deadline(at("2030-01-07T20:00"), Duration.ofSeconds(90), CHICAGO, null, null));
    }

    @Test
    void theClockStopsOvernight() {
        // 2 hours from 22:00: one hour before the pause, the second after it ends at 08:00.
        assertEquals(at("2030-01-08T09:00"), PickClock.deadline(at("2030-01-07T22:00"), Duration.ofHours(2), CHICAGO, NIGHT, MORNING));
        // Coming on the clock during the pause starts the count at 08:00.
        assertEquals(at("2030-01-08T08:30"), PickClock.deadline(at("2030-01-08T02:00"), Duration.ofMinutes(30), CHICAGO, NIGHT, MORNING));
        // Exactly at the pause start: it runs out then, not the next morning.
        assertEquals(at("2030-01-07T23:00"), PickClock.deadline(at("2030-01-07T22:00"), Duration.ofHours(1), CHICAGO, NIGHT, MORNING));
    }

    @Test
    void longTimersSpanSeveralNights() {
        // 24 hours of running clock with 15 running hours a day: 15 on day 1 (from 08:00), 9 on day 2.
        assertEquals(at("2030-01-08T17:00"), PickClock.deadline(at("2030-01-07T08:00"), Duration.ofHours(24), CHICAGO, NIGHT, MORNING));
    }

    @Test
    void aPauseWithinOneDayWorksToo() {
        // A lunch pause 12:00 to 13:00.
        assertEquals(at("2030-01-07T13:30"),
                PickClock.deadline(at("2030-01-07T11:30"), Duration.ofHours(1), CHICAGO, LocalTime.NOON, LocalTime.of(13, 0)));
    }

    @Test
    void daylightSavingFollowsTheWallClock() {
        // The night of 9 to 10 March 2030 is an hour shorter, but the pause still ends at 08:00 local.
        assertEquals(at("2030-03-10T08:30"), PickClock.deadline(at("2030-03-09T22:30"), Duration.ofHours(1), CHICAGO, NIGHT, MORNING));
    }

    @Test
    void pausedSaysWhetherTheClockRunsNow() {
        assertTrue(PickClock.paused(at("2030-01-08T03:00"), CHICAGO, NIGHT, MORNING));
        assertTrue(PickClock.paused(at("2030-01-07T23:00"), CHICAGO, NIGHT, MORNING));
        assertFalse(PickClock.paused(at("2030-01-08T08:00"), CHICAGO, NIGHT, MORNING));
        assertFalse(PickClock.paused(at("2030-01-08T03:00"), CHICAGO, null, null));
    }

    @Test
    void aHoldStopsTheClockAndGivesBackWhatWasLeft() {
        // 10 minutes from 20:00; 4 used, held 20:04-20:30, 6 left after: 20:36.
        var hold = new PickClock.Hold(at("2030-01-07T20:04"), at("2030-01-07T20:30"));
        assertEquals(at("2030-01-07T20:36"), PickClock.deadline(at("2030-01-07T20:00"), Duration.ofMinutes(10), CHICAGO, null, null, List.of(hold)));
        // While it's on there's no deadline.
        var open = new PickClock.Hold(at("2030-01-07T20:04"), null);
        assertNull(PickClock.deadline(at("2030-01-07T20:00"), Duration.ofMinutes(10), CHICAGO, null, null, List.of(open)));
    }

    @Test
    void holdsBeforeThePickOrAfterTheDeadlineDontCount() {
        var before = new PickClock.Hold(at("2030-01-07T19:00"), at("2030-01-07T19:30"));
        var after = new PickClock.Hold(at("2030-01-07T21:00"), null);
        assertEquals(at("2030-01-07T20:10"),
                PickClock.deadline(at("2030-01-07T20:00"), Duration.ofMinutes(10), CHICAGO, null, null, List.of(before, after)));
    }

    @Test
    void aPickThatComesUpDuringAHoldStartsWhenItEnds() {
        // An undo made while paused: its fresh timer starts at the resume.
        var hold = new PickClock.Hold(at("2030-01-07T20:00"), at("2030-01-07T20:20"));
        assertEquals(at("2030-01-07T20:25"), PickClock.deadline(at("2030-01-07T20:05"), Duration.ofMinutes(5), CHICAGO, null, null, List.of(hold)));
    }

    @Test
    void holdsAndTheOvernightPauseTogether() {
        // 2 hours from 21:00: 1 used by the 22:00 hold, which ends at 23:30 (inside the overnight pause),
        // so the second hour runs from 08:00.
        var hold = new PickClock.Hold(at("2030-01-07T22:00"), at("2030-01-07T23:30"));
        assertEquals(at("2030-01-08T09:00"), PickClock.deadline(at("2030-01-07T21:00"), Duration.ofHours(2), CHICAGO, NIGHT, MORNING, List.of(hold)));
    }

    @Test
    void badSettingsAreRefused() {
        Instant now = at("2030-01-07T20:00");
        assertThrows(IllegalArgumentException.class, () -> PickClock.deadline(now, Duration.ZERO, CHICAGO, null, null));
        assertThrows(IllegalArgumentException.class, () -> PickClock.deadline(now, Duration.ofMinutes(1), CHICAGO, NIGHT, null));
        assertThrows(IllegalArgumentException.class, () -> PickClock.deadline(now, Duration.ofMinutes(1), CHICAGO, NIGHT, NIGHT));
    }
}
