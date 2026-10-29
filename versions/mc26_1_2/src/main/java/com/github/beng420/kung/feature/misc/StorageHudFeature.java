package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.skyblock.SkyblockItemId;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * Shows the storage of the held Builder's Ruler, Basket of Seeds or Nether Wart Basket. The open storage screen sets
 * the contents; after that every use takes blocks from the first slot on: the ruler by its "You built N blocks!"
 * chat line, a basket by the length of the soil row it plants. Where the blocks come from, what "removed" and UNDO
 * do and where a basket's row starts are all unverified: see the "storage-hud" trace. The next screen open
 * corrects any drift.
 */
public final class StorageHudFeature extends ConfigurableFeature<MiscConfig> {
    public static final StorageHudFeature INSTANCE = new StorageHudFeature();
    private static final int COLUMNS = StorageGrid.COLUMNS;
    private static final int SLOT = 18;
    private static final int PADDING = 2;
    private static final int PREVIEW_ROWS = 2;
    /** A storage that reads empty this early may only be one whose content has not arrived yet. */
    private static final int EMPTY_READ_TICKS = 5;
    private final Map<HeldStorage, Snapshot> snapshots = new EnumMap<>(HeldStorage.class);
    private HeldStorage held;
    private Screen readScreen;
    private int openTicks;
    private int lastUseTick = -1;
    private CustomData tracedData;
    private final Set<HeldStorage> decodedItems = EnumSet.noneOf(HeldStorage.class);

