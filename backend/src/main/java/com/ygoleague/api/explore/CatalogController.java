package com.ygoleague.api.explore;

import com.ygoleague.season.pool.CardFilter;
import com.ygoleague.season.pool.PoolService.Facets;
import com.ygoleague.season.pool.PoolService.PoolQuery;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Cards page (YGO-38): the whole catalog, or one format's card pool (a TCG cutoff and an
 * official ban list), with the same filters and sort as a season's pool ({@link CardFilter}).
 * Public like the rest of {@code /api/explore}: global data only, never a league's.
 *
 * <p>The format is a preview: "cards released on or before the cutoff", with the list's statuses.
 * A real season's pool can differ (exceptions, soft bans, removals), which is shown on its Pool tab.
 */
@RestController
@RequestMapping("/api/explore/catalog")
public class CatalogController {

    static final int PAGE_SIZE = 50;

    /** One catalog card, with its status on the chosen list (null: unlimited, or no list chosen). */
    public record CatalogCard(long cardId, String name, String cardType, String archetype, LocalDate tcgDate, long passcode,
                              String banStatus, String race, String attribute, Integer level, Integer rank, Integer linkRating,
                              Integer atk, Integer def) {}

    public record CatalogPage(long total, int page, int pageSize, List<CatalogCard> items) {}

    /**
     * A format at a glance.
     *
     * @param forbidden etc.: listed cards that are in the format (a list can name cards released later)
     */
    public record FormatSummary(LocalDate cutoff, Long banListId, String banList, long cards, long monsters, long extra,
                                long spells, long traps, long forbidden, long limited, long semiLimited) {}

    private static final String EXTRA = "c.frame_type in ('fusion', 'synchro', 'xyz', 'link', 'fusion_pendulum', 'synchro_pendulum', 'xyz_pendulum')";

    private final JdbcClient jdbc;

    public CatalogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Catalog cards matching the filters. Every parameter is optional: {@code cutoff} limits to cards
     * released by then, {@code banList} (an official list's id) adds its statuses (and the ban status
     * filter), and the rest are {@link PoolQuery}'s (its pool-only {@code show} and {@code available}
     * are ignored).
     */
    @GetMapping
    public CatalogPage cards(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff,
                             @RequestParam(required = false) Long banList, PoolQuery query) {
        int page = query.page() == null ? 0 : query.page();
        int size = query.pageSize() == null ? PAGE_SIZE : query.pageSize();
        if (page < 0) throw bad("page must be >= 0");
        if (size < 10 || size > 100) throw bad("pageSize must be 10 to 100");

        CardFilter.Conditions where = format(cutoff);
        CardFilter.apply(query, where);
        Long rev = revision(banList);
        String from = " from card c left join ban_list_entry b on b.revision_id = :rev and b.card_id = c.id where " + where.where();

        var count = jdbc.sql("select count(*)" + from).param("rev", rev, java.sql.Types.BIGINT);
        var list = jdbc.sql("""
                        select c.id, c.name, c.card_type, c.archetype, c.tcg_date, c.passcodes[1] as passcode, b.status,
                               c.race, c.attribute, c.level, c.rank, c.link_rating, c.atk, c.def""" + from
                        + " order by " + CardFilter.order(query.sort()) + " limit :limit offset :offset")
                .param("rev", rev, java.sql.Types.BIGINT);
        for (var e : where.params().entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            list = list.param(e.getKey(), e.getValue());
        }
        long total = count.query(Long.class).single();
        List<CatalogCard> items = list.param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new CatalogCard(rs.getLong("id"), rs.getString("name"), rs.getString("card_type"),
                        rs.getString("archetype"), rs.getObject("tcg_date", LocalDate.class), rs.getLong("passcode"),
                        rs.getString("status"), rs.getString("race"), rs.getString("attribute"), (Integer) rs.getObject("level"),
                        (Integer) rs.getObject("rank"), (Integer) rs.getObject("link_rating"), (Integer) rs.getObject("atk"),
                        (Integer) rs.getObject("def")))
                .list();
        return new CatalogPage(total, page, size, items);
    }

    /** The values present in the format, for the filters' dropdowns. */
    @GetMapping("/facets")
    public Facets facets(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff) {
        String within = cutoff == null ? "true" : "c.tcg_date <= :cutoff";
        java.util.function.Function<String, List<String>> distinct = column -> {
            var q = jdbc.sql("select distinct " + column + " from card c where " + within + " and " + column + " is not null order by 1");
            return (cutoff == null ? q : q.param("cutoff", cutoff)).query(String.class).list();
        };
        var raceQuery = jdbc.sql("select distinct c.frame_type, c.race from card c where " + within + " order by 2");
        List<String[]> races = (cutoff == null ? raceQuery : raceQuery.param("cutoff", cutoff))
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
        List<String> monster = races.stream().filter(r -> !r[0].equals("spell") && !r[0].equals("trap") && !r[0].equals("token"))
                .map(r -> r[1]).distinct().toList();
        List<String> spell = races.stream().filter(r -> r[0].equals("spell")).map(r -> r[1]).distinct().toList();
        List<String> trap = races.stream().filter(r -> r[0].equals("trap")).map(r -> r[1]).distinct().toList();
        return new Facets(distinct.apply("c.attribute"), monster, spell, trap, distinct.apply("c.archetype"));
    }

    /** How big a format is, by kind, and how much of its ban list applies to it. */
    @GetMapping("/summary")
    public FormatSummary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff,
                                 @RequestParam(required = false) Long banList) {
        Long rev = revision(banList);
        String listName = banList == null ? null
                : jdbc.sql("select name from ban_list where id = :id").param("id", banList).query(String.class).single();
        CardFilter.Conditions where = format(cutoff);
        String sql = "select count(*),"
                + " count(*) filter (where c.frame_type not in ('spell', 'trap', 'token') and not (" + EXTRA + ")),"
                + " count(*) filter (where " + EXTRA + "),"
                + " count(*) filter (where c.frame_type = 'spell'),"
                + " count(*) filter (where c.frame_type = 'trap'),"
                + " count(*) filter (where b.status = 'FORBIDDEN'),"
                + " count(*) filter (where b.status = 'LIMITED'),"
                + " count(*) filter (where b.status = 'SEMI_LIMITED')"
                + " from card c left join ban_list_entry b on b.revision_id = :rev and b.card_id = c.id"
                + " where " + where.where();   // tokens count as cards (as in the search), not as monsters
        var q = jdbc.sql(sql)
                .param("rev", rev, java.sql.Types.BIGINT);
        for (var e : where.params().entrySet()) q = q.param(e.getKey(), e.getValue());
        return q.query((rs, n) -> new FormatSummary(cutoff, banList, listName, rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8))).single();
    }

    private static CardFilter.Conditions format(LocalDate cutoff) {
        CardFilter.Conditions where = new CardFilter.Conditions();
        if (cutoff != null) where.add("c.tcg_date <= :cutoff", "cutoff", cutoff);
        return where;
    }

    /** The latest revision of an official list; custom (league) lists are deliberately not found. */
    private Long revision(Long banListId) {
        if (banListId == null) return null;
        return jdbc.sql("""
                        select r.id from ban_list l join ban_list_revision r on r.ban_list_id = l.id
                        where l.id = :id and l.official_key is not null order by r.revision desc limit 1""")
                .param("id", banListId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such official ban list"));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
