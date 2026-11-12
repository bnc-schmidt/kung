package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.beng420.kung.feature.dungeon.room.RoomType;
import java.util.List;
import org.junit.Test;

public final class CobbleWallPillarCatalogTest {
    // M7 trace of 2026-11-11 12:47: cell 1,4 next to the start was the only room of the first scan that no entry named.
    @Test public void theNovember11PillarIsRecognizedFromItsBundledHash() {
        DungeonKnownRoomCatalog.reload();
        var hint = DungeonKnownRoomCatalog.knownCoreHint(1160143865);
        assertNotNull("Cobble Wall Pillar needs this hash", hint);
        assertEquals("Cobble Wall Pillar", hint.name());
        assertEquals(RoomType.NORMAL, hint.type());
        assertEquals(2, hint.secrets());
        assertEquals(1, hint.crypts());
        var snapshot = new DungeonMapSnapshot();
        snapshot.observeMapVisibleRoom(1, 4);
        snapshot.addScan(1, 1794397971792L, 5, 5, List.of(new DungeonScanPoint(2, 8, -153, -57,
            DungeonScanPointKind.ROOM, true, 1160143865, -875996031, 0, DungeonDoorKind.NONE, true)));
        var plan = DungeonLiveMapWriter.MatchRenderPlan.from(snapshot, KnownDungeonRoomRepository.INSTANCE);
        assertEquals(1, plan.matches().size());
        assertEquals("Cobble Wall Pillar", DungeonRoomRenderLayout.from(plan).rooms().getFirst().template().name());
    }
}
