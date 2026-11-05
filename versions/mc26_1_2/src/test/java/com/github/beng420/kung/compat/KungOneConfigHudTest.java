package com.github.beng420.kung.compat;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungHudLayout;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.polyfrost.oneconfig.api.config.v1.Properties;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.hud.v1.HudResize;

public final class KungOneConfigHudTest {
    @Test
    public void constructionAndNativeCapabilityQueriesKeepDisabledHudUnchanged() {
        FakeHud state = new FakeHud();
        var wrapper = state.wrapper(List.of());

        assertEquals("kung_example", wrapper.getId());
        assertEquals("Example", wrapper.getName());
        assertEquals("kung", wrapper.getModId());
        assertTrue(wrapper.getOwnsPlacement());
        assertTrue(wrapper.getPlacementReady());
        assertTrue(wrapper.getSupportsScale());
        assertEquals(HudResize.Both, wrapper.getResizeAxes());
        assertTrue(wrapper.getHidden());
        assertEquals(12F, wrapper.getX(), 0F);
        assertEquals(23F, wrapper.getY(), 0F);
        assertEquals(1F, wrapper.getScale(), 0F);
        // Switched off: no space in OneConfig's editor, as its Odin wrapper does.
        assertEquals(0F, wrapper.getScaledWidth(), 0F);
        assertEquals(0F, wrapper.getScaledHeight(), 0F);
        state.enabled[0] = true;
        assertEquals(120F, wrapper.getScaledWidth(), 0F);
        assertEquals(60F, wrapper.getScaledHeight(), 0F);
        state.enabled[0] = false;
        wrapper.onDragStart();
        wrapper.onDragEnd();
        wrapper.save();
        assertEquals(0, state.writes);
        assertFalse(state.enabled[0]);
        assertEquals(wrapper.getId(), state.wrapper(List.of()).getId());
    }

    @Test
    public void visibilityControlsTheExistingMasterAndObservesExternalChanges() {
        FakeHud state = new FakeHud();
        var wrapper = state.wrapper(List.of());
        wrapper.setHidden(true);
        assertEquals(0, state.writes);
        wrapper.setHidden(false);
        assertTrue(state.enabled[0]);
        assertFalse(wrapper.getHidden());
        wrapper.setHidden(false);
        assertEquals(1, state.writes);
        wrapper.setHidden(true);
        assertFalse(state.enabled[0]);
        assertEquals(2, state.writes);

        state.enabled[0] = true;
        assertFalse(wrapper.getHidden());
        assertEquals(2, state.writes);
    }

    @Test
    public void movingNativeBoundsPreservesContentOriginAndReadsLiveScale() {
        FakeHud state = new FakeHud();
        state.enabled[0] = true;
        var wrapper = state.wrapper(List.of());
        wrapper.setX(90.4F);
        wrapper.setY(-10.6F);
        assertArrayEquals(new int[] {98, -4, 100}, state.placement);
        assertEquals(90F, wrapper.getX(), 0F);
        assertEquals(-11F, wrapper.getY(), 0F);

        state.placement[2] = 150;
        assertEquals(1.5F, wrapper.getScale(), 0F);
        assertEquals(180F, wrapper.getScaledWidth(), 0F);
        assertEquals(90F, wrapper.getScaledHeight(), 0F);
        wrapper.setX(90F);
        wrapper.setY(-11F);
        assertArrayEquals(new int[] {102, -1, 150}, state.placement);
        assertEquals(90F, wrapper.getX(), 0F);
        assertEquals(-11F, wrapper.getY(), 0F);

        wrapper.setScaledWidth(500F);
        wrapper.setScaledHeight(500F);
        assertEquals(180F, wrapper.getScaledWidth(), 0F);
        assertEquals(90F, wrapper.getScaledHeight(), 0F);
        assertEquals(4, state.writes);
    }

    @Test
    public void nativeScaleUsesKungPercentLimitsAndRejectsNonFiniteInputs() {
        FakeHud state = new FakeHud();
        var wrapper = state.wrapper(List.of());
        wrapper.setScale(1.756F);
        assertEquals(176, state.placement[2]);
        assertEquals(1.76F, wrapper.getScale(), 0F);
        wrapper.setScale(0.01F);
        assertEquals(0.25F, wrapper.getScale(), 0F);
        wrapper.setScale(4F);
        assertEquals(3F, wrapper.getScale(), 0F);
        wrapper.setScale(3F);
        for (float invalid : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            wrapper.setX(invalid);
            wrapper.setY(invalid);
            wrapper.setScale(invalid);
        }
        assertArrayEquals(new int[] {20, 30, 300}, state.placement);
        assertEquals(3, state.writes);
    }

