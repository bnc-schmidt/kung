package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.ui.UiNumberField;

/**
 * The text being typed into one number cell. The screen draws numbers itself (an EditBox per cell would cost
 * several draw calls each), so this holds just the digits. A fresh edit starts selected: the first digit or
 * backspace replaces the whole value, as in a focused text field with everything selected.
 */
final class GreenhouseNumberEdit {
    /** Enough for 100000 and a typo; longer input is ignored. */
    private static final int MAX_DIGITS = 6;
    private final UiNumberField range;
    private final StringBuilder text;
    private boolean fresh = true;

    GreenhouseNumberEdit(int value, UiNumberField range) {
        this.range = range;
        this.text = new StringBuilder(Integer.toString(range.clamp(value)));
    }

    String text() { return text.toString(); }

    void type(int codepoint) {
        if (codepoint < '0' || codepoint > '9') return;
        if (fresh) text.setLength(0);
        fresh = false;
        if (text.length() < MAX_DIGITS) text.append((char) codepoint);
    }

    void backspace() {
        if (fresh) text.setLength(0);
        else if (!text.isEmpty()) text.setLength(text.length() - 1);
        fresh = false;
    }

    /** The typed number clamped into range; an empty field keeps {@code fallback}. */
    int commit(int fallback) {
        if (text.isEmpty()) return range.clamp(fallback);
        return range.clamp(Integer.parseInt(text.toString()));
    }
}
