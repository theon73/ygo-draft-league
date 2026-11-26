package com.ygoleague.auth;

import com.ygoleague.league.League;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueMemberRepository;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.league.LeagueRole;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import com.ygoleague.user.UserIdentity;
import com.ygoleague.user.UserIdentityRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test users and a sample league for the dev login, created on startup under the {@code dev}
 * profile. Idempotent: anything that already exists is left alone.
 *
 * <p>This is Java rather than a dev-only Flyway location, because Flyway refuses to start when
 * the database has a migration it can't see. Switching between dev and non-dev runs on the
 * same local database would then break.
 */
@Component
@Profile(DevLoginController.PROFILE)
class DevSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    static final String LEAGUE_NAME = "Dev League";

    /** subject -> display name. alice is the commissioner and (via application-dev.yml) a site admin. */
    private static final Map<String, String> USERS = new LinkedHashMap<>();
    static {
        USERS.put("alice", "Alice");
        USERS.put("bob", "Bob");
        USERS.put("carol", "Carol");
        USERS.put("dave", "Dave");
    }

    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final JdbcClient jdbc;

    DevSeed(AppUserRepository users, UserIdentityRepository identities, LeagueRepository leagues,
            LeagueMemberRepository members, JdbcClient jdbc) {
        this.users = users;
        this.identities = identities;
        this.leagues = leagues;
        this.members = members;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, AppUser> bySubject = new LinkedHashMap<>();
        USERS.forEach((subject, name) -> bySubject.put(subject,
                identities.findByProviderAndSubject(DevLoginController.PROVIDER, subject)
                        .map(UserIdentity::getUser)
                        .orElseGet(() -> {
                            AppUser u = users.save(new AppUser(name));
                            identities.save(new UserIdentity(u, DevLoginController.PROVIDER, subject, null));
                            return u;
                        })));

        boolean leagueExists = jdbc.sql("select count(*) from league where name = :n")
                .param("n", LEAGUE_NAME).query(Long.class).single() > 0;
        if (!leagueExists) {
            League league = leagues.save(new League(LEAGUE_NAME, bySubject.get("alice")));
            members.save(LeagueMember.forUser(league, bySubject.get("alice"), "Alice", LeagueRole.COMMISSIONER));
            members.save(LeagueMember.forUser(league, bySubject.get("bob"), "Bob", LeagueRole.PLAYER));
            members.save(LeagueMember.forUser(league, bySubject.get("carol"), "Carol", LeagueRole.PLAYER));
            members.save(LeagueMember.forUser(league, bySubject.get("dave"), "Dave", LeagueRole.VIEWER));
            members.save(LeagueMember.placeholder(league, "Erin (no account yet)", LeagueRole.PLAYER));
            log.info("Dev seed: created {} with 4 users and a placeholder", LEAGUE_NAME);
        }
    }
}
