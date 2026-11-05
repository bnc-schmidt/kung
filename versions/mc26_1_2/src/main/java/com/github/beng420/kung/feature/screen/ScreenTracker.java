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
        Screen screen = McCompat.screen(client);
        NpcDialogueTrace.screen(screen);
        boolean container = screen instanceof AbstractContainerScreen<?>;
        Component titleComponent = screen == null ? null : screen.getTitle();
        String title = titleComponent == null ? "" : titleComponent.getString().trim();
        String key = screen == null ? ""
            : screen.getClass().getName() + "|" + container + "|" + title.toLowerCase(Locale.ROOT);
        if (key.equals(lastScreenKey)) {
            return;
        }

        lastScreenKey = key;
        LoadoutsAutoCloseFeature.observeScreenChange(screen);
        if (screen == null) {
            KungDebugRecorder.event("screen", "closed");
            return;
        }
        String simpleClassName = screen.getClass().getSimpleName();
        KungDebugRecorder.event("screen",
            "opened title=\"" + title + "\" class=" + simpleClassName + " container=" + container);
        if (container && KungConfig.get().debug.interfaceMessagesEnabled() && client.player != null) {
            client.player.sendSystemMessage(KungMessages.debug(
                "Debug", "Opened interface: " + title + " (" + simpleClassName + ")"));
        }
    }
}
