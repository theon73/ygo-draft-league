package com.ygoleague.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Audit row for one catalog sync attempt. */
@Entity
@Table(name = "catalog_sync")
public class CatalogSync {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status = SyncStatus.RUNNING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SyncTrigger trigger;

    @Column(name = "source_version")
    private String sourceVersion;

    @Column(name = "sets_upserted")
    private Integer setsUpserted;

    @Column(name = "cards_added")
    private Integer cardsAdded;

    @Column(name = "cards_updated")
    private Integer cardsUpdated;

    @Column(name = "cards_ignored")
    private Integer cardsIgnored;

    private String error;

    protected CatalogSync() {}

    public CatalogSync(SyncTrigger trigger) {
        this.trigger = trigger;
    }

    void skipped(String version) {
        this.sourceVersion = version;
        finish(SyncStatus.SKIPPED);
    }

    void succeeded(String version, int sets, int added, int updated, int ignored) {
        this.sourceVersion = version;
        this.setsUpserted = sets;
        this.cardsAdded = added;
        this.cardsUpdated = updated;
        this.cardsIgnored = ignored;
        finish(SyncStatus.SUCCEEDED);
    }

    void failed(String version, String error) {
        this.sourceVersion = version;
        this.error = error;
        finish(SyncStatus.FAILED);
    }

    private void finish(SyncStatus status) {
        this.status = status;
        this.finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public SyncStatus getStatus() { return status; }
    public SyncTrigger getTrigger() { return trigger; }
    public String getSourceVersion() { return sourceVersion; }
    public Integer getSetsUpserted() { return setsUpserted; }
    public Integer getCardsAdded() { return cardsAdded; }
    public Integer getCardsUpdated() { return cardsUpdated; }
    public Integer getCardsIgnored() { return cardsIgnored; }
    public String getError() { return error; }

    @Override
    public String toString() {
        return "CatalogSync[" + status + ", version=" + sourceVersion + ", sets=" + setsUpserted
                + ", added=" + cardsAdded + ", updated=" + cardsUpdated + ", ignored=" + cardsIgnored
                + (error == null ? "" : ", error=" + error) + "]";
    }
}
