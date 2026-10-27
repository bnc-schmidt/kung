package com.github.beng420.kung.update;

import static org.junit.Assert.*;

import com.github.beng420.kung.config.KungSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class KungUpdateActionTest {
    private static final String DOWNLOAD = "https://github.com/bnc-schmidt/kung/releases/download/v0.3.5/kung-26.1.2-0.3.5.jar";

    @Test public void modrinthUsesTheSameAutomaticInstallationActionAsOtherLaunchers() throws Exception {
        // The GitHub updater's menu; the Modrinth jar shows the Modrinth App instead (ModrinthFlavorTest).
        org.junit.Assume.assumeFalse(com.github.beng420.kung.KungBuild.MODRINTH);
        var updater = KungUpdater.INSTANCE;
        var field = KungUpdater.class.getDeclaredField("state");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var state = (AtomicReference<Object>) field.get(updater);
        Object original = state.get();
        var blocked = KungUpdater.class.getDeclaredField("installationBlocked");
        blocked.setAccessible(true);
        boolean originallyBlocked = blocked.getBoolean(updater);
        blocked.setBoolean(updater, false);
        Map<String, String> properties = new HashMap<>();
        for (String key : new String[] {"minecraft.launcher.brand", "modrinth.internal.ipc.host", "modrinth.internal.ipc.port"}) {
            properties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        try {
            var gson = new Gson();
            var snapshot = new JsonObject();
            snapshot.addProperty("status", "UPDATE_AVAILABLE");
            snapshot.addProperty("currentVersion", "0.3.4");
            snapshot.addProperty("latestVersion", "0.3.5");
            snapshot.addProperty("message", "Update 0.3.5 available");
            var asset = new JsonObject();
            asset.addProperty("version", "0.3.5");
            asset.addProperty("assetName", "kung-26.1.2-0.3.5.jar");
            asset.addProperty("downloadUrl", DOWNLOAD);
            asset.addProperty("size", 6_000_000L);
            asset.addProperty("digest", "sha256:" + "0".repeat(64));
            snapshot.add("updateInfo", asset);
            state.set(gson.fromJson(snapshot, original.getClass()));
            System.setProperty("minecraft.launcher.brand", "theseus");

            var menu = KungSettings.defaults().getLast().features().getFirst();
            assertTrue(menu.clickable());
            assertEquals("Updates: Available", menu.name());
            assertTrue(updater.canInstallUpdate());
            assertEquals("Installs 0.3.5", updater.statusMessage());

            System.setProperty("minecraft.launcher.brand", "PrismLauncher");
            assertTrue(menu.clickable());
            assertTrue(updater.canInstallUpdate());
            assertEquals("Updates: Available", menu.name());

            // Launcher identity and IPC parameters are not evidence of an unreplaceable mod file.
            System.setProperty("modrinth.internal.ipc.port", "42317");
            System.setProperty("modrinth.internal.ipc.host", "127.0.0.1");
            assertTrue(menu.clickable());
            assertTrue(updater.canInstallUpdate());
            blocked.setBoolean(updater, true);
            assertFalse(menu.clickable());
            assertEquals("Updates: Recovery needed", menu.name());
            Object before = state.get();
            updater.installLatestAsync(null);
            assertSame(before, state.get());
            blocked.setBoolean(updater, false);
            for (String status : new String[] {"CHECKING", "UP_TO_DATE", "DOWNLOADING", "INSTALL_READY", "UNSUPPORTED", "FAILED"}) {
                snapshot.addProperty("status", status);
                state.set(gson.fromJson(snapshot, original.getClass()));
                assertFalse(status, menu.clickable());
                assertFalse(status, updater.canInstallUpdate());
            }
            snapshot.addProperty("status", "UPDATE_AVAILABLE");
            snapshot.remove("updateInfo");
            state.set(gson.fromJson(snapshot, original.getClass()));
            assertFalse(menu.clickable());
            assertFalse(updater.canInstallUpdate());
        } finally {
            state.set(original);
            blocked.setBoolean(updater, originallyBlocked);
            properties.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
    }
}
