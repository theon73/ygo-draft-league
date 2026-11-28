package com.ygoleague.live;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SseStreamsTest {

    private final SseStreams<String> streams = new SseStreams<>("test");

    @AfterEach
    void stop() {
        streams.shutdown();
    }

    /** By the time it runs the change is saved: a failing live update must not fail the request (YGO-35). */
    @Test
    void aFailingSendNeverEscapesPublish() {
        streams.open(1, "carol", "messages", "hello");
        assertEquals(1, streams.openStreams(1));
        assertDoesNotThrow(() -> streams.publish(1, tag -> { throw new IllegalArgumentException("boom"); }, "messages", "x"));
    }
}
