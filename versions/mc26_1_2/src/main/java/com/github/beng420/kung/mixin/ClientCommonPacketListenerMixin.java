package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.dungeon.DungeonServerTickEvents;
import com.github.beng420.kung.feature.misc.FrozenBlazeCapture;
import com.github.beng420.kung.feature.misc.FrozenBlazeHudFeature;
import com.github.beng420.kung.feature.misc.NpcDialogueTrace;
import com.github.beng420.kung.util.ServerTickSequence;
import com.github.beng420.kung.util.ServerTickPacketContext;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    @Unique
    private final ServerTickSequence kung$serverTicks = new ServerTickSequence();

    // TAIL keeps standalone tick pings in applied order with chat boundaries.
    // The bundle scope preserves provenance that this flattened handler would lose.
    @Inject(method = "handlePing", at = @At("TAIL"))
    private void kung$onPing(ClientboundPingPacket packet, CallbackInfo callbackInfo) {
        if (kung$serverTicks.accept(packet.getId(), ServerTickPacketContext.isApplyingBundle())) {
            DungeonServerTickEvents.post();
        }
    }

    // Log-only. Typed commands, chat-click commands (sendUnattendedCommand skips Fabric's COMMAND event)
    // and custom dialogue answers all leave through here.
    @Inject(method = "send", at = @At("HEAD"))
    private void kung$traceSent(Packet<?> packet, CallbackInfo callbackInfo) {
        NpcDialogueTrace.sent(packet);
        FrozenBlazeHudFeature.INSTANCE.observeSent(packet);
        FrozenBlazeCapture.sent(packet);
    }
}
