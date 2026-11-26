package com.ygoleague.league;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.support.RepositoryTest;
import com.ygoleague.user.AppUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@RepositoryTest
class LeagueRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired LeagueRepository leagues;
    @Autowired LeagueMemberRepository members;

    AppUser theo;
    League league;

    @BeforeEach
    void setUp() {
        theo = em.persist(new AppUser("Theo"));
        league = em.persist(new League("Classic League", theo));
        em.persist(LeagueMember.forUser(league, theo, "Theo", LeagueRole.OWNER));
        em.flush();
    }

    @Test
    void myLeaguesListsEveryLeagueTheUserBelongsTo() {
        League other = em.persist(new League("Another League", theo));
        em.persist(LeagueMember.forUser(other, theo, "Theo", LeagueRole.MEMBER));
        League notMine = em.persist(new League("Not Mine", theo));
        em.flush();

        List<String> names = leagues.findAllForUser(theo.getId()).stream().map(League::getName).toList();
        assertEquals(List.of("Another League", "Classic League"), names);
        assertTrue(members.findByLeagueIdAndUserId(notMine.getId(), theo.getId()).isEmpty());
    }

    @Test
    void manyPlaceholdersButOneMembershipPerUser() {
        em.persist(LeagueMember.placeholder(league, "Oli", LeagueRole.MEMBER));
        em.persist(LeagueMember.placeholder(league, "Zach", LeagueRole.MEMBER));
        em.flush();
        assertEquals(3, members.findByLeagueIdOrderByDisplayName(league.getId()).size());

        assertThrows(DataIntegrityViolationException.class,
                () -> members.saveAndFlush(LeagueMember.forUser(league, theo, "Theo again", LeagueRole.MEMBER)));
    }

    @Test
    void displayNamesAreUniqueWithinALeague() {
        assertThrows(DataIntegrityViolationException.class,
                () -> members.saveAndFlush(LeagueMember.placeholder(league, "Theo", LeagueRole.MEMBER)));
    }

    @Test
    void inviteMustBeEitherOpenOrClaimNotBoth() {
        LeagueMember placeholder = em.persistAndFlush(LeagueMember.placeholder(league, "Oli", LeagueRole.MEMBER));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into league_invite (league_id, code, role, member_id, max_uses, created_by)
                values (?, 'both', 'MEMBER', ?, 1, ?)""", league.getId(), placeholder.getId(), theo.getId()));
    }

    @Test
    void claimInviteCannotPointAtAnotherLeaguesMember() {
        League other = em.persist(new League("Other", theo));
        LeagueMember foreign = em.persistAndFlush(LeagueMember.placeholder(other, "Oli", LeagueRole.MEMBER));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into league_invite (league_id, code, member_id, max_uses, created_by)
                values (?, 'cross', ?, 1, ?)""", league.getId(), foreign.getId(), theo.getId()));
    }
}
