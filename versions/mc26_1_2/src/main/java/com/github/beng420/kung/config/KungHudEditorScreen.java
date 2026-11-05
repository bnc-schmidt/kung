package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.config.KungHudLayout.Bounds;
import com.github.beng420.kung.config.KungHudLayout.Entry;
import com.github.beng420.kung.feature.dungeon.DungeonStateTracker;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KeyCodes;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class KungHudEditorScreen extends Screen {
    private static final int BACKDROP = 0x33000000;
    private static final int HUD_BOX = 0x669AA4B2;
    private static final int HUD_BOX_HOVER = 0x889AA4B2;
    private static final int HUD_BOX_DRAG = 0xAA3498DB;
    private static final int BORDER = 0xCCFFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFD4DAE3;
    private static final int TOOLTIP_BG = 0xEE111318;
    private static final int MAP_CONTROLS_WIDTH = 214;
    private static final int MAP_CONTROLS_HEIGHT = 70;
    private static final int SLIDER_WIDTH = 100;
    private static final int SLIDER_HEIGHT = 18;
    /** HUDs drawn with sample data in the box; every other box shows its name. */
    private static final Set<String> PREVIEWED =
        Set.of("safari_uniques", "feast_progress", "dragon_debuff", "bow_draw_indicator", "frozen_blaze_hud");

    private final DungeonStateTracker dungeonStateTracker;
    private Entry dragging;
    private int dragOffsetX;
    private int dragOffsetY;
    private SettingEntry draggingSlider;

    public KungHudEditorScreen(DungeonStateTracker dungeonStateTracker) {
        super(Component.literal("Kung HUD Editor"));
        this.dungeonStateTracker = dungeonStateTracker;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.text(font, "Kung HUD Editor", 10, 10, TEXT, true);
        graphics.text(font, "Drag to move. Scroll to resize. Right click for settings.", 10, 22, MUTED, true);

        List<Entry> entries = hudEntries();

        Entry hovered = inMapControls(mouseX, mouseY) ? null : hoveredEntry(entries, mouseX, mouseY);
        Entry currentDragging = null;
        for (Entry entry : entries) {
            boolean isDragging = dragging != null && dragging.id().equals(entry.id());
            if (isDragging) currentDragging = entry;
            drawHudBox(graphics, entry, hovered == entry, isDragging);
        }

        if (currentDragging != null) {
            Bounds bounds = currentDragging.bounds();
            String coords = "x " + currentDragging.configX().getAsInt()
                + "  y " + currentDragging.configY().getAsInt();
            graphics.text(
                font,
                coords,
                bounds.x() + 4,
                bounds.y() + bounds.height() + 5,
                TEXT,
                true
            );
        } else if (hovered != null) {
            drawTooltip(graphics, hovered, mouseX, mouseY);
        }
        drawMapControls(graphics);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int button = McInput.button(event);

        if (inMapControls(mouseX, mouseY)) {
            if (button == 0) {
                List<SettingEntry> sliders = mapSliders();
                for (int index = 0; index < sliders.size(); index++) {
                    int y = mapControlsY() + 21 + index * 22;
                    if (mouseX >= sliderX() && mouseY >= y && mouseY < y + SLIDER_HEIGHT) {
                        draggingSlider = sliders.get(index);
                        draggingSlider.click(mouseX, sliderX(), SLIDER_WIDTH);
                        return true;
                    }
                }
            }
            return true;
        }

        Entry entry = hoveredEntry(hudEntries(), mouseX, mouseY);
        if (entry != null) {
            if (button == 0) {
                dragging = entry;
                // Capture the content origin so resizing during a drag preserves its anchor.
                dragOffsetX = mouseX - entry.configX().getAsInt();
                dragOffsetY = mouseY - entry.configY().getAsInt();
                return true;
            }
            if (button == 1) {
                McCompat.setScreen(Minecraft.getInstance(), new KungConfigScreen(entry.name()));
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider != null) {
            draggingSlider.click((int) event.x(), sliderX(), SLIDER_WIDTH);
            return true;
        }
        if (dragging == null) {
            return super.mouseDragged(event, dragX, dragY);
        }

        dragging.setX().accept((int) event.x() - dragOffsetX);
        dragging.setY().accept((int) event.y() - dragOffsetY);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || inMapControls((int) mouseX, (int) mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        Entry hovered = hoveredEntry(hudEntries(), (int) mouseX, (int) mouseY);
        if (hovered == null) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        int step = scrollY > 0 ? 5 : -5;
        hovered.setScalePercent().accept(Math.clamp(hovered.scalePercent().getAsInt() + step, 25, 300));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSlider != null) {
            draggingSlider = null;
            return true;
        }
        if (dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (McInput.key(event) == KeyCodes.ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Only the HUDs that are switched on; the rest have nothing to place. */
    private List<Entry> hudEntries() {
        return KungHudLayout.entries(KungConfig.get(), dungeonStateTracker).stream().filter(Entry::enabled).toList();
    }

    private void drawHudBox(GuiGraphicsExtractor graphics, Entry entry, boolean hovered, boolean draggingEntry) {
        Bounds bounds = entry.bounds();
        int color = draggingEntry ? HUD_BOX_DRAG : hovered ? HUD_BOX_HOVER : HUD_BOX;
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), color);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + 1, BORDER);
        graphics.fill(bounds.x(), bounds.y() + bounds.height() - 1, bounds.x() + bounds.width(), bounds.y() + bounds.height(), BORDER);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + 1, bounds.y() + bounds.height(), BORDER);
        graphics.fill(bounds.x() + bounds.width() - 1, bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), BORDER);
        if (PREVIEWED.contains(entry.id())) {
            entry.drawPreview(graphics);
        } else {
            drawCentered(graphics, entry.name(), bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2 - 4);
        }
    }

    private void drawTooltip(GuiGraphicsExtractor graphics, Entry entry, int mouseX, int mouseY) {
        String lineOne = entry.name() + "  " + entry.scalePercent().getAsInt() + "%";
        String lineTwo = "Drag to move, scroll to resize, right click for settings";
        int tooltipWidth = Math.max(font.width(lineOne), font.width(lineTwo)) + 10;
        int x = Math.min(mouseX + 12, width - tooltipWidth - 4);
        int y = Math.min(mouseY + 12, height - 30);
        graphics.fill(x, y, x + tooltipWidth, y + 28, TOOLTIP_BG);
        graphics.text(font, lineOne, x + 5, y + 5, TEXT, true);
        graphics.text(font, lineTwo, x + 5, y + 16, MUTED, true);
    }

    private List<SettingEntry> mapSliders() {
        var config = KungConfig.get().dungeon;
        return List.of(
            SettingEntry.slider("Map Scale", config::scale, config::setScale, 25, 300, 5),
            SettingEntry.slider("Text Scale", config::textScale, config::setTextScale, 50, 200, 5)
        );
    }

    private void drawMapControls(GuiGraphicsExtractor graphics) {
        int x = mapControlsX();
        int y = mapControlsY();
        graphics.fill(x, y, x + MAP_CONTROLS_WIDTH, y + MAP_CONTROLS_HEIGHT, TOOLTIP_BG);
        graphics.text(font, "Dungeon Map", x + 8, y + 7, TEXT, true);
        List<SettingEntry> sliders = mapSliders();
        for (int index = 0; index < sliders.size(); index++) {
            SettingEntry slider = sliders.get(index);
            int rowY = y + 21 + index * 22;
            graphics.text(font, slider.label(), x + 8, rowY + 4, MUTED, true);
            slider.draw(graphics, font, UiTheme.settings(), sliderX(), rowY, SLIDER_WIDTH, SLIDER_HEIGHT, false);
        }
    }

    private int mapControlsX() {
        return Math.max(4, width - MAP_CONTROLS_WIDTH - 8);
    }

    private int mapControlsY() {
        return Math.max(36, height - MAP_CONTROLS_HEIGHT - 8);
    }

    private int sliderX() {
        return mapControlsX() + MAP_CONTROLS_WIDTH - SLIDER_WIDTH - 8;
    }

    private boolean inMapControls(int mouseX, int mouseY) {
        return mouseX >= mapControlsX() && mouseX < mapControlsX() + MAP_CONTROLS_WIDTH
            && mouseY >= mapControlsY() && mouseY < mapControlsY() + MAP_CONTROLS_HEIGHT;
    }

    private static Entry hoveredEntry(List<Entry> entries, int mouseX, int mouseY) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (contains(entry.bounds(), mouseX, mouseY)) {
                return entry;
            }
        }
        return null;
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y) {
        graphics.text(font, text, centerX - font.width(text) / 2, y, TEXT, true);
    }

    private static boolean contains(Bounds bounds, int mouseX, int mouseY) {
        return UiBounds.inside(mouseX, mouseY, bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }
}
