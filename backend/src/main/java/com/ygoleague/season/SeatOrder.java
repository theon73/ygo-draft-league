package com.ygoleague.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Seat-order rules, free of Spring so they're easy to test. Seats are always 1..n with no gaps.
 * Ids are league member ids.
 */
public final class SeatOrder {

    private SeatOrder() {}

    /**
     * The new order after the participant set changes: people who stay keep their relative order,
     * newcomers go to the end in the order given.
     */
    public static List<Long> afterParticipantChange(List<Long> currentOrder, List<Long> newParticipants) {
        Set<Long> wanted = new LinkedHashSet<>(newParticipants);
        List<Long> order = new ArrayList<>();
        for (Long id : currentOrder) if (wanted.remove(id)) order.add(id);
        order.addAll(wanted);
        return order;
    }

    /** A requested manual order must contain every participant exactly once. */
    public static void requirePermutation(List<Long> requested, Set<Long> participants) {
        if (requested.size() != participants.size() || !new HashSet<>(requested).equals(participants)) {
            throw new IllegalArgumentException("The seat order must list every participant exactly once");
        }
    }

    /** A uniformly random order (Fisher–Yates via {@link Collections#shuffle}). */
    public static List<Long> shuffled(List<Long> participants, Random random) {
        List<Long> order = new ArrayList<>(participants);
        Collections.shuffle(order, random);
        return order;
    }

    /**
     * Last place picks first (YGO-39): the participants in the reverse of an earlier season's final
     * placement. Newcomers who didn't play it go first, in their current order, as if they finished
     * below everyone; people who played it but don't play now are skipped.
     *
     * @param current   the participants in their current seat order
     * @param placement the earlier season's final placement, first place first
     */
    public static List<Long> reverseOfPlacement(List<Long> current, List<Long> placement) {
        Set<Long> playing = new LinkedHashSet<>(current);
        List<Long> order = new ArrayList<>();
        Set<Long> placed = new HashSet<>(placement);
        for (Long id : current) if (!placed.contains(id)) order.add(id);
        List<Long> reversed = new ArrayList<>(placement);
        Collections.reverse(reversed);
        for (Long id : reversed) if (playing.contains(id)) order.add(id);
        return order;
    }
}
