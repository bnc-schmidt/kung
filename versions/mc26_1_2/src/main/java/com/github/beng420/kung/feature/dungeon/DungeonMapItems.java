package com.github.beng420.kung.feature.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

final class DungeonMapItems {
    private static final int DUNGEON_MAP_SLOT = 8;
    private static Object cachedLevel;
    private static MapItemSavedData cachedMapData;
    private static int cachedLookupTick = Integer.MIN_VALUE;
    private static MapItemSavedData cachedLookupMapData;

    private DungeonMapItems() {
    }

    static MapItemSavedData mapData(Minecraft client) {
        if (client.level == null || client.player == null) {
            clearCache();
            return null;
        }

        if (cachedLevel != client.level) {
            cachedLevel = client.level;
            clearMapDataCache();
        }

        int tick = client.player.tickCount;
        if (cachedLookupTick == tick) {
            return cachedLookupMapData != null ? cachedLookupMapData : cachedMapData;
        }

        MapItemSavedData found = findMapData(client);
        cachedLookupTick = tick;
        cachedLookupMapData = found;
        if (found != null) {
            cachedMapData = found;
            return found;
        }
        return cachedMapData;
    }

    private static void clearCache() {
        cachedLevel = null;
        clearMapDataCache();
    }

    private static void clearMapDataCache() {
        cachedMapData = null;
        cachedLookupTick = Integer.MIN_VALUE;
        cachedLookupMapData = null;
    }

    private static MapItemSavedData findMapData(Minecraft client) {
        MapItemSavedData preferred = mapData(client, client.player.getInventory().getItem(DUNGEON_MAP_SLOT));
        if (preferred != null) {
            return preferred;
        }
        MapItemSavedData offhand = mapData(client, client.player.getOffhandItem());
        if (offhand != null) {
            return offhand;
        }
        MapItemSavedData selected = mapData(client, client.player.getInventory().getSelectedItem());
        if (selected != null) {
            return selected;
        }
        int size = client.player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            MapItemSavedData found = mapData(client, client.player.getInventory().getItem(slot));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static MapItemSavedData mapData(Minecraft client, ItemStack stack) {
        if (stack == null || !stack.is(Items.FILLED_MAP)) {
            return null;
        }
        return MapItem.getSavedData(stack, client.level);
    }
}
