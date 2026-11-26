package com.ygoleague.api.image;

import com.ygoleague.image.CardImageService;
import com.ygoleague.image.ImageKey;
import com.ygoleague.image.ImageUnavailableException;
import com.ygoleague.image.ImageVariant;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/card-images/{small|full}/{passcode}.jpg}: the same path layout as the image
 * store and a future R2 bucket, so the frontend's image base URL can be pointed at either.
 * Public (card art isn't league data), and cached by browsers for a year: images never change.
 */
@RestController
@RequestMapping("/api/card-images")
public class CardImageController {

    private static final Pattern FILE = Pattern.compile("(\\d{1,12})\\.jpg");
    private static final CacheControl FOREVER = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final CardImageService images;

    public CardImageController(CardImageService images) {
        this.images = images;
    }

    @GetMapping("/{variant}/{file}")
    public ResponseEntity<byte[]> image(@PathVariable String variant, @PathVariable String file) {
        Optional<ImageVariant> v = ImageVariant.fromPath(variant);
        Matcher m = FILE.matcher(file);
        if (v.isEmpty() || !m.matches() || Long.parseLong(m.group(1)) <= 0) return ResponseEntity.notFound().build();
        return images.image(new ImageKey(v.get(), Long.parseLong(m.group(1))))
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(FOREVER).body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Couldn't fetch it right now: tell the browser to retry, and not to cache the failure. */
    @ExceptionHandler(ImageUnavailableException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
