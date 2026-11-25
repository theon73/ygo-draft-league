package com.ygoleague.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.catalog.CardMatcher.StoredCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardMatcherTest {

    static final StoredCard DARK_MAGICIAN = new StoredCard(1, 4041, List.of(46986414L, 46986420L), "h1");
    static final StoredCard TOKEN = new StoredCard(2, null, List.of(73915052L), "h2");
    final CardMatcher matcher = new CardMatcher(List.of(DARK_MAGICIAN, TOKEN));

    @Test
    void matchesByKonamiIdEvenWithDifferentPasscodes() {
        assertEquals(DARK_MAGICIAN, matcher.match(card(4041, 99999999L)).orElseThrow());
    }

    @Test
    void fallsBackToAnySharedPasscode() {
        assertEquals(TOKEN, matcher.match(card(null, 73915052L)).orElseThrow());
        assertEquals(DARK_MAGICIAN, matcher.match(card(null, 46986420L)).orElseThrow());
    }

    @Test
    void cardGainingAKonamiIdStillMatchesItsRow() {
        assertEquals(TOKEN, matcher.match(card(5555, 73915052L)).orElseThrow());
    }

    @Test
    void passcodeOverlapNeverStealsAnotherKonamiIdsRow() {
        assertTrue(matcher.match(card(1234, 46986414L)).isEmpty());
    }

    @Test
    void unknownCardIsNew() {
        assertTrue(matcher.match(card(7777, 12345678L)).isEmpty());
    }

    static CardRecord card(Integer konamiId, long passcode) {
        return new CardRecord(konamiId, List.of(passcode), "x", "Effect Monster", "effect", "Fiend", "DARK",
                3, null, null, null, null, 1000, 600, null, "text", java.time.LocalDate.of(2002, 6, 26),
                null, List.of(), "{}", "hash");
    }
}
