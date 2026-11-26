package com.ygoleague.image;

import java.util.Optional;

/** Where images come from the first time: YGOPRODeck ({@link YgoProDeckImageSource}). */
public interface ImageSource {

    /**
     * @return the JPEG, or empty if the source has no image for this passcode
     * @throws ImageUnavailableException if the source failed (network, rate limit, bad response)
     */
    Optional<byte[]> fetch(ImageKey key);
}
