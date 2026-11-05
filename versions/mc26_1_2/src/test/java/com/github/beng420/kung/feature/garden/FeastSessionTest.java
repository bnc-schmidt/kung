package com.github.beng420.kung.feature.garden;

import java.io.IOException;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeastSessionTest {
    private static final String DONATION = "RARE CROP! Seasoning (+148%) (automatically donated!)";
    private static final FeastContext.Event EVENT = new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:513");

    @Test
    public void liveMenuEstablishesBaselineAndSeasoningAdvancesIt() throws IOException {
        var session = new FeastSession();
        session.select(EVENT, true);
        var snapshot = FeastProgress.inspectMenu("Grand Feast", FeastProgressTest.liveGrandMenu()).snapshot();
        assertTrue(session.observeMenu(new Object(), snapshot));
        assertEquals(25, session.snapshot().donations());
        assertEquals(750, session.snapshot().goal());
        assertEquals(50, session.snapshot().toNext());
        session.closeMenu();
        assertTrue(session.donate(DONATION));
        assertEquals(26, session.snapshot().donations());
        assertEquals(49, session.snapshot().toNext());
    }

    @Test
    public void liveDonationAdvancesBeforeMenuReopenAndIsNotCountedAgainByResync() {
        var session = new FeastSession();
        session.select(EVENT, true);
        session.observeMenu(new Object(), total(27));
        session.closeMenu();
        assertTrue(session.donate("[17:36:58] RARE CROP! Seasoning (+80\uE02B) (automatically donated)"));
        assertEquals(28, session.snapshot().donations());
        assertEquals(47, session.snapshot().toNext());
        assertFalse(session.donate("[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse."));
        assertTrue(session.observeMenu(new Object(), total(28)));
        assertEquals(28, session.snapshot().donations());
    }

    @Test
    public void pollingUnchangedOrDelayedMenuLoreDoesNotUndoChatDonations() {
        var session = new FeastSession();
        Object menu = new Object();
        session.select(EVENT, true);
        assertTrue(session.observeMenu(menu, total(234)));
        session.donate(DONATION);
        session.donate(DONATION);
        assertFalse(session.observeMenu(menu, total(234)));
        assertFalse(session.observeMenu(menu, total(235)));
        assertEquals(236, session.snapshot().donations());
        assertTrue(session.observeMenu(menu, total(236)));
        assertEquals(236, session.snapshot().donations());
    }

    @Test
    public void reopeningCanCorrectTheCounterWithoutCreatingASecondDonation() {
        var session = new FeastSession();
        session.select(EVENT, true);
        session.observeMenu(new Object(), total(234));
        session.donate(DONATION);
        session.closeMenu();
        assertTrue(session.observeMenu(new Object(), total(234)));
        assertEquals(234, session.snapshot().donations());
    }

    @Test
    public void newTermClearsDonationsEvenWhenFinneganRemainsAndOldMenuStaysOpen() {
        var session = new FeastSession();
        Object menu = new Object();
        session.select(EVENT, true);
        session.observeMenu(menu, total(234));
        session.select(new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:514"), true);
        assertNull(session.snapshot());
        assertFalse(session.donate(DONATION));
        assertFalse(session.observeMenu(menu, total(234)));
        assertTrue(session.observeMenu(menu, total(0)));
        assertEquals(0, session.snapshot().donations());
    }

    @Test
    public void temporaryUnknownDateDuringWarpPreservesTheHarvestBaseline() {
        var session = new FeastSession();
        var harvest = new FeastContext.Event(FeastProgress.Kind.HARVEST, "harvest:1");
        session.select(harvest, true);
        session.observeMenu(new Object(), new FeastProgress.Snapshot(FeastProgress.Kind.HARVEST,
            234, List.of(5, 25, 75, 150, 250)));
        session.select(null, false);
        assertFalse(session.donate(DONATION));
        assertEquals(234, session.snapshot().donations());
        session.select(harvest, true);
        assertTrue(session.donate(DONATION));
        assertEquals(235, session.snapshot().donations());
        session.select(null, true);
        assertNull(session.snapshot());
    }

    @Test
    public void profileInvalidationAndDisconnectRequireANewBaseline() {
        var session = new FeastSession();
        Object menu = new Object();
        session.select(EVENT, true);
        session.observeMenu(menu, total(234));
        session.invalidate();
        assertNull(session.snapshot());
        assertFalse(session.observeMenu(menu, total(234)));
        session.closeMenu();
        session.select(EVENT, true);
        assertTrue(session.observeMenu(new Object(), total(5)));
        session.reset();
        assertNull(session.event());
        assertNull(session.snapshot());
        assertFalse(session.donate(DONATION));
    }

    @Test
    public void wrongFeastAndInactiveMenusCannotSetABaseline() {
        var session = new FeastSession();
        assertFalse(session.observeMenu(new Object(), total(234)));
        session.select(new FeastContext.Event(FeastProgress.Kind.HARVEST, "harvest:1"), true);
        assertFalse(session.observeMenu(new Object(), total(234)));
        assertNull(session.snapshot());
    }

    private static FeastProgress.Snapshot total(int donations) {
        return new FeastProgress.Snapshot(FeastProgress.Kind.GRAND, donations,
            List.of(5, 25, 75, 150, 250, 325, 475, 625, 750));
    }
}
