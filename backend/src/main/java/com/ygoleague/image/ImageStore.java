package com.ygoleague.image;

import java.util.Optional;

/** Where cached images live: local disk in dev ({@link LocalDiskImageStore}), Cloudflare R2 later. */
public interface ImageStore {

    Optional<byte[]> get(ImageKey key);

    boolean exists(ImageKey key);

    /** Stores the image. Writers must make it appear atomically: readers never see a partial file. */
    void put(ImageKey key, byte[] jpeg);
}
