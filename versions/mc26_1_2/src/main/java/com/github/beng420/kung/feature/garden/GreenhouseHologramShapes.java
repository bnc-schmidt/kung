package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.feature.garden.GreenhouseCropLook.Look;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * Everything the hologram shows, relative to the greenhouse corner: pure arithmetic over a grid and an
 * orientation, built once when either changes. x and z are blocks from the corner, y is blocks above the
 * hologram floor. Model placements say WHAT to draw where; drawing them is {@link GreenhouseModels}.
 */
final class GreenhouseHologramShapes {
    /**
     * One box with its fill and outline (ARGB; alpha 0 means none). The colours of what {@link Placement} carries are
     * only defaults: the hologram colours itself from the player's settings (see {@link GreenhouseOverlay}).
     */
    record Shape(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int fill, int outline,
                 float width) {
        /** The same box in other colours. */
        Shape recolored(int newFill, int newOutline, float newWidth) {
            return new Shape(minX, minY, minZ, maxX, maxY, maxZ, newFill, newOutline, newWidth);
        }
    }

    /**
     * A model to draw: a crop's head or block, or a mutation's head over its footprint. {@code x}/{@code z} are
     * the centre, {@code y} the base, {@code scale} multiplies the 0.5-block head cube. {@code cube} is the
     * coloured box drawn when no model can be (null for a mutation: its slab already marks the spot).
     * {@code slab} is the thin marker of a mutation's empty spot (null for a crop). {@code area} is the volume of the
     * whole cell or footprint, which is outlined or filled in the colour of its state.
     */
    record Placement(String crop, boolean mutation, double x, double y, double z, double scale, Look look,
                     Shape cube, Shape slab, Shape area) { }

    /**
     * One filled grid cell's whole-block volume, for ray tests: the cell at (row, column) of the grid as read,
     * moved by the orientation; y 0..1 above the hologram floor. Corner-relative like everything here.
     * {@code visible}: the cell currently shows something (its model, a red or orange box, a mutation's slab);
     * false where the right plant already stands, so a pick-block ray should pass through to the real world.
     */
    record CellBox(int row, int column, String crop, int flag, double minX, double minY, double minZ, double maxX,
                   double maxY, double maxZ, boolean visible) {
        /** A cell that shows something. */
        CellBox(int row, int column, String crop, int flag, double minX, double minY, double minZ, double maxX,
                double maxY, double maxZ) {
            this(row, column, crop, flag, minX, minY, minZ, maxX, maxY, maxZ, true);
        }

        CellBox withVisible(boolean shown) {
            return shown == visible ? this : new CellBox(row, column, crop, flag, minX, minY, minZ, maxX, maxY, maxZ,
                shown);
        }

        /** The volume in world coordinates for a greenhouse whose grid corner is this block. */
        AABB bounds(BlockPos corner) {
            return new AABB(corner.getX() + minX, GreenhouseLocator.HOLOGRAM_Y + minY, corner.getZ() + minZ,
                corner.getX() + maxX, GreenhouseLocator.HOLOGRAM_Y + maxY, corner.getZ() + maxZ);
        }
    }

    /** {@code shapes}: the (0, 0) frame and post; {@code placements}: models; {@code cells}: ray-test volumes. */
    record Layout(List<Shape> shapes, List<Placement> placements, List<CellBox> cells) {
        static final Layout EMPTY = new Layout(List.of(), List.of(), List.of());
    }

    /**
     * The layout's cells with {@code visible} set from the plot scan: false where the footprint holding the cell is
     * CORRECT. {@code states} is aligned with the layout's placements, or null when no scan matches it (then every
     * cell shows its model).
     */
    static List<CellBox> visibleCells(Layout layout, List<GreenhouseScanLogic.State> states) {
        if (states == null) return layout.cells();
        List<GreenhouseScanLogic.Footprint> footprints = GreenhouseScanLogic.footprints(layout.placements());
        List<CellBox> out = new ArrayList<>(layout.cells().size());
        for (CellBox cell : layout.cells()) {
            double centreX = (cell.minX() + cell.maxX()) / 2;
            double centreZ = (cell.minZ() + cell.maxZ()) / 2;
            boolean shown = true;
            for (int i = 0; i < footprints.size() && i < states.size(); i++) {
                GreenhouseScanLogic.Footprint footprint = footprints.get(i);
                if (centreX > footprint.minX() && centreX < footprint.maxX() && centreZ > footprint.minZ()
                    && centreZ < footprint.maxZ()) {
                    shown = states.get(i) != GreenhouseScanLogic.State.CORRECT;
                    break;
                }
            }
            out.add(cell.withVisible(shown));
        }
        return List.copyOf(out);
    }

    /** A crop's head is drawn the way a vanilla armor stand wears one: the 0.5-block head item at 1.1875x. */
    static final double CROP_HEAD_SCALE = 1.1875;
    /** A mutation's head grows with its footprint: 1x1 is 1, 2x2 is 2, 3x3 is 3 times the 0.5-block head. */
    static final double MUTATION_HEAD_SCALE_PER_CELL = 1.0;
    /** Keeps the bottom faces off the terrain's top face, which would flicker. */
    static final double LIFT = 0.01;
    static final double SLAB_HEIGHT = 0.15;

    private static final int GOLD = 0xFFD700;
    private static final int WHITE = 0xFFFFFF;
    private static final int FILL_ALPHA = 0x4D; // ~30%
    private static final int OUTLINE_ALPHA = 0xE6; // ~90%
    private static final int TARGET_FILL_ALPHA = 0x2E; // ~18%
    private static final double ALERT_INSET = 0.04;
    private static final double ALERT_HEIGHT = 0.9;
    private static final double CUBE_INSET = 0.12;
    private static final double CUBE_HEIGHT = 0.9;
    private static final double SLAB_INSET = 0.04;
    private static final double POST_HEIGHT = 1.6;

