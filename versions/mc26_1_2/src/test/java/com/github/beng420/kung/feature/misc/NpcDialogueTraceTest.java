package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import java.net.URI;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.junit.Test;

public final class NpcDialogueTraceTest extends MinecraftBootstrapTest {
    @Test
    public void clickableSegmentsFormatIntoOneTraceLine() {
        CompoundTag answer = new CompoundTag();
        answer.putString("npcId", "kaus");
        answer.putString("responseKey", "yes");
        Style custom = Style.EMPTY.withClickEvent(
            new ClickEvent.Custom(Identifier.parse("skyblock:dialogue_response"), Optional.of(answer)));
        Component message = Component.literal("Select an option: ")
            .append(Component.literal("[Sure]").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/selectnpcoption kaus r_1_1"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to\nanswer")))))
            .append(" ")
            .append(Component.literal("[Wiki]").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.OpenUrl(URI.create("https://wiki.hypixel.net/Kaus")))))
            .append(" ")
            // The current option format splits one answer over "[", label and "]".
            .append(Component.literal("[").withStyle(custom))
            .append(Component.literal("YES").withStyle(custom))
            .append(Component.literal("]").withStyle(custom));

        assertEquals("chat \"Select an option: [Sure] [Wiki] [YES]\""
                + " | \"[Sure]\" RUN_COMMAND=/selectnpcoption kaus r_1_1 hover=\"Click to answer\""
                + " | \"[Wiki]\" OPEN_URL=https://wiki.hypixel.net/Kaus"
                + " | \"[YES]\" CUSTOM=skyblock:dialogue_response payload={npcId:\"kaus\",responseKey:\"yes\"}",
            NpcDialogueTrace.describe(message, false));
        assertEquals("chat \"[NPC] Kaus: Hello!\"", NpcDialogueTrace.describe(Component.literal("[NPC] Kaus: Hello!"), false));
        assertNull(NpcDialogueTrace.describe(Component.literal("Lobby chatter"), false));
        // Brigette's quiz may use another click event than dialogue answers; the question text alone gets it logged.
        assertEquals("chat \"Question #2 @ Leave the party\" | \"Leave the party\" RUN_COMMAND=/quiz 2",
            NpcDialogueTrace.describe(Component.literal("Question #2 @ ").append(Component.literal("Leave the party")
                .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/quiz 2")))), false));
        // Clickable chat is logged only when the click belongs to an NPC prompt.
        assertNull(NpcDialogueTrace.describe(Component.literal("Steve joined the lobby!").withStyle(Style.EMPTY
            .withClickEvent(new ClickEvent.RunCommand("/viewprofile Steve"))), false));
        assertEquals("chat \"[Accept]\" | \"[Accept]\" RUN_COMMAND=/cb 1a2b",
            NpcDialogueTrace.describe(Component.literal("[Accept]").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/cb 1a2b"))), false));
    }
}
