package com.ygoleague.league;

import com.ygoleague.user.AppUser;
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

/**
 * A league: the tenant everything else hangs off. Visible to its members only. URLs identify it
 * by an opaque public id derived from {@code id} (see {@code common.PublicIds}).
 */
@Entity
@Table(name = "league")
public class League {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected League() {}

    public League(String name, AppUser createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
}
