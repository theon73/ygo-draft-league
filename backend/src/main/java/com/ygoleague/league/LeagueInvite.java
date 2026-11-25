package com.ygoleague.league;

import com.ygoleague.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A code a commissioner hands out to join a league. Two kinds:
 * <ul>
 *   <li><b>Open invite</b> ({@code role} set): whoever redeems it becomes a new member with that role.
 *   <li><b>Claim invite</b> ({@code member} set): the redeemer takes over a specific placeholder
 *       member. Always single-use.
 * </ul>
 */
@Entity
@Table(name = "league_invite")
public class LeagueInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "league_id")
    private League league;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    private LeagueRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private LeagueMember member;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LeagueInvite() {}

    private LeagueInvite(League league, String code, LeagueRole role, LeagueMember member,
                         Integer maxUses, Instant expiresAt, AppUser createdBy) {
        this.league = league;
        this.code = code;
        this.role = role;
        this.member = member;
        this.maxUses = maxUses;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    /** @param maxUses null for unlimited */
    public static LeagueInvite open(League league, String code, LeagueRole role, Integer maxUses,
                                    Instant expiresAt, AppUser createdBy) {
        if (role == null) throw new IllegalArgumentException("role is required");
        return new LeagueInvite(league, code, role, null, maxUses, expiresAt, createdBy);
    }

    public static LeagueInvite claim(LeagueMember placeholder, String code, Instant expiresAt, AppUser createdBy) {
        if (!placeholder.isPlaceholder()) throw new IllegalArgumentException("Member is not a placeholder");
        return new LeagueInvite(placeholder.getLeague(), code, null, placeholder, 1, expiresAt, createdBy);
    }

    public boolean isClaimInvite() {
        return member != null;
    }

    public boolean isRedeemable(Instant now) {
        return revokedAt == null
                && (expiresAt == null || now.isBefore(expiresAt))
                && (maxUses == null || useCount < maxUses);
    }

    /** Records one use. Callers create or claim the membership in the same transaction. */
    public void markUsed(Instant now) {
        if (!isRedeemable(now)) throw new IllegalStateException("Invite is not redeemable");
        useCount++;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
    }

    public Long getId() { return id; }
    public League getLeague() { return league; }
    public String getCode() { return code; }
    public LeagueRole getRole() { return role; }
    public LeagueMember getMember() { return member; }
    public Integer getMaxUses() { return maxUses; }
    public int getUseCount() { return useCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
