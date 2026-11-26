package com.ygoleague.draft;

import com.ygoleague.draft.SnakeOrder.Slot;
import java.util.List;

/**
 * Pure domain logic: where a draft stands, from its slots (see {@link SnakeOrder}) and how many
 * slots the pick log has filled. Picks fill slots strictly in order, so the next slot is simply
 * the one after the last filled one.
 */
public final class DraftProgress {

    /**
     * The slot on the clock.
     *
     * @param pickInTurn     which of the seat's consecutive picks this is (1-based)
     * @param picksInTurn    how many picks the seat makes in a row this turn
     */
    public record OnTheClock(Slot slot, int pickInTurn, int picksInTurn) {}

    private DraftProgress() {}

    public static boolean complete(List<Slot> slots, int filled) {
        return filled >= slots.size();
    }

    /** The slot to fill next, or null once every slot is filled. */
    public static OnTheClock onTheClock(List<Slot> slots, int filled) {
        if (filled < 0) throw new IllegalArgumentException("filled must be >= 0");
        if (complete(slots, filled)) return null;
        Slot slot = slots.get(filled);
        int first = filled;
        while (first > 0 && sameTurn(slots.get(first - 1), slot)) first--;
        int last = filled;
        while (last + 1 < slots.size() && sameTurn(slots.get(last + 1), slot)) last++;
        return new OnTheClock(slot, filled - first + 1, last - first + 1);
    }

    // A turn is a seat's consecutive picks within one round. In snake order the last seat of a round
    // also opens the next one, but that's two turns, so the round has to match too.
    private static boolean sameTurn(Slot a, Slot b) {
        return a.seat() == b.seat() && a.round() == b.round();
    }
}
