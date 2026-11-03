package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * "Chat: Party": the channel Hypixel sends your next message to. {@link ChatChannelsFeature} reads it from the
 * "You are now in the PARTY channel" notices, from /chat sent by you and from the tabs.
 */
public final class CurrentChatDisplayFeature extends ConfigurableFeature<MiscConfig> {
    public static final CurrentChatDisplayFeature INSTANCE = new CurrentChatDisplayFeature();
    private static final int LABEL_COLOR = 0x55FF55;
    private static final int PAD = 4;
    private static final int HEIGHT = 16;
    /** "Chat: Officer" in the default font, with the padding. */
    private static final int WIDTH = 72;

    private CurrentChatDisplayFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "current_chat_display"), (graphics, delta) -> render(graphics));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().currentChatDisplayEnabled(); }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (!isEnabled() || client.level == null || McCompat.hudHidden(client) || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), ChatChannelsFeature.send());
    }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, ChatChannels.Send send) {
        var font = Minecraft.getInstance().font;
        var line = Component.literal("Chat: ").withColor(LABEL_COLOR)
            .append(Component.literal(send.label()).withColor(send.color()));
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.currentChatDisplayX(), config.currentChatDisplayY());
            float scale = config.currentChatDisplayScale() / 100F;
            graphics.pose().scale(scale, scale);
            graphics.text(font, line, PAD, PAD, 0xFFFFFFFF, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, ChatChannels.Send.PARTY);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.currentChatDisplayScale() / 100F;
        return new KungHudLayout.Bounds(config.currentChatDisplayX(), config.currentChatDisplayY(),
            Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
