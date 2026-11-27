package com.ygoleague.notify;

/** Text for Discord messages. */
public final class DiscordText {

    private DiscordText() {}

    /**
     * Escapes Discord markdown in user-chosen text (names), so "*" or "_" shows as itself and a
     * name can't become a masked link ("[text](url)", which webhooks render). Names never start a
     * line in our messages, so line-start syntax (headers, lists, quotes) needs no escaping.
     */
    public static String escape(String text) {
        if (text == null) return "";
        return text.replaceAll("([\\\\*_~`|\\[\\]])", "\\\\$1");
    }
}
