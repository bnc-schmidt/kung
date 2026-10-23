package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.HypixelChatSender;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

/** Reminds you when a Quiz question stays unanswered for a while: a local message, optionally one to the party. */
public final class DungeonQuizReminderFeature extends ConfigurableFeature<DungeonConfig> {
    public static final DungeonQuizReminderFeature INSTANCE = new DungeonQuizReminderFeature();
    private final QuizReminderState state = new QuizReminderState();

    private DungeonQuizReminderFeature() { super(config -> config.dungeon); }

    @Override
    protected void onInitialize() {
        // ALLOW_GAME hears every message, also one the dungeon chat filter hides.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay && isEnabled()) {
                state.observe(message.getString(), System.currentTimeMillis(), config().quizReminderDelaySeconds() * 1000L);
            }
            return true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    @Override
    protected void onReset() {
        state.reset();
    }

    @Override
    public boolean isEnabled() { return initialized() && config().quizReminderEnabled(); }

    private void tick(Minecraft client) {
        if (client.player == null || !isEnabled() || !HypixelInstanceTracker.INSTANCE.catacombs()) {
            state.reset();
            return;
        }
        var action = state.tick(System.currentTimeMillis(), config().quizReminderRepeatEnabled(),
            config().quizReminderRepeatSeconds() * 1000L);
        if (action == QuizReminderState.Action.NONE) return;
        sendLocal(client);
        // The party hears it once per question, whatever the repeat setting says.
        if (action == QuizReminderState.Action.FIRST && config().quizReminderPartyEnabled()) {
            HypixelChatSender.INSTANCE.sendParty(client, config().quizReminderMessage());
        }
    }

    /** The settings screen's Test button: the local message only, never the party. */
    public void sendTest(Minecraft client) {
        if (initialized()) sendLocal(client);
    }

    private void sendLocal(Minecraft client) {
        KungMessages.send(client, KungMessages.Type.WARNING, "Quiz", config().quizReminderMessage());
    }
}
