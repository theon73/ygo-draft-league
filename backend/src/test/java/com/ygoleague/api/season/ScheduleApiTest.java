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
class ScheduleApiTest {

    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCD-_0123";
    static final String CALENDAR = "\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"";

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;
    @MockitoBean DiscordWebhook discord;

    AppUser yugi, kaiba, joey;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate season_match, schedule_draw, stage, trade_message, trade_offer_asset, trade_offer, trade_thread, season_transaction_allowance, season_transaction_line, season_transaction, "
                + "league_notification, draft_timer_change, draft_pause, draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        for (int i = 1; i <= 20; i++) card("Card " + i);
        org.mockito.Mockito.when(discord.post(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new DiscordWebhook.Delivery(true, 204, null));
        yugi = users.save(new AppUser("Yugi"));     // the owner: commissions, doesn't play
        kaiba = users.save(new AppUser("Kaiba"));
        joey = users.save(new AppUser("Joey"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        for (AppUser u : List.of(kaiba, joey)) {
            String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
            mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(u))).andExpect(status().isOk());
        }
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");

        // Seats: P1 (placeholder), Kaiba, P3 (placeholder), Joey; 2 rounds of 1 pick.
        long seasonId = jdbc.queryForObject("select id from season", Long.class);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P1', 'MEMBER' from season returning id", Long.class), 1, seasonId);
        seat(jdbc.queryForObject("select id from league_member where display_name = 'Kaiba'", Long.class), 2, seasonId);
        seat(jdbc.queryForObject("insert into league_member (league_id, display_name, role) select league_id, 'P3', 'MEMBER' from season returning id", Long.class), 3, seasonId);
        seat(jdbc.queryForObject("select id from league_member where display_name = 'Joey'", Long.class), 4, seasonId);
        send(put(s("/draft/settings")), yugi, "{\"order\":\"SNAKE\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}]}").andExpect(status().isOk());
    }

    /** Rules, pool and the whole draft (2 rounds), which draws the schedule. */
    private void ready(String extra, String startDate) throws Exception {
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + startDate + "\"," + CALENDAR + extra + "}").andExpect(status().isOk());
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        AppUser[] by = {yugi, kaiba, yugi, joey, joey, yugi, kaiba, yugi};
        for (int i = 1; i <= 8; i++) pick(by[i - 1], "Card " + i, i);
    }

    @Test
    void theScheduleIsDrawnWhenTheDraftCompletesAndCanBeRedrawnBeforeWeek1() throws Exception {
        mvc.perform(get(s("/schedule")).with(as(yugi))).andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(false))
                .andExpect(jsonPath("$.canGenerate").value(false))
                .andExpect(jsonPath("$.why").value("The schedule is drawn when the draft completes"));
        ready("", "2030-01-07");

        // 4 players, single round-robin: 3 weeks of 2 matches, every pair once.
        String body = mvc.perform(get(s("/schedule")).with(as(kaiba))).andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(true))
                .andExpect(jsonPath("$.drawnBy").doesNotExist())
                .andExpect(jsonPath("$.draws").value(1))
                .andExpect(jsonPath("$.canGenerate").value(false))        // members don't
                .andExpect(listAt("$.weeks[*].week", List.of(1, 2, 3)))
                .andExpect(listAt("$.weeks[*].bestOf", List.of(3, 3, 3)))
                .andExpect(listAt("$.weeks[*].current", List.of(true, false, false)))   // before week 1, week 1 is up next
                .andExpect(jsonPath("$.weeks[0].start").exists())
                .andReturn().getResponse().getContentAsString();
        List<String> pairs = new java.util.ArrayList<>();
        for (int w = 0; w < 3; w++) {
            List<String> a = JsonPath.read(body, "$.weeks[" + w + "].matches[*].a.name");
            List<String> b = JsonPath.read(body, "$.weeks[" + w + "].matches[*].b.name");
            Assertions.assertEquals(2, a.size());
            for (int i = 0; i < 2; i++) pairs.add(java.util.stream.Stream.of(a.get(i), b.get(i)).sorted().reduce((x, y) -> x + "-" + y).orElseThrow());
        }
        Assertions.assertEquals(List.of("Joey-Kaiba", "Joey-P1", "Joey-P3", "Kaiba-P1", "Kaiba-P3", "P1-P3"), pairs.stream().sorted().toList());
        List<Boolean> mine = JsonPath.read(body, "$.weeks[*].matches[?(@.mine == true)].mine");
        Assertions.assertEquals(3, mine.size(), "Kaiba plays once a week");

        // Only commissioners redraw, and only before week 1.
        send(post(s("/schedule/draw")), kaiba, null).andExpect(status().isForbidden());
        send(post(s("/schedule/draw")), yugi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.draws").value(2))
                .andExpect(jsonPath("$.drawnBy").value("Yugi"));
        Assertions.assertEquals(6, jdbc.queryForObject("select count(*) from season_match", Integer.class));
        Assertions.assertEquals(2, jdbc.queryForObject("select count(*) from schedule_draw", Integer.class));
    }

    @Test
    void onceWeek1BeginsTheScheduleIsFixedAndBestOfFollowsTheRulesByWeek() throws Exception {
        java.time.LocalDate start = java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusWeeks(1);
        ready("", start.toString());
        mvc.perform(get(s("/schedule")).with(as(yugi)))
                .andExpect(jsonPath("$.canGenerate").value(false))
                .andExpect(jsonPath("$.why").value("Week 1 has begun, so the schedule is fixed"));
        send(post(s("/schedule/draw")), yugi, null).andExpect(status().isConflict());

        // Best of 5 from week 3; the format itself can't change.
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + start + "\"," + CALENDAR + ",\"matchBestOf\":5,\"effectiveFromWeek\":3}").andExpect(status().isOk());
        mvc.perform(get(s("/schedule")).with(as(kaiba))).andExpect(listAt("$.weeks[*].bestOf", List.of(3, 3, 5)));
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + start + "\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":2,\"effectiveFromWeek\":3}")
                .andExpect(status().isConflict());
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + start + "\"," + CALENDAR + ",\"matchBestOf\":4,\"effectiveFromWeek\":3}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSeasonFromBeforeSchedulesCanDrawOne() throws Exception {
        java.time.LocalDate start = java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusWeeks(1);
        ready("", start.toString());
        jdbc.execute("delete from season_match; delete from schedule_draw; delete from stage");   // as if it went active before YGO-25
        mvc.perform(get(s("/schedule")).with(as(yugi)))
                .andExpect(jsonPath("$.generated").value(false))
                .andExpect(jsonPath("$.canGenerate").value(true));
        send(post(s("/schedule/draw")), yugi, null).andExpect(status().isOk()).andExpect(jsonPath("$.weeks.length()").value(3));
        send(post(s("/schedule/draw")), yugi, null).andExpect(status().isConflict());   // and then it's fixed (week 1 has begun)
    }

    // ---- helpers ----

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
