package com.github.beng420.kung.config.category;

public final class BRHelperConfig extends ConfigCategory {
    private boolean enabled = false;
    private int titleDurationTenths = 5;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public int titleDurationTenths() { return titleDurationTenths; }
    @Override public void normalize() { setTitleDurationTenths(titleDurationTenths()); }
    public void setTitleDurationTenths(int value) { titleDurationTenths = typed(value, 1); save(); }
}
