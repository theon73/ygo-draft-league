package com.ygoleague.api.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ygoleague.banlist.BanListService;
import com.ygoleague.banlist.OfficialBanListImporter;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.season.pool.PoolService.Kind;
import com.ygoleague.season.pool.PoolService.PoolQuery;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The Cards page's catalog search, format summary and ban list changes (YGO-38). */
@SpringBootTest(properties = {
        "app.catalog.sync.scheduled=false",
        "app.banlists.import-on-startup=false",
        "app.banlists.location=classpath*:fixtures/banlists/**/*.yaml"})
@Import(PostgresTestConfig.class)
class CatalogControllerTest {

    @Autowired CatalogController catalog;
    @Autowired ExploreController explore;
    @Autowired OfficialBanListImporter importer;
    @Autowired BanListService banLists;
    @Autowired AppUserRepository users;
    @Autowired LeagueRepository leagues;
    @Autowired JdbcTemplate jdbc;

    Map<String, Long> cardIds = new HashMap<>();
    long goat;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "card_set, catalog_sync, season_rules, season_player, season, league_invite, league_member, "
                + "league, user_identity, app_user restart identity cascade");
        card("Pot of Greed", 55144522L, "2002-03-08", "spell", "Spell Card", "Normal");
        card("Sangan", 26202165L, "2002-06-26", "effect", "Effect Monster", "Fiend");
        card("Dark Magician", 46986414L, "2002-03-08", "normal", "Normal Monster", "Spellcaster");
        card("Future Card", 11111111L, "2010-01-01", "effect", "Effect Monster", "Fiend");
        card("Thousand-Eyes Restrict", 63519819L, "2002-10-20", "fusion", "Fusion Monster", "Spellcaster");
        importer.importAll();
        goat = explore.summary().banLists().getFirst().id();
    }

    @Test
    void cutoffLimitsToReleasedCardsAndTheListAddsStatuses() {
        var all = catalog.cards(null, null, query(null, null));
        assertEquals(5, all.total());
        assertNull(all.items().getFirst().banStatus());   // no list chosen

        var format = catalog.cards(LocalDate.of(2005, 4, 1), goat, query(null, null));
        assertEquals(List.of("Dark Magician", "Pot of Greed", "Sangan", "Thousand-Eyes Restrict"), names(format));
        assertEquals(List.of("LIMITED", "FORBIDDEN", "LIMITED", "UNLIMITED"),
                format.items().stream().map(c -> c.banStatus() == null ? "UNLIMITED" : c.banStatus()).toList());
    }

    @Test
    void filtersMatchThePools() {
        assertEquals(List.of("Pot of Greed"), names(catalog.cards(null, goat, query(List.of("FORBIDDEN"), null))));
        assertEquals(List.of("Pot of Greed"), names(catalog.cards(null, null, query(null, List.of(Kind.SPELL)))));
    }

    @Test
    void summaryCountsTheFormatAndOnlyTheListedCardsInIt() {
        var s = catalog.summary(LocalDate.of(2005, 4, 1), goat);
        assertEquals("Test Goat List", s.banList());
        assertEquals(List.of(4L, 2L, 1L, 1L, 0L), List.of(s.cards(), s.monsters(), s.extra(), s.spells(), s.traps()));
        // Future Card is semi-limited on the list but released after the cutoff, so it doesn't count.
        assertEquals(List.of(1L, 2L, 0L), List.of(s.forbidden(), s.limited(), s.semiLimited()));
    }

    @Test
    void customListsAreNotFound() {
        AppUser commish = users.save(new AppUser("Commish"));
        League league = leagues.save(new League("L", commish));
        long custom = banLists.createCustom(league, "Secret", null, commish).getBanList().getId();
        var e = assertThrows(ResponseStatusException.class, () -> catalog.cards(null, custom, query(null, null)));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void banListShowsWhatChangedSinceThePreviousList() {
        assertNull(explore.banList(goat).previous());
        assertEquals(List.of(), explore.banList(goat).changes());

        // A later list: Pot stays Forbidden, Sangan goes to Semi-Limited, Dark Magician and Future Card come off.
        long later = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/2006-04-01', 'Later List', '2006-04-01') returning id", Long.class);
        long rev = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'h') returning id", Long.class, later);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) values (?, ?, 'FORBIDDEN'), (?, ?, 'SEMI_LIMITED')",
                rev, cardIds.get("Pot of Greed"), rev, cardIds.get("Sangan"));

        var detail = explore.banList(later);
        assertEquals("Test Goat List", detail.previous().name());
        assertEquals(List.of("Sangan: LIMITED → SEMI_LIMITED", "Dark Magician: LIMITED → UNLIMITED", "Future Card: SEMI_LIMITED → UNLIMITED"),
                detail.changes().stream().map(c -> c.name() + ": " + c.from() + " → " + (c.to() == null ? "UNLIMITED" : c.to())).toList());
    }

    private static PoolQuery query(List<String> banStatus, List<Kind> kind) {
        return new PoolQuery(null, null, null, null, kind, null, null, null, null, null, null, null, null, null, banStatus,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static List<String> names(CatalogController.CatalogPage page) {
        return page.items().stream().map(CatalogController.CatalogCard::name).toList();
    }

    private void card(String name, long passcode, String tcgDate, String frame, String type, String race) {
        Long id = jdbc.queryForObject("""
                insert into card (konami_id, passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (null, array[?]::bigint[], ?, ?, ?, ?, 'text', ?::date, '{}', 'h')
                returning id""", Long.class, passcode, name, type, frame, race, tcgDate);
        cardIds.put(name, id);
    }
}
