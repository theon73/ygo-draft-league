package com.ygoleague.notify;

/**
 * What a league can post to Discord. Only the draft's events exist so far; trades, free agency and
 * results join in Phase 4 (stored by name, so adding one needs no schema change).
 */
public enum NotificationEvent {
    /** The draft starting and finishing, every pick (including auto-picks, skips and make-ups), undos, pauses and timer changes. */
    DRAFT_PICKS,
    /** Who's on the clock whenever it moves, with the time left as a Discord countdown. */
    DRAFT_ON_THE_CLOCK
}
