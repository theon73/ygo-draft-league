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
    private final ResultService results;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public StandingsService(SeasonAccess access, SeasonRulesService rules, ResultService results, PublicIds publicIds, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.results = results;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public StandingsView standings(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        record P(long id, long memberId, String name, String team) {}
        Map<Long, P> players = new LinkedHashMap<>();
        jdbc.sql("""
                        select sp.id, m.id, m.display_name, sp.team_name from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s order by sp.seat nulls last, lower(m.display_name)""")
                .param("s", season.getId())
                .query(rs -> { players.put(rs.getLong(1), new P(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4))); });

        List<Standings.Result<Long>> finals = results.finalResults(season);
        List<Standings.Tiebreaker> order = rules.latest(season).results().order();
        List<Standings.Row<Long>> table = Standings.table(List.copyOf(players.keySet()), finals, order,
                id -> players.get(id).name().toLowerCase());
        List<Row> rows = table.stream().map(r -> {
            P p = players.get(r.player());
            return new Row(r.rank(), publicIds.encode(PublicIdType.LEAGUE_MEMBER, p.memberId()), p.name(), p.team(),
                    p.memberId() == ref.me().getId(), r.played(), r.wins(), r.losses(), r.draws(), r.gamesWon(), r.gamesLost(), r.gameDiff());
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
}
