package com.ygoleague.api.season;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import com.ygoleague.image.CardImageService;
import com.ygoleague.image.ImageVariant;
import com.ygoleague.season.pool.PoolOwnership;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
/** Starting a season from an earlier one, and the setup wizard's step (YGO-32). */
class SeasonCopyApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;   // no real fetches from YGOPRODeck
    @MockitoBean PoolOwnership ownership;

    AppUser yugi, kaiba, outsider;
    String league, season;
    Map<String, Long> ids = new HashMap<>();

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        card("Pot of Greed", 55144522L, "Spell Card", null, "2002-03-08");
        card("Elemental HERO Avian", 21844576L, "Normal Monster", "Elemental HERO", "2005-03-01");
        card("Elemental HERO Burstinatrix", 58932615L, "Normal Monster", "Elemental HERO", "2005-03-01");
        card("Polymerization", 24094653L, "Spell Card", null, "2002-03-08");
        card("Sheep Token", 73915052L, "Token", null, "2004-01-01");
        card("Judgment Dragon", 57774843L, "Effect Monster", "Lightsworn", "2008-05-01");   // just past a March 2008 cutoff
        card("Stardust Dragon", 44508094L, "Synchro Monster", null, "2008-11-01");

        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        outsider = users.save(new AppUser("Outsider"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba))).andExpect(status().isOk());
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");
    }

    @Test
    void aNewSeasonCanStartFromAnEarlierOne() throws Exception {
        String kaibaId = memberId("Kaiba");
        send(put(l("/seasons/" + season + "/rules")), yugi,
                "{\"startDate\":\"2030-01-07\",\"tcgCutoff\":\"2008-03-01\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":2,\"tradesPerWeek\":2}")
                .andExpect(status().isOk());
        send(put(l("/seasons/" + season + "/participants")), yugi, "{\"memberIds\":[\"" + memberId("Yugi") + "\",\"" + kaibaId + "\"]}")
                .andExpect(status().isOk());
        send(patch(l("/seasons/" + season + "/participants/" + kaibaId)), kaiba, "{\"teamName\":\"Blue-Eyes\"}").andExpect(status().isOk());
        send(put(l("/seasons/" + season + "/draft/settings")), yugi,
                "{\"order\":\"LINEAR\",\"rounds\":[{\"fromRound\":1,\"toRound\":2,\"picksPerTurn\":1}],\"scheduledStart\":\"2030-01-01T19:00\"}")
                .andExpect(status().isOk());
        send(post(pool("/generate")), yugi, null).andExpect(status().isOk());
        add("Judgment Dragon", "Banned in our format, but allowed here").andExpect(status().isCreated());

        // Everything, guided.
        String s2 = read(send(post(l("/seasons")), yugi, "{\"name\":\"S2\",\"copyFrom\":\"" + season + "\",\"copyPlayers\":true,"
                + "\"copyRules\":true,\"copyDraft\":true,\"copyPool\":true,\"guided\":true}").andExpect(status().isCreated()), "$.id");
        mvc.perform(get(l("/seasons/" + s2)).with(as(yugi)))
                .andExpect(listAt("$.participants[*].displayName", List.of("Yugi", "Kaiba")))
                .andExpect(jsonPath("$.participants[1].teamName").value("Blue-Eyes"))
                .andExpect(jsonPath("$.setupStep").value(1));
        mvc.perform(get(l("/seasons/" + s2 + "/rules")).with(as(yugi)))
                .andExpect(jsonPath("$.format.tcgCutoff").value("2008-03-01"))
                .andExpect(jsonPath("$.competition.roundRobinCycles").value(2))
                .andExpect(jsonPath("$.transactions.tradesPerWeek").value(2))
                .andExpect(jsonPath("$.calendar.startDate").doesNotExist());   // the dates are the new season's own
        mvc.perform(get(l("/seasons/" + s2 + "/draft")).with(as(yugi)))
                .andExpect(jsonPath("$.order").value("LINEAR"))
                .andExpect(jsonPath("$.roundCount").value(2))
                .andExpect(jsonPath("$.scheduledStart").doesNotExist());
        mvc.perform(get(l("/seasons/" + s2 + "/pool")).with(as(yugi)))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.exceptions").value(1))
                .andExpect(jsonPath("$.generatedCutoff").value("2008-03-01"));

        // It remembers where it came from (YGO-39); the old season points at it as the next one. S1 isn't
        // complete, so there are no final standings to reverse, and a random draw says it's random.
        mvc.perform(get(l("/seasons/" + s2)).with(as(kaiba)))
                .andExpect(jsonPath("$.copiedFrom.name").value("S1")).andExpect(jsonPath("$.copiedFrom.status").value("SETUP"))
                .andExpect(jsonPath("$.next").doesNotExist());
        mvc.perform(get(l("/seasons/" + season)).with(as(kaiba))).andExpect(jsonPath("$.next.name").value("S2"));
        send(post(l("/seasons/" + s2 + "/seats/reverse-standings")), yugi, null).andExpect(status().isBadRequest());
        send(post(l("/seasons/" + s2 + "/seats/randomize")), yugi, null)
                .andExpect(jsonPath("$.lastDraw.method").value("RANDOM")).andExpect(jsonPath("$.lastDraw.basedOn").doesNotExist());

        // Only what's ticked: the rules without the draft, no players, no pool.
        String s3 = read(send(post(l("/seasons")), yugi, "{\"name\":\"S3\",\"copyFrom\":\"" + season + "\",\"copyRules\":true}"), "$.id");
        mvc.perform(get(l("/seasons/" + s3)).with(as(yugi)))
                .andExpect(jsonPath("$.participants.length()").value(0))
                .andExpect(jsonPath("$.setupStep").doesNotExist());
        mvc.perform(get(l("/seasons/" + s3 + "/draft")).with(as(yugi))).andExpect(jsonPath("$.roundCount").value(0));
        mvc.perform(get(l("/seasons/" + s3 + "/pool")).with(as(yugi))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(l("/seasons/" + s3 + "/rules")).with(as(yugi))).andExpect(jsonPath("$.competition.roundRobinCycles").value(2));

        // The old {"name"} request still works; a season from another league can't be copied.
        send(post(l("/seasons")), yugi, "{\"name\":\"S4\"}").andExpect(status().isCreated());
        String other = read(send(post("/api/leagues"), outsider, "{\"name\":\"Elsewhere\",\"timeZone\":\"UTC\"}"), "$.id");
        String otherSeason = read(send(post("/api/leagues/" + other + "/seasons"), outsider, "{\"name\":\"X\"}"), "$.id");
        send(post(l("/seasons")), yugi, "{\"name\":\"S5\",\"copyFrom\":\"" + otherSeason + "\",\"copyRules\":true}").andExpect(status().isNotFound());
    }

    @Test
    void theWizardStepIsTheCommissionersDuringSetup() throws Exception {
        send(put(l("/seasons/" + season + "/setup-step")), yugi, "{\"step\":3}").andExpect(jsonPath("$.setupStep").value(3));
        send(put(l("/seasons/" + season + "/setup-step")), kaiba, "{\"step\":4}").andExpect(status().isForbidden());
        send(put(l("/seasons/" + season + "/setup-step")), yugi, "{\"step\":7}").andExpect(status().isBadRequest());
        send(put(l("/seasons/" + season + "/setup-step")), yugi, "{\"step\":null}").andExpect(jsonPath("$.setupStep").doesNotExist());
    }

    private String memberId(String name) throws Exception {
        String body = mvc.perform(get(l("")).with(as(yugi))).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.members[?(@.displayName == '" + name + "')].id");
        return ids.get(0);
    }

    private void card(String name, long passcode, String type, String archetype, String tcgDate) {
        Long id = jdbc.queryForObject("""
                insert into card (passcodes, name, card_type, frame_type, race, archetype, description, tcg_date, raw, content_hash)
                values (array[?]::bigint[], ?, ?, 'effect', 'Fiend', ?, 'text', ?::date, '{}', 'h') returning id""",
                Long.class, passcode, name, type, archetype, tcgDate);
        ids.put(name, id);
    }

    private void cutoff(String date) throws Exception {
        send(put(l("/seasons/" + season + "/rules")), yugi, "{\"tcgCutoff\":\"" + date + "\"}").andExpect(status().isOk());
    }

    private ResultActions add(String name, String reason) throws Exception {
        return send(post(pool("/cards")), yugi, "{\"cardId\":" + ids.get(name) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private String l(String path) {
        return "/api/leagues/" + league + path;
    }

    private String pool(String path) {
        return l("/seasons/" + season + "/pool" + path);
    }

    private static ResultMatcher listAt(String path, List<?> expected) {
        return result -> Assertions.assertEquals(expected, JsonPath.read(result.getResponse().getContentAsString(), path), path);
    }

    private static <T> T read(ResultActions result, String path) throws Exception {
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
