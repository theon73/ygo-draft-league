package com.ygoleague.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscordMessagesTest {

    @Test
    void onlyDiscordWebhookUrlsAreAccepted() {
        assertTrue(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz_-0123"));
        assertTrue(DiscordWebhook.isWebhookUrl("https://canary.discordapp.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz"));
        assertFalse(DiscordWebhook.isWebhookUrl("http://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz"));
        assertFalse(DiscordWebhook.isWebhookUrl("https://discord.com.evil.io/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz"));
        assertFalse(DiscordWebhook.isWebhookUrl("https://evil.io/?https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz"));
        assertFalse(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz/../../users"));
        assertFalse(DiscordWebhook.isWebhookUrl(null));
    }

    @Test
    void messagesNeverPingAnyoneAndStayWithinDiscordsLimit() {
        Map<String, Object> body = DiscordWebhook.payload("Battle City", "@everyone " + "x".repeat(3000), List.of());
        assertEquals(Map.of("parse", List.of(), "users", List.of()), body.get("allowed_mentions"));
        assertEquals(DiscordWebhook.MAX_CONTENT, ((String) body.get("content")).length());
        assertEquals("Battle City", body.get("username"));
        assertEquals("Draft league", DiscordWebhook.payload(" ", "hi", List.of()).get("username"));
        // Only well-formed user ids can be pinged.
        assertEquals(Map.of("parse", List.of(), "users", List.of("284104569123456789")),
                DiscordWebhook.payload("x", "<@284104569123456789>", List.of("284104569123456789", "everyone", "1")).get("allowed_mentions"));
    }

    @Test
    void errorMessagesNeverCarryTheToken() {
        String error = "I/O error on POST request for \"https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz_-0123\": timed out";
        String redacted = DiscordWebhook.redact(error);
        assertFalse(redacted.contains("abcdefghij"), redacted);
        assertEquals("I/O error on POST request for \"https://discord.com/api/webhooks/123456789012345678/…\": timed out", redacted);
    }

    @Test
    void rateLimitsWaitWhatDiscordAsks() {
        assertEquals(1500, DiscordWebhook.retryAfterMillis("{\"message\":\"You are being rate limited.\",\"retry_after\":1.5}"));
        assertEquals(10_000, DiscordWebhook.retryAfterMillis("{\"retry_after\":120}"));
        assertEquals(1000, DiscordWebhook.retryAfterMillis("not json"));
    }

    @Test
    void namesCantInjectMarkdown() {
        assertEquals("\\*\\*Joey\\*\\* \\_W\\_", DiscordText.escape("**Joey** _W_"));
        assertEquals("Blue-Eyes White Dragon (Alt)", DiscordText.escape("Blue-Eyes White Dragon (Alt)"));
        assertEquals("\\[free cards\\](https://x.io)", DiscordText.escape("[free cards](https://x.io)"));
    }

    @Test
    void longNewsIsSplitUnderTheSeasonsName() {
        List<String> lines = Collections.nCopies(60, "#1 · **Someone** drafted **" + "A".repeat(40) + "**");
        List<String> messages = DraftAnnouncer.messages("S1", lines);
        assertTrue(messages.size() > 1);
        for (String m : messages) {
            assertTrue(m.length() <= DiscordWebhook.MAX_CONTENT, "length " + m.length());
            assertTrue(m.startsWith("**S1 draft**"));
        }
        assertEquals(60, messages.stream().mapToLong(m -> m.lines().filter(l -> l.startsWith("#1")).count()).sum());
    }
}
