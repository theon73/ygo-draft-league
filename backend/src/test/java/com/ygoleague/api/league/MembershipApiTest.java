package com.ygoleague.api.league;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class MembershipApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    AppUser yugi, kaiba, joey, mai;
    String league;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate season_rules, season_player, season, league_invite, league_member, ban_list_entry, "
                + "ban_list_revision, ban_list, league, user_identity, app_user restart identity cascade");
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        joey = users.save(new AppUser("Joey"));
        mai = users.save(new AppUser("Mai"));
        league = JsonPath.read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void openInviteLetsAnyoneWithTheLinkJoinOnce() throws Exception {
        String code = invite("{\"expiresInDays\":7}");

        // Previewable signed out, so the page can name the league before sign-in.
        mvc.perform(get("/api/invites/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leagueName").value("Battle City"))
                .andExpect(jsonPath("$.kind").value("OPEN"))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.alreadyMember").value(false));
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf())).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leagueId").value(league));
        mvc.perform(get("/api/leagues/" + league).with(as(kaiba)))
                .andExpect(jsonPath("$.myRole").value("MEMBER"));
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You're already in this league"));
        mvc.perform(get("/api/invites/" + code).with(as(kaiba))).andExpect(jsonPath("$.alreadyMember").value(true));
    }

    @Test
    void usedUpRevokedExpiredAndUnknownInvitesDontWork() throws Exception {
        String single = invite("{\"maxUses\":1}");
        accept(single, kaiba).andExpect(status().isOk());
        accept(single, joey).andExpect(status().isGone()).andExpect(jsonPath("$.message").value("This invite has already been used"));

        String revoked = invite("{}");
        send(delete("/api/leagues/" + league + "/invites/" + revoked), yugi, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/invites/" + revoked)).andExpect(jsonPath("$.status").value("REVOKED"));
        accept(revoked, joey).andExpect(status().isGone());

        String expired = invite("{\"expiresInDays\":1}");
        jdbc.update("update league_invite set expires_at = now() - interval '1 minute' where code = ?", expired);
        mvc.perform(get("/api/invites/" + expired)).andExpect(jsonPath("$.status").value("EXPIRED"));
        accept(expired, joey).andExpect(status().isGone());

        mvc.perform(get("/api/invites/nope")).andExpect(status().isNotFound());
        accept("nope", joey).andExpect(status().isNotFound());

        // The owner's list only shows invites that still work.
        mvc.perform(get("/api/leagues/" + league + "/invites").with(as(yugi))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void placeholderSeatIsClaimedWithItsName() throws Exception {
        String seat = JsonPath.read(send(post("/api/leagues/" + league + "/members/placeholders"), yugi, "{\"displayName\":\"Joey W.\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placeholder").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        send(post("/api/leagues/" + league + "/members/placeholders"), yugi, "{\"displayName\":\"joey w.\"}")
                .andExpect(status().isConflict());

        String code = invite("{\"placeholderId\":\"" + seat + "\",\"expiresInDays\":30}");
        mvc.perform(get("/api/invites/" + code)).andExpect(jsonPath("$.kind").value("CLAIM")).andExpect(jsonPath("$.seatName").value("Joey W."));

        accept(code, joey).andExpect(status().isOk());
        mvc.perform(get("/api/leagues/" + league).with(as(joey)))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Joey W.')].you").value(true))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Joey W.')].placeholder").value(false))
                .andExpect(jsonPath("$.myRole").value("MEMBER"));
        accept(code, mai).andExpect(status().isGone());
        // Claim invites can't be made for seats that already have an account, and are always single-use.
        invite("{\"placeholderId\":\"" + seat + "\"}", 409);
    }

    @Test
    void joiningWithATakenNameGetsANumber() throws Exception {
        AppUser otherYugi = users.save(new AppUser("Yugi"));
        accept(invite("{}"), otherYugi).andExpect(status().isOk());
        mvc.perform(get("/api/leagues/" + league).with(as(otherYugi)))
                .andExpect(jsonPath("$.members[?(@.you == true)].displayName").value("Yugi 2"));
    }

    @Test
    void onlyTheOwnerManagesTheGroup() throws Exception {
        accept(invite("{}"), kaiba).andExpect(status().isOk());

        send(post("/api/leagues/" + league + "/invites"), kaiba, "{}").andExpect(status().isForbidden());
        mvc.perform(get("/api/leagues/" + league + "/invites").with(as(kaiba))).andExpect(status().isForbidden());
        send(post("/api/leagues/" + league + "/members/placeholders"), kaiba, "{\"displayName\":\"X\"}").andExpect(status().isForbidden());
        send(post("/api/leagues/" + league + "/invites"), mai, "{}").andExpect(status().isNotFound());

        invite("{\"expiresInDays\":0}", 400);
        invite("{\"maxUses\":0}", 400);
        String seat = JsonPath.read(send(post("/api/leagues/" + league + "/members/placeholders"), yugi, "{\"displayName\":\"Tea\"}")
                .andReturn().getResponse().getContentAsString(), "$.id");
        invite("{\"placeholderId\":\"" + seat + "\",\"maxUses\":5}", 400);
    }

    @Test
    void theOwnerCantBeRemovedButMembersCan() throws Exception {
        accept(invite("{}"), mai).andExpect(status().isOk());
        accept(invite("{}"), joey).andExpect(status().isOk());
        String yugiId = memberId(yugi, "Yugi");
        String maiId = memberId(yugi, "Mai");
        String joeyId = memberId(yugi, "Joey");

        send(delete("/api/leagues/" + league + "/members/" + yugiId), yugi, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The league owner can't be removed"));
        send(delete("/api/leagues/" + league + "/members/" + maiId), kaiba, null).andExpect(status().isNotFound());   // not a member

        // A season commissioner who isn't playing can be removed; their commissioner role goes with them.
        long leagueDbId = jdbc.queryForObject("select id from league", Long.class);
        long seasonId = jdbc.queryForObject("insert into season (league_id, name) values (?, 'S1') returning id", Long.class, leagueDbId);
        jdbc.update("insert into season_commissioner (season_id, league_id, league_member_id) "
                + "select ?, ?, id from league_member where display_name = 'Mai'", seasonId, leagueDbId);
        send(delete("/api/leagues/" + league + "/members/" + maiId), yugi, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/leagues/" + league).with(as(mai))).andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from season_commissioner", Long.class));

        // Someone playing in a season stays until they're taken out of it.
        jdbc.update("insert into season_player (season_id, league_id, league_member_id) "
                + "select ?, ?, id from league_member where display_name = 'Joey'", seasonId, leagueDbId);
        send(delete("/api/leagues/" + league + "/members/" + joeyId), yugi, null).andExpect(status().isConflict());

        send(delete("/api/leagues/" + league + "/members/not-an-id"), yugi, null).andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private String invite(String body) throws Exception {
        String json = send(post("/api/leagues/" + league + "/invites"), yugi, body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.code");
    }

    private void invite(String body, int expectedStatus) throws Exception {
        send(post("/api/leagues/" + league + "/invites"), yugi, body).andExpect(status().is(expectedStatus));
    }

    private ResultActions accept(String code, AppUser user) throws Exception {
        return mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(user)));
    }

    private String memberId(AppUser viewer, String displayName) throws Exception {
        String json = mvc.perform(get("/api/leagues/" + league).with(as(viewer))).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.members[?(@.displayName == '" + displayName + "')].id");
        return ids.getFirst();
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
