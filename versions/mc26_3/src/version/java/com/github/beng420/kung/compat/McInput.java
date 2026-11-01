package com.github.beng420.kung.compat;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.FloatBuffer;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;

/**
 * Key, scancode and button numbers in the form Kung stores keybinds: GLFW codes. 26.3 reports SDL numbers and
 * translates here. Kept apart from McCompat, whose static fields need Minecraft's registries bootstrapped.
 */
public final class McInput {
    private McInput() {
    }

    public static int key(KeyEvent event) { return GlfwKeyMap.glfwKey(event.key()); }

    /** The raw SDL scancode of a key GLFW had no code for (key() is -1), else 0. */
    public static int scancode(KeyEvent event) { return GlfwKeyMap.glfwKey(event.key()) >= 0 ? 0 : event.key(); }

    public static int button(MouseButtonEvent event) { return GlfwKeyMap.glfwButton(event.button()); }

    public static int button(MouseButtonInfo info) { return GlfwKeyMap.glfwButton(info.button()); }

    public static boolean isKeyDown(int key) {
        int scancode = GlfwKeyMap.sdlScancode(key);
        return scancode >= 0 && InputConstants.isKeyDown(scancode);
    }

    public static boolean isMouseDown(int button) {
        int sdl = GlfwKeyMap.sdlButton(button);
        return sdl > 0 && (SDLMouse.SDL_GetMouseState((FloatBuffer) null, (FloatBuffer) null) & 1 << sdl - 1) != 0;
    }

    /** What this keyboard layout prints on the key with GLFW code {@code key}; null without a printable name. */
    public static String keyName(int key) {
        int scancode = GlfwKeyMap.sdlScancode(key);
        if (scancode < 0) return null;
        String name = SDLKeyboard.SDL_GetKeyName(SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false));
        return name == null || name.isEmpty() ? null : name;
    }

    /** The number OneConfig's keybind editor uses for the key with GLFW code {@code key}, or -1 when it has none. */
    public static int platformKey(int key) { return GlfwKeyMap.sdlScancode(key); }

    /** The GLFW code of a key number OneConfig's keybind editor reports, or -1 when GLFW had none. */
    public static int glfwKey(int platformKey) { return GlfwKeyMap.glfwKey(platformKey); }

    public static int platformButton(int button) { return GlfwKeyMap.sdlButton(button); }

    public static int glfwButton(int platformButton) { return GlfwKeyMap.glfwButton(platformButton); }
}
