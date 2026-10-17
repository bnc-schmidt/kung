package com.github.beng420.kung.skyblock;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Reads the SkyBlock item id Hypixel stores in an item's custom data. */
public final class SkyblockItemId {
    private SkyblockItemId() { }

    /** The SkyBlock item id of a stack ("" when the client has no custom data for it). */
    public static String of(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("id", "");
    }
}
