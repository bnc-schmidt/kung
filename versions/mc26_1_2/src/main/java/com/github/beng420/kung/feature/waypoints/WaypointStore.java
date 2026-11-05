package com.github.beng420.kung.feature.waypoints;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.runtime.KungPaths;
import com.github.beng420.kung.util.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every waypoint group of the player, in {@code config/kung/waypoints.json}, next to the settings and not inside them:
 * the file is data the player built, and a settings reset must not take it along.
 *
 * <p>The file is {@code {"version":1,"groups":[...]}}. A change is written at once, to a temporary file that then
 * replaces the old one, so a crash leaves the last complete file. A file that cannot be read is copied to
 * {@code waypoints.json.bad-<millis>} before anything else touches it; a file from a newer Kung is never
 * written over at all.
 */
public final class WaypointStore {
    public static final int FORMAT_VERSION = 1;
    public static final WaypointStore INSTANCE = new WaypointStore(
        KungPaths.fileLayout().settingsDirectory().resolve("waypoints.json"));
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** What loading found. {@code problem} is empty for a clean load; {@code backup} is the copy of a damaged file. */
    public record LoadReport(int groups, int waypoints, int skipped, String problem, Path backup) {
        public boolean clean() { return problem.isEmpty() && skipped == 0; }
    }

    private final Path file;
    private final List<WaypointGroup> groups = new ArrayList<>();
    private boolean saveBlocked;
    private String saveError = "";

    WaypointStore(Path file) {
        this.file = file;
    }

    public synchronized LoadReport load() {
        groups.clear();
        saveBlocked = false;
        saveError = "";
        if (!Files.exists(file)) return report(0, "", null);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // Unreadable now does not mean empty: leave the file alone until it can be read.
            saveBlocked = true;
            return report(0, "cannot read " + file.getFileName() + ": " + exception.getMessage(), null);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException exception) {
            return report(0, "not valid JSON", backup());
        }
        int version = root.has("version") && root.get("version").isJsonPrimitive() ? root.get("version").getAsInt() : 0;
        if (version > FORMAT_VERSION) {
            saveBlocked = true;
            return report(0, "written by a newer Kung (format " + version + ")", null);
        }
        if (!root.has("groups") || !root.get("groups").isJsonArray()) {
            return report(0, "no groups list", backup());
        }
        WaypointCodec.Skipped skipped = new WaypointCodec.Skipped();
        groups.addAll(WaypointCodec.readGroups(root.getAsJsonArray("groups"), skipped));
        return report(skipped.total(), "", skipped.total() > 0 ? backup() : null);
    }

    private LoadReport report(int skipped, String problem, Path backup) {
        int waypoints = groups.stream().mapToInt(group -> group.waypoints().size()).sum();
        if (!problem.isEmpty() || skipped > 0) {
            KungMod.LOGGER.warn("Waypoints: {} ({} left out); original kept at {}",
                problem.isEmpty() ? "some entries could not be read" : problem, skipped, backup);
        }
        return new LoadReport(groups.size(), waypoints, skipped, problem, backup);
    }

    /** Copies the file next to itself; null when even that fails. */
    private Path backup() {
        Path copy = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
        try {
            Files.copy(file, copy);
            return copy;
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Waypoints: could not back up {}", file, exception);
            // No copy means the damaged original is the only one: do not replace it.
            saveBlocked = true;
            return null;
        }
    }

    /** Writes the file. False when saving is blocked or the write failed; {@link #saveError()} says why. */
    public synchronized boolean save() {
        if (saveBlocked) {
            saveError = "saving is off because the file on disk could not be read or backed up";
            return false;
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.add("groups", WaypointCodec.writeGroups(groups));
        try {
            AtomicFiles.write(file, GSON.toJson(root));
            saveError = "";
            return true;
        } catch (IOException | RuntimeException exception) {
            saveError = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            KungMod.LOGGER.warn("Waypoints: could not save {}", file, exception);
            return false;
        }
    }

    public synchronized String saveError() { return saveError; }

    /** All groups, in the order they were made. */
    synchronized List<WaypointGroup> groups() { return List.copyOf(groups); }

    /** The groups of one island, in order. */
    public synchronized List<WaypointGroup> groupsOn(String islandId) {
        return groups.stream().filter(group -> group.islandId().equals(islandId)).toList();
    }

    /**
     * What shows on an island: its own groups, then the groups that belong to no island. Those show on every island,
     * but only once the player is in SkyBlock, so they are left out when {@code inSkyBlock} is false.
     */
    public synchronized List<WaypointGroup> visibleOn(String islandId, boolean inSkyBlock) {
        if (!inSkyBlock) return List.of();
        List<WaypointGroup> visible = new ArrayList<>(groupsOn(islandId));
        if (!islandId.equals(WaypointIsland.UNKNOWN.id())) visible.addAll(groupsOn(WaypointIsland.UNKNOWN.id()));
        return visible;
    }

    /** The groups as JSON text, to tell whether an editor changed anything. */
    public static String fingerprint(List<WaypointGroup> groups) {
        return WaypointCodec.writeGroups(groups).toString();
    }

    /** Independent copies of every group, for an editor that keeps its changes until the player saves them. */
    public synchronized List<WaypointGroup> copyOfGroups() {
        return groups.stream().map(WaypointGroup::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * Swaps in copies of the groups an editor finished with and writes them. When the write fails the old groups
     * stay, so a refused save leaves nothing half-applied.
     */
    public synchronized boolean replaceAll(List<WaypointGroup> replacement) {
        List<WaypointGroup> before = List.copyOf(groups);
        groups.clear();
        for (WaypointGroup group : replacement) groups.add(group.copy());
        if (save()) return true;
        groups.clear();
        groups.addAll(before);
        return false;
    }

    public synchronized void add(WaypointGroup group) {
        groups.add(group);
        save();
    }

    public synchronized boolean remove(WaypointGroup group) {
        boolean removed = groups.removeIf(existing -> existing == group);
        if (removed) save();
        return removed;
    }

    /** Adds groups made elsewhere (an import) in one write. */
    public synchronized void addAll(List<WaypointGroup> added) {
        groups.addAll(added);
        save();
    }

    /** Puts every route back on its first waypoint; called on a new world. */
    public synchronized void resetRoutes() {
        groups.forEach(WaypointGroup::resetRoute);
    }
}
