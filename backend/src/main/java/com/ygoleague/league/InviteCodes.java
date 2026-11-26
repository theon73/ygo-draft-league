package com.ygoleague.league;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random invite codes: 128 bits from {@link SecureRandom}, base64url without padding (22
 * characters, URL-safe). Too many possibilities to guess, so holding the link is what grants
 * access. Codes are secrets: never log them.
 */
public final class InviteCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private InviteCodes() {}

    public static String next() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
