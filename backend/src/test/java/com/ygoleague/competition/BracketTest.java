package com.ygoleague.competition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ygoleague.competition.Bracket.Kind;
import com.ygoleague.competition.Bracket.Loser;
import com.ygoleague.competition.Bracket.Match;
import com.ygoleague.competition.Bracket.Seed;
import com.ygoleague.competition.Bracket.Source;
import com.ygoleague.competition.Bracket.Winner;
import java.util.List;
import org.junit.jupiter.api.Test;

class BracketTest {

    /** "5", "W0" (winner of match 0), "L0". */
    private static String s(Source src) {
        return switch (src) {
            case Seed x -> String.valueOf(x.seed());
            case Winner w -> "W" + w.match();
            case Loser l -> "L" + l.match();
        };
    }

    private static List<String> describe(List<Match> ms) {
        return ms.stream().map(m -> m.label() + ": " + s(m.a()) + " v " + s(m.b())).toList();
    }

    @Test
    void theStandardOrderKeepsTheTopSeedsApart() {
        assertArrayEquals(new int[] {1, 8, 4, 5, 2, 7, 3, 6}, Bracket.seedingOrder(8));
    }

    @Test
    void fourDirectAndAnNbaStylePlayInGivesByesToTheTopTwo() {
        assertEquals(List.of(
                "Play-in: 5 v 6: 5 v 6",
                "Play-in: 7 v 8: 7 v 8",
                "Play-in: for the 6th seed: L0 v W1",
                "Quarterfinal: 4 v W0",       // 4 v the 5 seed
                "Quarterfinal: 3 v W2",       // 3 v the 6 seed
                "Semifinal: 1 v W3",
                "Semifinal: 2 v W4",
                "Final: W5 v W6"), describe(Bracket.layout(4, 4)));
    }

    @Test
    void aTwoTeamPlayInIsOneMatchForOneSpot() {
        List<Match> ms = Bracket.layout(3, 2);   // 4 in the bracket, no byes
        assertEquals(List.of("Play-in: 4 v 5: 4 v 5", "Semifinal: 1 v W0", "Semifinal: 2 v 3", "Final: W1 v W2"), describe(ms));
        assertEquals(Kind.PLAY_IN, ms.getFirst().kind());
    }

    @Test
    void noPlayInWithAnOddFieldGivesTheTopSeedABye() {
        assertEquals(List.of("Semifinal: 2 v 3", "Final: 1 v W0"), describe(Bracket.layout(3, 0)));
        assertEquals(List.of("Final: 1 v 2"), describe(Bracket.layout(2, 0)));
        assertEquals(List.of("Quarterfinal: 1 v 8", "Quarterfinal: 4 v 5", "Quarterfinal: 2 v 7", "Quarterfinal: 3 v 6",
                "Semifinal: W0 v W1", "Semifinal: W2 v W3", "Final: W4 v W5"), describe(Bracket.layout(8, 0)));
    }

    @Test
    void impossibleShapesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Bracket.layout(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Bracket.layout(4, 3));
    }
}
