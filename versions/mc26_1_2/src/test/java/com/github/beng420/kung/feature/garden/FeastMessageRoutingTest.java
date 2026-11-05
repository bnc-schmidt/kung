package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.message.KungMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import org.junit.Test;
import static com.github.beng420.kung.TestFixtures.receiveGame;
import static org.junit.Assert.*;

public class FeastMessageRoutingTest {
    @Test
    public void countsBeforeChatModificationAndCancellationWithoutCountingActionBarsOrTwice() {
        var active = new AtomicBoolean(true);
        var hidden = new AtomicBoolean(false);
        var session = new FeastSession();
        session.select(new FeastContext.Event(FeastProgress.Kind.GRAND, "grand:513"), true);
        session.observeMenu(new Object(), new FeastProgress.Snapshot(FeastProgress.Kind.GRAND, 27,
            List.of(5, 25, 75, 150, 250, 350, 450, 550, 750)));
        session.closeMenu();
        var kernels = new FeastKernels();
        kernels.observeMenu(new Object(), 1_234L);
        var rate = new FeastKernelRate();
        rate.selectProfile("account", "Coconut");
        rate.updateContext(true, session.event(), 0);
        rate.crop(0);
        var observed = new ArrayList<String>();
        // Register a canceling mod first: Feast must still receive the message.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !active.get() || !hidden.get());
        KungMessages.onGameText(text -> {
            if (active.get()) {
                observed.add(text);
                session.donate(text);
                kernels.observeMessage(text);
                rate.observeMessage(text, 1_000);
            }
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
            active.get() ? Component.literal("Reformatted chat output") : message);
        String seasoning = "RARE CROP! Seasoning (+80\uE02B) (automatically donated)";
        try {
            assertTrue(receiveGame(seasoning, false));
            assertEquals(List.of(seasoning), observed);
            assertEquals(28, session.snapshot().donations());

            // Identical text in a second server event represents another drop, not a duplicate.
            assertTrue(receiveGame(seasoning, false));
            assertEquals(29, session.snapshot().donations());
            hidden.set(true);
            assertFalse(receiveGame(seasoning, false));
            assertEquals(30, session.snapshot().donations());
            assertEquals(3, observed.size());
            assertEquals(Long.valueOf(1_234), kernels.balance());
            assertEquals(0, rate.snapshot(1_000).kernels());

            assertFalse(receiveGame(seasoning, true));
            assertEquals(30, session.snapshot().donations());
            assertEquals(3, observed.size());
            assertFalse(receiveGame("[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse.", false));
            assertEquals(Long.valueOf(1_235), kernels.balance());
            assertEquals(30, session.snapshot().donations());
            assertEquals(1, rate.snapshot(1_000).kernels());
            assertFalse(receiveGame("[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse.", true));
            assertEquals("Action bars cannot credit the rate", 1, rate.snapshot(1_000).kernels());
        } finally {
            active.set(false);
        }
    }
}
