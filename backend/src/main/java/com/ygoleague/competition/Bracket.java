package com.ygoleague.competition;

import java.util.ArrayList;
import java.util.List;

/**
 * The playoff bracket's shape, from how many teams qualify directly and how many play in.
 * Spring-free: it lays out matches and where their players come from; seeding real players and
 * filling slots as results come in is the caller's.
 *
 * <ul>
 *   <li><b>Seeds</b> 1..D qualify directly (D = direct qualifiers); the next P play in.
 *   <li><b>Play-in, 2 teams:</b> one match, D+1 v D+2; the winner is bracket seed D+1.
 *   <li><b>Play-in, 4 teams (NBA style):</b> D+1 v D+2, the winner is seed D+1; D+3 v D+4, the loser
 *       is out; the loser of the first plays the winner of the second, and that winner is seed D+2.
 *   <li><b>Bracket:</b> single elimination over B = D + P/2 seeds, filled up to the next power of two
 *       with byes, which go to the top seeds. Standard seeding (1 v lowest, 2 v second lowest, …) in
 *       a fixed bracket: no reseeding.
 * </ul>
 *
 * Example (D = 4, 4-team play-in): seeds 5-8 play in for seeds 5 and 6; a 6-team bracket in a field of
 * 8, so seeds 1 and 2 have byes; quarterfinals 4 v 5 and 3 v 6; semifinals 1 v (4/5), 2 v (3/6); final.
 */
public final class Bracket {

    private Bracket() {}

    public enum Kind { PLAY_IN, PLAYOFFS }

    /** Where a match's player comes from: a seed, or the winner / loser of an earlier match (by index). */
    public sealed interface Source permits Seed, Winner, Loser {}

    public record Seed(int seed) implements Source {}

    public record Winner(int match) implements Source {}

    public record Loser(int match) implements Source {}

    /**
     * A match, in play order (every source refers to an earlier index).
     *
     * @param round 1-based within its kind (play-in rounds, then bracket rounds)
     * @param label "Play-in: 5 v 6", "Quarterfinal", "Semifinal", "Final", …
     */
    public record Match(Kind kind, int round, String label, Source a, Source b) {}

    public static List<Match> layout(int direct, int playIn) {
        if (direct < 1) throw new IllegalArgumentException("At least one team qualifies directly");
        if (playIn != 0 && playIn != 2 && playIn != 4) throw new IllegalArgumentException("A play-in has 0, 2 or 4 teams");
        int seeds = direct + playIn / 2;
        if (seeds < 2) throw new IllegalArgumentException("A bracket needs at least two teams");

        List<Match> out = new ArrayList<>();
        // Bracket seed -> where it comes from (direct seeds are themselves; play-in spots are winners).
        Source[] seedSource = new Source[seeds + 1];
        for (int s = 1; s <= direct; s++) seedSource[s] = new Seed(s);
        int d = direct;
        if (playIn == 2) {
            out.add(new Match(Kind.PLAY_IN, 1, "Play-in: " + (d + 1) + " v " + (d + 2), new Seed(d + 1), new Seed(d + 2)));
            seedSource[d + 1] = new Winner(0);
        } else if (playIn == 4) {
            out.add(new Match(Kind.PLAY_IN, 1, "Play-in: " + (d + 1) + " v " + (d + 2), new Seed(d + 1), new Seed(d + 2)));
            out.add(new Match(Kind.PLAY_IN, 1, "Play-in: " + (d + 3) + " v " + (d + 4), new Seed(d + 3), new Seed(d + 4)));
            out.add(new Match(Kind.PLAY_IN, 2, "Play-in: for the " + ordinal(d + 2) + " seed", new Loser(0), new Winner(1)));
            seedSource[d + 1] = new Winner(0);
            seedSource[d + 2] = new Winner(2);
        }

        int size = Integer.highestOneBit(seeds) == seeds ? seeds : Integer.highestOneBit(seeds) << 1;
        // Each entry is a Source still to be paired: a seed with a bye, or a match's winner.
        List<Source> current = new ArrayList<>();
        int[] order = seedingOrder(size);
        int roundsTotal = Integer.numberOfTrailingZeros(size);
        int round = 1;
        // First round: pairs from the seeding order; a seed facing a non-existent seed has a bye.
        for (int i = 0; i < order.length; i += 2) {
            int x = order[i];
            int y = order[i + 1];
            if (x <= seeds && y <= seeds) {
                out.add(new Match(Kind.PLAYOFFS, round, roundName(roundsTotal, round), seedSource[x], seedSource[y]));
                current.add(new Winner(out.size() - 1));
            } else {
                current.add(seedSource[Math.min(x, y)]);
            }
        }
        while (current.size() > 1) {
            round++;
            List<Source> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i += 2) {
                out.add(new Match(Kind.PLAYOFFS, round, roundName(roundsTotal, round), current.get(i), current.get(i + 1)));
                next.add(new Winner(out.size() - 1));
            }
            current = next;
        }
        return out;
    }

    /** Standard bracket order: 1, size, size/2+1 … so that 1 and 2 can only meet in the final. */
    static int[] seedingOrder(int size) {
        List<Integer> order = new ArrayList<>(List.of(1));
        while (order.size() < size) {
            int n = order.size() * 2;
            List<Integer> next = new ArrayList<>();
            for (int s : order) {
                next.add(s);
                next.add(n + 1 - s);
            }
            order = next;
        }
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    /** "Final", "Semifinal", "Quarterfinal", then "Round of 16", … (counted back from the final). */
    static String roundName(int roundsTotal, int round) {
        int fromEnd = roundsTotal - round;
        return switch (fromEnd) {
            case 0 -> "Final";
            case 1 -> "Semifinal";
            case 2 -> "Quarterfinal";
            default -> "Round of " + (1 << (fromEnd + 1));
        };
    }

    private static String ordinal(int n) {
        String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
