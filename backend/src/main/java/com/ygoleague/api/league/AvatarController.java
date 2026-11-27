package com.ygoleague.api.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.league.AvatarService;
import com.ygoleague.league.AvatarService.AvatarRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Roster avatars (YGO-30): a member's default and a season's override. Rules are in {@link AvatarService}. */
@RestController
public class AvatarController {

    private final AvatarService avatars;

    public AvatarController(AvatarService avatars) {
        this.avatars = avatars;
    }

    @PutMapping("/api/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void profile(@RequestBody AvatarRequest body, @AuthenticationPrincipal CurrentUser user) {
        avatars.setProfileAvatar(body, user);
    }

    @PutMapping("/api/leagues/{leagueId}/members/{memberId}/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void member(@PathVariable String leagueId, @PathVariable String memberId, @RequestBody AvatarRequest body,
                       @AuthenticationPrincipal CurrentUser user) {
        avatars.setMemberAvatar(leagueId, memberId, body, user);
    }

    @PutMapping("/api/leagues/{leagueId}/seasons/{seasonId}/participants/{memberId}/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void season(@PathVariable String leagueId, @PathVariable String seasonId, @PathVariable String memberId,
                       @RequestBody AvatarRequest body, @AuthenticationPrincipal CurrentUser user) {
        avatars.setSeasonAvatar(leagueId, seasonId, memberId, body, user);
    }
}
