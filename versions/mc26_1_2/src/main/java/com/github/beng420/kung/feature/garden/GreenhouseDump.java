package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.garden.GreenhouseDumpFormat.CellRef;
import com.github.beng420.kung.feature.garden.GreenhouseDumpFormat.Texture;
import com.github.beng420.kung.runtime.KungFileLayout;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.update.KungUpdater;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * {@code /kung greenhouse dump}: writes what the client sees in the garden plot the player stands in, to settle
 * the open questions of a plot scanner in one visit (armor stand positions and heads, soil blocks, orientation).
 * It needs neither a shown hologram nor the Carpenter. One entity query, 500 block reads, client thread only,
 * and it never throws: a failure comes back in {@link Outcome#error()}.
 */
public final class GreenhouseDump {
    /** {@code error} is null on success; then {@code path} is the file written. */
    public record Outcome(Path path, String error, int armorStands, int cells) {
        static Outcome failed(String error) { return new Outcome(null, error, 0, 0); }
    }

    private static final DateTimeFormatter FILE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final int MAX_CONTEXT_LINES = 60;
    private static final int FIRST_Y = 71;
    private static final int LAST_Y = 75;

    private GreenhouseDump() { }

    public static Outcome run(Minecraft client) {
        try {
            return dump(client);
        } catch (IOException | RuntimeException failure) {
            KungDebugRecorder.event("greenhouse", "dump failed: " + failure);
            return Outcome.failed("Dump failed: " + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : " (" + failure.getMessage() + ")"));
        }
    }

    private static Outcome dump(Minecraft client) throws IOException {
        if (client == null || client.player == null || client.level == null) return Outcome.failed("Not in a world.");
        if (!GreenhouseLocator.inGarden()) return Outcome.failed("You are not in the Garden.");
        var player = client.player;
        Level level = client.level;
        int playerX = (int) Math.floor(player.getX());
        int playerZ = (int) Math.floor(player.getZ());
        Optional<BlockPos> found = GreenhouseLocator.cornerOf(playerX, playerZ);
        if (found.isEmpty()) return Outcome.failed("Stand inside one of the Garden's 5x5 plots (around 0, 0).");
        BlockPos corner = found.get();

        GreenhouseDumpFormat.Body body = new GreenhouseDumpFormat.Body();
        header(body, client, corner, playerX, playerZ);

        List<Entity> entities = level.getEntities((Entity) null, GreenhouseLocator.plotBounds(playerX, playerZ));
        Map<String, Integer> typeCounts = new TreeMap<>();
        List<ArmorStand> stands = new ArrayList<>();
        List<Entity> others = new ArrayList<>();
        List<Entity> carpenters = new ArrayList<>();
        for (Entity entity : entities) {
            typeCounts.merge(typeId(entity), 1, Integer::sum);
            Component name = entity.getCustomName();
            if (name != null && name.getString().contains("Carpenter")) carpenters.add(entity);
            if (!GreenhouseDumpFormat.inGridArea(entity.getX(), entity.getY(), entity.getZ(), corner.getX(), corner.getZ())) {
                continue;
            }
            if (entity instanceof ArmorStand stand) stands.add(stand);
            else others.add(entity);
        }
        Comparator<Entity> byPosition = Comparator.<Entity>comparingDouble(Entity::getX)
            .thenComparingDouble(Entity::getZ).thenComparingDouble(Entity::getY);
        stands.sort(byPosition);
        others.sort(byPosition);

        body.line("");
        body.line("== entities in the plot (y " + GreenhouseLocator.PLOT_MIN_Y + ".." + GreenhouseLocator.PLOT_MAX_Y
            + ", whole 96x96) ==");
        body.line("total=" + entities.size() + " types: " + GreenhouseDumpFormat.counts(typeCounts));
        for (Entity entity : carpenters) body.line("carpenter-named: " + describe(entity, corner));

        body.line("");
        body.line("== armor stands in the grid area (x/z corner-1..corner+11, y 70..82): " + stands.size() + " ==");
        int index = 0;
        for (ArmorStand stand : stands) {
            index++;
            try {
                stand(body, index, stand, corner);
            } catch (RuntimeException failure) {
                body.line("stand #" + index + " id=" + stand.getId() + " ERROR " + failure);
            }
        }

        body.line("");
        body.line("== other entities in the grid area: " + others.size() + " ==");
        for (Entity entity : others) body.line(describe(entity, corner));

        body.line("");
        body.line("== blocks per cell (raw offsets from the corner; row/col = default mapping dx = 9 - row, dz = col;"
            + " y" + FIRST_Y + "..y" + LAST_Y + "; unloaded chunks read as air) ==");
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < GreenhouseGrid.SIZE; dx++) {
            for (int dz = 0; dz < GreenhouseGrid.SIZE; dz++) {
                StringBuilder cell = new StringBuilder("dx=").append(dx).append(" dz=").append(dz)
                    .append(" row=").append(GreenhouseGrid.SIZE - 1 - dx).append(" col=").append(dz).append(" |");
                for (int y = FIRST_Y; y <= LAST_Y; y++) {
                    at.set(corner.getX() + dx, y, corner.getZ() + dz);
                    cell.append(" y").append(y).append('=').append(block(level.getBlockState(at)));
                }
                body.line(cell.toString());
            }
        }

        String text = body.finish();
        Path path = write(client, text);
        return new Outcome(path, null, stands.size(), GreenhouseGrid.SIZE * GreenhouseGrid.SIZE);
    }

    private static void header(GreenhouseDumpFormat.Body body, Minecraft client, BlockPos corner, int playerX,
                               int playerZ) {
        var player = client.player;
        HypixelInstanceTracker instance = HypixelInstanceTracker.INSTANCE;
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("?");
        boolean confirmed = GreenhouseLocator.current(client, true).map(corner::equals).orElse(false);
        CellRef standing = GreenhouseDumpFormat.cell(player.getX(), player.getZ(), corner.getX(), corner.getZ());
        body.line("KUNG GREENHOUSE DUMP " + Instant.now());
        body.line("kung=" + KungUpdater.currentVersion() + " mc=" + mc + " flavor="
            + (KungBuild.MODRINTH ? "modrinth" : "full"));
        body.line("player=" + GreenhouseDumpFormat.quote(player.getName().getString()) + String.format(Locale.ROOT,
            " pos=%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f facing=%s", player.getX(), player.getY(), player.getZ(),
            player.getYRot(), player.getXRot(), player.getDirection().getName()));
        body.line(String.format(Locale.ROOT,
            "player cell: dx=%d dz=%d inGrid=%s row=%d col=%d offset-in-cell=%.3f,%.3f (0.5,0.5 = centre)",
            standing.dx(), standing.dz(), standing.inGrid(), standing.row(), standing.column(),
            player.getX() - corner.getX() - standing.dx(), player.getZ() - corner.getZ() - standing.dz()));
        body.line("plot origin=" + GreenhouseLocator.plotOrigin(playerX) + "," + GreenhouseLocator.plotOrigin(playerZ)
            + " corner=" + corner.getX() + "," + corner.getY() + "," + corner.getZ()
            + " (origin + 43, y 73, + 43; cells extend +x/+z)");
        body.line("carpenter detection confirms this plot=" + confirmed);
        body.line("hologram: " + GreenhouseHologram.stateSummary());
        body.line("scanner: " + GreenhouseScanner.summary());
        body.line("instance: tracking=" + instance.tracking() + " instanceLine=" + GreenhouseDumpFormat.quote(
            instance.instanceLine()) + " serverId=" + GreenhouseDumpFormat.quote(instance.serverId())
            + " location=" + instance.location());
        lines(body, "sidebar", instance.sidebarLines());
        lines(body, "visible (tab list / area)", instance.visibleLines());
    }

    private static void lines(GreenhouseDumpFormat.Body body, String label, List<String> lines) {
        body.line(label + " lines=" + lines.size());
        for (int i = 0; i < lines.size() && i < MAX_CONTEXT_LINES; i++) {
            body.line("  " + GreenhouseDumpFormat.quote(lines.get(i)));
        }
    }

    private static void stand(GreenhouseDumpFormat.Body body, int index, ArmorStand stand, BlockPos corner) {
        CellRef cell = GreenhouseDumpFormat.cell(stand.getX(), stand.getZ(), corner.getX(), corner.getZ());
        body.line(String.format(Locale.ROOT,
            "stand #%d id=%d pos=%.4f,%.4f,%.4f cell dx=%d dz=%d inGrid=%s row=%d col=%d marker=%s small=%s"
                + " invisible=%s name=%s", index, stand.getId(), stand.getX(), stand.getY(), stand.getZ(),
            cell.dx(), cell.dz(), cell.inGrid(), cell.row(), cell.column(), stand.isMarker(), stand.isSmall(),
            stand.isInvisible(), name(stand)));
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        body.line("  head: " + (head.isEmpty() ? "empty" : item(head)));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.HEAD) continue;
            ItemStack stack = stand.getItemBySlot(slot);
            if (!stack.isEmpty()) body.line("  equip " + slot.getName() + ": " + itemId(stack));
        }
    }

    private static String describe(Entity entity, BlockPos corner) {
        CellRef cell = GreenhouseDumpFormat.cell(entity.getX(), entity.getZ(), corner.getX(), corner.getZ());
        return String.format(Locale.ROOT, "type=%s id=%d pos=%.3f,%.3f,%.3f cell dx=%d dz=%d inGrid=%s name=%s",
            typeId(entity), entity.getId(), entity.getX(), entity.getY(), entity.getZ(), cell.dx(), cell.dz(),
            cell.inGrid(), name(entity));
    }

    private static String name(Entity entity) {
        Component custom = entity.getCustomName();
        return custom == null ? "-" : GreenhouseDumpFormat.quote(custom.getString());
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
    }

    /** Item id, the three names a stack can carry, and the skin of a player head (hash and the full base64). */
    private static String item(ItemStack stack) {
        StringBuilder out = new StringBuilder("item=").append(itemId(stack))
            .append(" hover=").append(GreenhouseDumpFormat.quote(stack.getHoverName().getString()))
            .append(" itemName=").append(component(stack.get(DataComponents.ITEM_NAME)))
            .append(" customName=").append(component(stack.get(DataComponents.CUSTOM_NAME)));
        try {
            ResolvableProfile profile = stack.get(DataComponents.PROFILE);
            if (profile == null) return out.append(" profile=none").toString();
            boolean any = false;
            for (var property : profile.partialProfile().properties().get("textures")) {
                any = true;
                Optional<Texture> texture = GreenhouseDumpFormat.texture(property.value());
                out.append(" textureHash=").append(texture.map(Texture::hash).orElse("?"))
                    .append(" textureUrl=").append(texture.map(Texture::url).orElse("?"))
                    .append(" base64=").append(property.value());
            }
            if (!any) out.append(" profile=present textures=none");
        } catch (RuntimeException failure) {
            out.append(" profile=ERROR ").append(failure);
        }
        return out.toString();
    }

    private static String component(Component component) {
        return component == null ? "-" : GreenhouseDumpFormat.quote(component.getString());
    }

    /** {@code minecraft:farmland[moisture=7]}: the id and every property of the state. */
    private static String block(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getProperties().isEmpty()) return id;
        StringBuilder out = new StringBuilder(id).append('[');
        boolean first = true;
        for (Property<?> property : state.getProperties()) {
            if (!first) out.append(',');
            first = false;
            out.append(propertyValue(state, property));
        }
        return out.append(']').toString();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName() + "=" + property.getName(state.getValue(property));
    }

    private static Path write(Minecraft client, String text) throws IOException {
        Path gameDirectory = client.gameDirectory.toPath();
        Path directory = new KungFileLayout(gameDirectory, gameDirectory.resolve("config")).logDirectory();
        Files.createDirectories(directory);
        Path path = directory.resolve("kung-greenhouse-dump-" + FILE_FORMAT.format(Instant.now()) + ".txt");
        // getBytes replaces a stray surrogate, where writeString would throw and lose the file.
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        return path.toAbsolutePath();
    }
}
