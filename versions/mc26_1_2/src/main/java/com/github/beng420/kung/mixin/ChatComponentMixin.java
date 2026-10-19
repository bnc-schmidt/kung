package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.misc.PeekChatFeature;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Shadow
    public abstract void extractRenderState(GuiGraphicsExtractor graphics, Font font, int tickCount, int mouseX,
                                            int mouseY, ChatComponent.DisplayMode mode, boolean changeCursor);

    /**
     * The HUD draws the chat with the fading BACKGROUND mode; while Peek Chat is held the same draw runs again as the
     * chat screen's FOREGROUND mode instead. The mouse is parked off-screen so no hover shows, as the cursor is
     * grabbed in the world. Only this public draw is touched, which the chat mods in use leave alone.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;"
        + "IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V", at = @At("HEAD"), cancellable = true)
    private void kung$peek(GuiGraphicsExtractor graphics, Font font, int tickCount, int mouseX, int mouseY,
                           ChatComponent.DisplayMode mode, boolean changeCursor, CallbackInfo callbackInfo) {
        if (mode != ChatComponent.DisplayMode.BACKGROUND || !PeekChatFeature.peeking()) return;
        callbackInfo.cancel();
        PeekChatFeature.asFocusedChat(() ->
            extractRenderState(graphics, font, tickCount, -1, -1, ChatComponent.DisplayMode.FOREGROUND, false));
    }

    /** Lets getHeight() pick the focused height while the peeked chat is drawn or scrolled. */
    @Inject(method = "isChatFocused", at = @At("RETURN"), cancellable = true)
    private void kung$focusedWhilePeeking(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (PeekChatFeature.chatFocusedByPeek()) callbackInfo.setReturnValue(true);
    }
}
