package com.ygoleague.notify;

import com.ygoleague.season.Season;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Posts executed transactions (free-agent claims now, trades in YGO-24) to the league's Discord,
 * if it chose the {@link NotificationEvent#TRANSACTIONS} event. Like the draft's news: one message
 * per transaction, sent after the change commits from a background thread, so a broken webhook
 * never fails or delays a claim, and a refused claim posts nothing. Names are escaped and mentions
 * are off (pings for trade offers come with trades).
 */
@Component
public class TransactionAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(TransactionAnnouncer.class);

    private final LeagueNotifications settings;
    private final DiscordWebhook discord;
    private final ExecutorService sender;
    private final boolean synchronous;

    public TransactionAnnouncer(LeagueNotifications settings, DiscordWebhook discord,
                                @Value("${app.notifications.synchronous:false}") boolean synchronous) {
        this.settings = settings;
        this.discord = discord;
        this.synchronous = synchronous;
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-transactions");
            t.setDaemon(true);
            return t;
        });
    }

    /** One line of news (already formatted with {@link DiscordText#escape}), posted once the current transaction commits. */
    public void announce(Season season, String line) {
        Optional<LeagueNotifications.Target> target = settings.target(season.getLeague().getId(), NotificationEvent.TRANSACTIONS);
        if (target.isEmpty()) return;
        String content = "**" + DiscordText.escape(season.getName()) + " transactions**\n" + line;
        Runnable post = () -> {
            try {
                DiscordWebhook.Delivery d = discord.post(target.get().webhookUrl(), target.get().leagueName(), content, List.of());
                if (d == null || !d.ok()) log.warn("Discord post for {} failed: {}", season.getName(), d);
            } catch (RuntimeException e) {
                log.warn("Discord post for {} failed", season.getName(), e);
            }
        };
        Runnable send = () -> { if (synchronous) post.run(); else sender.execute(post); };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }
}
