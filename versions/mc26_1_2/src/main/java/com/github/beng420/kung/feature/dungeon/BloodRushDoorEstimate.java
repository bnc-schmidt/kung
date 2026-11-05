package com.github.beng420.kung.feature.dungeon;

/** Door counts and wording without Minecraft GUI state. */
record BloodRushDoorEstimate(int count, boolean exact, int total) {
    static BloodRushDoorEstimate initial(DungeonLiveMapWriter.MatchRenderPlan plan, DungeonMapSnapshot snapshot) {
        int total = totalKnown(plan, snapshot);
        return new BloodRushDoorEstimate(total, isExact(plan, snapshot), total);
    }

    static BloodRushDoorEstimate remaining(DungeonLiveMapWriter.MatchRenderPlan plan, DungeonMapSnapshot snapshot) {
        int total = totalKnown(plan, snapshot);
        int opened = plan.openedRawNonStartSpecialDoorCells(snapshot).size();
        return new BloodRushDoorEstimate(Math.max(0, total - opened), isExact(plan, snapshot), total);
    }

    static int totalKnown(DungeonLiveMapWriter.MatchRenderPlan plan, DungeonMapSnapshot snapshot) {
        return Math.max(Math.max(plan.bloodRushTotalSpecialDoorCount(), plan.rawNonStartSpecialDoorCount(snapshot)),
            plan.knownNonStartSpecialDoorCount());
    }

    static boolean isExact(DungeonLiveMapWriter.MatchRenderPlan plan, DungeonMapSnapshot snapshot) {
        return totalKnown(plan, snapshot) > 0
            && (plan.bloodRushDoorEstimateExact(snapshot) || plan.rawNonStartSpecialDoorEstimateExact(snapshot));
    }

    boolean available() { return total > 0; }

    String initialTitle() {
        return count + (exact ? "" : "+") + (count == 1 ? " door" : " doors");
    }

    String remainingTitle(boolean bloodIsNext) {
        if (count == 1 && exact && bloodIsNext) return "Blood next";
        if (count == 1) return exact ? "Last door" : "1+ door left";
        return count + (exact ? "" : "+") + " doors left";
    }
}
