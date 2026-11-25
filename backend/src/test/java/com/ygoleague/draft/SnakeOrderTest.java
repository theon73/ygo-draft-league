package com.ygoleague.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ygoleague.draft.SnakeOrder.RoundBlock;
import com.ygoleague.draft.SnakeOrder.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnakeOrderTest {

    private static final List<RoundBlock> LEAGUE = List.of(
            new RoundBlock(1, 10, 1),
            new RoundBlock(11, 25, 2));

    @Test
    void leagueFormatHas400Picks() {
        assertEquals(400, SnakeOrder.build(10, LEAGUE).size());
    }

    @Test
    void firstTwoRoundsSnake() {
        List<Slot> slots = SnakeOrder.build(10, LEAGUE);
        assertEquals(new Slot(1, 1, 1, 1), slots.get(0));
        assertEquals(new Slot(10, 1, 10, 10), slots.get(9));
        assertEquals(new Slot(11, 2, 1, 10), slots.get(10)); // seat 10 picks back-to-back
        assertEquals(new Slot(20, 2, 10, 1), slots.get(19));
    }

    @Test
    void doublePickRoundsAreConsecutivePerSeat() {
        List<Slot> slots = SnakeOrder.build(10, LEAGUE);
        // Round 11 is odd → forward; picks 101-102 belong to seat 1.
        assertEquals(new Slot(101, 11, 1, 1), slots.get(100));
        assertEquals(new Slot(102, 11, 2, 1), slots.get(101));
        assertEquals(new Slot(103, 11, 3, 2), slots.get(102));
        // Round 12 is even → reverse; starts with seat 10.
        assertEquals(new Slot(121, 12, 1, 10), slots.get(120));
        assertEquals(new Slot(400, 25, 20, 10), slots.get(399));
    }

    @Test
    void rejectsGapsInRounds() {
        assertThrows(IllegalArgumentException.class,
                () -> SnakeOrder.build(10, List.of(new RoundBlock(2, 5, 1))));
    }
}
