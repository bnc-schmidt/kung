package com.github.beng420.kung.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class HypixelChatSenderTest {
    @Test
    public void addsKungPrefixExactlyOnce() {
        assertEquals("[Kung] hello", HypixelChatSender.prefixedMessage(" hello "));
        assertEquals("[Kung] hello", HypixelChatSender.prefixedMessage("[Kung] hello"));
    }

    @Test
    public void buildsHypixelChatCommands() {
        assertEquals("pc [Kung] ready", HypixelChatSender.command("/pc", "ready"));
        assertEquals("msg Steve [Kung] ready", HypixelChatSender.command("msg Steve", "ready"));
    }

    @Test
    public void rejectsMissingChannels() {
        assertThrows(IllegalArgumentException.class, () -> HypixelChatSender.command("", "hello"));
    }
}
