package com.ygoleague.catalog;

import com.ygoleague.catalog.ygoprodeck.YgoProDeckCardParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Serves the saved YGOPRODeck fixtures through the real parser, with knobs for each test. */
class FakeCatalogSource implements CatalogSource {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final YgoProDeckCardParser parser = new YgoProDeckCardParser(mapper);

    String version = "148.08";
    /** Applied to each raw card before parsing, to simulate upstream changes. */
    UnaryOperator<ObjectNode> edit = c -> c;
    /** When set, card streaming throws after this many cards, to simulate a mid-sync failure. */
    Integer failAfter;

    void reset() {
        version = "148.08";
        edit = c -> c;
        failAfter = null;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public List<SetRecord> sets() {
        try (InputStream in = resource("cardsets.json")) {
            return parser.parseSets(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int forEachCard(Consumer<CardRecord> sink) {
        int ignored = 0;
        int delivered = 0;
        try (InputStream in = resource("cardinfo.json")) {
            for (JsonNode card : mapper.readTree(in).path("data").values()) {
                if (failAfter != null && delivered == failAfter) throw new IllegalStateException("simulated outage");
                var record = parser.toRecord(edit.apply((ObjectNode) card.deepCopy()));
                if (record.isPresent()) {
                    sink.accept(record.get());
                    delivered++;
                } else {
                    ignored++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ignored;
    }

    private static InputStream resource(String name) {
        return FakeCatalogSource.class.getResourceAsStream("/ygoprodeck/" + name);
    }
}
