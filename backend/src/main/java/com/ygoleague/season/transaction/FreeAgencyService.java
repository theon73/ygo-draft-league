package com.ygoleague.season.transaction;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.notify.DiscordText;
import com.ygoleague.notify.TransactionAnnouncer;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.pool.PoolService;
import com.ygoleague.season.pool.PoolService.PoolCardView;
import com.ygoleague.season.rules.SeasonRulesService;
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
 * Free agency (YGO-23): first-come claims of cards nobody holds.
 *
 * <ul>
 *   <li><b>A claim is a swap:</b> the player picks up one free agent and drops one card of theirs,
 *       as one transaction, so rosters keep the size the draft gave them. There are no drops on
 *       their own. A dropped card is a free agent at once, for anyone (the dropper included).
 *   <li><b>Claimable</b> is the free-agent list's rule ({@link PoolService#isAvailable}): in the
 *       pool, not soft-banned, not Forbidden unless the draft allows it, held by nobody.
 *   <li><b>Weekly limit</b> ({@code transactions.freeAgencyPerWeek} in the rules in effect; none =
 *       unlimited): each claim counts once. Week 0, between the draft and week 1, shares week 1's
 *       allowance, so capacity resets each time a week of matches ends.
 *   <li>Open once the draft is complete (the season is active). A player claims for themselves; a
 *       commissioner can claim for any player (placeholders have no account), which counts toward
 *       that player's limit and is shown as made on their behalf.
 *   <li>Claims are serialized per season by locking the season row, so two people can't take the
 *       same card or overrun a limit at the same moment.
 * </ul>
 */
@Service
public class FreeAgencyService {

    /** @param memberId whose claim it is (default: the caller); claimCardId the free agent; dropCardId a card they hold */
    public record ClaimRequest(String memberId, Long claimCardId, Long dropCardId) {}

    /** @param limit claims per week (null = unlimited); left = limit - used (null = unlimited) */
    public record Allowance(String memberId, String displayName, boolean you, Integer limit, int used, Integer left) {}

    /**
     * @param open         claims can be made now
     * @param closedReason why not, when they can't
     * @param week         the league week now (0 = before week 1)
     * @param budgetWeek   the week whose allowance claims count toward now (week 0 counts as week 1)
     * @param players      the players the caller may claim for (themselves; every player for a commissioner)
     */
    public record FreeAgencyView(boolean open, String closedReason, int week, int budgetWeek, Integer limit, List<Allowance> players) {}

    public record CardRef(long cardId, String name, long passcode) {}

    /** One executed transaction, for the league's feed. {@code onBehalfBy}: the commissioner who made it for the player. */
    public record TransactionView(String kind, int week, Instant at, String memberId, String player, List<CardRef> added,
                                  List<CardRef> dropped, String onBehalfBy) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final PoolService pool;
    private final PublicIds publicIds;
    private final TransactionAnnouncer announcer;
    private final JdbcClient jdbc;

    public FreeAgencyService(SeasonAccess access, SeasonRulesService rules, PoolService pool, PublicIds publicIds,
                             TransactionAnnouncer announcer, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.pool = pool;
        this.publicIds = publicIds;
        this.announcer = announcer;
        this.jdbc = jdbc;
    }

    /** A player in this season. */
    private record Player(long id, long memberId, String name, Long userId) {}

    @Transactional(readOnly = true)
    public FreeAgencyView status(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        int week = rules.currentWeek(season);
        int budget = budgetWeek(week);
        Integer limit = limit(season, budget);
        List<Allowance> mine = players(season).stream()
                .filter(p -> ref.commissioner() || p.memberId() == ref.me().getId())
                .map(p -> {
                    int used = used(season, p.id(), budget);
                    return new Allowance(memberId(p.memberId()), p.name(), p.memberId() == ref.me().getId(), limit, used,
                            limit == null ? null : Math.max(0, limit - used));
                })
                .toList();
        return new FreeAgencyView(closedReason(season) == null, closedReason(season), week, budget, limit, mine);
    }

    @Transactional
    public TransactionView claim(String leagueId, String seasonId, ClaimRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null || req.claimCardId() == null || req.dropCardId() == null) throw bad("Choose a card to claim and a card to drop");
        jdbc.sql("select id from season where id = :s for update").param("s", season.getId()).query(Long.class).single();
        String closed = closedReason(season);
        if (closed != null) throw conflict(closed);

        Player player = target(season, ref, req.memberId());
        int week = rules.currentWeek(season);
        int budget = budgetWeek(week);
        Integer limit = limit(season, budget);
        int used = used(season, player.id(), budget);
        if (limit != null && used >= limit) {
            String who = player.memberId() == ref.me().getId() ? "You've" : player.name() + " has";
            throw conflict(limit == 0 ? "Free-agent claims are off this week"
                    : who + " used all " + limit + " free-agent claim" + (limit == 1 ? "" : "s") + " for week " + budget
                      + "; they reset when week " + (budget + 1) + " starts");
        }

        PoolCardView claim = pool.poolCard(season, req.claimCardId()).filter(c -> c.removedInWeek() == null)
                .orElseThrow(() -> conflict("That card isn't in the pool"));
        if (!pool.isAvailable(season, claim.cardId())) {
            if (claim.ownedBy() != null) throw conflict(claim.name() + " belongs to " + claim.ownedBy());
            if (claim.softBanned()) throw conflict(claim.name() + " is soft-banned, so it can't be claimed");
            if ("FORBIDDEN".equals(claim.banStatus())) throw conflict(claim.name() + " is Forbidden, so it can't be claimed");
            throw conflict(claim.name() + " can't be claimed");
        }
        boolean holdsDrop = jdbc.sql("select exists (select 1 from card_holding where season_id = :s and card_id = :c and season_player_id = :p)")
                .param("s", season.getId()).param("c", req.dropCardId()).param("p", player.id()).query(Boolean.class).single();
        if (!holdsDrop) throw conflict((player.memberId() == ref.me().getId() ? "You don't" : player.name() + " doesn't") + " hold the card to drop");
        PoolCardView drop = pool.poolCard(season, req.dropCardId()).orElseThrow();

