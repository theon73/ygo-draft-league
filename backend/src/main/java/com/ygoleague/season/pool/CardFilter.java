package com.ygoleague.season.pool;

import com.ygoleague.season.pool.PoolService.Deck;
import com.ygoleague.season.pool.PoolService.Kind;
import com.ygoleague.season.pool.PoolService.PoolQuery;
import com.ygoleague.season.pool.PoolService.Sort;
import com.ygoleague.season.pool.PoolService.Text;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The card filters and sort behind a {@link PoolQuery}, as SQL over {@code card c} and an optional
 * ban list entry {@code b} (YGO-38: shared by a season's pool and the catalog's Cards page, so both
 * filter the same way). Every condition is a fixed SQL fragment; user input only ever goes in as a
 * bound parameter.
 */
public final class CardFilter {

    private static final List<String> MAIN_FRAMES = List.of("normal", "effect", "ritual", "normal_pendulum", "effect_pendulum", "ritual_pendulum");
    private static final List<String> EXTRA_FRAMES = List.of("fusion", "synchro", "xyz", "link", "fusion_pendulum", "synchro_pendulum", "xyz_pendulum");
    private static final java.util.Set<String> BAN_STATUSES = java.util.Set.of("FORBIDDEN", "LIMITED", "SEMI_LIMITED", "UNLIMITED");
    private static final int MAX_CHOICES = 100;

    private CardFilter() {}

    /** SQL conditions joined with "and", and their bound values. */
    public static final class Conditions {
        final List<String> sql = new java.util.ArrayList<>();
        final Map<String, Object> params = new java.util.LinkedHashMap<>();

        public void add(String condition) { sql.add(condition); }

        public void add(String condition, String name, Object value) {
            sql.add(condition);
            params.put(name, value);
        }

        void param(String name, Object value) {
            if (value != null && !(value instanceof List<?> l && l.isEmpty())) params.put(name, value);
        }

        void range(String column, String name, Integer min, Integer max) {
            if (min != null) add(column + " >= :" + name + "Min", name + "Min", min);
            if (max != null) add(column + " <= :" + name + "Max", name + "Max", max);
        }

        /** The conditions joined with "and" ("true" when there are none). */
        public String where() {
            return sql.isEmpty() ? "true" : String.join(" and ", sql);
        }

        public Map<String, Object> params() {
            return params;
        }
    }

