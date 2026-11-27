package com.ygoleague.notify;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.league.League;
import com.ygoleague.league.LeagueAccess;
import com.ygoleague.league.LeagueRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A league's notification settings (YGO-21): its Discord webhook and which events go to it.
 *
 * <ul>
 *   <li>Only the league owner sees or changes them. Each member manages their own pings: a Discord
 *       user id on their account (entered by hand, not verified) and an opt-in per league.
 *   <li>The webhook URL is a secret and never leaves the server again once saved: the settings show
 *       only that one is set and its last few characters.
 * </ul>
 */
@Service
public class LeagueNotifications {

    /** @param webhookHint the end of the saved URL ("…AbCd"), or null when none is set */
    public record SettingsView(boolean webhookSet, String webhookHint, List<NotificationEvent> events) {}

    /**
     * @param webhookUrl a new URL; null keeps the saved one, "" removes it
     * @param events     what to post; null keeps the current choice
     */
    public record SettingsUpdate(String webhookUrl, List<NotificationEvent> events) {}

    /**
     * A member's own Discord pings.
     *
     * @param discordUserId          their Discord user id (account-wide), or null
     * @param ping                   they want to be @mentioned in this league
     * @param leaguePostsOnTheClock  this league posts "on the clock" messages (pings only come with those)
     */
    public record MyPings(String discordUserId, boolean ping, boolean leaguePostsOnTheClock) {}

    /** @param discordUserId null keeps it, "" removes it; ping null keeps it */
    public record MyPingsUpdate(String discordUserId, Boolean ping) {}

    /** Where a league's notifications of one kind go. */
    public record Target(String webhookUrl, String leagueName) {}

    private final LeagueAccess access;
    private final DiscordWebhook discord;
    private final JdbcClient jdbc;

