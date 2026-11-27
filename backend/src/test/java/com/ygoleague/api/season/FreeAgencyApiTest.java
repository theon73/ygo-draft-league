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
class FreeAgencyApiTest {

    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCD-_0123";
    static final String RULES = "\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"";

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;
    @MockitoBean DiscordWebhook discord;

    AppUser yugi, kaiba;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate season_transaction_line, season_transaction, league_notification, draft_timer_change, draft_pause, draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        for (int i = 1; i <= 20; i++) card("Card " + i);
        org.mockito.Mockito.when(discord.post(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DiscordWebhook.Delivery(true, 204, null));
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba))).andExpect(status().isOk());
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");

        // Seats: P1 (placeholder), Kaiba, P3 (placeholder); 2 rounds of 1 pick = 6 picks, 1 claim a week.
        long seasonId = jdbc.queryForObject("select id from season", Long.class);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P1', 'MEMBER' from season returning id", Long.class), 1, seasonId);
        seat(jdbc.queryForObject("select id from league_member where display_name = 'Kaiba'", Long.class), 2, seasonId);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P3', 'MEMBER' from season returning id", Long.class), 3, seasonId);
        send(put(s("/draft/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]}").andExpect(status().isOk());
        send(put(s("/rules")), yugi, "{" + RULES + ",\"freeAgencyPerWeek\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.freeAgencyPerWeek").value(1));
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
    }

    /** Runs the whole draft: P1 gets Cards 1 and 6, Kaiba 2 and 5, P3 3 and 4. */
    private void draft() throws Exception {
        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        for (int i = 1; i <= 6; i++) pick(i == 2 || i == 5 ? kaiba : yugi, "Card " + i, i);
    }

    @Test
    void aClaimSwapsAFreeAgentForACardOnTheRoster() throws Exception {
        mvc.perform(get(s("/free-agency")).with(as(kaiba))).andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.closedReason").value("Free agency opens when the draft is complete"));
        claim(kaiba, null, "Card 10", "Card 2").andExpect(status().isConflict());
        draft();
        notifyOn("TRANSACTIONS");

        // Week 0 (the season starts in 2030) counts toward week 1's allowance.
        mvc.perform(get(s("/free-agency")).with(as(kaiba))).andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.week").value(0))
                .andExpect(jsonPath("$.budgetWeek").value(1))
                .andExpect(listAt("$.players[*].displayName", List.of("Kaiba")))
                .andExpect(jsonPath("$.players[0].left").value(1));
        mvc.perform(get(s("/free-agency")).with(as(yugi)))   // a commissioner can claim for anyone
                .andExpect(listAt("$.players[*].displayName", List.of("P1", "Kaiba", "P3")));

        claim(kaiba, null, "Card 1", "Card 2").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Card 1 belongs to P1"));
        claim(kaiba, null, "Card 10", "Card 1").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("You don't hold the card to drop"));
        claim(kaiba, null, "Card 10", "Card 2").andExpect(status().isCreated())
                .andExpect(jsonPath("$.player").value("Kaiba"))
                .andExpect(listAt("$.added[*].name", List.of("Card 10")))
                .andExpect(listAt("$.dropped[*].name", List.of("Card 2")))
                .andExpect(jsonPath("$.week").value(0))
                .andExpect(jsonPath("$.onBehalfBy").doesNotExist());
        Assertions.assertTrue(posts().getLast().contains("**Kaiba** claimed **Card 10** and dropped **Card 2** · before week 1"), posts().getLast());

        // The roster keeps its size; the dropped card is a free agent again, with its history.
        mvc.perform(get(s("/rosters")).with(as(kaiba)))
                .andExpect(listAt("$.players[1].cards[*].card.name", List.of("Card 10", "Card 5")))
                .andExpect(jsonPath("$.players[1].cards[0].source").value("FREE_AGENCY"))
                .andExpect(jsonPath("$.freeAgents").value(14));
        mvc.perform(get(s("/cards/" + cardId("Card 2"))).with(as(kaiba)))
                .andExpect(jsonPath("$.owners.length()").value(0))
                .andExpect(listAt("$.history[*].source", List.of("DRAFT", "FREE_AGENCY")))
                .andExpect(jsonPath("$.history[1].from").value("Kaiba"))
                .andExpect(jsonPath("$.history[1].to").doesNotExist())
                .andExpect(jsonPath("$.history[1].onBehalfBy").doesNotExist());

        // One claim a week: used up until week 2.
        claim(kaiba, null, "Card 11", "Card 5").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You've used all 1 free-agent claim for week 1; they reset when week 2 starts"));
        mvc.perform(get(s("/free-agency")).with(as(kaiba))).andExpect(jsonPath("$.players[0].left").value(0));

        // Only commissioners claim for others. A dropped card can be claimed straight away.
        claim(kaiba, memberId("P1"), "Card 11", "Card 1").andExpect(status().isForbidden());
        claim(yugi, memberId("P1"), "Card 2", "Card 1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.player").value("P1"))
                .andExpect(jsonPath("$.onBehalfBy").value("Yugi"));
        mvc.perform(get(s("/transactions")).with(as(kaiba)))
                .andExpect(listAt("$[*].player", List.of("P1", "Kaiba")))
                .andExpect(listAt("$[*].kind", List.of("FREE_AGENCY", "FREE_AGENCY")));
        mvc.perform(get(s("/cards/" + cardId("Card 2"))).with(as(kaiba)))
                .andExpect(listAt("$.owners[*].displayName", List.of("P1")))
                .andExpect(jsonPath("$.history[2].onBehalfBy").value("Yugi"));
        Assertions.assertEquals(6, jdbc.queryForObject("select count(*) from card_holding", Integer.class));

        // A claim made in another week doesn't count toward this one.
        jdbc.update("update season_transaction set week = 2 where season_player_id = (select sp.id from season_player sp join league_member m on m.id = sp.league_member_id where m.display_name = 'Kaiba')");
        claim(kaiba, null, "Card 11", "Card 5").andExpect(status().isCreated());
    }

    @Test
    void softBannedAndForbiddenCardsCantBeClaimed() throws Exception {
        long list = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/x', 'List', '2008-03-01') returning id", Long.class);
        long rev = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'x') returning id", Long.class, list);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) values (?, ?, 'FORBIDDEN')", rev, cardId("Card 12"));
        send(put(s("/rules")), yugi, "{" + RULES + ",\"banListRevisionId\":" + rev + "}").andExpect(status().isOk());   // no limit now
        jdbc.update("update pool_card set soft_banned = true where card_id = ?", cardId("Card 13"));
        draft();

        claim(kaiba, null, "Card 12", "Card 2").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Card 12 is Forbidden, so it can't be claimed"));
        claim(kaiba, null, "Card 13", "Card 2").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Card 13 is soft-banned, so it can't be claimed"));
        // Unlimited: several claims in a row.
        claim(kaiba, null, "Card 14", "Card 2").andExpect(status().isCreated());
        claim(kaiba, null, "Card 15", "Card 14").andExpect(status().isCreated());
        mvc.perform(get(s("/free-agency")).with(as(kaiba))).andExpect(jsonPath("$.players[0].left").doesNotExist())
                .andExpect(jsonPath("$.players[0].used").value(2));
    }

    @Test
    void aMidSeasonLimitChangeAppliesFromItsWeek() throws Exception {
        draft();
        // Saved now, in effect from week 2: week 1's allowance (and week 0's) stays at 1.
        send(put(s("/rules")), yugi, "{" + RULES + ",\"freeAgencyPerWeek\":3,\"effectiveFromWeek\":2}").andExpect(status().isOk());
        mvc.perform(get(s("/free-agency")).with(as(kaiba))).andExpect(jsonPath("$.limit").value(1));
        send(put(s("/rules")), yugi, "{" + RULES + ",\"freeAgencyPerWeek\":2,\"effectiveFromWeek\":2}").andExpect(status().isOk());
        send(put(s("/rules")), yugi, "{" + RULES + ",\"freeAgencyPerWeek\":51,\"effectiveFromWeek\":2}").andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    private ResultActions claim(AppUser user, String memberId, String claim, String drop) throws Exception {
        return send(post(s("/free-agency/claims")), user, "{" + (memberId == null ? "" : "\"memberId\":\"" + memberId + "\",")
                + "\"claimCardId\":" + cardId(claim) + ",\"dropCardId\":" + cardId(drop) + "}");
    }

    private String memberId(String name) throws Exception {
        return JsonPath.read(mvc.perform(get(s("")).with(as(yugi))).andReturn().getResponse().getContentAsString(),
                "$.participants[?(@.displayName == '" + name + "')].memberId").toString().replaceAll("[\\[\\]\"]", "");
    }

    private void notifyOn(String... events) throws Exception {
        String list = String.join(",", java.util.Arrays.stream(events).map(e -> "\"" + e + "\"").toList());
        send(put(l("/notifications")), yugi, "{\"webhookUrl\":\"" + WEBHOOK + "\",\"events\":[" + list + "]}").andExpect(status().isOk());
    }

    private List<String> posts() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(discord, org.mockito.Mockito.atLeast(0))
                .post(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), captor.capture(), org.mockito.ArgumentMatchers.any());
        return captor.getAllValues();
    }

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
