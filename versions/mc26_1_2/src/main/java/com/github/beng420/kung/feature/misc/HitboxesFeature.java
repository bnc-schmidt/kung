package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.HitboxesConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.LineBoxes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.github.beng420.kung.config.KungConfig;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HitboxesFeature extends ConfigurableFeature<HitboxesConfig> {
    private static final RenderStateDataKey<List<Box>> BOXES = RenderStateDataKey.create(() -> "kung:hitboxes");
    public static final String SKYBLOCK = "skyblock:";
    public static final String ITEM = "item:";
    private static final Pattern LEVEL = Pattern.compile("\\[Lv\\d+]");
    private static final Pattern HEALTH = Pattern.compile("\\S*\\d\\S*\u2764.*$");
    private static final Pattern NOT_NAME = Pattern.compile("[^A-Za-z' ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_PATH = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
    /** Named stands are matched to their mobs this often; the boxes still follow the mobs every frame. */
    private static final int SCAN_TICKS = 10;
    private static final long RECENT_MILLIS = 60_000;
    /** Held items drawn this frame that match an item entry, in world space; filled by the item layer. */
    private static final List<Box> heldItemBoxes = new ArrayList<>();
    /** The selection, resolved once per change instead of per entity per frame. */
    private static Map<String, Integer> resolvedFor = Map.of();
    private static Map<EntityType<?>, String> byType = Map.of();
    private static Set<String> byItem = Set.of();
    private static boolean anySkyBlock;
    /** Mob entity id -> entry id, from the last scan of named armor stands. */
    private static Map<Integer, String> skyBlockMobs = Map.of();
    /** Id -> last seen: what stood around the player lately, still addable after it left or despawned. */
    private static final Map<String, Long> recent = new HashMap<>();
    private static int ticksUntilScan;

    public HitboxesFeature() { super(config -> config.hitboxes); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::scan);
        LevelRenderEvents.END_EXTRACTION.register(this::extract);
        // Before water, like vanilla's block outline: water writes depth, so lines drawn after it vanish behind
        // its surface. Drawn first, water blends over them while solid blocks still hide them. 26.2 only queues
        // them here; its translucent feature pass draws them, also before translucent terrain.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(HitboxesFeature::render);
    }

    @Override
    public boolean isEnabled() { return config().enabled(); }

    private void resolve() {
        var selected = config().activeEntities();
        if (selected.equals(resolvedFor)) return;
        resolvedFor = Map.copyOf(selected);
        Map<EntityType<?>, String> types = new HashMap<>();
        Set<String> items = new HashSet<>();
        boolean skyBlock = false;
        for (String id : resolvedFor.keySet()) {
            if (id.startsWith(SKYBLOCK)) skyBlock = true;
            else if (id.startsWith(ITEM)) items.add(id);
            else {
                // Entity types default to pig; an unknown id must stay unmatched instead.
                var key = Identifier.tryParse(id);
                if (key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                    types.put(BuiltInRegistries.ENTITY_TYPE.getValue(key), id);
                }
            }
        }
        byType = Map.copyOf(types);
        byItem = Set.copyOf(items);
        anySkyBlock = skyBlock;
    }

    /**
     * SkyBlock mobs are vanilla entities; their SkyBlock name sits on an armor stand above them.
     * Matching names is string work over every named stand, so it runs twice a second, not per frame.
     * The same pass remembers every type, name and held item for the Add Hitbox list.
     */
    private void scan(Minecraft client) {
        if (--ticksUntilScan > 0) return;
        ticksUntilScan = SCAN_TICKS;
        if (client.level == null) {
            skyBlockMobs = Map.of();
            return;
        }
        long now = Util.getMillis();
        recent.values().removeIf(seen -> now - seen > RECENT_MILLIS);
        boolean match = isEnabled();
        if (match) resolve();
        match &= anySkyBlock;
        Set<EntityType<?>> types = new HashSet<>();
        Map<Integer, String> mobs = new HashMap<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            types.add(entity.getType());
            if (!(entity instanceof ArmorStand stand)) continue;
            // Thrown items such as the Bonemerang are armor stands holding the item.
            for (ItemStack held : List.of(stand.getMainHandItem(), stand.getOffhandItem())) {
                if (!held.isEmpty()) recent.put(itemId(held), now);
            }
            traceNameTag(stand, client.player);
            if (!stand.hasCustomName()) continue;
            String nametag = stand.getCustomName().getString();
            String id = skyBlockId(nametag);
            if (id != null && id.length() > SKYBLOCK.length() + 2) recent.put(id, now);
            String entry = match ? skyBlockEntry(resolvedFor.keySet(), nametag) : null;
            if (entry == null) continue;
            // A name without a mob under it is the thing itself, such as an egg sac's SHOOT ME! stand.
            Entity mob = mobBelow(stand);
            if (mob == null) KungDebugRecorder.event("hitbox", "name without mob id=" + stand.getId()
                + " y=" + (int) stand.getY() + " name=\"" + KungDebugRecorder.compact(nametag) + "\"");
            mobs.put((mob == null ? stand : mob).getId(), entry);
        }
        for (EntityType<?> type : types) recent.put(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), now);
        skyBlockMobs = Map.copyOf(mobs);
    }

    private void extract(LevelExtractionContext context) {
        List<Box> boxes = new ArrayList<>();
        if (isEnabled()) {
            resolve();
            var camera = context.camera();
            Vec3 position = camera.position();
            float partialTick = camera.getCameraEntityPartialTicks(context.deltaTracker());
            for (var mob : skyBlockMobs.entrySet()) {
                Entity entity = context.level().getEntity(mob.getKey());
                if (entity != null && !entity.isRemoved()) boxes.add(box(mob.getValue(), interpolatedBounds(entity, partialTick)));
            }
            if (!byType.isEmpty()) for (Entity entity : context.level().entitiesForRendering()) {
                String id = byType.get(entity.getType());
                if (id == null || entity.isRemoved() || !entity.shouldRender(position.x, position.y, position.z)
                    || entity == camera.entity() && !camera.isDetached()) continue;
                if (!(entity instanceof EnderDragon) || config().dragonOverallBox()) {
                    boxes.add(box(id, interpolatedBounds(entity, partialTick)));
                }
                if (entity instanceof EnderDragon dragon && config().dragonPartBoxes()) {
                    for (var part : dragon.getSubEntities()) boxes.add(box(id, interpolatedBounds(part, partialTick)));
                }
            }
        }
        // Each frame owns immutable geometry, so world changes and disabling cannot leave old entities behind.
        context.levelState().setData(BOXES, List.copyOf(boxes));
    }

    /** Nearest living, non-stand entity just below the name; a stand sits at most a few blocks over its mob. */
    private static Entity mobBelow(ArmorStand stand) {
        Entity nearest = null;
        Entity self = Minecraft.getInstance().player;
        for (Entity candidate : stand.level().getEntities(stand, stand.getBoundingBox().inflate(1.5, 4, 1.5),
            entity -> mobCandidate(entity, stand, self))) {
            if (nearest == null || candidate.distanceToSqr(stand) < nearest.distanceToSqr(stand)) nearest = candidate;
        }
        return nearest;
    }

    /** Never the local player: a mob in melee range leaves the player nearer to its name than the mob, or the only one once it is gone. */
    static boolean mobCandidate(Entity entity, Entity stand, Entity self) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand) && entity != self && entity.getY() <= stand.getY() + 0.5;
    }

    /** Vanilla prints the type name, "Armor Stand", for a stand flagged to show a name it never got. */
    public static boolean drawsTypeName(ArmorStand stand) {
        return stand.isCustomNameVisible() && !stand.hasCustomName();
    }

    /** Trace only, the nametag is vanilla's: catches a stand showing "Armor Stand" or one whose name is flagged hidden. */
    private static void traceNameTag(ArmorStand stand, Entity player) {
        String problem = drawsTypeName(stand) ? "type-name" : stand.hasCustomName() && !stand.isCustomNameVisible() ? "name-hidden" : null;
        if (problem == null || player == null) return;
        var name = stand.getCustomName();
        KungDebugRecorder.event("nametag", problem + " id=" + stand.getId() + " age=" + stand.tickCount
            + " dist=" + (int) stand.distanceTo(player) + " dy=" + (int) (stand.getY() - player.getY())
            + " invisible=" + stand.isInvisible() + " marker=" + stand.isMarker()
            + " name=\"" + (name == null ? "" : KungDebugRecorder.compact(name.getString())) + "\"");
    }

    static String skyBlockEntry(Set<String> selected, String nametag) {
        String words = "_" + path(nametag) + "_";
        for (String id : selected) {
            if (id.startsWith(SKYBLOCK) && words.contains("_" + id.substring(SKYBLOCK.length()) + "_")) return id;
        }
        return null;
    }

    /** "[Lv55] Zealot 13,000/13,000❤" -> "Zealot": the level and HP change, the name does not. */
    static String mobName(String nametag) {
        String name = HEALTH.matcher(LEVEL.matcher(nametag).replaceAll("")).replaceAll("");
        return SPACES.matcher(NOT_NAME.matcher(name).replaceAll(" ")).replaceAll(" ").trim();
    }

    /** "Healthy Skeleton Master" -> "skyblock:skeleton_master", or null when no letters are left. */
    public static String skyBlockId(String name) {
        String path = path(mobName(name));
        return path.isEmpty() ? null : HitboxesConfig.withoutModifiers(SKYBLOCK + path);
    }

    private static String path(String text) {
        return EDGE_UNDERSCORES.matcher(NOT_PATH.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("_")).replaceAll("");
    }

    /** Everything seen around the player in the last minute: SkyBlock names, then items, then entity types. */
    public static List<String> recentIds() {
        return recent.keySet().stream().sorted(Comparator.comparingInt(
            (String id) -> id.startsWith(SKYBLOCK) ? 0 : id.startsWith(ITEM) ? 1 : 2).thenComparing(Comparator.naturalOrder())).toList();
    }

    private static AABB interpolatedBounds(Entity entity, float partialTick) {
        AABB bounds = entity.getBoundingBox().move(entity.getPosition(partialTick).subtract(entity.position()));
        // A nametag stand is a marker with a paper-thin box; boxed on its own it has to stay visible.
        return entity instanceof ArmorStand && bounds.getYsize() < 0.5 ? bounds.inflate(0.35) : bounds;
    }

    /**
     * Boxes the item itself where the game draws it, whatever the stand's pose or size.
     * ponytail: armor stands only - that is how SkyBlock shows thrown items such as the Bonemerang;
     * players and mobs holding the same item would light up too. Widen it when that is wanted.
     */
    public static void observeHeldItem(EntityType<?> holder, ItemStack stack, Matrix4f pose) {
        if (holder != McCompat.ARMOR_STAND || byItem.isEmpty() || stack.isEmpty() || heldItemBoxes.size() >= 256
            || !KungConfig.get().hitboxes.enabled()) return;
        String id = itemId(stack);
        if (!byItem.contains(id)) return;
        // Handheld items are drawn around (0, 4, 0.5)/16 from the hand anchor (item/handheld display).
        Vector3f center = pose.transformPosition(new Vector3f(0, 0.25F, 1 / 32F));
        float half = 0.3F * pose.transformDirection(new Vector3f(1, 0, 0)).length();
        Vec3 world = McCompat.camera(Minecraft.getInstance()).position().add(center.x, center.y, center.z);
        heldItemBoxes.add(box(id, new AABB(world, world).inflate(half)));
    }

    /** Only the drawn copy is styled; nothing reads it but the renderer. */
    private static Box box(String id, AABB bounds) {
        HitboxesConfig config = KungConfig.get().hitboxes;
        return new Box(scaled(bounds, config.boxSize(id)), config.entities().getOrDefault(id, HitboxesConfig.DEFAULT_COLOR),
            2.0F * config.lineThickness(id) / 100F);
    }

    /** Around the center, like Ice Spray's Box Size: no entry draws anything tied to the feet. */
    static AABB scaled(AABB bounds, int percent) {
        if (percent == 100) return bounds;
        double scale = percent / 100.0;
        return AABB.ofSize(bounds.getCenter(), bounds.getXsize() * scale, bounds.getYsize() * scale, bounds.getZsize() * scale);
    }

    /** "minecraft:bone" -> "item:bone", the id an item entry is stored under. */
    public static String itemId(ItemStack stack) {
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return ITEM + (key.getNamespace().equals("minecraft") ? key.getPath() : key.getNamespace() + "_" + key.getPath());
    }

    private static void render(LevelRenderContext context) {
        List<Box> boxes = new ArrayList<>(context.levelState().getDataOrDefault(BOXES, List.of()));
        boxes.addAll(heldItemBoxes);
        heldItemBoxes.clear();
        if (boxes.isEmpty()) return;
        Vec3 camera = context.levelState().cameraRenderState.pos;
        McCompat.draw(context, RenderTypes.lines(), (pose, vertices) -> {
            for (Box box : boxes) LineBoxes.box(vertices, pose, box.bounds(), camera.x, camera.y, camera.z, box.color(), box.width());
        });
    }

    /** Width in screen pixels: the lines vertex format carries it per vertex. */
    private record Box(AABB bounds, int color, float width) { }
}
