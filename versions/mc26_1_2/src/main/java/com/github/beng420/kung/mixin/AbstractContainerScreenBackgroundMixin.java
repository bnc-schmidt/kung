package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.hunting.AttributeMenuFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slot backgrounds that must cover other mods' item backgrounds. Skyblocker draws its rarity background at this same
 * point, right before the item, so this mixin has a high priority: Mixin applies it later, so its call runs after theirs.
 * Mods that draw at the head of extractSlot, or around it, draw before this anyway.
 */
@Mixin(value = AbstractContainerScreen.class, priority = 2000)
public abstract class AbstractContainerScreenBackgroundMixin {
    @Inject(
        method = "extractSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/item/ItemStack;III)V"
        )
    )
    private void kung$beforeSlotItem(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        AttributeMenuFeature.drawBackground((AbstractContainerScreen<?>) (Object) this, graphics, slot);
    }
}
