package com.ygoleague.api.admin;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.catalog.CatalogSync;
import com.ygoleague.catalog.CatalogSyncService;
import com.ygoleague.catalog.SyncTrigger;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site admin actions on the global catalog. {@code /api/admin/**} is restricted to admins in
 * {@code SecurityConfig}. The sync is idempotent (skipped when YGOPRODeck hasn't changed) and
 * every run is recorded in {@code catalog_sync}; who asked for it goes to the log.
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminCatalogController {

    private static final Logger log = LoggerFactory.getLogger(AdminCatalogController.class);

    public record SyncResult(String status, Instant startedAt, Instant finishedAt, String sourceVersion,
                             Integer cardsAdded, Integer cardsUpdated, String error) {}

    private final CatalogSyncService sync;

    public AdminCatalogController(CatalogSyncService sync) {
        this.sync = sync;
    }

    /** Runs a catalog sync now and waits for it (a few seconds). */
    @PostMapping("/sync")
    public SyncResult sync(@RequestParam(defaultValue = "false") boolean force, @AuthenticationPrincipal CurrentUser admin) {
        log.info("Catalog sync requested by {} (force={})", admin, force);
        CatalogSync run = sync.sync(SyncTrigger.MANUAL, force);
        return new SyncResult(run.getStatus().name(), run.getStartedAt(), run.getFinishedAt(), run.getSourceVersion(),
                run.getCardsAdded(), run.getCardsUpdated(), run.getError());
    }
}
