package com.github.beng420.kung.skyblock;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Floor metadata only; this never establishes that the client is in a dungeon. */
public record HypixelDungeonFloor(int floor, boolean masterMode) {
    public static final HypixelDungeonFloor UNKNOWN = new HypixelDungeonFloor(-1, false);
    private static final String FLOOR_TOKEN = "(?:VII|VI|IV|V|III|II|I|[1-7])";
    private static final Pattern FLOOR_WORD = Pattern.compile("^FLOOR\\s+");
    private static final Pattern FIELD =Pattern.compile("^(?:Area|Location|Dungeon)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOOR = Pattern.compile(
        "^(?:(Master Mode|MM)\\s+)?(?:The )?Catacombs(?:\\s*\\(([FM]?[0-7]|E)\\)|\\s*[-,]\\s*(Entrance|Floor\\s+" + FLOOR_TOKEN + "))$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENTRY = Pattern.compile(
        "^(?:-{3,}\\s*)?(?:\\[[A-Z0-9+]+]\\s+)?[A-Za-z0-9_]{1,16} entered "
            + "((?:(?:MM|Master Mode) )?The Catacombs, (?:Entrance|Floor " + FLOOR_TOKEN + "))!(?:\\s*-{3,})?$",
        Pattern.CASE_INSENSITIVE
    );

    public boolean known() {
        return floor >= 0 && floor <= 7;
    }

    public static HypixelDungeonFloor fromLines(List<String> lines) {
        if (lines == null) return UNKNOWN;
        HypixelDungeonFloor result = UNKNOWN;
        for (String line : lines) {
            HypixelDungeonFloor candidate = fromLine(line);
            if (!candidate.known()) continue;
            // A partially updated display must not choose arbitrarily between floors.
            if (result.known() && !result.equals(candidate)) return UNKNOWN;
            result = candidate;
        }
        return result;
    }

    public static HypixelDungeonFloor fromLine(String raw) {
        String line = HypixelLocation.clean(raw);
        Matcher field = FIELD.matcher(line);
        if (field.matches()) line = field.group(1).trim();
        if (line.startsWith("⏣") || line.startsWith("ф")) line = line.substring(1).trim();
        Matcher matcher = FLOOR.matcher(line);
        if (!matcher.matches()) return UNKNOWN;
        boolean master = matcher.group(1) != null;
        String token = matcher.group(2);
        if (token != null) {
            token = token.toUpperCase(Locale.ROOT);
            master |= token.startsWith("M");
            if (token.startsWith("M") || token.startsWith("F")) token = token.substring(1);
        } else {
            token = FLOOR_WORD.matcher(matcher.group(3).toUpperCase(Locale.ROOT)).replaceFirst("");
        }
        int floor = switch (token) {
            case "E", "ENTRANCE", "0" -> 0;
            case "I", "1" -> 1;
            case "II", "2" -> 2;
            case "III", "3" -> 3;
            case "IV", "4" -> 4;
            case "V", "5" -> 5;
            case "VI", "6" -> 6;
            case "VII", "7" -> 7;
            default -> -1;
        };
        return floor < 0 ? UNKNOWN : new HypixelDungeonFloor(floor, floor > 0 && master);
    }

    /** Caller must restrict this to server game messages and bind it to a later transfer. */
    public static HypixelDungeonFloor fromEntryMessage(String message) {
        Matcher matcher = ENTRY.matcher(HypixelLocation.clean(message));
        return matcher.matches() ? fromLine(matcher.group(1)) : UNKNOWN;
    }
}
