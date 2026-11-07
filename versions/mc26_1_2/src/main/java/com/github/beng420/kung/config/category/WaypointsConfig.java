package com.github.beng420.kung.config.category;

import com.github.beng420.kung.feature.waypoints.WaypointType;

/** The Waypoints settings. The waypoints themselves live in {@code config/kung/waypoints.json}, not here. */
public final class WaypointsConfig extends ConfigCategory {
    public static final int DEFAULT_LINE_COLOR = 0x00FF00;
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 10;
    public static final int MAX_LINE_THICKNESS = 6;
    private static final int RGB = 0xFFFFFF;

    private boolean enabled;
    private boolean autoSave;
    private String addKeybind = "";
    private WaypointType defaultType = WaypointType.WAYPOINT;
    private boolean routeLine = true;
    private int lineColor = DEFAULT_LINE_COLOR;
    private int lineThickness = 2;
    private boolean allowSkipping = true;
    private boolean allowGoingBackwards = true;
    private int activationRadius = 2;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; save(); }
    /** The draw type a new group starts with. */
    /** The key that adds a waypoint at the looked-at block to every marked group; empty when unbound. */
    public String addKeybind() { return addKeybind == null ? "" : addKeybind; }
    public void setAddKeybind(String value) { addKeybind = value == null ? "" : value; save(); }
    /** True when the editor writes every change at once, with no Done to press and nothing to discard. */
    public boolean autoSave() { return autoSave; }
    public void setAutoSave(boolean value) { autoSave = value; save(); }
    public WaypointType defaultType() { return defaultType == null ? WaypointType.WAYPOINT : defaultType; }
    public void setDefaultType(WaypointType value) { defaultType = value; save(); }
    public boolean routeLine() { return routeLine; }
    public void setRouteLine(boolean value) { routeLine = value; save(); }
    /** Opaque ARGB; the setter keeps only the RGB part. */
    public int lineColor() { return 0xFF000000 | (lineColor & RGB); }
    public void setLineColor(int value) { lineColor = value & RGB; save(); }
    public int lineThickness() { return Math.clamp(lineThickness, 1, MAX_LINE_THICKNESS); }
    public void setLineThickness(int value) { lineThickness = Math.clamp(value, 1, MAX_LINE_THICKNESS); save(); }
    public boolean allowSkipping() { return allowSkipping; }
    public void setAllowSkipping(boolean value) { allowSkipping = value; save(); }
    public boolean allowGoingBackwards() { return allowGoingBackwards; }
    public void setAllowGoingBackwards(boolean value) { allowGoingBackwards = value; save(); }
    /** Blocks from a route waypoint's centre within which it counts as reached. */
    public int activationRadius() { return Math.clamp(activationRadius, MIN_RADIUS, MAX_RADIUS); }
    public void setActivationRadius(int value) { activationRadius = Math.clamp(value, MIN_RADIUS, MAX_RADIUS); save(); }
}
