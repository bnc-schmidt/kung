package com.github.beng420.kung.config.category;

public final class GardenAlarmsConfig extends ConfigCategory {
    private boolean enabled;
    private boolean maxVisitor;
    private boolean noFfBuff;
    private boolean noSpray;
    private int volume = 70;
    private String muteKeybind = "";

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public boolean maxVisitor() { return maxVisitor; }
    public void setMaxVisitor(boolean value) { maxVisitor = value; save(); }
    public boolean noFfBuff() { return noFfBuff; }
    public void setNoFfBuff(boolean value) { noFfBuff = value; save(); }
    public boolean noSpray() { return noSpray; }
    public void setNoSpray(boolean value) { noSpray = value; save(); }
    public String muteKeybind() { return muteKeybind == null ? "" : muteKeybind; }
    public void setMuteKeybind(String value) { muteKeybind = value == null ? "" : value; save(); }
    public int volume() { return typed(volume, 1); }
    @Override public void normalize() { setVolume(volume()); }
    public void setVolume(int value) { volume = typed(value, 1); save(); }

    /** The old "6th Visitor Alarm" feature became the Max Visitor Alarm child setting of this feature. */
    public void migrateVisitorAlarm(boolean wasEnabled, Integer oldVolume) {
        enabled = wasEnabled;
        maxVisitor = wasEnabled;
        if (oldVolume != null) volume = typed(oldVolume, 1);
    }
}
