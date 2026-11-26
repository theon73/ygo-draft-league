package com.ygoleague.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueAccess;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.league.LeagueRole;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The season-level counterpart of {@link LeagueAccess}: every season endpoint goes through
 * {@link #member} (any league member may follow a season) or {@link #commissioner} (manage it).
 *
 * <ul>
 *   <li>Not in the league, or a season of another league, or a malformed id: 404.
 *   <li>In the league but not commissioning this season: 403.
 *   <li>A season's commissioners are the league owner (always) plus the members in
 *       {@code season_commissioner}.
 * </ul>
 */
@Component
public class SeasonAccess {

    /** The caller's membership, the season, and whether they commission it. */
    public record SeasonRef(LeagueMember me, Season season, boolean commissioner) {}

    private final LeagueAccess leagueAccess;
    private final LeagueRepository leagues;
    private final SeasonRepository seasons;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public SeasonAccess(LeagueAccess leagueAccess, LeagueRepository leagues, SeasonRepository seasons, PublicIds publicIds, JdbcClient jdbc) {
        this.leagueAccess = leagueAccess;
        this.leagues = leagues;
        this.seasons = seasons;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    public SeasonRef member(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        LeagueMember me = leagueAccess.require(publicLeagueId, caller, LeagueRole.MEMBER);
        Season season = seasonOf(me.getLeague(), publicSeasonId);
        return new SeasonRef(me, season, commissions(season, me));
    }

    /** For changes: also locks the league row, like every other change in a league. */
    public SeasonRef commissioner(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        LeagueMember me = leagueAccess.require(publicLeagueId, caller, LeagueRole.MEMBER);
        leagues.lockById(me.getLeague().getId());
        Season season = seasonOf(me.getLeague(), publicSeasonId);
        if (!commissions(season, me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only this season's commissioners can do this");
        }
        return new SeasonRef(me, season, true);
    }

    public boolean commissions(Season season, LeagueMember member) {
        return member.getRole() == LeagueRole.OWNER || commissionerIds(season).contains(member.getId());
    }

    /** Listed commissioners (the owner is implicit and not included). */
    public Set<Long> commissionerIds(Season season) {
        return Set.copyOf(jdbc.sql("select league_member_id from season_commissioner where season_id = :s")
                .param("s", season.getId()).query(Long.class).list());
    }

    public Season seasonOf(League league, String publicSeasonId) {
        return publicIds.decode(PublicIdType.SEASON, publicSeasonId)
                .flatMap(seasons::findById)
                .filter(s -> s.getLeague().getId().equals(league.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
    }
}
