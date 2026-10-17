package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.feature.garden.GreenhouseHologram.ShowResult;
import com.github.beng420.kung.feature.garden.GreenhouseSession.Tone;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class GreenhouseActionsTest {
    private static GreenhouseGrid oneCell() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(4, 4, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.INGREDIENT));
        return grid;
    }

    @Test
    public void showingTheDisplayedLayoutClosesTheScreenWithTheHologramsMessage() {
        AtomicInteger calls = new AtomicInteger();
        GreenhouseGrid layout = oneCell();

        GreenhouseActions.Outcome outcome = GreenhouseActions.showLayout(layout, grid -> {
            calls.incrementAndGet();
            assertEquals(layout, grid);
            return new ShowResult("Hologram armed: walk into your greenhouse", false);
        });

        assertTrue(outcome.closeScreen());
        assertEquals("Hologram armed: walk into your greenhouse", outcome.chat());
        assertNull(outcome.notice());
        assertEquals(1, calls.get());
    }

    @Test
    public void anEmptyLayoutStaysOpenWithAPopupAndNeverTouchesTheHologram() {
        AtomicInteger calls = new AtomicInteger();

        GreenhouseActions.Outcome outcome = GreenhouseActions.showLayout(new GreenhouseGrid(), grid -> {
            calls.incrementAndGet();
            return new ShowResult("unused", true);
        });

        assertFalse(outcome.closeScreen());
        assertEquals("No layout to show", outcome.notice());
        assertEquals("No layout to show", outcome.status());
        assertEquals(Tone.WARNING, outcome.tone());
        assertNull(outcome.chat());
        assertEquals(0, calls.get());
    }

    @Test
    public void aValidClipboardClosesTheScreenAndAnEmptyOneExplainsItself() {
        GreenhouseActions.Outcome shown = GreenhouseActions.showClipboard(
            () -> Optional.of(new ShowResult("Showing hologram in your greenhouse", true)));

        assertTrue(shown.closeScreen());
        assertEquals("Showing hologram in your greenhouse", shown.chat());

        GreenhouseActions.Outcome none = GreenhouseActions.showClipboard(Optional::empty);

        assertFalse(none.closeScreen());
        assertEquals("No valid layout code", none.notice());
        assertEquals("No valid layout code in clipboard", none.status());
        assertEquals(Tone.ERROR, none.tone());
        assertNull(none.chat());
    }

    @Test
    public void theCopyWarningCountsTheDroppedCells() {
        assertNull(GreenhouseActions.copyWarning(0));
        assertNull(GreenhouseActions.copyWarning(-1));
        assertEquals("1 cell could not be encoded (unknown crops)", GreenhouseActions.copyWarning(1));
        assertEquals("3 cells could not be encoded (unknown crops)", GreenhouseActions.copyWarning(3));
    }

    @Test
    public void theCountComesFromTheCodesOwnUnrepresentableCheck() {
        GreenhouseGrid grid = oneCell();
        grid.set(0, 0, new GreenhouseGrid.Cell("Mystery Crop", GreenhouseGrid.INGREDIENT));
        grid.set(0, 1, new GreenhouseGrid.Cell("Carrot", 7));

        String warning = GreenhouseActions.copyWarning(GreenhouseCode.unrepresentable(grid));

        assertEquals("2 cells could not be encoded (unknown crops)", warning);
        assertNull(GreenhouseActions.copyWarning(GreenhouseCode.unrepresentable(oneCell())));
    }
}
