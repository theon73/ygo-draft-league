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
    public record Participant(String memberId, String displayName, int seat, String teamName, boolean placeholder, boolean you) {}

    /** A league member who could be given a role in the season (shown to commissioners only). */
    public record Candidate(String memberId, String displayName, boolean placeholder) {}

    /** @param owner the league owner, a commissioner of every season (can't be removed) */
    public record Commissioner(String memberId, String displayName, boolean owner, boolean you) {}

    public record SeatDraw(String drawnBy, Instant drawnAt, List<String> order) {}

    /**
     * @param canManage  the caller commissions this season (show setup controls)
     * @param youPlay    the caller is one of its players
     * @param editable   participants and seats can still change (season in setup)
     * @param candidates every league member, for choosing players and commissioners (commissioners only)
     */
    public record SeasonDetail(String id, String name, SeasonStatus status, Instant createdAt, boolean canManage,
                               boolean youPlay, boolean editable, List<Commissioner> commissioners,
                               List<Participant> participants, List<Candidate> candidates, SeatDraw lastDraw) {}

    private final LeagueAccess access;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final SeasonRepository seasons;
    private final SeasonPlayerRepository players;
    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public SeasonService(LeagueAccess access, LeagueRepository leagues, LeagueMemberRepository members,
                         SeasonRepository seasons, SeasonPlayerRepository players, PublicIds publicIds, JdbcClient jdbc) {
        this.access = access;
        this.leagues = leagues;
        this.members = members;
        this.seasons = seasons;
        this.players = players;
        this.publicIds = publicIds;
        this.jdbc = jdbc;
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
                        p.getTeamName(), p.getMember().isPlaceholder(), p.getMember().getId().equals(me.getId())))
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
        return new SeasonDetail(publicIds.encode(PublicIdType.SEASON, season.getId()), season.getName(), season.getStatus(),
                season.getCreatedAt(), canManage, youPlay, season.getStatus() == SeasonStatus.SETUP, commissioners,
                participants, candidates, lastDraw(season));
    }

    /** Whether the member commissions the season: the league owner always does. */
    private boolean commissions(Season season, LeagueMember member) {
        return member.getRole() == LeagueRole.OWNER || commissionerIds(season).contains(member.getId());
    }

    private Set<Long> commissionerIds(Season season) {
        return Set.copyOf(jdbc.sql("select league_member_id from season_commissioner where season_id = :s")
                .param("s", season.getId()).query(Long.class).list());
    }

    private SeatDraw lastDraw(Season season) {
        return jdbc.sql("""
                        select u.display_name, d.drawn_at, d.seat_order
                        from season_seat_draw d join app_user u on u.id = d.drawn_by
                        where d.season_id = :s order by d.drawn_at desc, d.id desc limit 1
                        """)
                .param("s", season.getId())
                .query((rs, n) -> {
                    Long[] order = (Long[]) rs.getArray("seat_order").getArray();
                    return new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), List.of(order)};
                })
                .optional()
                .map(row -> {
                    @SuppressWarnings("unchecked") List<Long> ids = (List<Long>) row[2];
                    Map<Long, String> names = new HashMap<>();
                    members.findAllById(ids).forEach(m -> names.put(m.getId(), m.getDisplayName()));
                    // A member who has since left the league shows as "(removed member)".
                    return new SeatDraw((String) row[0], (Instant) row[1],
                            ids.stream().map(id -> names.getOrDefault(id, "(removed member)")).toList());
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

    /** Members who don't commission the season get 403 (they're in the league, so it isn't hidden). */
    private Managing lockAsCommissioner(String publicLeagueId, String publicSeasonId, CurrentUser caller) {
        LeagueMember me = access.require(publicLeagueId, caller, LeagueRole.MEMBER);
        leagues.lockById(me.getLeague().getId());
        Season season = seasonOf(me.getLeague(), publicSeasonId);
        if (!commissions(season, me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only this season's commissioners can do this");
        }
        return new Managing(me, season);
    }

    private Season seasonOf(League league, String publicSeasonId) {
        return publicIds.decode(PublicIdType.SEASON, publicSeasonId)
                .flatMap(seasons::findById)
                .filter(s -> s.getLeague().getId().equals(league.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such season"));
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
