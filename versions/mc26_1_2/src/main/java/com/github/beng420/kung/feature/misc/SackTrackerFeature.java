package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.SackItem;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.skyblock.SkyblockItemId;
import com.github.beng420.kung.ui.HudPose;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.ui.UiTooltip;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient.SackCounts;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Tracks chosen sack items: the track key over an item in a sack adds it, SkyBlock's own [Sacks]
 * messages move the counts, and an open sack sets them to what it shows. Every Minecraft account and SkyBlock
 * profile has its own items; none is tracked while the profile is unknown (see
 * {@link com.github.beng420.kung.config.category.SackProfiles}).
 */
public final class SackTrackerFeature extends ConfigurableFeature<MiscConfig> {
    public static final SackTrackerFeature INSTANCE = new SackTrackerFeature();
    private static final Pattern STORED = Pattern.compile("^Stored: ([\\d.,]+[kKmMbB]?)/");
    private static final Pattern LAST = Pattern.compile("\\(Last (?:(\\d+)m\\s*)?(\\d+)s\\.\\)");
    private static final Pattern CHANGE = Pattern.compile("^([+-][\\d,]+) (.+) \\((.+)\\)$");
    private static final int SYNC_TICKS = 10;
    /**
     * A row changed by a [Sacks] message within this long counts as grinded. Hypixel reports pick-ups about every 30 s,
     * so a row that stayed quiet for minutes has nothing unreported to lose in a world change.
     */
    private static final long ACTIVE_MILLIS = 5 * 60_000L;
    /**
     * After a world change marked rows "~" the API is asked after 4, 12, 30, 90 and 220 s (the ticks between): Hypixel
     * saves a profile at a world change, and about every 3 minutes besides. The 220 s ask is the last one.
     */
    private static final int[] RESYNC_DELAYS = {80, 160, 360, 1200, 2600};
    /** The first ask also checks the rows without a "~"; only marked rows are asked again. */
    private static final int QUIET_ASKS = 1;
    /** The Hypixel key is shared by every Kung player: one player may ask this often per 5 minutes. */
    private static final int RESYNC_MAX_PER_5_MIN = 10;
    private static final int LINE_HEIGHT = 11;
    private static final long MAX_GOAL = 999_999_999L;
    private static final int KEY_BACKSPACE = 259;
    private static final int KEY_V = 86;
    private static final int MOD_CONTROL = 2;
    private static final UiTheme THEME = UiTheme.settings();
    private static final Component RESET = Component.literal("[Reset Display]").withColor(0xFF5555);
    private static final String GOAL_HINT = "Type a number to set a target amount (Backspace deletes, Ctrl+Backspace clears)";
    private static final String PASTE_HINT = "Ctrl+V pastes a number from the clipboard";
    /** Cursor moves below this many GUI pixels count as resting: the render and key paths read the position differently. */
    private static final double REST_PIXELS = 1.0;
    /** The amount of an item tracked outside its sack: unknown until the sack is opened once. */
    private static final long UNKNOWN_AMOUNT = -1;
    private final HoveredTooltip<ItemStack> tooltip = new HoveredTooltip<>(ItemStack::isSameItemSameComponents);
    /** When an open sack last set each row: the [Sacks] message that covers that moment holds items the sack showed already. */
    private final Map<SackItem, Long> syncedAt = new java.util.IdentityHashMap<>();
    /**
     * Rows that may be too low: after a world change Hypixel drops the pick-ups it had not reported yet, so the
     * count stays short until a sack is read again (seen in a trace: 74 Flawed gems found just before a warp).
     * Only rows that were grinded just before the change get the mark, see {@link #ACTIVE_MILLIS}.
     */
    private final Set<SackItem> approximate = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** When a [Sacks] message last changed each row. */
    private final Map<SackItem, Long> changedAt = new java.util.IdentityHashMap<>();
    private long seenEpoch = Long.MIN_VALUE;
    /** The rows grinded when the connection dropped: Hypixel loses their unreported pick-ups too, so they get the "~" once the profile is back. */
    private List<String> activeBeforeLeaving = List.of();
    private int ticksUntilSync;
    /** Each marked row's count at the world change: the API holds what the chat lost on top of it. */
    private final Map<SackItem, Long> amountAtChange = new java.util.IdentityHashMap<>();
    private final java.util.ArrayDeque<Long> resyncRequests = new java.util.ArrayDeque<>();
    /** The next API ask (an index into RESYNC_DELAYS), -1 when none is due. */
    private int resyncStep = -1;
    private int ticksToResync;
    private boolean resyncInFlight;
    /** Counts the resyncs, so the answer to an earlier one is ignored. */
    private int resyncGeneration;
    /** The row the cursor rested on last, and where: it stays hovered while the cursor rests, however short its text got. */
    private SackItem held;
    private double heldX;
    private double heldY;

