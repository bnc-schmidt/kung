package com.github.beng420.kung.feature.safari;

import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Developer tracker for the Safari capsule throw, switched by {@code /kung dev safari}. It writes the launch, the
 * flight (one position per client tick) and the end of every capsule throw to the shared trace as
 * {@code safari-throw} lines, so the real physics can be fitted before any trajectory is drawn. It only watches.
 */
public final class SafariThrowTracker {
    static final String AREA = "safari-throw";

    private static final SafariThrowRecorder RECORDER =
        new SafariThrowRecorder(line -> KungDebugRecorder.event(AREA, line));
    /** Client thread only. */
    private static boolean on;

    private SafariThrowTracker() { }

    /** Called once from the Safari feature's initialization. */
    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SafariThrowTracker::tick);
        // A capsule can be thrown at a block in reach too, which the item callback alone would miss.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) use(player, hand);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) use(player, hand);
            return InteractionResult.PASS;
        });
        KungMessages.onGameText(message -> {
            if (on) RECORDER.message(message);
        });
    }

    public static String set(boolean enabled) {
        on = enabled;
        RECORDER.reset();
        return enabled
            ? "Throw tracker on. Throw capsules at many angles, then run /kung log save."
            : "Throw tracker off.";
    }

    private static void use(Player player, InteractionHand hand) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (!on || player != client.player || level == null) return;
        ItemStack stack = player.getItemInHand(hand);
        String name = HypixelLocation.stripFormatting(stack.getHoverName().getString());
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.contains("capsule") && !lower.contains("safari ball")) return;
        var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(player.getUUID());
        RECORDER.use(new Live(level), new SafariThrowRecorder.Launch(name, vec(player.position()), player.getEyeY(),
            player.getYRot(), player.getXRot(), vec(player.getDeltaMovement()), player.onGround(),
            player.isShiftKeyDown(), info == null ? -1 : info.getLatency()));
    }

    private static void tick(Minecraft client) {
        if (!on || client.player == null || client.level == null) return;
        RECORDER.tick(new Live(client.level));
    }

    private static SafariThrowRecorder.Vec vec(Vec3 value) {
        return new SafariThrowRecorder.Vec(value.x, value.y, value.z);
    }

    private record Live(ClientLevel level) implements SafariThrowRecorder.World {
        @Override
        public SafariThrowRecorder.Body get(int id) {
            Entity entity = level.getEntity(id);
            return entity == null || entity.isRemoved() ? null : body(entity);
        }

        @Override
        public List<SafariThrowRecorder.Body> near(SafariThrowRecorder.Vec center, double radius) {
            AABB box = new AABB(center.x() - radius, center.y() - radius, center.z() - radius,
                center.x() + radius, center.y() + radius, center.z() + radius);
            List<SafariThrowRecorder.Body> bodies = new ArrayList<>();
            for (Entity entity : level.getEntities((Entity) null, box)) {
                if (entity instanceof Player) continue;
                SafariThrowRecorder.Body body = body(entity);
                if (body.pos().distanceTo(center) <= radius) bodies.add(body);
            }
            return bodies;
        }

        @Override
        public String block(SafariThrowRecorder.Vec pos) {
            BlockPos at = BlockPos.containing(pos.x(), pos.y(), pos.z());
            return BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath() + "@"
                + at.getX() + "," + at.getY() + "," + at.getZ();
        }

        private static SafariThrowRecorder.Body body(Entity entity) {
            Component custom = entity.getCustomName();
            return new SafariThrowRecorder.Body(entity.getId(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), item(entity),
                custom == null ? "" : custom.getString(), vec(entity.position()), vec(entity.getDeltaMovement()),
                entity.getBbWidth(), entity.getBbHeight(), entity.tickCount);
        }

        /** What the entity shows or carries, since a thrown capsule may be an item, a projectile or a head. */
        private static String item(Entity entity) {
            ItemStack stack = ItemStack.EMPTY;
            if (entity instanceof ItemEntity dropped) stack = dropped.getItem();
            else if (entity instanceof ItemSupplier supplier) stack = supplier.getItem();
            else if (entity instanceof LivingEntity living) stack = living.getItemBySlot(EquipmentSlot.HEAD);
            return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        }
    }
}
