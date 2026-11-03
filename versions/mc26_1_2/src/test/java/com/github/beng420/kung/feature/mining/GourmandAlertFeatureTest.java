package com.github.beng420.kung.feature.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.Test;

public class GourmandAlertFeatureTest {
    @Test
    public void tastyMithrilIsKnownByItsSkyBlockIdOrItsName() {
        assertTrue(GourmandAlertFeature.isTastyMithril("MITHRIL_GOURMAND", "Anything"));
        assertTrue(GourmandAlertFeature.isTastyMithril("", "Tasty Mithril"));
        assertTrue(GourmandAlertFeature.isTastyMithril("", " Tasty Mithril "));
        assertFalse(GourmandAlertFeature.isTastyMithril("MITHRIL_ORE", "Mithril"));
        assertFalse(GourmandAlertFeature.isTastyMithril("", "Tasty Mithril Pickaxe"));
        assertFalse(GourmandAlertFeature.isTastyMithril("", null));
    }

    @Test
    public void mithrilHeldBeforeTheEventStartedIsALeftoverDonRefuses() {
        var leftovers = java.util.Set.of("old-event");
        assertFalse(GourmandAlertFeature.isCurrent("old-event", leftovers));
        assertTrue(GourmandAlertFeature.isCurrent("this-event", leftovers));
        // An item without the key (or a missed STARTED line: no leftovers known) still counts.
        assertTrue(GourmandAlertFeature.isCurrent("", leftovers));
        assertTrue(GourmandAlertFeature.isCurrent("old-event", java.util.Set.of()));
    }

    @Test
    public void theChatMessageRunsTheTeleportCommandOnClick() {
        Component message = GourmandAlertFeature.teleportMessage(60);
        assertTrue(message.getString().contains("Don Expresso leaves in 1 minute"));
        var link = message.getSiblings().get(message.getSiblings().size() - 1);
        assertTrue(link.getString().contains("teleport to Don Expresso"));
        assertEquals(new ClickEvent.RunCommand("/tptodonexpresso"), link.getStyle().getClickEvent());
    }

    @Test
    public void aWarningNamesTheMinuteOrTheSecondsLeft() {
        assertEquals("1 minute", GourmandAlertFeature.span(60));
        assertEquals("45 seconds", GourmandAlertFeature.span(45));
    }
}
