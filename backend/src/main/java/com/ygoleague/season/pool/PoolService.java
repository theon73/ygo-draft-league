package com.ygoleague.season.pool;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.image.CardImageService;
import com.ygoleague.image.ImageVariant;
import com.ygoleague.season.Season;
import com.ygoleague.season.SeasonAccess;
import com.ygoleague.season.SeasonStatus;
import com.ygoleague.season.rules.SeasonRulesService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * A season's card pool: the dated pool generated from the rules, plus the exception list, with
 * per-card flags.
 *
 * <ul>
 *   <li><b>Generating</b> (setup only) takes every card released in the TCG on or before the
 *       rules' cutoff, except tokens. Regenerating after a cutoff change adds and drops dated
 *       cards; exceptions and anything removed by hand are kept as they are.
 *   <li><b>Exceptions</b> are cards added by hand on top of the dated pool (typically just past
 *       the cutoff), with an optional reason. They can be added before or during the season.
 *   <li><b>Removing</b> a card is only possible while nobody owns it ({@link PoolOwnership});
 *       otherwise soft-ban it.
 *   <li>During setup changes take effect from week 0. Once the season has started, a change takes
 *       effect from a chosen week, no earlier than the current one, and the past keeps the pool it had.
 *   <li>Every change is logged in {@code pool_change}. Any member can see the pool; the season's
 *       commissioners change it.
 *   <li>Thumbnails of new pool cards are fetched in the background into the image cache.
 * </ul>
 */
@Service
public class PoolService {

    static final int PAGE_SIZE = 50;

    public enum Filter { ALL, DATED, EXCEPTIONS, FLAGGED, BAN_LIST, REMOVED }

    /**
     * @param generatedCutoff the cutoff the dated pool was last generated from (null if never)
     * @param rulesCutoff     the cutoff in the rules now; if it differs, regenerate
     */
    /**
     * @param overridden   pool cards whose max copies are set here rather than taken from the ban list
     * @param banList      the ban list in the season's rules (null for none); its counts are of pool cards
     */
    public record PoolSummary(boolean canManage, boolean started, int minEffectiveWeek, LocalDate rulesCutoff,
                              LocalDate generatedCutoff, Instant generatedAt, long total, long dated, long exceptions,
                              long softBanned, long requiresOwnership, long overridden, long removed, String banList,
                              long forbidden, long limited, long semiLimited) {}

    /**
     * @param passcode     the card's first artwork, for its thumbnail
     * @param maxPlayable  this league's override of the copy limit (null = none)
     * @param banStatus    FORBIDDEN / LIMITED / SEMI_LIMITED on the season's ban list, or null
     * @param effectiveMax copies a player may play: the override if set, else the ban list's limit (0/1/2), else 3
     * @param race         monster type, or a spell/trap's subtype ("Quick-Play", "Continuous", ...)
     * @param ownedBy      who holds the card (display names, in pick order), or null if nobody does
     */
    public record PoolCardView(long cardId, String name, String cardType, String archetype, LocalDate tcgDate, long passcode,
                               String source, boolean softBanned, boolean requiresOwnership, Integer maxPlayable,
                               String reason, int addedInWeek, Integer removedInWeek, String banStatus, int effectiveMax,
                               String race, String attribute, Integer level, Integer rank, Integer linkRating, Integer atk,
                               Integer def, String ownedBy) {}

    public record CardPage(long total, int page, int pageSize, List<PoolCardView> items) {}

    /** A catalog card that could be added as an exception. */
    public record Candidate(long cardId, String name, String cardType, LocalDate tcgDate, long passcode, boolean inPool) {}

    public record ArchetypeCount(String archetype, long cards) {}

    public record ChangeView(String kind, String card, int effectiveFromWeek, Map<String, Object> detail, String reason,
                             String by, Instant at) {}

    /**
     * Null fields are left as they are. {@code maxPlayable} (0 to 3) overrides the ban list's limit for
     * this league, in either direction (a house rule); {@code clearMaxPlayable} goes back to the ban list.
     */
    public record FlagsUpdate(Boolean softBanned, Boolean requiresOwnership, Integer maxPlayable, Integer effectiveFromWeek,
                              String reason, Boolean clearMaxPlayable) {}

