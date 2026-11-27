package com.ygoleague.season.transaction;

import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.season.Season;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A season's players as transactions see them: who they are, and whether they have an account. */
@Component
public class SeasonPlayers {

    /** @param userId the player's account, or null for a placeholder seat */
    public record Player(long id, long memberId, String name, Long userId) {
        public boolean placeholder() { return userId == null; }
    }

    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public SeasonPlayers(PublicIds publicIds, JdbcClient jdbc) {
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    /** In seat order. */
    public List<Player> list(Season season) {
        return jdbc.sql("""
                        select sp.id, m.id, m.display_name, m.user_id from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s order by sp.seat nulls last, lower(m.display_name)""")
                .param("s", season.getId())
                .query((rs, n) -> new Player(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4)))
                .list();
    }

    public Optional<Player> byMember(Season season, long memberId) {
        return list(season).stream().filter(p -> p.memberId() == memberId).findFirst();
    }

    /** By public league-member id; empty if it isn't one, or that member isn't playing this season. */
    public Optional<Player> byPublicMemberId(Season season, String publicMemberId) {
        return publicIds.decode(PublicIdType.LEAGUE_MEMBER, publicMemberId).flatMap(id -> byMember(season, id));
    }

    public String publicMemberId(Player p) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, p.memberId());
    }
}
