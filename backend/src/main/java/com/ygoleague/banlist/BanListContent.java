package com.ygoleague.banlist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Helpers over a revision's entries (card id -> status). Pure logic. */
public final class BanListContent {

    private BanListContent() {}

    /** SHA-256 over the entries sorted by card id; equal content gives an equal hash. */
    public static String hash(Map<Long, BanStatus> entries) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(entries).forEach((card, status) -> sb.append(card).append(':').append(status).append('\n'));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Applies changes to a revision's entries: a status sets or moves a card, null makes it
     * unlimited (removes it). Returns a new map.
     */
    public static Map<Long, BanStatus> apply(Map<Long, BanStatus> base, Map<Long, BanStatus> changes) {
        Map<Long, BanStatus> result = new TreeMap<>(base);
        changes.forEach((card, status) -> {
            if (status == null) result.remove(card);
            else result.put(card, status);
        });
        return result;
    }
}
