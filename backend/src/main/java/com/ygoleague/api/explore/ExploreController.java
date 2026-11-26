package com.ygoleague.api.explore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary read-only window onto the global data (card catalog, sync log, official ban lists) for
 * the frontend's Explorer page, until the real pool builder and league pages exist.
 *
 * <p>Unauthenticated on purpose, so it must only ever expose global data: never league-owned
 * custom ban lists or anything else under a league. Plain SQL projections, because these are
 * display-only views that don't need entities.
 */
@RestController
@RequestMapping("/api/explore")
public class ExploreController {

    static final int PAGE_SIZE = 50;

    private final JdbcClient jdbc;

    public ExploreController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Summary(long cards, long sets, long prints, SyncInfo lastSync, List<BanListInfo> banLists) {}

    public record SyncInfo(String status, String trigger, Instant startedAt, Instant finishedAt,
                           String sourceVersion, Integer cardsAdded, Integer cardsUpdated, String error) {}

    public record BanListInfo(long id, String name, LocalDate effectiveDate, int revision,
                              long forbidden, long limited, long semiLimited) {}

    public record CardRow(long id, String name, String cardType, LocalDate tcgDate) {}

    public record CardPage(long total, int page, int pageSize, List<CardRow> items) {}

    public record CardDetail(long id, String name, String cardType, String race, String attribute,
                             Integer level, Integer rank, Integer linkRating, Integer pendulumScale,
                             Integer atk, Integer def, String archetype, String description,
                             LocalDate tcgDate, List<Long> passcodes, List<PrintRow> prints,
                             List<BanStatusRow> banStatuses) {}

    public record PrintRow(String setName, LocalDate setDate, String printCode, String rarity) {}

    public record BanStatusRow(long banListId, String banList, String status) {}

    public record BanListDetail(BanListInfo info, String note, List<BanEntryRow> entries) {}

    public record BanEntryRow(long cardId, String name, String cardType, String status) {}

    /** Latest revision of each official list, newest list first. */
    private static final String OFFICIAL_LISTS = """
            select l.id, l.name, l.effective_date, r.id as revision_id, r.revision, r.note,
                   count(*) filter (where e.status = 'FORBIDDEN')    as forbidden,
                   count(*) filter (where e.status = 'LIMITED')      as limited,
                   count(*) filter (where e.status = 'SEMI_LIMITED') as semi_limited
            from ban_list l
            join ban_list_revision r on r.ban_list_id = l.id
             and r.revision = (select max(revision) from ban_list_revision where ban_list_id = l.id)
            left join ban_list_entry e on e.revision_id = r.id
            where l.official_key is not null
            """;
    private static final String OFFICIAL_LISTS_GROUP =
            " group by l.id, l.name, l.effective_date, r.id, r.revision, r.note order by l.effective_date desc";

    @GetMapping("/summary")
    public Summary summary() {
        long cards = jdbc.sql("select count(*) from card").query(Long.class).single();
        long sets = jdbc.sql("select count(*) from card_set").query(Long.class).single();
        long prints = jdbc.sql("select count(*) from card_print").query(Long.class).single();
        SyncInfo lastSync = jdbc.sql("""
                        select status, trigger, started_at, finished_at, source_version,
                               cards_added, cards_updated, error
                        from catalog_sync order by started_at desc limit 1
                        """)
                .query((rs, i) -> new SyncInfo(rs.getString("status"), rs.getString("trigger"),
                        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                        rs.getString("source_version"), (Integer) rs.getObject("cards_added"),
                        (Integer) rs.getObject("cards_updated"), rs.getString("error")))
                .optional().orElse(null);
        List<BanListInfo> banLists = jdbc.sql(OFFICIAL_LISTS + OFFICIAL_LISTS_GROUP)
                .query((rs, i) -> banListInfo(rs)).list();
        return new Summary(cards, sets, prints, lastSync, banLists);
    }

