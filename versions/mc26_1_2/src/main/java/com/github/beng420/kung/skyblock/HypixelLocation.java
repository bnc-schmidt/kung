package com.github.beng420.kung.skyblock;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Parses actual location fields, never location mentions in social or combat text. */
public record HypixelLocation(Kind kind, String name) {
    public enum Kind { UNKNOWN, CATACOMBS, DUNGEON_HUB, KUUDRA, SKYBLOCK }
    public static final HypixelLocation UNKNOWN = new HypixelLocation(Kind.UNKNOWN, "");
    private static final Pattern FIELD = Pattern.compile("^(?:Area|Location|Dungeon)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATACOMBS = Pattern.compile("^(?:The )?Catacombs(?:\\s*\\((?:E|[FM]?[0-7])\\))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER = Pattern.compile("\\b(?:mini\\d{1,5}|mega\\d{1,5}|m\\d{1,5})[a-z]{0,4}\\b", Pattern.CASE_INSENSITIVE);
    // String.replaceAll, matches and split compile their regex on every call; these run per packet and chat line.
    private static final Pattern KUUDRA_AREA = Pattern.compile("kuudra's hollow(?:\\s*\\(t[1-5]\\))?");
    private static final Pattern SERVER_LINE = Pattern.compile(
        "^(?:\\d{1,2}/\\d{1,2}/\\d{2,4}.*|(?:Server|Server ID):.*|(?:mini|mega|m)\\d+[a-z]*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAT_CODE = Pattern.compile("§.");
    private static final Pattern BLANKS = Pattern.compile("\\s+");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]]+]");
    private static final Pattern RANK_DOT = Pattern.compile("[\\u25cf\\u25cb]");
    private static final Pattern NOT_NAME_TEXT = Pattern.compile("[^A-Za-z0-9_ ]");
    /** A Minecraft name inside a longer text. */
    public static final Pattern PLAYER_NAME = Pattern.compile("\\b[A-Za-z0-9_]{3,16}\\b");

    public static HypixelLocation parse(List<String> lines) {
        HypixelLocation result = UNKNOWN;
        for (String raw : lines) {
            String line = clean(raw);
            Matcher field = FIELD.matcher(line);
            boolean locationField = field.matches() || line.startsWith("⏣") || line.startsWith("ф");
            String name = field.matches() ? field.group(1).trim()
                : locationField ? line.substring(1).trim() : line;
            HypixelLocation candidate = named(name, locationField);
            // Explicit non-dungeon areas win over stale floor lines while the board updates.
            if (candidate.kind == Kind.KUUDRA || candidate.kind == Kind.DUNGEON_HUB) return candidate;
            if (candidate.kind == Kind.SKYBLOCK && locationField) return candidate;
            if (candidate.kind != Kind.UNKNOWN) result = candidate;
        }
        return result;
    }

    private static HypixelLocation named(String name, boolean locationField) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (CATACOMBS.matcher(name).matches() || HypixelDungeonFloor.fromLine(name).known()) {
            return new HypixelLocation(Kind.CATACOMBS, name);
        }
        if (lower.equals("dungeon hub")) return new HypixelLocation(Kind.DUNGEON_HUB, "Dungeon Hub");
        if (lower.equals("kuudra") || KUUDRA_AREA.matcher(lower).matches()) return new HypixelLocation(Kind.KUUDRA, "Kuudra's Hollow");
        if (locationField && !name.isBlank() && !lower.equals("unknown")) return new HypixelLocation(Kind.SKYBLOCK, name);
        return UNKNOWN;
    }

    public static String serverId(List<String> lines) {
        for (String raw : lines) {
            String line = clean(raw);
            if (!SERVER_LINE.matcher(line).matches()) continue;
            Matcher matcher = SERVER.matcher(line);
            if (matcher.find()) return matcher.group().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    public static boolean isPlayerName(String name) {
        return name != null && PLAYER_NAME.matcher(name).matches();
    }

    /** The name as typed, trimmed; "" for null. */
    public static String trimmedName(String name) {
        return name == null ? "" : name.trim();
    }

    public static String clean(String text) {
        return collapseSpaces(stripFormatting(text));
    }

    /** Text without its "§x" formatting codes; "" for null. */
    public static String stripFormatting(String text) {
        return text == null ? "" : FORMAT_CODE.matcher(text).replaceAll("");
    }

    /** Runs of whitespace become one space, ends trimmed. */
    public static String collapseSpaces(String text) {
        return BLANKS.matcher(text).replaceAll(" ").trim();
    }

    /** Like {@code text.split("\\R")}, without compiling the pattern each time. */
    public static String[] lines(String text) {
        return LINE_BREAK.split(text);
    }

    /** Clean text with its [bracketed] ranks dropped: "[MVP+] Name" becomes "Name". */
    public static String withoutBrackets(String clean) {
        return collapseSpaces(BRACKETED.matcher(clean).replaceAll(" "));
    }

    /** Only the letters, digits, underscores and spaces of a player list line, so names can be scanned out of it. */
    static String nameText(String line) {
        String text = BRACKETED.matcher(line).replaceAll(" ");
        return NOT_NAME_TEXT.matcher(RANK_DOT.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    public static boolean isHypixelAddress(String address) {
        if (address == null || address.isBlank()) return false;
        String host = ServerAddress.parseString(address).getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }

    /** Whether the client is connected to a Hypixel server. */
    public static boolean onHypixel(Minecraft client) {
        var server = client.getCurrentServer();
        return server != null && isHypixelAddress(server.ip);
    }
}
