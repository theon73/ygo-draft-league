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
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class SeasonApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    AppUser yugi, kaiba, mai, outsider;
    String league;
    String yugiId, kaibaId, maiId, seatId;   // league member public ids; yugi owns the league, seat is a placeholder

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate season_seat_draw, season_rules, season_player, season, league_invite, league_member, ban_list_entry, "
                + "ban_list_revision, ban_list, league, user_identity, app_user restart identity cascade");
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        mai = users.save(new AppUser("Mai"));
        outsider = users.save(new AppUser("Outsider"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        join(kaiba);
        join(mai);
        seatId = read(send(post(l("/members/placeholders")), yugi, "{\"displayName\":\"Joey\"}"), "$.id");
        yugiId = memberId("Yugi");
        kaibaId = memberId("Kaiba");
        maiId = memberId("Mai");
    }

    @Test
    void commissionersCreateSeasonsAndTheLeagueOpensOnTheCurrentOne() throws Exception {
        mvc.perform(get("/api/leagues/" + league).with(as(kaiba)))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.currentSeasonId").doesNotExist());

        String s1 = season("Season 1");
        send(post(l("/seasons")), yugi, "{\"name\":\"season 1\"}").andExpect(status().isConflict());
        send(post(l("/seasons")), yugi, "{\"name\":\"  \"}").andExpect(status().isBadRequest());
        send(post(l("/seasons")), kaiba, "{\"name\":\"Mine\"}").andExpect(status().isForbidden());
        send(post(l("/seasons")), outsider, "{\"name\":\"Mine\"}").andExpect(status().isNotFound());

        jdbc.update("update season set status = 'COMPLETE'");
        String s2 = season("Season 2");
        mvc.perform(get("/api/leagues/" + league).with(as(mai)))
                .andExpect(jsonPath("$.seasons.length()").value(2))
                .andExpect(jsonPath("$.seasons[0].id").value(s2))
                .andExpect(jsonPath("$.currentSeasonId").value(s2));
        mvc.perform(get(l("/seasons")).with(as(mai))).andExpect(jsonPath("$[1].id").value(s1));

        // A running season stays current while the next one is set up (YGO-31).
        jdbc.update("update season set status = 'ACTIVE' where name = 'Season 2'");
        String s3 = season("Season 3");
        mvc.perform(get("/api/leagues/" + league).with(as(mai))).andExpect(jsonPath("$.currentSeasonId").value(s2));
        mvc.perform(get("/api/leagues").with(as(mai))).andExpect(jsonPath("$[0].currentSeason.id").value(s2));
        jdbc.update("update season set status = 'COMPLETE' where name = 'Season 2'");
        mvc.perform(get("/api/leagues/" + league).with(as(mai))).andExpect(jsonPath("$.currentSeasonId").value(s3));
    }

    @Test
    void participantsGetSeatsInOrderAndChangesKeepRelativeOrder() throws Exception {
        String s = season("S1");
        participants(s, yugi, yugiId, kaibaId, seatId)
                .andExpect(status().isOk())
                .andExpect(listAt("$.participants[*].displayName", List.of("Yugi", "Kaiba", "Joey")))
                .andExpect(listAt("$.participants[*].seat", List.of(1, 2, 3)))
                .andExpect(jsonPath("$.participants[2].placeholder").value(true));

        // Kaiba leaves: the rest close up, keeping their order.
        participants(s, yugi, seatId, yugiId)
                .andExpect(listAt("$.participants[*].displayName", List.of("Yugi", "Joey")))
                .andExpect(listAt("$.participants[*].seat", List.of(1, 2)));
        participants(s, yugi, yugiId, seatId, kaibaId)
                .andExpect(listAt("$.participants[*].displayName", List.of("Yugi", "Joey", "Kaiba")));

        participants(s, yugi, yugiId, yugiId).andExpect(status().isBadRequest());         // duplicate
        participants(s, kaiba, kaibaId).andExpect(status().isForbidden());                // only commissioners manage
        // Members who aren't playing follow the season, without the setup controls.
        mvc.perform(get(l("/seasons/" + s)).with(as(mai)))
                .andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.youPlay").value(false))
                .andExpect(jsonPath("$.candidates.length()").value(0));
        mvc.perform(get(l("/seasons/" + s)).with(as(kaiba))).andExpect(jsonPath("$.youPlay").value(true));
        mvc.perform(get(l("/seasons/" + s)).with(as(yugi)))
                .andExpect(listAt("$.candidates[*].displayName", List.of("Joey", "Kaiba", "Mai", "Yugi")));
    }

    @Test
    void commissionersArePerSeasonAndTheOwnerIsAlwaysOne() throws Exception {
        String s1 = season("S1");
        String s2 = season("S2");
        mvc.perform(get(l("/seasons/" + s1)).with(as(mai)))
                .andExpect(listAt("$.commissioners[*].displayName", List.of("Yugi")))
                .andExpect(jsonPath("$.commissioners[0].owner").value(true));

        commissioners(s1, yugi, kaibaId).andExpect(status().isOk())
                .andExpect(listAt("$.commissioners[*].displayName", List.of("Yugi", "Kaiba")));
        // Kaiba now runs S1 (without having to play in it), but not S2.
        participants(s1, kaiba, yugiId, maiId).andExpect(status().isOk());
        mvc.perform(get(l("/seasons/" + s1)).with(as(kaiba)))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.youPlay").value(false));
        participants(s2, kaiba, kaibaId).andExpect(status().isForbidden());
        // He can't create seasons (that's the league owner's) or manage the group.
        send(post(l("/seasons")), kaiba, "{\"name\":\"S3\"}").andExpect(status().isForbidden());

        commissioners(s1, kaiba, seatId).andExpect(status().isBadRequest());             // placeholders have no account
        commissioners(s1, mai, maiId).andExpect(status().isForbidden());                 // members can't appoint themselves
        // Leaving the owner out doesn't remove them: the owner always commissions.
        commissioners(s1, kaiba, maiId)
                .andExpect(listAt("$.commissioners[*].displayName", List.of("Yugi", "Mai")))
                .andExpect(jsonPath("$.canManage").value(false));                        // Kaiba stepped down
    }

    @Test
    void oneDropdownSetsAMembersSeasonRolesAtOnce() throws Exception {
        String s = season("S1");
        role(s, yugi, kaibaId, true, true).andExpect(status().isOk())
                .andExpect(listAt("$.commissioners[*].displayName", List.of("Yugi", "Kaiba")))
                .andExpect(listAt("$.participants[*].displayName", List.of("Kaiba")));
        role(s, yugi, maiId, false, true)
                .andExpect(listAt("$.participants[*].displayName", List.of("Kaiba", "Mai")))
                .andExpect(listAt("$.participants[*].seat", List.of(1, 2)));
        role(s, yugi, kaibaId, true, false)                                                   // stops playing, still commissions
                .andExpect(listAt("$.participants[*].displayName", List.of("Mai")))
                .andExpect(listAt("$.participants[*].seat", List.of(1)))
                .andExpect(listAt("$.commissioners[*].displayName", List.of("Yugi", "Kaiba")));
        role(s, yugi, yugiId, true, true).andExpect(listAt("$.participants[*].displayName", List.of("Mai", "Yugi")));

        role(s, yugi, yugiId, false, true).andExpect(status().isBadRequest());               // the owner always commissions
        role(s, yugi, seatId, true, false).andExpect(status().isBadRequest());               // placeholders can't commission
        role(s, mai, maiId, true, true).andExpect(status().isForbidden());                   // only commissioners assign roles

        jdbc.update("update season set status = 'DRAFTING'");
        role(s, yugi, maiId, true, true).andExpect(status().isOk());                         // commissioners can change any time
        role(s, yugi, maiId, true, false).andExpect(status().isConflict());                  // players are fixed once started
        role(s, yugi, kaibaId, false, true).andExpect(status().isConflict());
    }

    @Test
    void manualSeatOrderMustListEveryParticipantOnce() throws Exception {
        String s = season("S1");
        participants(s, yugi, yugiId, kaibaId, seatId);
        seats(s, kaibaId, seatId, yugiId)
                .andExpect(status().isOk())
                .andExpect(listAt("$.participants[*].displayName", List.of("Kaiba", "Joey", "Yugi")));
        seats(s, kaibaId, yugiId).andExpect(status().isBadRequest());
        seats(s, kaibaId, yugiId, maiId).andExpect(status().isBadRequest());
    }

    @Test
    void randomizingRecordsTheDrawForEveryoneToSee() throws Exception {
        String s = season("S1");
        send(post(l("/seasons/" + s + "/seats/randomize")), yugi, null).andExpect(status().isBadRequest());   // nobody to draw yet
        participants(s, yugi, yugiId, kaibaId, seatId);

        String body = send(post(l("/seasons/" + s + "/seats/randomize")), yugi, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastDraw.drawnBy").value("Yugi"))
                .andReturn().getResponse().getContentAsString();
        List<String> seated = JsonPath.read(body, "$.participants[*].displayName");
        List<String> drawn = JsonPath.read(body, "$.lastDraw.order");
        org.junit.jupiter.api.Assertions.assertEquals(seated, drawn);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("Yugi", "Kaiba", "Joey"), java.util.Set.copyOf(drawn));

        mvc.perform(get(l("/seasons/" + s)).with(as(mai))).andExpect(jsonPath("$.lastDraw.order").value(drawn));
        send(post(l("/seasons/" + s + "/seats/randomize")), yugi, null);
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject("select count(*) from season_seat_draw", Long.class));
    }

    @Test
    void onceStartedParticipantsAndSeatsAreFixedButTeamNamesAreNot() throws Exception {
        String s = season("S1");
        participants(s, yugi, yugiId, kaibaId);
        jdbc.update("update season set status = 'DRAFTING'");

        participants(s, yugi, yugiId).andExpect(status().isConflict());
        seats(s, kaibaId, yugiId).andExpect(status().isConflict());
        send(post(l("/seasons/" + s + "/seats/randomize")), yugi, null).andExpect(status().isConflict());
        mvc.perform(get(l("/seasons/" + s)).with(as(yugi))).andExpect(jsonPath("$.editable").value(false));
        teamName(s, kaiba, kaibaId, "Blue-Eyes Inc.").andExpect(status().isOk());
    }

    @Test
    void playersNameTheirOwnTeamCommissionersNameAny() throws Exception {
        String s = season("S1");
        participants(s, yugi, yugiId, kaibaId, seatId);

        teamName(s, kaiba, kaibaId, "  Kaiba   Corp ")
                .andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].teamName").value("Kaiba Corp"));
        teamName(s, kaiba, yugiId, "Hijacked").andExpect(status().isForbidden());
        teamName(s, yugi, seatId, "Team Wheeler").andExpect(status().isOk());
        teamName(s, yugi, kaibaId, "x".repeat(41)).andExpect(status().isBadRequest());
        teamName(s, yugi, kaibaId, "").andExpect(listAt("$.participants[?(@.displayName == 'Kaiba')].teamName", java.util.Collections.singletonList(null)));
        teamName(s, yugi, maiId, "Viewers").andExpect(status().isBadRequest());   // not in the season
    }

    @Test
    void seasonsOfOtherLeaguesAreInvisible() throws Exception {
        String s = season("S1");
        String other = read(send(post("/api/leagues"), outsider, "{\"name\":\"Elsewhere\"}"), "$.id");
        mvc.perform(get("/api/leagues/" + other + "/seasons/" + s).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get(l("/seasons/" + s)).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get(l("/seasons/" + league)).with(as(yugi))).andExpect(status().isNotFound());   // a league id isn't a season id
    }

    @Test
    void leagueTimeZoneIsARealRegionZoneSetByCommissioners() throws Exception {
        send(patch("/api/leagues/" + league), yugi, "{\"timeZone\":\"Europe/London\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.timeZone").value("Europe/London"));
        send(patch("/api/leagues/" + league), yugi, "{\"timeZone\":\"Mars/Olympus\"}").andExpect(status().isBadRequest());
        send(patch("/api/leagues/" + league), yugi, "{\"timeZone\":\"+02:00\"}").andExpect(status().isBadRequest());
        send(patch("/api/leagues/" + league), kaiba, "{\"timeZone\":\"Asia/Tokyo\"}").andExpect(status().isForbidden());
        // No zone given on creation means UTC.
        mvc.perform(get("/api/leagues/" + read(send(post("/api/leagues"), kaiba, "{\"name\":\"No Zone\"}"), "$.id")).with(as(kaiba)))
                .andExpect(jsonPath("$.timeZone").value("UTC"));
    }

    @Test
    void commissionersSetWhenWeeksStart() throws Exception {
        mvc.perform(get("/api/leagues/" + league).with(as(kaiba)))
                .andExpect(jsonPath("$.weekStartsOn").value("MONDAY"))
                .andExpect(jsonPath("$.weekStartsAt").value("00:00"))
                .andExpect(jsonPath("$.currentWeek.start").exists())
                .andExpect(jsonPath("$.currentWeek.end").exists());

        // Partial update: the time zone set at creation is kept.
        send(patch("/api/leagues/" + league), yugi, "{\"weekStartsOn\":\"TUESDAY\",\"weekStartsAt\":\"20:30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStartsOn").value("TUESDAY"))
                .andExpect(jsonPath("$.weekStartsAt").value("20:30"))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.currentWeek.start").value(org.hamcrest.Matchers.containsString("T20:30:00")));
        send(patch("/api/leagues/" + league), yugi, "{\"weekStartsAt\":\"25:00\"}").andExpect(status().isBadRequest());
        send(patch("/api/leagues/" + league), yugi, "{\"weekStartsAt\":\"20:30:15\"}").andExpect(status().isBadRequest());
        send(patch("/api/leagues/" + league), yugi, "{\"weekStartsOn\":\"FUNDAY\"}").andExpect(status().is4xxClientError());
        send(patch("/api/leagues/" + league), kaiba, "{\"weekStartsOn\":\"FRIDAY\"}").andExpect(status().isForbidden());
    }

    @Test
    void membersInASeasonCantBeRemovedFromTheLeague() throws Exception {
        participants(season("S1"), yugi, yugiId, kaibaId);
        send(delete(l("/members/" + kaibaId)), yugi, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Kaiba is part of a season")));
    }

    // ---- helpers ----

    /** Asserts a JSON path that yields a list (Spring's jsonPath().value() doesn't handle wildcard paths here). */
    private static org.springframework.test.web.servlet.ResultMatcher listAt(String path, List<?> expected) {
        return result -> org.junit.jupiter.api.Assertions.assertEquals(expected,
                JsonPath.read(result.getResponse().getContentAsString(), path), path);
    }

    private String l(String path) {
        return "/api/leagues/" + league + path;
    }

    private String season(String name) throws Exception {
        return read(send(post(l("/seasons")), yugi, "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()), "$.id");
    }

    private ResultActions participants(String season, AppUser by, String... memberIds) throws Exception {
        return send(put(l("/seasons/" + season + "/participants")), by, membersJson(memberIds));
    }

    private ResultActions role(String season, AppUser by, String memberId, boolean commissioner, boolean player) throws Exception {
        return send(put(l("/seasons/" + season + "/members/" + memberId)), by,
                "{\"commissioner\":" + commissioner + ",\"player\":" + player + "}");
    }

    private ResultActions commissioners(String season, AppUser by, String... memberIds) throws Exception {
        return send(put(l("/seasons/" + season + "/commissioners")), by, membersJson(memberIds));
    }

    private ResultActions seats(String season, String... memberIds) throws Exception {
        return send(put(l("/seasons/" + season + "/seats")), yugi, membersJson(memberIds));
    }

    private ResultActions teamName(String season, AppUser by, String memberId, String name) throws Exception {
        return send(patch(l("/seasons/" + season + "/participants/" + memberId)), by, "{\"teamName\":\"" + name + "\"}");
    }

    private void join(AppUser user) throws Exception {
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(user))).andExpect(status().isOk());
    }

    private String memberId(String displayName) throws Exception {
        String json = mvc.perform(get("/api/leagues/" + league).with(as(yugi))).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.members[?(@.displayName == '" + displayName + "')].id");
        return ids.getFirst();
    }

    private static String membersJson(String... ids) {
        return "{\"memberIds\":[" + String.join(",", java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").toList()) + "]}";
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
