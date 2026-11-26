package com.ygoleague.image;

import java.util.function.LongSupplier;

/**
 * Spaces calls out evenly: at most one per interval, across all threads. Callers that arrive
 * together are queued into consecutive slots. Spring-free, with the clock and sleep injectable
 * so it can be unit-tested without waiting.
 */
final class Throttle {

    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private long nextSlot;   // guarded by this

    Throttle(double perSecond) {
        this(perSecond, System::nanoTime, nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    Throttle(double perSecond, LongSupplier nanoClock, Sleeper sleeper) {
        if (perSecond <= 0) throw new IllegalArgumentException("perSecond must be positive");
        this.intervalNanos = (long) (1_000_000_000L / perSecond);
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.nextSlot = nanoClock.getAsLong();
    }

    /** Blocks until this caller's slot. */
    void acquire() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = nanoClock.getAsLong();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait > 0) sleeper.sleepNanos(wait);
    }
}
