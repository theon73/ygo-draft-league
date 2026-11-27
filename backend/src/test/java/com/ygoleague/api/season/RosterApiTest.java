package com.ygoleague.api.season;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ygoleague.auth.CurrentUser;
import com.ygoleague.image.CardImageService;
import com.ygoleague.notify.DiscordWebhook;
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

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false", "app.draft.timer.enabled=false", "app.notifications.synchronous=true"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class RosterApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;
    @MockitoBean DiscordWebhook discord;

    AppUser yugi, kaiba, joey;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate league_notification, draft_timer_change, draft_pause, draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        for (int i = 1; i <= 20; i++) card("Card " + i);
        jdbc.update("update card set frame_type = 'spell', card_type = 'Spell Card', race = 'Normal' where name = 'Card 5'");
        jdbc.update("update card set frame_type = 'fusion', card_type = 'Fusion Monster' where name = 'Card 6'");
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        joey = users.save(new AppUser("Joey"));   // not in the league
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba))).andExpect(status().isOk());
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");

        // Seats: P1 (placeholder), Kaiba, P3 (placeholder); 2 rounds of 1 pick = 6 picks.
        long seasonId = jdbc.queryForObject("select id from season", Long.class);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P1', 'MEMBER' from season returning id", Long.class), 1, seasonId);
        seat(jdbc.queryForObject("select id from league_member where display_name = 'Kaiba'", Long.class), 2, seasonId);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P3', 'MEMBER' from season returning id", Long.class), 3, seasonId);
        send(put(s("/draft/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]}").andExpect(status().isOk());
        send(put(s("/rules")), yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"}")
                .andExpect(status().isOk());
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
    }

    @Test
    void rostersFollowTheDraftAndSkipUndonePicks() throws Exception {
        // Nobody holds anything before the draft.
        mvc.perform(get(s("/rosters")).with(as(kaiba))).andExpect(status().isOk())
                .andExpect(jsonPath("$.freeAgents").value(20))
                .andExpect(listAt("$.players[*].displayName", List.of("P1", "Kaiba", "P3")))
                .andExpect(listAt("$.players[*].counts.total", List.of(0, 0, 0)));

        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1);    // for P1
        pick(kaiba, "Card 2", 2);   // his own
        pick(yugi, "Card 3", 3);
        send(post(s("/draft/undo")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 7", 3);    // P3 again, a different card
        pick(yugi, "Card 4", 4);
        pick(kaiba, "Card 5", 5);
        pick(yugi, "Card 6", 6);    // completes the draft

        mvc.perform(get(s("/rosters")).with(as(kaiba))).andExpect(status().isOk())
                .andExpect(jsonPath("$.freeAgents").value(14))
                .andExpect(listAt("$.players[*].you", List.of(false, true, false)))
                .andExpect(listAt("$.players[*].counts.total", List.of(2, 2, 2)))
                // Grouped (main monsters, extra, spells, traps), then by name.
                .andExpect(listAt("$.players[0].cards[*].card.name", List.of("Card 1", "Card 6")))
                .andExpect(listAt("$.players[0].cards[*].group", List.of("MONSTER", "EXTRA")))
                .andExpect(jsonPath("$.players[0].counts.extra").value(1))
                .andExpect(listAt("$.players[1].cards[*].card.name", List.of("Card 2", "Card 5")))
                .andExpect(jsonPath("$.players[1].counts.spells").value(1))
                .andExpect(listAt("$.players[1].cards[*].draftPick", List.of(2, 5)))
                .andExpect(listAt("$.players[2].cards[*].card.name", List.of("Card 4", "Card 7")))
                .andExpect(listAt("$.players[2].cards[*].acquiredWeek", List.of(0, 0)));

        // A card's owners and history.
        mvc.perform(get(s("/cards/" + cardId("Card 1"))).with(as(kaiba))).andExpect(status().isOk())
                .andExpect(jsonPath("$.card.name").value("Card 1"))
                .andExpect(listAt("$.owners[*].displayName", List.of("P1")))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].source").value("DRAFT"))
                .andExpect(jsonPath("$.history[0].to").value("P1"))
                .andExpect(jsonPath("$.history[0].from").doesNotExist())
                .andExpect(jsonPath("$.history[0].draftPick").value(1))
                .andExpect(jsonPath("$.history[0].onBehalfBy").value("Yugi"));
        mvc.perform(get(s("/cards/" + cardId("Card 2"))).with(as(kaiba)))
                .andExpect(jsonPath("$.owners[0].week").value(0))
                .andExpect(jsonPath("$.history[0].onBehalfBy").doesNotExist())
                .andExpect(jsonPath("$.history[0].auto").value(false));
        // The undone pick never happened as far as ownership goes.
        mvc.perform(get(s("/cards/" + cardId("Card 3"))).with(as(kaiba)))
                .andExpect(jsonPath("$.owners.length()").value(0))
                .andExpect(jsonPath("$.history.length()").value(0));
        Assertions.assertEquals(6, jdbc.queryForObject("select count(*) from card_holding", Integer.class));

        // Free agents are the pool's "available only" view.
        mvc.perform(get(s("/pool/cards?available=true&pageSize=100")).with(as(kaiba))).andExpect(jsonPath("$.total").value(14));
    }

    @Test
    void onlyMembersSeeRostersAndOnlyPoolCardsHavePages() throws Exception {
        mvc.perform(get(s("/rosters")).with(as(joey))).andExpect(status().isNotFound());
        mvc.perform(get(s("/cards/" + cardId("Card 1"))).with(as(joey))).andExpect(status().isNotFound());
        card("Card 21");   // in the catalog, not in the pool
        mvc.perform(get(s("/cards/" + cardId("Card 21"))).with(as(kaiba))).andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private void seat(long memberId, int seat, long seasonId) {
        jdbc.update("insert into season_player (season_id, league_id, league_member_id, seat) select ?, league_id, ?, ? from league_member where id = ?",
                seasonId, memberId, seat, memberId);
    }

    private void pick(AppUser user, String card, int overallPick) throws Exception {
        send(post(s("/draft/picks")), user, "{\"cardId\":" + cardId(card) + ",\"overallPick\":" + overallPick + "}").andExpect(status().isOk());
    }

    private long cardId(String name) {
        return jdbc.queryForObject("select id from card where name = ?", Long.class, name);
    }

    private void card(String name) {
        jdbc.update("""
                insert into card (passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (array[(select coalesce(max(id), 0) + 1000 from card)]::bigint[], ?, 'Effect Monster', 'effect', 'Fiend', 'text', '2002-03-08', '{}', 'h')""",
                name);
    }

    private String l(String path) { return "/api/leagues/" + league + path; }
    private String s(String path) { return l("/seasons/" + season + path); }

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
