package com.ygoleague.season.schedule;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.competition.Standings;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.rules.SeasonRulesService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The regular season's standings (YGO-26), computed from final results with {@link Standings}:
 * wins, then the season's tiebreakers in order. Pending and disputed results don't count until
 * they're final.
 */
@Service
public class StandingsService {

    public record Row(int rank, String memberId, String name, String teamName, boolean you, int played, int wins, int losses, int draws,
                      int gamesWon, int gamesLost, int gameDiff) {}

    /**
     * @param showDraws  any match this season was a draw (otherwise the table leaves the column out)
     * @param remaining  regular-season matches without a final result yet
     * @param waiting    reported results waiting for confirmation or a commissioner (not counted yet)
     */
    public record StandingsView(List<Standings.Tiebreaker> tiebreakers, boolean showDraws, int remaining, int waiting, List<Row> rows) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public StandingsService(SeasonAccess access, SeasonRulesService rules, PublicIds publicIds, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public StandingsView standings(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        return standings(ref.season(), ref.me().getId());
    }

    /** The table as it stands; {@code myMemberId} marks "you" (0 for nobody). */
    public StandingsView standings(Season season, long myMemberId) {
        record P(long id, long memberId, String name, String team) {}
        Map<Long, P> players = new LinkedHashMap<>();
        jdbc.sql("""
                        select sp.id, m.id, m.display_name, sp.team_name from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s order by sp.seat nulls last, lower(m.display_name)""")
                .param("s", season.getId())
                .query(rs -> { players.put(rs.getLong(1), new P(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4))); });

        List<Standings.Result<Long>> finals = finalResults(season);
        List<Standings.Tiebreaker> order = rules.latest(season).results().order();
        List<Standings.Row<Long>> table = Standings.table(List.copyOf(players.keySet()), finals, order,
                id -> players.get(id).name().toLowerCase());
        List<Row> rows = table.stream().map(r -> {
            P p = players.get(r.player());
            return new Row(r.rank(), publicIds.encode(PublicIdType.LEAGUE_MEMBER, p.memberId()), p.name(), p.team(),
                    p.memberId() == myMemberId, r.played(), r.wins(), r.losses(), r.draws(), r.gamesWon(), r.gamesLost(), r.gameDiff());
        }).toList();

        int remaining = jdbc.sql("""
                        select count(*) from season_match m join stage st on st.id = m.stage_id
                        where m.season_id = :s and st.kind = 'REGULAR' and m.player_b_id is not null
                          and not exists (select 1 from match_report r where r.match_id = m.id and r.status = 'CONFIRMED')""")
                .param("s", season.getId()).query(Integer.class).single();
        int waiting = jdbc.sql("select count(*) from match_report where season_id = :s and status in ('PENDING', 'DISPUTED')")
                .param("s", season.getId()).query(Integer.class).single();
        return new StandingsView(order, finals.stream().anyMatch(Standings.Result::draw), remaining, waiting, rows);
    }

    /** The season players in standings order (1st first): playoff seeding and the champion without playoffs. */
    public List<Long> order(Season season) {
        record P(long id, String name) {}
        List<P> players = jdbc.sql("""
                        select sp.id, m.display_name from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s""")
                .param("s", season.getId()).query((rs, n) -> new P(rs.getLong(1), rs.getString(2))).list();
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (P p : players) names.put(p.id(), p.name().toLowerCase());
        return Standings.table(players.stream().map(P::id).toList(), finalResults(season), rules.latest(season).results().order(), names::get)
                .stream().map(Standings.Row::player).toList();
    }

    /** Final results of a season's regular-season matches. */
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
}
