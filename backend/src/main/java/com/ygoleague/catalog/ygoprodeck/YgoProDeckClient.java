package com.ygoleague.catalog.ygoprodeck;

import com.ygoleague.catalog.CardRecord;
import com.ygoleague.catalog.CatalogSource;
import com.ygoleague.catalog.SetRecord;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link CatalogSource} backed by the public YGOPRODeck API v7. Three requests per sync: version,
 * set list, and the full card dump (~25 MB), which is spooled to a temp file and streamed.
 * Their guidelines ask for local storage of data rather than repeated queries, which is
 * what the catalog tables are.
 */
@Component
public class YgoProDeckClient implements CatalogSource {

    private static final String USER_AGENT = "ygo-draft-league-catalog-sync/1.0";

    private final RestClient http;
    private final YgoProDeckCardParser parser;

    public YgoProDeckClient(ObjectMapper mapper,
                            @Value("${app.catalog.ygoprodeck.base-url}") String baseUrl) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofMinutes(2));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        this.parser = new YgoProDeckCardParser(mapper);
    }

    @Override
    public String version() {
        return http.get().uri("/checkDBVer.php")
                .exchange((req, res) -> parser.parseVersion(okBody(res)));
    }

    @Override
    public List<SetRecord> sets() {
        return http.get().uri("/cardsets.php")
                .exchange((req, res) -> parser.parseSets(okBody(res)));
    }

    @Override
    public int forEachCard(Consumer<CardRecord> sink) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ygoprodeck-cards", ".json");
            Path target = tmp;
            http.get().uri("/cardinfo.php?misc=yes")
                    .exchange((req, res) -> Files.copy(okBody(res), target, StandardCopyOption.REPLACE_EXISTING));
            try (InputStream in = Files.newInputStream(tmp)) {
                return parser.parseCards(in, sink);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp dir cleanup */ }
            }
        }
    }

    private static InputStream okBody(org.springframework.http.client.ClientHttpResponse res) throws IOException {
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("YGOPRODeck responded " + res.getStatusCode());
        }
        return res.getBody();
    }
}
