package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LoadoutsAutoCloseFeatureTest {
    @Test
    public void storedSideButtonBindsMatchOnlyTheirOwnGlfwButton() {
        // GLFW buttons 3-7 are MB4-MB8; a captured bind must match its raw onButton code and nothing else,
        // so unbound left (0) and right (1) clicks are never claimed.
        for (int bound = 3; bound <= 7; bound++) {
            String keybind = LoadoutsAutoCloseFeature.keybindFromMouseButton(bound);
            for (int pressed = 0; pressed <= 7; pressed++) {
                assertEquals(keybind + " vs " + pressed, bound == pressed,
                    LoadoutsAutoCloseFeature.keybindMatchesMouse(keybind, pressed));
            }
        }
        // The owner's config: MB4 on one slot, F5 on the next; neither type leaks into the other.
        assertEquals("M4", LoadoutsAutoCloseFeature.keybindDisplay("mouse:3"));
        assertFalse(LoadoutsAutoCloseFeature.keybindMatchesMouse("key:294:63", 3));
        assertFalse(LoadoutsAutoCloseFeature.keybindMatchesMouse("key:294:63", 294));
    }

    @Test
    public void keyLabelsKeepTheirOwnNames() {
        assertEquals("Backspace", LoadoutsAutoCloseFeature.keybindDisplay("key:259:14"));
        assertEquals("Space", LoadoutsAutoCloseFeature.keybindDisplay("key:32:57"));
        assertEquals("Shift", LoadoutsAutoCloseFeature.keybindDisplay("key:340:42"));
    }

    @Test
    public void playerNamedLoadoutsOnTheGridAreNotTakenForMenuButtons() {
        // The owner's Loadout 7 (slot 32) is "Kuudra Clear"; the "clear" button word used to swallow its bind.
        assertTrue(LoadoutsAutoCloseFeature.nameAllowsSelection(32, "Kuudra Clear"));
        assertTrue(LoadoutsAutoCloseFeature.nameAllowsSelection(33, "Kuudra Dps"));
        // Off the grid the button words still stop Close / Next Page clicks from scheduling a close.
        assertFalse(LoadoutsAutoCloseFeature.nameAllowsSelection(49, "Close"));
        assertFalse(LoadoutsAutoCloseFeature.nameAllowsSelection(53, "Next Page"));
    }
}
