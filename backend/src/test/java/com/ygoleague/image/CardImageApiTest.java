package com.ygoleague.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ygoleague.auth.CurrentUser;
import com.ygoleague.support.PostgresTestConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {"app.catalog.sync.scheduled=false", "app.banlists.import-on-startup=false",
        "app.images.fetches-per-second=1000"})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, CardImageApiTest.FakeUpstream.class})
class CardImageApiTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 42, (byte) 0xFF, (byte) 0xD9};

    @TempDir static Path imageDir;

    @DynamicPropertySource
    static void imageDir(DynamicPropertyRegistry registry) {
        registry.add("app.images.local-dir", () -> imageDir.toString());
    }

    /** Stands in for YGOPRODeck: counts calls, and can be told to fail, 404 or block. */
    @TestConfiguration
    static class FakeUpstream {
        @Bean
        @Primary
        FakeSource fakeSource() {
            return new FakeSource();
        }
    }

    static class FakeSource implements ImageSource {
        final Map<ImageKey, AtomicInteger> calls = new ConcurrentHashMap<>();
        final Map<Long, String> behaviour = new ConcurrentHashMap<>();   // passcode -> "404" | "fail"
        volatile CountDownLatch gate;

        @Override
        public Optional<byte[]> fetch(ImageKey key) {
            calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try { g.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return switch (behaviour.getOrDefault(key.passcode(), "ok")) {
                case "404" -> Optional.empty();
                case "fail" -> throw new ImageUnavailableException("upstream down");
                default -> Optional.of(JPEG);
            };
        }

        int calls(ImageVariant v, long passcode) {
            AtomicInteger n = calls.get(new ImageKey(v, passcode));
            return n == null ? 0 : n.get();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FakeSource upstream;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws IOException {
        jdbc.execute("truncate ban_list_entry, ban_list_revision, ban_list, card_print, card_text_version, card restart identity cascade");
        card("Dark Magician", 46986414L, 46986415L, "2002-03-08");   // two artworks
        card("Pot of Greed", 55144522L, null, "2002-03-08");
        card("Future Card", 11111111L, null, "2010-01-01");
        upstream.calls.clear();
        upstream.behaviour.clear();
        upstream.gate = null;
        try (Stream<Path> files = Files.walk(imageDir)) {
            files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).filter(p -> !p.equals(imageDir)).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void firstRequestFetchesAndStoresLaterOnesComeFromTheCache() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/card-images/small/46986414.jpg"))   // signed out: images are public
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/jpeg"))
                    .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                    .andExpect(content().bytes(JPEG));
        }
        assertEquals(1, upstream.calls(ImageVariant.SMALL, 46986414L));
        assertTrue(Files.isRegularFile(imageDir.resolve("small/46986414.jpg")));
        // No cookies on images: a Set-Cookie would make them uncacheable by a CDN.
        mvc.perform(get("/api/card-images/small/46986414.jpg")).andExpect(header().doesNotExist("Set-Cookie"));
        // Each size is its own image.
        mvc.perform(get("/api/card-images/full/46986414.jpg")).andExpect(status().isOk());
        assertEquals(1, upstream.calls(ImageVariant.FULL, 46986414L));
    }

    @Test
    void onlyCatalogPasscodesAreFetched() throws Exception {
        mvc.perform(get("/api/card-images/small/99999999.jpg")).andExpect(status().isNotFound());
        assertEquals(0, upstream.calls(ImageVariant.SMALL, 99999999L));
        // Alternate artworks count.
        mvc.perform(get("/api/card-images/small/46986415.jpg")).andExpect(status().isOk());
    }

    @Test
    void malformedPathsAre404() throws Exception {
        mvc.perform(get("/api/card-images/huge/46986414.jpg")).andExpect(status().isNotFound());
        mvc.perform(get("/api/card-images/small/46986414.png")).andExpect(status().isNotFound());
        mvc.perform(get("/api/card-images/small/0.jpg")).andExpect(status().isNotFound());
        // Encoded slashes are rejected (400) by Spring Security's request firewall before reaching us.
        mvc.perform(get("/api/card-images/small/..%2F..%2Fetc.jpg")).andExpect(status().is4xxClientError());
        assertTrue(upstream.calls.isEmpty());
    }

    @Test
    void upstreamMissingIs404AndFailuresAre503WithoutCaching() throws Exception {
        upstream.behaviour.put(55144522L, "404");
        mvc.perform(get("/api/card-images/small/55144522.jpg")).andExpect(status().isNotFound());
        assertFalse(Files.exists(imageDir.resolve("small/55144522.jpg")));

        upstream.behaviour.put(55144522L, "fail");
        mvc.perform(get("/api/card-images/small/55144522.jpg"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(header().string("Cache-Control", "no-store"));

        upstream.behaviour.remove(55144522L);   // upstream recovers
        mvc.perform(get("/api/card-images/small/55144522.jpg")).andExpect(status().isOk());
    }

    @Test
    void concurrentRequestsForOneImageShareASingleFetch() throws Exception {
        upstream.gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> mvc.perform(get("/api/card-images/full/55144522.jpg")).andReturn().getResponse().getStatus()));
            }
            Thread.sleep(300);           // let them all pile up behind the first fetch
            upstream.gate.countDown();
            for (Future<Integer> r : results) assertEquals(200, r.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, upstream.calls(ImageVariant.FULL, 55144522L));
    }

    @Test
    void adminPrefetchWarmsAFormatsPoolInTheBackground() throws Exception {
        mvc.perform(post("/api/admin/card-images/prefetch").param("tcgCutoff", "2008-03-01").with(csrf()).with(as(false)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/card-images/prefetch").param("tcgCutoff", "2008-03-01").with(csrf()).with(as(true)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued").value(3));   // both Dark Magician artworks + Pot of Greed; not the 2010 card

        for (int i = 0; i < 50 && !Files.exists(imageDir.resolve("small/55144522.jpg")); i++) Thread.sleep(100);
        assertTrue(Files.exists(imageDir.resolve("small/46986414.jpg")));
        assertTrue(Files.exists(imageDir.resolve("small/46986415.jpg")));
        assertTrue(Files.exists(imageDir.resolve("small/55144522.jpg")));
        assertFalse(Files.exists(imageDir.resolve("small/11111111.jpg")));

        // Running it again queues nothing: everything is cached.
        mvc.perform(post("/api/admin/card-images/prefetch").param("tcgCutoff", "2008-03-01").with(csrf()).with(as(true)))
                .andExpect(jsonPath("$.queued").value(0));
    }

    private void card(String name, long passcode, Long altPasscode, String tcgDate) {
        jdbc.update("""
                insert into card (passcodes, name, card_type, frame_type, race, description, tcg_date, raw, content_hash)
                values (?::bigint[], ?, 'Effect Monster', 'effect', 'Spellcaster', 'text', ?::date, '{}', 'h')""",
                altPasscode == null ? "{" + passcode + "}" : "{" + passcode + "," + altPasscode + "}", name, tcgDate);
    }

    private static RequestPostProcessor as(boolean admin) {
        CurrentUser user = new CurrentUser(1L, "Admin", null, "dev", "admin", admin);
        return authentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "dev"));
    }
}
