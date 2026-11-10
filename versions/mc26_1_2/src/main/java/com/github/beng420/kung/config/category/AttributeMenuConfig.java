package com.github.beng420.kung.config.category;

public final class AttributeMenuConfig extends ConfigCategory {
    private boolean enabled;
    private boolean showLevel = true;
    private boolean levelOnlyBelowMax = true;
    private boolean redBackground = true;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public boolean showLevel() { return showLevel; }
    public void setShowLevel(boolean value) { showLevel = value; save(); }
    public boolean levelOnlyBelowMax() { return levelOnlyBelowMax; }
    public void setLevelOnlyBelowMax(boolean value) { levelOnlyBelowMax = value; save(); }
    public boolean redBackground() { return redBackground; }
    public void setRedBackground(boolean value) { redBackground = value; save(); }
}
