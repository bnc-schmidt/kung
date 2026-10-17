package com.github.beng420.kung.ui;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class UiTextField {
    /** Vanilla's EditBox cuts text to 32 characters, which silently truncates pasted links. */
    static final int DEFAULT_MAX_LENGTH = 8192;
    private final EditBox control;
    private Predicate<String> filter;

    public UiTextField(Font font, Component narration) {
        control = new EditBox(font, 0, 0, narration);
        control.setMaxLength(DEFAULT_MAX_LENGTH);
    }

    public UiTextField maxLength(int maxLength) {
        control.setMaxLength(maxLength);
        return this;
    }

    public UiTextField filter(Predicate<String> filter) {
        this.filter = filter;
        return this;
    }

    public UiTextField hint(String text) {
        control.setHint(Component.literal(text));
        return this;
    }

    public UiTextField textShadow(boolean textShadow) {
        control.setTextShadow(textShadow);
        return this;
    }

    public UiTextField bordered(boolean bordered) {
        control.setBordered(bordered);
        return this;
    }

    public UiTextField responder(java.util.function.Consumer<String> responder) {
        control.setResponder(responder);
        return this;
    }

    public UiTextField canLoseFocus(boolean canLoseFocus) {
        control.setCanLoseFocus(canLoseFocus);
        return this;
    }

    public void setBounds(int x, int y, int width, int height) {
        // EditBox works out where its text and hint sit inside setX/setY and not again after setWidth/setHeight,
        // and starts 0 high (the (font, int, int, Component) constructor is width and height). Size first, or
        // the first frame draws the hint (height - 8) / 2 = -4 units from the top until the first click.
        control.setWidth(width);
        control.setHeight(height);
        control.setX(x);
        control.setY(y);
    }

    public UiBounds bounds() {
        return new UiBounds(control.getX(), control.getY(), control.getWidth(), control.getHeight());
    }

    public boolean contains(int x, int y) {
        return bounds().contains(x, y);
    }

    public int x() {
        return control.getX();
    }

    public int width() {
        return control.getWidth();
    }

    public String value() {
        return control.getValue();
    }

    public void setValue(String value) {
        control.setValue(value == null ? "" : value);
    }

    public boolean isFocused() {
        return control.isFocused();
    }

    public void setFocused(boolean focused) {
        control.setFocused(focused);
    }

    public void moveCursorToEnd() {
        control.moveCursorToEnd(false);
    }

    public void extractRenderState(
        GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        control.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Right click clears the whole field, as in most mod menus.
        if (event.button() == 1 && contains((int) event.x(), (int) event.y())) {
            control.setValue("");
            control.setFocused(true);
            return true;
        }
        boolean handled = control.mouseClicked(event, doubleClick);
        // These fields are routed manually, outside Screen's child-widget focus handling.
        if (handled) control.setFocused(true);
        return handled;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return control.mouseDragged(event, dragX, dragY);
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return control.mouseReleased(event);
    }

    public boolean keyPressed(KeyEvent event) {
        return edit(() -> control.keyPressed(event));
    }

    public boolean charTyped(CharacterEvent event) {
        return edit(() -> control.charTyped(event));
    }

    private boolean edit(BooleanSupplier action) {
        if (filter == null) return action.getAsBoolean();
        String previous = control.getValue();
        int cursor = control.getCursorPosition();
        boolean handled = action.getAsBoolean();
        // Includes paste and deletion; 26.1.2's EditBox no longer exposes an input filter.
        if (!filter.test(control.getValue())) {
            control.setValue(previous);
            control.moveCursorTo(cursor, false);
        }
        return handled;
    }
}
