package com.github.beng420.kung.mixin;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.feature.garden.GreenhouseHologramSettings;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /**
     * Loadout mouse binds are claimed before MouseHandler hands the click to the screen. Other mods wrap
     * that call (Fabric screen events, SkyblockAPI, Architectury) and hook AbstractContainerScreen.mouseClicked,
     * and any of them can consume a side button before a mouseClicked hook runs. Only bound buttons are taken,
     * so unbound left/right clicks still reach the menu.
     */
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void kung$beforeButton(long window, MouseButtonInfo info, int action, CallbackInfo callbackInfo) {
        if (action == GLFW.GLFW_PRESS
            && McCompat.screen(Minecraft.getInstance()) instanceof AbstractContainerScreen<?> screen
            && LoadoutsAutoCloseFeature.handleLoadoutMouseHotkey(screen, info.button())) {
            callbackInfo.cancel();
            return;
        }
        // The Greenhouse hologram's toggle key may be a mouse button; it only observes, the click still goes on.
        Minecraft client = Minecraft.getInstance();
        if (action == GLFW.GLFW_PRESS && client.player != null && McCompat.screen(client) == null) {
            GreenhouseHologramSettings.onMousePress(info.button());
        }
    }
}
