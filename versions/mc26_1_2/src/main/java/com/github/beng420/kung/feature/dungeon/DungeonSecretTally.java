package com.github.beng420.kung.feature.dungeon;

import java.util.ArrayDeque;

/**
 * Counts the secrets this player found. The party total in the tab rises when anyone finds one, so a rise
 * counts as own only when an own action (chest, skull, item pickup, bat kill) happened close in time.
 * Either one can arrive first. Rises without an own action are teammates' secrets and are ignored.
 */
final class DungeonSecretTally {
    /** The tab lags the action a little; a longer window would credit a teammate's secret to a key pickup. */
    static final long WINDOW_MILLIS = 3_000L;

    private final ArrayDeque<Long> actions = new ArrayDeque<>();
    private final ArrayDeque<Long> rises = new ArrayDeque<>();
    private int total = -1;
    private int own;

    /** False until the first total reading: a late join has no baseline to count from. */
    boolean known() { return total >= 0; }

    int own() { return own; }

    /** True when this action matched a rise of the party total that was already seen. */
    boolean action(long nowMillis) {
        expire(nowMillis);
        if (rises.pollFirst() != null) {
            own++;
            return true;
        }
        actions.addLast(nowMillis);
        return false;
    }

    /** How many of the rises since the last reading this player's earlier actions claimed. */
    int total(int partyTotal, long nowMillis) {
        if (partyTotal < 0) return 0;
        expire(nowMillis);
        int claimed = 0;
        for (int found = total; total >= 0 && found < partyTotal; found++) {
            if (actions.pollFirst() != null) {
                own++;
                claimed++;
            } else {
                rises.addLast(nowMillis);
            }
        }
        total = partyTotal;
        return claimed;
    }

    void reset() {
        actions.clear();
        rises.clear();
        total = -1;
        own = 0;
    }

    private void expire(long nowMillis) {
        while (!actions.isEmpty() && nowMillis - actions.peekFirst() > WINDOW_MILLIS) actions.removeFirst();
        while (!rises.isEmpty() && nowMillis - rises.peekFirst() > WINDOW_MILLIS) rises.removeFirst();
    }
}
