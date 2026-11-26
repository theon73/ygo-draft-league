package com.ygoleague.banlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.banlist.CardNameIndex.CatalogCard;
import com.ygoleague.banlist.CardNameIndex.Matched;
import com.ygoleague.banlist.CardNameIndex.Unmatched;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardNameIndexTest {

    static final LocalDate D = LocalDate.of(2002, 3, 8);
    static final CatalogCard SANGAN = new CatalogCard(1, "Sangan", List.of(26202165L), D);
    static final CatalogCard HARPIE = new CatalogCard(2, "Harpie's Feather Duster", List.of(18144506L), D);
    static final CatalogCard BLS = new CatalogCard(3, "Black Luster Soldier - Envoy of the Beginning", List.of(72989439L), D);
    static final CatalogCard MAXX = new CatalogCard(4, "Maxx \"C\"", List.of(23434538L), D);
    static final CardNameIndex INDEX = new CardNameIndex(List.of(SANGAN, HARPIE, BLS, MAXX));

    @Test
    void matchesIgnoringCaseAndTypography() {
        assertEquals(SANGAN, matched("SANGAN"));
        assertEquals(HARPIE, matched("Harpie’s Feather Duster"));                       // curly apostrophe
        assertEquals(BLS, matched("Black Luster Soldier – Envoy of the  Beginning"));   // en dash, double space
        assertEquals(MAXX, matched("Maxx “C”"));                                   // curly quotes
    }

    @Test
    void passcodeWinsButMustAgreeWithTheName() {
        assertEquals(SANGAN, ((Matched) INDEX.resolve(new CardRef("Sangan", 26202165L))).card());
        Unmatched wrong = assertInstanceOf(Unmatched.class, INDEX.resolve(new CardRef("Sangan", 18144506L)));
        assertTrue(wrong.reason().contains("Harpie's Feather Duster"), wrong.reason());
    }

    @Test
    void ambiguousNamesNeedAPasscode() {
        CardNameIndex dupes = new CardNameIndex(List.of(SANGAN, new CatalogCard(9, "sangan", List.of(1L), D)));
        Unmatched u = assertInstanceOf(Unmatched.class, dupes.resolve(CardRef.named("Sangan")));
        assertTrue(u.reason().contains("ambiguous"));
    }

    @Test
    void typosGetSuggestions() {
        Unmatched u = assertInstanceOf(Unmatched.class, INDEX.resolve(CardRef.named("Harpies Feather Dusters")));
        assertEquals("Harpie's Feather Duster", u.suggestions().getFirst());
    }

    @Test
    void levenshteinGivesUpPastTheLimit() {
        assertEquals(1, CardNameIndex.levenshtein("sangan", "sanggan", 3));
        assertEquals(4, CardNameIndex.levenshtein("sangan", "harpie's feather duster", 3));
    }

    static CatalogCard matched(String name) {
        return assertInstanceOf(Matched.class, INDEX.resolve(CardRef.named(name))).card();
    }
}
