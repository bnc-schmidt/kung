package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.feature.dungeon.DungeonServerTickEvents;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.runtime.KungFileLayout;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.ScalableParticleOptionsBase;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Developer capture for the Frozen Blaze aura, driven by /kung fbcapture and independent of the HUD toggle and of
 * wearing the set. No source names the server signal that says "the aura of THIS player is on", so a run logs
 * everything near the player that could carry it into one file: a per-second histogram of every packet type,
 * particles, sounds, mob effects, damage tags, the local player's entity data, nearby players' armor, chat and
 * titles, and what we send.
 *
 * Inbound packets arrive through {@code PacketUtilsMixin}: its RETURN is reached only on the thread that owns the
 * packet processor (the Netty pass queues the packet and throws), so every packet shows up once, on the client
 * thread, before its handler runs. The handlers that never ask PacketUtils for the client thread (keep-alive, pong,
 * chunk batch start/finish, combat enter/end, debug sample, low disk space, disconnect) are not seen. Outbound
 * packets come from {@code ClientCommonPacketListenerImpl.send}, which also runs on Netty (keep-alive replies), so
 * the state below sits behind {@link #LOCK}. While no capture runs, every hook is one volatile read.
 */
public final class FrozenBlazeCapture {
    public static final int DEFAULT_SECONDS = 120;
    public static final int MAX_SECONDS = 300;
    /** Suggestions for the mark command; any text works. */
    public static final List<String> MARKS = List.of("walking", "stand-still", "moving-again", "other-fb-player-near",
        "set-off", "plain-blaze", "own-and-foreign-fb");
    public static final List<String> USAGE = List.of(
        "/kung fbcapture start [seconds]: record everything near you (default 120, max 300, stops by itself).",
        "/kung fbcapture mark <label>: note a phase while it runs, e.g. stand-still, other-fb-player-near, set-off.",
        "/kung fbcapture stop: write logs/kung/kung-fbcapture-<time>.log, copy it and show the path.",
        "Runs: 1) FB set on, walking and looking, nobody near. 2) Stand still 35 s without moving the mouse"
            + " (aura should stop near 30 s), then move.",
        "3) Take the set off while another FB player stands nearby. 4) Plain Blaze set."
            + " 5) Your FB set and a foreign FB player close together.");

    static final int MAX_LINES = 6000;
    static final int MAX_LINE_LENGTH = 600;
    /** Header, state, packet summary, marks and footer carry the histograms, so they get a far later cut. */
    static final int MAX_FORCED_LINE_LENGTH = 4000;
    /** Lines of one particle or sound kind per second; the rest is counted into the PKTS line. */
    static final int PER_KIND_PER_SECOND = 8;
    /** Detail lines of all kinds together per second, so a busy second cannot use up the cap for the end of the run. */
    static final int DETAILS_PER_SECOND = 30;
    private static final double RANGE = 16;
    private static final double EFFECT_RANGE = 10;
    /** Half-width of the box searched for the entity closest to a particle. */
    private static final double OWNER_BOX = 6;
    private static final int PLAYERS_PER_SECOND = 4;
    private static final String[] AURA_WORDS = {"Frozen", "Aura", "Blaze", "Blazing"};
    private static final DateTimeFormatter FILE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private static final Object LOCK = new Object();
    /** Read by every hook without the lock; everything below is guarded by LOCK. */
    private static volatile boolean active;
    private static Lines lines = new Lines(MAX_LINES, MAX_LINE_LENGTH, MAX_FORCED_LINE_LENGTH);
    private static final Map<Class<?>, int[]> received = new HashMap<>();
    private static final Map<Class<?>, int[]> sentCounts = new HashMap<>();
    private static final Budget budget = new Budget(DETAILS_PER_SECOND);
    /** lastSecond is the client tick of the latest per-second block, so stop does not write the same one twice. */
    private static int tick, limitTicks, serverTicks, secondTicks, moveIdle, lookIdle, lastSecond;
    private static long startNanos;
    private static Vec3 lastPos;
    private static float lastYaw, lastPitch;
    private static double moved;

    private FrozenBlazeCapture() { }

    public static boolean active() { return active; }

    /** Called once from the Frozen Blaze feature's initialization. */
    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FrozenBlazeCapture::onClientTick);
        DungeonServerTickEvents.register(FrozenBlazeCapture::serverTick);
    }

    public static String start(Minecraft client, int seconds) {
        synchronized (LOCK) {
            if (active) return "Already running, " + (limitTicks - tick + 19) / 20 + " s left. /kung fbcapture stop ends it.";
            lines = new Lines(MAX_LINES, MAX_LINE_LENGTH, MAX_FORCED_LINE_LENGTH);
            received.clear();
            sentCounts.clear();
            budget.reset();
            tick = serverTicks = secondTicks = moveIdle = lookIdle = 0;
            lastSecond = -1;
            moved = 0;
            lastPos = null;
            limitTicks = clampSeconds(seconds) * 20;
            startNanos = System.nanoTime();
            header(client);
            active = true;
            return "Capturing for " + limitTicks / 20 + " s. Mark phases with /kung fbcapture mark <label>,"
                + " end early with /kung fbcapture stop.";
        }
    }

    public static String mark(String label) {
        synchronized (LOCK) {
            if (!active) return "No capture is running. Start one with /kung fbcapture start.";
            lines.force(stamp() + "MARK " + label);
            return "Marked: " + label;
        }
    }

    /** Ends the run, writes the file and copies the same text; the reply names the file and its line count. */
    public static String stop(Minecraft client, String reason) {
        String text;
        int count, dropped;
        synchronized (LOCK) {
            if (!active) return "No capture is running.";
            active = false;
            if (lastSecond != tick) {
                try {
                    second(client);
                } catch (RuntimeException exception) {
                    fail(exception);
                }
            }
            dropped = lines.dropped();
            lines.force(stamp() + footer(reason, tick, elapsedMillis(), lines.size() + 1, dropped));
            text = lines.text();
            count = lines.size();
        }
        client.keyboardHandler.setClipboard(text);
        String note = dropped > 0 ? " " + dropped + " detail lines were dropped at the cap." : "";
        try {
            return "Saved " + count + " lines to " + write(client, text) + " and copied them to the clipboard." + note;
        } catch (IOException exception) {
            return "Could not save the file (" + exception.getClass().getSimpleName() + "); the " + count
                + " lines are on the clipboard." + note;
        }
    }

    /** Every packet the client thread is about to handle; see the class comment for what this covers. */
    public static void inbound(Packet<?> packet, PacketListener listener) {
        if (!active || listener.flow() != PacketFlow.CLIENTBOUND) return;
        synchronized (LOCK) {
            if (!active) return;
            count(received, packet.getClass());
            var client = Minecraft.getInstance();
            var player = client.player;
            var level = client.level;
            if (player == null || level == null) return;
            // A diagnostic must never turn into a "packet could not be processed" disconnect.
            try {
                dispatch(player, level, packet);
            } catch (RuntimeException exception) {
                note("ERROR " + packet.getClass().getSimpleName() + " " + exception);
            }
        }
    }

    /** Runs on the client thread and on Netty; only counts, never touches the world. */
    public static void sent(Packet<?> packet) {
        if (!active) return;
        synchronized (LOCK) {
            if (active) count(sentCounts, packet.getClass());
        }
    }

    private static void serverTick() {
        if (!active) return;
        synchronized (LOCK) {
            if (active) serverTicks++;
        }
    }

    private static void onClientTick(Minecraft client) {
        if (!active) return;
        String reason = null;
        synchronized (LOCK) {
            if (!active) return;
            // A diagnostic must never crash the client: any failure ends the capture and is written to the file.
            try {
                tick++;
                sample(client.player);
                if (tick % 20 == 0) second(client);
                if (tick >= limitTicks) reason = "auto";
            } catch (RuntimeException exception) {
                fail(exception);
                reason = "error";
            }
        }
        if (reason != null) {
            String result = stop(client, reason);
            if (client.player != null) client.player.sendSystemMessage(KungMessages.info("FB capture", result));
        }
    }

    private static void fail(RuntimeException exception) {
        lines.force(stamp() + "ERROR " + exception.getClass().getSimpleName() + " " + exception.getMessage());
    }

    /** Idle counters the way the HUD computes them, but without its toggle or full-set gate. */
    private static void sample(Player player) {
        secondTicks++;
        if (player == null) {
            lastPos = null;
            return;
        }
        Vec3 pos = player.position();
        boolean first = lastPos == null;
        moveIdle = FrozenBlazeHudFeature.advance(moveIdle, first || !pos.equals(lastPos));
        lookIdle = FrozenBlazeHudFeature.advance(lookIdle, first || player.getYRot() != lastYaw || player.getXRot() != lastPitch);
        if (!first) moved += Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
        lastPos = pos;
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
    }

    private static void header(Minecraft client) {
        var player = client.player;
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("?");
        lines.force(stamp() + "HEADER kung=" + KungUpdater.currentVersion() + " mc=" + mc
            + " flavor=" + (KungBuild.MODRINTH ? "modrinth" : "full") + " limit=" + limitTicks / 20 + "s"
            + " player=" + (player == null ? "-" : player.getName().getString()));
        if (player != null) {
            List<String> ids = armorIds(player);
            lines.force(stamp() + "HEADER armor=" + ids + " set=" + orDash(FrozenBlazeHudFeature.setName(ids))
                + " held=" + item(player.getMainHandItem()) + "/" + orDash(FrozenBlazeHudFeature.skyblockId(player.getMainHandItem()))
                + " pos=" + position(player.position())
                + " particles=" + client.options.particles().get());
        }
        lines.force(stamp() + "HEADER inbound=every packet whose handler asks PacketUtils for the client thread"
            + " (not KeepAlive, PongResponse, ChunkBatchStart/Finished, PlayerCombatEnter/End, DebugSample,"
            + " LowDiskSpaceWarning, Disconnect); outbound=ClientCommonPacketListenerImpl.send;"
            + " quiet=no detail line that second; detail=detail lines seen that second; more=detail lines left out by kind"
            + " (particles and sounds over " + PER_KIND_PER_SECOND + " per kind, every kind over " + DETAILS_PER_SECOND
            + " together)");
    }

    private static void dispatch(Player self, ClientLevel level, Packet<?> packet) {
        if (packet instanceof ClientboundLevelParticlesPacket particles) particle(self, level, particles);
        else if (packet instanceof ClientboundSoundPacket sound) sound(self, sound);
        else if (packet instanceof ClientboundUpdateMobEffectPacket update) {
            effect(self, level, update.getEntityId(), update.getEffect(),
                "add amp=" + update.getEffectAmplifier() + " dur=" + update.getEffectDurationTicks() + "t"
                    + " ambient=" + update.isEffectAmbient() + " visible=" + update.isEffectVisible());
        } else if (packet instanceof ClientboundRemoveMobEffectPacket remove) {
            effect(self, level, remove.entityId(), remove.effect(), "remove");
        } else if (packet instanceof ClientboundSetEntityDataPacket data) entityData(self, level, data);
        else if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            if (attributes.getEntityId() == self.getId()) {
                for (var snapshot : attributes.getValues()) {
                    var modifiers = snapshot.modifiers().stream().limit(6)
                        .map(modifier -> modifier.id() + "=" + modifier.amount() + " " + modifier.operation()).toList();
                    note("ATTR self " + snapshot.attribute().getRegisteredName() + " base=" + snapshot.base()
                        + " modifiers=" + snapshot.modifiers().size() + modifiers);
                }
            }
        } else if (packet instanceof ClientboundEntityEventPacket event) {
            if (event.getEntity(level) == self) note("EVENT self id=" + event.getEventId());
        } else if (packet instanceof ClientboundSystemChatPacket chat) {
            text(chat.overlay() ? "ACTIONBAR" : "CHAT", chat.content());
        } else if (packet instanceof ClientboundSetActionBarTextPacket bar) text("ACTIONBAR", bar.text());
        else if (packet instanceof ClientboundSetTitleTextPacket title) text("TITLE", title.text());
        else if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) text("SUBTITLE", subtitle.text());
    }

    private static void particle(Player self, ClientLevel level, ClientboundLevelParticlesPacket packet) {
        double dx = packet.getX() - self.getX(), dy = packet.getY() - self.getY(), dz = packet.getZ() - self.getZ();
        if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) return;
        String id = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType()));
        if (!budget.room("PARTICLE " + id, PER_KIND_PER_SECOND)) return;
        add(String.format(Locale.ROOT, "PARTICLE %s opt=%s n=%d dist=(%.2f,%.2f,%.2f) speed=%.3f limiter=%d always=%d %s near=%s",
            id, options(packet.getParticle()), packet.getCount(), packet.getXDist(), packet.getYDist(), packet.getZDist(),
            packet.getMaxSpeed(), packet.isOverrideLimiter() ? 1 : 0, packet.alwaysShow() ? 1 : 0,
            relative(dx, dy, dz), closest(self, level, packet.getX(), packet.getY(), packet.getZ())));
    }

    private static void sound(Player self, ClientboundSoundPacket packet) {
        double dx = packet.getX() - self.getX(), dy = packet.getY() - self.getY(), dz = packet.getZ() - self.getZ();
        if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) return;
        String id = packet.getSound().getRegisteredName();
        if (!budget.room("SOUND " + id, PER_KIND_PER_SECOND)) return;
        add(String.format(Locale.ROOT, "SOUND %s source=%s volume=%.2f pitch=%.2f %s",
            id, packet.getSource().getName(), packet.getVolume(), packet.getPitch(), relative(dx, dy, dz)));
    }

    private static void effect(Player self, ClientLevel level, int entityId, Holder<MobEffect> effect, String change) {
        Entity entity = level.getEntity(entityId);
        if (entity == null) return;
        double distance = Math.sqrt(self.distanceToSqr(entity));
        if (distance > EFFECT_RANGE) return;
        note(String.format(Locale.ROOT, "EFFECT %s %s d=%.1f id=%d %s",
            who(entity, self), effect.getRegisteredName(), distance, entityId, change));
    }

    /** The local player's data items are dumped as they change; named armor stands within range are damage-tag candidates. */
    private static void entityData(Player self, ClientLevel level, ClientboundSetEntityDataPacket packet) {
        if (packet.id() == self.getId()) {
            for (var item : packet.packedItems()) {
                Object value = item.value();
                note("DATA self id=" + item.id() + " " + (value == null ? "null" : value.getClass().getSimpleName())
                    + "=" + KungDebugRecorder.compact(String.valueOf(value)));
            }
            return;
        }
        if (!(level.getEntity(packet.id()) instanceof ArmorStand stand)) return;
        double distance = Math.sqrt(self.distanceToSqr(stand));
        if (distance > EFFECT_RANGE) return;
        for (var item : packet.packedItems()) {
            if (item.value() instanceof Optional<?> name && name.orElse(null) instanceof Component component) {
                String text = KungDebugRecorder.compact(component.getString());
                if (FrozenBlazeHudFeature.damageTag(text)) {
                    note(String.format(Locale.ROOT, "DMG %s d=%.1f id=%d", text, distance, packet.id()));
                }
            }
        }
    }

    private static void text(String kind, Component content) {
        String text = KungDebugRecorder.compact(content.getString());
        if (hasAuraWord(text)) note(kind + " " + text);
    }

    /** Once a second: own state, nearby players, then the packet histograms of that second. */
    private static void second(Minecraft client) {
        var player = client.player;
        var level = client.level;
        if (player == null || level == null) {
            lines.force(stamp() + "STATE no-player");
        } else {
            int near5 = 0, near8 = 0;
            for (var entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(8))) {
                if (entity == player || entity instanceof Player || entity instanceof ArmorStand) continue;
                double distance = Math.sqrt(player.distanceToSqr(entity));
                if (distance <= 8) near8++;
                if (distance <= 5) near5++;
            }
            lines.force(String.format(Locale.ROOT, "%sSTATE idle=%d/%d speed=%.2fb/s sprint=%d sneak=%d hp=%.1f/%.1f mobs5=%d mobs8=%d pos=%s",
                stamp(), moveIdle, lookIdle, moved * 20 / Math.max(1, secondTicks), player.isSprinting() ? 1 : 0,
                player.isShiftKeyDown() ? 1 : 0, player.getHealth(), player.getMaxHealth(), near5, near8, position(player.position())));
            var others = new ArrayList<Player>();
            for (var other : level.players()) {
                if (other != player && player.distanceToSqr(other) <= RANGE * RANGE) others.add(other);
            }
            others.sort(Comparator.comparingDouble(other -> player.distanceToSqr(other)));
            for (var other : others.subList(0, Math.min(PLAYERS_PER_SECOND, others.size()))) {
                var slots = Stream.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                    .map(other::getItemBySlot).toList();
                lines.force(String.format(Locale.ROOT, "%sPLAYER %s %s d=%.1f items=%s %s", stamp(),
                    other.getUUID().toString().substring(0, 8), other.getName().getString(), Math.sqrt(player.distanceToSqr(other)),
                    slots.stream().map(FrozenBlazeCapture::item).toList(),
                    armorSummary(slots.stream().map(FrozenBlazeHudFeature::skyblockId).toList())));
            }
            if (others.size() > PLAYERS_PER_SECOND) {
                lines.force(stamp() + "PLAYER +" + (others.size() - PLAYERS_PER_SECOND) + " more within " + (int) RANGE);
            }
        }
        lines.force(stamp() + packets(budget, received, sentCounts));
        received.clear();
        sentCounts.clear();
        budget.reset();
        secondTicks = 0;
        moved = 0;
        lastSecond = tick;
    }

    /** The entity (not a nameplate stand) nearest to a packet position, as "who@distance". */
    private static String closest(Player self, ClientLevel level, double x, double y, double z) {
        Entity best = null;
        double bestSqr = Double.MAX_VALUE;
        var box = new AABB(x - OWNER_BOX, y - OWNER_BOX, z - OWNER_BOX, x + OWNER_BOX, y + OWNER_BOX, z + OWNER_BOX);
        for (var entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity instanceof ArmorStand) continue;
            double sqr = entity.distanceToSqr(x, y, z);
            if (sqr < bestSqr) {
                best = entity;
                bestSqr = sqr;
            }
        }
        return best == null ? "-" : String.format(Locale.ROOT, "%s@%.1f", who(best, self), Math.sqrt(bestSqr));
    }

    private static String who(Entity entity, Player self) {
        if (entity == self) return "self";
        if (entity instanceof Player other) return "player:" + other.getName().getString();
        return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).replace("minecraft:", "");
    }

    /** Options class plus whatever it carries that could tell the aura's particles from other effects. */
    private static String options(ParticleOptions option) {
        var out = new StringBuilder(option.getClass().getSimpleName());
        if (option instanceof ColorParticleOption color) {
            out.append(String.format(Locale.ROOT, " color=%s alpha=%.2f", rgb(color.getRed(), color.getGreen(), color.getBlue()), color.getAlpha()));
        } else if (option instanceof SpellParticleOption spell) {
            out.append(String.format(Locale.ROOT, " color=%s power=%.2f", rgb(spell.getRed(), spell.getGreen(), spell.getBlue()), spell.getPower()));
        } else if (option instanceof DustParticleOptions dust) {
            out.append(" color=").append(rgb(dust.getColor().x, dust.getColor().y, dust.getColor().z));
        } else if (option instanceof DustColorTransitionOptions dust) {
            out.append(" from=").append(rgb(dust.getFromColor().x, dust.getFromColor().y, dust.getFromColor().z))
                .append(" to=").append(rgb(dust.getToColor().x, dust.getToColor().y, dust.getToColor().z));
        } else if (option instanceof BlockParticleOption block) {
            out.append(" block=").append(BuiltInRegistries.BLOCK.getKey(block.getState().getBlock()));
        } else if (option instanceof ItemParticleOption item) {
            out.append(" item=").append(item.getItem().item().getRegisteredName());
        } else if (option instanceof PowerParticleOption power) {
            out.append(String.format(Locale.ROOT, " power=%.2f", power.getPower()));
        } else if (option instanceof VibrationParticleOption vibration) {
            out.append(" arrival=").append(vibration.getArrivalInTicks());
        } else if (option instanceof ShriekParticleOption shriek) {
            out.append(" delay=").append(shriek.getDelay());
        }
        if (option instanceof ScalableParticleOptionsBase scalable) {
            out.append(String.format(Locale.ROOT, " scale=%.2f", scalable.getScale()));
        }
        return out.toString();
    }

    private static String rgb(float red, float green, float blue) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(red * 255), Math.round(green * 255), Math.round(blue * 255));
    }

    private static List<String> armorIds(Player player) {
        return Stream.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
            .map(slot -> FrozenBlazeHudFeature.skyblockId(player.getItemBySlot(slot))).toList();
    }

    private static String item(ItemStack stack) { return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())); }

    private static String position(Vec3 pos) {
        return String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)", pos.x, pos.y, pos.z);
    }

    private static String orDash(String value) { return value == null || value.isEmpty() ? "-" : value; }

    /** A detail line of a kind named by its first word; it is written only while this second's total budget lasts. */
    private static void note(String line) {
        if (budget.room(kindOf(line), DETAILS_PER_SECOND)) add(line);
    }

    private static void add(String line) { lines.add(stamp() + line); }

    private static String stamp() { return stamp(tick, elapsedMillis(), serverTicks); }

    private static long elapsedMillis() { return (System.nanoTime() - startNanos) / 1_000_000L; }

    private static Path write(Minecraft client, String text) throws IOException {
        Path gameDirectory = client.gameDirectory.toPath();
        Path directory = new KungFileLayout(gameDirectory, gameDirectory.resolve("config")).logDirectory();
        Files.createDirectories(directory);
        Path path = directory.resolve("kung-fbcapture-" + FILE_FORMAT.format(Instant.now()) + ".log");
        // getBytes replaces a stray surrogate, where writeString would throw and lose the file.
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        return path.toAbsolutePath();
    }

    // Pure helpers below; package-private so the tests can reach them without a game.

    static int clampSeconds(int seconds) { return Math.clamp(seconds, 1, MAX_SECONDS); }

    /** Client ticks, milliseconds and server ticks since the start, zero padded so lines sort and align. */
    static String stamp(int ticks, long millis, int serverTicks) {
        return String.format(Locale.ROOT, "[t=%04d +%06dms s=%04d] ", ticks, millis, serverTicks);
    }

    static String footer(String reason, int ticks, long millis, int lineCount, int dropped) {
        return "END reason=" + reason + " ticks=" + ticks + " ms=" + millis + " lines=" + lineCount + " dropped=" + dropped
            + (dropped > 0 ? " INCOMPLETE: detail lines past the cap of " + MAX_LINES + " were counted but not written" : "");
    }

    static void count(Map<Class<?>, int[]> counts, Class<?> type) { counts.computeIfAbsent(type, key -> new int[1])[0]++; }

    /** "total=N Name=n ..." with the busiest type first; a nested class keeps its outer name (Pos is hard to grep). */
    static String histogram(Map<Class<?>, int[]> counts) {
        if (counts.isEmpty()) return "none";
        int total = counts.values().stream().mapToInt(count -> count[0]).sum();
        return "total=" + total + counts.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Class<?>, int[]>>comparingInt(entry -> -entry.getValue()[0])
                .thenComparing(entry -> className(entry.getKey())))
            .map(entry -> " " + className(entry.getKey()) + "=" + entry.getValue()[0])
            .collect(Collectors.joining());
    }

    static String className(Class<?> type) {
        String name = type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
        Class<?> outer = type.getEnclosingClass();
        return outer == null ? name : outer.getSimpleName() + "." + name;
    }

    /** The per-second packet line. Detail and more come first: only the histograms grow with the packet types. */
    static String packets(Budget details, Map<Class<?>, int[]> in, Map<Class<?>, int[]> out) {
        return "PKTS " + details.summary() + " in[" + histogram(in) + "] out[" + histogram(out) + "]";
    }

    static String kindOf(String line) {
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }

    /** True while the kind is within its limit this second; the overflow is summed up by {@link #overflow}. */
    static boolean within(Map<String, int[]> counts, String kind, int limit) {
        return ++counts.computeIfAbsent(kind, key -> new int[1])[0] <= limit;
    }

    static String overflow(Map<String, int[]> counts, int limit) {
        String more = new TreeMap<>(counts).entrySet().stream().filter(entry -> entry.getValue()[0] > limit)
            .map(entry -> entry.getKey() + "+" + (entry.getValue()[0] - limit)).collect(Collectors.joining(", "));
        return more.isEmpty() ? "" : " more=" + more;
    }

    static String relative(double dx, double dy, double dz) {
        return String.format(Locale.ROOT, "rel=(%+.2f,%+.2f,%+.2f) h=%.2f", dx, dy, dz, Math.hypot(dx, dz));
    }

    /** Skyblock ids of the four armor slots; an all-empty list means the client entity carries no custom data. */
    static String armorSummary(List<String> ids) {
        if (ids.stream().allMatch(String::isEmpty)) return "ids=unreadable";
        return "ids=" + ids.stream().map(id -> id.isEmpty() ? "-" : id).toList()
            + " frozenBlaze=" + ids.stream().filter(id -> id.startsWith("FROZEN_BLAZE_")).count()
            + " blaze=" + ids.stream().filter(id -> id.startsWith("BLAZE_")).count()
            + " set=" + orDash(FrozenBlazeHudFeature.setName(ids));
    }

    static boolean hasAuraWord(String text) {
        for (String word : AURA_WORDS) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** What one second may write as detail lines: a limit per kind, a total over all kinds, the rest counted by kind. */
    static final class Budget {
        private final int total;
        private final Map<String, int[]> seen = new HashMap<>(), left = new HashMap<>();
        private int kept, details;

        Budget(int total) { this.total = total; }

        /** Counts one detail line; true while it fits both its kind's limit and the total of this second. */
        boolean room(String kind, int kindLimit) {
            details++;
            if (within(seen, kind, kindLimit) && kept < total) {
                kept++;
                return true;
            }
            left.computeIfAbsent(kind, key -> new int[1])[0]++;
            return false;
        }

        /** "quiet", or "detail=N" (all seen) with " more=KIND+n, ..." for what was left out. */
        String summary() { return details == 0 ? "quiet" : "detail=" + details + overflow(left, 0); }

        void reset() {
            seen.clear();
            left.clear();
            kept = details = 0;
        }
    }

    /** Bounded output: past the cap a detail line is only counted, while structure lines always get in. */
    static final class Lines {
        private final int max, maxLength, maxForcedLength;
        private final List<String> items = new ArrayList<>();
        private int dropped;

        Lines(int max, int maxLength, int maxForcedLength) {
            this.max = max;
            this.maxLength = maxLength;
            this.maxForcedLength = maxForcedLength;
        }

        void add(String line) {
            if (items.size() >= max) dropped++;
            else items.add(clip(line, maxLength));
        }

        /** Header, per-second summaries, marks and footer: a few hundred lines at most, so the cap does not apply. */
        void force(String line) { items.add(clip(line, maxForcedLength)); }

        int size() { return items.size(); }

        int dropped() { return dropped; }

        String text() { return String.join("\n", items) + "\n"; }

        /** One line of at most limit chars; the cut never leaves half of a surrogate pair. */
        private static String clip(String line, int limit) {
            String flat = line.replace('\r', ' ').replace('\n', ' ');
            if (flat.length() <= limit) return flat;
            int end = limit - 3;
            if (end > 0 && Character.isHighSurrogate(flat.charAt(end - 1))) end--;
            return flat.substring(0, end) + "...";
        }
    }
}
