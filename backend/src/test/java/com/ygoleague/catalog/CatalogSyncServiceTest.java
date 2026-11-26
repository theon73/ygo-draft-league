package com.ygoleague.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.support.PostgresTestConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.catalog.sync.scheduled=false")
@Import({PostgresTestConfig.class, CatalogSyncServiceTest.FakeSourceConfig.class})
class CatalogSyncServiceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeSourceConfig {
        @Bean
        @Primary
        FakeCatalogSource fakeCatalogSource() {
            return new FakeCatalogSource();
        }
    }

    @Autowired CatalogSyncService service;
    @Autowired FakeCatalogSource source;
    @Autowired CardRepository cards;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanCatalog() {
        source.reset();
        jdbc.execute("truncate card_print, card_text_version, card, card_set, catalog_sync restart identity cascade");
    }

    @Test
    void firstSyncImportsCardsSetsAndPrints() {
        CatalogSync run = service.sync(SyncTrigger.MANUAL, false);

        assertEquals(SyncStatus.SUCCEEDED, run.getStatus(), run::toString);
        assertEquals("148.08", run.getSourceVersion());
        assertEquals(8, run.getCardsAdded());
        assertEquals(0, run.getCardsUpdated());
        assertEquals(2, run.getCardsIgnored());
        assertEquals(13, run.getSetsUpserted());

        assertEquals(8L, count("card"));
        assertEquals(16L, count("card_print"));
        // The fixture's set list omits Dark Legends, which a Sangan print refers to; it's created on the fly.
        assertEquals(14L, count("card_set"));
        assertEquals(1L, jdbc.queryForObject("select count(*) from card_set where tcg_date is null", Long.class));
    }

    @Test
    void cardEntityReadsArraysAndJson() {
        service.sync(SyncTrigger.MANUAL, false);

        Card decode = cards.findByNameIgnoreCase("decode talker").getFirst();
        assertEquals(List.of("Top", "Bottom-Left", "Bottom-Right"), decode.getLinkMarkers());
        assertEquals(List.of(1861629L, 1861630L), decode.getPasscodes());
        assertNull(decode.getDef());
        assertTrue(decode.getRaw().contains("\"linkval\""));
        assertEquals(4, cards.findByPasscode(84013237L).orElseThrow().getRank());
        // Any artwork's passcode finds the card.
        assertEquals("Dark Magician", cards.findByPasscode(46986420L).orElseThrow().getName());
        assertEquals("Dark Magician", cards.findByPasscode(46986414L).orElseThrow().getName());
    }

    @Test
    void sameSourceVersionIsSkippedUnlessForced() {
        service.sync(SyncTrigger.SCHEDULED, false);

        CatalogSync skipped = service.sync(SyncTrigger.SCHEDULED, false);
        assertEquals(SyncStatus.SKIPPED, skipped.getStatus());

        CatalogSync forced = service.sync(SyncTrigger.MANUAL, true);
        assertEquals(SyncStatus.SUCCEEDED, forced.getStatus());
        assertEquals(0, forced.getCardsAdded());
        assertEquals(0, forced.getCardsUpdated());
    }

    @Test
    void priceAndPopularityChurnDoesNotRewriteCards() {
        service.sync(SyncTrigger.MANUAL, false);
        source.version = "148.09";
        source.edit = c -> {
            ((tools.jackson.databind.node.ObjectNode) c.path("misc_info").path(0)).put("views", 123);
            return c;
        };
        CatalogSync run = service.sync(SyncTrigger.SCHEDULED, false);
        assertEquals(0, run.getCardsUpdated());
    }

    @Test
    void changedCardsAreUpdatedAndTheirPrintsReplaced() {
        service.sync(SyncTrigger.MANUAL, false);
        source.version = "148.09";
        source.edit = c -> {
            if (c.path("name").asString().equals("Sangan")) {
                c.put("desc", "Errata'd text.");
                ((tools.jackson.databind.node.ArrayNode) c.path("card_sets")).remove(0);
            }
            return c;
        };

        CatalogSync run = service.sync(SyncTrigger.SCHEDULED, false);
        assertEquals(1, run.getCardsUpdated(), run::toString);
        assertEquals(0, run.getCardsAdded());

        Card sangan = cards.findByPasscode(26202165L).orElseThrow();
        assertEquals("Errata'd text.", sangan.getDescription());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from card_print where card_id = ?", Long.class, sangan.getId()));
    }

    @Test
    void reorderedResponsesKeepTheSameRows() {
        service.sync(SyncTrigger.MANUAL, false);
        source.version = "148.09";
        source.edit = c -> {
            var images = (tools.jackson.databind.node.ArrayNode) c.path("card_images");
            var reversed = new java.util.ArrayList<>(images.values()).reversed();
            images.removeAll();
            images.addAll(reversed);
            c.put("id", reversed.getFirst().path("id").asLong());
            return c;
        };
        CatalogSync run = service.sync(SyncTrigger.SCHEDULED, false);
        assertEquals(0, run.getCardsAdded(), run::toString);
        assertEquals(0, run.getCardsUpdated(), run::toString);
        assertEquals(8L, count("card"));
    }

    @Test
    void cardWithoutKonamiIdIsMatchedByPasscodeAndGainsOneLater() {
        source.edit = c -> {
            if (c.path("name").asString().equals("Pot of Greed")) {
                ((tools.jackson.databind.node.ObjectNode) c.path("misc_info").path(0)).remove("konami_id");
            }
            return c;
        };
        service.sync(SyncTrigger.MANUAL, false);
        Card before = cards.findByNameIgnoreCase("Pot of Greed").getFirst();
        assertNull(before.getKonamiId());

        // Still no konami id: matched by passcode, nothing to do.
        assertEquals(0, service.sync(SyncTrigger.MANUAL, true).getCardsAdded());

        // Upstream adds the konami id: same row is updated, not duplicated.
        source.version = "148.09";
        source.edit = c -> c;
        CatalogSync run = service.sync(SyncTrigger.SCHEDULED, false);
        assertEquals(0, run.getCardsAdded());
        assertEquals(1, run.getCardsUpdated());
        Card after = cards.findByNameIgnoreCase("Pot of Greed").getFirst();
        assertEquals(before.getId(), after.getId());
        assertEquals(4844, after.getKonamiId());
    }

    @Test
    void failedSyncIsLoggedAndLeavesCatalogUntouched() {
        source.failAfter = 3;

        CatalogSync run = service.sync(SyncTrigger.SCHEDULED, false);

        assertEquals(SyncStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("simulated outage"));
        assertEquals(0L, count("card"));
        assertEquals(0L, count("card_set"));
        assertEquals("FAILED", jdbc.queryForObject("select status from catalog_sync", String.class));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
