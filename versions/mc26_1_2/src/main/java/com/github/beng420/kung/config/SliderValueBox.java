package com.github.beng420.kung.config;

import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiTextField;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KeyCodes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The small box beside a slider that shows its value and takes a typed one. The box, not the slider, can reach any
 * number the setting allows: past the slider's ends the knob just sits at the end. One box serves a whole screen: the
 * slider being edited shows the live text field, every other one a plain box. Enter or a click elsewhere applies the
 * number, Escape drops it. A screen routes its clicks, keys and drags here while {@link #editing()}.
 */
final class SliderValueBox {
    static final int HEIGHT = 11;
    private static final int MIN_WIDTH = 30;
    private static final int PAD = 4;
    private static final int IDLE_BORDER = 0xFF8B8B8B;
    private static final int FILL = 0xFF000000;
    private final Font font;
    private final UiTextField field;
    private SettingEntry editing;
    private boolean drawn;

    SliderValueBox(Font font) {
        this.font = font;
        field = new UiTextField(font, Component.literal("Slider value")).maxLength(11)
            .filter(SettingEntry::acceptsNumberInput).textShadow(false);
    }

    boolean editing() { return editing != null; }

    boolean editing(SettingEntry slider) { return editing == slider; }

    /** The box takes the room of the text it shows, and never less than {@link #MIN_WIDTH}. */
    int width(SettingEntry slider) {
        String text = editing == slider ? field.value() : slider.sliderValue();
        return Math.max(MIN_WIDTH, font.width(text) + 2 * PAD + 2);
    }

    /** Where the box of a slider sits, right-aligned to {@code rightX}. */
    UiBounds bounds(SettingEntry slider, int rightX, int y) {
        int width = width(slider);
        return new UiBounds(rightX - width, y, width, HEIGHT);
    }

    /** Starts typing: the number is selected, so the first key replaces it. */
    void begin(SettingEntry slider) {
        if (slider == null || slider.kind() != SettingKind.SLIDER) return;
        editing = slider;
        field.setValue(slider.sliderNumber());
        field.setFocused(true);
        field.selectAll();
    }

    /** A screen calls this around the frame; an edit whose slider was not drawn (its row scrolled or folded away) ends. */
    void beginFrame() { drawn = false; }

    void endFrame() {
        if (editing != null && !drawn) cancel();
    }

    void draw(GuiGraphicsExtractor graphics, UiTheme theme, SettingEntry slider, int rightX, int y,
              int mouseX, int mouseY, float partialTick) {
        UiBounds box = bounds(slider, rightX, y);
        if (editing == slider) {
            drawn = true;
            field.setBounds(box.x(), box.y(), box.width(), box.height());
            field.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }
        int border = box.contains(mouseX, mouseY) ? theme.text() : IDLE_BORDER;
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), border);
        graphics.fill(box.x() + 1, box.y() + 1, box.right() - 1, box.bottom() - 1, FILL);
        graphics.text(font, slider.sliderValue(), box.x() + PAD, box.y() + (HEIGHT - 8) / 2 + 1, theme.text(), false);
    }

    /** True when the click was for the field. A click anywhere else applies the number and is left to the screen. */
    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (editing == null) return false;
        if (field.contains((int) event.x(), (int) event.y())) {
            field.mouseClicked(event, doubleClick);
            return true;
        }
        commit();
        return false;
    }

    boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return editing != null && field.mouseDragged(event, dragX, dragY);
    }

    void mouseReleased(MouseButtonEvent event) {
        if (editing != null) field.mouseReleased(event);
    }

    boolean keyPressed(KeyEvent event) {
        if (editing == null) return false;
        int key = McInput.key(event);
        if (key == KeyCodes.ENTER || key == KeyCodes.KP_ENTER) {
            commit();
        } else if (key == KeyCodes.ESCAPE) {
            cancel();
        } else {
            field.keyPressed(event);
        }
        return true;
    }

    boolean charTyped(CharacterEvent event) {
        return editing != null && field.charTyped(event);
    }

    /** Applies what was typed; text that is no number leaves the value as it was. */
    void commit() {
        SettingEntry slider = editing;
        if (slider == null) return;
        String text = field.value();
        cancel();
        slider.setSliderNumber(text);
    }

    void cancel() {
        editing = null;
        field.setFocused(false);
    }
}
