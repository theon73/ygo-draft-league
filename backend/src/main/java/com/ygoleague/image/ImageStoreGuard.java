package com.ygoleague.image;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start production with the local-disk image store. Hosted containers (Fly, Railway)
 * have a throwaway filesystem: the cache would be wiped on every deploy and restart, re-fetching
 * every image from YGOPRODeck, and with several instances each would keep its own copy. Production
 * must use shared, persistent storage (R2; see PLANNING.md "Card images").
 */
@Component
@Profile("prod")
class ImageStoreGuard {

    ImageStoreGuard(Environment env) {
        check(env.getProperty("app.images.store", "local"));
    }

    static void check(String store) {
        if ("local".equalsIgnoreCase(store.strip())) {
            throw new IllegalStateException("app.images.store=local isn't allowed with the 'prod' profile: a container's "
                    + "disk is wiped on every deploy. Use persistent shared storage (R2) for card images.");
        }
    }
}
