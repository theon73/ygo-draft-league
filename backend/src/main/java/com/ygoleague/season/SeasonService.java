package com.ygoleague.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueAccess;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueMemberRepository;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.league.LeagueRole;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Seasons within a league, and the roles in them: commissioners, players and seat order.
 *
 * <ul>
 *   <li>Roles are per season. A season's <b>commissioners</b> are the league owner (always,
 *       implicitly) plus the members listed in {@code season_commissioner}. Its <b>players</b> are
 *       its participants. Someone can be both. Any league member can follow any season.
 *   <li>The league owner creates seasons; a season's commissioners manage its commissioners,
 *       participants and seats.
 *   <li>Players can be any league member, placeholders included. Commissioners need an account.
 *   <li>Participants and seats can only change while the season is in setup. Team names can
 *       change any time, by a commissioner or the player themselves.
 *   <li>Seats are always 1..n. Randomizing records the draw permanently ({@code season_seat_draw}).
 *   <li>Changes lock the league row first, like membership changes, so they never interleave.
 * </ul>
 */
@Service
public class SeasonService {

    public static final int MAX_NAME_LENGTH = 60;
    public static final int MAX_TEAM_NAME_LENGTH = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record SeasonSummary(String id, String name, SeasonStatus status, Instant createdAt, long playerCount) {}

    /**
     * @param memberId the league member's public id
     * @param you      this participant is the caller
     */
    /** @param avatar the avatar shown this season; {@code ownAvatar}: it's this season's override, not the member's default */
    public record Participant(String memberId, String displayName, int seat, String teamName, boolean placeholder, boolean you,
                              Long avatar, boolean ownAvatar) {}

    /** A league member who could be given a role in the season (shown to commissioners only). */
    public record Candidate(String memberId, String displayName, boolean placeholder) {}

    /** @param owner the league owner, a commissioner of every season (can't be removed) */
    public record Commissioner(String memberId, String displayName, boolean owner, boolean you) {}

    /**
     * The latest recorded seat order.
     *
     * @param method  RANDOM (a draw) or REVERSE_STANDINGS (YGO-39; every earlier row is RANDOM)
     * @param basedOn for REVERSE_STANDINGS, the season whose standings were reversed
     */
    public record SeatDraw(String drawnBy, Instant drawnAt, List<String> order, String method, String basedOn) {}

    /** A season this one relates to (YGO-39), e.g. the one it was copied from. */
    public record SeasonRef(String id, String name, SeasonStatus status) {}

    /**
     * @param canManage  the caller commissions this season (show setup controls)
     * @param youPlay    the caller is one of its players
     * @param editable   participants and seats can still change (season in setup)
     * @param candidates every league member, for choosing players and commissioners (commissioners only)
     * @param currentWeek once the season is active: 0 before week 1, past {@code weekCount} after the regular season (YGO-31)
     * @param weekCount   the regular season's weeks, once active
     * @param playoffsStarted a commissioner has started the playoffs
     * @param setupStep   the setup wizard's step to resume at (YGO-32), or null when it isn't in use
     * @param unreadMessages the caller's unread trade messages and offers in this season (YGO-34)
     * @param copiedFrom  the season this one was copied from (YGO-39), or null; when it's complete, its
     *                    reverse standings can set the seats
     * @param next        the league's next season after this one (YGO-39), or null
     */
    public record SeasonDetail(String id, String name, SeasonStatus status, Instant createdAt, boolean canManage,
                               boolean youPlay, boolean editable, List<Commissioner> commissioners,
                               List<Participant> participants, List<Candidate> candidates, SeatDraw lastDraw,
                               Integer currentWeek, Integer weekCount, boolean playoffsStarted, Integer setupStep, int unreadMessages,
                               SeasonRef copiedFrom, SeasonRef next) {}

    private final LeagueAccess access;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final SeasonRepository seasons;
    private final SeasonPlayerRepository players;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;
    private final SeasonAccess seasonAccess;
    private final com.ygoleague.season.rules.SeasonRulesService rules;
    private final SeasonCopier copier;
    private final com.ygoleague.season.transaction.TradeInbox inbox;
    private final com.ygoleague.season.schedule.StandingsService standings;

