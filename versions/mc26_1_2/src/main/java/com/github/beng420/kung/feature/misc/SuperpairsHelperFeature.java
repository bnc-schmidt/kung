package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.garden.GreenhouseDumpFormat;
import com.github.beng420.kung.feature.misc.SuperpairsBoard.CardCount;
import com.github.beng420.kung.feature.misc.SuperpairsBoard.PairStats;
import com.github.beng420.kung.feature.misc.SuperpairsPlanner.Pick;
import com.github.beng420.kung.ui.HudPose;
import com.github.beng420.kung.ui.UiShapes;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

public final class SuperpairsHelperFeature extends ConfigurableFeature<MiscConfig> {
    public static final SuperpairsHelperFeature INSTANCE = new SuperpairsHelperFeature();
    private static final int MAX_CARD_LINES = 14;
    private static final int SAMPLE_WIDTH = 170;
    private static final int PADDING_X = 5;
    private static final int PADDING_Y = 4;
    private static final int ROW_HEIGHT = 10;
    private static final int PANEL = 0xAA111318;
    private static final int BORDER = 0xBB3498DB;
    private static final int TEXT = 0xFFFFFFFF;
    /** The gray of a chest slot, to cover the button under a card drawn back. */
    private static final int SLOT_BACKGROUND = 0xFF8B8B8B;
    private static final int PRIORITY_FRAME = 0xFFFFD700;
    private static final int BEST_FRAME = 0xFF55FF55;
    private static final int PAIR_FRAME = 0xFFFFFFFF;
    private static final Pattern REMAINING_CLICKS = Pattern.compile("Remaining Clicks: (\\d+)");
    private static final Pattern FORMATTING = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Observations and rendering run on the client thread after vanilla applies packets.
    private static final SuperpairsBoard board = new SuperpairsBoard();
    private static AbstractContainerMenu activeMenu;
    private static int topSlotCount;
    private static int slotUpdatePackets;
    private static int observedSlotUpdates;
    private static long lastLoggedRevision = -1;
    private static long lastBoardPacketMillis;
    /** The stack of every card seen face up, by slot. */
    private static final Map<Integer, ItemStack> faces = new HashMap<>();
    private static final Set<Integer> dead = new HashSet<>();
    /** Slots whose pair the game already took, as far as can be told. */
    private static Set<Integer> claimed = Set.of();
    /** Slots of the pairs that can be clicked: both cards known, not taken. */
    private static Set<Integer> pairSlots = Set.of();
    private static SuperpairsPlanner.Prompt prompt = SuperpairsPlanner.Prompt.UNKNOWN;
    private static Pick pick = Pick.NONE;
    /** The card revealed last; between the two clicks of a turn that is the first card. */
    private static int lastRevealSlot = -1;
    private static int remainingClicks = -1;

