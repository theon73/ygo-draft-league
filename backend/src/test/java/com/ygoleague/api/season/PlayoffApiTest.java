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
import com.ygoleague.season.schedule.ResultService;
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

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false", "app.draft.timer.enabled=false",
        "app.notifications.synchronous=true", "app.results.auto-confirm.enabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PlayoffApiTest {

    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCD-_0123";
    static final String RULES = "\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"";
    /** Strongest first: the regular season goes exactly this way, so these are the seeds. */
    static final List<String> STRENGTH = List.of("P1", "P2", "Kaiba", "Joey", "P5", "P6");

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResultService results;
    @MockitoBean CardImageService images;
    @MockitoBean DiscordWebhook discord;

    AppUser yugi, kaiba, joey;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate playoff_seed, match_report, season_match, schedule_draw, stage, trade_message, trade_offer_asset, trade_offer, trade_thread, "
                + "season_transaction_allowance, season_transaction_line, season_transaction, "
                + "league_notification, draft_timer_change, draft_pause, draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        for (int i = 1; i <= 20; i++) card("Card " + i);
        org.mockito.Mockito.when(discord.post(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DiscordWebhook.Delivery(true, 204, null));
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        joey = users.save(new AppUser("Joey"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        for (AppUser u : List.of(kaiba, joey)) {
            String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
            mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(u))).andExpect(status().isOk());
        }
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");
        long seasonId = jdbc.queryForObject("select id from season", Long.class);
        int seat = 1;
        for (String name : STRENGTH) {
            long member = name.startsWith("P")
                    ? jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, ?, 'MEMBER' from season returning id", Long.class, name)
                    : jdbc.queryForObject("select id from league_member where display_name = ?", Long.class, name);
            seat(member, seat++, seasonId);
        }
        send(put(s("/draft/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]}").andExpect(status().isOk());
    }

    private void ready(String playoffs) throws Exception {
        send(put(s("/rules")), yugi, "{" + RULES + ",\"freeAgencyPerWeek\":1" + playoffs + "}").andExpect(status().isOk());
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        for (int i = 1; i <= 12; i++) pick(yugi, "Card " + i, i);   // a commissioner can pick for every seat
    }

    /** A commissioner enters every regular-season result: the stronger player wins 2-0. */
    private void playRegularSeason() throws Exception {
        String body = mvc.perform(get(s("/schedule")).with(as(yugi))).andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> all = JsonPath.read(body, "$.weeks[*].matches[*]");
        for (var m : all) {
            if (m.get("b") == null) continue;
            String a = (String) ((java.util.Map<?, ?>) m.get("a")).get("name");
            String b = (String) ((java.util.Map<?, ?>) m.get("b")).get("name");
            boolean aWins = STRENGTH.indexOf(a) < STRENGTH.indexOf(b);
            send(post(s("/matches/" + m.get("id") + "/result")), yugi, "{\"gamesA\":" + (aWins ? 2 : 0) + ",\"gamesB\":" + (aWins ? 0 : 2) + "}")
                    .andExpect(status().isNoContent());
        }
    }

    private String playoffs(AppUser user) throws Exception {
        return mvc.perform(get(s("/playoffs")).with(as(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String matchId(String body, String label) {
        List<String> ids = JsonPath.read(body, "$.matches[?(@.label == '" + label + "')].id");
        return ids.getFirst();
    }

    private ResultActions enter(String matchId, String json) throws Exception {
        return send(post(s("/matches/" + matchId + "/result")), yugi, json);
    }

    @Test
    void anNbaStylePlayInFeedsTheBracketAndTheFinalCrownsAChampion() throws Exception {
        ready(",\"playoffsEnabled\":true,\"playoffDirectQualifiers\":2,\"playInTeams\":4,\"playInBestOf\":1");
        notifyOn("RESULTS");
        String before = playoffs(yugi);
        Assertions.assertEquals("Seeds 1-2 qualify directly; 3-6 play in for 2 spots; 4 in the bracket.", JsonPath.read(before, "$.shape"));
        Assertions.assertEquals(Boolean.FALSE, JsonPath.read(before, "$.canStart"));
        Assertions.assertEquals("15 regular-season matches don't have a final result yet", JsonPath.read(before, "$.why"));
        send(post(s("/playoffs/start")), yugi, null).andExpect(status().isConflict());

        playRegularSeason();
        mvc.perform(get(s("/playoffs")).with(as(kaiba)))   // a preview of the seeds from the standings
                .andExpect(listAt("$.seeds[*].player.name", STRENGTH))
                .andExpect(listAt("$.seeds[*].playIn", List.of(false, false, true, true, true, true)))
                .andExpect(jsonPath("$.canStart").value(false))   // commissioners start it
                // ...and the bracket they'd make, to draw before the start (YGO-30).
                .andExpect(listAt("$.preview[*].label", List.of("Play-in: 3 v 4", "Play-in: 5 v 6", "Play-in: for the 4th seed", "Semifinal", "Semifinal", "Final")))
                .andExpect(listAt("$.preview[0:2].a.name", List.of("Kaiba", "P5")))
                .andExpect(jsonPath("$.preview[3].aSource.kind").value("SEED"))
                .andExpect(jsonPath("$.preview[3].bSource.matchId").value("preview-2"))
                .andExpect(jsonPath("$.preview[0].canReport").value(false));
        send(post(s("/playoffs/start")), kaiba, null).andExpect(status().isForbidden());
        String body = mvc.perform(post(s("/playoffs/start")).with(csrf()).with(as(yugi))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Assertions.assertTrue(posts().getLast().contains("The playoffs have started"), posts().getLast());
        Assertions.assertEquals(List.of(), JsonPath.read(body, "$.preview"));   // the real bracket replaces it

        // The bracket: play-in 3 v 4 and 5 v 6 are ready (with a week each); the rest wait for results.
        Assertions.assertEquals(List.of("Play-in: 3 v 4", "Play-in: 5 v 6", "Play-in: for the 4th seed", "Semifinal", "Semifinal", "Final"),
                JsonPath.read(body, "$.matches[*].label"));
        Assertions.assertEquals(List.of("Kaiba", "P5"), JsonPath.read(body, "$.matches[0:2].a.name"));
        Assertions.assertEquals(List.of(1, 1, 1, 3, 3, 3), JsonPath.read(body, "$.matches[*].bestOf"));
        Assertions.assertEquals("Loser of Play-in: 3 v 4", JsonPath.read(body, "$.matches[2].aFrom"));
        Assertions.assertEquals("P1", JsonPath.read(body, "$.matches[3].a.name"));
        Assertions.assertEquals("Winner of Play-in: for the 4th seed", JsonPath.read(body, "$.matches[3].bFrom"));
        // The same sources as data, for drawing the bracket (YGO-30).
        Assertions.assertEquals("LOSER", JsonPath.read(body, "$.matches[2].aSource.kind"));
        Assertions.assertEquals(JsonPath.<String>read(body, "$.matches[0].id"), JsonPath.read(body, "$.matches[2].aSource.matchId"));
        Assertions.assertEquals("SEED", JsonPath.read(body, "$.matches[3].aSource.kind"));
        Assertions.assertEquals(1, (int) JsonPath.read(body, "$.matches[3].aSource.seed"));
        Assertions.assertEquals("WINNER", JsonPath.read(body, "$.matches[3].bSource.kind"));
        Assertions.assertEquals(JsonPath.<String>read(body, "$.matches[2].id"), JsonPath.read(body, "$.matches[3].bSource.matchId"));
        Assertions.assertNotNull(JsonPath.read(body, "$.matches[0].deadline"));

        // Trades and free agency close; regular-season results are frozen.
        mvc.perform(get(s("/free-agency")).with(as(kaiba))).andExpect(jsonPath("$.closedReason").value("Trades and free agency are closed for the playoffs"));
        send(put(s("/rules")), yugi, "{" + RULES + ",\"freeAgencyPerWeek\":1,\"playoffsEnabled\":true,\"playoffDirectQualifiers\":4,\"effectiveFromWeek\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("The playoffs have started, so their settings are fixed"));
        mvc.perform(get(s("/trades")).with(as(kaiba))).andExpect(jsonPath("$.open").value(false));
        String anyRegular = JsonPath.read(mvc.perform(get(s("/schedule")).with(as(yugi))).andReturn().getResponse().getContentAsString(),
                "$.weeks[0].matches[0].id");
        enter(anyRegular, "{\"gamesA\":2,\"gamesB\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The playoffs have started, so regular-season results are final"));

        // Play-in 3 v 4 (best of 1): Joey reports Kaiba 1-0, Kaiba confirms; Kaiba is the 3 seed in the semifinal against P2.
        String pi1 = matchId(body, "Play-in: 3 v 4");
        send(post(s("/matches/" + pi1 + "/report")), joey, "{\"gamesA\":1,\"gamesB\":1}").andExpect(status().isBadRequest());   // needs a winner
        send(post(s("/matches/" + pi1 + "/report")), joey, "{\"gamesA\":1,\"gamesB\":0}").andExpect(status().isNoContent());
        send(post(s("/matches/" + pi1 + "/confirm")), kaiba, null).andExpect(status().isNoContent());
        body = playoffs(kaiba);
        Assertions.assertEquals(List.of("P2", "Kaiba"), List.of(JsonPath.read(body, "$.matches[4].a.name"), JsonPath.read(body, "$.matches[4].b.name")));
        Assertions.assertEquals("Joey", JsonPath.read(body, "$.matches[2].a.name"));   // the loser plays again

        // A correction moves the right player on while the next match has no result.
        enter(pi1, "{\"gamesA\":0,\"gamesB\":1}").andExpect(status().isNoContent());   // Joey won after all
        body = playoffs(kaiba);
        Assertions.assertEquals("Joey", JsonPath.read(body, "$.matches[4].b.name"));
        Assertions.assertEquals("Kaiba", JsonPath.read(body, "$.matches[2].a.name"));

        enter(matchId(body, "Play-in: 5 v 6"), "{\"forfeit\":\"BOTH\"}").andExpect(status().isBadRequest());   // someone goes through
        enter(matchId(body, "Play-in: 5 v 6"), "{\"gamesA\":1,\"gamesB\":0}").andExpect(status().isNoContent());   // P5
        enter(matchId(body, "Play-in: for the 4th seed"), "{\"gamesA\":1,\"gamesB\":0}").andExpect(status().isNoContent());   // Kaiba
        String semi2 = JsonPath.<List<String>>read(playoffs(yugi), "$.matches[?(@.label == 'Semifinal')].id").get(1);
        enter(semi2, "{\"gamesA\":2,\"gamesB\":1}").andExpect(status().isNoContent());   // P2 beats Joey
        enter(pi1, "{\"gamesA\":1,\"gamesB\":0}").andExpect(status().isConflict());       // too late: the semifinal has a result
        String semi1 = JsonPath.<List<String>>read(playoffs(yugi), "$.matches[?(@.label == 'Semifinal')].id").get(0);
        enter(semi1, "{\"gamesA\":0,\"gamesB\":2}").andExpect(status().isNoContent());   // Kaiba beats P1
        body = playoffs(yugi);
        Assertions.assertEquals(List.of("Kaiba", "P2"), List.of(JsonPath.read(body, "$.matches[5].a.name"), JsonPath.read(body, "$.matches[5].b.name")));

        send(post(s("/matches/" + matchId(body, "Final") + "/report")), kaiba, "{\"gamesA\":2,\"gamesB\":1}").andExpect(status().isNoContent());
        Assertions.assertEquals(1, results.autoConfirmDue(java.time.Instant.now().plus(java.time.Duration.ofDays(3))));
        mvc.perform(get(s("/playoffs")).with(as(joey)))
                .andExpect(jsonPath("$.champion.name").value("Kaiba"))
                .andExpect(jsonPath("$.complete").value(true));
        Assertions.assertEquals("COMPLETE", jdbc.queryForObject("select status from season", String.class));
        Assertions.assertTrue(posts().getLast().contains("**Kaiba** won the final and is the S1 champion"), posts().getLast());

        // Next season (YGO-39): copied with its players, its seats can be the reverse of the final placement:
        // the champion picks last, the final's loser just before, everyone else in reverse standings order.
        List<String> standings = JsonPath.read(mvc.perform(get(s("/standings")).with(as(yugi))).andReturn().getResponse().getContentAsString(),
                "$.rows[*].name");
        List<String> placement = new java.util.ArrayList<>(List.of("Kaiba", "P2"));
        standings.stream().filter(n -> !placement.contains(n)).forEach(placement::add);
        java.util.Collections.reverse(placement);
        String s2 = JsonPath.read(send(post(l("/seasons")), yugi, "{\"name\":\"S2\",\"copyFrom\":\"" + season + "\",\"copyPlayers\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get(l("/seasons/" + season)).with(as(joey))).andExpect(jsonPath("$.next.name").value("S2"));
        mvc.perform(get(l("/seasons/" + s2)).with(as(joey)))
                .andExpect(jsonPath("$.copiedFrom.name").value("S1")).andExpect(jsonPath("$.copiedFrom.status").value("COMPLETE"));
        send(post(l("/seasons/" + s2 + "/seats/reverse-standings")), joey, null).andExpect(status().isForbidden());
        String detail = send(post(l("/seasons/" + s2 + "/seats/reverse-standings")), yugi, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastDraw.method").value("REVERSE_STANDINGS"))
                .andExpect(jsonPath("$.lastDraw.basedOn").value("S1"))
                .andReturn().getResponse().getContentAsString();
        Assertions.assertEquals(placement, JsonPath.read(detail, "$.participants[*].displayName"));
        Assertions.assertEquals(placement, JsonPath.read(detail, "$.lastDraw.order"));
    }

    @Test
    void withoutPlayoffsACommissionerCompletesTheSeasonAndTheLeaderIsChampion() throws Exception {
        ready("");
        mvc.perform(get(s("/playoffs")).with(as(yugi))).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.canComplete").value(false));
        send(post(s("/playoffs/complete")), yugi, null).andExpect(status().isConflict());
        playRegularSeason();
        send(post(s("/playoffs/complete")), kaiba, null).andExpect(status().isForbidden());
        send(post(s("/playoffs/complete")), yugi, null).andExpect(status().isOk()).andExpect(jsonPath("$.champion.name").value("P1"));
        send(post(s("/playoffs/start")), yugi, null).andExpect(status().isConflict());
    }

    @Test
    void playoffSettingsAreValidated() throws Exception {
        send(put(s("/rules")), yugi, "{" + RULES + ",\"playoffsEnabled\":true}").andExpect(status().isBadRequest());
        send(put(s("/rules")), yugi, "{" + RULES + ",\"playoffsEnabled\":true,\"playoffDirectQualifiers\":4,\"playInTeams\":3}").andExpect(status().isBadRequest());
        send(put(s("/rules")), yugi, "{" + RULES + ",\"playoffsEnabled\":true,\"playoffDirectQualifiers\":1}").andExpect(status().isBadRequest());
        send(put(s("/rules")), yugi, "{" + RULES + ",\"playoffsEnabled\":true,\"playoffDirectQualifiers\":4,\"playoffBestOf\":4}").andExpect(status().isBadRequest());
        send(put(s("/rules")), yugi, "{" + RULES + ",\"playoffsEnabled\":true,\"playoffDirectQualifiers\":4,\"playInTeams\":4,\"playoffBestOf\":5}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.playoffs.bestOf").value(5));
        mvc.perform(get(s("/playoffs")).with(as(kaiba)))
                .andExpect(jsonPath("$.playoffBestOf").value(5)).andExpect(jsonPath("$.playInBestOf").value(5))   // the play-in follows the playoffs
                .andExpect(jsonPath("$.shape").value("Seeds 1-4 qualify directly; 5-8 play in for 2 spots; 6 in the bracket, seeds 1 and 2 have byes."));
    }

    // ---- helpers ----

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
