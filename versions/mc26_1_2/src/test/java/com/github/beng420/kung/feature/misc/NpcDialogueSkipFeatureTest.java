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
    public void vincentAnswersYesInsteadOfAskingWhatDyesAre() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        // From a trace: "have you ever heard of dyes?" with these two answers.
        Option yes = new Option("[Yes...]", answer("vincent", "r_3_1").getClickEvent());
        Option no = new Option("[No, what are they?]", answer("vincent", "r_3_2").getClickEvent());
        assertSame(yes, NpcDialogueSkipFeature.choose(List.of(yes, no), "Vincent", choices));
        assertSame(yes, NpcDialogueSkipFeature.choose(List.of(no, yes), "", choices));
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
            // Brigette's quiz (wiki): question 1 accepts every answer; on 2 and 3 a wrong answer only repeats the question.
            {"Brigette", "It can lead you to lose a lot of items and money.",
                "It can lead you to lose access to your Minecraft account.",
                "It can lead you to lose your Hypixel SkyBlock profile."},
            {"Brigette", "Leave the party and report the player.",
                "Manually type the invite link in the browser and join the discord.",
                "Add the player on discord and ask for the invite link there."},
            {"Brigette", "Look for signs of your friend getting hacked, and don't click on that website any further.",
                "Register for an account and log in.", "Use a linked account (Google, Facebook etc.) to log in."},
        };
        for (String[] row : curated) {
            List<Option> options = java.util.stream.IntStream.range(1, row.length)
                .mapToObj(index -> new Option(row[index], answer("x", "r_" + index).getClickEvent())).toList();
            Option chosen = NpcDialogueSkipFeature.choose(options, row[0], choices);
            assertNotNull(row[0] + " " + row[1], chosen);
            assertEquals(row[0], row[1], chosen.label());
        }
        // choose knows the curated rows only; manual NPCs and unlisted prompts are pick's business.
        Option yes = new Option("[Yes]", answer("coral", "r_1").getClickEvent());
        Option no = new Option("[No]", answer("coral", "r_2").getClickEvent());
        assertNull(NpcDialogueSkipFeature.choose(List.of(yes, no), "Coral", choices));
        assertNull(NpcDialogueSkipFeature.choose(List.of(yes, no), "Hunter Billy", choices));
    }

    @Test
    public void unlistedPromptsTakeTheFirstAnswerWhileAnNpcTalks() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        // Michael's answers lead to the same place and no row names him: the first one is clicked, in any order.
        Option silly = new Option("[No, I am very silly]", answer("mobtypes_michael", "r_4_1").getClickEvent());
        Option smart = new Option("[Yes, I am very smart]", answer("mobtypes_michael", "r_4_2").getClickEvent());
        assertSame(silly, NpcDialogueSkipFeature.pick(List.of(silly, smart), "Michael", choices, 2));
        assertSame(smart, NpcDialogueSkipFeature.pick(List.of(smart, silly), "Michael", choices, 2));
        // A curated row still wins over the order, and a lone answer is still clicked.
        Option yeah = new Option("[Yeah]", answer("kelly", "r_1").getClickEvent());
        Option never = new Option("[Never heard of him]", answer("kelly", "r_2").getClickEvent());
        assertSame(never, NpcDialogueSkipFeature.pick(List.of(yeah, never), "Kelly", choices, 2));
        assertSame(silly, NpcDialogueSkipFeature.pick(List.of(silly), "Michael", choices, 2));
        // No NPC line for a while: a stray prompt stays with the player.
        assertNull(NpcDialogueSkipFeature.pick(List.of(silly, smart), "Michael", choices,
            NpcDialogueSkipFeature.LONE_ANSWER_WINDOW_MILLIS));
        // One label that reads like a payment keeps the whole prompt with the player.
        Option pay = new Option("[Pay 500 Coins]", answer("mobtypes_michael", "r_4_3").getClickEvent());
        assertNull(NpcDialogueSkipFeature.pick(List.of(smart, pay), "Michael", choices, 2));
        // Trades and swaps are marked manual, by name or by npcId.
        Option hoppityYes = new Option("[Yes]", answer("hoppity", "r_2_1").getClickEvent());
        Option hoppityNo = new Option("[No]", answer("hoppity", "r_2_2").getClickEvent());
        assertNull(NpcDialogueSkipFeature.pick(List.of(hoppityYes, hoppityNo), "Hoppity", choices, 2));
        assertNull(NpcDialogueSkipFeature.pick(List.of(hoppityYes, hoppityNo), "", choices, 2));
        Option coralYes = new Option("[Yes]", answer("coral_x", "r_1").getClickEvent());
        Option coralNo = new Option("[No]", answer("coral_x", "r_2").getClickEvent());
        assertNull(NpcDialogueSkipFeature.pick(List.of(coralYes, coralNo), "Coral", choices, 2));
        assertNull(NpcDialogueSkipFeature.pick(List.of(coralYes, coralNo), "Hunter Billy", choices, 2));
    }

    @Test
    public void brigetteQuizTakesTheRightAnswerInAnyOrderAndWithOrWithoutTheMarker() {
        var choices = NpcDialogueSkipFeature.loadChoices();
        String right = "Leave the party and report the player.";
        String[] labels = {"Manually type the invite link in the browser and join the discord.", right,
            "Add the player on discord and ask for the invite link there."};
        for (String marker : new String[] {"", "@ "}) {
            List<Option> options = new java.util.ArrayList<>();
            for (int index = 0; index < labels.length; index++) {
                options.add(new Option(marker + labels[index], answer("brigette", "q2_" + index).getClickEvent()));
            }
            for (int turn = 0; turn < labels.length; turn++) {
                java.util.Collections.rotate(options, 1);
                assertEquals(marker + right, NpcDialogueSkipFeature.choose(options, "Brigette", choices).label());
            }
            // One wrong or unknown label in the set: nothing is clicked.
            options.set(0, new Option(marker + "Join the party's Discord.", answer("brigette", "q2_x").getClickEvent()));
            assertNull(NpcDialogueSkipFeature.choose(options, "Brigette", choices));
        }
        // Only for Brigette.
        Option a = new Option(labels[0], answer("x", "r_1").getClickEvent());
        Option b = new Option(right, answer("x", "r_2").getClickEvent());
        Option c = new Option(labels[2], answer("x", "r_3").getClickEvent());
        assertNull(NpcDialogueSkipFeature.choose(List.of(a, b, c), "Ryan", choices));
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
        // Brigette's last prompt came 8.4 s after her previous line; that is still the dialogue.
        assertTrue(NpcDialogueSkipFeature.loneAnswerable(give, 8_410));
        assertTrue(NpcDialogueSkipFeature.loneAnswerable(give, NpcDialogueSkipFeature.LONE_ANSWER_WINDOW_MILLIS - 1));
        // A stray one, no NPC line for a while (or ever), stays with the player.
        assertFalse(NpcDialogueSkipFeature.loneAnswerable(give, NpcDialogueSkipFeature.LONE_ANSWER_WINDOW_MILLIS));
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
        // From a trace: the sidebar drops the title that the name stand carries.
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to Gerald", "Fisherman Gerald"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Give Gerald Raw Cod", "Fisherman Gerald"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to Gerald", "Fisherwoman Enid"));
        // From a trace: the sidebar shortens "David Hunterborough" to "David H.".
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to David H.", "David Hunterborough"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to David H. at the shop", "David Hunterborough"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to David H.", "David Smith"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to David", "David Hunterborough"));
        // "Check on" is a talk objective; trailing stand decorations such as ♫ do not count.
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Check on Melody", "Melody ♫"));
        // From a trace: Crop 'n Profit sends the player to Sam with "Find out what Sam wants".
        assertTrue(NpcDialogueSkipFeature.clickable("Find out what Sam wants"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Find out what Sam wants", "Sam"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Find out what Sam wants", "Samuel"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Find out what happened", "Sam"));
        assertFalse(NpcDialogueSkipFeature.clickable("Clean your Garden Plot"));
        // From a trace: the chat line names Alan where the sidebar says "Buy your first Huntaxe".
        assertTrue(NpcDialogueSkipFeature.clickable("Buy a Hunting Weapon from Alan"));
        assertFalse(NpcDialogueSkipFeature.clickable("Buy your first Huntaxe"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Buy a Hunting Weapon from Alan", "Alan"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Buy a Hunting Weapon from Alan", "Alana"));
        // From a trace: the objective may name a place after the NPC.
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to Sam at the Composter", "Sam"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to Sam at the Composter!", "Sam"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to Sam at the Composter", "Samuel"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to Samuel", "Sam"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Talk to Sam Smith", "Sam"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Check on Melody", "Ryan"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Claim the trousers from Charlie!", "Charlie"));
        assertFalse(NpcDialogueSkipFeature.objectiveNames("Complete Trial of Fire I", "Fire"));
        assertEquals("Talk to Kelly", NpcDialogueSkipFeature.sidebarObjective(
            List.of("SKYBLOCK", " ⏣ The Park", "", "Objective", "§eTalk to Kelly")));
        // From traces: a compass arrow can follow the heading, pointing any way. Before, such a sidebar read as no objective.
        for (String arrow : new String[] {"⬈", "⬉", "⬊", "⬋", "⬆", "⬇"}) {
            assertEquals(arrow, "Talk to Molbert", NpcDialogueSkipFeature.sidebarObjective(
                List.of("SKYBLOCK", " ⏣ The Park", "", "Objective " + arrow, "§eTalk to Molbert")));
        }
        assertEquals("", NpcDialogueSkipFeature.sidebarObjective(List.of("Objectives", "Talk to Molbert")));
    }

    @Test
    public void everyCompletedTrialSendsKungBackToRyan() {
        // From a trace: no sidebar objective, only this line.
        assertTrue(NpcDialogueSkipFeature.trialDone("TRIAL OF FIRE! Completed Trial of Fire III!"));
        assertTrue(NpcDialogueSkipFeature.trialDone("TRIAL OF FIRE! Completed Trial of Fire I!"));
        assertFalse(NpcDialogueSkipFeature.trialDone("[NPC] Ryan: Complete the Trial of Fire III and I'll upgrade your badge!"));
        assertTrue(NpcDialogueSkipFeature.objectiveNames("Talk to Ryan", "Ryan"));

        ClickGuard guard = new ClickGuard();
        guard.clicked("Talk to Ryan", 1);
        assertFalse(guard.allows("Talk to Ryan"));
        guard.forget();
        assertTrue("the next trial is a new reason", guard.allows("Talk to Ryan"));
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

    @Test
    public void aQuestThatAsksForTheSameTalkAgainIsAClickAgain() {
        // From a trace: Mole Problems says "Talk to Molbert", "Place traps", then "Talk to Molbert" once more.
        ClickGuard guard = new ClickGuard();
        guard.clicked("Talk to Molbert", 1);
        guard.objectiveChanged("Talk to Molbert"); // the same objective seen again is still the clicked one
        assertFalse(guard.allows("Talk to Molbert"));
        guard.objectiveChanged("Place traps");
        assertTrue("the quest moved on, so the same words are a new step", guard.allows("Talk to Molbert"));
        guard.clicked("Talk to Molbert", 1);
        assertFalse(guard.allows("Talk to Molbert"));
        // Two steps in a row can share the words, so the sidebar never changes: NEW OBJECTIVE in chat forgets the click.
        guard.forget();
        assertTrue(guard.allows("Talk to Molbert"));
    }

    @Test
    public void aClickThatAnotherNpcsLineOutlivedIsTriedAgain() {
        ClickGuard guard = new ClickGuard();
        guard.clicked("Talk to Joel", 1);
        guard.spoke(false); // the NPC still mid-dialogue talks on: the click came to nothing
        assertTrue(guard.allows("Talk to Joel"));
        guard.clicked("Talk to Joel", 1);
        guard.spoke(true); // Joel answered
        guard.spoke(false);
        assertFalse("an answered click stays answered", guard.allows("Talk to Joel"));
        guard.forget();
        for (int i = 0; i < ClickGuard.MAX_TRIES; i++) {
            assertTrue(guard.allows("Talk to Joel"));
            guard.clicked("Talk to Joel", 1);
            guard.unanswered();
        }
        assertFalse("three silent tries are enough", guard.allows("Talk to Joel"));
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
