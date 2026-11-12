package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.ui.HudPose;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * "Chat: Party": the channel Hypixel sends your next message to. {@link ChatChannelsFeature} reads it from the
 * "You are now in the PARTY channel" notices, from /chat sent by you, from the tabs and from where a plain message of
 * yours lands (its echo comes back as "Party > ..." or as a public line). In a chat conversation it
 * shows the player and the time left instead. It shows only while the chat is open, and the channel is saved, so it
 * is still there after a restart.
 */
public final class CurrentChatDisplayFeature extends ConfigurableFeature<MiscConfig> {
    public static final CurrentChatDisplayFeature INSTANCE = new CurrentChatDisplayFeature();
    private static final int LABEL_COLOR = 0x55FF55;
    private static final int DM_COLOR = 0xFF55FF;
    private static final int TIMER_COLOR = 0xAAAAAA;
    private static final int ENDED_COLOR = 0xFF5555;
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
        if (!isEnabled() || client.level == null || !(McCompat.screen(client) instanceof ChatScreen) || McCompat.hudHidden(client)
            || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), ChatChannelsFeature.send(), ChatChannelsFeature.conversation());
    }

    /** A chat conversation shows the player and the time left, or "session ended"; else the channel. */
    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, ChatChannels.Send send,
        ChatChannels.Conversation conversation) {
        var font = Minecraft.getInstance().font;
        var line = Component.literal("Chat: ").withColor(LABEL_COLOR);
        if (conversation == null) {
            line.append(Component.literal(send.label()).withColor(send.color()));
        } else {
            long now = System.currentTimeMillis();
            line.append(Component.literal(conversation.name()).withColor(DM_COLOR));
            line.append(conversation.ended(now) ? Component.literal(" - session ended").withColor(ENDED_COLOR)
                : Component.literal(" " + conversation.left(now)).withColor(TIMER_COLOR));
        }
        HudPose.begin(graphics, config.currentChatDisplayX(), config.currentChatDisplayY(),
            config.currentChatDisplayScale() / 100F);
        try {
            graphics.text(font, line, PAD, PAD, 0xFFFFFFFF, true);
        } finally {
            HudPose.end(graphics);
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, ChatChannels.Send.PARTY, null);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.currentChatDisplayScale() / 100F;
        return new KungHudLayout.Bounds(config.currentChatDisplayX(), config.currentChatDisplayY(),
            Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
