package com.ygoleague.season.transaction;

import com.ygoleague.live.SseStreams;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live messages and trades (YGO-35): each season member's server-sent-event stream, told when one
 * of their conversations changes (a message, an offer, or an offer accepted, declined, withdrawn,
 * cancelled, approved or rejected).
 *
 * <p>An event is a bare signal ({@code messages}, no content), so nothing private travels in it:
 * clients refetch the conversation list, the open conversation and the season's unread count, which
 * do their own access checks. It goes to the two players' accounts, and to the season's
 * commissioners when a placeholder (whom they act for) or an approval is involved.
 */
@Component
public class MessageEvents {

    /** Who a stream belongs to; {@code commissioner} as of when it opened (a reconnect re-checks it). */
    record Viewer(long userId, boolean commissioner) {}

    /** The (empty) event body; a record so it serializes as {@code {}}. */
    public record Changed() {}

    private final SseStreams<Viewer> streams = new SseStreams<>("message-events");

    /** Opens a stream for a season. The caller has already checked the viewer is a member. */
    public SseEmitter subscribe(long seasonId, long userId, boolean commissioner) {
        return streams.open(seasonId, new Viewer(userId, commissioner), "messages", new Changed());
    }

    /**
     * A conversation between these players changed. Null user ids are placeholders.
     *
     * @param commissioners also tell the season's commissioners (forced when a placeholder is involved)
     */
    public void conversationChanged(long seasonId, Collection<Long> playerUserIds, boolean commissioners) {
        Set<Long> users = playerUserIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        boolean alsoCommissioners = commissioners || users.size() < playerUserIds.size();
        streams.publish(seasonId, v -> users.contains(v.userId()) || (alsoCommissioners && v.commissioner()), "messages", new Changed());
    }

    /** Several conversations may have changed (e.g. offers cancelled after a card moved): tell everyone. */
    public void seasonChanged(long seasonId) {
        streams.publish(seasonId, v -> true, "messages", new Changed());
    }

    int openStreams(long seasonId) {
        return streams.openStreams(seasonId);
    }

    @PreDestroy
    void shutdown() {
        streams.shutdown();
    }
}
