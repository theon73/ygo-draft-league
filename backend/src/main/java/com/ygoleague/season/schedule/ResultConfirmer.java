package com.ygoleague.season.schedule;

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
 * Auto-confirms reported results nobody answered in time ({@link ResultService#autoConfirmDue}),
 * every minute by default. Like the pick timer: one instance assumed, and safe with several (the
 * status check in the update makes a second confirmation a no-op). Off with
 * {@code app.results.auto-confirm.enabled=false} (tests call it directly).
 */
@Component
@ConditionalOnProperty(name = "app.results.auto-confirm.enabled", havingValue = "true", matchIfMissing = true)
class ResultConfirmer {

    private static final Logger log = LoggerFactory.getLogger(ResultConfirmer.class);

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "result-confirmer");
        t.setDaemon(true);
        return t;
    });

    ResultConfirmer(ResultService results, @Value("${app.results.auto-confirm.tick-seconds:60}") long tickSeconds) {
        ticker.scheduleWithFixedDelay(() -> {
            try {
                int n = results.autoConfirmDue(Instant.now());
                if (n > 0) log.info("Auto-confirmed {} result(s)", n);
            } catch (RuntimeException e) {
                log.warn("Result auto-confirm tick failed", e);   // keep ticking
            }
        }, tickSeconds, tickSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
    }
}
