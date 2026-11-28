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
import com.ygoleague.season.schedule.ResultService;
import com.ygoleague.season.schedule.ScheduleService;
import com.ygoleague.season.transaction.FreeAgencyService;
import com.ygoleague.season.transaction.TradeService;
import com.ygoleague.user.AppUser;
import com.ygoleague.user.AppUserRepository;
import com.ygoleague.user.UserIdentity;
import com.ygoleague.user.UserIdentityRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * A dev-profile league with a full-size season in progress (YGO-37), for reviewing layouts with
 * many players: ten players (the four dev users plus six placeholders), the draft done, three
 * weeks of results, a trade, a free-agent claim and a few conversations.
 *
 * <p>Everything goes through the real services, as alice (the owner and commissioner), so the
 * data is what the app itself would have written. It needs the card catalog (and, for the ban
 * list, the imported lists); without them it logs and skips. It's created once, when no league of
 * that name exists: it is dated from its first startup (week 4 is current then) and ages after
 * that. Delete the league's rows to seed a fresh one.
 *
 * <p>The values here (10 players, 20 rounds, March 2008) are example data, as the conventions
 * allow for dev seeds.
 */
@Component
@Profile(DevLoginController.PROFILE)
@Order(2)   // after DevSeed, which creates the users
class DevDemoLeague implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDemoLeague.class);

    static final String LEAGUE_NAME = "Ten Player Demo";
    private static final String SEASON_NAME = "Spring Demo";
    private static final List<String> PLACEHOLDERS = List.of("Erin", "Frank", "Grace", "Heidi", "Ivan", "Judy");
    private static final int ROUNDS = 20;
    private static final int WEEKS_PLAYED = 3;

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
    private final TradeService trades;
    private final FreeAgencyService freeAgency;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    DevDemoLeague(AppUserRepository users, UserIdentityRepository identities, LeagueRepository leagues, LeagueMemberRepository members,
                  SeasonRepository seasons, PublicIds publicIds, LeagueService leagueService, SeasonService seasonService,
                  SeasonRulesService rules, PoolService pool, DraftService draft, ScheduleService schedule, ResultService results,
                  TradeService trades, FreeAgencyService freeAgency, JdbcClient jdbc, TransactionTemplate tx) {
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
        this.trades = trades;
        this.freeAgency = freeAgency;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean exists = jdbc.sql("select count(*) from league where name = :n").param("n", LEAGUE_NAME).query(Long.class).single() > 0;
        if (exists) return;
        long cards = jdbc.sql("select count(*) from card").query(Long.class).single();
        if (cards == 0) {
            log.info("Dev seed: skipped {} (no card catalog yet; run ./gradlew syncCatalog and restart)", LEAGUE_NAME);
            return;
        }
        try {
            tx.executeWithoutResult(status -> seed());
            log.info("Dev seed: created {} (10 players, draft done, week {} current)", LEAGUE_NAME, WEEKS_PLAYED + 1);
        } catch (RuntimeException e) {
            // A dev convenience must never stop the app from starting; everything was rolled back.
            log.warn("Dev seed: couldn't create {}: {}", LEAGUE_NAME, e.getMessage(), e);
        }
    }

    private void seed() {
        Map<String, CurrentUser> as = new LinkedHashMap<>();
        for (String subject : List.of("alice", "bob", "carol", "dave")) {
            AppUser u = identities.findByProviderAndSubject(DevLoginController.PROVIDER, subject).map(UserIdentity::getUser)
                    .orElseThrow(() -> new IllegalStateException("Dev user " + subject + " is missing"));
            as.put(subject, new CurrentUser(u.getId(), u.getDisplayName(), null, DevLoginController.PROVIDER, subject, false));
        }
        CurrentUser alice = as.get("alice");

        // The league: alice owns it (LeagueService adds her), the other dev users and six placeholders join.
        String leagueId = leagueService.create(LEAGUE_NAME, "America/Chicago", alice).id();
        League league = leagues.findById(publicIds.decode(PublicIdType.LEAGUE, leagueId).orElseThrow()).orElseThrow();
        List<LeagueMember> joined = new ArrayList<>();
        for (String subject : List.of("bob", "carol", "dave")) {
            CurrentUser u = as.get(subject);
            joined.add(members.save(LeagueMember.forUser(league, users.getReferenceById(u.userId()), u.displayName(), LeagueRole.MEMBER)));
        }
        for (String name : PLACEHOLDERS) joined.add(members.save(LeagueMember.placeholder(league, name, LeagueRole.MEMBER)));
        members.flush();

        // The season: everyone plays, seats drawn at random.
        String seasonId = seasonService.create(leagueId, SEASON_NAME, alice).id();
        Season season = seasons.findById(publicIds.decode(PublicIdType.SEASON, seasonId).orElseThrow()).orElseThrow();
        List<String> everyone = new ArrayList<>();
        everyone.add(memberId(members.findByLeagueIdAndUserId(league.getId(), alice.userId()).orElseThrow()));
        joined.forEach(m -> everyone.add(memberId(m)));
        seasonService.setParticipants(leagueId, seasonId, everyone, alice);
        seasonService.randomizeSeats(leagueId, seasonId, alice);
        seasonService.setTeamName(leagueId, seasonId, everyone.get(1), "Bob's Burners", alice);
        seasonService.setTeamName(leagueId, seasonId, everyone.get(2), "Carol's Chain Burn", alice);

        // Started three weeks ago, so week 4 is current: a round robin, best of 3, with playoffs and a play-in.
        LocalDate start = LocalDate.now(ZoneId.of("America/Chicago"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(WEEKS_PLAYED);
        Long banList = jdbc.sql("""
                        select r.id from ban_list_revision r join ban_list l on l.id = r.ban_list_id
                        where l.name = 'TCG March 2008' and l.league_id is null order by r.id desc limit 1""")
                .query(Long.class).optional().orElse(null);
        rules.save(leagueId, seasonId, new SeasonRulesService.RulesUpdate(start, null, LocalDate.of(2008, 3, 1), banList,
                SeasonRulesDocument.CompetitionFormat.ROUND_ROBIN, 1, 3, null,
                2, 2, 7, false, 48, null,
                true, 6, 4, 3, 1), alice);
        pool.generate(leagueId, seasonId, alice);
        draft.saveSettings(leagueId, seasonId, new DraftService.DraftUpdate(SeasonRulesDocument.DraftOrder.SNAKE,
                List.of(new SeasonRulesDocument.RoundBlock(1, ROUNDS, 1)), null, false, null, null, null, null), alice);
        draft.start(leagueId, seasonId, alice);

        // The draft: the most-reprinted cards (a rough stand-in for staples) go first, with some variety.
        Random random = new Random(37);
        List<Long> board = new ArrayList<>(jdbc.sql("""
                        select p.card_id from pool_card p left join card_print cp on cp.card_id = p.card_id
                        where p.season_id = :s group by p.card_id order by count(cp.*) desc, p.card_id""")
                .param("s", season.getId()).query(Long.class).list());
        board.removeIf(id -> !pool.isAvailable(season, id));
        int picks = ROUNDS * everyone.size();
        if (board.size() < picks + 10) throw new IllegalStateException("The pool has only " + board.size() + " draftable cards");
        for (int i = 0; i < picks; i++) {
            long card = board.remove(random.nextInt(Math.min(5, board.size())));
            draft.pick(leagueId, seasonId, new DraftService.PickRequest(card, null), alice);
        }
        seasons.flush();
        if (season.getStatus() != SeasonStatus.ACTIVE) throw new IllegalStateException("The draft didn't finish");

        // Results for the weeks played, by the commissioner, plus one report this week waiting for confirmation.
        ScheduleService.ScheduleView sched = schedule.view(leagueId, seasonId, alice);
        int[][] scores = {{2, 0}, {2, 1}, {1, 2}, {0, 2}, {2, 1}};
        for (ScheduleService.WeekView week : sched.weeks()) {
            if (week.week() > WEEKS_PLAYED) break;
            for (ScheduleService.MatchView m : week.matches()) {
                if (m.b() == null) continue;
                int[] s = scores[random.nextInt(scores.length)];
                results.enter(leagueId, seasonId, m.id(), new ResultService.EnterRequest(s[0], s[1], null, null, null), alice);
            }
        }
        Map<String, CurrentUser> byMember = new LinkedHashMap<>();
        byMember.put(everyone.get(0), alice);
        for (int i = 1; i <= 3; i++) byMember.put(everyone.get(i), as.get(List.of("bob", "carol", "dave").get(i - 1)));
        sched.weeks().stream().filter(w -> w.week() == WEEKS_PLAYED + 1).flatMap(w -> w.matches().stream())
                .filter(m -> m.b() != null && byMember.containsKey(m.a().memberId()) && !byMember.containsKey(m.b().memberId()))
                .findFirst()
                .ifPresent(m -> results.report(leagueId, seasonId, m.id(), new ResultService.ReportRequest(2, 1, null, null),
                        byMember.get(m.a().memberId())));

        // Transactions: bob and carol trade, dave claims a free agent, and carol has an offer out to alice.
        String bob = everyone.get(1), carol = everyone.get(2), dave = everyone.get(3), aliceId = everyone.get(0);
        trades.message(leagueId, seasonId, carol, new TradeService.MessageRequest(null,
                "Any interest in moving one of your spells? I'm short on removal."), as.get("bob"));
        trades.message(leagueId, seasonId, bob, new TradeService.MessageRequest(null,
                "Maybe. I'd want a monster back, let me look at your roster."), as.get("carol"));
        TradeService.OfferView deal = trades.propose(leagueId, seasonId, new TradeService.OfferRequest(null, carol,
                List.of(card(firstOwned(season, bob))), List.of(card(firstOwned(season, carol))), null, "Straight swap?"), as.get("bob"));
        trades.accept(leagueId, seasonId, deal.id(), new TradeService.ActRequest(null), as.get("carol"));
        trades.message(leagueId, seasonId, bob, new TradeService.MessageRequest(null, "Done, good luck this week."), as.get("carol"));

        trades.propose(leagueId, seasonId, new TradeService.OfferRequest(null, aliceId,
                List.of(card(firstOwned(season, carol))), List.of(card(firstOwned(season, aliceId))), null,
                "Would you take this for one of yours?"), as.get("carol"));
        trades.message(leagueId, seasonId, aliceId, new TradeService.MessageRequest(null,
                "GG last week! Rematch in the playoffs?"), as.get("dave"));

        long freeAgent = board.stream().filter(id -> pool.isAvailable(season, id)).findFirst().orElseThrow();
        freeAgency.claim(leagueId, seasonId, new FreeAgencyService.ClaimRequest(dave, freeAgent, lastOwned(season, dave)), as.get("dave"));
    }

    private String memberId(LeagueMember m) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, m.getId());
    }

    private static TradeService.AssetIn card(long cardId) {
        return new TradeService.AssetIn("CARD", cardId, 1);
    }

    /** A player's earliest-drafted card they still hold. */
    private long firstOwned(Season season, String memberId) {
        return owned(season, memberId, "asc");
    }

    private long lastOwned(Season season, String memberId) {
        return owned(season, memberId, "desc");
    }

    private long owned(Season season, String memberId, String direction) {
        long member = publicIds.decode(PublicIdType.LEAGUE_MEMBER, memberId).orElseThrow();
        // Draft picks not since traded away (the demo's trades move first-round cards, so later picks are safe).
        return jdbc.sql("""
                        select d.card_id from draft_pick d join season_player sp on sp.id = d.season_player_id
                        where d.season_id = :s and sp.league_member_id = :m and d.card_id is not null
                          and not exists (select 1 from trade_offer_asset a join trade_offer o on o.id = a.offer_id
                                          where o.season_id = :s and o.status = 'EXECUTED' and a.card_id = d.card_id)
                        order by d.overall_pick""" + " " + direction + " limit 1")
                .param("s", season.getId()).param("m", member).query(Long.class).single();
    }
}
