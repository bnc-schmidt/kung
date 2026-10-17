package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.ui.UiTheme;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the 10x10 greenhouse grid in device pixels, cheaply: a cell is two plain fills (a border and its
 * inside) and an icon, never a rounded shape. The vanilla GUI compares every new element with the ones before
 * it, so the cost of a frame grows with the square of the element count; see the budget in
 * docs/AI_HANDOFF.md. Everything that depends only on the grid is worked out when the plan changes.
 */
final class GreenhouseGridPainter {
    /** Share of a cell (or footprint) the icon covers; the rest is margin inside the border. */
    private static final float ICON_SHARE = 0.84F;
    private static final int LOCKED_FILL = 0xFF0E0E0E;
    /** Share of the soil's own colour kept behind an icon; the rest darkens it so bright heads read on pale sand. */
    private static final float SOIL_SHARE = 0.70F;
    private static final Map<String, Integer> COLORS = new HashMap<>();

    private record Label(String text, int width) { }

    private final UiTheme theme;
    private GreenhousePlan plan;
    private final ItemStack[] stacks = new ItemStack[GreenhouseState.CELLS];
    private final int[] fills = new int[GreenhouseState.CELLS];
    private final Map<String, Label> labels = new HashMap<>();
    private float labelDevice = -1F;
    private Font labelFont;

    GreenhouseGridPainter(UiTheme theme) {
        this.theme = theme;
    }

    /** The crop's colour comes from its name, so the same crop looks the same in every layout and list. */
    static int colorOf(String name) {
        Integer color = COLORS.get(name);
        if (color == null) {
            color = Mth.hsvToArgb((name.hashCode() & 0x7FFFFFFF) % 360 / 360F, 0.55F, 0.5F, 0xFF);
            COLORS.put(name, color);
        }
        return color;
    }

    /**
     * @param hovered index (row * 10 + column) of the cell under the pointer, or -1
     * @param editing true while clicks change which cells are locked
     */
    void draw(GuiGraphicsExtractor graphics, Font font, GreenhouseGridLayout layout, float device,
              GreenhousePlan current, GreenhouseState state, int hovered, boolean editing) {
        if (current != plan) rebuild(current);
        if (device != labelDevice || font != labelFont) {
            labels.clear();
            labelDevice = device;
            labelFont = font;
        }
        int size = GreenhouseGrid.SIZE;
        int cell = layout.cell();
        int pitch = layout.pitch();
        int edge = Math.max(1, Math.round(device));
        int hoverColor = editing ? theme.warning() : theme.text();
        graphics.pose().pushMatrix();
        try {
            graphics.pose().scale(1F / device, 1F / device);
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    int index = row * size + column;
                    GreenhouseGrid.Cell content = plan.grid().get(row, column);
                    boolean open = state.isUnlocked(index);
                    int border = theme.controlHover();
                    int fill = LOCKED_FILL;
                    if (content != null) {
                        fill = fills[index];
                        if (content.flag() == GreenhouseGrid.TARGET) border = theme.accent();
                        else if (content.flag() == GreenhouseGrid.BONUS) border = theme.success();
                    } else if (open) {
                        fill = theme.panelSoft();
                    }
                    if (!open) {
                        fill = darker(fill);
                        border = darker(border);
                    }
                    if (index == hovered) border = hoverColor;
                    int left = layout.left() + column * pitch;
                    int top = layout.top() + row * pitch;
                    graphics.fill(left, top, left + cell, top + cell, border);
                    graphics.fill(left + edge, top + edge, left + cell - edge, top + cell - edge, fill);
                }
            }
            // Icons come after every fill: a footprint's icon reaches over cells that are drawn later.
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    GreenhouseGrid.Cell content = plan.grid().get(row, column);
                    if (content == null) continue;
                    int width = cell;
                    int height = cell;
                    if (content.flag() == GreenhouseGrid.TARGET) {
                        GreenhousePlan.Block block = plan.blocks().get(plan.blockAt(row, column));
                        if (block.row() != row || block.column() != column) continue;
                        width = block.width() * pitch - layout.gap();
                        height = block.height() * pitch - layout.gap();
                    }
                    icon(graphics, font, content.name(), stacks[row * size + column], device,
                        layout.left() + column * pitch, layout.top() + row * pitch, width, height, edge);
                }
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void rebuild(GreenhousePlan next) {
        plan = next;
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                int index = row * GreenhouseGrid.SIZE + column;
                GreenhouseGrid.Cell content = next.grid().get(row, column);
                stacks[index] = content == null ? ItemStack.EMPTY : GreenhouseIcons.stack(content.name());
                fills[index] = content == null ? 0 : background(GreenhouseSoils.of(content.name()));
            }
        }
    }

    /** One icon centred in a {@code width} x {@code height} device-pixel box; coordinates are device pixels. */
    private void icon(GuiGraphicsExtractor graphics, Font font, String name, ItemStack stack, float device, int left,
                      int top, int width, int height, int edge) {
        int box = Math.min(width, height);
        int pixels = Math.max(1, Math.round((box - 2 * edge) * ICON_SHARE));
        int x = left + (width - pixels) / 2;
        int y = top + (height - pixels) / 2;
        graphics.pose().pushMatrix();
        try {
            if (!stack.isEmpty()) {
                // An item is 16 units wide; scaling the pose fits it to the cell in device pixels.
                graphics.pose().translate(x, y);
                float scale = pixels / 16F;
                graphics.pose().scale(scale, scale);
                graphics.item(stack, 0, 0);
            } else {
                // No model for this crop: its initials, sized in logical units like all text, snapped to the cell.
                Label label = label(font, name, (int) Math.floor((box - 2 * edge) / device));
                graphics.pose().translate(left + (width - Math.round(label.width() * device)) / 2,
                    top + (height - Math.round(font.lineHeight * device)) / 2);
                graphics.pose().scale(device, device);
                graphics.text(font, label.text(), 0, 0, theme.text(), false);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private Label label(Font font, String name, int maxWidth) {
        Label label = labels.get(name);
        if (label == null) {
            String text = font.plainSubstrByWidth(GreenhouseCrops.abbreviation(name), maxWidth);
            label = new Label(text, font.width(text));
            labels.put(name, label);
        }
        return label;
    }

    /** The cell background for a soil: its block colour, a little darker, opaque. */
    static int background(GreenhouseSoils.Soil soil) {
        int rgb = soil.rgb();
        int red = Math.round((rgb >> 16 & 0xFF) * SOIL_SHARE);
        int green = Math.round((rgb >> 8 & 0xFF) * SOIL_SHARE);
        int blue = Math.round((rgb & 0xFF) * SOIL_SHARE);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int darker(int argb) {
        return 0xFF000000 | (argb >> 1 & 0x7F7F7F);
    }
}
