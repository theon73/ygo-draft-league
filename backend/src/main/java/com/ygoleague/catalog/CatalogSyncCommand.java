package com.ygoleague.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot sync from the command line: starts the app without the web server, syncs, exits with
 * status 0 on success/skip or 1 on failure. Use {@code ./gradlew syncCatalog} (add
 * {@code -Pforce} to re-import even when the source version hasn't changed), or in a deployed
 * container: {@code java -jar app.jar --app.catalog.sync-and-exit=true --spring.main.web-application-type=none}.
 */
@Component
@ConditionalOnProperty(name = "app.catalog.sync-and-exit", havingValue = "true")
class CatalogSyncCommand implements ApplicationRunner {

    private final CatalogSyncService service;
    private final ConfigurableApplicationContext context;
    private final boolean force;

    CatalogSyncCommand(CatalogSyncService service, ConfigurableApplicationContext context,
                       @Value("${app.catalog.sync.force:false}") boolean force) {
        this.service = service;
        this.context = context;
        this.force = force;
    }

    @Override
    public void run(ApplicationArguments args) {
        CatalogSync result = service.sync(SyncTrigger.MANUAL, force);
        System.out.println(result);
        int code = result.getStatus() == SyncStatus.FAILED ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
