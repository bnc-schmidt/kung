package com.github.beng420.kung.mixin;

import com.github.beng420.kung.feature.misc.FrozenBlazeCapture;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {
    // Log-only. The method returns normally only on the processor's own thread: from Netty it queues the packet and
    // throws, so RETURN fires once per packet on the client thread, right before its handler (and per bundle child).
    // The descriptor is explicit because the ServerLevel overload shares the name; that one delegates here.
    @Inject(
        method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/network/PacketProcessor;)V",
        at = @At("RETURN")
    )
    private static void kung$onInbound(Packet<?> packet, PacketListener listener, PacketProcessor processor, CallbackInfo callbackInfo) {
        if (FrozenBlazeCapture.active()) FrozenBlazeCapture.inbound(packet, listener);
    }
}
