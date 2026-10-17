package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Extra;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Verdict;
import java.util.List;

/**
 * Whether every ingredient of the layout stands right, from completed scans: the layout counts as complete after two
 * consecutive scans agree that all its ingredient cells (the mutation spots are not ingredients) are CORRECT, and as
 * incomplete again after two consecutive scans that disagree. Completion is announced once, when it begins (and only
 * while the announcing is allowed); harvesting a crop ends it and arms the next announcement.
 *
 * <p>It also holds the player's override of the hiding ({@link #forced()}), because that lasts exactly as long as
 * the completion it overrides.
 */
final class GreenhouseCompletion {
    /** Consecutive scans that must agree before the answer changes. */
    static final int AGREE = 2;

    private boolean complete;
    private boolean announced;
    private boolean candidate;
    private boolean forced;
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
     * One completed scan. Returns true exactly once per completion, when the layout has just become complete (or
     * the announcing was allowed again while it is), and {@code announce} is true.
     */
    boolean update(boolean allCorrect, boolean announce) {
        if (allCorrect == candidate) {
            streak++;
        } else {
            candidate = allCorrect;
            streak = 1;
        }
        if (streak >= AGREE && candidate != complete) {
            complete = candidate;
            if (!complete) {
                announced = false;
                forced = false;
            }
        }
        if (complete && announce && !announced) {
            announced = true;
            return true;
        }
        return false;
    }

    /** Whether the last agreeing scans found every ingredient right. */
    boolean complete() { return complete; }

    /**
     * Whether the player showed the hologram with the toggle key although the layout is complete. Ends with the
     * completion (two scans that disagree) and with a reset; the toggle key sets and clears it.
     */
    boolean forced() { return forced; }

    void setForced(boolean value) { forced = value; }

    /** A new hologram or corner: nothing is known. */
    void reset() {
        complete = false;
        announced = false;
        candidate = false;
        forced = false;
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