    /** Adds the query's card conditions: name, card text, kinds and subtypes, monster details, ban status, stat ranges. */
    public static void apply(PoolQuery query, Conditions where) {
        where.add("lower(c.name) like :q escape '\\'", "q", like(query.q()));
        if (notBlank(query.cardText())) {
            if (query.cardText().strip().length() > 100) throw bad("Card text search is at most 100 characters");
            where.add("lower(c.description) like :cardText escape '\\'", "cardText", like(query.cardText()));
        }

        // Kinds: the ones chosen, or the ones the other filters imply. Each kind can carry its own subtype choice.
        boolean monsterFilters = !empty(query.monsterType()) || !empty(query.deck()) || !empty(query.summon()) || !empty(query.text())
                || !empty(query.ability()) || !empty(query.attribute()) || anyOf(query.levelMin(), query.levelMax(), query.rankMin(),
                query.rankMax(), query.linkMin(), query.linkMax(), query.atkMin(), query.atkMax(), query.defMin(), query.defMax(),
                query.scaleMin(), query.scaleMax());
        java.util.Set<Kind> kinds = java.util.EnumSet.noneOf(Kind.class);
        if (!empty(query.kind())) kinds.addAll(query.kind());
        else {
            if (monsterFilters) kinds.add(Kind.MONSTER);
            if (!empty(query.spellType())) kinds.add(Kind.SPELL);
            if (!empty(query.trapType())) kinds.add(Kind.TRAP);
        }
        if (!kinds.isEmpty()) {
            List<String> clauses = new java.util.ArrayList<>();
            if (kinds.contains(Kind.MONSTER)) {
                clauses.add("(c.frame_type not in ('spell', 'trap', 'token')" + (empty(query.monsterType()) ? "" : " and c.race in (:monsterType)") + ")");
                where.param("monsterType", choices(query.monsterType()));
            }
            if (kinds.contains(Kind.SPELL)) {
                clauses.add("(c.frame_type = 'spell'" + (empty(query.spellType()) ? "" : " and c.race in (:spellType)") + ")");
                where.param("spellType", choices(query.spellType()));
            }
            if (kinds.contains(Kind.TRAP)) {
                clauses.add("(c.frame_type = 'trap'" + (empty(query.trapType()) ? "" : " and c.race in (:trapType)") + ")");
                where.param("trapType", choices(query.trapType()));
            }
            where.add("(" + String.join(" or ", clauses) + ")");
        }

        if (!empty(query.deck()) && query.deck().size() == 1) {
            where.add("c.frame_type in (:frames)", "frames", query.deck().getFirst() == Deck.MAIN ? MAIN_FRAMES : EXTRA_FRAMES);
        }
        if (!empty(query.summon())) where.add("c.monster_tags && cast(array[:summon] as text[])", "summon", tags(query.summon()));
        if (!empty(query.text()) && query.text().size() == 1) {   // both chosen = no restriction
            where.add(query.text().getFirst() == Text.EFFECT ? "'Effect' = any(c.monster_tags)" : "not ('Effect' = any(c.monster_tags))");
        }
        if (!empty(query.ability())) where.add("c.monster_tags && cast(array[:ability] as text[])", "ability", tags(query.ability()));
        if (!empty(query.attribute())) where.add("c.attribute in (:attribute)", "attribute", choices(query.attribute()));
        if (!empty(query.archetype())) where.add("c.archetype in (:archetype)", "archetype", choices(query.archetype()));
        if (!empty(query.banStatus())) {
            List<String> statuses = choices(query.banStatus()).stream().map(x -> x.strip().toUpperCase()).toList();
            for (String st : statuses) if (!BAN_STATUSES.contains(st)) throw bad("Unknown ban status " + st);
            List<String> listed = statuses.stream().filter(st -> !st.equals("UNLIMITED")).toList();
            boolean unlimited = statuses.contains("UNLIMITED");
            if (listed.isEmpty()) where.add("b.status is null");
            else where.add("(b.status in (:ban)" + (unlimited ? " or b.status is null" : "") + ")", "ban", listed);
        }
        where.range("c.level", "level", query.levelMin(), query.levelMax());
        where.range("c.rank", "rank", query.rankMin(), query.rankMax());
        where.range("c.link_rating", "link", query.linkMin(), query.linkMax());
        where.range("c.atk", "atk", query.atkMin(), query.atkMax());
        where.range("c.def", "def", query.defMin(), query.defMax());
        where.range("c.pendulum_scale", "scale", query.scaleMin(), query.scaleMax());
    }

    /** The ORDER BY for a sort (name by default), ending in the card id so pages are stable. */
    public static String order(Sort sort) {
        return switch (sort == null ? Sort.NAME : sort) {
            case NAME -> "lower(c.name), c.id";
            case RELEASE -> "c.tcg_date desc, lower(c.name), c.id";
            case ATK -> "c.atk desc nulls last, lower(c.name), c.id";
            case DEF -> "c.def desc nulls last, lower(c.name), c.id";
            case LEVEL -> "coalesce(c.level, c.rank, c.link_rating) desc nulls last, lower(c.name), c.id";
        };
    }

    /** A case-insensitive "contains" pattern for {@code like ... escape '\'}. */
    public static String like(String q) {
        String term = q == null ? "" : q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + term + "%";
    }

    /** Enum choices as the tag words in {@code card.monster_tags} ("Xyz", "Tuner", ...). */
    private static List<String> tags(List<? extends Enum<?>> values) {
        return values.stream().map(v -> v.name().charAt(0) + v.name().substring(1).toLowerCase()).distinct().toList();
    }

    private static List<String> choices(List<String> values) {
        if (values == null) return List.of();
        List<String> cleaned = values.stream().filter(CardFilter::notBlank).map(String::strip).distinct().toList();
        if (cleaned.size() > MAX_CHOICES) throw bad("Too many choices in one filter");
        return cleaned;
    }

    private static boolean empty(List<?> values) {
        return values == null || values.stream().allMatch(v -> v == null || (v instanceof String str && str.isBlank()));
    }

    private static boolean anyOf(Integer... values) {
        return java.util.Arrays.stream(values).anyMatch(java.util.Objects::nonNull);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
