package com.ygoleague.catalog;

import java.time.LocalDate;
import java.util.List;

/**
 * A card as delivered by a catalog source, already mapped to our model.
 *
 * <p>Identity is {@code konamiId} when present, otherwise any of {@code passcodes} (one per
 * artwork, ascending); see {@link CardMatcher}. {@code rawJson} is the source record with volatile
 * and unstably-ordered fields normalized away; {@code contentHash} is its SHA-256, so unchanged
 * cards can be skipped on re-sync.
 */
public record CardRecord(
        Integer konamiId,
        List<Long> passcodes,
        String name,
        String cardType,
        String frameType,
        String race,
        String attribute,
        Integer level,
        Integer rank,
        Integer linkRating,
        List<String> linkMarkers,
        Integer pendulumScale,
        Integer atk,
        Integer def,
        String archetype,
        String description,
        LocalDate tcgDate,
        LocalDate ocgDate,
        List<PrintRecord> prints,
        String rawJson,
        String contentHash) {

    public CardRecord {
        linkMarkers = linkMarkers == null ? null : List.copyOf(linkMarkers);
        if (passcodes.isEmpty()) throw new IllegalArgumentException("A card needs at least one passcode");
        passcodes = passcodes.stream().sorted().toList();
        prints = List.copyOf(prints);
    }

    /** One printing of the card. {@code setName} is the set's natural key. */
    public record PrintRecord(String setName, String printCode, String rarity, String rarityCode) {}
}
