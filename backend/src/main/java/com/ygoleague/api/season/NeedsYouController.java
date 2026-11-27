package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.overview.NeedsYouService;
import com.ygoleague.season.overview.NeedsYouService.Item;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** "Needs you" (YGO-31): for the Home dashboard (every league) and a season's Overview. */
@RestController
public class NeedsYouController {

    private final NeedsYouService needsYou;

    public NeedsYouController(NeedsYouService needsYou) {
        this.needsYou = needsYou;
    }

    @GetMapping("/api/me/needs-you")
    public List<Item> mine(@AuthenticationPrincipal CurrentUser user) {
        return needsYou.forUser(user);
    }

    @GetMapping("/api/leagues/{leagueId}/seasons/{seasonId}/needs-you")
    public List<Item> season(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return needsYou.forSeason(leagueId, seasonId, user);
    }
}
