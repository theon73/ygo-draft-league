package com.ygoleague.image;

import java.util.Arrays;
import java.util.Optional;

/**
 * Image sizes we cache. {@code path} is our name (in URLs and storage keys); {@code upstream} is
 * YGOPRODeck's folder for it.
 */
public enum ImageVariant {
    /** ~170×245 thumbnail, ~26 KB. Lists, boards, rosters. */
    SMALL("small", "cards_small", null),
    /** ~420×615 full card, ~158 KB. Card pages and hover previews. */
    FULL("full", "cards", null),
    /**
     * The artwork alone, cropped from the card: roster avatars (YGO-30). The source's crops are
     * ~620px and ~170 KB, far more than a round avatar needs, so they're stored at 200px (~12 KB).
     */
    ART("art", "cards_cropped", 200);

    private final String path;
    private final String upstream;
    private final Integer maxDimension;

    ImageVariant(String path, String upstream, Integer maxDimension) {
        this.path = path;
        this.upstream = upstream;
        this.maxDimension = maxDimension;
    }

    /** Images larger than this (on either side) are scaled down before they're stored; null keeps them as sent. */
    public Integer maxDimension() { return maxDimension; }

    public String path() { return path; }

    public String upstream() { return upstream; }

    public static Optional<ImageVariant> fromPath(String path) {
        return Arrays.stream(values()).filter(v -> v.path.equals(path)).findFirst();
    }
}
