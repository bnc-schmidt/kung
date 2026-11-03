package com.github.beng420.kung.feature.waypoints;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints that share an island, a draw type and a through-walls choice. An {@code ordered} group is a route: only the
 * previous, current and next waypoint show, and walking up to the next one moves the route on (see {@link WaypointRoute}).
 */
public final class WaypointGroup {
    private String name;
    private String islandId;
    private boolean ordered;
    private boolean throughWalls = true;
    private WaypointType type;
    private final List<Waypoint> waypoints = new ArrayList<>();
    /** Where the player is on the route; never saved, back to the first waypoint on every new world. */
    private transient int currentIndex;

    public WaypointGroup(String name, String islandId, WaypointType type) {
        this.name = name == null ? "" : name;
        this.islandId = islandId == null || islandId.isBlank() ? WaypointIsland.UNKNOWN.id() : islandId;
        this.type = type == null ? WaypointType.WAYPOINT : type;
    }

    public String name() { return name; }
    public void setName(String value) { name = value == null ? "" : value; }
    public String islandId() { return islandId; }
    public void setIslandId(String value) { islandId = WaypointIsland.fromId(value).id(); }
    public boolean ordered() { return ordered; }
    public void setOrdered(boolean value) { ordered = value; currentIndex = 0; }
    public boolean throughWalls() { return throughWalls; }
    public void setThroughWalls(boolean value) { throughWalls = value; }
    public WaypointType type() { return type; }
    public void setType(WaypointType value) { type = value == null ? WaypointType.WAYPOINT : value; }

    /** The live list; callers that change it tell the store to save. */
    public List<Waypoint> waypoints() { return waypoints; }

    public int currentIndex() { return waypoints.isEmpty() ? 0 : Math.clamp(currentIndex, 0, waypoints.size() - 1); }
    public void setCurrentIndex(int value) { currentIndex = waypoints.isEmpty() ? 0 : Math.floorMod(value, waypoints.size()); }
    public void resetRoute() { currentIndex = 0; }

    /** True while the group has waypoints and all of them are switched on. */
    public boolean fullyEnabled() {
        return !waypoints.isEmpty() && waypoints.stream().allMatch(Waypoint::enabled);
    }

    public WaypointGroup copy() {
        WaypointGroup copy = new WaypointGroup(name, islandId, type);
        copy.ordered = ordered;
        copy.throughWalls = throughWalls;
        copy.waypoints.addAll(waypoints);
        copy.currentIndex = currentIndex;
        return copy;
    }
}
