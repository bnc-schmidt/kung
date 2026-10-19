package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.SackItem;
import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import org.junit.Test;

public final class SackTrackerFeatureTest {
    @Test
    public void onlyVanillaScreensShowTheTrackerNotOtherModsMenus() {
        assertTrue(SackTrackerFeature.vanilla(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class));
        assertFalse(SackTrackerFeature.vanilla(SackTrackerFeatureTest.class)); // stands in for a mod's own screen
    }

    @Test
    public void sackMenusCountsAndMessagesParse() {
        assertTrue(SackTrackerFeature.isSack("Large Enchanted Agronomy Sack"));
        assertFalse(SackTrackerFeature.isSack("Sack of Sacks"));

        assertEquals(Long.valueOf(1234), SackTrackerFeature.stored(List.of("Item", " Stored: 1,234/20.2k", "Click to pickup!")));
        assertNull(SackTrackerFeature.stored(List.of(" Rough: 12", " Flawed: 3")));
        assertEquals(Long.valueOf(1_500_000), SackTrackerFeature.parseAmount("1.5m"));
        assertEquals(Long.valueOf(10_000), SackTrackerFeature.parseAmount("10,000"));
        assertNull(SackTrackerFeature.parseAmount(""));

        // Added and removed lists net out per item; everything else in the hover is ignored.
        assertEquals(Map.of("Wheat", 1270L, "Cobblestone", -10L), SackTrackerFeature.changes(List.of(
            "Added items:\n  +1,280 Wheat (Agronomy Sack)\n\nThis message can be disabled in the settings.",
            "Removed items:\n  -10 Wheat (Agronomy Sack)\n  -10 Cobblestone (Mining Sack, Large Mining Sack)")));
    }

    private static final int BACKSPACE = 259;

    @Test
    public void typingBuildsAndErasesTheGoal() {
        assertEquals(Long.valueOf(1), SackTrackerFeature.nextGoal(0, '1', false));
        assertEquals(Long.valueOf(15), SackTrackerFeature.nextGoal(1, '5', false));
        assertEquals(Long.valueOf(150), SackTrackerFeature.nextGoal(15, 320, false)); // numpad 0
        assertEquals(Long.valueOf(0), SackTrackerFeature.nextGoal(0, '0', false)); // a leading zero stays "no goal"
        assertEquals(Long.valueOf(1), SackTrackerFeature.nextGoal(15, BACKSPACE, false));
        assertEquals(Long.valueOf(0), SackTrackerFeature.nextGoal(1, BACKSPACE, false));
        assertEquals(Long.valueOf(0), SackTrackerFeature.nextGoal(0, BACKSPACE, false));
        assertEquals(Long.valueOf(0), SackTrackerFeature.nextGoal(123_456, BACKSPACE, true));
        // At most nine digits: the digit is swallowed, not appended, and nothing overflows.
        assertEquals(Long.valueOf(999_999_999), SackTrackerFeature.nextGoal(99_999_999, '9', false));
        assertEquals(Long.valueOf(999_999_999), SackTrackerFeature.nextGoal(999_999_999, '1', false));
        assertEquals(Long.valueOf(Long.MAX_VALUE), SackTrackerFeature.nextGoal(Long.MAX_VALUE, '1', false));
        assertEquals(Long.valueOf(150_000_000), SackTrackerFeature.nextGoal(1_500_000_000L, BACKSPACE, false));
        // Every other key is left to the screen.
        assertNull(SackTrackerFeature.nextGoal(15, 'A', false));
        assertNull(SackTrackerFeature.nextGoal(15, 256, false)); // Escape
        assertNull(SackTrackerFeature.nextGoal(15, 261, false)); // Delete
    }

    @Test
    public void amountTurnsYellowRedGreenWithTheGoal() {
        assertEquals(List.of(0xFFFF55), amountAndGoalColors(new SackItem("Wheat", 0xFFFFFF, 1234, 0)));
        assertEquals(List.of(0xFF5555, 0xFFFFFF), amountAndGoalColors(new SackItem("Wheat", 0xFFFFFF, 14, 15)));
        assertEquals(List.of(0x55FF55, 0xFFFFFF), amountAndGoalColors(new SackItem("Wheat", 0xFFFFFF, 15, 15)));
        assertEquals(List.of(0x55FF55, 0xFFFFFF), amountAndGoalColors(new SackItem("Wheat", 0xFFFFFF, 20, 15)));
        assertEquals("Wheat: 1,234/15,000", SackTrackerFeature.lines(List.of(new SackItem("Wheat", 0xFFFFFF, 1234, 15000)), false).get(1).getString());
        assertEquals("Wheat: 1,234", SackTrackerFeature.lines(List.of(new SackItem("Wheat", 0xFFFFFF, 1234, 0)), false).get(1).getString());
    }

    /** Colors of the amount and, when there is a goal, the "/goal" part (the name and ": " come first). */
    private static List<Integer> amountAndGoalColors(SackItem item) {
        Component line = SackTrackerFeature.lines(List.of(item), false).get(1);
        return line.getSiblings().stream().skip(1).map(part -> part.getStyle().getColor().getValue()).toList();
    }

    @Test
    public void goalsSurviveSavingAndLoading() {
        Gson gson = new Gson();
        MiscConfig config = gson.fromJson(
            "{\"sackTrackerItems\":[{\"name\":\"Wheat\",\"color\":16777215,\"amount\":5,\"goal\":0}]}", MiscConfig.class);
        config.setSackTrackerGoal(config.sackTrackerItem("Wheat"), SackTrackerFeature.nextGoal(1, '5', false));
        MiscConfig reloaded = gson.fromJson(gson.toJson(config), MiscConfig.class);
        assertEquals(15, reloaded.sackTrackerItem("Wheat").goal());
        assertEquals(5, reloaded.sackTrackerItem("Wheat").amount());
    }
}
