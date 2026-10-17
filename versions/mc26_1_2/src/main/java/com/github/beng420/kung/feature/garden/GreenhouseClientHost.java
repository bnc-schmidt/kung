package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * The running game behind {@link GreenhouseSession}. Kung's own update card is built for update notices
 * (versions and buttons), so a finished solve uses the game's toast, whose manager moved between 26.1.2 and
 * 26.2 and is reached through {@link McCompat}.
 */
final class GreenhouseClientHost implements GreenhouseSession.Host {
    static final GreenhouseClientHost INSTANCE = new GreenhouseClientHost();
    /** One id, so a newer finish replaces a toast still on screen instead of stacking. */
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

    private GreenhouseClientHost() { }

    @Override public void runOnClient(Runnable task) { Minecraft.getInstance().execute(task); }

    @Override public void toast(String message) {
        SystemToast.addOrUpdate(McCompat.toasts(Minecraft.getInstance()), TOAST_ID, Component.literal("Greenhouse planner"),
            Component.literal(message));
    }

    @Override public void warn(String message, Throwable cause) { KungMod.LOGGER.warn(message, cause); }

    /** Ends a running solve when the game closes, so the worker does not outlive the client's own shutdown. */
    static void install(GreenhouseSession session) {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> session.shutdown());
    }
}
