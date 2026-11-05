package com.github.beng420.kung.config.category;

public final class FeastConfig extends ConfigCategory {
    public static final int DEFAULT_KERNEL_TIMEOUT_SECONDS = 60;
    public static final int MIN_KERNEL_TIMEOUT_SECONDS = 10;
    public static final int MAX_KERNEL_TIMEOUT_SECONDS = 300;
    private boolean enabled;
    private boolean showInHubFarm = true;
    private int kernelTimeoutSeconds = DEFAULT_KERNEL_TIMEOUT_SECONDS;
    private int x = 12;
    private int y = 190;
    private int scale = 100;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    public boolean showInHubFarm() { return showInHubFarm; }
    public void setShowInHubFarm(boolean value) { showInHubFarm = value; save(); }
    public int kernelTimeoutSeconds() { return kernelTimeoutSeconds; }
    public void setKernelTimeoutSeconds(int value) {
        kernelTimeoutSeconds = Math.clamp(value, MIN_KERNEL_TIMEOUT_SECONDS, MAX_KERNEL_TIMEOUT_SECONDS);
        save();
    }
    public void setKernelTimeoutText(String value) {
        if (value == null) return;
        try { setKernelTimeoutSeconds(Integer.parseInt(value.strip())); }
        catch (NumberFormatException ignored) { /* Invalid input retains the saved timeout. */ }
    }
    public int x() { return x; }
    public void setX(int value) { x = value; save(); }
    public int y() { return y; }
    public void setY(int value) { y = value; save(); }
    public int scale() { return scale; }
    @Override
    public void normalize() {
        setScale(scale());
        setKernelTimeoutSeconds(kernelTimeoutSeconds());
    }
    public void setScale(int value) { scale = Math.clamp(value, 25, 300); save(); }
}
