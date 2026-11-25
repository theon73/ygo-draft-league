package com.ygoleague.catalog;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves a card's wording as of a date, for formats that play older text. Pure logic, no Spring.
 *
 * <p>Superseded versions each record the date they stopped applying ({@code validUntil},
 * exclusive). The text in effect on date D is the version with the earliest
 * {@code validUntil > D}; if every recorded version ended on or before D, it's the current text.
 * Only old wordings need recording, so a card with no errata has no rows at all.
 */
public final class CardText {

    /** A superseded wording. */
    public record Version(LocalDate validUntil, String description) {}

    private CardText() {}

    public static String asOf(String currentText, List<Version> superseded, LocalDate date) {
        return superseded.stream()
                .filter(v -> v.validUntil().isAfter(date))
                .min(Comparator.comparing(Version::validUntil))
                .map(Version::description)
                .orElse(currentText);
    }
}
