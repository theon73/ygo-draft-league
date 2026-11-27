package com.ygoleague.competition;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a round-robin: who meets whom in each round. Spring-free.
 *
 * <ul>
 *   <li>The <b>circle method</b>: the first player stays put while the others rotate one place a
 *       round. Each cycle pairs every player with every other exactly once, so within a cycle
 *       nobody meets the same opponent twice, let alone in consecutive rounds.
 *   <li><b>Byes:</b> with an odd count a placeholder joins the circle, and whoever faces it sits
 *       the round out. Everyone gets exactly one bye per cycle.
 *   <li><b>Several cycles</b> (a double round-robin, …) reuse the first cycle's rounds, each later
 *       cycle starting one round further on (cycle c plays rounds c, c+1, …, wrapping). The round
 *       after a cycle boundary is then never the one before it, so no pairing (and no bye) repeats
 *       in consecutive rounds. The one exception is unavoidable: with two players every round is
 *       the same pairing.
 *   <li>The players' order (a random draw, recorded by the caller) decides who meets whom when;
 *       the same order always gives the same schedule.
 * </ul>
 */
public final class RoundRobinSchedule {

    private RoundRobinSchedule() {}

    /** One meeting. {@code b} is null for a bye: {@code a} sits the round out. */
    public record Pairing<T>(T a, T b) {
        public boolean bye() { return b == null; }
    }

    /** Rounds in order (round 1 first), each the round's pairings (byes last). */
    public static <T> List<List<Pairing<T>>> rounds(List<T> players, int cycles) {
        if (players.size() < 2) throw new IllegalArgumentException("A round-robin needs at least two players");
        if (cycles < 1) throw new IllegalArgumentException("At least one cycle");
        List<List<Pairing<T>>> cycle = oneCycle(players);
        int m = cycle.size();
        List<List<Pairing<T>>> out = new ArrayList<>(m * cycles);
        for (int c = 0; c < cycles; c++) {
            for (int r = 0; r < m; r++) out.add(cycle.get((c + r) % m));
        }
        return out;
    }

    private static <T> List<List<Pairing<T>>> oneCycle(List<T> players) {
        List<T> circle = new ArrayList<>(players);
        if (circle.size() % 2 == 1) circle.add(null);   // the bye
        int n = circle.size();
        List<List<Pairing<T>>> rounds = new ArrayList<>(n - 1);
        for (int r = 0; r < n - 1; r++) {
            List<Pairing<T>> matches = new ArrayList<>();
            Pairing<T> bye = null;
            for (int i = 0; i < n / 2; i++) {
                T x = circle.get(i);
                T y = circle.get(n - 1 - i);
                if (x == null || y == null) {
                    bye = new Pairing<>(x == null ? y : x, null);
                } else {
                    // Alternate which side is listed first, so nobody is always "a".
                    matches.add(r % 2 == 0 ? new Pairing<>(x, y) : new Pairing<>(y, x));
                }
            }
            if (bye != null) matches.add(bye);
            rounds.add(List.copyOf(matches));
            // Rotate everyone but the first: the last moves to second place.
            circle.add(1, circle.remove(n - 1));
        }
        return rounds;
    }
}
