package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Shows the whole chat while the peek key is held, without opening the chat screen: it draws as in the open chat
 * (no fading, full height, scroll bar) and the wheel scrolls it instead of the hotbar. ChatComponentMixin and
 * MouseHandlerMixin call in; the chat itself, and mods around it such as Chat Patches, stay untouched.
 *
 * <p>With Peek In Menus on, the key works while an inventory or other menu is open too. The HUD draws the chat
 * behind the menu, under its blur, so there the chat is drawn again over the menu instead.
 */
public final class PeekChatFeature extends ConfigurableFeature<MiscConfig> {
    public static final PeekChatFeature INSTANCE = new PeekChatFeature();
    private static boolean asFocused;
    private boolean scrolled;

    private PeekChatFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::releaseScroll);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) ->
                drawOverMenu(client, current, graphics)));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().peekChatEnabled(); }

    /** Whether the peek key is held, in the world with no screen open or, with Peek In Menus on, in a menu. */
    public static boolean peeking() {
        Minecraft client = Minecraft.getInstance();
        if (!INSTANCE.isEnabled() || client.player == null) return false;
        Screen screen = McCompat.screen(client);
        return (screen == null || INSTANCE.config().peekChatInMenus() && menuAllowsPeek(screen))
            && LoadoutsAutoCloseFeature.keybindHeld(INSTANCE.config().peekChatKeybind());
    }

    /** True while the peek is drawn over a menu, not in the world. */
    public static boolean peekingInMenu() {
        return McCompat.screen(Minecraft.getInstance()) != null && peeking();
    }

    /** The chat screen shows the chat already, and a focused text box takes the peek key as a letter. */
    private static boolean menuAllowsPeek(Screen screen) {
        return !(screen instanceof ChatScreen) && !(screen.getFocused() instanceof EditBox);
    }

    private static void drawOverMenu(Minecraft client, Screen screen, GuiGraphicsExtractor graphics) {
        if (screen != McCompat.screen(client) || !peeking()) return;
        graphics.nextStratum();
        // The mouse is parked off-screen so no hover shows, as in the world.
        asFocusedChat(() -> McCompat.chat(client).extractRenderState(graphics, client.font, McCompat.guiTicks(client),
            -1, -1, ChatComponent.DisplayMode.FOREGROUND, false));
    }

    /** Whether {@link #asFocusedChat} is running; ChatComponent then answers isChatFocused with yes. */
    public static boolean chatFocusedByPeek() { return asFocused; }

    /** Runs the chat as if its screen were open, so it takes the focused height (Chat Patches' too) and scroll limit. */
    public static void asFocusedChat(Runnable action) {
        asFocused = true;
        try {
            action.run();
        } finally {
            asFocused = false;
        }
    }

    /** The wheel while peeking scrolls the chat; true means it is taken, so the hotbar stays put. */
    public static boolean scroll(double wheel) {
        if (!peeking() || !INSTANCE.config().peekChatScrollEnabled()) return false;
        // Like the chat screen: one notch is one step of the configured number of lines (the wheel is -1..1).
        int lines = (int) (Math.clamp(wheel, -1.0, 1.0) * INSTANCE.config().peekChatScrollLines());
        INSTANCE.scrolled = true;
        asFocusedChat(() -> McCompat.chat(Minecraft.getInstance()).scrollChat(lines));
        return true;
    }

    /** Letting go of the key puts the chat back to the newest line, as closing the chat screen does. */
    private void releaseScroll(Minecraft client) {
        if (!scrolled || peeking()) return;
        scrolled = false;
        McCompat.chat(client).resetChatScroll();
    }
}
