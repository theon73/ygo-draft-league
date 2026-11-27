package com.ygoleague.season.schedule;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.competition.RoundRobinSchedule;
import com.ygoleague.competition.RoundRobinSchedule.Pairing;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.SeatOrder;
import com.ygoleague.season.rules.SeasonRulesDocument;
import com.ygoleague.season.rules.SeasonRulesDocument.CompetitionFormat;
import com.ygoleague.season.rules.SeasonRulesService;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The regular season's schedule (YGO-25).
 *
 * <ul>
 *   <li><b>Generated automatically when the draft completes</b> (decided 2026-11-26), from a random
 *       draw of the players, recorded like seat draws. One round-robin round a week, week = round.
 *       The layout is {@link RoundRobinSchedule}: every pair meets once per cycle, byes spread one per
 *       player per cycle, and nobody meets the same opponent (or sits out) in consecutive weeks.
 *   <li><b>Reshuffling:</b> a commissioner can draw again until week 1 begins (before any match can
 *       have been played); after that the schedule is fixed. Seasons that became active before
 *       YGO-25 have none yet, so a commissioner can generate one (the same draw).
 *   <li>Any member sees the whole schedule; the current week is marked ("this week's matchups").
 *   <li>Best-of comes from the rules in effect for each week.
 * </ul>
 */
