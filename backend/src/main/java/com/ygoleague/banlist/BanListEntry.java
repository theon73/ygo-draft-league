package com.ygoleague.banlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A card's status in one revision. Cards absent from a revision are unlimited. */
@Entity
@Table(name = "ban_list_entry")
@IdClass(BanListEntry.Key.class)
public class BanListEntry {

    public record Key(Long revisionId, Long cardId) implements Serializable {
        public Key() { this(null, null); }
    }

    @Id
    @Column(name = "revision_id")
    private Long revisionId;

    @Id
    @Column(name = "card_id")
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BanStatus status;

    protected BanListEntry() {}

    BanListEntry(Long revisionId, Long cardId, BanStatus status) {
        this.revisionId = revisionId;
        this.cardId = cardId;
        this.status = status;
    }

    public Long getRevisionId() { return revisionId; }
    public Long getCardId() { return cardId; }
    public BanStatus getStatus() { return status; }
}
