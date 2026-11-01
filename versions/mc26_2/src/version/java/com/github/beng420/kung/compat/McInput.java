package com.github.beng420.kung.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/**
 * Key, scancode and button numbers in the form Kung stores keybinds: GLFW codes. 26.3 reports SDL numbers and
 * translates here. Kept apart from McCompat, whose static fields need Minecraft's registries bootstrapped.
 */
public final class McInput {
    private McInput() {
    }

    public static int key(KeyEvent event) { return event.key(); }

    public static int scancode(KeyEvent event) { return event.scancode(); }

    public static int button(MouseButtonEvent event) { return event.button(); }

    public static int button(MouseButtonInfo info) { return info.button(); }

    public static boolean isKeyDown(int key) { return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key); }

    public static boolean isMouseDown(int button) {
        return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(), button) == GLFW.GLFW_PRESS;
    }

    /** What this keyboard layout prints on the key with GLFW code {@code key}; null without a printable name. */
    public static String keyName(int key) { return GLFW.glfwGetKeyName(key, 0); }

    /** The number OneConfig's keybind editor uses for the key with GLFW code {@code key}, or -1 when it has none. */
    public static int platformKey(int key) { return key; }

    /** The GLFW code of a key number OneConfig's keybind editor reports, or -1 when GLFW had none. */
    public static int glfwKey(int platformKey) { return platformKey; }

    public static int platformButton(int button) { return button; }

    public static int glfwButton(int platformButton) { return platformButton; }
}
