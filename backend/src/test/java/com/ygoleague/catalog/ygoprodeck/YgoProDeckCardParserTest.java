package com.ygoleague.catalog.ygoprodeck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.catalog.CardRecord;
import com.ygoleague.catalog.SetRecord;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Runs against trimmed real API responses in src/test/resources/ygoprodeck. */
class YgoProDeckCardParserTest {

    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final YgoProDeckCardParser PARSER = new YgoProDeckCardParser(MAPPER);

    static Map<String, CardRecord> cards;
    static int ignored;

    @BeforeAll
    static void parseFixture() {
        List<CardRecord> out = new ArrayList<>();
        try (InputStream in = resource("cardinfo.json")) {
            List<SetRecord> sets;
            try (InputStream setsIn = resource("cardsets.json")) {
                sets = PARSER.parseSets(setsIn);
            }
            ignored = PARSER.parseCards(in, YgoProDeckCardParser.setDates(sets), out::add);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        cards = out.stream().collect(Collectors.toMap(CardRecord::name, Function.identity()));
    }

    @Test
    void skipsOcgOnlyCardsAndSkillCards() {
        assertEquals(2, ignored);
        assertEquals(8, cards.size());
        assertFalse(cards.containsKey("Ancient Fusion"));            // Skill Card
        assertFalse(cards.containsKey("30,000-Year White Turtle"));  // no TCG release
    }

    @Test
    void missingTcgDateFallsBackToEarliestPrintWhenTheCardHasTcgPrints() {
        // Real source bug: Change of Heart has no tcg_date, though it's a 2002 TCG card.
        CardRecord coh = cards.get("Change of Heart");
        assertEquals(LocalDate.of(2023, 11, 2), coh.tcgDate());   // fixture keeps only its RA01 prints
    }

    @Test
    void mapsAnEffectMonster() {
        CardRecord sangan = cards.get("Sangan");
        assertEquals(List.of(26202165L), sangan.passcodes());
        assertEquals(4054, sangan.konamiId());
        assertEquals("Effect Monster", sangan.cardType());
        assertEquals("Fiend", sangan.race());
        assertEquals("DARK", sangan.attribute());
        assertEquals(3, sangan.level());
        assertNull(sangan.rank());
        assertEquals(1000, sangan.atk());
        assertEquals(600, sangan.def());
        assertEquals(LocalDate.of(2002, 6, 26), sangan.tcgDate());
        assertEquals(LocalDate.of(1999, 11, 18), sangan.ocgDate());
        assertEquals(2, sangan.prints().size());
    }

    @Test
    void xyzRankIsNotALevel() {
        CardRecord utopia = cards.get("Number 39: Utopia");
        assertNull(utopia.level());
        assertEquals(4, utopia.rank());
    }

    @Test
    void linkMonstersHaveRatingAndMarkersButNoLevelOrDef() {
        CardRecord decode = cards.get("Decode Talker");
        assertEquals(3, decode.linkRating());
        assertEquals(List.of("Top", "Bottom-Left", "Bottom-Right"), decode.linkMarkers());
        assertNull(decode.level());
        assertNull(decode.def());
        assertEquals(2300, decode.atk());
    }

    @Test
    void pendulumScale() {
        CardRecord odd = cards.get("Odd-Eyes Pendulum Dragon");
        assertEquals(4, odd.pendulumScale());
        assertEquals(7, odd.level());
    }

    @Test
    void spellsHaveNoMonsterStats() {
        CardRecord pot = cards.get("Pot of Greed");
        assertEquals("Normal", pot.race());
        assertNull(pot.attribute());
        assertNull(pot.level());
        assertNull(pot.atk());
        assertNull(pot.def());
    }

    @Test
    void questionMarkAtkIsNull() {
        assertNull(cards.get("Aegaion the Sea Castrum").atk());
    }

    @Test
    void alternateArtworksArePasscodesOnOneCardSorted() {
        List<Long> passcodes = cards.get("Dark Magician").passcodes();
        assertEquals(9, passcodes.size());
        assertTrue(passcodes.contains(46986414L));
        assertEquals(passcodes.stream().sorted().toList(), passcodes);
    }

    @Test
    void responseOrderingDoesNotChangeTheRecord() {
        // Real quirk: the same card comes back with a different top-level id and artwork order
        // depending on the endpoint (Dark Magician: 46986414 vs 46986420).
        JsonNode original = card("Dark Magician");
        ObjectNode reordered = (ObjectNode) original.deepCopy();
        reordered.put("id", 46986414L);
        var images = (tools.jackson.databind.node.ArrayNode) reordered.path("card_images");
        List<JsonNode> reversed = new ArrayList<>(images.values()).reversed();
        images.removeAll();
        images.addAll(reversed);

        CardRecord a = PARSER.toRecord(original).orElseThrow();
        CardRecord b = PARSER.toRecord(reordered).orElseThrow();
        assertEquals(a.passcodes(), b.passcodes());
        assertEquals(a.contentHash(), b.contentHash());
        assertFalse(a.rawJson().contains("\"id\":46986420,\"name\""));
    }

    @Test
    void rawJsonDropsVolatileFields() {
        String raw = cards.get("Sangan").rawJson();
        assertFalse(raw.contains("card_prices"));
        assertFalse(raw.contains("set_price"));
        assertFalse(raw.contains("viewsweek"));
        assertTrue(raw.contains("\"tcg_date\""));
    }

    @Test
    void contentHashIgnoresPricesButNotText() {
        JsonNode original = firstCard();
        String hash = PARSER.toRecord(original).orElseThrow().contentHash();

        ObjectNode pricier = (ObjectNode) original.deepCopy();
        ((ObjectNode) pricier.path("card_prices").path(0)).put("tcgplayer_price", "99.99");
        ((ObjectNode) pricier.path("misc_info").path(0)).put("views", 1);
        assertEquals(hash, PARSER.toRecord(pricier).orElseThrow().contentHash());

        ObjectNode errata = (ObjectNode) original.deepCopy();
        errata.put("desc", "New wording.");
        assertNotEquals(hash, PARSER.toRecord(errata).orElseThrow().contentHash());
    }

    @Test
    void parsesSetsAndVersion() throws Exception {
        try (InputStream in = resource("cardsets.json")) {
            List<SetRecord> sets = PARSER.parseSets(in);
            assertEquals(13, sets.size());
            assertTrue(sets.stream().allMatch(s -> s.name() != null && s.code() != null));
        }
        try (InputStream in = resource("checkDBVer.json")) {
            assertEquals("148.08", PARSER.parseVersion(in));
        }
    }

    static JsonNode card(String name) {
        try (InputStream in = resource("cardinfo.json")) {
            for (JsonNode c : MAPPER.readTree(in).path("data").values()) {
                if (c.path("name").asString().equals(name)) return c;
            }
            throw new IllegalArgumentException(name);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    static JsonNode firstCard() {
        try (InputStream in = resource("cardinfo.json")) {
            return MAPPER.readTree(in).path("data").path(0);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    static InputStream resource(String name) {
        return YgoProDeckCardParserTest.class.getResourceAsStream("/ygoprodeck/" + name);
    }
}
