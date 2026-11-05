package com.github.beng420.kung.feature.waypoints;

import com.github.beng420.kung.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Waypoint groups as JSON, in the field layout of the Skyblocker share format: a group has name, island, ordered,
 * renderThroughWalls, waypointType and waypoints; a waypoint has pos, name, colorComponents, alpha and shouldRender.
 * Kung's own file wraps this array in a versioned object (see {@link WaypointStore}); the share string is the bare array.
 * Reading is lenient: a group or waypoint that cannot be read is counted and skipped, never thrown for.
 */
final class WaypointCodec {
    private WaypointCodec() { }

    /** Counts what a read had to leave out. */
    static final class Skipped {
        int groups;
        int waypoints;

        int total() { return groups + waypoints; }
    }

    static JsonArray writeGroups(List<WaypointGroup> groups) {
        JsonArray array = new JsonArray();
        for (WaypointGroup group : groups) array.add(writeGroup(group));
        return array;
    }

    static JsonObject writeGroup(WaypointGroup group) {
        JsonObject json = new JsonObject();
        json.addProperty("name", group.name());
        json.addProperty("island", group.islandId());
        JsonArray waypoints = new JsonArray();
        for (Waypoint waypoint : group.waypoints()) waypoints.add(writeWaypoint(waypoint));
        json.add("waypoints", waypoints);
        json.addProperty("ordered", group.ordered());
        json.addProperty("renderThroughWalls", group.throughWalls());
        json.addProperty("waypointType", group.type().serialName());
        return json;
    }

    private static JsonObject writeWaypoint(Waypoint waypoint) {
        JsonObject json = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(waypoint.x());
        pos.add(waypoint.y());
        pos.add(waypoint.z());
        json.add("pos", pos);
        json.addProperty("name", waypoint.name());
        JsonArray color = new JsonArray();
        color.add((waypoint.rgb() >> 16 & 0xFF) / 255F);
        color.add((waypoint.rgb() >> 8 & 0xFF) / 255F);
        color.add((waypoint.rgb() & 0xFF) / 255F);
        json.add("colorComponents", color);
        json.addProperty("alpha", waypoint.alpha());
        json.addProperty("shouldRender", waypoint.enabled());
        return json;
    }

    static List<WaypointGroup> readGroups(JsonArray array, Skipped skipped) {
        List<WaypointGroup> groups = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                groups.add(readGroup(element.getAsJsonObject(), skipped));
            } catch (RuntimeException exception) {
                skipped.groups++;
            }
        }
        return groups;
    }

    private static WaypointGroup readGroup(JsonObject json, Skipped skipped) {
        WaypointGroup group = new WaypointGroup(Json.string(json, "name", ""), WaypointIsland.fromId(Json.string(json, "island", "")).id(),
            WaypointType.parse(Json.string(json, "waypointType", ""), WaypointType.WAYPOINT));
        group.setOrdered(Json.bool(json, "ordered", false));
        group.setThroughWalls(Json.bool(json, "renderThroughWalls", true));
        for (JsonElement element : json.has("waypoints") ? json.getAsJsonArray("waypoints") : new JsonArray()) {
            try {
                group.waypoints().add(readWaypoint(element.getAsJsonObject()));
            } catch (RuntimeException exception) {
                skipped.waypoints++;
            }
        }
        return group;
    }

    private static Waypoint readWaypoint(JsonObject json) {
        int[] pos = position(json.get("pos"));
        int rgb = Waypoint.DEFAULT_RGB;
        JsonElement color = json.get("colorComponents");
        if (color != null && color.isJsonArray() && color.getAsJsonArray().size() == 3) {
            JsonArray parts = color.getAsJsonArray();
            rgb = channel(parts.get(0)) << 16 | channel(parts.get(1)) << 8 | channel(parts.get(2));
        }
        float alpha = json.has("alpha") && json.get("alpha").isJsonPrimitive() ? json.get("alpha").getAsFloat() : Waypoint.DEFAULT_ALPHA;
        return new Waypoint(pos[0], pos[1], pos[2], plainText(json.get("name")), rgb, alpha, Json.bool(json, "shouldRender", true));
    }

    /** A position as [x, y, z] or as {"x":, "y":, "z":}. */
    private static int[] position(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() != 3) throw new IllegalArgumentException("Expected 3 coordinates");
            return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        }
        JsonObject object = element.getAsJsonObject();
        return new int[] {object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt()};
    }

    static int channel(JsonElement element) {
        return Math.round(Math.clamp(element.getAsFloat(), 0F, 1F) * 255F);
    }

    /** The text of a stored name: a plain string, or the text parts of a chat component. */
    static String plainText(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        StringBuilder text = new StringBuilder();
        if (element.isJsonArray()) {
            for (JsonElement part : element.getAsJsonArray()) text.append(plainText(part));
        } else {
            JsonObject object = element.getAsJsonObject();
            text.append(plainText(object.get("text")));
            text.append(plainText(object.get("extra")));
        }
        return text.toString();
    }
}
