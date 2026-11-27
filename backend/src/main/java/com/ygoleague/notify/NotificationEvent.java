package com.ygoleague.notify;

/**
 * What a league can post to Discord (stored by name, so adding one needs no schema change).
 * Results join in Phase 4.
 */
public enum NotificationEvent {
    /** The draft starting and finishing, every pick (including auto-picks, skips and make-ups), undos, pauses and timer changes. */
    DRAFT_PICKS,
    /** Who's on the clock whenever it moves, with the time left as a Discord countdown. */
    DRAFT_ON_THE_CLOCK,
    /** Executed transactions: free-agent claims (with their drops), and trades from YGO-24. */
    TRANSACTIONS
}
