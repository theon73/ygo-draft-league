package com.ygoleague.league;

/** A member's role within one league. A user can hold different roles in different leagues. */
public enum LeagueRole {
    /** Manages the league: rules, pool, members, overrides. A league can have several. */
    COMMISSIONER,
    /** Takes part in seasons: drafts, trades, plays matches. */
    PLAYER,
    /** Read-only access, granted by a commissioner to someone who isn't playing. */
    VIEWER;

    public boolean canWrite() {
        return this != VIEWER;
    }
}
