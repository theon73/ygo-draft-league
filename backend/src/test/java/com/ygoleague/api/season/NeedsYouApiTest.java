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
/** "Needs you" (YGO-31): setup, the pick, reports and confirmations, disputes, across leagues and per season. */
class NeedsYouApiTest {

    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCD-_0123";
    static final String CALENDAR = "\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\"";

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
        jdbc.execute("truncate match_report, season_match, schedule_draw, stage, trade_message, trade_offer_asset, trade_offer, trade_thread, season_transaction_allowance, season_transaction_line, season_transaction, "
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

    private void ready(String extra, String startDate) throws Exception {
        send(put(s("/rules")), yugi, "{\"startDate\":\"" + startDate + "\"," + CALENDAR + extra + "}").andExpect(status().isOk());
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        AppUser[] by = {yugi, kaiba, yugi, joey, joey, yugi, kaiba, yugi};
        for (int i = 1; i <= 8; i++) pick(by[i - 1], "Card " + i, i);
    }

    /** The match between two players: its id, and whether {@code first} is its player A. */
    private record M(String id, boolean firstIsA) {}

    private M match(String first, String second) throws Exception {
        String body = mvc.perform(get(s("/schedule")).with(as(yugi))).andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> all = JsonPath.read(body, "$.weeks[*].matches[*]");
        for (var m : all) {
            if (m.get("b") == null) continue;
            String a = (String) ((java.util.Map<?, ?>) m.get("a")).get("name");
            String b = (String) ((java.util.Map<?, ?>) m.get("b")).get("name");
            if (a.equals(first) && b.equals(second)) return new M((String) m.get("id"), true);
            if (a.equals(second) && b.equals(first)) return new M((String) m.get("id"), false);
        }
        throw new AssertionError("no match " + first + " v " + second);
    }

    /** Games for {@code first} and {@code second}, in the match's a / b order. */
    private String games(M m, int first, int second) {
        int a = m.firstIsA() ? first : second;
        int b = m.firstIsA() ? second : first;
        return "\"gamesA\":" + a + ",\"gamesB\":" + b;
    }

    private ResultActions report(AppUser user, M m, int first, int second) throws Exception {
        return send(post(s("/matches/" + m.id() + "/report")), user, "{" + games(m, first, second) + "}");
    }

    private List<?> standing(String path) throws Exception {
        return JsonPath.read(mvc.perform(get(s("/standings")).with(as(kaiba))).andReturn().getResponse().getContentAsString(), path);
    }

    @Test
    void setupNeedsTheCommissionerOnly() throws Exception {
        mvc.perform(get("/api/me/needs-you").with(as(yugi)))
                .andExpect(listAt("$[*].kind", List.of("FINISH_SETUP")))
                .andExpect(jsonPath("$[0].tab").value("draft"))
                .andExpect(jsonPath("$[0].commissioner").value(true));
        mvc.perform(get("/api/me/needs-you").with(as(kaiba))).andExpect(listAt("$[*].kind", List.of()));
    }

    @Test
    void yourPickIsYoursAlone() throws Exception {
        send(put(s("/rules")), yugi, "{\"startDate\":\"2030-01-07\"," + CALENDAR + "}").andExpect(status().isOk());
        send(post(s("/pool/generate")), yugi, null).andExpect(status().isOk());
        mvc.perform(get(s("/needs-you")).with(as(yugi))).andExpect(listAt("$[*].kind", List.of("START_DRAFT")));
        send(post(s("/draft/start")), yugi, null).andExpect(status().isOk());
        pick(yugi, "Card 1", 1);   // P1's pick; Kaiba is up next
        mvc.perform(get("/api/me/needs-you").with(as(kaiba)))
                .andExpect(listAt("$[*].kind", List.of("PICK")))
                .andExpect(jsonPath("$[0].title").value("It's your pick"))
                .andExpect(jsonPath("$[0].detail").value("Pick #2 · round 1"))
                .andExpect(jsonPath("$[0].league").value("Battle City"));
        mvc.perform(get("/api/me/needs-you").with(as(joey))).andExpect(listAt("$[*].kind", List.of()));
        mvc.perform(get("/api/me/needs-you").with(as(yugi))).andExpect(listAt("$[*].kind", List.of()));   // a commissioner can pick, but it isn't theirs
    }

    @Test
    void resultsToReportConfirmAndSettle() throws Exception {
        ready("", java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusWeeks(4).toString());   // every week is past
        mvc.perform(get(s("/needs-you")).with(as(kaiba)))
                .andExpect(listAt("$[*].kind", List.of("REPORT", "REPORT", "REPORT")));
        M kj = match("Kaiba", "Joey");
        report(joey, kj, 2, 1).andExpect(status().isNoContent());
        mvc.perform(get(s("/needs-you")).with(as(kaiba)))
                .andExpect(jsonPath("$[?(@.kind == 'CONFIRM')].title").value("Confirm Joey's report"))
                .andExpect(jsonPath("$[?(@.kind == 'CONFIRM')].matchId").value(kj.id()));
        mvc.perform(get(s("/needs-you")).with(as(joey)))   // reported: nothing more for Joey on that match
                .andExpect(jsonPath("$[?(@.matchId == '" + kj.id() + "')]").isEmpty());
        send(post(s("/matches/" + kj.id() + "/dispute")), kaiba, "{\"note\":\"It was 2-0 to me\"}").andExpect(status().isNoContent());
        mvc.perform(get(s("/needs-you")).with(as(yugi)))
                .andExpect(jsonPath("$[?(@.kind == 'DISPUTE')].detail").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("Kaiba"))));
        mvc.perform(get(s("")).with(as(kaiba))).andExpect(jsonPath("$.weekCount").value(3)).andExpect(jsonPath("$.currentWeek").value(4));
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
