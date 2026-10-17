package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.garden.GreenhousePickHook;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftPickBlockMixin {
    /**
     * Middle click is handled in a private no-argument method, the same in 26.1.2 and 26.2, which returns at
     * once on a miss. Fabric has no client event for it, so this runs first: the Greenhouse hologram claims the
     * click only when the view ray meets one of its cells before any real block or entity.
     */
    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void kung$pickHologramCell(CallbackInfo callbackInfo) {
        if (GreenhousePickHook.tryPick((Minecraft) (Object) this)) {
            callbackInfo.cancel();
        }
    }
}
