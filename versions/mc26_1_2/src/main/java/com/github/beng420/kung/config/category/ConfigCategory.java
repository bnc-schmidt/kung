package com.github.beng420.kung.config.category;

public abstract class ConfigCategory {
    private transient Runnable onChange;

    public final void onChange(Runnable onChange) {
        this.onChange = onChange;
    }

    protected final void save() {
        if (onChange != null) onChange.run();
    }

    /**
     * The largest number a slider's value box takes. The slider keeps its own, smaller range; the box may go
     * below and above it, down to {@code floor}, the least a setting can do without breaking.
     */
    public static final int TYPED_MAX = 1_000_000;

    protected static int typed(int value, int floor) { return Math.clamp(value, floor, TYPED_MAX); }

    /** Puts values read from disk back into range; the default has nothing to fix. */
    public void normalize() { }
}
