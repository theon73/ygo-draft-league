package com.ygoleague.league;

import static com.ygoleague.league.LeagueRole.COMMISSIONER;
import static com.ygoleague.league.LeagueRole.PLAYER;
import static com.ygoleague.league.LeagueRole.VIEWER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeagueRoleTest {

    @Test
    void rolesAreOrderedCommissionerPlayerViewer() {
        assertTrue(COMMISSIONER.includes(COMMISSIONER));
        assertTrue(COMMISSIONER.includes(PLAYER));
        assertTrue(COMMISSIONER.includes(VIEWER));
        assertTrue(PLAYER.includes(VIEWER));
        assertFalse(PLAYER.includes(COMMISSIONER));
        assertFalse(VIEWER.includes(PLAYER));
        assertTrue(PLAYER.canWrite());
        assertFalse(VIEWER.canWrite());
    }

    @Test
    void cleansLeagueNames() {
        org.junit.jupiter.api.Assertions.assertEquals("Goat Format League", LeagueService.cleanName("  Goat   Format\tLeague "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName("   "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName("x".repeat(61)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName("bad\u0007name"));
    }
}
