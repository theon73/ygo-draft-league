package com.ygoleague.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ygoleague.catalog.CardText.Version;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardTextTest {

    static final String CURRENT = "2020s wording";
    static final List<Version> VERSIONS = List.of(
            new Version(LocalDate.of(2010, 9, 1), "2000s wording"),   // replaced Sept 2010
            new Version(LocalDate.of(2005, 4, 1), "original wording") // replaced April 2005
    );

    @Test
    void picksTheVersionInEffectOnTheDate() {
        assertEquals("original wording", CardText.asOf(CURRENT, VERSIONS, LocalDate.of(2002, 6, 26)));
        assertEquals("original wording", CardText.asOf(CURRENT, VERSIONS, LocalDate.of(2005, 3, 31)));
        assertEquals("2000s wording", CardText.asOf(CURRENT, VERSIONS, LocalDate.of(2005, 4, 1)));
        assertEquals("2000s wording", CardText.asOf(CURRENT, VERSIONS, LocalDate.of(2008, 3, 1)));
        assertEquals(CURRENT, CardText.asOf(CURRENT, VERSIONS, LocalDate.of(2010, 9, 1)));
    }

    @Test
    void noRecordedVersionsMeansCurrentText() {
        assertEquals(CURRENT, CardText.asOf(CURRENT, List.of(), LocalDate.of(2005, 1, 1)));
    }
}
