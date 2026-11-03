package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import net.minecraft.client.Minecraft;

/** Turns the held state of the Mute Key into one press per push; read it every tick so the edge is current. */
final class AlarmMuteKey {
    private boolean down;

    /** True once when the key goes from up to down. */
    boolean press(boolean nowDown) {
        boolean pressed = nowDown && !down;
        down = nowDown;
        return pressed;
    }

    /** Only in the world with no screen open, so typing in chat or a sign never mutes. */
    boolean press(Minecraft client, String keybind) {
        return press(McCompat.screen(client) == null && LoadoutsAutoCloseFeature.keybindHeld(keybind));
    }
}
