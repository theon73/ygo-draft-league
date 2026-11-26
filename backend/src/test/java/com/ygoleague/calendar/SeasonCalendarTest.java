package com.ygoleague.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeasonCalendarTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    static final WeekBoundary MONDAY = new WeekBoundary(CHICAGO, DayOfWeek.MONDAY, LocalTime.MIDNIGHT);

    @Test
    void weekOneStartsAtTheFirstWeekStartOnOrAfterTheStartDate() {
        // 2027-01-04 is a Monday: week 1 starts that midnight.
        assertEquals("2027-01-04T00:00-06:00[America/Chicago]",
                new SeasonCalendar(MONDAY, LocalDate.parse("2027-01-04"), 3).firstWeekStart().toString());
        // A Wednesday start waits for the next Monday.
        assertEquals("2027-01-11T00:00-06:00[America/Chicago]",
                new SeasonCalendar(MONDAY, LocalDate.parse("2027-01-06"), 3).firstWeekStart().toString());
        // Tuesday 20:30 weeks: a Tuesday start date begins that evening.
        WeekBoundary tuesdayEvening = new WeekBoundary(CHICAGO, DayOfWeek.TUESDAY, LocalTime.of(20, 30));
        assertEquals("2027-01-05T20:30-06:00[America/Chicago]",
                new SeasonCalendar(tuesdayEvening, LocalDate.parse("2027-01-05"), 3).firstWeekStart().toString());
    }

    @Test
    void weeksAreNumberedBackToBack() {
        List<SeasonCalendar.SeasonWeek> weeks = new SeasonCalendar(MONDAY, LocalDate.parse("2027-01-04"), 3).weeks();
        assertEquals(List.of(1, 2, 3), weeks.stream().map(SeasonCalendar.SeasonWeek::number).toList());
        assertEquals(weeks.get(0).end(), weeks.get(1).start());
        assertEquals("2027-01-25T00:00-06:00[America/Chicago]", weeks.get(2).end().toString());
    }

    @Test
    void weekNumberAtIsPreSeasonThenWeeksThenOver() {
        SeasonCalendar calendar = new SeasonCalendar(MONDAY, LocalDate.parse("2027-01-04"), 2);
        assertEquals(0, calendar.weekNumberAt(at("2027-01-03T23:59")));
        assertEquals(1, calendar.weekNumberAt(at("2027-01-04T00:00")));
        assertEquals(1, calendar.weekNumberAt(at("2027-01-10T23:59")));
        assertEquals(2, calendar.weekNumberAt(at("2027-01-11T00:00")));
        assertEquals(3, calendar.weekNumberAt(at("2027-01-18T00:00")));   // past the last week
    }

    @Test
    void weeksKeepTheirLocalStartAcrossDaylightSaving() {
        // Clocks go forward on 2027-03-14 in Chicago.
        List<SeasonCalendar.SeasonWeek> weeks = new SeasonCalendar(MONDAY, LocalDate.parse("2027-03-08"), 2).weeks();
        assertEquals("2027-03-08T00:00-06:00[America/Chicago]", weeks.get(0).start().toString());
        assertEquals("2027-03-15T00:00-05:00[America/Chicago]", weeks.get(1).start().toString());
    }

    private static java.time.Instant at(String local) {
        return ZonedDateTime.of(LocalDateTime.parse(local), CHICAGO).toInstant();
    }
}
