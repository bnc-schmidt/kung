package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.Locale;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public final class LoadoutsAutoCloseFeature extends ConfigurableFeature<MiscConfig> {
    public static final LoadoutsAutoCloseFeature INSTANCE = new LoadoutsAutoCloseFeature();
    private static final int PLAYER_INVENTORY_SLOT_COUNT = 36;
    private static final int SLOT_UPDATE_LOG_LIMIT = 24;
    private static final int SUPPRESS_HOTKEY_OPEN_PACKET_COUNT = 4;
    private static final int[] LOADOUT_HOTKEY_SLOTS = {14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43};

    private static boolean pendingClose;
    private static boolean pendingEquipConfirmed;
    private static int pendingClickContainerId = -1;
    private static int pendingLastServerContainerId = -1;
    private static int suppressLoadoutsOpenPackets;
    private static int slotUpdateLogContainerId = -1;
    private static int slotUpdateLogCount;

    private LoadoutsAutoCloseFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> observeMessage(message));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(message));
    }

    @Override
    public boolean isEnabled() {
        return config().loadoutsAutoCloseEnabled();
    }

    public static void observeScreenChange(Screen screen) {
        if (!INSTANCE.isEnabled()) {
            return;
        }

        slotUpdateLogContainerId = -1;
        slotUpdateLogCount = 0;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            if (screen == null) {
                if (suppressLoadoutsOpenPackets > 0) {
                    KungDebugRecorder.event("loadouts-auto-close", "screen closed; keeping hotkey suppress remaining="
                        + suppressLoadoutsOpenPackets
                        + " clickContainer="
                        + pendingClickContainerId);
                } else {
                    clearPendingClose("screen-closed");
                }
            }
            KungDebugRecorder.event("loadouts-auto-close", "screen "
                + (screen == null ? "closed" : "non-container title=\"" + screen.getTitle().getString() + "\""));
            return;
        }

        AbstractContainerMenu menu = containerScreen.getMenu();
        KungDebugRecorder.event("loadouts-auto-close", "screen-open enabled=true title=\""
            + screen.getTitle().getString()
            + "\" class="
            + screen.getClass().getSimpleName()
            + " container="
            + menu.containerId
            + " slots="
            + menu.slots.size()
            + " topSlots="
            + topSlotCount(menu)
            + " context="
            + isLoadoutsContext(screen, menu, ItemStack.EMPTY)
            + " sample="
            + topSlotSummary(menu, 12));
        if (pendingClose && isLoadoutsContext(screen, menu, ItemStack.EMPTY)) {
            observeServerRefresh("screen-change", menu.containerId);
            closeIfReady("screen-change");
        }
    }

    public static void observeSlotClick(
        AbstractContainerScreen<?> screen,
        Slot slot,
        int slotId,
        int button,
        ContainerInput input
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || screen == null) {
            return;
        }

        ItemStack stack = slot == null ? ItemStack.EMPTY : slot.getItem();
        boolean context = isLoadoutsContext(screen, screen.getMenu(), stack);
        if (!INSTANCE.isEnabled() && !context) {
            return;
        }

        observeClick("screen", screen, screen.getMenu(), slotId, button, input, stack, context);
    }

    public static void observeContainerInput(int containerId, int slotId, int button, ContainerInput input) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        AbstractContainerMenu menu = client.player.containerMenu;
        ItemStack stack = slotStack(menu, slotId);
        boolean context = isLoadoutsContext(McCompat.screen(client), menu, stack);
        if (!INSTANCE.isEnabled() && !context) {
            return;
        }

        observeClick("game-mode", containerScreen, menu, slotId, button, input, stack, context);
    }

    public static boolean handleLoadoutHotkey(AbstractContainerScreen<?> screen, KeyEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!INSTANCE.isEnabled()
            || client.player == null
            || client.gameMode == null
            || screen == null
            || !isLoadoutsScreen(screen)) {
            return false;
        }

        int loadoutIndex = matchingKeybindIndex(event);
        if (loadoutIndex < 0) {
            return false;
        }

        return activateLoadoutHotkey(screen, loadoutIndex, "key");
    }

    /** Called from MouseHandler.onButton with the raw GLFW button; see MouseHandlerMixin for why not mouseClicked. */
    public static boolean handleLoadoutMouseHotkey(AbstractContainerScreen<?> screen, int button) {
        Minecraft client = Minecraft.getInstance();
        if (!INSTANCE.isEnabled()
            || client.player == null
            || client.gameMode == null
            || screen == null
            || !isLoadoutsScreen(screen)) {
            return false;
        }

        int loadoutIndex = matchingMouseKeybindIndex(button);
        if (loadoutIndex < 0) {
            return false;
        }

        return activateLoadoutHotkey(screen, loadoutIndex, "mouse");
    }

    public static String keybindFromKeyEvent(KeyEvent event) {
        if (event == null) {
            return "";
        }
        return "key:" + event.key() + ":" + event.scancode();
    }

    public static String keybindFromMouseButton(int button) {
        return "mouse:" + button;
    }

    public static String keybindDisplay(String keybind) {
        if (keybind == null || keybind.isBlank()) {
            return "None";
        }

        if (keybind.startsWith("mouse:")) {
            int button = parseKeybindPart(keybind, 1, -1);
            return button < 0 ? "Mouse" : "M" + (button + 1);
        }

        if (!keybind.startsWith("key:")) {
            return compactHotkeyLabel(keybind);
        }

        int key = parseKeybindPart(keybind, 1, -1);
        int scancode = parseKeybindPart(keybind, 2, 0);
        if (key >= 0) {
            return compactHotkeyLabel(keyName(key));
        }
        return scancode == 0 ? "None" : "Scan " + scancode;
    }

    private static boolean activateLoadoutHotkey(AbstractContainerScreen<?> screen, int loadoutIndex, String source) {
        Minecraft client = Minecraft.getInstance();
        int slotId = LOADOUT_HOTKEY_SLOTS[loadoutIndex];
        AbstractContainerMenu menu = screen.getMenu();
        ItemStack stack = slotStack(menu, slotId);
        if (!isLoadoutSelectionStack(slotId, stack)) {
            KungDebugRecorder.event("loadouts-auto-close", "hotkey ignored index="
                + (loadoutIndex + 1)
                + " slot="
                + slotId
                + " item="
                + itemDebug(stack));
            return true;
        }

        clearPendingClose("hotkey");
        pendingClose = true;
        pendingEquipConfirmed = false;
        pendingClickContainerId = menu.containerId;
        boolean closeOnlyOnChange = INSTANCE.config().loadoutsCloseOnlyOnChange();
        suppressLoadoutsOpenPackets = closeOnlyOnChange ? 0 : SUPPRESS_HOTKEY_OPEN_PACKET_COUNT;
        KungDebugRecorder.event("loadouts-auto-close", "hotkey index="
            + (loadoutIndex + 1)
            + " source="
            + source
            + " keybind=\""
            + INSTANCE.config().loadoutKeybind(loadoutIndex)
            + "\""
            + " slot="
            + slotId
            + " container="
            + menu.containerId
            + " closeOnlyOnChange="
            + closeOnlyOnChange
            + " item="
            + itemDebug(stack)
            + " suppressOpenPackets="
            + suppressLoadoutsOpenPackets);
        client.gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.PICKUP, client.player);
        if (closeOnlyOnChange) {
            KungDebugRecorder.event("loadouts-auto-close", "hotkey waiting for equip confirmation");
            return true;
        }
        screen.onClose();
        KungDebugRecorder.event("loadouts-auto-close", "hotkey closed immediate");
        return true;
    }

    public static String loadoutStackSizeLabel(AbstractContainerScreen<?> screen, Slot slot) {
        if (!INSTANCE.isEnabled()
            || screen == null
            || slot == null
            || !isLoadoutsScreen(screen)) {
            return null;
        }

        int loadoutIndex = slotIdToLoadoutIndex(slot.index);
        if (loadoutIndex < 0 || !isLoadoutSelectionStack(slot.index, slot.getItem())) {
            return null;
        }

        String keybind = INSTANCE.config().loadoutKeybind(loadoutIndex);
        if (keybind.isBlank()) {
            return null;
        }
        String label = keybindDisplay(keybind);
        if (label.isBlank()) {
            return null;
        }
        return label;
    }

    public static boolean shouldSuppressLoadoutsOpen(int containerId, Component title) {
        if (suppressLoadoutsOpenPackets <= 0 || !isLoadoutsTitle(title)) {
            return false;
        }

        suppressLoadoutsOpenPackets--;
        pendingLastServerContainerId = containerId;
        KungDebugRecorder.event("loadouts-auto-close", "suppressed loadouts open packet container="
            + containerId
            + " clickContainer="
            + pendingClickContainerId
            + " remaining="
            + suppressLoadoutsOpenPackets
            + " title=\""
            + title.getString()
            + "\"");
        if (suppressLoadoutsOpenPackets <= 0 && !pendingEquipConfirmed) {
            clearPendingClose("suppress-budget-used");
        }
        return true;
    }

    public static void observeSlotUpdate(int containerId, int slotId, ItemStack stack) {
        if (!INSTANCE.isEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            return;
        }

        AbstractContainerMenu menu = client.player.containerMenu;
        if (slotUpdateLogContainerId != containerId) {
            slotUpdateLogContainerId = containerId;
            slotUpdateLogCount = 0;
        }
        boolean topSlot = isTopSlot(menu, slotId);
        boolean stackPresent = stack != null && !stack.isEmpty();
        boolean context = stackPresent && isLoadoutsContext(screen, menu, stack);
        if (pendingClose && topSlot && context) {
            observeServerRefresh("slot-update", containerId);
        }
        if (slotUpdateLogCount >= SLOT_UPDATE_LOG_LIMIT || !isTopSlot(menu, slotId) || stack == null || stack.isEmpty()) {
            return;
        }

        slotUpdateLogCount++;
        KungDebugRecorder.event("loadouts-auto-close", "slot-update title=\""
            + screen.getTitle().getString()
            + "\" packetContainer="
            + containerId
            + " playerContainer="
            + menu.containerId
            + " slot="
            + slotId
            + " item="
            + itemDebug(stack)
            + " context="
            + context);
    }

    private static void observeClick(
        String source,
        Screen screen,
        AbstractContainerMenu menu,
        int slotId,
        int button,
        ContainerInput input,
        ItemStack stack,
        boolean context
    ) {
        String reason = context ? rejectionReason(menu, slotId, button, input, stack) : "not-loadouts-context";
        KungDebugRecorder.event(
            "loadouts-auto-close",
            source
                + " title=\"" + screen.getTitle().getString() + "\""
                + " container=" + (menu == null ? -1 : menu.containerId)
                + " slot=" + slotId
                + " button=" + button
                + " input=" + input
                + " item=" + itemDebug(stack)
                + " context=" + context
                + " topSlots=" + topSlotCount(menu)
                + " reason=" + reason
        );
        if (reason != null) {
            return;
        }

        pendingClose = true;
        pendingEquipConfirmed = false;
        pendingClickContainerId = menu == null ? -1 : menu.containerId;
        pendingLastServerContainerId = -1;
        KungDebugRecorder.event("loadouts-auto-close", "scheduled slot="
            + slotId
            + " source="
            + source
            + " reactive=true"
            + " clickContainer="
            + pendingClickContainerId);
    }

    private static void observeMessage(Component message) {
        if (!pendingClose || message == null) {
            return;
        }

        String text = cleanMessage(message.getString());
        LoadoutEquipResult equipResult = loadoutEquipResult(text);
        if (equipResult == LoadoutEquipResult.NONE) {
            return;
        }

        if (equipResult == LoadoutEquipResult.ALREADY_EQUIPPED && INSTANCE.config().loadoutsCloseOnlyOnChange()) {
            KungDebugRecorder.event("loadouts-auto-close", "already-equipped keep-open text=\""
                + text
                + "\" clickContainer="
                + pendingClickContainerId);
            clearPendingClose("already-equipped");
            return;
        }

        pendingEquipConfirmed = true;
        suppressLoadoutsOpenPackets = 0;
        KungDebugRecorder.event("loadouts-auto-close", "equip-confirmed text=\""
            + text
            + "\" clickContainer="
            + pendingClickContainerId
            + " lastServerContainer="
            + pendingLastServerContainerId);
        closeIfReady("equip-confirmed");
    }

    private static void observeServerRefresh(String source, int containerId) {
        boolean newContainer = pendingLastServerContainerId != containerId;
        pendingLastServerContainerId = containerId;
        if (newContainer) {
            KungDebugRecorder.event("loadouts-auto-close", "server-refresh source="
                + source
                + " container="
                + containerId
                + " clickContainer="
                + pendingClickContainerId
                + " replacement="
                + (pendingClickContainerId >= 0 && containerId != pendingClickContainerId)
                + " equipConfirmed="
                + pendingEquipConfirmed);
        }
    }

    private static void closeIfReady(String source) {
        if (!pendingClose) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (!INSTANCE.isEnabled() || client.player == null) {
            KungDebugRecorder.event("loadouts-auto-close", "reactive close cancelled source="
                + source
                + " "
                + currentScreenDebug(client));
            clearPendingClose("cancelled");
            return;
        }
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> containerScreen)
            || !isLoadoutsContext(McCompat.screen(client), containerScreen.getMenu(), ItemStack.EMPTY)) {
            if (pendingEquipConfirmed) {
                KungDebugRecorder.event("loadouts-auto-close", "reactive close complete source="
                    + source
                    + " no-loadouts-screen "
                    + currentScreenDebug(client));
                clearPendingClose("confirmed-no-screen");
                return;
            }
            KungDebugRecorder.event("loadouts-auto-close", "reactive close waiting source="
                + source
                + " "
                + currentScreenDebug(client));
            return;
        }

        int currentContainer = containerScreen.getMenu().containerId;
        boolean replacementContainer = pendingClickContainerId >= 0 && currentContainer != pendingClickContainerId;
        if (!pendingEquipConfirmed) {
            KungDebugRecorder.event("loadouts-auto-close", "reactive close waiting source="
                + source
                + " currentContainer="
                + currentContainer
                + " clickContainer="
                + pendingClickContainerId
                + " equipConfirmed=false");
            return;
        }

        int clickContainer = pendingClickContainerId;
        int lastServerContainer = pendingLastServerContainerId;
        boolean equipConfirmed = pendingEquipConfirmed;
        clearPendingClose("closing");
        containerScreen.onClose();
        KungDebugRecorder.event("loadouts-auto-close", "closed reactive source="
            + source
            + " clickContainer="
            + clickContainer
            + " currentContainer="
            + currentContainer
            + " lastServerContainer="
            + lastServerContainer
            + " equipConfirmed="
            + equipConfirmed
            + " replacement="
            + replacementContainer);
    }

    private static void clearPendingClose(String reason) {
        if (pendingClose) {
            KungDebugRecorder.event("loadouts-auto-close", "pending cleared reason="
                + reason
                + " equipConfirmed="
                + pendingEquipConfirmed
                + " clickContainer="
                + pendingClickContainerId
                + " lastServerContainer="
                + pendingLastServerContainerId);
        }
        pendingClose = false;
        pendingEquipConfirmed = false;
        pendingClickContainerId = -1;
        pendingLastServerContainerId = -1;
        suppressLoadoutsOpenPackets = 0;
    }

    private static LoadoutEquipResult loadoutEquipResult(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("you equipped ")) {
            return LoadoutEquipResult.EQUIPPED;
        }
        if (normalized.endsWith(" is already equipped!")) {
            return LoadoutEquipResult.ALREADY_EQUIPPED;
        }
        return LoadoutEquipResult.NONE;
    }

    private static String cleanMessage(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\u00a7.", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static int matchingKeybindIndex(KeyEvent event) {
        for (int index = 0; index < LOADOUT_HOTKEY_SLOTS.length; index++) {
            if (keybindMatchesKey(INSTANCE.config().loadoutKeybind(index), event)) {
                return index;
            }
        }
        return -1;
    }

    private static int matchingMouseKeybindIndex(int button) {
        for (int index = 0; index < LOADOUT_HOTKEY_SLOTS.length; index++) {
            if (keybindMatchesMouse(INSTANCE.config().loadoutKeybind(index), button)) {
                return index;
            }
        }
        return -1;
    }

    private static int slotIdToLoadoutIndex(int slotId) {
        for (int index = 0; index < LOADOUT_HOTKEY_SLOTS.length; index++) {
            if (LOADOUT_HOTKEY_SLOTS[index] == slotId) {
                return index;
            }
        }
        return -1;
    }

    private static String compactHotkeyLabel(String label) {
        if (label == null) {
            return "";
        }

        String normalized = label.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("caps lock")) {
            return "Caps";
        }
        if (lower.contains("shift")) {
            return "Shift";
        }
        if (lower.contains("control") || lower.contains("ctrl")) {
            return "Ctrl";
        }
        if (lower.contains("alt")) {
            return "Alt";
        }
        if (lower.contains("space")) {
            return "Space";
        }
        if (lower.contains("mouse button")) {
            return normalized.replace("Mouse Button ", "M");
        }
        return normalized;
    }

    public static boolean keybindMatchesKey(String keybind, KeyEvent event) {
        if (event == null || keybind == null || !keybind.startsWith("key:")) {
            return false;
        }

        int key = parseKeybindPart(keybind, 1, -1);
        int scancode = parseKeybindPart(keybind, 2, 0);
        if (key >= 0) {
            return key == event.key();
        }
        return scancode != 0 && scancode == event.scancode();
    }

    public static boolean keybindMatchesMouse(String keybind, int button) {
        return keybind != null
            && keybind.startsWith("mouse:")
            && parseKeybindPart(keybind, 1, -1) == button;
    }

    /** Whether the key or mouse button is down right now; a key bound by scancode alone never reads as down. */
    public static boolean keybindHeld(String keybind, Window window) {
        if (keybind == null) {
            return false;
        }
        int code = parseKeybindPart(keybind, 1, -1);
        if (keybind.startsWith("mouse:")) {
            return code >= 0 && GLFW.glfwGetMouseButton(window.handle(), code) == GLFW.GLFW_PRESS;
        }
        return keybind.startsWith("key:") && code >= 0 && InputConstants.isKeyDown(window, code);
    }

    private static int parseKeybindPart(String keybind, int part, int fallback) {
        String[] parts = keybind.split(":");
        if (part >= parts.length) {
            return fallback;
        }
        try {
            return Integer.parseInt(parts[part]);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * What this keyboard prints on a character key. GLFW numbers keys by their US position, so on a German layout
     * the key labelled Y is code 90. Null for keys without a character (and without GLFW, as in tests).
     */
    private static String layoutKeyName(int key) {
        if (key < 39 || key > 96 && key != 161 && key != 162) {
            return null;
        }
        try {
            String name = GLFW.glfwGetKeyName(key, 0);
            if (name == null || name.isEmpty()) {
                return null;
            }
            return name.length() == 1 ? String.valueOf(Character.toUpperCase(name.charAt(0))) : name;
        } catch (Throwable noGlfw) {
            return null;
        }
    }

    private static String keyName(int key) {
        String layoutName = layoutKeyName(key);
        if (layoutName != null) {
            return layoutName;
        }
        if (key >= 65 && key <= 90) {
            return String.valueOf((char) key);
        }
        if (key >= 48 && key <= 57) {
            return String.valueOf((char) key);
        }
        if (key >= 290 && key <= 314) {
            return "F" + (key - 289);
        }
        if (key >= 320 && key <= 329) {
            return "Num " + (key - 320);
        }

        return switch (key) {
            case 32 -> "Space";
            case 39 -> "'";
            case 44 -> ",";
            case 45 -> "-";
            case 46 -> ".";
            case 47 -> "/";
            case 59 -> ";";
            case 61 -> "=";
            case 91 -> "[";
            case 92 -> "\\";
            case 93 -> "]";
            case 96 -> "`";
            case 256 -> "Esc";
            case 257, 335 -> "Enter";
            case 258 -> "Tab";
            case 259 -> "Backspace";
            case 260 -> "Insert";
            case 261 -> "Delete";
            case 262 -> "Right";
            case 263 -> "Left";
            case 264 -> "Down";
            case 265 -> "Up";
            case 266 -> "Page Up";
            case 267 -> "Page Down";
            case 268 -> "Home";
            case 269 -> "End";
            case 280 -> "Caps";
            case 281 -> "Scroll";
            case 282 -> "Num Lock";
            case 283 -> "Print";
            case 284 -> "Pause";
            case 340, 344 -> "Shift";
            case 341, 345 -> "Ctrl";
            case 342, 346 -> "Alt";
            case 343, 347 -> "Super";
            case 348 -> "Menu";
            case 330 -> ".";
            case 331 -> "/";
            case 332 -> "*";
            case 333 -> "-";
            case 334 -> "+";
            case 336 -> "=";
            default -> "Key " + key;
        };
    }

    private static boolean isLoadoutsScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }

        return isLoadoutsTitle(screen.getTitle());
    }

    private static boolean isLoadoutsTitle(Component title) {
        if (title == null) {
            return false;
        }

        String normalized = title.getString().trim().toLowerCase(Locale.ROOT);
        return normalized.contains("loadouts");
    }

    private static boolean isLoadoutsContext(Screen screen, AbstractContainerMenu menu, ItemStack clickedStack) {
        return isLoadoutsScreen(screen)
            || isNamedLoadoutItem(clickedStack)
            || loadoutItemCount(menu) >= 2;
    }

    private static String rejectionReason(
        AbstractContainerMenu menu,
        int slotId,
        int button,
        ContainerInput input,
        ItemStack stack
    ) {
        if (!INSTANCE.isEnabled()) {
            return "disabled";
        }
        if (button != 0) {
            return "button";
        }
        if (!isSelectionInput(input)) {
            return "input";
        }
        if (!isTopSlot(menu, slotId)) {
            return "not-top-slot";
        }
        if (isLoadoutSelectionSlot(slotId) && (stack == null || stack.isEmpty())) {
            return null;
        }
        if (!isLoadoutSelectionStack(slotId, stack)) {
            return "not-selection-item";
        }

        return null;
    }

    private static boolean isSelectionInput(ContainerInput input) {
        return input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE;
    }

    private static boolean isTopSlot(AbstractContainerMenu menu, int slotId) {
        if (menu == null) {
            return false;
        }

        return slotId >= 0 && slotId < topSlotCount(menu);
    }

    private static boolean isLoadoutSelectionSlot(int slotId) {
        return slotIdToLoadoutIndex(slotId) >= 0;
    }

    static int topSlotCount(AbstractContainerMenu menu) {
        if (menu == null) {
            return 0;
        }

        return Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOT_COUNT);
    }

    static ItemStack slotStack(AbstractContainerMenu menu, int slotId) {
        if (menu == null || slotId < 0 || slotId >= menu.slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = menu.slots.get(slotId);
        return slot == null ? ItemStack.EMPTY : slot.getItem();
    }

    private static boolean isLoadoutSelectionStack(int slotId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (itemPath.contains("stained_glass")
            || itemPath.equals("barrier")
            || itemPath.equals("arrow")
            || itemPath.equals("gray_dye")
            || itemPath.equals("red_dye")) {
            return false;
        }

        return nameAllowsSelection(slotId, stack.getHoverName().getString());
    }

    /** Loadout names are player-chosen ("Kuudra Clear"), so button words are only checked off the loadout grid. */
    static boolean nameAllowsSelection(int slotId, String hoverName) {
        if (isLoadoutSelectionSlot(slotId)) {
            return true;
        }

        String name = hoverName.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return !(name.contains("close")
            || name.contains("go back")
            || name.contains("previous")
            || name.contains("next")
            || name.contains("cancel")
            || name.contains("delete")
            || name.contains("clear")
            || name.contains("settings")
            || name.contains("info")
            || name.contains("help")
            || name.contains("manage"));
    }

    private static int loadoutItemCount(AbstractContainerMenu menu) {
        if (menu == null) {
            return 0;
        }

        int topSlotCount = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOT_COUNT);
        int count = 0;
        for (int slotId = 0; slotId < topSlotCount; slotId++) {
            if (isNamedLoadoutItem(slotStack(menu, slotId))) {
                count++;
            }
        }

        return count;
    }

    private static boolean isNamedLoadoutItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("loadout");
    }

    static String itemDebug(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }

        String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        String name = stack.getHoverName().getString().replaceAll("\\s+", " ").trim();
        return itemPath + " \"" + name + "\" x" + stack.getCount();
    }

    private static String topSlotSummary(AbstractContainerMenu menu, int limit) {
        if (menu == null) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder("[");
        int appended = 0;
        int topSlotCount = topSlotCount(menu);
        for (int slotId = 0; slotId < topSlotCount && appended < limit; slotId++) {
            ItemStack stack = slotStack(menu, slotId);
            if (stack.isEmpty()) {
                continue;
            }
            if (appended > 0) {
                builder.append(" | ");
            }
            builder.append(slotId).append('=').append(itemDebug(stack));
            appended++;
        }
        if (topSlotCount > 0 && appended == limit) {
            builder.append(" | ...");
        }
        return builder.append(']').toString();
    }

    private static String currentScreenDebug(Minecraft client) {
        Screen screen = client == null ? null : McCompat.screen(client);
        if (screen == null) {
            return "screen=null";
        }

        return "screen=" + screen.getClass().getSimpleName() + " title=\"" + screen.getTitle().getString() + "\"";
    }

    private enum LoadoutEquipResult {
        NONE,
        EQUIPPED,
        ALREADY_EQUIPPED
    }
}
