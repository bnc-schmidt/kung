package com.github.beng420.kung.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * The join shortcuts, top-level slash commands that start a Catacombs floor: /entrance, /f1 to /f7 and /m1 to /m7 send the server command that
 * the Party Finder's "click here to enter" message runs, /joininstance with an instance name. They are not a
 * feature, so they have no setting and no entry in the config.
 */
final class DungeonJoinCommands {
    private static final String[] FLOOR_NUMBERS = {"ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN"};
    private static final Map<String, String> INSTANCES = instances();

    private DungeonJoinCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        INSTANCES.forEach((name, instance) -> dispatcher.register(
            literal(name).executes(context -> send(context.getSource(), name))));
    }

    /** The server command a shortcut sends, or null if the name is no shortcut. */
    static String serverCommand(String name) {
        String instance = INSTANCES.get(name);
        return instance == null ? null : "joininstance " + instance;
    }

    private static int send(FabricClientCommandSource source, String name) {
        source.getPlayer().connection.sendCommand(serverCommand(name));
        return 1;
    }

    private static Map<String, String> instances() {
        Map<String, String> instances = new LinkedHashMap<>();
        instances.put("entrance", "CATACOMBS_ENTRANCE");
        for (int floor = 0; floor < FLOOR_NUMBERS.length; floor++) {
            instances.put("f" + (floor + 1), "CATACOMBS_FLOOR_" + FLOOR_NUMBERS[floor]);
            instances.put("m" + (floor + 1), "MASTER_CATACOMBS_FLOOR_" + FLOOR_NUMBERS[floor]);
        }
        return instances;
    }
}
