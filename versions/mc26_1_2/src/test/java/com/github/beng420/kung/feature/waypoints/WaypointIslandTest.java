package com.github.beng420.kung.feature.waypoints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class WaypointIslandTest {
    @Test
    public void theTabListAreaRowNamesTheIsland() {
        assertEquals("hub", WaypointIsland.current(List.of("Server: m12", "Area: Hub")).id());
        assertEquals("dynamic", WaypointIsland.current(List.of("§bArea: §7Private Island")).id());
        assertEquals("crystal_hollows", WaypointIsland.current(List.of("Area: Crystal Hollows")).id());
        assertEquals("the rift", "rift", WaypointIsland.current(List.of("Area: The Rift")).id());
    }

    @Test
    public void spellingsTheTabListUsesForOneIslandMapToOneId() {
        assertEquals("garden", WaypointIsland.fromAreaName("Garden").id());
        assertEquals("garden", WaypointIsland.fromAreaName("The Garden").id());
        assertEquals("foraging_2", WaypointIsland.fromAreaName("Galatea").id());
        assertEquals("foraging_2", WaypointIsland.fromAreaName("Moonglade Marsh").id());
        assertEquals("dungeon", WaypointIsland.fromAreaName("The Catacombs (M7)").id());
        assertEquals("kuudra", WaypointIsland.fromAreaName("Kuudra's Hollow (T5)").id());
        assertEquals("mineshaft", WaypointIsland.fromAreaName("Glacite Mineshafts").id());
    }

    @Test
    public void aDungeonRunRowAlsoCounts() {
        assertEquals("dungeon", WaypointIsland.current(List.of("Dungeon: Catacombs")).id());
    }

    @Test
    public void aSidebarSubAreaIsNotAnIsland() {
        assertSame(WaypointIsland.UNKNOWN, WaypointIsland.current(List.of("⏣ Village", "Server: m12")));
        assertSame(WaypointIsland.UNKNOWN, WaypointIsland.current(List.of()));
        assertSame(WaypointIsland.UNKNOWN, WaypointIsland.current(List.of("Party > Friend: Area: Hub")));
    }

    @Test
    public void aDifferentIslandNeverSharesAnIdWithAnotherOne() {
        List<WaypointIsland> all = WaypointIsland.all();
        assertEquals(all.size(), all.stream().map(WaypointIsland::id).distinct().count());
        assertFalse(all.contains(WaypointIsland.UNKNOWN));
    }

    @Test
    public void anUnlistedIslandGetsAStableIdFromItsName() {
        WaypointIsland first = WaypointIsland.fromAreaName("Brand New Island (x)");
        WaypointIsland second = WaypointIsland.fromAreaName("brand new island");
        assertEquals(first.id(), second.id());
        assertEquals("area:brand new island", first.id());
        assertEquals("Brand New Island", first.name());
        assertEquals(first.id(), WaypointIsland.fromId(first.id()).id());
        assertFalse(first.isUnknown());
    }

    @Test
    public void storedIdsComeBackAsTheSameIsland() {
        for (WaypointIsland island : WaypointIsland.all()) {
            assertEquals(island, WaypointIsland.fromId(island.id()));
        }
        assertTrue(WaypointIsland.fromId("").isUnknown());
        assertTrue(WaypointIsland.fromId("UNKNOWN").isUnknown());
        assertEquals("hub", WaypointIsland.fromId("HUB").id());
    }
}
