package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.roster.RosterService;
import com.ygoleague.season.roster.RosterService.Rosters;
import com.ygoleague.season.roster.RosterService.SeasonCard;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rosters and card ownership in a season (read-only). The free agents are the pool list's
 * "available only" view ({@code GET …/pool/cards?available=true}).
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}")
public class RosterController {

    private final RosterService rosters;

    public RosterController(RosterService rosters) {
        this.rosters = rosters;
    }

    @GetMapping("/rosters")
    public Rosters rosters(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return rosters.rosters(leagueId, seasonId, user);
    }

    /** A pool card's season page: its flags, who holds it, and its ownership history. */
    @GetMapping("/cards/{cardId}")
    public SeasonCard card(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable long cardId,
                           @AuthenticationPrincipal CurrentUser user) {
        return rosters.card(leagueId, seasonId, cardId, user);
    }
}
