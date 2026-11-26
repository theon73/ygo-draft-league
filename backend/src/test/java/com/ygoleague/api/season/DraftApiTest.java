package com.ygoleague.api.season;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ygoleague.auth.CurrentUser;
import com.ygoleague.image.CardImageService;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class DraftApiTest {

    static final String ROUNDS = "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1},{\"fromRound\":3,\"toRound\":3,\"picksPerTurn\":2}]";

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;

    AppUser yugi, kaiba;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
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
