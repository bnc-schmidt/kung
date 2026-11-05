package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.DungeonConfig.DragonDebuffScope;
import com.github.beng420.kung.feature.dungeon.M7DragonTracker.Statue;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public final class DungeonDebuffTrackerTest {
    @Test
    public void earlyHitFeedbackKeepsSameTickMultiplicityAndOriginalOffsetsAcrossSelectionDelay() {
        var tracker = new DungeonDebuffTracker();
        UUID red = UUID.randomUUID();
        var dragon = tracker.spawn(red, Statue.RED, new Vec3(32, 6, 59));
        tracker.advance();
        for (int i = 0; i < 3; i++) assertTrue(tracker.arrowHit());
        for (int i = 0; i < 3; i++) tracker.advance();
        assertTrue(tracker.arrowHit());
        assertTrue(tracker.arrowHit());
        assertEquals(5, dragon.arrows.count);
        assertEquals(1, dragon.arrows.first);
        assertEquals(4, dragon.arrows.fifth);
        assertTrue(dragon.arrows.describe().contains("[1, 1, 1, 4, 4]"));
        assertTrue(dragon.arrows.describe().contains("26.7/20t"));
        assertTrue(dragon.arrows.describe().contains("Gaps (ticks): [0, 0, 3, 0]"));
    }

    @Test
    public void liveTraceEarlyThreeHitsGoToOneStatueAndLaterDpsDoesNotCount() {
        var tracker = new DungeonDebuffTracker();
        var blue = tracker.spawn(UUID.randomUUID(), Statue.BLUE, new Vec3(85.1099, 6, 96.3198));
        var purple = tracker.spawn(UUID.randomUUID(), Statue.PURPLE, new Vec3(85.1099, 6, 96.3198));
        // 20:31:01 trace: both dragons previously received [1, 4, 4, 133, 133, ...].
        for (int offset : new int[] {1, 4, 4, 133, 133, 133, 134, 134, 136}) {
            while (tracker.tick() < offset) tracker.advance();
            assertEquals(offset <= 40, tracker.arrowHit());
        }
        assertEquals(3, blue.arrows.count);
        assertEquals(0, purple.arrows.count);
        assertTrue(blue.summary(tracker.tick()).contains("Arrows: 3"));
        assertTrue(purple.summary(tracker.tick()).contains("Arrows: --"));
        assertFalse(blue.summary(tracker.tick()).contains("Hit sounds"));
    }

    @Test
    public void impactSoundsAndRemotePlayerFeedbackAreRejected() {
        var player = new Vec3(56, 6, 120);
        assertTrue(DungeonDebuffTracker.localArrowFeedback("minecraft:entity.arrow.hit_player", player, player));
        assertTrue(DungeonDebuffTracker.localArrowFeedback("minecraft:entity.arrow.hit_player", player.add(0, 1.6, 0), player));
        assertFalse(DungeonDebuffTracker.localArrowFeedback("minecraft:entity.arrow.hit", player, player));
        assertFalse(DungeonDebuffTracker.localArrowFeedback("minecraft:entity.arrow.hit_player", new Vec3(80, 6, 56), player));
    }

    @Test
    public void windowEndsAfterFortyTicksAndMissingPositionCannotInventAnAssignment() {
        var tracker = new DungeonDebuffTracker();
        var dragon = tracker.spawn(UUID.randomUUID(), Statue.RED, new Vec3(32, 6, 59));
        while (tracker.tick() < 40) tracker.advance();
        assertTrue(tracker.arrowHit());
        tracker.advance();
        assertFalse(tracker.arrowHit());
        assertEquals(1, dragon.arrows.count);
        tracker.clearDragons();
        var unknown = tracker.spawn(UUID.randomUUID(), Statue.BLUE);
        tracker.arrowHit();
        for (int i = 0; i < 3; i++) tracker.advance();
        assertFalse(tracker.arrowHit());
        assertEquals(0, unknown.arrows.count);
    }

    @Test
    public void sprayUsesOriginalPacketTickExpiresInServerTicksAndKeepsFirstDragonTiming() {
        var tracker = new DungeonDebuffTracker();
        UUID uuid = UUID.randomUUID();
        var dragon = tracker.spawn(uuid, Statue.PURPLE);
        tracker.advance();
        long packetTick = tracker.tick();
        tracker.advance(); // Delayed metadata/target resolution must not shift the measurement.
        assertTrue(tracker.spray(uuid, packetTick));
        assertEquals(1, dragon.sprayTick);
        assertFalse(tracker.spray(uuid, packetTick));
        while (tracker.tick() < 100) tracker.advance();
        assertTrue(tracker.highlighted().contains(uuid));
        tracker.advance();
        assertFalse(tracker.highlighted().contains(uuid));
        tracker.spray(uuid, tracker.tick());
        assertEquals(1, dragon.sprayTick);
        assertTrue(tracker.highlighted().contains(uuid));
    }

    @Test
    public void ordinaryMobSprayDoesNotRequireDragonTrackingAndResetClearsBoth() {
        var tracker = new DungeonDebuffTracker();
        UUID mob = UUID.randomUUID();
        assertFalse(tracker.spray(mob, tracker.tick()));
        assertTrue(tracker.highlighted().contains(mob));
        tracker.spawn(UUID.randomUUID(), Statue.GREEN);
        tracker.clearDragons();
        assertTrue(tracker.highlighted().contains(mob));
        tracker.reset();
        assertTrue(tracker.highlighted().isEmpty());
        assertEquals(0, tracker.tick());
    }

    @Test
    public void unloadedDragonIsNotKilledAndSameUuidCannotRestartItsClock() {
        var tracker = new DungeonDebuffTracker();
        UUID uuid = UUID.randomUUID();
        var dragon = tracker.spawn(uuid, Statue.ORANGE);
        tracker.advance();
        assertSame(dragon, tracker.spawn(uuid, Statue.ORANGE));
        assertEquals(0, dragon.spawnTick);
        assertSame(dragon, tracker.end(uuid, false));
        assertTrue(dragon.details().contains("death not confirmed"));
        assertTrue(dragon.summary(tracker.tick()).contains("Sprayed: no"));
        assertNull(tracker.end(uuid, true));
        assertSame(dragon, tracker.spawn(uuid, Statue.ORANGE));
        tracker.arrowHit();
        assertEquals(0, dragon.arrows.count);
        UUID next = UUID.randomUUID();
        assertEquals(1, tracker.spawn(next, Statue.ORANGE).spawnTick);
    }

    @Test
    public void iceMarkerRequiresUniqueNearbyTargetRatherThanEveryNearbyDragon() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var mob = new DungeonDebuffTracker.Target(a, Vec3.ZERO, new AABB(-.5, 0, -.5, .5, 2, .5), false);
        assertEquals(a, DungeonDebuffTracker.uniqueTarget(new Vec3(0, 1, 0), List.of(mob)));
        assertNull(DungeonDebuffTracker.uniqueTarget(new Vec3(5, 1, 0), List.of(mob)));
        var overlap = new DungeonDebuffTracker.Target(b, Vec3.ZERO, mob.bounds(), false);
        assertNull(DungeonDebuffTracker.uniqueTarget(new Vec3(0, 1, 0), List.of(mob, overlap)));
        var dragon = new DungeonDebuffTracker.Target(a, new Vec3(6, 0, 0), mob.bounds(), true);
        var other = new DungeonDebuffTracker.Target(b, new Vec3(-6, 0, 0), mob.bounds(), true);
        assertEquals(a, DungeonDebuffTracker.uniqueTarget(Vec3.ZERO, List.of(dragon)));
        assertNull(DungeonDebuffTracker.uniqueTarget(Vec3.ZERO, List.of(dragon, other)));
        assertNull(DungeonDebuffTracker.uniqueTarget(new Vec3(15, 0, 0), List.of(dragon)));
    }

    @Test public void markerInsideOneMobIsNotLostToNearbyOutsideCompetitor() {
        // 2026-09-19 12:29:06.340, tick 3545, marker 86167: distances 0 and 0.10264.
        var first = new DungeonDebuffTracker.Target(new UUID(0, 85901), new Vec3(76.412841796875, 221, 39.008544921875),
            new AABB(76.0628418, 221, 38.6585449, 76.7628418, 223.4000001, 39.3585449), false);
        var second = new DungeonDebuffTracker.Target(new UUID(0, 85957), new Vec3(76.5380859375, 221.0263671875, 38.45361328125),
            new AABB(76.1880859, 221.0263672, 38.1036133, 76.8880859, 223.4263673, 38.8036133), false);
        assertEquals(first.uuid(), DungeonDebuffTracker.uniqueTarget(new Vec3(76.46875, 222.59375, 38.90625), List.of(first, second)));
        // Marker 86168 is inside BOTH translated boxes and remains unknown.
        assertNull(DungeonDebuffTracker.uniqueTarget(new Vec3(76.53125, 222.3125, 38.75), List.of(first, second)));
        var nearBoundary = new DungeonDebuffTracker.Target(UUID.randomUUID(), Vec3.ZERO,
            new AABB(76.48, 221, 38.8, 77.18, 223.4, 39.5), false);
        assertNull(DungeonDebuffTracker.uniqueTarget(new Vec3(76.46875, 222.59375, 38.90625), List.of(first, nearBoundary)));
    }

    @Test public void simultaneousSprayedMobsKeepIndependentHighlightsAndExpiry() {
        var first = new DungeonDebuffTracker.Target(UUID.randomUUID(), Vec3.ZERO, new AABB(-.3, 0, -.3, .3, 2, .3), false);
        var second = new DungeonDebuffTracker.Target(UUID.randomUUID(), new Vec3(.7, 0, 0), new AABB(.4, 0, -.3, 1, 2, .3), false);
        var unsprayed = new DungeonDebuffTracker.Target(UUID.randomUUID(), new Vec3(0, 0, 1.5), new AABB(-.3, 0, 1.2, .3, 2, 1.8), false);
        var candidates = List.of(first, second, unsprayed);
        var tracker = new DungeonDebuffTracker();
        UUID a = DungeonDebuffTracker.uniqueTarget(new Vec3(0, 1, 0), candidates);
        UUID b = DungeonDebuffTracker.uniqueTarget(new Vec3(.7, 1, 0), candidates);
        assertEquals(first.uuid(), a);
        assertEquals(second.uuid(), b);
        tracker.spray(a, 0);
        tracker.spray(b, 0);
        assertEquals(java.util.Set.of(a, b), tracker.highlighted());
        for (int tick = 0; tick < 50; tick++) tracker.advance();
        tracker.spray(a, tracker.tick());
        for (int tick = 0; tick < 50; tick++) tracker.advance();
        assertEquals(java.util.Set.of(a), tracker.highlighted());
        for (int tick = 0; tick < 50; tick++) tracker.advance();
        assertTrue(tracker.highlighted().isEmpty());
    }

    @Test
    public void locationAndMemoryBoundsDoNotInventDragonState() {
        assertEquals(Statue.PURPLE, DungeonDebuffTracker.statueAt(new Vec3(56, 14, 125)));
        assertNull(DungeonDebuffTracker.statueAt(new Vec3(56, 100, 125)));
        assertNull(DungeonDebuffTracker.statueAt(Vec3.ZERO));
        var tracker = new DungeonDebuffTracker();
        for (int i = 0; i < 300; i++) tracker.spray(new UUID(0, i), 0);
        assertEquals(DungeonDebuffTracker.MAX_MARKERS, tracker.highlighted().size());
        UUID uuid = UUID.randomUUID();
        var dragon = tracker.spawn(uuid, Statue.RED, new Vec3(32, 6, 59));
        for (int i = 0; i < 300; i++) tracker.arrowHit();
        for (int i = 0; i < 3; i++) tracker.advance();
        assertEquals(128, dragon.arrows.count);
        assertTrue(dragon.arrows.describe().contains("first 64"));
        assertFalse(dragon.arrows.describe().contains("Infinity"));
    }

    @Test
    public void respawnOfSameColorResetsArrowsSprayAndHudTime() {
        var tracker = new DungeonDebuffTracker();
        UUID first = UUID.randomUUID();
        tracker.spawn(first, Statue.RED, new Vec3(32, 6, 59));
        tracker.arrowHit();
        tracker.spray(first, 0);
        for (int i = 0; i < 3; i++) tracker.advance();
        tracker.end(first, true);
        var lines = DragonDebuffHud.lines(tracker.latestDragons(), tracker.tick());
        assertTrue(lines.stream().anyMatch(line -> line.getString().equals("Red: Time: 0.15s | Arrows: 1 | Sprayed: 0t")));
        UUID nextId = UUID.randomUUID();
        var next = tracker.spawn(nextId, Statue.RED, new Vec3(32, 6, 59));
        tracker.spawn(UUID.randomUUID(), Statue.BLUE);
        assertEquals(2, tracker.latestDragons().size());
        assertEquals(0, tracker.latestDragons().getFirst().arrows.count);
        assertFalse(tracker.latestDragons().getFirst().ended);
        assertEquals(-1, next.sprayTick);
        assertEquals(3, next.spawnTick);
        assertFalse(next.resultAnnounced);
        tracker.advance();
        assertTrue(tracker.spray(nextId, tracker.tick()));
        tracker.arrowHit();
        for (int i = 0; i < 2; i++) tracker.advance();
        assertEquals(1, next.arrows.count);
        assertEquals(1, next.sprayTick);
    }

    @Test
    public void iceBoxSizeExpandsEveryDimensionAroundItsCenterWithoutMovingTheMob() {
        var vanilla = new AABB(-10, 20, 30, -9, 22, 31);
        var expanded = IceSprayHighlightRenderer.scaledBounds(vanilla, 200);
        assertEquals(vanilla.getCenter(), expanded.getCenter());
        assertEquals(2, expanded.getXsize(), 0.00001);
        assertEquals(4, expanded.getYsize(), 0.00001);
        assertEquals(2, expanded.getZsize(), 0.00001);
        assertEquals(1, vanilla.getXsize(), 0.00001);
        assertEquals(1.5, IceSprayHighlightRenderer.scaledBounds(vanilla, 150).getXsize(), 0.00001);
        assertEquals(vanilla.inflate(0.02), IceSprayHighlightRenderer.scaledBounds(vanilla, 100));
        assertEquals(expanded, IceSprayHighlightRenderer.scaledBounds(vanilla, 900));
        assertEquals(vanilla.inflate(0.02), IceSprayHighlightRenderer.scaledBounds(vanilla, -5));
    }

    @Test
    public void pairedSpawnsChoosePurpleAtItsStatueRegardlessOfPacketOrderOrLaterMovement() {
        for (boolean purpleFirst : new boolean[] {false, true}) {
            var tracker = new DungeonDebuffTracker();
            var first = tracker.spawn(UUID.randomUUID(), purpleFirst ? Statue.PURPLE : Statue.ORANGE, new Vec3(56, 12, 120));
            tracker.advance();
            var second = tracker.spawn(UUID.randomUUID(), purpleFirst ? Statue.ORANGE : Statue.PURPLE, new Vec3(80, 100, 56));
            assertTrue(tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).isEmpty());
            assertEquals(2, tracker.displayedDragons(DragonDebuffScope.ALL_DRAGONS).size());
            tracker.advance();
            tracker.advance();
            var selected = tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).getFirst();
            assertEquals(Statue.PURPLE, selected.statue);
            assertTrue(selected.shownIn(DragonDebuffScope.NEAREST_STATUE));
            var other = purpleFirst ? second : first;
            assertFalse(other.shownIn(DragonDebuffScope.NEAREST_STATUE));
            assertTrue(other.shownIn(DragonDebuffScope.ALL_DRAGONS));
            for (int i = 0; i < 20; i++) tracker.advance();
            assertSame(selected, tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).getFirst());
        }
    }

    @Test
    public void nextWaveSelectsAgainAndDeadSelectedResultDoesNotSwitchToTheOtherDragon() {
        var tracker = new DungeonDebuffTracker();
        UUID purpleId = UUID.randomUUID();
        var purple = tracker.spawn(purpleId, Statue.PURPLE, new Vec3(56, 14, 120));
        var orange = tracker.spawn(UUID.randomUUID(), Statue.ORANGE, new Vec3(56, 14, 120));
        tracker.spray(purpleId, 0);
        tracker.arrowHit();
        tracker.end(purpleId, true); // Even a kill within the grouping window still gets the correct report.
        for (int i = 0; i < 3; i++) tracker.advance();
        assertTrue(purple.shownIn(DragonDebuffScope.NEAREST_STATUE));
        assertEquals(0, purple.sprayTick);
        assertEquals(1, purple.arrows.count);
        assertFalse(orange.shownIn(DragonDebuffScope.NEAREST_STATUE));
        assertSame(purple, tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).getFirst());
        var next = tracker.spawn(UUID.randomUUID(), Statue.RED, new Vec3(32, 14, 59));
        for (int i = 0; i < 3; i++) tracker.advance();
        assertSame(next, tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).getFirst());
        assertTrue(purple.shownIn(DragonDebuffScope.NEAREST_STATUE));
        tracker.clearDragons();
        assertTrue(tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).isEmpty());
    }

    @Test
    public void nearestSelectionUsesOnlyNewSpawnsAndRequiresKnownPlayerPosition() {
        var tracker = new DungeonDebuffTracker();
        var single = tracker.spawn(UUID.randomUUID(), Statue.ORANGE, new Vec3(32, 200, 59));
        for (int i = 0; i < 3; i++) tracker.advance();
        assertSame(single, tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).getFirst());
        tracker.spawn(UUID.randomUUID(), Statue.PURPLE, null);
        for (int i = 0; i < 3; i++) tracker.advance();
        assertTrue(tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE).isEmpty());
        assertEquals(2, tracker.displayedDragons(DragonDebuffScope.ALL_DRAGONS).size());
        tracker.reset();
        assertTrue(tracker.displayedDragons(DragonDebuffScope.ALL_DRAGONS).isEmpty());
    }
}
