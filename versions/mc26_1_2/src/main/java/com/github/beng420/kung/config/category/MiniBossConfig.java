package com.github.beng420.kung.config.category;

public final class MiniBossConfig extends ConfigCategory {
    private boolean enabled;
    private int x = 6;
    private int y = 100;
    private int scale = 100;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public int x() { return x; }
    public void setX(int value) { x = value; save(); }
    public int y() { return y; }
    public void setY(int value) { y = value; save(); }
    public int scale() { return scale; }
    public void setScale(int value) { scale = Math.clamp(value, 25, 300); save(); }
    @Override public void normalize() { setScale(scale()); }
}