    private StorageHudFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // Hypixel sends some lines as chat and some as system messages; take both.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) observeMessage(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(message.getString()));
        // A click on a block and one in the air both start a basket's row; observeUse keeps only one per tick.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            observeUse(player, level, hand, hit.getBlockPos());
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            observeUse(player, level, hand, null);
            return InteractionResult.PASS;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onReset());
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "storage_hud"), (graphics, delta) -> render(graphics));
    }

    @Override
    public boolean isEnabled() { return initialized() && config().storageHudEnabled(); }

    private void tick(Minecraft client) {
        var player = client.player;
        held = isEnabled() && player != null ? HeldStorage.of(SkyblockItemId.of(player.getMainHandItem())) : null;
        if (held != null) {
            traceItemData(player.getMainHandItem());
            readStorage(client, player);
        } else {
            readScreen = null;
            tracedData = null;
        }
    }

    /**
     * Diagnostic only: Hypixel may keep the contents in the item's own data, which would make the screen read and
     * the counting unnecessary. Logs the data keys whenever they change, and decodes every byte array once per item
     * as gzipped NBT. Whether the contents are in there, and whether a use changes them, is read off the trace.
     */
    private void traceItemData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (Objects.equals(data, tracedData)) return;
        tracedData = data;
        if (data == null) {
            KungDebugRecorder.event("storage-hud", "item data " + held + " none");
            return;
        }
        CompoundTag tag = data.copyTag();
        boolean firstSight = decodedItems.add(held);
        StringBuilder keys = new StringBuilder();
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            keys.append(key).append('=');
            if (value instanceof ByteArrayTag bytes) {
                byte[] raw = bytes.getAsByteArray();
                keys.append("bytes[").append(raw.length).append("]#").append(Arrays.hashCode(raw));
                if (firstSight) traceDecoded(key, raw);
            } else {
                String text = String.valueOf(value);
                keys.append(text.length() > 40 ? text.substring(0, 40) + "..." : text);
            }
            keys.append(' ');
        }
        KungDebugRecorder.event("storage-hud", "item data " + held + " " + keys);
    }

    private static void traceDecoded(String key, byte[] raw) {
        try {
            String text = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap()).toString();
            KungDebugRecorder.event("storage-hud", "item data " + key + " decoded length=" + text.length());
            for (int from = 0, part = 1; from < text.length() && part <= 5; from += 800, part++) {
                KungDebugRecorder.event("storage-hud", "item data " + key + " part " + part + ": "
                    + text.substring(from, Math.min(text.length(), from + 800)));
            }
        } catch (Exception notGzippedNbt) {
            KungDebugRecorder.event("storage-hud", "item data " + key + " is no gzipped NBT: " + notGzippedNbt);
        }
    }

    /** While the storage screen is open, the grid is whatever it shows; the content arrives a tick or two after the screen. */
    private void readStorage(Minecraft client, Player player) {
        if (!(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen)) {
            readScreen = null;
            return;
        }
        if (screen != readScreen) {
            readScreen = screen;
            openTicks = 0;
        }
        openTicks++;
        String title = screen.getTitle().getString();
        if (!held.opens(title, player.getMainHandItem().getHoverName().getString())) {
            if (openTicks == 1) KungDebugRecorder.event("storage-hud", "screen is no " + held + " storage title=\""
                + HypixelLocation.clean(title) + "\"");
            return;
        }
        var slots = screen.getMenu().slots.stream().filter(slot -> slot.container != player.getInventory()).toList();
        String[] keys = new String[slots.size()];
        int[] counts = new int[slots.size()];
        ItemStack[] shown = new ItemStack[slots.size()];
        boolean filled = false;
        for (int index = 0; index < slots.size(); index++) {
            ItemStack stack = slots.get(index).getItem();
            keys[index] = stack.isEmpty() ? "" : itemKey(stack);
            counts[index] = stack.isEmpty() ? 0 : stack.getCount();
            shown[index] = stack.copy();
            filled |= !stack.isEmpty();
        }
        if (!filled && openTicks < EMPTY_READ_TICKS) return;
        var next = new Snapshot(new StorageGrid(keys, counts), shown);
        var previous = snapshots.put(held, next);
        String read = describe(next.grid);
        next.read = read;
        // "was" is what the HUD believed before this read: the gap to "blocks" is the drift of the prediction.
        if (previous == null || previous.dirty || !read.equals(previous.read)) {
            KungDebugRecorder.event("storage-hud", "storage read " + held + " " + read
                + (previous == null ? "" : " was=" + previous.grid.total()));
        }
    }

    private void observeMessage(String text) {
        if (!isEnabled()) return;
        int built = StorageGrid.builtBlocks(text);
        if (built < 0) {
            if (HypixelLocation.clean(text).startsWith("You removed ")) {
                KungDebugRecorder.event("storage-hud", "line not applied: " + HypixelLocation.clean(text));
            }
            return;
        }
        var snapshot = held == HeldStorage.BUILDERS_RULER ? snapshots.get(held) : null;
        if (snapshot == null) {
            KungDebugRecorder.event("storage-hud", "built " + built + " ignored held=" + held + " storage=" + snapshots.containsKey(HeldStorage.BUILDERS_RULER));
            return;
        }
        consume(held, snapshot, built, "built " + built);
    }

    /** A right-click with a basket: it takes one seed per soil block of the row ahead. */
    private void observeUse(Player player, Level level, InteractionHand hand, BlockPos clicked) {
        if (!isEnabled() || hand != InteractionHand.MAIN_HAND || !level.isClientSide()
            || player != Minecraft.getInstance().player || player.tickCount == lastUseTick) return;
        var storage = HeldStorage.of(SkyblockItemId.of(player.getMainHandItem()));
        var snapshot = storage == null || !storage.plantsRows() ? null : snapshots.get(storage);
        if (snapshot == null) return;
        lastUseTick = player.tickCount;
        BlockPos start = clicked != null ? clicked : player.blockPosition().below();
        Direction facing = player.getDirection();
        int row = BasketRow.length(start.getX(), start.getZ(), facing.getStepX(), facing.getStepZ(),
            (x, z) -> plantable(level, storage, new BlockPos(x, start.getY(), z)));
        consume(storage, snapshot, row, "row from " + start.toShortString() + (clicked == null ? " (below feet)" : "")
            + " facing " + facing + " length " + row);
    }

    private static boolean plantable(Level level, HeldStorage storage, BlockPos pos) {
        return storage.plantsOn(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString())
            && level.getBlockState(pos.above()).isAir();
    }

    private static void consume(HeldStorage storage, Snapshot snapshot, int used, String what) {
        int taken = snapshot.grid.consume(used);
        snapshot.dirty = true;
        snapshot.rebuild();
        KungDebugRecorder.event("storage-hud", storage + " " + what + " taken " + taken + " " + describe(snapshot.grid));
    }

    private static String describe(StorageGrid grid) {
        int first = grid.firstFilled();
        return "slots=" + grid.size() + " first=" + first + (first < 0 ? "" : " " + grid.key(first)) + " blocks=" + grid.total();
    }

    /** What makes two stacks the same block: the SkyBlock id, or the item for plain blocks that have none. */
    private static String itemKey(ItemStack stack) {
        String id = SkyblockItemId.of(stack);
        return id.isEmpty() ? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() : id;
    }

    @Override
    protected void onReset() {
        snapshots.clear();
        held = null;
        readScreen = null;
        openTicks = 0;
        lastUseTick = -1;
        tracedData = null;
        decodedItems.clear();
    }

    @Override
    protected void onShutdown() { onReset(); }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        var snapshot = held == null ? null : snapshots.get(held);
        if (!isEnabled() || snapshot == null || snapshot.cells.isEmpty() || McCompat.screen(client) != null
            || McCompat.hudHidden(client) || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), snapshot.cells);
    }

    private static int width() { return COLUMNS * SLOT - 2 + PADDING * 2; }

    private static int height(int rows) { return rows * SLOT - 2 + PADDING * 2; }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, List<ItemStack> cells) {
        var font = Minecraft.getInstance().font;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.storageHudX(), config.storageHudY());
            float scale = config.storageHudScale() / 100F;
            graphics.pose().scale(scale, scale);
            if (config.storageHudBackground()) graphics.fill(0, 0, width(), height(cells.size() / COLUMNS), 0x99111318);
            for (int index = 0; index < cells.size(); index++) {
                ItemStack stack = cells.get(index);
                if (stack.isEmpty()) continue;
                int x = PADDING + index % COLUMNS * SLOT;
                int y = PADDING + index / COLUMNS * SLOT;
                graphics.item(stack, x, y);
                graphics.itemDecorations(font, stack, x, y);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static List<ItemStack> previewCells() {
        List<ItemStack> cells = new ArrayList<>(Collections.nCopies(PREVIEW_ROWS * COLUMNS, ItemStack.EMPTY));
        cells.set(7, new ItemStack(Items.DIRT, 23));
        cells.set(8, new ItemStack(Items.DIRT, 64));
        cells.set(9, new ItemStack(Items.DIRT, 64));
        cells.set(10, new ItemStack(Items.COBBLESTONE, 64));
        cells.set(11, new ItemStack(Items.COBBLESTONE, 12));
        return cells;
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, previewCells());
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.storageHudScale() / 100F;
        return new KungHudLayout.Bounds(config.storageHudX(), config.storageHudY(),
            Math.round(width() * scale), Math.round(height(PREVIEW_ROWS) * scale));
    }

    /** One storage as last read, drained by the uses since. */
    private static final class Snapshot {
        private final StorageGrid grid;
        private final ItemStack[] stacks;
        /** The rows from the first to the last filled one, row by row; empty slots are {@link ItemStack#EMPTY}. */
        private List<ItemStack> cells = List.of();
        private String read = "";
        /** True once a use drained the grid: the next read is logged even when it looks the same. */
        private boolean dirty;

        private Snapshot(StorageGrid grid, ItemStack[] stacks) {
            this.grid = grid;
            this.stacks = stacks;
            rebuild();
        }

        private void rebuild() {
            int first = grid.firstFilled();
            if (first < 0) {
                cells = List.of();
                return;
            }
            int end = (grid.lastFilled() / COLUMNS + 1) * COLUMNS;
            List<ItemStack> rebuilt = new ArrayList<>();
            for (int slot = first / COLUMNS * COLUMNS; slot < end; slot++) {
                rebuilt.add(slot < grid.size() && grid.count(slot) > 0 ? stacks[slot].copyWithCount(grid.count(slot)) : ItemStack.EMPTY);
            }
            cells = Collections.unmodifiableList(rebuilt);
        }
    }
}
