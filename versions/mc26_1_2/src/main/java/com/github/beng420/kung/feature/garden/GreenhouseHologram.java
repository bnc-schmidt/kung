package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.message.KungMessages;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Session state of the greenhouse hologram: the layout shown, its orientation and the boxes derived from both.
 * Nothing persists and nothing here draws; {@link GreenhouseWorldRenderer} reads {@link #layout()} and
 * {@link #version()}. Client thread only.
 *
 * <p>The screen buttons call {@link #showFromClipboard()}, {@link #hide()}, {@link #rotate()} and
 * {@link #mirror()}; {@link #show(GreenhouseGrid)} is for code in this package that already holds a grid.
 */
public final class GreenhouseHologram {
    /** {@code insideGreenhouse}: the player stands in their greenhouse plot right now. */
    public record ShowResult(String message, boolean insideGreenhouse) { }

    static final String INSIDE = "Showing hologram in your greenhouse";
    static final String ARMED = "Hologram armed: walk into your greenhouse, or stand in it and run"
        + " /kung greenhouse blueprint here";
    static final String HERE = "Showing hologram in this garden plot (greenhouse detection skipped)";
    static final String NOT_IN_GARDEN = "Hologram armed: you are not in a Garden plot, so detection cannot be"
        + " skipped yet; stand in your greenhouse and run /kung greenhouse blueprint here";

    private static GreenhouseGrid grid;
    private static GreenhouseOrientation orientation = GreenhouseOrientation.IDENTITY;
    private static GreenhouseHologramShapes.Layout shown = GreenhouseHologramShapes.Layout.EMPTY;
    private static int version;
    /** The player's own show/hide (the toggle key): independent of showing a layout. */
    private static boolean visible = true;

    private GreenhouseHologram() { }

    /** Registers the world renderer once at client start; the hologram stays off until something shows one. */
    public static void initialize() {
        GreenhouseWorldRenderer.initialize();
        GreenhouseCrosshairLabel.initialize();
        GreenhouseHologramSettings.setToggleHandler(GreenhouseHologram::toggleVisible);
        GreenhouseHologramSettings.setUnloadHandler(GreenhouseHologram::unload);
        // Leaving or joining a server (also a profile change): the scan of the last session is not this one's.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onConnectionChange());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onConnectionChange());
    }

    /** The scan results belong to one session: the same corner after a rejoin must not reuse them. */
    static void onConnectionChange() {
        GreenhouseScanner.reset();
    }

    /** Shows the layout until {@link #hide()}; it is drawn only while the player is in their greenhouse. */
    static ShowResult show(GreenhouseGrid layout) {
        // Starts the heads' skin downloads: a head draws the default skin until its texture has arrived.
        GreenhouseIcons.warm();
        return show(layout, GreenhouseLocator.current(Minecraft.getInstance(), true).isPresent());
    }

    /** Test seam: the greenhouse lookup needs a running client. */
    static ShowResult show(GreenhouseGrid layout, boolean insideGreenhouse) {
        grid = copyOf(layout);
        visible = true;
        rebuild();
        GreenhouseScanner.reset();
        return new ShowResult(insideGreenhouse ? INSIDE : ARMED, insideGreenhouse);
    }

    /**
     * Decodes the clipboard (a Kung key, a Sky Mutations link or a bare layout string) and shows it. Empty when
     * the clipboard holds no layout with at least one cell; the caller reports that.
     */
    public static Optional<ShowResult> showFromClipboard() {
        return decodeNonEmpty(Minecraft.getInstance().keyboardHandler.getClipboard())
            .map(GreenhouseHologram::show);
    }

    /**
     * Like {@link #showFromClipboard()} for when the Carpenter cannot be found: the garden plot the player stands
     * in is taken as the greenhouse. Needs the Garden; elsewhere the hologram is only armed.
     */
    public static Optional<ShowResult> showFromClipboardHere() {
        Minecraft client = Minecraft.getInstance();
        // Decode first: a bad clipboard must not mark the plot.
        return decodeNonEmpty(client.keyboardHandler.getClipboard())
            .map(layout -> {
                GreenhouseIcons.warm();
                return showHere(layout, GreenhouseLocator.markHere(client).isPresent());
            });
    }

    /** Test seam for {@link #showFromClipboard()}. */
    static Optional<ShowResult> showFromText(String text, boolean insideGreenhouse) {
        return decodeNonEmpty(text).map(layout -> show(layout, insideGreenhouse));
    }

    /** Test seam for {@link #showFromClipboardHere()}; {@code marked}: the plot was taken as the greenhouse. */
    static Optional<ShowResult> showFromTextHere(String text, boolean marked) {
        return decodeNonEmpty(text).map(layout -> showHere(layout, marked));
    }

    private static ShowResult showHere(GreenhouseGrid layout, boolean marked) {
        show(layout, marked);
        return new ShowResult(marked ? HERE : NOT_IN_GARDEN, marked);
    }

    private static Optional<GreenhouseGrid> decodeNonEmpty(String text) {
        return GreenhouseCode.decode(text).filter(layout -> layout.filled() > 0);
    }

    /**
     * Turns the hologram off: drops the shown layout and stops scanning. The orientation stays for the next show.
     * {@code blueprint off} calls this, and so does a finished layout ({@link GreenhouseScanner#completeIfDone}).
     */
    public static void hide() {
        GreenhouseScanner.reset();
        grid = null;
        shown = GreenhouseHologramShapes.Layout.EMPTY;
        version++;
    }

    /**
     * The unload key: removes the hologram completely, as {@link #hide()} does, and says so. Empty, and nothing
     * changes, while no hologram is shown (the key then has no meaning).
     */
    static Optional<String> unloadMessage() {
        if (!isShowing()) return Optional.empty();
        hide();
        return Optional.of("Greenhouse hologram unloaded (show it again in /kung greenhouse)");
    }

    /** The unload key's handler: {@link #unloadMessage()} in chat, nothing at all while no hologram is shown. */
    static void unload() {
        unloadMessage().ifPresent(
            message -> KungMessages.send(Minecraft.getInstance(), KungMessages.Type.INFO, "", message));
    }

    public static boolean isShowing() { return grid != null; }

    /** Whether the player has not hidden the hologram with the toggle key (true whenever nothing is shown). */
    static boolean userVisible() { return visible; }

    /** Whether anything is drawn and claimed: a layout is shown and the player has not toggled it off. */
    static boolean isDrawn() { return isShowing() && visible; }

    /**
     * The toggle key's action, client thread: flips the hologram as {@link #toggle()} says and returns the chat
     * message for it. Empty, and nothing changes, while no hologram is shown (the key then has no meaning).
     */
    static Optional<String> toggleMessage() {
        return isShowing() ? Optional.of(toggle()) : Optional.empty();
    }

    /** The toggle key's handler: {@link #toggleMessage()} in chat, nothing at all while no hologram is shown. */
    static void toggleVisible() {
        toggleMessage().ifPresent(
            message -> KungMessages.send(Minecraft.getInstance(), KungMessages.Type.INFO, "", message));
    }

    /** The toggle key: flips the player's show/hide and returns the chat message for the new state. */
    static String toggle() {
        visible = !visible;
        return visible ? "Greenhouse hologram shown" : "Greenhouse hologram hidden";
    }

    /**
     * How many of this crop the last plot scan found planted in the grid area (armor stands wearing its head, or
     * its block); the two mushrooms count by their own names. 0 before a scan, while no hologram is shown or the
     * player is away from the greenhouse. For the pick-block supply logic; the amount required per crop comes
     * from the layout ({@link #cellBoxes()}).
     */
    public static int plantedCount(String crop) {
        return GreenhouseScanner.plantedCount(crop);
    }

    /**
     * How many of the shown layout's ingredient cells of this crop (flag != 0) already hold the right plant at the
     * last plot scan: the cells {@link #cellBoxes()} reports as not visible. Unlike {@link #plantedCount}, a plant
     * standing on the wrong cell does not count, and a cell with a wrong or unknown plant still needs the crop.
     * 0 before a scan, when the scan belongs to another layout and while no hologram is shown. This is what the
     * pick-block supply subtracts from what the layout needs.
     */
    public static int correctlyPlaced(String crop) {
        // Whether the hologram is hidden does not change what is planted.
        return correctlyPlaced(crop, allCellBoxes());
    }

    /**
     * A soil block the last plot scan found missing or wrong: its raw offset from the greenhouse corner, the soil that
     * belongs there (also when the wrong block or wrong state stands there), the y of the soil layer and how it
     * differs ({@code MISSING}: drawn as a translucent block, {@code WRONG_BLOCK}: red, {@code WRONG_STATE}: orange).
     */
    record SoilGhost(int dx, int dz, GreenhouseSoils.Soil soil, int y, GreenhouseSoilCheck.GhostKind kind) {
        /** A missing soil block. */
        SoilGhost(int dx, int dz, GreenhouseSoils.Soil soil, int y) {
            this(dx, dz, soil, y, GreenhouseSoilCheck.GhostKind.MISSING);
        }

        /** The ghost block's volume in world coordinates for a greenhouse whose grid corner is this block. */
        AABB bounds(BlockPos corner) {
            return new AABB(corner.getX() + dx, y, corner.getZ() + dz, corner.getX() + dx + 1, y + 1,
                corner.getZ() + dz + 1);
        }
    }

    /**
     * The soil ghosts of the latest scan (missing, wrong block and wrong state), for ray tests such as picking the soil
     * to fetch. Empty while nothing is drawn (no hologram or toggled off), before a scan and when no soil
     * layer was found.
     */
    static List<SoilGhost> soilGhosts() {
        int layer = GreenhouseScanner.soilLayer(version);
        if (!isDrawn() || layer < 0) return List.of();
        return GreenhouseScanner.ghosts(version).stream()
            .map(ghost -> new SoilGhost(ghost.dx(), ghost.dz(), ghost.soil(), layer, ghost.kind())).toList();
    }

    /**
     * The layout's footprints that a soil ghost keeps from being planted while the hide-models-over-wrong-soil setting
     * is on: the renderer draws no model, slab or outline for them, so a pick or crosshair label must not claim their
     * cells either (see {@link GreenhousePickHook#worldCells}). Empty with the setting off, while nothing is drawn and
     * when no soil layer is known, the same conditions the renderer hides under.
     */
    static List<GreenhouseScanLogic.Footprint> blockedFootprints() {
        if (!isDrawn() || !GreenhouseHologramSettings.hideModelsOverWrongSoil()
            || GreenhouseScanner.soilLayer(version) < 0) {
            return List.of();
        }
        return GreenhouseSoilCheck.blockedFootprints(GreenhouseScanLogic.footprints(shown.placements()),
            GreenhouseScanner.ghosts(version));
    }

    /** Test seam for {@link #correctlyPlaced(String)}. */
    static int correctlyPlaced(String crop, List<GreenhouseHologramShapes.CellBox> cells) {
        String wanted = GreenhouseCrops.canonical(crop == null ? "" : crop);
        int count = 0;
        for (GreenhouseHologramShapes.CellBox cell : cells) {
            if (cell.flag() != GreenhouseGrid.TARGET && !cell.visible() && cell.crop().equals(wanted)) count++;
        }
        return count;
    }

    /** The layout being shown, if any. */
    static Optional<GreenhouseGrid> grid() { return Optional.ofNullable(grid); }

    /** One more clockwise quarter turn; the orientation is kept while hidden. Returns the new orientation. */
    public static String rotate() {
        orientation = orientation.rotatedClockwise();
        rebuild();
        return orientation.describe();
    }

    /** Swaps the grid's left and right; the orientation is kept while hidden. Returns the new orientation. */
    public static String mirror() {
        orientation = orientation.mirrored();
        rebuild();
        return orientation.describe();
    }

    static GreenhouseOrientation orientation() { return orientation; }

    /** For the debug dump: whether something is shown, how it is turned and how many boxes it is. */
    static String stateSummary() {
        return "showing=" + isShowing() + " userVisible=" + visible + " complete=" + GreenhouseScanner.complete()
            + " orientation=" + orientation.describe()
            + " boxes=" + shown.shapes().size() + " models=" + shown.placements().size();
    }

    /** Boxes relative to the greenhouse corner; empty while hidden. */
    static List<GreenhouseHologramShapes.Shape> shapes() { return shown.shapes(); }

    /** The models to draw (crop heads and blocks, mutation heads), corner-relative; empty while hidden. */
    static List<GreenhouseHologramShapes.Placement> placements() { return shown.placements(); }

    /**
     * The volume of every filled grid cell with its row, column, crop name and flag, for ray tests such as picking
     * the crop the player looks at. Corner-relative (y 0..1 above the hologram floor): add the greenhouse corner,
     * or use {@link GreenhouseHologramShapes.CellBox#bounds}. Follows the orientation; empty while hidden. A cell
     * whose plant is already right (per the last plot scan) has {@code visible() == false}: nothing is drawn there.
     * Empty while nothing is drawn (toggled off with the key), so a pick-block ray
     * claims nothing then. Not a per-frame call: it builds the list.
     */
    static List<GreenhouseHologramShapes.CellBox> cellBoxes() {
        return isDrawn() ? allCellBoxes() : List.of();
    }

    /** The cells of the shown layout with their scan state, whether or not the hologram is currently drawn. */
    private static List<GreenhouseHologramShapes.CellBox> allCellBoxes() {
        return GreenhouseHologramShapes.visibleCells(shown, GreenhouseScanner.states(version));
    }

    /** Everything above in one piece. */
    static GreenhouseHologramShapes.Layout layout() { return shown; }

    /** Changes whenever {@link #shapes()} does, so the renderer rebuilds its world boxes only then. */
    static int version() { return version; }

    /** A snapshot: the planner keeps editing its own grid, which must not change a shown hologram on rotate. */
    private static GreenhouseGrid copyOf(GreenhouseGrid source) {
        GreenhouseGrid copy = new GreenhouseGrid();
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) copy.set(row, column, source.get(row, column));
        }
        return copy;
    }

    private static void rebuild() {
        shown = grid == null ? GreenhouseHologramShapes.Layout.EMPTY : GreenhouseHologramShapes.build(grid, orientation);
        version++;
    }
}
