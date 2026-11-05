package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.DungeonConfig.DragonDebuffScope;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Packet observations only: using the wand or aiming at a dragon is not a hit. */
public final class DungeonDebuffFeature extends ConfigurableFeature<DungeonConfig> {
    public static final DungeonDebuffFeature INSTANCE = new DungeonDebuffFeature();
    private final DungeonDebuffTracker tracker = new DungeonDebuffTracker();
    private final Map<UUID, IceMarker> markers = new LinkedHashMap<>();
    private final Map<UUID, EnderDragon> dragons = new HashMap<>();
    private final Map<UUID, Boolean> endings = new HashMap<>();
    private final Map<UUID, String> highlightStates = new HashMap<>();
    private long epoch = Long.MIN_VALUE;

    private DungeonDebuffFeature() { super(config -> config.dungeon); }

    @Override
    public boolean isEnabled() {
        return initialized() && (config().iceSprayHighlightEnabled() || config().dragonDebuffEnabled());
    }

    @Override
    protected void onInitialize() {
        IceSprayHighlightRenderer.initialize();
        DungeonServerTickEvents.register(() -> {
            if (ready() && tracker.advance()) {
                var selected = tracker.displayedDragons(DragonDebuffScope.NEAREST_STATUE);
                trace("statue-selection target=" + (selected.isEmpty() ? "unknown" : selected.getFirst().statue.label()));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("kung", "dragon_debuff"),
            (graphics, delta) -> {
                Minecraft client = Minecraft.getInstance();
                if (!ready() || !trackingDragons() || !config().dragonDebuffHudEnabled() || client.player == null || McCompat.hudHidden(client)
                    || McCompat.screen(client) instanceof KungHudEditorScreen || KungHudEditorState.externalEditing()) return;
                DragonDebuffHud.draw(graphics, config(), tracker.displayedDragons(config().dragonDebuffScope()), tracker.tick());
            });
    }

    @Override
    protected void onReset() {
        tracker.reset();
        markers.clear();
        dragons.clear();
        endings.clear();
        highlightStates.clear();
        epoch = Long.MIN_VALUE;
    }

    private boolean ready() {
        var instance = HypixelInstanceTracker.INSTANCE;
        if (!isEnabled() || Minecraft.getInstance().level == null || !instance.catacombs()) {
            onReset();
            return false;
        }
        if (epoch != instance.instanceEpoch()) {
            onReset();
            epoch = instance.instanceEpoch();
        }
        if (!trackingDragons()) {
            tracker.clearDragons();
            dragons.clear();
            endings.clear();
        }
        return true;
    }

    private boolean trackingDragons() {
        var floor = HypixelInstanceTracker.INSTANCE.dungeonFloor();
        return config().dragonDebuffEnabled() && floor.masterMode() && floor.floor() == 7;
    }

    public void observeSpawn(Entity entity) {
        if (!(entity instanceof EnderDragon dragon) || !ready() || !trackingDragons()) return;
        var statue = DungeonDebuffTracker.statueAt(entity.position());
        if (statue == null) return;
        var player = Minecraft.getInstance().player;
        var observation = tracker.spawn(entity.getUUID(), statue,
            player != null && HypixelInstanceTracker.INSTANCE.positionKnown() ? player.position() : null);
        if (observation == null || observation.ended) return;
        dragons.put(entity.getUUID(), dragon);
        trace("spawn name=" + statue.label() + " id=" + entity.getId() + " uuid=" + entity.getUUID()
            + " player=" + (player == null ? "unknown" : player.position()));
    }

    public void observeEquipment(ClientboundSetEquipmentPacket packet) {
        if (!ready()) return;
        if (packet.getSlots().stream().noneMatch(slot -> slot.getSecond().is(Items.PACKED_ICE))) return;
        Entity entity = Minecraft.getInstance().level.getEntity(packet.getEntity());
        trace("ice-equipment id=" + packet.getEntity() + " entity=" + (entity == null ? "missing" : entity.getType())
            + " position=" + (entity == null ? "unknown" : entity.position())
            + " matchPosition=" + (entity == null ? "unknown" : McCompat.interpolatedPosition(entity)));
        if (!(entity instanceof ArmorStand stand)) return;
        if (markers.containsKey(stand.getUUID()) || markers.size() >= DungeonDebuffTracker.MAX_MARKERS) return;
        IceMarker marker = new IceMarker(stand, tracker.tick());
        markers.put(stand.getUUID(), marker);
        resolve(marker, false);
    }

    private void resolve(IceMarker marker, boolean settledBatch) {
        if (marker.resolved || marker.attempts >= 4 || marker.entity.isRemoved()) return;
        marker.attempts++;
        if (!marker.entity.isInvisible()) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var markerTarget = matchingTarget(marker.entity);
        Vec3 markerPosition = markerTarget.position();
        List<DungeonDebuffTracker.Target> candidates = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, markerTarget.bounds().inflate(8))) {
            if (!sprayCandidate(entity.getType(), entity.getUUID(), entity.isAlive())) continue;
            if (candidates.size() >= 128) return; // In a crowded query, retain unknown rather than truncate the competition.
            candidates.add(matchingTarget(entity));
        }
        UUID target = marker.match(markerPosition, candidates, settledBatch);
        if (target == null) {
            trace("ice-marker id=" + marker.entity.getId() + " position=" + marker.entity.position()
                + " matchPosition=" + markerPosition
                + " settledBatch=" + settledBatch
                + " target=ambiguous-or-missing candidates=" + candidates.size()
                + " nearest=" + candidates.stream()
                    .sorted(Comparator.comparingDouble(candidate -> candidate.distanceTo(markerPosition)))
                    .limit(2).map(candidate -> "distance=" + candidate.distanceTo(markerPosition)
                        + " " + describeTarget(level.getEntity(candidate.uuid()))).toList());
            return;
        }
        tracker.spray(target, marker.observedTick);
        trace("ice-marker id=" + marker.entity.getId() + " target=" + target + " matchPosition=" + markerPosition
            + " observedTick=" + marker.observedTick
            + " expiresTick=" + (marker.observedTick + DungeonDebuffTracker.SPRAY_TICKS)
            + " " + describeTarget(level.getEntity(target)));
    }

