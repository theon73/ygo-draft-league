package com.ygoleague.competition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoundRobinTest {

    @Test
    void evenFieldsTakeNMinusOneRoundsPerCycle() {
        assertEquals(9, RoundRobin.rounds(10, 1));     // our league: 10 players, single round-robin
        assertEquals(18, RoundRobin.rounds(10, 2));
        assertEquals(9, RoundRobin.matchesPerPlayer(10, 1));
        assertFalse(RoundRobin.hasByes(10));
    }

    @Test
    void oddFieldsTakeNRoundsWithOneByeEachRound() {
        assertEquals(5, RoundRobin.rounds(5, 1));
        assertEquals(4, RoundRobin.matchesPerPlayer(5, 1));
        assertTrue(RoundRobin.hasByes(5));
        assertEquals(3, RoundRobin.rounds(3, 1));
    }

    @Test
    void fewerThanTwoPlayersMeansNoRounds() {
        assertEquals(0, RoundRobin.rounds(0, 1));
        assertEquals(0, RoundRobin.rounds(1, 3));
        assertEquals(1, RoundRobin.rounds(2, 1));
    }
}
