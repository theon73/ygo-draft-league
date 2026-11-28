package com.ygoleague.live;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open server-sent-event streams per season, each tagged with who it's for (YGO-17, shared since
 * YGO-35 by the draft and messages).
 *
 * <ul>
 *   <li>Sends go out after the current transaction commits, so a client that refetches sees the change.
 *   <li>A {@code ping} event every {@link #HEARTBEAT} keeps proxies from closing idle streams and finds
 *       streams whose client has gone. It's an event rather than a comment so the page sees it too:
 *       a proxy can keep the browser's side open after the backend has gone (Vite's dev proxy does
 *       when the backend restarts), and a page that hears nothing for a while reconnects (YGO-35). A stream ends after {@link #TIMEOUT}; the browser's
 *       EventSource reconnects by itself, which also re-checks access.
 *   <li>Streams are held in this process's memory, which is right for one backend instance. With
 *       several, a change on one must reach streams on the others (e.g. Postgres LISTEN/NOTIFY).
 * </ul>
 *
 * @param <T> what each stream is tagged with, to pick who an event goes to
 */
public final class SseStreams<T> {

    public static final Duration TIMEOUT = Duration.ofMinutes(30);
    public static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(SseStreams.class);

    private final Map<Long, Map<SseEmitter, T>> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat;

    public SseStreams(String name) {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name + "-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::ping, HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    /** Opens a stream for a season and sends it {@code first} right away, so a (re)connecting client knows it's current. */
    public SseEmitter open(long seasonId, T tag, String event, Object first) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        streams.computeIfAbsent(seasonId, id -> new ConcurrentHashMap<>()).put(emitter, tag);
        Runnable remove = () -> forget(seasonId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(seasonId, emitter, event, first);
        return emitter;
    }

    /**
     * Sends an event to the season's streams whose tag matches, once the current transaction (if any)
     * commits. Never throws: by then the change is saved, and a failed live update must not turn the
     * request that made it into an error (the client would think it failed, and maybe retry).
     */
    public void publish(long seasonId, Predicate<T> to, String event, Object data) {
        Runnable sendAll = () -> {
            try {
                streams.getOrDefault(seasonId, Map.of()).forEach((emitter, tag) -> {
                    if (to.test(tag)) send(seasonId, emitter, event, data);
                });
            } catch (RuntimeException e) {
                log.warn("Live '{}' update for season {} failed", event, seasonId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sendAll.run(); }
            });
        } else {
            sendAll.run();
        }
    }

    public int openStreams(long seasonId) {
        return streams.getOrDefault(seasonId, Map.of()).size();
    }

    private void send(long seasonId, SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // The client went away (or the stream already ended): drop it.
            drop(seasonId, emitter, e);
        } catch (RuntimeException e) {
            log.warn("Sending '{}' to a season {} stream failed", event, seasonId, e);
            drop(seasonId, emitter, e);
        }
    }

    /** Forgets a broken stream and ends it; ending one that's already gone can throw too, which is fine. */
    private void drop(long seasonId, SseEmitter emitter, Exception cause) {
        forget(seasonId, emitter);
        try {
            emitter.completeWithError(cause);
        } catch (RuntimeException ignored) {
            // already completed or its connection is gone
        }
    }

    private void ping() {
        streams.forEach((seasonId, forSeason) -> {
            for (SseEmitter emitter : forSeason.keySet()) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data(""));
                } catch (IOException | IllegalStateException e) {
                    drop(seasonId, emitter, e);
                } catch (RuntimeException e) {
                    log.warn("Stream heartbeat failed", e);
                    drop(seasonId, emitter, e);
                }
            }
        });
    }

    private void forget(long seasonId, SseEmitter emitter) {
        streams.computeIfPresent(seasonId, (id, map) -> {
            map.remove(emitter);
            return map.isEmpty() ? null : map;
        });
    }

    public void shutdown() {
        heartbeat.shutdownNow();
        streams.values().forEach(map -> map.keySet().forEach(SseEmitter::complete));
        streams.clear();
    }
}
