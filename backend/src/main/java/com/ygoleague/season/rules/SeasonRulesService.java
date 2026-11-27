package com.ygoleague.season.rules;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.banlist.BanList;
import com.ygoleague.banlist.BanListRevision;
import com.ygoleague.banlist.BanListRevisionRepository;
import com.ygoleague.calendar.SeasonCalendar;
import com.ygoleague.calendar.WeekBoundary;
import com.ygoleague.league.League;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonRules;
import com.ygoleague.season.SeasonRulesRepository;
import com.ygoleague.season.SeasonPlayerRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.competition.RoundRobin;
import com.ygoleague.season.rules.SeasonRulesDocument.CompetitionFormat;
import com.ygoleague.season.rules.SeasonRulesDocument.CompetitionRules;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reading and changing a season's rules. Rules are versioned and append-only
 * ({@link SeasonRules}): every save is a new version, so the history of changes is kept.
 *
 * <ul>
 *   <li>During setup, versions take effect from week 0, so the latest one is what applies.
 *   <li>Once the season has started, a change takes effect from a given week, no earlier than the
 *       current week or the latest change, so the past is always judged by the rules of the time.
 *       The calendar can't change then (off weeks will be the exception, in Phase 4).
 *   <li>Saving rules identical to the latest version does nothing.
 *   <li>Any member can read the rules; a season's commissioners change them.
 * </ul>
 */
@Service
public class SeasonRulesService {

    public static final int MAX_WEEKS = 52;
    static final LocalDate EARLIEST_TCG = LocalDate.of(2002, 1, 1);

    public static final int MAX_ROUND_ROBIN_CYCLES = 10;

    /**
     * What the commissioner sets. Omitted (null) fields are cleared, except {@code effectiveFromWeek}.
     *
     * @param regularWeeks     only for formats with an explicit week count (none yet); a round-robin's
     *                         weeks come from its cycles and the player count
     * @param roundRobinCycles how many times each pair of players meets
     */
    public record RulesUpdate(LocalDate startDate, Integer regularWeeks, LocalDate tcgCutoff, Long banListRevisionId,
                              CompetitionFormat competitionFormat, Integer roundRobinCycles, Integer effectiveFromWeek,
                              Integer freeAgencyPerWeek) {}

    public static final int MAX_PER_WEEK = 50;

    /**
     * @param version           the rules version shown (null if none has been saved yet)
     * @param started           the season has left setup: changes need {@code effectiveFromWeek}
     * @param currentWeek       0 = pre-season; null until the calendar is set
     * @param minEffectiveWeek  the earliest week a change can take effect from now
     */
    public record RulesView(Integer version, int effectiveFromWeek, boolean canManage, boolean started,
                            CalendarView calendar, FormatView format, CompetitionView competition,
                            SeasonRulesDocument.TransactionRules transactions, List<WeekView> weeks,
                            Integer currentWeek, int minEffectiveWeek, List<VersionView> history) {}

    /**
     * @param players          the season's current player count (what a round-robin's weeks follow)
     * @param rounds           round-robin: total rounds = weeks (one round a week); null otherwise
     * @param matchesPerPlayer round-robin: regular-season matches each player plays
     * @param byes             round-robin with an odd player count: one player sits out each round
     */
    public record CompetitionView(CompetitionFormat format, Integer roundRobinCycles, int players, Integer rounds,
                                  Integer matchesPerPlayer, boolean byes) {}

    /**
     * @param regularWeeks    the season's week count, whether set explicitly or derived from the format
     * @param weeksDerived    the count comes from the competition format (a round-robin), not a setting
     * @param weekStartLocked the week start was copied in when the season began (else it follows the league)
     */
    public record CalendarView(LocalDate startDate, Integer regularWeeks, boolean weeksDerived, String timeZone,
                               DayOfWeek weekStartsOn, String weekStartsAt, boolean weekStartLocked) {}

    public record FormatView(LocalDate tcgCutoff, BanListChoice banList) {}

