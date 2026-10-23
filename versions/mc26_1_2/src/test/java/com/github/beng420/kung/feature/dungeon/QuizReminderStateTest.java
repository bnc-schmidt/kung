package com.github.beng420.kung.feature.dungeon;

import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Action.FIRST;
import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Action.NONE;
import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Action.REPEAT;
import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Line.ANSWERED;
import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Line.ENDED;
import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Line.OTHER;
import static com.github.beng420.kung.feature.dungeon.QuizReminderState.Line.QUESTION;
import static org.junit.Assert.*;

import com.github.beng420.kung.config.category.DungeonConfig;
import org.junit.Test;

public class QuizReminderStateTest {
    private static final long DELAY = 10_000;
    private static final long T0 = 1_000_000;
    private static final String ORUO = "[STATUE] Oruo the Omniscient: ";

    @Test
    public void classifiesTheLinesOfARealRun() {
        // Copied from the chat of recorded dungeon runs.
        assertEquals(QUESTION, QuizReminderState.classify("Question #3"));
        assertEquals(ANSWERED, QuizReminderState.classify(ORUO + "Beng114 answered Question #1 correctly!"));
        assertEquals(ANSWERED, QuizReminderState.classify(ORUO + "marps answered the final question correctly!"));
        assertEquals(ENDED, QuizReminderState.classify(ORUO + "I bestow upon you all the power of a hundred years!"));
        assertEquals(ENDED, QuizReminderState.classify(ORUO + "Yikes"));
        for (String other : new String[] {
            ORUO + "Though I sit stationary in this prison that is The Catacombs, my knowledge knows no bounds.",
            ORUO + "One more question!", ORUO + "2 questions left... Then you will have proven your worth to me!",
            "Which brother is on the Spider's Den?", "ⓐ Rick", "Beng114 answered Question #1 correctly!", "", null}) {
            assertEquals(String.valueOf(other), OTHER, QuizReminderState.classify(other));
        }
    }

    @Test
    public void aQuestionNobodyAnswersRemindsOnceAfterTheDelay() {
        var state = new QuizReminderState();
        state.observe("Question #1", T0, DELAY);
        assertEquals(NONE, state.tick(T0 + DELAY - 1, false, DELAY));
        assertEquals(FIRST, state.tick(T0 + DELAY, false, DELAY));
        assertEquals(NONE, state.tick(T0 + 10 * DELAY, false, DELAY));
    }

    @Test
    public void repeatingRemindsAgainAtTheRepeatInterval() {
        var state = new QuizReminderState();
        state.observe("Question #1", T0, DELAY);
        assertEquals(FIRST, state.tick(T0 + DELAY, true, 20_000));
        assertEquals(NONE, state.tick(T0 + DELAY + 19_999, true, 20_000));
        assertEquals(REPEAT, state.tick(T0 + DELAY + 20_000, true, 20_000));
        assertEquals(REPEAT, state.tick(T0 + DELAY + 40_000, true, 20_000));
    }

    @Test
    public void anAnswerOrTheEndStopsTheCountdown() {
        var state = new QuizReminderState();
        state.observe("Question #1", T0, DELAY);
        state.observe(ORUO + "Beng114 answered Question #1 correctly!", T0 + 5_000, DELAY);
        assertEquals(NONE, state.tick(T0 + 60_000, true, DELAY));

        state.observe("Question #3", T0 + 70_000, DELAY);
        state.observe(ORUO + "I bestow upon you all the power of a hundred years!", T0 + 72_000, DELAY);
        assertEquals(NONE, state.tick(T0 + 200_000, true, DELAY));
    }

    @Test
    public void everyNewQuestionStartsOverAndSendsItsOwnFirstReminder() {
        var state = new QuizReminderState();
        state.observe("Question #1", T0, DELAY);
        assertEquals(FIRST, state.tick(T0 + DELAY, true, DELAY));
        assertEquals(REPEAT, state.tick(T0 + 2 * DELAY, true, DELAY));
        state.observe("Question #2", T0 + 3 * DELAY, DELAY);
        assertEquals(NONE, state.tick(T0 + 3 * DELAY + 1, true, DELAY));
        assertEquals(FIRST, state.tick(T0 + 4 * DELAY, true, DELAY));
    }

    @Test
    public void otherChatAndResetLeaveNothingPending() {
        var state = new QuizReminderState();
        state.observe(ORUO + "One more question!", T0, DELAY);
        assertEquals(NONE, state.tick(T0 + 100_000, true, DELAY));
        state.observe("Question #2", T0, DELAY);
        state.reset();
        assertEquals(NONE, state.tick(T0 + 100_000, true, DELAY));
    }

    @Test
    public void theSettingsStayInRangeAndTheMessageNeverEmpty() {
        var config = new DungeonConfig();
        assertFalse(config.quizReminderEnabled());
        assertFalse(config.quizReminderRepeatEnabled());
        assertFalse(config.quizReminderPartyEnabled());
        assertEquals(10, config.quizReminderDelaySeconds());
        // Repeat 0 follows the delay, wherever the delay is set.
        assertEquals(10, config.quizReminderRepeatSeconds());
        config.setQuizReminderDelaySeconds(25);
        assertEquals(25, config.quizReminderRepeatSeconds());
        assertEquals(0, config.quizReminderRepeatSetting());
        config.setQuizReminderRepeatSeconds(3);
        assertEquals(5, config.quizReminderRepeatSeconds());
        config.setQuizReminderRepeatSeconds(500);
        assertEquals(60, config.quizReminderRepeatSeconds());
        config.setQuizReminderDelaySeconds(1);
        assertEquals(5, config.quizReminderDelaySeconds());
        config.setQuizReminderDelaySeconds(999);
        assertEquals(60, config.quizReminderDelaySeconds());

        assertEquals(DungeonConfig.DEFAULT_QUIZ_REMINDER_MESSAGE, config.quizReminderMessage());
        config.setQuizReminderMessage("   ");
        assertEquals(DungeonConfig.DEFAULT_QUIZ_REMINDER_MESSAGE, config.quizReminderMessage());
        config.setQuizReminderMessage("[Kung] Answer the quiz!");
        assertEquals("Answer the quiz!", config.quizReminderMessage());
        config.setQuizReminderMessage("x".repeat(500));
        assertEquals(200, config.quizReminderMessage().length());
    }
}
