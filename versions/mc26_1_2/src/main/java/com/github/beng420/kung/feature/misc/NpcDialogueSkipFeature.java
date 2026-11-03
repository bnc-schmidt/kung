package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Full flavor only (listed in modrinthCut): answers NPC dialogue prompts and clicks the NPC a
 * "Talk to"/"Give" quest objective names. Both go through vanilla's own chat-click and right-click
 * paths, never through hand-built packets, and every action is logged under "npc-dialogue".
 */
public final class NpcDialogueSkipFeature extends ConfigurableFeature<MiscConfig> {
    public static final NpcDialogueSkipFeature INSTANCE = new NpcDialogueSkipFeature();
    static final String CHOICES_RESOURCE = "kung-npc-dialogue-choices.json";
    private static final String AREA = "npc-dialogue";
    /** The prompt lands about 2 s after the last NPC line; this only lets the server finish it. Fixed, not a disguise. */
    private static final long ANSWER_DELAY_MILLIS = 300L;
    private static final long ANSWER_DEDUPE_MILLIS = 10_000L;
    /**
     * A dialogue is over once no [NPC] line came for this long. Hypixel paces lines at about 0.67 s + 50 ms per
     * character, so gaps of 4-5 s are normal mid-dialogue; 3 s let Kung re-click Ryan while he was still talking.
     */
    static final long QUIET_MILLIS = 8_000L;
    /** Brigette paused 8.4 s before her last prompt, so a lone answer counts as part of the dialogue well beyond QUIET_MILLIS. */
    static final long LONE_ANSWER_WINDOW_MILLIS = 30_000L;
    /** The click that starts a dialogue lands well within this of the NPC's first line (about 0.2 s). */
    private static final long CLICK_TO_LINE_MILLIS = 3_000L;
    /** A line this soon after Kung's click answers it even when the speaker's name does not match the objective. */
    private static final long CLICK_ANSWER_MILLIS = 1_000L;
    private static final long NO_CLICK_LOG_MILLIS = 15_000L;
    private static final Pattern TRAILING_SYMBOLS = Pattern.compile("[^\\p{L}\\p{N}]+$");
    private static final Pattern NPC_LINE = Pattern.compile("^\\[NPC] (.+?): ");
    /** Labels that name coins or an amount; the wiki lists no button labels, so this errs on the side of the player. */
    private static final Pattern COSTS = Pattern.compile("(?i)coin|pay|buy|\\d");
    private static final Pattern CLICK_OBJECTIVE =
        Pattern.compile("^(?:Give|Talk to|Check on|Find out what|Buy .+ from) ", Pattern.CASE_INSENSITIVE);
    /** The chat line of a NEW OBJECTIVE may name the NPC where the sidebar does not ("Buy your first Huntaxe"). */
    private static final long CHAT_OBJECTIVE_MILLIS = 120_000L;
    /** "TRIAL OF FIRE! Completed Trial of Fire III!": Ryan has the next badge, but no sidebar objective says so. */
    private static final Pattern TRIAL_DONE = Pattern.compile("^TRIAL OF FIRE! Completed Trial of Fire \\w+!");
    private static final String TRIAL_OBJECTIVE = "Talk to Ryan";
    private static final List<Choice> CHOICES = loadChoices();

    private final ClickGuard clickGuard = new ClickGuard();
    /** A trial was completed and Ryan has not spoken since. */
    private boolean ryanDue;
    private String lastNpcName = "";
    private long lastNpcLineMillis;
    /** Entity id of the body the last [NPC] speaker was clicked on, for NPCs without a name stand (Romero). */
    private int speakerBodyId = -1;
    private String chatObjective = "";
    private long chatObjectiveMillis;
    private String loggedObjective = "";
    private String waitingLogged = "";
    /** Counts [NPC] lines; a line of another NPC lets Kung try the objective NPC once more, a click per line at most. */
    private int lineSeq;
    private int attemptedLine = -1;
    private long lastClickMillis;
    private long noClickLoggedMillis;
    private boolean objectiveNext;
    private Pending pending;
    private ClickEvent lastAnswer;
    private long lastAnswerMillis;
    /** The prompt last left to the player and when, so a duplicate of it stays quiet. */
    private List<ClickEvent> notified = List.of();
    private long notifiedMillis;
    /** Chat notice waiting for the next tick, so it lands after the prompt itself. */
    private String notice;

