package com.github.beng420.kung.command;

import static com.github.beng420.kung.command.CommandTestSupport.parse;
import static com.github.beng420.kung.command.CommandTestSupport.userDispatcher;
import static org.junit.Assert.*;

import org.junit.Test;

public class VisitorAlarmCommandTest {
    @Test
    public void muteClickResolvesToAnExecutableClientCommand() {
        var parsed = parse(userDispatcher(), "kung visitors mute");
        assertTrue(parsed.getExceptions().isEmpty());
        assertFalse(parsed.getReader().canRead());
        assertNotNull(parsed.getContext().getCommand());
    }
}
