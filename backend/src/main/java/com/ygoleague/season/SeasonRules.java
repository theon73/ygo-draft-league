package com.ygoleague.season;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable version of a season's rules. A change is a new row with the next version number.
 *
 * <p>The rules in effect for week W are the highest version whose {@code effectiveFromWeek <= W}
 * (week 0 = pre-season: setup and draft). To keep that unambiguous, a new version must not take
 * effect before the latest version's week, so changes are never retroactive.
 *
 * <p>{@code rules} is stored as raw JSON here. The typed model it parses into arrives with the
 * rules config work (Phase 2); {@code schemaVersion} identifies the JSON's shape.
 */
@Entity
@Table(name = "season_rules")
public class SeasonRules {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", updatable = false)
    private Season season;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "effective_from_week", nullable = false, updatable = false)
    private int effectiveFromWeek;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String rules;

    /** Null when the version came from system defaults. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SeasonRules() {}

    public SeasonRules(Season season, int version, int effectiveFromWeek, int schemaVersion,
                       String rules, AppUser createdBy) {
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (effectiveFromWeek < 0) throw new IllegalArgumentException("effectiveFromWeek must be >= 0");
        this.season = season;
        this.version = version;
        this.effectiveFromWeek = effectiveFromWeek;
        this.schemaVersion = schemaVersion;
        this.rules = rules;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Season getSeason() { return season; }
    public int getVersion() { return version; }
    public int getEffectiveFromWeek() { return effectiveFromWeek; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getRules() { return rules; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
