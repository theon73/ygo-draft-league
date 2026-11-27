package com.ygoleague.season.roster;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.pool.PoolService;
import com.ygoleague.season.pool.PoolService.PoolCardView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who holds what (YGO-22), read-only: every player's roster, and one card's owners and ownership
 * history within a season. Both read the ownership projection ({@code card_holding}) and its
 * history ({@code card_ownership_event}): the draft, free agency, and trades from YGO-24. Any member of the league can see
 * every roster (members follow every season, §0).
 */
@Service
public class RosterService {

    /** Where a card goes in a deck: main-deck monsters, extra-deck monsters, spells, traps. */
    public enum Group { MONSTER, EXTRA, SPELL, TRAP }

    /**
     * A card on a roster.
     *
     * @param quantity     copies held (always 1 with exclusive ownership)
     * @param acquiredAt   when the player got it (their latest acquisition of it)
     * @param acquiredWeek the league week of that (0 = the draft, before week 1)
     * @param source       how: DRAFT, FREE_AGENCY or TRADE
     * @param draftPick    the board slot, for drafted cards
     */
    public record RosterCard(PoolCardView card, Group group, int quantity, Instant acquiredAt, int acquiredWeek, String source,
                             Integer draftPick) {}

    public record Counts(int total, int monsters, int extra, int spells, int traps) {}

    public record Roster(String memberId, String displayName, String teamName, Integer seat, boolean placeholder, boolean you,
                         Counts counts, List<RosterCard> cards) {}

    /** @param freeAgents pool cards nobody holds that could be taken (see {@link PoolService#availableCount}) */
    public record Rosters(long freeAgents, List<Roster> players) {}

    /** Someone holding a card now. */
    public record Owner(String memberId, String displayName, int quantity, Instant since, int week) {}

    /**
     * One change of hands. {@code from} is null for the draft or a free-agent claim; {@code to} is
     * null for a drop back to free agency.
     *
     * @param auto       an auto-pick (timer or away mode), made by nobody
     * @param onBehalfBy the commissioner who made the pick for the player, or null if the player did
     */
    public record OwnershipChange(Instant at, int week, String source, String fromMemberId, String from, String toMemberId, String to,
                                  int quantity, Integer draftPick, boolean auto, String onBehalfBy) {}

    /** A pool card with its owners and history in this season. */
    public record SeasonCard(PoolCardView card, List<Owner> owners, List<OwnershipChange> history) {}

    private final SeasonAccess access;
    private final PoolService pool;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public RosterService(SeasonAccess access, PoolService pool, PublicIds publicIds, JdbcClient jdbc) {
        this.access = access;
        this.pool = pool;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    /** Every player's roster, in seat order, each sorted by deck group and then name. */
    @Transactional(readOnly = true)
    public Rosters rosters(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        long s = season.getId();

        // The held cards' pool rows, read the same way the pool list reads them.
        Map<Long, PoolCardView> cards = new LinkedHashMap<>();
        jdbc.sql(PoolService.CARD_SELECT + """
                         where p.season_id = :s and exists (select 1 from card_holding h where h.season_id = p.season_id and h.card_id = p.card_id)
                         order by lower(c.name), c.id""")
                .param("s", s).param("rev", pool.banListRevision(season), java.sql.Types.BIGINT)
                .query((rs, n) -> PoolService.cardRow(rs))
                .list()
                .forEach(c -> cards.put(c.cardId(), c));

        // Who holds each card, and their latest acquisition of it.
        record Holding(long playerId, long cardId, int quantity, Group group, Instant at, int week, String source, Integer draftPick) {}
        List<Holding> holdings = jdbc.sql("""
                        select h.season_player_id, h.card_id, h.quantity,
                               case when c.frame_type = 'spell' then 'SPELL' when c.frame_type = 'trap' then 'TRAP'
                                    when c.frame_type in ('fusion', 'synchro', 'xyz', 'link', 'fusion_pendulum', 'synchro_pendulum', 'xyz_pendulum')
                                    then 'EXTRA' else 'MONSTER' end,
                               e.at, e.week, e.source, e.draft_pick
                        from card_holding h join card c on c.id = h.card_id
                        left join lateral (select at, week, source, draft_pick from card_ownership_event e
                                           where e.season_id = h.season_id and e.card_id = h.card_id and e.to_player_id = h.season_player_id
                                           order by e.at desc, e.ordinal desc limit 1) e on true
                        where h.season_id = :s""")
                .param("s", s)
                .query((rs, n) -> new Holding(rs.getLong(1), rs.getLong(2), rs.getInt(3), Group.valueOf(rs.getString(4)),
                        rs.getTimestamp(5).toInstant(), rs.getInt(6), rs.getString(7), (Integer) rs.getObject(8)))
                .list();
        Map<Long, List<RosterCard>> byPlayer = new LinkedHashMap<>();
        for (Holding h : holdings) {
            byPlayer.computeIfAbsent(h.playerId(), k -> new ArrayList<>())
                    .add(new RosterCard(cards.get(h.cardId()), h.group(), h.quantity(), h.at(), h.week(), h.source(), h.draftPick()));
        }

        record Player(long id, long memberId, String name, String team, Integer seat, boolean placeholder) {}
        List<Player> players = jdbc.sql("""
                        select sp.id, m.id, m.display_name, sp.team_name, sp.seat, m.user_id is null
                        from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s order by sp.seat nulls last, lower(m.display_name)""")
                .param("s", s)
                .query((rs, n) -> new Player(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), (Integer) rs.getObject(5),
                        rs.getBoolean(6)))
                .list();

        List<Roster> rosters = players.stream().map(p -> {
            List<RosterCard> held = new ArrayList<>(byPlayer.getOrDefault(p.id(), List.of()));
            held.sort(java.util.Comparator.comparing(RosterCard::group)
                    .thenComparing(rc -> rc.card().name().toLowerCase())
                    .thenComparing(rc -> rc.card().cardId()));
            return new Roster(memberId(p.memberId()), p.name(), p.team(), p.seat(), p.placeholder(), p.memberId() == ref.me().getId(),
                    counts(held), held);
        }).toList();
        return new Rosters(pool.availableCount(season), rosters);
    }

