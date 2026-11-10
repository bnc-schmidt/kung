package com.github.beng420.kung.feature.waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;

/**
 * The edits behind {@code /kung waypoints}, on one island at a time. Groups and waypoints are addressed by their
 * 1-based place in the list that {@link #list} prints. Every edit is saved at once; a message ends with the save
 * error when the write failed. Bad input throws {@link IllegalArgumentException} with a sentence for the player.
 */
public final class WaypointEditor {
    private final WaypointStore store;
    /** The island being edited; null follows the island the player is on. */
    private WaypointIsland chosen;

    public WaypointEditor(WaypointStore store) {
        this.store = store;
    }

    public WaypointIsland island(WaypointIsland current) {
        return chosen != null ? chosen : current;
    }

    /** Picks the island to edit: {@code here}, {@code everywhere}, a Hypixel id, or an area name from the tab list. */
    public String chooseIsland(String text, WaypointIsland current) {
        String key = text.strip();
        if (key.equalsIgnoreCase("here")) {
            chosen = null;
            return "Editing the island you are on: " + current.name() + ".";
        }
        WaypointIsland island = key.equalsIgnoreCase("everywhere") ? WaypointIsland.UNKNOWN
            : WaypointIsland.all().stream().filter(known -> known.id().equalsIgnoreCase(key)).findFirst()
                .orElseGet(() -> WaypointIsland.fromAreaName(key));
        chosen = island;
        return "Editing " + island.name() + ".";
    }

    /** The lines that list the groups of the island being edited. */
    public List<String> list(WaypointIsland current) {
        WaypointIsland island = island(current);
        List<WaypointGroup> groups = store.groupsOn(island.id());
        List<String> lines = new ArrayList<>();
        lines.add(island.name() + ": " + groups.size() + (groups.size() == 1 ? " group" : " groups")
            + (chosen == null ? "" : " (not the island you are on)"));
        for (int g = 0; g < groups.size(); g++) {
            WaypointGroup group = groups.get(g);
            lines.add((g + 1) + ". " + group.name() + " - " + group.type().label()
                + (group.ordered() ? ", route" : "") + (group.throughWalls() ? ", through walls" : "")
                + ", " + group.waypoints().size() + " waypoints");
            for (int w = 0; w < group.waypoints().size(); w++) {
                Waypoint waypoint = group.waypoints().get(w);
                lines.add("   " + (g + 1) + "." + (w + 1) + " " + (waypoint.enabled() ? "on " : "off ") + waypoint.name()
                    + " (" + waypoint.x() + " " + waypoint.y() + " " + waypoint.z() + ") " + hex(waypoint.rgb()));
            }
        }
        return lines;
    }

    public String newGroup(String name, WaypointType type, WaypointIsland current) {
        WaypointIsland island = island(current);
        store.add(new WaypointGroup(name, island.id(), type));
        return saved("Group " + store.groupsOn(island.id()).size() + " \"" + name + "\" added to " + island.name() + ".");
    }

    public String removeGroup(int g, WaypointIsland current) {
        WaypointGroup group = group(g, current);
        store.remove(group);
        return saved("Group \"" + group.name() + "\" removed with " + group.waypoints().size() + " waypoints.");
    }

    public String renameGroup(int g, String name, WaypointIsland current) {
        group(g, current).setName(name);
        return saved("Group " + g + " is now \"" + name + "\".");
    }

    public String setOrdered(int g, boolean ordered, WaypointIsland current) {
        group(g, current).setOrdered(ordered);
        return saved("Group " + g + (ordered ? " is a route now." : " is a plain group now."));
    }

    public String setThroughWalls(int g, boolean throughWalls, WaypointIsland current) {
        group(g, current).setThroughWalls(throughWalls);
        return saved("Group " + g + (throughWalls ? " shows through walls." : " hides behind walls."));
    }

    public String setType(int g, WaypointType type, WaypointIsland current) {
        group(g, current).setType(type);
        return saved("Group " + g + " is drawn as " + type.label() + ".");
    }

    /** Switches every waypoint of a group on, or off when they are all on already. */
    public String toggleGroup(int g, WaypointIsland current) {
        WaypointGroup group = group(g, current);
        boolean enable = !group.fullyEnabled();
        group.waypoints().replaceAll(waypoint -> waypoint.withEnabled(enable));
        return saved("Group " + g + (enable ? ": all waypoints on." : ": all waypoints off."));
    }

    public String addWaypoint(int g, String name, BlockPos pos, WaypointIsland current) {
        WaypointGroup group = group(g, current);
        String label = name == null || name.isBlank() ? "Waypoint " + (group.waypoints().size() + 1) : name;
        group.waypoints().add(Waypoint.at(pos, label));
        return saved("Waypoint " + g + "." + group.waypoints().size() + " \"" + label + "\" added at "
            + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".");
    }

