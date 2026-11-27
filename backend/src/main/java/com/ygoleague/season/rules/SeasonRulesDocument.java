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
public record SeasonRulesDocument(CalendarRules calendar, FormatRules format, CompetitionRules competition, DraftRules draft,
                                  TransactionRules transactions) {

    /** Shape of the JSON. Bump when an existing section changes shape (new sections don't need it). */
    public static final int SCHEMA_VERSION = 1;

    public SeasonRulesDocument {
        calendar = calendar == null ? CalendarRules.EMPTY : calendar;
        format = format == null ? FormatRules.EMPTY : format;
        competition = competition == null ? CompetitionRules.EMPTY : competition;
        draft = draft == null ? DraftRules.EMPTY : draft;
        transactions = transactions == null ? TransactionRules.EMPTY : transactions;
    }

    public static final SeasonRulesDocument EMPTY = new SeasonRulesDocument(null, null, null, null, null);

    public SeasonRulesDocument withCalendar(CalendarRules c) { return new SeasonRulesDocument(c, format, competition, draft, transactions); }

    public SeasonRulesDocument withDraft(DraftRules d) { return new SeasonRulesDocument(calendar, format, competition, d, transactions); }

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

    /**
     * How the draft runs. Only snake drafts so far (auction and pack drafts later, §0).
     *
     * @param order              snake (direction alternates each round) or linear (seat order every round)
     * @param rounds             contiguous blocks of rounds from round 1, each with its picks per turn
     *                           (e.g. rounds 1-10 one pick, 11-25 two picks)
     * @param scheduledStart     when the draft is planned to start; informational (a commissioner starts it)
     * @param forbiddenDraftable whether cards Forbidden on the season's ban list can be drafted; off by
     *                           default, since in exclusive ownership they'd be a wasted pick
     * @param pickTimer          the pick timer, or null for none
     */
    public record DraftRules(DraftType type, DraftOrder order, List<RoundBlock> rounds, java.time.Instant scheduledStart,
                             boolean forbiddenDraftable, PickTimer pickTimer) {
        public static final DraftRules EMPTY = new DraftRules(DraftType.SNAKE, DraftOrder.SNAKE, List.of(), null, false, null);

        public DraftRules {
            type = type == null ? DraftType.SNAKE : type;
            order = order == null ? DraftOrder.SNAKE : order;
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
        }

        public int roundCount() {
            return rounds.isEmpty() ? 0 : rounds.getLast().toRound();
        }

        /** Picks each player makes over the whole draft. */
        public int picksPerPlayer() {
            return rounds.stream().mapToInt(r -> (r.toRound() - r.fromRound() + 1) * r.picksPerTurn()).sum();
        }
    }

    /**
     * The pick timer (YGO-18); null in {@link DraftRules} means no timer. Each pick gets the full
     * time, counted from when it comes on the clock.
     *
     * @param seconds   time per pick
     * @param onExpiry  what happens when it runs out
     * @param pauseFrom optional overnight pause in the league's time zone, e.g. 23:00 to 08:00 (may
     *                  wrap midnight; both or neither): the clock doesn't run inside it
     */
    public record PickTimer(int seconds, TimerExpiry onExpiry, java.time.LocalTime pauseFrom, java.time.LocalTime pauseTo) {
        public PickTimer {
            onExpiry = onExpiry == null ? TimerExpiry.WAIT : onExpiry;
        }
    }

    /**
     * WAIT: the pick stays open (shown as overdue). SKIP: the draft moves on and the player makes the
     * pick up later. AUTO_PICK: the first available card in the player's queue, or a skip if none.
     */
    public enum TimerExpiry { WAIT, SKIP, AUTO_PICK }

    public enum DraftType { SNAKE }

    public enum DraftOrder { SNAKE, LINEAR }

    public record RoundBlock(int fromRound, int toRound, int picksPerTurn) {}

    /**
     * Trades and free agency (YGO-23). Limits count per league week, and week 0 (between the draft
     * and week 1) shares week 1's allowance: capacity resets when each week of matches ends.
     *
     * @param freeAgencyPerWeek    free-agent claims (each a pick-up with its matching drop) a player may
     *                             make per week; null = unlimited, 0 = none
     * @param tradesPerWeek        trades a player may make per week (each counts for both sides); null = unlimited
     * @param tradeDeadlineWeek    the last week trades can be made in; null = no deadline
     * @param commissionerApproval accepted trades wait for a commissioner's approval before they execute
     */
    public record TransactionRules(Integer freeAgencyPerWeek, Integer tradesPerWeek, Integer tradeDeadlineWeek,
                                   Boolean commissionerApproval) {
        public static final TransactionRules EMPTY = new TransactionRules(null, null, null, false);

        /** Rules saved before a field existed read it as missing (null): approval is then off. */
        public TransactionRules {
            commissionerApproval = Boolean.TRUE.equals(commissionerApproval);
        }
    }
}
