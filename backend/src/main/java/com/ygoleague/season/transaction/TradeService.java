package com.ygoleague.season.transaction;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.notify.DiscordText;
import com.ygoleague.notify.TransactionAnnouncer;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.rules.SeasonRulesDocument.TransactionRules;
import com.ygoleague.season.rules.SeasonRulesService;
import com.ygoleague.season.transaction.SeasonPlayers.Player;
import com.ygoleague.season.transaction.TransactionFeed.CardRef;
import com.ygoleague.season.transaction.WeeklyLimits.Kind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trades (YGO-24).
 *
 * <ul>
 *   <li><b>Threads:</b> one conversation per pair of players in a season, holding every offer,
 *       counteroffer and message between them. <b>Private to the two players</b>, commissioners
 *       included. A commissioner can act as a <i>placeholder</i> player (no account), never as a
 *       player who has one.
 *   <li><b>Offers</b> give cards and/or allowances (this week's free-agent claims or trades) each
 *       way. <b>Even trades only:</b> both sides give the same number of cards, so rosters keep their
 *       size. An allowance can't be offered for a kind that's unlimited.
 *   <li>The recipient <b>accepts</b> or <b>declines</b> (a declined offer is kept but shown to
 *       nobody); the proposer can <b>withdraw</b>. A <b>counteroffer</b> is a new offer linked to the
 *       one it answers; both stay acceptable. Offers don't expire.
 *   <li><b>Executing</b> (on acceptance, or on a commissioner's approval when the rules ask for it)
 *       re-checks everything, moves the cards and allowances as one append-only transaction, counts
 *       one trade for <b>each</b> player, and cancels every other live offer whose cards moved
 *       ({@link StaleOffers}). Not after the trade deadline week.
 *   <li>Changes are serialized per season by locking the season row, like free agency.
 * </ul>
 */
@Service
public class TradeService {

    public static final int MAX_MESSAGE = 1000;
    static final int MAX_ASSETS_PER_SIDE = 20;
    static final int MAX_ALLOWANCE = 10;

    /** @param kind CARD (with cardId), FREE_AGENCY or TRADE (with quantity, default 1) */
    public record AssetIn(String kind, Long cardId, Integer quantity) {}

    /**
     * @param as         the player you act as (default: yourself; a commissioner may act as a placeholder)
     * @param toMemberId the other player
     * @param give       what you give; {@code get}: what they give
     * @param counterOf  the offer this answers, if any
     * @param message    an optional message posted in the thread with it
     */
    public record OfferRequest(String as, String toMemberId, List<AssetIn> give, List<AssetIn> get, String counterOf, String message) {}

    public record ActRequest(String as) {}

    public record MessageRequest(String as, String body) {}

    public record AssetView(String kind, CardRef card, int quantity) {}

    /**
     * @param fromGives what the proposer gives; {@code toGives} what the recipient gives
     * @param canAccept  etc.: what the viewer (acting as {@code as}) can do with it now
     */
    public record OfferView(String id, String fromMemberId, String from, String toMemberId, String to, String status, String counterOf,
                            Instant at, List<AssetView> fromGives, List<AssetView> toGives, String cancelReason,
                            boolean canAccept, boolean canDecline, boolean canWithdraw, boolean canApprove) {}

    /** @param typedBy who typed it, when not the author's own account (a commissioner for a placeholder) */
    public record MessageView(String authorMemberId, String author, String typedBy, String body, Instant at) {}

    /** One entry of a thread, oldest first: an offer or a message. */
    public record ThreadItem(String type, Instant at, OfferView offer, MessageView message) {}

    /** @param tradesLeft etc.: null = unlimited */
    public record PlayerLimits(Integer tradesLeft, Integer claimsLeft) {}

    public record ThreadView(String withMemberId, String with, List<ThreadItem> items, PlayerLimits mine, PlayerLimits theirs) {}

    public record PlayerRef(String memberId, String name, boolean you) {}

    /** @param live offers still open or waiting for approval; awaitingYou: open offers made to you */
    /** @param unread the other player's messages and offers since you last opened the conversation (YGO-34) */
    public record ThreadSummary(String withMemberId, String with, int live, int awaitingYou, Instant lastAt, int unread) {}

    /**
     * @param actors     who the caller can act as (themselves if they play; placeholders for a commissioner)
     * @param as         who this view is for
     * @param players    everyone else in the season, to start a thread with
     * @param approvals  accepted trades waiting for a commissioner (commissioners only)
     */
    public record TradesView(boolean open, String closedReason, int week, int budgetWeek, TransactionRules rules,
                             List<PlayerRef> actors, String as, PlayerLimits mine, List<PlayerRef> players, List<ThreadSummary> threads,
                             List<OfferView> approvals) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final SeasonPlayers players;
    private final WeeklyLimits limits;
    private final StaleOffers staleOffers;
    private final TransactionAnnouncer announcer;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;
    private final TradeInbox inbox;
    private final MessageEvents events;

    public TradeService(SeasonAccess access, SeasonRulesService rules, SeasonPlayers players, WeeklyLimits limits, StaleOffers staleOffers,
                        TransactionAnnouncer announcer, PublicIds publicIds, JdbcClient jdbc, TradeInbox inbox,
                        MessageEvents events) {
        this.inbox = inbox;
        this.events = events;
        this.access = access;
        this.rules = rules;
        this.players = players;
        this.limits = limits;
        this.staleOffers = staleOffers;
        this.announcer = announcer;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    // ---- Reading ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TradesView overview(String leagueId, String seasonId, String as, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        int week = rules.currentWeek(season);
        int budget = WeeklyLimits.allowanceWeek(week);
        TransactionRules r = rules.inEffect(season, budget).transactions();
        List<Player> all = players.list(season);
        List<Player> actors = actors(all, ref);
        Optional<Player> me = actors.isEmpty() ? Optional.empty() : Optional.of(actor(season, ref, as, actors));

        List<ThreadSummary> threads = me.map(p -> threads(season, p, all)).orElse(List.of());
        List<OfferView> approvals = !ref.commissioner() ? List.of()
                : offers(season, "o.season_id = :s and o.status = 'PENDING_APPROVAL'", Map.of(), null, true);
        return new TradesView(closedReason(season, week) == null, closedReason(season, week), week, budget, r,
                actors.stream().map(p -> ref(p, ref)).toList(), me.map(players::publicMemberId).orElse(null),
                me.map(p -> limitsOf(season, p, budget)).orElse(null),
                all.stream().filter(p -> me.isEmpty() || p.id() != me.get().id()).map(p -> ref(p, ref)).toList(),
                threads, approvals);
    }

    /**
     * Opens a live stream of "your conversations changed" signals for a member (YGO-35). Access is
     * checked here, inside a short transaction; the stream itself holds no database connection.
     */
    @Transactional(readOnly = true)
    public SseEmitter subscribe(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        return events.subscribe(ref.season().getId(), caller.userId(), ref.commissioner());
    }

    /** Opening the conversation as yourself (not as a placeholder you act for) marks it read. */
    @Transactional
    public ThreadView thread(String leagueId, String seasonId, String withMemberId, String as, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        Player me = actor(season, ref, as, actors(players.list(season), ref));
        Player other = other(season, me, withMemberId);
        int budget = limits.currentAllowanceWeek(season);
        Long threadId = threadId(season, me, other).orElse(null);
        if (threadId != null && me.userId() != null && me.userId() == caller.userId()) inbox.markRead(threadId, me.id());

        List<ThreadItem> items = new ArrayList<>();
        if (threadId != null) {
            for (OfferView o : offers(season, "o.thread_id = :t and o.status <> 'DECLINED'", Map.of("t", threadId), me, ref.commissioner())) {
                items.add(new ThreadItem("OFFER", o.at(), o, null));
            }
            jdbc.sql("""
                            select m.id, lm.display_name, case when lm.user_id is distinct from tm.author_user_id then u.display_name end,
                                   tm.body, tm.created_at
                            from trade_message tm join season_player sp on sp.id = tm.author_player_id
                            join league_member lm on lm.id = sp.league_member_id join league_member m on m.id = lm.id
                            join app_user u on u.id = tm.author_user_id
                            where tm.thread_id = :t order by tm.created_at, tm.id""")
                    .param("t", threadId)
                    .query(rs -> {
                        Instant at = rs.getTimestamp(5).toInstant();
                        items.add(new ThreadItem("MESSAGE", at, null, new MessageView(memberId(rs.getLong(1)), rs.getString(2),
                                rs.getString(3), rs.getString(4), at)));
                    });
        }
        items.sort(Comparator.comparing(ThreadItem::at));
        return new ThreadView(players.publicMemberId(other), other.name(), items, limitsOf(season, me, budget), limitsOf(season, other, budget));
    }

    // ---- Changing -----------------------------------------------------------------------------

    @Transactional
    public OfferView propose(String leagueId, String seasonId, OfferRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null) throw bad("Send the offer");
        TransactionWindow.lock(jdbc, season);
        int week = rules.currentWeek(season);
        String closed = closedReason(season, week);
        if (closed != null) throw conflict(closed);
        Player me = actor(season, ref, req.as(), actors(players.list(season), ref));
        Player other = other(season, me, req.toMemberId());
        int budget = WeeklyLimits.allowanceWeek(week);

        List<Asset> give = assets(req.give(), me);
        List<Asset> get = assets(req.get(), other);
        if (give.isEmpty() && get.isEmpty()) throw bad("An offer needs something on at least one side");
        long giveCards = give.stream().filter(a -> a.kind().equals("CARD")).count();
        long getCards = get.stream().filter(a -> a.kind().equals("CARD")).count();
        if (giveCards != getCards) {
            throw bad("Trades are even: both sides give the same number of cards (" + giveCards + " for " + getCards + " here), so rosters keep their size");
        }
        Set<Long> seen = new HashSet<>();
        for (Asset a : concat(give, get)) {
            if (a.cardId() != null && !seen.add(a.cardId())) throw bad("A card can only be in an offer once");
        }
        checkAssets(season, me, other, give, get, budget, true);

        Long counterOf = null;
        long thread = threadFor(season, me, other);
        if (req.counterOf() != null && !req.counterOf().isBlank()) {
            long id = decodeOffer(req.counterOf());
            counterOf = jdbc.sql("select id from trade_offer where id = :o and thread_id = :t and status <> 'DECLINED'")
                    .param("o", id).param("t", thread).query(Long.class).optional()
                    .orElseThrow(() -> bad("That offer isn't in this conversation"));
        }

        long offer = jdbc.sql("""
                        insert into trade_offer (thread_id, season_id, from_player_id, to_player_id, counter_of, created_by)
                        values (:t, :s, :f, :to, :c, :u) returning id""")
                .param("t", thread).param("s", season.getId()).param("f", me.id()).param("to", other.id())
                .param("c", counterOf, java.sql.Types.BIGINT).param("u", caller.userId())
                .query(Long.class).single();
        for (Asset a : concat(give, get)) {
            jdbc.sql("insert into trade_offer_asset (offer_id, giver_id, kind, card_id, quantity) values (:o, :g, :k, :c, :q)")
                    .param("o", offer).param("g", a.giver().id()).param("k", a.kind()).param("c", a.cardId(), java.sql.Types.BIGINT)
                    .param("q", a.quantity()).update();
        }
        if (req.message() != null && !req.message().isBlank()) postMessage(thread, me, req.message(), caller);
        changed(season, me, other, false);
        return offer(season, offer, me, ref.commissioner());
    }

    @Transactional
    public OfferView accept(String leagueId, String seasonId, String offerId, ActRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        TransactionWindow.lock(jdbc, season);
        Player me = actor(season, ref, req == null ? null : req.as(), actors(players.list(season), ref));
        Offer o = load(season, offerId, me);
        if (o.toId() != me.id()) throw forbidden("Only the player the offer was made to can accept it");
        if (!o.status().equals("OPEN")) throw conflict("This offer is " + o.status().toLowerCase().replace('_', ' '));
        int week = rules.currentWeek(season);
        String closed = closedReason(season, week);
        if (closed != null) throw conflict(closed);
        checkExecutable(season, o, WeeklyLimits.allowanceWeek(week));

        if (rules.inEffect(season, WeeklyLimits.allowanceWeek(week)).transactions().commissionerApproval()) {
            jdbc.sql("update trade_offer set status = 'PENDING_APPROVAL', accepted_at = now(), accepted_by = :u where id = :o")
                    .param("u", caller.userId()).param("o", o.id()).update();
        } else {
            jdbc.sql("update trade_offer set accepted_at = now(), accepted_by = :u where id = :o").param("u", caller.userId()).param("o", o.id()).update();
            execute(season, o, week, caller);
        }
        changed(season, o.from(), o.to(), true);
        return offer(season, o.id(), me, ref.commissioner());
    }

    @Transactional
    public OfferView decline(String leagueId, String seasonId, String offerId, ActRequest req, CurrentUser caller) {
        return close(leagueId, seasonId, offerId, req, caller, "DECLINED");
    }

    @Transactional
    public OfferView withdraw(String leagueId, String seasonId, String offerId, ActRequest req, CurrentUser caller) {
        return close(leagueId, seasonId, offerId, req, caller, "WITHDRAWN");
    }

    private OfferView close(String leagueId, String seasonId, String offerId, ActRequest req, CurrentUser caller, String status) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        TransactionWindow.lock(jdbc, season);
        Player me = actor(season, ref, req == null ? null : req.as(), actors(players.list(season), ref));
        Offer o = load(season, offerId, me);
        boolean decline = status.equals("DECLINED");
        if (decline && o.toId() != me.id()) throw forbidden("Only the player the offer was made to can decline it");
        if (!decline && o.fromId() != me.id()) throw forbidden("Only the player who made the offer can withdraw it");
        if (!o.status().equals("OPEN")) throw conflict("This offer is " + o.status().toLowerCase().replace('_', ' '));
        jdbc.sql("update trade_offer set status = :st, decided_at = now(), decided_by = :u where id = :o")
                .param("st", status).param("u", caller.userId()).param("o", o.id()).update();
        changed(season, o.from(), o.to(), false);
        return offer(season, o.id(), me, ref.commissioner());
    }

    /** A commissioner approves an accepted trade (when the rules ask for approval): it executes now. */
    @Transactional
    public OfferView approve(String leagueId, String seasonId, String offerId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(leagueId, seasonId, caller);
        Season season = ref.season();
        TransactionWindow.lock(jdbc, season);
        Offer o = loadPending(season, offerId);
        int week = rules.currentWeek(season);
        String closed = closedReason(season, week);
        if (closed != null) throw conflict(closed);
        checkExecutable(season, o, WeeklyLimits.allowanceWeek(week));
        jdbc.sql("update trade_offer set decided_at = now(), decided_by = :u where id = :o").param("u", caller.userId()).param("o", o.id()).update();
        execute(season, o, week, caller);
        changed(season, o.from(), o.to(), true);
        return offer(season, o.id(), null, true);
    }

    @Transactional
    public OfferView reject(String leagueId, String seasonId, String offerId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(leagueId, seasonId, caller);
        Season season = ref.season();
        TransactionWindow.lock(jdbc, season);
        Offer o = loadPending(season, offerId);
        jdbc.sql("update trade_offer set status = 'REJECTED', decided_at = now(), decided_by = :u where id = :o")
                .param("u", caller.userId()).param("o", o.id()).update();
        changed(season, o.from(), o.to(), true);
        return offer(season, o.id(), null, true);
    }

    @Transactional
    public ThreadView message(String leagueId, String seasonId, String withMemberId, MessageRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null || req.body() == null || req.body().isBlank()) throw bad("Write a message");
        Player me = actor(season, ref, req.as(), actors(players.list(season), ref));
        Player other = other(season, me, withMemberId);
        postMessage(threadFor(season, me, other), me, req.body(), caller);
        changed(season, me, other, false);
        return thread(leagueId, seasonId, withMemberId, req.as(), caller);
    }

    // ---- Executing ----------------------------------------------------------------------------

    /** Everything that must still hold for the trade to happen now; throws a 409 saying what doesn't. */
    private void checkExecutable(Season season, Offer o, int budget) {
        List<Asset> assets = assetsOf(o.id());
        checkAssets(season, o.from(), o.to(), assets.stream().filter(a -> a.giver().id() == o.fromId()).toList(),
                assets.stream().filter(a -> a.giver().id() == o.toId()).toList(), budget, false);
    }

    /**
     * Cards held by their givers; allowances available; and (when executing) a trade left for both
     * players, after any trade allowances they give away. When proposing, only the giver's own cards
     * and allowances are checked against now; limits are checked again on acceptance.
     */
    private void checkAssets(Season season, Player from, Player to, List<Asset> fromGives, List<Asset> toGives, int budget, boolean proposing) {
        for (Asset a : concat(fromGives, toGives)) {
            if (a.cardId() != null && !TransactionWindow.holds(jdbc, season, a.giver().id(), a.cardId())) {
                String name = jdbc.sql("select name from card where id = :c").param("c", a.cardId()).query(String.class).optional().orElse("That card");
                throw conflict(a.giver().name() + " doesn't hold " + name);
            }
        }
        for (Player p : List.of(from, to)) {
            List<Asset> gives = p.id() == from.id() ? fromGives : toGives;
            int claims = gives.stream().filter(a -> a.kind().equals("FREE_AGENCY")).mapToInt(Asset::quantity).sum();
            int trades = gives.stream().filter(a -> a.kind().equals("TRADE")).mapToInt(Asset::quantity).sum();
            WeeklyLimits.Allowance fa = limits.allowance(season, p.id(), Kind.FREE_AGENCY, budget);
            WeeklyLimits.Allowance tr = limits.allowance(season, p.id(), Kind.TRADE, budget);
            if (claims > 0 && fa.unlimited()) throw bad("Free-agent claims are unlimited, so they can't be traded");
            if (trades > 0 && tr.unlimited()) throw bad("Trades are unlimited, so they can't be traded");
            if (!fa.has(claims)) throw conflict(p.name() + " has only " + fa.left() + " free-agent claim" + (fa.left() == 1 ? "" : "s") + " left this week");
            // The trade itself uses one of each player's trades, on top of any they give away.
            int needed = trades + (proposing ? 0 : 1);
            if (!tr.has(needed)) {
                throw conflict(tr.left() == 0 ? p.name() + " has no trades left for week " + budget
                        : p.name() + " has only " + tr.left() + " trade" + (tr.left() == 1 ? "" : "s") + " left for week " + budget);
            }
        }
    }

    private void execute(Season season, Offer o, int week, CurrentUser caller) {
        int budget = WeeklyLimits.allowanceWeek(week);
        long tx = jdbc.sql("""
                        insert into season_transaction (season_id, kind, season_player_id, counterparty_id, week, created_by)
                        values (:s, 'TRADE', :f, :t, :w, :u) returning id""")
                .param("s", season.getId()).param("f", o.fromId()).param("t", o.toId()).param("w", week).param("u", caller.userId())
                .query(Long.class).single();
        List<Asset> assets = assetsOf(o.id());
        for (Asset a : assets) {
            long receiver = a.giver().id() == o.fromId() ? o.toId() : o.fromId();
            if (a.cardId() != null) {
                jdbc.sql("insert into season_transaction_line (transaction_id, card_id, from_player_id, to_player_id) values (:t, :c, :f, :to)")
                        .param("t", tx).param("c", a.cardId()).param("f", a.giver().id()).param("to", receiver).update();
            } else {
                jdbc.sql("""
                                insert into season_transaction_allowance (transaction_id, kind, from_player_id, to_player_id, quantity, week)
                                values (:t, :k, :f, :to, :q, :w)""")
                        .param("t", tx).param("k", a.kind()).param("f", a.giver().id()).param("to", receiver).param("q", a.quantity())
                        .param("w", budget).update();
            }
        }
        jdbc.sql("update trade_offer set status = 'EXECUTED', transaction_id = :t where id = :o").param("t", tx).param("o", o.id()).update();
        staleOffers.cancel(season);
        announcer.announce(season, "**" + DiscordText.escape(o.from().name()) + "** and **" + DiscordText.escape(o.to().name())
                + "** traded: " + side(o.to(), assets, o.fromId()) + "; " + side(o.from(), assets, o.toId()) + TransactionWindow.weekSuffix(week));
    }

    /** "**B** gets Card X, 1 free-agent claim" for what {@code giverId} gave. */
    private String side(Player receiver, List<Asset> assets, long giverId) {
        List<String> parts = new ArrayList<>();
        for (Asset a : assets) {
            if (a.giver().id() != giverId) continue;
            parts.add(a.cardId() != null ? "**" + DiscordText.escape(a.cardName()) + "**"
                    : a.quantity() + (a.kind().equals("TRADE") ? " trade" : " free-agent claim") + (a.quantity() == 1 ? "" : "s"));
        }
        return DiscordText.escape(receiver.name()) + " gets " + (parts.isEmpty() ? "nothing" : String.join(", ", parts));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private record Asset(Player giver, String kind, Long cardId, String cardName, int quantity) {}

    private record Offer(long id, long fromId, long toId, Player from, Player to, String status) {}

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private List<Asset> assets(List<AssetIn> in, Player giver) {
        if (in == null) return List.of();
        if (in.size() > MAX_ASSETS_PER_SIDE) throw bad("At most " + MAX_ASSETS_PER_SIDE + " things per side");
        List<Asset> out = new ArrayList<>();
        Map<String, Integer> allowances = new LinkedHashMap<>();
        for (AssetIn a : in) {
            String kind = a == null || a.kind() == null ? "" : a.kind().strip().toUpperCase();
            switch (kind) {
                case "CARD" -> {
                    if (a.cardId() == null) throw bad("Choose the card");
                    out.add(new Asset(giver, "CARD", a.cardId(), null, 1));
                }
                case "FREE_AGENCY", "TRADE" -> {
                    int q = a.quantity() == null ? 1 : a.quantity();
                    if (q < 1 || q > MAX_ALLOWANCE) throw bad("Allowances are 1 to " + MAX_ALLOWANCE + " at a time");
                    allowances.merge(kind, q, Integer::sum);
                }
                default -> throw bad("Unknown kind of asset: " + kind);
            }
        }
        allowances.forEach((k, q) -> out.add(new Asset(giver, k, null, null, q)));
        return out;
    }

    private List<Asset> assetsOf(long offerId) {
        Map<Long, Player> byId = new LinkedHashMap<>();
        return jdbc.sql("""
                        select a.giver_id, a.kind, a.card_id, c.name, a.quantity from trade_offer_asset a left join card c on c.id = a.card_id
                        where a.offer_id = :o order by a.id""")
                .param("o", offerId)
                .query((rs, n) -> new Asset(new Player(rs.getLong(1), 0, null, null), rs.getString(2), (Long) rs.getObject(3), rs.getString(4),
                        rs.getInt(5)))
                .list().stream()
                .map(a -> new Asset(byId.computeIfAbsent(a.giver().id(), this::playerById), a.kind(), a.cardId(), a.cardName(), a.quantity()))
                .toList();
    }

    private Player playerById(long seasonPlayerId) {
        return jdbc.sql("""
                        select sp.id, m.id, m.display_name, m.user_id from season_player sp join league_member m on m.id = sp.league_member_id
                        where sp.id = :p""")
                .param("p", seasonPlayerId)
                .query((rs, n) -> new Player(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4))).single();
    }

    /** An offer the viewer is a party to; 404 otherwise (offers are private to their players). */
    private Offer load(Season season, String publicId, Player viewer) {
        long id = decodeOffer(publicId);
        Offer o = jdbc.sql("select id, from_player_id, to_player_id, status from trade_offer where id = :o and season_id = :s and status <> 'DECLINED'")
                .param("o", id).param("s", season.getId())
                .query((rs, n) -> new Offer(rs.getLong(1), rs.getLong(2), rs.getLong(3), playerById(rs.getLong(2)), playerById(rs.getLong(3)),
                        rs.getString(4)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such offer"));
        if (viewer != null && o.fromId() != viewer.id() && o.toId() != viewer.id()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such offer");
        return o;
    }

    private Offer loadPending(Season season, String publicId) {
        Offer o = load(season, publicId, null);
        if (!o.status().equals("PENDING_APPROVAL")) throw conflict("This trade isn't waiting for approval");
        return o;
    }

    private long decodeOffer(String publicId) {
        return publicIds.decode(PublicIdType.TRADE_OFFER, publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such offer"));
    }

    private OfferView offer(Season season, long id, Player viewer, boolean commissioner) {
        return offers(season, "o.id = :o", Map.of("o", id), viewer, commissioner).getFirst();
    }

    /** Offers matching a condition on {@code trade_offer o}, oldest first, with what {@code viewer} may do with each. */
    private List<OfferView> offers(Season season, String where, Map<String, Object> params, Player viewer, boolean commissioner) {
        record Row(long id, long fromId, long fromMember, String from, long toId, long toMember, String to, String status, Long counterOf,
                   Instant at, String cancelReason) {}
        var q = jdbc.sql("""
                        select o.id, o.from_player_id, fm.id, fm.display_name, o.to_player_id, tm.id, tm.display_name, o.status, o.counter_of,
                               o.created_at, o.cancel_reason
                        from trade_offer o
                        join season_player fp on fp.id = o.from_player_id join league_member fm on fm.id = fp.league_member_id
                        join season_player tp on tp.id = o.to_player_id join league_member tm on tm.id = tp.league_member_id
                        where o.season_id = :s and """ + " " + where + " order by o.created_at, o.id")
                .param("s", season.getId());
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        List<Row> rows = q.query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getLong(6),
                rs.getString(7), rs.getString(8), (Long) rs.getObject(9), rs.getTimestamp(10).toInstant(), rs.getString(11))).list();
        if (rows.isEmpty()) return List.of();

        Map<Long, List<AssetView>> fromGives = new LinkedHashMap<>();
        Map<Long, List<AssetView>> toGives = new LinkedHashMap<>();
        Map<Long, Long> fromOf = new LinkedHashMap<>();
        for (Row r : rows) fromOf.put(r.id(), r.fromId());
        jdbc.sql("""
                        select a.offer_id, a.giver_id, a.kind, c.id, c.name, c.passcodes[1], a.quantity
                        from trade_offer_asset a left join card c on c.id = a.card_id
                        where a.offer_id in (:ids) order by a.offer_id, a.kind = 'CARD' desc, c.name, a.id""")
                .param("ids", rows.stream().map(Row::id).toList())
                .query(rs -> {
                    long offer = rs.getLong(1);
                    CardRef card = rs.getObject(4) == null ? null : new CardRef(rs.getLong(4), rs.getString(5), rs.getLong(6));
                    AssetView a = new AssetView(rs.getString(3), card, rs.getInt(7));
                    (rs.getLong(2) == fromOf.get(offer) ? fromGives : toGives).computeIfAbsent(offer, k -> new ArrayList<>()).add(a);
                });

        return rows.stream().map(r -> {
            boolean open = r.status().equals("OPEN");
            boolean toMe = viewer != null && viewer.id() == r.toId();
            boolean fromMe = viewer != null && viewer.id() == r.fromId();
            return new OfferView(offerId(r.id()), memberId(r.fromMember()), r.from(), memberId(r.toMember()), r.to(), r.status(),
                    r.counterOf() == null ? null : offerId(r.counterOf()), r.at(),
                    fromGives.getOrDefault(r.id(), List.of()), toGives.getOrDefault(r.id(), List.of()), r.cancelReason(),
                    open && toMe, open && toMe, open && fromMe, commissioner && r.status().equals("PENDING_APPROVAL"));
        }).toList();
    }

    private List<ThreadSummary> threads(Season season, Player me, List<Player> all) {
        Map<Long, Player> byId = new LinkedHashMap<>();
        for (Player p : all) byId.put(p.id(), p);
        return jdbc.sql("""
                        select case when t.player_low_id = :p then t.player_high_id else t.player_low_id end,
                               (select count(*) from trade_offer o where o.thread_id = t.id and o.status in ('OPEN', 'PENDING_APPROVAL')),
                               (select count(*) from trade_offer o where o.thread_id = t.id and o.status = 'OPEN' and o.to_player_id = :p),
                               greatest(t.created_at,
                                        (select max(o.created_at) from trade_offer o where o.thread_id = t.id and o.status <> 'DECLINED'),
                                        (select max(m.created_at) from trade_message m where m.thread_id = t.id)),
                               (select count(*) from (
                                   select m.created_at from trade_message m where m.thread_id = t.id and m.author_player_id <> :p
                                   union all
                                   select o.created_at from trade_offer o where o.thread_id = t.id and o.to_player_id = :p) x
                                where x.created_at > coalesce((select r.last_read_at from trade_thread_read r
                                                               where r.thread_id = t.id and r.season_player_id = :p), '-infinity'))
                        from trade_thread t
                        where t.season_id = :s and (t.player_low_id = :p or t.player_high_id = :p)
                        order by 4 desc""")
                .param("s", season.getId()).param("p", me.id())
                .query((rs, n) -> {
                    Player other = byId.get(rs.getLong(1));
                    return new ThreadSummary(players.publicMemberId(other), other.name(), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toInstant(),
                            rs.getInt(5));
                })
                .list();
    }

    private Optional<Long> threadId(Season season, Player a, Player b) {
        return jdbc.sql("select id from trade_thread where season_id = :s and player_low_id = :lo and player_high_id = :hi")
                .param("s", season.getId()).param("lo", Math.min(a.id(), b.id())).param("hi", Math.max(a.id(), b.id()))
                .query(Long.class).optional();
    }

    private long threadFor(Season season, Player a, Player b) {
        return threadId(season, a, b).orElseGet(() -> jdbc.sql("""
                        insert into trade_thread (season_id, player_low_id, player_high_id) values (:s, :lo, :hi)
                        on conflict (season_id, player_low_id, player_high_id) do update set season_id = excluded.season_id
                        returning id""")
                .param("s", season.getId()).param("lo", Math.min(a.id(), b.id())).param("hi", Math.max(a.id(), b.id()))
                .query(Long.class).single());
    }

    private void postMessage(long thread, Player author, String body, CurrentUser caller) {
        String text = body.strip();
        if (text.length() > MAX_MESSAGE) throw bad("Messages are at most " + MAX_MESSAGE + " characters");
        jdbc.sql("insert into trade_message (thread_id, author_player_id, author_user_id, body) values (:t, :p, :u, :b)")
                .param("t", thread).param("p", author.id()).param("u", caller.userId()).param("b", text).update();
    }

    /** The players the caller can act as: themselves if they play; any placeholder if they commission the season. */
    private List<Player> actors(List<Player> all, SeasonAccess.SeasonRef ref) {
        return all.stream().filter(p -> p.memberId() == ref.me().getId() || (ref.commissioner() && p.placeholder())).toList();
    }

    private Player actor(Season season, SeasonAccess.SeasonRef ref, String as, List<Player> actors) {
        if (as == null || as.isBlank()) {
            return actors.stream().filter(p -> p.memberId() == ref.me().getId()).findFirst()
                    .or(() -> actors.stream().findFirst())
                    .orElseThrow(() -> forbidden("You aren't playing this season"));
        }
        Player p = players.byPublicMemberId(season, as).orElseThrow(() -> bad("That member isn't playing this season"));
        if (actors.stream().noneMatch(a -> a.id() == p.id())) {
            throw forbidden("You can only trade as yourself" + (ref.commissioner() ? " or a placeholder player" : ""));
        }
        return p;
    }

    private Player other(Season season, Player me, String memberId) {
        if (memberId == null || memberId.isBlank()) throw bad("Choose the other player");
        Player other = players.byPublicMemberId(season, memberId).orElseThrow(() -> bad("That member isn't playing this season"));
        if (other.id() == me.id()) throw bad("You can't trade with yourself");
        return other;
    }

    private PlayerRef ref(Player p, SeasonAccess.SeasonRef ref) {
        return new PlayerRef(players.publicMemberId(p), p.name(), p.memberId() == ref.me().getId());
    }

    private PlayerLimits limitsOf(Season season, Player p, int budget) {
        return new PlayerLimits(limits.allowance(season, p.id(), Kind.TRADE, budget).left(),
                limits.allowance(season, p.id(), Kind.FREE_AGENCY, budget).left());
    }

    /** Closed until the draft is over, after the season, and after the trade deadline week. */
    private String closedReason(Season season, int week) {
        String closed = TransactionWindow.closedReason(jdbc, season, "Trading");
        if (closed != null) return closed;
        Integer deadline = rules.inEffect(season, WeeklyLimits.allowanceWeek(week)).transactions().tradeDeadlineWeek();
        if (deadline != null && week > deadline) return "The trade deadline (week " + deadline + ") has passed";
        return null;
    }

    /** Tells both players' open pages (and commissioners', when it concerns them) that their conversation changed (YGO-35). */
    private void changed(Season season, Player a, Player b, boolean commissioners) {
        events.conversationChanged(season.getId(), java.util.Arrays.asList(a.userId(), b.userId()), commissioners);
    }

    private String memberId(long id) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, id);
    }

    private String offerId(long id) {
        return publicIds.encode(PublicIdType.TRADE_OFFER, id);
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
