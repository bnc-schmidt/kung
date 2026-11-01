package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.config.category.HitboxesConfig;
import com.github.beng420.kung.feature.misc.HitboxesFeature;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiMenuFont;
import com.github.beng420.kung.ui.UiScale;
import com.github.beng420.kung.ui.UiScrollList;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.ui.UiTextField;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KeyCodes;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A search popup or a color popup, sharing the menu's font, input and scale. */
final class HitboxEditorScreen extends Screen {
    private static final UiTheme THEME = UiTheme.menu();
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 274;
    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 7;
    private static final int RADIUS = 65;
    private static final Identifier WHEEL = Identifier.fromNamespaceAndPath("kung", "hitbox_color_wheel");
    private final Screen parent;
    private final HitboxesConfig config;
    private final String entityId;
    private final java.util.function.IntConsumer saveColor;
    private final Font menuFont;
    private final UiTextField input;
    private final List<String> all;
    private final Set<String> recent;
    private final UiScrollList scroll = new UiScrollList();
    private List<String> matches = List.of();
    private String previousQuery;
    private int selected;
    private float hue;
    private float saturation;
    private float brightness;
    private int dragging;
    private boolean wheelReady;
    private int x;
    private int y;

    HitboxEditorScreen(Screen parent, HitboxesConfig config, String entityId) {
        this(parent, config, entityId, entityId == null ? "Add Hitbox" : HitboxSettings.name(entityId) + " Color",
            entityId == null ? 0 : config.entities().getOrDefault(entityId, HitboxesConfig.DEFAULT_COLOR),
            color -> config.setColor(entityId, color));
    }

    /** The color popup alone, for anything else with a color (bow draw threshold lines). */
    HitboxEditorScreen(Screen parent, String title, int color, java.util.function.IntConsumer saveColor) {
        this(parent, null, "", title, color, saveColor);
    }

    private HitboxEditorScreen(Screen parent, HitboxesConfig config, String entityId, String title, int color,
                               java.util.function.IntConsumer saveColor) {
        super(Component.literal(title));
        this.parent = parent;
        this.config = config;
        this.entityId = entityId;
        this.saveColor = saveColor;
        menuFont = UiMenuFont.wrap(font);
        input = new UiTextField(menuFont, Component.literal(entityId == null ? "Search entities" : "Hex color"))
            .maxLength(entityId == null ? 120 : 7).canLoseFocus(false).textShadow(false)
            .hint(entityId == null ? "Search entities..." : "#RRGGBB");
        input.setFocused(true);
        all = entityId == null ? HitboxSettings.addableIds() : List.of();
        recent = entityId == null ? Set.copyOf(HitboxesFeature.recentIds()) : Set.of();
        if (entityId != null) {
            setColor(color);
            updateHex();
        }
    }

