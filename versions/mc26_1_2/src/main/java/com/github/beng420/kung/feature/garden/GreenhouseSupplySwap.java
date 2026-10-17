package com.github.beng420.kung.feature.garden;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/**
 * Moves a stack from the main inventory into the selected hotbar slot with one inventory click. Full flavor
 * only: Hypixel disallows automatic interaction with inventories, so {@code versions/common.gradle} cuts this
 * class from the Modrinth jar (and fails the build if anything there still names it); the one caller sits in
 * {@code if (!KungBuild.MODRINTH)}.
 *
 * <p>Known risk, accepted by the user for the full (GitHub) flavor: the click is sent whether or not the
 * inventory screen is open. A vanilla client sends an inventory click only from an open container screen, so a
 * server that checks for that can tell this click from a player's.
 */
final class GreenhouseSupplySwap {
    private GreenhouseSupplySwap() { }

    /**
     * Swaps main inventory slot {@code mainSlot} (9-35) with the selected hotbar slot by sending the click packet
     * for the player's own inventory menu (id 0). That menu is also the current one when NO screen is open, so
     * this does send with the inventory closed; it only refuses while another container (a chest, an NPC window)
     * is the open menu. False when nothing was sent.
     */
    static boolean swap(Minecraft client, int mainSlot) {
        var player = client.player;
        var gameMode = client.gameMode;
        if (player == null || gameMode == null || player.containerMenu != player.inventoryMenu) return false;
        Inventory inventory = player.getInventory();
        int menuSlot = menuSlot(player.inventoryMenu, inventory, mainSlot);
        if (menuSlot < 0) return false;
        gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, inventory.getSelectedSlot(),
            ContainerInput.SWAP, player);
        return true;
    }

    /** The menu's slot id for an inventory index; the menu orders hotbar, main and armor its own way. */
    private static int menuSlot(AbstractContainerMenu menu, Inventory inventory, int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == inventoryIndex) return slot.index;
        }
        return -1;
    }
}