    private static Counts counts(List<RosterCard> cards) {
        int[] by = new int[Group.values().length];
        int total = 0;
        for (RosterCard c : cards) {
            by[c.group().ordinal()] += c.quantity();
            total += c.quantity();
        }
        return new Counts(total, by[Group.MONSTER.ordinal()], by[Group.EXTRA.ordinal()], by[Group.SPELL.ordinal()], by[Group.TRAP.ordinal()]);
    }

    /** A pool card in this season: its pool row, who holds it, and every change of hands (oldest first). */
    @Transactional(readOnly = true)
    public SeasonCard card(String leagueId, String seasonId, long cardId, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        long s = season.getId();
        PoolCardView card = jdbc.sql(PoolService.CARD_SELECT + " where p.season_id = :s and p.card_id = :c")
                .param("s", s).param("c", cardId).param("rev", pool.banListRevision(season), java.sql.Types.BIGINT)
                .query((rs, n) -> PoolService.cardRow(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That card isn't in this season's pool"));

        List<Owner> owners = jdbc.sql("""
                        select m.id, m.display_name, h.quantity, e.at, e.week
                        from card_holding h join season_player sp on sp.id = h.season_player_id
                        join league_member m on m.id = sp.league_member_id
                        left join lateral (select at, week from card_ownership_event e
                                           where e.season_id = h.season_id and e.card_id = h.card_id and e.to_player_id = h.season_player_id
                                           order by e.at desc, e.ordinal desc limit 1) e on true
                        where h.season_id = :s and h.card_id = :c
                        order by e.at, sp.seat""")
                .param("s", s).param("c", cardId)
                .query((rs, n) -> new Owner(memberId(rs.getLong(1)), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant(), rs.getInt(5)))
                .list();

        // onBehalfBy: the person who clicked, when it isn't the account of the player whose action it was
        // (for a trade, of either player: the one who accepted it isn't acting for anyone).
        List<OwnershipChange> history = jdbc.sql("""
                        select e.at, e.week, e.source, fm.id, fm.display_name, tm.id, tm.display_name, e.quantity, e.draft_pick,
                               e.source = 'DRAFT' and e.made_by is null,
                               case when e.made_by is distinct from am.user_id
                                         and (e.source <> 'TRADE' or (e.made_by is distinct from fm.user_id and e.made_by is distinct from tm.user_id))
                                    then u.display_name end
                        from card_ownership_event e
                        left join season_player fp on fp.id = e.from_player_id left join league_member fm on fm.id = fp.league_member_id
                        left join season_player tp on tp.id = e.to_player_id left join league_member tm on tm.id = tp.league_member_id
                        join season_player ap on ap.id = e.for_player_id join league_member am on am.id = ap.league_member_id
                        left join app_user u on u.id = e.made_by
                        where e.season_id = :s and e.card_id = :c
                        order by e.at, e.ordinal""")
                .param("s", s).param("c", cardId)
                .query((rs, n) -> new OwnershipChange(rs.getTimestamp(1).toInstant(), rs.getInt(2), rs.getString(3),
                        memberIdOrNull(rs, 4), rs.getString(5), memberIdOrNull(rs, 6), rs.getString(7), rs.getInt(8),
                        (Integer) rs.getObject(9), rs.getBoolean(10), rs.getString(11)))
                .list();
        return new SeasonCard(card, owners, history);
    }

    private String memberId(long id) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, id);
    }

    private String memberIdOrNull(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long id = rs.getLong(column);
        return rs.wasNull() ? null : memberId(id);
    }
}
