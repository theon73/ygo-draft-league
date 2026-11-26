package com.ygoleague.api.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.league.LeagueMembership;
import com.ygoleague.league.LeagueMembership.CreateInvite;
import com.ygoleague.league.LeagueMembership.InviteView;
import com.ygoleague.league.LeagueService.MemberView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The owner's tools for a league's members and invites. Access rules are in {@link LeagueMembership}. */
@RestController
@RequestMapping("/api/leagues/{leagueId}")
public class LeagueMembersController {

    public record PlaceholderRequest(String displayName) {}

    private final LeagueMembership membership;

    public LeagueMembersController(LeagueMembership membership) {
        this.membership = membership;
    }

    @GetMapping("/invites")
    public List<InviteView> invites(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        return membership.invites(leagueId, user);
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteView createInvite(@PathVariable String leagueId, @RequestBody CreateInvite body,
                                   @AuthenticationPrincipal CurrentUser user) {
        return membership.createInvite(leagueId, body, user);
    }

    @DeleteMapping("/invites/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@PathVariable String leagueId, @PathVariable String code, @AuthenticationPrincipal CurrentUser user) {
        membership.revokeInvite(leagueId, code, user);
    }

    @PostMapping("/members/placeholders")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView addPlaceholder(@PathVariable String leagueId, @RequestBody PlaceholderRequest body,
                                     @AuthenticationPrincipal CurrentUser user) {
        return membership.addPlaceholder(leagueId, body.displayName(), user);
    }

    @DeleteMapping("/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String leagueId, @PathVariable String memberId, @AuthenticationPrincipal CurrentUser user) {
        membership.removeMember(leagueId, memberId, user);
    }
}
