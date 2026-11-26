package com.ygoleague.draft;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.draft.DraftProgress.OnTheClock;
import com.ygoleague.draft.SnakeOrder.Slot;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonPlayer;
import com.ygoleague.season.SeasonPlayerRepository;
import com.ygoleague.season.SeasonRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.pool.PoolOwnership;
import com.ygoleague.season.rules.SeasonRulesDocument;
import com.ygoleague.season.rules.SeasonRulesDocument.DraftOrder;
import com.ygoleague.season.rules.SeasonRulesDocument.DraftRules;
import com.ygoleague.season.rules.SeasonRulesDocument.PickTimer;
import com.ygoleague.season.rules.SeasonRulesDocument.RoundBlock;
import com.ygoleague.season.rules.SeasonRulesDocument.TimerExpiry;
import com.ygoleague.season.rules.SeasonRulesService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * A season's draft: setting it up and starting it (YGO-15), making picks (YGO-16), and the pick
 * timer, skips, make-ups, auto-picks and away mode (YGO-18).
 *
 * <ul>
 *   <li>Draft settings live in the season rules (a versioned section), edited by the season's
 *       commissioners during setup.
 *   <li>Starting needs everything on the readiness checklist. It moves the season out of setup
 *       (players, seats and pool generation are then fixed), locks the calendar (the league's week
 *       start and any derived weeks are written into the season) and records the draft's settings
 *       and seat order as they were at the start in {@code draft.config}. From then on the draft
 *       runs from that config, never from the live rules.
 *   <li>Picks are append-only rows in {@code draft_pick}; the slot on the clock follows from how
 *       many slots they fill. A pick is made by the player whose seat is up, or by a commissioner
 *       for them (needed for placeholder seats, whose players have no account yet). Every pick
 *       locks the season's draft row first, so two picks never interleave.
 *   <li>A card can be drafted while it's in the pool, not soft-banned, not Forbidden (unless the
 *       draft allows it) and has a copy nobody holds.
 *   <li>The pick timer (optional, per pick, with an optional overnight pause) is applied by
 *       {@link DraftTimer}. On expiry it waits, skips (the draft moves on and the player makes the
 *       pick up later, before the draft ends) or auto-picks the first available card in the
 *       player's queue ({@link DraftQueue}), skipping if there is none. A player who is away gets
 *       that auto-pick as soon as they're on the clock; away turns off when their queue runs dry.
 *       Make-ups don't move the clock, but they restart the timer of the pick on it.
 *   <li>The last slot completes the draft (skipped picks still owed are forfeited): the final board is frozen into {@code draft.result}
 *       and the season becomes active.
 *   <li>Any member can see the draft and follow it live ({@link DraftEvents}); the season's
 *       commissioners change and start it. Starting and every pick are announced to open streams.
 * </ul>
 */
@Service
public class DraftService {

