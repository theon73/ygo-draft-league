package com.ygoleague.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** One printing of a card in a set at a rarity, e.g. LOB-EN005 Ultra Rare. */
@Entity
@Immutable
@Table(name = "card_print")
public class CardPrint {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private Card card;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "set_id")
    private CardSet set;

    @Column(name = "print_code")
    private String printCode;

    private String rarity;

    @Column(name = "rarity_code")
    private String rarityCode;

    protected CardPrint() {}

    public Long getId() { return id; }
    public Card getCard() { return card; }
    public CardSet getSet() { return set; }
    public String getPrintCode() { return printCode; }
    public String getRarity() { return rarity; }
    public String getRarityCode() { return rarityCode; }
}