    /** @param latestRevision the list's newest revision; if it's higher, a newer version of the list exists */
    public record BanListChoice(long revisionId, long listId, String name, int revision, boolean official,
                                int latestRevision, long latestRevisionId) {}

    public record WeekView(int number, OffsetDateTime start, OffsetDateTime end) {}

    public record VersionView(int version, int effectiveFromWeek, String by, Instant at) {}

    private final SeasonAccess access;
    private final SeasonRulesRepository rules;
    private final BanListRevisionRepository revisions;
    private final AppUserRepository users;
    private final ObjectMapper json;
    private final JdbcClient jdbc;
    private final SeasonPlayerRepository players;

    public SeasonRulesService(SeasonAccess access, SeasonRulesRepository rules, BanListRevisionRepository revisions,
                              AppUserRepository users, ObjectMapper json, JdbcClient jdbc, SeasonPlayerRepository players) {
        this.access = access;
        this.rules = rules;
        this.revisions = revisions;
        this.users = users;
        this.json = json;
        this.jdbc = jdbc;
        this.players = players;
    }

    @Transactional(readOnly = true)
    public RulesView get(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(publicLeagueId, publicSeasonId, caller);
        return view(ref.season(), ref.commissioner());
    }

    @Transactional
    public RulesView save(String publicLeagueId, String publicSeasonId, RulesUpdate update, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.commissioner(publicLeagueId, publicSeasonId, caller);
        Season season = ref.season();
        League league = season.getLeague();
        SeasonRules latest = rules.findFirstBySeasonIdOrderByVersionDesc(season.getId()).orElse(null);
        SeasonRulesDocument current = latest == null ? SeasonRulesDocument.EMPTY : parse(latest);

        CompetitionRules competition = competition(update);
        SeasonRulesDocument proposed = new SeasonRulesDocument(
                calendar(update, competition, current.calendar().weekStart()),
                new SeasonRulesDocument.FormatRules(validCutoff(update.tcgCutoff()), validBanList(update.banListRevisionId(), league)),
                competition, current.draft(),   // the draft section is edited on the Draft tab
                new SeasonRulesDocument.TransactionRules(perWeek(update.freeAgencyPerWeek(), "Free-agent claims")));

        int effectiveFrom = 0;
        if (season.getStatus() != SeasonStatus.SETUP) {
            if (!sameCalendar(proposed.calendar(), current.calendar(), competition) || !proposed.competition().equals(current.competition())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The calendar and competition format can't change once the season has started");
            }
            // Fixed from the start: keep it exactly as locked then (its week start, and a round-robin's weeks written out).
            proposed = proposed.withCalendar(current.calendar());
            int min = minEffectiveWeek(season, latest, current);
            if (update.effectiveFromWeek() == null) throw bad("Choose the week this change takes effect from");
            int max = Math.max(min, weekCount(season, current));
            if (update.effectiveFromWeek() < min || update.effectiveFromWeek() > max) {
                throw bad("This change can take effect from week " + min + " to week " + max + " (rules never change retroactively)");
            }
            effectiveFrom = update.effectiveFromWeek();
        }

        if (proposed.equals(current) && latest != null) return view(season, true);   // nothing changed
        AppUser by = users.getReferenceById(caller.userId());
        rules.save(new SeasonRules(season, latest == null ? 1 : latest.getVersion() + 1, effectiveFrom,
                SeasonRulesDocument.SCHEMA_VERSION, json.writeValueAsString(proposed), by));
        rules.flush();
        return view(season, true);
    }

    /**
     * Saves the draft section as a new rules version (setup only; the rest of the rules are kept).
     * The caller has checked the commissioner and locked the league.
     */
    @Transactional
    public void saveDraft(Season season, SeasonRulesDocument.DraftRules draft, CurrentUser caller) {
        if (season.getStatus() != SeasonStatus.SETUP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The draft has started; its settings are fixed");
        }
        append(season, latest(season).withDraft(draft), 0, caller.userId());
    }

