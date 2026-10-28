package com.github.beng420.kung.config.category;

public final class InvisibugConfig extends ConfigCategory {
    public static final int DEFAULT_COLOR = 0x00FFFF;
    private static final int RGB = 0xFFFFFF;

    private boolean enabled;
    private int color = DEFAULT_COLOR;
    private int thickness = 1;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    /** Opaque ARGB; the setter keeps only the RGB part. */
    public int color() { return 0xFF000000 | (color & RGB); }
    public void setColor(int value) { color = value & RGB; save(); }
    public int thickness() { return thickness; }
    public void setThickness(int value) { thickness = Math.clamp(value, 1, 6); save(); }
}
