package com.github.beng420.kung.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.PlayerData;
import com.github.beng420.kung.util.CatacombsAverageCalculator.ProfileData;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient.ProfileResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class CatacombsLocalStoreTest {
    private static final String WATERMELON = "9c2699d5-0a51-4361-a09c-a0127265a488";
    private static final String RASPBERRY = "5c4d3da3-11d7-472c-a929-12bcb8fe94d2";
    private static final ProfileResult FAILED = ProfileResult.error(HypixelSkyBlockProfileClient.NO_SOURCE);

    @Test
    public void runXpIsBookedOnlyIntoTheActiveProfileAndRepeatedLinesCountOnce() throws Exception {
        CatacombsLocalStore store = store();
        store.merge(api(), System.currentTimeMillis());
        CatacombsRecentXpTracker tracker = new CatacombsRecentXpTracker(store);

        tracker.observeMessage("+100,000 Catacombs Experience"); // Before any Profile ID line.
        tracker.observeMessage("§8Profile ID: " + RASPBERRY.toUpperCase());
        tracker.observeMessage("+300,000 Catacombs Experience");
        tracker.observeMessage("+300,000 Catacombs Experience"); // The same line echoed again.
        tracker.observeMessage("+250,000 Mage Experience");
        tracker.observeMessage("+62,500 Archer Experience (Team Bonus)");

        PlayerData local = store.apply("Me", FAILED).player();
        ProfileData before = profile(api(), RASPBERRY);
        ProfileData raspberry = profile(local, RASPBERRY);
        assertEquals(before.cataXp() + 300_000, raspberry.cataXp(), 0.001);
        assertEquals(before.classXp(DungeonClass.MAGE) + 250_000, raspberry.classXp(DungeonClass.MAGE), 0.001);
        assertEquals(before.classXp(DungeonClass.ARCHER) + 62_500, raspberry.classXp(DungeonClass.ARCHER), 0.001);
        assertEquals(profile(api(), WATERMELON), profile(local, WATERMELON));
        assertEquals("Raspberry", local.selectedProfile().cuteName());
    }

    @Test
    public void mergeKeepsRecentLocalRunsWhileTheApiLagsThenTakesTheApiAsTruth() throws Exception {
        CatacombsLocalStore store = store();
        store.merge(api(), System.currentTimeMillis());
        store.setActiveProfile(WATERMELON);
        assertTrue(store.book(null, 500_000));
        assertTrue(store.book(DungeonClass.TANK, 400_000));
        long booked = System.currentTimeMillis();

        ProfileData before = profile(api(), WATERMELON);
        ProfileData partlyCaughtUp = new ProfileData(before.id(), before.cuteName(), true, before.cataXp() + 100_000,
            before.classXp(), Map.of(DungeonClass.MAGE, 5), before.stats());
        PlayerData lagging = new PlayerData("Me", List.of(partlyCaughtUp, profile(api(), RASPBERRY)), 0);

        ProfileData recent = profile(store.merge(lagging, booked + 60_000L), WATERMELON);
        assertEquals(before.cataXp() + 500_000, recent.cataXp(), 0.001);
        assertEquals(before.classXp(DungeonClass.TANK) + 400_000, recent.classXp(DungeonClass.TANK), 0.001);
        assertEquals("perks always come from the API", 5, recent.classPerk(DungeonClass.MAGE));
        assertEquals(0, recent.classPerk(DungeonClass.TANK));

        ProfileData later = profile(store.merge(lagging, booked + CatacombsLocalStore.RECENT_RUN_MILLIS + 1), WATERMELON);
        assertEquals(partlyCaughtUp, later);
        assertEquals(partlyCaughtUp, profile(store.apply("Me", FAILED).player(), WATERMELON));
    }

    @Test
    public void failedLookupForYourselfIsAnsweredFromTheStoreOnlyWhenItHasData() throws Exception {
        assertSame(FAILED, store().apply("Me", FAILED));

        CatacombsLocalStore store = store();
        store.merge(api(), System.currentTimeMillis());
        assertSame("other players keep the original error", FAILED, store.apply("Someone", FAILED));
        ProfileResult local = store.apply("me", FAILED);
        assertTrue(local.success());
        assertTrue(local.local());
        assertEquals("Watermelon", local.player().selectedProfile().cuteName());
        assertTrue(new CatacombsAverageCalculator().calculate(local.player(), "", "", CatacombsAverageCalculator.Goal.CLASS_AVERAGE, 50, null, true).success());
    }

    @Test
    public void storeSurvivesSaveAndLoad() throws Exception {
        Path file = Files.createTempDirectory("kung-ca-cache").resolve("ca-cache.json");
        CatacombsLocalStore store = new CatacombsLocalStore(file, CatacombsLocalStoreTest::me);
        store.merge(api(), System.currentTimeMillis());
        store.setActiveProfile(WATERMELON);
        store.book(DungeonClass.HEALER, 1_234.5);
        store.save();

        ProfileResult reloaded = new CatacombsLocalStore(file, CatacombsLocalStoreTest::me).apply("Me", FAILED);
        assertEquals(store.apply("Me", FAILED), reloaded);
        assertTrue(profile(reloaded.player(), WATERMELON).stats().available());
    }

    private static CatacombsLocalStore store() throws Exception {
        return new CatacombsLocalStore(Files.createTempDirectory("kung-ca-cache").resolve("ca-cache.json"),
            CatacombsLocalStoreTest::me);
    }

    private static CatacombsLocalStore.LocalPlayer me() {
        return new CatacombsLocalStore.LocalPlayer("7b2c5b97-05a2-4f47-8f2d-725b9694bd4d", "Me");
    }

    private static ProfileData profile(PlayerData player, String id) {
        return player.profiles().stream().filter(profile -> profile.id().equals(id)).findFirst().orElseThrow();
    }

    private static PlayerData api() {
        try (var stream = CatacombsLocalStoreTest.class.getResourceAsStream("/catacombs/salty-whale-profiles.json");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return HypixelSkyBlockProfileClient.parseProfiles("7b2c5b9705a24f478f2d725b9694bd4d", "Me", new JsonObject(), root)
                .player();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
