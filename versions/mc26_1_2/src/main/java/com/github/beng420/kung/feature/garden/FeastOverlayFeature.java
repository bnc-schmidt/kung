package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.FeastConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.runtime.KungPaths;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.skyblock.SkyBlockMayorTracker;
import com.github.beng420.kung.skyblock.SkyBlockSidebar;
import com.github.beng420.kung.ui.HudPose;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public final class FeastOverlayFeature extends ConfigurableFeature<FeastConfig> {
    public static final FeastOverlayFeature INSTANCE = new FeastOverlayFeature();
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "feast_progress");
    private static final int WIDTH = 180;
    private static final int HEIGHT = 52;
    private static final int BAR_X = 3;
    private static final int BAR_Y = 15;
    private static final int BAR_WIDTH = WIDTH - BAR_X * 2;
    private static final int BAR_HEIGHT = 10;
    private static final int TEXT = 0xFFF3F5F7;
    private static final int GREEN = 0xFF29B34A;
    private static final int YELLOW = 0xFFE6C63B;
    private static final int BAR_OUTLINE = 0xEE101510;
    // Illustrative editor values; live milestone boundaries always come from the menu.
    private static final FeastProgress.Snapshot EXAMPLE = new FeastProgress.Snapshot(
        FeastProgress.Kind.GRAND, 234, List.of(5, 25, 75, 150, 250, 350, 450, 550, 750));

    private final FeastSession session = new FeastSession();
    private final FeastKernels kernels = new FeastKernels();
    private final FeastKernelRate kernelRate = new FeastKernelRate();
    private final GardenCropTracker crops = new GardenCropTracker();
    private final FeastContext context = new FeastContext();
    private FeastStateStore store;
    private FeastPersistence persistence;
    private long epoch = -1;
    private List<String> lastSidebar;
    private List<String> lastVisible;
    private boolean inGarden;
    private boolean inHubFarm;
    private boolean hypixel;
    private int menuTicks;
    private AbstractContainerMenu diagnosticMenu;
    private String diagnosticKey = "";
    private int diagnosticSnapshots;

    private FeastOverlayFeature() { super(config -> config.feast); }

    @Override
    protected void onInitialize() {
        store = new FeastStateStore(KungPaths.fileLayout().settingsDirectory().resolve("feast-progress.json"),
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "kung-feast-save");
                thread.setDaemon(true);
                return thread;
            }));
        persistence = new FeastPersistence(store, session, kernels, kernelRate);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        registerMessageObserver(this::observeMessage);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, HUD_ID,
            (graphics, delta) -> render(graphics));
    }

    static void registerMessageObserver(Consumer<String> observer) {
        // ALLOW_GAME calls every listener even when another mod hides the message, before MODIFY_GAME.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) observer.accept(message.getString());
            return true;
        });
    }

    private void observeMessage(String text) {
        if (!initialized() || !isEnabled() || !hypixel) return;
        if (FeastContext.profileMessage(text)) {
            context.observeProfileMessage(text);
            selectProfile();
        } else if (FeastContext.profileId(text) != null) {
            persistence.identify(FeastContext.profileId(text));
        } else if (text.contains("Seasoning") || text.contains("Kernel")) {
            updateContext();
            if (text.contains("Seasoning")) {
                boolean counted = session.donate(text);
                KungDebugRecorder.event("feast-donation", "counted=" + counted
                    + " amount=" + FeastProgress.donationAmount(text) + " event=" + session.event()
                    + " synced=" + (session.snapshot() != null) + " message=" + text);
                if (counted) recordProgress("donation");
            }
            if (FeastKernels.donationMessage(text)) {
                boolean rateCounted = kernelRate.observeMessage(text, now());
                var client = Minecraft.getInstance();
                var beforeMessage = client.level == null ? List.<String>of()
                    : SkyBlockSidebar.lines(client.level.getScoreboard(), null);
                if (kernels.observeMessage(text, beforeMessage) || rateCounted) recordKernels("donation");
            }
        }
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled(); }

    @Override
    protected void onReset() {
        kernelRate.pause(now());
        if (persistence != null) {
            persistence.save();
            persistence.reset();
        }
        session.reset();
        kernels.reset();
        kernelRate.reset();
        crops.reset();
        context.reset();
        epoch = -1;
        lastSidebar = null;
        lastVisible = null;
        inGarden = false;
        inHubFarm = false;
        hypixel = false;
        menuTicks = 0;
        diagnosticMenu = null;
        diagnosticKey = "";
        diagnosticSnapshots = 0;
    }

    @Override
    protected void onShutdown() {
        onReset();
        if (store != null) store.flush();
    }

    private void tick(Minecraft client) {
        if (!initialized()) return;
        if (!isEnabled()) {
            onReset();
            return;
        }
        // A world transfer can briefly have no player; only disconnect invalidates the session.
        if (client.player == null || client.level == null) {
            kernelRate.pause(now());
            return;
        }
        hypixel = HypixelLocation.onHypixel(client);
        if (!hypixel) {
            onReset();
            return;
        }
        updateContext();
        if (++menuTicks >= 5) {
            menuTicks = 0;
            observeMenu(client);
            persistence.save(); // Completed rate blocks also save when no donation or menu update arrives.
        }
    }

    private void updateContext() {
        HypixelInstanceTracker instance = HypixelInstanceTracker.INSTANCE;
        if (epoch != instance.instanceEpoch()) {
            epoch = instance.instanceEpoch();
            context.worldChanged();
            kernels.worldChanged();
            kernelRate.pause(now());
            crops.reset();
            lastSidebar = null;
            lastVisible = null;
        }
        if (lastSidebar != instance.sidebarLines() || lastVisible != instance.visibleLines()) {
            boolean sidebarChanged = lastSidebar != instance.sidebarLines();
            lastSidebar = instance.sidebarLines();
            lastVisible = instance.visibleLines();
            context.observe(lastSidebar, lastVisible);
            selectProfile();
            if (sidebarChanged) {
                Long previousSidebar = kernels.sidebarBalance();
                if (kernels.observeSidebar(lastSidebar)
                    || !java.util.Objects.equals(previousSidebar, kernels.sidebarBalance())) recordKernels("sidebar");
            }
            inGarden = instance.tracking() && FeastContext.garden(instance.instanceLine(), lastVisible);
            inHubFarm = instance.tracking() && FeastContext.hubFarm(instance.instanceLine(), lastVisible);
        }
        var mayor = SkyBlockMayorTracker.INSTANCE.feastStatus();
        var previous = session.snapshot();
        session.select(context.event(mayor.active(System.currentTimeMillis()), mayor.electionYear()), context.hasDate());
        kernels.selectFeast(session.event());
        kernelRate.setTimeoutSeconds(config().kernelTimeoutSeconds(), now());
        kernelRate.updateContext(hypixel && inGarden, session.event(), now());
        if (previous != null && session.snapshot() == null) {
            KungDebugRecorder.event("feast", "reset reason=changed-or-inactive-feast");
        }
        if (persistence != null) persistence.restoreProgress();
    }

    private void observeMenu(Minecraft client) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
            || screen.getMenu() != client.player.containerMenu
            || FeastProgress.Kind.fromTitle(screen.getTitle().getString()) == null
                && !FeastKernels.supportsMenu(screen.getTitle().getString())) {
            session.closeMenu();
            kernels.closeMenu();
            diagnosticMenu = null;
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        int topSlots = MenuItems.topSlots(menu);
        if (topSlots <= 0 || topSlots > 54) return;
        List<FeastProgress.MenuItem> items = new ArrayList<>();
        for (int index = 0; index < topSlots; index++) {
            var stack = menu.slots.get(index).getItem();
            if (stack.isEmpty()) continue;
            items.add(new FeastProgress.MenuItem(stack.getHoverName().getString(), MenuItems.lore(stack, 40)));
        }
        String title = screen.getTitle().getString();
        Long balance = FeastKernels.readMenu(title, items);
        long previousPendingGains = kernels.pendingGains();
        if (kernels.observeMenu(menu, balance) || previousPendingGains != kernels.pendingGains()) recordKernels("menu");
        if (FeastProgress.Kind.fromTitle(title) == null) {
            session.closeMenu();
            diagnosticMenu = null;
            return;
        }
        var read = FeastProgress.inspectMenu(title, items);
        diagnoseMenu(menu, title, items, read, balance);
        if (session.observeMenu(menu, read.snapshot())) recordProgress("menu");
    }

    public static void observeContainerInput(int containerId, int slotId, int button, ContainerInput input) {
        if (!INSTANCE.isEnabled() || (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE)
            || button < 0 || button > 1) return;
        var client = Minecraft.getInstance();
        var menu = currentGrandMenu(client, containerId);
        if (menu == null || slotId < 0 || slotId >= MenuItems.topSlots(menu)) return;
        var milestone = readMilestone(menu.slots.get(slotId).getItem());
        if (milestone == null || !milestone.claimable()) return;
        INSTANCE.updateContext();
        if (client.level != null) {
            Long previousSidebar = INSTANCE.kernels.sidebarBalance();
            if (INSTANCE.kernels.observeSidebar(SkyBlockSidebar.lines(client.level.getScoreboard(), null))
                || !java.util.Objects.equals(previousSidebar, INSTANCE.kernels.sidebarBalance())) {
                INSTANCE.recordKernels("claim-sidebar");
            }
        }
        if (INSTANCE.kernels.beginMilestoneClaim(milestone, now())) {
            KungDebugRecorder.event("feast-claim", "clicked container=" + containerId + " slot=" + slotId
                + " tier=" + milestone.tier() + " reward=" + milestone.kernels() + " balance=" + INSTANCE.kernels.balance());
        }
    }

    public static void observeCropClick(BlockPos pos) {
        if (!INSTANCE.isEnabled() || !INSTANCE.hypixel || !INSTANCE.inGarden) return;
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.player.isSpectator()) return;
        if (INSTANCE.crops.observe(pos, client.level.getBlockState(pos))) INSTANCE.kernelRate.crop(now());
    }

    public static void observeSlotUpdate(int containerId, int slotId, ItemStack stack) {
        if (!INSTANCE.kernels.pendingMilestoneClaims()) return;
        var menu = currentGrandMenu(Minecraft.getInstance(), containerId);
        if (menu == null || slotId < 0 || slotId >= MenuItems.topSlots(menu)) return;
        INSTANCE.updateContext();
        INSTANCE.observeClaimStack(stack);
    }

    public static void observeContentUpdate(int containerId, List<ItemStack> stacks) {
        if (!INSTANCE.kernels.pendingMilestoneClaims()) return;
        var menu = currentGrandMenu(Minecraft.getInstance(), containerId);
        if (menu == null || stacks.size() > menu.slots.size()) return;
        INSTANCE.updateContext();
        for (int slot = 0; slot < Math.min(stacks.size(), MenuItems.topSlots(menu)); slot++) {
            INSTANCE.observeClaimStack(stacks.get(slot));
        }
    }

    private void observeClaimStack(ItemStack stack) {
        var credit = kernels.observeMilestone(readMilestone(stack), now());
        if (credit != null) recordKernels("milestone tier=" + credit.tier() + " reward=" + credit.kernels());
    }

    private static FeastMilestoneClaims.Milestone readMilestone(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (!stack.has(DataComponents.LORE)) return null;
        return FeastMilestoneClaims.read("Grand Feast", new FeastProgress.MenuItem(stack.getHoverName().getString(),
            MenuItems.lore(stack, 40)), stack.hasFoil());
    }

    private static AbstractContainerMenu currentGrandMenu(Minecraft client, int containerId) {
        if (!INSTANCE.isEnabled() || client.player == null) return null;
        if (!HypixelLocation.onHypixel(client) || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)
            || FeastProgress.Kind.fromTitle(screen.getTitle().getString()) != FeastProgress.Kind.GRAND) return null;
        var menu = screen.getMenu();
        int topSlots = MenuItems.topSlots(menu);
        return menu == client.player.containerMenu && menu.containerId == containerId && topSlots > 0 && topSlots <= 54
            ? menu : null;
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    private void diagnoseMenu(AbstractContainerMenu menu, String title, List<FeastProgress.MenuItem> items,
                              FeastProgress.MenuRead read, Long menuKernels) {
        if (menu != diagnosticMenu) {
            diagnosticMenu = menu;
            diagnosticKey = "";
            diagnosticSnapshots = 0;
        }
        var milestones = items.stream()
            .filter(item -> HypixelLocation.clean(item.name()).contains("Milestone")).limit(9).toList();
        String key = read.reason() + ":" + session.event() + ":" + milestones + ":" + menuKernels;
        if (key.equals(diagnosticKey) || diagnosticSnapshots >= 3) return;
        diagnosticKey = key;
        diagnosticSnapshots++;
        KungDebugRecorder.event("feast-menu", "container=" + menu.containerId + " title=" + title
            + " event=" + session.event() + " result=" + read.reason() + " milestones=" + milestones.size());
        if (FeastProgress.Kind.fromTitle(title) == FeastProgress.Kind.GRAND) {
            KungDebugRecorder.event("feast-kernels", "source=menu-read balance=" + menuKernels
                + " bakery=" + items.stream().filter(item -> HypixelLocation.clean(item.name()).equals("Grand Bakery"))
                    .limit(2).toList());
        }
        // Capture the actual server lore on failed syncs instead of silently returning an unknown total.
        if (read.snapshot() == null) {
            for (var item : milestones) {
                KungDebugRecorder.event("feast-menu", "item=" + item.name() + " lore=" + item.lore());
            }
            if (milestones.isEmpty()) KungDebugRecorder.event("feast-menu",
                "names=" + items.stream().map(FeastProgress.MenuItem::name).toList());
        }
    }

    private void selectProfile() {
        var player = Minecraft.getInstance().player;
        if (persistence != null && player != null
            && persistence.select(player.getUUID().toString(), context.profile())) {
            KungDebugRecorder.event("feast", "profile=" + context.profile() + " cache-selected");
        }
    }

    private void recordKernels(String source) {
        var rate = kernelRate.snapshot(now());
        if (persistence != null) persistence.save();
        KungDebugRecorder.event("feast-kernels", "source=" + source + " balance=" + kernels.balance()
            + " sidebar=" + kernels.sidebarBalance() + " pendingGains=" + kernels.pendingGains()
            + " rateBlockKernels=" + rate.kernels() + " rateBlockFarmingMs=" + rate.farmingMillis()
            + " rateEstimate=" + rate.average() + " ratePerHour=" + rate.perHour() + " ratePaused=" + rate.paused());
    }

    private void recordProgress(String source) {
        if (persistence != null) persistence.save();
        var value = session.snapshot();
        KungDebugRecorder.event("feast", "source=" + source + " event=" + session.baselineEvent()
            + " donations=" + value.donations() + "/" + value.goal() + " goals=" + value.goals());
    }

    private void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null || McCompat.screen(client) instanceof KungHudEditorScreen
            || KungHudEditorState.externalEditing()) return;
        // Consult fresh shared instance state at render time, including the first frame after a warp.
        updateContext();
        if (!visible(hypixel, inGarden, inHubFarm, config().showInHubFarm(), session.event())) return;
        draw(graphics, config(), session.event().kind(), session.snapshot(), kernels.balance(), kernelRate.snapshot(now()));
    }

    static boolean visible(boolean hypixel, boolean garden, FeastContext.Event event) {
        return visible(hypixel, garden, false, false, event);
    }

    static boolean visible(boolean hypixel, boolean garden, boolean hubFarm, boolean showInHubFarm,
                           FeastContext.Event event) {
        return hypixel && (garden || hubFarm && showInHubFarm) && event != null;
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, FeastConfig config) {
        draw(graphics, config, FeastProgress.Kind.GRAND, EXAMPLE, 1_234L,
            new FeastKernelRate.Snapshot(0, 0, 120.0, false));
    }

    private static void draw(GuiGraphicsExtractor graphics, FeastConfig config,
                             FeastProgress.Kind kind, FeastProgress.Snapshot value, Long kernels, FeastKernelRate.Snapshot rate) {
        var font = Minecraft.getInstance().font;
        int goal = value == null ? kind.defaultGoal() : value.goal();
        String total = (value == null ? "--" : Integer.toString(value.donations())) + " / " + goal;
        String next = footer(kind, value, kernels);
        int segments = value == null ? (kind == FeastProgress.Kind.GRAND ? 9 : 5) : value.goals().size();
        float scale = config.scale() / 100.0F;
        HudPose.begin(graphics, config.x(), config.y(), scale);
        try {
            graphics.text(font, total, (WIDTH - font.width(total)) / 2, 2, TEXT, true);
            drawBar(graphics, segments, value);
            drawLine(graphics, next, 29, value != null && value.complete() ? GREEN : TEXT);
            String average = rateLine(kind, rate);
            if (!average.isEmpty()) drawLine(graphics, average, 41, TEXT);
        } finally {
            HudPose.end(graphics);
        }
    }

    private static void drawLine(GuiGraphicsExtractor graphics, String text, int y, int color) {
        var font = Minecraft.getInstance().font;
        float scale = Math.min(1.0F, BAR_WIDTH / (float) Math.max(1, font.width(text)));
        HudPose.begin(graphics, WIDTH / 2.0F, y, scale);
        try {
            graphics.text(font, text, -font.width(text) / 2, 0, color, true);
        } finally {
            HudPose.end(graphics);
        }
    }

    static String rateLine(FeastProgress.Kind kind, FeastKernelRate.Snapshot rate) {
        if (kind != FeastProgress.Kind.GRAND) return "";
        Long average = rate.perHour();
        return "Avg Kernels/h: " + (average == null ? "--" : String.format(Locale.US, "%,d", average))
            + (rate.paused() ? " (paused)" : average == null ? " (warming up)" : "");
    }

    static String footer(FeastProgress.Kind kind, FeastProgress.Snapshot value, Long kernels) {
        String next = value == null ? "Open Feast menu to sync"
            : value.complete() ? "All milestones reached" : value.toNext() + " to next";
        return kind == FeastProgress.Kind.GRAND
            ? next + " - " + (kernels == null ? "--" : String.format(Locale.US, "%,d", kernels)) + " Kernels"
            : next;
    }

    private static void drawBar(GuiGraphicsExtractor graphics, int segments, FeastProgress.Snapshot value) {
        graphics.fill(BAR_X, BAR_Y, BAR_X + BAR_WIDTH, BAR_Y + BAR_HEIGHT, BAR_OUTLINE);
        graphics.fill(BAR_X + 1, BAR_Y + 1, BAR_X + BAR_WIDTH - 1, BAR_Y + BAR_HEIGHT - 1, 0xBBE0E7E0);
        int previousGoal = 0;
        int markerX = -1;
        for (int index = 0; index < segments; index++) {
            int left = BAR_X + 1 + (int) Math.round((BAR_WIDTH - 2) * index / (double) segments);
            int right = BAR_X + 1 + (int) Math.round((BAR_WIDTH - 2) * (index + 1) / (double) segments);
            int contentLeft = left + (index == 0 ? 0 : 1);
            if (value != null) {
                int tierGoal = value.goals().get(index);
                if (value.donations() >= tierGoal) {
                    graphics.fill(contentLeft, BAR_Y + 1, right, BAR_Y + BAR_HEIGHT - 1, GREEN);
                } else if (value.donations() >= previousGoal) {
                    graphics.fill(contentLeft, BAR_Y + 1, right, BAR_Y + BAR_HEIGHT - 1, YELLOW);
                    double progress = (value.donations() - previousGoal) / (double) (tierGoal - previousGoal);
                    markerX = contentLeft + (int) Math.round((right - contentLeft - 1) * progress);
                }
                previousGoal = tierGoal;
            }
            if (index > 0) graphics.fill(left, BAR_Y + 1, left + 1, BAR_Y + BAR_HEIGHT - 1, 0xDD101510);
        }
        // Extend beyond the bar so the progress marker stays distinct from milestone dividers.
        if (markerX >= 0) {
            graphics.fill(markerX - 1, BAR_Y - 2, markerX + 2, BAR_Y + BAR_HEIGHT + 2, BAR_OUTLINE);
            graphics.fill(markerX, BAR_Y - 1, markerX + 1, BAR_Y + BAR_HEIGHT + 1, TEXT);
        }
    }

    public static KungHudLayout.Bounds overlayBounds(FeastConfig config) {
        float scale = config.scale() / 100.0F;
        return new KungHudLayout.Bounds(config.x(), config.y(), Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
