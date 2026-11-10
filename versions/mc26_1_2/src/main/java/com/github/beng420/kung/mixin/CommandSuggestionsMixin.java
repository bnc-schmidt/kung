package com.github.beng420.kung.mixin;

import com.github.beng420.kung.command.FriendNameCompletion;
import com.github.beng420.kung.skyblock.HypixelFriendTracker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    /** A server that never answers a name request must not keep the friends from showing. */
    private static final long SERVER_WAIT_MILLIS = 300L;

    /**
     * The tab suggestions of a typed command: when the word at the cursor is a player name, the friends join what the
     * command tree and the server give. Only this one call is wrapped, so the suggestion list and its usage line
     * work as vanilla's.
     */
    @WrapOperation(method = "updateCommandInfo", at = @At(value = "INVOKE",
        target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions("
            + "Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> kung$addFriendNames(CommandDispatcher<ClientSuggestionProvider> dispatcher,
                                                               ParseResults<ClientSuggestionProvider> parse, int cursor,
                                                               Operation<CompletableFuture<Suggestions>> original) {
        CompletableFuture<Suggestions> pending = original.call(dispatcher, parse, cursor);
        String input = parse.getReader().getString();
        int nameStart = FriendNameCompletion.nameStart(parse, cursor);
        if (nameStart < 0) return pending;
        return pending.completeOnTimeout(Suggestions.empty().join(), SERVER_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            .thenApply(suggestions -> FriendNameCompletion.merge(input, cursor, nameStart, suggestions,
                HypixelFriendTracker.INSTANCE.names()));
    }
}
