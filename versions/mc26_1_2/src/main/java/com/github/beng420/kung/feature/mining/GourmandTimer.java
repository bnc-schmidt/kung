package com.github.beng420.kung.feature.mining;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When Don Expresso leaves the Mithril Gourmand event, and which alert is due now. Times are millis from any
 * steady clock; the class knows nothing of Minecraft, so a test can drive it with plain numbers.
 */
final class GourmandTimer {
    /**
     * The wiki says the event lasts 10 minutes. No chat line marks the end of that timer (only the 20 s warning),
     * so the one-minute warning of an event nobody finishes early counts from the STARTED line.
     * One SkyHanni chat log (2026-10-20) has STARTED at 19:07:48 and the ENDED banner at 19:17:57, 10:09 later.
     * ponytail: the 10 minutes stay (a warning 9 s early beats one 9 s late); the 20 s line corrects a wrong length.
     */
    static final long EVENT_MILLIS = 10 * 60_000L;
    static final long WARNING_MILLIS = 60_000L;
    /**
     * A leading non-letter run (the arrow of an event banner) is fine; a player's "[MVP+] Name: ..." is not, so
     * nobody can fake the lines in public chat.
     */
    private static final Pattern STARTED = Pattern.compile("^[^A-Za-z]*MITHRIL GOURMAND STARTED!");
    private static final Pattern ENDED = Pattern.compile("^[^A-Za-z]*MITHRIL GOURMAND ENDED!");
    private static final Pattern LEAVING =
        Pattern.compile("^[^A-Za-z]*Don Expresso is leaving in (\\d{1,4}) (minute|second)s?!");

    enum Event { NONE, STARTED, LEAVING, ENDED }

    enum Kind { NONE, WARNING, COUNT }

    /**
     * What to show now. {@code seconds} is the time Don has left, rounded up; {@code link} marks the alerts that also
     * bring the teleport link in chat: the warning and the first second of the countdown.
     */
    record Alert(Kind kind, int seconds, boolean link) {
        static final Alert NONE = new Alert(Kind.NONE, 0, false);
    }

    private boolean active;
    private long deadline;
    private boolean warned;
    private int lastCount = Integer.MAX_VALUE;

    boolean active() { return active; }

    void reset() {
        active = false;
        warned = false;
        lastCount = Integer.MAX_VALUE;
    }

    /** Reads one chat line; anything but {@link Event#NONE} changed the timer. */
    Event observe(String line, long now) {
        String text = line == null ? "" : line.strip();
        if (STARTED.matcher(text).find()) {
            reset();
            active = true;
            deadline = now + EVENT_MILLIS;
            return Event.STARTED;
        }
        if (ENDED.matcher(text).find()) {
            reset();
            return Event.ENDED;
        }
        Matcher leaving = LEAVING.matcher(text);
        if (leaving.find()) {
            long amount = Long.parseLong(leaving.group(1));
            active = true;
            deadline = now + amount * (leaving.group(2).equals("minute") ? 60_000L : 1_000L);
            // A shifted deadline may count a second again; the warning stays shown once.
            lastCount = Integer.MAX_VALUE;
            return Event.LEAVING;
        }
        return Event.NONE;
    }

    /** The alert due at {@code now}: the warning once, then each countdown second once. */
    Alert tick(long now, boolean hasMithril, int countdownSeconds) {
        if (!active) return Alert.NONE;
        long remaining = deadline - now;
        if (remaining <= 0) {
            reset();
            return Alert.NONE;
        }
        if (!hasMithril || remaining > WARNING_MILLIS) return Alert.NONE;
        int seconds = (int) Math.ceilDiv(remaining, 1_000L);
        if (seconds <= countdownSeconds) {
            if (seconds >= lastCount) return Alert.NONE;
            boolean link = lastCount == Integer.MAX_VALUE;
            lastCount = seconds;
            warned = true; // the countdown replaces the warning
            return new Alert(Kind.COUNT, seconds, link);
        }
        if (warned) return Alert.NONE;
        warned = true;
        return new Alert(Kind.WARNING, seconds, true);
    }
}
