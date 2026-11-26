package com.ygoleague.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.league.LeagueService.MemberView;
import com.ygoleague.season.SeasonPlayerRepository;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is in a league (the group of people) and how they get in: invites (open and
 * placeholder-claim), placeholder members and removals. Roles in play (commissioner, player) are
 * per season; see {@code season.SeasonService}.
 *
 * <p>Rules:
 * <ul>
 *   <li>Managing the group (invites, placeholders, removals) is for the league owner.
 *   <li>The owner can't be removed. Handing a league to someone else is a later addition.
 *   <li>Someone who is part of a season (as a player) can't be removed: take them out of the
 *       season first, and once a season has started they stay in its history.
 *   <li>Every change locks the league row first ({@link LeagueRepository#lockById}), so changes in
 *       one league never interleave.
 * </ul>
 */
@Service
public class LeagueMembership {

    public static final int MAX_DISPLAY_NAME_LENGTH = 40;
    public static final int MAX_INVITE_DAYS = 90;
    public static final int MAX_INVITE_USES = 1000;

    public enum InviteKind { OPEN, CLAIM }

    public enum InviteStatus { VALID, EXPIRED, REVOKED, USED_UP }

    /** An invite as the owner sees it. {@code code} goes in the link. */
    public record InviteView(String code, InviteKind kind, String seatName, Integer maxUses, int useCount,
                             Instant expiresAt, Instant createdAt) {}

    /** What someone holding an invite link sees before accepting it. */
    public record InvitePreview(String leagueName, InviteKind kind, String seatName, InviteStatus status, boolean alreadyMember) {}

    /**
     * @param placeholderId for a claim invite: the placeholder member's public id; null for an open
     *                      invite (anyone with the link joins the league)
     * @param expiresInDays 1 to {@value #MAX_INVITE_DAYS}, or null for no expiry
     * @param maxUses       open invites only: 1 to {@value #MAX_INVITE_USES}, or null for unlimited
     */
    public record CreateInvite(String placeholderId, Integer expiresInDays, Integer maxUses) {}

    private final LeagueAccess access;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final LeagueInviteRepository invites;
    private final SeasonPlayerRepository seasonPlayers;
    private final AppUserRepository users;
    private final PublicIds publicIds;
    private final LeagueService leagueService;
    private final JdbcClient jdbc;

    public LeagueMembership(LeagueAccess access, LeagueRepository leagues, LeagueMemberRepository members,
                            LeagueInviteRepository invites, SeasonPlayerRepository seasonPlayers,
                            AppUserRepository users, PublicIds publicIds, LeagueService leagueService, JdbcClient jdbc) {
        this.access = access;
        this.leagues = leagues;
        this.members = members;
        this.invites = invites;
        this.seasonPlayers = seasonPlayers;
        this.users = users;
        this.publicIds = publicIds;
        this.leagueService = leagueService;
        this.jdbc = jdbc;
    }

    // ---- Owner: invites -----------------------------------------------------------------------

    /** Invites that can still be used, newest first. */
    @Transactional(readOnly = true)
    public List<InviteView> invites(String publicLeagueId, CurrentUser caller) {
        League league = access.require(publicLeagueId, caller, LeagueRole.OWNER).getLeague();
        Instant now = Instant.now();
        return invites.findByLeagueIdAndRevokedAtIsNullOrderByCreatedAtDesc(league.getId()).stream()
                .filter(i -> status(i, now) == InviteStatus.VALID)
                .map(LeagueMembership::view)
                .toList();
    }

    @Transactional
    public InviteView createInvite(String publicLeagueId, CreateInvite req, CurrentUser caller) {
        League league = lockAsOwner(publicLeagueId, caller);
        Integer days = req.expiresInDays();
        if (days != null && (days < 1 || days > MAX_INVITE_DAYS)) {
            throw bad("Expiry must be between 1 and " + MAX_INVITE_DAYS + " days, or none");
        }
        Instant expiresAt = days == null ? null : Instant.now().plus(Duration.ofDays(days));
        AppUser creator = users.getReferenceById(caller.userId());

        LeagueInvite invite;
        if (req.placeholderId() != null) {
            if (req.maxUses() != null) throw bad("A claim invite is single-use");
            LeagueMember seat = memberOf(league, req.placeholderId());
            if (!seat.isPlaceholder()) throw conflict(seat.getDisplayName() + " already has an account");
            invite = LeagueInvite.claim(seat, InviteCodes.next(), expiresAt, creator);
        } else {
            Integer uses = req.maxUses();
            if (uses != null && (uses < 1 || uses > MAX_INVITE_USES)) {
                throw bad("Max uses must be between 1 and " + MAX_INVITE_USES + ", or unlimited");
            }
            invite = LeagueInvite.open(league, InviteCodes.next(), LeagueRole.MEMBER, uses, expiresAt, creator);
        }
        return view(invites.save(invite));
    }

    @Transactional
    public void revokeInvite(String publicLeagueId, String code, CurrentUser caller) {
        League league = lockAsOwner(publicLeagueId, caller);
        LeagueInvite invite = invites.findByCode(code)
                .filter(i -> i.getLeague().getId().equals(league.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invite"));
        invite.revoke(Instant.now());
    }

    // ---- Owner: members -----------------------------------------------------------------------

    /** Adds a named seat for someone without an account; they claim it later with a claim invite. */
    @Transactional
    public MemberView addPlaceholder(String publicLeagueId, String displayName, CurrentUser caller) {
        League league = lockAsOwner(publicLeagueId, caller);
        String name = cleanDisplayName(displayName);
        if (members.existsByLeagueIdAndDisplayNameIgnoreCase(league.getId(), name)) {
            throw conflict("Someone in this league is already called " + name);
        }
        LeagueMember seat = members.save(LeagueMember.placeholder(league, name, LeagueRole.MEMBER));
        return leagueService.memberView(seat, false);
    }

    @Transactional
    public void removeMember(String publicLeagueId, String memberId, CurrentUser caller) {
        League league = lockAsOwner(publicLeagueId, caller);
        LeagueMember target = memberOf(league, memberId);
        if (target.getRole() == LeagueRole.OWNER) throw conflict("The league owner can't be removed");
        if (seasonPlayers.existsByMemberId(target.getId())) {
            throw conflict(target.getDisplayName() + " is part of a season. Remove them from its participants first; "
                    + "once a season has started, they stay in the league's history.");
        }
        // Commissioning a season isn't history the way playing is; it just ends.
        jdbc.sql("delete from season_commissioner where league_member_id = :m").param("m", target.getId()).update();
        invites.deleteByMemberId(target.getId());
        members.delete(target);
    }

    // ---- Anyone holding an invite link --------------------------------------------------------

    /** @param caller null when signed out (the link can be previewed before signing in) */
    @Transactional(readOnly = true)
    public InvitePreview preview(String code, CurrentUser caller) {
        LeagueInvite invite = invites.findByCode(code).orElseThrow(InviteNotFound::new);
        boolean alreadyMember = caller != null
                && members.findByLeagueIdAndUserId(invite.getLeague().getId(), caller.userId()).isPresent();
        return new InvitePreview(invite.getLeague().getName(), kind(invite), seatName(invite),
                status(invite, Instant.now()), alreadyMember);
    }

    /** Joins the league (or claims the seat) and returns the league's public id. */
    @Transactional
    public String accept(String code, CurrentUser caller) {
        long leagueId = invites.findByCode(code).orElseThrow(InviteNotFound::new).getLeague().getId();
        // Lock order is always league, then invite (as for owner changes), so no deadlocks.
        League league = leagues.lockById(leagueId).orElseThrow(InviteNotFound::new);
        LeagueInvite invite = invites.lockByCode(code).orElseThrow(InviteNotFound::new);

        Instant now = Instant.now();
        InviteStatus status = status(invite, now);
        if (status != InviteStatus.VALID) {
            throw new ResponseStatusException(HttpStatus.GONE, switch (status) {
                case EXPIRED -> "This invite has expired";
                case REVOKED -> "This invite was revoked";
                default -> "This invite has already been used";
            });
        }
        if (members.findByLeagueIdAndUserId(leagueId, caller.userId()).isPresent()) {
            throw conflict("You're already in this league");
        }

        AppUser user = users.findById(caller.userId()).orElseThrow();
        if (invite.isClaimInvite()) {
            invite.getMember().claim(user);
        } else {
            members.save(LeagueMember.forUser(league, user, uniqueDisplayName(leagueId, user.getDisplayName()), LeagueRole.MEMBER));
        }
        invite.markUsed(now);
        return publicIds.encode(PublicIdType.LEAGUE, leagueId);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private League lockAsOwner(String publicLeagueId, CurrentUser caller) {
        long leagueId = access.require(publicLeagueId, caller, LeagueRole.OWNER).getLeague().getId();
        return leagues.lockById(leagueId).orElseThrow();
    }

    private LeagueMember memberOf(League league, String memberId) {
        return publicIds.decode(PublicIdType.LEAGUE_MEMBER, memberId)
                .flatMap(members::findById)
                .filter(m -> m.getLeague().getId().equals(league.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such member"));
    }

    /** The user's own name, or "Name 2", "Name 3", ... if someone in the league already has it. */
    private String uniqueDisplayName(long leagueId, String userName) {
        // Leave room for a " 99" suffix within the length limit.
        String base = userName.length() > MAX_DISPLAY_NAME_LENGTH - 3
                ? userName.substring(0, MAX_DISPLAY_NAME_LENGTH - 3).strip() : userName;
        String name = base;
        for (int n = 2; members.existsByLeagueIdAndDisplayNameIgnoreCase(leagueId, name); n++) {
            name = base + " " + n;
        }
        return name;
    }

    static InviteStatus status(LeagueInvite invite, Instant now) {
        if (invite.getRevokedAt() != null) return InviteStatus.REVOKED;
        if (invite.getExpiresAt() != null && !now.isBefore(invite.getExpiresAt())) return InviteStatus.EXPIRED;
        if (invite.getMaxUses() != null && invite.getUseCount() >= invite.getMaxUses()) return InviteStatus.USED_UP;
        if (invite.isClaimInvite() && !invite.getMember().isPlaceholder()) return InviteStatus.USED_UP;
        return InviteStatus.VALID;
    }

    private static InviteView view(LeagueInvite i) {
        return new InviteView(i.getCode(), kind(i), seatName(i), i.getMaxUses(), i.getUseCount(), i.getExpiresAt(), i.getCreatedAt());
    }

    private static InviteKind kind(LeagueInvite i) {
        return i.isClaimInvite() ? InviteKind.CLAIM : InviteKind.OPEN;
    }

    private static String seatName(LeagueInvite i) {
        return i.isClaimInvite() ? i.getMember().getDisplayName() : null;
    }

    static String cleanDisplayName(String name) {
        String cleaned = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) throw bad("A name is required");
        if (cleaned.length() > MAX_DISPLAY_NAME_LENGTH) throw bad("Names can be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        if (cleaned.chars().anyMatch(Character::isISOControl)) throw bad("Names can't contain control characters");
        return cleaned;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /** Unknown invite codes are 404, same as a code that never existed. */
    static class InviteNotFound extends ResponseStatusException {
        InviteNotFound() {
            super(HttpStatus.NOT_FOUND, "This invite link isn't valid");
        }
    }
}
