package com.ygoleague.api.season;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

    // ---- helpers ----

    /** n players, rounds for 3 picks each... and everything else the checklist needs (20 cards, enough for 3 x 2). */
    private void ready(int n) throws Exception {
        players(n);
        settings("SNAKE", "[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]");
        rules("{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"}");
        send(post(pool("/generate")), yugi, null).andExpect(status().isOk());
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
