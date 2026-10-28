package com.github.beng420.kung.util;

import com.github.beng420.kung.feature.dungeon.KungServerSession;
import com.github.beng420.kung.skyblock.SkyBlockMayorTracker;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class CatacombsAverageCalculator {
    private static final long POST_50_XP = 200_000_000L;
    private static final DungeonClass[] CLASSES = DungeonClass.values();
    private static final double[] HECATOMB_BONUSES = {
        0.0, 0.0056, 0.0072, 0.0088, 0.0104, 0.012, 0.0136, 0.0152, 0.0168, 0.0184, 0.02
    };
    private static final long[] CATACOMBS_XP = {
        0L, 50L, 125L, 235L, 395L, 625L, 955L, 1_425L, 2_095L, 3_045L, 4_385L, 6_275L,
        8_940L, 12_700L, 17_960L, 25_340L, 35_640L, 50_040L, 70_040L, 97_640L, 135_640L,
        188_140L, 259_640L, 356_640L, 488_640L, 668_640L, 911_640L, 1_239_640L, 1_684_640L,
        2_284_640L, 3_084_640L, 4_149_640L, 5_559_640L, 7_459_640L, 9_959_640L, 13_259_640L,
        17_559_640L, 23_159_640L, 30_359_640L, 39_559_640L, 51_559_640L, 66_559_640L,
        85_559_640L, 109_559_640L, 139_559_640L, 177_559_640L, 225_559_640L, 285_559_640L,
        360_559_640L, 453_559_640L, 569_809_640L
    };

    /**
     * Your own XP comes from the loader, merged with or answered from your saved data either way.
     * {@code useLastRunXp} additionally swaps the preset class XP per run for your last run's; chat replies
     * keep the M7 max-bonus preset so they don't depend on whichever floor you ran last.
     * {@code level} is the target of every goal. {@code dungeonClass} narrows two goals: with
     * {@link Goal#CLASS_AVERAGE} it is the only class played, with {@link Goal#CLASS_LEVEL} the class to level.
     */
    public CompletableFuture<Result> calculateAsync(String username, Goal goal, int level, DungeonClass dungeonClass,
                                                    boolean useLastRunXp) {
        String normalized = username == null ? "" : username.trim();
        if (!validUsername(normalized)) {
            return CompletableFuture.completedFuture(Result.error("invalid username"));
        }

        return HypixelSkyBlockProfileClient.INSTANCE.loadCalculatorPlayer(normalized)
            .thenApply(result -> {
                if (!result.success()) {
                    return Result.error(externalProfileError(result.error()), "profileError=" + result.error());
                }
                Result calculated = calculate(result.player(), "source=" + result.source() + " secrets=" + result.secretsFound(),
                    normalized, goal, level, dungeonClass, useLastRunXp);
                return result.local() && calculated.success()
                    ? Result.ok(calculated.message() + " (local)", calculated.debugDetails())
                    : calculated;
            });
    }

    public Result calculate(PlayerData player) {
        return calculate(player, "", "", Goal.CLASS_AVERAGE, 50, null, true);
    }

    Result calculate(PlayerData player, String requestDebug, String requestedName, Goal goal, int level,
                     DungeonClass dungeonClass, boolean useLastRunXp) {
        if (player == null || player.profiles().isEmpty()) {
            return Result.error("no SkyBlock profile found");
        }
        ProfileData profile = player.selectedProfile();
        if (!profile.stats().available() && profile.totalDungeonXp() <= 0) {
            return Result.error("selected profile has no Dungeon stats", requestDebug
                + " profile=" + profile.cuteName());
        }
        EnumMap<DungeonClass, Double> classXpPerRun = classXpPerRun(profile.classPerks());
        Map<DungeonClass, Double> lastRunXp = useLastRunXp
            ? CatacombsRecentXpTracker.INSTANCE.localClassXpPerRun(requestedName)
            : Map.of();
        classXpPerRun.putAll(lastRunXp);
        Breakdown breakdown = calculateBreakdown(profile.classXp(), classXpPerRun, level);
        long cataXpPerRun = catacombsXpPerRun();
        long cataRuns = runsToCatacombs(profile.cataXp(), level, cataXpPerRun);
        String message = switch (goal) {
            case CATACOMBS -> catacombsSummaryLine(player.name(), "M7", level, cataRuns);
            case CLASS_LEVEL -> classLevelSummaryLine(player.name(), "M7", dungeonClass, level, profile.classXp(dungeonClass),
                runsToClassLevel(profile.classXp(dungeonClass), classXpPerRun.get(dungeonClass), level));
            case CLASS_AVERAGE -> dungeonClass == null
                ? classAverageSummaryLine(player.name(), "M7", level, breakdown)
                : oneClassSummaryLine(player.name(), "M7", dungeonClass, level,
                    runsWithOnly(dungeonClass, profile.classXp(), classXpPerRun, level));
        };
        return Result.ok(message, resultDebug(player, profile, breakdown, cataRuns, cataXpPerRun, classXpPerRun,
            requestDebug + " level=" + level + (dungeonClass == null ? "" : " class=" + dungeonClass.id()),
            useLastRunXp ? "lastRunXp=" + lastRunXp : "lastRunXp=disabled", goal));
    }

    private EnumMap<DungeonClass, Double> classXpPerRun(Map<DungeonClass, Integer> classPerks) {
        return classXpPerRun(classPerks, 300_000.0, HECATOMB_BONUSES[10], 0.06, 0.20, 0,
            SkyBlockMayorTracker.INSTANCE.catacombsXpMultiplier());
    }

    public static EnumMap<DungeonClass, Double> classXpPerRun(
        Map<DungeonClass, Integer> classPerks, double baseXp, double hecatomb,
        double scarfBonus, double graduateBonus, double globalBonus, double mayorMultiplier
    ) {
        EnumMap<DungeonClass, Double> classXpPerRun = new EnumMap<>(DungeonClass.class);
        for (DungeonClass dungeonClass : CLASSES) {
            double perk = Math.clamp(classPerks.getOrDefault(dungeonClass, 0), 0, 5) * 0.02;
            // Adjectils caps the class bonus at Derpy's 50%, including Aura.
            classXpPerRun.put(dungeonClass, baseXp * (1.0 + 2.0 * hecatomb + perk + scarfBonus + graduateBonus + globalBonus)
                * Math.min(1.5, mayorMultiplier));
        }
        return classXpPerRun;
    }

    private long catacombsXpPerRun() {
        return catacombsXpPerRun(300_000.0, true, HECATOMB_BONUSES[10], 10, 0,
            SkyBlockMayorTracker.INSTANCE.catacombsXpMultiplier());
    }

    public static long catacombsXpPerRun(double baseXp, boolean expertRing, double hecatomb,
                                         int explorerLevel, double globalBonus, double mayorMultiplier) {
        int maxRuns = baseXp >= 15_000.0 ? 26 : baseXp == 4_880.0 ? 51 : 76;
        double cataMultiplier;
        if (expertRing && mayorMultiplier > 1.0) {
            cataMultiplier = 0.95 + (mayorMultiplier - 1.0 + (maxRuns - 1) / 100.0)
                + 0.1 + hecatomb + (maxRuns - 1) * (0.024 + hecatomb / 50.0);
        } else if (expertRing) {
            cataMultiplier = 0.95 + 0.1 + hecatomb + (maxRuns - 1) * (0.024 + hecatomb / 50.0);
        } else {
            cataMultiplier = 0.95 + hecatomb + (maxRuns - 1) * (0.022 + hecatomb / 50.0);
        }
        // Catacombs Explorer is a separate attribute, not the Expert Ring bonus.
        // Match Adjectils' 300-score/max-completion projection, adding it exactly once.
        cataMultiplier += Math.clamp(explorerLevel, 0, 10) * 0.01;
        // Decimal bonuses can land a few ulps above an integer; don't invent one XP.
        return (long) Math.ceil(baseXp * cataMultiplier * (1.0 + globalBonus) - 0.0000001);
    }

    public static long runsToCatacombs(double currentXp, int targetLevel, long xpPerRun) {
        double remaining = xpForLevel(targetLevel) - currentXp;
        return remaining <= 0.0 ? 0L : xpPerRun <= 0 ? Long.MAX_VALUE : (long) Math.ceil(remaining / xpPerRun);
    }

    public static Breakdown calculateBreakdown(
        Map<DungeonClass, Double> currentClassXp,
        Map<DungeonClass, Double> classXpPerRun
    ) {
        return calculateBreakdown(currentClassXp, classXpPerRun, 50);
    }

    public static Breakdown calculateBreakdown(
        Map<DungeonClass, Double> currentClassXp,
        Map<DungeonClass, Double> classXpPerRun,
        int targetLevel
    ) {
        long targetXp = xpForLevel(targetLevel);
        EnumMap<DungeonClass, Double> remaining = new EnumMap<>(DungeonClass.class);
        EnumMap<DungeonClass, Long> perClass = new EnumMap<>(DungeonClass.class);
        double totalRemaining = 0.0;
        for (DungeonClass dungeonClass : CLASSES) {
            double xp = Math.max(0.0, targetXp - currentClassXp.getOrDefault(dungeonClass, 0.0));
            remaining.put(dungeonClass, xp);
            perClass.put(dungeonClass, 0L);
            totalRemaining += xp;
        }
        if (totalRemaining <= 0.0) {
            return new Breakdown(0L, perClass);
        }

        for (DungeonClass dungeonClass : CLASSES) {
            if (remaining.get(dungeonClass) > 0.0 && classXpPerRun.getOrDefault(dungeonClass, 0.0) <= 0.0) {
                for (DungeonClass classKey : CLASSES) {
                    perClass.put(classKey, remaining.get(classKey) > 0.0 ? Long.MAX_VALUE : 0L);
                }
                return new Breakdown(Long.MAX_VALUE, perClass);
            }
        }

        long low = 0L;
        long high = 0L;
        for (DungeonClass dungeonClass : CLASSES) {
            double xpPerRun = classXpPerRun.getOrDefault(dungeonClass, 0.0);
            if (xpPerRun > 0.0) {
                high += (long) Math.ceil(remaining.get(dungeonClass) / xpPerRun);
            }
        }
        high = Math.max(1L, high);
        while (requiredRunCount(remaining, classXpPerRun, high) > high && high < Long.MAX_VALUE / 2L) {
            high *= 2L;
        }

        while (low < high) {
            long mid = low + (high - low) / 2L;
            if (requiredRunCount(remaining, classXpPerRun, mid) <= mid) {
                high = mid;
            } else {
                low = mid + 1L;
            }
        }

        perClass = requiredRunsForTotal(remaining, classXpPerRun, low);
        long assigned = 0L;
        for (long runs : perClass.values()) {
            assigned += runs;
        }
        while (assigned < low) {
            DungeonClass target = extraRunTarget(remaining, classXpPerRun, perClass);
            perClass.put(target, perClass.get(target) + 1L);
            assigned++;
        }
        return new Breakdown(low, perClass);
    }

    private static long requiredRunCount(
        Map<DungeonClass, Double> remaining,
        Map<DungeonClass, Double> classXpPerRun,
        long totalRuns
    ) {
        long total = 0L;
        for (long runs : requiredRunsForTotal(remaining, classXpPerRun, totalRuns).values()) {
            total += runs;
        }
        return total;
    }

    private static EnumMap<DungeonClass, Long> requiredRunsForTotal(
        Map<DungeonClass, Double> remaining,
        Map<DungeonClass, Double> classXpPerRun,
        long totalRuns
    ) {
        EnumMap<DungeonClass, Long> runs = new EnumMap<>(DungeonClass.class);
        for (DungeonClass dungeonClass : CLASSES) {
            double xpPerRun = classXpPerRun.getOrDefault(dungeonClass, 0.0);
            double xpLeftAfterTeamBonus = remaining.getOrDefault(dungeonClass, 0.0) - xpPerRun * totalRuns / 4.0;
            long selectedRuns = xpLeftAfterTeamBonus <= 0.0001 || xpPerRun <= 0.0
                ? 0L
                : (long) Math.ceil(xpLeftAfterTeamBonus / (xpPerRun * 0.75) - 0.0000001);
            runs.put(dungeonClass, Math.max(0L, selectedRuns));
        }
        return runs;
    }

    private static DungeonClass extraRunTarget(
        Map<DungeonClass, Double> remaining,
        Map<DungeonClass, Double> classXpPerRun,
        Map<DungeonClass, Long> perClass
    ) {
        boolean hasRequiredClass = false;
        for (long runs : perClass.values()) {
            if (runs > 0L) {
                hasRequiredClass = true;
                break;
            }
        }

        DungeonClass best = CLASSES[0];
        double bestScore = Double.NEGATIVE_INFINITY;
        for (DungeonClass dungeonClass : CLASSES) {
            if (hasRequiredClass && perClass.getOrDefault(dungeonClass, 0L) <= 0L) {
                continue;
            }
            double xpPerRun = classXpPerRun.getOrDefault(dungeonClass, 1.0);
            double score = remaining.getOrDefault(dungeonClass, 0.0) / Math.max(1.0, xpPerRun);
            if (score > bestScore) {
                bestScore = score;
                best = dungeonClass;
            }
        }
        return best;
    }

    public static String classAverageSummaryLine(String playerName, String floor, Breakdown breakdown) {
        return classAverageSummaryLine(playerName, floor, 50, breakdown);
    }

    public static String classAverageSummaryLine(String playerName, String floor, int targetLevel, Breakdown breakdown) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (DungeonClass dungeonClass : CLASSES) {
            parts.add(dungeonClass.label().substring(0, 4) + " " + format(breakdown.perClass(dungeonClass)));
        }
        return playerName + " is " + format(breakdown.total()) + " " + floor
            + " runs away from ca" + targetLevel + " (" + String.join(" | ", parts) + ")";
    }

    public static String catacombsSummaryLine(String playerName, String floor, int targetLevel, long runs) {
        return playerName + " is " + format(runs) + " " + floor + " runs away from c" + targetLevel;
    }

    /**
     * Runs until every class reaches {@code targetLevel} when only {@code played} is played: it earns a run's
     * full class XP, every other class the 25% team share. The last class to get there decides.
     */
    public static long runsWithOnly(
        DungeonClass played,
        Map<DungeonClass, Double> currentClassXp,
        Map<DungeonClass, Double> classXpPerRun,
        int targetLevel
    ) {
        long targetXp = xpForLevel(targetLevel);
        long runs = 0L;
        for (DungeonClass dungeonClass : CLASSES) {
            double share = classXpPerRun.getOrDefault(dungeonClass, 0.0) * (dungeonClass == played ? 1.0 : 0.25);
            runs = Math.max(runs, ceilRuns(targetXp - currentClassXp.getOrDefault(dungeonClass, 0.0), share));
        }
        return runs;
    }

    /** Runs played as that class until it reaches the level; the other classes' share does not matter. */
    public static long runsToClassLevel(double currentClassXp, double classXpPerRun, int targetLevel) {
        return ceilRuns(xpForLevel(targetLevel) - currentClassXp, classXpPerRun);
    }

    private static long ceilRuns(double remainingXp, double xpPerRun) {
        return remainingXp <= 0.0 ? 0L : xpPerRun <= 0.0 ? Long.MAX_VALUE : (long) Math.ceil(remainingXp / xpPerRun - 0.0000001);
    }

    public static String oneClassSummaryLine(String playerName, String floor, DungeonClass played, int targetLevel, long runs) {
        return runs == 0L
            ? playerName + " already has ca" + targetLevel
            : playerName + " is " + format(runs) + " " + floor + " runs away from ca" + targetLevel + " with only "
                + played.label();
    }

    public static String classLevelSummaryLine(String playerName, String floor, DungeonClass dungeonClass, int targetLevel,
                                               double classXp, long runs) {
        // Floored, so 49.99 doesn't read as 50.0.
        double level = Math.floor(levelFromXp(classXp) * 10.0) / 10.0;
        return runs == 0L
            ? playerName + "'s " + dungeonClass.label() + " is already " + targetLevel
            : playerName + " is " + format(runs) + " " + floor + " runs away from " + dungeonClass.label()
                + " " + targetLevel + " (now " + String.format(Locale.ROOT, "%.1f", level) + ")";
    }

    private static String resultDebug(
        PlayerData player,
        ProfileData profile,
        Breakdown breakdown,
        long cataRuns,
        long cataXpPerRun,
        Map<DungeonClass, Double> classXpPerRun,
        String requestDebug,
        String adjustmentDebug,
        Goal goal
    ) {
        StringBuilder builder = new StringBuilder();
        if (requestDebug != null && !requestDebug.isBlank()) {
            builder.append(requestDebug).append(' ');
        }
        if (adjustmentDebug != null && !adjustmentDebug.isBlank()) {
            builder.append(adjustmentDebug).append(' ');
        }
        builder.append("goal=").append(goal.id())
            .append(" at=").append(Instant.now())
            .append(" player=").append(player.name())
            .append(" profile=").append(profile.cuteName())
            .append(" selected=").append(profile.selected())
            .append(" profileId=").append(profile.id().isBlank() ? "-" : profile.id())
            .append(" cataXp=").append(Math.round(profile.cataXp()))
            .append(" classXp=");
        for (DungeonClass dungeonClass : CLASSES) {
            builder.append(dungeonClass.id()).append(':').append(Math.round(profile.classXp().getOrDefault(dungeonClass, 0.0))).append(',');
        }
        builder.append(" perks=");
        for (DungeonClass dungeonClass : CLASSES) {
            builder.append(dungeonClass.id()).append(':').append(profile.classPerks().getOrDefault(dungeonClass, 0)).append(',');
        }
        double averageClassXpPerRun = 0.0;
        for (DungeonClass dungeonClass : CLASSES) {
            averageClassXpPerRun += classXpPerRun.getOrDefault(dungeonClass, 0.0);
        }
        averageClassXpPerRun /= CLASSES.length;
        builder.append(" mayor=").append(SkyBlockMayorTracker.INSTANCE.catacombsXpBoostLabel())
            .append(" mayorMultiplier=").append(String.format(Locale.ROOT, "%.3f", SkyBlockMayorTracker.INSTANCE.catacombsXpMultiplier()))
            .append(" cataXpPerRun=").append(cataXpPerRun)
            .append(" cataRuns=").append(cataRuns)
            .append(" avgClassXpPerRun=").append(Math.round(averageClassXpPerRun))
            .append(" totalRuns=").append(breakdown.total());
        return builder.toString();
    }

    public static long xpForLevel(int level) {
        return level <= 50 ? CATACOMBS_XP[Math.max(0, level)] : CATACOMBS_XP[50] + (level - 50L) * POST_50_XP;
    }

    public static double levelFromXp(double xp) {
        if (xp >= xpForLevel(50)) return 50.0 + (xp - xpForLevel(50)) / POST_50_XP;
        for (int level = 0; level < 50; level++) {
            long current = xpForLevel(level);
            long next = xpForLevel(level + 1);
            if (xp < next) return level + Math.max(0.0, xp - current) / (next - current);
        }
        return 50.0;
    }

    private static String format(long value) {
        return value == Long.MAX_VALUE ? "infinite" : Long.toString(value);
    }

    private static boolean validUsername(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{3,16}");
    }

    static String externalProfileError(String rawError) {
        // A failed own key plus a failed server is one combined trace error; the chat message follows the server's part.
        String error = HypixelSkyBlockProfileClient.lastError(rawError);
        if (HypixelSkyBlockProfileClient.NO_SOURCE.equals(error)) {
            return error;
        }
        if (KungServerSession.NOT_ALLOWED.equals(error)) {
            return "not allowed on the Kung server; set your own Hypixel API key";
        }
        if (KungServerSession.needsRestart(error)) {
            // The asker can't fix it and the exception text isn't theirs to read: just say whose session it is.
            return "Kung sign-in failed (Mojang session invalid, the Kung user has to restart the game)";
        }
        String lower = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (lower.contains("http 403")) {
            return "CA data service blocked the request";
        }
        if (lower.contains("http 429")) {
            return "CA data service is rate limited";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "CA data service timed out";
        }
        return "CA data service unavailable";
    }

    public enum DungeonClass {
        ARCHER("archer", "Archer", "Toxophilite", 0xFFFF6B6B),
        BERSERK("berserk", "Berserk", "Unbridled Rage", 0xFFFFB86C),
        HEALER("healer", "Healer", "Heart of Gold", 0xFFFF66FF),
        MAGE("mage", "Mage", "Cold Efficiency", 0xFF66E7FF),
        TANK("tank", "Tank", "Diamond in the Rough", 0xFF8DFF73);

        private final String id;
        private final String label;
        private final String perkName;
        private final int color;

        DungeonClass(String id, String label, String perkName, int color) {
            this.id = id;
            this.label = label;
            this.perkName = perkName;
            this.color = color;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public String perkName() {
            return perkName;
        }

        public int color() {
            return color;
        }
    }

    public enum Goal {
        CLASS_AVERAGE("ca"),
        CATACOMBS("c"),
        CLASS_LEVEL("class");

        private final String id;

        Goal(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Result(boolean success, String message, String debugDetails) {
        static Result ok(String message) {
            return ok(message, "");
        }

        static Result ok(String message, String debugDetails) {
            return new Result(true, message, debugDetails);
        }

        static Result error(String message) {
            return error(message, "");
        }

        static Result error(String message, String debugDetails) {
            return new Result(false, message, debugDetails);
        }
    }

    public record PlayerData(String name, List<ProfileData> profiles, int selectedIndex) {
        public PlayerData {
            profiles = List.copyOf(profiles);
        }

        public ProfileData selectedProfile() {
            return profiles.get(Math.clamp(selectedIndex, 0, profiles.size() - 1));
        }
    }

    public record ProfileData(
        String id,
        String cuteName,
        boolean selected,
        double cataXp,
        Map<DungeonClass, Double> classXp,
        Map<DungeonClass, Integer> classPerks,
        DungeonStats stats
    ) {
        public ProfileData(String id, String cuteName, boolean selected, double cataXp,
                           Map<DungeonClass, Double> classXp, Map<DungeonClass, Integer> classPerks) {
            this(id, cuteName, selected, cataXp, classXp, classPerks, DungeonStats.EMPTY);
        }

        public ProfileData {
            classXp = Map.copyOf(classXp);
            classPerks = Map.copyOf(classPerks);
        }

        public double classXp(DungeonClass dungeonClass) {
            return classXp.getOrDefault(dungeonClass, 0.0);
        }

        public int classPerk(DungeonClass dungeonClass) {
            return classPerks.getOrDefault(dungeonClass, 0);
        }

        double totalDungeonXp() {
            double total = cataXp;
            for (double xp : classXp.values()) {
                total += xp;
            }
            return total;
        }
    }

    public record DungeonStats(boolean available, DungeonClass selectedClass, long secrets, int dailyRuns, int journals,
                               FloorStats catacombs, FloorStats master, int explorerLevel) {
        private static final DungeonStats EMPTY = new DungeonStats(false, null, -1, 0, 0, FloorStats.EMPTY, FloorStats.EMPTY, -1);
    }

    public record FloorStats(Map<String, Integer> bestScore, Map<String, Integer> completions,
                             Map<String, Integer> sPlus, Map<String, Integer> s) {
        private static final FloorStats EMPTY = new FloorStats(Map.of(), Map.of(), Map.of(), Map.of());

        public int value(Map<String, Integer> values, String floor) {
            return values.getOrDefault(floor, 0);
        }

        public long totalCompletions() {
            if (completions.containsKey("total")) return completions.get("total");
            return completions.entrySet().stream().filter(entry -> entry.getKey().matches("[0-7]"))
                .mapToLong(Map.Entry::getValue).sum();
        }
    }

    public record Breakdown(long total, Map<DungeonClass, Long> perClass) {
        public long perClass(DungeonClass dungeonClass) {
            return perClass.getOrDefault(dungeonClass, 0L);
        }
    }
}
