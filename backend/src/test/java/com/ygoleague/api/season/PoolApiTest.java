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
class PoolApiTest {

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
    void generatingTakesEverythingUpToTheCutoffExceptTokensAndPrefetchesThumbnails() throws Exception {
        send(post(pool("/generate")), yugi, null).andExpect(status().isBadRequest());   // no cutoff yet
        cutoff("2008-03-01");

        send(post(pool("/generate")), yugi, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.dated").value(4))
                .andExpect(jsonPath("$.generatedCutoff").value("2008-03-01"))
                .andExpect(jsonPath("$.rulesCutoff").value("2008-03-01"));
        mvc.perform(get(pool("/cards")).with(as(kaiba)))
                .andExpect(listAt("$.items[*].name", List.of("Elemental HERO Avian", "Elemental HERO Burstinatrix", "Polymerization", "Pot of Greed")));
        verify(images).prefetch(argThat((Collection<Long> p) -> Set.copyOf(p).equals(Set.of(55144522L, 21844576L, 58932615L, 24094653L))),
                eq(ImageVariant.SMALL));

        mvc.perform(get(pool("/changes")).with(as(kaiba)))
                .andExpect(jsonPath("$[0].kind").value("GENERATED"))
                .andExpect(jsonPath("$[0].detail.added").value(4))
                .andExpect(jsonPath("$[0].by").value("Yugi"));
    }

    @Test
    void regeneratingAfterACutoffChangeAddsAndDropsDatedCardsButKeepsExceptionsAndRemovals() throws Exception {
        cutoff("2008-03-01");
        send(post(pool("/generate")), yugi, null);
        add("Judgment Dragon", "enables Lightsworn").andExpect(jsonPath("$.source").value("EXCEPTION"));
        send(delete(pool("/cards/" + ids.get("Pot of Greed"))), yugi, null).andExpect(status().isNoContent());

        cutoff("2004-01-01");   // earlier: the 2005 HEROes drop out
        send(post(pool("/generate")), yugi, null)
                .andExpect(jsonPath("$.total").value(2))           // Polymerization + Judgment Dragon (exception)
                .andExpect(jsonPath("$.exceptions").value(1))
                .andExpect(jsonPath("$.removed").value(1));       // Pot of Greed stays removed
        cutoff("2008-12-31");   // later: Judgment Dragon is simply dated now; Stardust joins
        send(post(pool("/generate")), yugi, null)
                .andExpect(jsonPath("$.exceptions").value(0))
                .andExpect(jsonPath("$.dated").value(5));
        mvc.perform(get(pool("/cards")).param("show", "REMOVED").with(as(yugi)))
                .andExpect(listAt("$.items[*].name", List.of("Pot of Greed")));
    }

    @Test
    void exceptionsAreHandPickedCardsOnTopOfTheDatedPool() throws Exception {
        cutoff("2008-03-01");
        send(post(pool("/generate")), yugi, null);
        clearInvocations(images);

        add("Judgment Dragon", "  enables   Lightsworn ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("EXCEPTION"))
                .andExpect(jsonPath("$.reason").value("enables Lightsworn"))
                .andExpect(jsonPath("$.addedInWeek").value(0));
        verify(images).prefetch(eq(List.of(57774843L)), eq(ImageVariant.SMALL));
        add("Judgment Dragon", null).andExpect(status().isConflict());
        send(post(pool("/cards")), yugi, "{\"cardId\":999999}").andExpect(status().isBadRequest());

        // A dated card that was removed comes back as dated, not as an exception.
        send(delete(pool("/cards/" + ids.get("Pot of Greed"))), yugi, null);
        add("Pot of Greed", null).andExpect(jsonPath("$.source").value("DATED")).andExpect(jsonPath("$.removedInWeek").doesNotExist());

        mvc.perform(get(pool("/candidates")).param("q", "dragon").with(as(yugi)))
                .andExpect(listAt("$[*].name", List.of("Judgment Dragon", "Stardust Dragon")))
                .andExpect(listAt("$[*].inPool", List.of(true, false)));
        mvc.perform(get(pool("/cards")).param("show", "EXCEPTIONS").with(as(kaiba)))
                .andExpect(listAt("$.items[*].name", List.of("Judgment Dragon")));
    }

    @Test
    void ownedCardsCantBeRemovedOnlySoftBanned() throws Exception {
        cutoff("2008-03-01");
        send(post(pool("/generate")), yugi, null);
        when(ownership.isOwned(any(), eq(ids.get("Polymerization")))).thenReturn(true);

        send(delete(pool("/cards/" + ids.get("Polymerization"))), yugi, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Soft-ban it instead")));
        send(delete(pool("/cards/" + ids.get("Stardust Dragon"))), yugi, null).andExpect(status().isNotFound());   // not in the pool
    }

    @Test
    void flagsOnOneCardOrAWholeArchetype() throws Exception {
        cutoff("2008-03-01");
        send(post(pool("/generate")), yugi, null);

        send(patch(pool("/cards/" + ids.get("Polymerization"))), yugi, "{\"requiresOwnership\":true}")
                .andExpect(jsonPath("$.requiresOwnership").value(true));
        send(patch(pool("/cards/" + ids.get("Pot of Greed"))), yugi, "{\"maxPlayable\":1,\"reason\":\"house rule\"}")
                .andExpect(jsonPath("$.maxPlayable").value(1));
        send(patch(pool("/cards/" + ids.get("Pot of Greed"))), yugi, "{\"clearMaxPlayable\":true}")
                .andExpect(jsonPath("$.maxPlayable").doesNotExist())
                .andExpect(jsonPath("$.effectiveMax").value(3));
        send(patch(pool("/cards/" + ids.get("Pot of Greed"))), yugi, "{\"maxPlayable\":1,\"clearMaxPlayable\":true}")
                .andExpect(status().isBadRequest());
        send(patch(pool("/cards/" + ids.get("Pot of Greed"))), yugi, "{\"maxPlayable\":4}").andExpect(status().isBadRequest());
        send(patch(pool("/cards/" + ids.get("Pot of Greed"))), yugi, "{}").andExpect(status().isBadRequest());

        mvc.perform(get(pool("/archetypes")).with(as(kaiba))).andExpect(jsonPath("$[0].archetype").value("Elemental HERO"))
                .andExpect(jsonPath("$[0].cards").value(2));
        send(patch(pool("/archetypes/Elemental HERO")), yugi, "{\"softBanned\":true}")
                .andExpect(jsonPath("$.softBanned").value(2))
                .andExpect(jsonPath("$.requiresOwnership").value(1));
        send(patch(pool("/archetypes/Nope")), yugi, "{\"softBanned\":true}").andExpect(status().isNotFound());
        mvc.perform(get(pool("/cards")).param("show", "FLAGGED").with(as(kaiba)))
                .andExpect(listAt("$.items[*].name", List.of("Elemental HERO Avian", "Elemental HERO Burstinatrix", "Polymerization")));
        mvc.perform(get(pool("/changes")).with(as(kaiba))).andExpect(jsonPath("$[0].detail.archetype").value("Elemental HERO"));
    }

    @Test
    void copyLimitsComeFromTheSeasonsBanListUnlessOverridden() throws Exception {
        long list = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/2008-03-01', 'TCG March 2008', '2008-03-01') returning id", Long.class);
        long rev = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'x') returning id", Long.class, list);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) values (?, ?, 'FORBIDDEN'), (?, ?, 'LIMITED'), (?, ?, 'SEMI_LIMITED')",
                rev, ids.get("Pot of Greed"), rev, ids.get("Polymerization"), rev, ids.get("Elemental HERO Avian"));
        send(put(l("/seasons/" + season + "/rules")), yugi, "{\"tcgCutoff\":\"2008-03-01\",\"banListRevisionId\":" + rev + "}").andExpect(status().isOk());
        send(post(pool("/generate")), yugi, null)
                .andExpect(jsonPath("$.banList").value("TCG March 2008"))
                .andExpect(jsonPath("$.forbidden").value(1))
                .andExpect(jsonPath("$.limited").value(1))
                .andExpect(jsonPath("$.semiLimited").value(1));

        mvc.perform(get(pool("/cards")).with(as(kaiba)))
                .andExpect(listAt("$.items[*].name", List.of("Elemental HERO Avian", "Elemental HERO Burstinatrix", "Polymerization", "Pot of Greed")))
                .andExpect(listAt("$.items[*].banStatus", java.util.Arrays.asList("SEMI_LIMITED", null, "LIMITED", "FORBIDDEN")))
                .andExpect(listAt("$.items[*].effectiveMax", List.of(2, 3, 1, 0)));
        mvc.perform(get(pool("/cards")).param("show", "BAN_LIST").with(as(kaiba)))
                .andExpect(jsonPath("$.total").value(3));
        // Rows carry the stats for their one-line summary.
        mvc.perform(get(pool("/cards")).param("q", "avian").with(as(kaiba)))
                .andExpect(jsonPath("$.items[0].race").value("Fiend"))
                .andExpect(jsonPath("$.items[0].attribute").doesNotExist());

        // A house rule: Polymerization at 2 in this league. Clearing goes back to the list's limit.
        send(patch(pool("/cards/" + ids.get("Polymerization"))), yugi, "{\"maxPlayable\":2}")
                .andExpect(jsonPath("$.banStatus").value("LIMITED"))
                .andExpect(jsonPath("$.maxPlayable").value(2))
                .andExpect(jsonPath("$.effectiveMax").value(2));
        send(patch(pool("/cards/" + ids.get("Polymerization"))), yugi, "{\"clearMaxPlayable\":true}")
                .andExpect(jsonPath("$.effectiveMax").value(1));
    }

    @Test
    void membersSeeThePoolCommissionersChangeIt() throws Exception {
        cutoff("2008-03-01");
        mvc.perform(get(pool("")).with(as(kaiba))).andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false));
        send(post(pool("/generate")), kaiba, null).andExpect(status().isForbidden());
        send(post(pool("/cards")), kaiba, "{\"cardId\":" + ids.get("Judgment Dragon") + "}").andExpect(status().isForbidden());
        mvc.perform(get(pool("")).with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test
    void midSeasonChangesTakeEffectFromAWeekAndLeaveThePastAlone() throws Exception {
        LocalDate start = LocalDate.now(ZoneId.of("America/Chicago")).minusWeeks(4);
        send(put(l("/seasons/" + season + "/rules")), yugi,
                "{\"startDate\":\"" + start + "\",\"regularWeeks\":10,\"tcgCutoff\":\"2008-03-01\"}").andExpect(status().isOk());
        send(post(pool("/generate")), yugi, null);
        jdbc.update("update season set status = 'ACTIVE'");
        int now = read(mvc.perform(get(pool("")).with(as(yugi))), "$.minEffectiveWeek");
        Assertions.assertTrue(now >= 4, "about four weeks in");

        send(post(pool("/generate")), yugi, null).andExpect(status().isConflict());
        send(post(pool("/cards")), yugi, "{\"cardId\":" + ids.get("Judgment Dragon") + "}").andExpect(status().isBadRequest());   // from when?
        send(post(pool("/cards")), yugi, "{\"cardId\":" + ids.get("Judgment Dragon") + ",\"effectiveFromWeek\":" + (now - 1) + "}")
                .andExpect(status().isBadRequest());   // not retroactively
        send(post(pool("/cards")), yugi, "{\"cardId\":" + ids.get("Judgment Dragon") + ",\"reason\":\"boost the bottom 3\",\"effectiveFromWeek\":" + now + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addedInWeek").value(now));
        send(delete(pool("/cards/" + ids.get("Pot of Greed"))), yugi, "{\"effectiveFromWeek\":" + (now + 1) + "}").andExpect(status().isNoContent());
        Assertions.assertEquals(now + 1, jdbc.queryForObject("select removed_in_week from pool_card where card_id = ?", Integer.class, ids.get("Pot of Greed")));
    }

    // ---- helpers ----

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
