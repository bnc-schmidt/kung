package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.experiment.AutoPacer;
import com.github.beng420.kung.feature.experiment.ExperimentAutoClicker;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Clicks the notes of Melody's Harp ("Harp - <song>") when they light up. Full flavor only: Hypixel disallows
 * automatic interaction with inventories, so {@code versions/common.gradle} cuts this class (and
 * {@link HarpPlanner}) from the Modrinth jar, and its one use sits in {@code if (!KungBuild.MODRINTH)}.
 * The click is the one {@link ExperimentAutoClicker} sends; every decision is logged under "harp".
 */
public final class AutoHarpFeature extends ConfigurableFeature<MiscConfig> {
    public static final AutoHarpFeature INSTANCE = new AutoHarpFeature();
    private static final String AREA = "harp";
    private static final String TITLE_PREFIX = "Harp - ";
    private static final int TOP_SLOT_COUNT = 54;

    /** One pacer per column, so two notes lit together each wait their own delay. */
    private final AutoPacer[] pacers = new AutoPacer[HarpPlanner.COLUMNS];
    private HarpPlanner planner = new HarpPlanner();
    private int[] behind = new int[HarpPlanner.COLUMNS];
    private AbstractContainerMenu activeMenu;
    private boolean layoutLogged;
    private String lastLogged = "";

    private AutoHarpFeature() {
        super(config -> config.misc);
        SplittableRandom random = new SplittableRandom();
        for (int column = 0; column < pacers.length; column++) {
            pacers[column] = new AutoPacer(random);
        }
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    @Override
    public boolean isEnabled() {
        return config().autoHarpEnabled();
    }

    private void tick(Minecraft client) {
        if (!refresh(client)) {
            return;
        }
        long now = System.currentTimeMillis();
        int ping = PerformanceHudFeature.freshPing(client);
        int delay = AutoPacer.waitFor(config().autoHarpDelayMillis(), ping);
        for (int slot : planner.targets()) {
            AutoPacer pacer = pacers[slot - HarpPlanner.FIRST_BUTTON_SLOT];
            if (pacer.poll(planner.note(slot), now, delay, false) && ExperimentAutoClicker.pickBlockClick(client, activeMenu, slot)) {
                planner.markClicked(slot);
                KungDebugRecorder.event(AREA, "click slot=" + slot + " note=" + planner.note(slot) + " wait=" + delay
                    + " ping=" + ping + " set=" + config().autoHarpDelayMillis() + " behind="
                    + behind[slot - HarpPlanner.FIRST_BUTTON_SLOT]);
                // One click per tick, like a hand: a second note lit together waits for the next tick.
                break;
            }
        }
    }

    /**
     * Called from the applied container packet hooks, never Netty receipt. Reading on every packet instead of
     * once per tick keeps a note that goes dark and lights again within one tick from looking like one note.
     */
    public static void observeMenuPacket(int containerId) {
        Minecraft client = Minecraft.getInstance();
        if (INSTANCE.initialized() && INSTANCE.activeMenu != null && client.player != null
            && client.player.containerMenu.containerId == containerId) {
            INSTANCE.refresh(client);
        }
    }

    /** Reads the buttons into the planner; false (after a reset) when no Harp song is open or the feature is off. */
    private boolean refresh(Minecraft client) {
        if (!initialized() || !isEnabled() || !trackHarp(client)) {
            resetState("closed-or-disabled");
            return false;
        }
        List<String> top = new ArrayList<>(TOP_SLOT_COUNT);
        for (int slot = 0; slot < TOP_SLOT_COUNT; slot++) {
            top.add(itemId(activeMenu.slots.get(slot).getItem()));
        }
        behind = HarpPlanner.notesBehind(top);
        List<Integer> expired = planner.observe(top.subList(HarpPlanner.FIRST_BUTTON_SLOT,
            HarpPlanner.FIRST_BUTTON_SLOT + HarpPlanner.COLUMNS), behind);
        if (!expired.isEmpty()) {
            KungDebugRecorder.event(AREA, "note went dark unclicked " + expired);
        }
        logState();
        return true;
    }

    /** True while the player has a Harp song open as their current menu. */
    private boolean trackHarp(Minecraft client) {
        Screen screen = McCompat.screen(client);
        if (client.player == null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        AbstractContainerMenu menu = container.getMenu();
        String title = screen.getTitle().getString().trim();
        if (menu != client.player.containerMenu || !title.startsWith(TITLE_PREFIX)
            || MenuItems.topSlots(menu) != TOP_SLOT_COUNT) {
            return false;
        }
        // Another song never continues what was learned, even with the same title.
        if (activeMenu != menu) {
            resetState("new-menu");
            activeMenu = menu;
            KungDebugRecorder.event(AREA, "start container=" + menu.containerId + " title=\"" + title + "\"");
        }
        return true;
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** For the trace: the layout once, then every change of the lit buttons with the name each shows. */
    private void logState() {
        if (!layoutLogged) {
            StringBuilder layout = new StringBuilder();
            for (int slot = 0; slot < TOP_SLOT_COUNT; slot++) {
                String item = itemId(activeMenu.slots.get(slot).getItem()).replace("minecraft:", "");
                if (!item.isEmpty() && !item.equals("black_stained_glass_pane")) {
                    layout.append(layout.length() > 0 ? ", " : "").append(slot).append('=').append(item);
                }
            }
            if (layout.length() > 0) {
                layoutLogged = true;
                KungDebugRecorder.event(AREA, "layout [" + layout + "]");
            }
        }
        StringBuilder lit = new StringBuilder("[");
        for (int slot = HarpPlanner.FIRST_BUTTON_SLOT; slot < HarpPlanner.FIRST_BUTTON_SLOT + HarpPlanner.COLUMNS; slot++) {
            if (planner.note(slot) != 0) {
                lit.append(lit.length() > 1 ? ", " : "").append(slot).append(" \"")
                    .append(activeMenu.slots.get(slot).getItem().getHoverName().getString()).append("\" note=")
                    .append(planner.note(slot)).append(" behind=").append(behind[slot - HarpPlanner.FIRST_BUTTON_SLOT]);
            }
        }
        String state = lit.append(']').toString();
        if (!state.equals(lastLogged)) {
            lastLogged = state;
            KungDebugRecorder.event(AREA, "lit " + state);
        }
    }

    private void resetState(String reason) {
        if (activeMenu == null) {
            return;
        }
        KungDebugRecorder.event(AREA, "reset container=" + activeMenu.containerId + " reason=" + reason);
        activeMenu = null;
        planner = new HarpPlanner();
        behind = new int[HarpPlanner.COLUMNS];
        layoutLogged = false;
        lastLogged = "";
        for (AutoPacer pacer : pacers) {
            pacer.idle();
        }
    }
}
