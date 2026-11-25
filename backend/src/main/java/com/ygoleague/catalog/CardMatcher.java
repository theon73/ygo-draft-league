package com.ygoleague.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the stored card an incoming {@link CardRecord} refers to. Pure logic, no Spring.
 *
 * <p>Matches on {@code konamiId} first (Konami's id: unique and stable). Cards without one (a few
 * tokens, not-yet-released cards) match on any shared passcode instead, and so does a stored row
 * that hasn't got a konami id yet, so a card that gains one later updates in place rather than
 * being duplicated.
 */
public final class CardMatcher {

    /** What the sync needs to know about a stored card. */
    public record StoredCard(long id, Integer konamiId, List<Long> passcodes, String contentHash) {}

    private final Map<Integer, StoredCard> byKonamiId = new HashMap<>();
    private final Map<Long, StoredCard> byPasscode = new HashMap<>();

    public CardMatcher(List<StoredCard> stored) {
        for (StoredCard c : stored) {
            if (c.konamiId() != null) byKonamiId.put(c.konamiId(), c);
            for (Long p : c.passcodes()) byPasscode.put(p, c);
        }
    }

    public Optional<StoredCard> match(CardRecord card) {
        if (card.konamiId() != null) {
            StoredCard hit = byKonamiId.get(card.konamiId());
            if (hit != null) return Optional.of(hit);
        }
        for (Long p : card.passcodes()) {
            StoredCard hit = byPasscode.get(p);
            // Never let a passcode overlap steal a row that belongs to a different konami id.
            if (hit != null && (hit.konamiId() == null || card.konamiId() == null
                    || hit.konamiId().equals(card.konamiId()))) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }
}
