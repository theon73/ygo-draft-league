package com.ygoleague.season.overview;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.draft.DraftService;
import com.ygoleague.draft.DraftService.DraftView;
import com.ygoleague.league.LeagueMember;
import com.ygoleague.league.LeagueMemberRepository;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonRepository;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.schedule.PlayoffService;
import com.ygoleague.season.schedule.PlayoffService.BracketMatch;
import com.ygoleague.season.schedule.ScheduleService;
import com.ygoleague.season.schedule.ScheduleService.MatchView;
import com.ygoleague.season.schedule.ScheduleService.PlayerRef;
import com.ygoleague.season.schedule.ScheduleService.ResultView;
import com.ygoleague.season.transaction.TradeService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Needs you" (YGO-31): what's waiting on the viewer in a season, or across all their leagues:
 * their pick, a result to report or confirm, a trade offer, and for commissioners disputes, trade
 * approvals, starting the draft or the playoffs, and completing the season.
 *
 * <p>Read-only, and built from the same services the season's pages use, called as the viewer, so
 * an item appears exactly when the page shows the matching button ({@code canConfirm},
 * {@code canStart}, a pickable slot, …). That costs a few queries per season, which is fine for
 * the handful of seasons a person is in; completed seasons are skipped.
 */
@Service
public class NeedsYouService {

    /**
     * One thing waiting on the viewer.
     *
     * @param kind    PICK, REPORT, CONFIRM, TRADE_OFFER, DISPUTE, TRADE_APPROVAL, START_DRAFT, FINISH_SETUP,
     *                START_PLAYOFFS or COMPLETE_SEASON
     * @param title   what to do, e.g. "Confirm Carol's report"
     * @param detail  the specifics, e.g. "Bob 1–2 Carol · week 2"
     * @param tab     the season tab where it's done ("draft", "schedule", "trades", …)
     * @param due     when it's due, if it has a deadline
     * @param matchId the match, for result items
     * @param commissioner it's a commissioner's job, not the viewer's own as a player
     */
    public record Item(String kind, String leagueId, String league, String seasonId, String season, String title, String detail,
                       String tab, Instant due, String matchId, boolean commissioner) {}

    private final SeasonAccess access;
    private final SeasonRepository seasons;
    private final LeagueMemberRepository members;
    private final DraftService draft;
    private final ScheduleService schedule;
    private final PlayoffService playoffs;
    private final TradeService trades;
    private final PublicIds publicIds;
    private final com.ygoleague.season.rules.SeasonRulesService rules;

    public NeedsYouService(SeasonAccess access, SeasonRepository seasons, LeagueMemberRepository members, DraftService draft,
                           ScheduleService schedule, PlayoffService playoffs, TradeService trades, PublicIds publicIds,
                           com.ygoleague.season.rules.SeasonRulesService rules) {
        this.rules = rules;
        this.access = access;
        this.seasons = seasons;
        this.members = members;
        this.draft = draft;
        this.schedule = schedule;
        this.playoffs = playoffs;
        this.trades = trades;
        this.publicIds = publicIds;
    }