    private NpcDialogueSkipFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) message(message);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    @Override
    public boolean isEnabled() {
        return config().skipNpcDialogueEnabled();
    }

    /** Setting on, in SkyBlock, and not in a dungeon or Kuudra run. */
    private boolean active(Minecraft client) {
        if (!initialized() || !isEnabled() || client.player == null || client.gameMode == null) return false;
        HypixelLocation.Kind kind = HypixelInstanceTracker.INSTANCE.location().kind();
        return kind == HypixelLocation.Kind.SKYBLOCK || kind == HypixelLocation.Kind.DUNGEON_HUB;
    }

    private void message(Component message) {
        if (!active(Minecraft.getInstance())) return;
        for (String raw : HypixelLocation.lines(message.getString())) {
            String line = HypixelLocation.clean(raw);
            if (line.isEmpty()) continue;
            Matcher npcLine = NPC_LINE.matcher(line);
            if (npcLine.find()) {
                int clicked = NpcDialogueTrace.clickedWithin(CLICK_TO_LINE_MILLIS);
                if (clicked >= 0) speakerBodyId = clicked;
                else if (!npcLine.group(1).equals(lastNpcName)) speakerBodyId = -1;
                lastNpcName = npcLine.group(1);
                lastNpcLineMillis = System.currentTimeMillis();
                lineSeq++;
                clickGuard.spoke(lastNpcLineMillis - lastClickMillis <= CLICK_ANSWER_MILLIS
                    || objectiveNames(currentObjective(), lastNpcName));
                if (lastNpcName.equals("Ryan")) ryanDue = false;
            }
            if (trialDone(line)) {
                ryanDue = true;
                clickGuard.forget();
                KungDebugRecorder.event(AREA, "trial done, Ryan is due");
            }
            if (objectiveNext) {
                chatObjective = line;
                chatObjectiveMillis = System.currentTimeMillis();
                objectiveNext = false;
            } else if (line.contains("NEW OBJECTIVE")) {
                objectiveNext = true;
                // A new step, even when its words repeat the clicked one's ("Talk to Molbert" twice in a row).
                clickGuard.forget();
            } else if (line.contains("QUEST COMPLETE")) {
                chatObjective = "";
            }
        }

        List<Option> options = options(message);
        if (options.isEmpty()) return;
        String npc = options.getFirst().npcId().orElse(lastNpcName);
        Option option = pick(options, lastNpcName, CHOICES, System.currentTimeMillis() - lastNpcLineMillis);
        List<String> labels = options.stream().map(Option::label).toList();
        if (option == null) {
            KungDebugRecorder.event(AREA, "skip prompt npc=" + npc + " labels=" + labels);
            notifySkipped(options, lastNpcName.isEmpty() ? npc : lastNpcName);
            return;
        }
        KungDebugRecorder.event(AREA, "prompt npc=" + npc + " labels=" + labels + " -> " + option.label());
        pending = new Pending(option, npc, System.currentTimeMillis() + ANSWER_DELAY_MILLIS);
    }

    /** Tells the player once per prompt that Kung leaves it to them. */
    private void notifySkipped(List<Option> options, String npc) {
        List<ClickEvent> prompt = options.stream().map(Option::click).toList();
        long now = System.currentTimeMillis();
        if (prompt.equals(notified) && now - notifiedMillis < ANSWER_DEDUPE_MILLIS) return;
        notified = prompt;
        notifiedMillis = now;
        notice = skipNotice(npc);
    }

    static String skipNotice(String npc) {
        return "Multiple choice (" + npc + "): not skipping, pick an answer yourself";
    }

    private void tick(Minecraft client) {
        if (!active(client)) {
            pending = null;
            notice = null;
            ryanDue = false;
            return;
        }
        if (notice != null) {
            client.player.sendSystemMessage(KungMessages.info("NPC", notice));
            notice = null;
        }
        long now = System.currentTimeMillis();
        if (pending != null && now >= pending.dueMillis()) answer(client, pending, now);
        clickObjectiveNpc(client, now);
    }

    private void answer(Minecraft client, Pending answer, long now) {
        pending = null;
        Option option = answer.option();
        if (option.click().equals(lastAnswer) && now - lastAnswerMillis < ANSWER_DEDUPE_MILLIS) {
            KungDebugRecorder.event(AREA, "skip answered npc=" + answer.npc() + " label=" + option.label());
            return;
        }
        lastAnswer = option.click();
        lastAnswerMillis = now;
        KungDebugRecorder.event(AREA, "auto-answer npc=" + answer.npc() + " key=" + option.key() + " label=" + option.label());
        ChatClick.run(option.click(), client);
    }

    private void clickObjectiveNpc(Minecraft client, long now) {
        LocalPlayer player = client.player;
        long sinceNpcLine = now - lastNpcLineMillis;
        int inventory = inventoryStamp(player);
        // Every tick, before the early returns: a hand-in mid-dialogue must be absorbed, not saved up as a re-arm.
        clickGuard.inventory(inventory, sinceNpcLine);
        String objective = currentObjective();
        // The NPC who is talking is left alone until the quiet window ends; another NPC's line is a reason to try now.
        boolean talking = sinceNpcLine < QUIET_MILLIS && (attemptedLine == lineSeq || objectiveNames(objective, lastNpcName));
        String waiting = McCompat.screen(client) != null ? "a screen is open"
            : pending != null ? "an answer is pending"
            : talking ? "the objective NPC is talking, or this line of another NPC was tried" : "";
        boolean clickable = clickable(objective);
        // A shop or hand-in screen right after the click is the NPC's answer too, though no [NPC] line comes.
        if (McCompat.screen(client) != null && now - lastClickMillis <= CLICK_TO_LINE_MILLIS) clickGuard.spoke(true);
        if (!waiting.isEmpty()) {
            // Logged once per reason: a trace saved while Kung waits must not look like Kung ignoring the NPC.
            if (clickable && !waiting.equals(waitingLogged)) {
                waitingLogged = waiting;
                KungDebugRecorder.event(AREA, "waiting objective=\"" + objective + "\": " + waiting);
            }
            return;
        }
        waitingLogged = "";
        // No line answered the click by the end of the quiet window: try again (the guard bounds the tries).
        if (now - lastClickMillis >= QUIET_MILLIS) clickGuard.unanswered();
        if (!objective.isEmpty() && !objective.equals(loggedObjective)) {
            // Without this a trace cannot tell "no objective" from "objective, but no NPC in reach".
            loggedObjective = objective;
            clickGuard.objectiveChanged(objective);
            KungDebugRecorder.event(AREA, "objective=\"" + objective + "\" clickable=" + clickable);
        }
        if (!clickable) return;
        if (!clickGuard.allows(objective)) {
            noClick(now, objective, () -> "the click guard holds this objective");
            return;
        }
        NpcTarget target = findNpc(player, objective);
        if (target == null) {
            noClick(now, objective, () -> "no NPC in reach, nearby=" + NpcDialogueTrace.nearby(player, 6, 6));
            return;
        }

        Player body = target.body();
        clickGuard.clicked(objective, inventory);
        attemptedLine = lineSeq;
        lastClickMillis = now;
        KungDebugRecorder.event(AREA, String.format(Locale.ROOT, "auto-click npc=%s objective=\"%s\" dist=%.1f",
            target.name(), objective, player.distanceTo(body)));
        // What a right-click on the entity does (Minecraft.startUseItem), aimed at the body's center.
        InteractionResult result = client.gameMode.interact(player, body,
            new EntityHitResult(body, body.getBoundingBox().getCenter()), InteractionHand.MAIN_HAND);
        McCompat.swingForResult(player, InteractionHand.MAIN_HAND, result);
    }

    private String currentObjective() {
        String objective = ryanDue ? TRIAL_OBJECTIVE : sidebarObjective(HypixelInstanceTracker.INSTANCE.sidebarLines());
        if (objective.isEmpty()) return chatObjective;
        boolean chatNamesNpc = !clickable(objective) && clickable(chatObjective)
            && System.currentTimeMillis() - chatObjectiveMillis < CHAT_OBJECTIVE_MILLIS;
        return chatNamesNpc ? chatObjective : objective;
    }

    /** A clickable objective that stays unclicked says why at most every 15 s; the trace could not tell the reasons apart. */
    private void noClick(long now, String objective, java.util.function.Supplier<String> reason) {
        if (now - noClickLoggedMillis < NO_CLICK_LOG_MILLIS) return;
        noClickLoggedMillis = now;
        KungDebugRecorder.event(AREA, "no click objective=\"" + objective + "\": " + reason.get());
    }

    /**
     * The nearest NPC body in interaction range with a name stand the objective names. Romero has no name stand
     * (he hides among lookalikes), so failing that the body the current speaker was clicked on counts too.
     */
    private NpcTarget findNpc(LocalPlayer player, String objective) {
        NpcTarget best = null;
        List<Player> bodies = player.level().getEntitiesOfClass(Player.class,
            player.getBoundingBox().inflate(player.entityInteractionRange() + 1),
            body -> npcBodyInReach(player, body));
        for (Player body : bodies) {
            if (best != null && player.distanceToSqr(body) >= player.distanceToSqr(best.body())) continue;
            for (ArmorStand stand : NpcDialogueTrace.nameStands(body)) {
                String name = HypixelLocation.clean(stand.getCustomName().getString());
                if (objectiveNames(objective, name)) {
                    best = new NpcTarget(body, name);
                    break;
                }
            }
        }
        if (best == null && speakerBodyId >= 0 && objectiveNames(objective, lastNpcName)
            && player.level().getEntity(speakerBodyId) instanceof Player body && npcBodyInReach(player, body)) {
            best = new NpcTarget(body, lastNpcName);
        }
        return best;
    }

    /** Real accounts have version-4 UUIDs, Hypixel's NPC bodies do not (see NpcDialogueTrace). */
    private static boolean npcBodyInReach(LocalPlayer player, Player body) {
        return body != player && body.getUUID().version() != 4 && player.isWithinEntityInteractionRange(body, 0);
    }

    /** An objective that sends the player to an NPC: Kung clicks it once that NPC is in reach. */
    static boolean clickable(String objective) {
        return CLICK_OBJECTIVE.matcher(objective).find();
    }

    /**
     * Whether a "Give"/"Talk to"/"Check on" objective names this NPC, case-insensitively. Matching the real stand
     * names instead of parsing the objective keeps multi-word NPCs intact: "Give Lumber Jack Oak Logs".
     */
    static boolean objectiveNames(String objective, String npc) {
        String name = trimSymbols(HypixelLocation.clean(npc).toLowerCase(Locale.ROOT));
        if (name.isEmpty()) return false;
        if (namesExactly(objective, Pattern.quote(name))) return true;
        // From a trace: the sidebar says "Talk to Gerald" while the stand reads "Fisherman Gerald".
        int space = name.indexOf(' ');
        if (space > 0 && namesExactly(objective, Pattern.quote(name.substring(space + 1)))) return true;
        // From a trace: a long surname shrinks to its initial, "Talk to David H." for David Hunterborough.
        int last = name.lastIndexOf(' ');
        return last > 0 && namesExactly(objective, Pattern.quote(name.substring(0, last + 1) + name.charAt(last + 1)) + "\\.?");
    }

    /** {@code name} is a regex, so an abbreviated surname can end in an optional dot. */
    private static boolean namesExactly(String objective, String name) {
        String text = HypixelLocation.clean(objective).toLowerCase(Locale.ROOT);
        if (text.startsWith("give ")) return Pattern.compile(name + " ").matcher(text.substring(5)).lookingAt();
        String rest = text.startsWith("talk to ") ? text.substring(8)
            : text.startsWith("check on ") ? text.substring(9)
            // "Find out what Sam wants" (Crop 'n Profit): the NPC sits between the words.
            : text.startsWith("find out what ") && text.endsWith(" wants") ? text.substring(14, text.length() - 6)
            // "Buy a Hunting Weapon from Alan": Kung opens the shop, the purchase stays the player's.
            : text.startsWith("buy ") && text.contains(" from ") ? text.substring(text.lastIndexOf(" from ") + 6) : null;
        if (rest == null) return false;
        rest = trimSymbols(rest);
        // A place may follow the name: "Talk to Sam at the Composter".
        return rest.matches("(?:the )?" + name + "(?: (?:at|in|near|by|on|inside|outside) .+)?");
    }

    /** Drops trailing punctuation and decorations on both sides: "Charlie!" and the stand "Melody ♫". */
    private static String trimSymbols(String text) {
        return TRAILING_SYMBOLS.matcher(text).replaceAll("");
    }

    /** Item and count per slot, so lore or name refreshes do not count as the inventory changing. */
    private static int inventoryStamp(LocalPlayer player) {
        int stamp = 1;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            stamp = 31 * stamp + (stack.isEmpty() ? 0 : Objects.hash(stack.getItem(), stack.getCount()));
        }
        return stamp;
    }

    /** Dialogue answers in one message: Custom skyblock:dialogue_response or legacy /selectnpcoption. */
    static List<Option> options(Component message) {
        Map<ClickEvent, StringBuilder> labels = new LinkedHashMap<>();
        message.visit((style, part) -> {
            ClickEvent click = style.getClickEvent();
            // One option is often split into "[", label and "]" parts that share one event.
            if (answer(click)) labels.computeIfAbsent(click, key -> new StringBuilder()).append(part);
            return Optional.<Void>empty();
        }, Style.EMPTY);
        return labels.entrySet().stream()
            .map(entry -> new Option(HypixelLocation.clean(entry.getValue().toString()), entry.getKey()))
            .toList();
    }

    /** Never /cb, /chatprompt or any other clickable chat: party invites, warps and links look alike. */
    private static boolean answer(ClickEvent click) {
        return switch (click) {
            case ClickEvent.Custom(Identifier id, Optional<Tag> ignored) -> id.equals(NpcDialogueTrace.DIALOGUE_RESPONSE);
            case ClickEvent.RunCommand(String command) -> command.startsWith("/selectnpcoption ");
            case null, default -> false;
        };
    }

    /**
     * A lone answer ("[GIVE ITEM]" of an Abiphone unlock included) is clicked only while an NPC is talking, so a stray
     * prompt stays the player's, and never when its label reads like a payment: Hypixel's key is "pay" for items too.
     */
    static boolean loneAnswerable(Option option, long sinceNpcLineMillis) {
        return sinceNpcLineMillis < LONE_ANSWER_WINDOW_MILLIS && !COSTS.matcher(option.label()).find();
    }

    /**
     * The answer to click, or null to leave the prompt to the player: the curated one for this NPC, else the first
     * answer. The first answer is taken only while an NPC is talking, never when a label reads like a payment, and
     * never for an NPC the list marks manual (its prompts spend coins or swap items).
     */
    static Option pick(List<Option> options, String npcName, List<Choice> choices, long sinceNpcLineMillis) {
        Option curated = choose(options, npcName, choices);
        if (curated != null && options.size() > 1) return curated;
        Option first = options.getFirst();
        boolean manual = options.size() > 1 && manual(choices, first.npcId().orElse(null), npcName);
        return !manual && options.stream().allMatch(option -> loneAnswerable(option, sinceNpcLineMillis)) ? first : null;
    }

    private static boolean manual(List<Choice> choices, String npcId, String npcName) {
        return choices.stream().anyMatch(choice -> choice.manual()
            && (choice.npcId() != null && choice.npcId().equals(npcId) || choice.npc() != null && choice.npc().equals(npcName)));
    }

    /** The only option, or the curated one for this NPC and exactly these labels; null when the list names none. */
    static Option choose(List<Option> options, String npcName, List<Choice> choices) {
        if (options.size() == 1) return options.getFirst();
        Set<String> labels = options.stream().map(option -> plain(option.label())).collect(Collectors.toSet());
        String npcId = options.getFirst().npcId().orElse(null);
        for (Choice choice : choices) {
            if (choice.manual()) continue;
            boolean npc = choice.npcId() != null ? choice.npcId().equals(npcId) : choice.npc().equals(npcName);
            if (!npc || !Set.copyOf(choice.options()).equals(labels)) continue;
            return options.stream().filter(option -> plain(option.label()).equals(choice.choose())).findFirst().orElse(null);
        }
        return null;
    }

    /**
     * Brigette's quiz answers read "@ answer" on the wiki, and no trace shows yet whether the marker is part of the
     * clickable label. Curated labels never carry it, so both forms match the same entry.
     */
    private static String plain(String label) {
        return label.startsWith("@ ") ? label.substring(2) : label;
    }

    static boolean trialDone(String line) {
        return TRIAL_DONE.matcher(line).find();
    }

    /** "Objective", or "Objective ⬈": traces show a compass arrow after the heading, pointing any way. */
    private static final Pattern OBJECTIVE_HEADING = Pattern.compile("^Objective(?: [^\\p{L}\\p{N}\\s]+)?$");

    /** The line under the sidebar's "Objective" heading, or "" when the sidebar shows none. */
    static String sidebarObjective(List<String> lines) {
        for (int index = 0; index + 1 < lines.size(); index++) {
            if (OBJECTIVE_HEADING.matcher(HypixelLocation.clean(lines.get(index))).matches()) return HypixelLocation.clean(lines.get(index + 1));
        }
        return "";
    }

    static List<Choice> loadChoices() {
        try (InputStream stream = NpcDialogueSkipFeature.class.getClassLoader().getResourceAsStream(CHOICES_RESOURCE)) {
            if (stream == null) throw new IOException("missing " + CHOICES_RESOURCE);
            Choices data = new Gson().fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Choices.class);
            return data == null || data.choices() == null ? List.of() : List.copyOf(data.choices());
        } catch (IOException | RuntimeException exception) {
            KungDebugRecorder.event(AREA, "choices failed to load: " + exception);
            return List.of();
        }
    }

    record Option(String label, ClickEvent click) {
        Optional<String> npcId() {
            return payload().flatMap(tag -> tag.getString("npcId"));
        }

        /** responseKey for Custom answers, the command for /selectnpcoption. */
        String key() {
            return click instanceof ClickEvent.RunCommand(String command) ? command
                : payload().flatMap(tag -> tag.getString("responseKey")).orElse("?");
        }

        private Optional<net.minecraft.nbt.CompoundTag> payload() {
            return click instanceof ClickEvent.Custom(Identifier ignored, Optional<Tag> payload)
                ? payload.flatMap(Tag::asCompound) : Optional.empty();
        }
    }

    /**
     * One curated multi-option answer; npcId when the payload carries a known one, else the [NPC] name. A manual entry
     * names an NPC (by npcId, npc or both) whose prompts always stay with the player.
     */
    record Choice(String npcId, String npc, List<String> options, String choose, boolean manual) {
    }

    private record Choices(String attribution, List<Choice> choices) {
    }

    private record Pending(Option option, String npc, long dueMillis) {
    }

    private record NpcTarget(Player body, String name) {
    }

    /**
     * At most one click per objective. A "Give" objective re-arms once the inventory changes while no NPC is
     * talking; a change mid-dialogue (the hand-in itself taking the items) only moves the baseline.
     */
    static final class ClickGuard {
        static final int MAX_TRIES = 3;
        private String clicked = "";
        private int inventory;
        private boolean rearmed;
        private boolean confirmed;
        private String triedObjective = "";
        private int tries;

        /**
         * An [NPC] line: one from the clicked NPC (or right after the click) shows the click began its dialogue. A click
         * that no such line answered, because another NPC was mid-dialogue, may be tried again.
         */
        void spoke(boolean answersClick) {
            if (answersClick) {
                confirmed = true;
                tries = 0;
            } else {
                unanswered();
            }
        }

        /** A click that nothing answered is tried again, up to {@link #MAX_TRIES} times per objective. */
        void unanswered() {
            if (!confirmed && tries < MAX_TRIES) clicked = "";
        }

        /** Called every tick with the current inventory and the time since the last [NPC] line. */
        void inventory(int stamp, long sinceNpcLineMillis) {
            if (stamp == inventory) return;
            inventory = stamp;
            if (sinceNpcLineMillis >= QUIET_MILLIS) rearmed = true;
        }

        /** A new reason to talk to the same NPC again (the next trial is done). */
        void forget() {
            clicked = "";
            triedObjective = "";
        }

        /**
         * The sidebar moved on to another objective: coming back to the clicked one is the next step of the quest
         * ("Talk to Molbert" comes three times in Mole Problems), not the old objective still open.
         */
        void objectiveChanged(String objective) {
            if (objective.equals(clicked)) return;
            clicked = "";
            if (!objective.equals(triedObjective)) triedObjective = "";
        }

        boolean allows(String objective) {
            return !objective.equals(clicked) || rearmed && objective.startsWith("Give ");
        }

        void clicked(String objective, int stamp) {
            tries = objective.equals(triedObjective) ? tries + 1 : 1;
            triedObjective = objective;
            clicked = objective;
            inventory = stamp;
            rearmed = false;
            confirmed = false;
        }
    }

    /** Reaches Screen's protected handler, the one ChatScreen runs for a clicked chat segment. Never instantiated. */
    private abstract static class ChatClick extends Screen {
        private ChatClick() {
            super(Component.empty());
        }

        static void run(ClickEvent click, Minecraft client) {
            // The open screen (usually none) is passed so vanilla leaves it as it is.
            defaultHandleGameClickEvent(click, client, McCompat.screen(client));
        }
    }
}
