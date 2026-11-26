package com.ygoleague.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls the catalog from a {@link CatalogSource} into the card tables.
 *
 * <ul>
 *   <li><b>Idempotent:</b> cards are matched to stored rows by {@link CardMatcher} (Konami id, else
 *       passcode) and unchanged cards (same content hash) are not written at all, so re-running
 *       is cheap and safe. Nothing is ever deleted.
 *   <li><b>Atomic:</b> all writes happen in one transaction; a failed sync leaves the catalog as
 *       it was.
 *   <li><b>Single-flight:</b> a Postgres advisory lock stops a manual and a scheduled sync from
 *       overlapping.
 *   <li><b>Skips no-ops:</b> if the source version equals the last successful sync's, nothing is
 *       downloaded unless {@code force} is set.
 * </ul>
 * Every attempt is recorded in {@code catalog_sync}, committed separately so failures are logged.
 * A successful sync publishes {@link CatalogSyncedEvent} after its transaction commits.
 */
@Service
public class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);
    private static final long LOCK_KEY = 0x59474F_434154L; // "YGO" "CAT"
    private static final int BATCH_SIZE = 500;

    private final CatalogSource source;
    private final CatalogWriter writer;
    private final CatalogSyncRepository syncs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public CatalogSyncService(CatalogSource source, CatalogWriter writer, CatalogSyncRepository syncs,
                              JdbcTemplate jdbc, TransactionTemplate tx, ApplicationEventPublisher events) {
        this.source = source;
        this.writer = writer;
        this.syncs = syncs;
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
    }

    /** Runs a sync and returns its audit row. Never throws for sync failures; check the status. */
    public CatalogSync sync(SyncTrigger trigger, boolean force) {
        CatalogSync run = syncs.save(new CatalogSync(trigger));
        String version = null;
        try {
            version = source.version();
            String lastVersion = syncs.findFirstByStatusOrderByStartedAtDesc(SyncStatus.SUCCEEDED)
                    .map(CatalogSync::getSourceVersion).orElse(null);
            if (!force && Objects.equals(version, lastVersion)) {
                run.skipped(version);
                log.info("Catalog sync skipped: source version {} already imported", version);
                return syncs.save(run);
            }
            String v = version;
            tx.executeWithoutResult(status -> apply(run, v));
            log.info("Catalog sync finished: {}", run);
        } catch (RuntimeException e) {
            run.failed(version, e.toString());
            log.error("Catalog sync failed", e);
        }
        CatalogSync saved = syncs.save(run);
        if (saved.getStatus() == SyncStatus.SUCCEEDED) events.publishEvent(new CatalogSyncedEvent(saved));
        return saved;
    }

    private void apply(CatalogSync run, String version) {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) throw new IllegalStateException("Another catalog sync is running");

        List<SetRecord> sets = source.sets();
        Map<String, Long> setIds = writer.upsertSets(sets);
        CardMatcher matcher = new CardMatcher(writer.storedCards());

        List<CardRecord> inserts = new ArrayList<>(BATCH_SIZE);
        List<CatalogWriter.CardUpdate> updates = new ArrayList<>(BATCH_SIZE);
        int[] added = {0};
        int[] updated = {0};
        int ignored = source.forEachCard(sets, card -> {
            var stored = matcher.match(card);
            if (stored.isEmpty()) {
                inserts.add(card);
                added[0]++;
            } else if (!stored.get().contentHash().equals(card.contentHash())) {
                updates.add(new CatalogWriter.CardUpdate(stored.get().id(), card));
                updated[0]++;
            }
            if (inserts.size() + updates.size() >= BATCH_SIZE) {
                writer.writeCards(inserts, updates, setIds);
                inserts.clear();
                updates.clear();
            }
        });
        writer.writeCards(inserts, updates, setIds);
        run.succeeded(version, sets.size(), added[0], updated[0], ignored);
    }
}
