package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.Locale;
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
    /** The boss that ends floors 0 to 6, by floor number; floor 7 has several. */
    private static final String[] BOSS_BY_FLOOR = {"Watcher", "Bonzo", "Scarf", "Professor", "Thorn", "Livid", "Sadan"};

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
        if (floor == 7) {
            return masterMode ? boss.equals("Wither King")
                : boss.equals("Necron") || boss.equals("Maxor, Storm, Goldor, and Necron");
        }
        return floor >= 0 && floor < BOSS_BY_FLOOR.length && boss.equals(BOSS_BY_FLOOR[floor]);
    }

    /** The floor whose boss a "[BOSS]" speaker name belongs to; any other name is a floor 7 boss. */
    static int floorForBoss(String bossName) {
        String lower = bossName.toLowerCase(Locale.ROOT);
        for (int floor = 1; floor < BOSS_BY_FLOOR.length; floor++) {
            if (lower.contains(BOSS_BY_FLOOR[floor].toLowerCase(Locale.ROOT))) return floor;
        }
        return 7;
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
        return HypixelLocation.clean(message);
    }
}