@Service
public class ScheduleService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record PlayerRef(String memberId, String name, String teamName, boolean you) {}

    /** {@code b} is null for a bye ({@code a} sits the week out). */
    public record MatchView(PlayerRef a, PlayerRef b, boolean mine) {}

    public record WeekView(int week, OffsetDateTime start, OffsetDateTime end, int bestOf, boolean current, List<MatchView> matches) {}

    /**
     * @param generated      there's a schedule
     * @param canGenerate    the caller can generate it now (or draw again, when one exists)
     * @param why            why it can't be generated or redrawn now, if it can't (for commissioners)
     * @param currentWeek    0 before week 1; the week count + 1 once the regular season is over
     * @param drawnBy        who made the latest draw; null = automatically when the draft completed
     * @param draws          how many draws there have been (reshuffles + 1)
     */
    public record ScheduleView(boolean generated, boolean canManage, boolean canGenerate, String why, int currentWeek,
                               Instant drawnAt, String drawnBy, int draws, List<WeekView> weeks) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public ScheduleService(SeasonAccess access, SeasonRulesService rules, PublicIds publicIds, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ScheduleView view(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        return view(ref.season(), ref.me().getId(), ref.commissioner());
    }

    /** A commissioner draws the schedule: the first time for a season without one, or again before week 1. */
    @Transactional
    public ScheduleView generate(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(leagueId, seasonId, caller);
        Season season = ref.season();
        jdbc.sql("select id from season where id = :s for update").param("s", season.getId()).query(Long.class).single();
        String why = whyNot(season);
        if (why != null) throw new ResponseStatusException(HttpStatus.CONFLICT, why);
        draw(season, caller.userId());
        return view(season, ref.me().getId(), true);
    }

    /**
     * Called as the draft completes, in its transaction: draws the regular season. Does nothing if
     * the rules don't describe a schedule (no round-robin, or fewer than two players), so it can
     * never stop the draft from finishing.
     */
    public void generateAutomatically(Season season) {
        if (schedulable(season) != null) return;
        draw(season, null);
    }

    // ---- Drawing ------------------------------------------------------------------------------

    private void draw(Season season, Long userId) {
        SeasonRulesDocument.CompetitionRules comp = rules.latest(season).competition();
        List<Long> players = jdbc.sql("select id from season_player where season_id = :s order by seat nulls last, id")
                .param("s", season.getId()).query(Long.class).list();
        List<Long> order = SeatOrder.shuffled(players, RANDOM);

        long stage = jdbc.sql("""
                        insert into stage (season_id, kind, format) values (:s, 'REGULAR', 'ROUND_ROBIN')
                        on conflict (season_id, kind) do update set format = excluded.format returning id""")
                .param("s", season.getId()).query(Long.class).single();
        jdbc.sql("delete from season_match where stage_id = :st").param("st", stage).update();
        jdbc.sql("insert into schedule_draw (stage_id, player_order, drawn_by) values (:st, cast(:o as bigint[]), :u)")
                .param("st", stage).param("o", "{" + String.join(",", order.stream().map(String::valueOf).toList()) + "}")
                .param("u", userId, java.sql.Types.BIGINT).update();

        List<List<Pairing<Long>>> rounds = RoundRobinSchedule.rounds(order, comp.roundRobinCycles());
        for (int r = 0; r < rounds.size(); r++) {
            for (Pairing<Long> p : rounds.get(r)) {
                jdbc.sql("""
                                insert into season_match (season_id, stage_id, week, round, player_a_id, player_b_id)
                                values (:s, :st, :w, :w, :a, :b)""")
                        .param("s", season.getId()).param("st", stage).param("w", r + 1).param("a", p.a())
                        .param("b", p.b(), java.sql.Types.BIGINT).update();
            }
        }
    }

    /** Why the rules can't give a schedule, or null if they can. */
    private String schedulable(Season season) {
        SeasonRulesDocument.CompetitionRules comp = rules.latest(season).competition();
        if (comp.format() != CompetitionFormat.ROUND_ROBIN || comp.roundRobinCycles() == null) {
            return "The rules don't set a round-robin, so there's nothing to schedule";
        }
        long players = jdbc.sql("select count(*) from season_player where season_id = :s").param("s", season.getId()).query(Long.class).single();
        if (players < 2) return "A schedule needs at least two players";
        return null;
    }

    /** Why a commissioner can't (re)draw now, or null if they can. */
    private String whyNot(Season season) {
        if (season.getStatus() == SeasonStatus.SETUP || season.getStatus() == SeasonStatus.DRAFTING) {
            return "The schedule is drawn when the draft completes";
        }
        if (season.getStatus() == SeasonStatus.COMPLETE) return "The season is over";
        String rulesProblem = schedulable(season);
        if (rulesProblem != null) return rulesProblem;
        if (stageId(season).isPresent() && rules.currentWeek(season) >= 1) return "Week 1 has begun, so the schedule is fixed";
        return null;
    }

    // ---- Reading ------------------------------------------------------------------------------

    private Optional<Long> stageId(Season season) {
        return jdbc.sql("select id from stage where season_id = :s and kind = 'REGULAR'").param("s", season.getId()).query(Long.class).optional();
    }

    private ScheduleView view(Season season, long myMemberId, boolean commissioner) {
        int current = rules.currentWeek(season);
        Optional<Long> stage = stageId(season);
        String why = commissioner ? whyNot(season) : null;
        if (stage.isEmpty()) return new ScheduleView(false, commissioner, commissioner && why == null, why, current, null, null, 0, List.of());

        record Draw(Instant at, String by, int count) {}
        Draw draw = jdbc.sql("""
                        select d.drawn_at, u.display_name, (select count(*) from schedule_draw x where x.stage_id = d.stage_id)
                        from schedule_draw d left join app_user u on u.id = d.drawn_by
                        where d.stage_id = :st order by d.drawn_at desc, d.id desc limit 1""")
                .param("st", stage.get())
                .query((rs, n) -> new Draw(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getInt(3))).single();

        Map<Long, PlayerRef> players = new LinkedHashMap<>();
        jdbc.sql("""
                        select sp.id, m.id, m.display_name, sp.team_name from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s""")
                .param("s", season.getId())
                .query(rs -> {
                    players.put(rs.getLong(1), new PlayerRef(publicIds.encode(PublicIdType.LEAGUE_MEMBER, rs.getLong(2)), rs.getString(3),
                            rs.getString(4), rs.getLong(2) == myMemberId));
                });

        Map<Integer, List<MatchView>> byWeek = new LinkedHashMap<>();
        jdbc.sql("select week, player_a_id, player_b_id from season_match where stage_id = :st order by week, player_b_id is null, id")
                .param("st", stage.get())
                .query(rs -> {
                    PlayerRef a = players.get(rs.getLong(2));
                    long bId = rs.getLong(3);
                    PlayerRef b = rs.wasNull() ? null : players.get(bId);
                    byWeek.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(new MatchView(a, b, a.you() || (b != null && b.you())));
                });

        Map<Integer, SeasonRulesService.WeekView> dates = new LinkedHashMap<>();
        for (SeasonRulesService.WeekView w : rules.weeks(season)) dates.put(w.number(), w);
        List<WeekView> weeks = new ArrayList<>();
        // Week 0 (before week 1) shows week 1 as "this week's" matchups to come.
        int highlighted = Math.max(current, 1);
        for (var e : byWeek.entrySet()) {
            SeasonRulesService.WeekView d = dates.get(e.getKey());
            weeks.add(new WeekView(e.getKey(), d == null ? null : d.start(), d == null ? null : d.end(),
                    rules.inEffect(season, e.getKey()).competition().bestOf(), e.getKey() == highlighted, e.getValue()));
        }
        return new ScheduleView(true, commissioner, commissioner && why == null, why, current, draw.at(), draw.by(), draw.count(), weeks);
    }
}
