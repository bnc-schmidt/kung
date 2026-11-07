package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.feature.waypoints.Waypoint;
import com.github.beng420.kung.feature.waypoints.WaypointGroup;
import com.github.beng420.kung.feature.waypoints.WaypointIsland;
import com.github.beng420.kung.feature.waypoints.WaypointShare;
import com.github.beng420.kung.feature.waypoints.WaypointStore;
import com.github.beng420.kung.feature.waypoints.WaypointType;
import com.github.beng420.kung.feature.waypoints.WaypointWallStyle;
import com.github.beng420.kung.feature.waypoints.WaypointsFeature;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiButton;
import com.github.beng420.kung.ui.UiMenuFont;
import com.github.beng420.kung.ui.UiScale;
import com.github.beng420.kung.ui.UiScrollList;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.ui.UiTextField;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KeyCodes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The waypoint editor, laid out like Skyblocker's: an island menu at the top right, then the waypoint groups of that
 * island as rows, each with its waypoints below it. A group row has its enabled box, name, Route, Walls, wall look and
 * type controls and the New Waypoint and delete buttons; a waypoint row has a grip to drag it, its enabled box, name,
 * X, Y, Z, color and opacity. The Show All tab lists every group with the areas (islands) it shows on; a group with no
 * area ticked shows everywhere.
 *
 * <p>Changes are made on a copy of every group. Done writes them, and closing any other way throws them away, unless the
 * Auto Save setting is on: then they are written as they are made and nothing is asked or discarded.
 * The Share button turns the same list into a picker for exporting, and its footer imports Skyblocker, Skytils and
 * Coleweight text from the clipboard.
 */
public final class WaypointsScreen extends Screen {
    private static final UiTheme THEME = UiTheme.menu();
    private static final int PANEL_WIDTH = 600;
    private static final int PANEL_HEIGHT = 396;
    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 11;
    private static final int LIST_TOP = 54;
    private static final int ROW_WIDTH = 560;
    private static final int DROPDOWN_ROWS = 12;
    private static final int DROPDOWN_ROW_HEIGHT = 18;
    private static final int SHOW_ALL_WIDTH = 68;
    private static final int AREA_COLUMNS = 3;
    private static final int AREA_CELL_WIDTH = 176;
    /** Menu units the pointer moves with the grip held before the row comes loose. */
    private static final int DRAG_START = 4;
    /** Menu units from the top or bottom of the list within which a drag scrolls it. */
    private static final int EDGE_ZONE = 22;
    private static final double EDGE_ROWS_PER_SECOND = 14;
    private static final int AUTO_SAVE_TICKS = 10;
    private static final int PREVIEW_COLOR = Waypoint.DEFAULT_RGB;
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{0,9}");
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{0,6}");
    private static final Pattern COMPLETE_HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final Pattern PERCENT = Pattern.compile("[0-9]{0,3}");
    private static final String[] MANUAL_LABELS = {"Share", "New Group", "Cancel", "Done"};
    private static final String[] AUTO_LABELS = {"Share", "New Group", "Done"};

    private final Screen parent;
    private final WaypointStore store = WaypointStore.INSTANCE;
    private final List<WaypointGroup> groups = store.copyOfGroups();
    private final Set<WaypointGroup> collapsed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<WaypointGroup> areasOpen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<WaypointGroup, BitSet> selected = new IdentityHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final UiScrollList scroll = new UiScrollList();
    private final UiScrollList dropdownScroll = new UiScrollList();
    private String savedState = WaypointStore.fingerprint(groups);
    private WaypointIsland island = WaypointsFeature.INSTANCE.island();
    private WaypointGroup lastGroup;
    private UiTextField focused;
    private Font menuFont;
    private boolean showAll;
    private boolean dropdownOpen;
    private boolean sharing;
    private boolean overrideIsland;
    private boolean sortImport;
    private boolean statusIsError;
    private String status = "";
    private int autoSaveTicks;
    private int x;
    private int y;
    // A drag: the row whose grip is held, where it was grabbed, and the list as it looks while the row is in the air.
    private WaypointRow pressed;
    private int pressX;
    private int pressY;
    private boolean dragging;
    private int dragX;
    private int dragY;
    private List<Row> dragView;
    private int gapIndex;
    private long lastDragNanos;
    private double edgeCarry;

    private sealed interface Row permits GroupRow, WaypointRow, AreaRow, GapRow { }

    private record GroupRow(WaypointGroup group, UiTextField name) implements Row { }

    private record WaypointRow(WaypointGroup group, int index, UiTextField name, UiTextField x, UiTextField y,
                               UiTextField z, UiTextField hex, UiTextField alpha) implements Row { }

    /** One line of the Show All area list: up to {@link #AREA_COLUMNS} islands of one group, each with a box. */
    private record AreaRow(WaypointGroup group, List<WaypointIsland> cells) implements Row { }

    /** The open slot a dragged row would land in. */
    private enum GapRow implements Row { INSTANCE }

