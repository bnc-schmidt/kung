package com.github.beng420.kung.util;

/** Hypixel's standalone non-zero tick stream, excluding pings carried inside protocol bundles. */
public final class ServerTickSequence {
    private boolean observed;
    private int lastId;

    public boolean accept(int id, boolean bundled) {
        // Bundle pings are additional synchronization traffic on Hypixel, not
        // additional game ticks. Ignore them before touching deduplication state.
        // A network delivery batch of standalone packets is NOT a protocol bundle.
        if (bundled) return false;
        // Zero is not a game tick. Do not let it replace the last real tick ID:
        // a sequence such as -10, 0, -10 must still count exactly once.
        if (id == 0) return false;
        if (observed && lastId == id) return false;
        observed = true;
        lastId = id;
        return true;
    }

    public void reset() {
        observed = false;
    }
}
