package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class GreenhouseNoticeTest {
    private final AtomicLong clock = new AtomicLong(10_000L);
    private final GreenhouseNotice notice = new GreenhouseNotice(clock::get);

    @Test
    public void showsForThreeSecondsThenGoesAway() {
        assertNull(notice.text());
        notice.show("Invalid layout link");

        assertEquals("Invalid layout link", notice.text());
        clock.addAndGet(2_999);
        assertEquals("Invalid layout link", notice.text());
        clock.addAndGet(1);
        assertNull(notice.text());
        clock.addAndGet(-1);
        // Once expired it stays gone, even if the clock were to move back.
        assertNull(notice.text());
    }

    @Test
    public void aNewerMessageReplacesTheOldOneAndRestartsTheTimer() {
        notice.show("first");
        clock.addAndGet(2_500);
        notice.show("second");
        clock.addAndGet(2_500);

        assertEquals("second", notice.text());
        clock.addAndGet(500);
        assertNull(notice.text());
    }

    @Test
    public void dismissAndBlankMessagesClearIt() {
        notice.show("something");
        notice.dismiss();
        assertNull(notice.text());

        notice.show("something");
        notice.show("  ");
        assertNull(notice.text());
        notice.show("again");
        notice.show(null);
        assertNull(notice.text());
    }

    @Test
    public void remainingFractionFallsFromOneToZero() {
        assertEquals(0F, notice.remaining(), 0F);
        notice.show("timed");
        assertEquals(1F, notice.remaining(), 1e-6F);
        clock.addAndGet(1_500);
        assertEquals(0.5F, notice.remaining(), 1e-6F);
        clock.addAndGet(1_500);
        assertEquals(0F, notice.remaining(), 0F);
        assertNull(notice.text());
    }
}
