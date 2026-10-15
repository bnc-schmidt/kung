package com.github.beng420.kung.feature.screen;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;

import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import com.github.beng420.kung.feature.misc.NpcDialogueTrace;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

public final class ScreenTracker {
    private static String lastScreenKey = "";

    private ScreenTracker() {
    }

    public static void initializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ScreenTracker::tick);
    }

    private static void tick(Minecraft client) {
        NpcDialogueTrace.screen(McCompat.screen(client));
        OpenScreen nextScreen = openScreen(McCompat.screen(client));
        String nextKey = nextScreen == null ? "" : nextScreen.key();
        if (nextKey.equals(lastScreenKey)) {
            return;
        }

        lastScreenKey = nextKey;
        LoadoutsAutoCloseFeature.observeScreenChange(McCompat.screen(client));
        KungDebugRecorder.event("screen", nextScreen == null
            ? "closed"
            : "opened title=\"" + nextScreen.title() + "\" class=" + nextScreen.simpleClassName()
                + " container=" + nextScreen.container());
        if (nextScreen != null
            && nextScreen.container()
            && KungConfig.get().debug.interfaceMessagesEnabled()
            && client.player != null) {
            client.player.sendSystemMessage(KungMessages.debug(
                "Debug",
                "Opened interface: "
                    + nextScreen.title()
                    + " ("
                    + nextScreen.simpleClassName()
                    + ")"
            ));
        }
    }

    private static OpenScreen openScreen(Screen screen) {
        if (screen == null) {
            return null;
        }

        Component titleComponent = screen.getTitle();
        String title = titleComponent == null ? "" : titleComponent.getString().trim();
        String className = screen.getClass().getName();
        String simpleClassName = screen.getClass().getSimpleName();
        boolean container = screen instanceof AbstractContainerScreen<?>;
        return new OpenScreen(title, className, simpleClassName, container);
    }

    public record OpenScreen(String title, String className, String simpleClassName, boolean container) {
        String key() {
            return className + "|" + container + "|" + title.toLowerCase(Locale.ROOT);
        }
    }
}
