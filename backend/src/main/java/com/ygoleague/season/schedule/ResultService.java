package com.ygoleague.season.schedule;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.competition.Standings;
import com.ygoleague.notify.DiscordText;
import com.ygoleague.notify.NotificationEvent;
import com.ygoleague.notify.TransactionAnnouncer;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.rules.SeasonRulesService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Match results (YGO-26).
 *
 * <ul>
 *   <li><b>Either player reports</b> the games each won (tied games left out: level games, e.g. 1-1,
 *       is a draw), with optional replay links. The <b>opponent confirms or disputes</b>; a report
 *       nobody answers <b>auto-confirms</b> after the season's delay ({@link ResultConfirmer}).
 *   <li>A score must fit the best-of in effect for the match's week: nobody past a majority, no more
 *       games than the best-of, not 0-0.
 *   <li><b>Commissioners</b> can enter or correct any result, including a <b>forfeit</b> for either
 *       or both players; theirs are final at once. Disputes are settled that way.
 *   <li><b>Late results</b> are fine: a match stays open after its week (shown as overdue) and counts
 *       for its own week whenever it's reported.
 *   <li>Reports are append-only history: a new report or a correction supersedes the live one.
 *   <li>Final results post to Discord under the {@link NotificationEvent#RESULTS} event.
 * </ul>
 */
@Service
public class ResultService {

    public static final int MAX_REPLAYS_LENGTH = 300;
    public static final int MAX_NOTE_LENGTH = 200;

    /**
     * @param replays optional replay links by game: element 0 is game 1; blank for none
     * @param notes   optional notes by game, the same way
     */
    public record ReportRequest(Integer gamesA, Integer gamesB, List<String> replays, List<String> notes) {}

    /** A commissioner's entry: games (as a report), or a forfeit (A, B or BOTH). */
    public record EnterRequest(Integer gamesA, Integer gamesB, String forfeit, List<String> replays, List<String> notes) {}

    public record DisputeRequest(String note) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final SeasonRepository seasons;
    private final PublicIds publicIds;
    private final TransactionAnnouncer announcer;
    private final JdbcClient jdbc;

    public ResultService(SeasonAccess access, SeasonRulesService rules, SeasonRepository seasons, PublicIds publicIds,
                         TransactionAnnouncer announcer, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.seasons = seasons;
        this.publicIds = publicIds;
        this.announcer = announcer;
        this.jdbc = jdbc;
    }

    /** A match and its two players (null b = a bye). */
    record Match(long id, long seasonId, int week, Long a, Long b, String aName, String bName, Long aUser, Long bUser) {}

    /** The live (not superseded) report of a match. */
    record Report(long id, int gamesA, int gamesB, String forfeit, String status, Long reportedPlayer, long reportedBy) {}

    // ---- Players ------------------------------------------------------------------------------

    @Transactional
    public void report(String leagueId, String seasonId, String matchId, ReportRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        lock(season);
        Match m = match(season, matchId);
        Long me = playerOf(m, caller);
        if (me == null) throw forbidden("Only the two players report a match; a commissioner can enter it for them");
        open(season);
        if (req == null || req.gamesA() == null || req.gamesB() == null) throw bad("Enter the games each player won");
        int bestOf = bestOf(season, m);
        checkScore(req.gamesA(), req.gamesB(), bestOf);
        List<String> replays = replays(req.replays(), bestOf);
        List<String> notes = notes(req.notes(), bestOf);

        Optional<Report> live = live(m);
        if (live.isPresent()) {
            Report r = live.get();
            switch (r.status()) {
                case "CONFIRMED" -> throw conflict("This result is final; ask a commissioner to correct it");
                case "PENDING" -> {
                    if (!me.equals(r.reportedPlayer())) {
                        throw conflict(nameOf(m, r.reportedPlayer()) + " already reported it: confirm it, or dispute it if it's wrong");
                    }
                }
                default -> { /* DISPUTED: either player can report again */ }
            }
            supersede(r.id());
        }
        jdbc.sql("""
                        insert into match_report (match_id, season_id, games_a, games_b, replays, game_notes, status, reported_by, reported_player)
                        values (:m, :s, :a, :b, cast(:r as text[]), cast(:n as text[]), 'PENDING', :u, :p)""")
                .param("m", m.id()).param("s", season.getId()).param("a", req.gamesA()).param("b", req.gamesB())
                .param("r", textArray(replays)).param("n", textArray(notes)).param("u", caller.userId()).param("p", me).update();
    }

    @Transactional
    public void confirm(String leagueId, String seasonId, String matchId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        lock(season);
        Match m = match(season, matchId);
        Report r = pendingForOpponent(m, caller, "confirm");
        jdbc.sql("update match_report set status = 'CONFIRMED', confirmed_by = :u, confirmed_at = now() where id = :r")
                .param("u", caller.userId()).param("r", r.id()).update();
        announceFinal(season, m, r.gamesA(), r.gamesB(), null, "");
    }

    @Transactional
    public void dispute(String leagueId, String seasonId, String matchId, DisputeRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        lock(season);
        Match m = match(season, matchId);
        Report r = pendingForOpponent(m, caller, "dispute");
        String note = req == null || req.note() == null || req.note().isBlank() ? null : req.note().strip();
        if (note != null && note.length() > 500) throw bad("Keep the note to 500 characters");
        jdbc.sql("update match_report set status = 'DISPUTED', dispute_note = :n, disputed_by = :u, disputed_at = now() where id = :r")
                .param("n", note).param("u", caller.userId()).param("r", r.id()).update();
        announcer.announce(season, NotificationEvent.RESULTS, "results", "⚠️ Week " + m.week() + ": **" + DiscordText.escape(m.aName()) + "** vs **"
                + DiscordText.escape(m.bName()) + "**: the reported result is disputed; a commissioner will settle it.");
    }

    // ---- Commissioners ------------------------------------------------------------------------

    /** Enters (or corrects) a result: games or a forfeit. Final at once. */
    @Transactional
    public void enter(String leagueId, String seasonId, String matchId, EnterRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(leagueId, seasonId, caller);
        Season season = ref.season();
        lock(season);
        Match m = match(season, matchId);
        if (m.b() == null) throw bad("A bye has no result");
        open(season);
        if (req == null) throw bad("Enter the games or a forfeit");
        String forfeit = req.forfeit() == null || req.forfeit().isBlank() ? null : req.forfeit().strip().toUpperCase();
        int a = 0;
        int b = 0;
        int bestOf = bestOf(season, m);
        if (forfeit != null) {
            if (!List.of("A", "B", "BOTH").contains(forfeit)) throw bad("A forfeit is by player A, player B, or both");
        } else {
            if (req.gamesA() == null || req.gamesB() == null) throw bad("Enter the games each player won, or a forfeit");
            a = req.gamesA();
            b = req.gamesB();
            checkScore(a, b, bestOf);
        }
        live(m).ifPresent(r -> supersede(r.id()));
        jdbc.sql("""
                        insert into match_report (match_id, season_id, games_a, games_b, forfeit, replays, game_notes, status, by_commissioner,
                                                  reported_by, confirmed_by, confirmed_at)
                        values (:m, :s, :a, :b, :f, cast(:r as text[]), cast(:n as text[]), 'CONFIRMED', true, :u, :u, now())""")
                .param("m", m.id()).param("s", season.getId()).param("a", a).param("b", b).param("f", forfeit)
                .param("r", textArray(replays(req.replays(), bestOf))).param("n", textArray(notes(req.notes(), bestOf)))
                .param("u", caller.userId()).update();
        announceFinal(season, m, a, b, forfeit, " (entered by " + DiscordText.escape(caller.displayName()) + ")");
    }

    // ---- Auto-confirm -------------------------------------------------------------------------

    /**
     * Confirms reports nobody answered within their season's delay (the rules in effect for the
     * match's week). Called by {@link ResultConfirmer}, in one transaction.
     *
     * @return how many were confirmed
     */
    @Transactional
    public int autoConfirmDue(Instant now) {
        record Due(long reportId, long seasonId, long matchId, Instant reportedAt, int week, int a, int b) {}
        List<Due> pending = jdbc.sql("""
                        select r.id, r.season_id, r.match_id, r.reported_at, m.week, r.games_a, r.games_b
                        from match_report r join season_match m on m.id = r.match_id
                        where r.status = 'PENDING' order by r.reported_at""")
                .query((rs, n) -> new Due(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7)))
                .list();
        int done = 0;
        for (Due d : pending) {
            Season season = seasons.findById(d.seasonId()).orElseThrow();
            int hours = rules.inEffect(season, d.week()).results().confirmHours();
            if (d.reportedAt().plus(hours, ChronoUnit.HOURS).isAfter(now)) continue;
            int changed = jdbc.sql("""
                            update match_report set status = 'CONFIRMED', confirmed_at = :now, auto_confirmed = true
                            where id = :r and status = 'PENDING'""")
                    .param("now", java.sql.Timestamp.from(now)).param("r", d.reportId()).update();
            if (changed == 0) continue;
            done++;
            Match m = matchById(season, d.matchId());
            announceFinal(season, m, d.a(), d.b(), null, " (auto-confirmed)");
        }
        return done;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Nobody past a majority, no more games than the best-of, not 0-0 (forfeits are separate). */
    static void checkScore(int a, int b, int bestOf) {
        int majority = bestOf / 2 + 1;
        if (a < 0 || b < 0) throw bad("Games can't be negative");
        if (a == 0 && b == 0) throw bad("Enter the games each player won (a match nobody played is a forfeit, for a commissioner)");
        if (a > majority || b > majority) throw bad("In a best of " + bestOf + ", the first to " + majority + " wins, so nobody wins more than " + majority);
        if (a == majority && b == majority) throw bad("Only one player can reach " + majority);
        if (a + b > bestOf) throw bad("A best of " + bestOf + " has at most " + bestOf + " games");
    }

    /** Replay links by game (blank = none), trimmed to the last game that has one. */
    static List<String> replays(List<String> in, int bestOf) {
        return byGame(in, bestOf, "replay link", url -> {
            if (url.length() > MAX_REPLAYS_LENGTH || !(url.startsWith("https://") || url.startsWith("http://"))) {
                throw bad("Replay links are web addresses (https://…), up to " + MAX_REPLAYS_LENGTH + " characters");
            }
        });
    }

    /** Notes by game (blank = none), trimmed to the last game that has one. */
    static List<String> notes(List<String> in, int bestOf) {
        return byGame(in, bestOf, "note", note -> {
            if (note.length() > MAX_NOTE_LENGTH) throw bad("Game notes are at most " + MAX_NOTE_LENGTH + " characters");
        });
    }

    private static List<String> byGame(List<String> in, int bestOf, String what, java.util.function.Consumer<String> check) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            String v = s == null ? "" : s.strip().replaceAll("\\s+", " ");
            if (!v.isEmpty()) check.accept(v);
            out.add(v);
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
        if (out.size() > bestOf) throw bad("A best of " + bestOf + " has " + bestOf + " games, so at most " + bestOf + " of each " + what);
        return out;
    }

    private static String textArray(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private void announceFinal(Season season, Match m, int a, int b, String forfeit, String suffix) {
        String an = "**" + DiscordText.escape(m.aName()) + "**";
        String bn = "**" + DiscordText.escape(m.bName()) + "**";
        String line;
        if (forfeit == null) {
            line = a == b ? an + " and " + bn + " drew " + a + "-" + b
                    : (a > b ? an + " beat " + bn + " " + a + "-" + b : bn + " beat " + an + " " + b + "-" + a);
        } else {
            line = switch (forfeit) {
                case "A" -> bn + " wins by forfeit against " + an;
                case "B" -> an + " wins by forfeit against " + bn;
                default -> an + " and " + bn + " both forfeit";
            };
        }
        announcer.announce(season, NotificationEvent.RESULTS, "results", "Week " + m.week() + ": " + line + suffix);
    }

    private Report pendingForOpponent(Match m, CurrentUser caller, String what) {
        Long me = playerOf(m, caller);
        Report r = live(m).filter(x -> x.status().equals("PENDING"))
                .orElseThrow(() -> conflict("There's no reported result waiting to " + what));
        if (me == null || me.equals(r.reportedPlayer())) throw forbidden("Only the opponent of whoever reported it can " + what + " it");
        return r;
    }

    private Optional<Report> live(Match m) {
        return jdbc.sql("""
                        select id, games_a, games_b, forfeit, status, reported_player, reported_by from match_report
                        where match_id = :m and status <> 'SUPERSEDED'""")
                .param("m", m.id())
                .query((rs, n) -> new Report(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        (Long) rs.getObject(6), rs.getLong(7)))
                .optional();
    }

    private void supersede(long reportId) {
        jdbc.sql("update match_report set status = 'SUPERSEDED' where id = :r").param("r", reportId).update();
    }

    private Long playerOf(Match m, CurrentUser caller) {
        if (m.aUser() != null && m.aUser() == caller.userId()) return m.a();
        if (m.bUser() != null && m.bUser() == caller.userId()) return m.b();
        return null;
    }

    private static String nameOf(Match m, Long player) {
        return player != null && player.equals(m.a()) ? m.aName() : m.bName();
    }

    private int bestOf(Season season, Match m) {
        return rules.inEffect(season, m.week()).competition().bestOf();
    }

    private void open(Season season) {
        if (season.getStatus() != SeasonStatus.ACTIVE) throw conflict("Results are reported while the season is in progress");
    }

    private void lock(Season season) {
        jdbc.sql("select id from season where id = :s for update").param("s", season.getId()).query(Long.class).single();
    }

    private Match match(Season season, String publicId) {
        long id = publicIds.decode(PublicIdType.MATCH, publicId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such match"));
        return matchById(season, id);
    }

    private Match matchById(Season season, long id) {
        return jdbc.sql("""
                        select m.id, m.season_id, m.week, m.player_a_id, m.player_b_id, am.display_name, bm.display_name, am.user_id, bm.user_id
                        from season_match m
                        left join season_player ap on ap.id = m.player_a_id left join league_member am on am.id = ap.league_member_id
                        left join season_player bp on bp.id = m.player_b_id left join league_member bm on bm.id = bp.league_member_id
                        where m.id = :m and m.season_id = :s""")
                .param("m", id).param("s", season.getId())
                .query((rs, n) -> new Match(rs.getLong(1), rs.getLong(2), rs.getInt(3), (Long) rs.getObject(4), (Long) rs.getObject(5),
                        rs.getString(6), rs.getString(7), (Long) rs.getObject(8), (Long) rs.getObject(9)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such match"));
    }

    // ---- Standings ----------------------------------------------------------------------------

    /** Final results of a season's regular-season matches, for {@link Standings}. */
    List<Standings.Result<Long>> finalResults(Season season) {
        return jdbc.sql("""
                        select m.player_a_id, m.player_b_id, r.games_a, r.games_b, r.forfeit
                        from match_report r join season_match m on m.id = r.match_id join stage st on st.id = m.stage_id
                        where r.season_id = :s and r.status = 'CONFIRMED' and st.kind = 'REGULAR' and m.player_b_id is not null""")
                .param("s", season.getId())
                .query((rs, n) -> new Standings.Result<>(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5) == null ? null : Standings.Forfeit.valueOf(rs.getString(5))))
                .list();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
