package com.github.beng420.kung.feature.waypoints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public final class WaypointShareTest {
    private static String share(String json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return "[Skyblocker-Waypoint-Data-V1]" + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    @Test
    public void aGroupSurvivesExportAndImport() {
        WaypointGroup group = new WaypointGroup("Route", "mining_3", WaypointType.OUTLINE);
        group.setOrdered(true);
        group.setThroughWalls(false);
        group.waypoints().add(new Waypoint(1, 2, 3, "A", 0xFF8000, 0.75F, true));
        group.waypoints().add(new Waypoint(-4, 5, -6, "B", 0x000000, 0F, false));

        WaypointShare.Imported imported = WaypointShare.decode("  " + WaypointShare.encode(List.of(group)) + "\n");

        assertEquals(0, imported.skipped());
        assertEquals(1, imported.groups().size());
        WaypointGroup back = imported.groups().get(0);
        assertEquals("Route", back.name());
        assertEquals("mining_3", back.islandId());
        assertEquals(WaypointType.OUTLINE, back.type());
        assertTrue(back.ordered());
        assertEquals(group.waypoints(), back.waypoints());
    }

    @Test
    public void aStringInTheSkyblockerLayoutIsRead() throws IOException {
        String json = """
            [{"name":"Pests","island":"garden","ordered":false,"renderThroughWalls":true,"waypointType":"outlined_waypoint",
              "waypoints":[
                {"pos":[10,70,-3],"name":"Plot 1","colorComponents":[1.0,0.0,0.5],"alpha":0.4,"shouldRender":true},
                {"pos":[0,0,0],"name":{"text":"Chat ","extra":[{"text":"name"}]},"colorComponents":[0,1,0],"alpha":0.5,"shouldRender":false}]}]
            """;

        WaypointGroup group = WaypointShare.decode(share(json)).groups().get(0);

        assertEquals("garden", group.islandId());
        assertEquals(WaypointType.OUTLINED_WAYPOINT, group.type());
        assertEquals(new Waypoint(10, 70, -3, "Plot 1", 0xFF0080, 0.4F, true), group.waypoints().get(0));
        assertEquals("Chat name", group.waypoints().get(1).name());
        assertEquals(false, group.waypoints().get(1).enabled());
    }

    @Test
    public void entriesThatCannotBeReadAreCountedNotFatal() throws IOException {
        String json = "[{\"name\":\"G\",\"island\":\"hub\",\"waypoints\":[{\"pos\":[1,2,3]},{\"name\":\"no pos\"}]},5]";
        WaypointShare.Imported imported = WaypointShare.decode(share(json));
        assertEquals(1, imported.groups().size());
        assertEquals(1, imported.groups().get(0).waypoints().size());
        assertEquals(2, imported.skipped());
    }

    @Test
    public void skytilsWaypointsAreReadPlainOrGzippedAndFillInTheIsland() throws IOException {
        String json = """
            {"categories":[
              {"name":"Cat","island":"hub","waypoints":[
                {"x":1,"y":2,"z":3,"name":"A","color":-2147418368,"enabled":true},
                {"x":4,"y":5,"z":6,"name":7,"enabled":false}]},
              {"name":"NoIsland","waypoints":[{"x":0,"y":0,"z":0,"name":"B","color":16711680,"enabled":true}]}]}
            """;
        WaypointIsland fallback = WaypointIsland.fromId("dynamic");

        for (String text : List.of(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
            "<Skytils-Waypoint-Data>(V1):" + share(json).substring(WaypointShare.PREFIX.length()))) {
            WaypointShare.Imported imported = WaypointShare.decodeSkytils(text, fallback);
            assertEquals(0, imported.skipped());
            assertEquals("hub", imported.groups().get(0).islandId());
            assertEquals(new Waypoint(1, 2, 3, "A", 0x00FF00, 128 / 255F, true), imported.groups().get(0).waypoints().get(0));
            assertEquals("a number as a name", "7", imported.groups().get(0).waypoints().get(1).name());
            assertEquals("a group without an island takes the default", "dynamic", imported.groups().get(1).islandId());
            assertEquals("no opacity means the default", Waypoint.DEFAULT_ALPHA, imported.groups().get(1).waypoints().get(0).alpha(), 0F);
        }
    }

    @Test
    public void aBareSkytilsListBecomesOneGroupOnTheDefaultIsland() {
        String list = Base64.getEncoder().encodeToString("[{\"x\":1,\"y\":2,\"z\":3,\"name\":\"Only\",\"enabled\":true}]"
            .getBytes(StandardCharsets.UTF_8));
        WaypointShare.Imported imported = WaypointShare.decodeSkytils(list, WaypointIsland.fromId("hub"));
        assertEquals(1, imported.groups().size());
        assertEquals("New Group", imported.groups().get(0).name());
        assertEquals("hub", imported.groups().get(0).islandId());
        assertThrows(IllegalArgumentException.class, () -> WaypointShare.decodeSkytils("@@@", WaypointIsland.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> WaypointShare.decodeSkytils("<Skytils-Waypoint-Data>(V9):abc", WaypointIsland.UNKNOWN));
    }

    @Test
    public void coleweightWaypointsBecomeOneRouteWithTheirColors() {
        String json = "[{\"x\":1,\"y\":2,\"z\":3,\"r\":1.0,\"g\":0.0,\"b\":0.0,\"options\":{\"name\":\"Start\"}},{\"x\":4,\"y\":5,\"z\":6},{\"y\":1}]";
        WaypointShare.Imported imported = WaypointShare.decodeColeweight(json, WaypointIsland.fromId("mining_3"));

        WaypointGroup group = imported.groups().get(0);
        assertEquals("Coleweight", group.name());
        assertTrue(group.ordered());
        assertEquals("mining_3", group.islandId());
        assertEquals(1, imported.skipped());
        assertEquals(new Waypoint(1, 2, 3, "Start", 0xFF0000, Waypoint.DEFAULT_ALPHA, true), group.waypoints().get(0));
        assertEquals(new Waypoint(4, 5, 6, "New Waypoint", 0x00FF00, Waypoint.DEFAULT_ALPHA, true), group.waypoints().get(1));
        assertThrows(IllegalArgumentException.class, () -> WaypointShare.decodeColeweight("{}", WaypointIsland.UNKNOWN));
    }

    @Test
    public void textThatIsNotAShareStringIsRefusedWithAReason() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WaypointShare.decode("hello")).getMessage()
            .contains("Skyblocker-Waypoint-Data-V1"));
        assertThrows(IllegalArgumentException.class, () -> WaypointShare.decode(WaypointShare.PREFIX + "!!!not base64"));
        assertThrows(IllegalArgumentException.class, () -> WaypointShare.decode(WaypointShare.PREFIX
            + Base64.getEncoder().encodeToString("plain, not gzip".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> WaypointShare.decode(null));
    }

    @Test
    public void anImportTakesTheSendersMarkOff() {
        WaypointGroup group = new WaypointGroup("Shared", "hub", WaypointType.WAYPOINT);
        group.setMarked(true);
        org.junit.Assert.assertFalse(WaypointShare.decode(WaypointShare.encode(List.of(group))).groups().get(0).marked());
    }
}
