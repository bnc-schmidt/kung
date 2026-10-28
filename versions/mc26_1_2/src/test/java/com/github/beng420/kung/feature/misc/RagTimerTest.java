package com.github.beng420.kung.feature.misc;

import static com.github.beng420.kung.feature.misc.RagTimer.Phase.*;
import static org.junit.Assert.*;

import org.junit.Test;

public final class RagTimerTest {
    private static void ticks(RagTimer timer, int count) {
        for (int i = 0; i < count; i++) timer.tick();
    }

    private static RagTimer started() {
        var timer = new RagTimer();
        assertTrue(timer.use(400));
        return timer;
    }

    @Test
    public void castThenEffectThenCooldownCountFromTheClick() {
        var timer = started();
        assertEquals(CAST, timer.phase());
        assertEquals(60, timer.left());
        ticks(timer, 59);
        assertEquals(CAST, timer.phase());
        assertEquals(1, timer.left());
        ticks(timer, 1);
        assertEquals(EFFECT, timer.phase());
        assertEquals(200, timer.left());
        ticks(timer, 200);
        assertEquals(COOLDOWN, timer.phase());
        assertEquals(140, timer.left());
        assertEquals(140, timer.total());
        ticks(timer, 139);
        assertEquals(COOLDOWN, timer.phase());
        ticks(timer, 1);
        assertEquals(IDLE, timer.phase());
        assertEquals(-1, timer.since());
    }

    @Test
    public void clicksDuringCastAndCooldownStartNothing() {
        var timer = started();
        ticks(timer, 30);
        assertFalse(timer.use(400));
        assertEquals(30, timer.left());
        ticks(timer, 270);
        assertEquals(COOLDOWN, timer.phase());
        assertFalse(timer.use(400));
        ticks(timer, 100);
        assertTrue(timer.use(400));
        assertEquals(CAST, timer.phase());
    }

    @Test
    public void shortCooldownFreesTheAxeInsideTheEffect() {
        var timer = new RagTimer();
        assertTrue(timer.use(100));
        ticks(timer, 99);
        assertFalse(timer.use(100));
        ticks(timer, 1);
        assertEquals(EFFECT, timer.phase());
        assertTrue(timer.use(100));
        assertEquals(CAST, timer.phase());
    }

    @Test
    public void abortEndsTheCastAndTheCooldownRunsOn() {
        var timer = started();
        ticks(timer, 20);
        assertTrue(timer.abort());
        assertEquals(COOLDOWN, timer.phase());
        assertEquals(380, timer.left());
        assertEquals(400, timer.total());
        ticks(timer, 380);
        assertEquals(IDLE, timer.phase());
    }

    @Test
    public void abortOnlyHitsARunningCast() {
        var timer = new RagTimer();
        assertFalse(timer.abort());
        timer.use(400);
        ticks(timer, 60);
        assertEquals(EFFECT, timer.phase());
        assertFalse(timer.abort());
        assertEquals(EFFECT, timer.phase());
    }

    @Test
    public void unconfirmedStartIsTakenBackWhenTheServerSaysCooldown() {
        var timer = started();
        ticks(timer, 3);
        timer.onCooldown(5);
        assertEquals(COOLDOWN, timer.phase());
        assertEquals(100, timer.left());
        ticks(timer, 100);
        assertEquals(IDLE, timer.phase());
    }

    @Test
    public void confirmedCastSurvivesASpammedClickAndAdjustsTheCooldown() {
        var timer = started();
        ticks(timer, 5);
        timer.castingIn(3, 400);
        timer.onCooldown(19);
        assertEquals(CAST, timer.phase());
        ticks(timer, 250);
        assertEquals(EFFECT, timer.phase());
        timer.onCooldown(7);
        ticks(timer, 5);
        assertEquals(COOLDOWN, timer.phase());
        assertEquals(135, timer.left());
    }

    @Test
    public void cooldownAnswerOnAnIdleAxeStartsACooldownOnly() {
        var timer = new RagTimer();
        timer.onCooldown(4);
        assertEquals(COOLDOWN, timer.phase());
        assertEquals(80, timer.left());
    }

    @Test
    public void lateAnswerAdjustsInsteadOfTakingBackTheCast() {
        var timer = started();
        ticks(timer, RagTimer.ANSWER_TICKS);
        timer.onCooldown(19);
        assertEquals(CAST, timer.phase());
        ticks(timer, 240);
        assertEquals(COOLDOWN, timer.phase());
        assertEquals(RagTimer.ANSWER_TICKS + 19 * 20 - 260, timer.left());
    }

    @Test
    public void notEnoughManaTakesBackOnlyAnUnconfirmedStart() {
        var timer = started();
        assertTrue(timer.rejected());
        assertEquals(IDLE, timer.phase());
        timer.use(400);
        timer.castingIn(3, 400);
        assertFalse(timer.rejected());
        assertEquals(CAST, timer.phase());
    }

    @Test
    public void castingInStartsACastTheClickMissed() {
        var timer = new RagTimer();
        timer.use(400);
        ticks(timer, 400);
        assertEquals(IDLE, timer.phase());
        timer.castingIn(2, 400);
        assertEquals(CAST, timer.phase());
        assertEquals(40, timer.left());
    }

    @Test
    public void castingInRightAfterAnAbortDoesNotRestartTheCast() {
        var timer = started();
        timer.abort();
        timer.castingIn(1, 400);
        assertEquals(COOLDOWN, timer.phase());
        ticks(timer, RagTimer.QUIET_TICKS);
        timer.castingIn(1, 400);
        assertEquals(CAST, timer.phase());
    }

    @Test
    public void onlyTheCastIsAlwaysShown() {
        assertTrue(RagTimer.visible(CAST, false, false));
        assertFalse(RagTimer.visible(EFFECT, false, true));
        assertTrue(RagTimer.visible(EFFECT, true, false));
        assertFalse(RagTimer.visible(COOLDOWN, true, false));
        assertTrue(RagTimer.visible(COOLDOWN, false, true));
        assertFalse(RagTimer.visible(IDLE, true, true));
    }

    @Test
    public void resetClearsEverything() {
        var timer = started();
        timer.reset();
        assertEquals(IDLE, timer.phase());
        assertTrue(timer.use(400));
    }
}