    private SackTrackerFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) observeMessage(message);
        });
        // ALLOW_GAME sees the profile lines even when another mod hides them from the chat.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) observeProfile(message.getString());
            return true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (!initialized()) return;
            activeBeforeLeaving = activeItems(System.currentTimeMillis()).stream().map(SackItem::name).toList();
            config().leaveSackTrackerProfile();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::syncOpenSack);
        SackApiProbe.register();
        // Every mod that shows an item (inventory, REI, JEI, ...) builds its tooltip through this event.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (isEnabled()) tooltip.observe(stack);
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "sack_tracker"), (graphics, delta) -> {
                if (McCompat.screen(Minecraft.getInstance()) == null) render(graphics);
            });
        // Menus draw over the HUD, so the tracker draws again on top of them - there it also takes clicks.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.beforeExtract(screen).register((current, graphics, mouseX, mouseY, delta) -> tooltip.beginFrame());
            ScreenEvents.afterExtract(screen).register(this::renderOverScreen);
            ScreenMouseEvents.allowMouseClick(screen).register((current, event) ->
                !click(current, event) && !trackHovered(current, McInput.button(event)));
            // Runs before the screen's own keyPressed, so a digit over a row never swaps the slot below with the hotbar.
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) ->
                !typeGoal(current, event) && !trackHovered(current, event));
        });
    }

    @Override
    public boolean isEnabled() { return initialized() && config().sackTrackerEnabled(); }

    /** Enabled and the profile is known: only then are there items to show, count or edit. */
    private boolean tracking() { return isEnabled() && config().sackTrackerProfileKnown(); }

    /** The "Profile ID:" line names the profile; the lines around it (name, switch) move it in and out of "unknown". */
    private void observeProfile(String text) {
        if (!initialized() || !text.contains("rofile")) return;
        var player = Minecraft.getInstance().player;
        if (!config().observeSackTrackerProfile(text, player == null ? null : player.getUUID().toString())) return;
        // The previous profile's sack reads say nothing about these rows.
        syncedAt.clear();
        changedAt.clear();
        approximate.clear();
        amountAtChange.clear();
        resyncStep = -1;
        // ponytail: matched by name, not profile - after a reconnect into another profile a same-named row gets a needless "~".
        if (config().sackTrackerProfileKnown()) {
            config().sackTrackerAllItems().stream().filter(item -> activeBeforeLeaving.contains(item.name())).forEach(approximate::add);
            startResync();
        }
        activeBeforeLeaving = List.of();
        KungDebugRecorder.event("sack-tracker", "profile " + (config().sackTrackerProfileKnown()
            ? config().sackTrackerProfileName() + " items=" + config().sackTrackerItems().size() : "unknown"));
    }

    /** The track key over an item in a sack: tracks it, or stops tracking it when it already is. */
    public static boolean handleKey(AbstractContainerScreen<?> screen, Slot hovered, KeyEvent event) {
        return INSTANCE.isEnabled() && LoadoutsAutoCloseFeature.keybindMatchesKey(INSTANCE.config().sackTrackerKeybind(), event)
            && INSTANCE.toggle(screen, hovered);
    }

    public static boolean handleMouse(AbstractContainerScreen<?> screen, Slot hovered, MouseButtonEvent event) {
        return INSTANCE.isEnabled() && event != null
            && LoadoutsAutoCloseFeature.keybindMatchesMouse(INSTANCE.config().sackTrackerKeybind(), McInput.button(event))
            && INSTANCE.toggle(screen, hovered);
    }

    private boolean toggle(AbstractContainerScreen<?> screen, Slot hovered) {
        if (!tracking() || hovered == null || !isSack(plain(screen.getTitle()))) return false;
        ItemStack stack = hovered.getItem();
        if (SackGemstones.isGemstoneSack(plain(screen.getTitle()))) return toggleGemstone(screen, stack);
        Long stored = stored(lore(stack));
        if (stored == null) return false;
        toggleItem(plain(stack.getHoverName()), stack, stored, "", "");
        return true;
    }

    /** Tracks the row, or stops tracking it when it is tracked, and tells the player which. */
    private void toggleItem(String name, ItemStack stack, long amount, String startedTail, String stoppedTail) {
        boolean tracked = config().toggleSackTrackerItem(name, color(stack.getHoverName()), amount);
        KungMessages.send(Minecraft.getInstance(), KungMessages.Type.SUCCESS, "Sack Tracker",
            tracked ? "Tracking " + name + startedTail : "Stopped tracking " + name + stoppedTail);
    }

    /**
     * A gem item holds every quality. Without a filter the whole item is tracked, by its total in Rough gems (the
     * "Stored:" line); with one, the quality it shows is tracked as a row of its own.
     */
    private boolean toggleGemstone(AbstractContainerScreen<?> screen, ItemStack stack) {
        var gem = SackGemstones.parseName(plain(stack.getHoverName()));
        if (gem == null) return false;
        String filter = SackGemstones.filterQuality(sackLore(screen));
        if (gem.quality() == null && filter == null) {
            Long total = wholeGemTotal(stack);
            if (total == null) return false;
            String all = " (all qualities, in Rough gems)";
            toggleItem(plain(stack.getHoverName()), stack, total, all, all);
            return true;
        }
        String quality = SackGemstones.trackedQuality(gem, filter);
        Long amount = SackGemstones.amounts(lore(stack), filter).get(quality);
        if (amount == null) {
            KungDebugRecorder.event("sack-tracker", "gemstone unreadable name=" + plain(stack.getHoverName())
                + " filter=" + filter + " lore=" + KungDebugRecorder.compact(String.join(" | ", lore(stack))));
            return false;
        }
        toggleItem(gem.trackedName(quality), stack, amount, "", "");
        return true;
    }

    /** The item's total in Rough gems: its "Stored:" line, else the sum of the listed qualities. */
    private static Long wholeGemTotal(ItemStack stack) {
        Long stored = stored(lore(stack));
        if (stored != null) return stored;
        var amounts = SackGemstones.amounts(lore(stack), null);
        return amounts.isEmpty() ? null : SackGemstones.roughTotal(amounts);
    }

    /** All lore lines of an open sack's items, where the gemstone filter button is among them. */
    private static List<String> sackLore(AbstractContainerScreen<?> screen) {
        List<String> lines = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) lines.addAll(lore(slot.getItem()));
        return lines;
    }

    private boolean trackHovered(Screen screen, KeyEvent event) {
        return isEnabled() && LoadoutsAutoCloseFeature.keybindMatchesKey(config().sackTrackerKeybind(), event)
            && trackOutsideSack(screen);
    }

    private boolean trackHovered(Screen screen, int mouseButton) {
        return isEnabled() && LoadoutsAutoCloseFeature.keybindMatchesMouse(config().sackTrackerKeybind(), mouseButton)
            && trackOutsideSack(screen);
    }

    /**
     * The track key over a sack item anywhere but a sack menu (those read the amount from the lore): in the
     * inventory, in item lists of other mods, in any menu. Only sack items qualify, by SkyBlock id, so a crafting
     * table does nothing. The amount is unknown until the sack is opened once.
     */
    private boolean trackOutsideSack(Screen screen) {
        if (!tracking() || screen.getFocused() instanceof EditBox
            || screen instanceof AbstractContainerScreen<?> container && isSack(plain(container.getTitle()))) return false;
        ItemStack stack = tooltip.hovered();
        if (stack == null || stack.isEmpty()) return false;
        String name = plain(stack.getHoverName());
        if (config().sackTrackerItem(name) == null && !SackItems.contains(SkyblockItemId.of(stack))) return false;
        toggleItem(name, stack, UNKNOWN_AMOUNT, ", open its sack once to read the amount", "");
        return true;
    }

    /** Rows a [Sacks] message changed within {@link #ACTIVE_MILLIS}: the ones that can have unreported pick-ups. */
    private List<SackItem> activeItems(long nowMillis) {
        return config().sackTrackerAllItems().stream().filter(item -> active(changedAt.get(item), nowMillis)).toList();
    }

    static boolean active(Long changedMillis, long nowMillis) {
        return changedMillis != null && nowMillis - changedMillis <= ACTIVE_MILLIS;
    }

    /** True while the row may be short of what the sack holds (after a world change, until a sack was read). */
    public boolean approximate(SackItem item) { return approximate.contains(item); }

    /**
     * Has the tracker read these sack items without showing them, for features that only read the amounts (the
     * Greenhouse planner). Each amount stays unknown until its sack is opened once. Does nothing while the tracker is
     * off or the profile is unknown.
     */
    public static void watch(Collection<String> names) {
        if (INSTANCE.tracking()) INSTANCE.config().watchSackTrackerItems(names);
    }

    /**
     * The amount the tracker holds for a sack item, tracked or watched. Empty while the tracker is off or the profile
     * unknown, for an item it neither tracks nor watches and for one whose sack was never opened.
     */
    public static OptionalLong trackedAmount(String name) {
        if (!INSTANCE.tracking()) return OptionalLong.empty();
        SackItem item = INSTANCE.config().sackTrackerItem(name);
        return item == null || item.amount() < 0 ? OptionalLong.empty() : OptionalLong.of(item.amount());
    }

    /** True while {@link #trackedAmount} may be too low (the tracker marks that row "~"). */
    public static boolean trackedApproximate(String name) {
        if (!INSTANCE.tracking()) return false;
        SackItem item = INSTANCE.config().sackTrackerItem(name);
        return item != null && INSTANCE.approximate(item);
    }

    /** Sets a row's count; a count that rises to its goal rings once and shows a small title. */
    private void setAmount(SackItem item, long amount) {
        boolean reached = goalReached(item.amount(), amount, item.goal());
        config().setSackTrackerAmount(item, amount);
        if (!reached) return;
        var client = Minecraft.getInstance();
        if (config().sackTrackerGoalSound()) {
            var sounds = client.getSoundManager();
            sounds.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5F, 1.0F));
            sounds.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 2.0F, 1.0F));
        }
        if (config().sackTrackerGoalTitle()) {
            // Only the subtitle line has text: an empty title keeps the vanilla title overlay up for it, in the smaller font.
            McCompat.setTitleTimes(client, 5, 40, 10);
            McCompat.setTitle(client, Component.empty());
            McCompat.setSubtitle(client, goalTitle(item.name(), item.color()));
        }
    }

    /** "Enchanted Wheat done": the item in its rarity color. */
    static Component goalTitle(String name, int color) {
        return Component.literal(name).withColor(color).append(Component.literal(" done").withColor(0x55FF55));
    }

    /** Below the goal before, at or above it now. An unread row (-1) never rings: opening its sack is no pick-up. */
    static boolean goalReached(long before, long after, long goal) {
        return goal > 0 && before >= 0 && before < goal && after >= goal;
    }

    /**
     * After a world change: the counts now of every row the API can answer for, and the first ask. Rows without a "~"
     * are checked once as well: the first pick-ups of a burst are lost before any [Sacks] message announced them.
     */
    private void startResync() {
        resyncGeneration++;
        amountAtChange.clear();
        for (SackItem item : config().sackTrackerAllItems()) {
            if (!item.id().isEmpty() && item.amount() >= 0) amountAtChange.put(item, item.amount());
        }
        resyncStep = !amountAtChange.isEmpty() && config().sackTrackerApiResync() ? 0 : -1;
        ticksToResync = RESYNC_DELAYS[0];
    }

    private void tickResync(Minecraft client) {
        if (resyncStep < 0 || resyncInFlight || client.player == null || --ticksToResync > 0) return;
        long now = System.currentTimeMillis();
        while (!resyncRequests.isEmpty() && now - resyncRequests.peekFirst() > 300_000L) resyncRequests.removeFirst();
        if (!tracking() || amountAtChange.isEmpty() || approximate.isEmpty() && resyncStep >= QUIET_ASKS
            || HypixelSkyBlockProfileClient.sackSource().isEmpty()
            || resyncRequests.size() >= RESYNC_MAX_PER_5_MIN) {
            resyncStep = -1; // the sack was read or the API cannot be asked: the rows keep their "~"
            return;
        }
        resyncRequests.addLast(now);
        resyncInFlight = true;
        int step = resyncStep;
        int generation = resyncGeneration;
        HypixelSkyBlockProfileClient.INSTANCE.loadSackCounts(client.player.getUUID().toString())
            .whenComplete((counts, error) -> client.execute(() -> {
                resyncInFlight = false;
                if (generation != resyncGeneration) return;
                if (error == null) applyApi(counts, step);
                else apiFailed(error, step);
            }));
    }

    /** Rows the API settles lose their "~"; the others stay marked and are asked again until the last ask. */
    private void applyApi(SackCounts counts, int step) {
        boolean last = step == RESYNC_DELAYS.length - 1;
        String profile = config().sackTrackerProfileName();
        if (!tracking() || !profile.isBlank() && !counts.profile().isBlank() && !profile.equalsIgnoreCase(counts.profile())) {
            KungDebugRecorder.event("sack-tracker", "api resync dropped: the API's profile " + counts.profile() + " is not " + profile);
            resyncStep = -1;
            return;
        }
        boolean pending = false;
        for (SackItem item : List.copyOf(amountAtChange.keySet())) {
            boolean marked = approximate.contains(item);
            Long api = counts.counts().get(item.id());
            long before = amountAtChange.get(item);
            if (api == null) {
                if (marked) KungDebugRecorder.event("sack-tracker", "api resync " + item.name() + " id=" + item.id() + " is not in the answer");
                continue;
            }
            // A row without "~" has nothing to wait for: only found pick-ups change it, the same count is no news.
            long resolved = resolvedAmount(item.amount(), api, before, last && marked);
            if (marked || resolved >= 0 && resolved != item.amount()) {
                KungDebugRecorder.event("sack-tracker", "api resync ask " + (step + 1) + " " + item.name() + " " + item.id()
                    + " api=" + api + " atChange=" + before + " tracked=" + item.amount()
                    + (resolved < 0 ? " not yet" : " -> " + resolved));
            }
            if (resolved < 0) {
                pending |= marked;
                continue;
            }
            setAmount(item, resolved);
            approximate.remove(item);
            amountAtChange.remove(item);
        }
        resyncStep = (pending || step + 1 < QUIET_ASKS) && !last ? step + 1 : -1;
        if (resyncStep >= 0) ticksToResync = RESYNC_DELAYS[resyncStep];
    }

    private void apiFailed(Throwable error, int step) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String reason = String.valueOf(cause.getMessage());
        KungDebugRecorder.event("sack-tracker", "api resync ask " + (step + 1) + " failed: " + reason);
        // A limit (429) will not pass in seconds: stop, the rows keep their "~" until the sack is read.
        int next = reason.contains("429") ? -1 : step + 1;
        resyncStep = next < RESYNC_DELAYS.length ? next : -1;
        if (resyncStep >= 0) ticksToResync = RESYNC_DELAYS[resyncStep];
    }

    /**
     * What an API answer makes of a row marked "~", given the row's count when the world changed. More than that
     * count means the chat lost pick-ups, and the difference is added to the current count. The same count is exact
     * only on the last ask, once the API surely refreshed since the change; less is an older snapshot. -1: not yet.
     */
    static long resolvedAmount(long current, long api, long amountAtChange, boolean last) {
        long lost = api - amountAtChange;
        if (lost > 0) return current + lost;
        return lost == 0 && last ? current : -1;
    }

    private void markSynced(SackItem item) {
        syncedAt.put(item, System.currentTimeMillis());
        approximate.remove(item);
    }

    private void syncOpenSack(Minecraft client) {
        long epoch = HypixelInstanceTracker.INSTANCE.instanceEpoch();
        if (epoch != seenEpoch) {
            boolean first = seenEpoch == Long.MIN_VALUE;
            seenEpoch = epoch;
            if (!first && tracking()) {
                // Only rows grinded just before the change can be short; a quiet row (a relog, an island hop) stays exact.
                List<SackItem> active = activeItems(System.currentTimeMillis());
                approximate.addAll(active);
                startResync();
            }
        }
        tickResync(client);
        if (--ticksUntilSync > 0) return;
        ticksUntilSync = SYNC_TICKS;
        if (!tracking() || config().sackTrackerAllItems().isEmpty()
            || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) || !isSack(plain(screen.getTitle()))) return;
        if (SackGemstones.isGemstoneSack(plain(screen.getTitle()))) {
            syncGemstoneSack(screen);
            return;
        }
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            SackItem item = stack.isEmpty() ? null : config().sackTrackerItem(plain(stack.getHoverName()));
            Long stored = item == null ? null : stored(lore(stack));
            if (stored == null) continue;
            config().setSackTrackerId(item, SkyblockItemId.of(stack));
            readFromSack(item, stored);
        }
    }

    /** Takes the count a sack shows for a row and marks the row as read. */
    private void readFromSack(SackItem item, long shown) {
        if (item.amount() != shown) KungDebugRecorder.event("sack-tracker",
            "sack shows " + item.name() + " " + shown + " (tracked " + item.amount() + ")");
        setAmount(item, shown);
        markSynced(item);
    }

    /** A tracked whole gem takes the item's total; a tracked quality takes the count its line lists. */
    private void syncGemstoneSack(AbstractContainerScreen<?> screen) {
        String filter = SackGemstones.filterQuality(sackLore(screen));
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            var gem = stack.isEmpty() ? null : SackGemstones.parseName(plain(stack.getHoverName()));
            if (gem == null) continue;
            SackItem whole = filter == null && gem.quality() == null ? config().sackTrackerItem(plain(stack.getHoverName())) : null;
            Long total = whole == null ? null : wholeGemTotal(stack);
            if (whole != null && total != null) readFromSack(whole, total);
            SackGemstones.amounts(lore(stack), filter).forEach((quality, amount) -> {
                // A filtered item names its quality itself; the amount line then belongs to that quality.
                String shown = gem.quality() != null ? gem.quality() : quality;
                SackItem item = config().sackTrackerItem(gem.trackedName(shown));
                if (item != null) readFromSack(item, amount);
            });
        }
    }

    private void observeMessage(Component message) {
        // Until the profile is known the pick-ups cannot be placed: they are dropped, not counted into a guess.
        if (!tracking() || config().sackTrackerAllItems().isEmpty()) return;
        String text = plain(message);
        if (!text.startsWith("[Sacks] ")) return;
        Set<String> hovers = hoverTexts(message);
        // Hypixel sums the pick-ups of the last ~30 s into one message, so the count trails what the sack holds. A
        // message that covers the moment a sack was opened holds items that sack showed already: only the part
        // after that moment counts (items arrive about evenly, so by time).
        int seconds = coveredSeconds(text);
        changes(hovers).forEach((name, delta) -> {
            applyChange(name, delta, seconds);
            // A tracked whole gem ("Onyx Gemstones") counts every quality in Rough gems.
            var gem = SackGemstones.parseName(name);
            if (gem != null && gem.quality() != null) {
                applyChange(gem.gem() + " Gemstones", delta * SackGemstones.multiplier(gem.quality()), seconds);
            }
        });
    }

    /** "+130 items" and "-10 items" of a [Sacks] message each carry their item list as hover text. */
    static Set<String> hoverTexts(Component message) {
        Set<String> hovers = new LinkedHashSet<>();
        message.visit((style, part) -> {
            if (style.getHoverEvent() instanceof HoverEvent.ShowText(Component value)) hovers.add(plain(value));
            return Optional.<Void>empty();
        }, Style.EMPTY);
        return hovers;
    }

    private void applyChange(String name, long delta, int seconds) {
        SackItem item = config().sackTrackerItem(name);
        if (item == null || item.amount() < 0) return; // nothing to add to until the sack shows the amount
        long now = System.currentTimeMillis();
        if (delta != 0) changedAt.put(item, now);
        long counted = countedShare(delta, now, seconds, syncedAt.getOrDefault(item, Long.MIN_VALUE));
        KungDebugRecorder.event("sack-tracker", "message " + name + " " + (delta > 0 ? "+" : "") + delta
            + (counted != delta ? " counted " + counted : "") + " -> " + (item.amount() + counted));
        setAmount(item, item.amount() + counted);
    }

    /** "(Last 26s.)" or "(Last 1m 5s.)" of a [Sacks] message: how long the pick-ups it sums took; 0 when it says nothing. */
    static int coveredSeconds(String text) {
        Matcher matcher = LAST.matcher(text);
        if (!matcher.find()) return 0;
        return (matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1)) * 60) + Integer.parseInt(matcher.group(2));
    }

    /**
     * The part of a message's change that the sack did not show yet: the message covers the {@code seconds} before
     * now, a sack read at {@code syncedMillis} already holds everything up to then.
     */
    static long countedShare(long delta, long nowMillis, int seconds, long syncedMillis) {
        long span = seconds * 1000L;
        if (span <= 0 || syncedMillis <= nowMillis - span) return delta;
        if (syncedMillis >= nowMillis) return 0;
        return Math.round(delta * ((nowMillis - syncedMillis) / (double) span));
    }

    /** Net change per item across the hover lists: "+1,280 Wheat (Agronomy Sack)". */
    static Map<String, Long> changes(Collection<String> hovers) {
        Map<String, Long> changes = new LinkedHashMap<>();
        for (String hover : hovers) {
            for (String line : hover.split("\n")) {
                Matcher matcher = CHANGE.matcher(line.strip());
                if (matcher.matches()) changes.merge(matcher.group(2), Long.parseLong(matcher.group(1).replace(",", "")), Long::sum);
            }
        }
        return changes;
    }

    static boolean isSack(String title) { return title.endsWith(" Sack"); }

    /** "Stored: 1,234/20.2k" -> 1234; null for anything that is no sack item (gemstones list qualities instead). */
    static Long stored(List<String> lore) {
        for (String line : lore) {
            Matcher matcher = STORED.matcher(line.strip());
            if (matcher.find()) return parseAmount(matcher.group(1));
        }
        return null;
    }

    /** "1,234" -> 1234, "20.2k" -> 20200; null when it is no number. */
    public static Long parseAmount(String text) {
        String value = text.strip().replace(",", "").toLowerCase(Locale.ROOT);
        long factor = value.endsWith("k") ? 1_000L : value.endsWith("m") ? 1_000_000L : value.endsWith("b") ? 1_000_000_000L : 1L;
        if (factor > 1) value = value.substring(0, value.length() - 1);
        try {
            return Math.round(Double.parseDouble(value) * factor);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<String> lore(ItemStack stack) {
        return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(SackTrackerFeature::plain).toList();
    }

    static String plain(Component component) { return HypixelLocation.stripFormatting(component.getString()); }

    /** The rarity color of the item's name. */
    private static int color(Component name) {
        return name.visit((style, part) -> part.isBlank() || style.getColor() == null
            ? Optional.<Integer>empty() : Optional.of(style.getColor().getValue()), Style.EMPTY).orElse(0xFFFFFF);
    }

    private void render(GuiGraphicsExtractor graphics) {
        if (visible() && McCompat.screen(Minecraft.getInstance()) == null) {
            draw(graphics, config(), lines(config().sackTrackerItems(), false, approximate, config().sackTrackerProfileName()));
        }
    }

    /** In a world only: menus over the title screen or while joining have no game to track. */
    private boolean visible() {
        var client = Minecraft.getInstance();
        return tracking() && !config().sackTrackerItems().isEmpty() && client.level != null
            && !McCompat.hudHidden(client) && !KungHudEditorState.externalEditing();
    }

    /** Over a menu the tracker is interactive; screens of Kung and of other mods (waypoint editors) keep their space. */
    private boolean overScreen(Screen screen) {
        return visible() && config().sackTrackerInMenus() && vanilla(screen.getClass());
    }

    /** Vanilla screens and every container screen: a mod that replaces the inventory (SkyblockInventoryScreen) still is one. */
    static boolean vanilla(Class<?> screenClass) {
        return screenClass.getName().startsWith("net.minecraft.") || AbstractContainerScreen.class.isAssignableFrom(screenClass);
    }

    private void renderOverScreen(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!overScreen(screen)) return;
        graphics.nextStratum();
        draw(graphics, config(), lines(config().sackTrackerItems(), true, approximate, config().sackTrackerProfileName()));
        if (hoveredItem(mouseX, mouseY) != null) {
            List<String> tooltip = typesGoals(screen)
                ? List.of("Click to remove from tracker", GOAL_HINT, PASTE_HINT) : List.of("Click to remove from tracker");
            UiTooltip.draw(graphics, Minecraft.getInstance().font, tooltip, mouseX + 8, mouseY, screen.width, screen.height, THEME);
        }
    }

    /**
     * Goal typing: inventories and containers only (chat and Kung's own screens keep their keys), and not
     * while a text field such as an anvil's name box has focus.
     */
    private boolean typesGoals(Screen screen) {
        return overScreen(screen) && screen instanceof AbstractContainerScreen<?> && !(screen.getFocused() instanceof EditBox);
    }

    /** True when the key edited the goal of the hovered tracker row; the screen must then not see it. */
    private boolean typeGoal(Screen screen, KeyEvent event) {
        if (!typesGoals(screen)) return false;
        var client = Minecraft.getInstance();
        SackItem item = hoveredItem(client.mouseHandler.getScaledXPos(client.getWindow()), client.mouseHandler.getScaledYPos(client.getWindow()));
        if (item == null) return false;
        int key = McInput.key(event);
        boolean control = (event.modifiers() & MOD_CONTROL) != 0;
        Long goal = control && key == KEY_V ? pastedGoal(client.keyboardHandler.getClipboard()) : nextGoal(item.goal(), key, control);
        if (goal == null) return false;
        config().setSackTrackerGoal(item, goal);
        return true;
    }

    /**
     * The tracker row the cursor is on. Deleting a goal digit shortens the row's text, which would slide it out from
     * under the cursor: while the cursor does not move, the row stays hovered, so every further key still counts for it.
     */
    private SackItem hoveredItem(double mouseX, double mouseY) {
        List<SackItem> items = config().sackTrackerItems();
        int line = lineAt(mouseX, mouseY);
        if (line >= 1 && line <= items.size()) {
            held = items.get(line - 1);
            heldX = mouseX;
            heldY = mouseY;
            return held;
        }
        if (held != null && resting(heldX, heldY, mouseX, mouseY) && items.contains(held)) return held;
        held = null;
        return null;
    }

    static boolean resting(double fromX, double fromY, double toX, double toY) {
        return Math.abs(fromX - toX) < REST_PIXELS && Math.abs(fromY - toY) < REST_PIXELS;
    }

    /** A goal from the clipboard: only a number ("1500", "1,500", "1.5m") within the typing limit; null for anything else. */
    static Long pastedGoal(String clipboard) {
        String text = clipboard == null ? "" : clipboard.strip();
        if (!text.matches("\\d[\\d,]*([kKmMbB]|\\.\\d+[kKmMbB])?")) return null;
        Long amount = parseAmount(text);
        return amount == null || amount < 1 || amount > MAX_GOAL ? null : amount;
    }

    /**
     * The goal after a key press: a digit (top row or numpad) appends, 1 then 5 is 15, up to nine digits;
     * Backspace drops the last digit, Ctrl+Backspace clears the goal (0 = none). Null for every other key.
     */
    static Long nextGoal(long goal, int key, boolean control) {
        int digit = key >= 48 && key <= 57 ? key - 48 : key >= 320 && key <= 329 ? key - 320 : -1;
        if (digit >= 0) return goal > (MAX_GOAL - digit) / 10 ? goal : goal * 10 + digit;
        if (key == KEY_BACKSPACE) return control ? 0L : goal / 10;
        return null;
    }

    /** Which drawn line the cursor is on: 1..items is an item, one past them is Reset Display, -1 is none. */
    private int lineAt(double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        List<Component> lines = lines(config().sackTrackerItems(), true, approximate, "");
        float scale = config().sackTrackerScale() / 100F;
        for (int index = 1; index < lines.size(); index++) {
            float left = config().sackTrackerX() + 8 * scale;
            float top = config().sackTrackerY() + (4 + index * LINE_HEIGHT) * scale;
            if (mouseX >= left && mouseX <= left + font.width(lines.get(index)) * scale
                && mouseY >= top && mouseY <= top + (LINE_HEIGHT - 1) * scale) return index;
        }
        return -1;
    }

    private boolean click(Screen screen, MouseButtonEvent event) {
        if (!overScreen(screen)) return false;
        int line = lineAt(event.x(), event.y());
        List<SackItem> items = config().sackTrackerItems();
        if (line >= 1 && line <= items.size()) config().removeSackTrackerItem(items.get(line - 1));
        else if (line == items.size() + 1) config().clearSackTrackerItems();
        else return false;
        return true;
    }

    /**
     * "Enchanted Wheat: 1,234/10,000" - red until the goal, green from it; no goal shows the count alone. Rows in
     * {@code approximate} get a "~" in front of the count; a known profile is named in the title.
     */
    static List<Component> lines(List<SackItem> items, boolean interactive, Set<SackItem> approximate, String profile) {
        List<Component> lines = new ArrayList<>();
        MutableComponent title = Component.literal("Sack Tracker").withColor(0xFFFF55).withStyle(ChatFormatting.BOLD);
        if (!profile.isBlank()) title.append(Component.literal(" " + profile).withColor(0xAAAAAA).withStyle(style -> style.withBold(false)));
        lines.add(title);
        for (SackItem item : items) {
            MutableComponent line = Component.literal(item.name()).withColor(item.color())
                .append(Component.literal(": ").withColor(0xFFFFFF));
            int amountColor = item.goal() <= 0 || item.amount() < 0 ? 0xFFFF55
                : item.amount() >= item.goal() ? 0x55FF55 : 0xFF5555;
            line.append(Component.literal((approximate.contains(item) && item.amount() >= 0 ? "~" : "")
                + amountText(item.amount())).withColor(amountColor));
            if (item.goal() > 0) line.append(Component.literal("/" + format(item.goal())).withColor(0xFFFFFF));
            lines.add(line);
        }
        if (interactive) lines.add(RESET);
        return lines;
    }

    public static String format(long amount) { return String.format(Locale.ROOT, "%,d", amount); }

    /** The count of a tracked item; "?" for one tracked outside its sack that has not been read yet. */
    public static String amountText(long amount) { return amount < 0 ? "?" : format(amount); }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, List<Component> lines) {
        var font = Minecraft.getInstance().font;
        HudPose.begin(graphics, config.sackTrackerX(), config.sackTrackerY(), config.sackTrackerScale() / 100F);
        try {
            for (int index = 0; index < lines.size(); index++) {
                graphics.text(font, lines.get(index), index == 0 ? 4 : 8, 4 + index * LINE_HEIGHT, 0xFFFFFFFF, true);
            }
        } finally {
            HudPose.end(graphics);
        }
    }

    private static List<Component> previewLines(MiscConfig config) {
        return lines(config.sackTrackerItems().isEmpty()
            ? List.of(new SackItem("Enchanted Wheat", 0x55FF55, 1234, 10000), new SackItem("Wheat", 0xFFFFFF, 56789, 0))
            : config.sackTrackerItems(), false, Set.of(), config.sackTrackerProfileName());
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, previewLines(config));
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        var client = Minecraft.getInstance();
        List<Component> lines = previewLines(config);
        int width = client == null ? 150 : lines.stream().mapToInt(line -> client.font.width(line)).max().orElse(0) + 16;
        float scale = config.sackTrackerScale() / 100F;
        return new KungHudLayout.Bounds(config.sackTrackerX(), config.sackTrackerY(),
            Math.round(width * scale), Math.round((lines.size() * LINE_HEIGHT + 6) * scale));
    }
}