    /** Across every league the caller belongs to: each season that isn't complete. */
    @Transactional(readOnly = true)
    public List<Item> forUser(CurrentUser caller) {
        List<Item> out = new ArrayList<>();
        for (LeagueMember m : members.findMembershipsOfUser(caller.userId())) {
            String leagueId = publicIds.encode(PublicIdType.LEAGUE, m.getLeague().getId());
            for (Season s : seasons.findByLeagueIdOrderByCreatedAtDesc(m.getLeague().getId())) {
                if (s.getStatus() == SeasonStatus.COMPLETE) continue;
                out.addAll(items(leagueId, s, caller));
            }
        }
        out.sort(ORDER);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Item> forSeason(String leagueId, String seasonId, CurrentUser caller) {
        Season s = access.member(leagueId, seasonId, caller).season();
        List<Item> out = new ArrayList<>(items(leagueId, s, caller));
        out.sort(ORDER);
        return out;
    }

    /** Your own things first (a pick before anything), then by deadline. */
    private static final Comparator<Item> ORDER = Comparator
            .comparing(Item::commissioner)
            .thenComparing((Item i) -> !i.kind().equals("PICK"))
            .thenComparing(Item::due, Comparator.nullsLast(Comparator.naturalOrder()));

    private List<Item> items(String leagueId, Season s, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, publicIds.encode(PublicIdType.SEASON, s.getId()), caller);
        String seasonId = publicIds.encode(PublicIdType.SEASON, s.getId());
        String league = s.getLeague().getName();
        List<Item> out = new ArrayList<>();
        Adder add = (kind, title, detail, tab, due, matchId, commish) ->
                out.add(new Item(kind, leagueId, league, seasonId, s.getName(), title, detail, tab, due, matchId, commish));

        switch (s.getStatus()) {
            case SETUP -> {
                if (!ref.commissioner()) break;
                DraftView d = draft.get(leagueId, seasonId, caller);
                List<DraftService.Check> missing = d.readiness().stream().filter(c -> !c.ok()).toList();
                if (missing.isEmpty()) add.add("START_DRAFT", "Start the draft", "Everything is ready.", "draft", d.scheduledStart(), null, true);
                else add.add("FINISH_SETUP", "Finish setting up " + s.getName(),
                        missing.getFirst().detail() + (missing.size() > 1 ? " (and " + (missing.size() - 1) + " more)" : ""),
                        // Into the setup wizard where it was left (YGO-32), else the Overview's checklist.
                        s.getSetupStep() != null ? "setup/" + s.getSetupStep() : "overview", null, null, true);
            }
            case DRAFTING -> {
                DraftView d = draft.get(leagueId, seasonId, caller);
                if (d.mySeat() == null) break;
                List<DraftService.PickableSlot> mine = d.pickable().stream().filter(p -> p.seat() == d.mySeat()).toList();
                if (mine.isEmpty()) break;
                boolean onClock = mine.stream().anyMatch(p -> !p.makeup());
                DraftService.PickableSlot first = mine.getFirst();
                add.add("PICK", onClock ? "It's your pick" : "You owe a skipped pick",
                        "Pick #" + first.overallPick() + " · round " + first.round() + (mine.size() > 1 ? " · " + mine.size() + " to make" : ""),
                        "draft", onClock ? d.deadline() : null, null, false);
            }
            case ACTIVE -> {
                java.time.Duration confirmIn = java.time.Duration.ofHours(rules.latest(s).results().confirmHours());
                ScheduleService.ScheduleView sv = schedule.view(leagueId, seasonId, caller);
                for (ScheduleService.WeekView w : sv.weeks()) {
                    for (MatchView m : w.matches()) {
                        if (m.b() == null) continue;
                        String where = "week " + w.week();
                        Instant due = w.end() == null ? null : w.end().toInstant();
                        matchItems(add, m.id(), m.a(), m.b(), m.result(), m.canReport(), m.canConfirm(), m.canEnter(),
                                m.overdue() || w.current() || w.week() <= sv.currentWeek(), where, due, confirmIn);
                    }
                }
                PlayoffService.PlayoffView pv = playoffs.view(leagueId, seasonId, caller);
                for (BracketMatch m : pv.matches()) {
                    if (m.a() == null || m.b() == null) continue;
                    matchItems(add, m.id(), m.a(), m.b(), m.result(), m.canReport(), m.canConfirm(), m.canEnter(), true,
                            m.label().toLowerCase(), m.deadline(), confirmIn);
                }
                if (pv.canStart()) add.add("START_PLAYOFFS", "Start the playoffs", "The regular season is final.", "schedule", null, null, true);
                if (pv.canComplete() && !pv.complete()) {
                    add.add("COMPLETE_SEASON", "Complete the season", pv.enabled() ? "The final has a result." : "Every match has a final result.",
                            "schedule", null, null, true);
                }
                TradeService.TradesView tv = trades.overview(leagueId, seasonId, null, caller);
                if (isPlayer(tv)) {
                    for (TradeService.ThreadSummary t : tv.threads()) {
                        if (t.awaitingYou() > 0) {
                            add.add("TRADE_OFFER", t.with() + (t.awaitingYou() == 1 ? " sent you a trade offer" : " sent you " + t.awaitingYou() + " trade offers"),
                                    "Accept, decline or counter it.", "trades", null, null, false);
                        }
                    }
                }
                for (TradeService.OfferView o : tv.approvals()) {
                    add.add("TRADE_APPROVAL", "Approve a trade", o.from() + " ↔ " + o.to(), "trades", null, null, true);
                }
            }
            default -> { }
        }
        return out;
    }

    /** The trades view is the caller's own (not a placeholder's a commissioner can act for). */
    private static boolean isPlayer(TradeService.TradesView tv) {
        return tv.as() != null && tv.actors().stream().anyMatch(a -> a.you() && a.memberId().equals(tv.as()));
    }

    private static void matchItems(Adder add, String matchId, PlayerRef a, PlayerRef b, ResultView r, boolean canReport, boolean canConfirm,
                                   boolean canEnter, boolean due, String where, Instant deadline, java.time.Duration confirmIn) {
        String status = r == null ? null : r.status();
        if (canConfirm) {
            PlayerRef reporter = a.name().equals(r.reportedBy()) ? a : b;
            // Due when an unanswered report confirms itself.
            add.add("CONFIRM", "Confirm " + reporter.name() + "'s report", score(a, b, r) + " · " + where, "schedule",
                    r.reportedAt() == null ? null : r.reportedAt().plus(confirmIn), matchId, false);
        } else if (canReport && r == null && due) {
            PlayerRef other = a.you() ? b : a;
            add.add("REPORT", "Report your match against " + other.name(), where.substring(0, 1).toUpperCase() + where.substring(1),
                    "schedule", deadline, matchId, false);
        }
        if (canEnter && "DISPUTED".equals(status)) {
            add.add("DISPUTE", "Settle a disputed result", a.name() + " vs " + b.name() + " · " + where, "schedule", null, matchId, true);
        }
    }

    private static String score(PlayerRef a, PlayerRef b, ResultView r) {
        if (r.forfeit() != null) return ("A".equals(r.forfeit()) ? a.name() : "B".equals(r.forfeit()) ? b.name() : "Both") + " forfeit";
        return a.name() + " " + r.gamesA() + "–" + r.gamesB() + " " + b.name();
    }

    @FunctionalInterface
    private interface Adder {
        void add(String kind, String title, String detail, String tab, Instant due, String matchId, boolean commissioner);
    }
}
