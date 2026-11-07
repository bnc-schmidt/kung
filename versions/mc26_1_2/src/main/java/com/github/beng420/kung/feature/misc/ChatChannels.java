package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kinds of chat line Chat Channels can hide, the channels Hypixel sends to, and the pure rules that read both from
 * chat. A kind is only recognised at the very start of the line, after an optional clock stamp, so a player cannot
 * fake one in public chat: their line starts with their own rank and name. Adding a kind is one more constant with
 * its pattern.
 */
public final class ChatChannels {
    /** Chat Patches puts "[12:34:56] " (its format is configurable, also "[3:04 PM]") in front of the text. */
    private static final Pattern CLOCK_STAMP =
        Pattern.compile("\\[(?:[0-9./-]+ )?\\d{1,2}:\\d{2}(?::\\d{2})?(?: ?[AaPp][Mm])?]\\s*");
    private static final Pattern SWITCH_NOTICE = Pattern.compile("You are now in the (.+) channel");
    /** Hypixel moves you to ALL on its own: when the party is gone, and when a chat conversation lapsed. */
    private static final Pattern MOVED_NOTICE = Pattern.compile("(?:You are not in a party and were moved to"
        + "|The conversation you were in expired and you have been moved back to) the ALL channel\\.?");
    private static final Pattern CHAT_COMMAND = Pattern.compile("/?chat\\s+(\\S+)\\s*", Pattern.CASE_INSENSITIVE);
    private static final String ALREADY_NOTICE = "You're already in this channel!";
    /** Every line of a kind starts with one of these; most lines do not, and skip the patterns. */
    private static final String FIRST_LETTERS = "GOPCFT";

    public enum Channel {
        GUILD("Guild", "Guild and Officer chat, and the joined and left lines of members.",
            "(?:Guild|Officer) > "),
        PARTY("Party", "Party chat.", "Party > "),
        COOP("Co-op", "Co-op chat.", "Co-op > "),
        /**
         * "From [MVP+] Name: text" and "To [MVP+] Name: text"; a player without a rank has none. SkyBlock's own
         * "From stash: Coal" has the same shape, so that name is no player.
         */
        DIRECT("DMs", "Direct messages: to and from one player, the ones /msg and /r send.",
            "(?:From|To) (?:(?:\\[[^\\]]+] )+\\w{1,16}|(?!stash:)\\w{1,16}): ");

        private final String label;
        private final String description;
        private final Pattern start;

        Channel(String label, String description, String start) {
            this.label = label;
            this.description = description;
            this.start = Pattern.compile(start);
        }

        public String label() { return label; }

        public String description() { return description; }
    }

    /** The channel Hypixel sends your next message to, the one {@code /chat <argument>} sets. */
    public enum Send {
        ALL("All", "all", 0xFFFF55),
        PARTY("Party", "party", 0x7777FF),
        GUILD("Guild", "guild", 0x55D655),
        OFFICER("Officer", "officer", 0x00AAAA),
        // Hypixel's name for the co-op channel is unconfirmed; the notice is matched on "CO-OP" and "COOP".
        COOP("Co-op", "coop", 0x55FFFF);

        private final String label;
        private final String argument;
        private final int color;

        Send(String label, String argument, int color) {
            this.label = label;
            this.argument = argument;
            this.color = color;
        }

        public String label() { return label; }

        public int color() { return color; }

        /** The command, without the slash, that switches to this channel. */
        public String command() { return "chat " + argument; }

        /** The kind of line this channel's tab shows; All shows every kind (null), Officer lines are Guild lines. */
        public Channel channel() {
            return switch (this) {
                case ALL -> null;
                case PARTY -> Channel.PARTY;
                case GUILD, OFFICER -> Channel.GUILD;
                case COOP -> Channel.COOP;
            };
        }
    }

    /**
     * The chat conversation Hypixel opens with one player (a /msg without text): your messages go to them until it
     * lapses, {@code window} after the last message you sent. Hypixel then moves you to ALL with a notice; until that
     * arrives, the session counts as ended.
     */
    public record Conversation(String name, long window, long endsAt) {
        private static final Pattern OPENED = Pattern.compile(
            "Opened a chat conversation with (?:\\[[^\\]]+] )*(\\w{1,16}) for the next (\\d{1,3}) minutes?\\..*");
        private static final Pattern SENT = Pattern.compile("To (?:\\[[^\\]]+] )*(\\w{1,16}): .*");

