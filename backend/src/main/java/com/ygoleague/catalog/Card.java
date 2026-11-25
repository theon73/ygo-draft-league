package com.ygoleague.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A card in the global catalog. Read-only through JPA: the catalog sync writes these rows in
 * bulk via {@link CatalogWriter}. Leagues never modify cards; per-season flags live on pool cards.
 */
@Entity
@Immutable
@Table(name = "card")
public class Card {

    @Id
    private Long id;

    /** Konami's database id: the stable identity. Null for a few tokens and unreleased cards. */
    @Column(name = "konami_id")
    private Integer konamiId;

    /**
     * Every artwork's 8-digit passcode, ascending. Each is also that artwork's YGOPRODeck image id.
     * The source doesn't say reliably which artwork is "original", so none is singled out.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Long> passcodes;

    private String name;

    /** e.g. "Effect Monster", "Spell Card", "XYZ Monster" (YGOPRODeck's wording). */
    @Column(name = "card_type")
    private String cardType;

    /** e.g. "effect", "spell", "xyz", "effect_pendulum". Handy for broad filters. */
    @Column(name = "frame_type")
    private String frameType;

    /** Monster type ("Fiend"), or Spell/Trap subtype ("Quick-Play", "Counter"). */
    private String race;

    private String attribute;
    private Integer level;
    private Integer rank;

    @Column(name = "link_rating")
    private Integer linkRating;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "link_markers")
    private List<String> linkMarkers;

    @Column(name = "pendulum_scale")
    private Integer pendulumScale;

    /** Null for "?" and for non-monsters. */
    private Integer atk;

    /** Null for "?", Link monsters and non-monsters. */
    private Integer def;

    private String archetype;

    /** Current text. For older wording see {@link CardText}. */
    private String description;

    @Column(name = "tcg_date")
    private LocalDate tcgDate;

    @Column(name = "ocg_date")
    private LocalDate ocgDate;

    @JdbcTypeCode(SqlTypes.JSON)
    private String raw;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Card() {}

    public Long getId() { return id; }
    public Integer getKonamiId() { return konamiId; }
    public List<Long> getPasscodes() { return passcodes; }
    public String getName() { return name; }
    public String getCardType() { return cardType; }
    public String getFrameType() { return frameType; }
    public String getRace() { return race; }
    public String getAttribute() { return attribute; }
    public Integer getLevel() { return level; }
    public Integer getRank() { return rank; }
    public Integer getLinkRating() { return linkRating; }
    public List<String> getLinkMarkers() { return linkMarkers; }
    public Integer getPendulumScale() { return pendulumScale; }
    public Integer getAtk() { return atk; }
    public Integer getDef() { return def; }
    public String getArchetype() { return archetype; }
    public String getDescription() { return description; }
    public LocalDate getTcgDate() { return tcgDate; }
    public LocalDate getOcgDate() { return ocgDate; }
    public String getRaw() { return raw; }
    public String getContentHash() { return contentHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
