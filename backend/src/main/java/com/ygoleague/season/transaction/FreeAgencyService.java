package com.ygoleague.season.transaction;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.notify.DiscordText;
import com.ygoleague.notify.TransactionAnnouncer;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.pool.PoolService;
import com.ygoleague.season.pool.PoolService.PoolCardView;
import com.ygoleague.season.rules.SeasonRulesService;
import com.ygoleague.season.transaction.SeasonPlayers.Player;
import com.ygoleague.season.transaction.TransactionFeed.TransactionView;
import com.ygoleague.season.transaction.WeeklyLimits.Kind;
import java.util.List;
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
 *   <li><b>Weekly limit</b> ({@link WeeklyLimits}): each claim counts once; claims received in
 *       trades add to it. Week 0, between the draft and week 1, shares week 1's allowance.
 *   <li>Open once the draft is complete (the season is active). A player claims for themselves; a
 *       commissioner can claim for any player (placeholders have no account), which counts toward
 *       that player's limit and is shown as made on their behalf.
 *   <li>Trade offers that included the dropped card are cancelled ({@link StaleOffers}).
 *   <li>Claims are serialized per season by locking the season row, so two people can't take the
 *       same card or overrun a limit at the same moment.
 * </ul>
 */
@Service
public class FreeAgencyService {

    /** @param memberId whose claim it is (default: the caller); claimCardId the free agent; dropCardId a card they hold */
    public record ClaimRequest(String memberId, Long claimCardId, Long dropCardId) {}

    /** @param limit this week's claims, with any traded in or out (null = unlimited); left = limit - used (null = unlimited) */
    public record Allowance(String memberId, String displayName, boolean you, Integer limit, int used, Integer left) {}

    /**
     * @param open         claims can be made now
     * @param closedReason why not, when they can't
     * @param week         the league week now (0 = before week 1)
     * @param budgetWeek   the week whose allowance claims count toward now (week 0 counts as week 1)
     * @param limit        the rules' claims per week (before trades); null = unlimited
     * @param players      the players the caller may claim for (themselves; every player for a commissioner)
     */
    public record FreeAgencyView(boolean open, String closedReason, int week, int budgetWeek, Integer limit, List<Allowance> players) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final PoolService pool;
    private final SeasonPlayers players;
    private final WeeklyLimits limits;
    private final StaleOffers staleOffers;
    private final TransactionFeed feed;
    private final TransactionAnnouncer announcer;
    private final JdbcClient jdbc;

    public FreeAgencyService(SeasonAccess access, SeasonRulesService rules, PoolService pool, SeasonPlayers players, WeeklyLimits limits,
                             StaleOffers staleOffers, TransactionFeed feed, TransactionAnnouncer announcer, JdbcClient jdbc) {
        this.access = access;
        this.rules = rules;
        this.pool = pool;
        this.players = players;
        this.limits = limits;
        this.staleOffers = staleOffers;
        this.feed = feed;
        this.announcer = announcer;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public FreeAgencyView status(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        int week = rules.currentWeek(season);
        int budget = WeeklyLimits.allowanceWeek(week);
        List<Allowance> mine = players.list(season).stream()
                .filter(p -> ref.commissioner() || p.memberId() == ref.me().getId())
                .map(p -> {
                    WeeklyLimits.Allowance a = limits.allowance(season, p.id(), Kind.FREE_AGENCY, budget);
                    return new Allowance(players.publicMemberId(p), p.name(), p.memberId() == ref.me().getId(),
                            a.unlimited() ? null : a.base() + a.received() - a.given(), a.used(), a.left());
                })
                .toList();
        String closed = TransactionWindow.closedReason(jdbc, season, "Free agency");
        return new FreeAgencyView(closed == null, closed, week, budget, limits.base(season, Kind.FREE_AGENCY, budget), mine);
    }

    @Transactional
    public TransactionView claim(String leagueId, String seasonId, ClaimRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null || req.claimCardId() == null || req.dropCardId() == null) throw bad("Choose a card to claim and a card to drop");
        TransactionWindow.lock(jdbc, season);
        String closed = TransactionWindow.closedReason(jdbc, season, "Free agency");
        if (closed != null) throw conflict(closed);

        Player player = target(season, ref, req.memberId());
        boolean self = player.memberId() == ref.me().getId();
        int week = rules.currentWeek(season);
        int budget = WeeklyLimits.allowanceWeek(week);
        WeeklyLimits.Allowance allowance = limits.allowance(season, player.id(), Kind.FREE_AGENCY, budget);
        if (!allowance.has(1)) {
            int limit = allowance.base() + allowance.received() - allowance.given();
            throw conflict(limit == 0 ? "Free-agent claims are off this week"
                    : (self ? "You've" : player.name() + " has") + " used all " + limit + " free-agent claim" + (limit == 1 ? "" : "s")
                      + " for week " + budget + "; they reset when week " + (budget + 1) + " starts");
        }

        PoolCardView claim = pool.poolCard(season, req.claimCardId()).filter(c -> c.removedInWeek() == null)
                .orElseThrow(() -> conflict("That card isn't in the pool"));
        if (!pool.isAvailable(season, claim.cardId())) {
            if (claim.ownedBy() != null) throw conflict(claim.name() + " belongs to " + claim.ownedBy());
            if (claim.softBanned()) throw conflict(claim.name() + " is soft-banned, so it can't be claimed");
            if ("FORBIDDEN".equals(claim.banStatus())) throw conflict(claim.name() + " is Forbidden, so it can't be claimed");
            throw conflict(claim.name() + " can't be claimed");
        }
        if (!TransactionWindow.holds(jdbc, season, player.id(), req.dropCardId())) {
            throw conflict((self ? "You don't" : player.name() + " doesn't") + " hold the card to drop");
        }
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
        staleOffers.cancel(season);

        boolean onBehalf = player.userId() == null || player.userId() != caller.userId();
        announcer.announce(season, "**" + DiscordText.escape(player.name()) + "** claimed **" + DiscordText.escape(claim.name())
                + "** and dropped **" + DiscordText.escape(drop.name()) + "**"
                + (onBehalf ? " (by " + DiscordText.escape(caller.displayName()) + ")" : "")
                + TransactionWindow.weekSuffix(week));
        return feed.list(season, tx).getFirst();
    }

    /** The season's executed transactions (claims and trades), newest first (the latest 200). */
    @Transactional(readOnly = true)
    public List<TransactionView> transactions(String leagueId, String seasonId, CurrentUser caller) {
        return feed.list(access.member(leagueId, seasonId, caller).season(), null);
    }

    /** Whose claim it is: the caller by default; another player only for a commissioner. */
    private Player target(Season season, SeasonAccess.SeasonRef ref, String memberId) {
        Player player = memberId == null || memberId.isBlank()
                ? players.byMember(season, ref.me().getId()).orElseThrow(() -> bad("You aren't playing this season"))
                : players.byPublicMemberId(season, memberId).orElseThrow(() -> bad("That member isn't playing this season"));
        if (player.memberId() != ref.me().getId() && !ref.commissioner()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a commissioner can make a claim for another player");
        }
        return player;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
