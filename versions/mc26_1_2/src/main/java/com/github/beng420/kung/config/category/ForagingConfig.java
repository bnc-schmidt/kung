package com.github.beng420.kung.config.category;

public final class ForagingConfig extends ConfigCategory {
    private boolean beaconSolverEnabled;

    public boolean beaconSolverEnabled() { return beaconSolverEnabled; }
    public void setBeaconSolverEnabled(boolean value) { beaconSolverEnabled = value; save(); }
}
