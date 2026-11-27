package com.ygoleague.competition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A league table from final match results. Spring-free.
 *
 * <ul>
 *   <li>A match is won by the player with more games; level games (e.g. 1-1, tied games left out) is
 *       a <b>draw</b>: neither a win nor a loss, but its games count.
 *   <li>A <b>forfeit</b> is a win for the other player (a double forfeit a loss for both); its games
 *       don't count toward games won or game difference.
 *   <li>Ranking: most wins first, then the season's tiebreakers in order, then name (so the order is
 *       always the same). <b>Head-to-head</b> compares wins in the matches between the players still
 *       tied at that point (a mini-league), and only separates them when it does.
 * </ul>
 */
public final class Standings {

    private Standings() {}

    public enum Tiebreaker { GAME_DIFF, GAMES_WON, HEAD_TO_HEAD }

    public static final List<Tiebreaker> DEFAULT_TIEBREAKERS = List.of(Tiebreaker.GAME_DIFF, Tiebreaker.GAMES_WON, Tiebreaker.HEAD_TO_HEAD);

    /** Who forfeited, if anyone. */
    public enum Forfeit { A, B, BOTH }

    /** A final result between a and b; games are ignored for a forfeit. */
    public record Result<T>(T a, T b, int gamesA, int gamesB, Forfeit forfeit) {
        /** The winner, or null for a draw or a double forfeit. */
        public T winner() {
            if (forfeit != null) return forfeit == Forfeit.A ? b : forfeit == Forfeit.B ? a : null;
            return gamesA > gamesB ? a : gamesB > gamesA ? b : null;
        }

        public boolean draw() {
            return forfeit == null && gamesA == gamesB;
        }

        public boolean involves(T p) {
            return a.equals(p) || b.equals(p);
        }
    }

    public record Row<T>(T player, int rank, int played, int wins, int losses, int draws, int gamesWon, int gamesLost) {
        public int gameDiff() { return gamesWon - gamesLost; }
    }

    /** {@code players} in any order; {@code name} breaks the final ties. Ranks are 1, 2, 3, … (every row gets its own place). */
    public static <T> List<Row<T>> table(List<T> players, List<Result<T>> results, List<Tiebreaker> tiebreakers, Function<T, String> name) {
        Map<T, int[]> s = new LinkedHashMap<>();   // played, wins, losses, draws, gamesWon, gamesLost
        for (T p : players) s.put(p, new int[6]);
        for (Result<T> r : results) {
            int[] a = s.get(r.a());
            int[] b = s.get(r.b());
            if (a == null || b == null) continue;
            a[0]++;
            b[0]++;
            T w = r.winner();
            if (r.draw()) { a[3]++; b[3]++; }
            else if (w == null) { a[2]++; b[2]++; }                 // double forfeit
            else if (w.equals(r.a())) { a[1]++; b[2]++; }
            else { b[1]++; a[2]++; }
            if (r.forfeit() == null) {
                a[4] += r.gamesA(); a[5] += r.gamesB();
                b[4] += r.gamesB(); b[5] += r.gamesA();
            }
        }

        List<T> order = new ArrayList<>(players);
        order.sort(Comparator.comparingInt((T p) -> -s.get(p)[1]).thenComparing(name));
        // Apply the tiebreakers group by group: players level on wins, then level after each tiebreaker.
        List<List<T>> groups = split(order, p -> s.get(p)[1]);
        for (Tiebreaker t : tiebreakers) {
            List<List<T>> next = new ArrayList<>();
            for (List<T> g : groups) {
                if (g.size() == 1) { next.add(g); continue; }
                Function<T, Integer> key = switch (t) {
                    case GAME_DIFF -> p -> s.get(p)[4] - s.get(p)[5];
                    case GAMES_WON -> p -> s.get(p)[4];
                    case HEAD_TO_HEAD -> headToHead(g, results);
                };
                List<T> sorted = new ArrayList<>(g);
                sorted.sort(Comparator.comparing((T p) -> -key.apply(p)).thenComparing(name));
                next.addAll(split(sorted, key));
            }
            groups = next;
        }

        List<Row<T>> rows = new ArrayList<>();
        int rank = 1;
        for (List<T> g : groups) {
            for (T p : g) {   // still level after every tiebreaker: by name
                int[] x = s.get(p);
                rows.add(new Row<>(p, rank++, x[0], x[1], x[2], x[3], x[4], x[5]));
            }
        }
        return rows;
    }

    /** Wins in matches among the group only. */
    private static <T> Function<T, Integer> headToHead(List<T> group, List<Result<T>> results) {
        Set<T> members = new HashSet<>(group);
        Map<T, Integer> wins = new LinkedHashMap<>();
        for (Result<T> r : results) {
            if (!members.contains(r.a()) || !members.contains(r.b())) continue;
            T w = r.winner();
            if (w != null) wins.merge(w, 1, Integer::sum);
        }
        return p -> wins.getOrDefault(p, 0);
    }

    /** Consecutive runs with the same key (the list is already sorted by it). */
    private static <T> List<List<T>> split(List<T> sorted, Function<T, Integer> key) {
        List<List<T>> out = new ArrayList<>();
        Integer last = null;
        for (T p : sorted) {
            Integer k = key.apply(p);
            if (out.isEmpty() || !k.equals(last)) out.add(new ArrayList<>());
            out.getLast().add(p);
            last = k;
        }
        return out;
    }
}
