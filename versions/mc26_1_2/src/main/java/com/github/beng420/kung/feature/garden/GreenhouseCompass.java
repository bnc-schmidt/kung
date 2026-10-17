package com.github.beng420.kung.feature.garden;

/**
 * The compass letters around the planner's grid, taken from {@link GreenhouseOrientation} so they say exactly
 * what the hologram will do: each edge of the grid is labelled with the world direction ({@code N}, {@code E},
 * {@code S} or {@code W}) it faces: north-up by default. The compass and the hologram stay consistent because both
 * read the same orientation.
 */
final class GreenhouseCompass {
    /** Index of each edge in {@link #labels}. */
    static final int TOP = 0;
    static final int RIGHT = 1;
    static final int BOTTOM = 2;
    static final int LEFT = 3;

    private GreenhouseCompass() { }

    /** The letters for the top, right, bottom and left edge of the shown grid. */
    static String[] labels(GreenhouseOrientation orientation) {
        double middle = GreenhouseGrid.SIZE / 2.0;
        double end = GreenhouseGrid.SIZE;
        var centre = orientation.point(middle, middle);
        return new String[] {
            direction(orientation.point(0, middle), centre),
            direction(orientation.point(middle, end), centre),
            direction(orientation.point(end, middle), centre),
            direction(orientation.point(middle, 0), centre),
        };
    }

    /** The world direction from the grid's centre to the middle of an edge: +X is east, +Z is south. */
    private static String direction(GreenhouseOrientation.Point edge, GreenhouseOrientation.Point centre) {
        double dx = edge.x() - centre.x();
        double dz = edge.z() - centre.z();
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? "E" : "W";
        return dz > 0 ? "S" : "N";
    }
}