        /** The conversation a notice opens, or null for any other line. */
        public static Conversation opened(String text, long now) {
            if (text == null) return null;
            Matcher notice = OPENED.matcher(HypixelLocation.clean(text));
            if (!notice.matches()) return null;
            long window = Long.parseLong(notice.group(2)) * 60_000L;
            return new Conversation(notice.group(1), window, now + window);
        }

        public boolean ended(long now) { return now >= endsAt; }

        /** This conversation after a line: your own message to its player, in time, starts the window again. */
        public Conversation after(String text, long now) {
            if (ended(now)) return this;
            Matcher sent = SENT.matcher(HypixelLocation.clean(text));
            return sent.matches() && sent.group(1).equalsIgnoreCase(name) ? new Conversation(name, window, now + window) : this;
        }

        /** The time left as "4:32", rounded up. */
        public String left(long now) {
            long seconds = (Math.max(0, endsAt - now) + 999) / 1000;
            return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        }
    }

    private ChatChannels() {}

    /** The kind of this line, or null for public chat and every other line, which are never hidden. */
    public static Channel classify(String text) {
        if (text == null) return null;
        String line = text.indexOf('\u00a7') >= 0 ? HypixelLocation.clean(text) : text;
        int length = line.length();
        int start = skipSpaces(line, 0);
        if (start < length && line.charAt(start) == '[') {
            Matcher stamp = CLOCK_STAMP.matcher(line).region(start, length);
            if (stamp.lookingAt()) start = skipSpaces(line, stamp.end());
        }
        if (start >= length || FIRST_LETTERS.indexOf(line.charAt(start)) < 0) return null;
        for (Channel channel : Channel.values()) {
            if (channel.start.matcher(line).region(start, length).lookingAt()) return channel;
        }
        return null;
    }

    private static int skipSpaces(String line, int from) {
        int index = from;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return index;
    }

    /** The channel a notice like "You are now in the PARTY channel" names, or null for any other line. */
    public static Send switchNotice(String text) {
        String line = HypixelLocation.clean(text);
        if (MOVED_NOTICE.matcher(line).matches()) return Send.ALL;
        Matcher notice = SWITCH_NOTICE.matcher(line);
        if (!notice.matches()) return null;
        String name = notice.group(1).toUpperCase(Locale.ROOT);
        for (Send send : Send.values()) {
            if (send != Send.COOP && send.name().equals(name)) return send;
        }
        return name.contains("CO-OP") || name.contains("COOP") ? Send.COOP : null;
    }

    /** Hypixel's answer to /chat for the channel you are in already; it does not say which channel that is. */
    public static boolean alreadyNotice(String text) { return ALREADY_NOTICE.equals(HypixelLocation.clean(text)); }

    /** The channel {@code /chat all|a|party|p|guild|g|officer|o|coop} (or /pchat) switches to, or null. */
    public static Send chatCommand(String command) {
        if (command == null) return null;
        String line = command.strip();
        if (line.equalsIgnoreCase("pchat") || line.equalsIgnoreCase("/pchat")) return Send.PARTY;
        Matcher chat = CHAT_COMMAND.matcher(line);
        if (!chat.matches()) return null;
        return switch (chat.group(1).toLowerCase(Locale.ROOT)) {
            case "all", "a" -> Send.ALL;
            case "party", "p" -> Send.PARTY;
            case "guild", "g" -> Send.GUILD;
            case "officer", "o" -> Send.OFFICER;
            case "coop", "co-op", "skyblock-coop" -> Send.COOP;
            default -> null;
        };
    }

    /** The channel saved under its enum name, All for a missing or unknown one. */
    public static Send storedSend(String name) {
        for (Send send : Send.values()) {
            if (send.name().equals(name)) return send;
        }
        return Send.ALL;
    }

    /** The channel a tab sends to: All (null) and the kinds that have a channel; DMs have none (null as well). */
    public static Send sendFor(Channel tab) {
        if (tab == null) return Send.ALL;
        return switch (tab) {
            case GUILD -> Send.GUILD;
            case PARTY -> Send.PARTY;
            case COOP -> Send.COOP;
            case DIRECT -> null;
        };
    }
}