    /**
     * When the draft starts the season: copies the league's week start into the calendar (so later
     * league changes don't move this season's weeks) and writes out derived weeks (a round-robin's)
     * as an explicit list, now that the player count is fixed.
     */
    @Transactional
    public void lockCalendarForStart(Season season, CurrentUser caller) {
        SeasonRulesDocument doc = latest(season);
        var league = season.getLeague();
        int weeks = weekCount(season, doc);
        var snapshot = new SeasonRulesDocument.WeekStartSnapshot(league.getTimeZone(), league.getWeekStartsOn(), league.getWeekStartsAt());
        var calendar = SeasonRulesDocument.CalendarRules.of(doc.calendar().startDate(), weeks, snapshot);
        append(season, doc.withCalendar(calendar), 0, caller.userId());
    }

    /**
     * Undoing a draft start (YGO-19): the calendar goes back to what it was before the start (the
     * week start follows the league again; derived weeks are derived again), as a new version.
     */
    @Transactional
    public void unlockCalendarAfterUnstart(Season season, SeasonRulesDocument.CalendarRules before, java.time.Instant draftStartedAt,
                                           CurrentUser caller) {
        if (before == null) {
            // Drafts started before YGO-19 didn't record it: take the last version saved before the start.
            before = rules.findFirstBySeasonIdAndCreatedAtBeforeOrderByVersionDesc(season.getId(), draftStartedAt)
                    .map(this::parse).orElse(SeasonRulesDocument.EMPTY).calendar();
        }
        append(season, latest(season).withCalendar(before), 0, caller.userId());
    }

    /** Writes a new version unless it would be identical to the latest. */
    private void append(Season season, SeasonRulesDocument doc, int effectiveFrom, long userId) {
        SeasonRules latestRow = rules.findFirstBySeasonIdOrderByVersionDesc(season.getId()).orElse(null);
        if (latestRow != null && parse(latestRow).equals(doc)) return;
        rules.save(new SeasonRules(season, latestRow == null ? 1 : latestRow.getVersion() + 1, effectiveFrom,
                SeasonRulesDocument.SCHEMA_VERSION, json.writeValueAsString(doc), users.getReferenceById(userId)));
        rules.flush();
    }

    /** The regular season's week count (explicit, or derived from the competition format); 0 if unknown. */
    public int weekCount(Season season) {
        return weekCount(season, latest(season));
    }

    /** The latest rules (what applies during setup), or the empty document if none were saved. */
    @Transactional(readOnly = true)
    public SeasonRulesDocument latest(Season season) {
        return rules.findFirstBySeasonIdOrderByVersionDesc(season.getId()).map(this::parse).orElse(SeasonRulesDocument.EMPTY);
    }

    /**
     * The rules in effect in a given week: the latest version that takes effect from that week or
     * earlier. Mid-season changes are saved ahead of time, so the latest version may not apply yet.
     */
    @Transactional(readOnly = true)
    public SeasonRulesDocument inEffect(Season season, int week) {
        return rules.findFirstBySeasonIdAndEffectiveFromWeekLessThanEqualOrderByVersionDesc(season.getId(), week)
                .map(this::parse).orElse(SeasonRulesDocument.EMPTY);
    }

    /** The current week of the season (0 = pre-season, or no calendar yet). */
    @Transactional(readOnly = true)
    public int currentWeek(Season season) {
        SeasonRulesDocument doc = latest(season);
        int weeks = weekCount(season, doc);
        if (doc.calendar().startDate() == null || weeks == 0) return 0;
        return new SeasonCalendar(boundary(doc, season.getLeague()), doc.calendar().startDate(), weeks).weekNumberAt(Instant.now());
    }

    // ---- Reading ------------------------------------------------------------------------------

