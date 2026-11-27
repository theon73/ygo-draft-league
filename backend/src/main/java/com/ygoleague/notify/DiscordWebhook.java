package com.ygoleague.notify;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts a message to a Discord webhook (YGO-21).
 *
 * <ul>
 *   <li>Only Discord webhook URLs are ever called ({@link #isWebhookUrl}), checked when saved and
 *       again here, and redirects aren't followed: the server can't be pointed at anything else.
 *   <li>Mentions are disabled in every message except for the user ids it names explicitly (members
 *       who opted in to pings), so a name like "@everyone" can't ping the channel.
 *   <li>The webhook's token never reaches the logs or error messages ({@link #redact}).
 *   <li>A 429 (rate limited) is retried after Discord's {@code retry_after}, a few times at most.
 * </ul>
 */
@Component
public class DiscordWebhook {

    /** discord.com (and its older and test domains) /api/webhooks/{id}/{token}. */
    static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?(?:discord|discordapp)\\.com/api/webhooks/\\d{5,25}/[A-Za-z0-9_-]{20,200}$");
    static final int MAX_CONTENT = 2000;
    private static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(DiscordWebhook.class);

    /** @param error Discord's (or the network's) complaint when not ok */
    public record Delivery(boolean ok, int status, String error) {}

    private final RestClient http;

    public DiscordWebhook() {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", "ygo-draft-league/1.0").build();
    }

    public static boolean isWebhookUrl(String url) {
        return url != null && WEBHOOK.matcher(url).matches();
    }

    /** The JSON body: the message, the name it's posted under, and mentions only for these user ids. */
    static Map<String, Object> payload(String username, String content, java.util.List<String> pingUserIds) {
        String name = username == null || username.isBlank() ? "Draft league" : username.strip();
        if (name.length() > 80) name = name.substring(0, 80);
        String text = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT - 1) + "…" : content;
        java.util.List<String> users = pingUserIds == null ? java.util.List.of()
                : pingUserIds.stream().filter(id -> id != null && id.matches("[0-9]{17,20}")).distinct().limit(100).toList();
        return Map.of("content", text, "username", name, "allowed_mentions", Map.of("parse", java.util.List.of(), "users", users));
    }

    public Delivery post(String url, String username, String content) {
        return post(url, username, content, java.util.List.of());
    }

    /** @param pingUserIds Discord user ids this message may ping (its {@code <@id>} mentions); everyone else stays quiet */
    public Delivery post(String url, String username, String content, java.util.List<String> pingUserIds) {
        if (!isWebhookUrl(url)) return new Delivery(false, 0, "Not a Discord webhook URL");
        for (int attempt = 1; ; attempt++) {
            try {
                Delivery d = http.post().uri(java.net.URI.create(url)).contentType(MediaType.APPLICATION_JSON)
                        .body(payload(username, content, pingUserIds))
                        .exchange((req, res) -> {
                            int status = res.getStatusCode().value();
                            if (status / 100 == 2) return new Delivery(true, status, null);
                            String body = new String(res.getBody().readNBytes(2000), java.nio.charset.StandardCharsets.UTF_8);
                            return new Delivery(false, status, body);
                        });
                if (d.ok() || d.status() != 429 || attempt >= MAX_ATTEMPTS) return d;
                Thread.sleep(retryAfterMillis(d.error()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Delivery(false, 0, "Interrupted");
            } catch (RuntimeException e) {
                // Network errors quote the request URL, and its last part is the webhook's secret token.
                log.debug("Discord webhook call failed: {}", redact(e.toString()));
                return new Delivery(false, 0, redact(e.getMessage()));
            }
        }
    }

    /** Hides webhook tokens in text (error messages quote the URL): ".../api/webhooks/123/abc" becomes ".../api/webhooks/123/…". */
    static String redact(String text) {
        return text == null ? null : text.replaceAll("(/api/webhooks/\\d+/)[A-Za-z0-9_-]+", "$1…");
    }

    /** Discord's 429 body has retry_after in seconds (fractional); wait that long, between 0.5 and 10 s. */
    static long retryAfterMillis(String body) {
        var m = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)").matcher(body == null ? "" : body);
        double seconds = m.find() ? Double.parseDouble(m.group(1)) : 1;
        return (long) (Math.min(10, Math.max(0.5, seconds)) * 1000);
    }
}
