package com.ygoleague.banlist;

import com.ygoleague.banlist.CardNameIndex.CatalogCard;
import com.ygoleague.banlist.CardNameIndex.Matched;
import com.ygoleague.banlist.CardNameIndex.Unmatched;
import com.ygoleague.catalog.CatalogSyncedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports the curated official ban lists ({@code banlists/**}{@code /*.yaml} on the classpath).
 *
 * <ul>
 *   <li>Runs on startup, after every successful catalog sync, and via {@code ./gradlew importBanLists}.
 *   <li>Each file is all-or-nothing: if any card name can't be matched, that list is not imported
 *       and the report names the card with suggestions. Other files still import.
 *   <li>Idempotent: a file whose resolved content equals the list's latest revision is a no-op.
 *       A changed file (e.g. a corrected entry) becomes a new revision; seasons pinned to the old
 *       revision keep it.
 * </ul>
 */
@Component
public class OfficialBanListImporter {

    private static final Logger log = LoggerFactory.getLogger(OfficialBanListImporter.class);
    private static final String ROOT = "banlists/";

    public record Failure(String key, List<String> problems) {}

    public record Report(List<String> imported, List<String> unchanged, List<Failure> failed,
                         List<String> warnings, String skippedReason) {
        public boolean hasFailures() {
            return !failed.isEmpty();
        }

        @Override
        public String toString() {
            if (skippedReason != null) return "Ban list import skipped: " + skippedReason;
            StringBuilder sb = new StringBuilder("Ban list import: imported=" + imported
                    + " unchanged=" + unchanged + " failed=" + failed.stream().map(Failure::key).toList());
            failed.forEach(f -> f.problems().forEach(p -> sb.append("\n  ").append(f.key()).append(": ").append(p)));
            warnings.forEach(w -> sb.append("\n  warning: ").append(w));
            return sb.toString();
        }
    }

    private final String location;
    private final boolean onStartup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BanListRepository lists;
    private final BanListRevisionRepository revisions;
    private final BanListService service;

    public OfficialBanListImporter(@Value("${app.banlists.location}") String location,
                                   @Value("${app.banlists.import-on-startup}") boolean onStartup,
                                   JdbcTemplate jdbc, TransactionTemplate tx, BanListRepository lists,
                                   BanListRevisionRepository revisions, BanListService service) {
        this.location = location;
        this.onStartup = onStartup;
        this.jdbc = jdbc;
        this.tx = tx;
        this.lists = lists;
        this.revisions = revisions;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (onStartup) logReport(importAll());
    }

    @EventListener
    void onCatalogSynced(CatalogSyncedEvent event) {
        logReport(importAll());
    }

    public Report importAll() {
        CardNameIndex index = new CardNameIndex(jdbc.query(
                "select id, name, passcodes, tcg_date from card",
                (rs, i) -> new CatalogCard(rs.getLong(1), rs.getString(2),
                        Arrays.asList((Long[]) rs.getArray(3).getArray()), rs.getObject(4, java.time.LocalDate.class))));
        if (index.isEmpty()) {
            return new Report(List.of(), List.of(), List.of(), List.of(), "card catalog is empty; run a catalog sync first");
        }

        List<String> imported = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Resource resource : resources()) {
            String key = keyOf(resource);
            try {
                OfficialBanListFile file;
                try (InputStream in = resource.getInputStream()) {
                    file = OfficialBanListFile.parse(key, in);
                }
                Map<Long, BanStatus> content = new LinkedHashMap<>();
                List<String> problems = new ArrayList<>();
                for (var entry : file.entryList()) {
                    switch (index.resolve(entry.getKey())) {
                        case Matched m -> {
                            content.put(m.card().id(), entry.getValue());
                            if (m.card().tcgDate().isAfter(file.effective())) {
                                warnings.add(key + ": '" + m.card().name() + "' was released " + m.card().tcgDate()
                                        + ", after the list took effect");
                            }
                        }
                        case Unmatched u -> problems.add("'" + u.ref() + "': " + u.reason()
                                + (u.suggestions().isEmpty() ? "" : "; did you mean " + u.suggestions() + "?"));
                    }
                }
                if (!problems.isEmpty()) {
                    failed.add(new Failure(key, problems));
                    continue;
                }
                boolean changed = Boolean.TRUE.equals(tx.execute(status -> write(file, content)));
                (changed ? imported : unchanged).add(key);
            } catch (IOException | RuntimeException e) {
                failed.add(new Failure(key, List.of(e.getMessage())));
            }
        }
        return new Report(imported, unchanged, failed, warnings, null);
    }

    /** @return true if a new revision was written */
    private boolean write(OfficialBanListFile file, Map<Long, BanStatus> content) {
        BanList list = lists.findByOfficialKey(file.key())
                .orElseGet(() -> lists.save(BanList.official(file.key(), file.name(), file.effective())));
        list.updateOfficialMetadata(file.name(), file.effective());
        var latest = revisions.findFirstByBanListIdOrderByRevisionDesc(list.getId());
        if (latest.isPresent() && latest.get().getContentHash().equals(BanListContent.hash(content))) return false;
        int next = latest.map(r -> r.getRevision() + 1).orElse(1);
        service.writeRevision(list, next, null, content, file.source(), null);
        return true;
    }

    private List<Resource> resources() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(location);
            return Arrays.stream(found).sorted(Comparator.comparing(OfficialBanListImporter::keyOf)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code .../banlists/tcg/2008-03-01.yaml} -> {@code tcg/2008-03-01}. */
    static String keyOf(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int root = url.lastIndexOf("/" + ROOT);
            String path = root >= 0 ? url.substring(root + ROOT.length() + 1) : resource.getFilename();
            return path.replaceFirst("\\.ya?ml$", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void logReport(Report report) {
        if (report.hasFailures()) log.error("{}", report);
        else log.info("{}", report);
    }
}
