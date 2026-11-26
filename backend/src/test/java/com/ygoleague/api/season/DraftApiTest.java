package com.ygoleague.api.season;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ygoleague.auth.CurrentUser;
import com.ygoleague.draft.DraftService;
import com.ygoleague.image.CardImageService;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false", "app.draft.timer.enabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class DraftApiTest {

    static final String ROUNDS = "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1},{\"fromRound\":3,\"toRound\":3,\"picksPerTurn\":2}]";

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DraftService drafts;
    @MockitoBean CardImageService images;

    AppUser yugi, kaiba;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate draft_pause, draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        for (int i = 1; i <= 20; i++) card("Card " + i, "2002-03-08");
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba))).andExpect(status().isOk());
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");
    }

    @Test
    void theBoardFollowsSeatsRoundsAndOrder() throws Exception {
        players(3);
        settings("SNAKE", ROUNDS).andExpect(status().isOk())
                .andExpect(jsonPath("$.roundCount").value(3))
                .andExpect(jsonPath("$.picksPerPlayer").value(4))
                .andExpect(jsonPath("$.totalPicks").value(12))
                .andExpect(listAt("$.board[*].seat", List.of(1, 2, 3, 3, 2, 1, 1, 1, 2, 2, 3, 3)))   // snake, then 2 picks a turn
                .andExpect(listAt("$.seats[*].displayName", List.of("P1", "P2", "P3")));
        settings("LINEAR", ROUNDS).andExpect(listAt("$.board[*].seat", List.of(1, 2, 3, 1, 2, 3, 1, 1, 2, 2, 3, 3)));
        // Members can follow along; only commissioners change it.
        mvc.perform(get(draft("")).with(as(kaiba))).andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false));
        send(put(draft("/settings")), kaiba, "{\"order\":\"SNAKE\",\"rounds\":" + ROUNDS + "}").andExpect(status().isForbidden());
    }

    @Test
    void settingsAreValidatedAndTheStartTimeIsInTheLeaguesZone() throws Exception {
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1},{\"fromRound\":4,\"toRound\":5,\"picksPerTurn\":1}]").andExpect(status().isBadRequest());
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":3,\"picksPerTurn\":0}]").andExpect(status().isBadRequest());
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":101,\"picksPerTurn\":1}]").andExpect(status().isBadRequest());
        send(put(draft("/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":" + ROUNDS + ",\"scheduledStart\":\"tonight\"}").andExpect(status().isBadRequest());

        send(put(draft("/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":" + ROUNDS + ",\"scheduledStart\":\"2030-01-05T19:30\"}")
                .andExpect(jsonPath("$.scheduledStart").value("2030-01-06T01:30:00Z"))   // 19:30 in Chicago
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"));
        // Settings are a versioned section of the rules; the other rules are kept.
        mvc.perform(get(l("/seasons/" + season + "/rules")).with(as(yugi))).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void forbiddenAndSoftBannedCardsDontCountAsDraftable() throws Exception {
        ready(3);
        long list = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/x', 'List', '2008-03-01') returning id", Long.class);
        long rev = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'x') returning id", Long.class, list);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) select ?, id, 'FORBIDDEN' from card where name in ('Card 1', 'Card 2')", rev);
        rules("{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\",\"banListRevisionId\":" + rev + "}");
        jdbc.update("update pool_card set soft_banned = true where card_id = (select id from card where name = 'Card 3')");

        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.draftableCards").value(17));
        send(put(draft("/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":" + ROUNDS + ",\"forbiddenDraftable\":true}")
                .andExpect(jsonPath("$.draftableCards").value(19));
    }

    @Test
    void startingNeedsEverythingOnTheChecklist() throws Exception {
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(listAt("$.readiness[*].ok", List.of(false, false, false, false, false)));
        send(post(draft("/start")), yugi, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Not ready to start: add at least two players")));

        players(3);
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":10,\"picksPerTurn\":1}]");   // 30 picks
        rules("{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"}");
        send(post(pool("/generate")), yugi, null).andExpect(status().isOk());
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.readiness[3].ok").value(false))                 // 20 cards for 30 picks
                .andExpect(jsonPath("$.readiness[3].detail").value("only 20 draftable cards for 30 picks"));
    }

    @Test
    void startingLeavesSetupLocksTheCalendarAndRecordsTheSettings() throws Exception {
        ready(3);
        send(post(draft("/start")), kaiba, null).andExpect(status().isForbidden());
        send(post(draft("/start")), yugi, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedBy").value("Yugi"))
                .andExpect(jsonPath("$.readiness.length()").value(0));
        Assertions.assertEquals("DRAFTING", jdbc.queryForObject("select status from season", String.class));
        Assertions.assertEquals(List.of("P1", "P2", "P3"),
                jdbc.queryForList("select s->>'displayName' from draft, jsonb_array_elements(config->'seats') s order by (s->>'seat')::int", String.class));

        // The calendar now has its own week start and an explicit list of the round-robin's 3 weeks.
        mvc.perform(get(l("/seasons/" + season + "/rules")).with(as(yugi)))
                .andExpect(jsonPath("$.calendar.weekStartLocked").value(true))
                .andExpect(jsonPath("$.calendar.weeksDerived").value(false))
                .andExpect(jsonPath("$.calendar.regularWeeks").value(3));
        send(patch("/api/leagues/" + league), yugi, "{\"weekStartsOn\":\"FRIDAY\"}").andExpect(status().isOk());
        mvc.perform(get(l("/seasons/" + season + "/rules")).with(as(yugi))).andExpect(jsonPath("$.calendar.weekStartsOn").value("MONDAY"));

        // Fixed from now on.
        send(post(draft("/start")), yugi, null).andExpect(status().isConflict());
        settings("LINEAR", ROUNDS).andExpect(status().isConflict());
        send(post(pool("/generate")), yugi, null).andExpect(status().isConflict());
    }

    // ---- picking (YGO-16) ----

    @Test
    void picksFollowTheBoardAndOnlyTheSeatOrACommissionerMakesThem() throws Exception {
        send(post(draft("/picks")), yugi, "{\"cardId\":" + cardId("Card 1") + "}").andExpect(status().isConflict());   // not started
        readyWithKaibaInSeat2();
        send(post(draft("/start")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.onTheClock.seat").value(1))
                .andExpect(jsonPath("$.canPick").value(true));
        mvc.perform(get(draft("")).with(as(kaiba)))
                .andExpect(jsonPath("$.mySeat").value(2))
                .andExpect(jsonPath("$.canPick").value(false));

        // Seat 1 is a placeholder with no account: Kaiba can't pick for it, a commissioner can.
        pick(kaiba, "Card 1", 1).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("It's P1's pick"));
        pick(yugi, "Card 1", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.picksMade").value(1))
                .andExpect(jsonPath("$.board[0].pick.name").value("Card 1"))
                .andExpect(jsonPath("$.board[0].pick.madeBy").value("Yugi"))
                .andExpect(jsonPath("$.board[0].pick.onBehalf").value(true))
                .andExpect(jsonPath("$.onTheClock.seat").value(2))
                .andExpect(jsonPath("$.onTheClock.displayName").value("Kaiba"))
                .andExpect(jsonPath("$.availableCards").value(19));

        // A stale click (the board moved on) and a taken card are refused.
        pick(kaiba, "Card 2", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The board has moved on: pick #2 is up now"));
        pick(kaiba, "Card 1", 2).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Card 1 has already been drafted"));
        pick(kaiba, "Card 2", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.board[1].pick.onBehalf").value(false))
                .andExpect(jsonPath("$.onTheClock.seat").value(3));

        // The pool knows who owns what, lists only what's left on request, and won't drop an owned card.
        mvc.perform(get(pool("/cards?available=true&pageSize=100")).with(as(kaiba)))
                .andExpect(jsonPath("$.total").value(18));
        mvc.perform(get(pool("/cards?q=Card 2&pageSize=10")).with(as(kaiba)))
                .andExpect(jsonPath("$.items[?(@.name == 'Card 2')].ownedBy").value("Kaiba"));
        send(delete(pool("/cards/" + cardId("Card 1"))), yugi, "{\"effectiveFromWeek\":0}").andExpect(status().isConflict());
        send(delete(pool("/cards/" + cardId("Card 9"))), yugi, "{\"effectiveFromWeek\":0}").andExpect(status().isNoContent());
        pick(yugi, "Card 9", 3).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("That card isn't in the pool"));
    }

    @Test
    void softBannedAndForbiddenCardsCantBeDrafted() throws Exception {
        ready(3);
        long list = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/x', 'List', '2008-03-01') returning id", Long.class);
        long rev = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'x') returning id", Long.class, list);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) values (?, ?, 'FORBIDDEN')", rev, cardId("Card 1"));
        rules("{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\",\"banListRevisionId\":" + rev + "}");
        jdbc.update("update pool_card set soft_banned = true where card_id = ?", cardId("Card 2"));
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());

        pick(yugi, "Card 1", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Card 1 is Forbidden, so it can't be drafted"));
        pick(yugi, "Card 2", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Card 2 is soft-banned, so it can't be drafted"));
        mvc.perform(get(pool("/cards?available=true&pageSize=100")).with(as(yugi))).andExpect(jsonPath("$.total").value(18));
    }

    @Test
    void theLastPickCompletesTheDraftAndStartsTheSeason() throws Exception {
        ready(3);   // 2 rounds x 3 seats = 6 picks
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        for (int i = 1; i <= 5; i++) pick(yugi, "Card " + i, i).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        pick(yugi, "Card 6", 6).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.onTheClock").doesNotExist())
                .andExpect(jsonPath("$.canPick").value(false))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(listAt("$.board[*].seat", List.of(1, 2, 3, 3, 2, 1)))
                .andExpect(listAt("$.board[*].pick.name", List.of("Card 1", "Card 2", "Card 3", "Card 4", "Card 5", "Card 6")));
        Assertions.assertEquals("ACTIVE", jdbc.queryForObject("select status from season", String.class));
        Assertions.assertEquals(List.of("P1:Card 1", "P2:Card 2", "P3:Card 3", "P3:Card 4", "P2:Card 5", "P1:Card 6"),
                jdbc.queryForList("select (e->>'displayName') || ':' || (e->>'cardName') from draft, jsonb_array_elements(result->'board') e "
                        + "order by (e->>'overallPick')::int", String.class));
        pick(yugi, "Card 7", 7).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("The draft is over"));
        // Locked in for good: no undo, pause or going back.
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.undoable").doesNotExist())
                .andExpect(jsonPath("$.canUnstart").value(false));
        for (String control : List.of("/undo", "/pause", "/resume", "/unstart")) {
            send(post(draft(control)), yugi, null).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("The draft is over"));
        }
        Assertions.assertEquals(6, jdbc.queryForObject("select count(*) from card_holding", Integer.class));
    }

    // ---- live updates (YGO-17) ----

    @Test
    void membersFollowTheDraftLiveAndOnlyCommittedChangesAreAnnounced() throws Exception {
        AppUser joey = users.save(new AppUser("Joey"));   // not in the league
        mvc.perform(get(draft("/events")).with(as(joey))).andExpect(status().isNotFound());

        ready(3);
        MvcResult stream = mvc.perform(get(draft("/events")).with(as(kaiba)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        // The current state straight away, then one event per change.
        Assertions.assertEquals(List.of("{\"status\":\"NOT_STARTED\",\"picksMade\":0,\"onTheClockSeat\":null,\"paused\":false}"), events(stream));

        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1).andExpect(status().isOk());
        pick(yugi, "Card 1", 2).andExpect(status().isConflict());   // refused: nothing to announce
        Assertions.assertEquals(List.of(
                "{\"status\":\"NOT_STARTED\",\"picksMade\":0,\"onTheClockSeat\":null,\"paused\":false}",
                "{\"status\":\"IN_PROGRESS\",\"picksMade\":0,\"onTheClockSeat\":1,\"paused\":false}",
                "{\"status\":\"IN_PROGRESS\",\"picksMade\":1,\"onTheClockSeat\":2,\"paused\":false}"), events(stream));

        for (int i = 2; i <= 6; i++) pick(yugi, "Card " + i, i).andExpect(status().isOk());
        List<String> all = events(stream);
        Assertions.assertEquals("{\"status\":\"COMPLETE\",\"picksMade\":6,\"onTheClockSeat\":null,\"paused\":false}", all.getLast());
    }

    // ---- queue, timer, away (YGO-18) ----

    @Test
    void theQueueIsPrivateHoldsPoolCardsAndLosesDraftedOnes() throws Exception {
        readyWithKaibaInSeat2();
        mvc.perform(get(draft("/queue")).with(as(yugi))).andExpect(status().isForbidden());   // not a player
        queue(kaiba, "Card 3");
        queue(kaiba, "Card 1");
        queue(kaiba, "Card 3");   // already there: no change
        mvc.perform(get(draft("/queue")).with(as(kaiba))).andExpect(listAt("$.cards[*].name", List.of("Card 3", "Card 1")));
        send(put(draft("/queue")), kaiba, "{\"cardIds\":[" + cardId("Card 1") + "," + cardId("Card 3") + "]}")
                .andExpect(listAt("$.cards[*].name", List.of("Card 1", "Card 3")))
                .andExpect(listAt("$.cards[*].available", List.of(true, true)));
        send(put(draft("/queue")), kaiba, "{\"cardIds\":[999999]}").andExpect(status().isBadRequest());

        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1).andExpect(status().isOk());
        mvc.perform(get(draft("/queue")).with(as(kaiba))).andExpect(listAt("$.cards[*].name", List.of("Card 3")));
    }

    @Test
    void timerSettingsAreValidated() throws Exception {
        players(3);
        timer(10, "SKIP", null, null).andExpect(status().isBadRequest());
        timer(60, "SKIP", "23:00", null).andExpect(status().isBadRequest());
        timer(60, "SKIP", "23:00", "23:00").andExpect(status().isBadRequest());
        timer(3600, "AUTO_PICK", "23:00", "08:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.timer.seconds").value(3600))
                .andExpect(jsonPath("$.timer.onExpiry").value("AUTO_PICK"))
                .andExpect(jsonPath("$.timer.pauseFrom").value("23:00:00"));
        timer(0, "SKIP", null, null).andExpect(jsonPath("$.timer").doesNotExist());
    }

    @Test
    void anExpiredPickIsSkippedAndMadeUpLater() throws Exception {
        readyWithKaibaInSeat2();
        timer(60, "SKIP", null, null);
        send(post(draft("/start")), yugi, null).andExpect(jsonPath("$.deadline").exists());

        drafts.expireDue(Instant.now());   // not due yet
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.picksMade").value(0));
        drafts.expireDue(Instant.now().plusSeconds(61));
        mvc.perform(get(draft("")).with(as(kaiba)))
                .andExpect(jsonPath("$.picksMade").value(1))
                .andExpect(jsonPath("$.board[0].skipped").value(true))
                .andExpect(jsonPath("$.board[0].pick").doesNotExist())
                .andExpect(jsonPath("$.onTheClock.seat").value(2))
                .andExpect(listAt("$.owed[*].displayName", List.of("P1")))
                .andExpect(listAt("$.pickable[*].overallPick", List.of(2)));   // Kaiba's own turn, not P1's make-up

        // P1's make-up: not Kaiba's to make, but a commissioner can, and it doesn't move the clock.
        pick(kaiba, "Card 1", 1).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("That's P1's skipped pick"));
        pick(yugi, "Card 1", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.board[0].pick.name").value("Card 1"))
                .andExpect(jsonPath("$.board[0].pick.madeUp").value(true))
                .andExpect(jsonPath("$.owed.length()").value(0))
                .andExpect(jsonPath("$.picksMade").value(1))
                .andExpect(jsonPath("$.onTheClock.seat").value(2));
        pick(yugi, "Card 2", 1).andExpect(status().isConflict());   // made up already
    }

    @Test
    void aMakeUpRestartsTheTimerOfThePickOnTheClock() throws Exception {
        readyWithKaibaInSeat2();
        timer(60, "SKIP", null, null);
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        drafts.expireDue(Instant.now().plusSeconds(61));   // pick 1 skipped; Kaiba is up
        jdbc.update("update draft_pick set made_at = made_at - interval '50 seconds'");   // ...and has been for 50 s

        pick(yugi, "Card 1", 1).andExpect(status().isOk());   // P1's make-up: Kaiba's 60 s start again
        drafts.expireDue(Instant.now().plusSeconds(20));      // past the old deadline, not the new one
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.onTheClock.seat").value(2));
        drafts.expireDue(Instant.now().plusSeconds(61));
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.board[1].skipped").value(true))
                .andExpect(jsonPath("$.onTheClock.seat").value(3));
    }

    @Test
    void autoPickTakesTheFirstAvailableQueuedCardOrSkips() throws Exception {
        readyWithKaibaInSeat2();
        timer(60, "AUTO_PICK", null, null);
        queue(kaiba, "Card 2");
        queue(kaiba, "Card 5");
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 2", 1).andExpect(status().isOk());   // takes Kaiba's first choice

        drafts.expireDue(Instant.now().plusSeconds(61));
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.board[1].pick.name").value("Card 5"))
                .andExpect(jsonPath("$.board[1].pick.auto").value(true))
                .andExpect(jsonPath("$.board[1].pick.madeBy").doesNotExist())
                .andExpect(jsonPath("$.onTheClock.seat").value(3));
        drafts.expireDue(Instant.now().plusSeconds(61));   // P3 has no queue: skipped
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.board[2].skipped").value(true))
                .andExpect(jsonPath("$.onTheClock.overallPick").value(4));
        Assertions.assertEquals(List.of("PICK", "AUTO", "SKIP"),
                jdbc.queryForList("select kind from draft_pick order by sequence", String.class));
    }

    @Test
    void awayModePicksAtOnceAndTurnsOffWhenTheQueueRunsDry() throws Exception {
        readyWithKaibaInSeat2();   // no timer: away mode works without one
        queue(kaiba, "Card 5");
        send(put(draft("/away")), kaiba, "{\"away\":true}").andExpect(jsonPath("$.away").value(true));
        send(put(draft("/away")), yugi, "{\"away\":true}").andExpect(status().isForbidden());   // not a player
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());

        pick(yugi, "Card 1", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.board[1].pick.name").value("Card 5"))   // Kaiba's pick, at once
                .andExpect(jsonPath("$.onTheClock.seat").value(3));
        pick(yugi, "Card 2", 3).andExpect(status().isOk());
        pick(yugi, "Card 3", 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.onTheClock.seat").value(2));   // queue empty: Kaiba is back on the clock
        mvc.perform(get(draft("/queue")).with(as(kaiba))).andExpect(jsonPath("$.away").value(false));

        // Turning away on while on the clock picks straight away.
        queue(kaiba, "Card 7");
        send(put(draft("/away")), kaiba, "{\"away\":true}").andExpect(status().isOk());
        mvc.perform(get(draft("")).with(as(kaiba)))
                .andExpect(jsonPath("$.board[4].pick.name").value("Card 7"))
                .andExpect(jsonPath("$.onTheClock.seat").value(1));
    }

    @Test
    void theDraftEndsWithTheLastSlotAndForfeitsWhatsStillOwed() throws Exception {
        ready(3);   // 6 picks
        timer(60, "SKIP", null, null);
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        drafts.expireDue(Instant.now().plusSeconds(61));   // pick 1 skipped
        for (int i = 2; i <= 6; i++) pick(yugi, "Card " + i, i).andExpect(status().isOk());
        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.owed.length()").value(0))
                .andExpect(jsonPath("$.board[0].skipped").value(true));
        pick(yugi, "Card 1", 1).andExpect(status().isConflict());
        Assertions.assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select (e->>'skipped')::boolean and e->>'cardId' is null from draft, jsonb_array_elements(result->'board') e "
                        + "where (e->>'overallPick')::int = 1", Boolean.class));
    }

    // ---- commissioner controls (YGO-19) ----

    @Test
    void undoWalksBackThroughTheLogAndFreesTheCards() throws Exception {
        readyWithKaibaInSeat2();
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1).andExpect(status().isOk());
        pick(kaiba, "Card 2", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.undoable").doesNotExist());   // players don't see it
        send(post(draft("/undo")), kaiba, null).andExpect(status().isForbidden());

        mvc.perform(get(draft("")).with(as(yugi)))
                .andExpect(jsonPath("$.undoable.overallPick").value(2))
                .andExpect(jsonPath("$.undoable.card").value("Card 2"))
                .andExpect(jsonPath("$.undoable.displayName").value("Kaiba"));
        send(post(draft("/undo")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.board[1].pick").doesNotExist())
                .andExpect(jsonPath("$.picksMade").value(1))
                .andExpect(jsonPath("$.onTheClock.seat").value(2))
                .andExpect(jsonPath("$.availableCards").value(19))
                .andExpect(jsonPath("$.undoable.card").value("Card 1"));
        send(post(draft("/undo")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.picksMade").value(0))
                .andExpect(jsonPath("$.onTheClock.seat").value(1))
                .andExpect(jsonPath("$.undoable").doesNotExist());
        send(post(draft("/undo")), yugi, null).andExpect(status().isConflict());
        Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from card_holding", Integer.class));

        // Nothing is lost from the log, and the same cards can be picked again.
        pick(yugi, "Card 1", 1).andExpect(status().isOk());
        pick(kaiba, "Card 2", 2).andExpect(status().isOk());
        Assertions.assertEquals(List.of("PICK", "PICK", "UNDO", "UNDO", "PICK", "PICK"),
                jdbc.queryForList("select kind from draft_pick order by sequence", String.class));
    }

    @Test
    void afterAnUndoWhoeverIsOnTheClockGetsAFreshTimer() throws Exception {
        readyWithKaibaInSeat2();
        timer(60, "SKIP", null, null);
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        drafts.expireDue(Instant.now().plusSeconds(61));   // P1 skipped by the timer
        // Make it look like this all happened a while ago.
        jdbc.update("update draft set started_at = started_at - interval '2 minutes'");
        jdbc.update("update draft_pick set made_at = made_at - interval '1 minute'");

        send(post(draft("/undo")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.onTheClock.seat").value(1))
                .andExpect(jsonPath("$.board[0].skipped").value(false));
        drafts.expireDue(Instant.now().plusSeconds(30));   // a stale timer would have expired by now
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.picksMade").value(0));
        drafts.expireDue(Instant.now().plusSeconds(61));
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.board[0].skipped").value(true));
    }

    @Test
    void undoingAnAutoPickTurnsAwayModeOffSoItIsntPickedAgain() throws Exception {
        readyWithKaibaInSeat2();
        queue(kaiba, "Card 5");
        queue(kaiba, "Card 6");
        send(put(draft("/away")), kaiba, "{\"away\":true}").andExpect(status().isOk());
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1).andExpect(jsonPath("$.board[1].pick.name").value("Card 5"));

        send(post(draft("/undo")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.board[1].pick").doesNotExist())
                .andExpect(jsonPath("$.onTheClock.seat").value(2));
        mvc.perform(get(draft("/queue")).with(as(kaiba))).andExpect(jsonPath("$.away").value(false));
    }

    @Test
    void anUndoneMakeUpIsOwedAgain() throws Exception {
        readyWithKaibaInSeat2();
        timer(60, "SKIP", null, null);
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        drafts.expireDue(Instant.now().plusSeconds(61));
        pick(yugi, "Card 1", 1).andExpect(jsonPath("$.board[0].pick.madeUp").value(true));
        send(post(draft("/undo")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.board[0].pick").doesNotExist())
                .andExpect(listAt("$.owed[*].overallPick", List.of(1)))
                .andExpect(jsonPath("$.onTheClock.seat").value(2));
        pick(yugi, "Card 1", 1).andExpect(status().isOk());   // and can be made up again
    }

    @Test
    void pausingStopsPicksAndTheTimerAndResumingGivesTheTimeBack() throws Exception {
        readyWithKaibaInSeat2();
        timer(60, "SKIP", null, null);
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        send(post(draft("/pause")), kaiba, null).andExpect(status().isForbidden());
        send(post(draft("/pause")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(true))
                .andExpect(jsonPath("$.heldBy").value("Yugi"))
                .andExpect(jsonPath("$.deadline").doesNotExist())
                .andExpect(jsonPath("$.canPick").value(false));
        send(post(draft("/pause")), yugi, null).andExpect(status().isConflict());
        pick(yugi, "Card 1", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("The draft is paused"));
        drafts.expireDue(Instant.now().plusSeconds(600));
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.picksMade").value(0));

        // P1 had used 10 s when the pause began, 90 s ago; resuming now leaves 50 s.
        send(post(draft("/resume")), yugi, null).andExpect(status().isOk()).andExpect(jsonPath("$.held").value(false));
        jdbc.update("update draft set started_at = now() - interval '100 seconds'");
        jdbc.update("update draft_pause set paused_at = now() - interval '90 seconds', resumed_at = now()");
        drafts.expireDue(Instant.now().plusSeconds(40));
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.picksMade").value(0));
        drafts.expireDue(Instant.now().plusSeconds(55));
        mvc.perform(get(draft("")).with(as(yugi))).andExpect(jsonPath("$.board[0].skipped").value(true));
        send(post(draft("/resume")), yugi, null).andExpect(status().isConflict());
    }

    @Test
    void aDraftWithNoPicksCanGoBackToSetup() throws Exception {
        ready(3);
        send(post(draft("/start")), yugi, null).andExpect(jsonPath("$.canUnstart").value(true));
        send(post(draft("/unstart")), kaiba, null).andExpect(status().isForbidden());
        send(post(draft("/unstart")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.readiness.length()").value(5));
        Assertions.assertEquals("SETUP", jdbc.queryForObject("select status from season", String.class));
        mvc.perform(get(l("/seasons/" + season + "/rules")).with(as(yugi)))
                .andExpect(jsonPath("$.calendar.weekStartLocked").value(false))
                .andExpect(jsonPath("$.calendar.weeksDerived").value(true));
        settings("LINEAR", ROUNDS).andExpect(status().isOk());   // editable again

        // With a pick in effect it can't; once every pick is undone it can, and the log is cleared.
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]");
        send(post(draft("/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1).andExpect(jsonPath("$.canUnstart").value(false));
        send(post(draft("/pause")), yugi, null).andExpect(status().isOk());
        send(post(draft("/unstart")), yugi, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Picks have been made; undo them all first to go back to setup"));
        send(post(draft("/undo")), yugi, null).andExpect(jsonPath("$.canUnstart").value(true));
        send(post(draft("/unstart")), yugi, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_STARTED"));
        Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from draft_pick", Integer.class));
        Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from draft_pause", Integer.class));
        send(post(draft("/start")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.picksMade").value(0))
                .andExpect(jsonPath("$.held").value(false));
    }

    // ---- helpers ----

    /** n players, rounds for 3 picks each... and everything else the checklist needs (20 cards, enough for 3 x 2). */
    private void ready(int n) throws Exception {
        players(n);
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]");
        rules("{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"}");
        send(post(pool("/generate")), yugi, null).andExpect(status().isOk());
    }

    /** Like ready(3), with Kaiba (who has an account) in seat 2 between two placeholders. */
    private void readyWithKaibaInSeat2() throws Exception {
        players(1);
        long seasonId = jdbc.queryForObject("select id from season", Long.class);
        jdbc.update("""
                insert into season_player (season_id, league_id, league_member_id, seat)
                select ?, league_id, id, 2 from league_member where display_name = 'Kaiba'""", seasonId);
        long member = jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P3', 'MEMBER' from season returning id", Long.class);
        jdbc.update("insert into season_player (season_id, league_id, league_member_id, seat) select id, league_id, ?, 3 from season", member);
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]");
        rules("{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"}");
        send(post(pool("/generate")), yugi, null).andExpect(status().isOk());
    }

    private void queue(AppUser user, String card) throws Exception {
        send(post(draft("/queue")), user, "{\"cardId\":" + cardId(card) + "}").andExpect(status().isOk());
    }

    /** Draft settings of 2 rounds x 1 pick, with a timer. */
    private ResultActions timer(int seconds, String onExpiry, String pauseFrom, String pauseTo) throws Exception {
        String pause = pauseFrom == null ? "" : ",\"pauseFrom\":\"" + pauseFrom + "\"" + (pauseTo == null ? "" : ",\"pauseTo\":\"" + pauseTo + "\"");
        return send(put(draft("/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}],"
                + "\"timerSeconds\":" + seconds + ",\"timerExpiry\":\"" + onExpiry + "\"" + pause + "}");
    }

    /** The data lines of the draft events a stream has received so far. */
    private static List<String> events(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString().lines()
                .filter(l -> l.startsWith("data:")).map(l -> l.substring("data:".length())).toList();
    }

    private ResultActions pick(AppUser user, String card, int overallPick) throws Exception {
        return send(post(draft("/picks")), user, "{\"cardId\":" + cardId(card) + ",\"overallPick\":" + overallPick + "}");
    }

    private long cardId(String name) {
        return jdbc.queryForObject("select id from card where name = ?", Long.class, name);
    }

    private void players(int n) {
        long leagueId = jdbc.queryForObject("select id from league where name = 'Battle City'", Long.class);
        long seasonId = jdbc.queryForObject("select id from season where league_id = ?", Long.class, leagueId);
        for (int i = 1; i <= n; i++) {
            long member = jdbc.queryForObject("insert into league_member (league_id, display_name, role) values (?, ?, 'MEMBER') returning id",
                    Long.class, leagueId, "P" + i);
            jdbc.update("insert into season_player (season_id, league_id, league_member_id, seat) values (?, ?, ?, ?)", seasonId, leagueId, member, i);
        }
    }

    private ResultActions settings(String order, String rounds) throws Exception {
        return send(put(draft("/settings")), yugi, "{\"order\":\"" + order + "\",\"rounds\":" + rounds + "}");
    }

    private void rules(String body) throws Exception {
        send(put(l("/seasons/" + season + "/rules")), yugi, body).andExpect(status().isOk());
    }

    private void card(String name, String released) {
        jdbc.update("""
                insert into card (passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (array[(select coalesce(max(id), 0) + 1000 from card)]::bigint[], ?, 'Effect Monster', 'effect', 'Fiend', 'text', ?::date, '{}', 'h')""",
                name, released);
    }

    private String l(String path) { return "/api/leagues/" + league + path; }
    private String draft(String path) { return l("/seasons/" + season + "/draft" + path); }
    private String pool(String path) { return l("/seasons/" + season + "/pool" + path); }

    private static ResultMatcher listAt(String path, List<?> expected) {
        return result -> Assertions.assertEquals(expected, JsonPath.read(result.getResponse().getContentAsString(), path), path);
    }

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private ResultActions send(MockHttpServletRequestBuilder req, AppUser user, String body) throws Exception {
        req.with(csrf()).with(as(user));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private static RequestPostProcessor as(AppUser user) {
        CurrentUser principal = new CurrentUser(user.getId(), user.getDisplayName(), null, "dev", "u" + user.getId(), false);
        return authentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "dev"));
    }
}
