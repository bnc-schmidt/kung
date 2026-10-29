package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorScreen;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.DungeonConfig.DragonPrioSplit;
import com.github.beng420.kung.config.category.DungeonConfig.DragonPrioUnit;
import java.util.List;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.feature.dungeon.M7DragonTracker.Statue;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.github.beng420.kung.runtime.KungFileLayout;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Local arena guidance; only server evidence can confirm a statue result. */
public final class M7DragonFeature extends ConfigurableFeature<DungeonConfig> {
    public static final M7DragonFeature INSTANCE = new M7DragonFeature();
    private final M7DragonTracker tracker = new M7DragonTracker();
    private final Map<UUID, EnderDragon> entities = new LinkedHashMap<>();
    /** Last health and highest health seen per dragon, from its entity data; any dragon, identified or not. */
    private record HpSeen(EnderDragon dragon, float last, float peak) { }
    private final Map<UUID, HpSeen> hpSeen = new java.util.HashMap<>();
    private final Map<Statue, Boolean> statuePresent = new EnumMap<>(Statue.class);
    /** Resolved statues awaiting one shared title; EnumMap dedupes and keeps arena order. */
    private final Map<Statue, M7DragonTracker.Outcome> pendingTitles = new EnumMap<>(Statue.class);
    /** Tick of the last pre-spawn particle burst seen at each anchor. */
    private final Map<Statue, Long> spawnHints = new EnumMap<>(Statue.class);
    /**
     * Dragon Prio: which hint of the run each dragon still waiting to spawn is (1 = Bers split,
     * 2 = Arch split, later = both). A dragon leaves the map the moment it spawns.
     */
    private final Map<Statue, Integer> prioNumbers = new EnumMap<>(Statue.class);
    private int hintCount;
    /** Server tick each dragon was first seen, and its per-tick part positions while being recorded. */
    private final Map<UUID, Long> spawnTicks = new java.util.HashMap<>();
    private final Map<UUID, List<double[]>> timelineRows = new java.util.HashMap<>();
    /** Newest recorded flight per statue, and how many flights the file already holds per statue. */
    private final Map<Statue, M7DragonAim.Timeline> timelines = new EnumMap<>(Statue.class);
    private final Map<Statue, Integer> timelineCounts = new EnumMap<>(Statue.class);
    private boolean timelinesLoaded;
    /** Flights repeat to within a quarter block, so a few per statue are plenty. */
    private static final int MAX_TIMELINES = 5;
    /** Most dragons die within 30 ticks; a prefire only needs the snap to the flight and the second after it. */
    private static final int MIN_TIMELINE_ROWS = 15;
    private static final String TIMELINE_FILE = "m7-dragon-timelines.jsonl";
    /** Flight path per statue, from spawn to the point the dragon left its range. */
    private final Map<Statue, List<Vec3>> trails = new EnumMap<>(Statue.class);
    private final java.util.EnumSet<Statue> trailsClosed = java.util.EnumSet.noneOf(Statue.class);
    private final java.util.Set<UUID> hitboxesRecorded = new java.util.HashSet<>();
    /** Most recent recorded start paths per statue, newest last, capped at MAX_RECORDED_PATHS. */
    private final Map<Statue, java.util.ArrayDeque<List<Vec3>>> recordedPaths = new EnumMap<>(Statue.class);
    private DungeonConfig.DragonPart recordedPathsPart;
    private static final int MAX_RECORDED_PATHS = 5;
    private static final int MAX_TRAIL_POINTS = 256;
    private final List<String> pendingMessages = new ArrayList<>();
    private final ArrayDeque<String> developerSamples = new ArrayDeque<>();
    private final ArrayDeque<String> developerEvents = new ArrayDeque<>();
    private final Map<ParticleType<?>, Long> particleSamples = new LinkedHashMap<>();
    private String lastDeveloperReport = "";
    private long epoch = Long.MIN_VALUE;
    private long witherKingEpoch = Long.MIN_VALUE;
    private long lastSampleTick = Long.MIN_VALUE;
    private long lastWaypointTrace = Long.MIN_VALUE;
    /** Your arrows in flight while a dragon is due or up; each becomes one line of SHOT_FILE. */
    private final List<ShotTrack> shots = new ArrayList<>();
    private static final String SHOT_FILE = "m7-dragon-shots.jsonl";

    private M7DragonFeature() { super(config -> config.dungeon); }

    @Override public boolean isEnabled() {
        return initialized() && (config().m7DragonHelperEnabled() || config().devDragonDiagnosticsEnabled());
    }

