package com.ygoleague.banlist;

/**
 * How a ban list file names a card: by name as printed (e.g. on Yugipedia), optionally with a
 * passcode when the name is ambiguous or was renamed since.
 */
public record CardRef(String name, Long passcode) {

    public CardRef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Card name is required");
    }

    public static CardRef named(String name) {
        return new CardRef(name, null);
    }

    @Override
    public String toString() {
        return passcode == null ? name : name + " (" + passcode + ")";
    }
}
