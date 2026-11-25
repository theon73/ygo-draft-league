package com.ygoleague.league;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ygoleague.user.AppUser;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Plain unit tests: no Spring, no database. */
class LeagueInviteTest {

    private final AppUser commish = new AppUser("Commish");
    private final League league = new League("Test League", commish);
    private final Instant now = Instant.parse("2026-11-25T12:00:00Z");

    @Test
    void openInviteRespectsMaxUses() {
        LeagueInvite invite = LeagueInvite.open(league, "abc", LeagueRole.PLAYER, 2, null, commish);
        invite.markUsed(now);
        invite.markUsed(now);
        assertFalse(invite.isRedeemable(now));
        assertThrows(IllegalStateException.class, () -> invite.markUsed(now));
    }

    @Test
    void unlimitedInviteStaysRedeemable() {
        LeagueInvite invite = LeagueInvite.open(league, "abc", LeagueRole.VIEWER, null, null, commish);
        for (int i = 0; i < 50; i++) invite.markUsed(now);
        assertTrue(invite.isRedeemable(now));
    }

    @Test
    void expiryAndRevocation() {
        LeagueInvite invite = LeagueInvite.open(league, "abc", LeagueRole.PLAYER, null,
                now.plus(Duration.ofDays(1)), commish);
        assertTrue(invite.isRedeemable(now));
        assertFalse(invite.isRedeemable(now.plus(Duration.ofDays(1))));

        invite.revoke(now);
        assertFalse(invite.isRedeemable(now));
    }

    @Test
    void claimInviteIsSingleUseAndRequiresPlaceholder() {
        LeagueMember placeholder = LeagueMember.placeholder(league, "Kyle", LeagueRole.PLAYER);
        LeagueInvite invite = LeagueInvite.claim(placeholder, "xyz", null, commish);
        assertTrue(invite.isClaimInvite());
        invite.markUsed(now);
        assertFalse(invite.isRedeemable(now));

        LeagueMember linked = LeagueMember.forUser(league, commish, "Commish", LeagueRole.COMMISSIONER);
        assertThrows(IllegalArgumentException.class, () -> LeagueInvite.claim(linked, "q", null, commish));
    }

    @Test
    void placeholderCanBeClaimedOnce() {
        LeagueMember placeholder = LeagueMember.placeholder(league, "Kyle", LeagueRole.PLAYER);
        placeholder.claim(new AppUser("Kyle"));
        assertFalse(placeholder.isPlaceholder());
        assertThrows(IllegalStateException.class, () -> placeholder.claim(new AppUser("Someone else")));
    }
}