    @Override protected void onInitialize() {
        M7DragonRenderer.initialize();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath("kung", "dragon_prio"), (graphics, delta) -> renderPrioHud(graphics));
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath("kung", "dragon_hp"),
            (graphics, delta) -> renderHp(graphics, delta.getGameTimeDeltaPartialTick(false)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onReset());
        DungeonServerTickEvents.register(() -> { if (ready()) publish(tracker.advance()); });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            observeMessage(message.getString(), overlay);
            return true;
        });
    }

    @Override protected void onReset() {
        clearObservations();
        epoch = Long.MIN_VALUE;
        witherKingEpoch = Long.MIN_VALUE;
    }

    @Override protected void onShutdown() { onReset(); }

    private void clearObservations() {
        spawnHints.clear();
        prioNumbers.clear();
        hintCount = 0;
        hitboxesRecorded.clear();
        trails.clear();
        trailsClosed.clear();
        if (!KungDeveloperAccess.allowed()) lastDeveloperReport = "";
        else if (!developerSamples.isEmpty()) lastDeveloperReport = currentDeveloperReport();
        tracker.reset();
        entities.clear();
        hpSeen.clear();
        statuePresent.clear();
        pendingMessages.clear();
        developerSamples.clear();
        developerEvents.clear();
        lastSampleTick = Long.MIN_VALUE;
        particleSamples.clear();
        spawnTicks.clear();
        timelineRows.clear();
        shots.clear();
        spamPlans.clear();
    }

    private void synchronizeEpoch() {
        long current = HypixelInstanceTracker.INSTANCE.instanceEpoch();
        if (epoch == current) return;
        clearObservations();
        epoch = current;
        witherKingEpoch = Long.MIN_VALUE;
    }

    static boolean contextAllowed(boolean catacombs, HypixelDungeonFloor floor, boolean witherKing) {
        return catacombs && (floor.known() ? floor.floor() == 7 && floor.masterMode() : witherKing);
    }

    private boolean ready() {
        synchronizeEpoch();
        var instance = HypixelInstanceTracker.INSTANCE;
        if (!isEnabled() || Minecraft.getInstance().level == null
            || !contextAllowed(instance.catacombs(), instance.dungeonFloor(), witherKingEpoch == epoch)) {
            clearObservations();
            return false;
        }
        if (!config().devDragonDiagnosticsEnabled()) {
            if (KungDeveloperAccess.allowed() && !developerSamples.isEmpty()) lastDeveloperReport = currentDeveloperReport();
            developerSamples.clear();
            developerEvents.clear();
            particleSamples.clear();
            if (!KungDeveloperAccess.allowed()) lastDeveloperReport = "";
        }
        return true;
    }

    private void observeMessage(String message, boolean overlay) {
        if (overlay) return;
        synchronizeEpoch();
        String clean = DungeonLifecycleSignals.clean(message);
        if (!HypixelInstanceTracker.INSTANCE.catacombs() || !clean.startsWith("[BOSS] Wither King:")) return;
        witherKingEpoch = epoch;
        if (!ready()) return;
        // Resolve after the packet batch so a chat packet preceding death metadata is not misassigned.
        if (pendingMessages.size() < 16) pendingMessages.add(clean);
        trace("boss-message " + clean);
    }

    public void observeSpawn(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
            observeArrow(arrow);
            return;
        }
        if (!(entity instanceof EnderDragon dragon) || !ready()) return;
        var previous = tracker.attempt(entity.getUUID());
        Statue statue = previous == null ? Statue.atSpawn(entity.position()) : previous.statue();
        if (statue == null) {
            sample("unidentified-spawn id=" + entity.getId() + " position=" + entity.position());
            return;
        }
        // Handler time, not next tick: the Dragon Prio number and line end with the very frame the dragon exists.
        if (previous == null) prioNumbers.remove(statue);
        publish(tracker.spawn(entity.getUUID(), statue, entity.position()));
        if (tracker.attempt(entity.getUUID()) == null) return;
        entities.put(entity.getUUID(), dragon);
        if (previous == null) {
            spawnTicks.put(entity.getUUID(), tracker.tick());
            services().dungeonStateTracker().splitTracker().dragonSpawned();
        }
        // A re-sent entity (chunk reload, re-track) is a position update, not a spawn: logging it
        // as "spawn" made mid-flight coordinates look like imprecise spawn anchors.
        trace((previous == null ? "spawn " : "reobserve ") + statue.label()
            + " id=" + entity.getId() + " uuid=" + entity.getUUID()
            + " position=" + entity.position());
    }

    public void observeData(Entity entity) {
        if (!(entity instanceof EnderDragon dragon)) return;
        if (dragon.getHealth() <= 0) observeDeath(entity);
        else noteHealth(dragon);
    }

    /**
     * Remembers the health the server just set, so Dragon HP can colour by what is left, and logs
     * every change with the server tick: that series shows how often Hypixel really updates it.
     */
    private void noteHealth(EnderDragon dragon) {
        float health = dragon.getHealth();
        if (!config().dragonHpEnabled() || !M7DragonHp.known(health) || !ready()) return;
        UUID uuid = dragon.getUUID();
        HpSeen seen = hpSeen.get(uuid);
        if (seen != null && seen.last() == health) return;
        hpSeen.put(uuid, new HpSeen(dragon, health, seen == null ? health : Math.max(seen.peak(), health)));
        var attempt = tracker.attempt(uuid);
        KungDebugRecorder.event("m7-dragon", "hp " + (attempt == null ? "unidentified" : attempt.statue().label())
            + " uuid=" + uuid + " tick=" + tracker.tick() + " hp=" + health);
    }

    public void observeDeath(Entity entity) {
        if (!(entity instanceof EnderDragon) || !ready()) return;
        var attempt = tracker.attempt(entity.getUUID());
        if (attempt == null) {
            if (tracker.unknownDeath(entity.getUUID())) trace("unidentified-death uuid=" + entity.getUUID()
                + " position=" + DungeonDebuffFeature.matchingPosition(entity));
            return;
        }
        if (attempt.dead()) return;
        tracker.move(entity.getUUID(), DungeonDebuffFeature.matchingPosition(entity));
        tracker.death(entity.getUUID());
        trace("death " + attempt.statue().label() + " uuid=" + entity.getUUID()
            + " position=" + attempt.position() + " estimatedInside=" + attempt.statue().contains(attempt.position()));
    }

    private void tick(Minecraft client) {
        if (!ready()) return;
        hpSeen.values().removeIf(seen -> seen.dragon().isRemoved());
        var iterator = entities.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            EnderDragon dragon = entry.getValue();
            if (dragon.getHealth() <= 0) {
                observeDeath(dragon);
                // The death animation lifts the corpse straight up ~15 blocks in place. That is not
                // flight and it decided nothing, so the path ends at 0 HP, not at the range exit.
                closeTrail(entry.getKey());
                flushTimeline(entry.getKey());
            }
            if (dragon.isRemoved()) {
                // A dragon that dies inside its range never trips the exit condition, so its path
                // was silently dropped. Flush on removal too - three of five went missing this way.
                closeTrail(entry.getKey());
                flushTimeline(entry.getKey());
                tracker.unload(entry.getKey());
                iterator.remove();
                continue;
            }
            tracker.move(entry.getKey(), DungeonDebuffFeature.matchingPosition(dragon));
            recordTimeline(entry.getKey(), dragon);
            recordTrail(dragon);
            recordHitbox(dragon);
        }
        // Five fixed blocks, only while the arena is loaded; never request chunks or scan structures.
        if (inArena(client)) for (Statue statue : Statue.values()) {
            if (!client.level.hasChunkAt(statue.statueBlock())) continue;
            boolean present = !client.level.getBlockState(statue.statueBlock()).isAir();
            Boolean previous = statuePresent.put(statue, present);
            if (previous != null && previous && !present) {
                trace("statue-broken " + statue.label() + " block=" + statue.statueBlock());
                publish(tracker.statueBroken(statue));
            }
        }
        for (String message : pendingMessages) publish(tracker.confirmMessage(message));
        pendingMessages.clear();
        trackShots(client);
        updateSpamPlans(client);
        if (config().devDragonDiagnosticsEnabled()
            && DungeonScanSchedule.due(tracker.tick(), lastSampleTick, 5)) {
            lastSampleTick = tracker.tick();
            capturePositions();
        }
        flushTitles(client);
    }

    /** One title per tick for every statue that resolved in it, so a double kill shows both. */
    private void flushTitles(Minecraft client) {
        if (pendingTitles.isEmpty()) return;
        if (client.gui == null) {
            pendingTitles.clear();
            return;
        }
        MutableComponent line = Component.empty();
        boolean first = true;
        for (var entry : pendingTitles.entrySet()) {
            if (!first) line.append(Component.literal("   "));
            first = false;
            var outcome = entry.getValue();
            line.append(Component.literal(entry.getKey().label()).withColor(entry.getKey().color() & 0xFFFFFF))
                .append(switch (outcome) {
                    case COUNTS -> Component.literal(" counts").withColor(0x55FF55);
                    case MISSED -> Component.literal(" missed").withColor(0xFF5555);
                    case UNKNOWN -> Component.literal(" unsure").withColor(0xFFAA00);
                });
        }
        pendingTitles.clear();
        // Subtitle slot rather than title: it draws at half the scale.
        McCompat.setTitleTimes(client, 0, 30, 5);
        McCompat.setTitle(client, Component.empty());
        McCompat.setSubtitle(client, line);
    }

    private static boolean inArena(Minecraft client) {
        if (client.player == null || !HypixelInstanceTracker.INSTANCE.positionKnown()) return false;
        Vec3 position = client.player.position();
        return position.x >= 0 && position.x <= 120 && position.y >= 0 && position.y < 45
            && position.z >= 25 && position.z <= 150;
    }

    List<M7DragonRenderer.Marker> renderMarkers(float partialTick) {
        if (!ready()) return List.of();
        Minecraft client = Minecraft.getInstance();
        if (McCompat.hudHidden(client) || !inArena(client)) return List.of();
        List<M7DragonRenderer.Marker> markers = new ArrayList<>();
        for (Statue statue : Statue.values()) {
            if (config().m7DragonHelperEnabled() && config().dragonSpawnMarkersEnabled()) {
                if (config().dragonMarkerMode() == DungeonConfig.DragonMarkerMode.SKELETON) {
                    addSkeleton(markers, statue, partialTick);
                } else {
                    for (DungeonConfig.DragonPart part : DungeonConfig.DragonPart.values()) {
                        if (config().dragonCorePart(part)) {
                            markers.add(point(corePoint(statue, part, partialTick), statue.color(), spawning(statue)));
                        }
                    }
                }
            }
            if (config().m7DragonHelperEnabled() && config().dragonStatueBoxesEnabled() && !tracker.statueCounted(statue)) {
                var attempt = tracker.latest(statue);
                boolean live = attempt != null && !attempt.dead() && attempt.loaded();
                // Edges name the statue; the fill, only while its dragon is up, says whether a kill right
                // now would count. 12% per face, since you often look through two faces of a box this big.
                markers.add(new M7DragonRenderer.Marker(statue.range(), statue.color() | 0xFF000000, 2.0F));
                if (live) markers.add(new M7DragonRenderer.Marker(statue.range(),
                    statue.contains(attempt.position()) ? 0x2055FF55 : 0x20FF5555, 0, true));
            }
        }
        if (config().m7DragonHelperEnabled()) addAimMarkers(markers, client, partialTick);
        if (config().devDragonDiagnosticsEnabled()) for (EnderDragon dragon : entities.values()) {
            if (dragon.isRemoved() || !dragon.isAlive()) continue;
            // White: received origin used by the estimate; yellow: interpolated body-box center.
            markers.add(point(DungeonDebuffFeature.matchingPosition(dragon), 0xFFFFFFFF));
            markers.add(point(dragon.getBoundingBox().getCenter()
                .add(dragon.getPosition(partialTick).subtract(dragon.position())), 0xFFFFFF55));
        }
        return markers;
    }

    /** Box Centre is the classic waypoint; every other part rides its own hitbox, or its spawn pose before the dragon exists. */
    private Vec3 corePoint(Statue statue, DungeonConfig.DragonPart part, float partialTick) {
        if (part == DungeonConfig.DragonPart.BOX) return waypoint(statue, partialTick);
        EnderDragon dragon = liveDragon(statue);
        if (dragon == null) {
            double[] pose = SPAWN_PARTS[part.part()];
            return statue.spawn().add(pose[0] + pose[3] / 2, pose[1] + pose[4] / 2, pose[2] + pose[5] / 2);
        }
        return trailPoint(dragon, part).add(dragon.getPosition(partialTick).subtract(dragon.position()));
    }

    /** Waypoint rests on the spawn anchor until that statue's dragon is in the air, then rides it. */
    private Vec3 waypoint(Statue statue, float partialTick) {
        var attempt = tracker.latest(statue);
        if (attempt == null || attempt.dead()) return spawnCentre(statue);
        EnderDragon dragon = entities.get(attempt.uuid());
        if (dragon == null || dragon.isRemoved() || !dragon.isAlive()) return spawnCentre(statue);
        // getPosition is the entity origin, which sits at the dragon's feet. Ride the body-box
        // centre instead, carrying over the interpolation delta so it does not stutter.
        Vec3 centre = dragonCentre(dragon)
            .add(dragon.getPosition(partialTick).subtract(dragon.position()));
        if (DungeonScanSchedule.due(tracker.tick(), lastWaypointTrace, 40)) {
            lastWaypointTrace = tracker.tick();
            trace("waypoint " + statue.label() + " origin=" + dragon.position()
                + " box=" + dragon.getBoundingBox() + " drawn=" + centre);
        }
        return centre;
    }

    /**
     * Extends the flight path while the dragon's origin is still inside its statue range, then
     * adds the first outside point and stops. Points are kept at least half a block apart, which
     * bounds the list without a timer - a dragon crossing a 27-block range leaves a few dozen.
     */
    /**
     * Dumps every part hitbox of a dragon once, as origin-relative x/y/z plus width/height/depth.
     * The body is the part you want the core inside, and its centre follows from its own box -
     * no eyeballing, no slider. One line per dragon per run.
     */
    private void recordHitbox(EnderDragon dragon) {
        if (!KungDeveloperAccess.allowed() || !hitboxesRecorded.add(dragon.getUUID())) return;
        var attempt = tracker.attempt(dragon.getUUID());
        if (attempt == null) return;
        Vec3 origin = dragon.position();
        StringBuilder line = new StringBuilder("{\"statue\":\"").append(attempt.statue().name())
            .append("\",\"tick\":").append(tracker.tick()).append(",\"parts\":[");
        int index = 0;
        for (var part : dragon.getSubEntities()) {
            AABB box = part.getBoundingBox();
            if (index > 0) line.append(',');
            line.append("{\"i\":").append(index++)
                .append(",\"x\":").append(round(box.minX - origin.x))
                .append(",\"y\":").append(round(box.minY - origin.y))
                .append(",\"z\":").append(round(box.minZ - origin.z))
                .append(",\"w\":").append(round(box.getXsize()))
                .append(",\"h\":").append(round(box.getYsize()))
                .append(",\"d\":").append(round(box.getZsize())).append('}');
        }
        AABB own = dragon.getBoundingBox();
        line.append("],\"own\":{\"y\":").append(round(own.minY - origin.y))
            .append(",\"h\":").append(round(own.getYsize())).append("}}");
        appendLine("m7-dragon-hitboxes.jsonl", line.toString());
    }

    private void closeTrail(UUID uuid) {
        var attempt = tracker.attempt(uuid);
        if (attempt == null || !trailsClosed.add(attempt.statue())) return;
        recordPath(attempt.statue(), trails.getOrDefault(attempt.statue(), List.of()));
    }

    /**
     * Where the core marker rides. Part hitboxes describe collision; this box is what lines up
     * with the model. The flight path follows the configured part instead (trailPoint).
     */
    /**
     * Where dragonCentre will be the moment this statue's dragon spawns: its box sits on the
     * anchor and is centred horizontally, so the centre is half the dragon's height up. Without
     * this the resting core sat on the raw anchor - four blocks below the first trail point.
     */
    static Vec3 spawnCentre(Statue statue) {
        return statue.spawn().add(0, McCompat.ENDER_DRAGON.getHeight() / 2.0, 0);
    }

    static Vec3 dragonCentre(EnderDragon dragon) {
        return dragon.getBoundingBox().getCenter();
    }

    static Vec3 trailPoint(EnderDragon dragon, DungeonConfig.DragonPart part) {
        var parts = dragon.getSubEntities();
        return part.part() >= 0 && part.part() < parts.length
            ? parts[part.part()].getBoundingBox().getCenter() : dragonCentre(dragon);
    }

    /** Box Centre keeps the original file, so paths recorded before the switch stay usable. */
    static String pathsFile(DungeonConfig.DragonPart part) {
        return part == DungeonConfig.DragonPart.BOX ? "m7-dragon-paths.jsonl"
            : "m7-dragon-paths-" + part.name().toLowerCase(java.util.Locale.ROOT) + ".jsonl";
    }

    /** One row per server tick for the first TIMELINE_TICKS after spawn: every part, anchor-relative. */
    private void recordTimeline(UUID uuid, EnderDragon dragon) {
        Long spawn = spawnTicks.get(uuid);
        var attempt = tracker.attempt(uuid);
        if (spawn == null || attempt == null || attempt.dead()) return;
        long offset = tracker.tick() - spawn;
        if (offset >= M7DragonAim.TIMELINE_TICKS) {
            flushTimeline(uuid);
            return;
        }
        List<double[]> rows = timelineRows.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        // Two client ticks can share a server tick; the first one wins.
        if (!rows.isEmpty() && rows.getLast()[0] >= offset) return;
        // Parts are simulated from the smoothed body; shift them onto the last server position.
        Vec3 lag = DungeonDebuffFeature.matchingPosition(dragon).subtract(dragon.position());
        var parts = DungeonConfig.DragonPart.values();
        double[] row = new double[1 + parts.length * 3];
        row[0] = offset;
        for (var part : parts) {
            Vec3 at = trailPoint(dragon, part).add(lag).subtract(attempt.statue().spawn());
            row[1 + part.ordinal() * 3] = at.x;
            row[2 + part.ordinal() * 3] = at.y;
            row[3 + part.ordinal() * 3] = at.z;
        }
        rows.add(row);
    }

    // ponytail: newest flight wins and the aim holds still past its last row; keep the longest per statue if that bites.
    private void flushTimeline(UUID uuid) {
        Long spawn = spawnTicks.remove(uuid);
        List<double[]> rows = timelineRows.remove(uuid);
        var attempt = tracker.attempt(uuid);
        if (spawn == null || rows == null || rows.size() < MIN_TIMELINE_ROWS || attempt == null) return;
        loadTimelines();
        Statue statue = attempt.statue();
        Long hint = spawnHints.get(statue);
        int hintTicks = hint == null || spawn - hint <= 0 || spawn - hint > 300 ? -1 : (int) (spawn - hint);
        var timeline = new M7DragonAim.Timeline(statue, hintTicks, List.copyOf(rows));
        timelines.put(statue, timeline);
        trace("timeline " + statue.label() + " rows=" + rows.size() + " hint=" + hintTicks);
        if (timelineCounts.merge(statue, 1, Integer::sum) <= MAX_TIMELINES) appendLine(TIMELINE_FILE, M7DragonAim.line(timeline));
    }

    private void loadTimelines() {
        if (timelinesLoaded) return;
        timelinesLoaded = true;
        Minecraft client = Minecraft.getInstance();
        Path gameDirectory = client == null ? Path.of(".") : client.gameDirectory.toPath();
        Path file = new KungFileLayout(gameDirectory, gameDirectory.resolve("config"))
            .dungeonDataDirectory().resolve(TIMELINE_FILE);
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var timeline = M7DragonAim.parse(line);
                if (timeline == null) continue;
                timelines.put(timeline.statue(), timeline);
                timelineCounts.merge(timeline.statue(), 1, Integer::sum);
            }
        } catch (IOException exception) {
            trace("timeline-load-failed " + exception.getMessage());
        }
    }

    /** Terminator for Archer/Berserker, Last Breath for everyone else, unless picked by hand. Developer only. */
    private DungeonConfig.DragonAimMode aimMode() {
        if (!KungDeveloperAccess.allowed()) return DungeonConfig.DragonAimMode.OFF;
        var mode = config().dragonAimMode();
        if (mode != DungeonConfig.DragonAimMode.AUTO) return mode;
        var selfClass = services().dungeonStateTracker().runStats().selfDungeonClass();
        return selfClass == DungeonRunStats.DungeonClass.ARCHER || selfClass == DungeonRunStats.DungeonClass.BERSERKER
            ? DungeonConfig.DragonAimMode.TERMINATOR : DungeonConfig.DragonAimMode.LAST_BREATH;
    }

    /** Ticks since this statue's dragon spawned, negative while its burst counts down; null outside the aim window. */
    private Double ticksSinceSpawn(Statue statue) {
        var attempt = tracker.latest(statue);
        Long spawn = attempt == null || attempt.dead() ? null : spawnTicks.get(attempt.uuid());
        if (spawn != null) return (double) (tracker.tick() - spawn);
        Long hint = spawnHints.get(statue);
        if (hint == null) return null;
        long sinceHint = tracker.tick() - hint;
        // Up to a second late: the burst-to-spawn gap varies by a few ticks between spawns.
        return sinceHint <= M7DragonAim.HINT_TO_SPAWN_TICKS + 20 ? (double) (sinceHint - M7DragonAim.HINT_TO_SPAWN_TICKS) : null;
    }

    private void addAimMarkers(List<M7DragonRenderer.Marker> markers, Minecraft client, float partialTick) {
        var mode = aimMode();
        if (mode == DungeonConfig.DragonAimMode.OFF || client.player == null) return;
        loadTimelines();
        Vec3 eye = client.player.getEyePosition(partialTick);
        for (Statue statue : Statue.values()) {
            Double since = ticksSinceSpawn(statue);
            if (since == null) continue;
            var timeline = timelines.get(statue);
            if (mode == DungeonConfig.DragonAimMode.LAST_BREATH) {
                var plan = spamPlans.get(statue);
                if (plan != null) addSpamMarkers(markers, plan, client, eye);
                continue;
            }
            // The neck: every dragon spawns facing one way and snaps to its flight within a few ticks,
            // and the recorded flight holds the snapped neck.
            // ponytail: without a recorded flight there is no snap or lead - aim at the neck where it
            // spawns, then where it is. One dragon alive for MIN_TIMELINE_ROWS ticks records one.
            Vec3 neck = timeline == null ? corePoint(statue, DungeonConfig.DragonPart.NECK, partialTick) : null;
            java.util.function.DoubleFunction<Vec3> neckAt =
                tick -> neck != null ? neck : timeline.at(tick, DungeonConfig.DragonPart.NECK);
            var aim = M7DragonAim.aim(eye, neckAt, since, M7DragonAim.ARROW_SPEED);
            if (aim == null) continue;
            // The marker grows a second before spawn, the moment to start running in.
            boolean runIn = since < 0 && since >= -20;
            // Gray lands before the spawn, white inside the hit window, red after it.
            int color = aim.arrivalTick() < 0 ? 0xFF888888
                : aim.arrivalTick() <= M7DragonAim.HIT_WINDOW_TICKS ? 0xFFFFFFFF : 0xFFFF5555;
            markers.add(point(aim.point(), color, runIn));
        }
    }

    /**
     * Last Breath is spammed from under the dragon, hitting on the way up and on the way down.
     * Per statue: where the body will be, the draws that get there from where you stand, and the aim
     * for the middle one. Refreshed every client tick; the marker only rides your eye in between.
     */
    record SpamPlan(Vec3 body, int min, int max, int draw, Vec3 direction) { }
    private final Map<Statue, SpamPlan> spamPlans = new EnumMap<>(Statue.class);

    private void updateSpamPlans(Minecraft client) {
        spamPlans.clear();
        var misc = com.github.beng420.kung.config.KungConfig.get().misc;
        // The Bow Draw bar's M7 Dragon Ticks need the plans without the aim markers.
        boolean bar = misc.bowDrawIndicatorEnabled() && misc.bowDrawDragonTicksEnabled();
        if (client.player == null || !inArena(client) || !config().m7DragonHelperEnabled()
            || !bar && aimMode() != DungeonConfig.DragonAimMode.LAST_BREATH) return;
        loadTimelines();
        Vec3 eye = client.player.getEyePosition();
        double nearest = Double.MAX_VALUE;
        for (Statue statue : Statue.values()) {
            Double since = ticksSinceSpawn(statue);
            if (since == null || since > M7DragonAim.HIT_WINDOW_TICKS) continue;
            var timeline = timelines.get(statue);
            // ponytail: without a recorded flight, the body where it is (spawn pose until it spawns).
            Vec3 body = timeline == null ? corePoint(statue, DungeonConfig.DragonPart.BODY, 1) : M7DragonAim.standSpot(timeline);
            SpamPlan plan = plan(client, eye, body);
            spamPlans.put(statue, plan);
            DrawHint hint = new DrawHint(plan.min(), plan.max(), plan.draw());
            lastHints.put(statue, hint);
            // Two dragons can spawn at once; the bar follows the one whose square is nearest.
            double distance = Math.hypot(body.x - eye.x, body.z - eye.z);
            if (distance < nearest) {
                nearest = distance;
                drawHint = plan.draw() < 0 ? null : hint;
                drawHintNanos = System.nanoTime();
            }
        }
    }

    /**
     * Flies every draw from 3 to 20 ticks at the body: too weak ones never get there, too strong ones
     * hit a block on the way (the statue you stand under). The clean ones are the range; aim for its middle.
     */
    private static SpamPlan plan(Minecraft client, Vec3 eye, Vec3 body) {
        int min = -1;
        int max = -1;
        Vec3[] directions = new Vec3[M7DragonAim.FULL_DRAW_TICKS + 1];
        for (int ticks = M7DragonAim.MIN_SHOT_TICKS; ticks <= M7DragonAim.FULL_DRAW_TICKS; ticks++) {
            double speed = M7DragonAim.drawSpeed(ticks);
            var shot = M7DragonAim.lobShot(eye, body, speed);
            if (shot == null) continue;
            if (!clear(client, eye, shot, speed)) {
                if (min >= 0) break;
                continue;
            }
            if (min < 0) min = ticks;
            max = ticks;
            directions[ticks] = shot.direction();
        }
        int draw = min < 0 ? -1 : (min + max) / 2;
        return new SpamPlan(body, min, max, draw, draw < 0 ? null : directions[draw]);
    }

    /** Flies the shot tick by tick, a quarter tick at a time, and fails on the first block with a collision box. */
    private static boolean clear(Minecraft client, Vec3 eye, M7DragonAim.Shot shot, double speed) {
        Vec3 at = eye;
        Vec3 velocity = shot.direction().scale(speed);
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int tick = 0; tick < shot.ticks(); tick++) {
            for (int quarter = 1; quarter <= 4; quarter++) {
                pos.set(at.x + velocity.x * quarter / 4, at.y + velocity.y * quarter / 4, at.z + velocity.z * quarter / 4);
                if (!client.level.getBlockState(pos).getCollisionShape(client.level, pos).isEmpty()) return false;
            }
            at = at.add(velocity);
            velocity = velocity.scale(0.99).subtract(0, 0.05, 0);
        }
        return true;
    }

    /**
     * A floor square under where the body will be (stand there; orange when nothing gets there cleanly
     * from where you are), and the aim dot for the middle draw, ten blocks out.
     */
    private void addSpamMarkers(List<M7DragonRenderer.Marker> markers, SpamPlan plan, Minecraft client, Vec3 eye) {
        Vec3 body = plan.body();
        double floor = Math.floor(client.player.getY() + 0.01);
        markers.add(new M7DragonRenderer.Marker(new AABB(body.x - 0.5, floor + 0.02, body.z - 0.5,
            body.x + 0.5, floor + 0.08, body.z + 0.5), plan.draw() < 0 ? 0xB0FFAA00 : 0xB0FFFFFF, 0, true));
        if (plan.direction() != null) {
            markers.add(point(eye.add(plan.direction().scale(M7DragonAim.LOB_MARKER_DISTANCE)), 0xFFFFFFFF, true));
        }
    }

    /** Draw ticks for the Bow Draw bar: min reaches the body, max still misses every block, draw is the aim's. */
    public record DrawHint(int min, int max, int draw) { }
    private DrawHint drawHint;
    private long drawHintNanos;
    /** Per statue, the range from where you stood at its last spawn; kept across runs for the menu. */
    private final Map<Statue, DrawHint> lastHints = new EnumMap<>(Statue.class);

    /** One line per dragon for the Bow Draw Indicator menu. */
    public List<String> drawRangeLines() {
        List<String> lines = new ArrayList<>();
        for (Statue statue : Statue.values()) {
            DrawHint hint = lastHints.get(statue);
            lines.add(statue.label() + ": " + (hint == null ? "no spawn seen this session"
                : hint.draw() < 0 ? "nothing got there cleanly from where you stood"
                : hint.min() + "-" + hint.max() + " ticks, aim drawn at " + hint.draw()));
        }
        return lines;
    }

    /** The hint while the spam markers are drawn, null a moment after they stop. */
    public DrawHint drawHint() {
        return System.nanoTime() - drawHintNanos < 200_000_000L ? drawHint : null;
    }

    /** One of your arrows, from the packet that spawned it to the tick it hit, stuck or vanished. */
    private static final class ShotTrack {
        final net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow;
        final long fireTick;
        final Vec3 feet;
        final Vec3 eye;
        final int draw;
        final String mode;
        final String dungeonClass;
        final Map<Statue, Double> since;
        Vec3 velocity;
        Vec3 last;
        int still;
        double closest = Double.MAX_VALUE;
        String closestAt = "null";
        double nearestNow = Double.MAX_VALUE;

        ShotTrack(net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow, long fireTick, Vec3 feet, Vec3 eye,
                  int draw, String mode, String dungeonClass, Map<Statue, Double> since) {
            this.arrow = arrow;
            this.fireTick = fireTick;
            this.feet = feet;
            this.eye = eye;
            this.draw = draw;
            this.mode = mode;
            this.dungeonClass = dungeonClass;
            this.since = since;
            this.velocity = arrow.getDeltaMovement();
        }
    }

    /**
     * Logs your arrows around each spawn - where you stood, how long you drew, when you fired and
     * where the arrow ended - so the spot, draw and timing that hit can be read off many runs.
     * Yours: it appears at your eye (Hypixel spawns it 0.1 below) and nobody else owns it.
     */
    private void observeArrow(net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
        // The same arrow can be added twice (every horizontal shot of the last run was logged twice).
        if (!KungDeveloperAccess.allowed() || !ready() || shots.size() >= 64
            || shots.stream().anyMatch(shot -> shot.arrow == arrow)) return;
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || !inArena(client) || arrow.getOwner() != null && arrow.getOwner() != player
            || arrow.position().distanceTo(player.getEyePosition()) > 2.5) return;
        Map<Statue, Double> since = new EnumMap<>(Statue.class);
        for (Statue statue : Statue.values()) {
            Double ticks = ticksSinceSpawn(statue);
            if (ticks != null) since.put(statue, ticks);
        }
        if (since.isEmpty()) return;
        // The draw this arrow came from is the last one released - while spamming the next is already running.
        // ponytail: -1 with the Bow Draw HUD switched off; its counter survives Hypixel rewriting the held bow.
        shots.add(new ShotTrack(arrow, tracker.tick(), player.position(), player.getEyePosition(),
            com.github.beng420.kung.feature.misc.BowDrawIndicatorFeature.INSTANCE.releasedTicks(), aimMode().name(),
            String.valueOf(services().dungeonStateTracker().runStats().selfDungeonClass()), since));
    }

    private void trackShots(Minecraft client) {
        var iterator = shots.iterator();
        while (iterator.hasNext()) {
            ShotTrack shot = iterator.next();
            long flight = tracker.tick() - shot.fireTick;
            Vec3 at = shot.arrow.position();
            // The spawn packet may leave the velocity to a motion packet a tick later.
            if (shot.velocity.lengthSqr() < 0.01) shot.velocity = shot.arrow.getDeltaMovement();
            if (shot.arrow.isRemoved()) {
                // An arrow that hits a dragon is removed; a tick earlier it was at most one step from a part.
                finishShot(shot, shot.nearestNow <= 3.5 ? "HIT" : "GONE", flight, shot.last == null ? at : shot.last);
                iterator.remove();
                continue;
            }
            shot.nearestNow = Double.MAX_VALUE;
            for (EnderDragon dragon : entities.values()) {
                var attempt = tracker.attempt(dragon.getUUID());
                if (attempt == null || attempt.dead() || !dragon.isAlive()) continue;
                var parts = dragon.getSubEntities();
                for (var part : DungeonConfig.DragonPart.values()) {
                    if (part.part() < 0 || part.part() >= parts.length) continue;
                    double distance = Math.sqrt(parts[part.part()].getBoundingBox().distanceToSqr(at));
                    shot.nearestNow = Math.min(shot.nearestNow, distance);
                    if (distance >= shot.closest) continue;
                    shot.closest = distance;
                    shot.closestAt = "{\"statue\":\"" + attempt.statue().name() + "\",\"part\":\"" + part.name()
                        + "\",\"distance\":" + round(distance) + ",\"flight\":" + flight + ",\"at\":" + xyz(at) + "}";
                }
            }
            shot.still = shot.last != null && at.distanceToSqr(shot.last) < 1e-4 ? shot.still + 1 : 0;
            shot.last = at;
            // Stuck in a block - the statue overhead, the floor - or never coming down.
            if (shot.still >= 3 || flight > 200) {
                finishShot(shot, shot.still >= 3 ? "STUCK" : "TIMEOUT", flight, at);
                iterator.remove();
            }
        }
    }

    // ponytail: one file append per arrow on the client thread; buffer per wave if Terminator spam shows in frame times.
    private void finishShot(ShotTrack shot, String end, long flight, Vec3 at) {
        StringBuilder since = new StringBuilder("{");
        shot.since.forEach((statue, ticks) -> since.append(since.length() > 1 ? "," : "")
            .append('"').append(statue.name()).append("\":").append(round(ticks)));
        appendLine(SHOT_FILE, "{\"tick\":" + shot.fireTick + ",\"mode\":\"" + shot.mode
            + "\",\"class\":\"" + shot.dungeonClass + "\",\"draw\":" + shot.draw
            + ",\"velocity\":" + xyz(shot.velocity) + ",\"feet\":" + xyz(shot.feet) + ",\"eye\":" + xyz(shot.eye)
            + ",\"since\":" + since.append('}') + ",\"closest\":" + shot.closestAt
            + ",\"end\":{\"reason\":\"" + end + "\",\"flight\":" + flight + ",\"at\":" + xyz(at) + "}}");
    }

    private static String xyz(Vec3 vector) {
        return "[" + round(vector.x) + "," + round(vector.y) + "," + round(vector.z) + "]";
    }

    private void recordTrail(EnderDragon dragon) {
        loadRecordedPaths();
        var attempt = tracker.attempt(dragon.getUUID());
        if (attempt == null) return;
        Statue statue = attempt.statue();
        // Purple teleports between random points; a line through them is no path.
        if (statue == Statue.PURPLE || trailsClosed.contains(statue)) return;
        List<Vec3> points = trails.computeIfAbsent(statue, ignored -> new ArrayList<>());
        if (extendTrail(points, trailPoint(dragon, config().dragonTrailPart()), statue.contains(dragon.position()))) {
            trailsClosed.add(statue);
            recordPath(statue, points);
        }
    }

    /**
     * Appends one path point and reports whether the trail is finished. The exit point is appended
     * before closing, so the line reaches the range boundary instead of stopping short of it.
     * Returns false for a point too close to the previous one to be worth keeping.
     */
    static boolean extendTrail(List<Vec3> points, Vec3 centre, boolean insideRange) {
        if (!points.isEmpty() && points.get(points.size() - 1).distanceToSqr(centre) < 0.25) return false;
        points.add(centre);
        return !insideRange || points.size() >= MAX_TRAIL_POINTS;
    }

    /**
     * Writes one finished path per dragon. Offsets are relative to the spawn anchor so two runs can
     * be compared directly; the whole point of the file is to answer whether the paths repeat at
     * all. If they do not, delete the file and this method with it.
     *
     * <p>Roughly 60 points per dragon at the 0.5-block spacing, so about 10 KB per run.
     */
    private void recordPath(Statue statue, List<Vec3> points) {
        if (points.size() < 2) return;
        StringBuilder line = new StringBuilder("{\"tick\":").append(tracker.tick())
            .append(",\"statue\":\"").append(statue.name()).append("\",\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            if (i > 0) line.append(',');
            line.append('[').append(round(point.x - statue.spawn().x))
                .append(',').append(round(point.y - statue.spawn().y))
                .append(',').append(round(point.z - statue.spawn().z)).append(']');
        }
        line.append("]}");
        appendLine(pathsFile(config().dragonTrailPart()), line.toString());
        rememberPath(statue, List.copyOf(points));
    }

    private void rememberPath(Statue statue, List<Vec3> absolute) {
        var paths = recordedPaths.computeIfAbsent(statue, ignored -> new java.util.ArrayDeque<>());
        paths.addLast(absolute);
        while (paths.size() > MAX_RECORDED_PATHS) paths.removeFirst();
    }

    /**
     * Reads the recorded start paths once per trail part. Stored as offsets from the spawn anchor,
     * so they are rebuilt against it. Only the newest MAX_RECORDED_PATHS per statue survive: enough
     * to see whether the paths repeat, bounded no matter how much M7 gets played.
     */
    private void loadRecordedPaths() {
        var part = config().dragonTrailPart();
        if (part == recordedPathsPart) return;
        if (recordedPathsPart != null) {
            // Switched mid-run: stop this run's trails so no path mixes two parts in one file.
            trails.clear();
            trailsClosed.addAll(java.util.EnumSet.allOf(Statue.class));
        }
        recordedPathsPart = part;
        recordedPaths.clear();
        Minecraft client = Minecraft.getInstance();
        Path gameDirectory = client == null ? Path.of(".") : client.gameDirectory.toPath();
        Path file = new KungFileLayout(gameDirectory, gameDirectory.resolve("config"))
            .dungeonDataDirectory().resolve(pathsFile(part));
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Statue statue;
                com.google.gson.JsonArray points;
                try {
                    var json = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    statue = Statue.valueOf(json.get("statue").getAsString());
                    points = json.getAsJsonArray("points");
                } catch (RuntimeException malformed) {
                    continue;
                }
                if (statue == Statue.PURPLE) continue;
                List<Vec3> absolute = new ArrayList<>(points.size());
                for (var point : points) {
                    var xyz = point.getAsJsonArray();
                    absolute.add(statue.spawn().add(xyz.get(0).getAsDouble(),
                        xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble()));
                }
                if (absolute.size() >= 2) rememberPath(statue, List.copyOf(absolute));
            }
        } catch (IOException exception) {
            trace("path-load-failed " + exception.getMessage());
        }
    }

    private void appendLine(String fileName, String line) {
        Minecraft client = Minecraft.getInstance();
        Path gameDirectory = client == null ? Path.of(".") : client.gameDirectory.toPath();
        Path file = new KungFileLayout(gameDirectory, gameDirectory.resolve("config"))
            .dungeonDataDirectory().resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException exception) {
            trace("append-failed " + fileName + " " + exception.getMessage());
        }
    }

    /** Two decimals: the sampling step is 0.5 blocks, so more digits are noise. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    List<M7DragonRenderer.Trail> renderTrails() {
        if (!ready()) return List.of();
        Minecraft client = Minecraft.getInstance();
        if (McCompat.hudHidden(client) || !inArena(client)
            || !config().m7DragonHelperEnabled() || !config().dragonFlightPathsEnabled()) {
            return List.of();
        }
        loadRecordedPaths();
        List<M7DragonRenderer.Trail> drawn = new ArrayList<>();
        // Recorded paths first and dimmed, so the live one reads on top of them.
        for (var entry : recordedPaths.entrySet()) {
            int dim = (entry.getKey().color() & 0x00FFFFFF) | 0x80000000;
            for (List<Vec3> path : entry.getValue()) drawn.add(new M7DragonRenderer.Trail(path, dim));
        }
        for (var entry : trails.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            drawn.add(new M7DragonRenderer.Trail(List.copyOf(entry.getValue()),
                entry.getKey().color() | 0xFF000000));
        }
        return drawn;
    }

    /**
     * The spawn pose, measured from 11 dragons: {x, y, z, width, height, depth} per part, relative
     * to the spawn anchor. All five statues produced byte-identical layouts, so one table covers
     * them all - dragons always spawn facing the same way.
     */
    private static final double[][] SPAWN_PARTS = {
        {-0.5, 0.0, -7.0, 1, 1, 1},
        {-1.5, 0.0, -7.0, 3, 3, 3},
        {-2.5, 0.0, -3.0, 5, 3, 5},
        {-1.0, 1.5, 2.5, 2, 2, 2},
        {-1.0, 1.5, 4.5, 2, 2, 2},
        {-1.0, 1.5, 6.5, 2, 2, 2},
        {2.5, 2.0, -2.0, 4, 2, 4},
        {-6.5, 2.0, -2.0, 4, 2, 4},
    };

    /** Live part hitboxes while the dragon exists, the measured spawn pose before it does. */
    private void addSkeleton(List<M7DragonRenderer.Marker> markers, Statue statue, float partialTick) {
        int color = statue.color() | 0xFF000000;
        EnderDragon dragon = liveDragon(statue);
        if (dragon == null) {
            Vec3 anchor = statue.spawn();
            for (double[] part : SPAWN_PARTS) {
                markers.add(new M7DragonRenderer.Marker(new AABB(
                    anchor.x + part[0], anchor.y + part[1], anchor.z + part[2],
                    anchor.x + part[0] + part[3], anchor.y + part[1] + part[4],
                    anchor.z + part[2] + part[5]), color, spawning(statue) ? 3.0F : 1.5F));
            }
            return;
        }
        Vec3 smoothing = dragon.getPosition(partialTick).subtract(dragon.position());
        for (var part : dragon.getSubEntities()) {
            markers.add(new M7DragonRenderer.Marker(
                part.getBoundingBox().move(smoothing), color, 1.5F));
        }
    }

    private EnderDragon liveDragon(Statue statue) {
        var attempt = tracker.latest(statue);
        if (attempt == null || attempt.dead()) return null;
        EnderDragon dragon = entities.get(attempt.uuid());
        return dragon == null || dragon.isRemoved() || !dragon.isAlive() ? null : dragon;
    }

    /** True while the anchor is flashing its pre-spawn burst and no dragon has appeared yet. */
    private boolean spawning(Statue statue) {
        Long hint = spawnHints.get(statue);
        if (hint == null || tracker.tick() - hint > M7DragonAim.HINT_TO_SPAWN_TICKS + 20) return false;
        var attempt = tracker.latest(statue);
        return attempt == null || attempt.dead() || entities.get(attempt.uuid()) == null;
    }

    /**
     * Dragon Prio: the Bers split (Berserker, Mage) takes spawn hint 1, the Arch split (Archer,
     * Healer, Tank) hint 2, and both take every later one. Mage, Healer and Tank pick their split;
     * a class that is not known yet sees everything rather than nothing.
     */
    static boolean prioShown(int hint, DungeonRunStats.DungeonClass self,
                             DragonPrioSplit mage, DragonPrioSplit healer, DragonPrioSplit tank) {
        if (hint >= 3) return true;
        DragonPrioSplit split = switch (self) {
            case ARCHER -> DragonPrioSplit.ARCH;
            case BERSERKER -> DragonPrioSplit.BERS;
            case MAGE -> mage;
            case HEALER -> healer;
            case TANK -> tank;
            case UNKNOWN -> null;
        };
        return split == null || split == (hint == 1 ? DragonPrioSplit.BERS : DragonPrioSplit.ARCH);
    }

    /** Dragons you prio whose burst is running and which have not spawned yet. */
    private List<Statue> prioStatues() {
        if (!ready() || !config().dragonPrioEnabled()) return List.of();
        var self = services().dungeonStateTracker().runStats().selfDungeonClass();
        List<Statue> mine = new ArrayList<>();
        for (var entry : prioNumbers.entrySet()) {
            if (ticksSinceSpawn(entry.getKey()) != null && prioShown(entry.getValue(), self,
                config().dragonPrioMage(), config().dragonPrioHealer(), config().dragonPrioTank())) mine.add(entry.getKey());
        }
        return mine;
    }

    /** Statues to draw a line to this frame; the renderer extracts only these, never a start point. */
    List<Statue> prioLines() {
        return McCompat.hudHidden(Minecraft.getInstance()) ? List.of() : prioStatues();
    }

    /** Asked again by the renderer when it draws, so a dragon that spawned since extraction costs no frame. */
    boolean prioPending(Statue statue) { return prioNumbers.containsKey(statue); }

    private void renderPrioHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!ready() || !config().dragonPrioHudShown() || client.player == null || McCompat.hudHidden(client)
            || McCompat.screen(client) instanceof KungHudEditorScreen || KungHudEditorState.externalEditing()) return;
        MutableComponent text = Component.empty();
        for (Statue statue : prioStatues()) {
            Double since = ticksSinceSpawn(statue);
            // The number ends at 0; the line goes on until the dragon is actually there.
            if (since == null || since > 0) continue;
            if (!text.getSiblings().isEmpty()) text.append(" ");
            text.append(Component.literal(prioText((long) -since, config().dragonPrioUnit())).withColor(statue.color() & 0xFFFFFF));
        }
        if (!text.getSiblings().isEmpty()) drawPrio(graphics, config(), text);
    }

    /**
     * Dragon HP: each dragon's live health as text on its body, projected like the Mimic ESP label.
     * Reads the health field the client already holds; no packets, no entity scan - a float and one
     * projection per dragon, and a dragon only counts once an entity-data packet has named it.
     */
    private void renderHp(GuiGraphicsExtractor graphics, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (!config().dragonHpEnabled() || hpSeen.isEmpty() || !ready() || client.player == null
            || McCompat.hudHidden(client)) return;
        for (HpSeen seen : hpSeen.values()) {
            EnderDragon dragon = seen.dragon();
            float health = dragon.getHealth();
            if (dragon.isRemoved() || !M7DragonHp.known(health)) continue;
            // Same interpolation carry-over as the core markers, or the text steps at 20 Hz.
            Vec3 core = trailPoint(dragon, DungeonConfig.DragonPart.BODY)
                .add(dragon.getPosition(partialTick).subtract(dragon.position()));
            Vec3 screen = client.gameRenderer.projectPointToScreen(core);
            if (!Double.isFinite(screen.x) || !Double.isFinite(screen.y) || screen.z < -1.0 || screen.z > 1.0) continue;
            String text = M7DragonHp.format(health);
            int x = (int) Math.round((screen.x + 1.0) * 0.5 * graphics.guiWidth()) - client.font.width(text) / 2;
            int y = (int) Math.round((1.0 - screen.y) * 0.5 * graphics.guiHeight()) - client.font.lineHeight / 2;
            graphics.text(client.font, text, x, y, 0xFF000000 | M7DragonHp.color(health, Math.max(seen.peak(), health)), true);
        }
    }

    private static void drawPrio(GuiGraphicsExtractor graphics, DungeonConfig config, Component text) {
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.dragonPrioX(), config.dragonPrioY());
            float scale = config.dragonPrioScale() / 100F;
            graphics.pose().scale(scale, scale);
            graphics.text(Minecraft.getInstance().font, text, 4, 4, 0xFFFFFFFF, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** The countdown number: server ticks as they are, or milliseconds (50 per tick). */
    static String prioText(long ticks, DragonPrioUnit unit) {
        return Long.toString(unit == DragonPrioUnit.MILLISECONDS ? ticks * 50L : ticks);
    }

    public static void drawPrioPreview(GuiGraphicsExtractor graphics, DungeonConfig config) {
        drawPrio(graphics, config, Component.literal(prioText(67, config.dragonPrioUnit())).withColor(Statue.BLUE.color() & 0xFFFFFF));
    }

    public static KungHudLayout.Bounds overlayBounds(DungeonConfig config) {
        float scale = config.dragonPrioScale() / 100F;
        int width = config.dragonPrioUnit() == DragonPrioUnit.MILLISECONDS ? 60 : 36;
        return new KungHudLayout.Bounds(config.dragonPrioX(), config.dragonPrioY(),
            Math.round(width * scale), Math.round(16 * scale));
    }

    /** A wireframe needs a little more size than the old filled dot to stay readable across the arena. */
    private static M7DragonRenderer.Marker point(Vec3 center, int color) {
        return point(center, color, false);
    }

    private static M7DragonRenderer.Marker point(Vec3 center, int color, boolean imminent) {
        double radius = imminent ? 0.45 : 0.12;
        return new M7DragonRenderer.Marker(new AABB(center, center).inflate(radius),
            color | 0xFF000000, imminent ? 5.0F : 3.0F, true);
    }

    private void publish(List<M7DragonTracker.Result> results) {
        for (var result : results) {
            String name = result.statue() == null ? "Dragon" : result.statue().label() + " dragon";
            String text = name + switch (result.outcome()) {
                case COUNTS -> " counts.";
                case MISSED -> " missed - it respawns.";
                case UNKNOWN -> " result unknown — no statue confirmation.";
            };
            trace("result " + text + " evidence=" + result.evidence());
            recordDeath(result);
            if (!config().m7DragonHelperEnabled() || !config().dragonCountNotificationsEnabled()) continue;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) continue;
            // Buffer instead of drawing now: statueBroken and confirmMessage both publish in the
            // same tick, so drawing per result stacked two titles. flushTitles draws one.
            if (result.statue() != null) pendingTitles.put(result.statue(), result.outcome());
        }
    }

    /**
     * Appends one line per resolved dragon so the real counting box can be fitted offline.
     * Runs unattended - no dev command - because the feature is already Beng114-only. Only
     * COUNTS and MISSED are written; UNKNOWN says nothing about the box.
     */
    /** Offsets are relative to the spawn anchor, which is the frame the counting box is fitted in. */
    static String deathLine(long tick, Statue statue, M7DragonTracker.Outcome outcome, Vec3 at) {
        return "{\"tick\":" + tick
            + ",\"statue\":\"" + statue.name() + "\""
            + ",\"outcome\":\"" + outcome.name() + "\""
            + ",\"x\":" + at.x + ",\"y\":" + at.y + ",\"z\":" + at.z
            + ",\"dx\":" + (at.x - statue.spawn().x)
            + ",\"dy\":" + (at.y - statue.spawn().y)
            + ",\"dz\":" + (at.z - statue.spawn().z)
            + ",\"predictedInside\":" + statue.contains(at) + "}";
    }

    /** One recorded kill, in offsets from its own spawn anchor. */
    record DeathSample(String statue, boolean counts, double dx, double dy, double dz) { }

    private static final Pattern SAMPLE_LINE = Pattern.compile(
        "\"statue\":\"(\\w+)\".*?\"outcome\":\"(\\w+)\".*?"
            + "\"dx\":(-?[\\d.]+),\"dy\":(-?[\\d.]+),\"dz\":(-?[\\d.]+)");

    static List<DeathSample> parseSamples(List<String> lines) {
        List<DeathSample> samples = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = SAMPLE_LINE.matcher(line);
            // UNKNOWN is no evidence either way: half of them were later-confirmed counts at the same spot.
            if (!matcher.find() || matcher.group(2).equals("UNKNOWN")) continue;
            samples.add(new DeathSample(matcher.group(1), matcher.group(2).equals("COUNTS"),
                Double.parseDouble(matcher.group(3)), Double.parseDouble(matcher.group(4)),
                Double.parseDouble(matcher.group(5))));
        }
        return samples;
    }

    /**
     * Keep a kill only when it tightens the bracket around the real box. A counting kill sits
     * inside, so it is news only if it reaches farther out than every inside sample already held;
     * a non-counting kill sits outside and is news only if it sits closer in than every outside
     * one. Compared per statue and per octant, because the estimate is not symmetric around y and
     * the statue blocks are not placed identically - a cross-octant compare could silently drop a
     * sample that was the only evidence for its side. Grinding M7 therefore adds a handful of
     * frontier points, not a line per dragon.
     */
    static boolean tightensBracket(DeathSample candidate, List<DeathSample> existing) {
        for (DeathSample old : existing) {
            if (old.counts() != candidate.counts() || !old.statue().equals(candidate.statue())) continue;
            if ((old.dx() < 0) != (candidate.dx() < 0)
                || (old.dy() < 0) != (candidate.dy() < 0)
                || (old.dz() < 0) != (candidate.dz() < 0)) continue;
            boolean covered = candidate.counts()
                ? Math.abs(old.dx()) >= Math.abs(candidate.dx())
                    && Math.abs(old.dy()) >= Math.abs(candidate.dy())
                    && Math.abs(old.dz()) >= Math.abs(candidate.dz())
                : Math.abs(old.dx()) <= Math.abs(candidate.dx())
                    && Math.abs(old.dy()) <= Math.abs(candidate.dy())
                    && Math.abs(old.dz()) <= Math.abs(candidate.dz());
            if (covered) return false;
        }
        return true;
    }

    private void recordDeath(M7DragonTracker.Result result) {
        Statue statue = result.statue();
        Vec3 at = result.position();
        if (!KungDeveloperAccess.allowed() || statue == null || at == null
            || result.outcome() == M7DragonTracker.Outcome.UNKNOWN) return;
        Minecraft client = Minecraft.getInstance();
        Path gameDirectory = client == null ? Path.of(".") : client.gameDirectory.toPath();
        Path file = new KungFileLayout(gameDirectory, gameDirectory.resolve("config"))
            .dungeonDataDirectory().resolve("m7-dragon-deaths.jsonl");
        String line = deathLine(tracker.tick(), statue, result.outcome(), at);
        try {
            DeathSample candidate = new DeathSample(statue.name(),
                result.outcome() == M7DragonTracker.Outcome.COUNTS,
                at.x - statue.spawn().x, at.y - statue.spawn().y, at.z - statue.spawn().z);
            List<DeathSample> existing = Files.exists(file)
                ? parseSamples(Files.readAllLines(file, StandardCharsets.UTF_8)) : List.of();
            if (!tightensBracket(candidate, existing)) {
                trace("death-sample-skipped " + statue.label() + " already bracketed");
                return;
            }
        } catch (IOException exception) {
            trace("death-sample-read-failed " + exception.getMessage());
        }
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException exception) {
            trace("death-record-failed " + exception.getMessage());
        }
    }

    /**
     * Hypixel plays a dense burst on the spawn anchor five blocks up, roughly a second before the
     * dragon exists: count=40, spread 2/3/2, speed 0. Matching the burst shape rather than the
     * particle type keeps ambient arena effects out - three different types carry the same burst.
     */
    private void observeSpawnHint(ClientboundLevelParticlesPacket packet) {
        if (packet.getCount() < 40 || packet.getMaxSpeed() != 0.0F) return;
        if (packet.getXDist() != 2.0F || packet.getYDist() != 3.0F || packet.getZDist() != 2.0F) return;
        for (Statue statue : Statue.values()) {
            if (Math.abs(packet.getX() - statue.spawn().x) < 0.5
                && Math.abs(packet.getY() - (statue.spawn().y + 5)) < 0.5
                && Math.abs(packet.getZ() - statue.spawn().z) < 0.5) {
                // The burst repeats until the dragon spawns; HINT_TO_SPAWN_TICKS counts from its first
                // packet. Refreshing on every packet left the countdown stuck ~100 ticks out.
                Long first = spawnHints.get(statue);
                if (first == null || tracker.tick() - first > M7DragonAim.HINT_TO_SPAWN_TICKS + 20) {
                    spawnHints.put(statue, tracker.tick());
                    prioNumbers.put(statue, ++hintCount);
                    trace("spawn-hint " + statue.label() + " tick=" + tracker.tick() + " number=" + hintCount);
                }
                return;
            }
        }
    }

    public void observeParticles(ClientboundLevelParticlesPacket packet) {
        if (!initialized() || !ready()) return;
        if (packet.getY() < 0 || packet.getY() > 45 || packet.getX() < 0 || packet.getX() > 120
            || packet.getZ() < 25 || packet.getZ() > 150) return;
        observeSpawnHint(packet);
        if (!config().devDragonDiagnosticsEnabled()) return;
        ParticleType<?> type = packet.getParticle().getType();
        Long previous = particleSamples.get(type);
        // Sample each type once per second so common effects cannot crowd out a rare range signal.
        if (previous != null && tracker.tick() - previous < 20) return;
        if (previous == null && particleSamples.size() >= 16) return;
        particleSamples.put(type, tracker.tick());
        sample("particle=" + BuiltInRegistries.PARTICLE_TYPE.getKey(type)
            + " pos=" + new Vec3(packet.getX(), packet.getY(), packet.getZ()) + " count=" + packet.getCount()
            + " spread=" + packet.getXDist() + "," + packet.getYDist() + "," + packet.getZDist()
            + " speed=" + packet.getMaxSpeed());
    }

    private void capturePositions() {
        for (var entry : entities.entrySet()) {
            EnderDragon dragon = entry.getValue();
            var attempt = tracker.attempt(entry.getKey());
            if (attempt == null) continue;
            Vec3 position = DungeonDebuffFeature.matchingPosition(dragon);
            sample(attempt.statue().label() + " uuid=" + entry.getKey() + " received=" + position
                + " rendered=" + dragon.position() + " bounds=" + dragon.getBoundingBox()
                + " health=" + dragon.getHealth() + " estimatedInside=" + attempt.statue().contains(position));
        }
    }

    private void trace(String message) {
        KungDebugRecorder.event("m7-dragon", message);
        sample(message);
        if (!initialized() || !config().devDragonDiagnosticsEnabled()) return;
        developerEvents.addLast("tick=" + tracker.tick() + " " + message);
        while (developerEvents.size() > 128) developerEvents.removeFirst();
    }

    private void sample(String message) {
        if (!KungDeveloperAccess.allowed() || !initialized() || !config().devDragonDiagnosticsEnabled()) return;
        developerSamples.addLast("tick=" + tracker.tick() + " " + message);
        while (developerSamples.size() > 512) developerSamples.removeFirst();
    }

    public String captureDeveloperSample() {
        if (!KungDeveloperAccess.allowed()) return "Developer access required.";
        if (!ready() || !config().devDragonDiagnosticsEnabled()) return "Enable Developer Diagnostics in M7 first.";
        Minecraft client = Minecraft.getInstance();
        trace("manual player=" + (client.player == null ? "unknown" : client.player.position())
            + " lookedBlock=" + (client.hitResult instanceof BlockHitResult block ? block.getBlockPos() : "none"));
        capturePositions();
        return "Dragon sample captured. Use /kung dev dragons copy.";
    }

    public String developerReport() {
        if (!KungDeveloperAccess.allowed()) return "Developer access required.";
        return developerSamples.isEmpty() && !lastDeveloperReport.isEmpty()
            ? lastDeveloperReport : currentDeveloperReport();
    }

    private String currentDeveloperReport() {
        StringBuilder report = new StringBuilder("Kung M7 dragon measurements\n");
        report.append("epoch=").append(epoch).append(" samples=").append(developerSamples.size())
            .append("/512\nRange is a community estimate; origin, not body intersection.\n");
        for (Statue statue : Statue.values()) report.append(String.format(Locale.ROOT,
            "%s spawn=%s range=%s statueBlock=%s%n", statue.label(), statue.spawn(), statue.range(), statue.statueBlock()));
        report.append("Events (last 128, retained separately from periodic samples):\n");
        developerEvents.forEach(line -> report.append(line).append('\n'));
        report.append("Recent samples (last 512):\n");
        developerSamples.forEach(line -> report.append(line).append('\n'));
        return report.toString();
    }
}
