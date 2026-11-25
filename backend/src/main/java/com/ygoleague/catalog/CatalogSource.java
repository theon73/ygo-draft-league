package com.ygoleague.catalog;

import java.util.List;
import java.util.function.Consumer;

/** Where the card catalog comes from. The production implementation is YGOPRODeck. */
public interface CatalogSource {

    /** An identifier that changes whenever the source's data changes; used to skip no-op syncs. */
    String version();

    List<SetRecord> sets();

    /**
     * Streams every importable card to {@code sink}, one at a time, so the full catalog never has
     * to sit in memory.
     *
     * @return the number of source cards deliberately not imported (OCG-only, Skill Cards)
     */
    int forEachCard(Consumer<CardRecord> sink);
}
