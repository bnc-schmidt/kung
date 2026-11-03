package com.github.beng420.kung.feature.waypoints;

import java.util.Locale;

/** How a waypoint is drawn. The serialized names match the "waypointType" field of the Skyblocker share format. */
public enum WaypointType {
    WAYPOINT("Waypoint", true, true, false),
    OUTLINED_WAYPOINT("Outlined Waypoint", true, true, true),
    HIGHLIGHT("Highlight", true, false, false),
    OUTLINED_HIGHLIGHT("Outlined Highlight", true, false, true),
    OUTLINE("Outline", false, false, true);

    private final String label;
    private final boolean fill;
    private final boolean beam;
    private final boolean outline;

    WaypointType(String label, boolean fill, boolean beam, boolean outline) {
        this.label = label;
        this.fill = fill;
        this.beam = beam;
        this.outline = outline;
    }

    public String label() { return label; }
    public boolean fill() { return fill; }
    public boolean beam() { return beam; }
    public boolean outline() { return outline; }

    public String serialName() { return name().toLowerCase(Locale.ROOT); }

    /** The type for a stored name; an unknown or missing name gives {@code fallback}. */
    public static WaypointType parse(String name, WaypointType fallback) {
        if (name == null) return fallback;
        for (WaypointType type : values()) {
            if (type.serialName().equalsIgnoreCase(name.trim())) return type;
        }
        return fallback;
    }
}
