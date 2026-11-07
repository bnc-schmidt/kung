package com.github.beng420.kung.feature.waypoints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WaypointStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Path file;

    @Before
    public void setUp() {
        file = temporary.getRoot().toPath().resolve("kung").resolve("waypoints.json");
    }

    private static WaypointGroup route(String island) {
        WaypointGroup group = new WaypointGroup("Mithril \"loop\"", island, WaypointType.OUTLINED_HIGHLIGHT);
        group.setOrdered(true);
        group.setThroughWalls(false);
        group.waypoints().add(new Waypoint(10, 64, -20, "First", 0x12AB34, 0.25F, true));
        group.waypoints().add(new Waypoint(-5, 70, 300, "Zweite Marke ü", 0xFF0000, 1F, false));
        return group;
    }

    private List<String> backups() throws IOException {
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.contains(".bad-")).toList();
        }
    }

    @Test
    public void groupsAndWaypointsSurviveARestart() {
        WaypointStore store = new WaypointStore(file);
        store.add(route("hub"));
        store.add(new WaypointGroup("Plain", "dynamic", WaypointType.WAYPOINT));

        WaypointStore restarted = new WaypointStore(file);
        WaypointStore.LoadReport report = restarted.load();

        assertTrue(report.clean());
        assertEquals(2, report.groups());
        assertEquals(2, report.waypoints());
        WaypointGroup loaded = restarted.groupsOn("hub").get(0);
        assertEquals("Mithril \"loop\"", loaded.name());
        assertTrue(loaded.ordered());
        assertFalse(loaded.throughWalls());
        assertEquals(WaypointType.OUTLINED_HIGHLIGHT, loaded.type());
        assertEquals(new Waypoint(10, 64, -20, "First", 0x12AB34, 0.25F, true), loaded.waypoints().get(0));
        assertEquals(new Waypoint(-5, 70, 300, "Zweite Marke ü", 0xFF0000, 1F, false), loaded.waypoints().get(1));
        assertEquals("Plain", restarted.groupsOn("dynamic").get(0).name());
    }

    @Test
    public void everyEditIsOnDiskAtOnceAndNoTemporaryFileStaysBehind() throws IOException {
        WaypointStore store = new WaypointStore(file);
        WaypointGroup group = route("hub");
        store.add(group);
        group.setName("Renamed");
        assertTrue(store.save());

        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("Renamed"));
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(List.of("waypoints.json"), files.map(path -> path.getFileName().toString()).toList());
        }
        store.remove(group);
        assertEquals(0, new WaypointStore(file).load().groups());
    }

    @Test
    public void aMissingFileIsAnEmptyCleanStart() {
        WaypointStore store = new WaypointStore(file);
        WaypointStore.LoadReport report = store.load();
        assertTrue(report.clean());
        assertEquals(0, report.groups());
        assertFalse("loading creates nothing", Files.exists(file));
    }

    @Test
    public void aBrokenFileIsKeptAsABackupAndTheStoreStartsEmpty() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":1,\"groups\":[ this is not json", StandardCharsets.UTF_8);

        WaypointStore store = new WaypointStore(file);
        WaypointStore.LoadReport report = store.load();

        assertFalse(report.clean());
        assertNotNull(report.backup());
        assertEquals("{\"version\":1,\"groups\":[ this is not json", Files.readString(report.backup(), StandardCharsets.UTF_8));
        assertEquals(0, report.groups());
        // A new group may overwrite the broken file now: the backup still holds it.
        store.add(route("hub"));
        assertEquals(1, new WaypointStore(file).load().groups());
        assertEquals(1, backups().size());
    }

    @Test
    public void entriesThatCannotBeReadAreLeftOutButEverythingElseLoadsAndTheOriginalIsBackedUp() throws IOException {
        String original = """
            {"version":1,"groups":[
              {"name":"Good","island":"hub","waypoints":[
                {"pos":[1,2,3],"name":"ok"},
                {"pos":[1,2],"name":"two coordinates"},
                {"name":"no position"}]},
              "not an object",
              {"name":"Also good","island":"farming_1","waypoints":[]}]}
            """;
        Files.createDirectories(file.getParent());
        Files.writeString(file, original, StandardCharsets.UTF_8);

        WaypointStore store = new WaypointStore(file);
        WaypointStore.LoadReport report = store.load();

        assertEquals(3, report.skipped());
        assertEquals(2, report.groups());
        assertEquals(1, report.waypoints());
        assertEquals("ok", store.groupsOn("hub").get(0).waypoints().get(0).name());
        assertEquals(original, Files.readString(report.backup(), StandardCharsets.UTF_8));
    }

    @Test
    public void aFileFromANewerKungIsNeverWrittenOver() throws IOException {
        String newer = "{\"version\":" + (WaypointStore.FORMAT_VERSION + 1) + ",\"groups\":[],\"extra\":\"data\"}";
        Files.createDirectories(file.getParent());
        Files.writeString(file, newer, StandardCharsets.UTF_8);

        WaypointStore store = new WaypointStore(file);
        WaypointStore.LoadReport report = store.load();
        store.add(route("hub"));

        assertFalse(report.clean());
        assertNull(report.backup());
        assertFalse(store.save());
        assertFalse(store.saveError().isEmpty());
        assertEquals(newer, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void groupsBelongToTheirIslandAndGroupsWithoutOneShowEverywhereInSkyBlock() {
        WaypointStore store = new WaypointStore(file);
        store.add(route("hub"));
        store.add(route("dynamic"));
        store.add(new WaypointGroup("Everywhere", WaypointIsland.UNKNOWN.id(), WaypointType.WAYPOINT));

        assertEquals(List.of("Mithril \"loop\"", "Everywhere"),
            store.visibleOn("hub", true).stream().map(WaypointGroup::name).toList());
        assertEquals(List.of("Mithril \"loop\"", "Everywhere"),
            store.visibleOn("dynamic", true).stream().map(WaypointGroup::name).toList());
        assertEquals(List.of("Everywhere"), store.visibleOn("crimson_isle", true).stream().map(WaypointGroup::name).toList());
        assertEquals("not in SkyBlock: nothing", List.of(), store.visibleOn("hub", false));
        assertEquals(1, store.groupsOn("hub").size());
    }

    @Test
    public void aGroupShowsOnEveryAreaTickedForItAndNoTickMeansEverywhere() {
        WaypointStore store = new WaypointStore(file);
        WaypointGroup two = new WaypointGroup("Two areas", "hub", WaypointType.HIGHLIGHT);
        two.setOnIsland("garden", true);
        two.setWallStyle(WaypointWallStyle.WAYPOINT);
        store.add(two);
        store.add(new WaypointGroup("Nowhere ticked", WaypointIsland.UNKNOWN.id(), WaypointType.WAYPOINT));

        assertEquals(List.of("Two areas", "Nowhere ticked"), store.visibleOn("hub", true).stream().map(WaypointGroup::name).toList());
        assertEquals(List.of("Two areas", "Nowhere ticked"), store.visibleOn("garden", true).stream().map(WaypointGroup::name).toList());
        assertEquals(List.of("Nowhere ticked"), store.visibleOn("dynamic", true).stream().map(WaypointGroup::name).toList());
        assertEquals("listed under Everywhere only when nothing is ticked", List.of("Nowhere ticked"),
            store.groupsOn(WaypointIsland.UNKNOWN.id()).stream().map(WaypointGroup::name).toList());

        WaypointStore restarted = new WaypointStore(file);
        assertTrue(restarted.load().clean());
        WaypointGroup loaded = restarted.groupsOn("garden").get(0);
        assertEquals(List.of("hub", "garden"), loaded.islands());
        assertEquals(WaypointWallStyle.WAYPOINT, loaded.wallStyle());
        assertEquals(WaypointWallStyle.ICON, restarted.groupsOn(WaypointIsland.UNKNOWN.id()).get(0).wallStyle());

        loaded.setOnIsland("hub", false);
        loaded.setOnIsland("garden", false);
        assertTrue("with the last tick gone it shows everywhere", loaded.shownOn("crimson_isle"));
    }

    @Test
    public void aFileWithOnlyTheSingleIslandFieldStillLoadsOnThatIsland() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":1,\"groups\":[{\"name\":\"Old\",\"island\":\"hub\",\"waypoints\":[]}]}", StandardCharsets.UTF_8);
        WaypointStore store = new WaypointStore(file);
        assertTrue(store.load().clean());
        assertEquals(List.of("hub"), store.groupsOn("hub").get(0).islands());
        assertEquals(WaypointWallStyle.ICON, store.groupsOn("hub").get(0).wallStyle());
    }

    @Test
    public void theIconWallLookDrawsNoBlockAndTheWaypointWallLookDrawsItsType() {
        for (WaypointType type : WaypointType.values()) {
            assertFalse(type.name(), WaypointWallStyle.ICON.fill(type) || WaypointWallStyle.ICON.outline(type));
            assertEquals(type.name(), type.fill(), WaypointWallStyle.WAYPOINT.fill(type));
            assertEquals(type.name(), type.outline(), WaypointWallStyle.WAYPOINT.outline(type));
        }
        assertEquals("a group starts with the icon, as Walls always did", WaypointWallStyle.ICON,
            new WaypointGroup("New", "hub", WaypointType.WAYPOINT).wallStyle());
        assertEquals(WaypointWallStyle.WAYPOINT, WaypointWallStyle.ICON.next());
        assertEquals(WaypointWallStyle.ICON, WaypointWallStyle.WAYPOINT.next());
        assertEquals("an unknown stored name gives the icon", WaypointWallStyle.ICON, WaypointWallStyle.parse("nonsense"));
    }

    @Test
    public void anIslandKungDoesNotListKeepsItsGroupsAcrossARestart() {
        WaypointIsland modern = WaypointIsland.fromAreaName("Brand New Island");
        WaypointStore store = new WaypointStore(file);
        store.add(new WaypointGroup("Future", modern.id(), WaypointType.WAYPOINT));

        WaypointStore restarted = new WaypointStore(file);
        restarted.load();
        assertEquals(1, restarted.groupsOn(WaypointIsland.fromAreaName("Brand New Island").id()).size());
    }

    @Test
    public void routesGoBackToTheirFirstWaypointOnReset() {
        WaypointStore store = new WaypointStore(file);
        WaypointGroup group = route("hub");
        store.add(group);
        group.setCurrentIndex(1);
        store.resetRoutes();
        assertEquals(0, group.currentIndex());
    }
}
