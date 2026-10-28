package com.github.beng420.kung.compat;

import static org.junit.Assert.assertEquals;

import com.github.beng420.kung.config.KungSettings;
import com.github.beng420.kung.config.SettingEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.polyfrost.oneconfig.api.config.v1.Property;
import org.polyfrost.oneconfig.api.config.v1.Tree;

public final class KungOneConfigResetTest {
    @Test
    public void aResetRowGetsANativeButtonThatShowsOnlyWhileItIsNeededAndRespectsWriteAccess() {
        var resets = new AtomicInteger();
        var needed = new AtomicBoolean();
        var allowed = new AtomicBoolean(true);
        var row = SettingEntry.toggle("!c50", () -> true, value -> { })
            .withReset(needed::get, "Back to the default channels.", resets::incrementAndGet);
        var feature = new KungSettings.FeatureEntry(() -> "Chat Commands", () -> true, () -> true, () -> { },
            List.of(row), false, List.of(), false);
        var bridge = new KungOneConfigTree(List.of(new KungSettings.CategoryEntry("Category", List.of(feature))),
            () -> { }, allowed::get);
        Tree tree = bridge.tree();
        Property<?> button = tree.map.values().stream()
            .filter(node -> "!c50 / Reset".equals(node.getTitle())).map(node -> (Property<?>) node).findFirst().orElseThrow();

        assertEquals(Property.Display.HIDDEN, button.getDisplay());
        needed.set(true);
        bridge.refresh();
        assertEquals(Property.Display.SHOWN, button.getDisplay());
        Runnable action = button.getMetadata("runnable");
        allowed.set(false);
        action.run();
        assertEquals(0, resets.get());
        allowed.set(true);
        action.run();
        assertEquals(1, resets.get());
    }
}
