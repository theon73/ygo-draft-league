package com.ygoleague.banlist;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew importBanLists}: imports the official ban list files, prints the report, and
 * exits 1 if any file failed (e.g. an unmatched card name), so it doubles as a check when
 * adding or editing a list.
 */
@Component
@ConditionalOnProperty(name = "app.banlists.import-and-exit", havingValue = "true")
class BanListImportCommand implements ApplicationRunner {

    private final OfficialBanListImporter importer;
    private final ConfigurableApplicationContext context;

    BanListImportCommand(OfficialBanListImporter importer, ConfigurableApplicationContext context) {
        this.importer = importer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        OfficialBanListImporter.Report report = importer.importAll();
        System.out.println(report);
        int code = report.hasFailures() || report.skippedReason() != null ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
