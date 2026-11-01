package com.github.beng420.kung.util;

/**
 * GLFW key codes, the numbers Kung stores keybinds as on every Minecraft version. McCompat.key turns a
 * key event into one of these, so 26.3 (which numbers keys by SDL scancode) needs no other change.
 */
public final class KeyCodes {
    public static final int SEMICOLON = 59;
    public static final int A = 65;
    public static final int Z = 90;
    public static final int ESCAPE = 256;
    public static final int ENTER = 257;
    public static final int TAB = 258;
    public static final int BACKSPACE = 259;
    public static final int DELETE = 261;
    public static final int DOWN = 264;
    public static final int UP = 265;
    public static final int KP_ENTER = 335;

    private KeyCodes() {
    }
}