    @Override
    protected void init() {
        parent.resize(width, height);
        layout();
        if (entityId != null && !wheelReady) {
            int size = RADIUS * 2;
            NativeImage pixels = new NativeImage(size, size, true);
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    double dx = px + 0.5 - RADIUS;
                    double dy = py + 0.5 - RADIUS;
                    if (Math.hypot(dx, dy) <= RADIUS) pixels.setPixel(px, py, wheelColor(dx, dy, RADIUS, 1F));
                }
            }
            minecraft.getTextureManager().register(WHEEL, new DynamicTexture(() -> "Kung hitbox color wheel", pixels));
            wheelReady = true;
        }
    }

    @Override
    public void removed() {
        if (wheelReady) minecraft.getTextureManager().release(WHEEL);
        wheelReady = false;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { McCompat.setScreen(minecraft, parent); }

    private float scale() { return UiScale.menu(Math.min(0.8F, Math.min(width / 344F, height / 298F))); }
    private int coordinate(double value) { return (int) Math.floor(value / scale()); }

    private void layout() {
        x = (coordinate(width) - PANEL_WIDTH) / 2;
        y = (coordinate(height) - PANEL_HEIGHT) / 2;
        input.setBounds(x + 14, entityId == null ? y + 30 : y + 218, PANEL_WIDTH - 28, 20);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        parent.extractRenderState(graphics, -1, -1, partialTick);
        layout();
        mouseX = coordinate(mouseX);
        mouseY = coordinate(mouseY);
        graphics.nextStratum();
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(scale(), scale());
            graphics.fill(0, 0, coordinate(width), coordinate(height), 0xAA000000);
            UiShapes.shadow(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5);
            UiShapes.rounded(graphics, x - 1, y - 1, PANEL_WIDTH + 2, PANEL_HEIGHT + 2, 6, THEME.accent());
            UiShapes.rounded(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, 5, THEME.panel());
            centered(graphics, getTitle().getString(), x + PANEL_WIDTH / 2, y + 12, THEME.text());
            if (entityId == null) drawSearch(graphics, mouseX, mouseY);
            else drawColor(graphics, mouseX, mouseY);
            input.extractRenderState(graphics, mouseX, mouseY, partialTick);
            button(graphics, cancelBounds(), "Cancel", mouseX, mouseY);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void refreshSearch() {
        if (input.value().equals(previousQuery)) return;
        previousQuery = input.value();
        matches = HitboxSettings.withTypedEntries(
            HitboxSettings.search(all, config.entities().keySet(), previousQuery), config.entities().keySet(), previousQuery);
        selected = 0;
        scroll.reset();
    }

    private void drawSearch(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        refreshSearch();
        int top = y + 57;
        if (matches.isEmpty()) centered(graphics, "No matching entities", x + PANEL_WIDTH / 2, top + 12, THEME.muted());
        for (int row = 0; row < VISIBLE_ROWS && scroll.offset() + row < matches.size(); row++) {
            int index = scroll.offset() + row;
            String id = matches.get(index);
            UiBounds bounds = new UiBounds(x + 14, top + row * ROW_HEIGHT, PANEL_WIDTH - 32, ROW_HEIGHT - 1);
            graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(),
                bounds.contains(mouseX, mouseY) || index == selected ? THEME.panelSoft() : THEME.control());
            graphics.text(menuFont, menuFont.plainSubstrByWidth(HitboxSettings.name(id), bounds.width() - 12),
                bounds.x() + 6, bounds.y() + 3, THEME.text(), false);
            graphics.text(menuFont, menuFont.plainSubstrByWidth(recent.contains(id) ? id + "  \u2022 seen nearby" : id,
                bounds.width() - 12), bounds.x() + 6, bounds.y() + 13, THEME.muted(), false);
        }
        if (matches.size() > VISIBLE_ROWS) {
            int height = VISIBLE_ROWS * ROW_HEIGHT;
            int thumb = Math.max(12, height * VISIBLE_ROWS / matches.size());
            int thumbY = top + (height - thumb) * scroll.offset() / (matches.size() - VISIBLE_ROWS);
            graphics.fill(x + PANEL_WIDTH - 16, top, x + PANEL_WIDTH - 14, top + height, THEME.control());
            graphics.fill(x + PANEL_WIDTH - 16, thumbY, x + PANEL_WIDTH - 14, thumbY + thumb, THEME.accent());
        }
        graphics.text(menuFont, matches.size() + " entities", x + 14, y + 232, THEME.muted(), false);
    }

    private void drawColor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int centerX = x + PANEL_WIDTH / 2;
        int centerY = y + 103;
        graphics.blit(RenderPipelines.GUI_TEXTURED, WHEEL, centerX - RADIUS, centerY - RADIUS,
            0F, 0F, RADIUS * 2, RADIUS * 2, RADIUS * 2, RADIUS * 2);
        int markerX = centerX + Math.round((float) Math.cos(hue * Math.PI * 2) * saturation * RADIUS);
        int markerY = centerY + Math.round((float) Math.sin(hue * Math.PI * 2) * saturation * RADIUS);
        graphics.fill(markerX - 3, markerY - 3, markerX + 4, markerY + 4, 0xFF000000);
        graphics.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, 0xFFFFFFFF);
        UiBounds slider = brightnessBounds();
        for (int column = 0; column < slider.width(); column++) {
            graphics.fill(slider.x() + column, slider.y(), slider.x() + column + 1, slider.y() + slider.height(),
                Color.HSBtoRGB(hue, saturation, column / (float) (slider.width() - 1)));
        }
        int marker = slider.x() + Math.round(brightness * (slider.width() - 1));
        graphics.fill(marker - 1, slider.y() - 2, marker + 2, slider.y() + slider.height() + 2, THEME.text());
        graphics.text(menuFont, "Brightness", x + 14, y + 172, THEME.muted(), false);
        graphics.text(menuFont, parseHex(input.value()) == null ? "Use #RRGGBB" : "Hex color",
            x + 14, y + 205, parseHex(input.value()) == null ? THEME.error() : THEME.muted(), false);
        UiShapes.rounded(graphics, x + PANEL_WIDTH - 53, y + 32, 38, 18, 3, color());
        button(graphics, saveBounds(), "Save", mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        layout();
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        if (McInput.button(event) != 0) return true;
        if (cancelBounds().contains(mx, my)) { onClose(); return true; }
        if (entityId == null) {
            refreshSearch();
            if (new UiBounds(x + 14, y + 57, PANEL_WIDTH - 32, VISIBLE_ROWS * ROW_HEIGHT).contains(mx, my)) {
                int index = scroll.offset() + (my - y - 57) / ROW_HEIGHT;
                if (index < matches.size()) choose(matches.get(index));
                return true;
            }
        } else {
            if (saveBounds().contains(mx, my)) { save(); return true; }
            if (Math.hypot(mx - x - PANEL_WIDTH / 2, my - y - 103) <= RADIUS) dragging = 1;
            else if (brightnessBounds().contains(mx, my)) dragging = 2;
            if (dragging != 0) { dragColor(mx, my); return true; }
        }
        input.mouseClicked(new MouseButtonEvent(mx, my, event.buttonInfo()), doubleClick);
        input.setFocused(true);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        int mx = coordinate(event.x());
        int my = coordinate(event.y());
        if (dragging != 0) dragColor(mx, my);
        else input.mouseDragged(new MouseButtonEvent(mx, my, event.buttonInfo()), dragX / scale(), dragY / scale());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = 0;
        input.mouseReleased(new MouseButtonEvent(coordinate(event.x()), coordinate(event.y()), event.buttonInfo()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (entityId == null) {
            refreshSearch();
            scroll.scrollBy(vertical < 0 ? 1 : vertical > 0 ? -1 : 0, matches.size(), VISIBLE_ROWS);
            selected = Math.clamp(selected, scroll.offset(), Math.max(scroll.offset(), Math.min(matches.size() - 1, scroll.offset() + VISIBLE_ROWS - 1)));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (McInput.key(event) == KeyCodes.ESCAPE) { onClose(); return true; }
        if (entityId == null) {
            refreshSearch();
            if (McInput.key(event) == KeyCodes.DOWN || McInput.key(event) == KeyCodes.UP) {
                selected = Math.clamp(selected + (McInput.key(event) == KeyCodes.DOWN ? 1 : -1), 0, Math.max(0, matches.size() - 1));
                scroll.setOffset(Math.clamp(scroll.offset(), selected - VISIBLE_ROWS + 1, selected), matches.size(), VISIBLE_ROWS);
                return true;
            }
        }
        if (McInput.key(event) == KeyCodes.ENTER || McInput.key(event) == KeyCodes.KP_ENTER) {
            if (entityId == null) { if (!matches.isEmpty()) choose(matches.get(selected)); }
            else save();
            return true;
        }
        input.keyPressed(event);
        edited();
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        input.charTyped(event);
        edited();
        return true;
    }

    private void edited() {
        if (entityId == null) refreshSearch();
        else {
            Integer parsed = parseHex(input.value());
            if (parsed != null) setColor(parsed);
        }
    }

    private void choose(String id) { config.add(id); onClose(); }

    private void save() {
        Integer parsed = parseHex(input.value());
        if (parsed != null) { saveColor.accept(parsed); onClose(); }
    }

    private void dragColor(int mx, int my) {
        if (dragging == 1) {
            int dx = mx - x - PANEL_WIDTH / 2;
            int dy = my - y - 103;
            hue = wheelHue(dx, dy);
            saturation = Math.min(1F, (float) Math.hypot(dx, dy) / RADIUS);
        } else {
            brightness = Math.clamp((mx - brightnessBounds().x()) / (float) (brightnessBounds().width() - 1), 0F, 1F);
        }
        updateHex();
    }

    private int color() { return Color.HSBtoRGB(hue, saturation, brightness); }
    private void updateHex() { input.setValue(String.format(Locale.ROOT, "#%06X", color() & 0xFFFFFF)); }

    private void setColor(int color) {
        float[] hsv = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2];
    }

    static Integer parseHex(String text) {
        return text.matches("#?[0-9a-fA-F]{6}") ? 0xFF000000 | Integer.parseInt(text.replace("#", ""), 16) : null;
    }

    private static float wheelHue(double dx, double dy) {
        return (float) ((Math.atan2(dy, dx) / (Math.PI * 2) + 1) % 1);
    }

    static int wheelColor(double dx, double dy, double radius, float brightness) {
        return Color.HSBtoRGB(wheelHue(dx, dy), Math.min(1F, (float) (Math.hypot(dx, dy) / radius)), brightness);
    }

    private UiBounds brightnessBounds() { return new UiBounds(x + 14, y + 185, PANEL_WIDTH - 28, 12); }
    private UiBounds saveBounds() { return new UiBounds(x + PANEL_WIDTH - 140, y + PANEL_HEIGHT - 26, 58, 18); }
    private UiBounds cancelBounds() { return new UiBounds(x + PANEL_WIDTH - 72, y + PANEL_HEIGHT - 26, 58, 18); }

    private void button(GuiGraphicsExtractor graphics, UiBounds bounds, String label, int mx, int my) {
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3,
            bounds.contains(mx, my) ? THEME.accent() : THEME.accentDark());
        centered(graphics, label, bounds.x() + bounds.width() / 2, bounds.y() + 5, THEME.text());
    }

    private void centered(GuiGraphicsExtractor graphics, String text, int centerX, int top, int color) {
        String shown = menuFont.plainSubstrByWidth(text, PANEL_WIDTH - 28);
        graphics.text(menuFont, shown, centerX - menuFont.width(shown) / 2, top, color, false);
    }
}
