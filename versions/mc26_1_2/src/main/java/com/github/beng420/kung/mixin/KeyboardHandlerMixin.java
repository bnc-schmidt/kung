package com.github.beng420.kung.mixin;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.feature.garden.GreenhouseHologramSettings;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    /**
     * Fabric has no client event for a key pressed in the world, and a screen-less press never reaches the
     * screen hooks the loadout and sack keys use. The private keyPress is the same in 26.1.2 and 26.2. This only
     * observes: the key still reaches Minecraft, and repeats and releases are ignored.
     */
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void kung$observeKeyPress(long window, int action, KeyEvent event, CallbackInfo callbackInfo) {
        Minecraft client = Minecraft.getInstance();
        if (action == GLFW.GLFW_PRESS && client.player != null && McCompat.screen(client) == null) {
            GreenhouseHologramSettings.onKeyPress(event);
        }
    }
}
