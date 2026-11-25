package com.ygoleague.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** A TCG product. {@code name} is unique; {@code code} is a prefix that several sets can share. */
@Entity
@Immutable
@Table(name = "card_set")
public class CardSet {

    @Id
    private Long id;

    private String name;
    private String code;

    @Column(name = "tcg_date")
    private LocalDate tcgDate;

    @Column(name = "card_count")
    private Integer cardCount;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected CardSet() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public LocalDate getTcgDate() { return tcgDate; }
    public Integer getCardCount() { return cardCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
