package com.github.beng420.kung.skyblock;

import static org.junit.Assert.*;

import com.github.beng420.kung.skyblock.JerryPerkpocalypse.Reading;
import com.github.beng420.kung.skyblock.JerryPerkpocalypse.Schedule;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.Test;

public class JerryPerkpocalypseTest {
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_700_000_000_000L;

    // Lore of the head in the hub's "Mayor Jerry" menu (copied from a screenshot, Diana active).
    private static final List<String> DIANA_LORE = List.of(
        "Mayor Jerry", "Perks List", "",
        "Perkpocalypse", "Activates all perks of another mayor every 18 SkyBlock days (6 hours).", "",
        "Statspocalypse", "Increases all Combat and Miscellaneous stats by 10%.", "",
        "Jerrypocalypse", "Reveal hidden Jerries from logging, farming, mining and killing mobs.", "",
        "The listed perks are available to all players until the closing of the next elections.", "",
        "Perkpocalypse Perks:", "",
        "Huntress' Intuition", "Gain +10.0 Tracking.", "",
        "Mythological Ritual", "Minister Diana will sell the Griffin Pet.", "",
        "Pet XP Buff", "Gain 35% more pet XP.", "",
        "Sharing is Caring", "Unlocks 2 more Exp Share slots in your pets menu.", "",
        "Next set of perks in 4h 23m!");

    @Test
    public void readsTheCopiedMayorAndTheCountdown() {
        Reading reading = JerryPerkpocalypse.parse(DIANA_LORE, T0);
        assertEquals("Diana", reading.mayor());
        assertEquals(T0 + 4 * HOUR + 23 * 60_000L, reading.switchAtMillis());
    }

    @Test
    public void jerrysOwnPerksBeforeTheHeaderAreIgnored() {
        assertEquals("Paul", JerryPerkpocalypse.parse(List.of("Perkpocalypse", "Marauder",
            "Perkpocalypse Perks:", "", "EZPZ", "+10 score", "Next set of perks in 1d 2h 3m 4s!"), T0).mayor());
        assertEquals(T0 + (26 * 3600 + 3 * 60 + 4) * 1000L, JerryPerkpocalypse.parse(List.of(
            "Perkpocalypse Perks:", "EZPZ", "Next set of perks in 1d 2h 3m 4s!"), T0).switchAtMillis());
    }

    @Test
    public void anythingUnreadableGivesNothing() {
        assertNull(JerryPerkpocalypse.parse(List.of("Marauder", "Next set of perks in 1h!"), T0));
        assertNull(JerryPerkpocalypse.parse(List.of("Perkpocalypse Perks:", "Mystery Perk", "Next set of perks in 1h!"), T0));
        assertNull(JerryPerkpocalypse.parse(List.of("Perkpocalypse Perks:", "EZPZ"), T0));
        assertNull(JerryPerkpocalypse.parse(List.of("Perkpocalypse Perks:", "EZPZ", "Next set of perks in soon!"), T0));
        assertTrue(JerryPerkpocalypse.hasHeader(List.of("Perkpocalypse Perks:")));
        assertFalse(JerryPerkpocalypse.hasHeader(List.of("Perkpocalypse")));
    }

    @Test
    public void oneReadingCoversItsOwnSlotAndEveryCycleRepeat() {
        Schedule schedule = Schedule.NONE.observe(520, "Diana", T0);
        assertEquals("Diana", schedule.mayorAt(T0 + HOUR));
        assertEquals("Diana", schedule.mayorAt(T0 + 36 * HOUR + HOUR));
        assertNull(schedule.mayorAt(T0 + 6 * HOUR + HOUR));
        assertNull(schedule.mayorAt(T0 - HOUR));
    }

    @Test
    public void readingsFromAnyTimeFillTheSameCycleDespiteMinuteRounding() {
        Schedule schedule = Schedule.NONE.observe(520, "Diana", T0)
            .observe(520, "Paul", T0 + 6 * HOUR + 60_000L)
            .observe(520, "Cole", T0 + 30 * HOUR - 60_000L);
        assertEquals("Paul", schedule.mayorAt(T0 + 7 * HOUR));
        assertEquals("Cole", schedule.mayorAt(T0 + 31 * HOUR));
        assertEquals("Paul", schedule.mayorAt(T0 + 42 * HOUR + HOUR));
        assertEquals(3, schedule.known());
    }

    @Test
    public void aMayorHasOnePositionSoTheLatestReadingWins() {
        Schedule schedule = Schedule.NONE.observe(520, "Diana", T0).observe(520, "Diana", T0 + 12 * HOUR);
        assertEquals(1, schedule.known());
        assertNull(schedule.mayorAt(T0 + HOUR));
        assertEquals("Diana", schedule.mayorAt(T0 + 13 * HOUR));
    }

    @Test
    public void aNewElectionYearStartsOver() {
        Schedule schedule = Schedule.NONE.observe(520, "Diana", T0).observe(528, "Paul", T0 + 6 * HOUR);
        assertEquals(528, schedule.year());
        assertEquals(1, schedule.known());
        assertEquals("Paul", schedule.mayorAt(T0 + 7 * HOUR));
    }

    @Test
    public void everyServerSyncServiceIsOptIn() {
        var dungeon = new com.github.beng420.kung.config.category.DungeonConfig();
        assertFalse(dungeon.jerrySyncEnabled());
        assertFalse(dungeon.roomSyncEnabled());
        assertFalse(dungeon.roomSyncUploadEnabled());
    }

    @Test
    public void statusShowsTheNextSwitchAndTheCycleStartingNow() {
        Schedule schedule = Schedule.NONE.observe(520, "Diana", T0).observe(520, "Paul", T0 + 12 * HOUR);
        assertEquals(T0 + 6 * HOUR, schedule.nextSwitchMillis(T0 + HOUR));
        assertEquals(T0 + 42 * HOUR, schedule.nextSwitchMillis(T0 + 37 * HOUR));
        assertEquals(List.of("Diana", "?", "Paul", "?", "?", "?"), schedule.cycleFrom(T0 + HOUR));
        assertEquals(List.of("Paul", "?", "?", "?", "Diana", "?"), schedule.cycleFrom(T0 + 13 * HOUR));
        assertEquals(List.of("?", "?", "?", "?", "?", "?"), Schedule.NONE.cycleFrom(T0));
    }

    @Test
    public void durationsReadLikeTheMenusCountdown() {
        assertEquals("4h 23m", JerryPerkpocalypse.duration(4 * HOUR + 23 * 60_000L + 59_000L));
        assertEquals("59m", JerryPerkpocalypse.duration(59 * 60_000L));
        assertEquals("<1m", JerryPerkpocalypse.duration(30_000L));
        assertEquals("<1m", JerryPerkpocalypse.duration(-5L));
    }

    @Test
    public void theServerAnswerRoundTrips() {
        Schedule schedule = Schedule.fromJson(JsonParser.parseString("""
            {"year":520,"anchor":%d,"positions":["Diana",null,"Paul",null,null,"Cole"]}
            """.formatted(T0)).getAsJsonObject());
        assertEquals(3, schedule.known());
        assertEquals("Paul", schedule.mayorAt(T0 + 2 * 6 * HOUR + HOUR));
        assertEquals(0, Schedule.fromJson(JsonParser.parseString("{}").getAsJsonObject()).known());
        assertEquals(0, Schedule.fromJson(JsonParser.parseString("""
            {"year":520,"anchor":1,"positions":["Diana"]}
            """).getAsJsonObject()).known());
    }
}
