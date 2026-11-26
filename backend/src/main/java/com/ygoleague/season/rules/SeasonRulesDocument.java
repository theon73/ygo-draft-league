package com.ygoleague.season.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

/**
 * A season's rules, as stored (JSON) in one {@code season_rules} version. Spring-free.
 *
 * <p>Design wide, build narrow: the settings catalog in PLANNING.md has a section per group
 * (pool, ownership, draft, transactions, matches, playoffs). Only the sections Phase 2 uses exist
 * so far; the others are added as their features are built, under a new {@link #SCHEMA_VERSION}
 * if the shape of existing sections changes.
 */
public record SeasonRulesDocument(CalendarRules calendar, FormatRules format, CompetitionRules competition) {

    /** Shape of the JSON. Bump when an existing section changes shape (new sections don't need it). */
    public static final int SCHEMA_VERSION = 1;

    public SeasonRulesDocument {
        calendar = calendar == null ? CalendarRules.EMPTY : calendar;
        format = format == null ? FormatRules.EMPTY : format;
        competition = competition == null ? CompetitionRules.EMPTY : competition;
    }

    public static final SeasonRulesDocument EMPTY = new SeasonRulesDocument(null, null, null);

    /**
     * When the season's weeks fall.
     *
     * @param startDate week 1 begins at the league's first week start on or after this date
     * @param weeks     the season's weeks in order. A list rather than a count so off weeks (Phase 4)
     *                  can sit between regular ones without changing the shape. Empty when the
     *                  competition format derives the weeks (a round-robin: from the player count,
     *                  until the season begins and the list is written out).
     * @param weekStart the league's week start, copied in when the season begins so later league
     *                  changes don't move a running season's weeks; null until then (the league's
     *                  current setting applies)
     */
    public record CalendarRules(LocalDate startDate, List<WeekPlan> weeks, WeekStartSnapshot weekStart) {
        public static final CalendarRules EMPTY = new CalendarRules(null, List.of(), null);

        public CalendarRules {
            weeks = weeks == null ? List.of() : List.copyOf(weeks);
        }

        public static CalendarRules of(LocalDate startDate, int regularWeeks, WeekStartSnapshot weekStart) {
            return new CalendarRules(startDate, Collections.nCopies(regularWeeks, new WeekPlan(WeekKind.REGULAR)), weekStart);
        }

        public int regularWeeks() {
            return (int) weeks.stream().filter(w -> w.kind() == WeekKind.REGULAR).count();
        }

        /** Both a start date and at least one week: the calendar can be laid out. */
        public boolean isComplete() {
            return startDate != null && !weeks.isEmpty();
        }
    }

    public record WeekPlan(WeekKind kind) {}

    /** Only regular weeks so far; {@code OFF} (no matches, e.g. holidays) comes in Phase 4. */
    public enum WeekKind { REGULAR }

    public record WeekStartSnapshot(String timeZone, DayOfWeek day, LocalTime time) {}

    /**
     * The format the season is played in.
     *
     * @param tcgCutoff         cards released in the TCG on or before this date make up the dated pool
     * @param banListRevisionId the exact ban list revision in force (a list's later revisions don't
     *                          change it); null for no ban list
     */
    public record FormatRules(LocalDate tcgCutoff, Long banListRevisionId) {
        public static final FormatRules EMPTY = new FormatRules(null, null);
    }

    /**
     * How the regular season's matches are scheduled.
     *
     * @param format           null until chosen
     * @param roundRobinCycles round-robin only: how many times each pair of players meets
     *                         (1 = single round-robin, 2 = double)
     */
    public record CompetitionRules(CompetitionFormat format, Integer roundRobinCycles) {
        public static final CompetitionRules EMPTY = new CompetitionRules(null, null);

        /** The weeks come from the format (and player count) rather than an explicit week count. */
        public boolean derivesWeeks() {
            return format == CompetitionFormat.ROUND_ROBIN;
        }
    }

    /**
     * Only round-robin so far. Swiss, weekly head-to-head and ladder come later (§0 scope
     * decisions); they'll use an explicit week count in the calendar.
     */
    public enum CompetitionFormat { ROUND_ROBIN }
}
