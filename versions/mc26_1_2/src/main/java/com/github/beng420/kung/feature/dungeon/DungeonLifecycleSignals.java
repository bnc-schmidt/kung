package com.github.beng420.kung.feature.dungeon;

import java.util.regex.Pattern;

public final class DungeonLifecycleSignals {
    private static final Pattern RUN_START = Pattern.compile("^Starting in 1 second\\.$");
    private static final Pattern RUN_FINISHED = Pattern.compile(
        "^\\s*(?:\\S\\s+)?Defeated\\s+(.+?)\\s+in\\s+0?([\\dhms ]+?)\\s*(?:\\(NEW RECORD!\\))?$"
    );
    // Same line as RUN_FINISHED, with the record marker kept: "☠ Defeated Necron in 06m 13s (NEW RECORD!)"
    private static final Pattern FLOOR_FINISH = Pattern.compile(
        "^\\s*(?:\\S\\s+)?Defeated\\s+(.+?)\\s+in\\s+0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$"
    );
    private static final Pattern TIME_PART = Pattern.compile("(\\d+)([hms])");
    private static final Pattern TEAM_SCORE = Pattern.compile("^Team Score: \\d{1,3} \\([SABCDUF][+]?\\)$");

    private DungeonLifecycleSignals() {
    }

    public static boolean isRunStart(String message) {
        return message != null && RUN_START.matcher(clean(message)).matches();
    }

    public static boolean isRunFinished(String message) {
        if (message == null) return false;
        String text = clean(message);
        return RUN_FINISHED.matcher(text).matches() || TEAM_SCORE.matcher(text).matches();
    }

    /** A score may also describe a wipe; only the matching victory banner confirms the boss. */
    public static boolean isVictoryForFloor(String message, int floor, boolean masterMode) {
        var victory = RUN_FINISHED.matcher(clean(message));
        if (!victory.matches()) return false;
        String boss = victory.group(1);
        if (boss.startsWith("The ")) boss = boss.substring(4);
        return switch (floor) {
            case 0 -> boss.equals("Watcher");
            case 1 -> boss.equals("Bonzo");
            case 2 -> boss.equals("Scarf");
            case 3 -> boss.equals("Professor");
            case 4 -> boss.equals("Thorn");
            case 5 -> boss.equals("Livid");
            case 6 -> boss.equals("Sadan");
            case 7 -> masterMode ? boss.equals("Wither King")
                : boss.equals("Necron") || boss.equals("Maxor, Storm, Goldor, and Necron");
            default -> false;
        };
    }

    public record FloorFinish(String boss, long millis, boolean newRecord) { }

    /** The boss and run time of a "Defeated ... in ..." line, or null for any other line. */
    public static FloorFinish parseFloorFinish(String message) {
        var finish = FLOOR_FINISH.matcher(clean(message));
        if (!finish.matches()) return null;
        long millis = 0L;
        boolean any = false;
        var parts = TIME_PART.matcher(finish.group(2));
        while (parts.find()) {
            any = true;
            millis += Long.parseLong(parts.group(1)) * switch (parts.group(2)) {
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
        }
        return any ? new FloorFinish(finish.group(1), millis, finish.group(3) != null) : null;
    }

    public static String clean(String message) {
        return message == null ? "" : message.replaceAll("\u00a7.", "").replaceAll("\\s+", " ").trim();
    }
}
