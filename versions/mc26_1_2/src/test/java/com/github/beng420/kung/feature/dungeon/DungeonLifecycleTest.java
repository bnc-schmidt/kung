package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.BRHelperConfig;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.runtime.AppServices;
import com.github.beng420.kung.skyblock.HypixelInstanceState;
import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.junit.Test;

public final class DungeonLifecycleTest {
    @Test public void scanReadinessSurvivesCountdownAndClearsOnInstanceExit() throws Exception {
        var config = KungConfig.get();
        var previousDungeon = config.dungeon;
        var previousBloodRush = config.bloodRush;
        boolean bloodRushInitialized = BloodRushHelperFeature.INSTANCE.initialized();
        config.dungeon = new DungeonConfig();
        config.bloodRush = new BRHelperConfig();
        try {
            var tracker = new DungeonStateTracker();
            BloodRushHelperFeature.INSTANCE.initialize(AppServices.create(config, tracker));
            assertFalse(tracker.canScanDungeon());
            lifecycle(tracker, "startDungeonInstance");
            assertTrue(tracker.canScanDungeon());
            assertFalse(tracker.realRunStarted());
            assertFalse(tracker.isRecording());
            tracker.mapSnapshot().observeStartRoom(1, 5);
            tracker.runStats().observeFloorMetadata("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");
            assertEquals(7, tracker.runStats().floor());

            lifecycle(tracker, "restartDungeonRun");
            assertFalse(tracker.splitTracker().hasKnownFloor());
            assertTrue(tracker.canScanDungeon());
            assertTrue(tracker.realRunStarted());
            assertEquals(new DungeonMapSnapshot.GridKey(2, 10), tracker.mapSnapshot().startRoom());

            lifecycle(tracker, "endDungeonInstance");
            assertFalse(tracker.canScanDungeon());
            assertFalse(tracker.realRunStarted());
            tracker.synchronizeInstance(null);
            assertFalse(tracker.canScanDungeon());
        } finally {
            if (!bloodRushInitialized) BloodRushHelperFeature.INSTANCE.shutdown();
            config.dungeon = previousDungeon;
            config.bloodRush = previousBloodRush;
        }
    }

    private static void lifecycle(DungeonStateTracker tracker, String methodName) throws Exception {
        var method = DungeonStateTracker.class.getDeclaredMethod(methodName, Minecraft.class);
        method.setAccessible(true);
        method.invoke(tracker, (Minecraft) null);
    }

    @Test public void recognizesFormattedLifecycleMessages() {
        assertTrue(DungeonLifecycleSignals.isRunStart("  §aStarting   in 1 second.  "));
        assertTrue(DungeonLifecycleSignals.isRunFinished("§r§cS Defeated Necron in 05m 42s (NEW RECORD!)"));
        assertFalse(DungeonLifecycleSignals.isRunStart("Starting in 2 seconds."));
        assertFalse(DungeonLifecycleSignals.isRunFinished("Necron was defeated"));
    }

    @Test public void floorOneTeamScoreCompletesRunsWithoutADefeatedBanner() {
        assertTrue(DungeonLifecycleSignals.isRunFinished("§r§aTeam Score: 191 (B)"));
        assertTrue(DungeonLifecycleSignals.isRunFinished("Team Score: 307 (S+)"));
        assertFalse(DungeonLifecycleSignals.isRunFinished("Score: 191 (B)"));
        assertFalse(DungeonLifecycleSignals.isRunFinished("Party > Ben: Team Score: 191 (B)"));
        assertFalse(DungeonLifecycleSignals.isRunFinished("The Catacombs - Floor I"));
        DungeonSplitTracker tracker = new DungeonSplitTracker();
        tracker.startRun(0L, 1, false);
        assertTrue(tracker.observeMessage("Team Score: 191 (B)", 20L));
        assertFalse(tracker.running());
        assertFalse(tracker.observeMessage("Defeated Bonzo in 3m 28s", 20L));
    }

    @Test public void entersOnFirstLocationPacketAndRetainsEvidenceDuringSidebarRebuild() {
        HypixelInstanceState state = new HypixelInstanceState();
        state.beginWorld();
        state.observe(location("⏣ The Catacombs (M7)"), "mini24bs");
        assertTrue(state.catacombs());
        for (int i = 0; i < 1000; i++) state.observe(HypixelLocation.UNKNOWN, "");
        assertTrue(state.catacombs()); // Includes boss phases outside the map grid.
        assertEquals("mini24bs", state.serverId());
    }

    @Test public void worldBoundaryImmediatelyInvalidatesTheDungeon() {
        HypixelInstanceState state = new HypixelInstanceState();
        state.observe(location("The Catacombs (F7)"), "mini1a");
        long epoch = state.epoch();
        state.beginWorld();
        assertFalse(state.catacombs());
        assertEquals(epoch + 1, state.epoch());
        assertEquals("", state.serverId());
        state.observe(HypixelLocation.UNKNOWN, "");
        assertFalse(state.catacombs());
    }

