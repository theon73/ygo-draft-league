package com.ygoleague.catalog.ygoprodeck;

import com.ygoleague.catalog.CardRecord;
import com.ygoleague.catalog.CardRecord.PrintRecord;
import com.ygoleague.catalog.SetRecord;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps YGOPRODeck API v7 JSON ({@code cardinfo.php?misc=yes}, {@code cardsets.php}) to catalog
 * records. No Spring and no I/O beyond the stream it's given, so it's tested against saved
 * responses.
 *
 * <p>Source quirks handled here:
 * <ul>
 *   <li>Xyz rank arrives in {@code level}; Link monsters send {@code level: 0} and no DEF.
 *   <li>"?" ATK/DEF arrive as -1 with a {@code question_atk}/{@code question_def} flag; stored as null.
 *   <li>Cards with no {@code tcg_date} are OCG-only and Skill Cards are Speed Duel only: both skipped.
 *   <li>The top-level {@code id} is just the first listed artwork, and artwork order differs between
 *       responses, so it's not used as identity. Every artwork id (= passcode) is kept, sorted, and
 *       the raw copy is normalized the same way so reordering doesn't look like a change.
 * </ul>
 */
public final class YgoProDeckCardParser {

    /** Fields that change without the card changing; stripped so they don't churn content hashes. */
    private static final Set<String> VOLATILE_CARD_FIELDS = Set.of("card_prices");
    private static final Set<String> VOLATILE_MISC_FIELDS =
            Set.of("views", "viewsweek", "upvotes", "downvotes", "staple");
    private static final Set<String> SKIPPED_TYPES = Set.of("Skill Card");

    private final ObjectMapper mapper;
    /** Reads one card at a time from a stream; the rest of the array is not "trailing garbage". */
    private final ObjectReader elementReader;

    public YgoProDeckCardParser(ObjectMapper mapper) {
        this.mapper = mapper;
        this.elementReader = mapper.readerFor(JsonNode.class)
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Streams {@code {"data": [card, ...]}} without loading the whole document.
     *
     * @return number of cards skipped as not importable
     */
    public int parseCards(InputStream in, Consumer<CardRecord> sink) {
        int ignored = 0;
        try (JsonParser parser = mapper.createParser(in)) {
            if (!seekToArray(parser, "data")) throw new IllegalArgumentException("No \"data\" array in response");
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode card = elementReader.readValue(parser);
                Optional<CardRecord> record = toRecord(card);
                if (record.isPresent()) sink.accept(record.get());
                else ignored++;
            }
        }
        return ignored;
    }

    public List<SetRecord> parseSets(InputStream in) {
        List<SetRecord> sets = new ArrayList<>();
        for (JsonNode s : mapper.readTree(in).values()) {
            sets.add(new SetRecord(
                    s.path("set_name").asString(),
                    s.path("set_code").asString(),
                    date(s.path("tcg_date")),
                    intOrNull(s.path("num_of_cards"))));
        }
        return sets;
    }

    public String parseVersion(InputStream in) {
        JsonNode root = mapper.readTree(in);
        String version = root.path(0).path("database_version").asString(null);
        if (version == null || version.isBlank()) throw new IllegalArgumentException("No database_version in response");
        return version;
    }

    /** Empty when the card is not importable (OCG-only or a Skill Card). */
    public Optional<CardRecord> toRecord(JsonNode card) {
        JsonNode misc = card.path("misc_info").path(0);
        LocalDate tcgDate = date(misc.path("tcg_date"));
        String cardType = card.path("type").asString();
        if (tcgDate == null || SKIPPED_TYPES.contains(cardType)) return Optional.empty();

        String frameType = card.path("frameType").asString();
        boolean xyz = frameType.contains("xyz");
        boolean link = frameType.contains("link");
        Integer levelField = intOrNull(card.path("level"));

        Integer atk = misc.path("question_atk").asInt(0) == 1 ? null : stat(card.path("atk"));
        Integer def = misc.path("question_def").asInt(0) == 1 || link ? null : stat(card.path("def"));

        List<Long> passcodes = new ArrayList<>();
        for (JsonNode img : card.path("card_images").values()) passcodes.add(img.path("id").asLong());
        if (passcodes.isEmpty()) passcodes.add(card.path("id").asLong());

        List<PrintRecord> prints = new ArrayList<>();
        for (JsonNode p : card.path("card_sets").values()) {
            prints.add(new PrintRecord(
                    p.path("set_name").asString(),
                    p.path("set_code").asString(),
                    p.path("set_rarity").asString(),
                    stringOrNull(p.path("set_rarity_code"))));
        }

        List<String> linkMarkers = null;
        if (card.has("linkmarkers")) {
            linkMarkers = new ArrayList<>();
            for (JsonNode m : card.path("linkmarkers").values()) linkMarkers.add(m.asString());
        }

        String rawJson = mapper.writeValueAsString(normalize(card));
        return Optional.of(new CardRecord(
                intOrNull(misc.path("konami_id")),
                passcodes,
                card.path("name").asString(),
                cardType,
                frameType,
                card.path("race").asString(),
                stringOrNull(card.path("attribute")),
                xyz || link ? null : levelField,
                xyz ? levelField : null,
                intOrNull(card.path("linkval")),
                linkMarkers,
                intOrNull(card.path("scale")),
                atk,
                def,
                stringOrNull(card.path("archetype")),
                card.path("desc").asString(),
                tcgDate,
                date(misc.path("ocg_date")),
                prints,
                rawJson,
                sha256(rawJson)));
    }

    /**
     * A copy of the card without fields that change on their own (prices, popularity) or whose
     * value depends on response ordering (top-level id, artwork order).
     */
    JsonNode normalize(JsonNode card) {
        ObjectNode copy = (ObjectNode) card.deepCopy();
        copy.remove(VOLATILE_CARD_FIELDS);
        copy.remove("id");
        if (copy.path("card_images") instanceof ArrayNode images) {
            List<JsonNode> sorted = new ArrayList<>(images.values());
            sorted.sort(Comparator.comparingLong(img -> img.path("id").asLong()));
            images.removeAll();
            images.addAll(sorted);
        }
        for (JsonNode p : copy.path("card_sets").values()) ((ObjectNode) p).remove("set_price");
        for (JsonNode m : copy.path("misc_info").values()) ((ObjectNode) m).remove(VOLATILE_MISC_FIELDS);
        return copy;
    }

    private static boolean seekToArray(JsonParser parser, String field) {
        JsonToken t;
        while ((t = parser.nextToken()) != null) {
            if (t == JsonToken.PROPERTY_NAME && field.equals(parser.currentName())) {
                return parser.nextToken() == JsonToken.START_ARRAY;
            }
        }
        return false;
    }

    private static Integer intOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asInt();
    }

    /** ATK/DEF; negative means "?" even if the flag is missing. */
    private static Integer stat(JsonNode n) {
        Integer v = intOrNull(n);
        return v == null || v < 0 ? null : v;
    }

    private static String stringOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    private static LocalDate date(JsonNode n) {
        String s = stringOrNull(n);
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }

    static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
