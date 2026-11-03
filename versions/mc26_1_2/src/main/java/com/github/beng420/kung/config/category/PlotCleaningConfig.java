package com.github.beng420.kung.config.category;

public final class PlotCleaningConfig extends ConfigCategory {
    private boolean enabled;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
}
