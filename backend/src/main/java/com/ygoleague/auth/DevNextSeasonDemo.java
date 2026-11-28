package com.ygoleague.auth;

import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.draft.DraftService;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueMemberRepository;
import com.ygoleague.league.LeagueRepository;
import com.ygoleague.league.LeagueRole;
import com.ygoleague.league.LeagueService;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonRepository;
import com.ygoleague.season.SeasonService;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.pool.PoolService;
import com.ygoleague.season.rules.SeasonRulesDocument;
import com.ygoleague.season.rules.SeasonRulesService;
import com.ygoleague.season.schedule.PlayoffService;
import com.ygoleague.season.schedule.ResultService;
import com.ygoleague.season.schedule.ScheduleService;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import com.ygoleague.user.UserIdentity;
import com.ygoleague.user.UserIdentityRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A dev-profile league whose only season is complete (YGO-39), for trying "Start the next season"
 * and the reverse-standings draft order: six players (the four dev users plus two placeholders), a
 * short draft, a finished round robin, and four-team playoffs with a champion and a runner-up.
 *
 * <p>Like {@link DevDemoLeague}, everything goes through the real services as alice (the owner),
 * it needs the card catalog, and it's created once, when no league of that name exists. Delete the
 * league's rows to seed it again (e.g. after trying the next season).
 *
 * <p>The values here (6 players, 5 rounds, March 2008) are example data.
 */
