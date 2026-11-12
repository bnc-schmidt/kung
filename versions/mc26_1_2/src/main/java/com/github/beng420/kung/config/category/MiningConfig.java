package com.github.beng420.kung.config.category;

public final class MiningConfig extends ConfigCategory {
    public static final int MIN_GOURMAND_COUNTDOWN_SECONDS = 3;
    public static final int MAX_GOURMAND_COUNTDOWN_SECONDS = 30;
    public static final int DEFAULT_GOURMAND_COUNTDOWN_SECONDS = 10;
    private boolean gourmandAlertEnabled;
    private int gourmandCountdownSeconds = DEFAULT_GOURMAND_COUNTDOWN_SECONDS;
    private int gourmandVolume = 70;

    public boolean gourmandAlertEnabled() { return gourmandAlertEnabled; }
    public void setGourmandAlertEnabled(boolean value) { gourmandAlertEnabled = value; save(); }
    public int gourmandCountdownSeconds() {
        return typed(gourmandCountdownSeconds, MIN_GOURMAND_COUNTDOWN_SECONDS);
    }
    public void setGourmandCountdownSeconds(int value) {
        gourmandCountdownSeconds = typed(value, MIN_GOURMAND_COUNTDOWN_SECONDS);
        save();
    }
    public int gourmandVolume() { return typed(gourmandVolume, 1); }
    @Override
    public void normalize() {
        setGourmandCountdownSeconds(gourmandCountdownSeconds());
        setGourmandVolume(gourmandVolume());
    }
    public void setGourmandVolume(int value) { gourmandVolume = typed(value, 1); save(); }
}
