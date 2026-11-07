package com.github.beng420.kung.feature.waypoints;

import java.util.Locale;

/**
 * What a waypoint group shows through blocks, when its Walls choice is on. {@link #ICON} is the small square at the
 * projected block, drawn on top of the normal waypoint (the box, outline and beam of its type, which blocks hide).
 * {@link #WAYPOINT} draws that normal waypoint itself through blocks, in its type's look, and no icon.
 */
public enum WaypointWallStyle {
    ICON("Icon"),
    WAYPOINT("Waypoint");

    private final String label;

    WaypointWallStyle(String label) {
        this.label = label;
    }

    public String label() { return label; }

    public String serialName() { return name().toLowerCase(Locale.ROOT); }

    /** True when the through-walls draw of a group of this type has a filled box. */
    public boolean fill(WaypointType type) {
        return this == WAYPOINT && type.fill();
    }

    /** True when the through-walls draw of a group of this type has box edges. */
    public boolean outline(WaypointType type) {
        return this == WAYPOINT && type.outline();
    }

    public WaypointWallStyle next() {
        WaypointWallStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** The style for a stored name; an unknown or missing name gives {@link #ICON}. */
    public static WaypointWallStyle parse(String name) {
        if (name != null) {
            for (WaypointWallStyle style : values()) {
                if (style.serialName().equalsIgnoreCase(name.trim())) return style;
            }
        }
        return ICON;
    }
}