    @Test
    public void profileReplayCannotChangeVisibilityPositionOrScale() {
        FakeHud state = new FakeHud();
        var wrapper = state.wrapper(List.of());
        state.writesAllowed[0] = false;
        wrapper.setHidden(false);
        wrapper.setX(100F);
        wrapper.setY(200F);
        wrapper.setScale(2F);
        wrapper.setScaledWidth(500F);
        wrapper.setScaledHeight(500F);
        wrapper.onDragEnd();
        wrapper.save();
        assertEquals(0, state.writes);
        assertArrayEquals(new int[] {20, 30, 100}, state.placement);
        assertTrue(wrapper.getHidden());

        state.writesAllowed[0] = true;
        wrapper.setHidden(false);
        wrapper.setX(100F);
        wrapper.setY(200F);
        wrapper.setScale(2F);
        assertEquals(4, state.writes);
        assertArrayEquals(new int[] {108, 207, 200}, state.placement);
        assertFalse(wrapper.getHidden());
    }

    @Test
    public void linkedSettingsRetainTheirLiveBindingsWithoutMutableListOwnership() {
        FakeHud state = new FakeHud();
        int[] detail = {7};
        Property<Integer> setting = Properties.functional(() -> detail[0], value -> detail[0] = value,
            "detail", "Detail", null, Integer.class);
        var supplied = new ArrayList<Property<?>>();
        supplied.add(setting);
        var wrapper = state.wrapper(supplied);
        supplied.clear();

        assertEquals(1, wrapper.linkedProperties().size());
        assertSame(setting, wrapper.linkedProperties().getFirst());
        wrapper.linkedProperties().getFirst().setAs(9);
        assertEquals(9, detail[0]);
        detail[0] = 11;
        assertEquals(11, wrapper.linkedProperties().getFirst().get());
        assertThrows(UnsupportedOperationException.class, () -> wrapper.linkedProperties().clear());
        assertEquals(0, state.writes);
    }

    @Test
    public void standaloneEntrypointsLoadWithBothOptionalApisUnavailable() throws Exception {
        Set<String> isolated = Set.of(
            "com.github.beng420.kung.KungClient",
            "com.github.beng420.kung.compat.KungOneConfigBridge",
            "com.github.beng420.kung.config.KungHudEditorState"
        );
        URL classes = KungHudLayout.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[] {classes}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("org.polyfrost.") || name.startsWith("com.terraformersmc.")) {
                        throw new ClassNotFoundException("Optional API absent: " + name);
                    }
                    if (!isolated.contains(name)) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.polyfrost.oneconfig.api.hud.v1.OneConfigHudWrapper", true, loader));
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.terraformersmc.modmenu.api.ModMenuApi", true, loader));
            for (String name : isolated) {
                Class<?> entrypoint = Class.forName(name, true, loader);
                assertSame(loader, entrypoint.getClassLoader());
                assertTrue(entrypoint.getDeclaredMethods().length > 0);
            }
            Class<?> state = Class.forName("com.github.beng420.kung.config.KungHudEditorState", true, loader);
            assertEquals(false, state.getMethod("externalEditing").invoke(null));
        }
    }

    private static final class FakeHud {
        final int[] placement = {20, 30, 100};
        final boolean[] enabled = {false};
        final boolean[] writesAllowed = {true};
        int writes;

        KungOneConfigHud wrapper(List<Property<?>> properties) {
            var entry = new KungHudLayout.Entry("example", "Example", () -> {
                float scale = placement[2] / 100F;
                return new KungHudLayout.Bounds(Math.round(placement[0] - 8 * scale),
                    Math.round(placement[1] - 7 * scale), Math.round(120 * scale), Math.round(60 * scale));
            }, () -> placement[0], () -> placement[1], value -> { placement[0] = value; writes++; },
                value -> { placement[1] = value; writes++; }, () -> placement[2],
                value -> { placement[2] = value; writes++; }, () -> enabled[0],
                value -> { enabled[0] = value; writes++; }, null);
            return new KungOneConfigHud(entry, properties, () -> writesAllowed[0]);
        }
    }
}
