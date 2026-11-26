package com.ygoleague.draft;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonPlayer;
import com.ygoleague.season.SeasonPlayerRepository;
import com.ygoleague.season.SeasonStatus;
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
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Setting up and starting a season's draft (YGO-15). Picking comes in YGO-16.
 *
 * <ul>
 *   <li>Draft settings live in the season rules (a versioned section), edited by the season's
 *       commissioners during setup.
 *   <li>Starting needs everything on the readiness checklist. It moves the season out of setup
 *       (players, seats and pool generation are then fixed), locks the calendar (the league's week
 *       start and any derived weeks are written into the season) and records the draft's settings
 *       and seat order as they were at the start in {@code draft.config}.
 *   <li>Any member can see the draft; the season's commissioners change and start it.
 * </ul>
 */
@Service
public class DraftService {

    public static final int MAX_ROUNDS = 100;
    public static final int MAX_PICKS_PER_TURN = 5;

    public enum DraftStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }

    /** @param scheduledStart local date-time in the league's zone, "YYYY-MM-DDTHH:mm", or null */
    public record DraftUpdate(DraftOrder order, List<RoundBlock> rounds, String scheduledStart, Boolean forbiddenDraftable) {}

    public record SeatView(int seat, String displayName, String teamName) {}

    public record SlotView(int overallPick, int round, int pickInRound, int seat) {}

    /** One readiness item; all must be ok to start. */
    public record Check(String item, boolean ok, String detail) {}

    /**
     * @param picksPerPlayer   picks each player makes over the draft
     * @param draftableCards   pool cards that can be drafted (not soft-banned; not Forbidden unless allowed)
     * @param board            every pick slot in order (round x seat); picks fill it in YGO-16
     */
    public record DraftView(DraftStatus status, boolean canManage, String timeZone, DraftOrder order, List<RoundBlock> rounds,
                            Instant scheduledStart, boolean forbiddenDraftable, int roundCount, int picksPerPlayer,
                            int totalPicks, long draftableCards, List<SeatView> seats, List<SlotView> board,
                            List<Check> readiness, Instant startedAt, String startedBy) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final SeasonPlayerRepository players;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DraftService(SeasonAccess access, SeasonRulesService rules, SeasonPlayerRepository players, JdbcClient jdbc, ObjectMapper json) {
        this.access = access;
        this.rules = rules;
        this.players = players;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public DraftView get(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        return view(ref.season(), ref.commissioner());
    }

    @Transactional
    public DraftView saveSettings(String leagueId, String seasonId, DraftUpdate update, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        ZoneId zone = ZoneId.of(season.getLeague().getTimeZone());
        DraftRules draft = new DraftRules(SeasonRulesDocument.DraftType.SNAKE, update.order(), validRounds(update.rounds()),
                scheduledStart(update.scheduledStart(), zone), Boolean.TRUE.equals(update.forbiddenDraftable()));
        rules.saveDraft(season, draft, caller);
        return view(season, true);
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
        List<Map<String, Object>> seatOrder = seated(season).stream()
                .map(p -> Map.<String, Object>of("seat", p.getSeat(), "memberId", p.getMember().getId(), "displayName", p.getMember().getDisplayName()))
                .toList();
        Map<String, Object> config = Map.of("draft", draft, "seats", seatOrder);
        rules.lockCalendarForStart(season, caller);
        jdbc.sql("insert into draft (season_id, type, config, started_by) values (:s, :t, cast(:c as jsonb), :u)")
                .param("s", season.getId()).param("t", draft.type().name()).param("c", json.writeValueAsString(config))
                .param("u", caller.userId()).update();
        season.setStatus(SeasonStatus.DRAFTING);
        return view(season, true);
    }

    // ---- Reading ------------------------------------------------------------------------------

    private DraftView view(Season season, boolean canManage) {
        DraftRules draft = rules.latest(season).draft();
        List<SeasonPlayer> seated = seated(season);
        List<SlotView> board = List.of();
        if (seated.size() >= 2 && !draft.rounds().isEmpty()) {
            board = SnakeOrder.build(seated.size(), toBlocks(draft.rounds()), draft.order() == DraftOrder.SNAKE).stream()
                    .map(s -> new SlotView(s.overallPick(), s.round(), s.pickInRound(), s.seat()))
                    .toList();
        }
        var started = jdbc.sql("""
                        select d.started_at, u.display_name from draft d left join app_user u on u.id = d.started_by
                        where d.season_id = :s""")
                .param("s", season.getId())
                .query((rs, n) -> new Object[] {rs.getTimestamp(1).toInstant(), rs.getString(2)}).optional();
        DraftStatus status = switch (season.getStatus()) {
            case SETUP -> DraftStatus.NOT_STARTED;
            case DRAFTING -> DraftStatus.IN_PROGRESS;
            default -> DraftStatus.COMPLETE;
        };
        return new DraftView(status, canManage, season.getLeague().getTimeZone(), draft.order(), draft.rounds(),
                draft.scheduledStart(), draft.forbiddenDraftable(), draft.roundCount(), draft.picksPerPlayer(),
                draft.picksPerPlayer() * seated.size(), draftableCards(season, draft),
                seated.stream().map(p -> new SeatView(p.getSeat(), p.getMember().getDisplayName(), p.getTeamName())).toList(),
                board, status == DraftStatus.NOT_STARTED ? readiness(season) : List.of(),
                started.map(r -> (Instant) r[0]).orElse(null), started.map(r -> (String) r[1]).orElse(null));
    }

    /** What has to be in place before the draft can start. */
    private List<Check> readiness(Season season) {
        SeasonRulesDocument doc = rules.latest(season);
        DraftRules draft = doc.draft();
        int seated = seated(season).size();
        long pool = jdbc.sql("select count(*) from pool_card where season_id = :s and removed_in_week is null")
                .param("s", season.getId()).query(Long.class).single();
        long draftable = draftableCards(season, draft);
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

    /** Pool cards that can be drafted: in the pool, not soft-banned, and not Forbidden unless allowed. */
    private long draftableCards(Season season, DraftRules draft) {
        Long rev = rules.latest(season).format().banListRevisionId();
        return jdbc.sql("""
                        select coalesce(sum(p.copies), 0) from pool_card p
                        left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where p.season_id = :s and p.removed_in_week is null and not p.soft_banned
                          and (:forbiddenOk or b.status is distinct from 'FORBIDDEN')""")
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
