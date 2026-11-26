package com.ygoleague.image;

/**
 * One cached image: a card artwork (its passcode) at one size. {@link #path()} is the same in
 * every place an image lives (local disk, R2 key, URL), so switching where images are served
 * from is configuration only. Images are immutable once stored.
 */
public record ImageKey(ImageVariant variant, long passcode) {

    public ImageKey {
        if (passcode <= 0) throw new IllegalArgumentException("passcode must be positive");
    }

    /** e.g. {@code small/46986414.jpg} */
    public String path() {
        return variant.path() + "/" + passcode + ".jpg";
    }
}
