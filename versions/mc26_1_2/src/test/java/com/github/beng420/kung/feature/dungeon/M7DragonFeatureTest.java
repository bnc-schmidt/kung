package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.runtime.AppServices;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public final class M7DragonFeatureTest extends MinecraftBootstrapTest {
    @Test public void copiedDiagnosticsStayOffWithoutTheDeveloperAccount() {
        var config = new Gson().fromJson("""
            {"dungeonMap":{"m7DragonHelperEnabled":true,"devDragonDiagnosticsEnabled":true}}
            """, KungConfig.class);
        var feature = M7DragonFeature.INSTANCE;
        feature.initialize(AppServices.create(config, new DungeonStateTracker()));
        try {
            // The helper runs for everyone; only its diagnostics need the developer account.
            assertTrue(feature.isEnabled());
            assertFalse(config.dungeon.devDragonDiagnosticsEnabled());
        } finally {
            feature.shutdown();
        }
    }

    @Test public void explicitFloorWinsOverBossFallbackAndRequiresDungeonInstance() {
        for (int number = 0; number <= 7; number++) {
            for (boolean master : new boolean[] {false, true}) {
                var floor = new HypixelDungeonFloor(number, master);
                for (boolean bossEvidence : new boolean[] {false, true}) {
                    assertEquals(number == 7 && master, M7DragonFeature.contextAllowed(true, floor, bossEvidence));
                    assertFalse(M7DragonFeature.contextAllowed(false, floor, bossEvidence));
                }
            }
        }
    }

    @Test public void missingFloorNeedsWitherKingEvidenceInTheCurrentDungeon() {
        assertFalse(M7DragonFeature.contextAllowed(true, HypixelDungeonFloor.UNKNOWN, false));
        assertTrue(M7DragonFeature.contextAllowed(true, HypixelDungeonFloor.UNKNOWN, true));
        assertFalse(M7DragonFeature.contextAllowed(false, HypixelDungeonFloor.UNKNOWN, true));
    }

    @Test public void deathLineRecordsOffsetsFromTheSpawnAnchorAndTheCurrentInsideVerdict() {
        // GREEN's anchor is (27, 14, 94); offsets are what the real counting box gets fitted against.
        String line = M7DragonFeature.deathLine(1234, M7DragonTracker.Statue.GREEN,
            M7DragonTracker.Outcome.COUNTS, new Vec3(24.5, 18.0, 81.25));
        assertTrue(line, line.contains("\"tick\":1234"));
        assertTrue(line, line.contains("\"statue\":\"GREEN\""));
        assertTrue(line, line.contains("\"outcome\":\"COUNTS\""));
        assertTrue(line, line.contains("\"dx\":-2.5"));
        assertTrue(line, line.contains("\"dy\":4.0"));
        assertTrue(line, line.contains("\"dz\":-12.75"));
        assertTrue(line, line.contains("\"predictedInside\":true"));
        // A death far outside the estimate must still be recorded, flagged as outside.
        String far = M7DragonFeature.deathLine(9, M7DragonTracker.Statue.GREEN,
            M7DragonTracker.Outcome.UNKNOWN, new Vec3(24.5, 34.0, 81.25));
        assertTrue(far, far.contains("\"predictedInside\":false"));
        assertTrue(far, far.contains("\"outcome\":\"UNKNOWN\""));
    }

    @Test public void onlyKillsThatTightenTheBracketAreRecorded() {
        var inside = new M7DragonFeature.DeathSample("BLUE", true, 4.0, 3.0, 4.0);
        var outside = new M7DragonFeature.DeathSample("BLUE", false, 9.0, 3.0, 9.0);
        List<M7DragonFeature.DeathSample> held = List.of(inside, outside);

        // A counting kill nearer the anchor than one already held adds nothing.
        assertFalse(M7DragonFeature.tightensBracket(
            new M7DragonFeature.DeathSample("BLUE", true, 2.0, 1.0, 2.0), held));
        // Reaching farther out while still counting pushes the inner bound outwards.
        assertTrue(M7DragonFeature.tightensBracket(
            new M7DragonFeature.DeathSample("BLUE", true, 6.0, 3.0, 4.0), held));
        // A non-counting kill farther out than a known outside one adds nothing.
        assertFalse(M7DragonFeature.tightensBracket(
            new M7DragonFeature.DeathSample("BLUE", false, 12.0, 5.0, 12.0), held));
        // Failing to count closer in pulls the outer bound inwards.
        assertTrue(M7DragonFeature.tightensBracket(
            new M7DragonFeature.DeathSample("BLUE", false, 7.0, 2.0, 7.0), held));
        // Another statue is bracketed separately.
        assertTrue(M7DragonFeature.tightensBracket(
            new M7DragonFeature.DeathSample("RED", true, 2.0, 1.0, 2.0), held));
        // The mirrored octant is its own frontier - y is not symmetric around the anchor.
        assertTrue(M7DragonFeature.tightensBracket(
            new M7DragonFeature.DeathSample("BLUE", true, 2.0, -1.0, 2.0), held));
    }

    @Test public void parseSamplesSkipsJunkAndReadsOffsets() {
        var parsed = M7DragonFeature.parseSamples(List.of(
            "not json at all",
            // UNKNOWN is no evidence: often a count confirmed late at the same spot.
            M7DragonFeature.deathLine(8, M7DragonTracker.Statue.RED,
                M7DragonTracker.Outcome.UNKNOWN, new Vec3(31.5, 18.0, 56.0)),
            M7DragonFeature.deathLine(7, M7DragonTracker.Statue.RED,
                M7DragonTracker.Outcome.MISSED, new Vec3(31.5, 18.0, 56.0))));
        assertEquals(1, parsed.size());
        assertEquals("RED", parsed.get(0).statue());
        assertFalse(parsed.get(0).counts());
        // RED anchors at (27, 14, 59).
        assertEquals(4.5, parsed.get(0).dx(), 1e-9);
        assertEquals(-3.0, parsed.get(0).dz(), 1e-9);
    }

    @Test public void measuredRangesStillContainEveryConfirmedKill() {
        // The five COUNTS kills recorded on 2026-09-20; tightening the boxes must not exclude them.
        assertTrue(M7DragonTracker.Statue.BLUE.contains(new Vec3(80.837890625, 17.162109375, 101.564208984375)));
        assertTrue(M7DragonTracker.Statue.GREEN.contains(new Vec3(25.98779296875, 15.68701171875, 89.61376953125)));
        assertTrue(M7DragonTracker.Statue.PURPLE.contains(new Vec3(56.0, 14.0, 125.0)));
        assertTrue(M7DragonTracker.Statue.ORANGE.contains(new Vec3(79.38525390625, 18.78271484375, 61.857177734375)));
        assertTrue(M7DragonTracker.Statue.RED.contains(new Vec3(31.38330078125, 18.50244140625, 56.323974609375)));
        // The farthest counted kills along Z, and the one failure near the edge.
        assertTrue(M7DragonTracker.Statue.ORANGE.contains(new Vec3(82.57275390625, 18.93896484375, 73.419677734375)));
        assertFalse(M7DragonTracker.Statue.GREEN.contains(new Vec3(25.23779296875, 18.93701171875, 74.08251953125)));
        // Red missed 12.85 out ("You just made a terrible mistake!", then respawned) and counted 10.85 out.
        assertFalse(M7DragonTracker.Statue.RED.contains(new Vec3(39.85205078125, 18.93994140625, 48.011474609375)));
        assertTrue(M7DragonTracker.Statue.RED.contains(new Vec3(37.85, 18.9, 49.2)));
        // Odin's boxes leave out three kills the Wither King confirmed as counting - known disagreements.
        assertFalse(M7DragonTracker.Statue.GREEN.contains(new Vec3(24.89404296875, 18.87451171875, 76.45751953125)));
        assertFalse(M7DragonTracker.Statue.BLUE.contains(new Vec3(76.025390625, 18.818359375, 109.439208984375)));
        assertFalse(M7DragonTracker.Statue.PURPLE.contains(new Vec3(70.0, 19.0, 117.0)));
        // Every spawn anchor must sit in its own range, or a dragon is outside before it moves.
        for (M7DragonTracker.Statue statue : M7DragonTracker.Statue.values()) {
            assertTrue(statue.label(), statue.contains(statue.spawn()));
        }
    }

    @Test public void boxCentreKeepsTheOriginalPathsFileAndEveryPartGetsItsOwn() {
        assertEquals("m7-dragon-paths.jsonl",
            M7DragonFeature.pathsFile(com.github.beng420.kung.config.category.DungeonConfig.DragonPart.BOX));
        assertEquals("m7-dragon-paths-neck.jsonl",
            M7DragonFeature.pathsFile(com.github.beng420.kung.config.category.DungeonConfig.DragonPart.NECK));
    }

    @Test public void theTrailKeepsTheExitPointAndThenStops() {
        List<Vec3> points = new ArrayList<>();
        assertFalse(M7DragonFeature.extendTrail(points, new Vec3(0, 0, 0), true));
        // Too close to the previous point to be worth a segment.
        assertFalse(M7DragonFeature.extendTrail(points, new Vec3(0.2, 0, 0), true));
        assertEquals(1, points.size());
        assertFalse(M7DragonFeature.extendTrail(points, new Vec3(1, 0, 0), true));
        assertEquals(2, points.size());
        // Leaving the range closes the trail, but the exit point is kept - otherwise the line
        // would stop short of the boundary, which is exactly where it has to reach.
        assertTrue(M7DragonFeature.extendTrail(points, new Vec3(2, 0, 0), false));
        assertEquals(3, points.size());
        assertEquals(new Vec3(2, 0, 0), points.get(2));
    }

    @Test public void theRestingCoreSitsExactlyWhereTheFirstTrailPointWillBe() {
        // Traced at spawn: origin (27, 14, 59), box y 14 -> 22, first trail point y 18. The resting
        // core fell back to the raw anchor at y 14 and sat four blocks under the path's start.
        assertEquals(new Vec3(27, 18, 59), M7DragonFeature.spawnCentre(M7DragonTracker.Statue.RED));
        for (M7DragonTracker.Statue statue : M7DragonTracker.Statue.values()) {
            assertEquals(statue.label(), statue.spawn().y + 4.0, M7DragonFeature.spawnCentre(statue).y, 1e-9);
        }
    }
}
