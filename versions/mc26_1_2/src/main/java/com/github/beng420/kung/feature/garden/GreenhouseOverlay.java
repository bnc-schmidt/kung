package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Layout;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Shape;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.Extra;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import java.util.ArrayList;
import java.util.List;

/**
 * The boxes of the hologram that are lines and fills rather than models, coloured after the settings and the plot
 * scan, the way Litematica marks a schematic: blue outline around what is still to place (a mutation spot also gets a
 * thin blue slab), red box over a wrong crop, pink box over a crop where the layout has none, a neutral outline over
 * something unrecognised, nothing where the right crop stands; and on the soil layer a blue outline where a block is
 * missing, a red box where another block is, an orange box where farmland and dirt are swapped. Pure arithmetic:
 * corner-relative like {@link GreenhouseHologramShapes} (y above the hologram floor, so the soil layer is below 0).
 */
final class GreenhouseOverlay {
    /** The soil boxes are a hair larger than the block so they cover its faces without flicker. */
    private static final double SOIL_GROW = 0.004;

    private GreenhouseOverlay() { }

    /**
     * Everything except the models, the cube stand-ins and the fixed (0, 0) marker.
     *
     * @param states     per placement of the layout, or null when no scan matches it (then: all still to place)
     * @param extras     crops seen where the layout has no entry
     * @param ghosts     soil cells that differ from what is expected
     * @param soilLayer  the y of the soil layer, or -1 when none was found (then no soil boxes)
     */
    static List<Shape> boxes(Layout layout, List<State> states, List<Extra> extras, List<Ghost> ghosts, int soilLayer,
                             GreenhousePalette palette) {
        List<Shape> out = new ArrayList<>();
        List<Placement> placements = layout.placements();
        for (int i = 0; i < placements.size(); i++) {
            Placement placement = placements.get(i);
            State state = states == null || i >= states.size() ? State.EMPTY : states.get(i);
            switch (state) {
                case CORRECT -> { }
                case WRONG -> out.add(placement.area().recolored(palette.wrongFill(), palette.wrongOutline(), 3.0F));
                case UNVERIFIED -> out.add(placement.area().recolored(GreenhousePalette.none(),
                    palette.unverifiedOutline(), 2.5F));
                case EMPTY -> {
                    out.add(placement.area().recolored(GreenhousePalette.none(), palette.placeOutline(), 2.0F));
                    if (placement.slab() != null) {
                        out.add(placement.slab().recolored(palette.placeFill(), GreenhousePalette.none(), 0F));
                    }
                }
            }
        }
        for (Extra extra : extras) {
            out.add(GreenhouseHologramShapes.area(extra.dx(), extra.dz(), extra.dx() + 1, extra.dz() + 1)
                .recolored(palette.extraFill(), palette.extraOutline(), 3.0F));
        }
        if (soilLayer >= 0) {
            double y = soilLayer - GreenhouseLocator.HOLOGRAM_Y;
            for (Ghost ghost : ghosts) out.add(soilBox(ghost, y, palette));
        }
        return List.copyOf(out);
    }

    /** The coloured stand-in for a crop no model can be drawn for: its hue moved toward the "to place" colour. */
    static Shape cube(Placement placement, GreenhousePalette palette) {
        Shape cube = placement.cube();
        if (cube == null) return null;
        return cube.recolored(palette.fill(palette.shifted(cube.outline() & 0xFFFFFF), 1F), GreenhousePalette.none(), 0F);
    }

    private static Shape soilBox(Ghost ghost, double y, GreenhousePalette palette) {
        int fill;
        int outline;
        float width;
        switch (ghost.kind()) {
            case WRONG_BLOCK -> {
                fill = palette.wrongFill();
                outline = palette.wrongOutline();
                width = 3.0F;
            }
            case WRONG_STATE -> {
                fill = palette.wrongStateFill();
                outline = palette.wrongStateOutline();
                width = 3.0F;
            }
            default -> {
                // Missing: the translucent block itself is drawn as a model; this is its outline.
                fill = GreenhousePalette.none();
                outline = palette.placeOutline();
                width = 2.0F;
            }
        }
        return new Shape(ghost.dx() - SOIL_GROW, y - SOIL_GROW, ghost.dz() - SOIL_GROW, ghost.dx() + 1 + SOIL_GROW,
            y + 1 + SOIL_GROW, ghost.dz() + 1 + SOIL_GROW, fill, outline, width);
    }
}
