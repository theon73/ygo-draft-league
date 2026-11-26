package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.rules.SeasonRulesService;
import com.ygoleague.season.rules.SeasonRulesService.RulesUpdate;
import com.ygoleague.season.rules.SeasonRulesService.RulesView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A season's rules: read by any member, changed by its commissioners (each save is a new version). */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/rules")
public class SeasonRulesController {

    private final SeasonRulesService rules;

    public SeasonRulesController(SeasonRulesService rules) {
        this.rules = rules;
    }

    @GetMapping
    public RulesView get(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return rules.get(leagueId, seasonId, user);
    }

    @PutMapping
    public RulesView save(@PathVariable String leagueId, @PathVariable String seasonId, @RequestBody RulesUpdate body,
                          @AuthenticationPrincipal CurrentUser user) {
        return rules.save(leagueId, seasonId, body, user);
    }
}
