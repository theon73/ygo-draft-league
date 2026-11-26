package com.ygoleague.season;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeatOrderTest {

    @Test
    void stayersKeepTheirOrderAndNewcomersGoLast() {
        // 2 leaves; 3 and 1 keep their relative order; 7 and 8 join at the end in the order given.
        assertEquals(List.of(3L, 1L, 7L, 8L), SeatOrder.afterParticipantChange(List.of(3L, 2L, 1L), List.of(1L, 3L, 7L, 8L)));
        assertEquals(List.of(5L, 6L), SeatOrder.afterParticipantChange(List.of(), List.of(5L, 6L)));
        assertEquals(List.of(), SeatOrder.afterParticipantChange(List.of(1L, 2L), List.of()));
    }

    @Test
    void manualOrderMustBeAPermutation() {
        Set<Long> participants = Set.of(1L, 2L, 3L);
        assertDoesNotThrow(() -> SeatOrder.requirePermutation(List.of(3L, 1L, 2L), participants));
        assertThrows(IllegalArgumentException.class, () -> SeatOrder.requirePermutation(List.of(1L, 2L), participants));
        assertThrows(IllegalArgumentException.class, () -> SeatOrder.requirePermutation(List.of(1L, 2L, 2L), participants));
        assertThrows(IllegalArgumentException.class, () -> SeatOrder.requirePermutation(List.of(1L, 2L, 4L), participants));
    }

    @Test
    void shuffleIsAPermutationAndRoughlyUniform() {
        List<Long> people = List.of(1L, 2L, 3L);
        Random random = new Random(42);
        Map<List<Long>, Integer> counts = new HashMap<>();
        for (int i = 0; i < 6000; i++) {
            List<Long> order = SeatOrder.shuffled(people, random);
            assertDoesNotThrow(() -> SeatOrder.requirePermutation(order, Set.copyOf(people)));
            counts.merge(order, 1, Integer::sum);
        }
        assertEquals(6, counts.size());                                   // all 3! orders occur
        counts.values().forEach(n -> assertEquals(1000, n, 150));         // each about 1/6 of the time
    }
}
