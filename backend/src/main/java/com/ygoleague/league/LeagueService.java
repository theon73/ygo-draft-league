package com.ygoleague.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating leagues and reading them. Authorization goes through {@link LeagueAccess}. */
@Service
public class LeagueService {

    public static final int MAX_NAME_LENGTH = 60;

    /** A league in "My leagues". {@code id} is the public id. */
    public record LeagueSummary(String id, String name, LeagueRole myRole, long memberCount) {}

    /** A league's overview page. Member user ids are never exposed. */
    public record LeagueDetail(String id, String name, LeagueRole myRole, Instant createdAt, List<MemberView> members) {}

    /**
     * @param id          the member's public id (for commissioner actions on them)
     * @param placeholder a seat the commissioner added for someone without an account yet
     * @param you         this member is the caller
     */
    public record MemberView(String id, String displayName, LeagueRole role, boolean placeholder, boolean you) {}

    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final AppUserRepository users;
    private final LeagueAccess access;
    private final PublicIds publicIds;

    public LeagueService(LeagueRepository leagues, LeagueMemberRepository members, AppUserRepository users,
                         LeagueAccess access, PublicIds publicIds) {
        this.leagues = leagues;
        this.members = members;
        this.users = users;
        this.access = access;
        this.publicIds = publicIds;
    }

    /** Creates a league with the caller as its first commissioner. */
    @Transactional
    public LeagueSummary create(String name, CurrentUser caller) {
        String cleaned = cleanName(name);
        AppUser creator = users.findById(caller.userId()).orElseThrow();
        League league = leagues.save(new League(cleaned, creator));
        members.save(LeagueMember.forUser(league, creator, creator.getDisplayName(), LeagueRole.COMMISSIONER));
        return new LeagueSummary(publicId(league), league.getName(), LeagueRole.COMMISSIONER, 1);
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

    /** Any member, viewers included, may see the overview. */
    @Transactional(readOnly = true)
    public LeagueDetail detail(String publicLeagueId, CurrentUser caller) {
        LeagueMember me = access.require(publicLeagueId, caller, LeagueRole.VIEWER);
        League league = me.getLeague();
        List<MemberView> roster = members.findByLeagueIdOrderByDisplayName(league.getId()).stream()
                .map(m -> memberView(m, m.getId().equals(me.getId())))
                .toList();
        return new LeagueDetail(publicId(league), league.getName(), me.getRole(), league.getCreatedAt(), roster);
    }

    public MemberView memberView(LeagueMember m, boolean you) {
        return new MemberView(publicIds.encode(PublicIdType.LEAGUE_MEMBER, m.getId()), m.getDisplayName(), m.getRole(),
                m.isPlaceholder(), you);
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