    public SeasonService(LeagueAccess access, LeagueRepository leagues, LeagueMemberRepository members,
                         SeasonRepository seasons, SeasonPlayerRepository players, PublicIds publicIds, JdbcClient jdbc,
                         SeasonAccess seasonAccess, com.ygoleague.season.rules.SeasonRulesService rules, SeasonCopier copier,
                         com.ygoleague.season.transaction.TradeInbox inbox,
                         com.ygoleague.season.schedule.StandingsService standings) {
        this.inbox = inbox;
        this.standings = standings;
        this.rules = rules;
        this.copier = copier;
        this.access = access;
        this.leagues = leagues;
        this.members = members;
        this.seasons = seasons;
        this.players = players;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
        this.seasonAccess = seasonAccess;
    }

    // ---- Reading ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SeasonSummary> list(String publicLeagueId, CurrentUser caller) {
        League league = access.require(publicLeagueId, caller, LeagueRole.MEMBER).getLeague();
        return seasons.findByLeagueIdOrderByCreatedAtDesc(league.getId()).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public SeasonDetail detail(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        LeagueMember me = access.require(publicLeagueId, caller, LeagueRole.MEMBER);
        return detail(seasonOf(me.getLeague(), publicSeasonId), me);
    }

    // ---- League owner -------------------------------------------------------------------------

    /**
     * A new season (owner only). Optionally started from an earlier season of the league (see
     * {@link SeasonCopier}) and set up with the guided wizard ({@code guided}: it starts at step 1).
     * Every option is optional, so the old {"name"} request still works.
     */
    public record CreateRequest(String name, String copyFrom, Boolean copyPlayers, Boolean copyRules, Boolean copyDraft,
                                Boolean copyPool, Boolean guided) {}

