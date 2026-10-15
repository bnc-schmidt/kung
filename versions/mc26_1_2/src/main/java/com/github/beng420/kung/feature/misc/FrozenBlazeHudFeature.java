package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

public final class FrozenBlazeHudFeature extends ConfigurableFeature<MiscConfig> {
    public static final FrozenBlazeHudFeature INSTANCE = new FrozenBlazeHudFeature();
    /** Wiki: the aura stops after 30 seconds without the player moving their mouse and player; it needs both. */
    static final int AFK_TICKS = 30 * 20;
    private static final String HINT = "Walk + look to reactivate";
    private static final int WIDTH = 136;
    private static final int HEIGHT = 26;
    private String set;
    private Vec3 lastPos;
    private float lastYaw, lastPitch;
    private int moveIdle, lookIdle;
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
        // ponytail: client-side estimate of a server rule; no real on/off signal is proven yet, see trace().
        boolean first = lastPos == null;
        int before = idleTicks(moveIdle, lookIdle);
        moveIdle = advance(moveIdle, first || !player.position().equals(lastPos));
        lookIdle = advance(lookIdle, first || player.getYRot() != lastYaw || player.getXRot() != lastPitch);
        lastPos = player.position();
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
        if (config().frozenBlazeAlarmEnabled() && wentOff(before, idleTicks(moveIdle, lookIdle))) alarm(client, set);
        if (++traceTicks % 20 == 0) trace(player);
    }

    /**
     * Trace only, area "frozen-blaze": no source names a real on/off signal for the aura. The wiki only says it adds a
     * flame and snow particle effect; forum posts and other mods (Squid-Utils, bomboFabric) just guess from input. So
     * every candidate is logged once a second next to the idle counters; the one that stops when idle reaches 600
     * (30 s, shows as "quiet" or lost tags) is the signal to build the state on. Particles, sounds and outgoing move
     * packets near the player come in through observe*; lore, damage-tag armor stands and slowed mobs are read here.
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

    /** The aura just ran out; idle time saturates at AFK_TICKS, so this fires once until an input brings it back on. */
    static boolean wentOff(int idleBefore, int idleNow) { return idleBefore < AFK_TICKS && idleNow >= AFK_TICKS; }

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
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("id", "");
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
        seen.clear();
        traceTicks = 0;
        lastLore = "";
    }

    private void render(GuiGraphicsExtractor graphics) {
        var client = Minecraft.getInstance();
        if (set == null || McCompat.hudHidden(client) || KungHudEditorState.externalEditing()) return;
        draw(graphics, config(), set, idleTicks(moveIdle, lookIdle));
    }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, String set, int idleTicks) {
        var font = Minecraft.getInstance().font;
        boolean on = idleTicks < AFK_TICKS;
        var status = Component.literal(set + " ").withColor(set.equals("Blaze") ? 0xFFAA00 : 0x55FFFF)
            .append(on ? Component.literal("on").withColor(0x55FF55) : Component.literal("off").withColor(0xFF5555));
        if (on) status.append(Component.literal(" " + (AFK_TICKS - idleTicks + 19) / 20 + "s").withColor(0xAAAAAA));
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
        draw(graphics, config, "Frozen Blaze", AFK_TICKS);
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        float scale = config.frozenBlazeHudScale() / 100F;
        return new KungHudLayout.Bounds(config.frozenBlazeHudX(), config.frozenBlazeHudY(),
            Math.round(WIDTH * scale), Math.round(HEIGHT * scale));
    }
}
