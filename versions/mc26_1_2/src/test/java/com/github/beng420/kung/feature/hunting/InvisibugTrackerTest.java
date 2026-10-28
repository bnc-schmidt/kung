package com.github.beng420.kung.feature.hunting;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.hunting.InvisibugTracker.End;
import com.github.beng420.kung.feature.hunting.InvisibugTracker.Stand;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class InvisibugTrackerTest {
    @Test
    public void aCritCountsOnlyRightAboveAStand() {
        // Real bugs from the 2026-10-27 trace: 0.8 above the stand, and flying, 1.33 above and a third aside.
        Stand resting = new Stand(1, -667.25, 124.40, -43.14);
        assertEquals(resting, InvisibugTracker.source(-667.32, 125.20, -42.98, List.of(resting)));
        Stand flying = new Stand(2, -631.55, 116.27, 77.42);
        assertEquals(flying, InvisibugTracker.source(-631.22, 117.60, 77.54, List.of(flying)));
        Stand sideways = new Stand(3, -565.22, 116.78, 21.50);
        assertEquals(sideways, InvisibugTracker.source(-564.72, 118.40, 21.64, List.of(sideways)));
        // A tree being chopped: the crit is 2.7 blocks aside and 0.27 above the nearest blank stand.
        Stand tree = new Stand(4, -575.94, 114.84, -28.47);
        assertNull(InvisibugTracker.source(-575.67, 115.11, -25.75, List.of(tree)));
        // Below the stand, or far above it: not its particle.
        assertNull(InvisibugTracker.source(0, -1, 0, List.of(new Stand(5, 0, 0, 0))));
        assertNull(InvisibugTracker.source(0, 3, 0, List.of(new Stand(6, 0, 0, 0))));
        assertNull(InvisibugTracker.source(0, 1, 0, List.of()));
        // Of two stands, the nearer one to the particle owns it.
        Stand far = new Stand(7, 0.9, 0, 0);
        Stand near = new Stand(8, 0.1, 0.2, 0);
        assertEquals(near, InvisibugTracker.source(0, 1, 0, List.of(far, near)));
    }

    @Test
    public void theLinePointsAtTheNearestBugToTheCamera() {
        Stand a = new Stand(1, 10, 0, 0);
        Stand b = new Stand(2, 0, 5, 0);
        assertEquals(b, InvisibugTracker.nearest(0, 0, 0, List.of(a, b), InvisibugTracker.LINE_RANGE));
        assertNull(InvisibugTracker.nearest(0, 0, 0, List.of(new Stand(3, 40, 0, 0)), InvisibugTracker.LINE_RANGE));
    }

    @Test
    public void aStandStaysFoundWhileCritsComeAndWhileItExists() {
        InvisibugTracker tracker = new InvisibugTracker();
        tracker.seen(7, 0);
        tracker.seen(8, 0);
        assertEquals(Map.of(), tracker.prune(InvisibugTracker.EXPIRE_TICKS, id -> true));
        tracker.seen(7, 60);
        // 8 had no crit for more than the expiry; 7 had one recently.
        assertEquals(Map.of(8, "expired"), tracker.prune(InvisibugTracker.EXPIRE_TICKS + 1, id -> true));
        assertTrue(tracker.contains(7));
        assertFalse(tracker.contains(8));
        // A stand that vanished is dropped at once, however fresh its last crit.
        assertEquals(Map.of(7, "gone"), tracker.prune(61, id -> false));
        assertTrue(tracker.isEmpty());
    }

    @Test
    public void onlyMoongladeMarshCounts() {
        assertTrue(InvisibugTracker.inMarsh("Moonglade Marsh", List.of()));
        assertTrue(InvisibugTracker.inMarsh("⏣ Moonglade Marsh", List.of()));
        // The sidebar names a sub-area; the tab list still names the island.
        assertTrue(InvisibugTracker.inMarsh("Murkwater Loch", List.of("Server: m12", "§bArea: §7Moonglade Marsh")));
        assertTrue(InvisibugTracker.inMarsh("", List.of("Area: Galatea")));
        assertFalse(InvisibugTracker.inMarsh("Hub", List.of("Area: Hub")));
        assertFalse(InvisibugTracker.inMarsh("", List.of()));
        // A mention in other text is not a location row.
        assertFalse(InvisibugTracker.inMarsh("Garden", List.of("Party: Moonglade Marsh run")));
    }

    @Test
    public void theLineEndsOnTheTargetOrTheScreenEdge() {
        // Straight ahead and on screen: the point itself, with a dot.
        End ahead = InvisibugTracker.lineEnd(0.5F, -0.25F, 2F);
        assertEquals(0.25F, ahead.x(), 1.0E-5F);
        assertEquals(-0.125F, ahead.y(), 1.0E-5F);
        assertTrue(ahead.onScreen());
        // Far to the right: same direction, stopped on the edge.
        End right = InvisibugTracker.lineEnd(8F, 2F, 2F);
        assertEquals(1F, right.x(), 1.0E-5F);
        assertEquals(0.25F, right.y(), 1.0E-5F);
        assertFalse(right.onScreen());
        // Behind the camera: the direction stays, so the line says which way to turn.
        End behind = InvisibugTracker.lineEnd(-0.5F, 0.25F, -1F);
        assertEquals(-1F, behind.x(), 1.0E-5F);
        assertEquals(0.5F, behind.y(), 1.0E-5F);
        assertFalse(behind.onScreen());
        // Exactly behind has no direction: down.
        End exact = InvisibugTracker.lineEnd(0F, 0F, -1F);
        assertEquals(0F, exact.x(), 0F);
        assertEquals(-1F, exact.y(), 0F);
    }
}
