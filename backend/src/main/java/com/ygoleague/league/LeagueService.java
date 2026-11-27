package com.ygoleague.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import com.ygoleague.calendar.WeekBoundary;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creating leagues and reading them. Authorization goes through {@link LeagueAccess}. */
@Service
public class LeagueService {

    public static final int MAX_NAME_LENGTH = 60;

    /** A league in "My leagues". {@code id} is the public id. */
    public record LeagueSummary(String id, String name, LeagueRole myRole, long memberCount) {}

    /**
     * A league's page. Member user ids are never exposed.
     *
     * @param timeZone        IANA zone; week boundaries, deadlines and draft times use it
     * @param seasons         newest first
     * @param currentSeasonId the season the league page opens on: the newest one that isn't
     *                        complete, else the newest; null if there are none
     * @param weekStartsOn    day each league week starts, in {@code timeZone}
     * @param weekStartsAt    local time it starts, "HH:mm"
     * @param currentWeek     the week containing now, as offsets in the league's zone (for display)
     */
    public record LeagueDetail(String id, String name, LeagueRole myRole, Instant createdAt, String timeZone,
                               DayOfWeek weekStartsOn, String weekStartsAt, WeekView currentWeek,
                               List<MemberView> members, List<SeasonLink> seasons, String currentSeasonId) {}

    public record WeekView(OffsetDateTime start, OffsetDateTime end) {}

    /** League settings a commissioner can change; null fields are left as they are. */
    public record SettingsUpdate(String timeZone, DayOfWeek weekStartsOn, String weekStartsAt) {}

    public record SeasonLink(String id, String name, SeasonStatus status) {}

    /**
     * @param id          the member's public id (for commissioner actions on them)
     * @param placeholder a seat the commissioner added for someone without an account yet
     * @param you         this member is the caller
     */
    /**
     * @param avatar    the avatar shown in this league (a passcode): the league's own for them, else their profile's
     * @param ownAvatar it's this league's own (so it can be removed), not their profile's
     */
    public record MemberView(String id, String displayName, LeagueRole role, boolean placeholder, boolean you, Long avatar,
                             boolean ownAvatar) {}

    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final AppUserRepository users;
    private final LeagueAccess access;
    private final PublicIds publicIds;
    private final SeasonRepository seasons;

    public LeagueService(LeagueRepository leagues, LeagueMemberRepository members, AppUserRepository users,
                         LeagueAccess access, PublicIds publicIds, SeasonRepository seasons) {
        this.leagues = leagues;
        this.members = members;
        this.users = users;
        this.access = access;
        this.publicIds = publicIds;
        this.seasons = seasons;
    }

    /**
     * Creates a league with the caller as its owner.
     *
     * @param timeZone the creator's zone (the browser sends it); UTC if absent or unknown
     */
    @Transactional
    public LeagueSummary create(String name, String timeZone, CurrentUser caller) {
        String cleaned = cleanName(name);
        // Lenient here: an unknown browser zone shouldn't stop anyone creating a league (it's editable later).
        String zone = timeZone != null && ZoneId.getAvailableZoneIds().contains(timeZone.strip()) ? timeZone.strip() : "UTC";
        AppUser creator = users.findById(caller.userId()).orElseThrow();
        League league = new League(cleaned, creator);
        league.setTimeZone(zone);
        league = leagues.save(league);
        members.save(LeagueMember.forUser(league, creator, creator.getDisplayName(), LeagueRole.OWNER));
        return new LeagueSummary(publicId(league), league.getName(), LeagueRole.OWNER, 1);
    }