    private RulesView view(Season season, boolean canManage) {
        SeasonRules latest = rules.findFirstBySeasonIdOrderByVersionDesc(season.getId()).orElse(null);
        SeasonRulesDocument doc = latest == null ? SeasonRulesDocument.EMPTY : parse(latest);
        League league = season.getLeague();
        WeekBoundary boundary = boundary(doc, league);

        SeasonRulesDocument.CalendarRules cal = doc.calendar();
        int weekCount = weekCount(season, doc);
        boolean derived = cal.weeks().isEmpty() && doc.competition().derivesWeeks();
        CalendarView calendar = new CalendarView(cal.startDate(), weekCount == 0 ? null : weekCount, derived,
                boundary.zone().getId(), boundary.startDay(), boundary.startTime().toString(), cal.weekStart() != null);

        int playerCount = (int) players.countBySeasonId(season.getId());
        CompetitionRules comp = doc.competition();
        boolean rr = comp.format() == CompetitionFormat.ROUND_ROBIN && comp.roundRobinCycles() != null;
        CompetitionView competition = new CompetitionView(comp.format(), comp.roundRobinCycles(), playerCount,
                rr ? RoundRobin.rounds(playerCount, comp.roundRobinCycles()) : null,
                rr ? RoundRobin.matchesPerPlayer(playerCount, comp.roundRobinCycles()) : null,
                rr && RoundRobin.hasByes(playerCount));

        List<WeekView> weeks = List.of();
        Integer currentWeek = null;
        if (cal.startDate() != null && weekCount > 0) {
            SeasonCalendar seasonCalendar = new SeasonCalendar(boundary, cal.startDate(), weekCount);
            weeks = seasonCalendar.weeks().stream()
                    .map(w -> new WeekView(w.number(), w.start().toOffsetDateTime(), w.end().toOffsetDateTime()))
                    .toList();
            currentWeek = seasonCalendar.weekNumberAt(Instant.now());
        }

        List<VersionView> history = jdbc.sql("""
                        select r.version, r.effective_from_week, u.display_name, r.created_at
                        from season_rules r left join app_user u on u.id = r.created_by
                        where r.season_id = :s order by r.version desc
                        """)
                .param("s", season.getId())
                .query((rs, n) -> new VersionView(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                .list();

        return new RulesView(latest == null ? null : latest.getVersion(), latest == null ? 0 : latest.getEffectiveFromWeek(),
                canManage, season.getStatus() != SeasonStatus.SETUP, calendar,
                new FormatView(doc.format().tcgCutoff(), banListChoice(doc.format().banListRevisionId())),
                competition, doc.transactions(), weeks, currentWeek, minEffectiveWeek(season, latest, doc), history);
    }

    /**
     * How many regular-season weeks there are: the calendar's explicit list if it has one, otherwise
     * what the format derives (a round-robin: its rounds for the current player count). 0 if unknown.
     */
    private int weekCount(Season season, SeasonRulesDocument doc) {
        if (!doc.calendar().weeks().isEmpty()) return doc.calendar().weeks().size();
        CompetitionRules comp = doc.competition();
        if (comp.derivesWeeks() && comp.roundRobinCycles() != null) {
            return RoundRobin.rounds((int) players.countBySeasonId(season.getId()), comp.roundRobinCycles());
        }
        return 0;
    }

    private BanListChoice banListChoice(Long revisionId) {
        if (revisionId == null) return null;
        return revisions.findById(revisionId).map(r -> {
            BanList list = r.getBanList();
            BanListRevision newest = revisions.findFirstByBanListIdOrderByRevisionDesc(list.getId()).orElse(r);
            return new BanListChoice(r.getId(), list.getId(), list.getName(), r.getRevision(), list.isOfficial(),
                    newest.getRevision(), newest.getId());
        }).orElse(null);
    }

    /** The season's own (copied) week start once it has begun; until then, the league's current one. */
    private static WeekBoundary boundary(SeasonRulesDocument doc, League league) {
        SeasonRulesDocument.WeekStartSnapshot snap = doc.calendar().weekStart();
        return snap == null ? league.weekBoundary() : new WeekBoundary(ZoneId.of(snap.timeZone()), snap.day(), snap.time());
    }

    private int minEffectiveWeek(Season season, SeasonRules latest, SeasonRulesDocument doc) {
        if (season.getStatus() == SeasonStatus.SETUP) return 0;
        int weeks = weekCount(season, doc);
        int current = doc.calendar().startDate() != null && weeks > 0
                ? new SeasonCalendar(boundary(doc, season.getLeague()), doc.calendar().startDate(), weeks).weekNumberAt(Instant.now())
                : 0;
        return Math.max(current, latest == null ? 0 : latest.getEffectiveFromWeek());
    }

    private SeasonRulesDocument parse(SeasonRules row) {
        return json.readValue(row.getRules(), SeasonRulesDocument.class);
    }

    // ---- Validation ---------------------------------------------------------------------------

    private static CompetitionRules competition(RulesUpdate update) {
        Integer cycles = update.roundRobinCycles();
        if (update.competitionFormat() == CompetitionFormat.ROUND_ROBIN) {
            if (cycles != null && (cycles < 1 || cycles > MAX_ROUND_ROBIN_CYCLES)) {
                throw bad("Each pair can meet 1 to " + MAX_ROUND_ROBIN_CYCLES + " times");
            }
        } else if (cycles != null) {
            throw bad("Cycles only apply to a round-robin");
        }
        return new CompetitionRules(update.competitionFormat(), update.competitionFormat() == null ? null : cycles);
    }

    private static SeasonRulesDocument.CalendarRules calendar(RulesUpdate update, CompetitionRules competition,
                                                              SeasonRulesDocument.WeekStartSnapshot weekStart) {
        Integer weeks = update.regularWeeks();
        if (weeks != null && competition.derivesWeeks()) {
            throw bad("A round-robin's weeks follow from how many times each pair meets; don't set a week count");
        }
        if (weeks != null && (weeks < 1 || weeks > MAX_WEEKS)) throw bad("A season has 1 to " + MAX_WEEKS + " weeks");
        LocalDate start = update.startDate();
        if (start != null && (start.getYear() < 2000 || start.getYear() > 2100)) throw bad("That start date doesn't look right");
        return weeks == null
                ? new SeasonRulesDocument.CalendarRules(start, List.of(), weekStart)
                : SeasonRulesDocument.CalendarRules.of(start, weeks, weekStart);
    }

    /**
     * Whether a save keeps the calendar as it is. A round-robin's weeks are derived, so the form sends
     * none, while a started season has them written out (locked at the draft's start): no weeks sent
     * for a derived format means "unchanged".
     */
    private static boolean sameCalendar(SeasonRulesDocument.CalendarRules proposed, SeasonRulesDocument.CalendarRules current,
                                        CompetitionRules competition) {
        boolean sameWeeks = proposed.weeks().equals(current.weeks()) || (proposed.weeks().isEmpty() && competition.derivesWeeks());
        return Objects.equals(proposed.startDate(), current.startDate()) && sameWeeks;
    }

    private static Integer perWeek(Integer value, String what) {
        if (value != null && (value < 0 || value > MAX_PER_WEEK)) throw bad(what + " per week: 0 to " + MAX_PER_WEEK + ", or blank for unlimited");
        return value;
    }

    private static LocalDate validCutoff(LocalDate cutoff) {
        if (cutoff == null) return null;
        if (cutoff.isBefore(EARLIEST_TCG)) throw bad("The TCG started in 2002, so the cutoff can't be earlier");
        if (cutoff.isAfter(LocalDate.now().plusYears(2))) throw bad("That cutoff is too far in the future");
        return cutoff;
    }

    /** The revision must exist and belong to a list the league can see (official, or its own). */
    private Long validBanList(Long revisionId, League league) {
        if (revisionId == null) return null;
        BanListRevision revision = revisions.findById(revisionId)
                .filter(r -> r.getBanList().isVisibleTo(league.getId()))
                .orElseThrow(() -> bad("Unknown ban list"));
        return revision.getId();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