    private GreenhouseHologramShapes() { }

    static Layout build(GreenhouseGrid grid, GreenhouseOrientation orientation) {
        List<Shape> shapes = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        List<CellBox> cells = new ArrayList<>();
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell == null) continue;
                GreenhouseOrientation.Offset at = orientation.offset(row, column);
                cells.add(new CellBox(row, column, cell.name(), cell.flag(), at.dx(), 0, at.dz(), at.dx() + 1, 1,
                    at.dz() + 1));
                if (cell.flag() == GreenhouseGrid.TARGET) continue;
                placements.add(new Placement(cell.name(), false, at.dx() + 0.5, LIFT, at.dz() + 0.5, CROP_HEAD_SCALE,
                    GreenhouseCropLook.of(cell.name()), cube(at, rgb(cell.name())), null,
                    area(at.dx(), at.dz(), at.dx() + 1, at.dz() + 1)));
            }
        }
        // One slab per mutation, as wide as its 1x1/2x2/3x3 footprint, so the empty soil it grows on is obvious,
        // and the mutation's own head standing on it, as large as the footprint.
        for (GreenhousePlan.Block block : GreenhousePlan.of(grid).blocks()) {
            Shape slab = flat(orientation, block.row(), block.column(), block.row() + block.height(),
                block.column() + block.width(), SLAB_INSET, SLAB_HEIGHT, GOLD, TARGET_FILL_ALPHA, 2.5F);
            // The footprint's edges, whichever way the orientation turned it.
            GreenhouseOrientation.Point from = orientation.point(block.row(), block.column());
            GreenhouseOrientation.Point to = orientation.point(block.row() + block.height(), block.column() + block.width());
            double minX = Math.min(from.x(), to.x());
            double minZ = Math.min(from.z(), to.z());
            double maxX = Math.max(from.x(), to.x());
            double maxZ = Math.max(from.z(), to.z());
            placements.add(new Placement(block.mutation(), true, (slab.minX() + slab.maxX()) / 2, LIFT + SLAB_HEIGHT,
                (slab.minZ() + slab.maxZ()) / 2, Math.max(block.width(), block.height()) * MUTATION_HEAD_SCALE_PER_CELL,
                GreenhouseCropLook.of(block.mutation()), null, slab,
                area(minX, minZ, maxX, maxZ)));
        }
        // Cell (0, 0) is the grid's top-left, whatever it holds: a heavy white frame and a post on its outer corner.
        shapes.add(flat(orientation, 0, 0, 1, 1, 0.02, 0.05, WHITE, 0x33, 4.0F));
        shapes.add(post(orientation));
        return new Layout(List.copyOf(shapes), List.copyOf(placements), List.copyOf(cells));
    }

    /** The same hue as the planner's cell colour (GreenhouseGridPainter), brighter so it reads in the world. */
    static int rgb(String name) {
        float hue = (name.hashCode() & 0x7FFFFFFF) % 360 / 360F;
        return Mth.hsvToArgb(hue, 0.6F, 1.0F, 0) & 0xFFFFFF;
    }

    /** The volume over a cell or footprint (corner-relative edges); coloured by whoever draws it. */
    static Shape area(double minX, double minZ, double maxX, double maxZ) {
        return new Shape(minX + ALERT_INSET, LIFT, minZ + ALERT_INSET, maxX - ALERT_INSET, LIFT + ALERT_HEIGHT,
            maxZ - ALERT_INSET, 0, 0, 2.0F);
    }

    /** The coloured stand-in for a cell whose model cannot be drawn. */
    private static Shape cube(GreenhouseOrientation.Offset at, int rgb) {
        return new Shape(at.dx() + CUBE_INSET, LIFT, at.dz() + CUBE_INSET, at.dx() + 1 - CUBE_INSET,
            LIFT + CUBE_HEIGHT, at.dz() + 1 - CUBE_INSET, rgb | FILL_ALPHA << 24, rgb | OUTLINE_ALPHA << 24, 2.0F);
    }

    /** A low box over a block of grid cells (edges are row/column numbers), lifted onto the floor. */
    private static Shape flat(GreenhouseOrientation orientation, int row0, int column0, int row1, int column1,
                              double inset, double height, int rgb, int fillAlpha, float width) {
        GreenhouseOrientation.Point a = orientation.point(row0, column0);
        GreenhouseOrientation.Point b = orientation.point(row1, column1);
        return new Shape(Math.min(a.x(), b.x()) + inset, LIFT, Math.min(a.z(), b.z()) + inset,
            Math.max(a.x(), b.x()) - inset, LIFT + height, Math.max(a.z(), b.z()) - inset,
            rgb | fillAlpha << 24, rgb | 0xFF000000, width);
    }

    /** A thin white post hugging the outer corner of cell (0, 0), tall enough to spot from across the plot. */
    private static Shape post(GreenhouseOrientation orientation) {
        GreenhouseOrientation.Point a = orientation.point(0.04, 0.04);
        GreenhouseOrientation.Point b = orientation.point(0.24, 0.24);
        return new Shape(Math.min(a.x(), b.x()), LIFT, Math.min(a.z(), b.z()),
            Math.max(a.x(), b.x()), LIFT + POST_HEIGHT, Math.max(a.z(), b.z()),
            WHITE | 0x66 << 24, 0xFFFFFFFF, 2.0F);
    }
}
