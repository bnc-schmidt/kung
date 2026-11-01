package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.misc.CustomSoundsFeature;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiMenuFont;
import com.github.beng420.kung.ui.UiScale;
import com.github.beng420.kung.ui.UiScrollList;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KeyCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** One section per sound event, and a picker listing the folder's files - after SBO's sound settings. */
final class CustomSoundsScreen extends Screen {
    private static final UiTheme THEME = UiTheme.menu();
    private static final int WIDTH = 470;
    private static final int SECTION_HEIGHT = 58;
    private static final int PICKER_WIDTH = 300;
    private static final int PICKER_ROW = 22;
    private static final int PICKER_ROWS = 9;
    private static final String NONE = "(None)";
    private final Screen parent;
    private final Font menuFont;
    private final List<Section> sections;
    private final UiScrollList pickerScroll = new UiScrollList();
    private Section picking;
    private Bar dragging;

    CustomSoundsScreen(Screen parent, MiscConfig config) {
        super(Component.literal("Kung Sound Settings"));
        this.parent = parent;
        menuFont = UiMenuFont.wrap(font);
        sections = List.of(
            new Section("Arrow Hit", config::customArrowHitSounds, config::setCustomArrowHitSounds,
                config::customArrowHitVolumeTenths, config::setCustomArrowHitVolumeTenths,
                config::customArrowHitPitchHundredths, config::setCustomArrowHitPitchHundredths,
                CustomSoundsFeature::testArrowHit),
            new Section("Wither Shield End", config::customWitherShieldExpireSounds, config::setCustomWitherShieldExpireSounds,
                config::customWitherShieldExpireVolumeTenths, config::setCustomWitherShieldExpireVolumeTenths,
                config::customWitherShieldExpirePitchHundredths, config::setCustomWitherShieldExpirePitchHundredths,
                CustomSoundsFeature::testWitherShieldExpire));
    }

    private record Section(String name, Supplier<String> sound, Consumer<String> setSound,
                           IntSupplier volume, IntConsumer setVolume, IntSupplier pitch, IntConsumer setPitch, Runnable test) {
        String shownSound() {
            var names = CustomSoundsFeature.configuredSoundNames(sound.get());
            return names.isEmpty() ? NONE : names.getFirst();
        }
    }

    /** A value bar: volume in tenths (0-50, x0.0-x5.0) or pitch in hundredths (25-300, x0.25-x3.00). */
    private record Bar(IntSupplier value, IntConsumer set, int min, int max, int step, boolean pitch) {
        String text() { return pitch ? String.format(Locale.ROOT, "x%.2f", value.getAsInt() / 100.0)
            : String.format(Locale.ROOT, "x%.1f", value.getAsInt() / 10.0); }
        void setFrom(int mouseX, UiBounds bounds) {
            double progress = Math.clamp((mouseX - bounds.x()) / (double) Math.max(1, bounds.width()), 0.0, 1.0);
            set.accept(Math.clamp(min + (int) Math.round(progress * (max - min) / step) * step, min, max));
        }
        void nudge(int direction) { set.accept(Math.clamp(value.getAsInt() + direction * step, min, max)); }
    }

