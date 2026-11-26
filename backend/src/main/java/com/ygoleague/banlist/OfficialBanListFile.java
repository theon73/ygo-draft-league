package com.ygoleague.banlist;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * One curated official ban list file, e.g. {@code banlists/tcg/2008-03-01.yaml}:
 *
 * <pre>
 * name: TCG March 2008
 * effective: 2008-03-01
 * source: https://yugipedia.com/...
 * forbidden:
 *   - "Butterfly Dagger - Elma"
 *   - { name: "Sangan", passcode: 26202165 }   # passcode only when a name is ambiguous
 * limited: [ ... ]
 * semiLimited: [ ... ]
 * </pre>
 *
 * Quote every card name: names like {@code Number 16: Shock Master} are otherwise read as YAML maps.
 * Parsing validates structure only; matching names to the catalog is {@link CardNameIndex}'s job.
 *
 * @param key path under {@code banlists/} without extension, e.g. {@code tcg/2008-03-01}; the
 *            permanent identity of the list
 */
public record OfficialBanListFile(String key, String name, LocalDate effective, String source,
                                  Map<CardRef, BanStatus> entries) {

    private static final Map<String, BanStatus> SECTIONS = Map.of(
            "forbidden", BanStatus.FORBIDDEN,
            "limited", BanStatus.LIMITED,
            "semilimited", BanStatus.SEMI_LIMITED);
    private static final Set<String> KNOWN_KEYS = Set.of("name", "effective", "source", "forbidden", "limited", "semilimited");

    public static OfficialBanListFile parse(String key, InputStream yaml) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(doc instanceof Map<?, ?> root)) throw invalid(key, "top level must be a mapping");

        Map<String, Object> fields = new LinkedHashMap<>();
        root.forEach((k, v) -> fields.put(String.valueOf(k).toLowerCase(Locale.ROOT), v));
        for (String k : fields.keySet()) {
            if (!KNOWN_KEYS.contains(k)) throw invalid(key, "unknown field '" + k + "'");
        }

        String name = requireString(key, fields, "name");
        LocalDate effective = toDate(key, fields.get("effective"));
        String source = fields.get("source") == null ? null : String.valueOf(fields.get("source"));

        String stem = key.substring(key.lastIndexOf('/') + 1);
        if (!stem.equals(effective.toString())) {
            throw invalid(key, "file name must be the effective date (" + effective + ".yaml)");
        }

        Map<CardRef, BanStatus> entries = new LinkedHashMap<>();
        Set<String> seenNames = new HashSet<>();
        for (var section : SECTIONS.entrySet()) {
            Object list = fields.get(section.getKey());
            if (list == null) continue;
            if (!(list instanceof List<?> items)) throw invalid(key, "'" + section.getKey() + "' must be a list");
            for (Object item : items) {
                CardRef ref = toCardRef(key, item);
                if (!seenNames.add(CardNameIndex.normalize(ref.name()))) {
                    throw invalid(key, "'" + ref.name() + "' is listed more than once");
                }
                entries.put(ref, section.getValue());
            }
        }
        if (entries.isEmpty()) throw invalid(key, "list has no cards");
        return new OfficialBanListFile(key, name, effective, source, entries);
    }

    private static CardRef toCardRef(String key, Object item) {
        if (item instanceof String s) return CardRef.named(s);
        if (item instanceof Map<?, ?> m) {
            if (m.containsKey("name")) {
                Object passcode = m.get("passcode");
                return new CardRef(String.valueOf(m.get("name")),
                        passcode == null ? null : ((Number) passcode).longValue());
            }
            throw invalid(key, "entry " + m + " looks like an unquoted name containing ': '; quote it");
        }
        throw invalid(key, "entries must be quoted names or {name, passcode} maps, got: " + item);
    }

    private static String requireString(String key, Map<String, Object> fields, String field) {
        Object v = fields.get(field);
        if (v == null || String.valueOf(v).isBlank()) throw invalid(key, "'" + field + "' is required");
        return String.valueOf(v);
    }

    private static LocalDate toDate(String key, Object v) {
        if (v instanceof Date d) return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if (v instanceof String s) return LocalDate.parse(s);
        throw invalid(key, "'effective' must be a date (YYYY-MM-DD)");
    }

    private static IllegalArgumentException invalid(String key, String message) {
        return new IllegalArgumentException("Ban list " + key + ": " + message);
    }

    /** Entries in file order, for reporting. */
    public List<Map.Entry<CardRef, BanStatus>> entryList() {
        return new ArrayList<>(entries.entrySet());
    }
}
