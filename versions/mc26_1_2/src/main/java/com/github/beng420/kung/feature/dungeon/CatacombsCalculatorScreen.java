package com.github.beng420.kung.feature.dungeon;

import static com.github.beng420.kung.ui.UiBounds.inside;
import static com.github.beng420.kung.util.CatacombsAverageCalculator.levelFromXp;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.util.CatacombsAverageCalculator;
import com.github.beng420.kung.util.CatacombsAverageCalculator.Breakdown;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.FloorStats;
import com.github.beng420.kung.util.CatacombsAverageCalculator.PlayerData;
import com.github.beng420.kung.util.CatacombsAverageCalculator.ProfileData;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.skyblock.SkyBlockMayorTracker;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiTextField;
import com.github.beng420.kung.ui.UiScrollList;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.ui.UiSpacing;
import com.github.beng420.kung.ui.UiTextStyle;
import com.github.beng420.kung.ui.UiNumberField;
import com.github.beng420.kung.ui.UiToggle;
import com.github.beng420.kung.util.KeyCodes;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class CatacombsCalculatorScreen extends Screen {
    private static final UiTheme THEME = UiTheme.catacombsCalculator();
    private static final UiTextStyle TEXT_STYLE = UiTextStyle.normal(THEME);

    private static final int TOP = 28;
    private static final int GAP = 10;
    private static final int ROW = 19;
    private static final int CONTROL_HEIGHT = UiSpacing.XL;
    private static final int CONTENT_HEIGHT = 838;
    private static final int SCROLL_STEP = 36;
    private static final UiNumberField HECATOMB_LEVEL = new UiNumberField(0, 10, 1);
    private static final UiNumberField GRADUATE_LEVEL = new UiNumberField(0, 10, 1);
    private static final UiNumberField EXPLORER_LEVEL = new UiNumberField(0, 10, 1);
    private static final UiNumberField TARGET_LEVEL = new UiNumberField(1, 200, 1);
    private static final UiNumberField CLASS_PERK_LEVEL = new UiNumberField(0, 5, 1);
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private static final DungeonClass[] CLASSES = DungeonClass.values();
    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private long loadRequestId;
    private final List<ClickRegion> clickRegions = new ArrayList<>();
    private final String initialUsername;
    private final EnumMap<DungeonClass, Integer> classPerks = new EnumMap<>(DungeonClass.class);
    private UiTextField usernameBox;
    private LoadState loadState = LoadState.IDLE;
    private String statusMessage = "Optional: load a player to autofill stats.";
    private PlayerData loadedPlayer;
    private int selectedProfileIndex;
    private FloorOption selectedFloor = FloorOption.M7;
    private boolean expertRing = true;
    private int hecatombLevel = 10;
    private ScarfAccessory scarfAccessory = ScarfAccessory.GRIMOIRE;
    private double globalBoost;
    private int graduateLevel = 10;
    private int explorerLevel = 10;
    private boolean explorerPreset = true;
    private int targetCatacombsLevel = 50;
    private int targetClassLevel = 50;
    private MayorBoost manualMayorBoost;
    private boolean autoLoadStarted;
    private boolean copiedClassAverage;
    private boolean copiedCatacombs;
    private final UiScrollList verticalScroll = new UiScrollList();

    public CatacombsCalculatorScreen(String username) {
        super(Component.literal("Kung Catacombs Calculator"));
        this.initialUsername = username == null ? "" : username.trim();
        for (DungeonClass dungeonClass : CLASSES) {
            classPerks.put(dungeonClass, 5);
        }
    }

    @Override
    protected void init() {
        if (usernameBox == null) {
            String name = initialUsername;
            if (name.isBlank() && Minecraft.getInstance().player != null) {
                name = Minecraft.getInstance().player.getName().getString();
            }
            usernameBox = new UiTextField(font, Component.literal("Username"))
                .maxLength(16)
                .textShadow(true);
            usernameBox.setValue(name);
        }
        layoutUsernameBox();
        if (!autoLoadStarted && !usernameBox.value().isBlank()) {
            autoLoadStarted = true;
            loadPlayer(usernameBox.value());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (usernameBox != null) usernameBox.setFocused(false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        verticalScroll.setOffsetWithinMax(verticalScroll.offset(), maxVerticalScroll());
        clickRegions.clear();
        layoutUsernameBox();
        graphics.fill(0, 0, width, height, THEME.backdrop());
        drawTopBar(graphics, mouseX, mouseY, partialTick);

        int contentTop = 78 - verticalScroll.offset();
        int leftWidth = Math.min(340, Math.max(260, width / 3));
        int rightX = GAP + leftWidth + GAP;
        int rightWidth = width - rightX - GAP;
        if (rightWidth < 300) {
            leftWidth = Math.max(240, width - 320 - GAP * 3);
            rightX = GAP + leftWidth + GAP;
            rightWidth = width - rightX - GAP;
        }
        drawLeftPanel(graphics, GAP, contentTop, leftWidth, mouseX, mouseY);
        drawRightPanel(graphics, rightX, contentTop, Math.max(260, rightWidth), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = toGuiX(event.x());
        int mouseY = toGuiY(event.y());
        if (usernameBox != null) {
            MouseButtonEvent guiEvent = new MouseButtonEvent(mouseX, mouseY, event.buttonInfo());
            if (usernameBox.contains(mouseX, mouseY)) {
                usernameBox.mouseClicked(guiEvent, doubleClick);
                return true;
            }
            usernameBox.setFocused(false);
        }
        for (int index = clickRegions.size() - 1; index >= 0; index--) {
            ClickRegion region = clickRegions.get(index);
            if (region.bounds().contains(mouseX, mouseY)) {
                region.action().click(mouseX, mouseY, McInput.button(event));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int direction = scrollY < 0 ? 1 : -1;
        verticalScroll.scrollByWithinMax(direction * SCROLL_STEP, maxVerticalScroll());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (McInput.key(event) == KeyCodes.ESCAPE) {
            onClose();
            return true;
        }
        if (usernameBox != null && usernameBox.isFocused()) {
            if (McInput.key(event) == KeyCodes.ENTER || McInput.key(event) == KeyCodes.KP_ENTER) {
                loadPlayer(usernameBox.value());
                return true;
            }
            return usernameBox.keyPressed(event);
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (usernameBox != null && usernameBox.isFocused()) {
            return usernameBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    private void drawTopBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        String title = "Kung Catacombs Calculator";
        graphics.text(font, title, width / 2 - font.width(title) / 2, UiSpacing.MD,
            TEXT_STYLE.color(), TEXT_STYLE.shadow());
        graphics.text(font, "Username:", usernameBox.x() - 58, TOP + 5, THEME.text(), true);
        usernameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawButton(graphics, usernameBox.x() + usernameBox.width() + 8, TOP, 54, 20, "Load", mouseX, mouseY,
            () -> loadPlayer(usernameBox.value()));
        drawStatus(graphics, usernameBox.x(), TOP + 24);
    }

    private void drawStatus(GuiGraphicsExtractor graphics, int x, int y) {
        int color = switch (loadState) {
            case LOADED -> THEME.success();
            case ERROR -> THEME.error();
            case LOADING -> THEME.warning();
            default -> THEME.muted();
        };
        graphics.text(font, trim(statusMessage, Math.min(420, width - x - GAP)), x, y, color, true);
    }

    private void drawLeftPanel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int mouseX, int mouseY) {
        drawPanel(graphics, x, y, panelWidth, CONTENT_HEIGHT);
        int lineY = y + 18;
        drawHeading(graphics, "Dungeon levels", x + 12, lineY);
        lineY += 28;
        ProfileData profile = selectedProfile();
        if (profile == null) {
            drawMuted(graphics, "Load a player to fill this side.", x + 14, lineY);
            lineY += 34;
        } else {
            if (loadedPlayer != null && loadedPlayer.profiles().size() > 1) {
                lineY += drawProfileButtons(graphics, x + 14, lineY, panelWidth - 28, mouseX, mouseY) + 8;
            }
            drawLevelBar(graphics, x + 14, lineY, panelWidth - 28, "Catacombs", THEME.text(), profile.cataXp(), true);
            lineY += 42;
            for (DungeonClass dungeonClass : CLASSES) {
                drawLevelBar(graphics, x + 14, lineY, panelWidth - 28, dungeonClass.label(), dungeonClass.color(), profile.classXp(dungeonClass), false);
                lineY += 42;
            }
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Class Average", decimal(classAverage(profile)), THEME.text());
            lineY += ROW;
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Daily runs remaining", String.valueOf(Math.max(0, 5 - profile.stats().dailyRuns())), THEME.text());
            lineY += ROW + 13;
        }

        drawHeading(graphics, "Dungeon stats", x + 12, lineY);
        lineY += 24;
        if (profile != null) {
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Profile", selectedProfileName(), THEME.text());
            lineY += ROW;
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Secrets found", profile.stats().secrets() < 0 ? "..." : format(profile.stats().secrets()), THEME.text());
            lineY += ROW;
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Journals completed", profile.stats().journals() + " / 25", THEME.text());
            lineY += ROW;
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Selected class", classLabel(profile.stats().selectedClass()), THEME.text());
            lineY += ROW;
            drawKV(graphics, x + 14, lineY, panelWidth - 28, "Total runs", format(profile.stats().catacombs().totalCompletions()), THEME.text());
            lineY += ROW + 13;
        } else {
            drawMuted(graphics, "...", x + 14, lineY);
            lineY += 38;
        }

        drawHeading(graphics, "Floor stats", x + 12, lineY);
        lineY += 24;
        FloorStats stats = profile == null ? null : selectedFloor.masterMode ? profile.stats().master() : profile.stats().catacombs();
        String floorNumber = selectedFloor.floorNumber;
        drawKV(graphics, x + 14, lineY, panelWidth - 28, "Best score", stats == null ? "..." : floorStat(stats.bestScore(), floorNumber), THEME.text());
        lineY += ROW;
        drawKV(graphics, x + 14, lineY, panelWidth - 28, "Personal best (S+)", stats == null ? "..." : duration(stats.sPlus(), floorNumber), THEME.text());
        lineY += ROW;
        drawKV(graphics, x + 14, lineY, panelWidth - 28, "Personal best (S)", stats == null ? "..." : duration(stats.s(), floorNumber), THEME.text());
        lineY += ROW;
        drawKV(graphics, x + 14, lineY, panelWidth - 28, "Completions", stats == null ? "..." : format((long) stats.value(stats.completions(), floorNumber)), THEME.text());
    }

    private void drawRightPanel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int mouseX, int mouseY) {
        drawPanel(graphics, x, y, panelWidth, CONTENT_HEIGHT);
        int lineY = y + 18;
        drawHeading(graphics, "Milestone calculations", x + 12, lineY);
        lineY += 24;
        drawMuted(graphics, "Values update instantly. Mayor boost follows the current election unless changed here.", x + 12, lineY);
        lineY += 28;

        int columnWidth = (panelWidth - 36) / 2;
        int leftX = x + 14;
        int rightX = leftX + columnWidth + 10;
        int rowY = lineY;
        drawControl(graphics, leftX, rowY, columnWidth, "Floor", selectedFloor.label, mouseX, mouseY,
            () -> selectedFloor = cycle(selectedFloor, -1),
            () -> selectedFloor = cycle(selectedFloor, 1));
        drawToggleControl(graphics, rightX, rowY, columnWidth, "Catacombs Expert Ring", expertRing,
            () -> expertRing = !expertRing);
        rowY += 38;
        drawControl(graphics, leftX, rowY, columnWidth, "Hecatomb", hecatombLabel(), mouseX, mouseY,
            () -> hecatombLevel = HECATOMB_LEVEL.decrement(hecatombLevel),
            () -> hecatombLevel = HECATOMB_LEVEL.increment(hecatombLevel));
        drawControl(graphics, rightX, rowY, columnWidth, "Scarf accessory", scarfAccessory.label, mouseX, mouseY,
            () -> scarfAccessory = cycle(scarfAccessory, -1),
            () -> scarfAccessory = cycle(scarfAccessory, 1));
        rowY += 38;
        drawControl(graphics, leftX, rowY, columnWidth, "Global boost", boostLabel(globalBoost), mouseX, mouseY,
            () -> globalBoost = cycleGlobalBoost(-1),
            () -> globalBoost = cycleGlobalBoost(1));
        MayorBoost mayorBoost = currentMayorBoost();
        drawControl(graphics, rightX, rowY, columnWidth, "Mayor boost", mayorLabel(mayorBoost), mouseX, mouseY,
            () -> manualMayorBoost = cycle(mayorBoost, -1),
            () -> manualMayorBoost = cycle(mayorBoost, 1));
        rowY += 38;
        drawControl(graphics, leftX, rowY, columnWidth, "Catacombs Graduate", romanLevel(graduateLevel) + " (" + (graduateLevel * 2) + "%)", mouseX, mouseY,
            () -> graduateLevel = GRADUATE_LEVEL.decrement(graduateLevel),
            () -> graduateLevel = GRADUATE_LEVEL.increment(graduateLevel));
        drawControl(graphics, rightX, rowY, columnWidth, "Target Catacombs level", String.valueOf(targetCatacombsLevel), mouseX, mouseY,
            () -> targetCatacombsLevel = TARGET_LEVEL.decrement(targetCatacombsLevel),
            () -> targetCatacombsLevel = TARGET_LEVEL.increment(targetCatacombsLevel));
        rowY += 38;
        drawControl(graphics, leftX, rowY, columnWidth, "Catacombs Explorer" + (explorerPreset ? " (preset)" : ""),
            romanLevel(explorerLevel) + " (" + explorerLevel + "%)", mouseX, mouseY,
            () -> setExplorerLevel(EXPLORER_LEVEL.decrement(explorerLevel)),
            () -> setExplorerLevel(EXPLORER_LEVEL.increment(explorerLevel)));
        drawControl(graphics, rightX, rowY, columnWidth, "Target level (each class)", String.valueOf(targetClassLevel), mouseX, mouseY,
            () -> targetClassLevel = TARGET_LEVEL.decrement(targetClassLevel),
            () -> targetClassLevel = TARGET_LEVEL.increment(targetClassLevel));
        rowY += 44;

        drawMuted(graphics, "Class XP boost perks (Essence shop, each level +2%)", leftX, rowY);
        rowY += 18;
        for (DungeonClass dungeonClass : CLASSES) {
            int perkLevel = classPerks.getOrDefault(dungeonClass, 0);
            drawInlineControl(graphics, leftX, rowY, panelWidth - 28,
                dungeonClass.perkName() + " (" + dungeonClass.label() + " XP)",
                romanLevel(perkLevel) + " (" + (perkLevel * 2) + "%)",
                mouseX,
                mouseY,
                () -> classPerks.put(dungeonClass, CLASS_PERK_LEVEL.decrement(perkLevel)),
                () -> classPerks.put(dungeonClass, CLASS_PERK_LEVEL.increment(perkLevel)));
            rowY += 29;
        }
        rowY += 10;

        Calculation calculation = calculate();
        int resultHeight = 178;
        graphics.fill(leftX, rowY, x + panelWidth - 14, rowY + resultHeight, THEME.panelDark());
        int resultY = rowY + 10;
        drawKV(graphics, leftX + 12, resultY, panelWidth - 52, "Catacombs XP per run", format(calculation.cataPerRun()), THEME.warning());
        resultY += ROW;
        drawKV(graphics, leftX + 12, resultY, panelWidth - 52, "Class XP per run (average)", format(Math.round(calculation.averageClassPerRun())), THEME.warning());
        resultY += ROW + 5;
        drawKV(graphics, leftX + 12, resultY, panelWidth - 52, "Runs to Catacombs " + calculation.targetLevel(), runsLabel(calculation.runsToCatacombs()), THEME.text());
        resultY += ROW;
        Breakdown breakdown = calculation.breakdown();
        for (DungeonClass dungeonClass : CLASSES) {
            drawKV(graphics, leftX + 12, resultY, panelWidth - 52, "Runs to " + dungeonClass.label() + " " + calculation.classTargetLevel(),
                breakdown == null ? "..." : runsLabel(breakdown.perClass(dungeonClass)), THEME.text());
            resultY += 15;
        }
        drawKV(graphics, leftX + 12, resultY, panelWidth - 52, "Runs to all classes " + calculation.classTargetLevel(),
            breakdown == null ? "..." : runsLabel(breakdown.total()), THEME.text());

        rowY += resultHeight + 14;
        String classAverageSummary = classAverageSummaryLine(calculation);
        rowY += drawWrappedText(graphics, classAverageSummary, leftX, rowY, panelWidth - 28, THEME.muted(), 3) + 8;
        drawButton(graphics, x + panelWidth - 86, rowY, 72, 20, copiedClassAverage ? "Copied!" : "Copy", mouseX, mouseY,
            () -> copyClassAverageSummary(calculation));
        rowY += 28;
        String catacombsSummary = catacombsSummaryLine(calculation);
        rowY += drawWrappedText(graphics, catacombsSummary, leftX, rowY, panelWidth - 28, THEME.muted(), 2) + 8;
        drawButton(graphics, x + panelWidth - 86, rowY, 72, 20, copiedCatacombs ? "Copied!" : "Copy", mouseX, mouseY,
            () -> copyCatacombsSummary(calculation));
        rowY += 28;
        if (manualMayorBoost != null) {
            drawButton(graphics, leftX, rowY, 78, 20, "Auto mayor", mouseX, mouseY, () -> manualMayorBoost = null);
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int panelHeight) {
        graphics.fill(x - 2, y - 2, x + panelWidth + 2, y + panelHeight + 2, THEME.border());
        graphics.fill(x, y, x + panelWidth, y + panelHeight, THEME.panel());
    }

    private int drawProfileButtons(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int panelWidth,
        int mouseX,
        int mouseY
    ) {
        int cursorX = x;
        int cursorY = y;
        int buttonHeight = 18;
        for (int index = 0; index < loadedPlayer.profiles().size(); index++) {
            ProfileData profile = loadedPlayer.profiles().get(index);
            String label = profile.selected() ? "* " + profile.cuteName() : profile.cuteName();
            int buttonWidth = Math.min(panelWidth, Math.max(50, font.width(label) + 14));
            if (cursorX > x && cursorX + buttonWidth > x + panelWidth) {
                cursorX = x;
                cursorY += buttonHeight + 4;
            }
            boolean selected = index == selectedProfileIndex;
            boolean hovered = inside(mouseX, mouseY, cursorX, cursorY, buttonWidth, buttonHeight);
            graphics.fill(cursorX, cursorY, cursorX + buttonWidth, cursorY + buttonHeight,
                selected ? THEME.controlHover() : hovered ? THEME.panelSoft() : THEME.panelDark());
            String shown = trim(label, buttonWidth - 8);
            graphics.text(font, shown, cursorX + buttonWidth / 2 - font.width(shown) / 2, cursorY + 5,
                selected ? THEME.warning() : THEME.text(), true);
            int profileIndex = index;
            addClickRegion(cursorX, cursorY, buttonWidth, buttonHeight, (clickX, clickY, button) -> {
                if (button == 0) {
                    selectedProfileIndex = profileIndex;
                    applyProfilePerks();
                    copiedClassAverage = false;
                    copiedCatacombs = false;
                }
            });
            cursorX += buttonWidth + 5;
        }
        return cursorY + buttonHeight - y;
    }

    private void drawHeading(GuiGraphicsExtractor graphics, String text, int x, int y) {
        graphics.text(font, text, x, y, THEME.text(), true);
    }

    private void drawMuted(GuiGraphicsExtractor graphics, String text, int x, int y) {
        graphics.text(font, trim(text, width - x - GAP), x, y, THEME.muted(), true);
    }

    private void drawLevelBar(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int barWidth,
        String label,
        int labelColor,
        double xp,
        boolean highlight
    ) {
        double level = levelFromXp(xp);
        String value = decimal(level);
        graphics.text(font, label, x, y, labelColor, true);
        graphics.text(font, value, x + barWidth - font.width(value), y, THEME.text(), true);
        int barY = y + 13;
        graphics.fill(x, barY, x + barWidth, barY + 5, THEME.panelDark());
        double progress = level - Math.floor(level);
        int fillWidth = (int) Math.round(barWidth * Math.clamp(progress, 0.0, 1.0));
        graphics.fill(x, barY, x + fillWidth, barY + 5, highlight ? THEME.warning() : THEME.accent());
        String xpLabel = compactXp(xp) + " XP";
        graphics.text(font, xpLabel, x + barWidth - font.width(xpLabel), barY + 8, THEME.muted(), true);
    }

    private void drawKV(GuiGraphicsExtractor graphics, int x, int y, int rowWidth, String label, String value, int valueColor) {
        graphics.text(font, trim(label, Math.max(20, rowWidth - 80)), x, y, THEME.muted(), true);
        String shown = trim(value, Math.max(40, rowWidth / 2));
        graphics.text(font, shown, x + rowWidth - font.width(shown), y, valueColor, true);
    }

    private void drawControl(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int controlWidth,
        String label,
        String value,
        int mouseX,
        int mouseY,
        Runnable previous,
        Runnable next
    ) {
        graphics.text(font, trim(label, controlWidth - 8), x, y, THEME.text(), true);
        drawStepper(graphics, x, y + 12, controlWidth, value, mouseX, mouseY, previous, next);
    }

    private void drawStepper(GuiGraphicsExtractor graphics, int x, int boxY, int controlWidth, String value,
                             int mouseX, int mouseY, Runnable previous, Runnable next) {
        graphics.fill(x, boxY, x + controlWidth, boxY + CONTROL_HEIGHT, THEME.control());
        boolean leftHover = inside(mouseX, mouseY, x, boxY, 16, CONTROL_HEIGHT);
        boolean rightHover = inside(mouseX, mouseY, x + controlWidth - 16, boxY, 16, CONTROL_HEIGHT);
        graphics.fill(x, boxY, x + 16, boxY + CONTROL_HEIGHT, leftHover ? THEME.controlHover() : THEME.control());
        graphics.fill(x + controlWidth - 16, boxY, x + controlWidth, boxY + CONTROL_HEIGHT, rightHover ? THEME.controlHover() : THEME.control());
        graphics.text(font, "<", x + 5, boxY + 4, THEME.text(), true);
        graphics.text(font, ">", x + controlWidth - 11, boxY + 4, THEME.text(), true);
        String shown = trim(value, controlWidth - 38);
        graphics.text(font, shown, x + controlWidth / 2 - font.width(shown) / 2, boxY + 4, THEME.text(), true);
        addClickRegion(x, boxY, controlWidth / 2, CONTROL_HEIGHT, (clickX, clickY, button) -> previous.run());
        addClickRegion(x + controlWidth / 2, boxY, controlWidth - controlWidth / 2, CONTROL_HEIGHT, (clickX, clickY, button) -> next.run());
    }

    private void drawToggleControl(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int controlWidth,
        String label,
        boolean enabled,
        Runnable toggle
    ) {
        graphics.text(font, trim(label, controlWidth - 8), x, y, THEME.text(), true);
        int boxY = y + 12;
        UiBounds bounds = new UiBounds(x, boxY, controlWidth, CONTROL_HEIGHT);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), THEME.control());
        UiToggle.draw(graphics, bounds, enabled, THEME);
        addClickRegion(bounds.x(), bounds.y(), bounds.width(), bounds.height(), (clickX, clickY, button) -> {
            if (button == 0) {
                toggle.run();
            }
        });
    }

    private void drawInlineControl(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int rowWidth,
        String label,
        String value,
        int mouseX,
        int mouseY,
        Runnable previous,
        Runnable next
    ) {
        int controlWidth = Math.min(150, Math.max(104, rowWidth / 4));
        int controlX = x + rowWidth - controlWidth;
        graphics.text(font, trim(label, Math.max(20, rowWidth - controlWidth - 12)), x, y + 5, THEME.text(), true);
        drawStepper(graphics, controlX, y + 1, controlWidth, value, mouseX, mouseY, previous, next);
    }

    private int drawWrappedText(
        GuiGraphicsExtractor graphics,
        String text,
        int x,
        int y,
        int maxWidth,
        int color,
        int maxLines
    ) {
        List<String> lines = wrappedLines(text, maxWidth, maxLines);
        int cursorY = y;
        for (String line : lines) {
            graphics.text(font, line, x, cursorY, color, true);
            cursorY += 12;
        }
        return Math.max(0, cursorY - y);
    }

    private List<String> wrappedLines(String text, int maxWidth, int maxLines) {
        if (text == null || text.isBlank() || maxLines <= 0) {
            return List.of();
        }
        ArrayList<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(word);
            if (lines.size() == maxLines - 1) {
                break;
            }
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(trim(current.toString(), maxWidth));
        }
        if (lines.size() == maxLines && font.width(lines.getLast()) > maxWidth) {
            lines.set(maxLines - 1, trim(lines.getLast(), maxWidth));
        }
        return List.copyOf(lines);
    }

    private void drawButton(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int buttonWidth,
        int buttonHeight,
        String label,
        int mouseX,
        int mouseY,
        Runnable action
    ) {
        boolean hovered = inside(mouseX, mouseY, x, y, buttonWidth, buttonHeight);
        graphics.fill(x, y, x + buttonWidth, y + buttonHeight, hovered ? THEME.controlHover() : THEME.control());
        String shown = trim(label, buttonWidth - 6);
        graphics.text(font, shown, x + buttonWidth / 2 - font.width(shown) / 2, y + 6, 0xFF000000, false);
        addClickRegion(x, y, buttonWidth, buttonHeight, (clickX, clickY, button) -> {
            if (button == 0) {
                action.run();
            }
        });
    }

    private void loadPlayer(String username) {
        String name = username == null ? "" : username.trim();
        if (name.isBlank()) {
            loadState = LoadState.ERROR;
            statusMessage = "Enter a username first.";
            return;
        }
        loadState = LoadState.LOADING;
        statusMessage = "Loading " + name + "...";
        copiedClassAverage = false;
        copiedCatacombs = false;
        long requestId = ++loadRequestId;
        loadedPlayer = null;
        HypixelSkyBlockProfileClient.INSTANCE.loadCalculatorPlayer(name)
            .whenComplete((result, throwable) -> Minecraft.getInstance().execute(() -> {
                if (requestId != loadRequestId) return;
                if (throwable != null || result == null) {
                    loadState = LoadState.ERROR;
                    statusMessage = "Could not load player data.";
                    KungMod.LOGGER.warn("Failed to load calculator profile data.", throwable);
                    return;
                }
                if (!result.success()) {
                    loadState = LoadState.ERROR;
                    statusMessage = HypixelSkyBlockProfileClient.lastError(result.error());
                    return;
                }
                loadedPlayer = result.player();
                selectedProfileIndex = Math.max(0, loadedPlayer.selectedIndex());
                applyProfilePerks();
                loadState = LoadState.LOADED;
                statusMessage = (result.local()
                    ? "Loaded " + selectedProfileName() + " from local data (updated " + age(result.localUpdatedMillis()) + ")."
                    : "Loaded " + loadedPlayer.name() + " on " + selectedProfileName() + ".")
                    + (loadedPlayer.selectedProfile().stats().available() ? "" : " No Dungeon stats on this profile.");
            }));
    }

    private static String age(long updatedMillis) {
        long minutes = Math.max(0L, System.currentTimeMillis() - updatedMillis) / 60_000L;
        return minutes < 1 ? "just now" : minutes < 60 ? minutes + "m ago"
            : minutes < 2_880 ? minutes / 60 + "h ago" : minutes / 1_440 + "d ago";
    }

    private void applyProfilePerks() {
        ProfileData profile = selectedProfile();
        if (profile == null) {
            return;
        }
        for (DungeonClass dungeonClass : CLASSES) {
            classPerks.put(dungeonClass, CLASS_PERK_LEVEL.clamp(profile.classPerk(dungeonClass)));
        }
        explorerPreset = profile.stats().explorerLevel() < 0;
        explorerLevel = explorerPreset ? 10 : EXPLORER_LEVEL.clamp(profile.stats().explorerLevel());
    }

    private void setExplorerLevel(int level) {
        explorerLevel = level;
        explorerPreset = false;
    }

    private Calculation calculate() {
        double baseXp = selectedFloor.baseXp;
        double hecatomb = CatacombsAverageCalculator.hecatombBonus(hecatombLevel);
        MayorBoost mayor = currentMayorBoost();
        long cataPerRun = CatacombsAverageCalculator.catacombsXpPerRun(baseXp, expertRing, hecatomb,
            explorerLevel, globalBoost, mayor.multiplier);

        EnumMap<DungeonClass, Double> classXpPerRun = CatacombsAverageCalculator.classXpPerRun(
            classPerks, baseXp, hecatomb, scarfAccessory.bonus, graduateLevel * 0.02,
            globalBoost, mayor.multiplier);
        double average = 0.0;
        for (DungeonClass dungeonClass : CLASSES) {
            average += classXpPerRun.get(dungeonClass);
        }
        average /= CLASSES.length;

        ProfileData profile = selectedProfile();
        Long runsToCatacombs = null;
        Breakdown breakdown = null;
        if (profile != null && profile.stats().available()) {
            runsToCatacombs = CatacombsAverageCalculator.runsToCatacombs(profile.cataXp(), targetCatacombsLevel, cataPerRun);
            breakdown = CatacombsAverageCalculator.calculateBreakdown(profile.classXp(), classXpPerRun, targetClassLevel);
        }
        return new Calculation(cataPerRun, average, targetCatacombsLevel, targetClassLevel, runsToCatacombs, breakdown);
    }

    private void copyClassAverageSummary(Calculation calculation) {
        String summary = classAverageSummaryLine(calculation);
        Minecraft.getInstance().keyboardHandler.setClipboard(summary);
        copiedClassAverage = true;
        copiedCatacombs = false;
    }

    private void copyCatacombsSummary(Calculation calculation) {
        String summary = catacombsSummaryLine(calculation);
        Minecraft.getInstance().keyboardHandler.setClipboard(summary);
        copiedClassAverage = false;
        copiedCatacombs = true;
    }

    private String classAverageSummaryLine(Calculation calculation) {
        if (loadedPlayer == null || calculation.breakdown() == null) {
            return "[Kung] Load a player to calculate ca" + calculation.classTargetLevel() + ".";
        }
        return "[Kung] " + CatacombsAverageCalculator.classAverageSummaryLine(
            loadedPlayer.name(), selectedFloor.shortLabel, calculation.classTargetLevel(), calculation.breakdown());
    }

    private String catacombsSummaryLine(Calculation calculation) {
        if (loadedPlayer == null || calculation.runsToCatacombs() == null) {
            return "[Kung] Load a player to calculate c" + calculation.targetLevel() + ".";
        }
        return "[Kung] " + CatacombsAverageCalculator.catacombsSummaryLine(
            loadedPlayer.name(), selectedFloor.shortLabel, calculation.targetLevel(), calculation.runsToCatacombs());
    }

    private MayorBoost currentMayorBoost() {
        if (manualMayorBoost != null) {
            return manualMayorBoost;
        }
        double multiplier = SkyBlockMayorTracker.INSTANCE.catacombsXpMultiplier();
        if (multiplier >= 1.58) {
            return MayorBoost.AURA;
        }
        if (multiplier >= 1.49) {
            return MayorBoost.DERPY;
        }
        return MayorBoost.NONE;
    }

    private String mayorLabel(MayorBoost boost) {
        if (manualMayorBoost != null) {
            return boost.label;
        }
        return "Auto: " + SkyBlockMayorTracker.INSTANCE.catacombsXpBoostLabel();
    }

    private static <T extends Enum<T>> T cycle(T value, int direction) {
        T[] values = value.getDeclaringClass().getEnumConstants();
        return values[Math.floorMod(value.ordinal() + direction, values.length)];
    }

    private double cycleGlobalBoost(int direction) {
        double[] values = {0.0, 0.05, 0.10, 0.15, 0.20, 0.30};
        int current = 0;
        for (int index = 0; index < values.length; index++) {
            if (Math.abs(values[index] - globalBoost) < 0.001) {
                current = index;
                break;
            }
        }
        return values[Math.floorMod(current + direction, values.length)];
    }

    private ProfileData selectedProfile() {
        if (loadedPlayer == null || loadedPlayer.profiles().isEmpty()) {
            return null;
        }
        selectedProfileIndex = Math.clamp(selectedProfileIndex, 0, loadedPlayer.profiles().size() - 1);
        return loadedPlayer.profiles().get(selectedProfileIndex);
    }

    private String selectedProfileName() {
        ProfileData profile = selectedProfile();
        return profile == null ? "..." : profile.cuteName();
    }

    private int maxVerticalScroll() {
        return Math.max(0, CONTENT_HEIGHT + 80 - height);
    }

    private void layoutUsernameBox() {
        if (usernameBox == null) {
            return;
        }
        int inputWidth = Math.min(176, Math.max(120, width / 4));
        int x = width / 2 - inputWidth / 2 - 28;
        usernameBox.setBounds(x, TOP, inputWidth, 20);
    }

    private int toGuiX(double eventX) {
        return toGuiCoordinate(eventX, width, true);
    }

    private int toGuiY(double eventY) {
        return toGuiCoordinate(eventY, height, false);
    }

    private static int toGuiCoordinate(double value, int currentGuiSize, boolean horizontal) {
        int rounded = (int) Math.round(value);
        if (rounded >= 0 && rounded <= currentGuiSize) {
            return rounded;
        }
        net.minecraft.client.Minecraft client = Minecraft.getInstance();
        int physicalSize = horizontal ? client.getWindow().getWidth() : client.getWindow().getHeight();
        int guiSize = horizontal ? client.getWindow().getGuiScaledWidth() : client.getWindow().getGuiScaledHeight();
        if (physicalSize <= 0 || guiSize <= 0) {
            return rounded;
        }
        return (int) Math.round(value * guiSize / physicalSize);
    }

    private void addClickRegion(int x, int y, int regionWidth, int regionHeight, ClickAction action) {
        clickRegions.add(new ClickRegion(new UiBounds(x, y, regionWidth, regionHeight), action));
    }

    private String trim(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        for (int end = value.length(); end > 0; end--) {
            String candidate = value.substring(0, end) + suffix;
            if (font.width(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return suffix;
    }

    private double classAverage(ProfileData profile) {
        double average = 0.0;
        for (DungeonClass dungeonClass : CLASSES) {
            average += levelFromXp(profile.classXp(dungeonClass));
        }
        return average / CLASSES.length;
    }

    private static String floorStat(Map<String, Integer> values, String floor) {
        Integer value = values.get(floor);
        return value == null ? "..." : String.valueOf(value);
    }

    private static String duration(Map<String, Integer> values, String floor) {
        Integer millis = values.get(floor);
        if (millis == null || millis <= 0) {
            return "...";
        }
        int seconds = Math.round(millis / 1000.0f);
        return (seconds / 60) + "m " + String.format(Locale.ROOT, "%02d", seconds % 60) + "s";
    }

    private String hecatombLabel() {
        int level = Math.clamp(hecatombLevel, 0, 10);
        if (level == 0) {
            return "None";
        }
        double shown = CatacombsAverageCalculator.hecatombBonus(level) * 100.0;
        return ROMAN[level] + " (+" + String.format(Locale.ROOT, "%.2f", shown) + "%)";
    }

    private static String romanLevel(int level) {
        return ROMAN[Math.clamp(level, 0, ROMAN.length - 1)];
    }

    private static String boostLabel(double boost) {
        return boost <= 0.0 ? "None" : "+" + Math.round(boost * 100.0) + "%";
    }

    private static String classLabel(DungeonClass dungeonClass) {
        return dungeonClass == null ? "..." : dungeonClass.label();
    }

    private static String format(long value) {
        return INTEGER_FORMAT.format(value);
    }

    private static String runsLabel(Long value) {
        if (value == null) {
            return "...";
        }
        if (value == Long.MAX_VALUE) {
            return "infinite";
        }
        return format(value);
    }

    private static String decimal(double value) {
        return String.format(Locale.GERMANY, "%.1f", value);
    }

    private static String compactXp(double value) {
        double absolute = Math.abs(value);
        if (absolute >= 1_000_000_000.0) {
            return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0);
        }
        if (absolute >= 1_000_000.0) {
            return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
        }
        if (absolute >= 1_000.0) {
            return String.format(Locale.ROOT, "%.1fK", value / 1_000.0);
        }
        return format(Math.round(value));
    }

    private enum LoadState {
        IDLE,
        LOADING,
        LOADED,
        ERROR
    }

    private enum FloorOption {
        ENTRANCE("Entrance", "E", "0", 55.0, false),
        F1("Floor 1", "F1", "1", 110.0, false),
        F2("Floor 2", "F2", "2", 220.0, false),
        F3("Floor 3", "F3", "3", 560.0, false),
        F4("Floor 4", "F4", "4", 1_420.0, false),
        F5("Floor 5", "F5", "5", 2_400.0, false),
        F6("Floor 6", "F6", "6", 4_880.0, false),
        F7("Floor 7", "F7", "7", 28_000.0, false),
        M1("Master 1", "M1", "1", 15_000.0, true),
        M2("Master 2", "M2", "2", 20_000.0, true),
        M3("Master 3", "M3", "3", 35_000.0, true),
        M4("Master 4", "M4", "4", 55_000.0, true),
        M5("Master 5", "M5", "5", 70_000.0, true),
        M6("Master 6", "M6", "6", 100_000.0, true),
        M7("Master 7", "M7", "7", 300_000.0, true);

        private final String label;
        private final String shortLabel;
        private final String floorNumber;
        private final double baseXp;
        private final boolean masterMode;

        FloorOption(String label, String shortLabel, String floorNumber, double baseXp, boolean masterMode) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.floorNumber = floorNumber;
            this.baseXp = baseXp;
            this.masterMode = masterMode;
        }
    }

    private enum ScarfAccessory {
        NONE("None", 0.0),
        STUDIES("Studies (+2%)", 0.02),
        THESIS("Thesis (+4%)", 0.04),
        GRIMOIRE("Grimoire (+6%)", 0.06);

        private final String label;
        private final double bonus;

        ScarfAccessory(String label, double bonus) {
            this.label = label;
            this.bonus = bonus;
        }
    }

    private enum MayorBoost {
        NONE("None", 1.0),
        DERPY("Derpy (+50%)", 1.5),
        AURA("Aura (+59%)", 1.59);

        private final String label;
        private final double multiplier;

        MayorBoost(String label, double multiplier) {
            this.label = label;
            this.multiplier = multiplier;
        }
    }

    @FunctionalInterface
    private interface ClickAction {
        void click(int mouseX, int mouseY, int button);
    }

    private record ClickRegion(UiBounds bounds, ClickAction action) {
    }

    private record Calculation(
        long cataPerRun,
        double averageClassPerRun,
        int targetLevel,
        int classTargetLevel,
        Long runsToCatacombs,
        Breakdown breakdown
    ) {
    }

}