    private SuperpairsHelperFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!INSTANCE.isEnabled() || !ensureSession(client)) {
                resetState("closed-or-disabled");
                return;
            }
            recordProgress();
            updatePlan();
            if (!KungBuild.MODRINTH) {
                SuperpairsAutoReveal.tick(client, board, activeMenu, lastBoardPacketMillis, INSTANCE.config(), pick);
            }
        });

        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!isSuperpairsGameScreen(screen)) {
                return;
            }
            KungDebugRecorder.event("superpairs", "screen title=\"" + screen.getTitle().getString()
                + "\" enabled=" + INSTANCE.isEnabled());
            ScreenEvents.afterExtract(screen).register((currentScreen, graphics, mouseX, mouseY, tickDelta) ->
                renderAfterScreen(currentScreen, graphics)
            );
        });
    }

    @Override
    public boolean isEnabled() {
        return config().superpairsHelperEnabled();
    }

    private static void renderAfterScreen(Screen screen, GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (KungHudEditorState.externalEditing() || !INSTANCE.isEnabled() || screen != McCompat.screen(client) || !ensureSession(client)) {
            return;
        }
        drawPanel(graphics, client, INSTANCE.config());
    }

    /** Called only from the applied ClientPacketListener slot hook, never Netty receipt. */
    public static void observeSlotUpdate(int containerId, int slot, ItemStack stack) {
        if (!acceptsPacket(containerId)) {
            return;
        }
        slotUpdatePackets++;
        if (SuperpairsBoard.isBoardSlot(topSlotCount, slot)) {
            lastBoardPacketMillis = System.currentTimeMillis();
        }
        observeBoardStack(slot, stack);
    }

    public static void observeContentUpdate(int containerId, List<ItemStack> stacks) {
        if (!acceptsPacket(containerId)) {
            return;
        }
        slotUpdatePackets++;
        lastBoardPacketMillis = System.currentTimeMillis();
        for (int slot = 0; slot < Math.min(topSlotCount, stacks.size()); slot++) {
            observeBoardStack(slot, stacks.get(slot));
        }
    }

    private static boolean acceptsPacket(int containerId) {
        Minecraft client = Minecraft.getInstance();
        return INSTANCE.isEnabled()
            && client.player != null
            && client.player.containerMenu.containerId == containerId
            && ensureSession(client);
    }

    private static boolean ensureSession(Minecraft client) {
        if (client.player == null || !isSuperpairsGameScreen(McCompat.screen(client))) {
            return false;
        }
        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) McCompat.screen(client)).getMenu();
        if (menu != client.player.containerMenu || MenuItems.topSlots(menu) != 54) {
            return false;
        }
        // A resize retains memory. Another menu never does, even with an identical
        // title or recycled numeric container id.
        if (activeMenu != menu) {
            resetState("new-menu");
            activeMenu = menu;
            topSlotCount = MenuItems.topSlots(menu);
            KungDebugRecorder.event("superpairs", "start container=" + menu.containerId
                + " title=\"" + McCompat.screen(client).getTitle().getString() + "\" top=" + topSlotCount);
            // One seed covers enabling during an open game. All subsequent reveals
            // come from packets, including brief flips and automatically revealed partners.
            for (int slot = 0; slot < topSlotCount; slot++) {
                observeBoardStack(slot, menu.slots.get(slot).getItem());
            }
        }
        return true;
    }

    private static boolean isSuperpairsGameScreen(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> && isGameTitle(screen.getTitle().getString());
    }

    static boolean isGameTitle(String title) {
        String normalized = cleanText(title).toLowerCase(Locale.ROOT);
        return normalized.equals("superpairs")
            || (normalized.startsWith("superpairs (") && normalized.endsWith(")"));
    }

    private static void observeBoardStack(int slot, ItemStack stack) {
        if (!SuperpairsBoard.isBoardSlot(topSlotCount, slot) || stack == null || stack.isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String label = cleanName(stack);
        long previousRevision = board.revision();
        SuperpairsBoard.Kind kind = board.observe(topSlotCount, slot, itemId, label, stack.getCount());
        if (kind == SuperpairsBoard.Kind.IGNORED) {
            return;
        }
        observedSlotUpdates++;
        if (kind != SuperpairsBoard.Kind.MARKER) {
            // The real stack, to draw it back when the game turns the card around again.
            faces.put(slot, stack.copy());
            lastRevealSlot = slot;
        }
        if (board.revision() != previousRevision && kind != SuperpairsBoard.Kind.MARKER) {
            KungDebugRecorder.event("superpairs", "reveal container=" + activeMenu.containerId
                + " slot=" + slot + " kind=" + kind + " item=" + itemId
                + " name=\"" + label + "\" count=" + stack.getCount() + " texture=" + textureHash(stack));
        }
    }

    /** The skin hash of a player head, so a trace can tell look-alike heads apart; "-" when there is none. */
    private static String textureHash(ItemStack stack) {
        try {
            ResolvableProfile profile = stack.get(DataComponents.PROFILE);
            if (profile != null) {
                for (var property : profile.partialProfile().properties().get("textures")) {
                    var texture = GreenhouseDumpFormat.texture(property.value());
                    if (texture.isPresent()) {
                        return texture.get().hash();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // a trace line is never worth a crash
        }
        return "-";
    }

    /**
     * Works out what to click next: which prompt the hidden buttons show, which pairs the game already took, and
     * the planner's pick. A card still face up while the buttons say they take a click is a taken pair (the
     * first card of the current turn excepted); while they read "?" the game is still showing a pair, so
     * nothing is concluded then.
     */
    private static void updatePlan() {
        MiscConfig config = INSTANCE.config();
        prompt = SuperpairsPlanner.Prompt.UNKNOWN;
        for (int slot = 0; slot < topSlotCount; slot++) {
            if (!SuperpairsBoard.isBoardSlot(topSlotCount, slot)) {
                continue;
            }
            String label = hiddenLabel(activeMenu.slots.get(slot).getItem());
            if (label != null) {
                prompt = SuperpairsPlanner.Prompt.of(label);
                break;
            }
        }
        remainingClicks = remainingClicks(activeMenu.slots.get(4).getItem());
        if (prompt != SuperpairsPlanner.Prompt.UNKNOWN && System.currentTimeMillis() - lastBoardPacketMillis >= 200L) {
            Set<Integer> taken = new HashSet<>(dead);
            for (int slot : faces.keySet()) {
                boolean firstOfTurn = prompt == SuperpairsPlanner.Prompt.SECOND && slot == lastRevealSlot;
                if (!firstOfTurn && faceUp(activeMenu.slots.get(slot).getItem())) {
                    taken.add(slot);
                }
            }
            if (!taken.equals(claimed)) {
                claimed = taken;
                KungDebugRecorder.event("superpairs", "taken pairs " + new TreeSet<>(claimed));
            }
        }
        pairSlots = SuperpairsPlanner.claimableSlots(board, claimed);
        boolean revealDone = board.boardSlotCount() > 0 && board.summary().maximumUnseenPairs() == 0;
        Pick next = SuperpairsPlanner.next(board, claimed, prompt, lastRevealSlot, revealDone, remainingClicks);
        if (!next.equals(pick)) {
            KungDebugRecorder.event("superpairs", "plan prompt=" + prompt + " pick=" + next.slots()
                + (next.reason().isEmpty() ? "" : " (" + next.reason() + ")") + " remaining=" + remainingClicks);
        }
        pick = next;
    }

    /** The hidden button's name when the stack is one, else null. */
    private static String hiddenLabel(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String label = stack.getHoverName().getString();
        return SuperpairsBoard.classify(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), label)
            == SuperpairsBoard.Kind.MARKER ? label : null;
    }

    /** The clicks the game still allows ("Remaining Clicks: 5" in slot 4), or -1. */
    private static int remainingClicks(ItemStack stack) {
        Matcher matcher = REMAINING_CLICKS.matcher(stack.getHoverName().getString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** For the auto mode: a slot that did not react to a click is a taken pair. */
    public static void markDead(int slot) {
        dead.add(slot);
        claimed = new HashSet<>(claimed);
        claimed.add(slot);
    }

    /**
     * Draws the helper's marks over a slot, from the slot render hook. Cards seen stay visible after the game
     * turns them around: the stack seen at the reveal is drawn over the slot (never the slot's later contents,
     * so a button, an empty slot or a click's local prediction cannot hide a card). Each kind of card gets a
     * background color and a letter, Titanic bottles and T7 enchantments a gold frame, and the best next click
     * a green one.
     */
    public static void drawSlot(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        if (activeMenu == null || screen.getMenu() != activeMenu || !INSTANCE.initialized()) {
            return;
        }
        MiscConfig config = INSTANCE.config();
        int x = slot.x;
        int y = slot.y;
        ItemStack face = faces.get(slot.index);
        if (face != null) {
            boolean faceUp = faceUp(slot.getItem());
            if (faceUp || config.superpairsKeepCardsVisible()) {
                int kind = board.legendIndexAt(slot.index);
                boolean colored = config.superpairsPairColors() && kind >= 0;
                if (!faceUp || colored) {
                    graphics.fill(x, y, x + 16, y + 16, colored ? PairStyle.color(kind) : SLOT_BACKGROUND);
                    graphics.item(face, x, y);
                    graphics.itemDecorations(Minecraft.getInstance().font, face, x, y, null);
                }
                if (config.superpairsPairLetters() && kind >= 0) {
                    graphics.fill(x, y, x + 7, y + 9, 0xCC000000);
                    graphics.text(Minecraft.getInstance().font, PairStyle.letter(kind), x + 1, y + 1, 0xFFFFFFFF, false);
                }
                if (config.superpairsMarkPairs() && pairSlots.contains(slot.index)) {
                    graphics.outline(x, y, 16, 16, PAIR_FRAME);
                }
                String label = board.labelAt(slot.index);
                if (label != null && SuperpairsValue.isPriority(label)) {
                    UiShapes.slotFrame(graphics, x, y, PRIORITY_FRAME);
                }
            }
        }
        if (config.superpairsMarkBestClick() && pick.slots().contains(slot.index)) {
            UiShapes.slotFrame(graphics, x, y, BEST_FRAME);
        }
    }

    /** True when the slot shows a card (reward or bonus) rather than a button, a pane or nothing. */
    private static boolean faceUp(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        SuperpairsBoard.Kind kind = SuperpairsBoard.classify(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            cleanName(stack));
        return kind == SuperpairsBoard.Kind.REWARD || kind == SuperpairsBoard.Kind.BONUS;
    }

    private static String cleanName(ItemStack stack) {
        return SuperpairsBoard.rewardLabel(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
            stack.getHoverName().getString(), MenuItems.lore(stack));
    }

    private static String cleanText(String value) {
        return value == null ? "" : WHITESPACE.matcher(FORMATTING.matcher(value).replaceAll(""))
            .replaceAll(" ").trim();
    }

    private static void recordProgress() {
        if (lastLoggedRevision == board.revision()) {
            return;
        }
        lastLoggedRevision = board.revision();
        PairStats stats = board.summary();
        KungDebugRecorder.event("superpairs", "state container=" + activeMenu.containerId
            + " board=" + board.boardSlotCount() + " bonus=" + board.bonusSlotCount()
            + " pairs=" + stats.knownPairs() + " singles=" + stats.singleCards()
            + " unseen=" + stats.unknownFields() + " maxPairs=" + stats.maximumTotalPairs()
            + " maxUnseenPairs=" + stats.maximumUnseenPairs()
            + (INSTANCE.config().superpairsHelperDebugEnabled()
                ? " packets=" + slotUpdatePackets + " observedSlots=" + observedSlotUpdates : ""));
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.superpairsHelperScale() / 100.0F;
        int sampleLines = 1 + MAX_CARD_LINES;
        int sampleHeight = PADDING_Y * 2 + sampleLines * ROW_HEIGHT;
        return new KungHudLayout.Bounds(
            Math.round(config.superpairsHelperX() - scale),
            Math.round(config.superpairsHelperY() - scale),
            Math.round((SAMPLE_WIDTH + PADDING_X * 2 + 2) * scale),
            Math.round((sampleHeight + 2) * scale)
        );
    }

    static List<String> panelLines(SuperpairsBoard board) {
        List<String> lines = new ArrayList<>();
        PairStats pairStats = board.summary();
        if (board.boardSlotCount() > 0) {
            int maximum = pairStats.maximumUnseenPairs();
            lines.add("Unseen pairs: " + (maximum == 0 ? "0" : "up to " + maximum));
        } else {
            lines.add("Unseen pairs: ?");
        }

        for (CardCount card : board.cards().stream().filter(card -> !card.enchantingXp()).limit(MAX_CARD_LINES).toList()) {
            lines.add(card.label() + "  " + card.count() + "/" + (card.count() + card.singles()));
        }
        return lines;
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, Minecraft client, MiscConfig config) {
        List<String> lines = panelLines(board);

        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, client.font.width(line));
        }

        int screenWidth = client.getWindow().getGuiScaledWidth();
        float scale = config.superpairsHelperScale() / 100.0F;
        textWidth = Math.min(textWidth, maxTextWidth(screenWidth, scale));
        int panelHeight = PADDING_Y * 2 + lines.size() * ROW_HEIGHT;

        HudPose.begin(graphics, panelX(config.superpairsHelperX(), screenWidth, textWidth, scale),
            config.superpairsHelperY(), scale);
        try {
            graphics.fill(-PADDING_X - 1, -PADDING_Y - 1, textWidth + PADDING_X + 1, panelHeight + 1, BORDER);
            graphics.fill(-PADDING_X, -PADDING_Y, textWidth + PADDING_X, panelHeight, PANEL);
            for (int index = 0; index < lines.size(); index++) {
                graphics.text(client.font, trimToWidth(client, lines.get(index), textWidth), 0, index * ROW_HEIGHT,
                    index == 0 ? 0xFFFFD76A : TEXT, true);
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    /** Lines are only cut when they are wider than the whole screen at this scale. */
    static int maxTextWidth(int screenWidth, float scale) {
        return Math.max(40, Math.round(screenWidth / scale) - 2 * (PADDING_X + 1));
    }

    /** Moved left instead of cut short when the panel would run past the right edge. */
    static int panelX(int configX, int screenWidth, int textWidth, float scale) {
        int rightmost = screenWidth - Math.round((textWidth + PADDING_X + 1) * scale);
        return Math.max(Math.round((PADDING_X + 1) * scale), Math.min(configX, rightmost));
    }

    private static String trimToWidth(Minecraft client, String value, int maxWidth) {
        if (client.font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        for (int end = value.length(); end > 0; end--) {
            String candidate = value.substring(0, end) + suffix;
            if (client.font.width(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return suffix;
    }

    private static void resetState(String reason) {
        if (activeMenu == null) {
            return;
        }
        recordProgress();
        KungDebugRecorder.event("superpairs", "reset container=" + activeMenu.containerId + " reason=" + reason);
        activeMenu = null;
        board.reset();
        topSlotCount = 0;
        slotUpdatePackets = 0;
        observedSlotUpdates = 0;
        lastLoggedRevision = -1;
        lastBoardPacketMillis = 0;
        faces.clear();
        dead.clear();
        claimed = Set.of();
        pairSlots = Set.of();
        prompt = SuperpairsPlanner.Prompt.UNKNOWN;
        pick = Pick.NONE;
        lastRevealSlot = -1;
        remainingClicks = -1;
        if (!KungBuild.MODRINTH) SuperpairsAutoReveal.reset();
    }
}
