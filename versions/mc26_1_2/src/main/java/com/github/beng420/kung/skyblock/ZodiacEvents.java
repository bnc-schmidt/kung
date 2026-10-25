package com.github.beng420.kung.skyblock;

import java.time.ZoneId;

/**
 * The SkyBlock zodiac events (Year of the Pig, Seal and Witch): each one runs for a whole SkyBlock year, 124 real
 * hours from the start of the year, and comes back every 12 years. The Seal's start was read in game (Sat 24 Oct 2026,
 * the start of SkyBlock year 522); Pig and Witch are assumed to run in the year before and after it.
 */
public final class ZodiacEvents {
    private static final long CYCLE_YEARS = 12;

    private ZodiacEvents() { }

    /** {@code yearInCycle} is the SkyBlock year modulo 12 in which the event runs. */
    public enum Event {
        PIG("Year of the Pig", 5), SEAL("Year of the Seal", 6), WITCH("Year of the Witch", 7);

        private final String title;
        private final long yearInCycle;

        Event(String title, long yearInCycle) {
            this.title = title;
            this.yearInCycle = yearInCycle;
        }

        public String title() { return title; }
    }

    /** The SkyBlock year that is running at {@code nowMillis}. */
    static long currentYear(long nowMillis) {
        return Math.floorDiv(nowMillis - JerryTimeline.yearStartMillis(1), JerryTimeline.SB_YEAR_MILLIS) + 1;
    }

    /** The first year of {@code event} that has not ended at {@code nowMillis}: the running one, or the next. */
    static long yearOnOrAfter(Event event, long nowMillis) {
        long year = currentYear(nowMillis);
        return year + Math.floorMod(event.yearInCycle - year, CYCLE_YEARS);
    }

    /** The reply to !yotp, !yotw or !yots: when it ends while it runs, when it starts otherwise. */
    public static String answer(Event event, long nowMillis, ZoneId zone) {
        long year = yearOnOrAfter(event, nowMillis);
        long start = JerryTimeline.yearStartMillis(year);
        long end = start + JerryTimeline.SB_YEAR_MILLIS;
        if (start <= nowMillis) {
            return event.title + " is active and ends " + SpecialMayors.inDays(end - nowMillis) + " (" + SpecialMayors.date(end, zone) + ").";
        }
        return event.title + " is not active. It starts " + SpecialMayors.inDays(start - nowMillis) + " (" + SpecialMayors.date(start, zone) + ").";
    }
}
