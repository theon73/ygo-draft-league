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
    void membersGetInOnlyTheOwnerManagesAndOutsidersGetNotFound() {
        AppUser owner = users.save(new AppUser("Owner"));
        AppUser member = users.save(new AppUser("Member"));
        AppUser outsider = users.save(new AppUser("Outsider"));
        League league = leagues.save(new League("Access League", owner));
        members.save(LeagueMember.forUser(league, owner, "Owner", LeagueRole.OWNER));
        members.save(LeagueMember.forUser(league, member, "Member", LeagueRole.MEMBER));
        String id = publicIds.encode(PublicIdType.LEAGUE, league.getId());

        assertEquals(LeagueRole.OWNER, access.require(id, as(owner), LeagueRole.OWNER).getRole());
        assertEquals("Access League", access.require(id, as(member), LeagueRole.MEMBER).getLeague().getName());

        var forbidden = assertThrows(ResponseStatusException.class, () -> access.require(id, as(member), LeagueRole.OWNER));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        var hidden = assertThrows(ResponseStatusException.class, () -> access.require(id, as(outsider), LeagueRole.MEMBER));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());
    }

    private static CurrentUser as(AppUser user) {
        return new CurrentUser(user.getId(), user.getDisplayName(), null, "dev", "u" + user.getId(), false);
    }
}
