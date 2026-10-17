package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GreenhouseNumberEditTest {
    private static GreenhouseNumberEdit count(int value) {
        return new GreenhouseNumberEdit(value, GreenhouseState.COUNT);
    }

    @Test
    public void startsAsTheCurrentValueAndKeepsItIfNothingIsTyped() {
        GreenhouseNumberEdit edit = count(52);

        assertEquals("52", edit.text());
        assertEquals(52, edit.commit(1));
    }

    @Test
    public void firstDigitReplacesTheValueLaterDigitsAppend() {
        GreenhouseNumberEdit edit = count(52);

        edit.type('7');
        assertEquals("7", edit.text());
        edit.type('0');
        edit.type('x');
        edit.type('1');
        assertEquals("701", edit.text());
        assertEquals(701, edit.commit(1));
    }

    @Test
    public void backspaceClearsAFreshValueThenRemovesOneDigit() {
        GreenhouseNumberEdit edit = count(52);

        edit.backspace();
        assertEquals("", edit.text());
        assertEquals(9, edit.commit(9));
        edit.type('1');
        edit.type('2');
        edit.backspace();
        assertEquals("1", edit.text());
    }

    @Test
    public void commitClampsIntoTheRange() {
        GreenhouseNumberEdit seconds = new GreenhouseNumberEdit(5, GreenhouseState.SECONDS);
        seconds.type('9');
        seconds.type('9');
        assertEquals(60, seconds.commit(5));

        GreenhouseNumberEdit zero = new GreenhouseNumberEdit(5, GreenhouseState.SECONDS);
        zero.type('0');
        assertEquals(1, zero.commit(5));

        GreenhouseNumberEdit priority = new GreenhouseNumberEdit(0, GreenhouseState.PRIORITY);
        assertEquals("0", priority.text());
        priority.type('0');
        assertEquals(0, priority.commit(0));
    }

    @Test
    public void digitCountIsBounded() {
        GreenhouseNumberEdit edit = count(1);

        for (int i = 0; i < 20; i++) edit.type('9');

        assertEquals("999999", edit.text());
        assertEquals(100_000, edit.commit(1));
    }
}
