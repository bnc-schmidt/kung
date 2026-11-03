package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class GardenAlarmsTest {
    private static final List<String> SIDEBAR_PLOT = List.of("SKYBLOCK", "Autumn 5th", "⏣ Plot - 5");
    private static final List<String> NO_EFFECTS = List.of("Pests: (0)", " Spray: None", " Bonus: INACTIVE");

    /** Harvests spread evenly: {@code count} crops from {@code start} to {@code end}. */
    private static FarmingActivity farmed(long start, long end, int count) {
        var run = new FarmingActivity();
        for (int i = 0; i < count; i++) run.harvest(start + (end - start) * i / (count - 1));
        return run;
    }

    @Test
    public void muteKeyCountsOnePressPerPushAndIgnoresAKeyHeldBefore() {
        var key = new AlarmMuteKey();
        assertTrue(key.press(true));
        assertFalse(key.press(true));
        assertFalse(key.press(false));
        assertTrue(key.press(true));
        assertFalse(key.press(false));
    }

    @Test
    public void aFewCropsNeverCountAsFarming() {
        var run = new FarmingActivity();
        for (int i = 0; i < 3; i++) run.harvest(i * 400L);
        assertFalse(run.active(1_000));
        assertFalse(run.active(3_000));
    }

    @Test
    public void aFastBurstThatIsOverQuicklyIsNotFarming() {
        assertFalse(farmed(0, 5_000, 200).active(5_000));
    }

    @Test
    public void sustainedHarvestingCountsAndAShortPauseKeepsIt() {
        var run = farmed(0, FarmingActivity.MIN_RUN_MILLIS, FarmingActivity.MIN_HARVESTS);
        assertTrue(run.active(FarmingActivity.MIN_RUN_MILLIS));
        assertTrue(run.active(FarmingActivity.MIN_RUN_MILLIS + FarmingActivity.MAX_GAP_MILLIS));
    }

    @Test
    public void aLongPauseEndsTheRunAndAFreshRunStartsOver() {
        var run = farmed(0, 30_000, 100);
        assertTrue(run.active(30_000));
        assertFalse(run.active(30_000 + FarmingActivity.MAX_GAP_MILLIS + 1));
        run.harvest(60_000);
        assertFalse(run.active(60_000));
        run.reset();
        assertFalse(run.active(30_000));
    }

    @Test
    public void greenhouseBarnGuestGardenAndMissingPlotNeverCountAsFarmingInAPlot() {
        assertTrue(FarmingActivity.farmingInPlot(true, false, "5", true));
        assertFalse(FarmingActivity.farmingInPlot(true, true, "5", true));
        assertFalse(FarmingActivity.farmingInPlot(true, false, null, true));
        assertFalse(FarmingActivity.farmingInPlot(false, false, "5", true));
        assertFalse(FarmingActivity.farmingInPlot(true, false, "5", false));
    }

    @Test
    public void readsPlotSprayAndBonusFromTheServerRows() {
        var none = GardenEffects.read(SIDEBAR_PLOT, NO_EFFECTS);
        assertEquals("5", none.plot());
        assertEquals(Boolean.FALSE, none.sprayed());
        assertEquals(Boolean.FALSE, none.ffBuff());
        var active = GardenEffects.read(SIDEBAR_PLOT,
            List.of("Pests: (2)", " Spray: Compost (4m 30s)", " Bonus: +60☘ 1h 2m"));
        assertEquals(Boolean.TRUE, active.sprayed());
        assertEquals(Boolean.TRUE, active.ffBuff());
        assertEquals(Boolean.TRUE, GardenEffects.read(SIDEBAR_PLOT,
            List.of("Pests: (1)", "Spray: Plant Matter (10m)")).sprayed());
    }

    @Test
    public void unreadableOrMissingRowsAreUnknownNotMissing() {
        var noWidget = GardenEffects.read(SIDEBAR_PLOT, List.of(" Spray: None", " Bonus: INACTIVE"));
        assertNull(noWidget.sprayed());
        assertNull(noWidget.ffBuff());
        var oddBonus = GardenEffects.read(SIDEBAR_PLOT, List.of("Pests: (0)", "Bonus: ???"));
        assertNull(oddBonus.ffBuff());
        assertNull(oddBonus.sprayed());
    }

    @Test
    public void barnAndGreenhouseRowsGiveNoPlot() {
        assertNull(GardenEffects.read(List.of("SKYBLOCK", "⏣ The Barn"), NO_EFFECTS).plot());
        assertNull(GardenEffects.read(List.of("SKYBLOCK", "⏣ Greenhouse Plot"), NO_EFFECTS).plot());
        assertEquals("12", GardenEffects.read(List.of("SKYBLOCK"), List.of("Plot - 12")).plot());
    }

    @Test
    public void missingEffectRingsOnceThenWaitsForTheEffect() {
        var alarm = new MissingEffectAlarm();
        assertFalse("not farming", alarm.update(false, false));
        assertFalse("tab does not say", alarm.update(true, null));
        assertTrue(alarm.update(true, false));
        assertFalse("latched", alarm.update(true, false));
        assertFalse("latched", alarm.update(true, null));
        assertFalse("effect back", alarm.update(true, true));
        assertTrue("lost again", alarm.update(true, false));
    }

    @Test
    public void rearmRingsAgainForTheNextPlotOrWorld() {
        var alarm = new MissingEffectAlarm();
        assertTrue(alarm.update(true, false));
        alarm.rearm();
        assertFalse("rearmed but not farming yet", alarm.update(false, false));
        assertTrue(alarm.update(true, false));
    }

    @Test
    public void shortHarvestingWithoutSprayNeverRings() {
        var run = new FarmingActivity();
        var alarm = new MissingEffectAlarm();
        for (int i = 0; i < 3; i++) run.harvest(i * 300L);
        var reading = GardenEffects.read(SIDEBAR_PLOT, NO_EFFECTS);
        boolean farming = FarmingActivity.farmingInPlot(true, false, reading.plot(), run.active(1_000));
        assertFalse(alarm.update(farming, reading.sprayed()));
    }

    @Test
    public void sustainedFarmingWithoutSprayRingsButNotInTheGreenhouse() {
        var run = farmed(0, 30_000, 120);
        var reading = GardenEffects.read(SIDEBAR_PLOT, NO_EFFECTS);
        var inGreenhouse = new MissingEffectAlarm();
        assertFalse(inGreenhouse.update(
            FarmingActivity.farmingInPlot(true, true, reading.plot(), run.active(30_000)), reading.sprayed()));
        var inPlot = new MissingEffectAlarm();
        assertTrue(inPlot.update(
            FarmingActivity.farmingInPlot(true, false, reading.plot(), run.active(30_000)), reading.sprayed()));
    }

    @Test
    public void visitorWidgetAcceptsTheRaisedCap() {
        var lines = new java.util.ArrayList<String>(List.of("Visitors: (10)"));
        Set<String> names = Set.of("A1", "B2", "C3", "D4", "E5", "F6", "G7", "H8", "I9", "J10");
        lines.addAll(names);
        lines.add("Next Visitor: Queue Full!");
        var queue = VisitorQueue.parse(lines);
        assertNotNull(queue);
        assertEquals(10, queue.count());
        assertTrue(queue.full());
        assertNull(VisitorQueue.parse(List.of("Visitors: (11)", "Next Visitor: Queue Full!")));
    }
}
