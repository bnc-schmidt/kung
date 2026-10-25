package com.github.beng420.kung.config.category;

public final class SlayerConfig extends ConfigCategory {
    private boolean tarantulaHelperEnabled = false;
    private boolean eggSacPredictionEnabled = false;
    private boolean eggSacBoxesEnabled = false;
    private boolean eggSacCountdownEnabled = false;
    private int eggSacCountdownX = 6;
    private int eggSacCountdownY = 90;
    private int eggSacCountdownScale = 100;
    private boolean blazeAttunementEnabled = false;
    private int blazeAttunementScale = 100;
    private boolean tarantulaDebugSlayerSpawned = true;
    private boolean tarantulaDebugSlayerPosition = true;
    private boolean tarantulaDebugSlayerPhaseChange = true;
    private boolean tarantulaDebugSlayerDead = true;
    private boolean tarantulaDebugEggSacPhaseStart = true;
    private boolean tarantulaDebugEggSacPhaseDone = true;
    private boolean tarantulaDebugEggSacLearning = true;

    public boolean tarantulaHelperEnabled() { return tarantulaHelperEnabled; }
    public void setTarantulaHelperEnabled(boolean value) { tarantulaHelperEnabled = value; save(); }
    public boolean eggSacPredictionEnabled() { return eggSacPredictionEnabled; }
    public void setEggSacPredictionEnabled(boolean value) { eggSacPredictionEnabled = value; save(); }
    public boolean eggSacBoxesEnabled() { return eggSacBoxesEnabled; }
    public void setEggSacBoxesEnabled(boolean value) { eggSacBoxesEnabled = value; save(); }
    public boolean eggSacCountdownEnabled() { return eggSacCountdownEnabled; }
    public void setEggSacCountdownEnabled(boolean value) { eggSacCountdownEnabled = value; save(); }
    public int eggSacCountdownX() { return eggSacCountdownX; }
    public void setEggSacCountdownX(int value) { eggSacCountdownX = value; save(); }
    public int eggSacCountdownY() { return eggSacCountdownY; }
    public void setEggSacCountdownY(int value) { eggSacCountdownY = value; save(); }
    public int eggSacCountdownScale() { return eggSacCountdownScale; }
    public void setEggSacCountdownScale(int value) { eggSacCountdownScale = Math.clamp(value, 25, 300); save(); }
    public boolean blazeAttunementEnabled() { return blazeAttunementEnabled; }
    public void setBlazeAttunementEnabled(boolean value) { blazeAttunementEnabled = value; save(); }
    public int blazeAttunementScale() { return blazeAttunementScale; }
    public void setBlazeAttunementScale(int value) { blazeAttunementScale = Math.clamp(value, 50, 300); save(); }
    public boolean tarantulaDebugSlayerSpawned() { return tarantulaDebugSlayerSpawned; }
    public void setTarantulaDebugSlayerSpawned(boolean value) { tarantulaDebugSlayerSpawned = value; save(); }
    public boolean tarantulaDebugSlayerPosition() { return tarantulaDebugSlayerPosition; }
    public void setTarantulaDebugSlayerPosition(boolean value) { tarantulaDebugSlayerPosition = value; save(); }
    public boolean tarantulaDebugSlayerPhaseChange() { return tarantulaDebugSlayerPhaseChange; }
    public void setTarantulaDebugSlayerPhaseChange(boolean value) { tarantulaDebugSlayerPhaseChange = value; save(); }
    public boolean tarantulaDebugSlayerDead() { return tarantulaDebugSlayerDead; }
    public void setTarantulaDebugSlayerDead(boolean value) { tarantulaDebugSlayerDead = value; save(); }
    public boolean tarantulaDebugEggSacPhaseStart() { return tarantulaDebugEggSacPhaseStart; }
    public void setTarantulaDebugEggSacPhaseStart(boolean value) { tarantulaDebugEggSacPhaseStart = value; save(); }
    public boolean tarantulaDebugEggSacPhaseDone() { return tarantulaDebugEggSacPhaseDone; }
    public void setTarantulaDebugEggSacPhaseDone(boolean value) { tarantulaDebugEggSacPhaseDone = value; save(); }
    public boolean tarantulaDebugEggSacLearning() { return tarantulaDebugEggSacLearning; }
    public void setTarantulaDebugEggSacLearning(boolean value) { tarantulaDebugEggSacLearning = value; save(); }
}
