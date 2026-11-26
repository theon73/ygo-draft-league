package com.ygoleague.image;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Downloads card images from YGOPRODeck ({@code https://images.ygoprodeck.com/images/<folder>/<passcode>.jpg}).
 * Only ever called through {@link CardImageService}, which throttles and caches, so each image
 * is fetched once. Responses that aren't a reasonably sized JPEG are rejected.
 */
@Component
class YgoProDeckImageSource implements ImageSource {

    private static final String USER_AGENT = "ygo-draft-league-image-cache/1.0";
    static final int MAX_BYTES = 2 * 1024 * 1024;   // real images are ~25-200 KB

    private final RestClient http;

    YgoProDeckImageSource(@Value("${app.images.upstream-base-url}") String baseUrl) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    @Override
    public Optional<byte[]> fetch(ImageKey key) {
        try {
            return http.get().uri("/{folder}/{passcode}.jpg", key.variant().upstream(), key.passcode())
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 404) return Optional.<byte[]>empty();
                        if (status != 200) throw new ImageUnavailableException("YGOPRODeck answered " + status + " for " + key.path());
                        MediaType type = res.getHeaders().getContentType();
                        if (type == null || !MediaType.IMAGE_JPEG.isCompatibleWith(type)) {
                            throw new ImageUnavailableException("YGOPRODeck sent " + type + " for " + key.path());
                        }
                        try (InputStream in = res.getBody()) {
                            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                                throw new ImageUnavailableException("Unexpected image size for " + key.path());
                            }
                            return Optional.of(bytes);
                        }
                    });
        } catch (ImageUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ImageUnavailableException("Couldn't reach YGOPRODeck for " + key.path(), e);
        }
    }
}
