package com.ygoleague.banlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.league.League;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.catalog.sync.scheduled=false",
        "app.banlists.import-on-startup=false",
        "app.banlists.location=classpath*:fixtures/banlists/**/*.yaml"})
@Import(PostgresTestConfig.class)
class BanListServiceTest {

    @Autowired OfficialBanListImporter importer;
    @Autowired BanListService service;
    @Autowired BanListRepository lists;
    @Autowired BanListRevisionRepository revisions;
    @Autowired AppUserRepository users;
    @Autowired LeagueRepository leagues;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    Map<String, Long> cardIds = new HashMap<>();

    @BeforeEach
    void seedCatalog() {
        jdbc.execute("truncate ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "season_rules, season_player, season, league_invite, league_member, league, user_identity, app_user "
                + "restart identity cascade");
        card("Pot of Greed", 4844, 55144522L, "2002-03-08");
        card("Sangan", 4054, 26202165L, "2002-06-26");
        card("Dark Magician", 4041, 46986414L, "2002-03-08");
        card("Future Card", 9999, 11111111L, "2010-01-01");
    }

    @Test
    void importsOfficialListsIdempotently() {
        OfficialBanListImporter.Report first = importer.importAll();
        assertEquals(List.of("tcg/2005-04-01"), first.imported(), first::toString);
        assertTrue(first.warnings().getFirst().contains("Future Card"), first::toString);

        BanList list = lists.findByOfficialKey("tcg/2005-04-01").orElseThrow();
        Map<Long, BanStatus> entries = service.entries(service.latestRevision(list.getId()).getId());
        assertEquals(Map.of(
                cardIds.get("Pot of Greed"), BanStatus.FORBIDDEN,
                cardIds.get("Sangan"), BanStatus.LIMITED,
                cardIds.get("Dark Magician"), BanStatus.LIMITED,
                cardIds.get("Future Card"), BanStatus.SEMI_LIMITED), entries);

        OfficialBanListImporter.Report second = importer.importAll();
        assertEquals(List.of("tcg/2005-04-01"), second.unchanged());
        assertEquals(1, service.latestRevision(list.getId()).getRevision());
    }

    @Test
    void aFileWithAnUnmatchedNameIsNotImportedButOthersAre() {
        OfficialBanListImporter bad = new OfficialBanListImporter(
                "classpath*:fixtures-bad/banlists/**/*.yaml", false, jdbc, tx, lists, revisions, service);
        OfficialBanListImporter.Report report = bad.importAll();

        assertEquals(List.of("tcg/2005-04-01"), report.imported());
        assertEquals("tcg/2006-09-01", report.failed().getFirst().key());
        String problem = report.failed().getFirst().problems().getFirst();
        assertTrue(problem.contains("Pot of Greeed") && problem.contains("Pot of Greed"), problem);
        assertTrue(lists.findByOfficialKey("tcg/2006-09-01").isEmpty());
    }

    @Test
    void emptyCatalogSkipsImport() {
        jdbc.execute("truncate card cascade");
        assertNotNull(importer.importAll().skippedReason());
    }

    @Test
    void customListCopiedFromOfficialThenRevisedLeavesTheOriginalIntact() {
        importer.importAll();
        AppUser commish = users.save(new AppUser("Commish"));
        League league = leagues.save(new League("Goat League", commish));
        BanListRevision official = service.latestRevision(lists.findByOfficialKey("tcg/2005-04-01").orElseThrow().getId());

        BanListRevision r1 = service.createCustom(league, "Goat + house bans", official.getId(), commish);
        assertEquals(1, r1.getRevision());
        assertEquals(official.getId(), r1.getBasedOn().getId());
        assertEquals(official.getContentHash(), r1.getContentHash());

        Map<Long, BanStatus> changes = new HashMap<>();
        changes.put(cardIds.get("Pot of Greed"), BanStatus.LIMITED);   // unban to 1
        changes.put(cardIds.get("Sangan"), null);                       // unlimited
        BanListRevision r2 = service.revise(league.getId(), r1.getBanList().getId(), changes, "house rules", commish);

        assertEquals(2, r2.getRevision());
        Map<Long, BanStatus> now = service.entries(r2.getId());
        assertEquals(BanStatus.LIMITED, now.get(cardIds.get("Pot of Greed")));
        assertTrue(!now.containsKey(cardIds.get("Sangan")));
        // Earlier revisions (which seasons may reference) are untouched.
        assertEquals(BanStatus.FORBIDDEN, service.entries(r1.getId()).get(cardIds.get("Pot of Greed")));
        assertEquals(BanStatus.FORBIDDEN, service.entries(official.getId()).get(cardIds.get("Pot of Greed")));
    }

    @Test
    void noOpRevisionWritesNothing() {
        AppUser commish = users.save(new AppUser("Commish"));
        League league = leagues.save(new League("L", commish));
        BanListRevision r1 = service.createCustom(league, "Empty", null, commish);
        BanListRevision same = service.revise(league.getId(), r1.getBanList().getId(),
                Map.of(cardIds.get("Sangan"), BanStatus.LIMITED), null, commish);
        BanListRevision again = service.revise(league.getId(), r1.getBanList().getId(),
                Map.of(cardIds.get("Sangan"), BanStatus.LIMITED), null, commish);
        assertEquals(2, same.getRevision());
        assertEquals(same.getId(), again.getId());
    }

    @Test
    void leaguesCannotTouchEachOthersCustomListsOrReviseOfficialOnes() {
        importer.importAll();
        AppUser a = users.save(new AppUser("A"));
        AppUser b = users.save(new AppUser("B"));
        League leagueA = leagues.save(new League("A League", a));
        League leagueB = leagues.save(new League("B League", b));
        BanListRevision aList = service.createCustom(leagueA, "A's list", null, a);

        assertThrows(NoSuchElementException.class, () -> service.createCustom(leagueB, "stolen", aList.getId(), b));
        assertThrows(NoSuchElementException.class,
                () -> service.revise(leagueB.getId(), aList.getBanList().getId(), Map.of(), null, b));
        long officialId = lists.findByOfficialKey("tcg/2005-04-01").orElseThrow().getId();
        assertThrows(NoSuchElementException.class, () -> service.revise(leagueA.getId(), officialId, Map.of(), null, a));

        // Official lists are visible to everyone; custom lists only to their league.
        assertEquals(List.of("Test Goat List", "A's list"), service.visibleTo(leagueA.getId()).stream().map(BanList::getName).toList());
        assertEquals(List.of("Test Goat List"), service.visibleTo(leagueB.getId()).stream().map(BanList::getName).toList());
    }

    private void card(String name, int konamiId, long passcode, String tcgDate) {
        Long id = jdbc.queryForObject("""
                insert into card (konami_id, passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (?, array[?]::bigint[], ?, 'Effect Monster', 'effect', 'Fiend', 'text', ?::date, '{}', 'h')
                returning id""", Long.class, konamiId, passcode, name, tcgDate);
        cardIds.put(name, id);
    }
}
