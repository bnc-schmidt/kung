package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.github.beng420.kung.feature.misc.ChatChannels;
import com.mojang.brigadier.CommandDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * The channel shortcuts, top-level slash commands that switch the send channel: /cp, /ca, /cg and /cc send the
 * {@code /chat} command of Party, All, Guild and Co-op. The server command goes out the way a typed one does, so Chat
 * Channels follows the switch like any other. They are not a feature, so they have no setting.
 */
final class ChatShortcuts {
    private static final Map<String, ChatChannels.Send> CHANNELS = channels();

    private ChatShortcuts() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        CHANNELS.forEach((name, send) -> dispatcher.register(
            literal(name).executes(context -> send(context.getSource(), name))));
    }

    /** The server command a shortcut sends, or null if the name is no shortcut. */
    static String serverCommand(String name) {
        ChatChannels.Send send = CHANNELS.get(name);
        return send == null ? null : send.command();
    }

    private static int send(FabricClientCommandSource source, String name) {
        source.getPlayer().connection.sendCommand(serverCommand(name));
        return 1;
    }

    private static Map<String, ChatChannels.Send> channels() {
        Map<String, ChatChannels.Send> channels = new LinkedHashMap<>();
        channels.put("cp", ChatChannels.Send.PARTY);
        channels.put("ca", ChatChannels.Send.ALL);
        channels.put("cg", ChatChannels.Send.GUILD);
        channels.put("cc", ChatChannels.Send.COOP);
        return channels;
    }
}
