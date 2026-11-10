package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.KungMod;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

public final class DungeonEventRouter {
    private static DungeonEventRouter activeRouter;

    private final DungeonStateTracker tracker;

    public DungeonEventRouter(DungeonStateTracker tracker) {
        this.tracker = java.util.Objects.requireNonNull(tracker, "tracker");
    }

    public void register() {
        activeRouter = this;
        ClientTickEvents.END_CLIENT_TICK.register(tracker::tick);
        DungeonServerTickEvents.register(tracker::serverTick);
        registerMessageObservers(tracker::observeGameMessage, tracker::observeChatMessage);
        // Own actions that can be a secret. The party total in the tab decides which of them were one.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            tracker.observeOwnBlockClick(hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            tracker.observeOwnUse();
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            tracker.observeOwnUse();
            return InteractionResult.PASS;
        });
    }

    /** The server's "player picked up an item" packet; only the local player's own pickups matter. */
    public static void observeItemPickup(int collectorId) {
        DungeonEventRouter router = activeRouter;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (router != null && player != null && player.getId() == collectorId) {
            router.tracker.observeOwnPickup();
        }
    }

    static void registerMessageObservers(BiConsumer<Component, Boolean> game, Consumer<Component> chat) {
        // Observe original messages even when another listener hides or rewrites their display.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            game.accept(message, overlay);
            return true;
        });
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            chat.accept(message);
            return true;
        });
    }

    public static void observeEntityDeath(Entity entity) {
        DungeonEventRouter router = activeRouter;
        if (router != null && entity != null) {
            router.tracker.observeEntityDeath(entity);
        }
    }

    public static void observeSkyblockerBonus(DungeonBonusContribution bonus) {
        DungeonEventRouter router = activeRouter;
        if (router == null) return;
        try {
            router.tracker.observeSkyblockerBonus(bonus);
        } catch (RuntimeException | LinkageError exception) {
            KungMod.LOGGER.warn("Failed to process Skyblocker dungeon bonus.", exception);
        }
    }

    public static void observeInstanceChanged() {
        DungeonEventRouter router = activeRouter;
        if (router != null) {
            try {
                router.tracker.synchronizeInstance(net.minecraft.client.Minecraft.getInstance());
            } catch (RuntimeException | LinkageError exception) {
                KungMod.LOGGER.warn("Failed to synchronize dungeon instance; retrying on the next client tick.", exception);
            }
        }
    }
}
