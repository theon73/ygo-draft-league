package com.ygoleague.banlist;

import java.util.Locale;

/** A card's restriction on a ban list. Cards not on a list are unlimited, so there's no UNLIMITED. */
public enum BanStatus {
    FORBIDDEN(0),
    LIMITED(1),
    SEMI_LIMITED(2);

    private final int maxCopies;

    BanStatus(int maxCopies) {
        this.maxCopies = maxCopies;
    }

    /** Copies allowed in a deck (main + extra + side) under this status. */
    public int maxCopies() {
        return maxCopies;
    }

    /** Accepts "Forbidden", "semi-limited", "SEMI_LIMITED", "Semi Limited", etc. */
    public static BanStatus parse(String label) {
        String key = label.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return valueOf(key);
    }
}
