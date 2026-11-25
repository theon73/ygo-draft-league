package com.ygoleague.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicIdsTest {

    // Same values as application.yml; the pinned-output test below guards against changing them.
    private final PublicIds ids = new PublicIds(
            "CS4A2WEpasq9JOlUgv0Fy7tnh8orReBPwXd1juimbkGD6VNZTIKfx5zHcLQ3YM", 8);

    @Test
    void roundTrips() {
        for (long id : new long[] {0, 1, 2, 42, 999_999, Long.MAX_VALUE / 2}) {
            String encoded = ids.encode(PublicIdType.LEAGUE, id);
            assertTrue(encoded.length() >= 8, encoded);
            assertEquals(Optional.of(id), ids.decode(PublicIdType.LEAGUE, encoded));
        }
    }

    @Test
    void uniqueAcrossIdsAndTypes() {
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 5_000; id++) {
            assertTrue(seen.add(ids.encode(PublicIdType.LEAGUE, id)));
            assertTrue(seen.add(ids.encode(PublicIdType.SEASON, id)));
        }
    }

    @Test
    void typeIsChecked() {
        String league = ids.encode(PublicIdType.LEAGUE, 7);
        assertNotEquals(league, ids.encode(PublicIdType.SEASON, 7));
        assertEquals(Optional.empty(), ids.decode(PublicIdType.SEASON, league));
    }

    @Test
    void rejectsGarbageAndNonCanonicalStrings() {
        assertEquals(Optional.empty(), ids.decode(PublicIdType.LEAGUE, null));
        assertEquals(Optional.empty(), ids.decode(PublicIdType.LEAGUE, ""));
        assertEquals(Optional.empty(), ids.decode(PublicIdType.LEAGUE, "not-an-id!"));
        assertEquals(Optional.empty(), ids.decode(PublicIdType.LEAGUE, "zzzzzzzz"));
        String valid = ids.encode(PublicIdType.LEAGUE, 42);
        assertEquals(Optional.empty(), ids.decode(PublicIdType.LEAGUE, valid + "x"));
        assertEquals(Optional.empty(), ids.decode(PublicIdType.LEAGUE, valid.substring(1)));
    }

    /**
     * Pins real outputs. If this fails, the alphabet, min length, a type tag or the Sqids library
     * changed, and every existing link would break. Don't update the expected values to make it
     * pass; revert the change instead.
     */
    @Test
    void encodingIsStable() {
        assertEquals("lIri2her", ids.encode(PublicIdType.LEAGUE, 1));
        assertEquals("n1J7vtaE", ids.encode(PublicIdType.SEASON, 1));
    }
}
