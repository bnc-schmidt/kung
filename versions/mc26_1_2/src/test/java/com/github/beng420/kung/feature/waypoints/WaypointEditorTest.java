package com.github.beng420.kung.feature.waypoints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WaypointEditorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final WaypointIsland HUB = WaypointIsland.fromId("hub");
    private static final WaypointIsland PRIVATE = WaypointIsland.fromId("dynamic");
    private Path file;
    private WaypointStore store;
    private WaypointEditor editor;

    @Before
    public void setUp() {
        file = temporary.getRoot().toPath().resolve("waypoints.json");
        store = new WaypointStore(file);
        editor = new WaypointEditor(store);
    }

    private WaypointStore reloaded() {
        WaypointStore again = new WaypointStore(file);
        again.load();
        return again;
    }

    @Test
    public void groupsAndWaypointsAreMadeEditedMovedAndRemovedOnTheIslandThePlayerIsOn() {
        editor.newGroup("Farm", WaypointType.HIGHLIGHT, HUB);
        editor.addWaypoint(1, "", new BlockPos(1, 2, 3), HUB);
        editor.addWaypoint(1, "Barn", new BlockPos(4, 5, 6), HUB);
        editor.addWaypoint(1, "Gate", new BlockPos(7, 8, 9), HUB);

        List<Waypoint> waypoints = reloaded().groupsOn("hub").get(0).waypoints();
        assertEquals(List.of("Waypoint 1", "Barn", "Gate"), waypoints.stream().map(Waypoint::name).toList());

        // Gate moves to the front: Gate, Waypoint 1, Barn.
        editor.moveWaypoint(1, 3, -2, HUB);
        editor.renameWaypoint(1, 2, "Edited", HUB);
        editor.moveWaypointTo(1, 2, new BlockPos(0, 64, 0), HUB);
        editor.colorWaypoint(1, 2, "#ff8000", HUB);
        editor.alphaWaypoint(1, 2, 25, HUB);
        editor.toggleWaypoint(1, 2, HUB);
        editor.removeWaypoint(1, 1, HUB);

        assertEquals(List.of(new Waypoint(0, 64, 0, "Edited", 0xFF8000, 0.25F, false),
                new Waypoint(4, 5, 6, "Barn", 0x00FF00, 0.5F, true)),
            reloaded().groupsOn("hub").get(0).waypoints());
    }

    @Test
    public void islandsKeepTheirGroupsApart() {
        editor.newGroup("Hub spots", WaypointType.WAYPOINT, HUB);
        editor.newGroup("Home", WaypointType.WAYPOINT, PRIVATE);

        assertEquals(List.of("Hub spots"), reloaded().groupsOn("hub").stream().map(WaypointGroup::name).toList());
        assertEquals(List.of("Home"), reloaded().groupsOn("dynamic").stream().map(WaypointGroup::name).toList());
        assertTrue(editor.list(HUB).get(0).startsWith("Hub: 1 group"));
        assertEquals("Hub spots", editor.list(HUB).get(1).split("\\. | - ")[1]);
    }

    @Test
    public void anotherIslandCanBeEditedFromAnywhereUntilTheEditorFollowsThePlayerAgain() {
        editor.chooseIsland("crystal_hollows", HUB);
        editor.newGroup("Hollows", WaypointType.WAYPOINT, HUB);
        editor.chooseIsland("Glacite Mineshafts", HUB);
        editor.newGroup("Shaft", WaypointType.WAYPOINT, HUB);
        editor.chooseIsland("everywhere", HUB);
        editor.newGroup("All", WaypointType.WAYPOINT, HUB);
        editor.chooseIsland("here", HUB);
        editor.newGroup("Local", WaypointType.WAYPOINT, HUB);

        WaypointStore again = reloaded();
        assertEquals(1, again.groupsOn("crystal_hollows").size());
        assertEquals(1, again.groupsOn("mineshaft").size());
        assertEquals(1, again.groupsOn("unknown").size());
        assertEquals(1, again.groupsOn("hub").size());
    }

    @Test
    public void aRouteIsAnOrderedGroupAndItsSettingsStick() {
        editor.newGroup("Loop", WaypointType.WAYPOINT, HUB);
        editor.setOrdered(1, true, HUB);
        editor.setThroughWalls(1, false, HUB);
        editor.setType(1, WaypointType.OUTLINE, HUB);
        editor.renameGroup(1, "Loop 2", HUB);

        WaypointGroup group = reloaded().groupsOn("hub").get(0);
        assertTrue(group.ordered());
        assertFalse(group.throughWalls());
        assertEquals(WaypointType.OUTLINE, group.type());
        assertEquals("Loop 2", group.name());
    }

    @Test
    public void switchingAGroupFlipsAllItsWaypointsTogether() {
        editor.newGroup("G", WaypointType.WAYPOINT, HUB);
        editor.addWaypoint(1, "a", BlockPos.ZERO, HUB);
        editor.addWaypoint(1, "b", BlockPos.ZERO, HUB);
        editor.toggleWaypoint(1, 1, HUB);

        editor.toggleGroup(1, HUB);
        assertTrue(store.groupsOn("hub").get(0).fullyEnabled());
        editor.toggleGroup(1, HUB);
        assertTrue(store.groupsOn("hub").get(0).waypoints().stream().noneMatch(Waypoint::enabled));
    }

    @Test
    public void badNumbersAndColorsAreRefusedWithoutChangingAnything() {
        editor.newGroup("G", WaypointType.WAYPOINT, HUB);
        editor.addWaypoint(1, "a", BlockPos.ZERO, HUB);

        assertThrows(IllegalArgumentException.class, () -> editor.removeGroup(2, HUB));
        assertThrows(IllegalArgumentException.class, () -> editor.removeWaypoint(1, 5, HUB));
        assertThrows(IllegalArgumentException.class, () -> editor.addWaypoint(3, "x", BlockPos.ZERO, HUB));
        assertThrows(IllegalArgumentException.class, () -> editor.colorWaypoint(1, 1, "green", HUB));
        assertThrows(IllegalArgumentException.class, () -> editor.removeGroup(1, PRIVATE));
        assertEquals(1, reloaded().groupsOn("hub").get(0).waypoints().size());
        assertEquals(0x00FF00, reloaded().groupsOn("hub").get(0).waypoints().get(0).rgb());
    }

    @Test
    public void exportedGroupsImportToAnotherIslandWhenAskedTo() {
        editor.newGroup("Shared", WaypointType.WAYPOINT, HUB);
        editor.addWaypoint(1, "spot", new BlockPos(3, 4, 5), HUB);
        String text = editor.export(0, HUB);

        String message = editor.importText(text, false, PRIVATE);
        assertTrue(message, message.startsWith("Imported 1 waypoints in 1 groups."));
        assertEquals("a group with an island keeps it", 2, reloaded().groupsOn("hub").size());

        editor.importText(text, true, PRIVATE);
        assertEquals("here moves it to the island being edited", 1, reloaded().groupsOn("dynamic").size());
        assertThrows(IllegalArgumentException.class, () -> editor.export(0, WaypointIsland.fromId("rift")));
        assertThrows(IllegalArgumentException.class, () -> editor.importText("nonsense", false, HUB));
    }

    @Test
    public void aFailedSaveIsReportedInTheMessage() {
        Path blocker = temporary.getRoot().toPath().resolve("blocker");
        try {
            java.nio.file.Files.writeString(blocker, "a file where the folder should be");
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        WaypointEditor broken = new WaypointEditor(new WaypointStore(blocker.resolve("waypoints.json")));
        String message = broken.newGroup("G", WaypointType.WAYPOINT, HUB);
        assertTrue(message, message.contains("Not saved"));
    }

    @Test
    public void theAddKeyPutsAWaypointInEveryMarkedGroupOfTheIslandAndNowhereElse() {
        editor.newGroup("Marked", WaypointType.HIGHLIGHT, HUB);
        editor.newGroup("Plain", WaypointType.HIGHLIGHT, HUB);
        editor.newGroup("Other island", WaypointType.WAYPOINT, PRIVATE);
        store.groupsOn("hub").get(0).setMarked(true);
        store.groupsOn("dynamic").get(0).setMarked(true);
        BlockPos pos = new BlockPos(4, 5, 6);

        String message = editor.addToMarked(pos, HUB, true);

        assertTrue(message, message.contains("\"Marked\"") && !message.contains("Plain") && !message.contains("Other island"));
        WaypointStore saved = reloaded();
        assertEquals(List.of(new Waypoint(4, 5, 6, "Waypoint 1", Waypoint.DEFAULT_RGB, Waypoint.DEFAULT_ALPHA, true)),
            saved.groupsOn("hub").get(0).waypoints());
        assertTrue("the mark is saved", saved.groupsOn("hub").get(0).marked());
        assertTrue(saved.groupsOn("hub").get(1).waypoints().isEmpty());
        assertTrue(saved.groupsOn("dynamic").get(0).waypoints().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> editor.addToMarked(pos, WaypointIsland.fromId("crimson_isle"), true));
        assertThrows("not in SkyBlock", IllegalArgumentException.class, () -> editor.addToMarked(pos, HUB, false));
    }
}