        long tx = jdbc.sql("""
                        insert into season_transaction (season_id, kind, season_player_id, week, created_by)
                        values (:s, 'FREE_AGENCY', :p, :w, :u) returning id""")
                .param("s", season.getId()).param("p", player.id()).param("w", week).param("u", caller.userId())
                .query(Long.class).single();
        jdbc.sql("""
                        insert into season_transaction_line (transaction_id, card_id, from_player_id, to_player_id)
                        values (:t, :claim, null, :p), (:t, :drop, :p, null)""")
                .param("t", tx).param("claim", claim.cardId()).param("drop", drop.cardId()).param("p", player.id()).update();

        boolean onBehalf = player.userId() == null || player.userId() != caller.userId();
        announcer.announce(season, "**" + DiscordText.escape(player.name()) + "** claimed **" + DiscordText.escape(claim.name())
                + "** and dropped **" + DiscordText.escape(drop.name()) + "**"
                + (onBehalf ? " (by " + DiscordText.escape(caller.displayName()) + ")" : "")
                + (week == 0 ? " · before week 1" : " · week " + week));
        return feed(season, tx).getFirst();
    }

    /** The season's executed transactions, newest first (the latest 200). */
    @Transactional(readOnly = true)
    public List<TransactionView> transactions(String leagueId, String seasonId, CurrentUser caller) {
        return feed(access.member(leagueId, seasonId, caller).season(), null);
    }

    private List<TransactionView> feed(Season season, Long only) {
        record Line(long tx, String kind, int week, Instant at, long memberId, String player, String by, CardRef card, boolean added) {}
        List<Line> lines = jdbc.sql("""
                        select t.id, t.kind, t.week, t.executed_at, m.id, m.display_name,
                               case when m.user_id is distinct from t.created_by then u.display_name end,
                               c.id, c.name, c.passcodes[1], l.to_player_id is not null
                        from (select * from season_transaction where season_id = :s and (cast(:only as bigint) is null or id = :only)
                              order by executed_at desc, id desc limit 200) t
                        join season_player sp on sp.id = t.season_player_id join league_member m on m.id = sp.league_member_id
                        join app_user u on u.id = t.created_by
                        join season_transaction_line l on l.transaction_id = t.id join card c on c.id = l.card_id
                        order by t.executed_at desc, t.id desc, l.id""")
                .param("s", season.getId()).param("only", only, java.sql.Types.BIGINT)
                .query((rs, n) -> new Line(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant(), rs.getLong(5),
                        rs.getString(6), rs.getString(7), new CardRef(rs.getLong(8), rs.getString(9), rs.getLong(10)), rs.getBoolean(11)))
                .list();
        Map<Long, List<Line>> byTx = new LinkedHashMap<>();
        for (Line l : lines) byTx.computeIfAbsent(l.tx(), k -> new ArrayList<>()).add(l);
        return byTx.values().stream().map(ls -> {
            Line first = ls.getFirst();
            return new TransactionView(first.kind(), first.week(), first.at(), memberId(first.memberId()), first.player(),
                    ls.stream().filter(Line::added).map(Line::card).toList(),
                    ls.stream().filter(l -> !l.added()).map(Line::card).toList(), first.by());
        }).toList();
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static String closedReason(Season season) {
        return switch (season.getStatus()) {
            case SETUP, DRAFTING -> "Free agency opens when the draft is complete";
            case COMPLETE -> "The season is over";
            case ACTIVE -> null;
        };
    }

    /** Week 0 (between the draft and week 1) counts toward week 1. */
    static int budgetWeek(int week) {
        return Math.max(week, 1);
    }

    private Integer limit(Season season, int budgetWeek) {
        return rules.inEffect(season, budgetWeek).transactions().freeAgencyPerWeek();
    }

    private int used(Season season, long playerId, int budgetWeek) {
        return jdbc.sql("""
                        select count(*) from season_transaction
                        where season_id = :s and season_player_id = :p and kind = 'FREE_AGENCY' and greatest(week, 1) = :w""")
                .param("s", season.getId()).param("p", playerId).param("w", budgetWeek).query(Integer.class).single();
    }

    private List<Player> players(Season season) {
        return jdbc.sql("""
                        select sp.id, m.id, m.display_name, m.user_id from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.season_id = :s order by sp.seat nulls last, lower(m.display_name)""")
                .param("s", season.getId())
                .query((rs, n) -> new Player(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4)))
                .list();
    }

    /** Whose claim it is: the caller by default; another player only for a commissioner. */
    private Player target(Season season, SeasonAccess.SeasonRef ref, String memberId) {
        long member = memberId == null || memberId.isBlank() ? ref.me().getId()
                : publicIds.decode(PublicIdType.LEAGUE_MEMBER, memberId).orElseThrow(() -> bad("Unknown player"));
        Player player = players(season).stream().filter(p -> p.memberId() == member).findFirst()
                .orElseThrow(() -> bad(member == ref.me().getId() ? "You aren't playing this season" : "That member isn't playing this season"));
        if (member != ref.me().getId() && !ref.commissioner()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a commissioner can make a claim for another player");
        }
        return player;
    }

    private String memberId(long id) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, id);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
