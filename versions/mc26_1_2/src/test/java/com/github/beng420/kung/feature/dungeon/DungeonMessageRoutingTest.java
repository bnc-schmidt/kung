package com.github.beng420.kung.feature.dungeon;

import static com.github.beng420.kung.TestFixtures.receiveGame;
import static org.junit.Assert.*;

import com.github.beng420.kung.util.KungDebugRecorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import org.junit.Test;

public final class DungeonMessageRoutingTest {
    @Test public void filteredAndReformattedMessagesKeepBonusEvidenceAndSplitBoundariesOnce() {
        var active = new AtomicBoolean(true);
        var hidden = new AtomicBoolean(false);
        var received = new ArrayList<String>();
        var stats = new DungeonRunStats();
        stats.configureForFloor(7, true);
        stats.observeTabLine(null, "Completed Rooms: 36/36", null);
        stats.observeScoreboardLine(null, "Cleared: 100%");
        stats.observeTabLine(null, "Crypts: 5", null);
        stats.observeStatLine(null, "Secrets: 41/52");
        stats.observeTabLine(null, "Secrets Found: 78.8%", null);
        AtomicLong clock = new AtomicLong();
        var splits = new DungeonSplitTracker(clock::get);
        splits.startRun(0L, 7, true);
        // Another mod may register its filter before Kung.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !active.get() || !hidden.get());
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> !active.get() || !hidden.get());
        DungeonEventRouter.registerMessageObservers((message, overlay) -> {
            if (!active.get()) return;
            received.add(message.getString());
            stats.observeMessage(null, message.getString(), 0L, overlay
                ? DungeonDeathTracker.MessageSource.ACTIONBAR : DungeonDeathTracker.MessageSource.SYSTEM);
            if (!overlay) splits.observeMessage(message.getString(), 0L);
        }, message -> {
            if (!active.get()) return;
            received.add(message.getString());
            stats.observeMessage(null, message.getString(), 0L, DungeonDeathTracker.MessageSource.CHAT);
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
            active.get() ? Component.literal("Reformatted output") : message);
        try {
            assertTrue(receiveGame("Party > [MVP+] OmoriLover_: Mimic Killed!", false));
            assertTrue(receiveGame("A Bat has been slain. +1 Bonus Score", false));
            assertEquals(299, stats.score(null, 52));
            assertEquals(42, stats.sPlusSecretTarget(null, 52));
            assertTrue(receiveGame("A Prince falls. +1 Bonus Score", true));
            assertFalse(stats.princeKilled());

            hidden.set(true);
            clock.set(1_119L);
            assertFalse(receiveGame("[NPC] Mort: Here, I found this map when I first entered the dungeon.", false));
            assertEquals(0L, splits.currentTotalDurationMillis());
            assertFalse(receiveGame("A Prince falls. +1 Bonus Score", false));
            assertEquals(300, stats.score(null, 52));
            assertEquals(41, stats.sPlusSecretTarget(null, 52));
            assertTrue(KungDebugRecorder.dump().contains("[score-bonus] prince=true bat=true"));
            assertFalse(receiveGame("A Prince falls. +1 Bonus Score", false));
            assertEquals(300, stats.score(null, 52));
            assertEquals("Each server event is observed once, before display filtering", 6, received.size());

            var report = Component.literal("Party > Alice: Prince Killed!");
            assertFalse(ClientReceiveMessageEvents.ALLOW_CHAT.invoker()
                .allowReceiveChatMessage(report, null, null, null, Instant.EPOCH));
            ClientReceiveMessageEvents.CHAT_CANCELED.invoker()
                .onReceiveChatMessageCanceled(report, null, null, null, Instant.EPOCH);
            assertEquals(7, received.size());
            assertEquals(300, stats.score(null, 52));
        } finally {
            active.set(false);
        }
    }
}
