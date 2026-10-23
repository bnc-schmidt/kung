package com.github.beng420.kung.feature.misc;

import java.util.function.BiPredicate;

/**
 * Finds the hovered item without knowing which mod draws the tooltip: every tooltip any mod builds is reported,
 * and a frame whose tooltips all belong to one item (the tooltip of the hovered item) names that item. A mod that
 * builds the tooltips of many items per frame, for a search or a price overlay, leaves nothing to track.
 */
final class HoveredTooltip<T> {
    private final BiPredicate<T, T> same;
    private long frame;
    private long seenFrame = -1;
    private T item;
    private boolean ambiguous;

    HoveredTooltip(BiPredicate<T, T> same) {
        this.same = same;
    }

    /** Called before each frame of a screen is drawn. */
    void beginFrame() {
        frame++;
    }

    void observe(T observed) {
        if (seenFrame != frame) {
            seenFrame = frame;
            item = observed;
            ambiguous = false;
        } else if (!ambiguous && !same.test(item, observed)) {
            ambiguous = true;
        }
    }

    /** The item of the last frame's tooltip; null when that frame had none or tooltips of several items. */
    T hovered() {
        return seenFrame == frame && !ambiguous ? item : null;
    }
}
