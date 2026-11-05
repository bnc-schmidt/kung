package com.github.beng420.kung.feature.dungeon;

public enum DungeonDoorKind {
    NONE,
    OPEN,
    WITHER,
    BLOOD;

    public boolean visible() {
        return this != NONE;
    }

    /** A Wither or Blood door: shut until its key is used. */
    public boolean locked() {
        return this == WITHER || this == BLOOD;
    }
}
