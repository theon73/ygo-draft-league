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
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who is in a league and how they get in: invites (open and placeholder-claim), placeholder
 * members, role changes and removals.
 *
 * <p>Rules:
 * <ul>
 *   <li>Managing members and invites needs the commissioner role ({@link LeagueAccess}).
 *   <li>A league always keeps at least one commissioner, and placeholders can't be commissioners
 *       (someone has to be able to sign in and run the league).
 *   <li>Open invites grant player or viewer. Making someone a commissioner is a separate promotion.
 *       Viewer invites are how the read-only viewer allowlist is managed.
 *   <li>Every change locks the league row first ({@link LeagueRepository#lockById}), so changes in
 *       one league never interleave.
 * </ul>
 */
@Service
public class LeagueMembership {

    public static final int MAX_DISPLAY_NAME_LENGTH = 40;
    public static final int MAX_INVITE_DAYS = 90;
    public static final int MAX_INVITE_USES = 1000;
    private static final Set<LeagueRole> OPEN_INVITE_ROLES = Set.of(LeagueRole.PLAYER, LeagueRole.VIEWER);

    public enum InviteKind { OPEN, CLAIM }

    public enum InviteStatus { VALID, EXPIRED, REVOKED, USED_UP }

    /** An invite as its league's commissioners see it. {@code code} goes in the link. */
    public record InviteView(String code, InviteKind kind, LeagueRole role, String seatName,
                             Integer maxUses, int useCount, Instant expiresAt, Instant createdAt) {}

    /** What someone holding an invite link sees before accepting it. */
    public record InvitePreview(String leagueName, InviteKind kind, LeagueRole role, String seatName,
                                InviteStatus status, boolean alreadyMember) {}

    /**
     * @param role          for an open invite: PLAYER or VIEWER
     * @param placeholderId for a claim invite: the placeholder member's public id (role must be null)
     * @param expiresInDays 1 to {@value #MAX_INVITE_DAYS}, or null for no expiry
     * @param maxUses       open invites only: 1 to {@value #MAX_INVITE_USES}, or null for unlimited
     */
    public record CreateInvite(LeagueRole role, String placeholderId, Integer expiresInDays, Integer maxUses) {}

    private final LeagueAccess access;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final LeagueInviteRepository invites;
    private final SeasonPlayerRepository seasonPlayers;
    private final AppUserRepository users;
    private final PublicIds publicIds;
    private final LeagueService leagueService;

    public LeagueMembership(LeagueAccess access, LeagueRepository leagues, LeagueMemberRepository members,
                            LeagueInviteRepository invites, SeasonPlayerRepository seasonPlayers,
                            AppUserRepository users, PublicIds publicIds, LeagueService leagueService) {
        this.access = access;
        this.leagues = leagues;
        this.members = members;
        this.invites = invites;
        this.seasonPlayers = seasonPlayers;
        this.users = users;
        this.publicIds = publicIds;
        this.leagueService = leagueService;
    }

    // ---- Commissioner: invites ----------------------------------------------------------------

    /** Invites that can still be used, newest first. */
    @Transactional(readOnly = true)
    public List<InviteView> invites(String publicLeagueId, CurrentUser caller) {
        League league = access.require(publicLeagueId, caller, LeagueRole.COMMISSIONER).getLeague();
        Instant now = Instant.now();
        return invites.findByLeagueIdAndRevokedAtIsNullOrderByCreatedAtDesc(league.getId()).stream()
                .filter(i -> status(i, now) == InviteStatus.VALID)
                .map(LeagueMembership::view)
                .toList();
    }

    @Transactional
    public InviteView createInvite(String publicLeagueId, CreateInvite req, CurrentUser caller) {
        League league = lockAsCommissioner(publicLeagueId, caller);
        Integer days = req.expiresInDays();
        if (days != null && (days < 1 || days > MAX_INVITE_DAYS)) {
            throw bad("Expiry must be between 1 and " + MAX_INVITE_DAYS + " days, or none");
        }
        Instant expiresAt = days == null ? null : Instant.now().plus(Duration.ofDays(days));
        AppUser creator = users.getReferenceById(caller.userId());

        LeagueInvite invite;
        if (req.placeholderId() != null) {
            if (req.role() != null || req.maxUses() != null) {
                throw bad("A claim invite takes the placeholder's role and is single-use");
            }
            LeagueMember seat = memberOf(league, req.placeholderId());
            if (!seat.isPlaceholder()) throw conflict(seat.getDisplayName() + " already has an account");
            invite = LeagueInvite.claim(seat, InviteCodes.next(), expiresAt, creator);
        } else {
            if (req.role() == null || !OPEN_INVITE_ROLES.contains(req.role())) {
                throw bad("An invite grants the player or viewer role");
            }
            Integer uses = req.maxUses();
            if (uses != null && (uses < 1 || uses > MAX_INVITE_USES)) {
                throw bad("Max uses must be between 1 and " + MAX_INVITE_USES + ", or unlimited");
            }
            invite = LeagueInvite.open(league, InviteCodes.next(), req.role(), uses, expiresAt, creator);
        }
        return view(invites.save(invite));
    }

    @Transactional
    public void revokeInvite(String publicLeagueId, String code, CurrentUser caller) {
        League league = lockAsCommissioner(publicLeagueId, caller);
        LeagueInvite invite = invites.findByCode(code)
                .filter(i -> i.getLeague().getId().equals(league.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invite"));
        invite.revoke(Instant.now());
    }

    // ---- Commissioner: members ----------------------------------------------------------------

    /** Adds a named player seat for someone without an account; they claim it later with a claim invite. */
    @Transactional
    public MemberView addPlaceholder(String publicLeagueId, String displayName, CurrentUser caller) {
        League league = lockAsCommissioner(publicLeagueId, caller);
        String name = cleanDisplayName(displayName);
        if (members.existsByLeagueIdAndDisplayNameIgnoreCase(league.getId(), name)) {
            throw conflict("Someone in this league is already called " + name);
        }
        LeagueMember seat = members.save(LeagueMember.placeholder(league, name, LeagueRole.PLAYER));
        return leagueService.memberView(seat, false);
    }

    @Transactional
    public MemberView changeRole(String publicLeagueId, String memberId, LeagueRole role, CurrentUser caller) {
        if (role == null) throw bad("Role is required");
        League league = lockAsCommissioner(publicLeagueId, caller);
        LeagueMember target = memberOf(league, memberId);
        if (target.isPlaceholder() && role == LeagueRole.COMMISSIONER) {
            throw bad("A placeholder can't be a commissioner until someone claims the seat");
        }
        if (target.getRole() == LeagueRole.COMMISSIONER && role != LeagueRole.COMMISSIONER) {
            requireAnotherCommissioner(league);
        }
        target.setRole(role);
        return leagueService.memberView(target, isCaller(target, caller));
    }

    /** Removes a member, e.g. a viewer leaving the allowlist. Not possible once they've played in a season. */
    @Transactional
    public void removeMember(String publicLeagueId, String memberId, CurrentUser caller) {
        League league = lockAsCommissioner(publicLeagueId, caller);
        LeagueMember target = memberOf(league, memberId);
        if (target.getRole() == LeagueRole.COMMISSIONER) requireAnotherCommissioner(league);
        if (seasonPlayers.existsByMemberId(target.getId())) {
            throw conflict(target.getDisplayName() + " has played in a season, so they stay in the league's history");
        }
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
        return new InvitePreview(invite.getLeague().getName(), kind(invite), invite.getRole(), seatName(invite),
                status(invite, Instant.now()), alreadyMember);
    }

    /** Joins the league (or claims the seat) and returns the league's public id. */
    @Transactional
    public String accept(String code, CurrentUser caller) {
        long leagueId = invites.findByCode(code).orElseThrow(InviteNotFound::new).getLeague().getId();
        // Lock order is always league, then invite (as for commissioner changes), so no deadlocks.
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
            members.save(LeagueMember.forUser(league, user, uniqueDisplayName(leagueId, user.getDisplayName()), invite.getRole()));
        }
        invite.markUsed(now);
        return publicIds.encode(PublicIdType.LEAGUE, leagueId);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private League lockAsCommissioner(String publicLeagueId, CurrentUser caller) {
        long leagueId = access.require(publicLeagueId, caller, LeagueRole.COMMISSIONER).getLeague().getId();
        return leagues.lockById(leagueId).orElseThrow();
    }

    private LeagueMember memberOf(League league, String memberId) {
        return publicIds.decode(PublicIdType.LEAGUE_MEMBER, memberId)
                .flatMap(members::findById)
                .filter(m -> m.getLeague().getId().equals(league.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such member"));
    }

    private void requireAnotherCommissioner(League league) {
        if (members.countByLeagueIdAndRole(league.getId(), LeagueRole.COMMISSIONER) <= 1) {
            throw conflict("A league needs at least one commissioner. Promote someone else first.");
        }
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

    private static boolean isCaller(LeagueMember m, CurrentUser caller) {
        return m.getUser() != null && m.getUser().getId() == caller.userId();
    }

    static InviteStatus status(LeagueInvite invite, Instant now) {
        if (invite.getRevokedAt() != null) return InviteStatus.REVOKED;
        if (invite.getExpiresAt() != null && !now.isBefore(invite.getExpiresAt())) return InviteStatus.EXPIRED;
        if (invite.getMaxUses() != null && invite.getUseCount() >= invite.getMaxUses()) return InviteStatus.USED_UP;
        if (invite.isClaimInvite() && !invite.getMember().isPlaceholder()) return InviteStatus.USED_UP;
        return InviteStatus.VALID;
    }

    private static InviteView view(LeagueInvite i) {
        return new InviteView(i.getCode(), kind(i), i.getRole(), seatName(i), i.getMaxUses(), i.getUseCount(),
                i.getExpiresAt(), i.getCreatedAt());
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