    public record AddRequest(Long cardId, String reason, Integer effectiveFromWeek) {}

    public record RemoveRequest(String reason, Integer effectiveFromWeek) {}

    private final SeasonAccess access;
    private final SeasonRulesService rules;
    private final PoolOwnership ownership;
    private final CardImageService images;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PoolService(SeasonAccess access, SeasonRulesService rules, PoolOwnership ownership, CardImageService images,
                       JdbcClient jdbc, ObjectMapper json) {
        this.access = access;
        this.rules = rules;
        this.ownership = ownership;
        this.images = images;
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- Reading ------------------------------------------------------------------------------

    /**
     * Pool card columns, with the ban list status from the revision bound as :rev (null = no list).
     * Read rows with {@link #cardRow}; the rosters ({@code season.roster}) list cards the same way.
     */
    public static final String CARD_SELECT = """
            select c.id, c.name, c.card_type, c.archetype, c.tcg_date, c.passcodes[1] as passcode, p.source,
                   p.soft_banned, p.requires_ownership, p.max_playable, p.reason, p.added_in_week, p.removed_in_week, b.status,
                   c.race, c.attribute, c.level, c.rank, c.link_rating, c.atk, c.def,
                   (select string_agg(m.display_name, ', ' order by sp.seat) from card_holding h
                    join season_player sp on sp.id = h.season_player_id join league_member m on m.id = sp.league_member_id
                    where h.season_id = p.season_id and h.card_id = p.card_id) as owned_by
            from pool_card p join card c on c.id = p.card_id
            left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
            """;

    public static PoolCardView cardRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer override = (Integer) rs.getObject(10);
        String ban = rs.getString(14);
        int fromList = ban == null ? 3 : switch (ban) {
            case "FORBIDDEN" -> 0;
            case "LIMITED" -> 1;
            default -> 2;   // SEMI_LIMITED
        };
        return new PoolCardView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), rs.getLong(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9),
                override, rs.getString(11), rs.getInt(12), (Integer) rs.getObject(13), ban, override != null ? override : fromList,
                rs.getString(15), rs.getString(16), (Integer) rs.getObject(17), (Integer) rs.getObject(18), (Integer) rs.getObject(19),
                (Integer) rs.getObject(20), (Integer) rs.getObject(21), rs.getString(22));
    }

    /** The ban list revision in the season's rules, to bind as :rev in {@link #CARD_SELECT}. */
    public Long banListRevision(Season season) {
        return rules.latest(season).format().banListRevisionId();
    }

    @Transactional(readOnly = true)
    public PoolSummary summary(String leagueId, String seasonId, CurrentUser caller) {
        SeasonAccess.SeasonRef ref = access.member(leagueId, seasonId, caller);
        Season season = ref.season();
        var generated = jdbc.sql("""
                        select detail->>'cutoff' as cutoff, created_at from pool_change
                        where season_id = :s and kind = 'GENERATED' order by created_at desc, id desc limit 1""")
                .param("s", season.getId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant()})
                .optional();
        Long rev = banListRevision(season);
        Map<String, Object> c = jdbc.sql("""
                        select count(*) filter (where p.removed_in_week is null) as total,
                               count(*) filter (where p.removed_in_week is null and p.source = 'DATED') as dated,
                               count(*) filter (where p.removed_in_week is null and p.source = 'EXCEPTION') as exceptions,
                               count(*) filter (where p.removed_in_week is null and p.soft_banned) as soft_banned,
                               count(*) filter (where p.removed_in_week is null and p.requires_ownership) as requires_ownership,
                               count(*) filter (where p.removed_in_week is null and p.max_playable is not null) as overridden,
                               count(*) filter (where p.removed_in_week is not null) as removed,
                               count(*) filter (where p.removed_in_week is null and b.status = 'FORBIDDEN') as forbidden,
                               count(*) filter (where p.removed_in_week is null and b.status = 'LIMITED') as limited,
                               count(*) filter (where p.removed_in_week is null and b.status = 'SEMI_LIMITED') as semi_limited
                        from pool_card p left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where p.season_id = :s""")
                .param("s", season.getId()).param("rev", rev, java.sql.Types.BIGINT).query().singleRow();
        String banListName = rev == null ? null : jdbc.sql("""
                        select l.name from ban_list_revision r join ban_list l on l.id = r.ban_list_id where r.id = :rev""")
                .param("rev", rev).query(String.class).optional().orElse(null);
        return new PoolSummary(ref.commissioner(), started(season), minEffectiveWeek(season),
                rules.latest(season).format().tcgCutoff(),
                generated.map(g -> g[0] == null ? null : LocalDate.parse((String) g[0])).orElse(null),
                generated.map(g -> (Instant) g[1]).orElse(null),
                n(c, "total"), n(c, "dated"), n(c, "exceptions"), n(c, "soft_banned"), n(c, "requires_ownership"),
                n(c, "overridden"), n(c, "removed"), banListName, n(c, "forbidden"), n(c, "limited"), n(c, "semi_limited"));
    }

    public enum Kind { MONSTER, SPELL, TRAP }

    /** Which deck a monster goes in. */
    public enum Deck { MAIN, EXTRA }

    /** How a monster is summoned, from its type line. */
    public enum SummonType { RITUAL, FUSION, SYNCHRO, XYZ, LINK }

    /** NORMAL = no effect (Normal monsters, and non-effect Fusions etc.); EFFECT = has an effect. */
    public enum Text { NORMAL, EFFECT }

    public enum Ability { TUNER, FLIP, UNION, GEMINI, SPIRIT, TOON, PENDULUM }

    public enum Sort { NAME, RELEASE, ATK, DEF, LEVEL }

    /**
     * Everything the pool list can be narrowed and ordered by. Every field is optional. A list means
     * "any of these" (multiple selection); different fields must all match. Filters that only make
     * sense for one kind imply it: choosing a spell type shows spells, an ATK range shows monsters.
     *
     * @param show        the curation view: all cards, exceptions, flagged, on the ban list, removed
     * @param cardText    a substring of the card's text (effect, flavor text; pendulum and monster effects alike), any case
     * @param monsterType monster types ("Spellcaster"); spellType / trapType: subtypes ("Quick-Play")
     * @param banStatus   FORBIDDEN, LIMITED, SEMI_LIMITED, or UNLIMITED (not on the list)
     * @param available   only cards that can still be drafted: not soft-banned, not Forbidden (unless the
     *                    draft allows it), and with a copy nobody holds
     */
    public record PoolQuery(String q, String cardText, Filter show, Boolean available, List<Kind> kind, List<Deck> deck, List<SummonType> summon, List<Text> text,
                            List<Ability> ability, List<String> attribute, List<String> monsterType, List<String> spellType,
                            List<String> trapType, List<String> archetype, List<String> banStatus,
                            Integer levelMin, Integer levelMax, Integer rankMin, Integer rankMax, Integer linkMin, Integer linkMax,
                            Integer atkMin, Integer atkMax, Integer defMin, Integer defMax, Integer scaleMin, Integer scaleMax,
                            Sort sort, Integer page, Integer pageSize) {}

    private static final List<String> MAIN_FRAMES = List.of("normal", "effect", "ritual", "normal_pendulum", "effect_pendulum", "ritual_pendulum");
    private static final List<String> EXTRA_FRAMES = List.of("fusion", "synchro", "xyz", "link", "fusion_pendulum", "synchro_pendulum", "xyz_pendulum");
    private static final java.util.Set<String> BAN_STATUSES = java.util.Set.of("FORBIDDEN", "LIMITED", "SEMI_LIMITED", "UNLIMITED");
    private static final int MAX_CHOICES = 100;

    @Transactional(readOnly = true)
    public CardPage cards(String leagueId, String seasonId, PoolQuery query, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        int page = query.page() == null ? 0 : query.page();
        int size = query.pageSize() == null ? PAGE_SIZE : query.pageSize();
        if (page < 0) throw bad("page must be >= 0");
        if (size < 10 || size > 100) throw bad("pageSize must be 10 to 100");

        // Every condition is a fixed SQL fragment; user input only ever goes in as a bound parameter.
        Conditions where = new Conditions();
        where.add("p.season_id = :s", "s", season.getId());
        where.add("lower(c.name) like :q escape '\\'", "q", like(query.q()));
        if (notBlank(query.cardText())) {
            if (query.cardText().strip().length() > 100) throw bad("Card text search is at most 100 characters");
            where.add("lower(c.description) like :cardText escape '\\'", "cardText", like(query.cardText()));
        }
        where.add(switch (query.show() == null ? Filter.ALL : query.show()) {
            case ALL -> "p.removed_in_week is null";
            case DATED -> "p.removed_in_week is null and p.source = 'DATED'";
            case EXCEPTIONS -> "p.removed_in_week is null and p.source = 'EXCEPTION'";
            case FLAGGED -> "p.removed_in_week is null and (p.soft_banned or p.requires_ownership or p.max_playable is not null)";
            case BAN_LIST -> "p.removed_in_week is null and b.status is not null";
            case REMOVED -> "p.removed_in_week is not null";
        });

        if (Boolean.TRUE.equals(query.available())) where.add(availableCondition(season));

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

        String order = switch (query.sort() == null ? Sort.NAME : query.sort()) {
            case NAME -> "lower(c.name), c.id";
            case RELEASE -> "c.tcg_date desc, lower(c.name), c.id";
            case ATK -> "c.atk desc nulls last, lower(c.name), c.id";
            case DEF -> "c.def desc nulls last, lower(c.name), c.id";
            case LEVEL -> "coalesce(c.level, c.rank, c.link_rating) desc nulls last, lower(c.name), c.id";
        };

        Long rev = banListRevision(season);
        String conditions = String.join(" and ", where.sql);
        var count = jdbc.sql("""
                        select count(*) from pool_card p join card c on c.id = p.card_id
                        left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id""" + " where " + conditions)
                .param("rev", rev, java.sql.Types.BIGINT);
        var list = jdbc.sql(CARD_SELECT + " where " + conditions + " order by " + order + " limit :limit offset :offset")
                .param("rev", rev, java.sql.Types.BIGINT);
        for (var e : where.params.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            list = list.param(e.getKey(), e.getValue());
        }
        long total = count.query(Long.class).single();
        List<PoolCardView> items = list.param("limit", size).param("offset", (long) page * size).query((rs, n) -> cardRow(rs)).list();
        return new CardPage(total, page, size, items);
    }

    /**
     * "Available": in the pool, not soft-banned, not Forbidden (unless the draft allows it), with a
     * copy nobody holds. During the draft these are the draftable cards; after it, the free agents.
     * SQL over pool_card p and its ban list entry b.
     */
    private String availableCondition(Season season) {
        return "p.removed_in_week is null and not p.soft_banned and p.copies > " + PoolOwnership.HELD_COPIES
                + (rules.latest(season).draft().forbiddenDraftable() ? "" : " and b.status is distinct from 'FORBIDDEN'");
    }

    /** How many cards are available (see {@link #availableCondition}): the free agents once the draft is over. */
    public long availableCount(Season season) {
        return jdbc.sql("""
                        select count(*) from pool_card p left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where p.season_id = :s and """ + " " + availableCondition(season))
                .param("s", season.getId()).param("rev", banListRevision(season), java.sql.Types.BIGINT)
                .query(Long.class).single();
    }

    /** Whether one card is available (see {@link #availableCondition}): draftable now, or claimable once the draft is over. */
    public boolean isAvailable(Season season, long cardId) {
        return jdbc.sql("""
                        select exists (select 1 from pool_card p left join ban_list_entry b on b.revision_id = :rev and b.card_id = p.card_id
                        where p.season_id = :s and p.card_id = :c and """ + " " + availableCondition(season) + ")")
                .param("s", season.getId()).param("c", cardId).param("rev", banListRevision(season), java.sql.Types.BIGINT)
                .query(Boolean.class).single();
    }

    /** One pool card's row (removed ones too), or empty if it was never in the pool. */
    public java.util.Optional<PoolCardView> poolCard(Season season, long cardId) {
        return jdbc.sql(CARD_SELECT + " where p.season_id = :s and p.card_id = :c")
                .param("s", season.getId()).param("c", cardId).param("rev", banListRevision(season), java.sql.Types.BIGINT)
                .query((rs, n) -> cardRow(rs))
                .optional();
    }

    /** SQL conditions joined with "and", and their bound values. */
    private static final class Conditions {
        final List<String> sql = new java.util.ArrayList<>();
        final Map<String, Object> params = new java.util.LinkedHashMap<>();

        void add(String condition) { sql.add(condition); }

        void add(String condition, String name, Object value) {
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
    }

    /** Enum choices as the tag words in {@code card.monster_tags} ("Xyz", "Tuner", ...). */
    private static List<String> tags(List<? extends Enum<?>> values) {
        return values.stream().map(v -> v.name().charAt(0) + v.name().substring(1).toLowerCase()).distinct().toList();
    }

    private static List<String> choices(List<String> values) {
        if (values == null) return List.of();
        List<String> cleaned = values.stream().filter(PoolService::notBlank).map(String::strip).distinct().toList();
        if (cleaned.size() > MAX_CHOICES) throw bad("Too many choices in one filter");
        return cleaned;
    }

    private static boolean empty(List<?> values) {
        return values == null || values.stream().allMatch(v -> v == null || (v instanceof String str && str.isBlank()));
    }

    private static boolean anyOf(Integer... values) {
        return java.util.Arrays.stream(values).anyMatch(java.util.Objects::nonNull);
    }

    /** The values present in the pool, for the browse filters' dropdowns. */
    public record Facets(List<String> attributes, List<String> monsterTypes, List<String> spellTypes, List<String> trapTypes,
                         List<String> archetypes) {}

    @Transactional(readOnly = true)
    public Facets facets(String leagueId, String seasonId, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        java.util.function.Function<String, List<String>> distinct = condition -> jdbc.sql(
                        "select distinct " + condition + " from pool_card p join card c on c.id = p.card_id "
                                + "where p.season_id = :s and p.removed_in_week is null and " + condition + " is not null order by 1")
                .param("s", season.getId()).query(String.class).list();
        List<String> races = jdbc.sql("""
                        select c.frame_type in ('spell', 'trap') as st, c.frame_type, c.race
                        from pool_card p join card c on c.id = p.card_id
                        where p.season_id = :s and p.removed_in_week is null group by 1, 2, 3 order by 3""")
                .param("s", season.getId()).query((rs, n) -> rs.getString(2) + "|" + rs.getString(3)).list();
        List<String> monster = races.stream().filter(r -> !r.startsWith("spell|") && !r.startsWith("trap|")).map(r -> r.substring(r.indexOf('|') + 1)).distinct().toList();
        List<String> spell = races.stream().filter(r -> r.startsWith("spell|")).map(r -> r.substring(6)).distinct().toList();
        List<String> trap = races.stream().filter(r -> r.startsWith("trap|")).map(r -> r.substring(5)).distinct().toList();
        return new Facets(distinct.apply("c.attribute"), monster, spell, trap, distinct.apply("c.archetype"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Catalog cards matching a name, for adding exceptions (up to 20). */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(String leagueId, String seasonId, String q, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        if (q == null || q.strip().length() < 2) return List.of();
        return jdbc.sql("""
                        select c.id, c.name, c.card_type, c.tcg_date, c.passcodes[1],
                               exists (select 1 from pool_card p where p.season_id = :s and p.card_id = c.id and p.removed_in_week is null)
                        from card c where lower(c.name) like :q escape '\\'
                        order by lower(c.name) limit 20""")
                .param("s", season.getId()).param("q", like(q))
                .query((rs, n) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getLong(5), rs.getBoolean(6)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ArchetypeCount> archetypes(String leagueId, String seasonId, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        return jdbc.sql("""
                        select c.archetype, count(*) from pool_card p join card c on c.id = p.card_id
                        where p.season_id = :s and p.removed_in_week is null and c.archetype is not null
                        group by c.archetype order by count(*) desc, c.archetype""")
                .param("s", season.getId())
                .query((rs, n) -> new ArchetypeCount(rs.getString(1), rs.getLong(2))).list();
    }

    @Transactional(readOnly = true)
    public List<ChangeView> changes(String leagueId, String seasonId, CurrentUser caller) {
        Season season = access.member(leagueId, seasonId, caller).season();
        return jdbc.sql("""
                        select pc.kind, c.name, pc.effective_from_week, pc.detail::text, pc.reason, u.display_name, pc.created_at
                        from pool_change pc left join card c on c.id = pc.card_id left join app_user u on u.id = pc.created_by
                        where pc.season_id = :s order by pc.created_at desc, pc.id desc limit 200""")
                .param("s", season.getId())
                .query((rs, n) -> new ChangeView(rs.getString(1), rs.getString(2), rs.getInt(3),
                        json.readValue(rs.getString(4), Map.class), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()))
                .list();
    }

    // ---- Changing -----------------------------------------------------------------------------

    /** Builds (or rebuilds) the dated pool from the rules' TCG cutoff. Setup only. */
    @Transactional
    public PoolSummary generate(String leagueId, String seasonId, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        if (started(season)) throw conflict("The pool can't be regenerated once the season has started; add or remove cards instead");
        LocalDate cutoff = rules.latest(season).format().tcgCutoff();
        if (cutoff == null) throw bad("Set the TCG cutoff on the Rules tab first");
        long s = season.getId();

        // Dated cards that no longer qualify (the cutoff moved earlier) leave; anything removed by hand stays removed.
        int dropped = jdbc.sql("""
                        delete from pool_card p using card c
                        where p.season_id = :s and p.card_id = c.id and p.source = 'DATED' and p.removed_in_week is null
                          and (c.tcg_date > :cutoff or c.card_type = 'Token')""")
                .param("s", s).param("cutoff", cutoff).update();
        // Exceptions that now fall inside the cutoff become ordinary dated cards.
        jdbc.sql("""
                        update pool_card p set source = 'DATED' from card c
                        where p.season_id = :s and p.card_id = c.id and p.source = 'EXCEPTION'
                          and c.tcg_date <= :cutoff and c.card_type <> 'Token'""")
                .param("s", s).param("cutoff", cutoff).update();
        int added = jdbc.sql("""
                        insert into pool_card (season_id, league_id, card_id, source)
                        select :s, :l, c.id, 'DATED' from card c
                        where c.tcg_date <= :cutoff and c.card_type <> 'Token'
                          and not exists (select 1 from pool_card p where p.season_id = :s and p.card_id = c.id)""")
                .param("s", s).param("l", season.getLeague().getId()).param("cutoff", cutoff).update();
        long total = jdbc.sql("select count(*) from pool_card where season_id = :s and removed_in_week is null")
                .param("s", s).query(Long.class).single();
        log(season, "GENERATED", null, 0, Map.of("cutoff", cutoff.toString(), "added", added, "dropped", dropped, "total", total),
                null, caller);
        prefetchThumbnails(season, null);
        return summary(leagueId, seasonId, caller);
    }

    /** Adds a card by hand: an exception if it's past the cutoff, or a dated card coming back. */
    @Transactional
    public PoolCardView add(String leagueId, String seasonId, AddRequest req, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        if (req.cardId() == null) throw bad("Choose a card");
        var card = jdbc.sql("select tcg_date, card_type from card where id = :c").param("c", req.cardId())
                .query((rs, n) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getString(2)})
                .optional().orElseThrow(() -> bad("Unknown card"));
        int week = effectiveWeek(season, req.effectiveFromWeek());
        String reason = clean(req.reason());
        LocalDate cutoff = rules.latest(season).format().tcgCutoff();
        boolean dated = cutoff != null && !((LocalDate) card[0]).isAfter(cutoff) && !"Token".equals(card[1]);

        // Present = there's a row; its value = whether the card is currently in the pool (not removed).
        // (Selecting removed_in_week itself wouldn't do: a null value reads as "no row".)
        var existing = jdbc.sql("select removed_in_week is null from pool_card where season_id = :s and card_id = :c")
                .param("s", season.getId()).param("c", req.cardId())
                .query(Boolean.class).optional();
        if (existing.orElse(false)) throw conflict("That card is already in the pool");
        if (existing.isPresent()) {
            jdbc.sql("""
                            update pool_card set removed_in_week = null, added_in_week = :w, source = :src, reason = :r
                            where season_id = :s and card_id = :c""")
                    .param("w", week).param("src", dated ? "DATED" : "EXCEPTION").param("r", reason)
                    .param("s", season.getId()).param("c", req.cardId()).update();
        } else {
            jdbc.sql("""
                            insert into pool_card (season_id, league_id, card_id, source, reason, added_in_week)
                            values (:s, :l, :c, :src, :r, :w)""")
                    .param("s", season.getId()).param("l", season.getLeague().getId()).param("c", req.cardId())
                    .param("src", dated ? "DATED" : "EXCEPTION").param("r", reason).param("w", week).update();
        }
        log(season, "ADDED", req.cardId(), week, Map.of("source", dated ? "DATED" : "EXCEPTION"), reason, caller);
        prefetchThumbnails(season, req.cardId());
        return card(season, req.cardId());
    }

    @Transactional
    public void remove(String leagueId, String seasonId, long cardId, RemoveRequest req, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        Integer addedIn = jdbc.sql("select added_in_week from pool_card where season_id = :s and card_id = :c and removed_in_week is null")
                .param("s", season.getId()).param("c", cardId).query(Integer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That card isn't in the pool"));
        if (ownership.isOwned(season, cardId)) {
            throw conflict("Someone owns that card, so it can't leave the pool. Soft-ban it instead to take it out of play.");
        }
        int week = Math.max(effectiveWeek(season, req == null ? null : req.effectiveFromWeek()), addedIn);
        jdbc.sql("update pool_card set removed_in_week = :w where season_id = :s and card_id = :c")
                .param("w", week).param("s", season.getId()).param("c", cardId).update();
        log(season, "REMOVED", cardId, week, Map.of(), req == null ? null : clean(req.reason()), caller);
    }

    @Transactional
    public PoolCardView setFlags(String leagueId, String seasonId, long cardId, FlagsUpdate update, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        int week = effectiveWeek(season, update.effectiveFromWeek());
        int changed = applyFlags(season, "p.card_id = :target", Map.of("target", cardId), update);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That card isn't in the pool");
        log(season, "FLAGS", cardId, week, flagDetail(update, null, changed), clean(update.reason()), caller);
        return card(season, cardId);
    }

    /** Sets flags on every pool card of an archetype at once (e.g. soft-ban an archetype). */
    @Transactional
    public PoolSummary setArchetypeFlags(String leagueId, String seasonId, String archetype, FlagsUpdate update, CurrentUser caller) {
        Season season = access.commissioner(leagueId, seasonId, caller).season();
        int week = effectiveWeek(season, update.effectiveFromWeek());
        int changed = applyFlags(season, "p.card_id in (select id from card where archetype = :target)", Map.of("target", archetype), update);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No pool cards in that archetype");
        log(season, "FLAGS", null, week, flagDetail(update, archetype, changed), clean(update.reason()), caller);
        return summary(leagueId, seasonId, caller);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private int applyFlags(Season season, String which, Map<String, Object> whichParams, FlagsUpdate u) {
        boolean clear = Boolean.TRUE.equals(u.clearMaxPlayable());
        if (u.softBanned() == null && u.requiresOwnership() == null && u.maxPlayable() == null && !clear) throw bad("Nothing to change");
        if (u.maxPlayable() != null && clear) throw bad("Set a max or clear it, not both");
        if (u.maxPlayable() != null && (u.maxPlayable() < 0 || u.maxPlayable() > 3)) throw bad("Max playable is 0 to 3");
        StringBuilder set = new StringBuilder();
        if (u.softBanned() != null) set.append("soft_banned = :sb, ");
        if (u.requiresOwnership() != null) set.append("requires_ownership = :ro, ");
        if (u.maxPlayable() != null) set.append("max_playable = :mp, ");
        if (clear) set.append("max_playable = null, ");
        set.setLength(set.length() - 2);
        var stmt = jdbc.sql("update pool_card p set " + set + " where p.season_id = :s and p.removed_in_week is null and " + which)
                .param("s", season.getId());
        if (u.softBanned() != null) stmt = stmt.param("sb", u.softBanned());
        if (u.requiresOwnership() != null) stmt = stmt.param("ro", u.requiresOwnership());
        if (u.maxPlayable() != null) stmt = stmt.param("mp", u.maxPlayable());
        for (var e : whichParams.entrySet()) stmt = stmt.param(e.getKey(), e.getValue());
        return stmt.update();
    }

    private static Map<String, Object> flagDetail(FlagsUpdate u, String archetype, int cards) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        if (u.softBanned() != null) d.put("softBanned", u.softBanned());
        if (u.requiresOwnership() != null) d.put("requiresOwnership", u.requiresOwnership());
        if (u.maxPlayable() != null) d.put("maxPlayable", u.maxPlayable());
        if (Boolean.TRUE.equals(u.clearMaxPlayable())) d.put("maxPlayable", "ban list");
        if (archetype != null) d.put("archetype", archetype);
        d.put("cards", cards);
        return d;
    }

    private PoolCardView card(Season season, long cardId) {
        return jdbc.sql(CARD_SELECT + " where p.season_id = :s and p.card_id = :c")
                .param("s", season.getId()).param("c", cardId).param("rev", banListRevision(season), java.sql.Types.BIGINT)
                .query((rs, n) -> cardRow(rs))
                .single();
    }

    private void log(Season season, String kind, Long cardId, int week, Map<String, Object> detail, String reason, CurrentUser by) {
        jdbc.sql("""
                        insert into pool_change (season_id, kind, card_id, effective_from_week, detail, reason, created_by)
                        values (:s, :k, :c, :w, cast(:d as jsonb), :r, :u)""")
                .param("s", season.getId()).param("k", kind).param("c", cardId, java.sql.Types.BIGINT).param("w", week)
                .param("d", json.writeValueAsString(detail)).param("r", reason).param("u", by.userId()).update();
    }

    /** Thumbnails of the pool (or one card), fetched in the background once this transaction commits. */
    private void prefetchThumbnails(Season season, Long cardId) {
        var query = jdbc.sql("""
                        select c.passcodes[1] from pool_card p join card c on c.id = p.card_id
                        where p.season_id = :s and p.removed_in_week is null""" + (cardId == null ? "" : " and p.card_id = :c"))
                .param("s", season.getId());
        if (cardId != null) query = query.param("c", cardId);
        List<Long> passcodes = query.query(Long.class).list();
        Runnable start = () -> images.prefetch(passcodes, ImageVariant.SMALL);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { start.run(); }
            });
        } else {
            start.run();
        }
    }

    private static boolean started(Season season) {
        return season.getStatus() != SeasonStatus.SETUP;
    }

    private int minEffectiveWeek(Season season) {
        return started(season) ? rules.currentWeek(season) : 0;
    }

    /** Setup: week 0. Started: the requested week, which must not be in the past. */
    private int effectiveWeek(Season season, Integer requested) {
        if (!started(season)) return 0;
        int min = minEffectiveWeek(season);
        if (requested == null) throw bad("Choose the week this change takes effect from");
        if (requested < min) throw bad("Changes can't take effect before week " + min + " (the pool's past stays as it was)");
        return requested;
    }

    private static String like(String q) {
        String term = q == null ? "" : q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + term + "%";
    }

    private static String clean(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String r = reason.strip().replaceAll("\\s+", " ");
        if (r.length() > 200) throw bad("Reasons can be at most 200 characters");
        return r;
    }

    private static long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
