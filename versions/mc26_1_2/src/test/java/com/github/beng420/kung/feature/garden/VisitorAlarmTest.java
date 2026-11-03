package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class VisitorAlarmTest {
    private static final Set<String> FIVE = Set.of("Maeve", "Liam", "Stella", "Jack", "Beth");
    private static final Set<String> REPLACED = Set.of("Maeve", "Liam", "Stella", "Jack", "Duke");
    private static VisitorQueue.Snapshot full() { return new VisitorQueue.Snapshot(FIVE, null, true); }
    private static VisitorQueue.Snapshot four(long timer) {
        return new VisitorQueue.Snapshot(Set.of("Maeve", "Liam", "Stella", "Jack"), timer, false);
    }

    @Test
    public void guestGardensAndIncompleteWorldTransfersCannotTrigger() {
        assertTrue(VisitorQueue.ownGarden("Garden", List.of("§eSKYBLOCK ♲"), List.of()));
        assertFalse(VisitorQueue.ownGarden("Garden", List.of("§eSKYBLOCK GUEST"), List.of()));
        assertFalse(VisitorQueue.ownGarden("Garden", List.of(), List.of("Area: Garden")));
        assertFalse(VisitorQueue.ownGarden("Hub", List.of("SKYBLOCK"), List.of("Area: Hub")));
    }

    @Test
    public void readsServerWidgetWithFormattingAndCountdown() {
        var queue = VisitorQueue.parse(List.of("Stats:", "§b§lVisitors: §r§f(5)", " §aMaeve", " §9Liam",
            " §6Stella", " §aJack", " §dBeth", " Next Visitor: §e1m 23s", "Guests (0)"));
        assertNotNull(queue);
        assertEquals(FIVE, queue.visitors());
        assertEquals(Long.valueOf(83_000), queue.remainingMillis());
        assertFalse(queue.full());
    }

    @Test
    public void queueFullAndZeroVisitorsAreDifferentFromUnknown() {
        assertTrue(VisitorQueue.parse(List.of("Visitors: (5)", "Maeve", "Liam", "Stella", "Jack", "Beth",
            "Next Visitor: Queue Full!")).full());
        assertEquals(0, VisitorQueue.parse(List.of("Visitors: (0)", "Next Visitor: 14m 2s")).count());
        assertNull(VisitorQueue.parse(List.of("Visitors: (5)", "Maeve", "Liam", "Next Visitor: Queue Full!")));
        assertNull(VisitorQueue.parse(List.of("Visitors: (5)", "Maeve", "Liam", "Stella", "Jack", "Jack",
            "Next Visitor: Queue Full!")));
        assertNull(VisitorQueue.parse(List.of("Party > Ben: Visitors: (5)", "Next Visitor: Queue Full!")));
        assertNull(VisitorQueue.parse(List.of("Visitors: (0)", "Next Visitor: Not Unlocked!")));
    }

    @Test
    public void parsesOnlyCompleteBoundedTimers() {
        assertEquals(Long.valueOf(0), VisitorQueue.duration("0s"));
        assertEquals(Long.valueOf(900_000), VisitorQueue.duration("15m"));
        assertEquals(Long.valueOf(61_000), VisitorQueue.duration("1m1s"));
        for (String invalid : List.of("", "Soon", "1m 99s", "99999999999s", "-1s", "1m left", "Queue Full!")) {
            assertNull(invalid, VisitorQueue.duration(invalid));
        }
    }

    @Test
    public void aFullQueueRingsOnceTheRosterIsStableAndStaysLatched() {
        var state = new VisitorAlarmState();
        state.observe(full());
        assertFalse(state.ringing());
        state.tick();
        assertTrue(state.ringing());
        state.observe(null);
        state.tick();
        assertTrue("missing tab data cannot stop it", state.ringing());
    }

    @Test
    public void roomInTheQueueNeverRingsWhateverTheTimerSays() {
        var state = new VisitorAlarmState();
        state.observe(four(0));
        state.tick();
        assertFalse(state.ringing());
        state.observe(new VisitorQueue.Snapshot(FIVE, 1_000L, false));
        state.tick();
        assertFalse("below a raised cap five visitors are not full", state.ringing());
    }

    @Test
    public void fillingTheQueueRings() {
        var state = new VisitorAlarmState();
        state.observe(four(60_000));
        state.tick();
        assertFalse(state.ringing());
        state.observe(full());
        state.tick();
        assertTrue(state.ringing());
    }

    @Test
    public void servingAVisitorStopsTheAlarmAndTheNextFillRingsAgain() {
        var state = new VisitorAlarmState();
        state.observe(full());
        state.tick();
        state.observe(four(300_000));
        assertFalse(state.ringing());
        state.tick();
        assertFalse(state.ringing());
        state.observe(new VisitorQueue.Snapshot(REPLACED, null, true));
        state.tick();
        assertTrue(state.ringing());
    }

    @Test
    public void aVisitorReplacedWithinOneUpdateStaysQuietUntilRoomOpens() {
        var state = new VisitorAlarmState();
        state.observe(full());
        state.tick();
        assertTrue(state.ringing());
        state.observe(new VisitorQueue.Snapshot(REPLACED, null, true));
        state.tick();
        assertFalse(state.ringing());
        state.observe(four(300_000));
        state.observe(full());
        state.tick();
        assertTrue(state.ringing());
    }

    @Test
    public void raisedCapQueueOfTenRingsWhenFull() {
        var nine = new VisitorQueue.Snapshot(Set.of("A1", "B2", "C3", "D4", "E5", "F6", "G7", "H8", "I9"), 1_000L, false);
        var ten = new VisitorQueue.Snapshot(Set.of("A1", "B2", "C3", "D4", "E5", "F6", "G7", "H8", "I9", "J10"), null, true);
        var state = new VisitorAlarmState();
        state.observe(nine);
        state.tick();
        assertFalse(state.ringing());
        state.observe(ten);
        state.tick();
        assertTrue(state.ringing());
    }

    @Test
    public void disableDisconnectOrProfileResetClearsEverything() {
        var state = new VisitorAlarmState();
        state.observe(full());
        state.tick();
        state.reset();
        state.tick();
        assertFalse(state.ringing());
        assertNull(state.queue());
    }
}
