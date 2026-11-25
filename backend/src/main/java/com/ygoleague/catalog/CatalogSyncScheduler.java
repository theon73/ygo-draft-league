package com.ygoleague.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Weekly catalog sync. Disable with {@code app.catalog.sync.scheduled=false}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.catalog.sync.scheduled", havingValue = "true")
class CatalogSyncScheduler {

    private final CatalogSyncService service;

    CatalogSyncScheduler(CatalogSyncService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.catalog.sync.cron}", zone = "UTC")
    void weekly() {
        service.sync(SyncTrigger.SCHEDULED, false);
    }
}
