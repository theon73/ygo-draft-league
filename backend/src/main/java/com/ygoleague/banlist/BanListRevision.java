package com.ygoleague.banlist;

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

/** One immutable version of a ban list's contents. Entries are in {@link BanListEntry}. */
@Entity
@Table(name = "ban_list_revision")
public class BanListRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ban_list_id", updatable = false)
    private BanList banList;

    @Column(nullable = false, updatable = false)
    private int revision;

    /** For a copied list's first revision: the revision it was copied from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "based_on_revision_id", updatable = false)
    private BanListRevision basedOn;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    @Column(updatable = false)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BanListRevision() {}

    BanListRevision(BanList banList, int revision, BanListRevision basedOn, String contentHash,
                    String note, AppUser createdBy) {
        this.banList = banList;
        this.revision = revision;
        this.basedOn = basedOn;
        this.contentHash = contentHash;
        this.note = note;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public BanList getBanList() { return banList; }
    public int getRevision() { return revision; }
    public BanListRevision getBasedOn() { return basedOn; }
    public String getContentHash() { return contentHash; }
    public String getNote() { return note; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
