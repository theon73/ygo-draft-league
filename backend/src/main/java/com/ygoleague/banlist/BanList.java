package com.ygoleague.banlist;

import com.ygoleague.league.League;
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
import java.time.LocalDate;

/**
 * A ban list's identity. Its content lives in {@link BanListRevision}s; seasons reference a
 * revision, never the list, so edits are never retroactive.
 *
 * <p>Official lists ({@code league == null}) come from repo files and are keyed by file path.
 * Custom lists belong to one league.
 */
@Entity
@Table(name = "ban_list")
public class BanList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "league_id", updatable = false)
    private League league;

    @Column(name = "official_key", updatable = false)
    private String officialKey;

    @Column(nullable = false)
    private String name;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BanList() {}

    public static BanList official(String key, String name, LocalDate effectiveDate) {
        BanList list = new BanList();
        list.officialKey = key;
        list.name = name;
        list.effectiveDate = effectiveDate;
        return list;
    }

    public static BanList custom(League league, String name, AppUser createdBy) {
        BanList list = new BanList();
        list.league = league;
        list.name = name;
        list.createdBy = createdBy;
        return list;
    }

    public boolean isOfficial() {
        return officialKey != null;
    }

    /** Whether a league may use (reference or copy) this list: official lists, or its own. */
    public boolean isVisibleTo(Long leagueId) {
        return isOfficial() || league.getId().equals(leagueId);
    }

    public Long getId() { return id; }
    public League getLeague() { return league; }
    public String getOfficialKey() { return officialKey; }
    public String getName() { return name; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    /** Official list metadata follows its file (e.g. a corrected name). */
    void updateOfficialMetadata(String name, LocalDate effectiveDate) {
        this.name = name;
        this.effectiveDate = effectiveDate;
    }

    public void rename(String name) {
        if (isOfficial()) throw new IllegalStateException("Official lists are named by their file");
        this.name = name;
    }
}
