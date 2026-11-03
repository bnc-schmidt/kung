package com.github.beng420.kung.feature.waypoints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WaypointTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static Waypoint named(String name) {
        return new Waypoint(0, 0, 0, name, 0, 0.5F, true);
    }

    @Test
    public void sortingByNameCountsNumbersLikeAPlayer() {
        List<Waypoint> waypoints = new ArrayList<>(List.of(named("Plot 10"), named("Plot 2"), named("Barn"), named("Plot 1"),
            named("Plot"), named("A99999999999999999999"), named("A2")));
        waypoints.sort(Waypoint.NAME_ORDER);
        assertEquals(List.of("A2", "A99999999999999999999", "Barn", "Plot", "Plot 1", "Plot 2", "Plot 10"),
            waypoints.stream().map(Waypoint::name).toList());
    }

    @Test
    public void valuesAreKeptInRange() {
        Waypoint waypoint = new Waypoint(1, 2, 3, null, 0x1FF0000, 7F, true);
        assertEquals("", waypoint.name());
        assertEquals(0xFF0000, waypoint.rgb());
        assertEquals(1F, waypoint.alpha(), 0F);
        assertEquals(Waypoint.DEFAULT_ALPHA, new Waypoint(0, 0, 0, "", 0, Float.NaN, true).alpha(), 0F);
    }

    @Test
    public void aRefusedSaveLeavesTheStoredGroupsAndTheEditorsCopiesApart() throws java.io.IOException {
        Path file = temporary.getRoot().toPath().resolve("waypoints.json");
        java.nio.file.Files.writeString(file, "{\"version\":" + (WaypointStore.FORMAT_VERSION + 1) + ",\"groups\":[]}");
        WaypointStore store = new WaypointStore(file);
        store.load();
        store.add(new WaypointGroup("Kept", "hub", WaypointType.WAYPOINT));

        WaypointGroup edited = new WaypointGroup("Edited", "hub", WaypointType.WAYPOINT);
        assertEquals(false, store.replaceAll(List.of(edited)));

        assertEquals(List.of("Kept"), store.groups().stream().map(WaypointGroup::name).toList());
        edited.setName("Changed after");
        assertEquals("the store never held the editor's own objects", List.of("Kept"),
            store.groups().stream().map(WaypointGroup::name).toList());
    }

    @Test
    public void aCopyKeepsTheRouteProgress() {
        WaypointGroup route = new WaypointGroup("Route", "hub", WaypointType.WAYPOINT);
        route.setOrdered(true);
        route.waypoints().addAll(List.of(named("a"), named("b"), named("c")));
        route.setCurrentIndex(2);
        assertEquals(2, route.copy().currentIndex());
    }

    @Test
    public void anEditorKeepsItsChangesApartUntilTheyAreWritten() {
        Path file = temporary.getRoot().toPath().resolve("waypoints.json");
        WaypointStore store = new WaypointStore(file);
        WaypointGroup group = new WaypointGroup("Original", "hub", WaypointType.WAYPOINT);
        group.waypoints().add(named("one"));
        store.add(group);

        List<WaypointGroup> copy = store.copyOfGroups();
        String before = WaypointStore.fingerprint(copy);
        assertEquals("a copy of what is stored is no change", WaypointStore.fingerprint(store.groups()), before);

        copy.get(0).setName("Edited");
        copy.get(0).waypoints().add(named("two"));
        assertNotEquals(before, WaypointStore.fingerprint(copy));
        assertEquals("Original", store.groups().get(0).name());
        assertEquals(1, store.groups().get(0).waypoints().size());

        assertTrue(store.replaceAll(copy));
        WaypointStore restarted = new WaypointStore(file);
        restarted.load();
        assertEquals("Edited", restarted.groups().get(0).name());
        assertEquals(2, restarted.groups().get(0).waypoints().size());
    }
}
