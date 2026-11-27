package com.ygoleague.api.season;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.season.schedule.ScheduleService;
import com.ygoleague.season.schedule.ScheduleService.ScheduleView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The regular season's schedule. Rules are in {@link ScheduleService}. */
@RestController
@RequestMapping("/api/leagues/{leagueId}/seasons/{seasonId}/schedule")
public class ScheduleController {

    private final ScheduleService schedule;

    public ScheduleController(ScheduleService schedule) {
        this.schedule = schedule;
    }

    @GetMapping
    public ScheduleView view(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return schedule.view(leagueId, seasonId, user);
    }

    /** Commissioners: draw the schedule (a season without one), or draw again before week 1. */
    @PostMapping("/draw")
    public ScheduleView draw(@PathVariable String leagueId, @PathVariable String seasonId, @AuthenticationPrincipal CurrentUser user) {
        return schedule.generate(leagueId, seasonId, user);
    }
}
