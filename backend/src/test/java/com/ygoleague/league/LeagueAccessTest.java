package com.ygoleague.league;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.support.PostgresTestConfig;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false"})
@Import(PostgresTestConfig.class)
class LeagueAccessTest {

    @Autowired LeagueAccess access;
    @Autowired AppUserRepository users;
    @Autowired LeagueRepository leagues;
    @Autowired LeagueMemberRepository members;
    @Autowired PublicIds publicIds;

    @Test
    void membersNeedTheRequiredRoleAndOutsidersGetNotFound() {
        AppUser commish = users.save(new AppUser("Commish"));
        AppUser player = users.save(new AppUser("Player"));
        AppUser outsider = users.save(new AppUser("Outsider"));
        League league = leagues.save(new League("Access League", commish));
        members.save(LeagueMember.forUser(league, commish, "Commish", LeagueRole.COMMISSIONER));
        members.save(LeagueMember.forUser(league, player, "Player", LeagueRole.PLAYER));
        String id = publicIds.encode(PublicIdType.LEAGUE, league.getId());

        assertEquals(LeagueRole.COMMISSIONER, access.require(id, as(commish), LeagueRole.COMMISSIONER).getRole());
        assertEquals("Access League", access.require(id, as(player), LeagueRole.VIEWER).getLeague().getName());

        var forbidden = assertThrows(ResponseStatusException.class, () -> access.require(id, as(player), LeagueRole.COMMISSIONER));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        var hidden = assertThrows(ResponseStatusException.class, () -> access.require(id, as(outsider), LeagueRole.VIEWER));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());
    }

    private static CurrentUser as(AppUser user) {
        return new CurrentUser(user.getId(), user.getDisplayName(), null, "dev", "u" + user.getId(), false);
    }
}
