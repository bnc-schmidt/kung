package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.config.category.SplitsConfig;
import com.github.beng420.kung.feature.dungeon.DungeonSplitTracker;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiButton;
import com.github.beng420.kung.ui.UiMenuFont;
import com.github.beng420.kung.ui.UiScale;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.ui.UiTextField;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KeyCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class SplitPersonalBestsScreen extends Screen {
    private static final UiTheme THEME = UiTheme.menu();
    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 408;
    private static final Pattern TIME_INPUT = Pattern.compile("[0-9]{0,15}(:[0-9]{0,2}(\\.[0-9]{0,3})?)?");
    private static final Pattern COMPLETE_TIME = Pattern.compile("[0-9]{1,15}:[0-5][0-9]\\.[0-9]{3}");
    private final SplitsConfig config = KungConfig.get().splits;
    private final DungeonSplitTracker tracker;
    private final List<Row> rows = new ArrayList<>();
    private Font menuFont;
    private int selectedFloor;
    private int x;
    private int y;
    private String status = "Save and Clear apply immediately. Esc closes this page.";

    public SplitPersonalBestsScreen(DungeonSplitTracker tracker) {
        super(Component.literal("Split Personal Bests"));
        this.tracker = tracker;
    }

    @Override protected void init() {
        menuFont = UiMenuFont.wrap(font);
        if (rows.isEmpty()) selectFloor(selectedFloor);
        layout();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { focus(-1); }

    private int floor() { return selectedFloor > 7 ? selectedFloor - 7 : selectedFloor; }
    private boolean masterMode() { return selectedFloor > 7; }
    private static String floorLabel(int index) {
        return index == 0 ? "Entrance" : index > 7 ? "M" + (index - 7) : "F" + index;
    }

    private void selectFloor(int index) {
        selectedFloor = index;
        focus(-1);
        rows.clear();
        for (String phase : DungeonSplitTracker.namesFor(floor(), masterMode())) {
            UiTextField input = new UiTextField(menuFont, Component.literal(phase + " personal best, minutes:seconds.milliseconds"))
                .maxLength(22).filter(SplitPersonalBestsScreen::acceptsTimeInput).hint("00:00.000").textShadow(false);
            long best = config.personalBestMillis(floor(), masterMode(), phase);
            if (best > 0L) input.setValue(formatTime(best));
            rows.add(new Row(phase, input));
        }
        layout();
    }

    private float scale() { return UiScale.menu(Math.min(0.8F, Math.min(width / 520F, height / 428F))); }
    private int coordinate(double value) { return (int) Math.floor(value / scale()); }

    private void layout() {
        x = (coordinate(width) - PANEL_WIDTH) / 2;
        y = (coordinate(height) - PANEL_HEIGHT) / 2;
        for (int i = 0; i < rows.size(); i++) rows.get(i).input().setBounds(x + 265, rowY(i), 111, 20);
    }

    private int rowY(int index) { return y + 104 + index * 24; }
    private UiBounds saveBounds(int index) { return new UiBounds(x + 383, rowY(index), 46, 20); }
    private UiBounds clearBounds(int index) { return new UiBounds(x + 436, rowY(index), 50, 20); }
    private UiBounds resetAverageBounds() { return new UiBounds(x + 394, y + 6, 92, 20); }
    private UiBounds floorBounds(int index) {
        if (index == 0) return new UiBounds(x + 14, y + 32, 65, 20);
        int number = index > 7 ? index - 7 : index;
        return new UiBounds(x + 86 + (number - 1) * 58, y + (index > 7 ? 56 : 32), 52, 20);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        layout();
        mouseX = coordinate(mouseX);
        mouseY = coordinate(mouseY);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale(), scale());
            graphics.fill(0, 0, coordinate(width), coordinate(height), THEME.backdrop());
            UiShapes.shadow(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5);
            UiShapes.rounded(graphics, x - 1, y - 1, PANEL_WIDTH + 2, PANEL_HEIGHT + 2, 6, THEME.accent());
            UiShapes.rounded(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5, THEME.panel());
            graphics.text(menuFont, getTitle(), x + 14, y + 12, THEME.accent(), false);
            int runs = config.recentRunCount(floor(), masterMode());
            graphics.text(menuFont, "AVG: " + runs + "/" + SplitsConfig.RECENT_RUN_LIMIT + " runs",
                x + 270, y + 12, THEME.muted(), false);
            UiButton.draw(graphics, menuFont, THEME, resetAverageBounds(), "Reset AVG", mouseX, mouseY, runs > 0, THEME.error());
            for (int i = 0; i < 15; i++) {
                UiButton.draw(graphics, menuFont, THEME, floorBounds(i), floorLabel(i), mouseX, mouseY, true,
                    i == selectedFloor ? THEME.accent() : THEME.text());
            }
            graphics.text(menuFont, "Split", x + 14, y + 88, THEME.muted(), false);
            graphics.text(menuFont, "Saved PB", x + 174, y + 88, THEME.muted(), false);
            graphics.text(menuFont, "New time", x + 265, y + 88, THEME.muted(), false);
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                int top = rowY(i);
                long best = config.personalBestMillis(floor(), masterMode(), row.phase());
                graphics.text(menuFont, row.phase(), x + 14, top + 6, THEME.text(), false);
                graphics.text(menuFont, menuFont.plainSubstrByWidth(formatTime(best), 84),
                    x + 174, top + 6, THEME.warning(), false);
                row.input().extractRenderState(graphics, mouseX, mouseY, partialTick);
                UiButton.draw(graphics, menuFont, THEME, saveBounds(i), "Save", mouseX, mouseY, parseTime(row.input().value()) > 0L, THEME.success());
                UiButton.draw(graphics, menuFont, THEME, clearBounds(i), "Clear", mouseX, mouseY, best > 0L, THEME.error());
            }
            graphics.text(menuFont, "Format: mm:ss.mmm (e.g. 01:23.456). Time must be above zero.",
                x + 14, y + 378, THEME.muted(), false);
            graphics.text(menuFont, status, x + 14, y + 392, THEME.muted(), false);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (McInput.button(event) != 0) return false;
        layout();
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        if (resetAverageBounds().contains(mx, my)) {
            if (config.recentRunCount(floor(), masterMode()) > 0) {
                config.clearRecentRuns(floor(), masterMode());
                status = floorLabel(selectedFloor) + " AVG history reset. Personal bests kept.";
            }
            return true;
        }
        for (int i = 0; i < 15; i++) {
            if (floorBounds(i).contains(mx, my)) {
                if (i != selectedFloor) selectFloor(i);
                return true;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            if (saveBounds(i).contains(mx, my)) { save(rows.get(i)); return true; }
            if (clearBounds(i).contains(mx, my)) { clear(rows.get(i)); return true; }
        }
        int hit = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).input().contains(mx, my)) hit = i;
        focus(hit);
        if (hit >= 0) rows.get(hit).input().mouseClicked(new MouseButtonEvent(mx, my, event.buttonInfo()), doubleClick);
        return true;
    }

    private void focus(int index) {
        for (Row row : rows) if (row.input().isFocused()) row.input().setFocused(false);
        if (index >= 0) rows.get(index).input().setFocused(true);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        for (Row row : rows) if (row.input().isFocused()) {
            return row.input().mouseDragged(new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()),
                dragX / scale(), dragY / scale());
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        for (Row row : rows) row.input().mouseReleased(new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (McInput.key(event) == KeyCodes.ESCAPE) { onClose(); return true; }
        if (McInput.key(event) == KeyCodes.TAB) {
            int focused = -1;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).input().isFocused()) focused = i;
            int next = focused < 0 ? 0 : Math.floorMod(focused + (event.hasShiftDown() ? -1 : 1), rows.size());
            focus(next);
            return true;
        }
        for (Row row : rows) if (row.input().isFocused()) {
            if (McInput.key(event) == KeyCodes.ENTER || McInput.key(event) == KeyCodes.KP_ENTER) save(row);
            else row.input().keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        for (Row row : rows) if (row.input().isFocused()) return row.input().charTyped(event);
        return false;
    }

    private void save(Row row) {
        long millis = parseTime(row.input().value());
        if (millis <= 0L) return;
        config.setPersonalBestMillis(floor(), masterMode(), row.phase(), millis);
        tracker.personalBestEdited(floor(), masterMode(), row.phase());
        row.input().setValue(formatTime(millis));
        status = floorLabel(selectedFloor) + " / " + row.phase() + " saved. Esc closes this page.";
    }

    private void clear(Row row) {
        if (config.personalBestMillis(floor(), masterMode(), row.phase()) <= 0L) return;
        config.clearPersonalBest(floor(), masterMode(), row.phase());
        tracker.personalBestEdited(floor(), masterMode(), row.phase());
        row.input().setValue("");
        status = floorLabel(selectedFloor) + " / " + row.phase() + " cleared. Esc closes this page.";
    }

    static boolean acceptsTimeInput(String text) { return TIME_INPUT.matcher(text).matches(); }

    static long parseTime(String text) {
        if (!COMPLETE_TIME.matcher(text).matches()) return -1L;
        int colon = text.indexOf(':');
        try {
            long minutes = Long.parseLong(text.substring(0, colon));
            long seconds = Long.parseLong(text.substring(colon + 1, colon + 3));
            long millis = Math.addExact(Math.multiplyExact(minutes, 60_000L),
                seconds * 1_000L + Long.parseLong(text.substring(colon + 4)));
            return millis > 0L ? millis : -1L;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return -1L;
        }
    }

    static String formatTime(long millis) {
        return millis <= 0L ? "--" : String.format(Locale.ROOT, "%02d:%02d.%03d",
            millis / 60_000L, millis / 1_000L % 60L, millis % 1_000L);
    }

    private record Row(String phase, UiTextField input) { }
}