    /**
     * The Add Waypoint Key: a waypoint at {@code pos} in every marked group that shows on the island, named as the
     * editor names a new one. Throws when no group is marked there.
     */
    public String addToMarked(BlockPos pos, WaypointIsland current, boolean active) {
        List<WaypointGroup> marked = store.visibleOn(current.id(), active).stream().filter(WaypointGroup::marked).toList();
        if (marked.isEmpty()) {
            throw new IllegalArgumentException("No marked group on " + current.name() + ". Mark one with the * in the waypoint editor.");
        }
        List<String> names = new ArrayList<>();
        for (WaypointGroup group : marked) {
            group.waypoints().add(Waypoint.at(pos, "Waypoint " + (group.waypoints().size() + 1)));
            names.add("\"" + group.name() + "\"");
        }
        return saved("Waypoint added at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " to " + String.join(", ", names) + ".");
    }

    public String removeWaypoint(int g, int w, WaypointIsland current) {
        WaypointGroup group = group(g, current);
        Waypoint removed = group.waypoints().remove(index(group, w));
        return saved("Waypoint \"" + removed.name() + "\" removed.");
    }

    /** Moves a waypoint up (-1) or down (+1) its group; the route order is the list order. */
    public String moveWaypoint(int g, int w, int delta, WaypointIsland current) {
        WaypointGroup group = group(g, current);
        int from = index(group, w);
        int to = Math.clamp(from + delta, 0, group.waypoints().size() - 1);
        group.waypoints().add(to, group.waypoints().remove(from));
        return saved("Waypoint is now number " + (to + 1) + " of group " + g + ".");
    }

    public String renameWaypoint(int g, int w, String name, WaypointIsland current) {
        return replace(g, w, current, waypoint -> waypoint.withName(name), "named \"" + name + "\"");
    }

    public String moveWaypointTo(int g, int w, BlockPos pos, WaypointIsland current) {
        return replace(g, w, current, waypoint -> waypoint.withPos(pos.getX(), pos.getY(), pos.getZ()),
            "at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    /** Sets the color from {@code #RRGGBB} (or {@code RRGGBB}); the opacity stays. */
    public String colorWaypoint(int g, int w, String hex, WaypointIsland current) {
        String digits = hex.strip().startsWith("#") ? hex.strip().substring(1) : hex.strip();
        int rgb;
        try {
            if (digits.length() != 6) throw new NumberFormatException();
            rgb = Integer.parseInt(digits, 16);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("A color is six hex digits, like #00FF00.");
        }
        return replace(g, w, current, waypoint -> waypoint.withColor(rgb, waypoint.alpha()), "colored " + hex(rgb));
    }

    /** Sets the fill opacity in percent. */
    public String alphaWaypoint(int g, int w, int percent, WaypointIsland current) {
        float alpha = Math.clamp(percent, 0, 100) / 100F;
        return replace(g, w, current, waypoint -> waypoint.withColor(waypoint.rgb(), alpha), "at " + percent + "% opacity");
    }

    public String toggleWaypoint(int g, int w, WaypointIsland current) {
        WaypointGroup group = group(g, current);
        int index = index(group, w);
        Waypoint waypoint = group.waypoints().get(index);
        group.waypoints().set(index, waypoint.withEnabled(!waypoint.enabled()));
        return saved("Waypoint " + g + "." + w + (waypoint.enabled() ? " is off." : " is on."));
    }

    /** The share string for one group, or for all groups of the island when {@code g} is 0. */
    public String export(int g, WaypointIsland current) {
        List<WaypointGroup> groups = store.groupsOn(island(current).id());
        if (g > 0) groups = List.of(group(g, current));
        if (groups.isEmpty()) throw new IllegalArgumentException("Nothing to export on " + island(current).name() + ".");
        return WaypointShare.encode(groups);
    }

    /**
     * Adds the groups of a share string, each on the island it names (a group with none shows everywhere); with
     * {@code here} every group goes to the island being edited.
     */
    public String importText(String text, boolean here, WaypointIsland current) {
        WaypointShare.Imported imported = WaypointShare.decode(text);
        WaypointIsland island = island(current);
        List<WaypointGroup> groups = new ArrayList<>();
        int waypoints = 0;
        for (WaypointGroup group : imported.groups()) {
            if (here) group.setIslandId(island.id());
            waypoints += group.waypoints().size();
            groups.add(group);
        }
        if (groups.isEmpty()) throw new IllegalArgumentException("The string holds no waypoint groups.");
        store.addAll(groups);
        String left = imported.skipped() > 0 ? " " + imported.skipped() + " entries could not be read and were left out." : "";
        return saved("Imported " + waypoints + " waypoints in " + groups.size() + " groups." + left);
    }

    private String replace(int g, int w, WaypointIsland current, java.util.function.UnaryOperator<Waypoint> change, String what) {
        WaypointGroup group = group(g, current);
        int index = index(group, w);
        group.waypoints().set(index, change.apply(group.waypoints().get(index)));
        return saved("Waypoint " + g + "." + w + " " + what + ".");
    }

    private WaypointGroup group(int g, WaypointIsland current) {
        List<WaypointGroup> groups = store.groupsOn(island(current).id());
        if (g < 1 || g > groups.size()) {
            throw new IllegalArgumentException("No group " + g + " on " + island(current).name() + " (it has " + groups.size() + ").");
        }
        return groups.get(g - 1);
    }

    private static int index(WaypointGroup group, int w) {
        if (w < 1 || w > group.waypoints().size()) {
            throw new IllegalArgumentException("No waypoint " + w + " in group \"" + group.name() + "\" (it has "
                + group.waypoints().size() + ").");
        }
        return w - 1;
    }

    /** Saves after an edit made in place; the message gets the reason when the write failed. */
    private String saved(String message) {
        return store.save() ? message : message + " Not saved: " + store.saveError() + ".";
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }
}
