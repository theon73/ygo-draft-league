package com.ygoleague.season.transaction;

import com.ygoleague.common.PublicIdType;
import com.ygoleague.common.PublicIds;
import com.ygoleague.season.Season;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Executed transactions as the league sees them: the Transactions tab's feed. */
@Component
public class TransactionFeed {

    public record CardRef(long cardId, String name, long passcode) {}

    /** Allowances moved by a trade: {@code quantity} of {@code kind} (FREE_AGENCY or TRADE) for week {@code week}. */
    public record AllowanceRef(String kind, int quantity, int week, String fromMemberId, String from, String toMemberId, String to) {}

    /**
     * One executed transaction. {@code added} / {@code dropped} are from {@code player}'s side: for a
     * claim, the free agent and the drop; for a trade (player = who made the offer), what they
     * received and what they gave; the counterparty got the reverse.
     *
     * @param onBehalfBy the commissioner who made it for the player, or who approved the trade
     */
    public record TransactionView(String kind, int week, Instant at, String memberId, String player, String counterpartyMemberId,
                                  String counterparty, List<CardRef> added, List<CardRef> dropped, List<AllowanceRef> allowances,
                                  String onBehalfBy) {}

    private final PublicIds publicIds;
    private final JdbcClient jdbc;

    public TransactionFeed(PublicIds publicIds, JdbcClient jdbc) {
        this.publicIds = publicIds;
        this.jdbc = jdbc;
    }

    /** Newest first, the latest 200; or just transaction {@code only}. */
    public List<TransactionView> list(Season season, Long only) {
        record Tx(long id, String kind, int week, Instant at, long playerId, long memberId, String player, Long cpMemberId,
                  String counterparty, String by) {}
        List<Tx> txs = jdbc.sql("""
                        select t.id, t.kind, t.week, t.executed_at, t.season_player_id, m.id, m.display_name, cm.id, cm.display_name,
                               case when m.user_id is distinct from t.created_by and cm.user_id is distinct from t.created_by
                                    then u.display_name end
                        from season_transaction t
                        join season_player sp on sp.id = t.season_player_id join league_member m on m.id = sp.league_member_id
                        left join season_player cp on cp.id = t.counterparty_id left join league_member cm on cm.id = cp.league_member_id
                        join app_user u on u.id = t.created_by
                        where t.season_id = :s and (cast(:only as bigint) is null or t.id = :only)
                        order by t.executed_at desc, t.id desc limit 200""")
                .param("s", season.getId()).param("only", only, java.sql.Types.BIGINT)
                .query((rs, n) -> new Tx(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant(), rs.getLong(5),
                        rs.getLong(6), rs.getString(7), (Long) rs.getObject(8), rs.getString(9), rs.getString(10)))
                .list();
        if (txs.isEmpty()) return List.of();
        List<Long> ids = txs.stream().map(Tx::id).toList();

        Map<Long, List<CardRef>> added = new LinkedHashMap<>();
        Map<Long, List<CardRef>> dropped = new LinkedHashMap<>();
        Map<Long, Long> playerOf = new LinkedHashMap<>();
        for (Tx t : txs) playerOf.put(t.id(), t.playerId());
        jdbc.sql("""
                        select l.transaction_id, l.to_player_id, c.id, c.name, c.passcodes[1]
                        from season_transaction_line l join card c on c.id = l.card_id
                        where l.transaction_id in (:ids) order by l.id""")
                .param("ids", ids)
                .query(rs -> {
                    long tx = rs.getLong(1);
                    Long to = (Long) rs.getObject(2);
                    CardRef card = new CardRef(rs.getLong(3), rs.getString(4), rs.getLong(5));
                    (to != null && to.equals(playerOf.get(tx)) ? added : dropped).computeIfAbsent(tx, k -> new ArrayList<>()).add(card);
                });

        Map<Long, List<AllowanceRef>> allowances = new LinkedHashMap<>();
        jdbc.sql("""
                        select a.transaction_id, a.kind, a.quantity, a.week, fm.id, fm.display_name, tm.id, tm.display_name
                        from season_transaction_allowance a
                        join season_player fp on fp.id = a.from_player_id join league_member fm on fm.id = fp.league_member_id
                        join season_player tp on tp.id = a.to_player_id join league_member tm on tm.id = tp.league_member_id
                        where a.transaction_id in (:ids) order by a.id""")
                .param("ids", ids)
                .query(rs -> {
                    allowances.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new AllowanceRef(rs.getString(2), rs.getInt(3), rs.getInt(4), memberId(rs.getLong(5)), rs.getString(6),
                                    memberId(rs.getLong(7)), rs.getString(8)));
                });

        return txs.stream().map(t -> new TransactionView(t.kind(), t.week(), t.at(), memberId(t.memberId()), t.player(),
                t.cpMemberId() == null ? null : memberId(t.cpMemberId()), t.counterparty(),
                added.getOrDefault(t.id(), List.of()), dropped.getOrDefault(t.id(), List.of()),
                allowances.getOrDefault(t.id(), List.of()), t.by())).toList();
    }

    private String memberId(long id) {
        return publicIds.encode(PublicIdType.LEAGUE_MEMBER, id);
    }
}
