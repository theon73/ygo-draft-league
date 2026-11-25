package com.ygoleague.common;

/**
 * Entity types that appear in URLs. The tag is encoded alongside the id, so the same numeric id
 * produces a different public id per type, and a league's public id can't be used as a season's.
 *
 * <p><b>Tags are permanent.</b> Never change or reuse one; only add new types with new tags.
 */
public enum PublicIdType {
    LEAGUE(1),
    SEASON(2);

    private final long tag;

    PublicIdType(long tag) {
        this.tag = tag;
    }

    long tag() {
        return tag;
    }
}
