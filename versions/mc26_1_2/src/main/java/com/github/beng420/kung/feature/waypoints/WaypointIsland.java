package com.github.beng420.kung.feature.waypoints;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An island by its Hypixel id ("hub", "dynamic"), the key a waypoint group is stored under. The ids and names are the
 * ones of Hypixel's games resource, so shared Skyblocker strings land on the same islands. {@link #UNKNOWN} marks a
 * group that shows on every island. An island Kung does not list gets the id {@code area:<name>}: its groups are
 * kept and shown there, but a Skyblocker import does not know that id.
 */
public record WaypointIsland(String id, String name) {
    public static final WaypointIsland UNKNOWN = new WaypointIsland("unknown", "Everywhere");
    private static final String CUSTOM_PREFIX = "area:";
    /** The tab list names the island in an "Area:" row; a dungeon run writes "Dungeon:" instead. */
    private static final Pattern AREA_ROW = Pattern.compile("^(?:Area|Dungeon)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    private static final Map<String, WaypointIsland> BY_ID = new LinkedHashMap<>();
    private static final Map<String, WaypointIsland> BY_NAME = new LinkedHashMap<>();

    static {
        known("dynamic", "Private Island");
        known("garden", "Garden", "the garden");
        known("hub", "Hub");
        known("farming_1", "The Farming Islands");
        known("foraging_1", "The Park");
        known("combat_1", "Spider's Den");
        known("combat_2", "Blazing Fortress");
        known("combat_3", "The End");
        known("crimson_isle", "Crimson Isle");
        known("mining_1", "Gold Mine");
        known("mining_2", "Deep Caverns");
        known("mining_3", "Dwarven Mines");
        known("fishing_1", "Backwater Bayou");
        known("dungeon_hub", "Dungeon Hub");
        known("winter", "Jerry's Workshop");
        known("rift", "The Rift");
        known("dark_auction", "Dark Auction");
        known("crystal_hollows", "Crystal Hollows");
        known("dungeon", "Dungeons", "the catacombs", "catacombs", "dungeon");
        known("kuudra", "Kuudra's Hollow", "kuudra");
        known("mineshaft", "Glacite Mineshafts", "mineshaft");
        known("foraging_2", "Moonglade Marsh", "galatea");
        known("foraging_3", "Torrhus Canyon");
        known("safari", "Safari");
        known("lotus_atoll", "Lotus Atoll");
    }

    private static void known(String id, String name, String... aliases) {
        WaypointIsland island = new WaypointIsland(id, name);
        BY_ID.put(id, island);
        BY_NAME.put(name.toLowerCase(Locale.ROOT), island);
        for (String alias : aliases) BY_NAME.put(alias, island);
    }

    /** Every island Kung lists, in menu order. */
    public static List<WaypointIsland> all() {
        return List.copyOf(BY_ID.values());
    }

    /** The island for a stored or imported id; an id nobody lists keeps its text as its name. */
    public static WaypointIsland fromId(String id) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase(UNKNOWN.id)) return UNKNOWN;
        String key = id.trim().toLowerCase(Locale.ROOT);
        WaypointIsland known = BY_ID.get(key);
        if (known != null) return known;
        return new WaypointIsland(key, key.startsWith(CUSTOM_PREFIX) ? key.substring(CUSTOM_PREFIX.length()) : key);
    }

    /** The island for a tab-list area name such as "Hub", "The Garden" or "Kuudra's Hollow (T5)". */
    public static WaypointIsland fromAreaName(String area) {
        String name = SUFFIX.matcher(HypixelLocation.clean(area)).replaceFirst("").trim();
        if (name.isEmpty()) return UNKNOWN;
        String key = name.toLowerCase(Locale.ROOT);
        WaypointIsland known = BY_NAME.get(key);
        return known != null ? known : new WaypointIsland(CUSTOM_PREFIX + key, name);
    }

    /** The island named by the tab list's "Area:" row, or {@link #UNKNOWN} while no such row has arrived. */
    public static WaypointIsland current(List<String> visibleLines) {
        for (String raw : visibleLines) {
            Matcher row = AREA_ROW.matcher(HypixelLocation.clean(raw));
            if (row.matches()) return fromAreaName(row.group(1));
        }
        return UNKNOWN;
    }

    public boolean isUnknown() {
        return id.equals(UNKNOWN.id);
    }
}
