package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.schedule.ResultService;
import com.ygoleague.season.schedule.ResultService.DisputeRequest;
import com.ygoleague.season.schedule.ResultService.EnterRequest;
import com.ygoleague.season.schedule.ResultService.ReportRequest;
import com.ygoleague.season.schedule.StandingsService;
import com.ygoleague.season.schedule.StandingsService.StandingsView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Match results and standings. Matches and their results are listed by {@code GET …/schedule};
 * the rules for each action are in {@link ResultService}.
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}")
public class ResultController {

    private final ResultService results;
    private final StandingsService standings;

    public ResultController(ResultService results, StandingsService standings) {
        this.results = results;
        this.standings = standings;
    }

    @PostMapping("/matches/{matchId}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String matchId,
                       @RequestBody ReportRequest body, @AuthenticationPrincipal CurrentUser user) {
        results.report(leagueId, seasonId, matchId, body, user);
    }

    @PostMapping("/matches/{matchId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String matchId,
                        @AuthenticationPrincipal CurrentUser user) {
        results.confirm(leagueId, seasonId, matchId, user);
    }

    @PostMapping("/matches/{matchId}/dispute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dispute(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String matchId,
                        @RequestBody(required = false) DisputeRequest body, @AuthenticationPrincipal CurrentUser user) {
        results.dispute(leagueId, seasonId, matchId, body, user);
    }

    /** Commissioners: enter or correct a result (games or a forfeit); final at once. */
    @PostMapping("/matches/{matchId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enter(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String matchId,
                      @RequestBody EnterRequest body, @AuthenticationPrincipal CurrentUser user) {
        results.enter(leagueId, seasonId, matchId, body, user);
    }

    @GetMapping("/standings")
    public StandingsView standings(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return standings.standings(leagueId, seasonId, user);
    }
}
