package com.ygoleague.league;

/**
 * A member's role within one league. A user can hold different roles in different leagues.
 * Roles are ordered: a commissioner can do everything a player can, and a player everything a
 * viewer can (see {@link #includes}).
 */
public enum LeagueRole {
    /** Manages the league: rules, pool, members, overrides. A league can have several. */
    COMMISSIONER(3),
    /** Takes part in seasons: drafts, trades, plays matches. */
    PLAYER(2),
    /** Read-only access, granted by a commissioner to someone who isn't playing. */
    VIEWER(1);

    private final int rank;

    LeagueRole(int rank) {
        this.rank = rank;
    }

    /** Whether holding this role grants at least {@code required}'s permissions. */
    public boolean includes(LeagueRole required) {
        return rank >= required.rank;
    }

    public boolean canWrite() {
        return includes(PLAYER);
    }
}
