package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.config.category.GreenhouseConfig;
import com.github.beng420.kung.feature.misc.LoadoutsAutoCloseFeature;
import java.util.function.Supplier;
import net.minecraft.client.input.KeyEvent;

/**
 * What the player chose for the hologram (opacity, colors, auto-hide) and its toggle key. The getters are
 * package-private for the renderer and read the live config on every call, so a change in the menu shows at once;
 * before the config is loaded they return the defaults. The class is public only for the key mixins.
 */
public final class GreenhouseHologramSettings {
    private static Supplier<GreenhouseConfig> source = () -> KungConfig.get().greenhouse;
    private static volatile Runnable toggleHandler;

    private GreenhouseHologramSettings() { }

    /** 0.05 to 1.0: the alpha of a fully visible hologram model. */
    static float opacity() { return config().hologramOpacity(); }

    /** The four colors are opaque ARGB; scale their alpha with {@link #withOpacity}. */
    static int colorToPlace() { return config().colorToPlace(); }
    static int colorWrong() { return config().colorWrong(); }
    static int colorExtra() { return config().colorExtra(); }
    static int colorWrongState() { return config().colorWrongState(); }

    static boolean hideWhenComplete() { return config().hideWhenComplete(); }

    /** The GLFW key code of the toggle key, or -1 when it is unbound or bound to a mouse button. */
    static int toggleKey() { return config().toggleKey(); }

    /** {@code rgb} (any alpha is dropped) with alpha = opacity * factor; use factor 1 for the model, less for fills. */
    static int withOpacity(int rgb, float factor) {
        int alpha = Math.round(Math.clamp(opacity() * factor, 0F, 1F) * 255F);
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    /** The hologram registers what a press of the toggle key does; it runs on the client thread. */
    static void setToggleHandler(Runnable handler) { toggleHandler = handler; }

    static void onToggleKey() {
        Runnable handler = toggleHandler;
        if (handler != null) handler.run();
    }

    /** Called by KeyboardHandlerMixin for key presses while no screen is open. Never cancels the key. */
    public static void onKeyPress(KeyEvent event) {
        if (LoadoutsAutoCloseFeature.keybindMatchesKey(config().toggleKeybind(), event)) onToggleKey();
    }

    /** Called by MouseHandlerMixin for button presses while no screen is open. Never cancels the click. */
    public static void onMousePress(int button) {
        if (LoadoutsAutoCloseFeature.keybindMatchesMouse(config().toggleKeybind(), button)) onToggleKey();
    }

    /** Tests swap the config source so they never write the real config file; null restores the live one. */
    static void useConfig(Supplier<GreenhouseConfig> replacement) {
        source = replacement == null ? () -> KungConfig.get().greenhouse : replacement;
    }

    private static GreenhouseConfig config() { return source.get(); }
}
