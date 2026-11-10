package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.experiment.ExperimentSolverFeature;
import com.github.beng420.kung.feature.foraging.BeaconSolverFeature;
import com.github.beng420.kung.feature.hunting.AttributeMenuFeature;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import com.github.beng420.kung.feature.misc.SuperpairsHelperFeature;
import com.github.beng420.kung.feature.misc.SackTrackerFeature;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Shadow
    protected Slot hoveredSlot;

    @Unique
    private Slot kung$extractingSlot;

    // 26.3 adds a private slotClicked(Slot, int, MouseButtonEvent, ContainerInput) that delegates here, so name the overload.
    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
        at = @At("TAIL"))
    private void kung$afterSlotClicked(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo callbackInfo) {
        LoadoutsAutoCloseFeature.observeSlotClick(
            (AbstractContainerScreen<?>) (Object) this,
            slot,
            slotId,
            button,
            input
        );
        ExperimentSolverFeature.observeSlotClick((AbstractContainerScreen<?>) (Object) this, slotId);
        BeaconSolverFeature.observeSlotClick((AbstractContainerScreen<?>) (Object) this, slotId, button, input);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void kung$beforeKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> callbackInfo) {
        var screen = (AbstractContainerScreen<?>) (Object) this;
        if (LoadoutsAutoCloseFeature.handleLoadoutHotkey(screen, event)
            || SackTrackerFeature.handleKey(screen, hoveredSlot, event)) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void kung$beforeMouseClicked(
        MouseButtonEvent event,
        boolean doubleClick,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        // Loadout mouse binds are claimed earlier, in MouseHandlerMixin.
        if (SackTrackerFeature.handleMouse((AbstractContainerScreen<?>) (Object) this, hoveredSlot, event)) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void kung$beforeExtractSlot(
        GuiGraphicsExtractor graphics,
        Slot slot,
        int mouseX,
        int mouseY,
        CallbackInfo callbackInfo
    ) {
        kung$extractingSlot = slot;
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void kung$afterExtractSlot(
        GuiGraphicsExtractor graphics,
        Slot slot,
        int mouseX,
        int mouseY,
        CallbackInfo callbackInfo
    ) {
        kung$extractingSlot = null;
        SuperpairsHelperFeature.drawSlot((AbstractContainerScreen<?>) (Object) this, graphics, slot);
        ExperimentSolverFeature.drawSlot((AbstractContainerScreen<?>) (Object) this, graphics, slot);
        BeaconSolverFeature.drawSlot((AbstractContainerScreen<?>) (Object) this, graphics, slot);
    }

    // Hovering a card that Superpairs' Keep Cards Visible draws over a button shows the card's tooltip, not the button's.
    @Inject(method = "getTooltipFromContainerItem", at = @At("HEAD"), cancellable = true)
    private void kung$keptCardTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> callbackInfo) {
        List<Component> kept = SuperpairsHelperFeature.keptCardTooltip((AbstractContainerScreen<?>) (Object) this, hoveredSlot, stack);
        if (kept != null) {
            callbackInfo.setReturnValue(kept);
        }
    }

    @ModifyArg(
        method = "extractSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"
        ),
        index = 4
    )
    private String kung$loadoutStackSizeLabel(String original) {
        var screen = (AbstractContainerScreen<?>) (Object) this;
        // The Attribute Menu's level replaces any count another mod set; the other labels only fill an empty one.
        String attributeLevel = AttributeMenuFeature.slotLabel(screen, kung$extractingSlot);
        if (attributeLevel != null) {
            return attributeLevel;
        }
        if (original != null) {
            return original;
        }
        String loadout = LoadoutsAutoCloseFeature.loadoutStackSizeLabel(screen, kung$extractingSlot);
        return loadout != null ? loadout : BeaconSolverFeature.slotLabel(screen, kung$extractingSlot);
    }
}
