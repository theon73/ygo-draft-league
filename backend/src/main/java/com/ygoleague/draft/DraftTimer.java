package com.ygoleague.draft;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the pick timer (YGO-18): every few seconds, applies expiry to drafts whose pick on the clock
 * has run out ({@link DraftService#expireDue}). A pick can therefore run over by up to one tick.
 *
 * <p>Like the live streams, this assumes one backend instance; with several, each would tick, and
 * the per-season lock plus the deadline check keep that safe (the second finds nothing due).
 * Off with {@code app.draft.timer.enabled=false} (tests drive expiry directly).
 */
@Component
@ConditionalOnProperty(name = "app.draft.timer.enabled", havingValue = "true", matchIfMissing = true)
class DraftTimer {

    private static final Logger log = LoggerFactory.getLogger(DraftTimer.class);

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draft-timer");
        t.setDaemon(true);
        return t;
    });

    DraftTimer(DraftService drafts, @Value("${app.draft.timer.tick-seconds:5}") long tickSeconds) {
        ticker.scheduleWithFixedDelay(() -> {
            try {
                drafts.expireDue(Instant.now());
            } catch (RuntimeException e) {
                log.warn("Pick timer tick failed", e);   // keep ticking: an exception would cancel the schedule
            }
        }, tickSeconds, tickSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
    }
}
