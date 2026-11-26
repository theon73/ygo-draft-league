package com.ygoleague.api.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.league.LeagueService;
import com.ygoleague.league.LeagueService.LeagueDetail;
import com.ygoleague.league.LeagueService.LeagueSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leagues the caller belongs to. Everything here needs a signed-in user (enforced by
 * {@code SecurityConfig}); per-league access is checked by {@code LeagueAccess}.
 */
@RestController
@RequestMapping("/api/leagues")
public class LeagueController {

    public record CreateLeagueRequest(String name) {}

    public record ErrorBody(String message) {}

    private final LeagueService leagues;

    public LeagueController(LeagueService leagues) {
        this.leagues = leagues;
    }

    @GetMapping
    public List<LeagueSummary> myLeagues(@AuthenticationPrincipal CurrentUser user) {
        return leagues.myLeagues(user);
    }

    @PostMapping
    public ResponseEntity<LeagueSummary> create(@RequestBody CreateLeagueRequest body, @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leagues.create(body.name(), user));
    }

    @GetMapping("/{leagueId}")
    public LeagueDetail league(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        return leagues.detail(leagueId, user);
    }

    /** Validation failures from the service (e.g. a blank name) become 400 with a readable message. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorBody(e.getMessage()));
    }
}
