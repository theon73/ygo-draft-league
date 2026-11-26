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

/** Browsing the pool: filters, sorting and facets. */
@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PoolBrowseApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CardImageService images;

    AppUser yugi;
    String league, season;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate pool_change, pool_card, season_seat_draw, season_commissioner, season_rules, season_player, season, "
                + "league_invite, league_member, ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card, "
                + "league, user_identity, app_user restart identity cascade");
        //   name                          card_type                  frame             race          attr     lvl  rank link scale atk   def   archetype         released      type line (after the monster type)
        card("Dark Magician",              "Normal Monster",          "normal",         "Spellcaster", "DARK",  7,  null, null, null, 2500, 2100, "Dark Magician",  "2002-03-08", "Normal");
        card("Sangan",                     "Effect Monster",          "effect",         "Fiend",       "DARK",  3,  null, null, null, 1000, 600,  null,             "2002-06-26", "Effect");
        card("Breaker the Magical Warrior","Effect Monster",          "effect",         "Spellcaster", "DARK",  4,  null, null, null, 1600, 1000, null,             "2004-03-01", "Effect");
        card("Elemental HERO Shining Flare Wingman", "Fusion Monster", "fusion",        "Warrior",     "LIGHT", 8,  null, null, null, 2500, 2100, "Elemental HERO", "2005-12-01", "Fusion", "Effect");
        card("Blue-Eyes Ultimate Dragon",  "Fusion Monster",          "fusion",         "Dragon",      "LIGHT", 12, null, null, null, 4500, 3800, "Blue-Eyes",      "2002-03-08", "Fusion");
        card("Stardust Dragon",            "Synchro Monster",         "synchro",        "Dragon",      "WIND",  8,  null, null, null, 2500, 2000, null,             "2008-11-01", "Synchro", "Effect");
        card("Formula Synchron",           "Synchro Tuner Monster",   "synchro",        "Machine",     "LIGHT", 2,  null, null, null, 200,  1500, "Synchron",       "2011-03-01", "Synchro", "Tuner", "Effect");
        card("Number 39: Utopia",          "XYZ Monster",             "xyz",            "Warrior",     "LIGHT", null, 4,   null, null, 2500, 2000, "Utopia",         "2011-07-01", "Xyz", "Effect");
        card("Decode Talker",              "Link Monster",            "link",           "Cyberse",     "DARK",  null, null, 3,   null, 2300, null, "Code Talker",    "2017-01-01", "Link", "Effect");
        card("Odd-Eyes Pendulum Dragon",   "Pendulum Effect Monster", "effect_pendulum","Dragon",      "DARK",  7,  null, null, 4,    2500, 2000, "Odd-Eyes",       "2014-05-01", "Pendulum", "Effect");
        card("Junk Synchron",              "Tuner Monster",           "effect",         "Warrior",     "DARK",  3,  null, null, null, 1300, 500,  "Synchron",       "2008-11-01", "Tuner", "Effect");
        card("Y-Dragon Head",              "Union Effect Monster",    "effect",         "Machine",     "LIGHT", 4,  null, null, null, 1500, 1600, null,             "2004-05-01", "Union", "Effect");
        card("Mystical Space Typhoon",     "Spell Card",              "spell",          "Quick-Play",  null,    null, null, null, null, null, null, null,           "2002-06-26");
        card("Pot of Greed",               "Spell Card",              "spell",          "Normal",      null,    null, null, null, null, null, null, null,           "2002-03-08");
        card("Mirror Force",               "Trap Card",               "trap",           "Normal",      null,    null, null, null, null, null, null, null,           "2002-06-26");
        card("Call of the Haunted",        "Trap Card",               "trap",           "Continuous",  null,    null, null, null, null, null, null, null,           "2002-06-26");

        yugi = users.save(new AppUser("Yugi"));
        league = read(send(post("/api/leagues"), "{\"name\":\"Battle City\"}"), "$.id");
        season = read(send(post(l("/seasons")), "{\"name\":\"S1\"}"), "$.id");
        send(put(l("/seasons/" + season + "/rules")), "{\"tcgCutoff\":\"2020-01-01\"}").andExpect(status().isOk());
        send(post(pool("/generate")), null).andExpect(jsonPath("$.total").value(16));
    }

    @Test
    void kindsDecksAndSummonTypesWithMultipleSelection() throws Exception {
        names("kind=SPELL&kind=TRAP", List.of("Call of the Haunted", "Mirror Force", "Mystical Space Typhoon", "Pot of Greed"));
        names("deck=EXTRA", List.of("Blue-Eyes Ultimate Dragon", "Decode Talker", "Elemental HERO Shining Flare Wingman", "Formula Synchron",
                "Number 39: Utopia", "Stardust Dragon"));
        names("summon=FUSION&summon=SYNCHRO", List.of("Blue-Eyes Ultimate Dragon", "Elemental HERO Shining Flare Wingman", "Formula Synchron", "Stardust Dragon"));
        names("summon=XYZ", List.of("Number 39: Utopia"));
        names("summon=LINK", List.of("Decode Talker"));
        // Both decks chosen is just "monsters".
        Assertions.assertEquals(12, total("deck=MAIN&deck=EXTRA"));
    }

    @Test
    void effectNormalAndAbilities() throws Exception {
        // Normal = no effect: Normal monsters and non-effect Extra Deck monsters alike.
        names("text=NORMAL", List.of("Blue-Eyes Ultimate Dragon", "Dark Magician"));
        names("text=EFFECT&deck=MAIN", List.of("Breaker the Magical Warrior", "Junk Synchron", "Odd-Eyes Pendulum Dragon", "Sangan", "Y-Dragon Head"));
        names("text=EFFECT&summon=FUSION", List.of("Elemental HERO Shining Flare Wingman"));
        names("ability=TUNER", List.of("Formula Synchron", "Junk Synchron"));
        names("ability=TUNER&summon=SYNCHRO", List.of("Formula Synchron"));                     // Synchro Tuners
        names("ability=UNION", List.of("Y-Dragon Head"));
        names("ability=PENDULUM", List.of("Odd-Eyes Pendulum Dragon"));
        names("ability=UNION&ability=PENDULUM", List.of("Odd-Eyes Pendulum Dragon", "Y-Dragon Head"));   // any of
    }

    @Test
    void attributesTypesAndArchetypesAreMultiSelect() throws Exception {
        names("attribute=WIND&attribute=LIGHT&deck=EXTRA", List.of("Blue-Eyes Ultimate Dragon", "Elemental HERO Shining Flare Wingman",
                "Formula Synchron", "Number 39: Utopia", "Stardust Dragon"));
        names("monsterType=Dragon&monsterType=Cyberse", List.of("Blue-Eyes Ultimate Dragon", "Decode Talker", "Odd-Eyes Pendulum Dragon", "Stardust Dragon"));
        names("archetype=Synchron&archetype=Utopia", List.of("Formula Synchron", "Junk Synchron", "Number 39: Utopia"));
        // A spell type implies spells (no monsters slip in); mixing kinds keeps each kind's own subtype choice.
        names("spellType=Quick-Play", List.of("Mystical Space Typhoon"));
        names("spellType=Quick-Play&trapType=Continuous", List.of("Call of the Haunted", "Mystical Space Typhoon"));
        names("kind=SPELL&kind=TRAP&trapType=Continuous", List.of("Call of the Haunted", "Mystical Space Typhoon", "Pot of Greed"));
    }

    @Test
    void levelRankLinkAtkDefAndScaleRanges() throws Exception {
        names("levelMin=7&levelMax=8", List.of("Dark Magician", "Elemental HERO Shining Flare Wingman", "Odd-Eyes Pendulum Dragon", "Stardust Dragon"));
        names("rankMin=4&rankMax=4", List.of("Number 39: Utopia"));                                 // ranks aren't levels
        names("linkMin=2", List.of("Decode Talker"));
        names("atkMin=2500&defMax=2000", List.of("Number 39: Utopia", "Odd-Eyes Pendulum Dragon", "Stardust Dragon"));
        names("scaleMin=1&scaleMax=5", List.of("Odd-Eyes Pendulum Dragon"));
        // A range implies monsters: spells and traps (no ATK) don't show up.
        Assertions.assertEquals(12, total("atkMin=0"));
    }

    @Test
    void searchesCardText() throws Exception {
        jdbc.update("update card set description = 'FLIP: Destroy 1 monster on the field.' where name = 'Sangan'");
        jdbc.update("update card set description = 'When this card is destroyed by battle: Draw 1 card.' where name = 'Y-Dragon Head'");
        jdbc.update("update card set description = 'Target 1 Spell/Trap on the field; DESTROY that target.' where name = 'Mystical Space Typhoon'");

        names("cardText=destroy", List.of("Mystical Space Typhoon", "Sangan", "Y-Dragon Head"));   // any case, any kind
        names("cardText=destroy&kind=MONSTER", List.of("Sangan", "Y-Dragon Head"));
        names("cardText=draw 1&ability=UNION", List.of("Y-Dragon Head"));
        names("cardText=100%", List.of());                                                       // % is literal, not a wildcard
        mvc.perform(get(pool("/cards?cardText=" + "x".repeat(101))).with(as(yugi))).andExpect(status().isBadRequest());
    }

    @Test
    void sortsByReleaseAtkAndLevel() throws Exception {
        List<String> byRelease = page("kind=MONSTER&sort=RELEASE");
        Assertions.assertEquals("Decode Talker", byRelease.getFirst());
        List<String> byAtk = page("deck=MAIN&sort=ATK");
        Assertions.assertEquals(List.of("Dark Magician", "Odd-Eyes Pendulum Dragon", "Breaker the Magical Warrior", "Y-Dragon Head", "Junk Synchron", "Sangan"), byAtk);
        Assertions.assertEquals("Blue-Eyes Ultimate Dragon", page("kind=MONSTER&sort=LEVEL").getFirst());   // level 12
    }

    @Test
    void banStatusesAndPaging() throws Exception {
        long list = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/x', 'List', '2008-03-01') returning id", Long.class);
        long rev = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'x') returning id", Long.class, list);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) select ?, id, 'FORBIDDEN' from card where name = 'Pot of Greed'", rev);
        jdbc.update("insert into ban_list_entry (revision_id, card_id, status) select ?, id, 'LIMITED' from card where name in ('Sangan', 'Mirror Force')", rev);
        send(put(l("/seasons/" + season + "/rules")), "{\"tcgCutoff\":\"2020-01-01\",\"banListRevisionId\":" + rev + "}").andExpect(status().isOk());

        names("banStatus=LIMITED", List.of("Mirror Force", "Sangan"));
        names("banStatus=LIMITED&banStatus=FORBIDDEN", List.of("Mirror Force", "Pot of Greed", "Sangan"));
        Assertions.assertEquals(13, total("banStatus=UNLIMITED"));
        Assertions.assertEquals(15, total("banStatus=UNLIMITED&banStatus=LIMITED"));
        mvc.perform(get(pool("/cards?banStatus=NOPE")).with(as(yugi))).andExpect(status().isBadRequest());

        mvc.perform(get(pool("/cards?pageSize=10&page=1")).with(as(yugi)))
                .andExpect(jsonPath("$.total").value(16)).andExpect(jsonPath("$.items.length()").value(6)).andExpect(jsonPath("$.pageSize").value(10));
        mvc.perform(get(pool("/cards?pageSize=500")).with(as(yugi))).andExpect(status().isBadRequest());
        mvc.perform(get(pool("/cards?summon=WIZARD")).with(as(yugi))).andExpect(status().isBadRequest());
    }

    @Test
    void facetsListWhatsInThePool() throws Exception {
        mvc.perform(get(pool("/facets")).with(as(yugi)))
                .andExpect(listAt("$.attributes", List.of("DARK", "LIGHT", "WIND")))
                .andExpect(listAt("$.spellTypes", List.of("Normal", "Quick-Play")))
                .andExpect(listAt("$.trapTypes", List.of("Continuous", "Normal")))
                .andExpect(listAt("$.monsterTypes", List.of("Cyberse", "Dragon", "Fiend", "Machine", "Spellcaster", "Warrior")))
                .andExpect(listAt("$.archetypes", List.of("Blue-Eyes", "Code Talker", "Dark Magician", "Elemental HERO", "Odd-Eyes", "Synchron", "Utopia")));
    }

    // ---- helpers ----

    /** {@code typeline} is what follows the monster type in YGOPRODeck's type line; it drives the tags. */
    private void card(String name, String type, String frame, String race, String attribute, Integer level, Integer rank, Integer link,
                      Integer scale, Integer atk, Integer def, String archetype, String released, String... typeline) {
        String raw = typeline.length == 0 ? "{}"
                : "{\"typeline\":[\"" + race + "\",\"" + String.join("\",\"", typeline) + "\"]}";
        jdbc.update("""
                insert into card (passcodes, name, card_type, frame_type, race, attribute, level, rank, link_rating, pendulum_scale, atk, def,
                                  archetype, description, tcg_date, raw, content_hash)
                values (array[(select coalesce(max(id), 0) + 1000 from card)]::bigint[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'text', ?::date, ?::jsonb, 'h')""",
                name, type, frame, race, attribute, level, rank, link, scale, atk, def, archetype, released, raw);
    }

    private void names(String query, List<String> expected) throws Exception {
        Assertions.assertEquals(expected, page(query), query);
    }

    private List<String> page(String query) throws Exception {
        return JsonPath.read(mvc.perform(get(pool("/cards?" + query)).with(as(yugi)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].name");
    }

    private int total(String query) throws Exception {
        return JsonPath.read(mvc.perform(get(pool("/cards?" + query)).with(as(yugi))).andReturn().getResponse().getContentAsString(), "$.total");
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

    private static String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String body) throws Exception {
        req.with(csrf()).with(as(yugi));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private static RequestPostProcessor as(AppUser user) {
        CurrentUser principal = new CurrentUser(user.getId(), user.getDisplayName(), null, "dev", "u" + user.getId(), false);
        return authentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "dev"));
    }
}
