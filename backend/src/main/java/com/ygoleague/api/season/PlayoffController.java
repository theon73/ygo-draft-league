package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.schedule.PlayoffService;
import com.ygoleague.season.schedule.PlayoffService.PlayoffView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The playoffs and completing the season. Playoff results use the match endpoints
 * ({@code …/matches/{id}/report|confirm|dispute|result}); rules are in {@link PlayoffService}.
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/playoffs")
public class PlayoffController {

    private final PlayoffService playoffs;

    public PlayoffController(PlayoffService playoffs) {
        this.playoffs = playoffs;
    }

    @GetMapping
    public PlayoffView view(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return playoffs.view(leagueId, seasonId, user);
    }

    /** Commissioners: seed from the standings and start (every regular-season result must be final). */
    @PostMapping("/start")
    public PlayoffView start(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return playoffs.start(leagueId, seasonId, user);
    }

    /** Commissioners, seasons without playoffs: complete the season (the standings' leader is champion). */
    @PostMapping("/complete")
    public PlayoffView complete(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return playoffs.completeWithoutPlayoffs(leagueId, seasonId, user);
    }
}
