package com.ygoleague.api.league;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueMemberRepository;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.league.LeagueRole;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class LeagueApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired LeagueRepository leagues;
    @Autowired LeagueMemberRepository members;
    @Autowired PublicIds publicIds;
    @Autowired JdbcTemplate jdbc;

    AppUser yugi;
    AppUser kaiba;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate season_rules, season_player, season, league_invite, league_member, ban_list_entry, "
                + "ban_list_revision, ban_list, league, user_identity, app_user restart identity cascade");
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
    }

    @Test
    void createdLeagueMakesTheCreatorItsCommissioner() throws Exception {
        String body = mvc.perform(post("/api/leagues").with(csrf()).with(as(yugi))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Goat   Format \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Goat Format"))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/leagues").with(as(yugi)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].memberCount").value(1));
        mvc.perform(get("/api/leagues/" + id).with(as(yugi)))
                .andExpect(jsonPath("$.members[0].displayName").value("Yugi"))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].you").value(true));
        // Someone else's "My leagues" doesn't include it.
        mvc.perform(get("/api/leagues").with(as(kaiba))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidNamesAreRejected() throws Exception {
        mvc.perform(post("/api/leagues").with(csrf()).with(as(yugi)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("League name is required"));
        mvc.perform(post("/api/leagues").with(csrf()).with(as(yugi)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void outsidersGet404NeverTheLeague() throws Exception {
        String id = league("Secret League", yugi);

        mvc.perform(get("/api/leagues/" + id).with(as(kaiba))).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/not-a-real-id").with(as(kaiba))).andExpect(status().isNotFound());
        // A valid public id of another type (a season) doesn't resolve to a league either.
        long leagueDbId = publicIds.decode(PublicIdType.LEAGUE, id).orElseThrow();
        mvc.perform(get("/api/leagues/" + publicIds.encode(PublicIdType.SEASON, leagueDbId)).with(as(yugi)))
                .andExpect(status().isNotFound());
    }

    @Test
    void membersOfAnyRoleSeeTheOverviewWithPlaceholdersMarked() throws Exception {
        String id = league("Dev League", yugi);
        League league = leagues.findById(publicIds.decode(PublicIdType.LEAGUE, id).orElseThrow()).orElseThrow();
        members.save(LeagueMember.forUser(league, kaiba, "Kaiba", LeagueRole.MEMBER));
        members.save(LeagueMember.placeholder(league, "Joey (no account yet)", LeagueRole.MEMBER));

        mvc.perform(get("/api/leagues/" + id).with(as(kaiba)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("MEMBER"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Joey (no account yet)')].placeholder").value(true))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Kaiba')].you").value(true))
                .andExpect(jsonPath("$.members[?(@.displayName == 'Yugi')].you").value(false));
        mvc.perform(get("/api/leagues").with(as(kaiba))).andExpect(jsonPath("$[0].memberCount").value(3));
    }

    @Test
    void signedOutAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/leagues")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/leagues").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/leagues").with(as(yugi)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    private String league(String name, AppUser creator) throws Exception {
        String body = mvc.perform(post("/api/leagues").with(csrf()).with(as(creator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static RequestPostProcessor as(AppUser user) {
        CurrentUser principal = new CurrentUser(user.getId(), user.getDisplayName(), null, "dev", "u" + user.getId(), false);
        return authentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "dev"));
    }
}