    @Test public void differentServerStartsNewSessionEvenWithoutAWorldPacket() {
        HypixelInstanceState state = new HypixelInstanceState();
        state.observe(location("The Catacombs (F7)"), "mini1a");
        // The tracker holds a different id for SERVER_CHANGE_CONFIRM_TICKS before observing it.
        assertTrue(state.holdServerChange("mini2bc"));
        List<String> sidebar = List.of("09/10/26 mini2bc");
        for (int tick = 1; tick < HypixelInstanceState.SERVER_CHANGE_CONFIRM_TICKS; tick++) {
            assertEquals("", state.confirmedServerChange(sidebar));
            assertTrue(state.catacombs());
        }
        assertEquals("mini2bc", state.confirmedServerChange(sidebar));
        state.observe(HypixelLocation.UNKNOWN, "mini2bc");
        assertFalse(state.catacombs());
        assertEquals(1, state.epoch());
    }

    @Test public void explicitOtherLocationEndsDungeonWithoutWaiting() {
        for (String name : List.of("Area: Kuudra's Hollow (T5)", "⏣ Dungeon Hub", "Area: Private Island", "⏣ Village", "Area: The Rift")) {
            HypixelInstanceState state = new HypixelInstanceState();
            state.observe(location("The Catacombs (F7)"), "mini1a");
            state.observe(location(name), "");
            assertFalse(name, state.catacombs());
        }
    }

    @Test public void kuudraOverridesStaleCatacombsLineAndGenericCombatCounters() {
        assertEquals(HypixelLocation.Kind.KUUDRA, HypixelLocation.parse(List.of(
            "The Catacombs (M7)", "⏣ Kuudra's Hollow (T5)", "Deaths: 0", "Score: 300")).kind());
        assertEquals(HypixelLocation.Kind.UNKNOWN, HypixelLocation.parse(List.of("Deaths: 0", "Score: 300", "Milestone 3", "Starting in 1 second.")).kind());
    }

    @Test public void otherPlayersAndPartyFinderCannotSetLocation() {
        for (String text : List.of("Friends in The Catacombs", "Ben is in The Catacombs", "MVP Ben entered The Catacombs, Floor VII!", "Party > Ben: Area: The Catacombs", "Selected Dungeon: The Catacombs", "Best Run: F7")) {
            assertEquals(text, HypixelLocation.Kind.UNKNOWN, location(text).kind());
        }
    }

    @Test public void entranceAndFormattedFloorFieldsAreRecognized() {
        assertEquals(HypixelLocation.Kind.CATACOMBS, location("§7⏣ §cThe Catacombs §7(E)").kind());
        assertEquals(HypixelLocation.Kind.CATACOMBS, location("Dungeon: Catacombs").kind());
        assertEquals(HypixelLocation.Kind.CATACOMBS, location("Location: Master Mode The Catacombs - Floor VII").kind());
        assertEquals(HypixelLocation.Kind.CATACOMBS, location("Dungeon: The Catacombs - Entrance").kind());
        assertEquals(HypixelLocation.Kind.DUNGEON_HUB, location("Area: Dungeon Hub").kind());
        assertEquals(HypixelLocation.Kind.KUUDRA, location("Kuudra's Hollow (T1)").kind());
    }

    @Test public void serverIdsSupportMultiLetterSuffixes() {
        assertEquals("mini24bs", HypixelLocation.serverId(List.of("09/10/26 mini24BS")));
        assertEquals("mini82ch", HypixelLocation.serverId(List.of("Server: mini82CH")));
        assertEquals("", HypixelLocation.serverId(List.of("Friend in mini24BS")));
    }

    @Test public void updatedTabLocationWinsAndUnrelatedScorePacketsCannotRestoreOldDungeon() {
        HypixelInstanceState state = new HypixelInstanceState();
        HypixelLocation catacombs = location("The Catacombs (M7)");
        state.observe(HypixelInstanceState.Source.SIDEBAR, catacombs, "mini1a");
        state.observe(HypixelInstanceState.Source.TAB, location("Area: Kuudra's Hollow (T5)"), "mini1a");
        state.observe(HypixelInstanceState.Source.SIDEBAR, catacombs, "mini1a");
        assertFalse(state.catacombs());
        assertEquals(HypixelLocation.Kind.KUUDRA, state.location().kind());
    }

    @Test public void newServerRowCannotReuseOldLocationOrAnotherSourcesCache() {
        HypixelInstanceState state = new HypixelInstanceState();
        HypixelLocation catacombs = location("The Catacombs (F7)");
        state.observe(HypixelInstanceState.Source.SIDEBAR, catacombs, "mini1a");
        assertTrue(state.observe(HypixelInstanceState.Source.SIDEBAR, catacombs, "mini2b"));
        assertFalse(state.catacombs());
        state.observe(HypixelInstanceState.Source.TAB, HypixelLocation.UNKNOWN, "mini2b");
        assertFalse(state.catacombs());
        state.observe(HypixelInstanceState.Source.SIDEBAR, catacombs, "mini2b");
        assertTrue(state.catacombs()); // A fresh location field in the new session is accepted immediately.
    }

    private static HypixelLocation location(String text) { return HypixelLocation.parse(List.of(text)); }
}
