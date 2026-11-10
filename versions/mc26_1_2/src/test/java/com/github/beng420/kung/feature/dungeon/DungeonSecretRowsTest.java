package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.MinecraftBootstrapTest;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

public final class DungeonSecretRowsTest extends MinecraftBootstrapTest {
    private static final UUID SELF = new UUID(0, 1);
    private static final UUID TEAMMATE = new UUID(0, 2);

    private static DungeonRunStats run() {
        var stats = new DungeonRunStats();
        stats.rememberSelf(TEAMMATE, "Awesomeness_Boy");
        stats.rememberSelf(SELF, "Beng114");
        return stats;
    }

    private static List<String> counts(DungeonRunStats stats) {
        return stats.secretRows().stream().map(row -> row.name() + "=" + row.count()).toList();
    }

    /** One secret of the own player: an own pickup and the party total rising right after it. */
    private static void ownSecret(DungeonRunStats stats, int newTotal) {
        stats.observeOwnPickup(System.currentTimeMillis());
        stats.observeTabLine(null, "Secrets Found: " + newTotal, null);
    }

    @Test
    public void theOwnCountGrowsWithOwnSecretsAndATeammateWithoutAReportStaysUnknown() {
        var stats = run();
        stats.observeTabLine(null, "Secrets Found: 10", null);
        assertEquals(List.of("Beng114=0", "Awesomeness_Boy=?"), counts(stats));
        ownSecret(stats, 11);
        stats.observeTabLine(null, "Secrets Found: 12", null);
        assertEquals(List.of("Beng114=1", "Awesomeness_Boy=?"), counts(stats));
        assertEquals(12, stats.partySecretTotal());
    }

    @Test
    public void beforeAnyTotalNobodyHasACount() {
        var stats = run();
        assertEquals(List.of("Beng114=?", "Awesomeness_Boy=?"), counts(stats));
        assertEquals(-1, stats.partySecretTotal());
    }

    @Test
    public void aTeammatesOwnReportShowsAndTheApiCountReplacesTheOwnTallyAtTheEnd() {
        var stats = run();
        stats.observeTabLine(null, "Secrets Found: 0", null);
        ownSecret(stats, 1);
        stats.mergeRemoteLivePlayers(List.of(new DungeonRoomDataSyncClient.LivePlayerReport(
            "Awesomeness_Boy", 3, 0, "Awesomeness_Boy", 10L, "PERSONAL")));
        assertEquals(List.of("Beng114=1", "Awesomeness_Boy=3"), counts(stats));
        stats.playerStats(SELF).setApiRunSecretsFound(5);
        assertEquals(List.of("Beng114=5", "Awesomeness_Boy=3"), counts(stats));
    }

    @Test
    public void theLiveTallyTravelsToTeammatesAsPersonalAndTheExactCountReplacesIt() {
        var stats = run();
        assertEquals(List.of(), stats.livePlayerReports(null));
        stats.observeTabLine(null, "Secrets Found: 0", null);
        ownSecret(stats, 1);
        var live = stats.livePlayerReports(null).getFirst();
        assertEquals("Beng114", live.name());
        assertEquals(1, live.secretsFound());
        assertEquals("PERSONAL", live.secretsSource());
        stats.playerStats(SELF).setApiRunSecretsFound(4);
        var exact = stats.livePlayerReports(null).getFirst();
        assertEquals(4, exact.secretsFound());
        assertEquals("API_DELTA", exact.secretsSource());
    }

    @Test
    public void aChestOrSkullCountsOncePerBlockAndOtherBlocksNever() {
        var stats = run();
        stats.observeTabLine(null, "Secrets Found: 0", null);
        var chest = new BlockPos(10, 70, 10);
        long now = System.currentTimeMillis();
        stats.observeOwnBlockClick(chest, Blocks.CHEST.defaultBlockState(), now);
        stats.observeOwnBlockClick(chest, Blocks.CHEST.defaultBlockState(), now);
        stats.observeOwnBlockClick(new BlockPos(11, 70, 10), Blocks.STONE.defaultBlockState(), now);
        stats.observeOwnBlockClick(new BlockPos(12, 70, 10), Blocks.PLAYER_HEAD.defaultBlockState(), now);
        // Two valid clicks, so a rise of three only gives two to this player.
        stats.observeTabLine(null, "Secrets Found: 3", null);
        assertEquals(List.of("Beng114=2", "Awesomeness_Boy=?"), counts(stats));
    }
}
