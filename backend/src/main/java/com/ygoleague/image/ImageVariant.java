package com.ygoleague.image;

import java.util.Arrays;
import java.util.Optional;

/**
 * Image sizes we cache. {@code path} is our name (in URLs and storage keys); {@code upstream} is
 * YGOPRODeck's folder for it. Art crops ({@code cards_cropped}) aren't cached until a feature
 * needs them (see PLANNING.md, "Card images").
 */
public enum ImageVariant {
    /** ~170×245 thumbnail, ~26 KB. Lists, boards, rosters. */
    SMALL("small", "cards_small"),
    /** ~420×615 full card, ~158 KB. Card pages and hover previews. */
    FULL("full", "cards");

    private final String path;
    private final String upstream;

    ImageVariant(String path, String upstream) {
        this.path = path;
        this.upstream = upstream;
    }

    public String path() { return path; }

    public String upstream() { return upstream; }

    public static Optional<ImageVariant> fromPath(String path) {
        return Arrays.stream(values()).filter(v -> v.path.equals(path)).findFirst();
    }
}
