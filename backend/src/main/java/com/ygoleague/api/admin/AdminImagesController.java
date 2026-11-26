package com.ygoleague.api.admin;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.image.CardImageService;
import com.ygoleague.image.ImageVariant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Warms the image cache in the background, e.g. a format's whole pool before its draft (until
 * Phase 2 does this when a pool is finalized). Idempotent: cached images are skipped.
 */
@RestController
@RequestMapping("/api/admin/card-images")
public class AdminImagesController {

    private static final Logger log = LoggerFactory.getLogger(AdminImagesController.class);

    public record Queued(int queued) {}

    private final CardImageService images;

    public AdminImagesController(CardImageService images) {
        this.images = images;
    }

    @PostMapping("/prefetch")
    public ResponseEntity<Queued> prefetch(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tcgCutoff,
                                           @RequestParam(defaultValue = "small") String size,
                                           @AuthenticationPrincipal CurrentUser admin) {
        ImageVariant variant = ImageVariant.fromPath(size).orElse(null);
        if (variant == null) return ResponseEntity.badRequest().build();
        int queued = images.prefetchUpTo(tcgCutoff, variant);
        log.info("Image prefetch requested by {}: {} {} images up to {}", admin, queued, size, tcgCutoff);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Queued(queued));
    }
}
