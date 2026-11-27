package com.ygoleague.competition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.competition.RoundRobinSchedule.Pairing;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoundRobinScheduleTest {

    @ParameterizedTest(name = "{0} players, {1} cycles")
    @CsvSource({"2,1", "3,1", "4,1", "5,2", "6,2", "7,3", "10,1", "10,2", "11,2"})
    void everyPairMeetsOncePerCycleAndNothingRepeatsInConsecutiveRounds(int players, int cycles) {
        List<Integer> ids = IntStream.rangeClosed(1, players).boxed().toList();
        List<List<Pairing<Integer>>> rounds = RoundRobinSchedule.rounds(ids, cycles);
        assertEquals(RoundRobin.rounds(players, cycles), rounds.size());

        Map<Set<Integer>, Integer> meetings = new HashMap<>();
        Map<Integer, Integer> byes = new HashMap<>();
        for (List<Pairing<Integer>> round : rounds) {
            Set<Integer> seen = new HashSet<>();
            for (Pairing<Integer> p : round) {
                assertTrue(seen.add(p.a()), "plays once a round");
                if (p.bye()) {
                    byes.merge(p.a(), 1, Integer::sum);
                } else {
                    assertTrue(seen.add(p.b()), "plays once a round");
                    meetings.merge(Set.of(p.a(), p.b()), 1, Integer::sum);
                }
            }
            assertEquals(players, seen.size(), "everyone plays or has a bye");
        }
        assertEquals(players * (players - 1) / 2, meetings.size());
        meetings.values().forEach(n -> assertEquals(cycles, n));
        if (players % 2 == 1) ids.forEach(id -> assertEquals(cycles, byes.get(id), "one bye per cycle"));
        else assertTrue(byes.isEmpty());

        if (players > 2) {
            for (int r = 1; r < rounds.size(); r++) {
                Set<Set<Integer>> before = pairs(rounds.get(r - 1));
                for (Set<Integer> pair : pairs(rounds.get(r))) assertTrue(!before.contains(pair), "repeat in rounds " + r + "-" + (r + 1));
                assertTrue(byeOf(rounds.get(r - 1)) == null || !byeOf(rounds.get(r - 1)).equals(byeOf(rounds.get(r))), "bye twice in a row");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"4", "6"})
    void theFirstListedSideAlternates(int players) {
        List<List<Pairing<Integer>>> rounds = RoundRobinSchedule.rounds(IntStream.rangeClosed(1, players).boxed().toList(), 1);
        long firstListed = rounds.stream().flatMap(List::stream).filter(p -> p.a() == 1).count();
        assertTrue(firstListed > 0 && firstListed < players - 1, "player 1 isn't always listed first");
    }

    private static Set<Set<Integer>> pairs(List<Pairing<Integer>> round) {
        Set<Set<Integer>> out = new HashSet<>();
        for (Pairing<Integer> p : round) if (!p.bye()) out.add(Set.of(p.a(), p.b()));
        return out;
    }

    private static Integer byeOf(List<Pairing<Integer>> round) {
        return round.stream().filter(Pairing::bye).map(Pairing::a).findFirst().orElse(null);
    }
}