    /** Opens on the island the player is on. {@code parent} is shown again when the editor closes; it may be null. */
    public WaypointsScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        collapseAll();
    }

    @Override
    protected void init() {
        menuFont = UiMenuFont.wrap(font);
        rebuild();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        focus(null);
        autoSave();
    }

    @Override
    public void tick() {
        super.tick();
        if (++autoSaveTicks >= AUTO_SAVE_TICKS) {
            autoSaveTicks = 0;
            autoSave();
        }
    }

    // region Model

    private boolean areaMode() { return showAll && !sharing; }

    private List<WaypointGroup> islandGroups() {
        if (showAll) return groups;
        return groups.stream().filter(group -> group.listedOn(island.id())).toList();
    }

    private void collapseAll() {
        collapsed.addAll(islandGroups());
    }

    private void selectIsland(WaypointIsland next) {
        island = next;
        showAll = false;
        collapsed.clear();
        collapseAll();
        scroll.reset();
        lastGroup = null;
        dropdownOpen = false;
        rebuild();
    }

    private void toggleShowAll() {
        showAll = !showAll;
        areasOpen.clear();
        collapsed.clear();
        if (!showAll) collapseAll();
        scroll.reset();
        lastGroup = null;
        dropdownOpen = false;
        focus(null);
        rebuild();
    }

    private List<WaypointIsland> islandChoices() {
        List<WaypointIsland> choices = new ArrayList<>(WaypointIsland.all());
        for (WaypointGroup group : groups) {
            for (String id : group.islands()) {
                WaypointIsland known = WaypointIsland.fromId(id);
                if (choices.stream().noneMatch(choice -> choice.id().equals(known.id()))) choices.add(known);
            }
        }
        choices.add(WaypointIsland.UNKNOWN);
        if (choices.stream().noneMatch(choice -> choice.id().equals(island.id()))) choices.add(island);
        return choices;
    }

    /** The islands a group can be ticked for: every island but "Everywhere", which is what no tick means. */
    private List<WaypointIsland> areaChoices() {
        return islandChoices().stream().filter(choice -> !choice.isUnknown()).toList();
    }

    private int groupCount(WaypointIsland choice) {
        return (int) groups.stream().filter(group -> group.listedOn(choice.id())).count();
    }

    private boolean changed() {
        return !WaypointStore.fingerprint(groups).equals(savedState);
    }

    /** With Auto Save on, writes the groups when they differ from what was last written. False when a write failed. */
    private boolean autoSave() {
        if (!KungConfig.get().waypoints.autoSave() || !changed()) return true;
        if (store.replaceAll(groups)) {
            savedState = WaypointStore.fingerprint(groups);
            return true;
        }
        say("Not saved: " + store.saveError(), true);
        return false;
    }

    private BlockPos defaultPos() {
        var client = minecraft;
        if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
        return client.player != null ? client.player.blockPosition() : BlockPos.ZERO;
    }

    private void replace(WaypointGroup group, int index, UnaryOperator<Waypoint> change) {
        if (index >= 0 && index < group.waypoints().size()) group.waypoints().set(index, change.apply(group.waypoints().get(index)));
    }

    private void newGroup() {
        // Made in Show All, or under Everywhere, a group has no area ticked and so shows everywhere.
        String islandId = showAll ? WaypointIsland.UNKNOWN.id() : island.id();
        WaypointGroup group = new WaypointGroup("New Group", islandId, KungConfig.get().waypoints.defaultType());
        int at = lastGroup != null && groups.contains(lastGroup) ? groups.indexOf(lastGroup) + 1 : groups.size();
        groups.add(at, group);
        lastGroup = group;
        rebuild();
        reveal(group, false);
    }

    private void newWaypoint(WaypointGroup group) {
        group.waypoints().add(Waypoint.at(defaultPos(), "Waypoint " + (group.waypoints().size() + 1)));
        collapsed.remove(group);
        lastGroup = group;
        rebuild();
        reveal(group, true);
    }

    /** Scrolls down far enough to show a group's row, or its last waypoint row, if that lies below the window. */
    private void reveal(WaypointGroup group, boolean lastWaypoint) {
        int target = -1;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row instanceof GroupRow groupRow && groupRow.group() == group && !lastWaypoint) target = index;
            if (row instanceof WaypointRow waypointRow && waypointRow.group() == group && lastWaypoint) target = index;
        }
        if (target >= 0) {
            scroll.setOffsetWithinMax(Math.max(scroll.offset(), target - VISIBLE_ROWS + 1),
                UiScrollList.maxOffset(rows.size(), VISIBLE_ROWS));
        }
    }

    private void deleteWaypoint(WaypointGroup group, int index) {
        group.waypoints().remove(index);
        selected.remove(group);
        rebuild();
    }

    private void moveWaypoint(WaypointGroup group, int index, int delta) {
        int to = Math.clamp(index + delta, 0, group.waypoints().size() - 1);
        if (to == index) return;
        group.waypoints().add(to, group.waypoints().remove(index));
        // The share ticks are by position, so a move would leave them on the wrong waypoints.
        selected.remove(group);
        rebuild();
    }

    // endregion

    // region Rows and fields

    private void rebuild() {
        focused = null;
        cancelDrag();
        rows.clear();
        for (WaypointGroup group : islandGroups()) {
            rows.add(new GroupRow(group, field(group.name(), 64, null, group::setName, "Group name", "Name")));
            if (areaMode()) {
                if (areasOpen.contains(group)) addAreaRows(group);
                continue;
            }
            if (collapsed.contains(group)) continue;
            for (int index = 0; index < group.waypoints().size(); index++) rows.add(waypointRow(group, index));
        }
        scroll.setOffsetWithinMax(scroll.offset(), UiScrollList.maxOffset(rows.size(), VISIBLE_ROWS));
    }

    private void addAreaRows(WaypointGroup group) {
        List<WaypointIsland> choices = areaChoices();
        for (int from = 0; from < choices.size(); from += AREA_COLUMNS) {
            rows.add(new AreaRow(group, List.copyOf(choices.subList(from, Math.min(choices.size(), from + AREA_COLUMNS)))));
        }
    }

    private WaypointRow waypointRow(WaypointGroup group, int index) {
        Waypoint waypoint = group.waypoints().get(index);
        return new WaypointRow(group, index,
            field(waypoint.name(), 64, null, value -> replace(group, index, w -> w.withName(value)), "Waypoint name", "Name"),
            field(Integer.toString(waypoint.x()), 10, INTEGER.asMatchPredicate(), value ->
                replace(group, index, w -> w.withPos(number(value), w.y(), w.z())), "X", "X"),
            field(Integer.toString(waypoint.y()), 10, INTEGER.asMatchPredicate(), value ->
                replace(group, index, w -> w.withPos(w.x(), number(value), w.z())), "Y", "Y"),
            field(Integer.toString(waypoint.z()), 10, INTEGER.asMatchPredicate(), value ->
                replace(group, index, w -> w.withPos(w.x(), w.y(), number(value))), "Z", "Z"),
            field(String.format("#%06X", waypoint.rgb()), 7, HEX.asMatchPredicate(), value -> {
                if (COMPLETE_HEX.matcher(value).matches()) {
                    int rgb = Integer.parseInt(value.startsWith("#") ? value.substring(1) : value, 16);
                    replace(group, index, w -> w.withColor(rgb, w.alpha()));
                }
            }, "Color as #RRGGBB", "#RRGGBB"),
            field(Integer.toString(Math.round(waypoint.alpha() * 100F)), 3, PERCENT.asMatchPredicate(), value -> {
                if (!value.isEmpty()) replace(group, index, w -> w.withColor(w.rgb(), Math.min(100, Integer.parseInt(value)) / 100F));
            }, "Opacity in percent", "%"));
    }

    /** An empty field or a lone minus sign counts as 0, as in Skyblocker's editor. */
    private static int number(String value) {
        return value.isEmpty() || value.equals("-") ? 0 : Integer.parseInt(value);
    }

    private UiTextField field(String value, int maxLength, Predicate<String> filter, Consumer<String> responder,
                              String narration, String hint) {
        UiTextField field = new UiTextField(menuFont, Component.literal(narration)).maxLength(maxLength)
            .hint(hint).textShadow(false);
        if (filter != null) field.filter(filter);
        // The value goes in before the responder, so building a row changes nothing.
        field.setValue(value);
        // EditBox calls its responder with the new text before UiTextField can take a refused edit back, so the
        // responder only ever sees text that passes the filter ("abc" in a number field must not reach parseInt).
        return field.responder(text -> {
            if (filter == null || filter.test(text)) responder.accept(text);
        });
    }

    private void focus(UiTextField next) {
        if (focused != null && focused != next) focused.setFocused(false);
        focused = next;
        if (next != null) next.setFocused(true);
    }

    private List<UiTextField> fieldsOf(Row row) {
        if (row instanceof GroupRow group) return List.of(group.name());
        if (row instanceof WaypointRow waypoint) {
            return List.of(waypoint.name(), waypoint.x(), waypoint.y(), waypoint.z(), waypoint.hex(), waypoint.alpha());
        }
        return List.of();
    }

    // endregion

    // region Layout

    private float scale() {
        return UiScale.menu(Math.min(0.8F, Math.min(width / (PANEL_WIDTH + 20F), height / (PANEL_HEIGHT + 20F))));
    }

    private int coordinate(double value) { return (int) Math.floor(value / scale()); }

    /** The rows as they are drawn and clicked: the plain list, or the list with the gap while a row is dragged. */
    private List<Row> view() { return dragView != null ? dragView : rows; }

    private void layout() {
        x = (coordinate(width) - PANEL_WIDTH) / 2;
        y = (coordinate(height) - PANEL_HEIGHT) / 2;
        List<Row> list = view();
        int first = scroll.offset();
        for (int slot = 0; slot < VISIBLE_ROWS && first + slot < list.size(); slot++) {
            Row row = list.get(first + slot);
            int top = listTop() + slot * ROW_HEIGHT + 2;
            if (row instanceof GroupRow group) {
                group.name().setBounds(left() + (areaMode() ? 36 : 52), top, areaMode() ? 130 : 84, 20);
            } else if (row instanceof WaypointRow waypoint) {
                waypoint.name().setBounds(left() + 56, top, 140, 20);
                waypoint.x().setBounds(left() + 200, top, 44, 20);
                waypoint.y().setBounds(left() + 248, top, 44, 20);
                waypoint.z().setBounds(left() + 296, top, 44, 20);
                waypoint.hex().setBounds(left() + 370, top, 68, 20);
                waypoint.alpha().setBounds(left() + 442, top, 40, 20);
            }
        }
    }

    private int left() { return x + 14; }
    private int listTop() { return y + LIST_TOP; }
    private int right() { return left() + ROW_WIDTH; }
    private int footerTop() { return y + LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + 6; }

    private UiBounds settingsBounds() { return new UiBounds(x + 100, y + 8, 80, 20); }
    private UiBounds autoSaveBounds() { return new UiBounds(x + 192, y + 8, 90, 20); }
    private UiBounds showAllBounds() { return new UiBounds(dropdownBounds().x() - 6 - SHOW_ALL_WIDTH, y + 8, SHOW_ALL_WIDTH, 20); }
    private UiBounds dropdownBounds() { return new UiBounds(x + PANEL_WIDTH - 14 - 170, y + 8, 170, 20); }
    private UiBounds warningBounds() { return new UiBounds(left(), y + 34, ROW_WIDTH, 16); }
    private UiBounds arrowBounds(int top) { return new UiBounds(left(), top + 4, 14, 16); }
    private UiBounds groupCheckBounds(int top) { return new UiBounds(left() + 18, top + 6, 12, 12); }
    private UiBounds markBounds(int top) { return new UiBounds(left() + 34, top + 2, 14, 20); }
    private UiBounds routeBounds(int top) { return new UiBounds(left() + 140, top + 2, 38, 20); }
    private UiBounds wallsBounds(int top) { return new UiBounds(left() + 182, top + 2, 38, 20); }
    private UiBounds wallStyleBounds(int top) { return new UiBounds(left() + 224, top + 2, 80, 20); }
    private UiBounds typeBounds(int top) { return new UiBounds(left() + 308, top + 2, 134, 20); }
    private UiBounds addBounds(int top) { return new UiBounds(right() - 108, top + 2, 84, 20); }
    private UiBounds deleteBounds(int top) { return new UiBounds(right() - 20, top + 2, 20, 20); }
    private UiBounds upBounds(int top) { return new UiBounds(left() + 8, top + 2, 12, 10); }
    private UiBounds downBounds(int top) { return new UiBounds(left() + 8, top + 12, 12, 10); }
    private UiBounds gripBounds(int top) { return new UiBounds(left() + 22, top + 4, 14, 16); }
    private UiBounds waypointCheckBounds(int top) { return new UiBounds(left() + 38, top + 6, 12, 12); }
    private UiBounds swatchBounds(int top) { return new UiBounds(left() + 346, top + 2, 20, 20); }
    private UiBounds areaCell(int top, int column) { return new UiBounds(left() + 24 + column * AREA_CELL_WIDTH, top + 2, AREA_CELL_WIDTH, 20); }
    private UiBounds footerButton(int index, int count) {
        int gap = 6;
        int width = (ROW_WIDTH - gap * (count - 1)) / count;
        return new UiBounds(left() + index * (width + gap), footerTop() + 38, width, 20);
    }
    private UiBounds optionBounds(int index) { return new UiBounds(left() + index * 200, footerTop() + 14, 190, 16); }
    private UiBounds dropdownEntry(int slot) {
        UiBounds button = dropdownBounds();
        return new UiBounds(button.x(), button.bottom() + 2 + slot * DROPDOWN_ROW_HEIGHT, button.width(), DROPDOWN_ROW_HEIGHT);
    }

    // endregion

    // region Drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        int mx = coordinate(mouseX);
        int my = coordinate(mouseY);
        if (dragging) {
            updateDrag(mx, my, true);
            layout();
        }
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale(), scale());
            graphics.fill(0, 0, coordinate(width), coordinate(height), THEME.backdrop());
            UiShapes.shadow(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5);
            UiShapes.rounded(graphics, x - 1, y - 1, PANEL_WIDTH + 2, PANEL_HEIGHT + 2, 6, THEME.accent());
            UiShapes.rounded(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5, THEME.panel());
            graphics.text(menuFont, sharing ? "Share Waypoints" : "Waypoints", x + 14, y + 14, THEME.accent(), false);
            if (!sharing) {
                UiButton.draw(graphics, menuFont, THEME, settingsBounds(), "Settings", mx, my, true, THEME.text());
                UiBounds auto = autoSaveBounds();
                checkbox(graphics, KungConfig.get().waypoints.autoSave(), new UiBounds(auto.x(), auto.y() + 4, 12, 12));
                graphics.text(menuFont, "Auto Save", auto.x() + 18, auto.y() + 6, THEME.text(), false);
                UiButton.draw(graphics, menuFont, THEME, showAllBounds(), "Show All", mx, my, true,
                    showAll ? THEME.accent() : THEME.text());
            }
            drawDropdownButton(graphics, mx, my);
            drawWarning(graphics, mx, my);
            drawRows(graphics, mx, my, partialTick);
            drawFooter(graphics, mx, my);
            if (dragging) drawDragged(graphics);
            if (dropdownOpen) drawDropdown(graphics, mx, my);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void drawWarning(GuiGraphicsExtractor graphics, int mx, int my) {
        UiBounds bounds = warningBounds();
        if (!KungConfig.get().waypoints.enabled()) {
            UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
                bounds.contains(mx, my) ? THEME.controlHover() : THEME.control());
            graphics.text(menuFont, "Waypoints is off, so nothing shows in the world. Click here to turn it on.",
                bounds.x() + 6, bounds.y() + 4, THEME.error(), false);
        } else {
            graphics.text(menuFont, tip(), bounds.x() + 2, bounds.y() + 4, THEME.muted(), false);
        }
    }

    private String tip() {
        if (sharing) return "Tick what to export, or import from the clipboard.";
        if (showAll) return "Click > to pick the areas of a group. No area ticked shows it everywhere.";
        return KungConfig.get().waypoints.autoSave() ? "Click > to open a group. Drag a row by its grip. Changes save by themselves."
            : "Click > to open a group. Drag a row by its grip. Done saves, Cancel discards.";
    }

    private void drawDropdownButton(GuiGraphicsExtractor graphics, int mx, int my) {
        UiBounds bounds = dropdownBounds();
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
            bounds.contains(mx, my) || dropdownOpen ? THEME.controlHover() : THEME.control());
        graphics.text(menuFont, menuFont.plainSubstrByWidth(island.name(), bounds.width() - 24), bounds.x() + 6, bounds.y() + 6,
            showAll ? THEME.muted() : THEME.text(), false);
        graphics.text(menuFont, dropdownOpen ? "^" : "v", bounds.right() - 12, bounds.y() + 6, THEME.muted(), false);
    }

    private void drawDropdown(GuiGraphicsExtractor graphics, int mx, int my) {
        List<WaypointIsland> choices = islandChoices();
        UiBounds first = dropdownEntry(0);
        int shown = Math.min(DROPDOWN_ROWS, choices.size());
        UiShapes.rounded(graphics, first.x() - 1, first.y() - 1, first.width() + 2, shown * DROPDOWN_ROW_HEIGHT + 2, 3, THEME.accent());
        UiShapes.rounded(graphics, first.x(), first.y(), first.width(), shown * DROPDOWN_ROW_HEIGHT, 3, THEME.panelDark());
        for (int slot = 0; slot < shown; slot++) {
            int index = dropdownScroll.offset() + slot;
            if (index >= choices.size()) break;
            WaypointIsland choice = choices.get(index);
            UiBounds bounds = dropdownEntry(slot);
            if (bounds.contains(mx, my)) graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), THEME.controlHover());
            int count = groupCount(choice);
            boolean current = !showAll && choice.id().equals(island.id());
            graphics.text(menuFont, choice.name() + (count > 0 ? "  (" + count + ")" : ""), bounds.x() + 6, bounds.y() + 5,
                current ? THEME.accent() : count > 0 ? THEME.text() : THEME.muted(), false);
        }
    }

    private void drawRows(GuiGraphicsExtractor graphics, int mx, int my, float partialTick) {
        List<Row> list = view();
        int first = scroll.offset();
        if (list.isEmpty()) {
            graphics.text(menuFont, showAll ? "No waypoint groups yet. New Group makes one."
                : "No waypoint groups on " + island.name() + ". New Group makes one.", left() + 2, listTop() + 8, THEME.muted(), false);
        }
        int[] source = dragging ? sourceSpan(list) : null;
        for (int slot = 0; slot < VISIBLE_ROWS && first + slot < list.size(); slot++) {
            Row row = list.get(first + slot);
            int top = listTop() + slot * ROW_HEIGHT;
            int index = first + slot;
            boolean fromGroup = source != null && index >= source[0] && index <= source[1];
            boolean hovered = !dragging && my >= top && my < top + ROW_HEIGHT && mx >= left() && mx < right();
            if (fromGroup) graphics.fill(left(), top, right(), top + ROW_HEIGHT, THEME.accent() & 0xFFFFFF | 0x20000000);
            if (row instanceof GroupRow group) drawGroup(graphics, group, top, mx, my, partialTick);
            else if (row instanceof WaypointRow waypoint) drawWaypoint(graphics, waypoint, top, hovered, mx, my, partialTick);
            else if (row instanceof AreaRow areas) drawAreas(graphics, areas, top, mx, my);
            else drawGap(graphics, top);
            if (fromGroup) outlineSource(graphics, top, index == source[0], index == source[1]);
        }
        int maxOffset = UiScrollList.maxOffset(list.size(), VISIBLE_ROWS);
        if (maxOffset > 0) {
            int track = VISIBLE_ROWS * ROW_HEIGHT;
            int thumb = Math.max(16, track * VISIBLE_ROWS / list.size());
            int thumbTop = listTop() + (track - thumb) * scroll.offset() / maxOffset;
            graphics.fill(x + PANEL_WIDTH - 10, listTop(), x + PANEL_WIDTH - 6, listTop() + track, THEME.panelDark());
            graphics.fill(x + PANEL_WIDTH - 10, thumbTop, x + PANEL_WIDTH - 6, thumbTop + thumb, THEME.accent());
        }
    }

    private void drawGroup(GuiGraphicsExtractor graphics, GroupRow row, int top, int mx, int my, float partialTick) {
        WaypointGroup group = row.group();
        graphics.fill(left(), top + 1, right(), top + ROW_HEIGHT - 1, THEME.panelSoft());
        boolean open = areaMode() ? areasOpen.contains(group) : !collapsed.contains(group);
        UiButton.draw(graphics, menuFont, THEME, arrowBounds(top), open ? "v" : ">", mx, my, true, THEME.text());
        checkbox(graphics, group.fullyEnabled() && !sharing || sharing && allSelected(group), groupCheckBounds(top));
        if (sharing) {
            graphics.text(menuFont, group.name(), left() + 38, top + 8, THEME.text(), false);
            graphics.text(menuFont, (group.ordered() ? "Route, " : "") + group.type().label() + ", "
                + group.waypoints().size() + " waypoints", left() + 172, top + 8, THEME.muted(), false);
            return;
        }
        row.name().extractRenderState(graphics, mx, my, partialTick);
        if (areaMode()) {
            UiBounds delete = deleteBounds(top);
            int textX = left() + 172;
            graphics.text(menuFont, menuFont.plainSubstrByWidth(areaSummary(group), delete.x() - 8 - textX), textX, top + 8,
                group.islands().isEmpty() ? THEME.muted() : THEME.text(), false);
            UiButton.draw(graphics, menuFont, THEME, delete, "X", mx, my, true, THEME.error());
            return;
        }
        UiButton.draw(graphics, menuFont, THEME, markBounds(top), "*", mx, my, true, group.marked() ? THEME.accent() : THEME.muted());
        UiButton.draw(graphics, menuFont, THEME, routeBounds(top), "Route", mx, my, true, group.ordered() ? THEME.accent() : THEME.muted());
        UiButton.draw(graphics, menuFont, THEME, wallsBounds(top), "Walls", mx, my, true, group.throughWalls() ? THEME.accent() : THEME.muted());
        // The look through walls, and the type, each with a block that shows what it draws.
        UiBounds style = wallStyleBounds(top);
        UiButton.draw(graphics, menuFont, THEME, style, "", mx, my, true, THEME.text());
        if (group.wallStyle() == WaypointWallStyle.ICON) WaypointPreview.drawIcon(graphics, style.x() + 3, style.y() + 1, PREVIEW_COLOR);
        else WaypointPreview.draw(graphics, style.x() + 3, style.y() + 1, PREVIEW_COLOR, group.type().fill(), group.type().outline(), false);
        graphics.text(menuFont, group.wallStyle().label(), style.x() + 25, style.y() + 6,
            group.throughWalls() ? THEME.text() : THEME.muted(), false);
        UiBounds type = typeBounds(top);
        UiButton.draw(graphics, menuFont, THEME, type, "", mx, my, true, THEME.text());
        WaypointPreview.draw(graphics, type.x() + 3, type.y() + 1, PREVIEW_COLOR, group.type().fill(), group.type().outline(),
            group.type().beam());
        graphics.text(menuFont, menuFont.plainSubstrByWidth(group.type().label(), type.width() - 30), type.x() + 25, type.y() + 6,
            THEME.text(), false);
        UiButton.draw(graphics, menuFont, THEME, addBounds(top), "+ Waypoint", mx, my, true, THEME.success());
        UiButton.draw(graphics, menuFont, THEME, deleteBounds(top), "X", mx, my, true, THEME.error());
    }

    private String areaSummary(WaypointGroup group) {
        List<String> ids = group.islands();
        if (ids.isEmpty()) return "Everywhere (no area ticked)";
        StringBuilder names = new StringBuilder();
        for (String id : ids) {
            if (!names.isEmpty()) names.append(", ");
            names.append(WaypointIsland.fromId(id).name());
        }
        return names.toString();
    }

    private void drawAreas(GuiGraphicsExtractor graphics, AreaRow row, int top, int mx, int my) {
        graphics.fill(left() + 20, top + 1, right(), top + ROW_HEIGHT - 1, THEME.panelDark());
        for (int column = 0; column < row.cells().size(); column++) {
            WaypointIsland cell = row.cells().get(column);
            UiBounds bounds = areaCell(top, column);
            boolean on = row.group().onIsland(cell.id());
            if (bounds.contains(mx, my)) graphics.fill(bounds.x(), bounds.y(), bounds.right() - 4, bounds.bottom(), THEME.controlHover());
            checkbox(graphics, on, new UiBounds(bounds.x() + 4, bounds.y() + 4, 12, 12));
            graphics.text(menuFont, menuFont.plainSubstrByWidth(cell.name(), bounds.width() - 30), bounds.x() + 22, bounds.y() + 6,
                on ? THEME.accent() : THEME.text(), false);
        }
    }

    /** The rows of the dragged waypoint's own group in the list as drawn: its group row up to the next group row. */
    private int[] sourceSpan(List<Row> list) {
        int from = -1;
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index) instanceof GroupRow group && group.group() == pressed.group()) {
                from = index;
                break;
            }
        }
        if (from < 0) return null;
        int to = list.size() - 1;
        for (int index = from + 1; index < list.size(); index++) {
            if (list.get(index) instanceof GroupRow) {
                to = index - 1;
                break;
            }
        }
        return new int[] {from, to};
    }

    /** One row's part of the outline around the group a waypoint is dragged out of. */
    private void outlineSource(GuiGraphicsExtractor graphics, int top, boolean first, boolean last) {
        int color = THEME.accent();
        graphics.fill(left() - 2, top, left(), top + ROW_HEIGHT, color);
        graphics.fill(right(), top, right() + 2, top + ROW_HEIGHT, color);
        if (first) graphics.fill(left() - 2, top, right() + 2, top + 2, color);
        if (last) graphics.fill(left() - 2, top + ROW_HEIGHT - 2, right() + 2, top + ROW_HEIGHT, color);
    }

    private void drawGap(GuiGraphicsExtractor graphics, int top) {
        int fill = THEME.accent() & 0xFFFFFF | 0x50000000;
        graphics.fill(left(), top + 1, right(), top + ROW_HEIGHT - 1, fill);
        graphics.fill(left(), top + 1, right(), top + 2, THEME.accent());
        graphics.fill(left(), top + ROW_HEIGHT - 2, right(), top + ROW_HEIGHT - 1, THEME.accent());
    }

    private void drawWaypoint(GuiGraphicsExtractor graphics, WaypointRow row, int top, boolean hovered, int mx, int my,
                              float partialTick) {
        WaypointGroup group = row.group();
        if (row.index() >= group.waypoints().size()) return;
        Waypoint waypoint = group.waypoints().get(row.index());
        checkbox(graphics, sharing ? isSelected(group, row.index()) : waypoint.enabled(), waypointCheckBounds(top));
        if (sharing) {
            graphics.text(menuFont, waypoint.name(), left() + 58, top + 8, THEME.text(), false);
            graphics.text(menuFont, waypoint.x() + " " + waypoint.y() + " " + waypoint.z(), left() + 240, top + 8,
                THEME.muted(), false);
            return;
        }
        if (hovered) {
            UiButton.draw(graphics, menuFont, THEME, upBounds(top), "^", mx, my, row.index() > 0, THEME.text());
            UiButton.draw(graphics, menuFont, THEME, downBounds(top), "v", mx, my, row.index() < group.waypoints().size() - 1, THEME.text());
        }
        drawGrip(graphics, gripBounds(top), gripBounds(top).contains(mx, my));
        row.name().extractRenderState(graphics, mx, my, partialTick);
        row.x().extractRenderState(graphics, mx, my, partialTick);
        row.y().extractRenderState(graphics, mx, my, partialTick);
        row.z().extractRenderState(graphics, mx, my, partialTick);
        UiBounds swatch = swatchBounds(top);
        graphics.fill(swatch.x(), swatch.y(), swatch.right(), swatch.bottom(), swatch.contains(mx, my) ? THEME.text() : THEME.border());
        graphics.fill(swatch.x() + 1, swatch.y() + 1, swatch.right() - 1, swatch.bottom() - 1, 0xFF000000 | waypoint.rgb());
        row.hex().extractRenderState(graphics, mx, my, partialTick);
        row.alpha().extractRenderState(graphics, mx, my, partialTick);
        UiButton.draw(graphics, menuFont, THEME, deleteBounds(top), "X", mx, my, true, THEME.error());
    }

    private void drawGrip(GuiGraphicsExtractor graphics, UiBounds bounds, boolean lit) {
        int color = lit ? THEME.accent() : THEME.muted();
        for (int bar = 0; bar < 3; bar++) {
            graphics.fill(bounds.x() + 2, bounds.y() + 4 + bar * 3, bounds.right() - 2, bounds.y() + 5 + bar * 3, color);
        }
    }

    /** The row in the air, following the pointer. */
    private void drawDragged(GuiGraphicsExtractor graphics) {
        WaypointGroup group = pressed.group();
        if (pressed.index() >= group.waypoints().size()) return;
        Waypoint waypoint = group.waypoints().get(pressed.index());
        int top = Math.clamp(dragY - ROW_HEIGHT / 2, y + 4, y + PANEL_HEIGHT - ROW_HEIGHT - 4);
        graphics.fill(left() - 1, top, right() + 1, top + ROW_HEIGHT, THEME.accent());
        graphics.fill(left(), top + 1, right(), top + ROW_HEIGHT - 1, THEME.control());
        drawGrip(graphics, gripBounds(top), true);
        checkbox(graphics, waypoint.enabled(), waypointCheckBounds(top));
        graphics.text(menuFont, menuFont.plainSubstrByWidth(waypoint.name(), 140), left() + 58, top + 8, THEME.text(), false);
        graphics.text(menuFont, waypoint.x() + " " + waypoint.y() + " " + waypoint.z(), left() + 204, top + 8, THEME.muted(), false);
        UiBounds swatch = swatchBounds(top);
        graphics.fill(swatch.x(), swatch.y() + 2, swatch.right(), swatch.bottom() - 2, 0xFF000000 | waypoint.rgb());
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int mx, int my) {
        int top = footerTop();
        graphics.text(menuFont, hint(mx, my).isEmpty() ? status : hint(mx, my), left(), top,
            hint(mx, my).isEmpty() ? (statusIsError ? THEME.error() : THEME.success()) : THEME.muted(), false);
        if (sharing) {
            checkbox(graphics, overrideIsland, new UiBounds(left(), optionBounds(0).y() + 2, 12, 12));
            graphics.text(menuFont, "Import to " + island.name(), left() + 18, optionBounds(0).y() + 4, THEME.text(), false);
            checkbox(graphics, sortImport, new UiBounds(left() + 200, optionBounds(1).y() + 2, 12, 12));
            graphics.text(menuFont, "Sort imported waypoints", left() + 218, optionBounds(1).y() + 4, THEME.text(), false);
            String[] labels = {"Import Skyblocker", "Export Skyblocker", "Import Skytils", "Import Coleweight", "Done"};
            for (int i = 0; i < labels.length; i++) {
                UiButton.draw(graphics, menuFont, THEME, footerButton(i, labels.length), labels[i], mx, my, true,
                    i == labels.length - 1 ? THEME.accent() : THEME.text());
            }
        } else {
            String[] labels = footerLabels();
            for (int i = 0; i < labels.length; i++) {
                UiButton.draw(graphics, menuFont, THEME, footerButton(i, labels.length), labels[i], mx, my, true,
                    i == labels.length - 1 ? THEME.accent() : THEME.text());
            }
        }
    }

    private String[] footerLabels() {
        return KungConfig.get().waypoints.autoSave() ? AUTO_LABELS : MANUAL_LABELS;
    }

    /** What the control under the pointer does, shown in the footer in place of the last message. */
    private String hint(int mx, int my) {
        int slot = rowSlot(my);
        if (slot < 0 || sharing || dragging) return "";
        int top = listTop() + slot * ROW_HEIGHT;
        if (mx < left() || mx >= right()) return "";
        Row row = view().get(scroll.offset() + slot);
        if (row instanceof GroupRow && !areaMode()) {
            if (markBounds(top).contains(mx, my)) return "Marks the group: the Add Waypoint Key puts a new waypoint in every marked group.";
            if (routeBounds(top).contains(mx, my)) return "Route: shows the waypoints one after the other and moves on as you walk up to them.";
            if (wallsBounds(top).contains(mx, my)) return "Walls: draws the waypoints through blocks.";
            if (wallStyleBounds(top).contains(mx, my)) return "What Walls shows through blocks: a small icon, or the waypoint itself. Click to change.";
            if (typeBounds(top).contains(mx, my)) return "How the waypoints of this group are drawn. Click to change.";
            if (groupCheckBounds(top).contains(mx, my)) return "Switches every waypoint of the group on or off.";
        } else if (row instanceof WaypointRow) {
            if (gripBounds(top).contains(mx, my)) return "Drag to move the waypoint, also into another group.";
            if (swatchBounds(top).contains(mx, my)) return "Opens the color picker.";
        }
        return "";
    }

    private void checkbox(GuiGraphicsExtractor graphics, boolean checked, UiBounds bounds) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), THEME.border());
        graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, THEME.control());
        if (checked) graphics.fill(bounds.x() + 3, bounds.y() + 3, bounds.right() - 3, bounds.bottom() - 3, THEME.accent());
    }

    // endregion

    // region Share selection

    private boolean isSelected(WaypointGroup group, int index) {
        BitSet bits = selected.get(group);
        return bits != null && bits.get(index);
    }

    private boolean allSelected(WaypointGroup group) {
        BitSet bits = selected.get(group);
        return !group.waypoints().isEmpty() && bits != null && bits.cardinality() == group.waypoints().size();
    }

    private void select(WaypointGroup group, int index, boolean on) {
        selected.computeIfAbsent(group, key -> new BitSet()).set(index, on);
    }

    private void selectAll(WaypointGroup group, boolean on) {
        for (int index = 0; index < group.waypoints().size(); index++) select(group, index, on);
    }

    // endregion

    // region Dragging

    /**
     * Puts the dragged row's slot under the pointer: the list without that row, plus an open gap where the pointer is.
     * With {@code scrolling} the list also scrolls while the pointer rests at its top or bottom edge.
     */
    private void updateDrag(int mx, int my, boolean scrolling) {
        dragX = mx;
        dragY = my;
        if (scrolling) scrollAtEdge(my);
        List<Row> without = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (row != pressed) without.add(row);
        }
        // Row 0 is always a group, so a waypoint never lands above the first group.
        gapIndex = Math.clamp((long) scroll.offset() + Math.floorDiv(my - listTop(), ROW_HEIGHT), 1, without.size());
        without.add(gapIndex, GapRow.INSTANCE);
        dragView = without;
        scroll.setOffsetWithinMax(scroll.offset(), UiScrollList.maxOffset(without.size(), VISIBLE_ROWS));
    }

    /** Scrolls faster the closer the pointer is to the edge, and keeps going past it. */
    private void scrollAtEdge(int my) {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastDragNanos) / 1.0E9);
        lastDragNanos = now;
        int top = listTop();
        int bottom = top + VISIBLE_ROWS * ROW_HEIGHT;
        double speed = 0;
        if (my < top + EDGE_ZONE) speed = -Math.min(1.0, (top + EDGE_ZONE - my) / (double) EDGE_ZONE) * EDGE_ROWS_PER_SECOND;
        else if (my > bottom - EDGE_ZONE) speed = Math.min(1.0, (my - (bottom - EDGE_ZONE)) / (double) EDGE_ZONE) * EDGE_ROWS_PER_SECOND;
        edgeCarry = speed == 0 ? 0 : edgeCarry + speed * seconds;
        int step = (int) edgeCarry;
        if (step != 0) {
            edgeCarry -= step;
            scroll.scrollByWithinMax(step, UiScrollList.maxOffset(rows.size(), VISIBLE_ROWS));
        }
    }

    private void startDrag() {
        dragging = true;
        focus(null);
        lastDragNanos = System.nanoTime();
        edgeCarry = 0;
    }

    private void cancelDrag() {
        pressed = null;
        dragging = false;
        dragView = null;
    }

    /** Moves the dragged waypoint to the gap: after the row above it, or first in the group when that row is a group. */
    private void drop() {
        if (dragView == null || gapIndex < 1 || gapIndex > dragView.size()) return;
        Row above = dragView.get(gapIndex - 1);
        WaypointGroup from = pressed.group();
        int fromIndex = pressed.index();
        WaypointGroup to;
        int at;
        if (above instanceof GroupRow groupRow) {
            to = groupRow.group();
            at = collapsed.contains(to) ? Integer.MAX_VALUE : 0;
        } else if (above instanceof WaypointRow waypointRow) {
            to = waypointRow.group();
            // Taking the dragged waypoint out first moves the ones behind it in its own group one place up.
            at = to == from && waypointRow.index() > fromIndex ? waypointRow.index() : waypointRow.index() + 1;
        } else {
            return;
        }
        if (fromIndex >= from.waypoints().size()) return;
        Waypoint moved = from.waypoints().remove(fromIndex);
        to.waypoints().add(Math.min(at, to.waypoints().size()), moved);
        // The share ticks are by position, so a move would leave them on the wrong waypoints.
        selected.remove(from);
        selected.remove(to);
        collapsed.remove(to);
        lastGroup = to;
        rebuild();
    }

    // endregion

    // region Input

    private int rowSlot(int my) {
        if (my < listTop() || my >= listTop() + VISIBLE_ROWS * ROW_HEIGHT) return -1;
        int slot = (my - listTop()) / ROW_HEIGHT;
        return scroll.offset() + slot < view().size() ? slot : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        layout();
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        int mouseButton = McInput.button(event);
        // A press with no release before it (the release went to another window) must not leave the editor stuck.
        cancelDrag();
        if (dropdownOpen) {
            handleDropdownClick(mx, my);
            return true;
        }
        if (mouseButton == 0 && handleChromeClick(mx, my)) return true;
        int slot = rowSlot(my);
        if (slot >= 0 && mx >= left() && mx < right()) {
            Row row = view().get(scroll.offset() + slot);
            if (row instanceof GroupRow group) lastGroup = group.group();
            else if (row instanceof WaypointRow waypoint) lastGroup = waypoint.group();
            else if (row instanceof AreaRow areas) lastGroup = areas.group();
            int top = listTop() + slot * ROW_HEIGHT;
            if (mouseButton == 0 && handleRowClick(row, top, mx, my, doubleClick)) {
                // A button or box was clicked, so typing no longer goes to the field that was open.
                focus(null);
                return true;
            }
            if (!sharing) {
                for (UiTextField field : fieldsOf(row)) {
                    if (field.contains(mx, my)) {
                        focus(field);
                        field.mouseClicked(new MouseButtonEvent(mx, my, event.buttonInfo()), doubleClick);
                        return true;
                    }
                }
            }
        }
        focus(null);
        return true;
    }

    private void handleDropdownClick(int mx, int my) {
        List<WaypointIsland> choices = islandChoices();
        for (int slot = 0; slot < Math.min(DROPDOWN_ROWS, choices.size()); slot++) {
            int index = dropdownScroll.offset() + slot;
            if (index < choices.size() && dropdownEntry(slot).contains(mx, my)) {
                selectIsland(choices.get(index));
                return;
            }
        }
        dropdownOpen = false;
    }

    /** The header and footer controls. */
    private boolean handleChromeClick(int mx, int my) {
        if (dropdownBounds().contains(mx, my)) {
            dropdownOpen = true;
            dropdownScroll.reset();
            focus(null);
            return true;
        }
        if (!sharing && settingsBounds().contains(mx, my)) {
            McCompat.setScreen(minecraft, KungConfigScreen.fromParent(this, "Waypoints"));
            return true;
        }
        if (!sharing && showAllBounds().contains(mx, my)) {
            toggleShowAll();
            return true;
        }
        if (!sharing && autoSaveBounds().contains(mx, my)) {
            KungConfig.get().waypoints.setAutoSave(!KungConfig.get().waypoints.autoSave());
            return true;
        }
        if (!KungConfig.get().waypoints.enabled() && warningBounds().contains(mx, my)) {
            KungConfig.get().waypoints.setEnabled(true);
            return true;
        }
        if (sharing) return handleShareFooterClick(mx, my);
        String[] labels = footerLabels();
        for (int i = 0; i < labels.length; i++) {
            if (!footerButton(i, labels.length).contains(mx, my)) continue;
            switch (labels[i]) {
                case "Share" -> { sharing = true; focus(null); status = ""; rebuild(); }
                case "New Group" -> newGroup();
                case "Cancel" -> onClose();
                default -> done();
            }
            return true;
        }
        return false;
    }

    private boolean handleShareFooterClick(int mx, int my) {
        if (optionBounds(0).contains(mx, my)) { overrideIsland = !overrideIsland; return true; }
        if (optionBounds(1).contains(mx, my)) { sortImport = !sortImport; return true; }
        for (int i = 0; i < 5; i++) {
            if (!footerButton(i, 5).contains(mx, my)) continue;
            switch (i) {
                case 0 -> importFrom("Skyblocker", WaypointShare::decode);
                case 1 -> export();
                case 2 -> importFrom("Skytils", text -> WaypointShare.decodeSkytils(text, island));
                case 3 -> importFrom("Coleweight", text -> WaypointShare.decodeColeweight(text, island));
                default -> { sharing = false; status = ""; rebuild(); }
            }
            return true;
        }
        return false;
    }

    /**
     * A click on a row's own controls. A double click never deletes a group: the rows move up after the first
     * delete, so the second click could land on the next group's X. A waypoint delete takes every click, so clicking X
     * fast clears a run of waypoints (Minecraft calls every click within 250 ms of the last one a double click).
     */
    private boolean handleRowClick(Row row, int top, int mx, int my, boolean doubleClick) {
        if (row instanceof AreaRow areas) {
            for (int column = 0; column < areas.cells().size(); column++) {
                if (!areaCell(top, column).contains(mx, my)) continue;
                String id = areas.cells().get(column).id();
                areas.group().setOnIsland(id, !areas.group().onIsland(id));
                return true;
            }
            return false;
        }
        if (row instanceof GroupRow groupRow) return handleGroupClick(groupRow.group(), top, mx, my, doubleClick);
        if (!(row instanceof WaypointRow waypointRow)) return false;
        WaypointGroup group = waypointRow.group();
        int index = waypointRow.index();
        if (waypointCheckBounds(top).contains(mx, my)) {
            if (sharing) select(group, index, !isSelected(group, index));
            else replace(group, index, waypoint -> waypoint.withEnabled(!waypoint.enabled()));
            return true;
        }
        if (sharing) return false;
        if (gripBounds(top).contains(mx, my)) {
            pressed = waypointRow;
            pressX = mx;
            pressY = my;
            dragX = mx;
            dragY = my;
            return true;
        }
        if (upBounds(top).contains(mx, my)) { moveWaypoint(group, index, -1); return true; }
        if (downBounds(top).contains(mx, my)) { moveWaypoint(group, index, 1); return true; }
        if (deleteBounds(top).contains(mx, my)) {
            deleteWaypoint(group, index);
            return true;
        }
        if (swatchBounds(top).contains(mx, my)) {
            Waypoint waypoint = group.waypoints().get(index);
            McCompat.setScreen(minecraft, new HitboxEditorScreen(this, "Waypoint Color", 0xFF000000 | waypoint.rgb(),
                color -> replace(group, index, w -> w.withColor(color & 0xFFFFFF, w.alpha()))));
            return true;
        }
        return false;
    }

    private boolean handleGroupClick(WaypointGroup group, int top, int mx, int my, boolean doubleClick) {
        if (arrowBounds(top).contains(mx, my)) {
            Set<WaypointGroup> open = areaMode() ? areasOpen : collapsed;
            if (!open.remove(group)) open.add(group);
            rebuild();
            return true;
        }
        if (groupCheckBounds(top).contains(mx, my)) {
            if (sharing) selectAll(group, !allSelected(group));
            else {
                boolean enable = !group.fullyEnabled();
                group.waypoints().replaceAll(waypoint -> waypoint.withEnabled(enable));
            }
            return true;
        }
        if (sharing) return false;
        if (!areaMode()) {
            if (markBounds(top).contains(mx, my)) { group.setMarked(!group.marked()); return true; }
            if (routeBounds(top).contains(mx, my)) { group.setOrdered(!group.ordered()); return true; }
            if (wallsBounds(top).contains(mx, my)) { group.setThroughWalls(!group.throughWalls()); return true; }
            if (wallStyleBounds(top).contains(mx, my)) { group.setWallStyle(group.wallStyle().next()); return true; }
            if (typeBounds(top).contains(mx, my)) {
                WaypointType[] types = WaypointType.values();
                group.setType(types[(group.type().ordinal() + 1) % types.length]);
                return true;
            }
            if (addBounds(top).contains(mx, my)) { newWaypoint(group); return true; }
        }
        if (deleteBounds(top).contains(mx, my)) {
            if (doubleClick) return true;
            groups.remove(group);
            selected.remove(group);
            collapsed.remove(group);
            areasOpen.remove(group);
            rebuild();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressed != null) {
            int mx = coordinate(event.x());
            int my = coordinate(event.y());
            if (!dragging && Math.hypot(mx - pressX, my - pressY) >= DRAG_START) startDrag();
            this.dragX = mx;
            this.dragY = my;
            return true;
        }
        if (focused == null) return false;
        return focused.mouseDragged(new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()),
            dragX / scale(), dragY / scale());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressed != null) {
            if (dragging) {
                updateDrag(coordinate(event.x()), coordinate(event.y()), false);
                drop();
            }
            cancelDrag();
            return true;
        }
        if (focused != null) focused.mouseReleased(new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        int step = scrollY < 0 ? 1 : -1;
        if (dropdownOpen) {
            dropdownScroll.scrollByWithinMax(step, UiScrollList.maxOffset(islandChoices().size(), DROPDOWN_ROWS));
            return true;
        }
        focus(null);
        scroll.scrollByWithinMax(step, UiScrollList.maxOffset(view().size(), VISIBLE_ROWS));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = McInput.key(event);
        if (key == KeyCodes.ESCAPE) {
            if (pressed != null) cancelDrag();
            else if (dropdownOpen) dropdownOpen = false;
            else onClose();
            return true;
        }
        if (focused != null) {
            if (key == KeyCodes.ENTER || key == KeyCodes.KP_ENTER) focus(null);
            else focused.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return focused != null && focused.charTyped(event);
    }

    // endregion

    // region Closing and sharing

    /**
     * Esc and Cancel: leave the share view first, then close. Nothing is asked: with Auto Save the changes are already
     * written (a refused write keeps the editor open), without it they are discarded, as the Cancel label says.
     */
    @Override
    public void onClose() {
        if (sharing) {
            sharing = false;
            status = "";
            rebuild();
            return;
        }
        if (!autoSave()) return;
        McCompat.setScreen(minecraft, parent);
    }

    private void done() {
        if (store.replaceAll(groups)) {
            savedState = WaypointStore.fingerprint(groups);
            McCompat.setScreen(minecraft, parent);
        } else {
            say("Not saved: " + store.saveError(), true);
        }
    }

    private void say(String message, boolean error) {
        status = message;
        statusIsError = error;
    }

    private void export() {
        List<WaypointGroup> picked = new ArrayList<>();
        int count = 0;
        for (WaypointGroup group : islandGroups()) {
            BitSet bits = selected.get(group);
            if (bits == null || bits.isEmpty()) continue;
            WaypointGroup copy = group.copy();
            List<Waypoint> chosen = new ArrayList<>();
            for (int index = 0; index < group.waypoints().size(); index++) if (bits.get(index)) chosen.add(group.waypoints().get(index));
            copy.waypoints().clear();
            copy.waypoints().addAll(chosen);
            picked.add(copy);
            count += chosen.size();
        }
        if (picked.isEmpty()) {
            say("Nothing is ticked on " + island.name() + ".", true);
            return;
        }
        minecraft.keyboardHandler.setClipboard(WaypointShare.encode(picked));
        say("Copied " + count + " waypoints in " + picked.size() + " groups to the clipboard.", false);
    }

    private void importFrom(String format, java.util.function.Function<String, WaypointShare.Imported> reader) {
        try {
            WaypointShare.Imported imported = reader.apply(minecraft.keyboardHandler.getClipboard());
            int waypoints = 0;
            int elsewhere = 0;
            for (WaypointGroup group : imported.groups()) {
                if (overrideIsland) group.setIslandId(island.id());
                if (sortImport) group.waypoints().sort(Waypoint.NAME_ORDER);
                groups.add(group);
                selectAll(group, true);
                waypoints += group.waypoints().size();
                if (!group.listedOn(island.id())) elsewhere++;
            }
            rebuild();
            say("Imported " + waypoints + " " + format + " waypoints in " + imported.groups().size() + " groups."
                + (elsewhere > 0 ? " " + elsewhere + " belong to other islands: find them in the island menu." : "")
                + (imported.skipped() > 0 ? " " + imported.skipped() + " entries were left out." : ""), false);
        } catch (IllegalArgumentException exception) {
            say(exception.getMessage(), true);
        }
    }

    // endregion
}
