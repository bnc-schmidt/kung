package com.github.beng420.kung.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.util.CatacombsAverageCalculator.Breakdown;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.ProfileData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;

public final class CatacombsCalculatorTest {
    private static final String UUID = "7b2c5b9705a24f478f2d725b9694bd4d";
    private static final double LEVEL_50_XP = 569_809_640;

    @Test
    public void selectedWatermelonCannotBeOverwrittenByLaterEmptyRaspberry() {
        var result = parse(fixture());
        assertTrue(result.error(), result.success());
        assertEquals(2, result.player().profiles().size());
        assertEquals("Watermelon", result.player().selectedProfile().cuteName());
        assertTrue(result.player().selectedProfile().stats().available());
        assertEquals(10, result.player().selectedProfile().stats().explorerLevel());
        assertFalse(result.player().profiles().get(1).stats().available());
        assertEquals(1_072_528_552.5049516, result.player().selectedProfile().cataXp(), 0.0001);
    }

    @Test
    public void noSelectedFlagFallsBackToDungeonProgressWithoutLastSave() {
        JsonObject root = fixture();
        root.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("selected", false);
        assertEquals("Watermelon", parse(root).player().selectedProfile().cuteName());
    }

    @Test
    public void actualSelectedEmptyProfileIsNotSilentlyReplacedByAnotherProfile() {
        JsonObject root = fixture();
        root.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("selected", false);
        root.getAsJsonArray("profiles").get(1).getAsJsonObject().addProperty("selected", true);
        ProfileData selected = parse(root).player().selectedProfile();
        assertEquals("Raspberry", selected.cuteName());
        assertFalse(selected.stats().available());
    }

    @Test
    public void rejectsFailedResponsesMissingMembersAndEmptyProfileLists() {
        JsonObject failed = fixture();
        failed.addProperty("success", false);
        assertFalse(parse(failed).success());
        assertFalse(HypixelSkyBlockProfileClient.parseProfiles("another-player", "Other",
            new JsonObject(), fixture()).success());
        assertFalse(parse(JsonParser.parseString("{\"success\":true,\"profiles\":[]}").getAsJsonObject()).success());
    }

    @Test
    public void matchesExactAdjectilsSaltyWhaleExampleWithoutMayor() {
        ProfileData profile = parse(fixture()).player().selectedProfile();
        var xp = selectedXp(profile.classPerks(), 1);
        Breakdown result = CatacombsAverageCalculator.calculateBreakdown(profile.classXp(), xp);
        assertEquals(420_000, xp.get(DungeonClass.ARCHER), 0.0001);
        assertEquals(1538, result.total());
        assertEquals(484, result.perClass(DungeonClass.ARCHER));
        assertEquals(514, result.perClass(DungeonClass.BERSERK));
        assertEquals(540, result.perClass(DungeonClass.MAGE));
        assertEquals(0, result.perClass(DungeonClass.HEALER));
        assertEquals(0, result.perClass(DungeonClass.TANK));
        assertCompletes(profile.classXp(), xp, result);
    }

    @Test
    public void serverStrippedProfilesParseExactlyLikeTheFullHypixelResponse() {
        var full = parse(fixture());
        var stripped = parse(fixture("salty-whale-profiles-stripped.json"));
        assertTrue(full.error(), full.success());
        assertEquals(2, stripped.player().profiles().size());
        for (int i = 0; i < 2; i++) {
            ProfileData a = full.player().profiles().get(i);
            ProfileData b = stripped.player().profiles().get(i);
            assertEquals(a.cataXp(), b.cataXp(), 0.0);
            assertEquals(a.classXp(), b.classXp());
            assertEquals(a.classPerks(), b.classPerks());
            assertEquals(a.stats(), b.stats());
        }
        assertEquals(full, stripped);
        ProfileData a = full.player().selectedProfile();
        ProfileData b = stripped.player().selectedProfile();
        assertEquals(CatacombsAverageCalculator.calculateBreakdown(a.classXp(), selectedXp(a.classPerks(), 1)),
            CatacombsAverageCalculator.calculateBreakdown(b.classXp(), selectedXp(b.classPerks(), 1)));
    }

