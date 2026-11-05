package com.github.beng420.kung.command;

import static com.github.beng420.kung.command.KungCommandActions.info;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.feature.dungeon.M7DragonFeature;
import com.github.beng420.kung.feature.misc.SackApiProbe;
import com.github.beng420.kung.feature.safari.SafariThrowTracker;
import com.github.beng420.kung.feature.slayer.TarantulaHelperFeature;
import com.github.beng420.kung.runtime.KungDeveloperAccess;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class DeveloperCommandGroup {
    private DeveloperCommandGroup() { }

    static void register(LiteralArgumentBuilder<FabricClientCommandSource> root) {
        if (!KungDeveloperAccess.allowed()) return;
        root.then(literal("dev")
            .then(literal("dragons")
                .executes(context -> info(context, "Dev", "Use /kung dev dragons on, off, sample or copy."))
                .then(literal("on").executes(context -> diagnostics(context, true)))
                .then(literal("off").executes(context -> diagnostics(context, false)))
                .then(literal("sample").executes(context -> info(context, "Dev",
                    M7DragonFeature.INSTANCE.captureDeveloperSample())))
                .then(literal("copy").executes(context -> {
                    context.getSource().getClient().keyboardHandler.setClipboard(M7DragonFeature.INSTANCE.developerReport());
                    return info(context, "Dev", "Dragon measurements copied.");
                })))
            .then(literal("safari")
                .executes(context -> info(context, "Dev", "Use /kung dev safari on or off."))
                .then(literal("on").executes(context -> info(context, "Dev", SafariThrowTracker.set(true))))
                .then(literal("off").executes(context -> info(context, "Dev", SafariThrowTracker.set(false)))))
            .then(literal("sackapi")
                .executes(context -> info(context, "Dev", "Use /kung dev sackapi on or off."))
                .then(literal("on").executes(context -> info(context, "Dev", SackApiProbe.set(true))))
                .then(literal("off").executes(context -> info(context, "Dev", SackApiProbe.set(false)))))
            .then(literal("tara").executes(context -> info(context, "Dev",
                TarantulaHelperFeature.INSTANCE.eggSacGridReport()))));
    }

    private static int diagnostics(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        KungConfig.get().dungeon.setDevDragonDiagnosticsEnabled(enabled);
        return info(context, "Dev", "Dragon diagnostics " + (enabled ? "enabled." : "disabled."));
    }
}
