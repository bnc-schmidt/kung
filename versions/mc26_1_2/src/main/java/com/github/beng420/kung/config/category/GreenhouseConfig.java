package com.github.beng420.kung.config.category;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Look and toggle key of the Greenhouse hologram. This category has no master toggle on purpose: the hologram is
 * user-triggered (a layout is shown or it is not), so there is nothing to switch on before it can work.
 * Colors are stored as plain RGB; the opacity setting supplies the alpha when the hologram is drawn.
 */
public final class GreenhouseConfig extends ConfigCategory {
    public static final float DEFAULT_OPACITY = 0.30F;
    public static final float MIN_OPACITY = 0.05F;
    public static final float MAX_OPACITY = 1.0F;
    public static final int DEFAULT_COLOR_TO_PLACE = 0x4D8DFF;
    public static final int DEFAULT_COLOR_WRONG = 0xFF3B3B;
    public static final int DEFAULT_COLOR_EXTRA = 0xFF55D8;
    public static final int DEFAULT_COLOR_WRONG_STATE = 0xFFA126;
    /** GLFW_KEY_K, in the same "key:<key>:<scancode>" form the loadout and sack keybinds use. */
    public static final String DEFAULT_TOGGLE_KEYBIND = "key:75:0";
    private static final int RGB = 0xFFFFFF;
    private static final Pattern KEYBIND = Pattern.compile("|key:-?\\d+(:-?\\d+)?|mouse:\\d+");

    private float hologramOpacity = DEFAULT_OPACITY;
    private int colorToPlace = DEFAULT_COLOR_TO_PLACE;
    private int colorWrong = DEFAULT_COLOR_WRONG;
    private int colorExtra = DEFAULT_COLOR_EXTRA;
    private int colorWrongState = DEFAULT_COLOR_WRONG_STATE;
    private boolean hideWhenComplete = true;
    private boolean hideModelsOverWrongSoil = true;
    private boolean showCrosshairLabel = true;
    private String toggleKeybind = DEFAULT_TOGGLE_KEYBIND;

    public float hologramOpacity() { return clampOpacity(hologramOpacity); }
    public void setHologramOpacity(float value) { hologramOpacity = clampOpacity(value); save(); }
    /** The menu slider works in whole percent. */
    public int hologramOpacityPercent() { return Math.round(hologramOpacity() * 100F); }
    public void setHologramOpacityPercent(int percent) { setHologramOpacity(percent / 100F); }

    /** Colors come back as opaque ARGB; any alpha passed to a setter is dropped. */
    public int colorToPlace() { return opaque(colorToPlace); }
    public void setColorToPlace(int color) { colorToPlace = color & RGB; save(); }
    public int colorWrong() { return opaque(colorWrong); }
    public void setColorWrong(int color) { colorWrong = color & RGB; save(); }
    public int colorExtra() { return opaque(colorExtra); }
    public void setColorExtra(int color) { colorExtra = color & RGB; save(); }
    public int colorWrongState() { return opaque(colorWrongState); }
    public void setColorWrongState(int color) { colorWrongState = color & RGB; save(); }

    /** Turns the hologram off for good once the layout is complete. The key keeps its old name "hide" for old files. */
    public boolean hideWhenComplete() { return hideWhenComplete; }
    public void setHideWhenComplete(boolean value) { hideWhenComplete = value; save(); }

    /** No crop model or to-place outline over a cell whose soil is missing or wrong. */
    public boolean hideModelsOverWrongSoil() { return hideModelsOverWrongSoil; }
    public void setHideModelsOverWrongSoil(boolean value) { hideModelsOverWrongSoil = value; save(); }

    /** A small name label under the crosshair while it is aimed at a hologram cell or soil marker. */
    public boolean showCrosshairLabel() { return showCrosshairLabel; }
    public void setShowCrosshairLabel(boolean value) { showCrosshairLabel = value; save(); }

    /** Raw keybind text; empty means unbound. */
    public String toggleKeybind() { return toggleKeybind == null ? DEFAULT_TOGGLE_KEYBIND : toggleKeybind; }

    /** A malformed value keeps the current key; the settings screen only ever passes well-formed ones. */
    public void setToggleKeybind(String value) {
        if (value == null || !KEYBIND.matcher(value).matches()) return;
        toggleKeybind = value;
        save();
    }

    /** The GLFW key code, or -1 when unbound or bound to a mouse button. */
    public int toggleKey() {
        String[] parts = toggleKeybind().split(":");
        if (parts.length < 2 || !parts[0].equals("key")) return -1;
        try {
            return Math.max(-1, Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    public void normalize() {
        hologramOpacity = clampOpacity(hologramOpacity);
        colorToPlace &= RGB;
        colorWrong &= RGB;
        colorExtra &= RGB;
        colorWrongState &= RGB;
        if (toggleKeybind == null || !KEYBIND.matcher(toggleKeybind).matches()) toggleKeybind = DEFAULT_TOGGLE_KEYBIND;
    }

    /**
     * Drops values of the wrong JSON type before Gson maps them: one bad hand edit here would otherwise throw
     * out the whole config file. A dropped value keeps its default; range errors are left to {@link #normalize()}.
     */
    public static void sanitize(JsonObject root) {
        if (!(root.get("greenhouse") instanceof JsonObject json)) {
            root.remove("greenhouse");
            return;
        }
        for (String name : new String[] {"hologramOpacity", "colorToPlace", "colorWrong", "colorExtra", "colorWrongState"}) {
            keepIf(json, name, JsonPrimitive::isNumber);
        }
        for (String name : new String[] {"hideWhenComplete", "hideModelsOverWrongSoil", "showCrosshairLabel"}) {
            keepIf(json, name, JsonPrimitive::isBoolean);
        }
        keepIf(json, "toggleKeybind", JsonPrimitive::isString);
    }

    private static void keepIf(JsonObject json, String name, Predicate<JsonPrimitive> valid) {
        JsonElement value = json.get(name);
        if (value != null && !(value instanceof JsonPrimitive primitive && valid.test(primitive))) json.remove(name);
    }

    private static float clampOpacity(float value) {
        return Float.isNaN(value) ? DEFAULT_OPACITY : Math.clamp(value, MIN_OPACITY, MAX_OPACITY);
    }

    private static int opaque(int rgb) { return 0xFF000000 | (rgb & RGB); }
}
