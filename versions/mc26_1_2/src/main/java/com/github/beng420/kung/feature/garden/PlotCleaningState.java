package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.PlotCleaningHelperFeature.Plot;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which garden plots are being cleaned in the current Garden instance: a plot counts once Hypixel shows a
 * cleaning progress below 100% for it, and stops counting when Hypixel announces it clean. A plot that never
 * showed a progress is never cleanable, so a plot that is already clean keeps its grass and flowers. Plain Java,
 * so the rules are unit-tested without a client.
 */
final class PlotCleaningState {
    private static final Pattern CLEAN_MESSAGE = Pattern.compile("Plot - .+ is now clean!");
    // Both orders, since the display line is not known: "Cleaning: 45%" and "45% cleaned".
    private static final Pattern PERCENT_AFTER_WORD = Pattern.compile("(?i)clean\\w*\\D{0,30}?(\\d{1,3}(?:[.,]\\d+)?)\\s*%");
    private static final Pattern PERCENT_BEFORE_WORD = Pattern.compile("(?i)(\\d{1,3}(?:[.,]\\d+)?)\\s*%\\W{0,5}(?:is\\s+)?clean");

    private final Set<Plot> cleanable = new HashSet<>();
    private final Set<Plot> announced = new HashSet<>();
    private long epoch = Long.MIN_VALUE;

    /** The percentage a line shows next to the word "clean", or NaN when it shows none. */
    static double percent(String line) {
        Matcher match = PERCENT_AFTER_WORD.matcher(line);
        if (!match.find()) {
            match = PERCENT_BEFORE_WORD.matcher(line);
            if (!match.find()) return Double.NaN;
        }
        return Double.parseDouble(match.group(1).replace(',', '.'));
    }

    /** Hypixel's chat line when a plot is finished. */
    static boolean cleanMessage(String line) { return CLEAN_MESSAGE.matcher(line).matches(); }

    /** A new Garden instance forgets everything; call before any other method. */
    void sync(long epoch) {
        if (epoch == this.epoch) return;
        this.epoch = epoch;
        clear();
    }

    void clear() {
        cleanable.clear();
        announced.clear();
    }

    /** The progress Hypixel shows for the plot the player stands in. */
    void progress(Plot plot, double percent) {
        if (announced.contains(plot)) return;
        if (percent < 100) cleanable.add(plot);
        else cleanable.remove(plot);
    }

    /** Hypixel announced the plot clean, whatever a stale progress line says afterwards. */
    void announcedClean(Plot plot) {
        cleanable.remove(plot);
        announced.add(plot);
    }

    boolean cleaning(Plot plot) { return cleanable.contains(plot); }
}
