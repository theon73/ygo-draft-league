package com.ygoleague.image;

/** The image exists in principle but couldn't be fetched right now; worth retrying later. */
public class ImageUnavailableException extends RuntimeException {

    public ImageUnavailableException(String message) {
        super(message);
    }

    public ImageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
