package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.feature.garden.GreenhouseCosts.CostMode;
import com.github.beng420.kung.feature.garden.GreenhouseSession.Tone;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.ui.UiMenuFont;
import com.github.beng420.kung.ui.UiScale;
import com.github.beng420.kung.ui.UiScrollList;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.ui.UiTextField;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.ui.UiTooltip;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Greenhouse planner: choose mutation goals, optionally lock plot cells, solve, and read, rotate or share the
 * layout. Left column sets the problem, the middle shows the solution, the right column holds crop priorities.
 *
 * <p>Draw budget: the vanilla GUI compares each new element with those before it (about quadratic), so a frame
 * stays far below ~1,000 elements: plain fills only (a handful of rounded shapes for the panel), one cached
 * stack per icon, only visible list rows, and the strings and widths cached below. No stack or component is
 * built inside {@link #extractRenderState}. The per-section op counts are in docs/AI_HANDOFF.md.
 */
public final class GreenhouseScreen extends Screen {
    private static final UiTheme THEME = UiTheme.menu();
    private static final int EXPANDED_WIDTH = 900;
    /** The left and middle columns and a margin, equal to {@code RX}: the width with priorities collapsed. */
    private static final int COLLAPSED_WIDTH = 606;
    private static final int PANEL_HEIGHT = 520;
    /** Logical units; the panel is scaled to fit, at most this much above a GUI unit. */
    private static final float MAX_SCALE = 1.25F;
    private static final int FIELD_H = 18;

    // Left column: plot cells card, mutation search, selected targets, solve controls.
    private static final int LX = 12;
    private static final int LW = 270;
    private static final int CARD_Y = 34;
    private static final int CARD_H = 50;
    private static final int SEARCH_LABEL_Y = 92;
    private static final int SEARCH_Y = 104;
    private static final int DROP_Y = SEARCH_Y + FIELD_H;
    private static final int DROP_ROW = 18;
    private static final int DROP_ROWS = 8;
    private static final int SEL_HEAD_Y = 128;
    private static final int SEL_Y = 142;
    private static final int SEL_ROW = 20;
    private static final int SEL_ROWS = 10;
    private static final int SOLVE_Y = SEL_Y + SEL_ROWS * SEL_ROW + 10;
    /** The Prices switch sits between the Solve row and the progress bar. */
    private static final int PRICE_Y = SOLVE_Y + 28;
    private static final int PRICE_SEG_X = 44;
    private static final int PRICE_SEG_W = 112;
    private static final int BAR_Y = SOLVE_Y + 52;
    private static final int STOP_EARLY_X = 212;
    private static final int STOP_EARLY_W = 58;
    /** The hologram buttons under the progress line: Show and Hide side by side, then the clipboard one. */
    private static final int HOLO_Y = BAR_Y + 54;
    private static final int HOLO_CLIP_Y = HOLO_Y + 24;
    private static final int HOLO_H = 20;
    private static final int HOLO_W = 132;
    private static final int NAME_W = 84;
    private static final int TARGET_X = 108;
    private static final int TARGET_W = 46;
    private static final int MAX_X = 154;
    private static final int MAX_W = 30;
    private static final int COUNT_X = 188;
    private static final int COUNT_W = 54;
    /** The stacked up and down arrows at the right edge of a count cell. */
    private static final int ARROW_W = 12;
    private static final int REMOVE_X = 246;
    private static final int REMOVE_W = 16;
    private static final int TIME_LABEL_X = 94;
    private static final int TIME_FIELD_X = 150;
    private static final int TIME_FIELD_W = 44;
    /** How long a newly picked or already selected target row stays highlighted. */
    private static final long FLASH_NANOS = 1_200_000_000L;

    // Middle column: toolbar, the grid with its compass, link, status and the result lists.
    private static final int MX = 294;
    private static final int MW = 300;
    private static final int TOOL_Y = 34;
    private static final int TOOL_H = 12;
    private static final int TOOL_GAP = 3;
    private static final int TOOL_PAD = 3;
    private static final int NORTH_Y = 48;
    private static final int GRID_Y = 60;
    private static final int CELL = 22;
    private static final int GAP = 2;
    private static final int GRID_SIZE = GreenhouseGrid.SIZE * CELL + (GreenhouseGrid.SIZE - 1) * GAP;
    private static final int GRID_X = MX + (MW - GRID_SIZE) / 2;
    private static final int LINK_Y = 316;
    private static final int STATUS_Y = 339;
    private static final int RESULT_HEAD_Y = 353;
    private static final int RESULT_Y = 365;
    private static final int RESULT_ROW = 18;
    private static final int RESULT_ROWS = 5;
    private static final int HALF = 146;
    private static final int CROPS_X = MX + 156;
    private static final int SOIL_Y = 459;
    private static final int COST_Y = SOIL_Y + 11;
    private static final int NOTE_Y = COST_Y + 12;
    private static final int NOTE_LINES = 3;
    private static final int TOGGLE_W = 96;
    private static final int ARROW_UP = 0;
    private static final int ARROW_DOWN = 1;
    private static final int ARROW_RIGHT = 2;

    // Right column: crop priorities.
    private static final int RX = 606;
    private static final int RW = 282;
    private static final int PRIO_Y = 66;
    private static final int PRIO_ROW = 18;
    private static final int PRIO_ROWS = 22;
    private static final int PRIO_NAME_X = 22;
    private static final int MINUS_X = 178;
    private static final int VALUE_X = 198;
    private static final int VALUE_W = 40;
    private static final int PLUS_X = 242;
    private static final int STEP_W = 16;

    private static final List<String> PRIORITY_TIP = List.of("Crop priorities", "Higher = use that crop less.",
        "A tie-breaker after cost/difficulty:", "it only decides between layouts that cost the same.");
    private static final List<String> STOP_EARLY_TIP = List.of("Stop early",
        "Ends the search when its restarts agree;", "it may miss slightly better layouts.");
    private static final List<String> BAZAAR_TIP = List.of("Prices: Bazaar", "Minimises the coins to buy the crops.",
        "Applies to the next solve.");
    private static final List<String> IRONMAN_TIP = List.of("Prices: Ironman", "Minimises difficulty by rarity,",
        "not coins. Applies to the next solve.");
    private static final String[] TOOLS = {"Rotate left", "Rotate right", "Mirror", "Clear", "Paste", "Copy code"};
    private static final String[] TOOL_RESULTS = {"Rotated left", "Rotated right", "Mirrored", "Layout cleared"};
    /** Room kept free left and right of a button label before it counts as too long. */
    private static final int BUTTON_PAD = 3;
    private static final int KIND_TARGET_NAME = 1;
    private static final int KIND_DROP_NAME = 2;
    private static final int KIND_DROP_REQUIREMENT = 3;
    private static final int KIND_PRIORITY_NAME = 4;
    private static final int KIND_GOAL = 5;
    private static final int KIND_CROP = 6;
    private static final String CROP_CLICK_TIP = "Click: pull what is missing from your Sacks";
    private static final int KIND_STATUS = 7;
    private static final int KIND_SOIL = 8;
    private static final int KIND_COST = 9;
    private static final int KIND_PRICE = 10;
    private static final int NOTICE_Y = 32;
    private static final int NOTICE_H = 24;
    private static final int NOTICE_X_W = 16;
    private static final int NOTICE_MAX_W = 340;
    private static final int KIND_COUNT = 1;
    private static final int KIND_SECONDS = 2;
    private static final int KIND_PRIORITY = 3;

    private record GoalRow(String text, int color, String note) { }

    private record CropRow(String name, String text, int count) { }

    /** Truncated strings by source text; cleared when the font or the device scale changes. */
    private static final class Fit {
        private final int width;
        private final Map<String, String> cache = new HashMap<>();

        Fit(int width) { this.width = width; }

        String of(Font font, String text) {
            String fitted = cache.get(text);
            if (fitted == null) {
                fitted = font.plainSubstrByWidth(text, width);
                cache.put(text, fitted);
            }
            return fitted;
        }
    }

    /** A label with one number in it, rebuilt only when the number changes (not once per frame). */
    private static final class Counted {
        private final String prefix;
        private final String suffix;
        private int shown = -1;
        private String text = "";

        Counted(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
        }

        String of(int value) {
            if (value != shown) {
                shown = value;
                text = prefix + value + suffix;
            }
            return text;
        }
    }

    /** The planner outlives this screen: settings, layout, status and any running solve live in the session. */
    private final GreenhouseSession session = GreenhouseSession.get();
    private final GreenhouseState state = session.state();
    private final GreenhouseNotice notice = new GreenhouseNotice(() -> System.nanoTime() / 1_000_000L);
    private final GreenhouseGridPainter painter = new GreenhouseGridPainter(THEME);
    private final List<String> priorityCrops;
    private final UiScrollList dropdownScroll = new UiScrollList();
    private final UiScrollList targetScroll = new UiScrollList();
    private final UiScrollList goalScroll = new UiScrollList();
    private final UiScrollList cropScroll = new UiScrollList();
    private final UiScrollList priorityScroll = new UiScrollList();
    private final Fit targetName = new Fit(NAME_W);
    private final Fit dropName = new Fit(104);
    private final Fit dropRequirement = new Fit(132);
    private final Fit priorityName = new Fit(150);
    private final Fit goalText = new Fit(HALF - 6);
    private final Fit cropText = new Fit(HALF - 26);
    private final Fit statusText = new Fit(MW);
    private final Fit soilFit = new Fit(MW);
    private final Fit noticeFit = new Fit(NOTICE_MAX_W - 40);
    private final Fit costFit = new Fit(MW);
    private final Fit priceFit = new Fit(LW);
    /** Slow ping-pong scroll for labels that do not fit: always for buttons, only while hovered for list text. */
    private final GreenhouseMarquee marquee = new GreenhouseMarquee(() -> System.nanoTime() / 1_000_000L);
    private final List<Fit> fits = List.of(targetName, dropName, dropRequirement, priorityName, goalText, cropText,
        statusText, soilFit, noticeFit, costFit, priceFit);
    private final Counted cellsHead = new Counted("", " / " + GreenhouseState.CELLS + " cells");
    private final Counted targetsHead = new Counted("Selected targets (", ")");
    private final Counted cropsHead = new Counted("Crops to plant (", ")");
    private final Map<String, Integer> widths = new HashMap<>();
    private final Map<String, String> requirements = new HashMap<>();
    private final int[] searchAt = new int[4];
    private final int[] linkAt = new int[4];

    /** What the lists below were built from; they are rebuilt when the session shows something else. */
    private GreenhousePlan shownPlan;
    private GreenhouseSummary shownSummary;
    private int noticeLeft;
    private int noticeTop;
    private int noticeWidth;
    private List<GoalRow> goalRows = List.of();
    private List<CropRow> cropRows = List.of();
    private List<String> notes = List.of();
    /** More wrapped note lines exist than fit under the lists; hovering them shows all. */
    private boolean notesCut;
    private List<FormattedCharSequence> noteLines;
    private List<GreenhouseMutations.Mutation> matches = List.of();
    private String soilText = "";
    private boolean editingCells;
    private GreenhouseOrientation compassFor;
    private String[] compass = new String[4];
    private String flashName;
    private long flashUntilNanos;
    /** Toolbar button positions inside the middle column; null until measured for the font. */
    private int[] toolLeft;
    private int[] toolWidth;
    private String lastSolveText = "";
    private List<String> cellTip;
    private GreenhousePlan cellTipPlan;
    private int cellTipIndex = -1;
    private boolean cellTipOpen;

    private GreenhouseNumberEdit numberEdit;
    private int editKind;
    private int editIndex;

    private String progressText = "";
    private long progressTenths = -1;

    private GreenhouseGridLayout layoutCache;
    private int layoutX = Integer.MIN_VALUE;
    private int layoutY;
    private float layoutDevice;
    private float cachedDevice = -1F;
    private int ticks;
    private List<String> tooltip;
    private UiTextField link;
    private UiTextField search;
    private Font menuFont;
    private int x;
    private int y;

    public GreenhouseScreen() {
        super(Component.literal("Greenhouse"));
        // The first access parses the bundled recipes (a visible hitch); take it here rather than on Solve.
        Set<String> needed = new LinkedHashSet<>();
        for (GreenhouseMutations.Mutation mutation : GreenhouseMutations.supported()) {
            needed.addAll(mutation.needs().keySet());
        }
        priorityCrops = GreenhouseCrops.NAMES.stream().filter(needed::contains).toList();
        matches = GreenhouseState.matching(GreenhouseMutations.all(), "");
    }

    @Override protected void init() {
        menuFont = UiMenuFont.wrap(font);
        clearTextCaches();
        noteLines = null;
        if (link == null) {
            link = new UiTextField(menuFont, Component.literal("Sky Mutations link"))
                .maxLength(GreenhouseLayoutCodec.MAX_LINK_LENGTH)
                .hint("Paste layout code or Sky Mutations link").textShadow(false);
            search = new UiTextField(menuFont, Component.literal("Search mutations")).maxLength(40)
                .hint("Search mutations...").textShadow(false).responder(query -> {
                    matches = GreenhouseState.matching(GreenhouseMutations.all(), query);
                    dropdownScroll.reset();
                });
        }
        // Heads draw the default skin until their texture arrives, so start every download now.
        GreenhouseIcons.warm();
        session.viewerOpened();
        // Bazaar prices are fetched in the background and only when missing or old; never waited for here.
        session.refreshPrices();
        layout();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        super.tick();
        // The render cache forgets entries not drawn for five minutes; keep scrolled-away heads loaded.
        if (++ticks % 1200 == 0) GreenhouseIcons.warm();
    }

    @Override public void removed() {
        commitEdit();
        // A running solve goes on without this screen; the session toasts when it ends.
        session.viewerClosed();
        session.persist();
        if (link != null) { link.setFocused(false); search.setFocused(false); }
    }

    private float scale() {
        return UiScale.menu(Math.min(MAX_SCALE, Math.min(width / (panelWidth() + 24F), height / (PANEL_HEIGHT + 20F))));
    }

    /** The panel is as wide as its visible columns, so collapsing the priorities lets everything scale up. */
    private int panelWidth() { return state.prioritiesOpen() ? EXPANDED_WIDTH : COLLAPSED_WIDTH; }

    private int coordinate(double value) { return (int) Math.floor(value / scale()); }
    private int guiScale() { return Minecraft.getInstance().getWindow().getGuiScale(); }

    private void layout() {
        x = (coordinate(width) - panelWidth()) / 2;
        y = (coordinate(height) - PANEL_HEIGHT) / 2;
        place(search, searchAt, x + LX, y + SEARCH_Y, LW);
        place(link, linkAt, x + MX, y + LINK_Y, MW);
    }

    /** EditBox bounds are only written when they change; each setter would otherwise run every frame. */
    private static void place(UiTextField field, int[] at, int left, int top, int fieldWidth) {
        if (at[0] == left && at[1] == top && at[2] == fieldWidth) return;
        at[0] = left;
        at[1] = top;
        at[2] = fieldWidth;
        field.setBounds(left, top, fieldWidth, FIELD_H);
    }

    private void clearTextCaches() {
        for (Fit fit : fits) fit.cache.clear();
        widths.clear();
        toolLeft = null;
    }

    private int width(String text) {
        Integer known = widths.get(text);
        if (known == null) {
            known = menuFont.width(text);
            widths.put(text, known);
        }
        return known;
    }

    /**
     * Cells are laid out in whole device pixels; see {@link GreenhouseGridLayout}. The layout is rebuilt only
     * when the panel moves or the scale changes.
     */
    private GreenhouseGridLayout gridLayout() {
        float device = scale() * guiScale();
        if (layoutCache == null || layoutX != x || layoutY != y || layoutDevice != device) {
            layoutCache = GreenhouseGridLayout.of(x + GRID_X, y + GRID_Y, GreenhouseGrid.SIZE, CELL, GAP, device);
            layoutX = x;
            layoutY = y;
            layoutDevice = device;
        }
        return layoutCache;
    }

    /** Index (row * 10 + column) of the cell under a point in GUI units, hit-tested in device pixels; -1 if none. */
    private int cellAt(double guiX, double guiY) {
        int gui = guiScale();
        int[] at = gridLayout().at((int) Math.floor(guiX * gui), (int) Math.floor(guiY * gui));
        return at == null ? -1 : at[0] * GreenhouseGrid.SIZE + at[1];
    }

    private static boolean inside(int px, int py, int left, int top, int w, int h) {
        return px >= left && px < left + w && py >= top && py < top + h;
    }

    // --- drawing ---------------------------------------------------------------------------------------------

    private void fill(GuiGraphicsExtractor graphics, int left, int top, int w, int h, int color) {
        graphics.fill(left, top, left + w, top + h, color);
    }

    private void text(GuiGraphicsExtractor graphics, String text, int left, int top, int color) {
        graphics.text(menuFont, text, left, top, color, false);
    }

    private void button(GuiGraphicsExtractor graphics, int left, int top, int w, int h, String label, boolean hover,
                        int background, int color) {
        int shade = hover ? (background == THEME.accent() ? THEME.accentDark() : THEME.controlHover()) : background;
        fill(graphics, left, top, w, h, shade);
        int textTop = top + (h - menuFont.lineHeight) / 2;
        int overflow = width(label) - (w - 2 * BUTTON_PAD);
        if (overflow <= 0) {
            text(graphics, label, left + (w - width(label)) / 2, textTop, color);
        } else {
            // Too long for the button: slide it back and forth inside the button so all of it can be read.
            clippedText(graphics, label, left + BUTTON_PAD, textTop, left + w - BUTTON_PAD, top, top + h,
                marquee.always(overflow), color);
        }
    }

    /** The whole text at {@code left - offset}, visible only between {@code clipLeft} and {@code clipRight}. */
    private void clippedText(GuiGraphicsExtractor graphics, String text, int left, int top, int clipRight, int clipTop,
                             int clipBottom, int offset, int color) {
        graphics.enableScissor(left, clipTop, clipRight, clipBottom);
        text(graphics, text, left - offset, top, color);
        graphics.disableScissor();
    }

    /**
     * Text that may be wider than its room. If it fits it is drawn as it is; too wide and not hovered it is cut
     * to fit (cached); too wide and hovered it scrolls inside its room, starting afresh each time the pointer
     * arrives, so the screen is only busy where the player is looking.
     *
     * @param kind  which list the text belongs to, with {@code index} the element's identity for the marquee
     */
    private void fitted(GuiGraphicsExtractor graphics, Fit fit, String text, int left, int top, int color,
                        boolean hover, int kind, int index) {
        int overflow = width(text) - fit.width;
        if (overflow <= 0) {
            text(graphics, text, left, top, color);
        } else if (!hover) {
            text(graphics, fit.of(menuFont, text), left, top, color);
        } else {
            clippedText(graphics, text, left, top, left + fit.width, top - 1, top + menuFont.lineHeight + 1,
                marquee.onHover(((long) kind << 32) | index, overflow, true), color);
        }
    }

    /** A crop's model, or a small coloured square when it has none. */
    private void icon(GuiGraphicsExtractor graphics, String crop, int left, int top) {
        ItemStack stack = GreenhouseIcons.stack(crop);
        if (stack.isEmpty()) fill(graphics, left + 2, top + 2, 12, 12, GreenhouseGridPainter.colorOf(crop));
        else graphics.item(stack, left, top);
    }

    private void scrollbar(GuiGraphicsExtractor graphics, int left, int top, int height, UiScrollList scroll,
                           int content, int rows) {
        if (content <= rows) return;
        int thumb = Math.max(8, height * rows / content);
        int at = top + (height - thumb) * scroll.offset() / (content - rows);
        fill(graphics, left, at, 2, thumb, THEME.muted());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        float device = scale() * guiScale();
        if (device != cachedDevice) {
            cachedDevice = device;
            clearTextCaches();
        }
        int mx = coordinate(mouseX);
        int my = coordinate(mouseY);
        int hoveredCell = cellAt(mouseX + 0.5, mouseY + 0.5);
        tooltip = null;
        syncDerived();
        marquee.beginFrame();
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale(), scale());
            fill(graphics, 0, 0, coordinate(width), coordinate(height), THEME.backdrop());
            // Two soft rectangles stand in for UiShapes.shadow, which would cost about 130 elements here.
            int panelWidth = panelWidth();
            fill(graphics, x - 3, y - 1, panelWidth + 6, PANEL_HEIGHT + 6, 0x22000000);
            fill(graphics, x - 1, y, panelWidth + 2, PANEL_HEIGHT + 3, 0x33000000);
            UiShapes.rounded(graphics, x - 1, y - 1, panelWidth + 2, PANEL_HEIGHT + 2, 4, THEME.accent());
            UiShapes.rounded(graphics, x, y, panelWidth, PANEL_HEIGHT, 3, THEME.panel());
            text(graphics, "Greenhouse planner", x + 14, y + 14, THEME.accent());
            int toggleX = toggleLeft();
            boolean open = state.prioritiesOpen();
            button(graphics, toggleX, y + 8, TOGGLE_W, 18, "Priorities", inside(mx, my, toggleX, y + 8, TOGGLE_W, 18),
                open ? THEME.accentDark() : THEME.control(), THEME.text());
            if (open) arrow(graphics, toggleX + TOGGLE_W - 14, y + 15, ARROW_DOWN, THEME.text());
            else arrow(graphics, toggleX + TOGGLE_W - 12, y + 14, ARROW_RIGHT, THEME.text());
            text(graphics, "Esc closes", toggleX - 10 - width("Esc closes"), y + 14, THEME.muted());
            boolean covered = dropdownOpen() && inside(mx, my, x + LX, y + DROP_Y, LW, DROP_ROWS * DROP_ROW + 2);
            drawLeft(graphics, covered ? -1 : mx, covered ? -1 : my, partialTick);
            drawMiddle(graphics, mx, my, hoveredCell, partialTick);
            if (open) drawRight(graphics, mx, my);
            if (dropdownOpen()) drawDropdown(graphics, mx, my);
            drawNotice(graphics, mx, my);
            if (tooltip != null) {
                UiTooltip.draw(graphics, menuFont, tooltip, mx + 10, my + 10, coordinate(width), coordinate(height),
                    THEME);
            }
            marquee.endFrame();
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void drawLeft(GuiGraphicsExtractor graphics, int mx, int my, float partialTick) {
        int left = x + LX;
        fill(graphics, left, y + CARD_Y, LW, CARD_H, THEME.panelSoft());
        text(graphics, cellsHead.of(state.unlockedCount()), left + 8, y + CARD_Y + 7, THEME.text());
        button(graphics, left + LW - 88, y + CARD_Y + 4, 80, 18, "Edit cells",
            inside(mx, my, left + LW - 88, y + CARD_Y + 4, 80, 18), editingCells ? THEME.accent() : THEME.control(),
            THEME.text());
        button(graphics, left + 8, y + CARD_Y + 28, 124, 18, "Select all",
            inside(mx, my, left + 8, y + CARD_Y + 28, 124, 18), THEME.control(), THEME.text());
        button(graphics, left + 138, y + CARD_Y + 28, 124, 18, "Reset",
            inside(mx, my, left + 138, y + CARD_Y + 28, 124, 18), THEME.control(), THEME.text());

        text(graphics, "Mutation targets", left, y + SEARCH_LABEL_Y, THEME.muted());
        search.extractRenderState(graphics, mx, my, partialTick);

        List<GreenhouseState.Target> targets = state.targets();
        text(graphics, targetsHead.of(targets.size()), left, y + SEL_HEAD_Y, THEME.muted());
        fill(graphics, left, y + SEL_Y, LW, SEL_ROWS * SEL_ROW, THEME.panelSoft());
        if (targets.isEmpty()) {
            text(graphics, "Search above and click a mutation to add it.", left + 8, y + SEL_Y + 6, THEME.muted());
        }
        // A row that was just picked fades its highlight out; it is one fill and only while it lasts.
        long flashLeft = flashName == null ? 0 : flashUntilNanos - System.nanoTime();
        if (flashLeft <= 0) flashName = null;
        int flashColor = ((int) (0xB0 * flashLeft / FLASH_NANOS) << 24) | (THEME.accent() & 0xFFFFFF);
        for (int slot = 0; slot < SEL_ROWS && targetScroll.offset() + slot < targets.size(); slot++) {
            int index = targetScroll.offset() + slot;
            GreenhouseState.Target target = targets.get(index);
            int top = y + SEL_Y + slot * SEL_ROW;
            boolean maximize = target.mode() == GreenhouseState.Mode.MAX;
            if (flashName != null && target.mutation().equals(flashName)) fill(graphics, left, top, LW - 4, SEL_ROW, flashColor);
            icon(graphics, target.mutation(), left + 2, top + 2);
            fitted(graphics, targetName, target.mutation(), left + 22, top + 6, THEME.text(),
                inside(mx, my, left + 22, top, NAME_W, SEL_ROW), KIND_TARGET_NAME, index);
            button(graphics, left + TARGET_X, top + 2, TARGET_W, 16, "Target",
                inside(mx, my, left + TARGET_X, top + 2, TARGET_W, 16), maximize ? THEME.control() : THEME.accent(),
                THEME.text());
            button(graphics, left + MAX_X, top + 2, MAX_W, 16, "Max", inside(mx, my, left + MAX_X, top + 2, MAX_W, 16),
                maximize ? THEME.accent() : THEME.control(), THEME.text());
            boolean editing = numberEdit != null && editKind == KIND_COUNT && editIndex == index;
            numberBox(graphics, left + COUNT_X, top + 2, COUNT_W, 16,
                editing ? numberEdit.text() : Integer.toString(target.count()), editing,
                inside(mx, my, left + COUNT_X, top + 2, COUNT_W - ARROW_W, 16), maximize ? THEME.muted() : THEME.text());
            countArrows(graphics, left + COUNT_X + COUNT_W - ARROW_W, top + 2, mx, my);
            text(graphics, "x", left + REMOVE_X + 5, top + 6,
                inside(mx, my, left + REMOVE_X, top + 2, REMOVE_W, 16) ? THEME.error() : THEME.muted());
        }
        scrollbar(graphics, left + LW - 3, y + SEL_Y, SEL_ROWS * SEL_ROW, targetScroll, targets.size(), SEL_ROWS);

        int solveY = y + SOLVE_Y;
        boolean running = session.running();
        boolean stopping = session.stopping();
        button(graphics, left, solveY, 84, 22, stopping ? "Stopping..." : running ? "Stop" : "Solve",
            inside(mx, my, left, solveY, 84, 22), THEME.control(),
            stopping ? THEME.muted() : running ? THEME.error() : THEME.success());
        text(graphics, "Max time", left + TIME_LABEL_X, solveY + 7, THEME.muted());
        boolean editingSeconds = numberEdit != null && editKind == KIND_SECONDS;
        numberBox(graphics, left + TIME_FIELD_X, solveY + 2, TIME_FIELD_W, 18,
            editingSeconds ? numberEdit.text() : Integer.toString(state.seconds()), editingSeconds,
            inside(mx, my, left + TIME_FIELD_X, solveY + 2, TIME_FIELD_W, 18), THEME.text());
        text(graphics, "s", left + TIME_FIELD_X + TIME_FIELD_W + 6, solveY + 7, THEME.muted());
        boolean hoverStop = inside(mx, my, left + STOP_EARLY_X, solveY + 2, STOP_EARLY_W, 18);
        button(graphics, left + STOP_EARLY_X, solveY + 2, STOP_EARLY_W, 18, "Stop early", hoverStop,
            state.stopEarly() ? THEME.accent() : THEME.control(), THEME.text());
        if (hoverStop) tooltip = STOP_EARLY_TIP;

        int priceY = y + PRICE_Y;
        boolean ironman = state.costMode() == CostMode.IRONMAN;
        boolean hoverBazaar = inside(mx, my, left + PRICE_SEG_X, priceY, PRICE_SEG_W, 18);
        boolean hoverIronman = inside(mx, my, left + PRICE_SEG_X + PRICE_SEG_W, priceY, PRICE_SEG_W, 18);
        text(graphics, "Prices", left, priceY + 5, THEME.muted());
        button(graphics, left + PRICE_SEG_X, priceY, PRICE_SEG_W, 18, "Bazaar", hoverBazaar,
            ironman ? THEME.control() : THEME.accent(), THEME.text());
        button(graphics, left + PRICE_SEG_X + PRICE_SEG_W, priceY, PRICE_SEG_W, 18, "Ironman", hoverIronman,
            ironman ? THEME.accent() : THEME.control(), THEME.text());
        if (hoverBazaar) tooltip = BAZAAR_TIP;
        else if (hoverIronman) tooltip = IRONMAN_TIP;

        fill(graphics, left, y + BAR_Y, LW, 8, THEME.panelSoft());
        if (running) {
            long elapsed = session.elapsedMillis();
            double share = Math.min(1.0, elapsed / (double) session.budgetMillis());
            fill(graphics, left, y + BAR_Y, (int) Math.round(LW * share), 8, THEME.accent());
            long tenths = elapsed / 100;
            if (tenths != progressTenths) {
                progressTenths = tenths;
                progressText = String.format(Locale.ROOT, "Solving... %.1f / %d s", elapsed / 1000.0,
                    session.budgetMillis() / 1000);
            }
            text(graphics, progressText, left, y + BAR_Y + 12, THEME.muted());
        } else if (shownSummary != null) {
            text(graphics, lastSolveText, left, y + BAR_Y + 12, THEME.muted());
        }
        fitted(graphics, priceFit, session.priceLine(), left, y + BAR_Y + 23, THEME.muted(),
            inside(mx, my, left, y + BAR_Y + 22, LW, menuFont.lineHeight + 2), KIND_PRICE, 0);

        button(graphics, left, y + HOLO_Y, HOLO_W, HOLO_H, "Show in world", inside(mx, my, left, y + HOLO_Y, HOLO_W, HOLO_H),
            THEME.control(), THEME.text());
        if (GreenhouseHologram.isShowing()) {
            button(graphics, left + LW - HOLO_W, y + HOLO_Y, HOLO_W, HOLO_H, "Hide hologram",
                inside(mx, my, left + LW - HOLO_W, y + HOLO_Y, HOLO_W, HOLO_H), THEME.control(), THEME.text());
        }
        button(graphics, left, y + HOLO_CLIP_Y, LW, HOLO_H, "Show in world (clipboard)",
            inside(mx, my, left, y + HOLO_CLIP_Y, LW, HOLO_H), THEME.control(), THEME.text());
    }

    /** The stacked up and down arrows of a count cell: one column fill, two small triangles, a highlight on hover. */
    private void countArrows(GuiGraphicsExtractor graphics, int left, int top, int mx, int my) {
        fill(graphics, left, top, ARROW_W, 16, THEME.controlHover());
        if (inside(mx, my, left, top, ARROW_W, 16)) {
            fill(graphics, left, my < top + 8 ? top : top + 8, ARROW_W, 8, THEME.accent());
        }
        arrow(graphics, left + 3, top + 2, ARROW_UP, THEME.text());
        arrow(graphics, left + 3, top + 11, ARROW_DOWN, THEME.text());
    }

    /** A five unit triangle from three fills, so it does not depend on the font having an arrow glyph. */
    private void arrow(GuiGraphicsExtractor graphics, int left, int top, int direction, int color) {
        switch (direction) {
            case ARROW_UP -> {
                fill(graphics, left + 2, top, 1, 1, color);
                fill(graphics, left + 1, top + 1, 3, 1, color);
                fill(graphics, left, top + 2, 5, 1, color);
            }
            case ARROW_DOWN -> {
                fill(graphics, left, top, 5, 1, color);
                fill(graphics, left + 1, top + 1, 3, 1, color);
                fill(graphics, left + 2, top + 2, 1, 1, color);
            }
            default -> {
                fill(graphics, left, top, 1, 5, color);
                fill(graphics, left + 1, top + 1, 1, 3, color);
                fill(graphics, left + 2, top + 2, 1, 1, color);
            }
        }
    }

    /** Where the Priorities toggle sits: the top right corner of the panel, whatever its width. */
    private int toggleLeft() { return x + panelWidth() - 12 - TOGGLE_W; }

    /**
     * Button widths follow their labels and together fill the column: spare room is shared out evenly, so a
     * short label does not sit in a button much wider than a long one. Measured again after the font or the scale
     * changes. A label that still does not fit scrolls (see {@link #button}).
     */
    private void toolbar() {
        if (toolLeft != null) return;
        int[] natural = new int[TOOLS.length];
        int sum = 0;
        for (int i = 0; i < TOOLS.length; i++) {
            natural[i] = width(TOOLS[i]) + 2 * TOOL_PAD;
            sum += natural[i];
        }
        int room = MW - (TOOLS.length - 1) * TOOL_GAP;
        int spare = room - sum;
        int[] lefts = new int[TOOLS.length];
        int[] widths = new int[TOOLS.length];
        int at = 0;
        for (int i = 0; i < TOOLS.length; i++) {
            lefts[i] = at;
            // Spare room evenly when everything fits; otherwise every button shrinks in proportion.
            int own = spare >= 0 ? natural[i] + spare / TOOLS.length : natural[i] * room / sum;
            widths[i] = i == TOOLS.length - 1 ? MW - at : own;
            at += widths[i] + TOOL_GAP;
        }
        toolWidth = widths;
        toolLeft = lefts;
    }

    /** A number cell: a plain fill and one text; the caret is one more fill, only while it is being edited. */
    private void numberBox(GuiGraphicsExtractor graphics, int left, int top, int w, int h, String value,
                           boolean editing, boolean hover, int color) {
        fill(graphics, left, top, w, h, editing ? THEME.accentDark() : hover ? THEME.controlHover() : THEME.control());
        int textX = left + 4;
        text(graphics, value, textX, top + (h - menuFont.lineHeight) / 2, color);
        if (editing && (System.currentTimeMillis() / 500 & 1) == 0) {
            fill(graphics, textX + menuFont.width(value), top + 3, 1, h - 6, THEME.text());
        }
    }

    private void drawMiddle(GuiGraphicsExtractor graphics, int mx, int my, int hoveredCell, float partialTick) {
        int left = x + MX;
        toolbar();
        for (int i = 0; i < TOOLS.length; i++) {
            int bx = left + toolLeft[i];
            int by = y + TOOL_Y;
            button(graphics, bx, by, toolWidth[i], TOOL_H, TOOLS[i], inside(mx, my, bx, by, toolWidth[i], TOOL_H),
                THEME.control(), i == 3 ? THEME.error() : THEME.text());
        }

        // Compass: the letters come from GreenhouseOrientation, the mapping the hologram uses, so the two agree:
        // by default the grid's top edge faces N, its right edge E, its bottom S and its left W, and the letters
        // follow /kung greenhouse blueprint rotate|mirror.
        GreenhouseOrientation orientation = GreenhouseHologram.orientation();
        if (!orientation.equals(compassFor)) {
            compassFor = orientation;
            compass = GreenhouseCompass.labels(orientation);
        }
        int gridLeft = x + GRID_X;
        int gridTop = y + GRID_Y;
        String topLabel = compass[GreenhouseCompass.TOP];
        String bottomLabel = compass[GreenhouseCompass.BOTTOM];
        String leftLabel = compass[GreenhouseCompass.LEFT];
        String rightLabel = compass[GreenhouseCompass.RIGHT];
        text(graphics, topLabel, gridLeft + (GRID_SIZE - width(topLabel)) / 2, y + NORTH_Y, THEME.muted());
        text(graphics, bottomLabel, gridLeft + (GRID_SIZE - width(bottomLabel)) / 2, gridTop + GRID_SIZE + 4, THEME.muted());
        text(graphics, leftLabel, gridLeft - 4 - width(leftLabel), gridTop + (GRID_SIZE - menuFont.lineHeight) / 2,
            THEME.muted());
        text(graphics, rightLabel, gridLeft + GRID_SIZE + 4, gridTop + (GRID_SIZE - menuFont.lineHeight) / 2, THEME.muted());

        painter.draw(graphics, menuFont, gridLayout(), scale() * guiScale(), session.plan(), state, hoveredCell,
            editingCells);
        if (hoveredCell >= 0) tooltip = cellTooltip(hoveredCell);

        link.extractRenderState(graphics, mx, my, partialTick);
        fitted(graphics, statusText, session.status(), left, y + STATUS_Y, toneColor(session.tone()),
            inside(mx, my, left, y + STATUS_Y - 1, MW, menuFont.lineHeight + 2), KIND_STATUS, 0);

        text(graphics, "Goals", left, y + RESULT_HEAD_Y, THEME.muted());
        text(graphics, cropsHead.of(cropRows.size()), x + CROPS_X, y + RESULT_HEAD_Y, THEME.muted());
        fill(graphics, left, y + RESULT_Y, HALF, RESULT_ROWS * RESULT_ROW, THEME.panelSoft());
        fill(graphics, x + CROPS_X, y + RESULT_Y, MW - 156, RESULT_ROWS * RESULT_ROW, THEME.panelSoft());
        if (goalRows.isEmpty()) text(graphics, "No result yet", left + 6, y + RESULT_Y + 5, THEME.muted());
        for (int slot = 0; slot < RESULT_ROWS && goalScroll.offset() + slot < goalRows.size(); slot++) {
            GoalRow row = goalRows.get(goalScroll.offset() + slot);
            int top = y + RESULT_Y + slot * RESULT_ROW;
            fitted(graphics, goalText, row.text(), left + 6, top + 5, row.color(), inside(mx, my, left, top, HALF, RESULT_ROW),
                KIND_GOAL, goalScroll.offset() + slot);
            if (!row.note().isEmpty() && inside(mx, my, left, top, HALF, RESULT_ROW)) tooltip = List.of(row.note());
        }
        scrollbar(graphics, left + HALF - 3, y + RESULT_Y, RESULT_ROWS * RESULT_ROW, goalScroll, goalRows.size(),
            RESULT_ROWS);
        for (int slot = 0; slot < RESULT_ROWS && cropScroll.offset() + slot < cropRows.size(); slot++) {
            CropRow row = cropRows.get(cropScroll.offset() + slot);
            int top = y + RESULT_Y + slot * RESULT_ROW;
            icon(graphics, row.name(), x + CROPS_X + 1, top + 1);
            fitted(graphics, cropText, row.text(), x + CROPS_X + 20, top + 5, THEME.text(),
                inside(mx, my, x + CROPS_X + 20, top, HALF - 26, RESULT_ROW), KIND_CROP, cropScroll.offset() + slot);
            if (inside(mx, my, x + CROPS_X, top, MW - 156, RESULT_ROW)) tooltip = cropTooltip(row);
        }
        scrollbar(graphics, x + MX + MW - 3, y + RESULT_Y, RESULT_ROWS * RESULT_ROW, cropScroll, cropRows.size(),
            RESULT_ROWS);

        if (!soilText.isEmpty()) {
            fitted(graphics, soilFit, soilText, left, y + SOIL_Y, THEME.muted(),
                inside(mx, my, left, y + SOIL_Y - 1, MW, menuFont.lineHeight + 2), KIND_SOIL, 0);
        }
        String cost = shownSummary == null ? "" : shownSummary.costLine();
        if (!cost.isEmpty()) {
            fitted(graphics, costFit, cost, left, y + COST_Y, THEME.text(),
                inside(mx, my, left, y + COST_Y - 1, MW, menuFont.lineHeight + 2), KIND_COST, 0);
        }
        if (noteLines == null) noteLines = wrapNotes();
        for (int i = 0; i < noteLines.size(); i++) {
            graphics.text(menuFont, noteLines.get(i), left, y + NOTE_Y + i * 11, THEME.warning(), false);
        }
        if (notesCut && inside(mx, my, left, y + NOTE_Y, MW, NOTE_LINES * 11)) tooltip = notes;
    }

    /** What one crop of the shopping list costs and what all of its cells cost, for the weighting chosen now. */
    private List<String> cropTooltip(CropRow row) {
        var quotes = session.quotes();
        var quote = quotes.get(GreenhouseCrops.canonical(row.name()));
        String each = GreenhouseCosts.describe(row.name(), quotes);
        if (quote == null || quote.basis() == GreenhouseCosts.Basis.FREE) {
            return List.of(row.name() + " x" + row.count(), each, CROP_CLICK_TIP);
        }
        return List.of(row.name() + " x" + row.count(), "Each: " + each,
            "All: " + GreenhouseCosts.formatTotal(quote.cost() * row.count(), state.costMode()), CROP_CLICK_TIP);
    }

    /** The lines are rebuilt only when the hovered cell, the grid or the lock on the cell changes, not every frame. */
    private List<String> cellTooltip(int index) {
        GreenhousePlan plan = session.plan();
        boolean open = state.isUnlocked(index);
        if (index == cellTipIndex && plan == cellTipPlan && open == cellTipOpen) return cellTip;
        cellTipIndex = index;
        cellTipPlan = plan;
        cellTipOpen = open;
        int row = index / GreenhouseGrid.SIZE;
        int column = index % GreenhouseGrid.SIZE;
        GreenhouseGrid.Cell content = plan.grid().get(row, column);
        String place = "Row " + (row + 1) + ", column " + (column + 1);
        if (content == null) {
            cellTip = open ? null : List.of("Locked cell", place);
        } else {
            String role = switch (content.flag()) {
                case GreenhouseGrid.TARGET -> "Target mutation";
                case GreenhouseGrid.BONUS -> "Bonus crop";
                default -> "Ingredient";
            };
            String soil = "Soil: " + GreenhouseSoils.of(content.name()).displayName()
                + (content.flag() == GreenhouseGrid.TARGET ? " (needed under the spot)" : "");
            cellTip = List.of(content.name(), place, role, soil);
        }
        return cellTip;
    }

    private void drawRight(GuiGraphicsExtractor graphics, int mx, int my) {
        int left = x + RX;
        text(graphics, "Crop priorities", left, y + 38, THEME.text());
        text(graphics, "Higher = use less", left, y + 50, THEME.muted());
        if (inside(mx, my, left, y + 36, RW - 116, 24)) tooltip = PRIORITY_TIP;
        button(graphics, left + RW - 112, y + 34, 112, 18, "Reset priorities",
            inside(mx, my, left + RW - 112, y + 34, 112, 18), THEME.control(), THEME.text());
        fill(graphics, left, y + PRIO_Y, RW, PRIO_ROWS * PRIO_ROW, THEME.panelSoft());
        for (int slot = 0; slot < PRIO_ROWS && priorityScroll.offset() + slot < priorityCrops.size(); slot++) {
            int index = priorityScroll.offset() + slot;
            String crop = priorityCrops.get(index);
            int top = y + PRIO_Y + slot * PRIO_ROW;
            icon(graphics, crop, left + 2, top + 1);
            fitted(graphics, priorityName, crop, left + PRIO_NAME_X, top + 5, THEME.text(),
                inside(mx, my, left + PRIO_NAME_X, top, 150, PRIO_ROW), KIND_PRIORITY_NAME, index);
            stepper(graphics, left + MINUS_X, top + 1, false, inside(mx, my, left + MINUS_X, top + 1, STEP_W, 16));
            stepper(graphics, left + PLUS_X, top + 1, true, inside(mx, my, left + PLUS_X, top + 1, STEP_W, 16));
            boolean editing = numberEdit != null && editKind == KIND_PRIORITY && editIndex == index;
            int value = state.priority(crop);
            numberBox(graphics, left + VALUE_X, top + 1, VALUE_W, 16, editing ? numberEdit.text() : Integer.toString(value),
                editing, inside(mx, my, left + VALUE_X, top + 1, VALUE_W, 16), value == 0 ? THEME.muted() : THEME.text());
        }
        scrollbar(graphics, left + RW - 3, y + PRIO_Y, PRIO_ROWS * PRIO_ROW, priorityScroll, priorityCrops.size(),
            PRIO_ROWS);
        text(graphics, "Priorities only break ties between", left, y + PRIO_Y + PRIO_ROWS * PRIO_ROW + 8, THEME.muted());
        text(graphics, "layouts that grow the same goals.", left, y + PRIO_Y + PRIO_ROWS * PRIO_ROW + 19, THEME.muted());
    }

    /** A 16x16 minus or plus button; the glyph is one or two fills, not a text call. */
    private void stepper(GuiGraphicsExtractor graphics, int left, int top, boolean plus, boolean hover) {
        fill(graphics, left, top, STEP_W, 16, hover ? THEME.controlHover() : THEME.control());
        fill(graphics, left + 4, top + 7, 8, 2, THEME.text());
        if (plus) fill(graphics, left + 7, top + 4, 2, 8, THEME.text());
    }

    // --- mutation dropdown -----------------------------------------------------------------------------------

    private boolean dropdownOpen() { return search != null && search.isFocused(); }

    private boolean inDropdown(int mx, int my) {
        return dropdownOpen() && inside(mx, my, x + LX, y + DROP_Y, LW, DROP_ROWS * DROP_ROW + 2);
    }

    private void drawDropdown(GuiGraphicsExtractor graphics, int mx, int my) {
        int left = x + LX;
        int top = y + DROP_Y;
        fill(graphics, left - 1, top, LW + 2, DROP_ROWS * DROP_ROW + 3, THEME.accent());
        fill(graphics, left, top, LW, DROP_ROWS * DROP_ROW + 2, THEME.panel());
        if (matches.isEmpty()) text(graphics, "No matching mutation", left + 8, top + 6, THEME.muted());
        for (int slot = 0; slot < DROP_ROWS && dropdownScroll.offset() + slot < matches.size(); slot++) {
            GreenhouseMutations.Mutation mutation = matches.get(dropdownScroll.offset() + slot);
            int rowTop = top + 1 + slot * DROP_ROW;
            if (inside(mx, my, left, rowTop, LW, DROP_ROW)) fill(graphics, left, rowTop, LW, DROP_ROW, THEME.controlHover());
            icon(graphics, mutation.name(), left + 3, rowTop + 1);
            fitted(graphics, dropName, mutation.name(), left + 24, rowTop + 5,
                state.isSelected(mutation.name()) ? THEME.accent() : THEME.text(),
                inside(mx, my, left + 24, rowTop, 104, DROP_ROW), KIND_DROP_NAME, dropdownScroll.offset() + slot);
            fitted(graphics, dropRequirement, requirement(mutation), left + 132, rowTop + 5, THEME.muted(),
                inside(mx, my, left + 132, rowTop, 132, DROP_ROW), KIND_DROP_REQUIREMENT,
                dropdownScroll.offset() + slot);
        }
        scrollbar(graphics, left + LW - 3, top + 1, DROP_ROWS * DROP_ROW, dropdownScroll, matches.size(), DROP_ROWS);
    }

    private String requirement(GreenhouseMutations.Mutation mutation) {
        String known = requirements.get(mutation.name());
        if (known == null) {
            StringBuilder out = new StringBuilder();
            if (mutation.emptyRing()) out.append("Empty ring (no crops)");
            else {
                mutation.needs().forEach((crop, count) -> {
                    if (!out.isEmpty()) out.append(", ");
                    out.append(crop).append(" x").append(count);
                });
            }
            known = out.toString();
            requirements.put(mutation.name(), known);
        }
        return known;
    }

    // --- actions ---------------------------------------------------------------------------------------------

    private void say(String message, Tone tone) { session.say(message, tone); }

    /** Shows a short message in the popup near the top centre of the panel; a newer message replaces it. */
    void showNotice(String message) { notice.show(message); }

    private int toneColor(Tone tone) {
        return switch (tone) {
            case MUTED -> THEME.muted();
            case SUCCESS -> THEME.success();
            case WARNING -> THEME.warning();
            case ERROR -> THEME.error();
        };
    }

    private int levelColor(GreenhouseSummary.Level level) {
        return switch (level) {
            case REACHED -> THEME.success();
            case SHORT -> THEME.warning();
            case FAILED -> THEME.error();
        };
    }

    /** Rebuilds the lists a grid or a result feeds, when the session shows a different one. */
    private void syncDerived() {
        GreenhousePlan plan = session.plan();
        if (plan != shownPlan) {
            shownPlan = plan;
            List<CropRow> rows = new ArrayList<>();
            plan.crops().forEach((crop, count) -> rows.add(new CropRow(crop, crop + " x" + count, count)));
            cropRows = List.copyOf(rows);
            // A live preview replaces the lists several times a second: keep the player's scroll position then,
            // but never past the end of a list that just got shorter.
            if (!session.running()) cropScroll.reset();
            cropScroll.scrollBy(0, cropRows.size(), RESULT_ROWS);
            soilText = plan.soils().isEmpty() ? "" : "Soil: " + String.join(", ", plan.soils());
        }
        GreenhouseSummary summary = session.summary();
        if (summary != shownSummary) {
            shownSummary = summary;
            List<GoalRow> rows = new ArrayList<>();
            if (summary != null) {
                for (GreenhouseSummary.Goal goal : summary.goals()) {
                    rows.add(new GoalRow(goal.text(), levelColor(goal.level()), goal.note()));
                }
            }
            goalRows = List.copyOf(rows);
            if (!session.running()) goalScroll.reset();
            goalScroll.scrollBy(0, goalRows.size(), RESULT_ROWS);
            notes = summary == null ? List.of() : summary.notes();
            noteLines = null;
            lastSolveText = summary == null ? "" : summary.lastSolve();
        }
    }

    private List<FormattedCharSequence> wrapNotes() {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String note : notes) lines.addAll(menuFont.split(Component.literal(note), MW));
        notesCut = lines.size() > NOTE_LINES;
        return notesCut ? List.copyOf(lines.subList(0, NOTE_LINES)) : List.copyOf(lines);
    }

    /** Loads link text into the grid; the field mirrors what was loaded, and a failure also opens the popup. */
    private void load(String text) {
        String error = session.loadLayout(text);
        if (error != null) showNotice(error);
        else link.setValue(text.strip());
    }

    /** Puts Kung's layout key (not a Sky Mutations link) in the clipboard and the link field. */
    private void copy() {
        if (session.running()) {
            // The grid on show is an interim preview of the running solve: copy nothing from it.
            perform(GreenhouseActions.busy());
            return;
        }
        GreenhouseGrid grid = session.plan().grid();
        String code = GreenhouseCode.encode(grid);
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        link.setValue(code);
        String warning = GreenhouseActions.copyWarning(GreenhouseCode.unrepresentable(grid));
        if (warning == null) {
            say("Layout code copied", Tone.SUCCESS);
        } else {
            say("Layout code copied. " + warning, Tone.WARNING);
            showNotice(warning);
        }
    }

    /**
     * Carries out what the hologram buttons decided: a shown hologram closes the screen and is reported in chat
     * (the screen is gone, so the status line would not be seen); anything else stays and explains itself.
     */
    private void perform(GreenhouseActions.Outcome outcome) {
        if (outcome.closeScreen()) {
            McCompat.chat(Minecraft.getInstance()).addClientSystemMessage(KungMessages.info("Greenhouse", outcome.chat()));
            onClose();
            return;
        }
        say(outcome.status(), outcome.tone());
        showNotice(outcome.notice());
    }

    /** A grid change the session refused (a solve is running) also opens the popup; null means it was done. */
    private void refused(String reason) {
        if (reason != null) showNotice(reason);
    }

    private void tool(int index) {
        switch (index) {
            case 0 -> refused(session.change(GreenhouseGrid::rotatedLeft, true, TOOL_RESULTS[0]));
            case 1 -> refused(session.change(GreenhouseGrid::rotatedRight, true, TOOL_RESULTS[1]));
            case 2 -> refused(session.change(GreenhouseGrid::mirrored, true, TOOL_RESULTS[2]));
            case 3 -> refused(session.change(GreenhouseGrid::cleared, false, TOOL_RESULTS[3]));
            case 4 -> load(Minecraft.getInstance().keyboardHandler.getClipboard());
            default -> copy();
        }
    }

    /**
     * The popup: a bordered box, its text, an x at the top-right corner and a bar of the time left; at most
     * about seven draw ops. The box is remembered for hit-testing.
     */
    private void drawNotice(GuiGraphicsExtractor graphics, int mx, int my) {
        String message = notice.text();
        if (message == null) {
            noticeWidth = 0;
            return;
        }
        String shown = noticeFit.of(menuFont, message);
        noticeWidth = Math.min(NOTICE_MAX_W, width(shown) + 40);
        noticeLeft = x + (panelWidth() - noticeWidth) / 2;
        noticeTop = y + NOTICE_Y;
        fill(graphics, noticeLeft - 1, noticeTop - 1, noticeWidth + 2, NOTICE_H + 2, THEME.accent());
        fill(graphics, noticeLeft, noticeTop, noticeWidth, NOTICE_H, THEME.panelSoft());
        text(graphics, shown, noticeLeft + 10, noticeTop + 8, THEME.text());
        int closeLeft = noticeLeft + noticeWidth - NOTICE_X_W - 2;
        boolean hover = inside(mx, my, closeLeft, noticeTop + 2, NOTICE_X_W, NOTICE_X_W);
        if (hover) fill(graphics, closeLeft, noticeTop + 2, NOTICE_X_W, NOTICE_X_W, THEME.controlHover());
        text(graphics, "x", closeLeft + 5, noticeTop + 6, hover ? THEME.error() : THEME.muted());
        fill(graphics, noticeLeft, noticeTop + NOTICE_H - 2, Math.round(noticeWidth * notice.remaining()), 2,
            THEME.accent());
    }

    // --- number cells ----------------------------------------------------------------------------------------

    private void beginEdit(int kind, int index, int value) {
        commitEdit();
        search.setFocused(false);
        link.setFocused(false);
        editKind = kind;
        editIndex = index;
        numberEdit = new GreenhouseNumberEdit(value, kind == KIND_COUNT ? GreenhouseState.COUNT
            : kind == KIND_SECONDS ? GreenhouseState.SECONDS : GreenhouseState.PRIORITY);
    }

    private void commitEdit() {
        if (numberEdit == null) return;
        GreenhouseNumberEdit edit = numberEdit;
        numberEdit = null;
        switch (editKind) {
            case KIND_COUNT -> {
                if (editIndex < state.targets().size()) {
                    state.setCount(editIndex, edit.commit(state.targets().get(editIndex).count()));
                }
            }
            case KIND_SECONDS -> state.setSeconds(edit.commit(state.seconds()));
            default -> {
                if (editIndex < priorityCrops.size()) {
                    String crop = priorityCrops.get(editIndex);
                    state.setPriority(crop, edit.commit(state.priority(crop)));
                }
            }
        }
        session.persist();
    }

    private void step(int cropIndex, int delta) {
        commitEdit();
        String crop = priorityCrops.get(cropIndex);
        state.setPriority(crop, state.priority(crop) + delta);
        session.persist();
    }

    // --- input -----------------------------------------------------------------------------------------------

    /**
     * A dropdown choice: adds the target (or finds the existing one), scrolls its row into view and flashes it,
     * and closes the list, since it overlays the rows below it.
     */
    private void pick(int index) {
        if (index >= matches.size()) return;
        String name = matches.get(index).name();
        boolean added = state.add(name);
        if (added) session.persist();
        int row = state.indexOf(name);
        int rows = state.targets().size();
        if (row >= 0 && row < targetScroll.offset()) targetScroll.setOffset(row, rows, SEL_ROWS);
        else if (row >= targetScroll.offset() + SEL_ROWS) targetScroll.setOffset(row - SEL_ROWS + 1, rows, SEL_ROWS);
        flashName = name;
        flashUntilNanos = System.nanoTime() + FLASH_NANOS;
        search.setValue("");
        search.setFocused(false);
        say(added ? "Added " + name : name + " is already selected", added ? Tone.SUCCESS : Tone.WARNING);
    }

    private void togglePriorities() {
        commitEdit();
        state.setPrioritiesOpen(!state.prioritiesOpen());
        session.persist();
    }

    /** One step on a target's count (Shift: ten). A step on a Max row turns it into a fixed target, as typing does. */
    private void stepTarget(int index, int delta) {
        commitEdit();
        state.setMode(index, GreenhouseState.Mode.TARGET);
        state.stepCount(index, delta);
        session.persist();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0 && event.button() != 1) return false;
        layout();
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        if (notice.text() != null && inside(mx, my, noticeLeft, noticeTop, noticeWidth, NOTICE_H)) {
            // The popup is a small modal strip: a click on its x closes it, any other click on it is spent.
            if (event.button() == 0 && inside(mx, my, noticeLeft + noticeWidth - NOTICE_X_W - 2, noticeTop + 2,
                NOTICE_X_W, NOTICE_X_W)) {
                notice.dismiss();
            }
            return true;
        }
        boolean onSearch = search.contains(mx, my);
        boolean onLink = link.contains(mx, my);
        if (dropdownOpen() && !onSearch) {
            if (inDropdown(mx, my)) {
                int slot = Math.floorDiv(my - (y + DROP_Y + 1), DROP_ROW);
                if (event.button() == 0 && slot >= 0 && slot < DROP_ROWS) pick(dropdownScroll.offset() + slot);
                return true;
            }
            // A click anywhere else closes the list and then acts normally; the list overlays, so nothing moves.
            search.setFocused(false);
        }
        if (onLink || onSearch) {
            commitEdit();
            link.setFocused(onLink);
            search.setFocused(onSearch);
            (onLink ? link : search).mouseClicked(new MouseButtonEvent(mx, my, event.buttonInfo()), doubleClick);
            return true;
        }
        link.setFocused(false);
        if (event.button() != 0) return true;
        commitEdit();
        int cell = cellAt(event.x(), event.y());
        if (cell >= 0) {
            if (editingCells) state.beginPaint(cell);
            return true;
        }
        if (inside(mx, my, toggleLeft(), y + 8, TOGGLE_W, 18)) {
            togglePriorities();
            return true;
        }
        toolbar();
        for (int i = 0; i < TOOLS.length; i++) {
            if (inside(mx, my, x + MX + toolLeft[i], y + TOOL_Y, toolWidth[i], TOOL_H)) {
                tool(i);
                return true;
            }
        }
        if (clickCrop(mx, my)) return true;
        if (!clickLeft(mx, my, event.hasShiftDown())) clickRight(mx, my, event.hasShiftDown());
        return true;
    }

    /** A click on a crop of the shopping list pulls what is missing of it from the Sacks (see GreenhouseSupplyRunner#fetch). */
    private boolean clickCrop(int mx, int my) {
        if (!inside(mx, my, x + CROPS_X, y + RESULT_Y, MW - 156, RESULT_ROWS * RESULT_ROW)) return false;
        int index = cropScroll.offset() + (my - (y + RESULT_Y)) / RESULT_ROW;
        if (index >= cropRows.size()) return false;
        CropRow row = cropRows.get(index);
        GreenhouseSupplyRunner.fetch(row.name(), row.count());
        say("Checked your Sacks for " + row.name() + " (see chat)", Tone.MUTED);
        return true;
    }

    private boolean clickLeft(int mx, int my, boolean shift) {
        int left = x + LX;
        if (inside(mx, my, left + LW - 88, y + CARD_Y + 4, 80, 18)) {
            editingCells = !editingCells;
            say(editingCells ? "Click or drag grid cells to lock or unlock them" : "Cell editing off", Tone.MUTED);
            return true;
        }
        if (inside(mx, my, left + 8, y + CARD_Y + 28, 124, 18)) {
            state.unlockAll();
            session.persist();
            say("All cells unlocked", Tone.MUTED);
            return true;
        }
        if (inside(mx, my, left + 138, y + CARD_Y + 28, 124, 18)) {
            state.unlockAll();
            editingCells = false;
            session.persist();
            say("Cells reset", Tone.MUTED);
            return true;
        }
        if (inside(mx, my, left, y + SOLVE_Y, 84, 22)) {
            if (session.running()) {
                session.stop();
            } else {
                commitEdit();
                session.solve();
            }
            return true;
        }
        if (inside(mx, my, left + STOP_EARLY_X, y + SOLVE_Y + 2, STOP_EARLY_W, 18)) {
            state.setStopEarly(!state.stopEarly());
            session.persist();
            say(state.stopEarly() ? "Stop early on: the search may end before the time limit"
                : "Stop early off: the search uses the whole time limit", Tone.MUTED);
            return true;
        }
        if (inside(mx, my, left + PRICE_SEG_X, y + PRICE_Y, PRICE_SEG_W, 18)) {
            setCostMode(CostMode.BAZAAR);
            return true;
        }
        if (inside(mx, my, left + PRICE_SEG_X + PRICE_SEG_W, y + PRICE_Y, PRICE_SEG_W, 18)) {
            setCostMode(CostMode.IRONMAN);
            return true;
        }
        if (inside(mx, my, left, y + HOLO_Y, HOLO_W, HOLO_H)) {
            perform(GreenhouseActions.showLayout(session.plan().grid(), session.running(),
                layout -> GreenhouseHologram.show(layout, state.mask())));
            return true;
        }
        if (GreenhouseHologram.isShowing() && inside(mx, my, left + LW - HOLO_W, y + HOLO_Y, HOLO_W, HOLO_H)) {
            GreenhouseHologram.hide();
            say("Hologram hidden", Tone.MUTED);
            return true;
        }
        if (inside(mx, my, left, y + HOLO_CLIP_Y, LW, HOLO_H)) {
            perform(GreenhouseActions.showClipboard(GreenhouseHologram::showFromClipboard));
            return true;
        }
        if (inside(mx, my, left + TIME_FIELD_X, y + SOLVE_Y + 2, TIME_FIELD_W, 18)) {
            beginEdit(KIND_SECONDS, 0, state.seconds());
            return true;
        }
        int row = (my - (y + SEL_Y)) / SEL_ROW;
        if (my >= y + SEL_Y && row < SEL_ROWS && mx >= left && mx < left + LW) {
            int index = targetScroll.offset() + row;
            if (index < state.targets().size()) {
                clickTarget(index, mx - left, my - (y + SEL_Y + row * SEL_ROW + 2), shift);
                return true;
            }
        }
        return false;
    }

    /** The weighting only matters to the next solve; nothing is re-run, and the price line below follows the choice. */
    private void setCostMode(CostMode mode) {
        if (state.costMode() == mode) return;
        state.setCostMode(mode);
        session.persist();
        say(mode == CostMode.IRONMAN ? "Prices: Ironman, the next solve weighs by rarity"
            : "Prices: Bazaar, the next solve weighs by coins", Tone.MUTED);
        session.refreshPrices();
    }

    private void clickTarget(int index, int dx, int dy, boolean shift) {
        if (dx >= TARGET_X && dx < TARGET_X + TARGET_W) {
            state.setMode(index, GreenhouseState.Mode.TARGET);
            session.persist();
        } else if (dx >= MAX_X && dx < MAX_X + MAX_W) {
            state.setMode(index, GreenhouseState.Mode.MAX);
            session.persist();
        } else if (dx >= COUNT_X + COUNT_W - ARROW_W && dx < COUNT_X + COUNT_W) {
            if (dy >= 0 && dy < 16) stepTarget(index, (dy < 8 ? 1 : -1) * (shift ? 10 : 1));
        } else if (dx >= COUNT_X && dx < COUNT_X + COUNT_W) {
            // Typing a count only means something for a fixed target, so the click switches to it.
            state.setMode(index, GreenhouseState.Mode.TARGET);
            beginEdit(KIND_COUNT, index, state.targets().get(index).count());
        } else if (dx >= REMOVE_X && dx < REMOVE_X + REMOVE_W) {
            state.remove(index);
            targetScroll.scrollBy(0, state.targets().size(), SEL_ROWS);
            session.persist();
        }
    }

    private boolean clickRight(int mx, int my, boolean shift) {
        if (!state.prioritiesOpen()) return false;
        int left = x + RX;
        if (inside(mx, my, left + RW - 112, y + 34, 112, 18)) {
            state.resetPriorities();
            session.persist();
            say("Priorities reset", Tone.MUTED);
            return true;
        }
        int row = (my - (y + PRIO_Y)) / PRIO_ROW;
        if (my < y + PRIO_Y || row >= PRIO_ROWS || mx < left || mx >= left + RW) return false;
        int index = priorityScroll.offset() + row;
        if (index >= priorityCrops.size()) return false;
        int dx = mx - left;
        int amount = shift ? 10 : 1;
        if (dx >= MINUS_X && dx < MINUS_X + STEP_W) step(index, -amount);
        else if (dx >= PLUS_X && dx < PLUS_X + STEP_W) step(index, amount);
        else if (dx >= VALUE_X && dx < VALUE_X + VALUE_W) beginEdit(KIND_PRIORITY, index, state.priority(priorityCrops.get(index)));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (state.painting()) {
            // Only while Edit cells is on: a stroke that outlived the toggle or the screen must not flip locks.
            int cell = editingCells ? cellAt(event.x(), event.y()) : -1;
            if (cell >= 0) state.paint(cell);
            return true;
        }
        UiTextField focused = link.isFocused() ? link : search.isFocused() ? search : null;
        return focused != null && focused.mouseDragged(
            new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()),
            dragX / scale(), dragY / scale());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (state.painting()) {
            state.endPaint();
            session.persist();
        }
        MouseButtonEvent scaled = new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo());
        link.mouseReleased(scaled);
        search.mouseReleased(scaled);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int mx = coordinate(mouseX);
        int my = coordinate(mouseY);
        int direction = scrollY < 0 ? 1 : scrollY > 0 ? -1 : 0;
        if (dropdownOpen() && inDropdown(mx, my)) {
            dropdownScroll.scrollBy(direction, matches.size(), DROP_ROWS);
        } else if (inside(mx, my, x + LX, y + SEL_Y, LW, SEL_ROWS * SEL_ROW)) {
            int index = targetScroll.offset() + (my - (y + SEL_Y)) / SEL_ROW;
            int dx = mx - (x + LX);
            if (index < state.targets().size() && dx >= COUNT_X && dx < COUNT_X + COUNT_W) {
                // The wheel over a count changes it (up raises), Shift in tens, instead of scrolling the list.
                if (scrollY != 0) stepTarget(index, (scrollY > 0 ? 1 : -1) * (Minecraft.getInstance().hasShiftDown() ? 10 : 1));
            } else {
                targetScroll.scrollBy(direction, state.targets().size(), SEL_ROWS);
            }
        } else if (state.prioritiesOpen() && inside(mx, my, x + RX, y + PRIO_Y, RW, PRIO_ROWS * PRIO_ROW)) {
            priorityScroll.scrollBy(direction * 3, priorityCrops.size(), PRIO_ROWS);
        } else if (inside(mx, my, x + MX, y + RESULT_Y, HALF, RESULT_ROWS * RESULT_ROW)) {
            goalScroll.scrollBy(direction, goalRows.size(), RESULT_ROWS);
        } else if (inside(mx, my, x + CROPS_X, y + RESULT_Y, MW - 156, RESULT_ROWS * RESULT_ROW)) {
            cropScroll.scrollBy(direction, cropRows.size(), RESULT_ROWS);
        } else {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // One Escape per layer: the dropdown, then a number being typed, then the screen.
            if (search.isFocused()) search.setFocused(false);
            else if (numberEdit != null) numberEdit = null;
            else onClose();
            return true;
        }
        if (numberEdit != null) {
            if (enter || key == GLFW.GLFW_KEY_TAB) commitEdit();
            else if (key == GLFW.GLFW_KEY_BACKSPACE) numberEdit.backspace();
            return true;
        }
        if (link.isFocused()) {
            if (enter) load(link.value());
            else link.keyPressed(event);
            return true;
        }
        if (search.isFocused()) {
            if (enter) {
                if (!matches.isEmpty()) pick(0);
            } else {
                search.keyPressed(event);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (numberEdit != null) {
            numberEdit.type(event.codepoint());
            return true;
        }
        if (link.isFocused()) return link.charTyped(event);
        return search.isFocused() && search.charTyped(event);
    }
}
