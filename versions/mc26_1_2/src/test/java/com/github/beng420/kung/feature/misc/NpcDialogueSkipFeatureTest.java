package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import com.github.beng420.kung.feature.misc.NpcDialogueSkipFeature.ClickGuard;
import com.github.beng420.kung.feature.misc.NpcDialogueSkipFeature.Option;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public final class NpcDialogueSkipFeatureTest {
    @BeforeClass
    public static void bootstrap() {
        // ClickEvent.Action loads the dialog registry codecs.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void fullBuildOnly() {
        org.junit.Assume.assumeFalse("Skip NPC Dialogue is not in the Modrinth build", com.github.beng420.kung.KungBuild.MODRINTH);
    }

    @Test
    public void onlyDialogueAnswersCountAsOptions() {
        Style answer = answer("fire_guy", "x_3");
        // The current format splits one answer over "[", label and "]".
        MutableComponent single = Component.literal("Select an option: ")
            .append(Component.literal("[").withStyle(answer))
            .append(Component.literal("Sure, I guess?").withStyle(answer))
            .append(Component.literal("]").withStyle(answer));
        List<Option> options = NpcDialogueSkipFeature.options(single);
        assertEquals(1, options.size());
        assertEquals("[Sure, I guess?]", options.getFirst().label());
        assertEquals(Optional.of("fire_guy"), options.getFirst().npcId());
        assertEquals("x_3", options.getFirst().key());

        MutableComponent two = Component.literal("Select an option: ")
            .append(Component.literal("[Yeah]").withStyle(answer("kelly", "r_1")))
            .append(" ")
            .append(Component.literal("[Never heard of him]").withStyle(answer("kelly", "r_2")))
            .append(Component.literal(" [Accept]").withStyle(command("/cb 1a2b")))
            .append(Component.literal(" Steve").withStyle(command("/viewprofile Steve")));
        assertEquals(List.of("[Yeah]", "[Never heard of him]"),
            NpcDialogueSkipFeature.options(two).stream().map(Option::label).toList());
        assertTrue(NpcDialogueSkipFeature.options(Component.literal("[Accept]").withStyle(command("/cb 1a2b"))).isEmpty());
    }

    @Test
    public void answersOneOptionOrACuratedChoiceOnly() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        Option sure = new Option("[Sure, I guess?]", answer("fire_guy", "x_3").getClickEvent());
        assertSame(sure, NpcDialogueSkipFeature.choose(List.of(sure), "Ryan", choices));

        Option yeah = new Option("[Yeah]", answer("kelly", "r_1").getClickEvent());
        Option never = new Option("[Never heard of him]", answer("kelly", "r_2").getClickEvent());
        assertNull(NpcDialogueSkipFeature.choose(List.of(yeah, never), "Ryan", choices));
        assertSame(never, NpcDialogueSkipFeature.choose(List.of(yeah, never), "Kelly", choices));
    }

    @Test
    public void molbertTakesSureAndNeverTheMoleAnswer() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        // The mole answer only makes Molbert repeat the question; [Sure...] asks for the 512 Jungle Logs.
        Option sure = new Option("[Sure...]", answer("molbert", "r_7_1").getClickEvent());
        Option mole = new Option("[You look like a mole yourself]", answer("molbert", "r_7_2").getClickEvent());
        assertSame(sure, NpcDialogueSkipFeature.choose(List.of(sure, mole), "Molbert", choices));
        // Keyed by npcId, so a missing or stale speaker name changes nothing.
        assertSame(sure, NpcDialogueSkipFeature.choose(List.of(mole, sure), "", choices));
        // Another label set from the same NPC is a prompt nobody checked.
        Option other = new Option("[Maybe]", answer("molbert", "r_9_1").getClickEvent());
        assertNull(NpcDialogueSkipFeature.choose(List.of(sure, other), "Molbert", choices));
    }

    @Test
    public void hideyhoPlaysAgainOnSureNeverOnNoThanks() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        // From a Safari trace: "How about it?" with these two answers; [No thanks...] ends the game of hide and seek.
        Option sure = new Option("[Sure]", answer("hideyho", "r_4_1").getClickEvent());
        Option no = new Option("[No thanks...]", answer("hideyho", "r_4_2").getClickEvent());
        assertSame(sure, NpcDialogueSkipFeature.choose(List.of(sure, no), "Hideyho", choices));
        // Keyed by npcId, so the speaker name does not matter ("[MOB]" lines do not even name him as an [NPC]).
        assertSame(sure, NpcDialogueSkipFeature.choose(List.of(no, sure), "", choices));
    }

    @Test
    public void curatedNpcsTakeTheAnswerThatMovesOn() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        // npc, chosen label first, then the answers that refuse, repeat or only explain.
        String[][] curated = {
            {"Lumber Jack", "[Sure]", "[Nah, I'm good]"},
            {"Susan", "[Absolutely!]", "[Tell me more about the Safety Squad.]", "[Not right now.]"},
            {"Susan", "[Sounds good with me!]", "[Give me a moment.]"},
            {"Susan", "[Absolutely!]", "[Let me think about it a bit more.]"},
            {"Susan", "[I'm good to go!]", "[Give me a moment.]"},
            {"Loras", "[Yes]", "[Later, maybe]"},
            {"Miria", "[Let's do it!]", "[Maybe later]"},
            {"Miria", "[I sure am!]", "[I'll come back later]"},
            {"Sanger", "[Yep!]", "[Can you give me an example?]"},
            {"Sanger", "[Yep!]", "[Can you give me another example]"},
            {"Sanger", "[Yep!]", "[Can you give me one last example?]"},
            {"\"Hunter\" Tobias", "[Nice!]", "[Suuuuuure...]"},
            // Both answers lead to the same place; Honey's [No] skips her aside, Swoop's and Archie's [Yes] the intro.
            {"Swoop", "[Yes]", "[No]"},
            {"Honey", "[No]", "[Yes]"},
            {"Archie", "[Yes]", "[No]"},
        };
        for (String[] row : curated) {
            List<Option> options = java.util.stream.IntStream.range(1, row.length)
                .mapToObj(index -> new Option(row[index], answer("x", "r_" + index).getClickEvent())).toList();
            Option chosen = NpcDialogueSkipFeature.choose(options, row[0], choices);
            assertNotNull(row[0] + " " + row[1], chosen);
            assertEquals(row[0], row[1], chosen.label());
        }
        // Trades and swaps stay with the player, and so does any NPC not on the list.
        Option yes = new Option("[Yes]", answer("coral", "r_1").getClickEvent());
        Option no = new Option("[No]", answer("coral", "r_2").getClickEvent());
        assertNull(NpcDialogueSkipFeature.choose(List.of(yes, no), "Coral", choices));
        assertNull(NpcDialogueSkipFeature.choose(List.of(yes, no), "Hunter Billy", choices));
    }

    @Test
    public void abiphoneGiveItemIsClickedOnlyInsideTheCall() {
        // Professor Wynd's unlock prompt: a standalone component, no "Select an option:" line around it.
        List<Option> options = NpcDialogueSkipFeature.options(
            Component.literal("[GIVE ITEM]").withStyle(answer("meteorologist", "pay")));
        assertEquals(1, options.size());
        Option give = options.getFirst();
        assertEquals("[GIVE ITEM]", give.label());
        assertEquals(Optional.of("meteorologist"), give.npcId());
        assertEquals("pay", give.key());
        assertEquals(answer("meteorologist", "pay").getClickEvent(), give.click());
        var choices = NpcDialogueSkipFeature.loadChoices();
        assertSame(give, NpcDialogueSkipFeature.choose(options, "Professor Wynd", choices));
        // It lands right after the last "[NPC] Professor Wynd: ✆ ..." line.
        assertTrue(NpcDialogueSkipFeature.loneAnswerable(give, 2));
        assertTrue(NpcDialogueSkipFeature.loneAnswerable(give, NpcDialogueSkipFeature.QUIET_MILLIS - 1));
        // A stray one, no NPC line for a while (or ever), stays with the player.
        assertFalse(NpcDialogueSkipFeature.loneAnswerable(give, NpcDialogueSkipFeature.QUIET_MILLIS));
        assertFalse(NpcDialogueSkipFeature.loneAnswerable(give, System.currentTimeMillis()));
    }

    @Test
    public void lonePromptsThatCostCoinsStayWithThePlayer() {
        // The wiki names no labels; the payload key is "pay" for items as well, so only the label counts.
        for (String label : new String[] {"[PAY COINS]", "[Pay 32,000,000 Coins]", "[GIVE 1,000,000]", "[Buy it]"}) {
            Option cost = new Option(label, answer("blacksmith", "pay").getClickEvent());
            assertFalse(label, NpcDialogueSkipFeature.loneAnswerable(cost, 2));
        }
        assertTrue(NpcDialogueSkipFeature.loneAnswerable(
            new Option("[Sure, I guess?]", answer("fire_guy", "x_3").getClickEvent()), 2));
        // Calls with several answers keep the curated rule: Hoppity's [Yes] buys a rabbit with coins.
        Option yes = new Option("[Yes]", answer("hoppity", "r_2_1").getClickEvent());
        Option no = new Option("[No]", answer("hoppity", "r_2_2").getClickEvent());
        assertNull(NpcDialogueSkipFeature.choose(List.of(yes, no), "Hoppity", NpcDialogueSkipFeature.loadChoices()));
    }

    @Test
    public void leavingAPromptToThePlayerIsAnnouncedWithTheNpc() {
        assertEquals("Multiple choice (Coral): not skipping, pick an answer yourself",
            NpcDialogueSkipFeature.skipNotice("Coral"));
    }

    @Test
    public void clicksOnlyTalkAndGiveObjectives() {
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to Charlie", "Charlie"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to the lumber jack!", "§aLumber Jack"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Give Kelly Spruce Logs", "Kelly"));
        // Romero has no name stand, so his objective is matched against the [NPC] speaker name instead.
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Give Romero Yellow Rock", "Romero"));
        // Multi-word NPCs: the stand name, not the first word, decides.
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Give Lumber Jack Oak Logs", "Lumber Jack"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Give Lumber Jack Oak Logs", "Jack"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to Charlie", "Charlie Jr"));
        // "Check on" is a talk objective; trailing stand decorations such as ♫ do not count.
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Check on Melody", "Melody ♫"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Check on Melody", "Ryan"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Claim the trousers from Charlie!", "Charlie"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Complete Trial of Fire I", "Fire"));
        assertEquals("Talk to Kelly", NpcDialogueSkipFeature.sidebarObjective(
            List.of("SKYBLOCK", " ⏣ The Park", "", "Objective", "§eTalk to Kelly")));
    }

    @Test
    public void oneClickPerObjective() {
        ClickGuard guard = new ClickGuard();
        assertTrue(guard.allows("Talk to Charlie"));
        guard.clicked("Talk to Charlie", 1);
        guard.inventory(2, 60_000);
        assertFalse("Talk objectives never re-arm", guard.allows("Talk to Charlie"));
        assertTrue(guard.allows("Give Ryan Dark Oak Logs"));
        guard.clicked("Give Ryan Dark Oak Logs", 2);
        assertFalse("still missing the logs", guard.allows("Give Ryan Dark Oak Logs"));
        // The hand-in takes the logs while Ryan is still talking: no re-click, even once he goes quiet.
        guard.inventory(3, 3_000);
        guard.inventory(3, NpcDialogueSkipFeature.QUIET_MILLIS);
        assertFalse(guard.allows("Give Ryan Dark Oak Logs"));
        guard.inventory(4, NpcDialogueSkipFeature.QUIET_MILLIS);
        assertTrue("changed after 8 s of quiet", guard.allows("Give Ryan Dark Oak Logs"));
    }

    private static Style answer(String npcId, String responseKey) {
        CompoundTag payload = new CompoundTag();
        payload.putString("npcId", npcId);
        payload.putString("responseKey", responseKey);
        return Style.EMPTY.withClickEvent(new ClickEvent.Custom(Identifier.parse("skyblock:dialogue_response"), Optional.of(payload)));
    }

    private static Style command(String command) {
        return Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command));
    }
}
