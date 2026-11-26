package com.ygoleague.draft;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.draft.DraftProgress.OnTheClock;
import com.ygoleague.draft.SnakeOrder.Slot;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonPlayer;
import com.ygoleague.season.SeasonPlayerRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.pool.PoolOwnership;
import com.ygoleague.season.rules.SeasonRulesDocument;
import com.ygoleague.season.rules.SeasonRulesDocument.DraftOrder;
import com.ygoleague.season.rules.SeasonRulesDocument.DraftRules;
import com.ygoleague.season.rules.SeasonRulesDocument.RoundBlock;
import com.ygoleague.season.rules.SeasonRulesService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * A season's draft: setting it up and starting it (YGO-15), and making picks (YGO-16).
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
 *   <li>The last pick completes the draft: the final board is frozen into {@code draft.result}
 *       and the season becomes active.
 *   <li>Any member can see the draft and follow it live ({@link DraftEvents}); the season's
 *       commissioners change and start it. Starting and every pick are announced to open streams.
 * </ul>
 */
@Service
public class DraftService {

    public static final int MAX_ROUNDS = 100;
    public static final int MAX_PICKS_PER_TURN = 5;

    public enum DraftStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }

    /** @param scheduledStart local date-time in the league's zone, "YYYY-MM-DDTHH:mm", or null */
    public record DraftUpdate(DraftOrder order, List<RoundBlock> rounds, String scheduledStart, Boolean forbiddenDraftable) {}

    /** @param overallPick the slot the picker believes is up; if the board has moved on, the pick is refused */
    public record PickRequest(Long cardId, Integer overallPick) {}

    /** What {@code draft.config} holds: the settings and seat order as they were at the start. */
    record DraftConfig(DraftRules draft, List<ConfigSeat> seats) {}

    record ConfigSeat(int seat, long memberId, String displayName) {}

    public record SeatView(int seat, String displayName, String teamName) {}

    /**
     * @param madeBy   who made the pick (display name)
     * @param onBehalf true when a commissioner made it for the seat's player
     */
    public record PickView(long cardId, String name, long passcode, String madeBy, boolean onBehalf, Instant madeAt) {}

    /** @param pick the card picked in this slot, or null while it's open */
    public record SlotView(int overallPick, int round, int pickInRound, int seat, PickView pick) {}

    /**
     * The slot on the clock.
     *
     * @param pickInTurn  which of the seat's consecutive picks this is; picksInTurn: how many it makes in a row
     */
    public record ClockView(int overallPick, int round, int seat, String displayName, int pickInTurn, int picksInTurn) {}

    /** One readiness item; all must be ok to start. */
    public record Check(String item, boolean ok, String detail) {}

    /**
     * @param picksPerPlayer   picks each player makes over the draft
     * @param draftableCards   pool cards that can be drafted (not soft-banned; not Forbidden unless allowed)
     * @param availableCards   of those, the ones nobody has drafted yet
     * @param board            every pick slot in order (round x seat), with the card picked in each filled one
     * @param onTheClock       the slot up next, while the draft is in progress
     * @param mySeat           the caller's seat, if they play this season
     * @param canPick          the caller may make the pick on the clock (their seat, or a commissioner)
     */
    public record DraftView(DraftStatus status, boolean canManage, String timeZone, DraftOrder order, List<RoundBlock> rounds,
                            Instant scheduledStart, boolean forbiddenDraftable, int roundCount, int picksPerPlayer,
                            int totalPicks, long draftableCards, long availableCards, List<SeatView> seats, List<SlotView> board,
                            int picksMade, ClockView onTheClock, Integer mySeat, boolean canPick,
                            List<Check> readiness, Instant startedAt, String startedBy, Instant completedAt) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final SeasonPlayerRepository players;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DraftEvents events;

    public DraftService(SeasonAccess access, SeasonRulesService rules, SeasonPlayerRepository players, JdbcClient jdbc,
                        ObjectMapper json, DraftEvents events) {
        this.access = access;
        this.rules = rules;
        this.players = players;
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
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
                scheduledStart(update.scheduledStart(), zone), Boolean.TRUE.equals(update.forbiddenDraftable()));
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
        events.publish(season.getId(), changeOf(season));
        return view(season, true, caller);
    }

    // ---- Picking ------------------------------------------------------------------------------

    /** Makes the pick on the clock: by the seat's player, or by a commissioner for them. */
    @Transactional
    public DraftView pick(String leagueId, String seasonId, PickRequest req, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        if (req == null || req.cardId() == null) throw bad("Choose a card to draft");

        // The per-season lock: every pick takes this row first, so the checks below and the insert
        // see a log nobody else is writing to.
        var row = jdbc.sql("select config::text, completed_at from draft where season_id = :s for update")
                .param("s", season.getId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2)}).optional()
                .orElseThrow(() -> conflict("The draft hasn't started"));
        if (row[1] != null) throw conflict("The draft is over");
        DraftConfig config = json.readValue((String) row[0], DraftConfig.class);
        List<Slot> slots = slots(config);
        List<PickRow> log = picks(season);
        OnTheClock clock = DraftProgress.onTheClock(slots, log.size());
        if (clock == null) throw conflict("The draft is over");
        if (req.overallPick() != null && req.overallPick() != clock.slot().overallPick()) {
            throw conflict("The board has moved on: pick #" + clock.slot().overallPick() + " is up now");
        }

        ConfigSeat seat = seatOf(config, clock.slot().seat());
        SeasonPlayer player = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .filter(p -> p.getMember().getId() == seat.memberId()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seat " + seat.seat() + " has no season player"));
        boolean ownTurn = player.getMember().getUser() != null && player.getMember().getUser().getId() == caller.userId();
        if (!ownTurn && !ref.commissioner()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "It's " + player.getMember().getDisplayName() + "'s pick");
        }

        checkDraftable(season, config.draft(), req.cardId());
        jdbc.sql("""
                        insert into draft_pick (season_id, sequence, overall_pick, season_player_id, card_id, made_by)
                        values (:s, :seq, :pick, :player, :card, :by)""")
                .param("s", season.getId()).param("seq", log.size() + 1).param("pick", clock.slot().overallPick())
                .param("player", player.getId()).param("card", req.cardId()).param("by", caller.userId()).update();

        if (DraftProgress.complete(slots, log.size() + 1)) complete(season, config, slots);
        events.publish(season.getId(), changeOf(season));
        return view(season, ref.commissioner(), caller);
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

    /** The last pick: freeze the final board and start the season. */
    private void complete(Season season, DraftConfig config, List<Slot> slots) {
        Map<Integer, PickRow> bySlot = picks(season).stream().collect(Collectors.toMap(PickRow::overallPick, Function.identity(), (a, b) -> b));
        Map<Long, SeasonPlayer> byMember = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .collect(Collectors.toMap(p -> p.getMember().getId(), Function.identity()));
        List<Map<String, Object>> board = new ArrayList<>();
        for (Slot slot : slots) {
            PickRow p = bySlot.get(slot.overallPick());
            ConfigSeat seat = seatOf(config, slot.seat());
            SeasonPlayer player = byMember.get(seat.memberId());
            Map<String, Object> entry = new HashMap<>();
            entry.put("overallPick", slot.overallPick());
            entry.put("round", slot.round());
            entry.put("pickInRound", slot.pickInRound());
            entry.put("seat", slot.seat());
            entry.put("memberId", seat.memberId());
            entry.put("displayName", player.getMember().getDisplayName());
            entry.put("teamName", player.getTeamName());
            entry.put("cardId", p.cardId());
            entry.put("cardName", p.name());
            entry.put("madeBy", p.madeById());
            entry.put("madeByName", p.madeBy());
            entry.put("madeAt", p.madeAt().toString());
            board.add(entry);
        }
        jdbc.sql("update draft set completed_at = now(), result = cast(:r as jsonb) where season_id = :s")
                .param("s", season.getId()).param("r", json.writeValueAsString(Map.of("board", board))).update();
        season.setStatus(SeasonStatus.ACTIVE);
    }

    // ---- Reading ------------------------------------------------------------------------------

    /** One row of the pick log (PICK rows only, in order), with its card and who made it. */
    private record PickRow(int overallPick, long seasonPlayerId, long cardId, String name, long passcode,
                           long madeById, String madeBy, Long seatUserId, Instant madeAt) {}

    private List<PickRow> picks(Season season) {
        return jdbc.sql("""
                        select dp.overall_pick, dp.season_player_id, c.id, c.name, c.passcodes[1], dp.made_by, u.display_name,
                               m.user_id, dp.made_at
                        from draft_pick dp
                        join card c on c.id = dp.card_id
                        join app_user u on u.id = dp.made_by
                        join season_player sp on sp.id = dp.season_player_id
                        join league_member m on m.id = sp.league_member_id
                        where dp.season_id = :s and dp.kind = 'PICK'
                        order by dp.sequence""")
                .param("s", season.getId())
                .query((rs, n) -> new PickRow(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                        rs.getLong(6), rs.getString(7), (Long) rs.getObject(8), rs.getTimestamp(9).toInstant()))
                .list();
    }

    private DraftView view(Season season, boolean canManage, CurrentUser caller) {
        var draftRow = jdbc.sql("""
                        select d.config::text, d.started_at, u.display_name, d.completed_at
                        from draft d left join app_user u on u.id = d.started_by where d.season_id = :s""")
                .param("s", season.getId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()})
                .optional();
        List<SeasonPlayer> seated = seated(season);
        Map<Long, SeasonPlayer> byMember = seated.stream().collect(Collectors.toMap(p -> p.getMember().getId(), Function.identity()));
        Integer mySeat = seated.stream()
                .filter(p -> p.getMember().getUser() != null && p.getMember().getUser().getId() == caller.userId())
                .map(SeasonPlayer::getSeat).findFirst().orElse(null);

        if (draftRow.isEmpty()) {
            // Not started: everything comes from the live rules and seats, as a preview.
            DraftRules draft = rules.latest(season).draft();
            List<SlotView> board = seated.size() >= 2 && !draft.rounds().isEmpty()
                    ? SnakeOrder.build(seated.size(), toBlocks(draft.rounds()), draft.order() == DraftOrder.SNAKE).stream()
                            .map(s -> new SlotView(s.overallPick(), s.round(), s.pickInRound(), s.seat(), null)).toList()
                    : List.of();
            long draftable = draftableCards(season, draft, false);
            return new DraftView(DraftStatus.NOT_STARTED, canManage, season.getLeague().getTimeZone(), draft.order(), draft.rounds(),
                    draft.scheduledStart(), draft.forbiddenDraftable(), draft.roundCount(), draft.picksPerPlayer(),
                    draft.picksPerPlayer() * seated.size(), draftable, draftable,
                    seated.stream().map(p -> new SeatView(p.getSeat(), p.getMember().getDisplayName(), p.getTeamName())).toList(),
                    board, 0, null, mySeat, false, readiness(season), null, null, null);
        }

        // Started: the draft runs from the config recorded at the start, filled in from the pick log.
        Object[] d = draftRow.get();
        DraftConfig config = json.readValue((String) d[0], DraftConfig.class);
        DraftRules draft = config.draft();
        List<Slot> slots = slots(config);
        List<PickRow> log = picks(season);
        Map<Integer, PickRow> bySlot = log.stream().collect(Collectors.toMap(PickRow::overallPick, Function.identity(), (a, b) -> b));
        List<SlotView> board = slots.stream().map(s -> {
            PickRow p = bySlot.get(s.overallPick());
            return new SlotView(s.overallPick(), s.round(), s.pickInRound(), s.seat(), p == null ? null
                    : new PickView(p.cardId(), p.name(), p.passcode(), p.madeBy(), !Objects.equals(p.madeById(), p.seatUserId()), p.madeAt()));
        }).toList();
        List<SeatView> seats = config.seats().stream().map(s -> {
            SeasonPlayer p = byMember.get(s.memberId());
            return p == null ? new SeatView(s.seat(), s.displayName(), null)
                    : new SeatView(s.seat(), p.getMember().getDisplayName(), p.getTeamName());
        }).toList();

        OnTheClock clock = d[3] == null ? DraftProgress.onTheClock(slots, log.size()) : null;
        ClockView clockView = null;
        if (clock != null) {
            String name = seats.stream().filter(s -> s.seat() == clock.slot().seat()).map(SeatView::displayName).findFirst().orElse("?");
            clockView = new ClockView(clock.slot().overallPick(), clock.slot().round(), clock.slot().seat(), name,
                    clock.pickInTurn(), clock.picksInTurn());
        }
        boolean canPick = clock != null && (canManage || Objects.equals(mySeat, clock.slot().seat()));
        return new DraftView(d[3] == null ? DraftStatus.IN_PROGRESS : DraftStatus.COMPLETE, canManage,
                season.getLeague().getTimeZone(), draft.order(), draft.rounds(), draft.scheduledStart(), draft.forbiddenDraftable(),
                draft.roundCount(), draft.picksPerPlayer(), slots.size(), draftableCards(season, draft, false),
                draftableCards(season, draft, true), seats, board, log.size(), clockView, mySeat, canPick,
                List.of(), (Instant) d[1], (String) d[2], (Instant) d[3]);
    }

    /** The draft's state as the live stream announces it. */
    private DraftEvents.DraftChanged changeOf(Season season) {
        var row = jdbc.sql("select config::text, completed_at is not null from draft where season_id = :s")
                .param("s", season.getId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getBoolean(2)}).optional();
        if (row.isEmpty()) return new DraftEvents.DraftChanged(DraftStatus.NOT_STARTED, 0, null);
        int made = jdbc.sql("select count(*) from draft_pick where season_id = :s and kind = 'PICK'")
                .param("s", season.getId()).query(Integer.class).single();
        if ((boolean) row.get()[1]) return new DraftEvents.DraftChanged(DraftStatus.COMPLETE, made, null);
        OnTheClock clock = DraftProgress.onTheClock(slots(json.readValue((String) row.get()[0], DraftConfig.class)), made);
        return new DraftEvents.DraftChanged(DraftStatus.IN_PROGRESS, made, clock == null ? null : clock.slot().seat());
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
