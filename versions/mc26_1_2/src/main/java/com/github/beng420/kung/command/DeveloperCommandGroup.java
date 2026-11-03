package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.M7DragonFeature;
import com.github.beng420.kung.feature.misc.SackApiProbe;
import com.github.beng420.kung.feature.safari.SafariThrowTracker;
import com.github.beng420.kung.feature.slayer.TarantulaHelperFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class DeveloperCommandGroup {
    private DeveloperCommandGroup() { }

    static void register(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        if (!KungDeveloperAccess.allowed()) return;
        root.then(literal("dev").requires(source -> KungDeveloperAccess.allowed())
            .then(literal("dragons")
                .executes(context -> reply(context.getSource(), "Use /kung dev dragons on, off, sample or copy."))
                .then(literal("on").executes(context -> diagnostics(context.getSource(), true)))
                .then(literal("off").executes(context -> diagnostics(context.getSource(), false)))
                .then(literal("sample").executes(context -> reply(context.getSource(),
                    M7DragonFeature.INSTANCE.captureDeveloperSample())))
                .then(literal("copy").executes(context -> {
                    if (!KungDeveloperAccess.allowed()) return 0;
                    context.getSource().getClient().keyboardHandler.setClipboard(M7DragonFeature.INSTANCE.developerReport());
                    return reply(context.getSource(), "Dragon measurements copied.");
                })))
            .then(literal("safari")
                .executes(context -> reply(context.getSource(), "Use /kung dev safari on or off."))
                .then(literal("on").executes(context -> reply(context.getSource(), SafariThrowTracker.set(true))))
                .then(literal("off").executes(context -> reply(context.getSource(), SafariThrowTracker.set(false)))))
            .then(literal("sackapi")
                .executes(context -> reply(context.getSource(), "Use /kung dev sackapi on or off."))
                .then(literal("on").executes(context -> reply(context.getSource(), SackApiProbe.set(true))))
                .then(literal("off").executes(context -> reply(context.getSource(), SackApiProbe.set(false)))))
            .then(literal("tara").executes(context -> reply(context.getSource(),
                TarantulaHelperFeature.INSTANCE.eggSacGridReport()))));
    }

    private static int diagnostics(FabricClientCommandSource source, boolean enabled) {
        if (!KungDeveloperAccess.allowed()) return 0;
        KungConfig.get().dungeon.setDevDragonDiagnosticsEnabled(enabled);
        return reply(source, "Dragon diagnostics " + (enabled ? "enabled." : "disabled."));
    }

    private static int reply(FabricClientCommandSource source, String text) {
        source.sendFeedback(KungMessages.info("Dev", text));
        return 1;
    }
}
