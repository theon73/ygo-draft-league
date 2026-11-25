package com.ygoleague.season;

/** Coarse season lifecycle. Finer structure (regular season, playoffs) lives in stages. */
public enum SeasonStatus {
    /** Commissioner is configuring rules, pool and seats. */
    SETUP,
    DRAFTING,
    /** Matches and transactions. */
    ACTIVE,
    COMPLETE
}
