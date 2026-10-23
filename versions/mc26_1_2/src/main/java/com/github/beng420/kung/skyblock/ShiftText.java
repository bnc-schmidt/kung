package com.github.beng420.kung.skyblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Text that shows a short form, and a detailed one while Shift is held. A hover tooltip is laid out again every
 * frame, so the choice is made when the text is read, not when the message is created. Client side only: nothing
 * sends or stores it, so the codec is a formality (it keeps the short text).
 */
final class ShiftText implements ComponentContents {
    private static final MapCodec<ShiftText> CODEC = Codec.STRING.fieldOf("text").xmap(text -> new ShiftText(text, text), text -> text.compact);
    /** Replaced in tests, where there is no game to ask. */
    static BooleanSupplier shiftHeld = ShiftText::gameShift;

    private final String compact;
    private final String detailed;

    private ShiftText(String compact, String detailed) {
        this.compact = compact;
        this.detailed = detailed;
    }

    static MutableComponent of(String compact, String detailed) {
        return MutableComponent.create(new ShiftText(compact, detailed));
    }

    private static boolean gameShift() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.hasShiftDown();
    }

    private String current() {
        return shiftHeld.getAsBoolean() ? detailed : compact;
    }

    @Override
    public <T> Optional<T> visit(FormattedText.ContentConsumer<T> consumer) {
        return consumer.accept(current());
    }

    @Override
    public <T> Optional<T> visit(FormattedText.StyledContentConsumer<T> consumer, Style style) {
        return consumer.accept(style, current());
    }

    @Override
    public MapCodec<? extends ComponentContents> codec() {
        return CODEC;
    }
}
