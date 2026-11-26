package com.ygoleague.draft;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure domain logic: expands a snake-draft config into the full ordered pick list.
 *
 * <p>Snake order alternates round direction (odd rounds 1→N, even rounds N→1); linear order runs
 * every round 1→N. In rounds with picksPerTurn > 1, a seat makes its picks consecutively before
 * the next seat goes.
 *
 * <p>Our league: 10 seats, rounds 1–10 × 1 pick, rounds 11–25 × 2 picks = 400 picks.
 */
public final class SnakeOrder {

    /** Inclusive round range sharing the same picks-per-turn. */
    public record RoundBlock(int fromRound, int toRound, int picksPerTurn) {
        public RoundBlock {
            if (fromRound < 1 || toRound < fromRound || picksPerTurn < 1) {
                throw new IllegalArgumentException("Invalid round block");
            }
        }
    }

    /** One slot in the draft. Seats are 1-based. */
    public record Slot(int overallPick, int round, int pickInRound, int seat) {}

    private SnakeOrder() {}

    public static List<Slot> build(int seats, List<RoundBlock> blocks) {
        return build(seats, blocks, true);
    }

    /** @param snake true for snake order, false for linear (every round in seat order) */
    public static List<Slot> build(int seats, List<RoundBlock> blocks, boolean snake) {
        if (seats < 2) throw new IllegalArgumentException("Need at least 2 seats");
        if (blocks.isEmpty()) throw new IllegalArgumentException("Need at least one round");
        List<Slot> slots = new ArrayList<>();
        int overall = 0;
        int expectedRound = 1;
        for (RoundBlock block : blocks) {
            if (block.fromRound() != expectedRound) {
                throw new IllegalArgumentException("Round blocks must be contiguous starting at 1");
            }
            for (int round = block.fromRound(); round <= block.toRound(); round++) {
                boolean forward = !snake || round % 2 == 1;
                int pickInRound = 0;
                for (int i = 0; i < seats; i++) {
                    int seat = forward ? i + 1 : seats - i;
                    for (int p = 0; p < block.picksPerTurn(); p++) {
                        slots.add(new Slot(++overall, round, ++pickInRound, seat));
                    }
                }
            }
            expectedRound = block.toRound() + 1;
        }
        return List.copyOf(slots);
    }
}