@Component
@Profile(DevLoginController.PROFILE)
@Order(3)   // after DevSeed, which creates the users
class DevNextSeasonDemo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevNextSeasonDemo.class);

    static final String LEAGUE_NAME = "Next Season Demo";
    private static final String SEASON_NAME = "Season 1";
    private static final List<String> PLACEHOLDERS = List.of("Erin", "Frank");
    private static final int ROUNDS = 5;

    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final LeagueRepository leagues;
    private final LeagueMemberRepository members;
    private final SeasonRepository seasons;
    private final PublicIds publicIds;
    private final LeagueService leagueService;
    private final SeasonService seasonService;
    private final SeasonRulesService rules;
    private final PoolService pool;
    private final DraftService draft;
    private final ScheduleService schedule;
    private final ResultService results;
    private final PlayoffService playoffs;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    DevNextSeasonDemo(AppUserRepository users, UserIdentityRepository identities, LeagueRepository leagues, LeagueMemberRepository members,
                      SeasonRepository seasons, PublicIds publicIds, LeagueService leagueService, SeasonService seasonService,
                      SeasonRulesService rules, PoolService pool, DraftService draft, ScheduleService schedule, ResultService results,
                      PlayoffService playoffs, JdbcClient jdbc, TransactionTemplate tx) {
        this.users = users;
        this.identities = identities;
        this.leagues = leagues;
        this.members = members;
        this.seasons = seasons;
        this.publicIds = publicIds;
        this.leagueService = leagueService;
        this.seasonService = seasonService;
        this.rules = rules;
        this.pool = pool;
        this.draft = draft;
        this.schedule = schedule;
        this.results = results;
        this.playoffs = playoffs;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("select count(*) from league where name = :n").param("n", LEAGUE_NAME).query(Long.class).single() > 0;
        if (exists) return;
        if (jdbc.sql("select count(*) from card").query(Long.class).single() == 0) {
            log.info("Dev seed: skipped {} (no card catalog yet; run ./gradlew syncCatalog and restart)", LEAGUE_NAME);
            return;
        }
        try {
            tx.executeWithoutResult(status -> seed());
            log.info("Dev seed: created {} ({} complete, ready for the next season)", LEAGUE_NAME, SEASON_NAME);
        } catch (RuntimeException e) {
            // A dev convenience must never stop the app from starting; everything was rolled back.
            log.warn("Dev seed: couldn't create {}: {}", LEAGUE_NAME, e.getMessage(), e);
        }
    }

    private void seed() {
        CurrentUser alice = devUser("alice");

        String leagueId = leagueService.create(LEAGUE_NAME, "America/Chicago", alice).id();
        League league = leagues.findById(publicIds.decode(PublicIdType.LEAGUE, leagueId).orElseThrow()).orElseThrow();
        List<String> everyone = new ArrayList<>();
        everyone.add(memberId(members.findByLeagueIdAndUserId(league.getId(), alice.userId()).orElseThrow()));
        for (String subject : List.of("bob", "carol", "dave")) {
            CurrentUser u = devUser(subject);
            everyone.add(memberId(members.save(LeagueMember.forUser(league, users.getReferenceById(u.userId()), u.displayName(), LeagueRole.MEMBER))));
        }
        for (String name : PLACEHOLDERS) everyone.add(memberId(members.save(LeagueMember.placeholder(league, name, LeagueRole.MEMBER))));
        members.flush();

        String seasonId = seasonService.create(leagueId, SEASON_NAME, alice).id();
        Season season = seasons.findById(publicIds.decode(PublicIdType.SEASON, seasonId).orElseThrow()).orElseThrow();
        seasonService.setParticipants(leagueId, seasonId, everyone, alice);
        seasonService.randomizeSeats(leagueId, seasonId, alice);
        seasonService.setTeamName(leagueId, seasonId, everyone.get(1), "Bob's Burners", alice);

        // A single round robin (5 weeks for 6 players) that ended last week; best of 3; top four to the playoffs.
        LocalDate start = LocalDate.now(ZoneId.of("America/Chicago"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(everyone.size());
        Long banList = jdbc.sql("""
                        select r.id from ban_list_revision r join ban_list l on l.id = r.ban_list_id
                        where l.name = 'TCG March 2008' and l.league_id is null order by r.id desc limit 1""")
                .query(Long.class).optional().orElse(null);
        rules.save(leagueId, seasonId, new SeasonRulesService.RulesUpdate(start, null, LocalDate.of(2008, 3, 1), banList,
                SeasonRulesDocument.CompetitionFormat.ROUND_ROBIN, 1, 3, null,
                2, 2, null, false, 48, null,
                true, 4, 0, 3, null), alice);
        pool.generate(leagueId, seasonId, alice);
        draft.saveSettings(leagueId, seasonId, new DraftService.DraftUpdate(SeasonRulesDocument.DraftOrder.SNAKE,
                List.of(new SeasonRulesDocument.RoundBlock(1, ROUNDS, 1)), null, false, null, null, null, null), alice);
        draft.start(leagueId, seasonId, alice);

        // The draft: the most-reprinted cards first, in order.
        List<Long> board = new ArrayList<>(jdbc.sql("""
                        select p.card_id from pool_card p left join card_print cp on cp.card_id = p.card_id
                        where p.season_id = :s group by p.card_id order by count(cp.*) desc, p.card_id""")
                .param("s", season.getId()).query(Long.class).list());
        board.removeIf(id -> !pool.isAvailable(season, id));
        for (int i = 0; i < ROUNDS * everyone.size(); i++) {
            draft.pick(leagueId, seasonId, new DraftService.PickRequest(board.remove(0), null), alice);
        }
        seasons.flush();
        if (season.getStatus() != SeasonStatus.ACTIVE) throw new IllegalStateException("The draft didn't finish");

        // Every regular-season result, entered by the commissioner. Scores vary by match so the table spreads out.
        int[][] scores = {{2, 0}, {0, 2}, {2, 1}, {1, 2}, {2, 1}, {2, 0}, {1, 2}};
        int n = 0;
        for (ScheduleService.WeekView week : schedule.view(leagueId, seasonId, alice).weeks()) {
            for (ScheduleService.MatchView m : week.matches()) {
                if (m.b() == null) continue;
                int[] s = scores[n++ % scores.length];
                results.enter(leagueId, seasonId, m.id(), new ResultService.EnterRequest(s[0], s[1], null, null, null), alice);
            }
        }

        // The playoffs: semifinals, then the final; its result completes the season.
        playoffs.start(leagueId, seasonId, alice);
        for (int round = 0; round < 2; round++) {
            for (var m : playoffs.view(leagueId, seasonId, alice).matches()) {
                if (m.a() == null || m.b() == null || m.result() != null) continue;
                results.enter(leagueId, seasonId, m.id(), new ResultService.EnterRequest(round == 0 ? 2 : 1, round == 0 ? 1 : 2, null, null, null), alice);
            }
        }
        seasons.flush();
        String status = jdbc.sql("select status from season where id = :s").param("s", season.getId()).query(String.class).single();
        if (!status.equals("COMPLETE")) {
            throw new IllegalStateException("The playoffs didn't complete the season");
        }
    }

    private CurrentUser devUser(String subject) {
        AppUser u = identities.findByProviderAndSubject(DevLoginController.PROVIDER, subject).map(UserIdentity::getUser)
                .orElseThrow(() -> new IllegalStateException("Dev user " + subject + " is missing"));
        return new CurrentUser(u.getId(), u.getDisplayName(), null, DevLoginController.PROVIDER, subject, false);
    }

    private String memberId(LeagueMember m) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, m.getId());
    }
}
