package com.github.beng420.kung.skyblock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * The three special mayors win the election of every 8th SkyBlock year in a fixed order, so each comes back every 24
 * years: Scorpius (504, 528, ...), Derpy (512, 536, ...), Jerry (520, 544, ...), per the wiki's timetable. An election
 * closes when the winner takes office (Late Spring 27 of the year after), see {@link JerryTimeline}.
 */
public final class SpecialMayors {
    private static final int PERIOD_YEARS = 24;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private SpecialMayors() { }

    public enum Special {
        SCORPIUS("Scorpius", 504), DERPY("Derpy", 512), JERRY("Jerry", 520);

        private final String mayor;
        private final long firstElectionYear;

        Special(String mayor, long firstElectionYear) {
            this.mayor = mayor;
            this.firstElectionYear = firstElectionYear;
        }

        public String mayor() { return mayor; }
    }

    /** The reply to !jerry, !scorp or !derpy: who is in office, or when the next election closes. */
    public static String answer(Special special, long nowMillis, ZoneId zone) {
        SkyBlockMayorTracker tracker = SkyBlockMayorTracker.INSTANCE;
        return answer(special, nowMillis, zone, tracker.mayorName(), tracker.electionYear(),
            year -> JerryPerkpocalypse.INSTANCE.rotationText(year, nowMillis));
    }

    /** {@code mayorNow} and {@code electionYear} are what the election API says; blank/negative before it answered. */
    static String answer(Special special, long nowMillis, ZoneId zone, String mayorNow, long electionYear,
                         LongFunction<String> jerryRotation) {
        // The API is authoritative; the calendar only stands in while it has not answered yet.
        long officeYear = mayorNow.isBlank() || special.mayor.equalsIgnoreCase(mayorNow)
            ? (!mayorNow.isBlank() && electionYear >= 0 ? electionYear : officeYearByCalendar(special, nowMillis)) : -1;
        if (officeYear >= 0) {
            if (special == Special.JERRY) return jerryRotation.apply(officeYear);
            long end = JerryTimeline.termEndMillis(officeYear);
            return special.mayor + " is in office and leaves " + inDays(end - nowMillis) + " (" + date(end, zone) + ").";
        }
        if (!mayorNow.isBlank() && special.mayor.equalsIgnoreCase(mayorNow)) return special.mayor + " is in office.";
        long start = JerryTimeline.termStartMillis(nextElectionYear(special, nowMillis));
        return special.mayor + " is not in office. Next election: " + inDays(start - nowMillis) + " (" + date(start, zone) + ").";
    }

    /** The election year whose term starts next after {@code nowMillis}. */
    static long nextElectionYear(Special special, long nowMillis) {
        long year = special.firstElectionYear;
        while (JerryTimeline.termStartMillis(year) <= nowMillis) year += PERIOD_YEARS;
        return year;
    }

    /** The election year of the term of this mayor that is running at {@code nowMillis}, or -1. */
    static long officeYearByCalendar(Special special, long nowMillis) {
        for (long year = special.firstElectionYear; JerryTimeline.termStartMillis(year) <= nowMillis; year += PERIOD_YEARS) {
            if (nowMillis < JerryTimeline.termEndMillis(year)) return year;
        }
        return -1;
    }

    /** "in 34 days", "in 1 day", "in 5h" (under a day), "now". */
    static String inDays(long millis) {
        if (millis <= 0) return "now";
        long days = millis / 86_400_000L;
        if (days >= 1) return "in " + days + (days == 1 ? " day" : " days");
        long hours = millis / 3_600_000L;
        return hours >= 1 ? "in " + hours + "h" : "in <1h";
    }

    static String date(long millis, ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(millis).atZone(zone));
    }
}
