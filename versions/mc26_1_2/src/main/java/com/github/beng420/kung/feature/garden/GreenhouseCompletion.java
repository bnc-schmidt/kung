package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Extra;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Verdict;
import java.util.List;

/**
 * Whether every ingredient of the layout stands right, from completed scans: the layout counts as complete after two
 * consecutive scans agree that all its ingredient cells (the mutation spots are not ingredients) are CORRECT, and as
 * incomplete again after two consecutive scans that disagree. When it is complete and the setting asks for it,
 * {@link #update} says so once; the caller then deactivates the hologram (which resets this state), so nothing
 * lingers and nothing re-arms it.
 */
final class GreenhouseCompletion {
    /** The one chat line sent when a finished layout turns the hologram off. */
    static final String MESSAGE = "Greenhouse layout complete: all crops are placed correctly. Hologram deactivated"
        + " (use Show in world in /kung greenhouse to show it again).";
    /** Consecutive scans that must agree before the answer changes. */
    static final int AGREE = 2;

    private boolean complete;
    private boolean candidate;
    private int streak;

    /**
     * Whether the layout is complete in one scan: it has at least one ingredient cell, all of them are CORRECT and no
     * recognised crop stands where the layout has none ({@code extras}, the scan's raw ones: the pink unplanned
     * markers). Soil markers do not count. {@code raw} is the scan's verdicts aligned with the placements.
     */
    static boolean allIngredientsCorrect(List<Placement> placements, List<Verdict> raw, List<Extra> extras) {
        if (placements.isEmpty() || raw.size() != placements.size() || !extras.isEmpty()) return false;
        int ingredients = 0;
        for (int i = 0; i < placements.size(); i++) {
            if (placements.get(i).mutation()) continue;
            ingredients++;
            if (raw.get(i).state() != State.CORRECT) return false;
        }
        return ingredients > 0;
    }

    /**
     * One completed scan. True when the layout is complete after it and {@code enabled} (the setting) is on: the
     * caller must deactivate the hologram, which {@link #reset resets} this state, so it is true once per completion.
     */
    boolean update(boolean allCorrect, boolean enabled) {
        if (allCorrect == candidate) {
            streak++;
        } else {
            candidate = allCorrect;
            streak = 1;
        }
        if (streak >= AGREE) complete = candidate;
        return complete && enabled;
    }

    /** Whether the last agreeing scans found every ingredient right. */
    boolean complete() { return complete; }

    /** A new hologram or corner, or the hologram was deactivated: nothing is known. */
    void reset() {
        complete = false;
        candidate = false;
        streak = 0;
    }

    /** Test seam: the state a scan sequence would have produced. */
    void setForTest(boolean value) {
        reset();
        complete = value;
        candidate = value;
        streak = AGREE;
    }
}
