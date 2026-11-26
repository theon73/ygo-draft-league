package com.ygoleague.banlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class OfficialBanListFileTest {

    /** Every shipped list must at least be structurally valid. Name matching is checked by importBanLists. */
    @Test
    void allShippedListsParse() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:banlists/**/*.yaml");
        assertTrue(files.length >= 2);
        for (Resource r : files) {
            try (InputStream in = r.getInputStream()) {
                OfficialBanListFile.parse(OfficialBanListImporter.keyOf(r), in);
            }
        }
    }

    @Test
    void march2008MatchesTheSourceSheet() throws Exception {
        OfficialBanListFile f = shipped("tcg/2008-03-01");
        assertEquals("TCG March 2008", f.name());
        assertEquals(LocalDate.of(2008, 3, 1), f.effective());
        Map<BanStatus, Long> counts = f.entries().values().stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        assertEquals(Map.of(BanStatus.FORBIDDEN, 38L, BanStatus.LIMITED, 55L, BanStatus.SEMI_LIMITED, 12L), counts);
        assertEquals(BanStatus.LIMITED, f.entries().get(CardRef.named("Sangan")));
    }

    @Test
    void march2017KeepsQuotedPunctuation() throws Exception {
        OfficialBanListFile f = shipped("tcg/2017-03-31");
        assertEquals(176, f.entries().size());
        assertEquals(BanStatus.LIMITED, f.entries().get(CardRef.named("Maxx \"C\"")));
        assertEquals(BanStatus.FORBIDDEN, f.entries().get(CardRef.named("Number 16: Shock Master")));
    }

    @Test
    void passcodeFormIsSupported() {
        OfficialBanListFile f = parse("tcg/2005-04-01", """
                name: x
                effective: 2005-04-01
                limited:
                  - { name: "Sangan", passcode: 26202165 }
                """);
        assertEquals(BanStatus.LIMITED, f.entries().get(new CardRef("Sangan", 26202165L)));
    }

    @Test
    void unquotedNameWithColonIsRejectedWithAHint() {
        var e = assertThrows(IllegalArgumentException.class, () -> parse("tcg/2017-03-31", """
                name: x
                effective: 2017-03-31
                forbidden:
                  - Number 16: Shock Master
                """));
        assertTrue(e.getMessage().contains("quote it"), e.getMessage());
    }

    @Test
    void rejectsDuplicatesAcrossSections() {
        var e = assertThrows(IllegalArgumentException.class, () -> parse("tcg/2005-04-01", """
                name: x
                effective: 2005-04-01
                forbidden: ["Sangan"]
                limited: ["sangan"]
                """));
        assertTrue(e.getMessage().contains("more than once"), e.getMessage());
    }

    @Test
    void fileNameMustBeTheEffectiveDate() {
        var e = assertThrows(IllegalArgumentException.class, () -> parse("tcg/2005-04-01", """
                name: x
                effective: 2005-04-02
                forbidden: ["Sangan"]
                """));
        assertTrue(e.getMessage().contains("2005-04-02.yaml"), e.getMessage());
    }

    @Test
    void rejectsUnknownFieldsSoTyposDontSilentlyDropASection() {
        var e = assertThrows(IllegalArgumentException.class, () -> parse("tcg/2005-04-01", """
                name: x
                effective: 2005-04-01
                semi-limited: ["Sangan"]
                """));
        assertTrue(e.getMessage().contains("unknown field 'semi-limited'"), e.getMessage());
    }

    static OfficialBanListFile shipped(String key) throws Exception {
        try (InputStream in = OfficialBanListFileTest.class.getResourceAsStream("/banlists/" + key + ".yaml")) {
            return OfficialBanListFile.parse(key, in);
        }
    }

    static OfficialBanListFile parse(String key, String yaml) {
        return OfficialBanListFile.parse(key, new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
