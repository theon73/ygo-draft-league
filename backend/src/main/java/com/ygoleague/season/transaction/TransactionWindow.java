package com.ygoleague.season.transaction;

import com.ygoleague.season.Season;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Small rules shared by free agency and trades. */
final class TransactionWindow {

    private TransactionWindow() {}

    /** Why transactions of this kind can't happen now, or null if they can (the season is active). */
    static String closedReason(Season season, String what) {
        return switch (season.getStatus()) {
            case SETUP, DRAFTING -> what + " opens when the draft is complete";
            case COMPLETE -> "The season is over";
            case ACTIVE -> null;
        };
    }

    /** Serializes a season's transactions: two can't take the same card or overrun a limit together. */
    static void lock(JdbcClient jdbc, Season season) {
        jdbc.sql("select id from season where id = :s for update").param("s", season.getId()).query(Long.class).single();
    }

    static boolean holds(JdbcClient jdbc, Season season, long playerId, long cardId) {
        return jdbc.sql("select exists (select 1 from card_holding where season_id = :s and card_id = :c and season_player_id = :p)")
                .param("s", season.getId()).param("c", cardId).param("p", playerId).query(Boolean.class).single();
    }

    static String weekSuffix(int week) {
        return week == 0 ? " · before week 1" : " · week " + week;
    }
}