    @Transactional
    public SeasonSummary create(String publicLeagueId, CreateRequest req, CurrentUser caller) {
        SeasonSummary created = create(publicLeagueId, req.name(), caller);
        Season season = seasons.findById(publicIds.decode(PublicIdType.SEASON, created.id()).orElseThrow()).orElseThrow();
        if (req.copyFrom() != null && !req.copyFrom().isBlank()) {
            Season from = publicIds.decode(PublicIdType.SEASON, req.copyFrom())
                    .flatMap(seasons::findById)
                    .filter(s -> s.getLeague().getId().equals(season.getLeague().getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season to copy from"));
            copier.copy(from, season, new SeasonCopier.Parts(Boolean.TRUE.equals(req.copyPlayers()), Boolean.TRUE.equals(req.copyRules()),
                    Boolean.TRUE.equals(req.copyDraft()), Boolean.TRUE.equals(req.copyPool())), caller.userId());
            season.setCopiedFromSeasonId(from.getId());
        }
        if (Boolean.TRUE.equals(req.guided())) season.setSetupStep(1);
        return summary(season);
    }

    /**
     * Where the setup wizard is (commissioners, during setup): the step to resume at, or null when
     * setup is finished (or the wizard is left for the regular tabs).
     */
    @Transactional
    public SeasonDetail setSetupStep(String publicLeagueId, String publicSeasonId, Integer step, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = seasonAccess.commissioner(publicLeagueId, publicSeasonId, caller);
        Season season = ref.season();
        if (season.getStatus() != SeasonStatus.SETUP) throw conflict("This season's setup is over");
        if (step != null && (step < 1 || step > 6)) throw bad("Setup has steps 1 to 6");
        season.setSetupStep(step);
        return detail(season, ref.me());
    }

    @Transactional
    public SeasonSummary create(String publicLeagueId, String name, CurrentUser caller) {
        LeagueMember me = access.require(publicLeagueId, caller, LeagueRole.OWNER);
        League league = leagues.lockById(me.getLeague().getId()).orElseThrow();
        String cleaned = clean(name, MAX_NAME_LENGTH, "Season name");
        if (cleaned == null) throw bad("Season name is required");
        if (seasons.existsByLeagueIdAndNameIgnoreCase(league.getId(), cleaned)) {
            throw conflict("This league already has a season called " + cleaned);
        }
        return summary(seasons.save(new Season(league, cleaned)));
    }

    // ---- Season commissioners -----------------------------------------------------------------

    /**
     * Replaces the season's commissioners (besides the owner, who always is one and needn't be
     * listed). Commissioners need an account. Allowed at any stage of the season.
     */
    @Transactional
    public SeasonDetail setCommissioners(String publicLeagueId, String publicSeasonId, List<String> memberIds, CurrentUser caller) {
        Managing m = lockAsCommissioner(publicLeagueId, publicSeasonId, caller);
        List<LeagueMember> chosen = membersOf(m.me().getLeague(), memberIds).stream()
                .filter(c -> c.getRole() != LeagueRole.OWNER)
                .toList();
        for (LeagueMember c : chosen) {
            if (c.isPlaceholder()) throw bad(c.getDisplayName() + " doesn't have an account yet, so can't be a commissioner");
        }
        jdbc.sql("delete from season_commissioner where season_id = :s").param("s", m.season().getId()).update();
        for (LeagueMember c : chosen) {
            jdbc.sql("insert into season_commissioner (season_id, league_id, league_member_id) values (:s, :l, :m)")
                    .param("s", m.season().getId()).param("l", m.me().getLeague().getId()).param("m", c.getId()).update();
        }
        return detail(m.season(), m.me());
    }

    /**
     * Sets one member's roles in the season in a single step (the Setup page's role dropdown).
     * The league owner is always a commissioner; placeholders can play but not commission. Whether
     * someone plays can only change during setup; commissioners can change any time. A new player
     * takes the last seat; a leaving player's seat closes up.
     */
    @Transactional
    public SeasonDetail setMemberRole(String publicLeagueId, String publicSeasonId, String memberId,
                                      boolean commissioner, boolean player, CurrentUser caller) {
        Managing managing = lockAsCommissioner(publicLeagueId, publicSeasonId, caller);
        Season season = managing.season();
        LeagueMember target = membersOf(managing.me().getLeague(), List.of(memberId)).getFirst();
        boolean isOwner = target.getRole() == LeagueRole.OWNER;
        if (isOwner && !commissioner) throw bad("The league owner is always a commissioner");
        if (commissioner && target.isPlaceholder()) throw bad(target.getDisplayName() + " doesn't have an account yet, so can't be a commissioner");

        List<SeasonPlayer> seated = players.findBySeasonIdOrderBySeat(season.getId());
        boolean playsNow = seated.stream().anyMatch(p -> p.getMember().getId().equals(target.getId()));
        if (player != playsNow) {
            requireEditable(season);
            List<Long> order = new ArrayList<>(seated.stream().map(p -> p.getMember().getId()).toList());
            if (player) {
                players.save(new SeasonPlayer(season, target));
                order.add(target.getId());
            } else {
                players.deleteAll(seated.stream().filter(p -> p.getMember().getId().equals(target.getId())).toList());
                players.flush();
                order.remove(target.getId());
            }
            assignSeats(season, order);
        }
        if (!isOwner) {
            jdbc.sql("delete from season_commissioner where season_id = :s and league_member_id = :m")
                    .param("s", season.getId()).param("m", target.getId()).update();
            if (commissioner) {
                jdbc.sql("insert into season_commissioner (season_id, league_id, league_member_id) values (:s, :l, :m)")
                        .param("s", season.getId()).param("l", season.getLeague().getId()).param("m", target.getId()).update();
            }
        }
        return detail(season, managing.me());
    }

    /** Replaces the participant list. People who stay keep their relative seat order; newcomers go last. */
    @Transactional
    public SeasonDetail setParticipants(String publicLeagueId, String publicSeasonId, List<String> memberIds, CurrentUser caller) {
        Managing managing = lockAsCommissioner(publicLeagueId, publicSeasonId, caller);
        LeagueMember me = managing.me();
        Season season = requireEditable(managing.season());
        List<LeagueMember> chosen = membersOf(me.getLeague(), memberIds);

        List<SeasonPlayer> current = players.findBySeasonIdOrderBySeat(season.getId());
        Set<Long> chosenIds = chosen.stream().map(LeagueMember::getId).collect(Collectors.toSet());
        List<SeasonPlayer> leaving = current.stream().filter(p -> !chosenIds.contains(p.getMember().getId())).toList();
        players.deleteAll(leaving);
        players.flush();

        Set<Long> already = current.stream().map(p -> p.getMember().getId()).collect(Collectors.toSet());
        for (LeagueMember m : chosen) {
            if (!already.contains(m.getId())) players.save(new SeasonPlayer(season, m));
        }
        List<Long> order = SeatOrder.afterParticipantChange(
                current.stream().map(p -> p.getMember().getId()).toList(),
                chosen.stream().map(LeagueMember::getId).toList());
        assignSeats(season, order);
        return detail(season, me);
    }

    @Transactional
    public SeasonDetail setSeatOrder(String publicLeagueId, String publicSeasonId, List<String> memberIdsInOrder, CurrentUser caller) {
        Managing m = lockAsCommissioner(publicLeagueId, publicSeasonId, caller);
        LeagueMember me = m.me();
        Season season = requireEditable(m.season());
        List<Long> order = membersOf(me.getLeague(), memberIdsInOrder).stream().map(LeagueMember::getId).toList();
        Set<Long> participants = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .map(p -> p.getMember().getId()).collect(Collectors.toSet());
        try {
            SeatOrder.requirePermutation(order, participants);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        assignSeats(season, order);
        return detail(season, me);
    }

    /** Shuffles the seats and records the draw for everyone to see. */
    @Transactional
    public SeasonDetail randomizeSeats(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        Managing m = lockAsCommissioner(publicLeagueId, publicSeasonId, caller);
        LeagueMember me = m.me();
        Season season = requireEditable(m.season());
        List<Long> participants = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .map(p -> p.getMember().getId()).toList();
        if (participants.size() < 2) throw bad("Add at least two participants before drawing seats");
        List<Long> order = SeatOrder.shuffled(participants, RANDOM);
        assignSeats(season, order);
        jdbc.sql("insert into season_seat_draw (season_id, drawn_by, seat_order) values (:s, :u, :o)")
                .param("s", season.getId()).param("u", caller.userId()).param("o", order.toArray(Long[]::new))
                .update();
        return detail(season, me);
    }

    /**
     * Last place picks first (YGO-39): the seats in the reverse of the final placement of the season
     * this one was copied from, which must be complete (see {@link SeatOrder#reverseOfPlacement} for
     * newcomers). Recorded like a draw, so everyone sees how the order was set.
     */
    @Transactional
    public SeasonDetail reverseStandingsSeats(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        Managing m = lockAsCommissioner(publicLeagueId, publicSeasonId, caller);
        Season season = requireEditable(m.season());
        Season from = season.getCopiedFromSeasonId() == null ? null : seasons.findById(season.getCopiedFromSeasonId()).orElse(null);
        if (from == null || from.getStatus() != SeasonStatus.COMPLETE) {
            throw bad("This season wasn't copied from a completed season, so there are no final standings to reverse");
        }
        List<Long> participants = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .map(p -> p.getMember().getId()).toList();
        if (participants.size() < 2) throw bad("Add at least two participants before setting seats");
        List<Long> order = SeatOrder.reverseOfPlacement(participants, standings.finalPlacement(from));
        assignSeats(season, order);
        jdbc.sql("""
                        insert into season_seat_draw (season_id, drawn_by, seat_order, method, based_on_season_id)
                        values (:s, :u, :o, 'REVERSE_STANDINGS', :f)""")
                .param("s", season.getId()).param("u", caller.userId()).param("o", order.toArray(Long[]::new))
                .param("f", from.getId()).update();
        return detail(season, m.me());
    }

    /** Commissioners can name any team; players can name their own. Blank clears it. */
    @Transactional
    public SeasonDetail setTeamName(String publicLeagueId, String publicSeasonId, String memberId, String teamName, CurrentUser caller) {
        LeagueMember me = access.require(publicLeagueId, caller, LeagueRole.MEMBER);
        leagues.lockById(me.getLeague().getId());
        Season season = seasonOf(me.getLeague(), publicSeasonId);
        LeagueMember target = membersOf(me.getLeague(), List.of(memberId)).getFirst();
        if (!commissions(season, me) && !target.getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only name your own team");
        }
        SeasonPlayer player = players.findBySeasonIdOrderBySeat(season.getId()).stream()
                .filter(p -> p.getMember().getId().equals(target.getId()))
                .findFirst()
                .orElseThrow(() -> bad(target.getDisplayName() + " isn't in this season"));
        player.setTeamName(clean(teamName, MAX_TEAM_NAME_LENGTH, "Team name"));
        return detail(season, me);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private SeasonDetail detail(Season season, LeagueMember me) {
        boolean canManage = commissions(season, me);
        List<SeasonPlayer> seated = players.findBySeasonIdOrderBySeat(season.getId());
        List<Participant> participants = seated.stream()
                .map(p -> new Participant(memberId(p.getMember()), p.getMember().getDisplayName(), p.getSeat(),
                        p.getTeamName(), p.getMember().isPlaceholder(), p.getMember().getId().equals(me.getId()),
                        p.avatar(), p.getAvatarPasscode() != null))
                .toList();
        boolean youPlay = participants.stream().anyMatch(Participant::you);

        List<LeagueMember> all = members.findByLeagueIdOrderByDisplayName(season.getLeague().getId());
        Set<Long> listed = commissionerIds(season);
        List<Commissioner> commissioners = all.stream()
                .filter(m -> m.getRole() == LeagueRole.OWNER || listed.contains(m.getId()))
                .sorted(java.util.Comparator.comparing((LeagueMember m) -> m.getRole() != LeagueRole.OWNER))
                .map(m -> new Commissioner(memberId(m), m.getDisplayName(), m.getRole() == LeagueRole.OWNER, m.getId().equals(me.getId())))
                .toList();

        List<Candidate> candidates = !canManage ? List.of() : all.stream()
                .map(m -> new Candidate(memberId(m), m.getDisplayName(), m.isPlaceholder()))
                .toList();
        boolean active = season.getStatus() == SeasonStatus.ACTIVE;
        return new SeasonDetail(publicIds.encode(PublicIdType.SEASON, season.getId()), season.getName(), season.getStatus(),
                season.getCreatedAt(), canManage, youPlay, season.getStatus() == SeasonStatus.SETUP, commissioners,
                participants, candidates, lastDraw(season),
                active ? rules.currentWeek(season) : null, active ? rules.weeks(season).size() : null,
                jdbc.sql("select exists (select 1 from stage where season_id = :s and kind = 'PLAYOFFS' and started_at is not null)")
                        .param("s", season.getId()).query(Boolean.class).single(),
                season.getStatus() == SeasonStatus.SETUP ? season.getSetupStep() : null,
                me.getUser() == null ? 0 : inbox.inSeason(me.getUser().getId(), season.getId()),
                season.getCopiedFromSeasonId() == null ? null : seasons.findById(season.getCopiedFromSeasonId()).map(this::ref).orElse(null),
                nextAfter(season));
    }

    private SeasonRef ref(Season s) {
        return new SeasonRef(publicIds.encode(PublicIdType.SEASON, s.getId()), s.getName(), s.getStatus());
    }

    /** The league's oldest season created after this one, if any. */
    private SeasonRef nextAfter(Season season) {
        Season next = null;
        for (Season s : seasons.findByLeagueIdOrderByCreatedAtDesc(season.getLeague().getId())) {
            if (s.getId().equals(season.getId())) break;
            next = s;
        }
        return next == null ? null : ref(next);
    }

    private boolean commissions(Season season, LeagueMember member) {
        return seasonAccess.commissions(season, member);
    }

    private Set<Long> commissionerIds(Season season) {
        return seasonAccess.commissionerIds(season);
    }

    private SeatDraw lastDraw(Season season) {
        return jdbc.sql("""
                        select u.display_name, d.drawn_at, d.seat_order, d.method, b.name
                        from season_seat_draw d join app_user u on u.id = d.drawn_by
                        left join season b on b.id = d.based_on_season_id
                        where d.season_id = :s order by d.drawn_at desc, d.id desc limit 1
                        """)
                .param("s", season.getId())
                .query((rs, n) -> {
                    Long[] order = (Long[]) rs.getArray("seat_order").getArray();
                    return new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), List.of(order), rs.getString(4), rs.getString(5)};
                })
                .optional()
                .map(row -> {
                    @SuppressWarnings("unchecked") List<Long> ids = (List<Long>) row[2];
                    Map<Long, String> names = new HashMap<>();
                    members.findAllById(ids).forEach(m -> names.put(m.getId(), m.getDisplayName()));
                    // A member who has since left the league shows as "(removed member)".
                    return new SeatDraw((String) row[0], (Instant) row[1],
                            ids.stream().map(id -> names.getOrDefault(id, "(removed member)")).toList(), (String) row[3], (String) row[4]);
                })
                .orElse(null);
    }

    /** Sets seats 1..n in the given order. Clears them first: (season, seat) is unique and checked per row. */
    private void assignSeats(Season season, List<Long> memberOrder) {
        List<SeasonPlayer> all = players.findBySeasonIdOrderBySeat(season.getId());
        all.forEach(p -> p.setSeat(null));
        players.flush();
        Map<Long, SeasonPlayer> byMember = all.stream().collect(Collectors.toMap(p -> p.getMember().getId(), Function.identity()));
        for (int i = 0; i < memberOrder.size(); i++) byMember.get(memberOrder.get(i)).setSeat(i + 1);
        players.flush();
    }

    /** The caller's membership and the season they're managing. */
    private record Managing(LeagueMember me, Season season) {}

    private Managing lockAsCommissioner(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = seasonAccess.commissioner(publicLeagueId, publicSeasonId, caller);
        return new Managing(ref.me(), ref.season());
    }

    private Season seasonOf(League league, String publicSeasonId) {
        return seasonAccess.seasonOf(league, publicSeasonId);
    }

    private static Season requireEditable(Season season) {
        if (season.getStatus() != SeasonStatus.SETUP) throw conflict("This season has started; participants and seats are fixed");
        return season;
    }

    /** Resolves public member ids within the league, keeping order and rejecting duplicates or strangers. */
    private List<LeagueMember> membersOf(League league, List<String> publicMemberIds) {
        if (publicMemberIds == null) throw bad("Member ids are required");
        if (new HashSet<>(publicMemberIds).size() != publicMemberIds.size()) throw bad("A member is listed twice");
        List<LeagueMember> result = new ArrayList<>();
        for (String id : new LinkedHashSet<>(publicMemberIds)) {
            result.add(publicIds.decode(PublicIdType.LEAGUE_MEMBER, id)
                    .flatMap(members::findById)
                    .filter(m -> m.getLeague().getId().equals(league.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such member")));
        }
        return result;
    }

    private SeasonSummary summary(Season s) {
        return new SeasonSummary(publicIds.encode(PublicIdType.SEASON, s.getId()), s.getName(), s.getStatus(), s.getCreatedAt(),
                players.countBySeasonId(s.getId()));
    }

    private String memberId(LeagueMember m) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, m.getId());
    }

    /** Trims and collapses whitespace; null for blank. Rejects over-long names and control characters. */
    static String clean(String value, int max, String what) {
        String cleaned = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > max) throw bad(what + " can be at most " + max + " characters");
        if (cleaned.chars().anyMatch(Character::isISOControl)) throw bad(what + " can't contain control characters");
        return cleaned;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
