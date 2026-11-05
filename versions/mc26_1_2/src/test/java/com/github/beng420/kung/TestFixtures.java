package com.github.beng420.kung;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

/** Helpers that several test classes need. */
public final class TestFixtures {
    private TestFixtures() { }

    /** Runs Fabric's complete delivery sequence for one game message, including its post-filter events. */
    public static boolean receiveGame(String text, boolean overlay) {
        Component message = Component.literal(text);
        boolean allowed = ClientReceiveMessageEvents.ALLOW_GAME.invoker().allowReceiveGameMessage(message, overlay);
        if (allowed) {
            message = ClientReceiveMessageEvents.MODIFY_GAME.invoker().modifyReceivedGameMessage(message, overlay);
            ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(message, overlay);
        } else {
            ClientReceiveMessageEvents.GAME_CANCELED.invoker().onReceiveGameMessageCanceled(message, overlay);
        }
        return allowed;
    }
}
