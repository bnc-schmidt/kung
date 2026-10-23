package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.skyblock.SkyblockItemId;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

public final class FrozenBlazeHudFeature extends ConfigurableFeature<MiscConfig> {
    public static final FrozenBlazeHudFeature INSTANCE = new FrozenBlazeHudFeature();
    /** Wiki: the aura stops after 30 seconds without the player moving their mouse and player; it needs both. */
    static final int AFK_TICKS = 30 * 20;
    /** A pulse keeps the aura "on" past the idle estimate for this long; silence alone never means off (see auraOn). */
    static final int PULSE_TIMEOUT = 60;
    /** The off alarm needs the aura to have been on this long: a signal that keeps flickering must not nag every second. */
    static final int ALARM_MIN_ON_TICKS = 60;
    /** How far back the own positions reach: the server places the pulse where we were one round trip ago. */
    static final int TRAIL_TICKS = 20;
    private static final String HINT = "Walk + look to reactivate";
    private static final int WIDTH = 136;
    private static final int HEIGHT = 26;
    private String set;
    private Vec3 lastPos;
    private float lastYaw, lastPitch;
    private int moveIdle, lookIdle;
    /** Ticks since the last own aura pulse, -1 until one arrives. A pulse can only prove the aura is on. */
    private int sincePulse = -1;
    private int onTicks;
    private boolean wasOn;
    /** The own positions of the last ticks as x, y, z triples; read by the network thread, so it is replaced, never edited. */
    private volatile double[] trail = new double[0];
    /** Candidate signals of the current second: key -> {packets, summed count, nearest distance}; see trace. */
    private final Map<String, double[]> seen = new TreeMap<>();
    private int traceTicks;
    private String lastLore = "";
    private static final double TRACE_RANGE = 6;

