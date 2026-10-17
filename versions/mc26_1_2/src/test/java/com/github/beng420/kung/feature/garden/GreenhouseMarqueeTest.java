package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class GreenhouseMarqueeTest {
    private final AtomicLong clock = new AtomicLong(50_000L);
    private final GreenhouseMarquee marquee = new GreenhouseMarquee(clock::get);

    @Test
    public void aLabelThatFitsNeverMoves() {
        for (long t = 0; t < 10_000; t += 137) {
            assertEquals(0, GreenhouseMarquee.offset(0, t));
            assertEquals(0, GreenhouseMarquee.offset(-5, t));
        }
        assertEquals(0, marquee.always(0));
        assertEquals(0, marquee.onHover(1, 0, true));
    }

    @Test
    public void waitsScrollsToTheEndWaitsAndScrollsBack() {
        // 22 units at 22 units per second take one second each way, with 800 ms pauses: a 3.6 s cycle.
        int overflow = 22;
        assertEquals(0, GreenhouseMarquee.offset(overflow, 0));
        assertEquals(0, GreenhouseMarquee.offset(overflow, 799));
        assertEquals(0, GreenhouseMarquee.offset(overflow, 800));
        assertEquals(11, GreenhouseMarquee.offset(overflow, 1_300));
        assertEquals(21, GreenhouseMarquee.offset(overflow, 1_799));
        assertEquals(22, GreenhouseMarquee.offset(overflow, 1_800));
        assertEquals(22, GreenhouseMarquee.offset(overflow, 2_599));
        assertEquals(22, GreenhouseMarquee.offset(overflow, 2_600));
        assertEquals(11, GreenhouseMarquee.offset(overflow, 3_100));
        assertEquals(0, GreenhouseMarquee.offset(overflow, 3_599));
        // And around again.
        assertEquals(0, GreenhouseMarquee.offset(overflow, 3_600));
        assertEquals(0, GreenhouseMarquee.offset(overflow, 3_600 + 799));
        assertEquals(11, GreenhouseMarquee.offset(overflow, 3_600 + 1_300));
        assertEquals(22, GreenhouseMarquee.offset(overflow, 3_600 * 5 + 2_000));
    }

    @Test
    public void theTravelTimeGrowsWithTheOverflowAtAboutTwentyTwoUnitsPerSecond() {
        // 44 units: two seconds each way.
        assertEquals(22, GreenhouseMarquee.offset(44, 800 + 1_000));
        assertEquals(44, GreenhouseMarquee.offset(44, 800 + 2_000));
        assertEquals(44, GreenhouseMarquee.offset(44, 800 + 2_000 + 799));
        assertEquals(22, GreenhouseMarquee.offset(44, 800 + 2_000 + 800 + 1_000));
        // One unit of overflow still pauses first, and never leaves 0..overflow.
        for (int overflow : new int[] {1, 3, 7, 22, 45, 120}) {
            for (long t = 0; t < 20_000; t += 53) {
                int offset = GreenhouseMarquee.offset(overflow, t);
                assertTrue(overflow + " at " + t + ": " + offset, offset >= 0 && offset <= overflow);
            }
            assertEquals(0, GreenhouseMarquee.offset(overflow, 100));
        }
    }

    @Test
    public void theMotionIsContinuousAndNeverJumpsMoreThanTheSpeedAllows() {
        int overflow = 60;
        int previous = GreenhouseMarquee.offset(overflow, 0);
        for (long t = 1; t < 30_000; t++) {
            int offset = GreenhouseMarquee.offset(overflow, t);
            // 22 units per second is 0.022 per millisecond: at most one unit per 45 ms, so never more than 1 per ms.
            assertTrue("jump at " + t, Math.abs(offset - previous) <= 1);
            previous = offset;
        }
    }

    @Test
    public void negativeTimeCountsAsTheStart() {
        assertEquals(0, GreenhouseMarquee.offset(30, -1));
        assertEquals(0, GreenhouseMarquee.offset(30, Long.MIN_VALUE));
    }

    @Test
    public void buttonLabelsFollowTheClockFromTheMomentTheMarqueeWasMade() {
        assertEquals(0, marquee.always(22));
        clock.addAndGet(799);
        assertEquals(0, marquee.always(22));
        clock.addAndGet(501);
        assertEquals(11, marquee.always(22));
        clock.addAndGet(500);
        assertEquals(22, marquee.always(22));
        clock.addAndGet(1_800);
        assertEquals(0, marquee.always(22));
    }

    @Test
    public void hoveredTextStartsFromTheBeginningEachTimeThePointerArrives() {
        marquee.beginFrame();
        assertEquals(0, marquee.onHover(7, 22, true));
        marquee.endFrame();
        clock.addAndGet(1_300);
        marquee.beginFrame();
        assertEquals(11, marquee.onHover(7, 22, true));
        marquee.endFrame();

        // A frame without a hover, then the pointer comes back: the pause starts again.
        clock.addAndGet(16);
        marquee.beginFrame();
        assertEquals(0, marquee.onHover(7, 22, false));
        marquee.endFrame();
        clock.addAndGet(16);
        marquee.beginFrame();
        assertEquals(0, marquee.onHover(7, 22, true));
        marquee.endFrame();
        clock.addAndGet(1_000);
        marquee.beginFrame();
        assertEquals(4, marquee.onHover(7, 22, true));
        marquee.endFrame();
    }

    @Test
    public void movingToAnotherElementRestartsAndOnlyOneIsEverHovered() {
        marquee.beginFrame();
        marquee.onHover(1, 22, true);
        marquee.endFrame();
        clock.addAndGet(1_300);
        marquee.beginFrame();
        assertEquals(11, marquee.onHover(1, 22, true));
        marquee.endFrame();

        clock.addAndGet(10);
        marquee.beginFrame();
        assertEquals(0, marquee.onHover(2, 22, true));
        marquee.endFrame();
        clock.addAndGet(1_300);
        marquee.beginFrame();
        assertEquals(11, marquee.onHover(2, 22, true));
        marquee.endFrame();
    }
}
