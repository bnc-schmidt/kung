package com.github.beng420.kung.feature.waypoints;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class WaypointRouteTest {
    /** Five waypoints on a line along x, centres at 0.5, 10.5, 20.5, 30.5, 40.5. */
    private static List<Waypoint> line() {
        List<Waypoint> waypoints = new ArrayList<>();
        for (int i = 0; i < 5; i++) waypoints.add(new Waypoint(i * 10, 64, 0, "w" + i, 0, 0.5F, true));
        return waypoints;
    }

    @Test
    public void onlyPreviousCurrentAndNextShow() {
        assertEquals(WaypointRoute.Role.CURRENT, WaypointRoute.role(2, 2, 5));
        assertEquals(WaypointRoute.Role.PREVIOUS, WaypointRoute.role(1, 2, 5));
        assertEquals(WaypointRoute.Role.NEXT, WaypointRoute.role(3, 2, 5));
        assertEquals(WaypointRoute.Role.NONE, WaypointRoute.role(0, 2, 5));
        assertEquals(WaypointRoute.Role.NONE, WaypointRoute.role(4, 2, 5));
    }

    @Test
    public void theRouteWrapsAroundItsEnds() {
        assertEquals(WaypointRoute.Role.PREVIOUS, WaypointRoute.role(4, 0, 5));
        assertEquals(WaypointRoute.Role.NEXT, WaypointRoute.role(0, 4, 5));
        assertEquals(WaypointRoute.Role.CURRENT, WaypointRoute.role(0, 0, 1));
        assertEquals(WaypointRoute.Role.NONE, WaypointRoute.role(0, 0, 0));
    }

    @Test
    public void walkingUpToTheNextWaypointMovesTheRouteOn() {
        assertEquals(1, WaypointRoute.advance(line(), 0, 10.5, 64.5, 0.5, 2, true, true));
        assertEquals(0, WaypointRoute.advance(line(), 0, 5.5, 64.5, 0.5, 2, true, true));
        assertEquals("radius is exclusive", 0, WaypointRoute.advance(line(), 0, 12.5, 64.5, 0.5, 2, true, true));
    }

    @Test
    public void withBothOptionsOffOnlyTheNextWaypointCounts() {
        assertEquals(1, WaypointRoute.advance(line(), 0, 10.5, 64.5, 0.5, 2, false, false));
        assertEquals("going back is off", 2, WaypointRoute.advance(line(), 2, 10.5, 64.5, 0.5, 2, false, true));
        assertEquals("skipping ahead needs going backwards too", 0, WaypointRoute.advance(line(), 0, 30.5, 64.5, 0.5, 2, false, true));
    }

    @Test
    public void goingBackwardsAllowsThePreviousWaypointAndSkippingAllowsAnyOther() {
        assertEquals(1, WaypointRoute.advance(line(), 2, 10.5, 64.5, 0.5, 2, true, false));
        assertEquals("a far waypoint is a skip", 2, WaypointRoute.advance(line(), 2, 40.5, 64.5, 0.5, 2, true, false));
        assertEquals(4, WaypointRoute.advance(line(), 2, 40.5, 64.5, 0.5, 2, true, true));
        assertEquals(0, WaypointRoute.advance(line(), 2, 0.5, 64.5, 0.5, 2, true, true));
    }

    @Test
    public void anEmptyRouteStaysAtZeroAndAStaleIndexIsClamped() {
        assertEquals(0, WaypointRoute.advance(List.of(), 3, 0, 0, 0, 2, true, true));
        assertEquals(4, WaypointRoute.advance(line(), 99, 1000, 64, 0, 2, true, true));
    }

    @Test
    public void stepWrapsInBothDirections() {
        assertEquals(1, WaypointRoute.step(0, 5, 1));
        assertEquals(4, WaypointRoute.step(0, 5, -1));
        assertEquals(0, WaypointRoute.step(4, 5, 1));
        assertEquals(0, WaypointRoute.step(0, 0, 1));
    }
}
