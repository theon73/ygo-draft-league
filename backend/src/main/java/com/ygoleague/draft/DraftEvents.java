package com.ygoleague.draft;

import com.ygoleague.live.SseStreams;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live draft updates (YGO-17): the open server-sent-event streams per season, and telling them when
 * the draft changes. An event is a signal, not the board: {@code draft} with the status, how many
 * picks are made and the seat on the clock. Clients refetch {@code GET …/draft} for the rest, which
 * keeps per-viewer fields (can I pick, my seat) and access checks in one place. Every member's
 * stream gets every draft event. See {@link SseStreams} for delivery and heartbeats.
 */
@Component
public class DraftEvents {

    /**
     * @param onTheClockSeat null when nobody is (not started, or complete)
     * @param paused         a commissioner has paused the draft
     */
    public record DraftChanged(DraftService.DraftStatus status, int picksMade, Integer onTheClockSeat, boolean paused) {}

    private final SseStreams<Boolean> streams = new SseStreams<>("draft-events");

    /** Opens a stream for a season. The caller has already checked the viewer may see it. */
    public SseEmitter subscribe(long seasonId, DraftChanged current) {
        return streams.open(seasonId, Boolean.TRUE, "draft", current);
    }

    /** Tells the season's streams the draft changed, once the current transaction (if any) commits. */
    public void publish(long seasonId, DraftChanged change) {
        streams.publish(seasonId, any -> true, "draft", change);
    }

    int openStreams(long seasonId) {
        return streams.openStreams(seasonId);
    }

    @PreDestroy
    void shutdown() {
        streams.shutdown();
    }
}
