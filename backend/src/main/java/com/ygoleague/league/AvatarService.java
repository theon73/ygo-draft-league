package com.ygoleague.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.season.SeasonAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Roster avatars (YGO-30): a card's art that stands for a player on rosters, the draft board,
 * match tiles and standings.
 *
 * <ul>
 *   <li>A profile avatar belongs to the account (the header, and every league that has nothing
 *       more specific). A league member's avatar is their default in that league's seasons; a season
 *       player can override it for one season. A season shows
 *       {@code coalesce(season override, league default, profile)}.
 *   <li>Any card in the catalog can be chosen (it's cosmetic), with any of its artworks.
 *   <li>Players set their own. For a placeholder (no account yet), the league owner sets the
 *       default and a season's commissioners set the season's; whoever claims the seat can change it.
 * </ul>
 */
@Service
public class AvatarService {

    /** {@code cardId} null clears the avatar; {@code passcode} null means the card's first artwork. */
    public record AvatarRequest(Long cardId, Long passcode) {}

    private final LeagueAccess leagueAccess;
    private final SeasonAccess seasonAccess;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public AvatarService(LeagueAccess leagueAccess, SeasonAccess seasonAccess, PublicIds publicIds, JdbcClient jdbc) {
        this.leagueAccess = leagueAccess;
        this.seasonAccess = seasonAccess;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    @Transactional
    public void setProfileAvatar(AvatarRequest body, CurrentUser caller) {
        Long[] art = art(body);
        jdbc.sql("update app_user set avatar_card_id = :c, avatar_passcode = :p where id = :u")
                .param("c", art[0]).param("p", art[1]).param("u", caller.userId()).update();
    }

    @Transactional(readOnly = true)
    public Long profileAvatar(long userId) {
        return jdbc.sql("select avatar_passcode from app_user where id = :u").param("u", userId)
                .query((rs, n) -> (Long) rs.getObject(1)).optional().orElse(null);
    }

    @Transactional
    public void setMemberAvatar(String leagueId, String memberId, AvatarRequest body, CurrentUser caller) {
        LeagueMember me = leagueAccess.require(leagueId, caller, LeagueRole.MEMBER);
        Target target = target(me.getLeague().getId(), memberId);
        boolean self = target.id() == me.getId();
        if (!self && !(target.placeholder() && me.getRole() == LeagueRole.OWNER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only change your own avatar (the owner sets placeholders')");
        }
        Long[] art = art(body);
        jdbc.sql("update league_member set avatar_card_id = :c, avatar_passcode = :p where id = :m")
                .param("c", art[0]).param("p", art[1]).param("m", target.id()).update();
    }

    @Transactional
    public void setSeasonAvatar(String leagueId, String seasonId, String memberId, AvatarRequest body, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = seasonAccess.member(leagueId, seasonId, caller);
        Target target = target(ref.season().getLeague().getId(), memberId);
        boolean self = target.id() == ref.me().getId();
        if (!self && !(target.placeholder() && ref.commissioner())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only change your own avatar (commissioners set placeholders')");
        }
        Long[] art = art(body);
        int updated = jdbc.sql("update season_player set avatar_card_id = :c, avatar_passcode = :p where season_id = :s and league_member_id = :m")
                .param("c", art[0]).param("p", art[1]).param("s", ref.season().getId()).param("m", target.id()).update();
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "They aren't playing this season");
    }

    private record Target(long id, boolean placeholder) {}

    private Target target(long leagueId, String publicMemberId) {
        return publicIds.decode(PublicIdType.LEAGUE_MEMBER, publicMemberId)
                .flatMap(id -> jdbc.sql("select id, user_id is null from league_member where id = :m and league_id = :l")
                        .param("m", id).param("l", leagueId)
                        .query((rs, n) -> new Target(rs.getLong(1), rs.getBoolean(2))).optional())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such member"));
    }

    /** {card id, passcode}, both null to clear; the passcode must be one of the card's artworks. */
    private Long[] art(AvatarRequest body) {
        if (body == null || body.cardId() == null) return new Long[] {null, null};
        Long passcode = jdbc.sql("""
                        select case when cast(:p as bigint) is null then passcodes[1]
                                    when cast(:p as bigint) = any(passcodes) then cast(:p as bigint) end
                        from card where id = :c""")
                .param("p", body.passcode()).param("c", body.cardId())
                .query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such card"));
        if (passcode == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That artwork isn't one of the card's");
        return new Long[] {body.cardId(), passcode};
    }
}