    /** Cards whose name contains {@code q} (case-insensitive), alphabetical, {@value PAGE_SIZE} per page. */
    @GetMapping("/cards")
    public CardPage cards(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        String pattern = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long total = jdbc.sql("select count(*) from card where lower(name) like :p escape '\\'")
                .param("p", pattern).query(Long.class).single();
        List<CardRow> items = jdbc.sql("""
                        select id, name, card_type, tcg_date from card
                        where lower(name) like :p escape '\\'
                        order by lower(name), id limit :limit offset :offset
                        """)
                .param("p", pattern).param("limit", PAGE_SIZE).param("offset", (long) page * PAGE_SIZE)
                .query((rs, i) -> new CardRow(rs.getLong("id"), rs.getString("name"),
                        rs.getString("card_type"), rs.getObject("tcg_date", LocalDate.class)))
                .list();
        return new CardPage(total, page, PAGE_SIZE, items);
    }

    @GetMapping("/cards/{id}")
    public CardDetail card(@PathVariable long id) {
        List<PrintRow> prints = jdbc.sql("""
                        select s.name, s.tcg_date, p.print_code, p.rarity
                        from card_print p join card_set s on s.id = p.set_id
                        where p.card_id = :id
                        order by s.tcg_date nulls last, p.print_code, p.rarity
                        """)
                .param("id", id)
                .query((rs, i) -> new PrintRow(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getString(4)))
                .list();
        List<BanStatusRow> banStatuses = jdbc.sql("""
                        select l.id, l.name, e.status
                        from ban_list l
                        join ban_list_revision r on r.ban_list_id = l.id
                         and r.revision = (select max(revision) from ban_list_revision where ban_list_id = l.id)
                        join ban_list_entry e on e.revision_id = r.id and e.card_id = :id
                        where l.official_key is not null
                        order by l.effective_date desc
                        """)
                .param("id", id)
                .query((rs, i) -> new BanStatusRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .list();
        return jdbc.sql("""
                        select id, name, card_type, race, attribute, level, rank, link_rating,
                               pendulum_scale, atk, def, archetype, description, tcg_date, passcodes
                        from card where id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new CardDetail(rs.getLong("id"), rs.getString("name"),
                        rs.getString("card_type"), rs.getString("race"), rs.getString("attribute"),
                        (Integer) rs.getObject("level"), (Integer) rs.getObject("rank"),
                        (Integer) rs.getObject("link_rating"), (Integer) rs.getObject("pendulum_scale"),
                        (Integer) rs.getObject("atk"), (Integer) rs.getObject("def"),
                        rs.getString("archetype"), rs.getString("description"),
                        rs.getObject("tcg_date", LocalDate.class),
                        List.of((Long[]) rs.getArray("passcodes").getArray()), prints, banStatuses))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such card"));
    }

    /** Latest revision of an official list. Custom (league-owned) lists are deliberately not found. */
    @GetMapping("/banlists/{id}")
    public BanListDetail banList(@PathVariable long id) {
        record Head(BanListInfo info, long revisionId, String note) {}
        Head head = jdbc.sql(OFFICIAL_LISTS + " and l.id = :id" + OFFICIAL_LISTS_GROUP)
                .param("id", id)
                .query((rs, i) -> new Head(banListInfo(rs), rs.getLong("revision_id"), rs.getString("note")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such official ban list"));
        List<BanEntryRow> entries = jdbc.sql("""
                        select c.id, c.name, c.card_type, e.status
                        from ban_list_entry e join card c on c.id = e.card_id
                        where e.revision_id = :rev
                        order by case e.status when 'FORBIDDEN' then 0 when 'LIMITED' then 1 else 2 end, lower(c.name)
                        """)
                .param("rev", head.revisionId())
                .query((rs, i) -> new BanEntryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
        return new BanListDetail(head.info(), head.note(), entries);
    }

    private static BanListInfo banListInfo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BanListInfo(rs.getLong("id"), rs.getString("name"),
                rs.getObject("effective_date", LocalDate.class), rs.getInt("revision"),
                rs.getLong("forbidden"), rs.getLong("limited"), rs.getLong("semi_limited"));
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
