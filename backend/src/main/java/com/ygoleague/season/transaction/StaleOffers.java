package com.ygoleague.season.transaction;

import com.ygoleague.season.Season;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Cancels live trade offers (open, or waiting for approval) that can no longer happen because a
 * card in them moved: its giver doesn't hold it any more (traded away, or dropped in a claim).
 * Called inside every transaction that moves cards, after the move.
 */
@Component
public class StaleOffers {

    private final JdbcClient jdbc;
    private final MessageEvents events;

    public StaleOffers(JdbcClient jdbc, MessageEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** @return how many offers were cancelled */
    public int cancel(Season season) {
        int cancelled = jdbc.sql("""
                        update trade_offer o set status = 'CANCELLED', decided_at = now(),
                               cancel_reason = (select c.name || ' changed hands' from trade_offer_asset a join card c on c.id = a.card_id
                                                where a.offer_id = o.id and a.kind = 'CARD'
                                                  and not exists (select 1 from card_holding h where h.season_id = o.season_id
                                                                  and h.card_id = a.card_id and h.season_player_id = a.giver_id)
                                                order by c.name limit 1)
                        where o.season_id = :s and o.status in ('OPEN', 'PENDING_APPROVAL')
                          and exists (select 1 from trade_offer_asset a where a.offer_id = o.id and a.kind = 'CARD'
                                        and not exists (select 1 from card_holding h where h.season_id = o.season_id
                                                        and h.card_id = a.card_id and h.season_player_id = a.giver_id))""")
                .param("s", season.getId()).update();
        if (cancelled > 0) events.seasonChanged(season.getId()); // the conversations they were in (YGO-35)
        return cancelled;
    }
}
