package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class VisitorOfferTest {
    private static final Set<String> VISITORS = Set.of("Dulin", "Trinity", "Maeve", "Ludleth", "Taylor");
    private static final List<String> INFO = List.of("", "Rarity: RARE", "", "§7Offers Accepted: §a1,234");
    private static final VisitorQueue.Snapshot FULL = new VisitorQueue.Snapshot(VISITORS, null, true);

    @Test
    public void acceptAndRefuseClicksSilenceWithoutWaitingForVisitorToDisappear() {
        for (String button : List.of("§aAccept Offer", "§cRefuse Offer")) {
            var state = ringing();
            assertTrue(VisitorOffer.isDecision("§9Dulin", button, INFO, VISITORS));
            assertTrue(state.acknowledge());
            assertFalse(state.ringing());
            assertEquals(VISITORS, state.queue().visitors());
            state.tick();
            state.observe(FULL);
            state.observe(null);
            state.tick();
            assertFalse("Unchanged, delayed or missing data cannot restart the acknowledged alarm", state.ringing());
        }
    }

    @Test
    public void nextFillCanRingAfterImmediateReplacementOnceRoomOpened() {
        var state = ringing();
        state.acknowledge();
        var replaced = new VisitorQueue.Snapshot(Set.of("Beth", "Trinity", "Maeve", "Ludleth", "Taylor"), null, true);
        state.observe(replaced);
        state.tick();
        assertFalse(state.ringing());
        state.observe(new VisitorQueue.Snapshot(Set.of("Trinity", "Maeve", "Ludleth", "Taylor"), 300_000L, false));
        state.observe(replaced);
        state.tick();
        assertTrue(state.ringing());
    }

    @Test
    public void clicksBeforeAnAlarmAndResetDoNotSuppressFutureAlarms() {
        var state = new VisitorAlarmState();
        state.observe(FULL);
        assertFalse(state.acknowledge());
        state.tick();
        assertTrue(state.ringing());
        state.acknowledge();
        state.reset();
        state.observe(FULL);
        state.tick();
        assertTrue(state.ringing());
    }

    @Test
    public void unrelatedMenuControlsAndOffersDoNotAcknowledge() {
        for (String button : List.of("Close", "Go Back", "Visitor Details", "Confirm", "Accept Offer Now", "")) {
            assertFalse(button, VisitorOffer.isDecision("Dulin", button, INFO, VISITORS));
        }
        assertFalse(VisitorOffer.isDecision("Auction House", "Accept Offer", INFO, VISITORS));
        assertFalse(VisitorOffer.isDecision("Dulin", "Accept Offer", List.of("Cost: 1,000 Coins"), VISITORS));
        assertFalse(VisitorOffer.isDecision("Dulin", "Refuse Offer", List.of(), VISITORS));
        assertFalse(VisitorOffer.isDecision("Dulin", "Accept Offer", INFO, Set.of()));
    }

    private static VisitorAlarmState ringing() {
        var state = new VisitorAlarmState();
        state.observe(FULL);
        state.tick();
        assertTrue(state.ringing());
        return state;
    }
}
