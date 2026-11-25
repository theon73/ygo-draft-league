package com.ygoleague.catalog;

import java.sql.Array;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk writes for the catalog sync, in plain JDBC: ~15k cards and ~45k prints per full sync is
 * well past where per-entity JPA saves are pleasant. Callers own the transaction.
 */
@Repository
public class CatalogWriter {

    private static final String CARD_COLUMNS = """
            konami_id, passcodes, name, card_type, frame_type, race, attribute, level, rank, link_rating,
            link_markers, pendulum_scale, atk, def, archetype, description, tcg_date, ocg_date, raw, content_hash""";

    private static final String INSERT_CARD = "insert into card (" + CARD_COLUMNS + ")"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)";

    private static final String UPDATE_CARD = """
            update card set konami_id = ?, passcodes = ?, name = ?, card_type = ?, frame_type = ?, race = ?,
                attribute = ?, level = ?, rank = ?, link_rating = ?, link_markers = ?, pendulum_scale = ?,
                atk = ?, def = ?, archetype = ?, description = ?, tcg_date = ?, ocg_date = ?,
                raw = ?::jsonb, content_hash = ?, updated_at = now()
            where id = ?""";

    /** A matched card to overwrite. */
    public record CardUpdate(long id, CardRecord card) {}

    private final JdbcTemplate jdbc;

    public CatalogWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upserts sets by name. Returns set ids by name, including sets that already existed. */
    public Map<String, Long> upsertSets(List<SetRecord> sets) {
        jdbc.batchUpdate("""
                insert into card_set (name, code, tcg_date, card_count) values (?, ?, ?, ?)
                on conflict (name) do update set code = excluded.code, tcg_date = excluded.tcg_date,
                    card_count = excluded.card_count, updated_at = now()
                where (card_set.code, card_set.tcg_date, card_set.card_count)
                      is distinct from (excluded.code, excluded.tcg_date, excluded.card_count)
                """, sets, 500, (ps, s) -> {
            ps.setString(1, s.name());
            ps.setString(2, s.code());
            setDate(ps, 3, s.tcgDate());
            setInt(ps, 4, s.cardCount());
        });
        return setIdsByName();
    }

    /** Identity and content hash of every stored card, for {@link CardMatcher}. */
    public List<CardMatcher.StoredCard> storedCards() {
        return jdbc.query("select id, konami_id, passcodes, content_hash from card", (rs, i) -> {
            Long[] passcodes = (Long[]) rs.getArray(3).getArray();
            return new CardMatcher.StoredCard(rs.getLong(1), (Integer) rs.getObject(2),
                    List.of(passcodes), rs.getString(4));
        });
    }

    /**
     * Inserts new cards and overwrites matched ones, then replaces the prints of every card
     * written. Prints may name sets missing from the source's set list (a couple of promos);
     * those sets are created on the fly with the print code's prefix and no date, and added to
     * {@code setIds}.
     */
    public void writeCards(List<CardRecord> inserts, List<CardUpdate> updates, Map<String, Long> setIds) {
        Map<CardRecord, Long> written = new java.util.IdentityHashMap<>();

        jdbc.batchUpdate(UPDATE_CARD, updates, 500, (ps, u) -> {
            bindCard(ps, u.card());
            ps.setLong(21, u.id());
        });
        updates.forEach(u -> written.put(u.card(), u.id()));

        if (!inserts.isEmpty()) {
            jdbc.batchUpdate(INSERT_CARD, inserts, 500, CatalogWriter::bindCard);
            // Batches can't return generated keys portably; look the new rows up by first passcode.
            Long[] firstPasscodes = inserts.stream().map(c -> c.passcodes().getFirst()).toArray(Long[]::new);
            Map<Long, Long> idByPasscode = new HashMap<>();
            jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "select passcodes[1], id from card where passcodes[1] = any(?)");
                ps.setArray(1, con.createArrayOf("bigint", firstPasscodes));
                return ps;
            }, rs -> { idByPasscode.put(rs.getLong(1), rs.getLong(2)); });
            inserts.forEach(c -> written.put(c, idByPasscode.get(c.passcodes().getFirst())));
        }

        if (written.isEmpty()) return;
        Long[] ids = written.values().toArray(Long[]::new);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("delete from card_print where card_id = any(?)");
            ps.setArray(1, con.createArrayOf("bigint", ids));
            return ps;
        });

        record PrintRow(long cardId, long setId, CardRecord.PrintRecord print) {}
        List<PrintRow> rows = new ArrayList<>();
        written.forEach((card, cardId) -> {
            for (CardRecord.PrintRecord p : card.prints()) {
                long setId = setIds.computeIfAbsent(p.setName(), name -> createUnlistedSet(name, p.printCode()));
                rows.add(new PrintRow(cardId, setId, p));
            }
        });
        jdbc.batchUpdate("""
                insert into card_print (card_id, set_id, print_code, rarity, rarity_code) values (?, ?, ?, ?, ?)
                on conflict do nothing
                """, rows, 1000, (ps, r) -> {
            ps.setLong(1, r.cardId());
            ps.setLong(2, r.setId());
            ps.setString(3, r.print().printCode());
            ps.setString(4, r.print().rarity());
            ps.setString(5, r.print().rarityCode());
        });
    }

    private static void bindCard(PreparedStatement ps, CardRecord c) throws SQLException {
        setInt(ps, 1, c.konamiId());
        ps.setArray(2, array(ps, "bigint", c.passcodes().toArray()));
        ps.setString(3, c.name());
        ps.setString(4, c.cardType());
        ps.setString(5, c.frameType());
        ps.setString(6, c.race());
        ps.setString(7, c.attribute());
        setInt(ps, 8, c.level());
        setInt(ps, 9, c.rank());
        setInt(ps, 10, c.linkRating());
        ps.setArray(11, c.linkMarkers() == null ? null : array(ps, "text", c.linkMarkers().toArray()));
        setInt(ps, 12, c.pendulumScale());
        setInt(ps, 13, c.atk());
        setInt(ps, 14, c.def());
        ps.setString(15, c.archetype());
        ps.setString(16, c.description());
        setDate(ps, 17, c.tcgDate());
        setDate(ps, 18, c.ocgDate());
        ps.setString(19, c.rawJson());
        ps.setString(20, c.contentHash());
    }

    private long createUnlistedSet(String name, String printCode) {
        int dash = printCode.indexOf('-');
        String code = dash > 0 ? printCode.substring(0, dash) : printCode;
        return jdbc.queryForObject("""
                insert into card_set (name, code) values (?, ?)
                on conflict (name) do update set name = excluded.name
                returning id
                """, Long.class, name, code);
    }

    private Map<String, Long> setIdsByName() {
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("select name, id from card_set", rs -> { ids.put(rs.getString(1), rs.getLong(2)); });
        return ids;
    }

    private static Array array(PreparedStatement ps, String type, Object[] values) throws SQLException {
        return ps.getConnection().createArrayOf(type, values);
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER);
        else ps.setInt(i, v);
    }

    private static void setDate(PreparedStatement ps, int i, java.time.LocalDate d) throws SQLException {
        if (d == null) ps.setNull(i, Types.DATE);
        else ps.setDate(i, Date.valueOf(d));
    }
}
