package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.skyblock.SkyblockItemId;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** The running game behind {@link GreenhouseSupplyRunner}: the player's inventory, command route and chat. */
final class GreenhouseSupplyHost implements GreenhouseSupplyRunner.Host {
    private static final String AREA = "Greenhouse";
    private static final String TRACE_AREA = "greenhouse-supply";
    private int sends;

    /** Hooks the runner into the client tick, game chat and disconnect events; called once. */
    static void register(GreenhouseSupplyRunner runner) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> runner.tick());
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) runner.onChat(message.getString());
            return true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runner.reset());
    }

    @Override public long now() { return System.nanoTime() / 1_000_000L; }

    @Override public boolean onHypixel() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && HypixelLocation.isHypixelAddress(server.ip);
    }

    @Override public int required(String crop) {
        return GreenhouseHologram.grid().map(grid -> GreenhouseSupply.required(grid, crop)).orElse(0);
    }

    @Override public int soilNeeded(GreenhouseSoils.Soil soil) {
        return GreenhouseSupply.soilNeeded(soil, GreenhouseSupply.soilsToFetch(GreenhouseHologram.soilGhosts()));
    }

    @Override public Map<String, Integer> inventory() {
        Map<String, Integer> counts = new HashMap<>();
        var player = Minecraft.getInstance().player;
        if (player == null) return counts;
        // The 36 inventory slots only: armor, offhand and the cursor stack never count as carried.
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            String id = GreenhouseSupply.normalizeId(SkyblockItemId.of(stack));
            if (!id.isEmpty()) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    @Override public int hotbarSlot(String id) { return firstSlot(id, 0, Inventory.getSelectionSize()); }

    @Override public int mainSlot(String id) {
        var player = Minecraft.getInstance().player;
        return player == null ? -1 : firstSlot(id, Inventory.getSelectionSize(), player.getInventory().getNonEquipmentItems().size());
    }

    private static int firstSlot(String id, int from, int to) {
        var player = Minecraft.getInstance().player;
        if (player == null) return -1;
        NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty() && GreenhouseSupply.normalizeId(SkyblockItemId.of(stack)).equals(id)) return slot;
        }
        return -1;
    }

    @Override public void select(int hotbarSlot) {
        var player = Minecraft.getInstance().player;
        // The game sends the new slot to the server on its next tick, as for the number keys.
        if (player != null) player.getInventory().setSelectedSlot(hotbarSlot);
    }

    @Override public boolean swapIntoSelected(int mainSlot) {
        // The only inventory click of the Greenhouse supply: a compile-time cut, so the Modrinth jar has none.
        if (!KungBuild.MODRINTH) return GreenhouseSupplySwap.swap(Minecraft.getInstance(), mainSlot);
        return false;
    }

    @Override public void sendCommand(String command) {
        var player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        // Trace evidence: one line per call of ours around the connection call, so a trace that shows two
        // "npc-dialogue command=" packet lines for one "send" here was doubled outside this class.
        int number = ++sends;
        KungDebugRecorder.event(TRACE_AREA, "send #" + number + " /" + command);
        player.connection.sendCommand(command);
        KungDebugRecorder.event(TRACE_AREA, "send #" + number + " returned");
    }

    @Override public void chat(GreenhouseSupplyRunner.Level level, String message) {
        KungMessages.Type type = switch (level) {
            case INFO -> KungMessages.Type.INFO;
            case WARNING -> KungMessages.Type.WARNING;
            case ERROR -> KungMessages.Type.ERROR;
        };
        KungMessages.send(Minecraft.getInstance(), type, AREA, message);
    }
}