    private Bar volumeBar(Section section) { return new Bar(section.volume(), section.setVolume(), 0, 50, 1, false); }
    private Bar pitchBar(Section section) { return new Bar(section.pitch(), section.setPitch(), 25, 300, 5, true); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { McCompat.setScreen(minecraft, parent); }

    private float scale() { return UiScale.menu(Math.min(0.8F, Math.min(width / 500F, height / 250F))); }
    private int coordinate(double value) { return (int) Math.floor(value / scale()); }
    private int left() { return (coordinate(width) - WIDTH) / 2; }
    private int top() { return Math.max(8, (coordinate(height) - 60 - sections.size() * SECTION_HEIGHT) / 2); }

    // Layout of one section's control row: [sound] [volume][-][+] [pitch][-][+] [Test].
    private int rowY(int index) { return top() + 40 + index * SECTION_HEIGHT + 26; }
    private UiBounds soundBounds(int index) { return new UiBounds(left(), rowY(index), 130, 20); }
    private UiBounds volumeBounds(int index) { return new UiBounds(left() + 138, rowY(index), 90, 20); }
    private UiBounds volumeMinus(int index) { return new UiBounds(left() + 230, rowY(index), 18, 20); }
    private UiBounds volumePlus(int index) { return new UiBounds(left() + 250, rowY(index), 18, 20); }
    private UiBounds pitchBounds(int index) { return new UiBounds(left() + 276, rowY(index), 90, 20); }
    private UiBounds pitchMinus(int index) { return new UiBounds(left() + 368, rowY(index), 18, 20); }
    private UiBounds pitchPlus(int index) { return new UiBounds(left() + 388, rowY(index), 18, 20); }
    private UiBounds testBounds(int index) { return new UiBounds(left() + WIDTH - 56, rowY(index), 56, 20); }
    private UiBounds folderBounds() { return new UiBounds(left() + WIDTH - 90, top() + 16, 90, 16); }

    private UiBounds pickerBounds() {
        int panelHeight = 30 + PICKER_ROWS * PICKER_ROW + 8;
        return new UiBounds((coordinate(width) - PICKER_WIDTH) / 2, (coordinate(height) - panelHeight) / 2, PICKER_WIDTH, panelHeight);
    }

    private List<String> pickerOptions() {
        var options = new ArrayList<String>();
        options.add(NONE);
        options.addAll(CustomSoundsFeature.availableSounds());
        return options;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int mx = picking == null ? coordinate(mouseX) : -1;
        int my = picking == null ? coordinate(mouseY) : -1;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale(), scale());
            graphics.fill(0, 0, coordinate(width), coordinate(height), THEME.backdrop());
            int left = left();
            int top = top();
            centered(graphics, getTitle().getString(), left + WIDTH / 2, top, THEME.accent());
            String folder = "Folder: " + CustomSoundsFeature.soundsFolder();
            graphics.text(menuFont, menuFont.plainSubstrByWidth(folder, WIDTH - 100), left, top + 20, THEME.muted(), false);
            button(graphics, folderBounds(), "Open Folder", mx, my, THEME.accentDark());
            for (int index = 0; index < sections.size(); index++) drawSection(graphics, index, mx, my);
            if (picking != null) drawPicker(graphics, coordinate(mouseX), coordinate(mouseY));
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void drawSection(GuiGraphicsExtractor graphics, int index, int mx, int my) {
        Section section = sections.get(index);
        int y = top() + 40 + index * SECTION_HEIGHT;
        graphics.text(menuFont, section.name(), left(), y, THEME.text(), false);
        String status = "Sound: " + section.shownSound() + "  |  Volume: " + volumeBar(section).text()
            + "  |  Pitch: " + pitchBar(section).text();
        graphics.text(menuFont, menuFont.plainSubstrByWidth(status, WIDTH), left(), y + 12, THEME.muted(), false);
        button(graphics, soundBounds(index), section.shownSound(), mx, my, THEME.panelDark());
        drawBar(graphics, volumeBounds(index), volumeBar(section));
        button(graphics, volumeMinus(index), "-", mx, my, THEME.control());
        button(graphics, volumePlus(index), "+", mx, my, THEME.control());
        drawBar(graphics, pitchBounds(index), pitchBar(section));
        button(graphics, pitchMinus(index), "-", mx, my, THEME.control());
        button(graphics, pitchPlus(index), "+", mx, my, THEME.control());
        button(graphics, testBounds(index), "Test", mx, my, 0xFF1E7A2E);
        graphics.fill(left(), y + SECTION_HEIGHT - 6, left() + WIDTH, y + SECTION_HEIGHT - 5, THEME.border());
    }

    private void drawBar(GuiGraphicsExtractor graphics, UiBounds bounds, Bar bar) {
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3, THEME.control());
        float progress = (bar.value().getAsInt() - bar.min()) / (float) (bar.max() - bar.min());
        int filled = Math.round(progress * (bounds.width() - 4));
        if (filled > 0) graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.x() + 2 + filled, bounds.y() + bounds.height() - 2, THEME.accent());
        centered(graphics, bar.text(), bounds.x() + bounds.width() / 2, bounds.y() + 6, THEME.text());
    }

    private void drawPicker(GuiGraphicsExtractor graphics, int mx, int my) {
        graphics.nextStratum();
        graphics.fill(0, 0, coordinate(width), coordinate(height), 0x99000000);
        UiBounds panel = pickerBounds();
        UiShapes.shadow(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 5);
        UiShapes.rounded(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 5, THEME.panel());
        centered(graphics, "Select " + picking.name(), panel.x() + panel.width() / 2, panel.y() + 10, THEME.text());
        List<String> options = pickerOptions();
        String current = picking.shownSound();
        for (int row = 0; row < PICKER_ROWS && pickerScroll.offset() + row < options.size(); row++) {
            String option = options.get(pickerScroll.offset() + row);
            UiBounds bounds = new UiBounds(panel.x() + 10, panel.y() + 30 + row * PICKER_ROW, panel.width() - 24, PICKER_ROW - 3);
            UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
                bounds.contains(mx, my) ? THEME.panelSoft() : THEME.control());
            graphics.text(menuFont, menuFont.plainSubstrByWidth(option, bounds.width() - 12), bounds.x() + 6, bounds.y() + 6,
                option.equals(current) ? THEME.accent() : THEME.text(), false);
        }
        if (options.size() > PICKER_ROWS) {
            int top = panel.y() + 30;
            int height = PICKER_ROWS * PICKER_ROW;
            int thumb = Math.max(12, height * PICKER_ROWS / options.size());
            int thumbY = top + (height - thumb) * pickerScroll.offset() / (options.size() - PICKER_ROWS);
            graphics.fill(panel.x() + panel.width() - 10, top, panel.x() + panel.width() - 8, top + height, THEME.control());
            graphics.fill(panel.x() + panel.width() - 10, thumbY, panel.x() + panel.width() - 8, thumbY + thumb, THEME.accent());
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        if (McInput.button(event) != 0) return true;
        if (picking != null) {
            UiBounds panel = pickerBounds();
            if (!panel.contains(mx, my)) { picking = null; return true; }
            int row = (my - panel.y() - 30) / PICKER_ROW;
            List<String> options = pickerOptions();
            int index = pickerScroll.offset() + row;
            if (my >= panel.y() + 30 && row < PICKER_ROWS && index < options.size()) {
                String option = options.get(index);
                picking.setSound().accept(option.equals(NONE) ? "" : option);
                picking = null;
            }
            return true;
        }
        if (folderBounds().contains(mx, my)) { CustomSoundsFeature.openSoundsFolder(); return true; }
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            if (soundBounds(index).contains(mx, my)) {
                picking = section;
                pickerScroll.reset();
            } else if (volumeBounds(index).contains(mx, my)) {
                dragging = volumeBar(section);
                dragging.setFrom(mx, volumeBounds(index));
            } else if (pitchBounds(index).contains(mx, my)) {
                dragging = pitchBar(section);
                dragging.setFrom(mx, pitchBounds(index));
            } else if (volumeMinus(index).contains(mx, my)) volumeBar(section).nudge(-1);
            else if (volumePlus(index).contains(mx, my)) volumeBar(section).nudge(1);
            else if (pitchMinus(index).contains(mx, my)) pitchBar(section).nudge(-1);
            else if (pitchPlus(index).contains(mx, my)) pitchBar(section).nudge(1);
            else if (testBounds(index).contains(mx, my)) section.test().run();
            else continue;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging == null) return true;
        int mx = coordinate(event.x());
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            if (dragging.value() == section.volume()) dragging.setFrom(mx, volumeBounds(index));
            if (dragging.value() == section.pitch()) dragging.setFrom(mx, pitchBounds(index));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (picking != null) pickerScroll.scrollBy(vertical < 0 ? 1 : vertical > 0 ? -1 : 0, pickerOptions().size(), PICKER_ROWS);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (McInput.key(event) == KeyCodes.ESCAPE) {
            if (picking != null) picking = null;
            else onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void button(GuiGraphicsExtractor graphics, UiBounds bounds, String label, int mx, int my, int color) {
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
            bounds.contains(mx, my) ? THEME.accent() : color);
        String shown = menuFont.plainSubstrByWidth(label, bounds.width() - 8);
        graphics.text(menuFont, shown, bounds.x() + (bounds.width() - menuFont.width(shown)) / 2,
            bounds.y() + (bounds.height() - 8) / 2, THEME.text(), false);
    }

    private void centered(GuiGraphicsExtractor graphics, String text, int centerX, int top, int color) {
        graphics.text(menuFont, text, centerX - menuFont.width(text) / 2, top, color, false);
    }
}
