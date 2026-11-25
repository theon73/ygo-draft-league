package com.ygoleague.catalog;

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

/** Superseded wording of a card, in effect until {@code validUntil} (exclusive). See {@link CardText}. */
@Entity
@Table(name = "card_text_version")
public class CardTextVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id")
    private Card card;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(nullable = false)
    private String description;

    /** Where the wording came from: a URL or a short note. */
    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CardTextVersion() {}

    public CardTextVersion(Card card, LocalDate validUntil, String description, String source) {
        this.card = card;
        this.validUntil = validUntil;
        this.description = description;
        this.source = source;
    }

    public Long getId() { return id; }
    public Card getCard() { return card; }
    public LocalDate getValidUntil() { return validUntil; }
    public String getDescription() { return description; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
