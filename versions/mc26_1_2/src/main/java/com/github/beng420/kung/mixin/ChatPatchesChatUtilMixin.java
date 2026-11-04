package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.misc.ChatChannelsFeature;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Patches' copy menu finds the clicked message by counting messages with lines and reads allMessages at that
 * index. Chat Channels' hidden messages have no lines, so the index fell short; these two conversions of its ChatUtil
 * count in allMessages instead. Optional: without Chat Patches nothing applies.
 */
@Pseudo
@Mixin(targets = "obro1961.chatpatches.util.ChatUtil", remap = false)
public abstract class ChatPatchesChatUtilMixin {
    /** Clicked line to message: the count of shown messages becomes the index of allMessages. */
    @Inject(method = "visible2Message(I)I", at = @At("RETURN"), cancellable = true, require = 0)
    private static void kung$allIndexOfShown(int line, CallbackInfoReturnable<Integer> callbackInfo) {
        callbackInfo.setReturnValue(ChatChannelsFeature.allIndexOfShown(callbackInfo.getReturnValueI()));
    }

    /** Message to its line, for the selection outline: the index of allMessages becomes the count of shown ones. */
    @ModifyVariable(method = "message2Visible(I)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private static int kung$shownIndexOfAll(int index) {
        return ChatChannelsFeature.shownIndexOfAll(index);
    }

    /**
     * The dupe counter removes the lines of an earlier equal message. A hidden one has none, so its line is -1 and
     * the removal crashed the packet handler ("Network Protocol Error"). Past the end of the lines it removes nothing.
     */
    @ModifyVariable(method = "tryCondenseDupes", at = @At("STORE"), index = 11, require = 0)
    private static int kung$noLineIsPastTheEnd(int line) {
        return line < 0 ? Integer.MAX_VALUE : line;
    }

    @Redirect(method = "tryCondenseDupes",
        at = @At(value = "INVOKE", target = "Ljava/util/List;remove(I)Ljava/lang/Object;", ordinal = 0), require = 0)
    private static Object kung$removeNoLine(List<?> lines, int index) {
        return index < 0 || index >= lines.size() ? null : lines.remove(index);
    }
}
