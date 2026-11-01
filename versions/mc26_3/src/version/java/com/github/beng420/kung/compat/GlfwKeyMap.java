package com.github.beng420.kung.compat;

import java.util.Arrays;

/**
 * 26.3 numbers keys by SDL scancode; Kung keeps GLFW key codes everywhere else (stored keybinds, shared
 * code). Pairs are (GLFW code, SDL scancode) for every InputConstants.KEY_* both versions define.
 */
final class GlfwKeyMap {
    private static final int[] PAIRS = {
        32, 44, 39, 52, 44, 54, 45, 45, 46, 55, 47, 56,
        48, 39, 49, 30, 50, 31, 51, 32, 52, 33, 53, 34,
        54, 35, 55, 36, 56, 37, 57, 38, 59, 51, 61, 46,
        65, 4, 66, 5, 67, 6, 68, 7, 69, 8, 70, 9,
        71, 10, 72, 11, 73, 12, 74, 13, 75, 14, 76, 15,
        77, 16, 78, 17, 79, 18, 80, 19, 81, 20, 82, 21,
        83, 22, 84, 23, 85, 24, 86, 25, 87, 26, 88, 27,
        89, 28, 90, 29, 91, 47, 92, 49, 93, 48, 96, 53,
        256, 41, 257, 40, 258, 43, 259, 42, 260, 73, 261, 76,
        262, 79, 263, 80, 264, 81, 265, 82, 266, 75, 267, 78,
        268, 74, 269, 77, 280, 57, 281, 71, 282, 83, 283, 70,
        284, 72, 290, 58, 291, 59, 292, 60, 293, 61, 294, 62,
        295, 63, 296, 64, 297, 65, 298, 66, 299, 67, 300, 68,
        301, 69, 302, 104, 303, 105, 304, 106, 305, 107, 306, 108,
        307, 109, 308, 110, 309, 111, 310, 112, 311, 113, 312, 114,
        313, 115, 320, 98, 321, 89, 322, 90, 323, 91, 324, 92,
        325, 93, 326, 94, 327, 95, 328, 96, 329, 97, 330, 220,
        332, 85, 334, 87, 335, 88, 336, 103, 340, 225, 341, 224,
        342, 226, 343, 227, 344, 229, 345, 228, 346, 230, 347, 231,
    };
    private static final int[] SDL_TO_GLFW = new int[512];
    private static final int[] GLFW_TO_SDL = new int[512];

    static {
        Arrays.fill(SDL_TO_GLFW, -1);
        Arrays.fill(GLFW_TO_SDL, -1);
        for (int i = 0; i < PAIRS.length; i += 2) {
            GLFW_TO_SDL[PAIRS[i]] = PAIRS[i + 1];
            SDL_TO_GLFW[PAIRS[i + 1]] = PAIRS[i];
        }
    }

    private GlfwKeyMap() {
    }

    /** The GLFW code of an SDL scancode, or -1 for a key GLFW had no code for. */
    static int glfwKey(int sdlScancode) {
        return sdlScancode >= 0 && sdlScancode < SDL_TO_GLFW.length ? SDL_TO_GLFW[sdlScancode] : -1;
    }

    /** The SDL scancode of a GLFW key code, or -1 when there is none. */
    static int sdlScancode(int glfwKey) {
        return glfwKey >= 0 && glfwKey < GLFW_TO_SDL.length ? GLFW_TO_SDL[glfwKey] : -1;
    }

    /** GLFW numbers buttons left 0, right 1, middle 2, then 3 up; SDL numbers them left 1, middle 2, right 3, then 4 up. */
    static int glfwButton(int sdlButton) {
        return sdlButton == 1 ? 0 : sdlButton == 3 ? 1 : sdlButton == 2 ? 2 : sdlButton - 1;
    }

    static int sdlButton(int glfwButton) {
        return glfwButton == 0 ? 1 : glfwButton == 1 ? 3 : glfwButton == 2 ? 2 : glfwButton + 1;
    }
}
