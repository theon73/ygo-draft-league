package com.ygoleague.common;

import java.util.List;
import java.util.Optional;
import org.sqids.Sqids;

/**
 * Converts database ids to opaque URL ids and back, with no extra column. Uses
 * <a href="https://sqids.org">Sqids</a>, a reversible encoding, over {@code [type tag, id]}.
 *
 * <ul>
 *   <li><b>Unique:</b> the encoding is one-to-one, so distinct (type, id) pairs never collide.
 *   <li><b>Canonical:</b> Sqids can decode a few non-canonical strings to the same numbers, so
 *       {@link #decode} re-encodes and rejects anything that isn't the exact canonical string.
 *       Every entity therefore has exactly one valid URL.
 *   <li><b>Not security:</b> this hides sequential ids and counts, nothing more. Access is
 *       enforced by per-league authorization.
 * </ul>
 *
 * <p><b>The alphabet and minimum length are permanent:</b> changing either changes every public
 * id and breaks every existing link.
 */
public final class PublicIds {

    private final Sqids sqids;

    public PublicIds(String alphabet, int minLength) {
        this.sqids = Sqids.builder().alphabet(alphabet).minLength(minLength).build();
    }

    public String encode(PublicIdType type, long id) {
        if (id < 0) throw new IllegalArgumentException("id must be >= 0");
        return sqids.encode(List.of(type.tag(), id));
    }

    /** The database id, or empty if the string isn't a canonical public id of this type. */
    public Optional<Long> decode(PublicIdType type, String publicId) {
        if (publicId == null || publicId.isEmpty()) return Optional.empty();
        List<Long> parts;
        try {
            parts = sqids.decode(publicId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (parts.size() != 2 || parts.get(0) != type.tag()) return Optional.empty();
        long id = parts.get(1);
        return encode(type, id).equals(publicId) ? Optional.of(id) : Optional.empty();
    }
}
