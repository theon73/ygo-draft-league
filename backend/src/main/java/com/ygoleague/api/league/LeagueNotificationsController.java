package com.ygoleague.api.league;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.notify.LeagueNotifications;
import com.ygoleague.notify.LeagueNotifications.MyPings;
import com.ygoleague.notify.LeagueNotifications.MyPingsUpdate;
import com.ygoleague.notify.LeagueNotifications.SettingsUpdate;
import com.ygoleague.notify.LeagueNotifications.SettingsView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A league's Discord notifications (owner only), and each member's own Discord pings ({@code /me}).
 * Rules are in {@link LeagueNotifications}.
 */
@RestController
@RequestMapping("/api/leagues/{leagueId}/notifications")
public class LeagueNotificationsController {

    private final LeagueNotifications notifications;

    public LeagueNotificationsController(LeagueNotifications notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public SettingsView get(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        return notifications.get(leagueId, user);
    }

    @PutMapping
    public SettingsView update(@PathVariable String leagueId, @RequestBody SettingsUpdate body, @AuthenticationPrincipal CurrentUser user) {
        return notifications.update(leagueId, body, user);
    }

    /** The caller's own pings: their Discord user id (all leagues) and opt-in here. Any member. */
    @GetMapping("/me")
    public MyPings myPings(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        return notifications.myPings(leagueId, user);
    }

    @PutMapping("/me")
    public MyPings updateMyPings(@PathVariable String leagueId, @RequestBody MyPingsUpdate body, @AuthenticationPrincipal CurrentUser user) {
        return notifications.updateMyPings(leagueId, body, user);
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@PathVariable String leagueId, @AuthenticationPrincipal CurrentUser user) {
        notifications.test(leagueId, user);
    }
}
