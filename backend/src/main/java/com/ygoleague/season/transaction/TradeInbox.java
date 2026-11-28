package com.ygoleague.season.transaction;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unread trade conversations (YGO-34). A player's unread items in a conversation are the other
 * player's messages and the offers sent to them since they last read it (trade_thread_read; no row
 * = nothing read yet). Reading happens when the player opens the conversation as themselves.
 *
 * <p>Counted for a signed-in person across every season they play, so the same numbers can badge a
 * league card, a season, the Trades tab and each conversation.
 */
@Component
public class TradeInbox {

    /** Unread items in one conversation of one of the person's seasons. */
    public record Unread(long leagueId, long seasonId, long playerId, long threadId, long otherPlayerId, int count) {}

    private final JdbcClient jdbc;

    TradeInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every conversation with something unread for this user, in any league and season they play. */
    @Transactional(readOnly = true)
    public List<Unread> forUser(long userId) {
        return jdbc.sql("""
                        with mine as (
                            select sp.id as player_id, sp.season_id, s.league_id
                            from season_player sp join league_member m on m.id = sp.league_member_id join season s on s.id = sp.season_id
                            where m.user_id = :u)
                        select me.league_id, me.season_id, me.player_id, t.id,
                               case when t.player_low_id = me.player_id then t.player_high_id else t.player_low_id end,
                               count(*)
                        from mine me
                        join trade_thread t on me.player_id in (t.player_low_id, t.player_high_id)
                        join lateral (
                            select m.created_at from trade_message m where m.thread_id = t.id and m.author_player_id <> me.player_id
                            union all
                            select o.created_at from trade_offer o where o.thread_id = t.id and o.to_player_id = me.player_id) x on true
                        left join trade_thread_read r on r.thread_id = t.id and r.season_player_id = me.player_id
                        where r.last_read_at is null or x.created_at > r.last_read_at
                        group by me.league_id, me.season_id, me.player_id, t.id, t.player_low_id, t.player_high_id""")
                .param("u", userId)
                .query((rs, n) -> new Unread(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getInt(6)))
                .list();
    }

    /** The total unread for this user in one season, or across one league. */
    public int inSeason(long userId, long seasonId) {
        return forUser(userId).stream().filter(u -> u.seasonId() == seasonId).mapToInt(Unread::count).sum();
    }

    @Transactional
    public void markRead(long threadId, long playerId) {
        jdbc.sql("""
                        insert into trade_thread_read (thread_id, season_player_id, last_read_at) values (:t, :p, now())
                        on conflict (thread_id, season_player_id) do update set last_read_at = excluded.last_read_at""")
                .param("t", threadId).param("p", playerId).update();
    }
}