    public void observeSound(ClientboundSoundPacket packet) {
        if (!ready() || !trackingDragons()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || dragons.isEmpty()) return;
        String sound = packet.getSound().value().location().toString();
        if (!sound.equals("minecraft:entity.arrow.hit_player")) return;
        Vec3 origin = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        boolean local = DungeonDebuffTracker.localArrowFeedback(sound, origin, client.player.position());
        boolean accepted = local && tracker.arrowHit();
        trace("arrow-feedback accepted=" + accepted + " local=" + local + " position=" + origin
            + " player=" + client.player.position() + " volume=" + packet.getVolume() + " pitch=" + packet.getPitch()
            + " held=" + client.player.getMainHandItem().getHoverName().getString());
    }

    public void observeDeath(Entity entity) {
        if (!(entity instanceof EnderDragon) || !ready() || !trackingDragons()) return;
        if (dragons.containsKey(entity.getUUID())) endings.put(entity.getUUID(), true);
    }

    public void observeData(Entity entity) {
        if (entity instanceof EnderDragon dragon && dragon.getHealth() <= 0) observeDeath(entity);
    }

    private void tick(Minecraft client) {
        if (!ready()) return;
        markers.values().removeIf(marker -> marker.entity.isRemoved());
        for (IceMarker marker : markers.values()) resolve(marker, true);
        for (var entry : dragons.entrySet()) {
            if (entry.getValue().isRemoved()) endings.putIfAbsent(entry.getKey(), false);
        }
        // End-of-batch publication includes hit sounds sent just after the death/health packet.
        for (var entry : endings.entrySet()) {
            tracker.end(entry.getKey(), entry.getValue());
            dragons.remove(entry.getKey());
        }
        endings.clear();
        var scope = config().dragonDebuffScope();
        for (var result : tracker.observations()) {
            if (result.selectionPending) continue;
            boolean shown = result.shownIn(scope);
            if (result.ended && !result.resultAnnounced) {
                result.resultAnnounced = true;
                trace("result " + result.summary(tracker.tick()) + " " + result.details());
                if (shown && config().dragonDebuffChatEnabled()) {
                    Component message = KungMessages.info("Debuff", result.summary(tracker.tick())).copy()
                        .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(KungMessages.highlight(result.details()))));
                    KungMessages.send(client, message);
                }
            }
        }
    }

    public List<Entity> highlightedEntities() {
        if (!isEnabled() || !config().iceSprayHighlightEnabled()
            || !HypixelInstanceTracker.INSTANCE.catacombs()
            || epoch != HypixelInstanceTracker.INSTANCE.instanceEpoch()) return List.of();
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        List<Entity> entities = new ArrayList<>();
        var highlighted = tracker.highlighted();
        for (UUID uuid : highlighted) {
            Entity entity = level.getEntity(uuid);
            String state = entity == null ? "missing" : entity.isRemoved() ? "removed" : !entity.isAlive() ? "dead" : "ready";
            if (!state.equals(highlightStates.put(uuid, state))) {
                trace("ice-highlight target=" + uuid + " state=" + state + " " + describeTarget(entity));
            }
            if (state.equals("ready")) entities.add(entity);
        }
        highlightStates.keySet().removeIf(uuid -> {
            if (highlighted.contains(uuid)) return false;
            trace("ice-highlight target=" + uuid + " state=inactive");
            return true;
        });
        return entities;
    }

    private static String describeTarget(Entity entity) {
        if (entity == null) return "entity=missing";
        return "entity=" + entity.getId() + ":" + entity.getType() + " name=\"" + entity.getName().getString()
            + "\" position=" + entity.position() + " matchPosition=" + McCompat.interpolatedPosition(entity) + " bounds=" + entity.getBoundingBox()
            + (entity instanceof LivingEntity living ? " health=" + living.getHealth() : "");
    }

    static DungeonDebuffTracker.Target matchingTarget(Entity entity) {
        // Packet observations must not compare a new marker with a mob still interpolating behind it.
        Vec3 position = McCompat.interpolatedPosition(entity);
        return new DungeonDebuffTracker.Target(entity.getUUID(), position,
            entity.getBoundingBox().move(position.subtract(entity.position())), entity instanceof EnderDragon);
    }

    static boolean sprayCandidate(EntityType<?> type, UUID uuid, boolean alive) {
        // Hypixel minibosses use NPC Player bodies; real player accounts have version-4 UUIDs.
        return alive && type != McCompat.ARMOR_STAND && (type != McCompat.PLAYER || uuid.version() != 4);
    }

    private void trace(String message) {
        KungDebugRecorder.event("dungeon-debuff", "tick=" + tracker.tick() + " " + message);
    }

    static final class IceMarker {
        final ArmorStand entity;
        final long observedTick;
        int attempts;
        boolean resolved;

        IceMarker(ArmorStand entity, long observedTick) {
            this.entity = entity;
            this.observedTick = observedTick;
        }

        UUID match(Vec3 position, List<DungeonDebuffTracker.Target> candidates, boolean settledBatch) {
            if (resolved) return null;
            UUID target = DungeonDebuffTracker.uniqueTarget(position, candidates);
            // Equipment can precede movement/metadata in this batch. After it settles, do not
            // reinterpret an ambiguous marker because unrelated mobs moved away on later ticks.
            if (target != null || settledBatch && !candidates.isEmpty()) resolved = true;
            return target;
        }
    }
}
