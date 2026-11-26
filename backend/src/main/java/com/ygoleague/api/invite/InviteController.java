package com.ygoleague.api.invite;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.league.LeagueMembership;
import com.ygoleague.league.LeagueMembership.InvitePreview;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invite links ({@code /invite/<code>} in the frontend). Previewing works signed out, so the page
 * can say which league you're joining before you sign in; accepting needs a signed-in user.
 */
@RestController
@RequestMapping("/api/invites/{code}")
public class InviteController {

    public record Accepted(String leagueId) {}

    private final LeagueMembership membership;

    public InviteController(LeagueMembership membership) {
        this.membership = membership;
    }

    @GetMapping
    public InvitePreview preview(@PathVariable String code, @AuthenticationPrincipal CurrentUser user) {
        return membership.preview(code, user);
    }

    @PostMapping("/accept")
    public Accepted accept(@PathVariable String code, @AuthenticationPrincipal CurrentUser user) {
        return new Accepted(membership.accept(code, user));
    }
}
