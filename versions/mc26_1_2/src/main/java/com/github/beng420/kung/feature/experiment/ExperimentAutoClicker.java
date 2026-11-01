package com.github.beng420.kung.feature.experiment;

import java.util.SplittableRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Clicks experiment slots for the player. Full flavor only: Hypixel disallows automatic interaction with
 * inventories, so {@code versions/common.gradle} cuts this class from the Modrinth jar (and fails the build if
 * anything there still names it); every caller sits in {@code if (!KungBuild.MODRINTH)}.
 *
 * <p>It sends the same left click as a click on the open screen, through vanilla's own container click path,
 * and only while that screen's menu is the player's current one.
 */
public final class ExperimentAutoClicker {
    private static final AutoPacer PACER = new AutoPacer(new SplittableRandom());
    private static final AutoPacer CLOSE_PACER = new AutoPacer(new SplittableRandom());

    private ExperimentAutoClicker() { }

    /**
     * Clicks {@code target} once {@code step} has waited its delay. A negative target means nothing to click
     * yet and restarts the wait. True when a click was sent.
     */
    public static boolean tick(Minecraft client, AbstractContainerMenu menu, int target, long step,
                               boolean slowStart, int delayMillis) {
        if (target < 0) {
            PACER.idle();
            return false;
        }
        return PACER.poll(step, System.currentTimeMillis(), delayMillis, slowStart) && click(client, menu, target);
    }

    /**
     * Closes the experiment window, as Escape does, once {@code step} has waited twice the click delay (the
     * server should have the last click by then). True when the window was closed.
     */
    public static boolean tickClose(Minecraft client, AbstractContainerMenu menu, long step, int delayMillis) {
        LocalPlayer player = client.player;
        if (player == null || player.containerMenu != menu
            || !CLOSE_PACER.poll(step, System.currentTimeMillis(), delayMillis * 2, false)) {
            return false;
        }
        player.closeContainer();
        return true;
    }

    /** One left click on {@code slot} of {@code menu}; false when nothing was sent. */
    public static boolean click(Minecraft client, AbstractContainerMenu menu, int slot) {
        return click(client, menu, slot, 0, ContainerInput.PICKUP);
    }

    /**
     * One middle click (Pick Block) on {@code slot}: the client changes nothing, so the item is not picked up
     * and a quick second click does not meet a half-moved stack. False when nothing was sent.
     */
    public static boolean pickBlockClick(Minecraft client, AbstractContainerMenu menu, int slot) {
        return click(client, menu, slot, 2, ContainerInput.CLONE);
    }

    private static boolean click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        LocalPlayer player = client.player;
        MultiPlayerGameMode gameMode = client.gameMode;
        if (player == null || gameMode == null || player.containerMenu != menu
            || slot < 0 || slot >= menu.slots.size()) {
            return false;
        }
        gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
        return true;
    }

    public static void reset() {
        PACER.idle();
        CLOSE_PACER.idle();
    }
}
