package com.ygoleague.banlist;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves ban list card references to catalog cards. Pure logic, no Spring.
 *
 * <p>Names match case-insensitively after normalizing typography (curly quotes, en/em dashes,
 * accents, extra spaces), since lists copied from wikis and spreadsheets vary. A passcode, when
 * given, wins over the name and must belong to a card with that name. Unmatched names come back
 * with the closest catalog names as suggestions.
 */
public final class CardNameIndex {

    /** What resolution needs to know about a catalog card. */
    public record CatalogCard(long id, String name, List<Long> passcodes, LocalDate tcgDate) {}

    public sealed interface Resolution {}
    public record Matched(CatalogCard card) implements Resolution {}
    public record Unmatched(CardRef ref, String reason, List<String> suggestions) implements Resolution {}

    private final List<CatalogCard> cards;
    private final Map<String, List<CatalogCard>> byName = new HashMap<>();
    private final Map<Long, CatalogCard> byPasscode = new HashMap<>();

    public CardNameIndex(List<CatalogCard> cards) {
        this.cards = List.copyOf(cards);
        for (CatalogCard c : cards) {
            byName.computeIfAbsent(normalize(c.name()), k -> new ArrayList<>()).add(c);
            for (Long p : c.passcodes()) byPasscode.put(p, c);
        }
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    public Resolution resolve(CardRef ref) {
        String key = normalize(ref.name());
        if (ref.passcode() != null) {
            CatalogCard c = byPasscode.get(ref.passcode());
            if (c == null) return new Unmatched(ref, "no card has passcode " + ref.passcode(), suggest(key));
            if (!normalize(c.name()).equals(key)) {
                return new Unmatched(ref, "passcode " + ref.passcode() + " is '" + c.name() + "'", List.of(c.name()));
            }
            return new Matched(c);
        }
        List<CatalogCard> hits = byName.getOrDefault(key, List.of());
        if (hits.size() == 1) return new Matched(hits.getFirst());
        if (hits.size() > 1) {
            return new Unmatched(ref, "name is ambiguous; add a passcode",
                    hits.stream().map(c -> c.name() + " " + c.passcodes()).toList());
        }
        return new Unmatched(ref, "no card with this name", suggest(key));
    }

    /** Lowercase, ASCII quotes/dashes, no accents, single spaces. */
    public static String normalize(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-').replace('−', '-');
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<String> suggest(String key) {
        record Scored(String name, int distance) {}
        int limit = Math.max(3, key.length() / 3);
        return cards.stream()
                .map(c -> new Scored(c.name(), levenshtein(key, normalize(c.name()), limit)))
                .filter(s -> s.distance() <= limit)
                .sorted(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name))
                .limit(3)
                .map(Scored::name)
                .toList();
    }

    /** Edit distance, giving up early (returning limit + 1) once it must exceed {@code limit}. */
    static int levenshtein(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] t = prev; prev = curr; curr = t;
        }
        return prev[b.length()];
    }
}