    @Transactional(readOnly = true)
    public List<LeagueSummary> myLeagues(CurrentUser caller) {
        List<LeagueMember> mine = members.findMembershipsOfUser(caller.userId());
        if (mine.isEmpty()) return List.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : members.countByLeague(mine.stream().map(m -> m.getLeague().getId()).toList())) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return mine.stream()
                .map(m -> new LeagueSummary(publicId(m.getLeague()), m.getLeague().getName(), m.getRole(),
                        counts.getOrDefault(m.getLeague().getId(), 0L)))
                .toList();
    }

    /** Any member may see the league. */
    @Transactional(readOnly = true)
    public LeagueDetail detail(String publicLeagueId, CurrentUser caller) {
        LeagueMember me = access.require(publicLeagueId, caller, LeagueRole.MEMBER);
        League league = me.getLeague();
        List<MemberView> roster = members.findByLeagueIdOrderByDisplayName(league.getId()).stream()
                .map(m -> memberView(m, m.getId().equals(me.getId())))
                .toList();
        List<Season> all = seasons.findByLeagueIdOrderByCreatedAtDesc(league.getId());
        List<SeasonLink> links = all.stream()
                .map(s -> new SeasonLink(publicIds.encode(PublicIdType.SEASON, s.getId()), s.getName(), s.getStatus()))
                .toList();
        String current = all.stream().filter(s -> s.getStatus() != SeasonStatus.COMPLETE).findFirst()
                .or(() -> all.stream().findFirst())
                .map(s -> publicIds.encode(PublicIdType.SEASON, s.getId()))
                .orElse(null);
        WeekBoundary.Week week = league.weekBoundary().weekContaining(Instant.now());
        return new LeagueDetail(publicId(league), league.getName(), me.getRole(), league.getCreatedAt(), league.getTimeZone(),
                league.getWeekStartsOn(), league.getWeekStartsAt().toString(),
                new WeekView(week.start().toOffsetDateTime(), week.end().toOffsetDateTime()),
                roster, links, current);
    }

    /** The owner only. Fields left null keep their current value. */
    @Transactional
    public LeagueDetail updateSettings(String publicLeagueId, SettingsUpdate update, CurrentUser caller) {
        League league = access.require(publicLeagueId, caller, LeagueRole.OWNER).getLeague();
        League locked = leagues.lockById(league.getId()).orElseThrow();
        if (update.timeZone() != null) locked.setTimeZone(validZone(update.timeZone()));
        if (update.weekStartsOn() != null || update.weekStartsAt() != null) {
            DayOfWeek day = update.weekStartsOn() != null ? update.weekStartsOn() : locked.getWeekStartsOn();
            LocalTime time = update.weekStartsAt() != null ? validTime(update.weekStartsAt()) : locked.getWeekStartsAt();
            locked.setWeekStart(day, time);
        }
        return detail(publicLeagueId, caller);
    }

    /** "HH:mm", 24-hour, whole minutes. */
    static LocalTime validTime(String value) {
        try {
            LocalTime time = LocalTime.parse(value.strip());
            if (time.getSecond() != 0 || time.getNano() != 0) throw new DateTimeParseException("whole minutes only", value, 0);
            return time;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Week start time must be HH:mm, e.g. 00:00 or 20:30");
        }
    }

    /** A region-based IANA zone ("Europe/London"), not a fixed offset, so daylight saving is handled. */
    static String validZone(String zone) {
        String id = zone == null ? "" : zone.strip();
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + id);
        }
        return id;
    }

    public MemberView memberView(LeagueMember m, boolean you) {
        return new MemberView(publicIds.encode(PublicIdType.LEAGUE_MEMBER, m.getId()), m.getDisplayName(), m.getRole(),
                m.isPlaceholder(), you, m.avatar(), m.getAvatarPasscode() != null);
    }

    /** Trims and collapses whitespace; rejects blank, over-long and control-character names. */
    static String cleanName(String name) {
        String cleaned = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) throw new IllegalArgumentException("League name is required");
        if (cleaned.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("League name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (cleaned.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("League name can't contain control characters");
        }
        return cleaned;
    }

    private String publicId(League league) {
        return publicIds.encode(PublicIdType.LEAGUE, league.getId());
    }
}
