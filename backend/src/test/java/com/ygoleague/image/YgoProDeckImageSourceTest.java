package com.ygoleague.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a tiny local HTTP server standing in for images.ygoprodeck.com. */
class YgoProDeckImageSourceTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};

    HttpServer server;
    YgoProDeckImageSource source;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/cards_small/1.jpg", 200, "image/jpeg", JPEG);
        respond("/cards/1.jpg", 200, "image/jpeg", new byte[] {9, 9});
        respond("/cards_small/2.jpg", 404, "text/html", "nope".getBytes());
        respond("/cards_small/3.jpg", 200, "text/html", "<html>".getBytes());
        respond("/cards_small/4.jpg", 503, "text/plain", "busy".getBytes());
        respond("/cards_small/5.jpg", 200, "image/jpeg", new byte[YgoProDeckImageSource.MAX_BYTES + 1]);
        server.start();
        source = new YgoProDeckImageSource("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchesTheRightFolderPerVariant() {
        assertArrayEquals(JPEG, source.fetch(new ImageKey(ImageVariant.SMALL, 1)).orElseThrow());
        assertArrayEquals(new byte[] {9, 9}, source.fetch(new ImageKey(ImageVariant.FULL, 1)).orElseThrow());
    }

    @Test
    void missingIsEmptyAndBadResponsesAreUnavailable() {
        assertEquals(Optional.empty(), source.fetch(new ImageKey(ImageVariant.SMALL, 2)));
        assertThrows(ImageUnavailableException.class, () -> source.fetch(new ImageKey(ImageVariant.SMALL, 3)));   // not a JPEG
        assertThrows(ImageUnavailableException.class, () -> source.fetch(new ImageKey(ImageVariant.SMALL, 4)));   // 503
        assertThrows(ImageUnavailableException.class, () -> source.fetch(new ImageKey(ImageVariant.SMALL, 5)));   // too big
        server.stop(0);
        assertThrows(ImageUnavailableException.class, () -> source.fetch(new ImageKey(ImageVariant.SMALL, 1)));   // unreachable
    }

    private void respond(String path, int status, String type, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }
}
