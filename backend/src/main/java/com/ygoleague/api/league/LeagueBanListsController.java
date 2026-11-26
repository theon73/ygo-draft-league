package com.ygoleague.api.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.banlist.BanList;
import com.ygoleague.banlist.BanListService;
import com.ygoleague.league.LeagueAccess;
import com.ygoleague.league.LeagueRole;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ban lists a league can choose from for its seasons: every official list, plus its own custom lists. */
@RestController
@RequestMapping("/api/leagues/{leagueId}/ban-lists")
public class LeagueBanListsController {

    /** @param latestRevisionId what a season picking this list pins to */
    public record BanListOption(long listId, String name, boolean official, LocalDate effectiveDate, int latestRevision,
                                long latestRevisionId) {}

    private final LeagueAccess access;
    private final BanListService banLists;

    public LeagueBanListsController(LeagueAccess access, BanListService banLists) {
        this.access = access;
        this.banLists = banLists;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<BanListOption> options(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        long id = access.require(leagueId, user, LeagueRole.MEMBER).getLeague().getId();
        return banLists.visibleTo(id).stream()
                .map(list -> option(list))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private java.util.Optional<BanListOption> option(BanList list) {
        try {
            var latest = banLists.latestRevision(list.getId());
            return java.util.Optional.of(new BanListOption(list.getId(), list.getName(), list.isOfficial(), list.getEffectiveDate(),
                    latest.getRevision(), latest.getId()));
        } catch (java.util.NoSuchElementException e) {
            return java.util.Optional.empty();   // a list with no revisions yet has nothing to pick
        }
    }
}
