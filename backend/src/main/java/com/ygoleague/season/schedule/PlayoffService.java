package com.ygoleague.season.schedule;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.competition.Bracket;
import com.ygoleague.notify.DiscordText;
import com.ygoleague.notify.NotificationEvent;
import com.ygoleague.notify.TransactionAnnouncer;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.rules.SeasonRulesDocument;
import com.ygoleague.season.rules.SeasonRulesService;
import com.ygoleague.season.schedule.ScheduleService.GameView;
import com.ygoleague.season.schedule.ScheduleService.PlayerRef;
import com.ygoleague.season.schedule.ScheduleService.ResultView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Playoffs and completing the season (YGO-27).
 *
 * <ul>
 *   <li><b>Settings</b> are the season's {@code playoffs} rules: on or off, how many qualify
 *       directly, a play-in of 0, 2 or 4 teams, and best-ofs for the playoffs and the play-in
 *       (defaulting to the regular season's). The shape is {@link Bracket}.
 *   <li><b>A commissioner starts the playoffs</b> once every regular-season match has a final
 *       result. Seeding is frozen from the standings then, and <b>trades and free agency close</b>.
 *   <li><b>No set weeks:</b> a match gets its players as earlier results become final, and has
 *       <b>one week</b> from then ({@code deadline_at}); finishing early moves things on at once.
 *       Overdue matches stay open, like regular ones; commissioners can settle them.
 *   <li>Playoff results go through the usual report / confirm flow, but need a winner (no draws, no
 *       double forfeit). Slots are recomputed from results every time one becomes final
 *       ({@link #advance}), so a commissioner's correction moves the right player on, as long as the
 *       next match hasn't had a result reported yet.
 *   <li><b>Completing:</b> the final's result makes its winner champion and completes the season.
 *       Without playoffs, a commissioner completes it and the top of the standings is champion.
 * </ul>
 */
@Service
public class PlayoffService {

    static final Duration MATCH_WINDOW = Duration.ofDays(7);

    public record SeedView(int seed, PlayerRef player, boolean playIn) {}

    /**
     * A bracket match. {@code a} / {@code b} are null until known; {@code aFrom} / {@code bFrom} say
     * where they come from meanwhile ("Seed 5", "Winner of Play-in: 5 v 6", …).
     */
    public record BracketMatch(String id, String stage, int round, String label, Integer seedA, Integer seedB, PlayerRef a, PlayerRef b,
                               String aFrom, String bFrom, int bestOf, Instant readyAt, Instant deadline, boolean overdue,
                               ResultView result, PlayerRef winner, boolean mine, boolean canReport, boolean canConfirm, boolean canEnter,
                               SlotSource aSource, SlotSource bSource) {}

    /**
     * Where a bracket slot's player comes from, as data for drawing the bracket (YGO-30): a seed
     * ({@code kind} SEED, {@code seed}), or the WINNER / LOSER of an earlier match ({@code matchId}).
     */
    public record SlotSource(String kind, Integer seed, String matchId) {}

    /**
     * @param settings  the playoff rules (null fields = defaults)
     * @param shape     e.g. "Seeds 1-4 qualify; 5-8 play in for 2 spots; seeds 1 and 2 have byes"
     * @param started   when the playoffs started (seeding frozen), or null
     * @param canStart  the caller can start them now; {@code why}: why not, otherwise
     * @param seeds     frozen seeds once started; before that, a preview from the current standings
     * @param preview   before the start: the bracket those seeds would make (ids "preview-N"; nothing can be
     *                  reported on it), so the tree can be drawn early (YGO-30). Empty once started.
     */
    public record PlayoffView(boolean enabled, SeasonRulesDocument.PlayoffRules settings, String shape, int playoffBestOf, int playInBestOf,
                              Instant started, boolean canStart, boolean canComplete, String why, List<SeedView> seeds,
                              List<BracketMatch> matches, PlayerRef champion, boolean complete, List<BracketMatch> preview) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final StandingsService standings;
    private final PublicIds publicIds;
    private final TransactionAnnouncer announcer;
    private final JdbcClient jdbc;

    public PlayoffService(SeasonAccess access, SeasonRulesService rules, StandingsService standings, PublicIds publicIds,
                          TransactionAnnouncer announcer, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.standings = standings;
        this.publicIds = publicIds;
        this.announcer = announcer;
        this.jdbc = jdbc;
    }

    // ---- Reading ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PlayoffView view(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        return view(ref.season(), ref.me().getId(), ref.commissioner());
    }

    private PlayoffView view(Season season, long myMemberId, boolean commissioner) {
        SeasonRulesDocument doc = rules.latest(season);
        SeasonRulesDocument.PlayoffRules p = doc.playoffs();
        int regular = doc.competition().bestOf();
        Map<Long, PlayerRef> players = players(season, myMemberId);
        Optional<Long> stage = stage(season, "PLAYOFFS");
        Instant started = stage.map(st -> jdbc.sql("select started_at from stage where id = :st").param("st", st)
                .query((rs, n) -> ts(rs.getTimestamp(1))).list().getFirst()).orElse(null);

        List<SeedView> seeds = new ArrayList<>();
        int playInFrom = p.on() ? p.directQualifiers() + 1 : Integer.MAX_VALUE;
        if (stage.isPresent()) {
            jdbc.sql("select seed, season_player_id from playoff_seed where stage_id = :st order by seed").param("st", stage.get())
                    .query(rs -> { seeds.add(new SeedView(rs.getInt(1), players.get(rs.getLong(2)), rs.getInt(1) >= playInFrom)); });
        } else if (p.on()) {
            List<Long> order = standings.order(season);
            for (int i = 0; i < Math.min(order.size(), p.directQualifiers() + p.playIn()); i++) {
                seeds.add(new SeedView(i + 1, players.get(order.get(i)), i + 1 >= playInFrom));
            }
        }

        String why = commissioner && p.on() ? whyNotStart(season, p) : null;
        // A list, not single(): the column is null until the season completes, and single() refuses null.
        Long champion = jdbc.sql("select champion_player_id from season where id = :s").param("s", season.getId())
                .query((rs, n) -> (Long) rs.getObject(1)).list().getFirst();
        boolean complete = season.getStatus() == SeasonStatus.COMPLETE;
        String whyNotComplete = commissioner ? whyNotComplete(season, p) : "";
        return new PlayoffView(p.on(), p, p.on() ? shape(p) : null, p.playoffBestOf(regular), p.playInBestOf(regular), started,
                commissioner && p.on() && why == null, commissioner && whyNotComplete == null, p.on() ? why : whyNotComplete, seeds,
                matches(season, players, commissioner), champion == null ? null : players.get(champion), complete,
                started == null && p.on() && !complete ? preview(p, seeds, regular) : List.of());
    }

    /** The bracket the previewed seeds would make; the same layout as {@link #start}, with nothing stored. */
    private static List<BracketMatch> preview(SeasonRulesDocument.PlayoffRules p, List<SeedView> seeds, int regular) {
        List<Bracket.Match> layout;
        try {
            layout = Bracket.layout(p.directQualifiers(), p.playIn());
        } catch (IllegalArgumentException e) {
            return List.of();   // settings that can't make a bracket; the commissioner sees why elsewhere
        }
        List<BracketMatch> out = new ArrayList<>();
        for (int i = 0; i < layout.size(); i++) {
            Bracket.Match m = layout.get(i);
            Integer seedA = m.a() instanceof Bracket.Seed sa ? sa.seed() : null;
            Integer seedB = m.b() instanceof Bracket.Seed sb ? sb.seed() : null;
            boolean playIn = m.kind() == Bracket.Kind.PLAY_IN;
            out.add(new BracketMatch("preview-" + i, playIn ? "PLAY_IN" : "PLAYOFFS", m.round(), m.label(), seedA, seedB,
                    seedPlayer(seeds, seedA), seedPlayer(seeds, seedB), previewFrom(m.a(), layout), previewFrom(m.b(), layout),
                    playIn ? p.playInBestOf(regular) : p.playoffBestOf(regular), null, null, false, null, null, false, false, false, false,
                    previewSource(m.a()), previewSource(m.b())));
        }
        return out;
    }

    private static PlayerRef seedPlayer(List<SeedView> seeds, Integer seed) {
        return seed == null || seed > seeds.size() ? null : seeds.get(seed - 1).player();
    }

    private static String previewFrom(Bracket.Source src, List<Bracket.Match> layout) {
        return switch (src) {
            case Bracket.Seed s -> "Seed " + s.seed();
            case Bracket.Winner w -> "Winner of " + layout.get(w.match()).label();
            case Bracket.Loser l -> "Loser of " + layout.get(l.match()).label();
        };
    }

    private static SlotSource previewSource(Bracket.Source src) {
        return switch (src) {
            case Bracket.Seed s -> new SlotSource("SEED", s.seed(), null);
            case Bracket.Winner w -> new SlotSource("WINNER", null, "preview-" + w.match());
            case Bracket.Loser l -> new SlotSource("LOSER", null, "preview-" + l.match());
        };
    }

    /** "Seeds 1-4 qualify directly; 5-8 play in for 2 spots; seeds 1 and 2 have byes." */
    static String shape(SeasonRulesDocument.PlayoffRules p) {
        int d = p.directQualifiers();
        int in = p.playIn();
        int bracket = d + in / 2;
        int size = Integer.highestOneBit(bracket) == bracket ? bracket : Integer.highestOneBit(bracket) << 1;
        int byes = size - bracket;
        StringBuilder sb = new StringBuilder(d == 1 ? "Seed 1 qualifies directly" : "Seeds 1-" + d + " qualify directly");
        if (in == 2) sb.append("; ").append(d + 1).append(" v ").append(d + 2).append(" play in for 1 spot");
        if (in == 4) sb.append("; ").append(d + 1).append("-").append(d + 4).append(" play in for 2 spots");
        sb.append("; ").append(bracket).append(" in the bracket");
        if (byes == 1) sb.append(", seed 1 has a bye");
        else if (byes > 1) sb.append(", seeds 1").append(byes == 2 ? " and 2" : "-" + byes).append(" have byes");
        return sb.append('.').toString();
    }

    private List<BracketMatch> matches(Season season, Map<Long, PlayerRef> players, boolean commissioner) {
        record Row(long id, String stage, int round, String label, Long a, Long b, String slotA, String slotB, int bestOf, Instant ready,
                   Instant deadline, ResultView result, Long reportedPlayer) {}
        List<Row> rows = jdbc.sql("""
                        select m.id, st.kind, m.bracket_round, m.label, m.player_a_id, m.player_b_id, m.slot_a, m.slot_b, m.best_of, m.ready_at,
                               m.deadline_at, r.games_a, r.games_b, r.forfeit, r.status, r.auto_confirmed, r.by_commissioner, r.reported_player,
                               r.replays, r.game_notes, r.dispute_note, r.reported_at
                        from season_match m join stage st on st.id = m.stage_id
                        left join match_report r on r.match_id = m.id and r.status <> 'SUPERSEDED'
                        where m.season_id = :s and st.kind in ('PLAY_IN', 'PLAYOFFS') order by m.ordinal""")
                .param("s", season.getId())
                .query((rs, n) -> {
                    ResultView result = null;
                    Long reporter = (Long) rs.getObject(18);
                    if (rs.getString(15) != null) {
                        PlayerRef rp = reporter == null ? null : players.get(reporter);
                        result = new ResultView(rs.getInt(12), rs.getInt(13), rs.getString(14), rs.getString(15), rs.getBoolean(16),
                                rs.getBoolean(17), rp == null ? null : rp.name(),
                                games((String[]) rs.getArray(19).getArray(), (String[]) rs.getArray(20).getArray()), rs.getString(21),
                                rs.getTimestamp(22).toInstant());
                    }
                    return new Row(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), (Long) rs.getObject(5), (Long) rs.getObject(6),
                            rs.getString(7), rs.getString(8), rs.getInt(9), ts(rs.getTimestamp(10)), ts(rs.getTimestamp(11)), result, reporter);
                })
                .list();
        Map<Long, String> labels = new HashMap<>();
        for (Row r : rows) labels.put(r.id(), r.label());
        Map<Long, Integer> seedOf = new HashMap<>();
        jdbc.sql("""
                        select ps.season_player_id, ps.seed from playoff_seed ps join stage st on st.id = ps.stage_id
                        where st.season_id = :s and st.kind = 'PLAYOFFS'""")
                .param("s", season.getId()).query(rs -> { seedOf.put(rs.getLong(1), rs.getInt(2)); });

        Instant now = Instant.now();
        boolean active = season.getStatus() == SeasonStatus.ACTIVE;
        List<BracketMatch> out = new ArrayList<>();
        for (Row r : rows) {
            PlayerRef a = r.a() == null ? null : players.get(r.a());
            PlayerRef b = r.b() == null ? null : players.get(r.b());
            String status = r.result() == null ? null : r.result().status();
            boolean mine = (a != null && a.you()) || (b != null && b.you());
            boolean ready = a != null && b != null;
            boolean reportedByMe = r.reportedPlayer() != null && players.get(r.reportedPlayer()).you();
            PlayerRef winner = null;
            if ("CONFIRMED".equals(status)) {
                Long w = winnerOf(r.a(), r.b(), r.result().gamesA(), r.result().gamesB(), r.result().forfeit());
                winner = w == null ? null : players.get(w);
            }
            out.add(new BracketMatch(publicIds.encode(PublicIdType.MATCH, r.id()), r.stage(), r.round(), r.label(),
                    r.a() == null ? null : seedOf.get(r.a()), r.b() == null ? null : seedOf.get(r.b()), a, b,
                    describe(r.slotA(), labels), describe(r.slotB(), labels), r.bestOf(), r.ready(), r.deadline(),
                    ready && !"CONFIRMED".equals(status) && r.deadline() != null && r.deadline().isBefore(now), r.result(), winner, mine,
                    active && ready && mine && !"CONFIRMED".equals(status) && !("PENDING".equals(status) && !reportedByMe),
                    active && ready && mine && "PENDING".equals(status) && !reportedByMe,
                    commissioner && active && ready,
                    source(r.slotA()), source(r.slotB())));
        }
        return out;
    }

    private SlotSource source(String slot) {
        if (slot == null) return null;
        String[] p = slot.split(":");
        return "SEED".equals(p[0])
                ? new SlotSource("SEED", Integer.parseInt(p[1]), null)
                : new SlotSource(p[0], null, publicIds.encode(PublicIdType.MATCH, Long.parseLong(p[1])));
    }

    private static String describe(String slot, Map<Long, String> labels) {
        if (slot == null) return null;
        String[] p = slot.split(":");
        return switch (p[0]) {
            case "SEED" -> "Seed " + p[1];
            case "WINNER" -> "Winner of " + labels.getOrDefault(Long.parseLong(p[1]), "an earlier match");
            default -> "Loser of " + labels.getOrDefault(Long.parseLong(p[1]), "an earlier match");
        };
    }

    // ---- Starting -----------------------------------------------------------------------------

    @Transactional
    public PlayoffView start(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(leagueId, seasonId, caller);
        Season season = ref.season();
        lock(season);
        SeasonRulesDocument doc = rules.latest(season);
        SeasonRulesDocument.PlayoffRules p = doc.playoffs();
        String why = whyNotStart(season, p);
        if (why != null) throw conflict(why);

        int regular = doc.competition().bestOf();
        List<Long> order = standings.order(season);
        long playoffs = createStage(season, "PLAYOFFS", caller.userId());
        Long playIn = p.playIn() > 0 ? createStage(season, "PLAY_IN", caller.userId()) : null;
        int seeds = p.directQualifiers() + p.playIn();
        for (int i = 0; i < seeds; i++) {
            jdbc.sql("insert into playoff_seed (stage_id, seed, season_player_id) values (:st, :seed, :p)")
                    .param("st", playoffs).param("seed", i + 1).param("p", order.get(i)).update();
        }

        List<Bracket.Match> layout = Bracket.layout(p.directQualifiers(), p.playIn());
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < layout.size(); i++) {
            Bracket.Match m = layout.get(i);
            boolean isPlayIn = m.kind() == Bracket.Kind.PLAY_IN;
            ids.add(jdbc.sql("""
                            insert into season_match (season_id, stage_id, slot_a, slot_b, label, bracket_round, best_of, ordinal)
                            values (:s, :st, :a, :b, :label, :round, :bo, :ord) returning id""")
                    .param("s", season.getId()).param("st", isPlayIn ? playIn : playoffs)
                    .param("a", slot(m.a(), ids)).param("b", slot(m.b(), ids)).param("label", m.label()).param("round", m.round())
                    .param("bo", isPlayIn ? p.playInBestOf(regular) : p.playoffBestOf(regular)).param("ord", i + 1)
                    .query(Long.class).single());
        }
        advance(season);
        announcer.announce(season, NotificationEvent.RESULTS, "playoffs", "🏁 The playoffs have started: " + shape(p));
        return view(season, ref.me().getId(), true);
    }

    private static String slot(Bracket.Source src, List<Long> ids) {
        return switch (src) {
            case Bracket.Seed s -> "SEED:" + s.seed();
            case Bracket.Winner w -> "WINNER:" + ids.get(w.match());
            case Bracket.Loser l -> "LOSER:" + ids.get(l.match());
        };
    }

    private long createStage(Season season, String kind, long userId) {
        return jdbc.sql("""
                        insert into stage (season_id, kind, format, started_at, started_by) values (:s, :k, 'SINGLE_ELIMINATION', now(), :u)
                        returning id""")
                .param("s", season.getId()).param("k", kind).param("u", userId).query(Long.class).single();
    }

    /** Why the playoffs can't start now (null if they can). */
    private String whyNotStart(Season season, SeasonRulesDocument.PlayoffRules p) {
        if (season.getStatus() == SeasonStatus.COMPLETE) return "The season is over";
        if (!p.on()) return "This season has no playoffs (turn them on in the Rules tab)";
        if (stage(season, "PLAYOFFS").isPresent()) return "The playoffs have started";
        if (season.getStatus() != SeasonStatus.ACTIVE) return "The playoffs start after the regular season";
        int players = jdbc.sql("select count(*) from season_player where season_id = :s").param("s", season.getId()).query(Integer.class).single();
        int needed = p.directQualifiers() + p.playIn();
        if (players < needed) return "The playoffs need " + needed + " players, and this season has " + players;
        String unfinished = unfinishedRegular(season);
        return unfinished;
    }

    /** Null if every regular-season match is final; otherwise how many aren't. */
    private String unfinishedRegular(Season season) {
        Optional<Long> regular = stage(season, "REGULAR");
        if (regular.isEmpty()) return "There's no regular-season schedule yet";
        int open = jdbc.sql("""
                        select count(*) from season_match m where m.stage_id = :st and m.player_b_id is not null
                          and not exists (select 1 from match_report r where r.match_id = m.id and r.status = 'CONFIRMED')""")
                .param("st", regular.get()).query(Integer.class).single();
        return open == 0 ? null
                : open + " regular-season match" + (open == 1 ? " doesn't" : "es don't") + " have a final result yet";
    }

    // ---- Advancing and completing -------------------------------------------------------------

    /**
     * Fills bracket slots from seeds and final results, in bracket order; a match gets its week when
     * both its players are known. Recomputed from scratch, so a corrected result moves the right
     * player on; refused if that would change a match that already has a reported result. Completes
     * the season when the final has a winner. Called after every playoff result becomes final.
     */
    public void advance(Season season) {
        record M(long id, String slotA, String slotB, Long a, Long b, Instant ready, String label) {}
        List<M> ms = jdbc.sql("""
                        select m.id, m.slot_a, m.slot_b, m.player_a_id, m.player_b_id, m.ready_at, m.label
                        from season_match m join stage st on st.id = m.stage_id
                        where m.season_id = :s and st.kind in ('PLAY_IN', 'PLAYOFFS') order by m.ordinal""")
                .param("s", season.getId())
                .query((rs, n) -> new M(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), (Long) rs.getObject(5),
                        ts(rs.getTimestamp(6)), rs.getString(7)))
                .list();
        if (ms.isEmpty()) return;
        Map<Integer, Long> seeds = new HashMap<>();
        jdbc.sql("""
                        select ps.seed, ps.season_player_id from playoff_seed ps join stage st on st.id = ps.stage_id
                        where st.season_id = :s and st.kind = 'PLAYOFFS'""")
                .param("s", season.getId()).query(rs -> { seeds.put(rs.getInt(1), rs.getLong(2)); });

        Map<Long, Long[]> outcome = new LinkedHashMap<>();   // match id -> {winner, loser}, when final
        Long champion = null;
        for (int i = 0; i < ms.size(); i++) {
            M m = ms.get(i);
            Long a = resolve(m.slotA(), seeds, outcome);
            Long b = resolve(m.slotB(), seeds, outcome);
            if (!java.util.Objects.equals(a, m.a()) || !java.util.Objects.equals(b, m.b())) {
                boolean reported = jdbc.sql("select exists (select 1 from match_report where match_id = :m and status <> 'SUPERSEDED')")
                        .param("m", m.id()).query(Boolean.class).single();
                if (reported) throw conflict(m.label() + " already has a result for its current players; correct that first");
                Instant now = Instant.now();
                boolean ready = a != null && b != null;
                jdbc.sql("update season_match set player_a_id = :a, player_b_id = :b, ready_at = :r, deadline_at = :d where id = :m")
                        .param("a", a, java.sql.Types.BIGINT).param("b", b, java.sql.Types.BIGINT)
                        .param("r", ready ? java.sql.Timestamp.from(now) : null, java.sql.Types.TIMESTAMP)
                        .param("d", ready ? java.sql.Timestamp.from(now.plus(MATCH_WINDOW)) : null, java.sql.Types.TIMESTAMP)
                        .param("m", m.id()).update();
            }
            if (a != null && b != null) {
                Optional<Long> w = jdbc.sql("""
                                select games_a, games_b, forfeit from match_report where match_id = :m and status = 'CONFIRMED'""")
                        .param("m", m.id())
                        .query((rs, n) -> winnerOf(a, b, rs.getInt(1), rs.getInt(2), rs.getString(3))).optional();
                if (w.isPresent() && w.get() != null) {
                    outcome.put(m.id(), new Long[] {w.get(), w.get().equals(a) ? b : a});
                    if (i == ms.size() - 1) champion = w.get();
                }
            }
        }
        if (champion != null && season.getStatus() != SeasonStatus.COMPLETE) complete(season, champion, "won the final");
    }

    private static Long resolve(String slot, Map<Integer, Long> seeds, Map<Long, Long[]> outcome) {
        String[] p = slot.split(":");
        return switch (p[0]) {
            case "SEED" -> seeds.get(Integer.parseInt(p[1]));
            case "WINNER" -> Optional.ofNullable(outcome.get(Long.parseLong(p[1]))).map(o -> o[0]).orElse(null);
            default -> Optional.ofNullable(outcome.get(Long.parseLong(p[1]))).map(o -> o[1]).orElse(null);
        };
    }

    /** Without playoffs: a commissioner completes the season, and the top of the standings is champion. */
    @Transactional
    public PlayoffView completeWithoutPlayoffs(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(leagueId, seasonId, caller);
        Season season = ref.season();
        lock(season);
        String why = whyNotComplete(season, rules.latest(season).playoffs());
        if (why != null) throw conflict(why);
        complete(season, standings.order(season).getFirst(), "finished top of the standings");
        return view(season, ref.me().getId(), true);
    }

    private String whyNotComplete(Season season, SeasonRulesDocument.PlayoffRules p) {
        if (season.getStatus() == SeasonStatus.COMPLETE) return "The season is complete";
        if (p.on()) return "With playoffs, the final's result completes the season";
        if (season.getStatus() != SeasonStatus.ACTIVE) return "The season completes after the regular season";
        return unfinishedRegular(season);
    }

    private void complete(Season season, long champion, String how) {
        jdbc.sql("update season set champion_player_id = :c, completed_at = now() where id = :s")
                .param("c", champion).param("s", season.getId()).update();
        season.setStatus(SeasonStatus.COMPLETE);
        // Offers still open can never be accepted now (the season-end rule from YGO-24).
        jdbc.sql("""
                        update trade_offer set status = 'CANCELLED', decided_at = now(), cancel_reason = 'The season ended'
                        where season_id = :s and status in ('OPEN', 'PENDING_APPROVAL')""")
                .param("s", season.getId()).update();
        String name = jdbc.sql("""
                        select m.display_name from season_player sp join league_member m on m.id = sp.league_member_id where sp.id = :p""")
                .param("p", champion).query(String.class).single();
        announcer.announce(season, NotificationEvent.RESULTS, "results", "🏆 **" + DiscordText.escape(name) + "** " + how + " and is the "
                + DiscordText.escape(season.getName()) + " champion!");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** The winner of a final result, or null for a draw / double forfeit (not allowed in the playoffs). */
    static Long winnerOf(Long a, Long b, int gamesA, int gamesB, String forfeit) {
        if (forfeit != null) return switch (forfeit) {
            case "A" -> b;
            case "B" -> a;
            default -> null;
        };
        return gamesA > gamesB ? a : gamesB > gamesA ? b : null;
    }

    private Optional<Long> stage(Season season, String kind) {
        return jdbc.sql("select id from stage where season_id = :s and kind = :k").param("s", season.getId()).param("k", kind)
                .query(Long.class).optional();
    }

    private Map<Long, PlayerRef> players(Season season, long myMemberId) {
        Map<Long, PlayerRef> out = new LinkedHashMap<>();
        jdbc.sql("""
                        select sp.id, m.id, m.display_name, sp.team_name, coalesce(sp.avatar_passcode, m.avatar_passcode, (select u.avatar_passcode from app_user u where u.id = m.user_id)) from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s""")
                .param("s", season.getId())
                .query(rs -> {
                    out.put(rs.getLong(1), new PlayerRef(publicIds.encode(PublicIdType.LEAGUE_MEMBER, rs.getLong(2)), rs.getString(3),
                            rs.getString(4), rs.getLong(2) == myMemberId, (Long) rs.getObject(5)));
                });
        return out;
    }

    private static List<GameView> games(String[] replays, String[] notes) {
        List<GameView> out = new ArrayList<>();
        for (int g = 0; g < Math.max(replays.length, notes.length); g++) {
            String replay = g < replays.length && !replays[g].isBlank() ? replays[g] : null;
            String note = g < notes.length && !notes[g].isBlank() ? notes[g] : null;
            if (replay != null || note != null) out.add(new GameView(g + 1, replay, note));
        }
        return out;
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private void lock(Season season) {
        jdbc.sql("select id from season where id = :s for update").param("s", season.getId()).query(Long.class).single();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
