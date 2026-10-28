package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.dungeon.DungeonEventRouter;
import com.github.beng420.kung.feature.dungeon.DungeonDebuffFeature;
import com.github.beng420.kung.feature.dungeon.M7DragonFeature;
import com.github.beng420.kung.feature.misc.FrozenBlazeHudFeature;
import com.github.beng420.kung.feature.misc.PerformanceHudFeature;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import com.github.beng420.kung.feature.garden.FeastOverlayFeature;
import com.github.beng420.kung.feature.hunting.InvisibugLineFeature;
import com.github.beng420.kung.feature.misc.CustomSoundsFeature;
import com.github.beng420.kung.feature.misc.SuperpairsHelperFeature;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.ServerTickPacketContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @WrapMethod(method = "handleBundlePacket")
    private void kung$applyBundle(ClientboundBundlePacket packet, Operation<Void> original) {
        ServerTickPacketContext.applyBundle(() -> original.call(packet));
    }

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void kung$onSound(ClientboundSoundPacket packet, CallbackInfo callbackInfo) {
        CustomSoundsFeature.observeSoundEvent(packet.getSound().value());
        DungeonDebuffFeature.INSTANCE.observeSound(packet);
        FrozenBlazeHudFeature.INSTANCE.observeSound(packet);
    }

    @Inject(method = "handleSoundEntityEvent", at = @At("TAIL"))
    private void kung$onEntitySound(ClientboundSoundEntityPacket packet, CallbackInfo callbackInfo) {
        CustomSoundsFeature.observeSoundEvent(packet.getSound().value());
    }

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void kung$onEntityAdded(ClientboundAddEntityPacket packet, CallbackInfo callbackInfo) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            DungeonDebuffFeature.INSTANCE.observeSpawn(level.getEntity(packet.getId()));
            M7DragonFeature.INSTANCE.observeSpawn(level.getEntity(packet.getId()));
        }
    }

    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void kung$onEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo callbackInfo) {
        DungeonDebuffFeature.INSTANCE.observeEquipment(packet);
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void kung$onEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo callbackInfo) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            DungeonDebuffFeature.INSTANCE.observeData(level.getEntity(packet.id()));
            M7DragonFeature.INSTANCE.observeData(level.getEntity(packet.id()));
        }
    }

    @Inject(method = "handleParticleEvent", at = @At("TAIL"))
    private void kung$onParticles(ClientboundLevelParticlesPacket packet, CallbackInfo callbackInfo) {
        M7DragonFeature.INSTANCE.observeParticles(packet);
        FrozenBlazeHudFeature.INSTANCE.observeParticles(packet);
        InvisibugLineFeature.INSTANCE.observeParticles(packet);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void kung$onContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo callbackInfo) {
        SuperpairsHelperFeature.observeSlotUpdate(packet.getContainerId(), packet.getSlot(), packet.getItem());
        FeastOverlayFeature.observeSlotUpdate(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void kung$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo callbackInfo) {
        SuperpairsHelperFeature.observeContentUpdate(packet.containerId(), packet.items());
        FeastOverlayFeature.observeContentUpdate(packet.containerId(), packet.items());
    }

    // TAIL runs after vanilla applies the packet on the client thread, never on Netty's read thread.
    @Inject(method = {"handleLogin", "handleRespawn", "handleConfigurationStart"}, at = @At("TAIL"))
    private void kung$onWorldChanged(CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observeWorldChangePacket();
    }

    @Inject(method = "handlePongResponse", at = @At("TAIL"))
    private void kung$onPong(ClientboundPongResponsePacket packet, CallbackInfo callbackInfo) {
        PerformanceHudFeature.observePong(packet.time());
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void kung$onPlayerPosition(CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observePlayerPosition();
    }

    @Inject(method = "handleSetScore", at = @At("TAIL"))
    private void kung$onScore(ClientboundSetScorePacket packet, CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observeSidebarScore(packet.owner());
    }

    @Inject(method = "handleSetPlayerTeamPacket", at = @At("TAIL"))
    private void kung$onTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observeSidebarTeam(packet);
    }

    @Inject(method = {"handleAddObjective", "handleSetDisplayObjective", "handleResetScore"}, at = @At("TAIL"))
    private void kung$onSidebar(CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observeSidebar();
    }

    @Inject(method = "handleTabListCustomisation", at = @At("TAIL"))
    private void kung$onTab(ClientboundTabListPacket packet, CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observeTabList(packet.header(), packet.footer());
    }

    @Inject(method = "handlePlayerInfoUpdate", at = @At("TAIL"))
    private void kung$onPlayerInfo(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observePlayerInfo(packet);
    }

    @Inject(method = "handlePlayerInfoRemove", at = @At("TAIL"))
    private void kung$onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo callbackInfo) {
        HypixelInstanceTracker.INSTANCE.observePlayerInfoRemoved(packet.profileIds());
    }

    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void kung$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo callbackInfo) {
        if (packet.getEventId() != EntityEvent.DEATH) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Entity entity = packet.getEntity(client.level);
        DungeonEventRouter.observeEntityDeath(entity);
        DungeonDebuffFeature.INSTANCE.observeDeath(entity);
        M7DragonFeature.INSTANCE.observeDeath(entity);
    }
}