    public LeagueNotifications(LeagueAccess access, DiscordWebhook discord, JdbcClient jdbc) {
        this.access = access;
        this.discord = discord;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SettingsView get(String leagueId, CurrentUser caller) {
        League league = access.require(leagueId, caller, LeagueRole.OWNER).getLeague();
        return view(league.getId());
    }

    @Transactional
    public SettingsView update(String leagueId, SettingsUpdate update, CurrentUser caller) {
        League league = access.require(leagueId, caller, LeagueRole.OWNER).getLeague();
        if (update == null) throw bad("Send the settings");
        String url = update.webhookUrl() == null ? null : update.webhookUrl().strip();
        if (url != null && !url.isEmpty() && !DiscordWebhook.isWebhookUrl(url)) {
            throw bad("That isn't a Discord webhook URL (Server settings → Integrations → Webhooks → Copy Webhook URL)");
        }
        jdbc.sql("""
                        insert into league_notification (league_id, discord_webhook_url, events, updated_by)
                        values (:l, :url, cast(:events as text[]), :u)
                        on conflict (league_id) do update set
                            discord_webhook_url = case when :keepUrl then league_notification.discord_webhook_url else excluded.discord_webhook_url end,
                            events = case when :keepEvents then league_notification.events else excluded.events end,
                            updated_at = now(), updated_by = excluded.updated_by""")
                .param("l", league.getId())
                .param("url", url == null || url.isEmpty() ? null : url, java.sql.Types.VARCHAR)
                .param("keepUrl", url == null)
                .param("events", arrayLiteral(update.events() == null ? List.of() : update.events()))
                .param("keepEvents", update.events() == null)
                .param("u", caller.userId())
                .update();
        return view(league.getId());
    }

    /** Sends a test message right away, so the owner sees whether the webhook works. */
    @Transactional(readOnly = true)
    public void test(String leagueId, CurrentUser caller) {
        League league = access.require(leagueId, caller, LeagueRole.OWNER).getLeague();
        String url = webhook(league.getId()).orElseThrow(() -> bad("Save a webhook URL first"));
        DiscordWebhook.Delivery d = discord.post(url, league.getName(),
                "✅ Test message from the draft league app. Draft notifications for **" + DiscordText.escape(league.getName()) + "** will appear here.");
        if (!d.ok()) {
            String why = d.status() == 401 || d.status() == 404 ? "Discord doesn't know this webhook (deleted or mistyped?)"
                    : d.status() == 0 ? "couldn't reach Discord (" + d.error() + ")" : "Discord answered " + d.status();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The test message failed: " + why);
        }
    }

    @Transactional(readOnly = true)
    public MyPings myPings(String leagueId, CurrentUser caller) {
        var me = access.require(leagueId, caller, LeagueRole.MEMBER);
        return myPings(me.getLeague().getId(), me.getId(), caller.userId());
    }

    /** Sets the caller's Discord user id (for all their leagues) and whether they want pings in this league. */
    @Transactional
    public MyPings updateMyPings(String leagueId, MyPingsUpdate update, CurrentUser caller) {
        var me = access.require(leagueId, caller, LeagueRole.MEMBER);
        if (update == null) throw bad("Send the settings");
        if (update.discordUserId() != null) {
            String id = update.discordUserId().strip();
            if (!id.isEmpty() && !id.matches("[0-9]{17,20}")) {
                throw bad("A Discord user id is a 17 to 20 digit number (Discord: Developer Mode on, then right-click your name, Copy User ID)");
            }
            jdbc.sql("update app_user set discord_user_id = :id where id = :u")
                    .param("id", id.isEmpty() ? null : id, java.sql.Types.VARCHAR).param("u", caller.userId()).update();
        }
        if (update.ping() != null) {
            jdbc.sql("update league_member set discord_ping = :p where id = :m").param("p", update.ping()).param("m", me.getId()).update();
        }
        return myPings(me.getLeague().getId(), me.getId(), caller.userId());
    }

    /**
     * The Discord user id to @mention for a league member, if they've opted in to pings in that league
     * and entered an id. For any notification that's about a particular member (on the clock now;
     * trades and more later).
     */
    public Optional<String> pingId(long leagueMemberId) {
        return jdbc.sql("""
                        select u.discord_user_id from league_member m join app_user u on u.id = m.user_id
                        where m.id = :m and m.discord_ping and u.discord_user_id is not null""")
                .param("m", leagueMemberId).query(String.class).optional();
    }

    private MyPings myPings(long leagueId, long memberId, long userId) {
        String id = jdbc.sql("select discord_user_id from app_user where id = :u").param("u", userId).query(String.class).optional().orElse(null);
        boolean ping = jdbc.sql("select discord_ping from league_member where id = :m").param("m", memberId).query(Boolean.class).single();
        return new MyPings(id, ping, target(leagueId, NotificationEvent.DRAFT_ON_THE_CLOCK).isPresent());
    }

    /** Where to post a league's notifications of this kind, if it posts them at all. */
    public Optional<Target> target(long leagueId, NotificationEvent event) {
        return jdbc.sql("""
                        select n.discord_webhook_url, l.name from league_notification n join league l on l.id = n.league_id
                        where n.league_id = :l and n.discord_webhook_url is not null and :event = any(n.events)""")
                .param("l", leagueId).param("event", event.name())
                .query((rs, i) -> new Target(rs.getString(1), rs.getString(2))).optional();
    }

    private Optional<String> webhook(long leagueId) {
        return jdbc.sql("select discord_webhook_url from league_notification where league_id = :l and discord_webhook_url is not null")
                .param("l", leagueId).query(String.class).optional();
    }

    private SettingsView view(long leagueId) {
        return jdbc.sql("select discord_webhook_url, events from league_notification where league_id = :l")
                .param("l", leagueId)
                .query((rs, i) -> {
                    String url = rs.getString(1);
                    String[] names = (String[]) rs.getArray(2).getArray();
                    Set<NotificationEvent> events = EnumSet.noneOf(NotificationEvent.class);
                    for (String n : names) {
                        try { events.add(NotificationEvent.valueOf(n)); } catch (IllegalArgumentException ignored) { /* retired event */ }
                    }
                    return new SettingsView(url != null, url == null ? null : "…" + url.substring(url.length() - 4), List.copyOf(events));
                })
                .optional().orElse(new SettingsView(false, null, List.of()));
    }

    private static String arrayLiteral(List<NotificationEvent> events) {
        return "{" + String.join(",", events.stream().distinct().map(Enum::name).toList()) + "}";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
