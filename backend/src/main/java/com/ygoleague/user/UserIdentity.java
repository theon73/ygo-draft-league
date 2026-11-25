package com.ygoleague.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** One external login (provider + the provider's stable subject id) linked to an {@link AppUser}. */
@Entity
@Table(name = "user_identity")
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String subject;

    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected UserIdentity() {}

    public UserIdentity(AppUser user, String provider, String subject, String email) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public String getProvider() { return provider; }
    public String getSubject() { return subject; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}
