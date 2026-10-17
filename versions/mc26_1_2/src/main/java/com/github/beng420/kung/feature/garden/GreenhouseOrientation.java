package com.github.beng420.kung.feature.garden;

/**
 * How a grid's (row, column) lands on the world, as an offset from the greenhouse corner (+X east, +Z south).
 *
 * <p>UNVERIFIED in game: the default, {@code dx = 9 - row, dz = column}, is the one Skyblocker uses for Sky
 * Mutations layouts and is believed (not confirmed) to put the planner's top-left cell at the greenhouse's
 * north-east end. The player calibrates with {@code /kung greenhouse blueprint rotate|mirror}, so everything
 * here stays one small, exact transform: grid-space mirror first, then the default mapping, then clockwise
 * quarter turns (seen from above) of the 10x10 square.
 */
record GreenhouseOrientation(int rotation, boolean mirror) {
    static final GreenhouseOrientation IDENTITY = new GreenhouseOrientation(0, false);
    private static final int SIZE = GreenhouseGrid.SIZE;

    /** A position in the 10x10 square, in blocks from the corner; whole numbers are block edges. */
    record Point(double x, double z) { }

    /** A whole cell's offset from the corner. */
    record Offset(int dx, int dz) { }

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
        double u = mirror ? SIZE - column : column;
        double x = SIZE - row;
        double z = u;
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

    /** For chat: the state the player is calibrating. */
    String describe() {
        return "rotated " + rotation * 90 + " degrees clockwise" + (mirror ? ", mirrored" : ", not mirrored");
    }
}
