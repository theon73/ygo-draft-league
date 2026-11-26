package com.ygoleague.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one place that decides whether a user may act on a league. Every league-scoped endpoint
 * starts with {@link #require}.
 *
 * <ul>
 *   <li><b>Not a member (or no such league, or a malformed id): 404.</b> Leagues are members-only,
 *       so outsiders can't even learn that a league exists.
 *   <li><b>A member without the required role: 403.</b> They already know the league exists, so
 *       saying "not allowed" leaks nothing.
 * </ul>
 */
@Service
public class LeagueAccess {

    private final LeagueMemberRepository members;
    private final PublicIds publicIds;

    public LeagueAccess(LeagueMemberRepository members, PublicIds publicIds) {
        this.members = members;
        this.publicIds = publicIds;
    }

    /**
     * @param publicLeagueId the league's id as it appears in URLs
     * @return the caller's membership, with its league loaded
     * @throws ResponseStatusException 404 or 403 as described on the class
     */
    @Transactional(readOnly = true)
    public LeagueMember require(String publicLeagueId, CurrentUser user, LeagueRole atLeast) {
        LeagueMember membership = publicIds.decode(PublicIdType.LEAGUE, publicLeagueId)
                .flatMap(leagueId -> members.findWithLeague(leagueId, user.userId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such league"));
        if (!membership.getRole().includes(atLeast)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Needs the " + atLeast.name().toLowerCase() + " role in this league");
        }
        return membership;
    }
}
