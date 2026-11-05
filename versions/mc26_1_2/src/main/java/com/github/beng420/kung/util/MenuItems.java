package com.github.beng420.kung.util;

import com.github.beng420.kung.feature.garden.GreenhouseDumpFormat;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

/** Reads container menus and the items in them. */
public final class MenuItems {
    /** The player inventory (27 slots and the hotbar) that every container menu ends with. */
    private static final int PLAYER_SLOTS = 36;

    private MenuItems() { }

    /** The slots above the player inventory; 0 for no menu. */
    public static int topSlots(AbstractContainerMenu menu) {
        return menu == null ? 0 : Math.max(0, menu.slots.size() - PLAYER_SLOTS);
    }

    /** The text of every lore line; empty without lore. */
    public static List<String> lore(ItemStack stack) {
        return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(Component::getString).toList();
    }

    /** The text of the first {@code max} lore lines; empty without lore. */
    public static List<String> lore(ItemStack stack, int max) {
        return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().limit(max).map(Component::getString).toList();
    }

    /** The skin hash of a player head: the last segment of the url in its first readable "textures" property; null without one. */
    public static String skinHash(ItemStack head) {
        try {
            ResolvableProfile profile = head.get(DataComponents.PROFILE);
            if (profile == null) return null;
            for (var property : profile.partialProfile().properties().get("textures")) {
                var texture = GreenhouseDumpFormat.texture(property.value());
                if (texture.isPresent()) return texture.get().hash();
            }
        } catch (RuntimeException failure) {
            return null;
        }
        return null;
    }
}
