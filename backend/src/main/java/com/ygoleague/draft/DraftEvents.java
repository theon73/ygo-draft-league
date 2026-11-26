package com.ygoleague.draft;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live draft updates (YGO-17): the open server-sent-event streams per season, and telling them when
 * the draft changes.
 *
 * <ul>
 *   <li>An event is a signal, not the board: {@code draft} with the status, how many picks are made
 *       and the seat on the clock. Clients refetch {@code GET …/draft} for the rest, which keeps
 *       per-viewer fields (can I pick, my seat) and access checks in one place.
 *   <li>Events go out after the change commits, so a client that refetches sees it.
 *   <li>A comment line every {@link #HEARTBEAT} keeps proxies from closing idle streams and finds
 *       streams whose client has gone. A stream ends after {@link #TIMEOUT}; the browser's
 *       EventSource reconnects by itself, which also re-checks access.
 *   <li>Streams are held in this process's memory, which is right for one backend instance. With
 *       several, a pick on one must reach streams on the others (e.g. Postgres LISTEN/NOTIFY).
 * </ul>
 */
@Component
public class DraftEvents {

    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final Duration HEARTBEAT = Duration.ofSeconds(20);

    /**
     * @param onTheClockSeat null when nobody is (not started, or complete)
     * @param paused         a commissioner has paused the draft
     */
    public record DraftChanged(DraftService.DraftStatus status, int picksMade, Integer onTheClockSeat, boolean paused) {}

    private static final Logger log = LoggerFactory.getLogger(DraftEvents.class);

    private final Map<Long, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draft-events-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public DraftEvents() {
        heartbeat.scheduleAtFixedRate(this::ping, HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    /** Opens a stream for a season. The caller has already checked the viewer may see it. */
    public SseEmitter subscribe(long seasonId, DraftChanged current) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Set<SseEmitter> forSeason = streams.computeIfAbsent(seasonId, id -> ConcurrentHashMap.newKeySet());
        forSeason.add(emitter);
        Runnable remove = () -> forget(seasonId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // The state right away, so a client that (re)connects knows it's current without waiting for a change.
        send(seasonId, emitter, current);
        return emitter;
    }

    /** Tells the season's streams the draft changed, once the current transaction (if any) commits. */
    public void publish(long seasonId, DraftChanged change) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sendAll(seasonId, change); }
            });
        } else {
            sendAll(seasonId, change);
        }
    }

    int openStreams(long seasonId) {
        return streams.getOrDefault(seasonId, Set.of()).size();
    }

    private void sendAll(long seasonId, DraftChanged change) {
        for (SseEmitter emitter : streams.getOrDefault(seasonId, Set.of())) send(seasonId, emitter, change);
    }

    private void send(long seasonId, SseEmitter emitter, DraftChanged change) {
        try {
            emitter.send(SseEmitter.event().name("draft").data(change));
        } catch (IOException | IllegalStateException e) {
            // The client went away (or the stream already ended): drop it.
            forget(seasonId, emitter);
            emitter.completeWithError(e);
        }
    }

    private void ping() {
        streams.forEach((seasonId, forSeason) -> {
            for (SseEmitter emitter : forSeason) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    forget(seasonId, emitter);
                    emitter.completeWithError(e);
                } catch (RuntimeException e) {
                    log.warn("Draft stream heartbeat failed", e);
                    forget(seasonId, emitter);
                }
            }
        });
    }

    private void forget(long seasonId, SseEmitter emitter) {
        streams.computeIfPresent(seasonId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        streams.values().forEach(set -> set.forEach(SseEmitter::complete));
        streams.clear();
    }
}