    public static final int MAX_ROUNDS = 100;
    public static final int MAX_PICKS_PER_TURN = 5;
    public static final int MIN_TIMER_SECONDS = 15;
    public static final int MAX_TIMER_SECONDS = 7 * 24 * 3600;

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    public enum DraftStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }

    /**
     * @param scheduledStart local date-time in the league's zone, "YYYY-MM-DDTHH:mm", or null
     * @param timerSeconds   time per pick, or null/0 for no timer
     * @param pauseFrom      optional overnight pause, "HH:mm" in the league's zone (with pauseTo)
     */
    public record DraftUpdate(DraftOrder order, List<RoundBlock> rounds, String scheduledStart, Boolean forbiddenDraftable,
                              Integer timerSeconds, TimerExpiry timerExpiry, String pauseFrom, String pauseTo) {}

    /**
     * @param overallPick the slot to fill: the one on the clock (the picker's view of it; if the board
     *                    has moved on, the pick is refused), or a skipped slot being made up
     */
    public record PickRequest(Long cardId, Integer overallPick) {}

    public record AwayRequest(Boolean away) {}

    /** What {@code draft.config} holds: the settings and seat order as they were at the start. */
    record DraftConfig(DraftRules draft, List<ConfigSeat> seats) {}

    record ConfigSeat(int seat, long memberId, String displayName) {}

    public record SeatView(int seat, String displayName, String teamName) {}

    /**
     * @param madeBy   who made the pick (display name); null for an auto-pick
     * @param onBehalf a commissioner made it for the seat's player
     * @param auto     picked automatically from the player's queue (timer or away mode)
     * @param madeUp   made later, in place of a skipped pick
     */
    public record PickView(long cardId, String name, long passcode, String madeBy, boolean onBehalf, boolean auto,
                           boolean madeUp, Instant madeAt) {}

    /**
     * @param pick    the card picked in this slot, or null while it's open (or skipped and not made up)
     * @param skipped the timer ran out on this slot (it may since have been made up)
     */
    public record SlotView(int overallPick, int round, int pickInRound, int seat, PickView pick, boolean skipped) {}

    /**
     * The slot on the clock.
     *
     * @param pickInTurn  which of the seat's consecutive picks this is; picksInTurn: how many it makes in a row
     */
    public record ClockView(int overallPick, int round, int seat, String displayName, int pickInTurn, int picksInTurn) {}

    /** A slot the caller may fill now: the one on the clock, or a skipped one to make up. */
    public record PickableSlot(int overallPick, int round, int seat, String displayName, boolean makeup) {}

    /** A skipped pick not yet made up. */
    public record OwedPick(int overallPick, int round, int seat, String displayName) {}

    /** One readiness item; all must be ok to start. */
    public record Check(String item, boolean ok, String detail) {}

    /**
     * @param picksPerPlayer   picks each player makes over the draft
     * @param draftableCards   pool cards that can be drafted (not soft-banned; not Forbidden unless allowed)
     * @param availableCards   of those, the ones nobody has drafted yet
     * @param board            every pick slot in order (round x seat), with the card picked in each filled one
     * @param timer            the pick timer, or null for none
     * @param picksMade        slots the draft has moved past (picks, auto-picks and skips)
     * @param onTheClock       the slot up next, while the draft is in progress
     * @param deadline         when the pick on the clock runs out (with a timer)
     * @param paused           the timer is in its overnight pause right now
     * @param owed             skipped picks not yet made up, everyone's
     * @param pickable         slots the caller may fill now
     * @param mySeat           the caller's seat, if they play this season
     * @param away             the caller's away mode (null if they don't play)
     * @param canPick          the caller may fill some slot now
     * @param serverTime       the server's clock, so a countdown can correct for the browser's
     */
    public record DraftView(DraftStatus status, boolean canManage, String timeZone, DraftOrder order, List<RoundBlock> rounds,
                            Instant scheduledStart, boolean forbiddenDraftable, PickTimer timer, int roundCount, int picksPerPlayer,
                            int totalPicks, long draftableCards, long availableCards, List<SeatView> seats, List<SlotView> board,
                            int picksMade, ClockView onTheClock, Instant deadline, boolean paused, List<OwedPick> owed,
                            List<PickableSlot> pickable, Integer mySeat, Boolean away, boolean canPick,
                            List<Check> readiness, Instant startedAt, String startedBy, Instant completedAt, Instant serverTime) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final SeasonRepository seasons;
    private final SeasonPlayerRepository players;
    private final DraftQueue queue;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DraftEvents events;
    private final TransactionTemplate tx;

    public DraftService(SeasonAccess access, SeasonRulesService rules, SeasonRepository seasons, SeasonPlayerRepository players,
                        DraftQueue queue, JdbcClient jdbc, ObjectMapper json, DraftEvents events, PlatformTransactionManager txManager) {
        this.access = access;
        this.rules = rules;
        this.seasons = seasons;
        this.players = players;
        this.queue = queue;
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Opens a live stream of draft changes for a member. Access is checked here, inside a short
     * transaction; the stream itself holds no database connection.
     */
    @Transactional(readOnly = true)
    public SseEmitter subscribe(String leagueId, String seasonId, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        return events.subscribe(season.getId(), changeOf(season));
    }

    @Transactional(readOnly = true)
    public DraftView get(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        return view(ref.season(), ref.commissioner(), caller);
    }

    @Transactional
    public DraftView saveSettings(String leagueId, String seasonId, DraftUpdate update, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        ZoneId zone = ZoneId.of(season.getLeague().getTimeZone());
        DraftRules draft = new DraftRules(SeasonRulesDocument.DraftType.SNAKE, update.order(), validRounds(update.rounds()),
                scheduledStart(update.scheduledStart(), zone), Boolean.TRUE.equals(update.forbiddenDraftable()),
                validTimer(update.timerSeconds(), update.timerExpiry(), update.pauseFrom(), update.pauseTo()));
        rules.saveDraft(season, draft, caller);
        return view(season, true, caller);
    }

    /** Starts the draft: checks readiness, leaves setup, locks the calendar and records the settings. */
    @Transactional
    public DraftView start(String leagueId, String seasonId, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        if (season.getStatus() != SeasonStatus.SETUP) throw conflict("The draft has already started");
        List<Check> notReady = readiness(season).stream().filter(c -> !c.ok()).toList();
        if (!notReady.isEmpty()) {
            throw conflict("Not ready to start: " + String.join("; ", notReady.stream().map(Check::detail).toList()));
        }
        DraftRules draft = rules.latest(season).draft();
        List<ConfigSeat> seats = seated(season).stream()
                .map(p -> new ConfigSeat(p.getSeat(), p.getMember().getId(), p.getMember().getDisplayName()))
                .toList();
        rules.lockCalendarForStart(season, caller);
        jdbc.sql("insert into draft (season_id, type, config, started_by) values (:s, :t, cast(:c as jsonb), :u)")
                .param("s", season.getId()).param("t", draft.type().name())
                .param("c", json.writeValueAsString(new DraftConfig(draft, seats))).param("u", caller.userId()).update();
        season.setStatus(SeasonStatus.DRAFTING);
        // Whoever is up first may be away already.
        advance(season);
        events.publish(season.getId(), changeOf(season));
        return view(season, true, caller);
    }

    // ---- Picking ------------------------------------------------------------------------------

    /**
     * Fills a slot: the one on the clock (by the seat's player, or a commissioner for them), or a
     * skipped one being made up (the same people, any time before the draft ends).
     */
    @Transactional
    public DraftView pick(String leagueId, String seasonId, PickRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null || req.cardId() == null) throw bad("Choose a card to draft");
        Loaded d = lockOpen(season);

        OnTheClock clock = d.clock();
        Slot target;
        boolean makeup;
        if (clock != null && (req.overallPick() == null || req.overallPick() == clock.slot().overallPick())) {
            target = clock.slot();
            makeup = false;
        } else {
            target = d.owed().stream().filter(s -> req.overallPick() != null && s.overallPick() == req.overallPick()).findFirst()
                    .orElseThrow(() -> conflict(clock == null ? "The draft is over"
                            : "The board has moved on: pick #" + clock.slot().overallPick() + " is up now"));
            makeup = true;
        }

        SeasonPlayer player = playerAt(season, d.config(), target.seat());
        boolean own = player.getMember().getUser() != null && player.getMember().getUser().getId() == caller.userId();
        if (!own && !ref.commissioner()) {
            String name = player.getMember().getDisplayName();
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, makeup ? "That's " + name + "'s skipped pick" : "It's " + name + "'s pick");
        }

        checkDraftable(season, d.config().draft(), req.cardId());
        record(season, target.overallPick(), player.getId(), "PICK", req.cardId(), caller.userId(), makeup);
        if (!makeup) advance(season);
        events.publish(season.getId(), changeOf(season));
        return view(season, ref.commissioner(), caller);
    }

    /** Turns the caller's away mode on or off; turning it on while on the clock picks right away. */
    @Transactional
    public DraftQueue.QueueView setAway(String leagueId, String seasonId, AwayRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null || req.away() == null) throw bad("Say whether you're away");
        SeasonPlayer me = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .filter(p -> p.getSeat() != null && p.getMember().getId().equals(ref.me().getId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Only players in this season can be away"));
        switch (season.getStatus()) {
            case SETUP -> setAwayFlag(me.getId(), req.away());
            case DRAFTING -> {
                lockOpen(season);
                setAwayFlag(me.getId(), req.away());
                if (req.away() && advance(season)) events.publish(season.getId(), changeOf(season));
            }
            default -> throw conflict("The draft is over");
        }
        return queue.get(leagueId, seasonId, caller);
    }

    /**
     * Applies the timer to every draft whose pick on the clock has run out (called by
     * {@link DraftTimer}). Each draft is handled in its own transaction, so one failing doesn't stop
     * the others.
     */
    public void expireDue(Instant now) {
        List<Long> running = jdbc.sql("""
                        select season_id from draft
                        where completed_at is null and jsonb_typeof(config->'draft'->'pickTimer') = 'object'""")
                .query(Long.class).list();
        for (long seasonId : running) {
            try {
                tx.executeWithoutResult(status -> expire(seasonId, now));
            } catch (RuntimeException e) {
                log.warn("Pick timer failed for season {}", seasonId, e);
            }
        }
    }

    /** One draft's timer: if the pick on the clock is past its deadline, wait, skip or auto-pick. */
    void expire(long seasonId, Instant now) {
        Season season = seasons.findById(seasonId).orElse(null);
        if (season == null) return;
        Loaded d = load(season, true).orElse(null);
        if (d == null || d.completed()) return;
        PickTimer timer = d.config().draft().pickTimer();
        OnTheClock clock = d.clock();
        if (timer == null || clock == null || timer.onExpiry() == TimerExpiry.WAIT) return;
        if (now.isBefore(deadline(d, timer, zone(season)))) return;

        SeasonPlayer player = playerAt(season, d.config(), clock.slot().seat());
        Optional<Long> card = timer.onExpiry() == TimerExpiry.AUTO_PICK
                ? queue.firstAvailable(season, player.getId(), d.config().draft()) : Optional.empty();
        if (card.isPresent()) record(season, clock.slot().overallPick(), player.getId(), "AUTO", card.get(), null, false);
        else record(season, clock.slot().overallPick(), player.getId(), "SKIP", null, null, false);
        advance(season);
        events.publish(season.getId(), changeOf(season));
    }

    /**
     * After the clock moves: completes the draft if every slot is filled, else auto-picks for
     * whoever is up while they're away (and their queue has something; if not, away turns off and
     * they're on the clock as usual). Returns whether it picked anything.
     */
    private boolean advance(Season season) {
        boolean picked = false;
        while (true) {
            Loaded d = load(season, false).orElseThrow();
            if (DraftProgress.complete(d.slots(), d.filled())) {
                complete(season, d);
                return picked;
            }
            Slot slot = d.clock().slot();
            SeasonPlayer player = playerAt(season, d.config(), slot.seat());
            boolean away = jdbc.sql("select draft_away from season_player where id = :p").param("p", player.getId())
                    .query(Boolean.class).single();
            if (!away) return picked;
            Optional<Long> card = queue.firstAvailable(season, player.getId(), d.config().draft());
            if (card.isEmpty()) {
                setAwayFlag(player.getId(), false);
                return picked;
            }
            record(season, slot.overallPick(), player.getId(), "AUTO", card.get(), null, false);
            picked = true;
        }
    }

    /** Appends a row to the pick log (the caller holds the draft lock). */
    private void record(Season season, int overallPick, long seasonPlayerId, String kind, Long cardId, Long madeBy, boolean makeup) {
        jdbc.sql("""
                        insert into draft_pick (season_id, sequence, kind, overall_pick, season_player_id, card_id, made_by, makeup)
                        values (:s, (select coalesce(max(sequence), 0) + 1 from draft_pick where season_id = :s),
                                :kind, :pick, :player, :card, :by, :makeup)""")
                .param("s", season.getId()).param("kind", kind).param("pick", overallPick).param("player", seasonPlayerId)
                .param("card", cardId, java.sql.Types.BIGINT).param("by", madeBy, java.sql.Types.BIGINT).param("makeup", makeup)
                .update();
        if (cardId != null) queue.dropIfTaken(season, cardId);
    }

    private void setAwayFlag(long seasonPlayerId, boolean away) {
        jdbc.sql("update season_player set draft_away = :a where id = :p").param("a", away).param("p", seasonPlayerId).update();
    }

    private void checkDraftable(Season season, DraftRules draft, long cardId) {
        Long rev = rules.latest(season).format().banListRevisionId();
        var card = jdbc.sql("""
                        select c.name, p.soft_banned, b.status, p.copies, %s as held
                        from pool_card p join card c on c.id = p.card_id
                        left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where p.season_id = :s and p.card_id = :c and p.removed_in_week is null""".formatted(PoolOwnership.HELD_COPIES))
                .param("s", season.getId()).param("c", cardId).param("rev", rev, java.sql.Types.BIGINT)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getInt(4), rs.getInt(5)})
                .optional()
                .orElseThrow(() -> conflict("That card isn't in the pool"));
        String name = (String) card[0];
        if ((boolean) card[1]) throw conflict(name + " is soft-banned, so it can't be drafted");
        if ("FORBIDDEN".equals(card[2]) && !draft.forbiddenDraftable()) throw conflict(name + " is Forbidden, so it can't be drafted");
        if ((int) card[4] >= (int) card[3]) throw conflict(name + " has already been drafted");
    }

    /**
     * The last slot filled: freeze the final board and start the season. Skipped picks still owed
     * are forfeited (their slots stay empty in the result).
     */
    private void complete(Season season, Loaded d) {
        Map<Integer, LogRow> picked = d.pickedBySlot();
        Map<Long, SeasonPlayer> byMember = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .collect(Collectors.toMap(p -> p.getMember().getId(), Function.identity()));
        List<Map<String, Object>> board = new ArrayList<>();
        for (Slot slot : d.slots()) {
            LogRow p = picked.get(slot.overallPick());
            ConfigSeat seat = seatOf(d.config(), slot.seat());
            SeasonPlayer player = byMember.get(seat.memberId());
            Map<String, Object> entry = new HashMap<>();
            entry.put("overallPick", slot.overallPick());
            entry.put("round", slot.round());
            entry.put("pickInRound", slot.pickInRound());
            entry.put("seat", slot.seat());
            entry.put("memberId", seat.memberId());
            entry.put("displayName", player.getMember().getDisplayName());
            entry.put("teamName", player.getTeamName());
            entry.put("skipped", d.skippedSlots().contains(slot.overallPick()));
            if (p != null) {
                entry.put("cardId", p.cardId());
                entry.put("cardName", p.cardName());
                entry.put("kind", p.kind());
                entry.put("madeUp", p.makeup());
                entry.put("madeBy", p.madeById());
                entry.put("madeByName", p.madeBy());
                entry.put("madeAt", p.madeAt().toString());
            }
            board.add(entry);
        }
        jdbc.sql("update draft set completed_at = now(), result = cast(:r as jsonb) where season_id = :s")
                .param("s", season.getId()).param("r", json.writeValueAsString(Map.of("board", board))).update();
        season.setStatus(SeasonStatus.ACTIVE);
    }

    // ---- The draft as loaded ------------------------------------------------------------------

    /** One row of the pick log, in order, with its card (if any) and who made it (if anyone). */
    private record LogRow(String kind, int overallPick, boolean makeup, long seasonPlayerId, Long cardId, String cardName,
                          long passcode, Long madeById, String madeBy, Long seatUserId, Instant madeAt) {}

    /** A started draft: its frozen config, its slots and the whole pick log. */
    private record Loaded(DraftConfig config, List<Slot> slots, List<LogRow> log, Instant startedAt, String startedBy,
                          Instant completedAt) {
        boolean completed() { return completedAt != null; }

        /** Slots the clock has moved past: every row except make-ups. */
        int filled() { return (int) log.stream().filter(r -> !r.makeup()).count(); }

        OnTheClock clock() { return completed() ? null : DraftProgress.onTheClock(slots, filled()); }

        /**
         * Where the timer of the pick on the clock counts from: the last row of the log, or the start.
         * A make-up doesn't move the clock, but it restarts the timer like any other pick.
         */
        Instant onClockSince() {
            return log.isEmpty() ? startedAt : log.getLast().madeAt();
        }

        java.util.Set<Integer> skippedSlots() {
            return log.stream().filter(r -> r.kind().equals("SKIP")).map(LogRow::overallPick).collect(Collectors.toSet());
        }

        /** The card picked for each slot (on the clock or as a make-up). */
        Map<Integer, LogRow> pickedBySlot() {
            return log.stream().filter(r -> r.cardId() != null)
                    .collect(Collectors.toMap(LogRow::overallPick, Function.identity(), (a, b) -> b));
        }

        /** Skipped slots not yet made up, in order. Nothing is owed once the draft is over. */
        List<Slot> owed() {
            if (completed()) return List.of();
            Map<Integer, LogRow> picked = pickedBySlot();
            java.util.Set<Integer> skipped = skippedSlots();
            return slots.stream().filter(s -> skipped.contains(s.overallPick()) && !picked.containsKey(s.overallPick())).toList();
        }
    }

    private Optional<Loaded> load(Season season, boolean lock) {
        var row = jdbc.sql("""
                        select d.config::text, d.started_at, u.display_name, d.completed_at
                        from draft d left join app_user u on u.id = d.started_by
                        where d.season_id = :s""" + (lock ? " for update of d" : ""))
                .param("s", season.getId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()})
                .optional();
        if (row.isEmpty()) return Optional.empty();
        DraftConfig config = json.readValue((String) row.get()[0], DraftConfig.class);
        List<LogRow> log = jdbc.sql("""
                        select dp.kind, dp.overall_pick, dp.makeup, dp.season_player_id, c.id, c.name, c.passcodes[1],
                               dp.made_by, u.display_name, m.user_id, dp.made_at
                        from draft_pick dp
                        left join card c on c.id = dp.card_id
                        left join app_user u on u.id = dp.made_by
                        join season_player sp on sp.id = dp.season_player_id
                        join league_member m on m.id = sp.league_member_id
                        where dp.season_id = :s
                        order by dp.sequence""")
                .param("s", season.getId())
                .query((rs, n) -> new LogRow(rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getLong(4), (Long) rs.getObject(5),
                        rs.getString(6), rs.getLong(7), (Long) rs.getObject(8), rs.getString(9), (Long) rs.getObject(10),
                        rs.getTimestamp(11).toInstant()))
                .list();
        Object[] r = row.get();
        return Optional.of(new Loaded(config, slots(config), log, (Instant) r[1], (String) r[2], (Instant) r[3]));
    }

    /** Takes the per-season lock on a draft that's running: every change to the log goes through here first. */
    private Loaded lockOpen(Season season) {
        Loaded d = load(season, true).orElseThrow(() -> conflict("The draft hasn't started"));
        if (d.completed()) throw conflict("The draft is over");
        return d;
    }

    private SeasonPlayer playerAt(Season season, DraftConfig config, int seat) {
        long memberId = seatOf(config, seat).memberId();
        return players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .filter(p -> p.getMember().getId() == memberId).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seat " + seat + " has no season player"));
    }

    private static Instant deadline(Loaded d, PickTimer timer, ZoneId zone) {
        return PickClock.deadline(d.onClockSince(), Duration.ofSeconds(timer.seconds()), zone, timer.pauseFrom(), timer.pauseTo());
    }

    private static ZoneId zone(Season season) {
        return ZoneId.of(season.getLeague().getTimeZone());
    }

    // ---- Reading ------------------------------------------------------------------------------

    private DraftView view(Season season, boolean canManage, CurrentUser caller) {
        Instant now = Instant.now();
        List<SeasonPlayer> seated = seated(season);
        Map<Long, SeasonPlayer> byMember = seated.stream().collect(Collectors.toMap(p -> p.getMember().getId(), Function.identity()));
        SeasonPlayer me = seated.stream()
                .filter(p -> p.getMember().getUser() != null && p.getMember().getUser().getId() == caller.userId())
                .findFirst().orElse(null);
        Integer mySeat = me == null ? null : me.getSeat();
        Boolean away = me == null ? null
                : jdbc.sql("select draft_away from season_player where id = :p").param("p", me.getId()).query(Boolean.class).single();
        Optional<Loaded> loaded = load(season, false);

        if (loaded.isEmpty()) {
            // Not started: everything comes from the live rules and seats, as a preview.
            DraftRules draft = rules.latest(season).draft();
            List<SlotView> board = seated.size() >= 2 && !draft.rounds().isEmpty()
                    ? SnakeOrder.build(seated.size(), toBlocks(draft.rounds()), draft.order() == DraftOrder.SNAKE).stream()
                            .map(s -> new SlotView(s.overallPick(), s.round(), s.pickInRound(), s.seat(), null, false)).toList()
                    : List.of();
            long draftable = draftableCards(season, draft, false);
            return new DraftView(DraftStatus.NOT_STARTED, canManage, season.getLeague().getTimeZone(), draft.order(), draft.rounds(),
                    draft.scheduledStart(), draft.forbiddenDraftable(), draft.pickTimer(), draft.roundCount(), draft.picksPerPlayer(),
                    draft.picksPerPlayer() * seated.size(), draftable, draftable,
                    seated.stream().map(p -> new SeatView(p.getSeat(), p.getMember().getDisplayName(), p.getTeamName())).toList(),
                    board, 0, null, null, false, List.of(), List.of(), mySeat, away, false, readiness(season), null, null, null, now);
        }

        // Started: the draft runs from the config recorded at the start, filled in from the pick log.
        Loaded d = loaded.get();
        DraftRules draft = d.config().draft();
        Map<Integer, LogRow> picked = d.pickedBySlot();
        java.util.Set<Integer> skipped = d.skippedSlots();
        List<SlotView> board = d.slots().stream().map(s -> {
            LogRow p = picked.get(s.overallPick());
            PickView pick = p == null ? null : new PickView(p.cardId(), p.cardName(), p.passcode(), p.madeBy(),
                    p.kind().equals("PICK") && !Objects.equals(p.madeById(), p.seatUserId()), p.kind().equals("AUTO"), p.makeup(), p.madeAt());
            return new SlotView(s.overallPick(), s.round(), s.pickInRound(), s.seat(), pick, skipped.contains(s.overallPick()));
        }).toList();
        List<SeatView> seats = d.config().seats().stream().map(s -> {
            SeasonPlayer p = byMember.get(s.memberId());
            return p == null ? new SeatView(s.seat(), s.displayName(), null)
                    : new SeatView(s.seat(), p.getMember().getDisplayName(), p.getTeamName());
        }).toList();
        Function<Integer, String> nameAt = seat -> seats.stream().filter(s -> s.seat() == seat).map(SeatView::displayName).findFirst().orElse("?");

        OnTheClock clock = d.clock();
        ClockView clockView = null;
        Instant deadline = null;
        boolean paused = false;
        List<PickableSlot> pickable = new ArrayList<>();
        if (clock != null) {
            Slot s = clock.slot();
            clockView = new ClockView(s.overallPick(), s.round(), s.seat(), nameAt.apply(s.seat()), clock.pickInTurn(), clock.picksInTurn());
            PickTimer timer = draft.pickTimer();
            if (timer != null) {
                deadline = deadline(d, timer, zone(season));
                paused = PickClock.paused(now, zone(season), timer.pauseFrom(), timer.pauseTo());
            }
            if (canManage || Objects.equals(mySeat, s.seat())) pickable.add(new PickableSlot(s.overallPick(), s.round(), s.seat(), nameAt.apply(s.seat()), false));
        }
        List<OwedPick> owed = d.owed().stream().map(s -> new OwedPick(s.overallPick(), s.round(), s.seat(), nameAt.apply(s.seat()))).toList();
        for (OwedPick o : owed) {
            if (canManage || Objects.equals(mySeat, o.seat())) pickable.add(new PickableSlot(o.overallPick(), o.round(), o.seat(), o.displayName(), true));
        }
        return new DraftView(d.completed() ? DraftStatus.COMPLETE : DraftStatus.IN_PROGRESS, canManage,
                season.getLeague().getTimeZone(), draft.order(), draft.rounds(), draft.scheduledStart(), draft.forbiddenDraftable(),
                draft.pickTimer(), draft.roundCount(), draft.picksPerPlayer(), d.slots().size(), draftableCards(season, draft, false),
                draftableCards(season, draft, true), seats, board, d.filled(), clockView, deadline, paused, owed, pickable,
                mySeat, away, !pickable.isEmpty(), List.of(), d.startedAt(), d.startedBy(), d.completedAt(), now);
    }

    /** The draft's state as the live stream announces it. */
    private DraftEvents.DraftChanged changeOf(Season season) {
        Optional<Loaded> loaded = load(season, false);
        if (loaded.isEmpty()) return new DraftEvents.DraftChanged(DraftStatus.NOT_STARTED, 0, null);
        Loaded d = loaded.get();
        if (d.completed()) return new DraftEvents.DraftChanged(DraftStatus.COMPLETE, d.filled(), null);
        return new DraftEvents.DraftChanged(DraftStatus.IN_PROGRESS, d.filled(), d.clock().slot().seat());
    }

    private static List<Slot> slots(DraftConfig config) {
        return SnakeOrder.build(config.seats().size(), toBlocks(config.draft().rounds()), config.draft().order() == DraftOrder.SNAKE);
    }

    private static ConfigSeat seatOf(DraftConfig config, int seat) {
        return config.seats().stream().filter(s -> s.seat() == seat).findFirst()
                .orElseThrow(() -> new IllegalStateException("No seat " + seat + " in the draft config"));
    }

    /** What has to be in place before the draft can start. */
    private List<Check> readiness(Season season) {
        SeasonRulesDocument doc = rules.latest(season);
        DraftRules draft = doc.draft();
        int seated = seated(season).size();
        long pool = jdbc.sql("select count(*) from pool_card where season_id = :s and removed_in_week is null")
                .param("s", season.getId()).query(Long.class).single();
        long draftable = draftableCards(season, draft, false);
        int needed = draft.picksPerPlayer() * seated;
        int weeks = rules.weekCount(season);
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("players", seated >= 2,
                seated >= 2 ? seated + " players seated" : "add at least two players on the Setup tab"));
        checks.add(new Check("rounds", !draft.rounds().isEmpty(),
                draft.rounds().isEmpty() ? "set the draft rounds" : draft.roundCount() + " rounds, " + draft.picksPerPlayer() + " picks each"));
        checks.add(new Check("pool", pool > 0, pool > 0 ? pool + " cards in the pool" : "generate the card pool on the Pool tab"));
        checks.add(new Check("enough cards", pool > 0 && needed > 0 && draftable >= needed,
                needed == 0 ? "needs players and rounds first"
                        : draftable >= needed ? draftable + " draftable cards for " + needed + " picks"
                        : "only " + draftable + " draftable cards for " + needed + " picks"));
        checks.add(new Check("calendar", doc.calendar().startDate() != null && weeks > 0,
                doc.calendar().startDate() != null && weeks > 0 ? weeks + " weeks from " + doc.calendar().startDate()
                        : "set the start date and competition (or weeks) on the Rules tab"));
        return checks;
    }

    /**
     * Copies of pool cards that can be drafted: in the pool, not soft-banned, and not Forbidden
     * unless allowed. With {@code unheld}, only the copies nobody has drafted yet.
     */
    private long draftableCards(Season season, DraftRules draft, boolean unheld) {
        Long rev = rules.latest(season).format().banListRevisionId();
        return jdbc.sql("""
                        select coalesce(sum(p.copies%s), 0) from pool_card p
                        left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where p.season_id = :s and p.removed_in_week is null and not p.soft_banned
                          and (:forbiddenOk or b.status is distinct from 'FORBIDDEN')""".formatted(unheld ? " - " + PoolOwnership.HELD_COPIES : ""))
                .param("s", season.getId()).param("rev", rev, java.sql.Types.BIGINT).param("forbiddenOk", draft.forbiddenDraftable())
                .query(Long.class).single();
    }

    private List<SeasonPlayer> seated(Season season) {
        return players.findBySeasonIdOrderBySeat(season.getId()).stream().filter(p -> p.getSeat() != null).toList();
    }

    // ---- Validation ---------------------------------------------------------------------------

    /** Contiguous blocks from round 1, 1 to {@value #MAX_PICKS_PER_TURN} picks a turn, at most {@value #MAX_ROUNDS} rounds. */
    static List<RoundBlock> validRounds(List<RoundBlock> rounds) {
        if (rounds == null || rounds.isEmpty()) return List.of();
        int expected = 1;
        for (RoundBlock r : rounds) {
            if (r.fromRound() != expected || r.toRound() < r.fromRound()) throw bad("Round blocks must run on from round 1 without gaps");
            if (r.picksPerTurn() < 1 || r.picksPerTurn() > MAX_PICKS_PER_TURN) throw bad("Picks per turn is 1 to " + MAX_PICKS_PER_TURN);
            expected = r.toRound() + 1;
        }
        if (expected - 1 > MAX_ROUNDS) throw bad("A draft has at most " + MAX_ROUNDS + " rounds");
        return List.copyOf(rounds);
    }

    /** No timer (null or 0), or {@value #MIN_TIMER_SECONDS} s to 7 days per pick with an optional "HH:mm" pause window. */
    static PickTimer validTimer(Integer seconds, TimerExpiry onExpiry, String pauseFrom, String pauseTo) {
        if (seconds == null || seconds == 0) return null;
        if (seconds < MIN_TIMER_SECONDS || seconds > MAX_TIMER_SECONDS) {
            throw bad("The pick timer is " + MIN_TIMER_SECONDS + " seconds to 7 days");
        }
        LocalTime from = time(pauseFrom);
        LocalTime to = time(pauseTo);
        if ((from == null) != (to == null)) throw bad("The overnight pause needs a start and an end");
        if (from != null && from.equals(to)) throw bad("The overnight pause can't start and end at the same time");
        return new PickTimer(seconds, onExpiry, from, to);
    }

    private static LocalTime time(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return null;
        try {
            return LocalTime.parse(hhmm.strip());
        } catch (DateTimeParseException e) {
            throw bad("Pause times look like 23:00");
        }
    }

    private static List<SnakeOrder.RoundBlock> toBlocks(List<RoundBlock> rounds) {
        return rounds.stream().map(r -> new SnakeOrder.RoundBlock(r.fromRound(), r.toRound(), r.picksPerTurn())).toList();
    }

    private static Instant scheduledStart(String local, ZoneId zone) {
        if (local == null || local.isBlank()) return null;
        try {
            return LocalDateTime.parse(local.strip()).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw bad("The start time must look like 2026-12-01T19:30");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
