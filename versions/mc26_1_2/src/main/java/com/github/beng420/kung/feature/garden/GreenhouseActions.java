package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseSession.Tone;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the screen's hologram and copy buttons lead to, decided without the game: the screen closes with a chat
 * message when a hologram was shown, and otherwise stays and explains why in its popup and status line. The
 * hologram itself is reached through the functions passed in, so this is unit-tested.
 */
final class GreenhouseActions {
    static final String NO_LAYOUT = "No layout to show";
    static final String NO_CODE = "No valid layout code";
    static final String NO_CODE_STATUS = "No valid layout code in clipboard";

    /**
     * Either {@code closeScreen} (and {@code chat} is the message to send as a Kung chat line) or the screen
     * stays and shows {@code notice} in its popup and {@code status} in its status line with {@code tone}.
     */
    record Outcome(boolean closeScreen, String chat, String notice, String status, Tone tone) {
        static Outcome close(String chat) { return new Outcome(true, chat, null, null, Tone.MUTED); }

        static Outcome stay(String notice, String status, Tone tone) { return new Outcome(false, null, notice, status, tone); }
    }

    private GreenhouseActions() { }

    /** "Show in world": the displayed layout, which must hold at least one cell; {@code show} is not called otherwise. */
    static Outcome showLayout(GreenhouseGrid layout, Function<GreenhouseGrid, GreenhouseHologram.ShowResult> show) {
        if (layout.filled() == 0) return Outcome.stay(NO_LAYOUT, NO_LAYOUT, Tone.WARNING);
        return Outcome.close(show.apply(layout).message());
    }

    /** "Show in world (clipboard)": {@code show} is empty when the clipboard holds no valid layout code. */
    static Outcome showClipboard(Supplier<Optional<GreenhouseHologram.ShowResult>> show) {
        return show.get().map(shown -> Outcome.close(shown.message()))
            .orElseGet(() -> Outcome.stay(NO_CODE, NO_CODE_STATUS, Tone.ERROR));
    }

    /** The warning for a code that had to drop cells, or null when all of them fit. */
    static String copyWarning(int dropped) {
        if (dropped <= 0) return null;
        return dropped + (dropped == 1 ? " cell" : " cells") + " could not be encoded (unknown crops)";
    }
}
