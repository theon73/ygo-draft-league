package com.ygoleague.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ygoleague.league.League;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueRole;
import com.ygoleague.support.RepositoryTest;
import com.ygoleague.user.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@RepositoryTest
class SeasonRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeasonPlayerRepository seasonPlayers;
    @Autowired SeasonRulesRepository rules;

    AppUser theo;
    League league;
    Season season;
    LeagueMember member;

    @BeforeEach
    void setUp() {
        theo = em.persist(new AppUser("Theo"));
        league = em.persist(new League("Classic League", "classic-league", theo));
        member = em.persist(LeagueMember.forUser(league, theo, "Theo", LeagueRole.COMMISSIONER));
        season = em.persist(new Season(league, "Season 1"));
        em.flush();
    }

    @Test
    void newSeasonStartsInSetup() {
        assertEquals(SeasonStatus.SETUP, em.find(Season.class, season.getId()).getStatus());
    }

    @Test
    void seatsAreUniqueWithinASeason() {
        SeasonPlayer a = new SeasonPlayer(season, member);
        a.setSeat(1);
        em.persist(a);
        SeasonPlayer b = new SeasonPlayer(season, em.persist(LeagueMember.placeholder(league, "Oli", LeagueRole.PLAYER)));
        b.setSeat(1);
        assertThrows(DataIntegrityViolationException.class, () -> seasonPlayers.saveAndFlush(b));
    }

    @Test
    void seasonPlayerMustComeFromTheSeasonsLeague() {
        LeagueMember outsider = outsider();
        assertThrows(IllegalArgumentException.class, () -> new SeasonPlayer(season, outsider));
    }

    // The composite foreign keys enforce the same rule even if something bypasses the entity.
    // (One violation per test: Postgres aborts the transaction after the first.)

    @Test
    void databaseRejectsMemberFromAnotherLeague() {
        LeagueMember outsider = outsider();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into season_player (season_id, league_id, league_member_id) values (?, ?, ?)",
                season.getId(), league.getId(), outsider.getId()));
    }

    @Test
    void databaseRejectsMismatchedLeagueId() {
        LeagueMember outsider = outsider();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into season_player (season_id, league_id, league_member_id) values (?, ?, ?)",
                season.getId(), outsider.getLeague().getId(), outsider.getId()));
    }

    @Test
    void rulesInEffectFollowTheVersionTimeline() {
        em.persist(new SeasonRules(season, 1, 0, 1, "{\"draft\":{\"seats\":10}}", null));
        em.persist(new SeasonRules(season, 2, 0, 1, "{\"draft\":{\"seats\":8}}", theo));   // fixed during setup
        em.persist(new SeasonRules(season, 3, 4, 1, "{\"banList\":\"updated\"}", theo));   // mid-season change
        em.flush();
        em.clear();

        assertEquals(2, inEffect(0));
        assertEquals(2, inEffect(3));
        assertEquals(3, inEffect(4));
        assertEquals(3, inEffect(12));
        assertEquals(3, rules.findFirstBySeasonIdOrderByVersionDesc(season.getId()).orElseThrow().getVersion());
    }

    @Test
    void rulesJsonRoundTripsAsJsonb() {
        em.persistAndFlush(new SeasonRules(season, 1, 0, 1, "{\"draft\": {\"seats\": 10}}", null));
        Integer seats = jdbc.queryForObject(
                "select (rules -> 'draft' ->> 'seats')::int from season_rules where season_id = ?",
                Integer.class, season.getId());
        assertEquals(10, seats);
    }

    @Test
    void versionNumbersAreUniquePerSeason() {
        em.persist(new SeasonRules(season, 1, 0, 1, "{}", null));
        assertThrows(DataIntegrityViolationException.class,
                () -> rules.saveAndFlush(new SeasonRules(season, 1, 2, 1, "{}", theo)));
    }

    private LeagueMember outsider() {
        League other = em.persist(new League("Other", "other", theo));
        return em.persistAndFlush(LeagueMember.placeholder(other, "Outsider", LeagueRole.PLAYER));
    }

    private int inEffect(int week) {
        return rules.findFirstBySeasonIdAndEffectiveFromWeekLessThanEqualOrderByVersionDesc(season.getId(), week)
                .orElseThrow().getVersion();
    }
}
