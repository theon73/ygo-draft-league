package com.ygoleague.competition;

/**
 * Round-robin arithmetic. Spring-free.
 *
 * <p>Each <b>cycle</b> pairs every player with every other player once. With an even number of
 * players a cycle takes n - 1 rounds; with an odd number it takes n rounds, and each round one
 * player has a bye. One round is played per week (rounds per week may become a setting later).
 */
public final class RoundRobin {

    private RoundRobin() {}

    public static int roundsPerCycle(int players) {
        if (players < 2) return 0;
        return players % 2 == 0 ? players - 1 : players;
    }

    public static int rounds(int players, int cycles) {
        return roundsPerCycle(players) * cycles;
    }

    /** Every player plays every other player once per cycle. */
    public static int matchesPerPlayer(int players, int cycles) {
        return players < 2 ? 0 : (players - 1) * cycles;
    }

    public static boolean hasByes(int players) {
        return players >= 2 && players % 2 == 1;
    }
}
