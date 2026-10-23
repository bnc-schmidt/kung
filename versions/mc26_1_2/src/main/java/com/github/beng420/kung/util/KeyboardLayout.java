package com.github.beng420.kung.util;

import com.github.beng420.kung.config.KungConfig;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;

/**
 * What a key is labelled on the player's keyboard. GLFW numbers keys by their US position, and Kung stores keybinds by
 * that number, so only the label shown in menus (and the key labelled Y for Peek Chat) depends on the layout. Auto asks
 * the system (glfwGetKeyName); when that answers wrongly, a layout can be picked under Debug.
 */
public enum KeyboardLayout {
    AUTO("Auto"), QWERTY("QWERTY (US)"), QWERTZ("QWERTZ (German)"), AZERTY("AZERTY (French)");

    private final String label;

    KeyboardLayout(String label) { this.label = label; }

    public String label() { return label; }

    public static KeyboardLayout current() {
        return KungConfig.get().debug.keyboardLayout();
    }

    /**
     * The letter this layout prints on the key with GLFW code {@code key} (A to Z, and the US semicolon key that
     * carries the M of AZERTY), or null for Auto and for every other key.
     */
    public String letterOn(int key) {
        if (this == AUTO) return null;
        if (key == GLFW.GLFW_KEY_SEMICOLON) return this == AZERTY ? "M" : null;
        if (key < GLFW.GLFW_KEY_A || key > GLFW.GLFW_KEY_Z) return null;
        char us = (char) key;
        return String.valueOf(switch (this) {
            case QWERTZ -> us == 'Y' ? 'Z' : us == 'Z' ? 'Y' : us;
            case AZERTY -> switch (us) {
                case 'A' -> 'Q';
                case 'Q' -> 'A';
                case 'W' -> 'Z';
                case 'Z' -> 'W';
                case 'M' -> ','; // the US M key carries the comma on AZERTY
                default -> us;
            };
            default -> us;
        });
    }

    /** The GLFW code of the key labelled with this letter, or -1 (Auto never knows). */
    public int keyLabelled(String letter) {
        if (this == AUTO) return -1;
        String wanted = letter.toUpperCase(Locale.ROOT);
        for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z; key++) {
            if (wanted.equals(letterOn(key))) return key;
        }
        return letterOn(GLFW.GLFW_KEY_SEMICOLON) != null && wanted.equals(letterOn(GLFW.GLFW_KEY_SEMICOLON)) ? GLFW.GLFW_KEY_SEMICOLON : -1;
    }
}
