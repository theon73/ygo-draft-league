package com.ygoleague.competition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ygoleague.competition.Standings.Forfeit;
import com.ygoleague.competition.Standings.Result;
import com.ygoleague.competition.Standings.Row;
import com.ygoleague.competition.Standings.Tiebreaker;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StandingsTest {

    static final Function<String, String> NAME = Function.identity();

    private static List<String> order(List<Row<String>> rows) {
        return rows.stream().map(Row::player).toList();
    }

    @Test
    void winsFirstThenGameDifferenceThenGamesWon() {
        List<Result<String>> results = List.of(
                new Result<>("A", "B", 2, 0, null),
                new Result<>("C", "D", 2, 1, null),
                new Result<>("B", "D", 2, 1, null),
                new Result<>("C", "A", 2, 1, null));
        // A: 1 win, games 3-2 (+1). B: 1 win, 2-3 (-1). C: 2 wins. D: 0 wins.
        List<Row<String>> table = Standings.table(List.of("A", "B", "C", "D"), results, Standings.DEFAULT_TIEBREAKERS, NAME);
        assertEquals(List.of("C", "A", "B", "D"), order(table));
        assertEquals(List.of(1, 2, 3, 4), table.stream().map(Row::rank).toList());
        Row<String> a = table.get(1);
        assertEquals(List.of(2, 1, 1, 0, 3, 2), List.of(a.played(), a.wins(), a.losses(), a.draws(), a.gamesWon(), a.gamesLost()));
    }

    @Test
    void drawsCountGamesButNoWinOrLoss() {
        List<Row<String>> table = Standings.table(List.of("A", "B"), List.of(new Result<>("A", "B", 1, 1, null)), Standings.DEFAULT_TIEBREAKERS, NAME);
        assertEquals(1, table.get(0).draws());
        assertEquals(0, table.get(0).wins() + table.get(0).losses());
        assertEquals(1, table.get(0).gamesWon());
    }

    @Test
    void forfeitsAreWinsWithoutGamesAndADoubleForfeitIsALossForBoth() {
        List<Result<String>> results = List.of(new Result<>("A", "B", 0, 0, Forfeit.B), new Result<>("C", "D", 0, 0, Forfeit.BOTH));
        List<Row<String>> table = Standings.table(List.of("A", "B", "C", "D"), results, Standings.DEFAULT_TIEBREAKERS, NAME);
        assertEquals("A", table.get(0).player());
        assertEquals(0, table.get(0).gamesWon());
        Row<String> c = table.stream().filter(r -> r.player().equals("C")).findFirst().orElseThrow();
        assertEquals(1, c.losses());
    }

    @Test
    void headToHeadSeparatesPlayersStillLevelAfterTheOtherTiebreakers() {
        List<Result<String>> results = List.of(
                new Result<>("A", "C", 2, 1, null),
                new Result<>("B", "A", 2, 1, null),
                new Result<>("D", "B", 2, 1, null));
        // D: 1 win, +1. A and B: 1 win each, games 3-3, so level on wins, difference and games won; B beat A.
        List<Row<String>> table = Standings.table(List.of("A", "B", "C", "D"), results, Standings.DEFAULT_TIEBREAKERS, NAME);
        assertEquals(List.of("D", "B", "A", "C"), order(table));
        // Without head-to-head, the name decides.
        assertEquals(List.of("D", "A", "B", "C"), order(Standings.table(List.of("A", "B", "C", "D"), results,
                List.of(Tiebreaker.GAME_DIFF, Tiebreaker.GAMES_WON), NAME)));
    }

    @Test
    void aThreeWayHeadToHeadThatDoesntSeparateFallsBackToNames() {
        List<Result<String>> results = List.of(new Result<>("B", "C", 2, 0, null), new Result<>("A", "B", 2, 0, null), new Result<>("C", "A", 2, 0, null));
        assertEquals(List.of("A", "B", "C"), order(Standings.table(List.of("C", "B", "A"), results, Standings.DEFAULT_TIEBREAKERS, NAME)));
    }
}
