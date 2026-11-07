package com.github.beng420.kung.feature.waypoints;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Waypoints that share their areas, a draw type and a through-walls choice. The areas are islands: the group shows on
 * each ticked island, and a group with none ticked shows everywhere. An {@code ordered} group is a route: only the
 * previous, current and next waypoint show, and walking up to the next one moves the route on (see {@link WaypointRoute}).
 */
public final class WaypointGroup {
    private String name;
    /** Island ids the group shows on; empty means everywhere. */
    private final Set<String> islands = new LinkedHashSet<>();
    private boolean ordered;
    private boolean throughWalls = true;
    private boolean marked;
    private WaypointWallStyle wallStyle = WaypointWallStyle.ICON;
    private WaypointType type;
    private final List<Waypoint> waypoints = new ArrayList<>();
    /** Where the player is on the route; never saved, back to the first waypoint on every new world. */
    private transient int currentIndex;

    public WaypointGroup(String name, String islandId, WaypointType type) {
        this.name = name == null ? "" : name;
        setIslandId(islandId);
        this.type = type == null ? WaypointType.WAYPOINT : type;
    }

    public String name() { return name; }
    public void setName(String value) { name = value == null ? "" : value; }

    /** The first island of the group, or the "unknown" id when it shows everywhere. */
    public String islandId() { return islands.isEmpty() ? WaypointIsland.UNKNOWN.id() : islands.iterator().next(); }

    /** Puts the group on one island; the "unknown" id or a blank one puts it everywhere. */
    public void setIslandId(String value) {
        islands.clear();
        addIsland(value);
    }

    /** The ids of the islands the group shows on, in the order they were ticked; empty means everywhere. */
    public List<String> islands() { return List.copyOf(islands); }

    public void setIslands(Collection<String> ids) {
        islands.clear();
        ids.forEach(this::addIsland);
    }

    private void addIsland(String id) {
        WaypointIsland island = WaypointIsland.fromId(id);
        if (!island.isUnknown()) islands.add(island.id());
    }

    public boolean onIsland(String islandId) { return islands.contains(WaypointIsland.fromId(islandId).id()); }

    public void setOnIsland(String islandId, boolean on) {
        String id = WaypointIsland.fromId(islandId).id();
        if (on) islands.add(id);
        else islands.remove(id);
    }

    /** True when the group is listed under this island: ticked there, or, for the "unknown" id, ticked nowhere. */
    public boolean listedOn(String islandId) {
        return WaypointIsland.fromId(islandId).isUnknown() ? islands.isEmpty() : onIsland(islandId);
    }

    /** True when the group draws on this island: ticked there, or ticked nowhere. */
    public boolean shownOn(String islandId) { return islands.isEmpty() || onIsland(islandId); }

    public boolean ordered() { return ordered; }
    public void setOrdered(boolean value) { ordered = value; currentIndex = 0; }
    /** True for a group the player starred: the Add Waypoint Key puts new waypoints in it. */
    public boolean marked() { return marked; }
    public void setMarked(boolean value) { marked = value; }
    public boolean throughWalls() { return throughWalls; }
    public void setThroughWalls(boolean value) { throughWalls = value; }
    public WaypointWallStyle wallStyle() { return wallStyle; }
    public void setWallStyle(WaypointWallStyle value) { wallStyle = value == null ? WaypointWallStyle.ICON : value; }
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
        WaypointGroup copy = new WaypointGroup(name, WaypointIsland.UNKNOWN.id(), type);
        copy.islands.addAll(islands);
        copy.ordered = ordered;
        copy.throughWalls = throughWalls;
        copy.marked = marked;
        copy.wallStyle = wallStyle;
        copy.waypoints.addAll(waypoints);
        copy.currentIndex = currentIndex;
        return copy;
    }
}
