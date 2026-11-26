package com.ygoleague.season.pool;

import com.ygoleague.season.Season;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Whether a pool card is owned by anyone in the season. A card can only leave the pool while
 * nobody owns it; to take an owned card out of play, soft-ban it instead.
 *
 * <p>Reads the {@code card_holding} view, the ownership projection (the draft for now; trades and
 * free agency join it in Phase 4).
 */
@Component
public class PoolOwnership {

    /** SQL for the copies of pool card {@code p} that someone holds; use it inside a query aliasing pool_card as p. */
    public static final String HELD_COPIES =
            "(select coalesce(sum(h.quantity), 0) from card_holding h where h.season_id = p.season_id and h.card_id = p.card_id)";

    private final JdbcClient jdbc;

    public PoolOwnership(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isOwned(Season season, long cardId) {
        return jdbc.sql("select exists (select 1 from card_holding where season_id = :s and card_id = :c)")
                .param("s", season.getId()).param("c", cardId).query(Boolean.class).single();
    }
}
