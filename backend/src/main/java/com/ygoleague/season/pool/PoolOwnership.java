package com.ygoleague.season.pool;

import com.ygoleague.season.Season;
import org.springframework.stereotype.Component;

/**
 * Whether a pool card is owned by anyone in the season. A card can only leave the pool while
 * nobody owns it; to take an owned card out of play, soft-ban it instead.
 *
 * <p>Ownership doesn't exist until the draft (Phase 3) writes picks, so for now nothing is owned.
 * Phase 3 replaces this with a check against the ownership projection.
 */
@Component
public class PoolOwnership {

    public boolean isOwned(Season season, long cardId) {
        return false;
    }
}
