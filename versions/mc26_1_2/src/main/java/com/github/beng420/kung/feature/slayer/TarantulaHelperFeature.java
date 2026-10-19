package com.github.beng420.kung.feature.slayer;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.SlayerConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.github.beng420.kung.runtime.KungFileLayout;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.ServerTpsTracker;
import com.github.beng420.kung.util.LineBoxes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TarantulaHelperFeature extends ConfigurableFeature<SlayerConfig> {
    public static final TarantulaHelperFeature INSTANCE = new TarantulaHelperFeature();

    private static final double SEARCH_RANGE = 30.0;
    private static final double NAME_TO_BODY_RANGE = 4.0;
    private static final double OWNER_SEARCH_RANGE = 5.0;
    /**
     * Hypixel dropped the "Spawned by" stand: after our own spawn message the nearest untagged boss in range is ours,
     * however far it spawned, until the slayer ends. The fight lasts 3 minutes plus the cocoon pauses of the timer.
     */
    private static final long SPAWN_ARM_MILLIS = 300_000L;
    private static final long SIDEBAR_LAG_MILLIS = 10_000L;
    /** A boss's stands share its column; anything further out sideways belongs to another boss. */
    private static final double OWNER_COLUMN_RANGE = 1.5;
    private static final double EGG_SAC_SEARCH_RANGE = 24.0;
    private static final double EGG_SAC_PAIR_RANGE = 2.5;
    private static final double EGG_SAC_BOSS_RANGE = 24.0;
    private static final double EGG_SAC_CENTER_ABOVE_BOSS = 9.42;
    /**
     * 646 recorded sacs from 143 phases: they sit in a disc around the centre, never in the corners of
     * the old square, and their height matches the centre exactly (99% within 3.05 blocks, |dy| < 0.03).
     */
    private static final double EGG_SAC_PREDICTION_RADIUS = 3.05;
    private static final double EGG_SAC_PREDICTION_HALF_X = EGG_SAC_PREDICTION_RADIUS;
    private static final double EGG_SAC_PREDICTION_HALF_Y = 0.42;
    private static final double EGG_SAC_PREDICTION_HALF_Z = EGG_SAC_PREDICTION_RADIUS;
    /** Other players fight their bosses next to ours; their sacs are far outside our own disc. */
    private static final double EGG_SAC_CLUSTER_RANGE = 8.0;
    private static final double EGG_SAC_CLUSTER_HEIGHT = 4.0;
    private static final float FIRST_EGG_SAC_PRE_TRIGGER_HEALTH = 0.86F;
    private static final float SECOND_EGG_SAC_PRE_TRIGGER_HEALTH = 0.54F;
    private static final long EGG_SAC_DONE_GRACE_MILLIS = 250L;
    private static final long BOSS_REBIND_GRACE_MILLIS = 2_000L;
    private static final long BOSS_MISSING_PREDICTION_MILLIS = 450L;
    private static final long SLAYER_END_SUPPRESS_MILLIS = 1_500L;
    /** The leap into the egg sac phase gains far more height in one tick than walking over a slab. */
    private static final double JUMP_RISE_PER_TICK = 0.3;
    /**
     * The spawn runs on server ticks, so it is counted in them; 2.3s to 2.6s of measurements at 20 TPS.
     */
    private static final long EGG_SAC_SPAWN_DELAY_TICKS = 49;
    private static final int PREDICTION_RED = 0;
    private static final int PREDICTION_GREEN = 255;
    private static final int PREDICTION_BLUE = 210;
    private static final double PREDICTION_GRID_SPACING = 0.75;
    private static final double EGG_SAC_BOX_HALF_WIDTH = 0.35;
    private static final int EGG_SAC_BOX_COLOR = 0xFF00FFD2;
    /**
     * Hypixel keeps the real health in the nametag. Mobs carry "795.1k/1.2M❤"; a slayer boss did only its
     * current health ("☠ Tarantula Broodfather V 9.4M❤") and since 2026-10-18 names the maximum too
     * ("[Lv396]  Tarantula Broodfather V 10M/10M❤").
     */
    private static final java.util.regex.Pattern NAMETAG_HEALTH =
        java.util.regex.Pattern.compile("(?:([\\d.,]+[kKmMbB]?)/)?([\\d.,]+[kKmMbB]?)❤");
    private static final String EGG_SAC_FILE = "tarantula-egg-sacs.txt";
    private static final double PREDICTION_GRID_HALF_SIZE = 0.17;
    private static final double PREDICTION_GRID_BLOCK_SCAN_ABOVE = 1.0;
    private static final int PREDICTION_GRID_MAX_LOWERED_STEPS = 6;
    private static final int PREDICTION_GRID_ALPHA = 185;

    private Entity activeBoss;
    private int activeBossId = -1;
    private int activePhase;
    private long lastPositionDebugMillis;
    private long missingBossSinceMillis;
    private boolean eggSacPhaseActive;
    private boolean eggSacSeenDuringPhase;
    private long eggSacPhaseStartedMillis;
    private long eggSacPhaseStartedTicks;
    private long lastEggSacSeenMillis;
    private Vec3 eggSacPredictionAnchor;
    private double eggSacPredictionHalfX = EGG_SAC_PREDICTION_HALF_X;
    private double eggSacPredictionHalfY = EGG_SAC_PREDICTION_HALF_Y;
    private double eggSacPredictionHalfZ = EGG_SAC_PREDICTION_HALF_Z;
    private List<EggSac> visibleEggSacs = List.of();
    private boolean eggSacPhaseStartDebugSent;
    /** He has exactly two egg sac phases; after those there is nothing left to predict. */
    private int eggSacPhasesWithSacs;
    private boolean firstEggSacPhasePredicted;
    private boolean secondEggSacPhasePredicted;
    private boolean cocoonEggSacPhasePredicted;
    private boolean eggSacJumpArmed;
    private boolean trackingTraced = true;
    private double lastBossY = Double.NaN;
    /** Offsets from the jump spot of every sac seen in the running phase, one entry per spawn point. */
    private final Map<String, Vec3> phaseSacOffsets = new LinkedHashMap<>();
    private float lastBossHealthPercent = Float.NaN;
    private long slayerEndSeenMillis;
    private long spawnMessageMillis;
    private boolean activeBossWasConjoined;
    /** A slayer nametag names no maximum, so the boss's full health is the highest value seen. */
    private double bossMaxHealth;
    /** The raw nametag value behind the last fraction, so a trace shows whose boss was read. */
    private double lastBossHealthValue;
    private int activeNameCarrierId = -1;
    /** The health this phase started at; while it runs the boss cannot lose any. */
    private float phaseStartHealth = Float.NaN;

    private TarantulaHelperFeature() {
        super(cfg -> cfg.slayer);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
            observeMessage(Minecraft.getInstance(), message.getString())
        );
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(Minecraft.getInstance(), message.getString())
        );
        // Before water, like vanilla's block outline: water writes depth, so boxes and grid drawn after it vanish
        // behind its surface. The grid is nearly opaque, so its own depth barely shows against water behind it.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(this::render);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "egg_sac_countdown"), (graphics, delta) -> renderCountdown(graphics));
    }

    @Override
    public boolean isEnabled() {
        return config().tarantulaHelperEnabled();
    }

    private void observeMessage(Minecraft client, String rawMessage) {
        if (!isEnabled() || client.player == null) {
            return;
        }

        String message = clean(rawMessage);
        if (isBossSpawnMessage(message)) {
            spawnMessageMillis = System.currentTimeMillis();
            KungDebugRecorder.event("tarantula", "spawn message");
        }
        if (isEggSacPhaseStartMessage(message)) {
            // The message also fires when hitting someone else's boss; without a boss of our own that
            // would start a phase nothing can ever end. The id survives his cocoon, the entity does not.
            if (activeBossId == -1) {
                return;
            }

            Entity boss = activeBoss(client);
            if (boss != null) {
                // The health here is what the real threshold looks like; it tunes the jump arming.
                float fraction = bossHealthFraction(client, boss);
                startEggSacPhase(client, boss, "hatchling message at "
                    + (Float.isNaN(fraction) ? "unknown" : Math.round(fraction * 100) + "%"));
            } else {
                // No boss entity while he cocoons; the message alone is proof enough to show the box.
                startEggSacPhase(client, null, "hatchling message");
            }
            return;
        }

        if (isOwnSlayerEndMessage(message)) {
            slayerEndSeenMillis = System.currentTimeMillis();
            spawnMessageMillis = 0L;
            if (activeBossId != -1) {
                finishEggSacPhase(client);
                debug(client, DebugMessage.SLAYER_DEAD, "slayer dead");
            }
            clearTrackedBoss();
        }
    }

    private void tick(Minecraft client) {
        boolean shouldTrack = isEnabled()
            && (config().eggSacPredictionEnabled() || KungConfig.get().debug.tarantulaMessages());

        if (!shouldTrack || client.level == null || client.player == null) {
            clearTrackedBoss();
            spawnMessageMillis = 0L;
            return;
        }

        Entity boss = activeBoss(client);
        if (boss == null && activeBossId != -1) {
            boss = findOwnBoss(client);
            if (boss != null) {
                rebindBossAfterPhaseTransition(client, boss);
            } else {
                List<EggSac> nearbyEggSacs = findEggSacs(client, null);
                visibleEggSacs = nearbyEggSacs;
                if (!nearbyEggSacs.isEmpty()) {
                    Entity previousBoss = activeBoss != null ? activeBoss : client.player;
                    startEggSacPhase(client, previousBoss, "sacs visible");
                    visibleEggSacs = nearbyEggSacs;
                    markEggSacsSeen();
                    markBossMissing(client);
                    return;
                } else if (primeMissingBossPrediction(client)) {
                    markBossMissing(client);
                    return;
                } else if (!markBossMissing(client)) {
                    return;
                } else if (eggSacPhaseActive || fightIsOn()) {
                    // He sits in his cocoon: no entity to find, but the phase or the whole fight is still running.
                    // Clearing now would forget the phases and could not bind the Conjoined Brood that follows.
                    return;
                } else {
                    finishEggSacPhase(client);
                    debug(client, DebugMessage.SLAYER_DEAD, "slayer dead");
                    clearTrackedBoss();
                    return;
                }
            }
        } else if (boss == null) {
            boss = findOwnBoss(client);
        }

        if (boss == null) {
            // Nothing bound: the phase still needs a spot, so take the nearest boss body there is.
            if (eggSacPhaseActive) anchorToAnyBossBody(client);
            traceTracking(client, false);
            return;
        }
        traceTracking(client, true);
        if (activeBossId == -1) {
            bossMaxHealth = 0;
            activeNameCarrierId = -1;
            float fraction = bossHealthFraction(client, boss);
            KungDebugRecorder.event("tarantula", "boss bound type=" + boss.getType().toShortString()
                + " health=" + (Float.isNaN(fraction) ? "unknown" : Math.round(lastBossHealthValue) + ""));
        }

        if (activeBossId == -1) {
            activeBoss = boss;
            activeBossId = boss.getId();
            activePhase = phaseFor(client, boss);
            lastPositionDebugMillis = 0L;
            missingBossSinceMillis = 0L;
            activeBossWasConjoined = isConjoinedBrood(boss);
            debug(client, DebugMessage.SLAYER_SPAWNED, "slayer spawned");
        } else {
            activeBoss = boss;
            missingBossSinceMillis = 0L;
            activeBossWasConjoined = activeBossWasConjoined || isConjoinedBrood(boss);
        }

        int phase = phaseFor(client, boss);
        if (phase != 0 && activePhase != 0 && phase != activePhase) {
            activePhase = phase;
            debug(client, DebugMessage.SLAYER_PHASE_CHANGE, "slayer phase change");
        } else if (activePhase == 0) {
            activePhase = phase;
        }

        anticipateEggSacPhase(client, boss);
        updateEggSacPhase(client, boss);

        long now = System.currentTimeMillis();
        if (now - lastPositionDebugMillis >= 1000L) {
            lastPositionDebugMillis = now;
            debug(client, DebugMessage.SLAYER_POSITION, "slayer pos: " + positionText(boss));
        }
    }

    /**
     * Only while an egg sac phase runs, over the boss. He disappears into its cocoon just as the sacs
     * spawn, so the last anchor carries the box through that gap instead of letting it blink out.
     */
    private void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (isEnabled() && config().eggSacBoxesEnabled() && !visibleEggSacs.isEmpty() && client.level != null) {
            drawEggSacBoxes(context, client);
        }
        if (!isEnabled()
            || !config().eggSacPredictionEnabled()
            || client.level == null
            || client.player == null
            || !eggSacPhaseActive
            || eggSacSeenDuringPhase
            || recentlySawSlayerEnd()) {
            return;
        }

        Entity boss = activeBoss(client);
        Vec3 center = boss != null && canRenderPredictionForBoss(boss)
            ? predictionAnchorFor(boss, renderPartialTick())
            : eggSacPredictionAnchor;
        if (center == null) {
            return;
        }

        PoseStack poseStack = context.poseStack();
        Vec3 camera = McCompat.camera(client).position();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        McCompat.draw(context, RenderTypes.debugQuads(),
            (pose, vertices) -> drawEggSacPredictionGrid(client, pose, vertices, center));
        poseStack.popPose();
    }

    private Vec3 predictionAnchorFor(Entity boss, float partialTick) {
        Vec3 position = boss.getPosition(partialTick);
        return new Vec3(position.x, position.y + boss.getBbHeight() + EGG_SAC_CENTER_ABOVE_BOSS, position.z);
    }

    private float renderPartialTick() {
        Minecraft client = Minecraft.getInstance();
        return McCompat.camera(client).getCameraEntityPartialTicks(client.getDeltaTracker());
    }

    private void renderCountdown(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || !config().eggSacCountdownEnabled() || McCompat.hudHidden(client)
            || KungHudEditorState.externalEditing() || !eggSacPhaseActive || eggSacSeenDuringPhase) {
            return;
        }

        drawCountdown(graphics, config(), ServerTpsTracker.INSTANCE.ticks() - eggSacPhaseStartedTicks);
    }

    /** Ticks to the sacs, and how far they are overdue once it runs out; that drift is the debug value. */
    private static void drawCountdown(GuiGraphicsExtractor graphics, SlayerConfig config, long elapsedTicks) {
        long remaining = EGG_SAC_SPAWN_DELAY_TICKS - elapsedTicks;
        String text = remaining > 0
            ? String.format(Locale.ROOT, "Egg Sacs in %.1fs", remaining / 20.0)
            : String.format(Locale.ROOT, "Egg Sacs +%.1fs", -remaining / 20.0);
        int color = remaining > 20 ? 0xFF55FF55 : remaining > 0 ? 0xFFFFFF55 : 0xFFFF5555;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.eggSacCountdownX(), config.eggSacCountdownY());
            float scale = config.eggSacCountdownScale() / 100F;
            graphics.pose().scale(scale, scale);
            graphics.text(Minecraft.getInstance().font, text, 4, 4, color, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    public static void drawCountdownPreview(GuiGraphicsExtractor graphics, SlayerConfig config) {
        drawCountdown(graphics, config, EGG_SAC_SPAWN_DELAY_TICKS - 28);
    }

    public static KungHudLayout.Bounds overlayBounds(SlayerConfig config) {
        float scale = config.eggSacCountdownScale() / 100F;
        return new KungHudLayout.Bounds(config.eggSacCountdownX(), config.eggSacCountdownY(),
            Math.round(96 * scale), Math.round(16 * scale));
    }

    /** The sacs themselves carry no name, so nothing but their own timer can find them. */
    private void drawEggSacBoxes(LevelRenderContext context, Minecraft client) {
        Vec3 camera = McCompat.camera(client).position();
        McCompat.draw(context, RenderTypes.lines(), (pose, lines) -> {
            for (EggSac sac : visibleEggSacs) {
                AABB box = new AABB(sac.center(), sac.center()).inflate(EGG_SAC_BOX_HALF_WIDTH, sac.halfHeight(),
                    EGG_SAC_BOX_HALF_WIDTH);
                LineBoxes.box(lines, pose, box, camera.x, camera.y, camera.z, EGG_SAC_BOX_COLOR, 2.0F);
            }
        });
    }

    private Entity activeBoss(Minecraft client) {
        if (activeBossId == -1 || client.level == null) {
            return null;
        }

        Entity boss = client.level.getEntity(activeBossId);
        if (boss == null
            || !boss.isAlive()
            || client.player == null
            || client.player.distanceToSqr(boss) > SEARCH_RANGE * SEARCH_RANGE) {
            return null;
        }
        return boss;
    }

    /** Why the helper has no boss: the names it does see decide whether matching or ownership failed. */
    private void traceTracking(Minecraft client, boolean found) {
        if (found == trackingTraced) return;
        trackingTraced = found;
        if (found) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!entity.hasCustomName() || client.player.distanceToSqr(entity) > SEARCH_RANGE * SEARCH_RANGE) continue;
            String name = plain(entityName(entity));
            if (name.isBlank() || names.length() > 300) continue;
            names.append(names.isEmpty() ? "" : " | ").append(name);
        }
        KungDebugRecorder.event("tarantula", "no own boss; fight=" + fightIsOn() + " sidebarBoss="
            + sidebarShowsBoss(HypixelInstanceTracker.INSTANCE.sidebarLines()) + " nearby=[" + names + "]");
    }

    /** Anchor without ownership proof: only used while a phase already started, so it is our fight. */
    private void anchorToAnyBossBody(Minecraft client) {
        double best = SEARCH_RANGE * SEARCH_RANGE;
        Entity body = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!hasTarantulaBossName(entity)) continue;
            Entity candidate = nearestBossBody(client, entity);
            if (candidate == null) continue;
            double distance = client.player.distanceToSqr(candidate);
            if (distance < best) {
                best = distance;
                body = candidate;
            }
        }
        if (body != null) updateEggSacPrediction(client, body);
    }

    private Entity findOwnBoss(Minecraft client) {
        Entity best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!isAliveCandidate(client, entity) || !hasTarantulaBossName(entity)) {
                continue;
            }
            if (!isOwnBossNameCarrier(client, entity)) {
                continue;
            }

            Entity body = nearestBossBody(client, entity);
            Entity candidate = body != null ? body : entity;
            double distance = client.player.distanceToSqr(candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }

        return best;
    }

    private Entity nearestBossBody(Minecraft client, Entity nameCarrier) {
        Entity best = null;
        double bestDistance = NAME_TO_BODY_RANGE * NAME_TO_BODY_RANGE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!isAliveCandidate(client, entity) || !isSpiderBody(entity)) {
                continue;
            }

            double distance = entity.distanceToSqr(nameCarrier);
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean isAliveCandidate(Minecraft client, Entity entity) {
        return entity != client.player
            && entity.isAlive()
            && client.player.distanceToSqr(entity) <= SEARCH_RANGE * SEARCH_RANGE;
    }

    private boolean isSpiderBody(Entity entity) {
        EntityType<?> type = entity.getType();
        return type == McCompat.SPIDER || type == McCompat.CAVE_SPIDER;
    }

    private boolean hasTarantulaBossName(Entity entity) {
        Component customName = entity.getCustomName();
        String name = clean(customName != null ? customName.getString() : entity.getName().getString());
        return name.contains("tarantula broodfather")
            || name.contains("primordial broodfather")
            || name.contains("primordial broodmother")
            || name.contains("conjoined brood");
    }

    private boolean isOwnBossNameCarrier(Minecraft client, Entity nameCarrier) {
        // Until 2026-10-18 every boss carried a "Spawned by" stand and Hypixel may bring it back: when it is there, it
        // alone decides, as before. Boss tags without it fall back to knowing that our own fight is on.
        String owner = ownerNameFor(client, nameCarrier);
        if (owner != null) {
            return owner.equalsIgnoreCase(client.player.getName().getString());
        }
        return adoptsUntagged(activeBossId != -1, fightIsOn());
    }

    private long sinceSpawn() {
        return spawnMessageMillis == 0L ? -1L : System.currentTimeMillis() - spawnMessageMillis;
    }

    /** Our boss is alive or about to be, though it may be out of sight (in its cocoon, far away). */
    private boolean fightIsOn() {
        return fightIsOn(HypixelInstanceTracker.INSTANCE.sidebarLines(), sinceSpawn());
    }

    /**
     * The sidebar says "Slay the boss!" while our boss lives, which no late chat message can undo: a cocooned boss
     * completes its quest about 6 s after, when the next boss has spawned already. The spawn message bridges the
     * sidebar's lag, and stands in for it when no sidebar was read at all.
     */
    static boolean fightIsOn(List<String> sidebar, long sinceSpawnMillis) {
        if (sidebar.isEmpty()) {
            return spawnArmed(sinceSpawnMillis);
        }
        return sidebarShowsBoss(sidebar) || (sinceSpawnMillis >= 0L && sinceSpawnMillis <= SIDEBAR_LAG_MILLIS);
    }

    static boolean sidebarShowsBoss(List<String> sidebar) {
        return sidebar.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("slay the boss"));
    }

    static boolean spawnArmed(long sinceSpawnMillis) {
        return sinceSpawnMillis >= 0L && sinceSpawnMillis <= SPAWN_ARM_MILLIS;
    }

    /**
     * An untagged boss is ours while we fight one (the Conjoined Brood spawns up to ~10 blocks from where the old
     * body last stood, after his jump) or while our fight is on. Callers only look in range.
     */
    static boolean adoptsUntagged(boolean fighting, boolean fightOn) {
        return fighting || fightOn;
    }

    /** "slayer boss! the tarantula broodfather v spawned!"; the mini boss line says "slayer miniboss!". */
    static boolean isBossSpawnMessage(String message) {
        return message.startsWith("slayer boss!") && message.contains("spawned") && message.contains("brood");
    }

    private String ownerNameFor(Minecraft client, Entity nameCarrier) {
        for (int offset = -4; offset <= 4; offset++) {
            if (offset == 0) continue;
            String owner = ownerNameFrom(client.level.getEntity(nameCarrier.getId() + offset));
            if (owner != null) {
                return owner;
            }
        }

        // Only the stand in the same column belongs to this boss; five blocks away is another fight.
        Entity nearestOwner = null;
        double bestDistance = OWNER_COLUMN_RANGE * OWNER_COLUMN_RANGE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || ownerNameFrom(entity) == null) {
                continue;
            }

            double distance = horizontalDistanceSqr(entity, nameCarrier);
            if (distance < bestDistance && Math.abs(entity.getY() - nameCarrier.getY()) <= OWNER_SEARCH_RANGE) {
                nearestOwner = entity;
                bestDistance = distance;
            }
        }

        return ownerNameFrom(nearestOwner);
    }

    private static double horizontalDistanceSqr(Entity first, Entity second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private String ownerNameFrom(Entity entity) {
        if (entity == null) {
            return null;
        }

        Component customName = entity.getCustomName();
        String text = plain(customName != null ? customName.getString() : entity.getName().getString());
        String lower = text.toLowerCase(Locale.ROOT);
        String marker = "spawned by:";
        int markerIndex = lower.indexOf(marker);
        if (markerIndex == -1) {
            return null;
        }

        String rest = text.substring(markerIndex + marker.length()).trim();
        StringBuilder owner = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                owner.append(c);
            } else if (!owner.isEmpty()) {
                break;
            }
        }

        return owner.isEmpty() ? null : owner.toString();
    }

    private boolean isOwnSlayerEndMessage(String message) {
        // Cocooning the boss with the attribute ends the fight as surely as killing it does.
        return message.contains("cocooned your slayer boss")
            || message.contains("slayer quest complete")
            || message.contains("slayer quest failed")
            || message.contains("slayer boss slain")
            || message.contains("boss slain")
            || message.contains("nice! slayer boss");
    }

    private boolean isEggSacPhaseStartMessage(String message) {
        return message.contains("broodfather's hatchlings")
            || message.contains("broodfathers hatchlings")
            || message.contains("kill the hatchlings");
    }

    /**
     * Tarantula jumps when his health reaches a threshold, spins on the spot and only then spawns the
     * cobwebs and egg sacs. The jump is the cue, and the spot he jumps from is where they appear.
     */
    private void anticipateEggSacPhase(Minecraft client, Entity boss) {
        double previousY = lastBossY;
        lastBossY = boss.getY();
        // The entity's own last tick, not ours: he often leaps in the very tick we bind him, and our
        // own memory is still empty then.
        double rise = Math.max(boss.getY() - boss.yOld, Double.isNaN(previousY) ? 0.0 : boss.getY() - previousY);
        if (isConjoinedBrood(boss)) {
            lastBossHealthPercent = Float.NaN;
            return;
        }

        // The box belongs over the boss, so the anchor follows him; it only has to outlive him.
        if (canRenderPredictionForBoss(boss)) {
            updateEggSacPrediction(client, boss);
        }

        float healthPercent = bossHealthFraction(client, boss);
        if (Float.isNaN(healthPercent)) {
            return;
        }

        float previousHealthPercent = lastBossHealthPercent;
        lastBossHealthPercent = healthPercent;
        if ((!firstEggSacPhasePredicted && crossedHealthThreshold(previousHealthPercent, healthPercent, FIRST_EGG_SAC_PRE_TRIGGER_HEALTH))
            || (firstEggSacPhasePredicted && !secondEggSacPhasePredicted
                && crossedHealthThreshold(previousHealthPercent, healthPercent, SECOND_EGG_SAC_PRE_TRIGGER_HEALTH))) {
            eggSacJumpArmed = true;
            KungDebugRecorder.event("tarantula", "egg sac armed health=" + Math.round(healthPercent * 100)
                + "% (" + Math.round(lastBossHealthValue) + " of " + Math.round(bossMaxHealth) + ")");
        }

        // Real damage lands again, so the sacs this phase waited for were never coming. While the phase
        // runs he is invulnerable, so anything past health jitter means the phase was a false alarm.
        if (eggSacPhaseActive && !eggSacSeenDuringPhase && !Float.isNaN(phaseStartHealth)
            && healthPercent < phaseStartHealth - 0.005F) {
            KungDebugRecorder.event("tarantula", "boss damageable again, no sacs");
            finishEggSacPhase(client);
        }

        // After the first phase the second one is certain, and a burst can take him past both thresholds
        // before a crossing is ever seen. Between phases every leap of his is that phase's leap.
        if (eggSacPhasesWithSacs >= 1 && !eggSacPhaseActive) {
            eggSacJumpArmed = true;
        }

        if (eggSacJumpArmed && rise > JUMP_RISE_PER_TICK) {
            eggSacJumpArmed = false;
            KungDebugRecorder.event("tarantula", String.format(Locale.ROOT, "egg sac jump rise=%.2f", rise));
            primeEggSacPhase(client, boss, "jump");
            return;
        }
    }

    /**
     * The entity's own health stays full on Hypixel, so the fraction comes from the nametag above the
     * body. Slayer bosses stack several stands (owner, name, health), so any of them may carry it.
     */
    private float bossHealthFraction(Minecraft client, Entity boss) {
        Entity carrier = ownNameCarrier(client, boss);
        double[] health = carrier == null ? null : nametagHealth(plain(entityName(carrier)));
        if (health == null) {
            return Float.NaN;
        }
        if (health[1] > 0) {
            return (float) Math.clamp(health[0] / health[1], 0.0, 1.0);
        }

        // A boss tag names no maximum, so the highest health seen for this boss is its full health.
        lastBossHealthValue = health[0];
        bossMaxHealth = Math.max(bossMaxHealth, health[0]);
        return bossMaxHealth <= 0 ? Float.NaN : (float) Math.clamp(health[0] / bossMaxHealth, 0.0, 1.0);
    }

    /**
     * Our own boss's nametag. Other players fight their bosses right next to ours here, so the nearest
     * boss tag is regularly someone else's and its health would jump between fights.
     */
    private Entity ownNameCarrier(Minecraft client, Entity boss) {
        Entity remembered = activeNameCarrierId == -1 ? null : client.level.getEntity(activeNameCarrierId);
        if (remembered != null && hasTarantulaBossName(remembered)
            && horizontalDistanceSqr(remembered, boss) <= OWNER_COLUMN_RANGE * OWNER_COLUMN_RANGE) {
            return remembered;
        }

        double bestDistance = OWNER_COLUMN_RANGE * OWNER_COLUMN_RANGE;
        Entity best = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!hasTarantulaBossName(entity) || !isOwnBossNameCarrier(client, entity)) {
                continue;
            }

            double distance = horizontalDistanceSqr(entity, boss);
            if (distance < bestDistance && Math.abs(entity.getY() - boss.getY()) <= NAME_TO_BODY_RANGE) {
                bestDistance = distance;
                best = entity;
            }
        }
        activeNameCarrierId = best == null ? -1 : best.getId();
        return best;
    }

    /** "... 795.1k/1.2M❤" -> {795100, 1200000}; "... 9.4M❤" -> {9400000, -1}; null without health. */
    static double[] nametagHealth(String name) {
        var matcher = NAMETAG_HEALTH.matcher(name);
        if (!matcher.find()) {
            return null;
        }

        boolean hasMax = matcher.group(1) != null;
        double current = nametagAmount(hasMax ? matcher.group(1) : matcher.group(2));
        double max = hasMax ? nametagAmount(matcher.group(2)) : -1;
        return current < 0 ? null : new double[] {current, max};
    }

    private static double nametagAmount(String text) {
        String value = text.replace(",", "").toLowerCase(Locale.ROOT);
        double factor = value.endsWith("k") ? 1_000 : value.endsWith("m") ? 1_000_000 : value.endsWith("b") ? 1_000_000_000 : 1;
        if (factor > 1) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Double.parseDouble(value) * factor;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private boolean crossedHealthThreshold(float previousHealthPercent, float healthPercent, float threshold) {
        if (Float.isNaN(previousHealthPercent)) {
            return healthPercent <= threshold;
        }
        return previousHealthPercent > threshold && healthPercent <= threshold;
    }

    private boolean primeMissingBossPrediction(Minecraft client) {
        if (activeBoss == null || activeBossWasConjoined || recentlySawSlayerEnd()) {
            return false;
        }

        long missingFor = missingBossSinceMillis == 0L ? 0L : System.currentTimeMillis() - missingBossSinceMillis;
        if (missingFor > BOSS_MISSING_PREDICTION_MILLIS) {
            return false;
        }

        if (!firstEggSacPhasePredicted) {
            firstEggSacPhasePredicted = true;
            primeEggSacPhase(client, activeBoss, "boss vanished");
            return true;
        }
        if (!secondEggSacPhasePredicted) {
            secondEggSacPhasePredicted = true;
            primeEggSacPhase(client, activeBoss, "boss vanished");
            return true;
        }
        if (!cocoonEggSacPhasePredicted && eggSacSeenDuringPhase) {
            cocoonEggSacPhasePredicted = true;
            primeEggSacPhase(client, activeBoss, "boss vanished");
            return true;
        }
        return false;
    }

    private boolean recentlySawSlayerEnd() {
        return slayerEndSeenMillis != 0L && System.currentTimeMillis() - slayerEndSeenMillis <= SLAYER_END_SUPPRESS_MILLIS;
    }

    private boolean isConjoinedBrood(Entity entity) {
        return clean(entityName(entity)).contains("conjoined brood");
    }

    private boolean canRenderPredictionForBoss(Entity boss) {
        return isSpiderBody(boss)
            && !isConjoinedBrood(boss)
            && !isDyingBoss(boss);
    }

    private boolean isDyingBoss(Entity boss) {
        return boss instanceof LivingEntity living
            && living.getMaxHealth() > 0.0F
            && living.getHealth() <= 0.0F;
    }

    private void updateEggSacPhase(Minecraft client, Entity boss) {
        long now = System.currentTimeMillis();
        List<EggSac> eggSacs = findEggSacs(client, boss);
        visibleEggSacs = eggSacs;
        if (!eggSacs.isEmpty()) {
            if (!eggSacPhaseActive) {
                startEggSacPhase(client, boss, "sacs visible");
            } else if (!eggSacPhaseStartDebugSent) {
                sendEggSacPhaseStartDebug(client);
            }
            markEggSacsSeen();
            return;
        }

        if (!eggSacPhaseActive) {
            return;
        }

        boolean sacsDisappeared = eggSacSeenDuringPhase && now - lastEggSacSeenMillis > EGG_SAC_DONE_GRACE_MILLIS;
        if (sacsDisappeared) {
            // The next phase waits for its own signal. Assuming a skip here left the timer running
            // through every damage phase that did not skip.
            finishEggSacPhase(client);
        }
    }

    /** Our own sacs sit in one tight cluster around the prediction; anything else is another fight. */
    private boolean belongsToOurPhase(Entity timer, Entity boss) {
        if (eggSacPredictionAnchor != null) {
            double dx = timer.getX() - eggSacPredictionAnchor.x;
            double dz = timer.getZ() - eggSacPredictionAnchor.z;
            return Math.hypot(dx, dz) <= EGG_SAC_CLUSTER_RANGE
                && Math.abs(timer.getY() - eggSacPredictionAnchor.y) <= EGG_SAC_CLUSTER_HEIGHT;
        }
        return boss == null || timer.distanceToSqr(boss) <= EGG_SAC_BOSS_RANGE * EGG_SAC_BOSS_RANGE;
    }

    /**
     * Every sac carries a timer and a SHOOT ME! stand. They are paired one to one by distance: with
     * sacs standing close together a shared stand used to give one sac two boxes and its neighbour none,
     * and a shot sac kept its box by borrowing the stand next door.
     */
    private List<EggSac> findEggSacs(Minecraft client, Entity boss) {
        java.util.List<Entity> timers = new java.util.ArrayList<>();
        java.util.List<Entity> markers = new java.util.ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)
                || client.player.distanceToSqr(entity) > EGG_SAC_SEARCH_RANGE * EGG_SAC_SEARCH_RANGE
                || !belongsToOurPhase(entity, boss)) {
                continue;
            }

            String name = clean(entityName(entity));
            if (isEggSacTimer(name)) {
                timers.add(entity);
            } else if (name.equals("shoot me!")) {
                markers.add(entity);
            }
        }

        java.util.ArrayList<EggSac> sacs = new java.util.ArrayList<>();
        for (Entity timer : timers) {
            Entity closest = null;
            double bestDistance = EGG_SAC_PAIR_RANGE * EGG_SAC_PAIR_RANGE;
            for (Entity marker : markers) {
                double distance = marker.distanceToSqr(timer);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    closest = marker;
                }
            }
            if (closest == null) {
                continue;
            }

            markers.remove(closest);
            Vec3 timerCenter = timer.getBoundingBox().getCenter();
            Vec3 markerCenter = closest.getBoundingBox().getCenter();
            double halfHeight = Math.clamp(Math.abs(timerCenter.y - markerCenter.y) * 0.35 + 0.22, 0.28, 0.42);
            sacs.add(new EggSac(new Vec3((timerCenter.x + markerCenter.x) * 0.5,
                (timerCenter.y + markerCenter.y) * 0.5, (timerCenter.z + markerCenter.z) * 0.5), halfHeight));
        }
        return sacs;
    }

    private void rebindBossAfterPhaseTransition(Minecraft client, Entity boss) {
        activeBoss = boss;
        activeBossId = boss.getId();
        activePhase = phaseFor(client, boss);
        activeBossWasConjoined = activeBossWasConjoined || isConjoinedBrood(boss);
        lastBossHealthPercent = Float.NaN;
        missingBossSinceMillis = 0L;
        debug(client, DebugMessage.SLAYER_PHASE_CHANGE, "slayer phase change");
    }

    private boolean markBossMissing(Minecraft client) {
        long now = System.currentTimeMillis();
        if (missingBossSinceMillis == 0L) {
            missingBossSinceMillis = now;
            return false;
        }

        return now - missingBossSinceMillis > BOSS_REBIND_GRACE_MILLIS;
    }

    private void markEggSacsSeen() {
        if (!eggSacSeenDuringPhase) {
            eggSacPhasesWithSacs++;
        }
        if (!eggSacSeenDuringPhase && eggSacPhaseStartedMillis > 0) {
            KungDebugRecorder.event("tarantula", "sacs " + (ServerTpsTracker.INSTANCE.ticks() - eggSacPhaseStartedTicks)
                + " server ticks after the phase started, "
                + String.format(Locale.ROOT, "%.1fs real", (System.currentTimeMillis() - eggSacPhaseStartedMillis) / 1000.0));
        }
        eggSacSeenDuringPhase = true;
        lastEggSacSeenMillis = System.currentTimeMillis();
        recordSacOffsets();
    }

    /** Which of the predicted spots actually carry a sac, collected over many bosses for the developer. */
    private void recordSacOffsets() {
        if (eggSacPredictionAnchor == null || !KungDeveloperAccess.allowed()) {
            return;
        }

        for (EggSac sac : visibleEggSacs) {
            Vec3 offset = sac.center().subtract(eggSacPredictionAnchor);
            phaseSacOffsets.putIfAbsent(String.format(Locale.ROOT, "%.1f,%.1f", offset.x, offset.z), offset);
        }
    }

    private void writeSacOffsets() {
        if (phaseSacOffsets.isEmpty() || eggSacPredictionAnchor == null) {
            phaseSacOffsets.clear();
            return;
        }

        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "anchor=%.2f,%.2f,%.2f sacs=",
            eggSacPredictionAnchor.x, eggSacPredictionAnchor.y, eggSacPredictionAnchor.z));
        boolean first = true;
        for (Vec3 offset : phaseSacOffsets.values()) {
            if (!first) line.append(';');
            first = false;
            line.append(String.format(Locale.ROOT, "%.2f,%.2f,%.2f", offset.x, offset.y, offset.z));
        }
        phaseSacOffsets.clear();
        Path file = eggSacFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            KungDebugRecorder.event("tarantula", "egg sac write failed: " + exception.getMessage());
        }
    }

    private static Path eggSacFile() {
        Minecraft client = Minecraft.getInstance();
        Path gameDirectory = client == null ? Path.of(".") : client.gameDirectory.toPath();
        return new KungFileLayout(gameDirectory, gameDirectory.resolve("config"))
            .slayerDataDirectory().resolve(EGG_SAC_FILE);
    }

    /** How often each grid cell carried a sac, over every phase recorded so far. */
    public String eggSacGridReport() {
        Path file = eggSacFile();
        List<String> lines;
        try {
            lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException exception) {
            return "Egg sac data unreadable: " + exception.getMessage();
        }
        if (lines.isEmpty()) {
            return "No egg sac phases recorded yet. File: " + file;
        }

        Map<String, Integer> counts = eggSacGridCounts(lines);
        int columns = (int) Math.floor(EGG_SAC_PREDICTION_HALF_X / PREDICTION_GRID_SPACING);
        int rows = (int) Math.floor(EGG_SAC_PREDICTION_HALF_Z / PREDICTION_GRID_SPACING);
        StringBuilder report = new StringBuilder("Egg sac spots over " + lines.size() + " phases (x right, z down):");
        for (int gridZ = -rows; gridZ <= rows; gridZ++) {
            report.append(String.format(Locale.ROOT, "%n%3d:", gridZ));
            for (int gridX = -columns; gridX <= columns; gridX++) {
                report.append(String.format(Locale.ROOT, "%5d", counts.getOrDefault(gridX + "," + gridZ, 0)));
            }
        }
        return report + System.lineSeparator() + "File: " + file;
    }

    /** "anchor=x,y,z sacs=dx,dy,dz;..." per phase -> hits per "gridX,gridZ" cell. */
    static Map<String, Integer> eggSacGridCounts(List<String> lines) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String line : lines) {
            int start = line.indexOf("sacs=");
            if (start < 0) continue;
            for (String entry : line.substring(start + 5).split(";")) {
                String[] parts = entry.split(",");
                if (parts.length != 3) continue;
                try {
                    int gridX = (int) Math.round(Double.parseDouble(parts[0]) / PREDICTION_GRID_SPACING);
                    int gridZ = (int) Math.round(Double.parseDouble(parts[2]) / PREDICTION_GRID_SPACING);
                    counts.merge(gridX + "," + gridZ, 1, Integer::sum);
                } catch (NumberFormatException exception) {
                    // A truncated line from a crash; the rest of the file still counts.
                }
            }
        }
        return counts;
    }

    private String entityName(Entity entity) {
        Component customName = entity.getCustomName();
        return customName != null ? customName.getString() : entity.getName().getString();
    }

    private boolean isEggSacTimer(String name) {
        return name.matches("\\d+s \\d+/\\d+");
    }

    private void primeEggSacPhase(Minecraft client, Entity boss, String reason) {
        startEggSacPhase(client, boss, false, reason);
    }

    private void startEggSacPhase(Minecraft client, Entity boss, String reason) {
        startEggSacPhase(client, boss, true, reason);
    }

    private void startEggSacPhase(Minecraft client, Entity boss, boolean sendDebug, String reason) {
        // A phase whose sacs already showed is over: on a skip the next one starts right on top of it.
        if (eggSacPhaseActive && eggSacSeenDuringPhase) {
            finishEggSacPhase(client);
        }
        if (eggSacPhasesWithSacs >= 2) {
            return;
        }
        if (eggSacPhaseActive) {
            if (sendDebug && !eggSacPhaseStartDebugSent) {
                sendEggSacPhaseStartDebug(client);
            }
            return;
        }

        long now = System.currentTimeMillis();
        eggSacPhaseStartedTicks = ServerTpsTracker.INSTANCE.ticks();
        phaseStartHealth = boss == null ? Float.NaN : bossHealthFraction(client, boss);
        eggSacPhaseActive = true;
        KungDebugRecorder.event("tarantula", "egg sac phase started: " + reason);
        markEggSacPhasePredicted();
        eggSacSeenDuringPhase = false;
        eggSacPhaseStartedMillis = now;
        lastEggSacSeenMillis = now;
        visibleEggSacs = List.of();
        eggSacPhaseStartDebugSent = false;
        if (eggSacPredictionAnchor == null && boss != null) {
            updateEggSacPrediction(client, boss);
        }
        if (sendDebug) {
            sendEggSacPhaseStartDebug(client);
        }
    }

    private void markEggSacPhasePredicted() {
        if (!firstEggSacPhasePredicted) {
            firstEggSacPhasePredicted = true;
        } else if (!secondEggSacPhasePredicted) {
            secondEggSacPhasePredicted = true;
        } else {
            cocoonEggSacPhasePredicted = true;
        }
    }

    private void sendEggSacPhaseStartDebug(Minecraft client) {
        eggSacPhaseStartDebugSent = true;
        debug(client, DebugMessage.EGG_SAC_PHASE_START, "egg sac phase start");
    }

    private void finishEggSacPhase(Minecraft client) {
        if (!eggSacPhaseActive) {
            return;
        }

        writeSacOffsets();
        eggSacPhaseActive = false;
        eggSacSeenDuringPhase = false;
        eggSacPhaseStartedMillis = 0L;
        lastEggSacSeenMillis = 0L;
        eggSacPredictionAnchor = null;
        eggSacPredictionHalfX = EGG_SAC_PREDICTION_HALF_X;
        eggSacPredictionHalfY = EGG_SAC_PREDICTION_HALF_Y;
        eggSacPredictionHalfZ = EGG_SAC_PREDICTION_HALF_Z;
        visibleEggSacs = List.of();
        eggSacPhaseStartDebugSent = false;
        eggSacJumpArmed = false;
        debug(client, DebugMessage.EGG_SAC_PHASE_DONE, "egg sac phase done");
    }

    private String clean(String text) {
        return plain(text)
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private String plain(String text) {
        return text.replaceAll("(?:\\u00a7|\\u00c2\\u00a7).", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private int phaseFor(Minecraft client, Entity boss) {
        float healthPercent = bossHealthFraction(client, boss);
        if (Float.isNaN(healthPercent)) {
            return 0;
        }

        if (healthPercent > 0.66F) {
            return 1;
        }
        if (healthPercent > 0.33F) {
            return 2;
        }
        return 3;
    }

    private String positionText(Entity boss) {
        AABB bounds = boss.getBoundingBox();
        double x = (bounds.minX + bounds.maxX) * 0.5;
        double y = bounds.minY;
        double z = (bounds.minZ + bounds.maxZ) * 0.5;
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
    }

    private void debug(Minecraft client, DebugMessage type, String message) {
        boolean debugEnabled = KungConfig.get().debug.tarantulaMessagesEnabled();
        if (debugEnabled && isDebugMessageEnabled(type) && client.player != null) {
            client.player.sendSystemMessage(KungMessages.debug("Tarantula", message));
        }
    }

    private boolean isDebugMessageEnabled(DebugMessage type) {
        return switch (type) {
            case SLAYER_SPAWNED -> config().tarantulaDebugSlayerSpawned();
            case SLAYER_POSITION -> config().tarantulaDebugSlayerPosition();
            case SLAYER_PHASE_CHANGE -> config().tarantulaDebugSlayerPhaseChange();
            case SLAYER_DEAD -> config().tarantulaDebugSlayerDead();
            case EGG_SAC_PHASE_START -> config().tarantulaDebugEggSacPhaseStart();
            case EGG_SAC_PHASE_DONE -> config().tarantulaDebugEggSacPhaseDone();
        };
    }

    private void clearTrackedBoss() {
        activeBoss = null;
        activeBossId = -1;
        activePhase = 0;
        lastPositionDebugMillis = 0L;
        missingBossSinceMillis = 0L;
        eggSacPhaseActive = false;
        eggSacSeenDuringPhase = false;
        eggSacPhaseStartedMillis = 0L;
        lastEggSacSeenMillis = 0L;
        // The anchor stays: a box on its own clock still needs a spot when the boss entity is gone.
        eggSacPredictionHalfX = EGG_SAC_PREDICTION_HALF_X;
        eggSacPredictionHalfY = EGG_SAC_PREDICTION_HALF_Y;
        eggSacPredictionHalfZ = EGG_SAC_PREDICTION_HALF_Z;
        visibleEggSacs = List.of();
        eggSacPhaseStartDebugSent = false;
        firstEggSacPhasePredicted = false;
        secondEggSacPhasePredicted = false;
        cocoonEggSacPhasePredicted = false;
        lastBossHealthPercent = Float.NaN;
        activeBossWasConjoined = false;
        eggSacJumpArmed = false;
        eggSacPhasesWithSacs = 0;
        lastBossY = Double.NaN;
        bossMaxHealth = 0;
        activeNameCarrierId = -1;
        phaseStartHealth = Float.NaN;
        phaseSacOffsets.clear();
    }

    private void updateEggSacPrediction(Minecraft client, Entity boss) {
        AABB bounds = boss.getBoundingBox();
        double bossX = (bounds.minX + bounds.maxX) * 0.5;
        double bossZ = (bounds.minZ + bounds.maxZ) * 0.5;
        eggSacPredictionAnchor = new Vec3(bossX, bounds.maxY + EGG_SAC_CENTER_ABOVE_BOSS, bossZ);
        eggSacPredictionHalfX = EGG_SAC_PREDICTION_HALF_X;
        eggSacPredictionHalfY = EGG_SAC_PREDICTION_HALF_Y;
        eggSacPredictionHalfZ = EGG_SAC_PREDICTION_HALF_Z;
    }

    private void drawEggSacPredictionGrid(Minecraft client, PoseStack.Pose pose, VertexConsumer vertices, Vec3 center) {
        int minGridX = (int) Math.ceil(-eggSacPredictionHalfX / PREDICTION_GRID_SPACING);
        int maxGridX = (int) Math.floor(eggSacPredictionHalfX / PREDICTION_GRID_SPACING);
        int minGridZ = (int) Math.ceil(-eggSacPredictionHalfZ / PREDICTION_GRID_SPACING);
        int maxGridZ = (int) Math.floor(eggSacPredictionHalfZ / PREDICTION_GRID_SPACING);
        double halfY = Math.min(PREDICTION_GRID_HALF_SIZE, Math.max(0.10, eggSacPredictionHalfY * 0.4));
        for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
            for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
                // The corners of the square never carried a sac, so only the disc is drawn.
                if (Math.hypot(gridX * PREDICTION_GRID_SPACING, gridZ * PREDICTION_GRID_SPACING)
                    > EGG_SAC_PREDICTION_RADIUS) {
                    continue;
                }

                double x = center.x + gridX * PREDICTION_GRID_SPACING;
                double z = center.z + gridZ * PREDICTION_GRID_SPACING;
                double y = gridYFor(client, x, center.y, z);
                drawBox(
                    pose,
                    vertices,
                    x - PREDICTION_GRID_HALF_SIZE,
                    y - halfY,
                    z - PREDICTION_GRID_HALF_SIZE,
                    x + PREDICTION_GRID_HALF_SIZE,
                    y + halfY,
                    z + PREDICTION_GRID_HALF_SIZE,
                    PREDICTION_RED,
                    PREDICTION_GREEN,
                    PREDICTION_BLUE,
                    PREDICTION_GRID_ALPHA
                );
            }
        }
    }

    private double gridYFor(Minecraft client, double x, double normalY, double z) {
        if (client.level == null) {
            return normalY;
        }

        for (int step = 0; step <= PREDICTION_GRID_MAX_LOWERED_STEPS; step++) {
            double candidateY = normalY - step;
            if (!isGridSlotBlocked(client, x, candidateY, z)) {
                return candidateY;
            }
        }

        return normalY - PREDICTION_GRID_MAX_LOWERED_STEPS;
    }

    private boolean isGridSlotBlocked(Minecraft client, double x, double centerY, double z) {
        int baseY = (int) Math.floor(centerY);
        int topY = (int) Math.floor(centerY + PREDICTION_GRID_BLOCK_SCAN_ABOVE);
        for (int y = baseY; y <= topY; y++) {
            BlockPos pos = BlockPos.containing(x, y, z);
            BlockState state = client.level.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(client.level, pos).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private void drawBox(
        PoseStack.Pose pose,
        VertexConsumer vertices,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int red,
        int green,
        int blue,
        int alpha
    ) {
        addQuad(pose, vertices, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        addQuad(pose, vertices, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        addQuad(pose, vertices, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, red, green, blue, alpha);
        addQuad(pose, vertices, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, red, green, blue, alpha);
        addQuad(pose, vertices, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        addQuad(pose, vertices, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ, red, green, blue, alpha);
    }

    private void addQuad(
        PoseStack.Pose pose,
        VertexConsumer vertices,
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2,
        double x3,
        double y3,
        double z3,
        double x4,
        double y4,
        double z4,
        int red,
        int green,
        int blue,
        int alpha
    ) {
        addVertex(pose, vertices, x1, y1, z1, red, green, blue, alpha);
        addVertex(pose, vertices, x2, y2, z2, red, green, blue, alpha);
        addVertex(pose, vertices, x3, y3, z3, red, green, blue, alpha);
        addVertex(pose, vertices, x4, y4, z4, red, green, blue, alpha);
    }

    private void addVertex(
        PoseStack.Pose pose,
        VertexConsumer vertices,
        double x,
        double y,
        double z,
        int red,
        int green,
        int blue,
        int alpha
    ) {
        vertices.addVertex(pose, (float) x, (float) y, (float) z)
            .setColor(red, green, blue, alpha);
    }

    private enum DebugMessage {
        SLAYER_SPAWNED,
        SLAYER_POSITION,
        SLAYER_PHASE_CHANGE,
        SLAYER_DEAD,
        EGG_SAC_PHASE_START,
        EGG_SAC_PHASE_DONE
    }

    record EggSac(Vec3 center, double halfHeight) {
    }
}
