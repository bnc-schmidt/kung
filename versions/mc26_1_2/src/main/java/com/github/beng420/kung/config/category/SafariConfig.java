package com.github.beng420.kung.config.category;

public final class SafariConfig extends ConfigCategory {
    private boolean enabled;
    private int x = 12;
    private int y = 240;
    private int scale = 75;
    private boolean background = true;
    private boolean allZones;

    public boolean enabled() { return enabled; }
    public boolean background() { return background; }
    public void setBackground(boolean value) { background = value; save(); }
    public boolean allZones() { return allZones; }
    public void setAllZones(boolean value) { allZones = value; save(); }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public int x() { return x; }
    public void setX(int value) { x = value; save(); }
    public int y() { return y; }
    public void setY(int value) { y = value; save(); }
    public int scale() { return scale; }
    public void setScale(int value) { scale = Math.clamp(value, 25, 300); save(); }
}
