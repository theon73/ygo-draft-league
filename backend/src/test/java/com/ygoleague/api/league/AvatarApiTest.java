package com.ygoleague.api.league;

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
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Roster avatars (YGO-30): a member default, a season override, and who may set which. */
@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false", "app.draft.timer.enabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class AvatarApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;

    AppUser yugi, kaiba;
    String league, season, yugiId, kaibaId, placeholderId;
    long dragon;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate draft_queue, draft_pick, draft, pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        dragon = jdbc.queryForObject("""
                insert into card (passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (array[89631139, 89631140]::bigint[], 'Blue-Eyes White Dragon', 'Normal Monster', 'normal', 'Dragon', 'text', '2002-03-08', '{}', 'h')
                returning id""", Long.class);
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba))).andExpect(status().isOk());
        placeholderId = read(send(post(l("/members/placeholders")), yugi, "{\"displayName\":\"Joey\"}"), "$.id");
        String body = mvc.perform(get(l("")).with(as(yugi))).andReturn().getResponse().getContentAsString();
        yugiId = JsonPath.<java.util.List<String>>read(body, "$.members[?(@.displayName == 'Yugi')].id").get(0);
        kaibaId = JsonPath.<java.util.List<String>>read(body, "$.members[?(@.displayName == 'Kaiba')].id").get(0);
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");
        send(put(s("/participants")), yugi, "{\"memberIds\":[\"" + yugiId + "\",\"" + kaibaId + "\",\"" + placeholderId + "\"]}").andExpect(status().isOk());
    }

    @Test
    void aMemberDefaultShowsInEverySeasonUntilASeasonOverridesIt() throws Exception {
        send(put(l("/members/" + kaibaId + "/avatar")), kaiba, "{\"cardId\":" + dragon + "}").andExpect(status().isNoContent());
        mvc.perform(get(l("")).with(as(kaiba))).andExpect(jsonPath("$.members[?(@.displayName == 'Kaiba')].avatar").value(89631139));
        participant("Kaiba").andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].avatar").value(89631139))
                .andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].ownAvatar").value(false));
        mvc.perform(get(s("/rosters")).with(as(kaiba))).andExpect(jsonPath("$.players[?(@.displayName == 'Kaiba')].avatar").value(89631139));

        // This season only: the other artwork.
        send(put(s("/participants/" + kaibaId + "/avatar")), kaiba, "{\"cardId\":" + dragon + ",\"passcode\":89631140}").andExpect(status().isNoContent());
        participant("Kaiba").andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].avatar").value(89631140))
                .andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].ownAvatar").value(true));
        mvc.perform(get(l("")).with(as(kaiba))).andExpect(jsonPath("$.members[?(@.displayName == 'Kaiba')].avatar").value(89631139));

        // Clearing the override falls back to the default.
        send(put(s("/participants/" + kaibaId + "/avatar")), kaiba, "{\"cardId\":null}").andExpect(status().isNoContent());
        participant("Kaiba").andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].avatar").value(89631139));
    }

    @Test
    void playersSetTheirOwnAndCommissionersOnlyPlaceholders() throws Exception {
        String art = "{\"cardId\":" + dragon + "}";
        send(put(l("/members/" + yugiId + "/avatar")), kaiba, art).andExpect(status().isForbidden());
        send(put(l("/members/" + placeholderId + "/avatar")), kaiba, art).andExpect(status().isForbidden());   // not the owner
        send(put(s("/participants/" + placeholderId + "/avatar")), kaiba, art).andExpect(status().isForbidden());   // not a commissioner
        send(put(l("/members/" + kaibaId + "/avatar")), yugi, art).andExpect(status().isForbidden());   // a real player's is theirs
        send(put(s("/participants/" + kaibaId + "/avatar")), yugi, art).andExpect(status().isForbidden());

        send(put(l("/members/" + placeholderId + "/avatar")), yugi, art).andExpect(status().isNoContent());
        send(put(s("/participants/" + placeholderId + "/avatar")), yugi, "{\"cardId\":" + dragon + ",\"passcode\":89631140}").andExpect(status().isNoContent());
        participant("Joey").andExpect(jsonPath("$.participants[?(@.displayName == 'Joey')].avatar").value(89631140));
    }

    @Test
    void aProfileAvatarIsTheFallbackEverywhere() throws Exception {
        send(put("/api/me/avatar"), kaiba, "{\"cardId\":" + dragon + ",\"passcode\":89631140}").andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/session").with(as(kaiba))).andExpect(jsonPath("$.user.avatar").value(89631140));
        mvc.perform(get(l("")).with(as(kaiba)))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Kaiba')].avatar").value(89631140))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Kaiba')].ownAvatar").value(false));
        mvc.perform(get(s("/rosters")).with(as(kaiba))).andExpect(jsonPath("$.players[?(@.displayName == 'Kaiba')].avatar").value(89631140));
        participant("Kaiba").andExpect(jsonPath("$.participants[?(@.displayName == 'Kaiba')].avatar").value(89631140));

        // A league's own avatar wins over the profile's in that league.
        send(put(l("/members/" + kaibaId + "/avatar")), kaiba, "{\"cardId\":" + dragon + ",\"passcode\":89631139}").andExpect(status().isNoContent());
        mvc.perform(get(s("/rosters")).with(as(kaiba))).andExpect(jsonPath("$.players[?(@.displayName == 'Kaiba')].avatar").value(89631139));
        mvc.perform(get("/api/auth/session").with(as(kaiba))).andExpect(jsonPath("$.user.avatar").value(89631140));
    }

    @Test
    void theArtworkMustBeTheCards() throws Exception {
        send(put(l("/members/" + kaibaId + "/avatar")), kaiba, "{\"cardId\":" + dragon + ",\"passcode\":12345}").andExpect(status().isBadRequest());
        send(put(l("/members/" + kaibaId + "/avatar")), kaiba, "{\"cardId\":999999}").andExpect(status().isBadRequest());
    }

    private ResultActions participant(String ignored) throws Exception {
        return mvc.perform(get(s("")).with(as(yugi))).andExpect(status().isOk());
    }

    private String l(String path) { return "/api/leagues/" + league + path; }
    private String s(String path) { return l("/seasons/" + season + path); }

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
