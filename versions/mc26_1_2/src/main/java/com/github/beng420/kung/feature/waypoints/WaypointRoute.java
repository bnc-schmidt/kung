package com.github.beng420.kung.feature.waypoints;

import java.util.List;

/** The route rules of an ordered {@link WaypointGroup}: which waypoint is current and which ones show. */
public final class WaypointRoute {
    private WaypointRoute() { }

    /** A waypoint's place relative to the current one. Only the three named roles are drawn. */
    public enum Role {
        NONE, PREVIOUS, CURRENT, NEXT;

        public boolean shown() { return this != NONE; }
    }

    public static Role role(int index, int current, int size) {
        if (size <= 0) return Role.NONE;
        if (index == current) return Role.CURRENT;
        if (index == Math.floorMod(current - 1, size)) return Role.PREVIOUS;
        if (index == Math.floorMod(current + 1, size)) return Role.NEXT;
        return Role.NONE;
    }

    /**
     * The current index after the player stepped to a point. A waypoint within {@code radius} blocks of its centre becomes
     * current when it is allowed: the next one always, the previous one with {@code backwards}, and any other one only
     * with both {@code backwards} and {@code skipping}. The player is near when the feet ({@code py}) or the eyes
     * ({@code eyeY}, above the same x and z) are within the radius. The waypoints are checked in order, each against the
     * index the check before it left.
     */
    public static int advance(List<Waypoint> waypoints, int current, double px, double py, double pz, double eyeY,
                              double radius, boolean backwards, boolean skipping) {
        int size = waypoints.size();
        if (size == 0) return 0;
        int index = Math.clamp(current, 0, size - 1);
        double radiusSqr = radius * radius;
        for (int i = 0; i < size; i++) {
            int next = (index + 1) % size;
            int previous = Math.floorMod(index - 1, size);
            boolean reachable = (backwards || i == next) && (skipping || i == next || i == previous);
            Waypoint waypoint = waypoints.get(i);
            double nearest = Math.min(waypoint.distanceSqr(px, py, pz), waypoint.distanceSqr(px, eyeY, pz));
            if (reachable && nearest < radiusSqr) index = i;
        }
        return index;
    }

    /** Moves the route by hand, wrapping around either end. */
    public static int step(int current, int size, int delta) {
        return size <= 0 ? 0 : Math.floorMod(current + delta, size);
    }
}
