package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.feature.waypoints.Waypoint;
import com.github.beng420.kung.feature.waypoints.WaypointGroup;
import com.github.beng420.kung.feature.waypoints.WaypointIsland;
import com.github.beng420.kung.feature.waypoints.WaypointShare;
import com.github.beng420.kung.feature.waypoints.WaypointStore;
import com.github.beng420.kung.feature.waypoints.WaypointType;
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
import net.minecraft.client.gui.screens.ConfirmScreen;
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
 * island as rows, each with its waypoints below it. A group row has its enabled box, name, Route, Walls and type
 * controls and the New Waypoint and delete buttons; a waypoint row has its enabled box, name, X, Y, Z, color and opacity.
 * Changes are made on a copy of every group and written only by Done. The Share button turns the same list into
 * a picker for exporting, and its footer imports Skyblocker, Skytils and Coleweight text from the clipboard.
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
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{0,9}");
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{0,6}");
    private static final Pattern COMPLETE_HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final Pattern PERCENT = Pattern.compile("[0-9]{0,3}");

    private final Screen parent;
    private final WaypointStore store = WaypointStore.INSTANCE;
    private final List<WaypointGroup> groups = store.copyOfGroups();
    private final String savedState = WaypointStore.fingerprint(groups);
    private final Set<WaypointGroup> collapsed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<WaypointGroup, BitSet> selected = new IdentityHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final UiScrollList scroll = new UiScrollList();
    private final UiScrollList dropdownScroll = new UiScrollList();
    private WaypointIsland island = WaypointsFeature.INSTANCE.island();
    private WaypointGroup lastGroup;
    private UiTextField focused;
    private Font menuFont;
    private boolean dropdownOpen;
    private boolean sharing;
    private boolean overrideIsland;
    private boolean sortImport;
    private boolean statusIsError;
    private String status = "";
    private int x;
    private int y;

    private sealed interface Row permits GroupRow, WaypointRow { }

    private record GroupRow(WaypointGroup group, UiTextField name) implements Row { }

    private record WaypointRow(WaypointGroup group, int index, UiTextField name, UiTextField x, UiTextField y,
                               UiTextField z, UiTextField hex, UiTextField alpha) implements Row { }

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
    @Override public void removed() { focus(null); }

    // region Model

    private List<WaypointGroup> islandGroups() {
        return groups.stream().filter(group -> group.islandId().equals(island.id())).toList();
    }

    private void collapseAll() {
        collapsed.addAll(islandGroups());
    }

    private void selectIsland(WaypointIsland next) {
        island = next;
        collapsed.clear();
        collapseAll();
        scroll.reset();
        lastGroup = null;
        dropdownOpen = false;
        rebuild();
    }

    private List<WaypointIsland> islandChoices() {
        List<WaypointIsland> choices = new ArrayList<>(WaypointIsland.all());
        for (WaypointGroup group : groups) {
            WaypointIsland known = WaypointIsland.fromId(group.islandId());
            if (!known.isUnknown() && choices.stream().noneMatch(choice -> choice.id().equals(known.id()))) choices.add(known);
        }
        choices.add(WaypointIsland.UNKNOWN);
        if (choices.stream().noneMatch(choice -> choice.id().equals(island.id()))) choices.add(island);
        return choices;
    }

    private int groupCount(WaypointIsland choice) {
        return (int) groups.stream().filter(group -> group.islandId().equals(choice.id())).count();
    }

    private boolean changed() {
        return !WaypointStore.fingerprint(groups).equals(savedState);
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
        WaypointGroup group = new WaypointGroup("New Group", island.id(), KungConfig.get().waypoints.defaultType());
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
        rows.clear();
        for (WaypointGroup group : islandGroups()) {
            rows.add(new GroupRow(group, field(group.name(), 64, null, group::setName, "Group name", "Name")));
            if (collapsed.contains(group)) continue;
            for (int index = 0; index < group.waypoints().size(); index++) rows.add(waypointRow(group, index));
        }
        scroll.setOffsetWithinMax(scroll.offset(), UiScrollList.maxOffset(rows.size(), VISIBLE_ROWS));
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
        return row instanceof GroupRow group ? List.of(group.name())
            : List.of(((WaypointRow) row).name(), ((WaypointRow) row).x(), ((WaypointRow) row).y(), ((WaypointRow) row).z(),
                ((WaypointRow) row).hex(), ((WaypointRow) row).alpha());
    }

    // endregion

    // region Layout

    private float scale() {
        return UiScale.menu(Math.min(0.8F, Math.min(width / (PANEL_WIDTH + 20F), height / (PANEL_HEIGHT + 20F))));
    }

    private int coordinate(double value) { return (int) Math.floor(value / scale()); }

    private void layout() {
        x = (coordinate(width) - PANEL_WIDTH) / 2;
        y = (coordinate(height) - PANEL_HEIGHT) / 2;
        int first = scroll.offset();
        for (int slot = 0; slot < VISIBLE_ROWS && first + slot < rows.size(); slot++) {
            Row row = rows.get(first + slot);
            int top = listTop() + slot * ROW_HEIGHT + 2;
            if (row instanceof GroupRow group) {
                group.name().setBounds(left() + 36, top, 130, 20);
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
    private UiBounds dropdownBounds() { return new UiBounds(x + PANEL_WIDTH - 14 - 170, y + 8, 170, 20); }
    private UiBounds warningBounds() { return new UiBounds(left(), y + 34, ROW_WIDTH, 16); }
    private UiBounds arrowBounds(int top) { return new UiBounds(left(), top + 4, 14, 16); }
    private UiBounds groupCheckBounds(int top) { return new UiBounds(left() + 18, top + 6, 12, 12); }
    private UiBounds routeBounds(int top) { return new UiBounds(left() + 172, top + 2, 44, 20); }
    private UiBounds wallsBounds(int top) { return new UiBounds(left() + 220, top + 2, 44, 20); }
    private UiBounds typeBounds(int top) { return new UiBounds(left() + 268, top + 2, 112, 20); }
    private UiBounds addBounds(int top) { return new UiBounds(right() - 108, top + 2, 84, 20); }
    private UiBounds deleteBounds(int top) { return new UiBounds(right() - 20, top + 2, 20, 20); }
    private UiBounds upBounds(int top) { return new UiBounds(left() + 8, top + 2, 12, 10); }
    private UiBounds downBounds(int top) { return new UiBounds(left() + 8, top + 12, 12, 10); }
    private UiBounds waypointCheckBounds(int top) { return new UiBounds(left() + 38, top + 6, 12, 12); }
    private UiBounds swatchBounds(int top) { return new UiBounds(left() + 346, top + 2, 20, 20); }
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
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale(), scale());
            graphics.fill(0, 0, coordinate(width), coordinate(height), THEME.backdrop());
            UiShapes.shadow(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5);
            UiShapes.rounded(graphics, x - 1, y - 1, PANEL_WIDTH + 2, PANEL_HEIGHT + 2, 6, THEME.accent());
            UiShapes.rounded(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5, THEME.panel());
            graphics.text(menuFont, sharing ? "Share Waypoints" : "Waypoints", x + 14, y + 14, THEME.accent(), false);
            if (!sharing) UiButton.draw(graphics, menuFont, THEME, settingsBounds(), "Settings", mx, my, true, THEME.text());
            drawDropdownButton(graphics, mx, my);
            drawWarning(graphics, mx, my);
            drawRows(graphics, mx, my, partialTick);
            drawFooter(graphics, mx, my);
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
            graphics.text(menuFont, sharing ? "Tick what to export, or import from the clipboard."
                : "Click > to open a group. Done saves, Cancel discards.", bounds.x() + 2, bounds.y() + 4, THEME.muted(), false);
        }
    }

    private void drawDropdownButton(GuiGraphicsExtractor graphics, int mx, int my) {
        UiBounds bounds = dropdownBounds();
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
            bounds.contains(mx, my) || dropdownOpen ? THEME.controlHover() : THEME.control());
        graphics.text(menuFont, menuFont.plainSubstrByWidth(island.name(), bounds.width() - 24), bounds.x() + 6, bounds.y() + 6,
            THEME.text(), false);
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
            boolean current = choice.id().equals(island.id());
            graphics.text(menuFont, choice.name() + (count > 0 ? "  (" + count + ")" : ""), bounds.x() + 6, bounds.y() + 5,
                current ? THEME.accent() : count > 0 ? THEME.text() : THEME.muted(), false);
        }
    }

    private void drawRows(GuiGraphicsExtractor graphics, int mx, int my, float partialTick) {
        int first = scroll.offset();
        if (rows.isEmpty()) {
            graphics.text(menuFont, "No waypoint groups on " + island.name() + ". New Group makes one.", left() + 2,
                listTop() + 8, THEME.muted(), false);
        }
        for (int slot = 0; slot < VISIBLE_ROWS && first + slot < rows.size(); slot++) {
            Row row = rows.get(first + slot);
            int top = listTop() + slot * ROW_HEIGHT;
            boolean hovered = my >= top && my < top + ROW_HEIGHT && mx >= left() && mx < right();
            if (row instanceof GroupRow group) drawGroup(graphics, group, top, mx, my, partialTick);
            else drawWaypoint(graphics, (WaypointRow) row, top, hovered, mx, my, partialTick);
        }
        int maxOffset = UiScrollList.maxOffset(rows.size(), VISIBLE_ROWS);
        if (maxOffset > 0) {
            int track = VISIBLE_ROWS * ROW_HEIGHT;
            int thumb = Math.max(16, track * VISIBLE_ROWS / rows.size());
            int thumbTop = listTop() + (track - thumb) * scroll.offset() / maxOffset;
            graphics.fill(x + PANEL_WIDTH - 10, listTop(), x + PANEL_WIDTH - 6, listTop() + track, THEME.panelDark());
            graphics.fill(x + PANEL_WIDTH - 10, thumbTop, x + PANEL_WIDTH - 6, thumbTop + thumb, THEME.accent());
        }
    }

    private void drawGroup(GuiGraphicsExtractor graphics, GroupRow row, int top, int mx, int my, float partialTick) {
        WaypointGroup group = row.group();
        graphics.fill(left(), top + 1, right(), top + ROW_HEIGHT - 1, THEME.panelSoft());
        UiButton.draw(graphics, menuFont, THEME, arrowBounds(top), collapsed.contains(group) ? ">" : "v", mx, my, true, THEME.text());
        checkbox(graphics, group.fullyEnabled() && !sharing || sharing && allSelected(group), groupCheckBounds(top));
        if (sharing) {
            graphics.text(menuFont, group.name(), left() + 38, top + 8, THEME.text(), false);
            graphics.text(menuFont, (group.ordered() ? "Route, " : "") + group.type().label() + ", "
                + group.waypoints().size() + " waypoints", left() + 172, top + 8, THEME.muted(), false);
            return;
        }
        row.name().extractRenderState(graphics, mx, my, partialTick);
        UiButton.draw(graphics, menuFont, THEME, routeBounds(top), "Route", mx, my, true, group.ordered() ? THEME.accent() : THEME.muted());
        UiButton.draw(graphics, menuFont, THEME, wallsBounds(top), "Walls", mx, my, true, group.throughWalls() ? THEME.accent() : THEME.muted());
        UiButton.draw(graphics, menuFont, THEME, typeBounds(top), group.type().label(), mx, my, true, THEME.text());
        UiButton.draw(graphics, menuFont, THEME, addBounds(top), "+ Waypoint", mx, my, true, THEME.success());
        UiButton.draw(graphics, menuFont, THEME, deleteBounds(top), "X", mx, my, true, THEME.error());
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
            String[] labels = {"Share", "New Group", "Cancel", "Done"};
            for (int i = 0; i < labels.length; i++) {
                UiButton.draw(graphics, menuFont, THEME, footerButton(i, labels.length), labels[i], mx, my, true,
                    i == labels.length - 1 ? THEME.accent() : THEME.text());
            }
        }
    }

    /** What the control under the pointer does, shown in the footer in place of the last message. */
    private String hint(int mx, int my) {
        int slot = rowSlot(my);
        if (slot < 0 || sharing) return "";
        int top = listTop() + slot * ROW_HEIGHT;
        if (mx < left() || mx >= right()) return "";
        if (rows.get(scroll.offset() + slot) instanceof GroupRow) {
            if (routeBounds(top).contains(mx, my)) return "Route: shows the waypoints one after the other and moves on as you walk up to them.";
            if (wallsBounds(top).contains(mx, my)) return "Walls: draws the waypoints through blocks.";
            if (typeBounds(top).contains(mx, my)) return "How the waypoints of this group are drawn. Click to change.";
            if (groupCheckBounds(top).contains(mx, my)) return "Switches every waypoint of the group on or off.";
        } else if (swatchBounds(top).contains(mx, my)) {
            return "Opens the color picker.";
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

    // region Input

    private int rowSlot(int my) {
        if (my < listTop() || my >= listTop() + VISIBLE_ROWS * ROW_HEIGHT) return -1;
        int slot = (my - listTop()) / ROW_HEIGHT;
        return scroll.offset() + slot < rows.size() ? slot : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        layout();
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        int mouseButton = McInput.button(event);
        if (dropdownOpen) {
            handleDropdownClick(mx, my);
            return true;
        }
        if (mouseButton == 0 && handleChromeClick(mx, my)) return true;
        int slot = rowSlot(my);
        if (slot >= 0 && mx >= left() && mx < right()) {
            Row row = rows.get(scroll.offset() + slot);
            lastGroup = row instanceof GroupRow group ? group.group() : ((WaypointRow) row).group();
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
        if (!KungConfig.get().waypoints.enabled() && warningBounds().contains(mx, my)) {
            KungConfig.get().waypoints.setEnabled(true);
            return true;
        }
        if (sharing) return handleShareFooterClick(mx, my);
        for (int i = 0; i < 4; i++) {
            if (!footerButton(i, 4).contains(mx, my)) continue;
            switch (i) {
                case 0 -> { sharing = true; focus(null); status = ""; rebuild(); }
                case 1 -> newGroup();
                case 2 -> onClose();
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
     * A click on a row's own controls. The second click of a double click never deletes: the rows move up after the
     * first delete, so it would land on the next row's X.
     */
    private boolean handleRowClick(Row row, int top, int mx, int my, boolean doubleClick) {
        if (row instanceof GroupRow groupRow) {
            WaypointGroup group = groupRow.group();
            if (arrowBounds(top).contains(mx, my)) {
                if (!collapsed.remove(group)) collapsed.add(group);
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
            if (routeBounds(top).contains(mx, my)) { group.setOrdered(!group.ordered()); return true; }
            if (wallsBounds(top).contains(mx, my)) { group.setThroughWalls(!group.throughWalls()); return true; }
            if (typeBounds(top).contains(mx, my)) {
                WaypointType[] types = WaypointType.values();
                group.setType(types[(group.type().ordinal() + 1) % types.length]);
                return true;
            }
            if (addBounds(top).contains(mx, my)) { newWaypoint(group); return true; }
            if (deleteBounds(top).contains(mx, my)) {
                if (doubleClick) return true;
                groups.remove(group);
                selected.remove(group);
                collapsed.remove(group);
                rebuild();
                return true;
            }
            return false;
        }
        WaypointRow waypointRow = (WaypointRow) row;
        WaypointGroup group = waypointRow.group();
        int index = waypointRow.index();
        if (waypointCheckBounds(top).contains(mx, my)) {
            if (sharing) select(group, index, !isSelected(group, index));
            else replace(group, index, waypoint -> waypoint.withEnabled(!waypoint.enabled()));
            return true;
        }
        if (sharing) return false;
        if (upBounds(top).contains(mx, my)) { moveWaypoint(group, index, -1); return true; }
        if (downBounds(top).contains(mx, my)) { moveWaypoint(group, index, 1); return true; }
        if (deleteBounds(top).contains(mx, my)) {
            if (!doubleClick) deleteWaypoint(group, index);
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

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (focused == null) return false;
        return focused.mouseDragged(new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()),
            dragX / scale(), dragY / scale());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
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
        scroll.scrollByWithinMax(step, UiScrollList.maxOffset(rows.size(), VISIBLE_ROWS));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = McInput.key(event);
        if (key == KeyCodes.ESCAPE) {
            if (dropdownOpen) dropdownOpen = false;
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

    /** Esc and Cancel: leaves the share view first, then asks before throwing changes away. */
    @Override
    public void onClose() {
        if (sharing) {
            sharing = false;
            status = "";
            rebuild();
            return;
        }
        if (!changed()) {
            McCompat.setScreen(minecraft, parent);
            return;
        }
        McCompat.setScreen(minecraft, new ConfirmScreen(discard -> McCompat.setScreen(minecraft, discard ? parent : this),
            Component.literal("Discard changes?"), Component.literal("Your waypoint changes are not saved."),
            Component.literal("Discard"), Component.literal("Keep editing")));
    }

    private void done() {
        if (store.replaceAll(groups)) {
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
                if (!group.islandId().equals(island.id())) elsewhere++;
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
