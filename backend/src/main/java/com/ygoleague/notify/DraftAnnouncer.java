package com.ygoleague.notify;

import com.ygoleague.season.Season;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Posts the draft's news to the league's Discord (YGO-21).
 *
 * <p>The draft notes what happens as it happens ({@link #note}, {@link #clockMoved}); everything
 * noted in one transaction goes out as one message after it commits, so a burst (several away-mode
 * auto-picks at once) is one post and a refused or rolled-back change posts nothing. Messages are
 * sent from a single background thread, in order: a slow or broken webhook never holds up or fails
 * a pick, it's only logged.
 */
@Component
public class DraftAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(DraftAnnouncer.class);

    /** What one transaction has to say about one season's draft. */
    private static final class Pending {
        final List<String> lines = new ArrayList<>();
        boolean clockMoved;
        ClockLine clockLine;
        Optional<LeagueNotifications.Target> picks = Optional.empty();
        Optional<LeagueNotifications.Target> clock = Optional.empty();
        String seasonName;
    }

    /**
     * Who's on the clock, as a line of news.
     *
     * @param pingUserId the Discord user id the line @mentions (the player opted in), or null
     */
    public record ClockLine(String text, String pingUserId) {}

    private final LeagueNotifications settings;
    private final DiscordWebhook discord;
    private final ExecutorService sender;
    private final boolean synchronous;

    public DraftAnnouncer(LeagueNotifications settings, DiscordWebhook discord,
                          @Value("${app.notifications.synchronous:false}") boolean synchronous) {
        this.settings = settings;
        this.discord = discord;
        this.synchronous = synchronous;
        this.sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-sender");
            t.setDaemon(true);
            return t;
        });
    }

    /** A line of draft news (a pick, a skip, an undo, …), already formatted. */
    public void note(Season season, String line) {
        pending(season).lines.add(line);
    }

    /** The slot on the clock changed (or its timer restarted): say who's up now. */
    public void clockMoved(Season season) {
        pending(season).clockMoved = true;
    }

    /**
     * Called at the end of a change, still inside its transaction: looks up where the league posts
     * (and the on-the-clock line, if the clock moved) while the database is at hand.
     *
     * @param clockLine who's on the clock now, or null if nobody is
     */
    public void prepare(Season season, Supplier<ClockLine> clockLine) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        Pending p = bound().get(season.getId());
        if (p == null) return;
        long leagueId = season.getLeague().getId();
        p.seasonName = season.getName();
        p.picks = settings.target(leagueId, NotificationEvent.DRAFT_PICKS);
        p.clock = settings.target(leagueId, NotificationEvent.DRAFT_ON_THE_CLOCK);
        if (p.clockMoved && p.clock.isPresent()) p.clockLine = clockLine.get();
    }

    private Pending pending(Season season) {
        return bound().computeIfAbsent(season.getId(), id -> new Pending());
    }

    /** This transaction's pending news per season, created (with its commit hook) on first use. */
    @SuppressWarnings("unchecked")
    private Map<Long, Pending> bound() {
        Object existing = TransactionSynchronizationManager.getResource(this);
        if (existing != null) return (Map<Long, Pending>) existing;
        Map<Long, Pending> map = new LinkedHashMap<>();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return map;   // outside a transaction: nothing to send
        TransactionSynchronizationManager.bindResource(this, map);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { map.values().forEach(DraftAnnouncer.this::send); }

            @Override public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(DraftAnnouncer.this); }
        });
        return map;
    }

    private void send(Pending p) {
        // One message per webhook: picks and the clock usually share one, so they go together.
        Map<String, List<String>> byUrl = new LinkedHashMap<>();
        Map<String, List<String>> pings = new LinkedHashMap<>();
        String league = p.picks.or(() -> p.clock).map(LeagueNotifications.Target::leagueName).orElse(null);
        if (p.picks.isPresent() && !p.lines.isEmpty()) byUrl.computeIfAbsent(p.picks.get().webhookUrl(), u -> new ArrayList<>()).addAll(p.lines);
        if (p.clock.isPresent() && p.clockLine != null) {
            byUrl.computeIfAbsent(p.clock.get().webhookUrl(), u -> new ArrayList<>()).add(p.clockLine.text());
            if (p.clockLine.pingUserId() != null) pings.computeIfAbsent(p.clock.get().webhookUrl(), u -> new ArrayList<>()).add(p.clockLine.pingUserId());
        }
        byUrl.forEach((url, lines) -> {
            for (String content : messages(p.seasonName, lines)) {
                Runnable post = () -> {
                    try {
                        DiscordWebhook.Delivery d = discord.post(url, league, content, pings.getOrDefault(url, List.of()));
                        if (d == null || !d.ok()) log.warn("Discord post for {} failed: {}", p.seasonName, d);
                    } catch (RuntimeException e) {
                        log.warn("Discord post for {} failed", p.seasonName, e);   // never let a post break the draft
                    }
                };
                if (synchronous) post.run(); else sender.execute(post);
            }
        });
    }

    /** The lines as messages under the season's name, split to stay inside Discord's 2000 characters. */
    static List<String> messages(String seasonName, List<String> lines) {
        String header = "**" + DiscordText.escape(seasonName) + " draft**";
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (String line : lines) {
            if (current.length() + 1 + line.length() > DiscordWebhook.MAX_CONTENT - 10 && current.length() > header.length()) {
                out.add(current.toString());
                current = new StringBuilder(header + " (cont.)");
            }
            current.append('\n').append(line);
        }
        if (current.length() > header.length()) out.add(current.toString());
        return out;
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }
}
