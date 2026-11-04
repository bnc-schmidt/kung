package com.github.beng420.kung.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.ArrayList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.junit.Test;

public final class ServerTpsTrackerTest {
    @Test
    public void reportedStormStreamMustNotCountIts188AdditionalBundledPingsAsTicks() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-1), 0L);
        // 16:37:16 trace: 1,239 accepted pings, bundle counter 835 -> 1,023.
        // Noamm shows 52.55 s = (1,239 - 188) * 50 ms of actual tick time.
        int bundled = 0;
        for (int tick = 1; tick <= 1051; tick++) {
            long now = tick * 54_923_000_000L / 1051;
            tracker.recordPacketAtNanos(new ClientboundPingPacket(-tick - 1), now);
            while (bundled < tick * 188 / 1051) {
                tracker.recordPacketAtNanos(new ClientboundBundlePacket(List.of(
                    new ClientboundPingPacket(10_000 + bundled++)
                )), now);
            }
        }
        assertTrue(tracker.diagnostics(), tracker.diagnostics().contains("receivedTicks=1052 "));
        assertEquals(1051 * 1000.0 / 54_923, tracker.snapshotAtNanos(54_923_000_000L).average(), 0.0001);
    }

    @Test
    public void sustainedTenTpsSurvivesDeliveryBatchesAndAdditionalProtocolBundles() {
        for (int batchSize : new int[] {1, 10}) {
            ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
            tracker.reset();
            tracker.recordPacketAtNanos(new ClientboundPingPacket(-1), 0L);
            for (int endTick = batchSize; endTick <= 600; endTick += batchSize) {
                List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
                for (int tick = endTick - batchSize + 1; tick <= endTick; tick++) {
                    packets.add(new ClientboundPingPacket(-tick));
                    packets.add(new ClientboundPingPacket(0));
                    packets.add(new ClientboundPingPacket(-tick - 1));
                }
                long now = endTick * 100_000_000L;
                // Co-delivery retains standalone envelopes. Protocol bundles
                // alongside them contain additional pings, not more game ticks.
                for (Packet<?> packet : packets) tracker.recordPacketAtNanos(packet, now);
                tracker.recordPacketAtNanos(new ClientboundBundlePacket(List.of(
                    new ClientboundPingPacket(10_000 + endTick)
                )), now);
            }
            var snapshot = tracker.snapshotAtNanos(60_000_000_000L);
            assertEquals(10.0, snapshot.current(), 0.0001);
            assertEquals(10.0, snapshot.average(), 0.0001);
            assertTrue(tracker.diagnostics().contains("receivedTicks=601"));
        }
    }

    @Test
    public void packetsResumingAfterAStallDoNotInventTicksForTheGapOrIdDistance() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-1), 0L);
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-2), 50_000_000L);
        assertEquals(0.0, tracker.snapshotAtNanos(3_050_000_000L).current(), 0.0001);
        tracker.recordPacketAtNanos(new ClientboundBundlePacket(List.of(
            new ClientboundPingPacket(-2), new ClientboundPingPacket(0),
            new ClientboundPingPacket(-200), new ClientboundPingPacket(-201)
        )), 3_150_000_000L);
        assertEquals(0.0, tracker.snapshotAtNanos(3_150_000_000L).current(), 0.0001);
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-200), 3_150_000_000L);
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-201), 3_150_000_000L);
        assertEquals(1.0, tracker.snapshotAtNanos(3_150_000_000L).current(), 0.0001);
        assertTrue(tracker.diagnostics().contains("receivedTicks=4"));
        assertEquals(0.0, tracker.snapshotAtNanos(5_150_000_000L).current(), 0.0001);
    }

    @Test
    public void aCompleteStallAgesOutHealthyTpsEvenWithoutAnotherPacket() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        long now = 5_000_000_000L;
        for (int tick = 0; tick <= 40; tick++) {
            tracker.recordPacketAtNanos(new ClientboundPingPacket(-tick - 1),
                now - 5_000_000_000L + tick * 50_000_000L);
        }
        assertEquals(0.0, tracker.snapshotAtNanos(now).current(), 0.0001);
        tracker.recordPacketAtNanos(new ClientboundBundlePacket(List.of(
            new ClientboundPingPacket(0), new ClientboundPingPacket(-41)
        )), now);
        assertEquals(0.0, tracker.snapshotAtNanos(now).current(), 0.0001);
    }

    @Test
    public void aConnectionStallIsNotServerLagOnceItsBurstDeliversTheMissedTicks() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        int id = 0;
        for (int tick = 0; tick <= 100; tick++) {
            tracker.recordPacketAtNanos(new ClientboundPingPacket(--id), tick * 50_000_000L);
        }
        // 1.5 s without a single packet can be the connection as much as the server.
        assertEquals(20.0, tracker.deliveredSnapshotAtNanos(6_500_000_000L).current(), 0.0001);
        // At 7 s the connection recovers: the 40 ticks the server ran meanwhile arrive at once.
        for (int tick = 0; tick < 40; tick++) {
            tracker.recordPacketAtNanos(new ClientboundPingPacket(--id), 7_000_000_000L);
        }
        for (int tick = 1; tick <= 20; tick++) {
            tracker.recordPacketAtNanos(new ClientboundPingPacket(--id), 7_000_000_000L + tick * 50_000_000L);
        }
        assertEquals("Current: 20.0 (max/min/avg) 20.0/20.0/20.0",
            tracker.deliveredSnapshotAtNanos(8_000_000_000L).message());
        assertEquals(161, tracker.ticks());
    }

    @Test
    public void reportsUnavailableBeforeTwoTicks() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();

        assertFalse(tracker.snapshot().available());
        assertEquals("TPS data is not ready yet.", tracker.snapshot().message());
    }

    @Test
    public void formatsCurrentMaxMinAndAverageTps() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();

        long now = 0L;
        for (int tick = 0; tick <= 40; tick++) {
            tracker.recordTickAtNanos(now);
            now += 50_000_000L;
        }

        ServerTpsTracker.TpsSnapshot snapshot = tracker.snapshotAtNanos(2_000_000_000L);

        assertTrue(snapshot.available());
        assertEquals("Current: 20.0 (max/min/avg) 20.0/20.0/20.0", snapshot.message());
    }

    @Test
    public void bundlePingsCannotInflateOrDeduplicateTheStandaloneStream() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-1), 0L);
        for (int tick = 1; tick <= 100; tick++) {
            tracker.recordPacketAtNanos(new ClientboundBundlePacket(List.of(
                new ClientboundPingPacket(-tick), new ClientboundPingPacket(-tick - 1)
            )), tick * 50_000_000L);
            tracker.recordPacketAtNanos(new ClientboundPingPacket(-tick - 1), tick * 50_000_000L);
        }
        assertEquals(20.0, tracker.snapshotAtNanos(5_000_000_000L).average(), 0.0001);
        assertTrue(tracker.diagnostics(), tracker.diagnostics().contains("receivedTicks=101 ignoredBundledPings=200 duplicatePings=0"));
    }

    @Test
    public void batchingSeveralDistinctTicksAtOneArrivalDoesNotDiscardTicks() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-1), 0L);
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-2), 100_000_000L);
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-3), 100_000_000L);
        assertEquals(20.0, tracker.snapshotAtNanos(100_000_000L).average(), 0.0001);
    }

    @Test
    public void appliedTickSequenceAllowsNewIdsAndResetsWithoutInferringTicksFromIdDistance() {
        ServerTickSequence sequence = new ServerTickSequence();
        assertTrue(sequence.accept(-1, false));
        assertFalse(sequence.accept(-1, false));
        assertTrue(sequence.accept(-50, false));
        assertFalse(sequence.accept(0, false));
        assertFalse(sequence.accept(-50, false));
        sequence.reset();
        assertFalse(sequence.accept(0, false));
        assertTrue(sequence.accept(-50, false));
    }

    @Test
    public void zeroPingsNeverStartATickStream() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        for (int ping = 0; ping < 50; ping++) {
            tracker.recordPacketAtNanos(new ClientboundPingPacket(0), ping * 50_000_000L);
        }
        assertFalse(tracker.snapshot().available());
        assertTrue(tracker.diagnostics().contains("receivedTicks=0"));
        assertTrue(tracker.diagnostics().contains("nonTickPings=50"));
    }

    @Test
    public void zeroPingsBetweenRepeatedTickIdsDoNotInflateTpsOrHideASlowServer() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        for (int tick = 0; tick <= 100; tick++) {
            for (Packet<?> packet : List.of(
                new ClientboundPingPacket(-tick - 1), new ClientboundPingPacket(0),
                new ClientboundPingPacket(-tick - 1), new ClientboundPingPacket(0)
            )) tracker.recordPacketAtNanos(packet, tick * 100_000_000L);
        }
        assertEquals(10.0, tracker.snapshotAtNanos(10_000_000_000L).average(), 0.0001);
        assertTrue(tracker.diagnostics(), tracker.diagnostics().contains("receivedTicks=101 ignoredBundledPings=0 duplicatePings=101 nonTickPings=202"));
    }

    @Test
    public void bundleOnlyTrafficNeverEstablishesATickStreamAndResetClearsDiagnostics() {
        ServerTpsTracker tracker = ServerTpsTracker.INSTANCE;
        tracker.reset();
        for (int id = 1; id <= 60; id++) {
            tracker.recordPacketAtNanos(new ClientboundBundlePacket(List.of(
                new ClientboundPingPacket(id), new ClientboundPingPacket(-id), new ClientboundPingPacket(0)
            )), id * 50_000_000L);
        }
        assertFalse(tracker.snapshotAtNanos(3_000_000_000L).available());
        assertTrue(tracker.diagnostics().contains("receivedTicks=0 ignoredBundledPings=180"));
        tracker.reset();
        assertTrue(tracker.diagnostics().contains("receivedTicks=0 ignoredBundledPings=0"));
        tracker.recordPacketAtNanos(new ClientboundPingPacket(-60), 4_000_000_000L);
        assertTrue(tracker.diagnostics().contains("receivedTicks=1 ignoredBundledPings=0"));
    }
}
