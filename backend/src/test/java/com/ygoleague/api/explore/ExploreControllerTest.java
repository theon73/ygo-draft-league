package com.ygoleague.api.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ygoleague.banlist.BanListService;
import com.ygoleague.banlist.OfficialBanListImporter;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
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

@SpringBootTest(properties = {
        "app.catalog.sync.scheduled=false",
        "app.banlists.import-on-startup=false",
        "app.banlists.location=classpath*:fixtures/banlists/**/*.yaml"})
@Import(PostgresTestConfig.class)
class ExploreControllerTest {

    @Autowired ExploreController explore;
    @Autowired OfficialBanListImporter importer;
    @Autowired BanListService banLists;
    @Autowired AppUserRepository users;
    @Autowired LeagueRepository leagues;
    @Autowired JdbcTemplate jdbc;

    Map<String, Long> cardIds = new HashMap<>();

    @BeforeEach
    void seed() {
        jdbc.execute("truncate ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "card_set, catalog_sync, season_rules, season_player, season, league_invite, league_member, "
                + "league, user_identity, app_user restart identity cascade");
        card("Pot of Greed", 4844, 55144522L, "2002-03-08");
        card("Sangan", 4054, 26202165L, "2002-06-26");
        card("Dark Magician", 4041, 46986414L, "2002-03-08");
        card("Future Card", 9999, 11111111L, "2010-01-01");
        card("100% Card", 9998, 22222222L, "2004-01-01");
        importer.importAll();
    }

    @Test
    void summaryCountsCatalogAndOfficialListsOnly() {
        AppUser commish = users.save(new AppUser("Commish"));
        League league = leagues.save(new League("L", commish));
        banLists.createCustom(league, "House list", null, commish);

        ExploreController.Summary s = explore.summary();
        assertEquals(5, s.cards());
        assertNull(s.lastSync());
        assertEquals(List.of("Test Goat List"), s.banLists().stream().map(ExploreController.BanListInfo::name).toList());
        ExploreController.BanListInfo goat = s.banLists().getFirst();
        assertEquals(List.of(1L, 2L, 1L), List.of(goat.forbidden(), goat.limited(), goat.semiLimited()));
    }

    @Test
    void searchIsCaseInsensitiveAndTreatsWildcardsLiterally() {
        assertEquals(List.of("Dark Magician"), names(explore.cards("magic", 0)));
        assertEquals(List.of("100% Card"), names(explore.cards("%", 0)));
        assertEquals(5, explore.cards("", 0).total());
        assertEquals(List.of(), explore.cards("", 1).items());
    }

    @Test
    void cardDetailIncludesOfficialBanStatuses() {
        ExploreController.CardDetail pot = explore.card(cardIds.get("Pot of Greed"));
        assertEquals(List.of(55144522L), pot.passcodes());
        assertEquals(List.of("FORBIDDEN"), pot.banStatuses().stream().map(ExploreController.BanStatusRow::status).toList());

        var missing = assertThrows(ResponseStatusException.class, () -> explore.card(-1));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void banListShowsEntriesButHidesCustomLists() {
        long officialId = explore.summary().banLists().getFirst().id();
        var detail = explore.banList(officialId);
        assertEquals(List.of("Pot of Greed", "Dark Magician", "Sangan", "Future Card"),
                detail.entries().stream().map(ExploreController.BanEntryRow::name).toList());

        AppUser commish = users.save(new AppUser("Commish"));
        League league = leagues.save(new League("L", commish));
        long customId = banLists.createCustom(league, "Secret", null, commish).getBanList().getId();
        var hidden = assertThrows(ResponseStatusException.class, () -> explore.banList(customId));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());
    }

    private static List<String> names(ExploreController.CardPage page) {
        return page.items().stream().map(ExploreController.CardRow::name).toList();
    }

    private void card(String name, int konamiId, long passcode, String tcgDate) {
        Long id = jdbc.queryForObject("""
                insert into card (konami_id, passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (?, array[?]::bigint[], ?, 'Effect Monster', 'effect', 'Fiend', 'text', ?::date, '{}', 'h')
                returning id""", Long.class, konamiId, passcode, name, tcgDate);
        cardIds.put(name, id);
    }
}