    private FrozenBlazeHudFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (set != null && (text.contains("Frozen") || text.contains("Aura") || text.contains("Blaze")))
                KungDebugRecorder.event("frozen-blaze", (overlay ? "actionbar " : "message ") + text);
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "frozen_blaze_hud"), (graphics, delta) -> render(graphics));
        FrozenBlazeCapture.register();
    }

    @Override
    public boolean isEnabled() { return initialized() && config().frozenBlazeHudEnabled(); }

    private void tick(Minecraft client) {
        var player = client.player;
        set = isEnabled() && player != null ? setName(Stream.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
            .map(slot -> skyblockId(player.getItemBySlot(slot)))
            .toList()) : null;
        if (set == null) {
            onReset();
            return;
        }
        // The idle counters decide on/off (see auraOn); an own pulse (see observeParticles) can keep it on past them.
        boolean first = lastPos == null;
        moveIdle = advance(moveIdle, first || !player.position().equals(lastPos));
        lookIdle = advance(lookIdle, first || player.getYRot() != lastYaw || player.getXRot() != lastPitch);
        lastPos = player.position();
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
        if (sincePulse >= 0) sincePulse = Math.min(sincePulse + 1, PULSE_TIMEOUT + 1);
        trail = pushed(trail, player.getX(), player.getY(), player.getZ(), TRAIL_TICKS);
        boolean on = auraOn(sincePulse, idleTicks(moveIdle, lookIdle));
        if (config().frozenBlazeAlarmEnabled() && shouldAlarm(wasOn, on, onTicks)) alarm(client, set);
        onTicks = on ? onTicks + 1 : 0;
        wasOn = on;
        if (++traceTicks % 20 == 0) trace(player);
    }

    /**
     * Trace only, area "frozen-blaze". The signal is found (the own flame pulse, see observeParticles; /kung fbcapture
     * showed it stop at 30 s idle); this stays to check whether plain Blaze pulses the same way. Particles, sounds and
     * outgoing move packets near the player come in through observe*; lore, damage-tag armor stands and slowed mobs
     * are read here.
     */
    private void trace(Player player) {
        var tags = new ArrayList<String>();
        int stands = 0, slowed = 0;
        for (var entity : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(TRACE_RANGE), other -> other != player)) {
            if (entity instanceof ArmorStand) {
                if (!entity.hasCustomName()) continue;
                stands++;
                String name = entity.getCustomName().getString();
                if (damageTag(name) && tags.size() < 3) tags.add(name);
            } else if (entity.hasEffect(MobEffects.SLOWNESS)) slowed++;
        }
        KungDebugRecorder.event("frozen-blaze", "idle=" + moveIdle + "/" + lookIdle + " " + describe(seen)
            + " stands=" + stands + " tags=" + tags + " slowed=" + slowed);
        seen.clear();
        // The static set-bonus line; an on/off state in the lore would show up as a changed line.
        String lore = player.getItemBySlot(EquipmentSlot.CHEST).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream()
            .map(Component::getString).filter(line -> line.contains("Bonus") || line.contains("Aura")).collect(Collectors.joining(" | "));
        if (!lore.equals(lastLore)) KungDebugRecorder.event("frozen-blaze", "lore " + (lastLore = lore));
    }

    public void observeParticles(ClientboundLevelParticlesPacket packet) {
        var player = Minecraft.getInstance().player;
        if (set != null && player != null && packet.getParticle().getType() == ParticleTypes.FLAME && packet.getCount() == 8
            && (atPlayer(packet.getX() - player.getX(), packet.getY() - player.getY(), packet.getZ() - player.getZ())
                || atPlayerRecently(trail, packet.getX(), packet.getY(), packet.getZ())))
            sincePulse = 0;
        near("particle " + BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType()), packet.getCount(),
            packet.getX(), packet.getY(), packet.getZ());
    }

    public void observeSound(ClientboundSoundPacket packet) {
        near("sound " + BuiltInRegistries.SOUND_EVENT.getKey(packet.getSound().value()), 1, packet.getX(), packet.getY(), packet.getZ());
    }

    /** What the server sees from us: a vanilla client resends its position about every 20 ticks and rotation only on change. */
    public void observeSent(Packet<?> packet) {
        if (set == null || !(packet instanceof ServerboundMovePlayerPacket move)) return;
        String kind = (move.hasPosition() ? "pos" : "") + (move.hasRotation() ? "rot" : "");
        note(seen, "move " + (kind.isEmpty() ? "status" : kind), 1, 0);
    }

    private void near(String key, int count, double x, double y, double z) {
        var player = Minecraft.getInstance().player;
        if (set == null || player == null) return;
        double distance = Math.sqrt(player.distanceToSqr(x, y, z));
        if (distance <= TRACE_RANGE) note(seen, key, count, distance);
    }

    static void note(Map<String, double[]> seen, String key, int count, double distance) {
        double[] s = seen.computeIfAbsent(key, k -> new double[]{0, 0, Double.MAX_VALUE});
        s[0]++;
        s[1] += count;
        s[2] = Math.min(s[2], distance);
    }

    static String describe(Map<String, double[]> seen) {
        if (seen.isEmpty()) return "quiet";
        return seen.entrySet().stream().map(e -> String.format(Locale.ROOT, "%s x%.0f n=%.0f near=%.1f",
            e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).collect(Collectors.joining(", "));
    }

    /** Hypixel damage numbers float as named armor stands; mob nameplates carry [Lv..] and health hearts, so they are not tags. */
    static boolean damageTag(String name) { return name.chars().anyMatch(Character::isDigit) && name.indexOf(0x2764) < 0; }

    /** The aura just ran out; fires once until it comes back on. */
    static boolean wentOff(boolean before, boolean now) { return before && !now; }

    /** The off alarm: the aura just ran out after being on for a while, not after a flicker. */
    static boolean shouldAlarm(boolean before, boolean now, int onTicks) {
        return wentOff(before, now) && onTicks >= ALARM_MIN_ON_TICKS;
    }

    /**
     * The idle estimate decides: 30 s without walking and looking ends the aura. A recent own pulse overrules it, since
     * that is the server saying the aura works. The reverse does not hold: the aura sends a pulse only now and then
     * (the 2026-10-22 capture has gaps of 164, 475 and 1060 ticks while the player was active), so silence means nothing.
     */
    static boolean auraOn(int sincePulse, int idleTicks) {
        return idleTicks < AFK_TICKS || sincePulse >= 0 && sincePulse <= PULSE_TIMEOUT;
    }

    /** The own pulse is a flame burst at the player's chest (capture, standing still: 0.00 / +1.00 / 0.00); other players' auras send none. */
    static boolean atPlayer(double dx, double dy, double dz) {
        return Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5 && Math.abs(dy - 1) < 0.5;
    }

    /**
     * A pulse is sent where the server has the player, which is behind the client while walking: about one round trip
     * of movement, over a block when sprinting at a high ping. So it counts when it sits at any recent own position.
     */
    static boolean atPlayerRecently(double[] trail, double x, double y, double z) {
        for (int i = 0; i + 2 < trail.length; i += 3) {
            if (atPlayer(x - trail[i], y - trail[i + 1], z - trail[i + 2])) return true;
        }
        return false;
    }

    /** The trail with one more position, the oldest ones dropped beyond the limit. */
    static double[] pushed(double[] trail, double x, double y, double z, int limit) {
        int keep = Math.min(trail.length / 3, limit - 1) * 3;
        double[] next = new double[keep + 3];
        System.arraycopy(trail, trail.length - keep, next, 0, keep);
        next[keep] = x;
        next[keep + 1] = y;
        next[keep + 2] = z;
        return next;
    }

    private static void alarm(Minecraft client, String set) {
        McCompat.setTitleTimes(client, 0, 40, 10);
        McCompat.setTitle(client, Component.literal(set + " off").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        McCompat.setSubtitle(client, Component.literal(HINT).withStyle(ChatFormatting.GRAY));
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.6F, 1.0F));
    }

    /** One tick of an idle counter: any input resets it. */
    static int advance(int idle, boolean active) { return active ? 0 : Math.min(idle + 1, AFK_TICKS); }

    /** The aura needs walking and mouse movement, so the older of the two inputs decides when it runs out. */
    static int idleTicks(int moveIdle, int lookIdle) { return Math.max(moveIdle, lookIdle); }

    /** The SkyBlock item id of a stack ("" when the client has no custom data for it). */
    static String skyblockId(ItemStack stack) {
        return SkyblockItemId.of(stack);
    }

    /** SkyBlock armor IDs, helmet to boots; only a full set grants the aura. */
    static String setName(List<String> ids) {
        if (ids.stream().allMatch(id -> id.startsWith("FROZEN_BLAZE_"))) return "Frozen Blaze";
        if (ids.stream().allMatch(id -> id.startsWith("BLAZE_"))) return "Blaze";
        return null;
    }

    @Override
    protected void onReset() {
        set = null;
        lastPos = null;
        moveIdle = lookIdle = 0;
        sincePulse = -1;
        onTicks = 0;
        trail = new double[0];
        wasOn = false;
        seen.clear();
        traceTicks = 0;
        lastLore = "";
    }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (set == null || McCompat.hudHidden(client) || KungHudEditorState.externalEditing()) return;
        int idle = idleTicks(moveIdle, lookIdle);
        draw(graphics, config(), set, auraOn(sincePulse, idle), idle);
    }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, String set, boolean on, int idleTicks) {
        var font = Minecraft.getInstance().font;
        var status = Component.literal(set + " ").withColor(set.equals("Blaze") ? 0xFFAA00 : 0x55FFFF)
            .append(on ? Component.literal("on").withColor(0x55FF55) : Component.literal("off").withColor(0xFF5555));
        if (on && idleTicks < AFK_TICKS) status.append(Component.literal(" " + (AFK_TICKS - idleTicks + 19) / 20 + "s").withColor(0xAAAAAA));
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.frozenBlazeHudX(), config.frozenBlazeHudY());
            float scale = config.frozenBlazeHudScale() / 100F;
            graphics.pose().scale(scale, scale);
            graphics.text(font, status, 4, 4, 0xFFFFFFFF, true);
            if (!on) graphics.text(font, HINT, 4, 15, 0xFFAAAAAA, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, "Frozen Blaze", false, AFK_TICKS);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.frozenBlazeHudScale() / 100F;
        return new KungHudLayout.Bounds(config.frozenBlazeHudX(), config.frozenBlazeHudY(),
            Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
