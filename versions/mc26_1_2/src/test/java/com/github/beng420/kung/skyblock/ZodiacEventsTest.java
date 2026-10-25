package com.github.beng420.kung.skyblock;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.misc.ChatCommandAliasTest;
import com.github.beng420.kung.skyblock.ZodiacEvents.Event;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;

public class ZodiacEventsTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli();
    // From the in-game calendar: Year of the Seal starts Sat 24 Oct 2026 15:55 (CEST) and lasts 5d 4h.
    private static final long SEAL_START = Instant.parse("2026-10-24T13:55:00Z").toEpochMilli();

    @Test
    public void theSealStartsWhereTheGameCalendarSaysAndLastsOneSkyBlockYear() {
        assertEquals(521, ZodiacEvents.currentYear(NOW));
        assertEquals(522, ZodiacEvents.yearOnOrAfter(Event.SEAL, NOW));
        assertEquals(SEAL_START, JerryTimeline.yearStartMillis(522));
        assertEquals(124 * 3_600_000L, JerryTimeline.SB_YEAR_MILLIS);
        // The year changes exactly at the start: before it year 521 runs, from it 522.
        assertEquals(521, ZodiacEvents.currentYear(SEAL_START - 1));
        assertEquals(522, ZodiacEvents.currentYear(SEAL_START));
    }

    @Test
    public void everyEventComesBackEveryTwelveYears() {
        assertEquals(522, ZodiacEvents.yearOnOrAfter(Event.SEAL, NOW));
        assertEquals(534, ZodiacEvents.yearOnOrAfter(Event.SEAL, SEAL_START + JerryTimeline.SB_YEAR_MILLIS));
        assertEquals(522, ZodiacEvents.yearOnOrAfter(Event.SEAL, SEAL_START)); // running counts
        // Pig and Witch run in the year before and after the Seal (an assumption, see ZodiacEvents).
        assertEquals(521, ZodiacEvents.yearOnOrAfter(Event.PIG, NOW));
        assertEquals(523, ZodiacEvents.yearOnOrAfter(Event.WITCH, NOW));
        assertEquals(533, ZodiacEvents.yearOnOrAfter(Event.PIG, SEAL_START));
    }

    @Test
    public void aRunningEventSaysWhenItEndsAndAnyOtherWhenItStarts() {
        assertEquals("Year of the Seal is not active. It starts in 1 day (Sat 24 Oct).", ZodiacEvents.answer(Event.SEAL, NOW, UTC));
        assertEquals("Year of the Pig is active and ends in 1 day (Sat 24 Oct).", ZodiacEvents.answer(Event.PIG, NOW, UTC));
        assertEquals("Year of the Witch is not active. It starts in 6 days (Thu 29 Oct).", ZodiacEvents.answer(Event.WITCH, NOW, UTC));
        // Short waits read in hours.
        long evening = SEAL_START - 5 * 3_600_000L - 59 * 60_000L;
        assertEquals("Year of the Seal is not active. It starts in 5h (Sat 24 Oct).", ZodiacEvents.answer(Event.SEAL, evening, UTC));
        // Once the Seal runs: ends in 5 days, on Thursday the 29th.
        assertEquals("Year of the Seal is active and ends in 5 days (Thu 29 Oct).", ZodiacEvents.answer(Event.SEAL, SEAL_START + 1_000, UTC));
        // The date follows the zone of whoever answers: 19:55 on the 29th is still the 29th in Berlin.
        assertEquals("Year of the Seal is active and ends in 5 days (Thu 29 Oct).",
            ZodiacEvents.answer(Event.SEAL, SEAL_START + 1_000, ZoneId.of("Europe/Berlin")));
    }

    @Test
    public void theCommandsHaveTheirOwnNamesAndSwitches() {
        var config = new com.github.beng420.kung.config.category.MiscConfig();
        assertTrue(ChatCommandAliasTest.matches("!yotp", "YOTP"));
        assertTrue(ChatCommandAliasTest.matches("!yotw", "YOTW"));
        assertTrue(ChatCommandAliasTest.matches("!YOTS ", "YOTS"));
        assertFalse(ChatCommandAliasTest.matches("!yots now", "YOTS"));
        assertFalse(ChatCommandAliasTest.matches("!yot", "YOTS"));
        // Party and guild on, public chat and DMs off, each command on its own.
        assertTrue(config.yotpPartyCommandsEnabled() && config.yotwGuildCommandsEnabled() && config.yotsChatCommandEnabled());
        assertFalse(config.yotpAllChatCommandsEnabled() || config.yotwPrivateCommandsEnabled());
        config.setYotsChatCommandEnabled(false);
        assertTrue(config.yotpChatCommandEnabled() && config.yotwChatCommandEnabled());
    }
}
