package com.github.beng420.kung.feature.slayer;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.SlayerConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The Hellion Shield of every Inferno Demonlord and its demons (Quazii, Typhoeus) near the player as text on the bodies
 * themselves: the attunement and the dagger hits left. Hypixel writes both on a stand beside each body. The text reads
 * that stand and takes the body's interpolated position when the frame is extracted, so it neither lags nor stutters
 * behind a stand that only moves with the server's ticks.
 *
 * <p>Whose boss it is does not matter: since Hypixel dropped the "Spawned by" stand there is no telling, and a boss
 * next to ours is as good to read. Bosses and demons are tracked well beyond the text's range, so a demon phase is seen
 * from its first demon to its last.
 *
 * <p>It is drawn in the HUD at the projected position of the body. Text in the world is sorted among the entities,
 * so particles, fire and water drawn after it painted over parts of it; the HUD comes last, nothing covers it.
 */
public final class BlazeSlayerFeature extends ConfigurableFeature<SlayerConfig> {
    public static final BlazeSlayerFeature INSTANCE = new BlazeSlayerFeature();

    private static final RenderStateDataKey<List<Label>> LABELS = RenderStateDataKey.create(() -> "kung:blaze-attunement");
    /** Mobs are tracked out to here. */
    private static final double SEARCH_RANGE = 48.0;
    /** The text is drawn only on mobs this close to the player. */
    private static final double LABEL_RANGE = 10.0;
    /** A body's stands hover in its column; the stand may trail a teleport by a tick, so the column is generous. */
    private static final double COLUMN_RANGE = 2.5;
    private static final double STAND_BELOW = 1.5;
    private static final double STAND_ABOVE = 5.0;
    /**
     * The stand that names a body is spawned right after it, so its id lies next to the body's. A plain Blaze beside
     * the boss can have the boss's stand in its column, but never in its id range.
     */
    private static final int MAX_NAME_STAND_GAP = 12;
    /** Demons spawn where the boss stood; they belong to the fight whose boss was last seen closest. */
    private static final double DEMON_RANGE = 40.0;
    /** What a boss held before its demons is kept this long after it was last seen. */
    private static final long FIGHT_MEMORY_MILLIS = 120_000L;
    /** A stand that blinks away for a tick (the body teleported) must not blink the text. */
    private static final long TAG_GRACE_MILLIS = 500L;
    /** Share of the body's height the text sits at. */
    private static final double TEXT_HEIGHT = 0.6;
    /** Text size in GUI pixels per font pixel: bigger near, smaller far, never unreadable. */
    private static final double NEAR_SCALE_DISTANCE = 14.0;
    private static final double MIN_DISTANCE_SCALE = 0.8;
    private static final double MAX_DISTANCE_SCALE = 1.6;
    /** A frame older than this was not drawn by the level (loading screen, paused render): show nothing. */
    private static final long FRAME_MAX_AGE_NANOS = 150_000_000L;

    private final Map<Integer, Actor> actors = new HashMap<>();
    /** One per boss body: what the boss held before its demons came, and where its demons are in the fight. */
    private final Map<Integer, Fight> fights = new HashMap<>();
    /** Bosses we cocooned: the body can outlive the fight, and the next boss spawns beside it. */
    private final Set<Integer> cocooned = new HashSet<>();
    private final Map<Integer, BlazeShield.Kind> claimed = new HashMap<>();
    /** Name stand -> the body it named. It stays while the body hides, so a Blaze that walks into the column is not it. */
    private final Map<Integer, Integer> nameStandOwners = new HashMap<>();
    private boolean projectionChecked;
    private Frame frame = Frame.EMPTY;

