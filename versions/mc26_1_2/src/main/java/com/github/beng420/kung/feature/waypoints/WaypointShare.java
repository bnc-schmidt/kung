package com.github.beng420.kung.feature.waypoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Waypoints as text that can be pasted to another player: the Skyblocker share format, {@code [Skyblocker-Waypoint-Data-V1]}
 * followed by the group array as GZIP and Base64. Kung writes and reads that format, and also reads Skytils and
 * Coleweight waypoints, as Skyblocker does. Skyblocker's old ordered-list format is not supported.
 */
public final class WaypointShare {
    static final String PREFIX = "[Skyblocker-Waypoint-Data-V1]";

    private WaypointShare() { }

    /** What an import found: the groups, and how many entries of the text had to be left out. */
    public record Imported(List<WaypointGroup> groups, int skipped) { }

    public static String encode(List<WaypointGroup> groups) {
        byte[] json = WaypointCodec.writeGroups(groups).toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json);
        } catch (IOException exception) {
            throw new IllegalStateException(exception); // A byte array cannot fail to take bytes.
        }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /**
     * Reads Skytils waypoints: Base64 of a JSON object with {@code categories}, optionally gzipped and led by
     * {@code <Skytils-Waypoint-Data>(V1):}, or a bare list of waypoints. A group without a known island gets {@code defaultIsland}.
     */
    public static Imported decodeSkytils(String text, WaypointIsland defaultIsland) {
        String trimmed = text == null ? "" : text.strip();
        try {
            byte[] bytes;
            if (trimmed.startsWith("<Skytils-Waypoint-Data>(V")) {
                int version = Integer.parseInt(trimmed.substring(25, trimmed.indexOf(')')));
                if (version != 1) throw new IllegalArgumentException("Unknown Skytils waypoint data version " + version);
                bytes = gunzip(trimmed.substring(trimmed.indexOf(':') + 1).replaceAll("\\s+", ""));
            } else {
                bytes = Base64.getDecoder().decode(trimmed.replaceAll("\\s+", ""));
            }
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            WaypointCodec.Skipped skipped = new WaypointCodec.Skipped();
            List<WaypointGroup> groups = new ArrayList<>();
            if (root.isJsonObject()) {
                for (JsonElement element : root.getAsJsonObject().getAsJsonArray("categories")) {
                    try {
                        JsonObject json = element.getAsJsonObject();
                        WaypointGroup group = new WaypointGroup(WaypointCodec.plainText(json.get("name")),
                            WaypointIsland.fromId(WaypointCodec.plainText(json.get("island"))).id(), WaypointType.WAYPOINT);
                        readSkytilsWaypoints(json.getAsJsonArray("waypoints"), group, skipped);
                        groups.add(group);
                    } catch (RuntimeException exception) {
                        skipped.groups++;
                    }
                }
            } else {
                WaypointGroup group = new WaypointGroup("New Group", defaultIsland.id(), WaypointType.WAYPOINT);
                readSkytilsWaypoints(root.getAsJsonArray(), group, skipped);
                groups.add(group);
            }
            for (WaypointGroup group : groups) {
                if (group.islandId().equals(WaypointIsland.UNKNOWN.id())) group.setIslandId(defaultIsland.id());
            }
            return new Imported(groups, skipped.total());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("That is not Skytils waypoint data", exception);
        }
    }

    private static void readSkytilsWaypoints(JsonArray array, WaypointGroup group, WaypointCodec.Skipped skipped) {
        for (JsonElement element : array) {
            try {
                JsonObject json = element.getAsJsonObject();
                int argb = json.has("color") ? json.get("color").getAsInt() : 0x8000FF00;
                // Skytils writes an opacity of 0 for "unset"; Skyblocker reads that as the default.
                float alpha = (argb >>> 24) == 0 ? Waypoint.DEFAULT_ALPHA : (argb >>> 24) / 255F;
                group.waypoints().add(new Waypoint(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt(),
                    WaypointCodec.plainText(json.get("name")), argb & 0xFFFFFF, alpha, json.get("enabled").getAsBoolean()));
            } catch (RuntimeException exception) {
                skipped.waypoints++;
            }
        }
    }

    /** Reads a Coleweight (Snoopy) list: {@code [{x, y, z, r, g, b, options: {name}}]}, as one route named Coleweight. */
    public static Imported decodeColeweight(String text, WaypointIsland defaultIsland) {
        try {
            WaypointGroup group = new WaypointGroup("Coleweight", defaultIsland.id(), WaypointType.WAYPOINT);
            group.setOrdered(true);
            WaypointCodec.Skipped skipped = new WaypointCodec.Skipped();
            for (JsonElement element : JsonParser.parseString(text == null ? "" : text.strip()).getAsJsonArray()) {
                try {
                    JsonObject json = element.getAsJsonObject();
                    String name = json.has("options") && json.getAsJsonObject("options").has("name")
                        ? WaypointCodec.plainText(json.getAsJsonObject("options").get("name")) : "New Waypoint";
                    int rgb = channel(json, "r", 0) << 16 | channel(json, "g", 255) << 8 | channel(json, "b", 0);
                    group.waypoints().add(new Waypoint(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt(),
                        name, rgb, Waypoint.DEFAULT_ALPHA, true));
                } catch (RuntimeException exception) {
                    skipped.waypoints++;
                }
            }
            return new Imported(List.of(group), skipped.total());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("That is not a Coleweight waypoint list", exception);
        }
    }

    private static int channel(JsonObject json, String key, int fallback) {
        return json.has(key) ? WaypointCodec.channel(json.get(key)) : fallback;
    }

    private static byte[] gunzip(String base64) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return gzip.readAllBytes();
        }
    }

    /** Reads a share string; {@link IllegalArgumentException} carries the reason it is not one. */
    public static Imported decode(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a Skyblocker waypoint string (it must start with " + PREFIX + ")");
        }
        String body = trimmed.substring(PREFIX.length()).replaceAll("\\s+", "");
        try {
            JsonArray array = JsonParser.parseString(new String(gunzip(body), StandardCharsets.UTF_8)).getAsJsonArray();
            WaypointCodec.Skipped skipped = new WaypointCodec.Skipped();
            List<WaypointGroup> groups = WaypointCodec.readGroups(array, skipped);
            // A mark is the sender's choice of where their key adds waypoints, not the receiver's.
            groups.forEach(group -> group.setMarked(false));
            return new Imported(groups, skipped.total());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("The waypoint string is damaged", exception);
        }
    }
}
