package com.ygoleague.league;

/**
 * A member's standing in a league, which is a group of people. Everything about play (who
 * commissions a season, who plays in it) is per season: see {@code season.SeasonAccess}.
 */
public enum LeagueRole {
    /**
     * Created the league (exactly one per league). Manages the group: invites, placeholders,
     * removing members, league settings, creating seasons. Always a commissioner of every season.
     */
    OWNER(2),
    /** In the group. Can follow every season of the league, and play in the ones they're added to. */
    MEMBER(1);

    private final int rank;

    LeagueRole(int rank) {
        this.rank = rank;
    }

    /** Whether holding this role grants at least {@code required}'s permissions. */
    public boolean includes(LeagueRole required) {
        return rank >= required.rank;
    }
}
