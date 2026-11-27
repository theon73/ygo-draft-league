package com.ygoleague.image;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Card images, cached on first request (see PLANNING.md, "Card images").
 *
 * <ul>
 *   <li><b>Only catalog passcodes</b> are fetched, so the endpoint can't be used as an open proxy
 *       to YGOPRODeck and storage stays bounded by the catalog.
 *   <li><b>One throttle for the whole app</b> ({@code app.images.fetches-per-second}) keeps us well
 *       under YGOPRODeck's limits however many people load images at once.
 *   <li><b>Concurrent requests for the same image share one fetch.</b>
 *   <li>A caller waits at most {@link #MAX_WAIT}; after that it gets "unavailable, retry" while the
 *       fetch carries on and lands in the cache.
 * </ul>
 */
@Service
public class CardImageService {

    private static final Logger log = LoggerFactory.getLogger(CardImageService.class);
    static final Duration MAX_WAIT = Duration.ofSeconds(20);

    private final ImageStore store;
    private final ImageSource source;
    private final JdbcClient jdbc;
    private final Throttle throttle;
    private final ConcurrentHashMap<ImageKey, CompletableFuture<Optional<byte[]>>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService fetchers = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("image-fetch-", 0).daemon().factory());
    private final ExecutorService prefetcher = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("image-prefetch").daemon().factory());

    CardImageService(ImageStore store, ImageSource source, JdbcClient jdbc,
                     @Value("${app.images.fetches-per-second}") double fetchesPerSecond) {
        this.store = store;
        this.source = source;
        this.jdbc = jdbc;
        this.throttle = new Throttle(fetchesPerSecond);
    }

    /**
     * @return the JPEG, or empty if the passcode isn't in the catalog or YGOPRODeck has no image
     * @throws ImageUnavailableException if it couldn't be fetched (yet); worth retrying
     */
    public Optional<byte[]> image(ImageKey key) {
        Optional<byte[]> cached = store.get(key);
        if (cached.isPresent()) return cached;
        if (!inCatalog(key.passcode())) return Optional.empty();
        try {
            return fetchOnce(key).get(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ImageUnavailableException("Still fetching " + key.path() + "; try again shortly");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ImageUnavailableException u) throw u;
            throw new ImageUnavailableException("Couldn't fetch " + key.path(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageUnavailableException("Interrupted fetching " + key.path());
        }
    }

    /**
     * Queues every artwork of every card released in the TCG on or before {@code tcgCutoff} for
     * background fetching (a format's pool, until Phase 2 builds real pools). Already-cached images
     * are skipped. Returns how many were queued.
     */
    public int prefetchUpTo(LocalDate tcgCutoff, ImageVariant variant) {
        return prefetch(jdbc.sql("select distinct unnest(passcodes) from card where tcg_date <= :d order by 1")
                .param("d", tcgCutoff).query(Long.class).list(), variant);
    }

    /** Queues these passcodes' images for background fetching (e.g. a season's pool); cached ones are skipped. */
    public int prefetch(java.util.Collection<Long> passcodes, ImageVariant variant) {
        List<ImageKey> missing = passcodes.stream()
                .distinct()
                .map(p -> new ImageKey(variant, p))
                .filter(k -> !store.exists(k))
                .toList();
        if (missing.isEmpty()) return 0;
        AtomicInteger done = new AtomicInteger();
        prefetcher.execute(() -> {
            for (ImageKey key : missing) {
                try {
                    fetchOnce(key).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    log.warn("Prefetch of {} failed: {}", key.path(), e.getCause().getMessage());
                }
                if (done.incrementAndGet() % 250 == 0) log.info("Image prefetch: {}/{}", done.get(), missing.size());
            }
            log.info("Image prefetch finished: {} images ({})", missing.size(), variant.path());
        });
        return missing.size();
    }

    private CompletableFuture<Optional<byte[]>> fetchOnce(ImageKey key) {
        CompletableFuture<Optional<byte[]>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<byte[]>> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) return existing;
        fetchers.execute(() -> {
            try {
                mine.complete(fetchAndStore(key));
            } catch (Throwable t) {
                mine.completeExceptionally(t);
            } finally {
                inFlight.remove(key, mine);
            }
        });
        return mine;
    }

    private Optional<byte[]> fetchAndStore(ImageKey key) throws InterruptedException {
        Optional<byte[]> cached = store.get(key);   // another fetch may have just finished
        if (cached.isPresent()) return cached;
        throttle.acquire();
        Integer max = key.variant().maxDimension();
        Optional<byte[]> fetched = source.fetch(key).map(bytes -> max == null ? bytes : ImageResizer.fitWithin(bytes, max));
        fetched.ifPresent(bytes -> store.put(key, bytes));
        return fetched;
    }

    private boolean inCatalog(long passcode) {
        return jdbc.sql("select exists (select 1 from card where passcodes @> array[cast(:p as bigint)])")
                .param("p", passcode).query(Boolean.class).single();
    }

    @PreDestroy
    void shutdown() {
        prefetcher.shutdownNow();
        fetchers.shutdownNow();
    }
}
