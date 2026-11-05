package com.github.beng420.kung.feature.garden;

/**
 * How a grid's (row, column) lands on the world, as an offset from the greenhouse corner (+X east, +Z south).
 *
 * <p>The default is north-up: {@code dx = column, dz = row}, so the planner's top edge faces north, its right edge
 * east, its bottom edge south and its left edge west, and cell (0, 0) is the greenhouse's north-west corner.
 * (Until orientation version 2 the default was {@code dx = 9 - row, dz = column}, which showed east on top;
 * {@link GreenhouseState} turns old saved data to keep its meaning.) Whether this matches a layout copied from
 * Sky Mutations is UNVERIFIED in game; the player calibrates with {@code /kung greenhouse blueprint rotate|mirror},
 * so everything here stays one small, exact transform: grid-space mirror first, then the default mapping, then
 * clockwise quarter turns (seen from above) of the 10x10 square.
 */
record GreenhouseOrientation(int rotation, boolean mirror) {
    static final GreenhouseOrientation IDENTITY = new GreenhouseOrientation(0, false);
    private static final int SIZE = GreenhouseGrid.SIZE;

    /** A position in the 10x10 square, in blocks from the corner; whole numbers are block edges. */
    record Point(double x, double z) { }

    /** A whole cell's offset from the corner. */
    record Offset(int dx, int dz) { }

    /** The compass letter ({@code N}, {@code E}, {@code S} or {@code W}) of the world direction each edge faces. */
    record Compass(String top, String right, String bottom, String left) { }

    GreenhouseOrientation {
        rotation = Math.floorMod(rotation, 4);
    }

    GreenhouseOrientation rotatedClockwise() { return new GreenhouseOrientation(rotation + 1, mirror); }

    GreenhouseOrientation mirrored() { return new GreenhouseOrientation(rotation, !mirror); }

    /**
     * Where a grid position (row and column in cell units, edges are whole numbers) lands. The grid's top-left
     * corner is {@code point(0, 0)}; the centre of cell (r, c) is {@code point(r + 0.5, c + 0.5)}.
     */
    Point point(double row, double column) {
        double x = mirror ? SIZE - column : column;
        double z = row;
        for (int turn = 0; turn < rotation; turn++) {
            double turned = SIZE - z;
            z = x;
            x = turned;
        }
        return new Point(x, z);
    }

    Offset offset(int row, int column) {
        Point centre = point(row + 0.5, column + 0.5);
        return new Offset((int) Math.floor(centre.x()), (int) Math.floor(centre.z()));
    }

    /** The letters around the planner grid; they follow the mapping the hologram uses, so the two agree. */
    Compass compass() {
        double middle = SIZE / 2.0;
        Point centre = point(middle, middle);
        return new Compass(direction(point(0, middle), centre), direction(point(middle, SIZE), centre),
            direction(point(SIZE, middle), centre), direction(point(middle, 0), centre));
    }

    /** The world direction from the grid's centre to the middle of an edge: +X is east, +Z is south. */
    private static String direction(Point edge, Point centre) {
        double dx = edge.x() - centre.x();
        double dz = edge.z() - centre.z();
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? "E" : "W";
        return dz > 0 ? "S" : "N";
    }

    /** For chat: the state the player is calibrating. */
    String describe() {
        return "rotated " + rotation * 90 + " degrees clockwise" + (mirror ? ", mirrored" : ", not mirrored");
    }
}
