package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.SeasonService;
import com.ygoleague.season.SeasonService.SeasonDetail;
import com.ygoleague.season.SeasonService.SeasonSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A league's seasons, participants and seat order. Access rules are in {@link SeasonService}. */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons")
public class SeasonController {

    public record SetupStepRequest(Integer step) {}

    public record MembersRequest(List<String> memberIds) {}

    public record TeamNameRequest(String teamName) {}

    private final SeasonService seasons;

    public SeasonController(SeasonService seasons) {
        this.seasons = seasons;
    }

    @GetMapping
    public List<SeasonSummary> list(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        return seasons.list(leagueId, user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonSummary create(@PathVariable String leagueId, @RequestBody SeasonService.CreateRequest body,
                                @AuthenticationPrincipal CurrentUser user) {
        return seasons.create(leagueId, body, user);
    }

    @GetMapping("/{seasonId}")
    public SeasonDetail season(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return seasons.detail(leagueId, seasonId, user);
    }

    public record MemberRoleRequest(boolean commissioner, boolean player) {}

    /** Sets one member's season roles at once: commissioner and/or player (the Setup page's dropdown). */
    @PutMapping("/{seasonId}/members/{memberId}")
    public SeasonDetail memberRole(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String memberId,
                                   @RequestBody MemberRoleRequest body, @AuthenticationPrincipal CurrentUser user) {
        return seasons.setMemberRole(leagueId, seasonId, memberId, body.commissioner(), body.player(), user);
    }

    /** Replaces the season's commissioners (the league owner always is one). */
    @PutMapping("/{seasonId}/commissioners")
    public SeasonDetail commissioners(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody MembersRequest body,
                                      @AuthenticationPrincipal CurrentUser user) {
        return seasons.setCommissioners(leagueId, seasonId, body.memberIds(), user);
    }

    /** Replaces the participant list (setup only). */
    @PutMapping("/{seasonId}/participants")
    public SeasonDetail participants(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody MembersRequest body,
                                     @AuthenticationPrincipal CurrentUser user) {
        return seasons.setParticipants(leagueId, seasonId, body.memberIds(), user);
    }

    /** Sets the seat order by hand: every participant, seat 1 first (setup only). */
    @PutMapping("/{seasonId}/seats")
    public SeasonDetail seats(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody MembersRequest body,
                              @AuthenticationPrincipal CurrentUser user) {
        return seasons.setSeatOrder(leagueId, seasonId, body.memberIds(), user);
    }

    /** Seats in the reverse of the copied-from season's final standings (YGO-39; setup only). */
    @PostMapping("/{seasonId}/seats/reverse-standings")
    public SeasonDetail reverseStandings(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return seasons.reverseStandingsSeats(leagueId, seasonId, user);
    }

    /** Randomizes the seats and records the draw (setup only). */
    @PostMapping("/{seasonId}/seats/randomize")
    public SeasonDetail randomize(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return seasons.randomizeSeats(leagueId, seasonId, user);
    }

    @PutMapping("/{seasonId}/setup-step")
    public SeasonDetail setupStep(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody SetupStepRequest body,
                                  @AuthenticationPrincipal CurrentUser user) {
        return seasons.setSetupStep(leagueId, seasonId, body.step(), user);
    }

    @PatchMapping("/{seasonId}/participants/{memberId}")
    public SeasonDetail teamName(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String memberId,
                                 @RequestBody TeamNameRequest body, @AuthenticationPrincipal CurrentUser user) {
        return seasons.setTeamName(leagueId, seasonId, memberId, body.teamName(), user);
    }
}
