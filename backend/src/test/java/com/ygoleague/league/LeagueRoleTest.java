package com.ygoleague.league;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeagueRoleTest {

    @Test
    void theOwnerCanDoEverythingAMemberCan() {
        assertTrue(LeagueRole.OWNER.includes(LeagueRole.MEMBER));
        assertTrue(LeagueRole.OWNER.includes(LeagueRole.OWNER));
        assertTrue(LeagueRole.MEMBER.includes(LeagueRole.MEMBER));
        assertFalse(LeagueRole.MEMBER.includes(LeagueRole.OWNER));
    }

    @Test
    void cleansLeagueNames() {
        assertEquals("Goat Format League", LeagueService.cleanName("  Goat   Format\tLeague "));
        assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName("   "));
        assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName(null));
        assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName("x".repeat(61)));
        assertThrows(IllegalArgumentException.class, () -> LeagueService.cleanName("bad\u0007name"));
    }
}
