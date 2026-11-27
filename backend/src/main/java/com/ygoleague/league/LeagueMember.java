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
 * A seat in a league with a role. {@code user} is null for a placeholder member: someone the
 * commissioner added by name who hasn't signed up yet. A placeholder is claimed through an invite.
 */
@Entity
@Table(name = "league_member")
public class LeagueMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "league_id")
    private League league;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** Name shown in this league. Unique within the league. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeagueRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // The avatar (YGO-30) is written with SQL by AvatarService; the entity only reads it.
    @Column(name = "avatar_passcode", insertable = false, updatable = false)
    private Long avatarPasscode;

    protected LeagueMember() {}

    private LeagueMember(League league, AppUser user, String displayName, LeagueRole role) {
        this.league = league;
        this.user = user;
        this.displayName = displayName;
        this.role = role;
    }

    public static LeagueMember forUser(League league, AppUser user, String displayName, LeagueRole role) {
        if (user == null) throw new IllegalArgumentException("user is required; use placeholder()");
        return new LeagueMember(league, user, displayName, role);
    }

    public static LeagueMember placeholder(League league, String displayName, LeagueRole role) {
        return new LeagueMember(league, null, displayName, role);
    }

    public boolean isPlaceholder() {
        return user == null;
    }

    /** Links a placeholder member to the account that claimed it. */
    public void claim(AppUser claimant) {
        if (!isPlaceholder()) throw new IllegalStateException("Member is already linked to an account");
        this.user = claimant;
    }

    public Long getId() { return id; }
    public League getLeague() { return league; }
    public AppUser getUser() { return user; }
    public String getDisplayName() { return displayName; }
    public LeagueRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    /** This league's avatar for them (a passcode), or null. */
    public Long getAvatarPasscode() { return avatarPasscode; }
    /** The avatar shown in this league: its own, else their profile's (placeholders have none). */
    public Long avatar() { return avatarPasscode != null ? avatarPasscode : user == null ? null : user.getAvatarPasscode(); }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setRole(LeagueRole role) { this.role = role; }
}
