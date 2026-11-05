package com.github.beng420.kung.config;

import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import org.junit.Test;

public final class SplitPersonalBestsScreenTest extends MinecraftBootstrapTest {
    @Test public void inputFilterAllowsEditingButRejectsLettersAndMisplacedSeparators() {
        for (String text : new String[] {"", "0", "01:", "01:2", "01:23.", ":.456", "01:23.456"}) {
            assertTrue(text, SplitPersonalBestsScreen.acceptsTimeInput(text));
        }
        for (String text : new String[] {"hello", "1m 23s", "-01:23.456", "01:23,456", "1:2:3", "1.23", "01:23.4567", " 01:23.456"}) {
            assertFalse(text, SplitPersonalBestsScreen.acceptsTimeInput(text));
        }
    }

    @Test public void saveRequiresACompletePositiveTimeWithValidSecondsAndNoOverflow() {
        assertEquals(83_456L, SplitPersonalBestsScreen.parseTime("01:23.456"));
        assertEquals(1L, SplitPersonalBestsScreen.parseTime("00:00.001"));
        assertEquals(6_000_000L, SplitPersonalBestsScreen.parseTime("100:00.000"));
        for (String text : new String[] {"", "00:00.000", "01:60.000", "01:2.000", "01:23", "01:23.4", "01:23.45", "999999999999999:59.999"}) {
            assertEquals(text, -1L, SplitPersonalBestsScreen.parseTime(text));
        }
    }

    @Test public void displayingThenSavingPreservesEveryStoredMillisecond() {
        assertEquals("--", SplitPersonalBestsScreen.formatTime(-1L));
        assertEquals("01:23.456", SplitPersonalBestsScreen.formatTime(83_456L));
        for (long millis : new long[] {1L, 999L, 60_001L, 3_600_000L, Long.MAX_VALUE}) {
            String shown = SplitPersonalBestsScreen.formatTime(millis);
            assertTrue(SplitPersonalBestsScreen.acceptsTimeInput(shown));
            assertEquals(millis, SplitPersonalBestsScreen.parseTime(shown));
        }
    }
}
