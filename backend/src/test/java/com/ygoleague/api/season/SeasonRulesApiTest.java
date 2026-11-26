package com.ygoleague.api.season;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ygoleague.auth.CurrentUser;
import com.ygoleague.calendar.SeasonCalendar;
import com.ygoleague.calendar.WeekBoundary;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class SeasonRulesApiTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    AppUser yugi, kaiba, outsider;
    String league, season;
    long officialRevision, otherLeaguesRevision;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate season_seat_draw, season_commissioner, season_rules, season_player, season, league_invite, "
                + "league_member, ban_list_entry, ban_list_revision, ban_list, league, user_identity, app_user restart identity cascade");
        yugi = users.save(new AppUser("Yugi"));
        kaiba = users.save(new AppUser("Kaiba"));
        outsider = users.save(new AppUser("Outsider"));
        league = read(send(post("/api/leagues"), yugi, "{\"name\":\"Battle City\",\"timeZone\":\"America/Chicago\"}"), "$.id");
        String code = read(send(post(l("/invites")), yugi, "{}"), "$.code");
        mvc.perform(post("/api/invites/" + code + "/accept").with(csrf()).with(as(kaiba))).andExpect(status().isOk());
        season = read(send(post(l("/seasons")), yugi, "{\"name\":\"S1\"}"), "$.id");

        long official = jdbc.queryForObject("insert into ban_list (official_key, name, effective_date) values ('tcg/2008-03-01', 'TCG March 2008', '2008-03-01') returning id", Long.class);
        jdbc.update("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'a')", official);
        officialRevision = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 2, 'b') returning id", Long.class, official);
        String other = read(send(post("/api/leagues"), outsider, "{\"name\":\"Elsewhere\"}"), "$.id");
        long otherLeagueId = jdbc.queryForObject("select id from league where name = 'Elsewhere'", Long.class);
        long custom = jdbc.queryForObject("insert into ban_list (league_id, name) values (?, 'Their house list') returning id", Long.class, otherLeagueId);
        otherLeaguesRevision = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'c') returning id", Long.class, custom);
    }

    @Test
    void newSeasonHasNoRulesYetAndFollowsTheLeaguesWeekStart() throws Exception {
        mvc.perform(get(rules()).with(as(kaiba)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.calendar.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.calendar.weekStartsOn").value("MONDAY"))
                .andExpect(jsonPath("$.calendar.weekStartLocked").value(false))
                .andExpect(jsonPath("$.weeks.length()").value(0));
        mvc.perform(get(rules()).with(as(yugi))).andExpect(jsonPath("$.canManage").value(true));
        mvc.perform(get(rules()).with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test
    void savingDuringSetupLaysOutTheWeeksAndVersionsEveryChange() throws Exception {
        save(yugi, "{\"startDate\":\"2030-01-02\",\"regularWeeks\":3,\"tcgCutoff\":\"2008-03-01\",\"banListRevisionId\":" + officialRevision + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.effectiveFromWeek").value(0))
                .andExpect(jsonPath("$.calendar.regularWeeks").value(3))
                .andExpect(jsonPath("$.format.tcgCutoff").value("2008-03-01"))
                .andExpect(jsonPath("$.format.banList.name").value("TCG March 2008"))
                .andExpect(jsonPath("$.format.banList.revision").value(2))
                .andExpect(jsonPath("$.currentWeek").value(0))
                // 2030-01-02 is a Wednesday, so week 1 starts the next Monday (league weeks start Monday 00:00).
                .andExpect(listAt("$.weeks[*].start", List.of("2030-01-07T00:00:00-06:00", "2030-01-14T00:00:00-06:00", "2030-01-21T00:00:00-06:00")));

        // Saving the same rules again is a no-op; a real change is a new version, both from week 0.
        save(yugi, "{\"startDate\":\"2030-01-02\",\"regularWeeks\":3,\"tcgCutoff\":\"2008-03-01\",\"banListRevisionId\":" + officialRevision + "}")
                .andExpect(jsonPath("$.version").value(1));
        save(yugi, "{\"startDate\":\"2030-01-02\",\"regularWeeks\":9,\"tcgCutoff\":\"2008-03-01\"}")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.format.banList").doesNotExist())
                .andExpect(listAt("$.history[*].version", List.of(2, 1)))
                .andExpect(listAt("$.history[*].by", List.of("Yugi", "Yugi")));

        // Until the season begins, its weeks follow the league's week start.
        send(patch("/api/leagues/" + league), yugi, "{\"weekStartsOn\":\"WEDNESDAY\",\"weekStartsAt\":\"20:00\"}").andExpect(status().isOk());
        mvc.perform(get(rules()).with(as(kaiba)))
                .andExpect(jsonPath("$.calendar.weekStartsOn").value("WEDNESDAY"))
                .andExpect(jsonPath("$.weeks[0].start").value("2030-01-02T20:00:00-06:00"));
    }

    @Test
    void invalidRulesAreRejected() throws Exception {
        save(yugi, "{\"regularWeeks\":0}").andExpect(status().isBadRequest());
        save(yugi, "{\"regularWeeks\":53}").andExpect(status().isBadRequest());
        save(yugi, "{\"tcgCutoff\":\"1999-12-31\"}").andExpect(status().isBadRequest());
        save(yugi, "{\"banListRevisionId\":999999}").andExpect(status().isBadRequest());
        save(yugi, "{\"banListRevisionId\":" + otherLeaguesRevision + "}").andExpect(status().isBadRequest());   // another league's list
        save(kaiba, "{\"regularWeeks\":5}").andExpect(status().isForbidden());
        save(outsider, "{\"regularWeeks\":5}").andExpect(status().isNotFound());
        // Partial rules are fine while setting up.
        save(yugi, "{\"tcgCutoff\":\"2008-03-01\"}").andExpect(status().isOk()).andExpect(jsonPath("$.weeks.length()").value(0));
    }

    @Test
    void leaguesChooseFromOfficialListsAndTheirOwn() throws Exception {
        long ownLeagueId = jdbc.queryForObject("select id from league where name = 'Battle City'", Long.class);
        long own = jdbc.queryForObject("insert into ban_list (league_id, name) values (?, 'House rules') returning id", Long.class, ownLeagueId);
        long ownRevision = jdbc.queryForObject("insert into ban_list_revision (ban_list_id, revision, content_hash) values (?, 1, 'd') returning id", Long.class, own);

        mvc.perform(get(l("/ban-lists")).with(as(kaiba)))
                .andExpect(listAt("$[*].name", List.of("TCG March 2008", "House rules")))
                .andExpect(jsonPath("$[0].latestRevision").value(2))
                .andExpect(jsonPath("$[0].latestRevisionId").value(officialRevision));
        save(yugi, "{\"banListRevisionId\":" + ownRevision + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format.banList.official").value(false));
    }

    @Test
    void aRoundRobinsWeeksFollowHowOftenPairsMeetAndThePlayerCount() throws Exception {
        players(4);
        save(yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competition.format").value("ROUND_ROBIN"))
                .andExpect(jsonPath("$.competition.players").value(4))
                .andExpect(jsonPath("$.competition.rounds").value(3))
                .andExpect(jsonPath("$.competition.matchesPerPlayer").value(3))
                .andExpect(jsonPath("$.competition.byes").value(false))
                .andExpect(jsonPath("$.calendar.regularWeeks").value(3))
                .andExpect(jsonPath("$.calendar.weeksDerived").value(true))
                .andExpect(jsonPath("$.weeks.length()").value(3));

        // A fifth player: 5 rounds, each with a bye. Nothing to re-save, the weeks just follow.
        players(1);
        mvc.perform(get(rules()).with(as(kaiba)))
                .andExpect(jsonPath("$.competition.rounds").value(5))
                .andExpect(jsonPath("$.competition.matchesPerPlayer").value(4))
                .andExpect(jsonPath("$.competition.byes").value(true))
                .andExpect(jsonPath("$.weeks.length()").value(5));

        // Double round-robin.
        save(yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":2}")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.weeks.length()").value(10));

        save(yugi, "{\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"regularWeeks\":9}").andExpect(status().isBadRequest());
        save(yugi, "{\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":0}").andExpect(status().isBadRequest());
        save(yugi, "{\"roundRobinCycles\":2}").andExpect(status().isBadRequest());           // cycles without the format
        // Chosen but not filled in yet is fine during setup: no weeks until the cycles are set.
        save(yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.weeks.length()").value(0));
    }

    @Test
    void onceStartedTheCompetitionFormatIsFixed() throws Exception {
        players(4);
        save(yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1}").andExpect(status().isOk());
        jdbc.update("update season set status = 'DRAFTING'");
        save(yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":2,\"effectiveFromWeek\":0}")
                .andExpect(status().isConflict());
        save(yugi, "{\"startDate\":\"2030-01-07\",\"competitionFormat\":\"ROUND_ROBIN\",\"roundRobinCycles\":1,\"tcgCutoff\":\"2008-03-01\",\"effectiveFromWeek\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void onceStartedChangesTakeEffectFromNowOnAndTheCalendarIsFixed() throws Exception {
        // A 10-week season that started about four weeks ago.
        LocalDate start = LocalDate.now(CHICAGO).minusWeeks(4);
        save(yugi, "{\"startDate\":\"" + start + "\",\"regularWeeks\":10,\"tcgCutoff\":\"2008-03-01\"}").andExpect(status().isOk());
        jdbc.update("update season set status = 'ACTIVE'");
        int now = new SeasonCalendar(new WeekBoundary(CHICAGO, DayOfWeek.MONDAY, LocalTime.MIDNIGHT), start, 10).weekNumberAt(Instant.now());

        mvc.perform(get(rules()).with(as(yugi)))
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.currentWeek").value(now))
                .andExpect(jsonPath("$.minEffectiveWeek").value(now));

        String banListChange = "\"startDate\":\"" + start + "\",\"regularWeeks\":10,\"tcgCutoff\":\"2008-03-01\",\"banListRevisionId\":" + officialRevision;
        save(yugi, "{" + banListChange + "}").andExpect(status().isBadRequest());                                  // must say from when
        save(yugi, "{" + banListChange + ",\"effectiveFromWeek\":" + (now - 1) + "}").andExpect(status().isBadRequest());   // no retroactive changes
        save(yugi, "{\"startDate\":\"" + start + "\",\"regularWeeks\":12,\"tcgCutoff\":\"2008-03-01\",\"effectiveFromWeek\":" + now + "}")
                .andExpect(status().isConflict());                                                                  // calendar is fixed
        save(yugi, "{" + banListChange + ",\"effectiveFromWeek\":" + (now + 1) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.effectiveFromWeek").value(now + 1));
        // The next change can't take effect before that one.
        mvc.perform(get(rules()).with(as(yugi))).andExpect(jsonPath("$.minEffectiveWeek").value(now + 1));
        Assertions.assertEquals(2, jdbc.queryForObject("select count(*) from season_rules", Long.class));
    }

    // ---- helpers ----

    /** Adds n placeholder members to the league and seats them in the season, after anyone already seated. */
    private void players(int n) {
        long leagueId = jdbc.queryForObject("select id from league where name = 'Battle City'", Long.class);
        long seasonId = jdbc.queryForObject("select id from season where league_id = ?", Long.class, leagueId);
        int seated = jdbc.queryForObject("select count(*) from season_player where season_id = ?", Integer.class, seasonId);
        for (int i = 1; i <= n; i++) {
            long member = jdbc.queryForObject("insert into league_member (league_id, display_name, role) values (?, ?, 'MEMBER') returning id",
                    Long.class, leagueId, "Seat " + (seated + i));
            jdbc.update("insert into season_player (season_id, league_id, league_member_id, seat) values (?, ?, ?, ?)",
                    seasonId, leagueId, member, seated + i);
        }
    }

    private String l(String path) {
        return "/api/leagues/" + league + path;
    }

    private String rules() {
        return l("/seasons/" + season + "/rules");
    }

    private ResultActions save(AppUser by, String body) throws Exception {
        return send(put(rules()), by, body);
    }

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
