package com.ygoleague.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.draft.DraftProgress.OnTheClock;
import com.ygoleague.draft.SnakeOrder.RoundBlock;
import com.ygoleague.draft.SnakeOrder.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

class DraftProgressTest {

    // 3 seats: 2 rounds of 1 pick, then 1 round of 2 picks = 12 slots.
    private static final List<Slot> SLOTS = SnakeOrder.build(3, List.of(new RoundBlock(1, 2, 1), new RoundBlock(3, 3, 2)));

    @Test
    void theFirstSlotIsUpBeforeAnyPick() {
        assertEquals(new OnTheClock(new Slot(1, 1, 1, 1), 1, 1), DraftProgress.onTheClock(SLOTS, 0));
    }

    @Test
    void theSnakeTurnIsTwoTurnsNotOne() {
        // Seat 3 ends round 1 and opens round 2: two separate single-pick turns.
        assertEquals(new OnTheClock(new Slot(3, 1, 3, 3), 1, 1), DraftProgress.onTheClock(SLOTS, 2));
        assertEquals(new OnTheClock(new Slot(4, 2, 1, 3), 1, 1), DraftProgress.onTheClock(SLOTS, 3));
    }

    @Test
    void multiPickTurnsCountTheirPicks() {
        assertEquals(new OnTheClock(new Slot(7, 3, 1, 1), 1, 2), DraftProgress.onTheClock(SLOTS, 6));
        assertEquals(new OnTheClock(new Slot(8, 3, 2, 1), 2, 2), DraftProgress.onTheClock(SLOTS, 7));
        assertEquals(new OnTheClock(new Slot(9, 3, 3, 2), 1, 2), DraftProgress.onTheClock(SLOTS, 8));
    }

    @Test
    void nobodyIsUpOnceEverySlotIsFilled() {
        assertNull(DraftProgress.onTheClock(SLOTS, 12));
        assertTrue(DraftProgress.complete(SLOTS, 12));
        assertThrows(IllegalArgumentException.class, () -> DraftProgress.onTheClock(SLOTS, -1));
    }
}
