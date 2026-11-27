package com.ygoleague.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A site account. Global: not tied to any league. Logins live in {@link UserIdentity}. */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // The profile avatar (YGO-30) is written with SQL by AvatarService; the entity only reads it.
    @Column(name = "avatar_passcode", insertable = false, updatable = false)
    private Long avatarPasscode;

    protected AppUser() {}

    public AppUser(String displayName) {
        this.displayName = displayName;
    }

    public Long getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public Instant getCreatedAt() { return createdAt; }
    /** The profile avatar's artwork (a passcode), or null. */
    public Long getAvatarPasscode() { return avatarPasscode; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
