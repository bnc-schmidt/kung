package com.github.beng420.kung.config.category;

public abstract class ConfigCategory {
    private transient Runnable onChange;

    public final void onChange(Runnable onChange) {
        this.onChange = onChange;
    }

    protected final void save() {
        if (onChange != null) onChange.run();
    }

    /** Puts values read from disk back into range; the default has nothing to fix. */
    public void normalize() { }
}