    private BlazeSlayerFeature() {
        super(cfg -> cfg.slayer);
    }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> observeMessage(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
            observeMessage(message.getString()));
        LevelRenderEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.COLLECT_SUBMITS.register(this::capture);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "blaze_attunement"), (graphics, delta) -> draw(graphics));
    }

    @Override
    public boolean isEnabled() {
        return config().blazeAttunementEnabled();
    }

    private void observeMessage(String rawMessage) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null) {
            return;
        }

        String message = clean(rawMessage);
        if (message.contains("cocooned your slayer boss")) {
            // The cocoon ends the fight like a kill, but its body can linger beside the boss that spawns next.
            Actor boss = nearestBoss(client, false);
            if (boss != null) {
                cocooned.add(boss.bodyId);
                actors.remove(boss.bodyId);
                fights.remove(boss.bodyId);
            }
            KungDebugRecorder.event("blaze", "cocooned");
            return;
        }

        BlazeShield.Attunement required = BlazeShield.strikeRequirement(message);
        Actor boss = required == null ? null : nearestBoss(client, true);
        if (boss != null) {
            boss.returning.strike(required);
            KungDebugRecorder.event("blaze", "strike wants " + required + " while holding");
        }
    }

    /** The boss in text range closest to the player; with {@code returningOnly} only one that is back from its demons. */
    private Actor nearestBoss(Minecraft client, boolean returningOnly) {
        Actor best = null;
        double bestDistance = LABEL_RANGE * LABEL_RANGE;
        for (Actor actor : actors.values()) {
            Entity body = client.level.getEntity(actor.bodyId);
            if (actor.kind != BlazeShield.Kind.BOSS || body == null || (returningOnly && actor.returning == null)) {
                continue;
            }
            double distance = client.player.distanceToSqr(body);
            if (distance <= bestDistance) {
                best = actor;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void clear() {
        actors.clear();
        fights.clear();
        cocooned.clear();
        claimed.clear();
        nameStandOwners.clear();
        projectionChecked = false;
        frame = Frame.EMPTY;
    }

    private void tick(Minecraft client) {
        if (!isEnabled() || client.level == null || client.player == null) {
            if (!actors.isEmpty() || !fights.isEmpty()) {
                clear();
            }
            return;
        }

        long now = System.currentTimeMillis();
        actors.values().removeIf(actor -> bodyOf(client, actor) == null);
        cocooned.removeIf(id -> client.level.getEntity(id) == null);
        claimed.keySet().removeIf(id -> client.level.getEntity(id) == null);
        nameStandOwners.keySet().removeIf(id -> client.level.getEntity(id) == null);
        fights.values().removeIf(fight -> now - fight.lastSeenMillis > FIGHT_MEMORY_MILLIS);

        List<ArmorStand> stands = new ArrayList<>();
        List<Entity> bodies = new ArrayList<>();
        double rangeSqr = SEARCH_RANGE * SEARCH_RANGE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand stand) {
                if (stand.hasCustomName() && client.player.distanceToSqr(stand) <= rangeSqr) {
                    stands.add(stand);
                }
            } else if (isBodyType(entity) && entity.isAlive() && client.player.distanceToSqr(entity) <= rangeSqr) {
                bodies.add(entity);
            }
        }
        bind(bodies, stands, now);

        for (Fight fight : fights.values()) {
            fight.demons = 0;
        }
        for (Actor actor : actors.values()) {
            Entity body = bodyOf(client, actor);
            if (body == null) {
                continue;
            }
            pickCarrier(client, actor, body, stands, now);
            if (actor.fight == null) {
                continue;
            }
            if (actor.kind == BlazeShield.Kind.BOSS) {
                actor.fight.lastPos = body.position();
                actor.fight.lastSeenMillis = now;
            } else {
                actor.fight.demons++;
            }
        }

        for (Fight fight : fights.values()) {
            trackDemonPhase(fight, now);
        }
        for (Actor actor : actors.values()) {
            remember(client.level, actor);
        }
    }

    private void bind(List<Entity> bodies, List<ArmorStand> stands, long now) {
        List<Candidate> candidates = new ArrayList<>();
        for (Entity body : bodies) {
            if (actors.containsKey(body.getId()) || cocooned.contains(body.getId())) {
                continue;
            }
            Candidate candidate = analyse(body, stands);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        // One name stand names one body: where two bodies stand in its column, the one whose id is next to it wins.
        candidates.sort(Comparator.comparingInt(Candidate::gap));
        for (Candidate candidate : candidates) {
            Integer owner = candidate.nameStandId() == -1 ? null : nameStandOwners.get(candidate.nameStandId());
            if (owner == null || owner == candidate.body().getId()) {
                add(candidate, now);
            }
        }
    }

    /**
     * What the stands around a body say it is. Every slayer boss and every demon is a body with three stands spawned
     * right after it: its name, its shield and "SLAYER BOSS" (a mini boss has "SLAYER MINIBOSS" there). So a body is one
     * of ours only when its column holds the name of a boss or demon and a "SLAYER BOSS" stand, both with ids next to
     * the body's, and no mini boss stand nearer. That keeps out every Blaze, Millennia-Aged Blaze and mini boss that
     * merely stands in a column. The boss is a Blaze and the demons are not. A boss we already knew may come back
     * before its stands do, and then its shield stand is proof enough.
     */
    private Candidate analyse(Entity body, List<ArmorStand> stands) {
        BlazeShield.Kind kind = null;
        String namedText = null;
        int nameStandId = -1;
        boolean shield = false;
        int nameGap = MAX_NAME_STAND_GAP + 1;
        int bossGap = MAX_NAME_STAND_GAP + 1;
        int miniGap = MAX_NAME_STAND_GAP + 1;
        List<String> names = new ArrayList<>();
        for (ArmorStand stand : stands) {
            if (!inColumn(stand, body)) {
                continue;
            }
            String text = plain(entityName(stand));
            int gap = Math.abs(stand.getId() - body.getId());
            names.add(text + "@" + gap);
            if (BlazeShield.kindOf(text) != null && gap < nameGap) {
                nameGap = gap;
                kind = BlazeShield.kindOf(text);
                namedText = text;
                nameStandId = stand.getId();
            }
            if (BlazeShield.isSlayerBossStand(text)) {
                bossGap = Math.min(bossGap, gap);
            } else if (BlazeShield.isMinibossStand(text)) {
                miniGap = Math.min(miniGap, gap);
            }
            shield |= BlazeShield.parse(text) != null;
        }

        List<String> shown = names.size() > 8 ? names.subList(0, 8) : names;
        boolean slayerBoss = bossGap <= MAX_NAME_STAND_GAP && bossGap < miniGap;
        if (kind != null && slayerBoss && (kind == BlazeShield.Kind.BOSS) == body instanceof Blaze) {
            String slot = kind == BlazeShield.Kind.BOSS ? "boss" : BlazeShield.slotOf(namedText);
            return new Candidate(body, kind, slot, shown, nameStandId, nameGap);
        }
        if (shield && fights.containsKey(body.getId())) {
            return new Candidate(body, BlazeShield.Kind.BOSS, "boss", shown, -1, 0);
        }
        return null;
    }

    private void add(Candidate candidate, long now) {
        Entity body = candidate.body();
        if (candidate.nameStandId() != -1) {
            nameStandOwners.putIfAbsent(candidate.nameStandId(), body.getId());
        }
        Actor actor = new Actor(body.getId(), candidate.kind(), candidate.slot());
        if (actor.kind == BlazeShield.Kind.BOSS) {
            Fight fight = fights.computeIfAbsent(body.getId(), Fight::new);
            fight.lastPos = body.position();
            fight.lastSeenMillis = now;
            actor.fight = fight;
            if (fight.memory != null && (fight.demonsSeen || fight.returnPending)) {
                actor.returning = new BlazeShield.Return(fight.memory, now);
                fight.returnPending = false;
            }
        } else {
            actor.fight = nearestFight(body.position());
            if (actor.fight != null) {
                actor.fight.demonsSeen = true;
            }
        }
        actors.put(body.getId(), actor);
        KungDebugRecorder.event("blaze", "bound " + candidate.slot() + " id=" + body.getId() + " type="
            + body.getType().toShortString() + " stands=" + candidate.names()
            + (actor.returning != null ? " holding " + actor.fight.memory : ""));
    }

    private Fight nearestFight(Vec3 at) {
        Fight best = null;
        double bestDistance = DEMON_RANGE * DEMON_RANGE;
        for (Fight fight : fights.values()) {
            double distance = fight.lastPos == null ? Double.MAX_VALUE : fight.lastPos.distanceToSqr(at);
            if (distance <= bestDistance) {
                best = fight;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * A shield stand belongs to the first mob that read it. The stand of a demon that just died stays in the boss's
     * column for a moment, frozen on the demon's last shield: the boss must not read it, and must take its own
     * stand the moment that appears instead of staying on the stale one until it is removed.
     */
    private boolean claimedByOther(ArmorStand stand, Actor actor) {
        BlazeShield.Kind owner = claimed.get(stand.getId());
        return owner != null && owner != actor.kind;
    }

    /**
     * The body's own shield stand, not the nearest one: the demons that just died may still have theirs standing
     * where the boss returns. Stands of one mob are spawned together, so their ids lie next to the body's.
     */
    private void pickCarrier(Minecraft client, Actor actor, Entity body, List<ArmorStand> stands, long now) {
        Entity current = actor.carrierId == -1 ? null : client.level.getEntity(actor.carrierId);
        if (current instanceof ArmorStand stand && inColumn(stand, body) && !claimedByOther(stand, actor)
            && BlazeShield.parse(plain(entityName(stand))) != null) {
            actor.carrierSeenMillis = now;
            return;
        }

        ArmorStand best = null;
        int bestGap = Integer.MAX_VALUE;
        for (ArmorStand stand : stands) {
            if (!inColumn(stand, body) || claimedByOther(stand, actor)
                || BlazeShield.parse(plain(entityName(stand))) == null) {
                continue;
            }
            int gap = Math.abs(stand.getId() - body.getId());
            if (gap < bestGap) {
                bestGap = gap;
                best = stand;
            }
        }
        if (best != null) {
            actor.carrierId = best.getId();
            actor.carrierSeenMillis = now;
            claimed.put(best.getId(), actor.kind);
        } else if (now - actor.carrierSeenMillis > TAG_GRACE_MILLIS) {
            actor.carrierId = -1;
            actor.reading = null;
        }
    }

    private void trackDemonPhase(Fight fight, long now) {
        Actor boss = actors.get(fight.bossId);
        if (fight.demons > 0) {
            fight.demonsSeen = true;
        } else if (fight.demonsSeen) {
            fight.demonsSeen = false;
            fight.returnPending = true;
            KungDebugRecorder.event("blaze", "demons gone, boss memory=" + fight.memory);
        }

        if (boss != null && boss.returning != null && boss.returning.released()) {
            boss.returning = null;
            KungDebugRecorder.event("blaze", "boss tag fresh again: " + boss.reading);
        }
        if (fight.returnPending && boss != null && fight.memory != null) {
            if (boss.returning == null) {
                boss.returning = new BlazeShield.Return(fight.memory, now);
                KungDebugRecorder.event("blaze", "boss back, holding " + fight.memory + " while the tag reads "
                    + boss.reading);
            }
            fight.returnPending = false;
        }
    }

    /** The boss's own last shield: what it shows again once the demons are gone while its tag lags. */
    private void remember(ClientLevel level, Actor actor) {
        BlazeShield.Reading tag = readTag(level, actor);
        if (tag != null && actor.kind == BlazeShield.Kind.BOSS && actor.fight != null && actor.returning == null
            && actor.fight.demons == 0) {
            actor.fight.memory = tag;
        }
    }

    /** The reading of the actor's stand right now; parsed again only when the stand's text changed. */
    private BlazeShield.Reading readTag(ClientLevel level, Actor actor) {
        Entity carrier = actor.carrierId == -1 ? null : level.getEntity(actor.carrierId);
        if (carrier == null || carrier.isRemoved()) {
            return actor.reading;
        }

        String name = plain(entityName(carrier));
        if (!name.equals(actor.tagText)) {
            actor.tagText = name;
            actor.reading = BlazeShield.parse(name);
            if (actor.reading != null && actor.reading.hits() == BlazeShield.Reading.NO_HITS
                && actor.reading.attunement() != BlazeShield.Attunement.IMMUNE) {
                KungDebugRecorder.event("blaze", "no hit count in " + actor.slot + " tag: " + name);
            }
        }
        return actor.reading;
    }

    private void extract(LevelExtractionContext context) {
        List<Label> labels = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        if (isEnabled() && !actors.isEmpty() && client.player != null) {
            var camera = context.camera();
            Vec3 eye = camera.position();
            float partialTick = camera.getCameraEntityPartialTicks(context.deltaTracker());
            double userScale = config().blazeAttunementScale() / 100.0;
            long now = System.currentTimeMillis();
            for (Actor actor : actors.values()) {
                Entity body = context.level().getEntity(actor.bodyId);
                if (body == null || body.isRemoved() || !body.isAlive()
                    || client.player.distanceToSqr(body) > LABEL_RANGE * LABEL_RANGE) {
                    continue;
                }

                BlazeShield.Reading tag = readTag(context.level(), actor);
                BlazeShield.Reading shown = actor.returning != null ? actor.returning.resolve(tag, now) : tag;
                if (shown == null) {
                    continue;
                }

                Vec3 at = body.getPosition(partialTick);
                double y = at.y + body.getBbHeight() * TEXT_HEIGHT;
                double distance = eye.distanceTo(new Vec3(at.x, y, at.z));
                float scale = (float) (userScale
                    * Math.clamp(NEAR_SCALE_DISTANCE / Math.max(distance, 1.0), MIN_DISTANCE_SCALE, MAX_DISTANCE_SCALE));
                labels.add(new Label(at.x, y, at.z, scale, text(shown)));
            }
        }
        // Each frame owns immutable labels, so a world change or disabling cannot leave old text behind.
        context.levelState().setData(LABELS, List.copyOf(labels));
    }

    private static Component text(BlazeShield.Reading reading) {
        MutableComponent text = Component.literal(reading.attunement().name())
            .withColor(reading.attunement().rgb).withStyle(ChatFormatting.BOLD);
        if (reading.hits() != BlazeShield.Reading.NO_HITS) {
            text.append(Component.literal(" " + reading.hits()).withColor(reading.attunement().rgb)
                .withStyle(ChatFormatting.BOLD));
        }
        return text;
    }

    /** Keeps this frame's labels with the matrices the level was drawn with; the HUD projects them last. */
    private void capture(LevelRenderContext context) {
        List<Label> labels = context.levelState().getDataOrDefault(LABELS, List.of());
        if (labels.isEmpty()) {
            frame = Frame.EMPTY;
            return;
        }

        CameraRenderState camera = context.levelState().cameraRenderState;
        Matrix4f clip = clipMatrix(camera, Minecraft.getInstance());
        if (!projectionChecked) {
            projectionChecked = true;
            checkProjection(camera, clip);
        }
        frame = new Frame(clip, camera.pos, labels, System.nanoTime());
    }

    /**
     * The matrix the world was drawn with: projection, then the camera's bobbing and hurt tilt, then its rotation.
     * GameRenderer builds it from private parts, so it is rebuilt here from the same snapshot values.
     */
    public static Matrix4f clipMatrix(CameraRenderState camera, Minecraft client) {
        CameraEntityRenderState entity = camera.entityRenderState;
        Matrix4f shake = new Matrix4f();
        if (entity.isLiving) {
            if (entity.isDeadOrDying) {
                shake.rotateZ(radians(40F - 8000F / (Math.min(entity.deathTime, 20F) + 200F)));
            }
            if (entity.hurtTime >= 0F && entity.hurtDuration > 0) {
                float hurt = entity.hurtTime / entity.hurtDuration;
                hurt = (float) Math.sin(hurt * hurt * hurt * hurt * Math.PI);
                shake.rotateY(radians(-entity.hurtDir))
                    .rotateZ(radians((float) (-hurt * 14.0 * client.options.damageTiltStrength().get())))
                    .rotateY(radians(entity.hurtDir));
            }
        }
        if (entity.isPlayer && client.options.bobView().get()) {
            float walk = entity.backwardsInterpolatedWalkDistance;
            float bob = entity.bob;
            float sin = (float) Math.sin(walk * Math.PI);
            float cos = (float) Math.cos(walk * Math.PI);
            shake.translate(sin * bob * 0.5F, -Math.abs(cos * bob), 0F)
                .rotateZ(radians(sin * bob * 3F))
                .rotateX(radians(Math.abs((float) Math.cos(walk * Math.PI - 0.2F) * bob) * 5F));
        }
        return new Matrix4f(camera.projectionMatrix).mul(shake).mul(camera.viewRotationMatrix);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** A point straight ahead must land in the middle of the screen; the trace shows whether the matrices are right. */
    private static void checkProjection(CameraRenderState camera, Matrix4f clip) {
        Vector3f ahead = camera.orientation.transform(new Vector3f(0F, 0F, -1F)).mul(8F);
        Vector4f point = new Vector4f(ahead.x, ahead.y, ahead.z, 1F);
        clip.transform(point);
        KungDebugRecorder.event("blaze", String.format(Locale.ROOT,
            "projection check: straight ahead lands at x=%.2f y=%.2f (0 is the screen centre), w=%.2f",
            point.x / point.w, point.y / point.w, point.w));
    }

    private void draw(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        Frame current = frame;
        if (current.labels().isEmpty() || !isEnabled() || McCompat.hudHidden(client)
            || System.nanoTime() - current.nanos() > FRAME_MAX_AGE_NANOS) {
            return;
        }

        Font font = client.font;
        double guiScale = client.getWindow().getGuiScale();
        for (Label label : current.labels()) {
            Vector4f point = new Vector4f((float) (label.x() - current.camera().x),
                (float) (label.y() - current.camera().y), (float) (label.z() - current.camera().z), 1F);
            current.clip().transform(point);
            if (point.w < 0.05F) {
                continue;
            }
            float ndcX = point.x / point.w;
            float ndcY = point.y / point.w;
            if (Math.abs(ndcX) > 1.3F || Math.abs(ndcY) > 1.3F) {
                continue;
            }

            // On the device pixel grid: crisp text that still moves in the smallest steps the screen has.
            float x = snap((ndcX * 0.5F + 0.5F) * graphics.guiWidth(), guiScale);
            float y = snap((0.5F - ndcY * 0.5F) * graphics.guiHeight(), guiScale);
            int width = font.width(label.text());
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(x, y);
                graphics.pose().scale(label.scale(), label.scale());
                graphics.text(font, label.text(), -width / 2, -4, 0xFFFFFFFF, true);
            } finally {
                graphics.pose().popMatrix();
            }
        }
    }

    private static float snap(float guiCoordinate, double guiScale) {
        return (float) (Math.round(guiCoordinate * guiScale) / guiScale);
    }

    private Entity bodyOf(Minecraft client, Actor actor) {
        Entity body = client.level.getEntity(actor.bodyId);
        double range = SEARCH_RANGE * SEARCH_RANGE;
        return body == null || !body.isAlive() || client.player.distanceToSqr(body) > range ? null : body;
    }

    private static boolean isBodyType(Entity entity) {
        return entity instanceof Blaze || entity instanceof WitherSkeleton || entity instanceof ZombifiedPiglin;
    }

    private static boolean inColumn(Entity stand, Entity body) {
        double dx = stand.getX() - body.getX();
        double dz = stand.getZ() - body.getZ();
        double above = stand.getY() - body.getY();
        return dx * dx + dz * dz <= COLUMN_RANGE * COLUMN_RANGE && above >= -STAND_BELOW && above <= STAND_ABOVE;
    }

    private static String entityName(Entity entity) {
        Component customName = entity.getCustomName();
        return customName != null ? customName.getString() : entity.getName().getString();
    }

    private static String plain(String text) {
        return text.replaceAll("(?:\\u00a7|\\u00c2\\u00a7).", "").replaceAll("\\s+", " ").trim();
    }

    private static String clean(String text) {
        return plain(text).toLowerCase(Locale.ROOT);
    }

    private static final class Actor {
        final int bodyId;
        final BlazeShield.Kind kind;
        /** "boss", "quazii" or "typhoeus", for the trace. */
        final String slot;
        /** The boss's own fight, or the fight whose boss was closest when this demon appeared; null when none was seen. */
        Fight fight;
        int carrierId = -1;
        long carrierSeenMillis;
        String tagText = "";
        BlazeShield.Reading reading;
        BlazeShield.Return returning;

        Actor(int bodyId, BlazeShield.Kind kind, String slot) {
            this.bodyId = bodyId;
            this.kind = kind;
            this.slot = slot;
        }
    }

    /** A boss with its demons: what it held before they came and when they are all gone. */
    private static final class Fight {
        Vec3 lastPos;
        long lastSeenMillis;
        BlazeShield.Reading memory;
        boolean demonsSeen;
        boolean returnPending;
        /** Demons alive in range this tick. */
        int demons;
        final int bossId;

        Fight(int bossId) {
            this.bossId = bossId;
        }
    }

    /** {@code gap} is the id distance between the body and its name stand, {@code nameStandId} -1 for a returning boss. */
    private record Candidate(Entity body, BlazeShield.Kind kind, String slot, List<String> names, int nameStandId,
                             int gap) {
    }

    private record Label(double x, double y, double z, float scale, Component text) {
    }

    private record Frame(Matrix4f clip, Vec3 camera, List<Label> labels, long nanos) {
        static final Frame EMPTY = new Frame(new Matrix4f(), Vec3.ZERO, List.of(), 0L);
    }
}
