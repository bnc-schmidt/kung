package com.github.beng420.kung.skyblock;

import static org.junit.Assert.*;

import com.github.beng420.kung.skyblock.SpecialMayors.Special;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;

public class SpecialMayorsTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli(); // Jerry (election 520) is in office

    private static String answer(Special special, long now, String mayorNow, long year) {
        return SpecialMayors.answer(special, now, UTC, mayorNow, year, electionYear -> "Jerry rotation: for " + electionYear);
    }

    @Test
    public void theNextElectionOfEachSpecialMayorFollowsTheTwentyFourYearRotation() {
        assertEquals(528, SpecialMayors.nextElectionYear(Special.SCORPIUS, NOW));
        assertEquals(536, SpecialMayors.nextElectionYear(Special.DERPY, NOW));
        assertEquals(544, SpecialMayors.nextElectionYear(Special.JERRY, NOW));
        // Their terms start on Late Spring 27 of the year after the election.
        assertEquals(Instant.parse("2026-11-30T23:15:00Z").toEpochMilli(), JerryTimeline.termStartMillis(528));
        assertEquals(Instant.parse("2027-01-11T07:15:00Z").toEpochMilli(), JerryTimeline.termStartMillis(536));
        assertEquals(Instant.parse("2027-02-21T15:15:00Z").toEpochMilli(), JerryTimeline.termStartMillis(544));
        // Right after a term began, the next one is 24 years (124 days) later.
        long afterJerryStarted = Instant.parse("2026-10-20T15:15:01Z").toEpochMilli();
        assertEquals(544, SpecialMayors.nextElectionYear(Special.JERRY, afterJerryStarted));
        assertEquals(520, SpecialMayors.nextElectionYear(Special.JERRY, Instant.parse("2026-10-20T15:14:59Z").toEpochMilli()));
    }

    @Test
    public void outsideOfficeTheAnswerGivesDaysAndTheDate() {
        assertEquals("Scorpius is not in office. Next election: in 38 days (Mon 30 Nov).", answer(Special.SCORPIUS, NOW, "Jerry", 520));
        assertEquals("Derpy is not in office. Next election: in 79 days (Mon 11 Jan).", answer(Special.DERPY, NOW, "Jerry", 520));
        // Jerry himself, while a regular mayor is in office.
        long between = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();
        assertEquals("Jerry is not in office. Next election: in 111 days (Sun 21 Feb).", answer(Special.JERRY, between, "Paul", 522));
        // The date follows the zone of whoever answers.
        assertEquals("Scorpius is not in office. Next election: in 38 days (Tue 1 Dec).", SpecialMayors.answer(Special.SCORPIUS, NOW,
            ZoneId.of("Europe/Berlin"), "Jerry", 520, year -> ""));
    }

    @Test
    public void inOfficeJerryAnswersWithHisRotationAndTheOthersWithTheirEnd() {
        assertEquals("Jerry rotation: for 520", answer(Special.JERRY, NOW, "Jerry", 520));
        long during = Instant.parse("2026-12-01T12:00:00Z").toEpochMilli();
        assertEquals("Scorpius is in office and leaves in 4 days (Sun 6 Dec).", answer(Special.SCORPIUS, during, "Scorpius", 528));
        // Jerry is not in office then, and neither is Derpy.
        assertTrue(answer(Special.JERRY, during, "Scorpius", 528).startsWith("Jerry is not in office."));
        assertTrue(answer(Special.DERPY, during, "Scorpius", 528).startsWith("Derpy is not in office."));
    }

    @Test
    public void beforeTheApiAnswersTheCalendarDecidesAndWithoutAYearTheAnswerStaysShort() {
        assertEquals("Jerry rotation: for 520", answer(Special.JERRY, NOW, "", -1));
        assertTrue(answer(Special.DERPY, NOW, "", -1).startsWith("Derpy is not in office."));
        assertEquals("Derpy is in office.", answer(Special.DERPY, NOW, "Derpy", -1)); // outside any calendar term of his
        assertEquals(-1, SpecialMayors.officeYearByCalendar(Special.JERRY, Instant.parse("2026-10-20T15:14:59Z").toEpochMilli()));
        assertEquals(520, SpecialMayors.officeYearByCalendar(Special.JERRY, Instant.parse("2026-10-25T19:14:59Z").toEpochMilli()));
        assertEquals(-1, SpecialMayors.officeYearByCalendar(Special.JERRY, Instant.parse("2026-10-25T19:15:00Z").toEpochMilli()));
    }

    @Test
    public void daysReadAsDaysAndShortWaitsAsHours() {
        assertEquals("now", SpecialMayors.inDays(0));
        assertEquals("in <1h", SpecialMayors.inDays(30 * 60_000L));
        assertEquals("in 5h", SpecialMayors.inDays(5 * 3_600_000L + 59 * 60_000L));
        assertEquals("in 1 day", SpecialMayors.inDays(25 * 3_600_000L));
        assertEquals("in 2 days", SpecialMayors.inDays(48 * 3_600_000L));
    }
}