    @Test
    public void derpyReducesRealProfileTo1026RunsAndIncludesPassiveClassXp() {
        ProfileData profile = parse(fixture()).player().selectedProfile();
        var xp = selectedXp(profile.classPerks(), 1.5);
        Breakdown result = CatacombsAverageCalculator.calculateBreakdown(profile.classXp(), xp);
        assertEquals(630_000, xp.get(DungeonClass.ARCHER), 0.0001);
        assertEquals(1026, result.total());
        assertEquals(0, result.perClass(DungeonClass.HEALER));
        assertEquals(0, result.perClass(DungeonClass.TANK));
        assertCompletes(profile.classXp(), xp, result);
    }

    @Test
    public void oldWrongProfileReproducesTheReported2436Runs() {
        var xp = selectedXp(Map.of(), 1.5);
        Breakdown result = CatacombsAverageCalculator.calculateBreakdown(Map.of(), xp);
        assertEquals(2436, result.total());
        assertEquals(488, result.perClass(DungeonClass.ARCHER));
        assertEquals(487, result.perClass(DungeonClass.HEALER));
    }

    @Test
    public void handlesCompletedClassesAndCapsAuraLikeAdjectils() {
        EnumMap<DungeonClass, Double> completed = new EnumMap<>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) completed.put(dungeonClass, LEVEL_50_XP);
        assertEquals(0, CatacombsAverageCalculator.calculateBreakdown(completed, Map.of()).total());
        assertEquals(Long.MAX_VALUE, CatacombsAverageCalculator.calculateBreakdown(Map.of(), Map.of()).total());
        assertEquals(selectedXp(Map.of(), 1.5), selectedXp(Map.of(), 1.59));
    }

    @Test
    public void customCatacombsTargetsUseOverflowXpAndRoundUpRemainingRuns() {
        assertEquals(177_559_640L, CatacombsAverageCalculator.xpForLevel(45));
        assertEquals(769_809_640L, CatacombsAverageCalculator.xpForLevel(51));
        assertEquals(1_169_809_640L, CatacombsAverageCalculator.xpForLevel(53));
        assertEquals(30_569_809_640L, CatacombsAverageCalculator.xpForLevel(200));
        assertEquals(0, CatacombsAverageCalculator.runsToCatacombs(LEVEL_50_XP, 45, 534_000));
        assertEquals(375, CatacombsAverageCalculator.runsToCatacombs(LEVEL_50_XP, 51, 534_000));
        assertEquals(1124, CatacombsAverageCalculator.runsToCatacombs(LEVEL_50_XP, 53, 534_000));
        assertEquals(0, CatacombsAverageCalculator.runsToCatacombs(1_169_809_640, 53, 0));
        assertEquals(Long.MAX_VALUE, CatacombsAverageCalculator.runsToCatacombs(LEVEL_50_XP, 53, 0));
    }

    @Test
    public void displayedLevelsRetainOverflowProgressBeyondFifty() {
        for (int level : new int[] {0, 1, 45, 49, 50, 51, 52, 53, 200}) {
            long xp = CatacombsAverageCalculator.xpForLevel(level);
            long next = CatacombsAverageCalculator.xpForLevel(level + 1);
            assertEquals(level, CatacombsAverageCalculator.levelFromXp(xp), 0.0);
            assertEquals(level + 0.25, CatacombsAverageCalculator.levelFromXp(xp + (next - xp) * 0.25), 0.000001);
        }
        assertEquals(0, CatacombsAverageCalculator.levelFromXp(-1), 0.0);
    }

    @Test
    public void ca51AndCa52RequireEveryClassToReachTheTargetWithPassiveXp() {
        var current = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        var xp = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            current.put(dungeonClass, LEVEL_50_XP);
            xp.put(dungeonClass, 400_000.0);
        }
        for (int target : new int[] {51, 52}) {
            Breakdown result = CatacombsAverageCalculator.calculateBreakdown(current, xp, target);
            assertEquals(1250L * (target - 50), result.total());
            for (DungeonClass dungeonClass : DungeonClass.values()) {
                assertEquals(250L * (target - 50), result.perClass(dungeonClass));
            }
            assertCompletes(current, xp, result, CatacombsAverageCalculator.xpForLevel(target));
            assertTrue(CatacombsAverageCalculator.classAverageSummaryLine("Beng114", "M7", target, result)
                .contains("runs away from ca" + target + " (Arch "));
        }
        // An uncapped average of 52 is not completion: the other four classes are still 50.
        current.put(DungeonClass.ARCHER, (double) CatacombsAverageCalculator.xpForLevel(60));
        Breakdown result = CatacombsAverageCalculator.calculateBreakdown(current, xp, 51);
        assertTrue(result.total() > 0);
        assertEquals(0, result.perClass(DungeonClass.ARCHER));
        assertCompletes(current, xp, result, CatacombsAverageCalculator.xpForLevel(51));
    }

    @Test
    public void lowerAndAlreadyCompletedClassTargetsDoNotUseTheOldFiftyThreshold() {
        var current = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        var xp = selectedXp(Map.of(), 1);
        long targetXp = CatacombsAverageCalculator.xpForLevel(45);
        for (DungeonClass dungeonClass : DungeonClass.values()) current.put(dungeonClass, (double) targetXp);
        assertEquals(0, CatacombsAverageCalculator.calculateBreakdown(current, xp, 45).total());
        current.put(DungeonClass.MAGE, targetXp - xp.get(DungeonClass.MAGE) - 1);
        Breakdown result = CatacombsAverageCalculator.calculateBreakdown(current, xp, 45);
        assertEquals(2, result.total());
        assertEquals(2, result.perClass(DungeonClass.MAGE));
        assertCompletes(current, xp, result, targetXp);
    }

    @Test
    public void explorerMatchesAdjectilsCatacombsDefaultsAcrossMayorBonuses() {
        assertEquals(534_000, CatacombsAverageCalculator.catacombsXpPerRun(300_000, true, 0.02, 10, 0, 1));
        assertEquals(759_000, CatacombsAverageCalculator.catacombsXpPerRun(300_000, true, 0.02, 10, 0, 1.5));
        assertEquals(786_000, CatacombsAverageCalculator.catacombsXpPerRun(300_000, true, 0.02, 10, 0, 1.59));
    }

    @Test
    public void explorerIsIndependentOfExpertRingAndAddedOnlyOnce() {
        for (boolean ring : new boolean[] {false, true}) {
            long without = CatacombsAverageCalculator.catacombsXpPerRun(300_000, ring, 0.02, 0, 0, 1);
            long levelFive = CatacombsAverageCalculator.catacombsXpPerRun(300_000, ring, 0.02, 5, 0, 1);
            long levelTen = CatacombsAverageCalculator.catacombsXpPerRun(300_000, ring, 0.02, 10, 0, 1);
            assertEquals(15_000, levelFive - without);
            assertEquals(30_000, levelTen - without);
        }
    }

    @Test
    public void globalCatacombsBonusMultipliesExplorerAndLowerFloorsUseTheirOwnBaseXp() {
        assertEquals(640_800, CatacombsAverageCalculator.catacombsXpPerRun(300_000, true, 0.02, 10, 0.20, 1));
        assertEquals(330, CatacombsAverageCalculator.catacombsXpPerRun(110, true, 0.02, 10, 0, 1));
        assertEquals(11_664, CatacombsAverageCalculator.catacombsXpPerRun(4880, true, 0.02, 10, 0, 1));
    }

    @Test
    public void explorerConvertsCumulativeEpicShardsToLevelsInsteadOfClampingStacks() {
        JsonObject member = new JsonObject();
        JsonObject attributes = new JsonObject();
        JsonObject stacks = new JsonObject();
        attributes.add("stacks", stacks);
        member.add("attributes", attributes);
        int[] shards = {0, 1, 2, 3, 4, 8, 9, 24, 25, 31, 32, 64};
        int[] levels = {0, 1, 2, 2, 3, 4, 5, 8, 9, 9, 10, 10};
        for (int i = 0; i < shards.length; i++) {
            stacks.addProperty("catacombs_explorer", shards[i]);
            assertEquals("shards=" + shards[i], levels[i], HypixelSkyBlockProfileClient.explorerAttributeLevel(member));
        }
    }

    @Test
    public void absentAttributeApiDataDiffersFromKnownZeroExplorer() {
        assertEquals(-1, HypixelSkyBlockProfileClient.explorerAttributeLevel(new JsonObject()));
        assertEquals(0, HypixelSkyBlockProfileClient.explorerAttributeLevel(
            JsonParser.parseString("{\"attributes\":{\"stacks\":{}}}").getAsJsonObject()));
    }

    @Test
    public void onlyPlayingOneClassLetsTheOthersEarnAQuarterAndTheSlowestClassSetsTheRuns() {
        var current = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        var xp = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            current.put(dungeonClass, 0.0);
            xp.put(dungeonClass, 400_000.0);
        }
        // The played Archer needs 1425 runs at 400k, the other four need 5699 at their 100k share.
        long runs = CatacombsAverageCalculator.runsWithOnly(DungeonClass.ARCHER, current, xp, 50);
        assertEquals(5699, runs);
        assertSoloCompletes(DungeonClass.ARCHER, current, xp, runs, 50);

        // A played class far behind the rest is the slowest itself.
        for (DungeonClass dungeonClass : DungeonClass.values()) current.put(dungeonClass, LEVEL_50_XP);
        current.put(DungeonClass.MAGE, 0.0);
        xp.put(DungeonClass.MAGE, 100_000.0);
        runs = CatacombsAverageCalculator.runsWithOnly(DungeonClass.MAGE, current, xp, 50);
        assertEquals(5699, runs);
        assertSoloCompletes(DungeonClass.MAGE, current, xp, runs, 50);
        // Played by someone else, the same class only earns its quarter share.
        runs = CatacombsAverageCalculator.runsWithOnly(DungeonClass.TANK, current, xp, 50);
        assertEquals(22_793, runs);
        assertSoloCompletes(DungeonClass.TANK, current, xp, runs, 50);
    }

    @Test
    public void onlyPlayingOneClassIsDoneWithoutRunsOrNeverWithoutXp() {
        var current = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) current.put(dungeonClass, LEVEL_50_XP);
        // Finished classes need no XP rate; a missing one does.
        assertEquals(0, CatacombsAverageCalculator.runsWithOnly(DungeonClass.TANK, current, Map.of(), 50));
        current.put(DungeonClass.HEALER, LEVEL_50_XP - 1);
        assertEquals(Long.MAX_VALUE, CatacombsAverageCalculator.runsWithOnly(DungeonClass.TANK, current, Map.of(), 50));
        assertEquals(1, CatacombsAverageCalculator.runsWithOnly(DungeonClass.HEALER, current,
            Map.of(DungeonClass.HEALER, 1.0), 50));
    }

    @Test
    public void onlyPlayingOneClassNeverBeatsTheBestMixOnARealProfile() {
        ProfileData profile = parse(fixture("salty-whale-profiles.json")).player().selectedProfile();
        var xp = selectedXp(profile.classPerks(), 1.0);
        Breakdown mixed = CatacombsAverageCalculator.calculateBreakdown(profile.classXp(), xp);
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            long runs = CatacombsAverageCalculator.runsWithOnly(dungeonClass, profile.classXp(), xp, 50);
            assertTrue(dungeonClass.label(), runs >= mixed.total());
            assertSoloCompletes(dungeonClass, profile.classXp(), xp, runs, 50);
        }
    }

    @Test
    public void classLevelRunsRoundUpAndIgnoreTheOtherClasses() {
        assertEquals(2, CatacombsAverageCalculator.runsToClassLevel(LEVEL_50_XP - 1_000_000, 630_000, 50));
        assertEquals(1, CatacombsAverageCalculator.runsToClassLevel(LEVEL_50_XP - 630_000, 630_000, 50));
        assertEquals(2, CatacombsAverageCalculator.runsToClassLevel(LEVEL_50_XP - 630_001, 630_000, 50));
        assertEquals(0, CatacombsAverageCalculator.runsToClassLevel(LEVEL_50_XP, 0, 50));
        assertEquals(Long.MAX_VALUE, CatacombsAverageCalculator.runsToClassLevel(0, 0, 50));
    }

    @Test
    public void oneClassAndClassLevelAnswersNameTheClassAndStayShort() {
        assertEquals("Beng114 is 5699 M7 runs away from ca50 with only Archer",
            CatacombsAverageCalculator.oneClassSummaryLine("Beng114", "M7", DungeonClass.ARCHER, 50, 5699));
        assertEquals("Beng114 already has ca50", CatacombsAverageCalculator.oneClassSummaryLine("Beng114", "M7",
            DungeonClass.ARCHER, 50, 0));
        assertEquals("Beng114 is infinite M7 runs away from ca50 with only Tank",
            CatacombsAverageCalculator.oneClassSummaryLine("Beng114", "M7", DungeonClass.TANK, 50, Long.MAX_VALUE));
        // 49.99 is shown as 49.9, not rounded up to the level it has not reached.
        assertEquals("Beng114 is 2 M7 runs away from Archer 50 (now 49.9)",
            CatacombsAverageCalculator.classLevelSummaryLine("Beng114", "M7", DungeonClass.ARCHER, 50, LEVEL_50_XP - 1_000_000, 2));
        assertEquals("Beng114's Archer is already 50",
            CatacombsAverageCalculator.classLevelSummaryLine("Beng114", "M7", DungeonClass.ARCHER, 50, LEVEL_50_XP, 0));
    }

    @Test
    public void everyGoalAnswersFromTheSameProfileData() {
        var player = parse(fixture("salty-whale-profiles.json")).player();
        var calculator = new CatacombsAverageCalculator();

        var level = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CLASS_LEVEL, 50, DungeonClass.MAGE, false);
        assertTrue(level.message(), level.success());
        assertTrue(level.message(), level.message().startsWith(player.name() + " is ") && level.message().contains(" M7 runs away from Mage 50 (now ")
            || level.message().equals(player.name() + "'s Mage is already 50"));
        assertTrue(level.debugDetails().contains("class=mage"));

        var solo = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CLASS_AVERAGE, 50, DungeonClass.TANK, false);
        assertTrue(solo.message(), solo.success());
        assertTrue(solo.message(), solo.message().endsWith("with only Tank") || solo.message().endsWith("already has ca50"));

        var plain = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CLASS_AVERAGE, 50, null, false);
        assertTrue(plain.message(), plain.message().contains("runs away from ca50 (Arch "));
        assertFalse(plain.debugDetails().contains("class="));
    }

    @Test
    public void everyLevelFromOneUpIsATargetNotJustFifty() {
        var current = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        var xp = new EnumMap<DungeonClass, Double>(DungeonClass.class);
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            current.put(dungeonClass, 0.0);
            xp.put(dungeonClass, 1_000_000.0);
        }
        // Level 60 is 2,569,809,640 XP: 2570 runs for the played class, 10280 at the 250k share of the others.
        long runs = CatacombsAverageCalculator.runsWithOnly(DungeonClass.ARCHER, current, xp, 60);
        assertEquals(10_280, runs);
        assertSoloCompletes(DungeonClass.ARCHER, current, xp, runs, 60);
        assertEquals(2, CatacombsAverageCalculator.runsWithOnly(DungeonClass.ARCHER, current, Map.of(
            DungeonClass.ARCHER, 100.0, DungeonClass.BERSERK, 100.0, DungeonClass.HEALER, 100.0,
            DungeonClass.MAGE, 100.0, DungeonClass.TANK, 100.0), 1)); // 50 XP: 25 per run for the others
        assertEquals(2570, CatacombsAverageCalculator.runsToClassLevel(0, 1_000_000, 60));
        assertEquals(1, CatacombsAverageCalculator.runsToClassLevel(0, 100, 1));
        assertEquals("Beng114 is 42 M7 runs away from Archer 60 (now 50.5)",
            CatacombsAverageCalculator.classLevelSummaryLine("Beng114", "M7", DungeonClass.ARCHER, 60, LEVEL_50_XP + 100_000_000, 42));
        assertEquals("Beng114's Archer is already 60",
            CatacombsAverageCalculator.classLevelSummaryLine("Beng114", "M7", DungeonClass.ARCHER, 60, CatacombsAverageCalculator.xpForLevel(60), 0));
        assertEquals("Beng114 is 7 M7 runs away from ca60 with only Mage",
            CatacombsAverageCalculator.oneClassSummaryLine("Beng114", "M7", DungeonClass.MAGE, 60, 7));
        assertEquals("Beng114 already has ca60", CatacombsAverageCalculator.oneClassSummaryLine("Beng114", "M7",
            DungeonClass.MAGE, 60, 0));

        var player = parse(fixture("salty-whale-profiles.json")).player();
        var calculator = new CatacombsAverageCalculator();
        var cata = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CATACOMBS, 67, null, false);
        assertTrue(cata.message(), cata.message().startsWith(player.name() + " is ") && cata.message().endsWith(" M7 runs away from c67"));
        assertTrue(cata.debugDetails(), cata.debugDetails().contains("level=67"));
        var average = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CLASS_AVERAGE, 60, null, false);
        assertTrue(average.message(), average.message().contains(" M7 runs away from ca60 (Arch "));
        var solo = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CLASS_AVERAGE, 60, DungeonClass.HEALER, false);
        assertTrue(solo.message(), solo.message().endsWith(" M7 runs away from ca60 with only Healer"));
        // A higher goal never needs fewer runs than a lower one.
        var lower = calculator.calculate(player, "", "Salty_Whale", CatacombsAverageCalculator.Goal.CLASS_AVERAGE, 50, null, false);
        assertTrue(runsIn(average.message()) >= runsIn(lower.message()));
    }

    private static long runsIn(String message) {
        return Long.parseLong(message.replaceFirst("^.* is ([0-9]+) M7 runs away.*$", "$1"));
    }

    private static void assertSoloCompletes(DungeonClass played, Map<DungeonClass, Double> current,
                                            Map<DungeonClass, Double> xp, long runs, int level) {
        // The planned runs finish every class; one run fewer leaves the last class short.
        assertTrue(everyClassDone(played, current, xp, runs, level));
        assertTrue(runs == 0 || !everyClassDone(played, current, xp, runs - 1, level));
    }

    private static boolean everyClassDone(DungeonClass played, Map<DungeonClass, Double> current,
                                          Map<DungeonClass, Double> xp, long runs, int level) {
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            double share = xp.get(dungeonClass) * (dungeonClass == played ? 1.0 : 0.25);
            if (current.getOrDefault(dungeonClass, 0.0) + runs * share < CatacombsAverageCalculator.xpForLevel(level)) return false;
        }
        return true;
    }

    private static void assertCompletes(Map<DungeonClass, Double> current, Map<DungeonClass, Double> xp, Breakdown result) {
        assertCompletes(current, xp, result, LEVEL_50_XP);
    }

    private static void assertCompletes(Map<DungeonClass, Double> current, Map<DungeonClass, Double> xp,
                                        Breakdown result, double targetXp) {
        assertEquals(result.total(), result.perClass().values().stream().mapToLong(Long::longValue).sum());
        for (DungeonClass dungeonClass : DungeonClass.values()) {
            double selected = result.perClass(dungeonClass);
            double passive = (result.total() - selected) / 4.0;
            assertTrue(dungeonClass.label(), current.getOrDefault(dungeonClass, 0.0)
                + xp.get(dungeonClass) * (selected + passive) >= targetXp);
        }
    }

    private static EnumMap<DungeonClass, Double> selectedXp(Map<DungeonClass, Integer> perks, double mayor) {
        return CatacombsAverageCalculator.classXpPerRun(perks, 300_000, 0.02, 0.06, 0.20, 0, mayor);
    }

    private static HypixelSkyBlockProfileClient.ProfileResult parse(JsonObject root) {
        return HypixelSkyBlockProfileClient.parseProfiles(UUID, "Salty_Whale", new JsonObject(), root);
    }

    private static JsonObject fixture() {
        return fixture("salty-whale-profiles.json");
    }

    private static JsonObject fixture(String name) {
        try (var stream = CatacombsCalculatorTest.class.getResourceAsStream("/catacombs/" + name);
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
