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
}
