package com.ygoleague.season.transaction;

import com.ygoleague.season.Season;
import com.ygoleague.season.rules.SeasonRulesDocument.TransactionRules;
import com.ygoleague.season.rules.SeasonRulesService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Weekly transaction allowances (YGO-23, YGO-24): how many free-agent claims and trades a player
 * has left in a week.
 *
 * <ul>
 *   <li>Limits count per <b>allowance week</b>: the league week, except that week 0 (between the
 *       draft and week 1) counts as week 1. They reset when each week starts.
 *   <li>The base allowance is the rules version in effect for that week; null means unlimited.
 *   <li>Allowances can be traded (YGO-24): what a player received in trades that week is added,
 *       what they gave away is taken off. Trading isn't offered for an unlimited kind.
 *   <li>Used: their claims that week, and every executed trade they were a party to (either side).
 * </ul>
 */
@Component
public class WeeklyLimits {

    /** The two limited kinds of transaction; also the kinds of tradeable allowance. */
    public enum Kind { FREE_AGENCY, TRADE }

    /** @param left base + received - given - used, never below 0; null when unlimited */
    public record Allowance(Integer base, int received, int given, int used, Integer left) {
        public boolean unlimited() { return base == null; }

        public boolean has(int n) { return left == null || left >= n; }
    }

    private final SeasonRulesService rules;
    private final JdbcClient jdbc;

    public WeeklyLimits(SeasonRulesService rules, JdbcClient jdbc) {
        this.rules = rules;
        this.jdbc = jdbc;
    }

    /** Week 0 (between the draft and week 1) counts as week 1. */
    public static int allowanceWeek(int week) {
        return Math.max(week, 1);
    }

    /** The allowance week right now. */
    public int currentAllowanceWeek(Season season) {
        return allowanceWeek(rules.currentWeek(season));
    }

    public Integer base(Season season, Kind kind, int allowanceWeek) {
        TransactionRules t = rules.inEffect(season, allowanceWeek).transactions();
        return kind == Kind.FREE_AGENCY ? t.freeAgencyPerWeek() : t.tradesPerWeek();
    }

    public Allowance allowance(Season season, long playerId, Kind kind, int allowanceWeek) {
        Integer base = base(season, kind, allowanceWeek);
        int used = kind == Kind.FREE_AGENCY
                ? count("""
                        select count(*) from season_transaction where season_id = :s and kind = 'FREE_AGENCY'
                          and season_player_id = :p and greatest(week, 1) = :w""", season, playerId, allowanceWeek)
                : count("""
                        select count(*) from season_transaction where season_id = :s and kind = 'TRADE'
                          and (season_player_id = :p or counterparty_id = :p) and greatest(week, 1) = :w""", season, playerId, allowanceWeek);
        int received = sum(season, playerId, kind, allowanceWeek, "to_player_id");
        int given = sum(season, playerId, kind, allowanceWeek, "from_player_id");
        Integer left = base == null ? null : Math.max(0, base + received - given - used);
        return new Allowance(base, received, given, used, left);
    }

    private int count(String sql, Season season, long playerId, int week) {
        return jdbc.sql(sql).param("s", season.getId()).param("p", playerId).param("w", week).query(Integer.class).single();
    }

    private int sum(Season season, long playerId, Kind kind, int week, String side) {
        return jdbc.sql("""
                        select coalesce(sum(a.quantity), 0) from season_transaction_allowance a
                        join season_transaction t on t.id = a.transaction_id
                        where t.season_id = :s and a.kind = :k and a.week = :w and a.""" + side + " = :p")
                .param("s", season.getId()).param("k", kind.name()).param("w", week).param("p", playerId)
                .query(Integer.class).single();
    }
}
