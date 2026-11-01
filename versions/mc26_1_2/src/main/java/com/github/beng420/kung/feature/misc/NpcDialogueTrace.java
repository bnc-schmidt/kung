package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Log-only trace ("npc-dialogue") for the dialogue skip: NPC chat and prompts, sent commands and dialogue
 * answers, entity clicks and NPC hand-over screens. It never sends, clicks, cancels or changes anything.
 * The recorder has no on/off switch, so each hook stays a few checks until something dialogue-shaped appears.
 * It also remembers which entity was clicked last, for the dialogue skip.
 */
public final class NpcDialogueTrace {
    private static final String AREA = "npc-dialogue";
    private static final Pattern DIALOGUE = Pattern.compile(
        "^(?:\\[NPC] |\\[Sacks] |You claimed .*!)|Select an option|\\[YES]|\\[NO]|NEW OBJECTIVE|QUEST COMPLETE|Question #\\d");
    private static final Pattern OBJECTIVE = Pattern.compile("NEW OBJECTIVE|QUEST COMPLETE");
    static final Identifier DIALOGUE_RESPONSE = Identifier.parse("skyblock:dialogue_response");
    private static final Pattern NPC_COMMAND = Pattern.compile("/(?:selectnpcoption|chatprompt|cb) ");
    /** "Claim Reward" or "<Name>'s ..." such as "Sam's Scythe". */
    private static final Pattern HAND_OVER_TITLE = Pattern.compile("Claim Reward|\\S.*'s .+");
    private static final long HAND_OVER_WINDOW_MILLIS = 10_000L;

    private static int objectiveLinesLeft;
    private static long lastNpcLineMillis;
    private static int clickedId = -1;
    private static long clickedMillis;
    private static int handOverContainer = -1;
    private static String handOverSlots = "";

    private NpcDialogueTrace() {
    }

    /** Every received chat or game message except the action bar. */
    public static void message(Component message) {
        String text = KungDebugRecorder.compact(message.getString());
        if (text.startsWith("[NPC] ")) lastNpcLineMillis = System.currentTimeMillis();
        boolean objectiveLine = objectiveLinesLeft > 0;
        if (objectiveLine) objectiveLinesLeft--;
        if (OBJECTIVE.matcher(text).find()) objectiveLinesLeft = 2;
        String line = describe(message, objectiveLine);
        if (line != null) KungDebugRecorder.event(AREA, line);
    }

    /** The trace line for a message, or null unless it has dialogue text or an NPC answer/prompt click. */
    static String describe(Component message, boolean force) {
        String text = KungDebugRecorder.compact(message.getString());
        Map<Object, Segment> segments = new LinkedHashMap<>();
        message.visit((style, part) -> {
            ClickEvent click = style.getClickEvent();
            HoverEvent hover = style.getHoverEvent();
            // One option is often split into "[", label and "]" parts that share one event.
            if (click != null || hover != null) {
                segments.computeIfAbsent(click != null ? click : hover,
                    key -> new Segment(new StringBuilder(), click, hover)).text().append(part);
            }
            return Optional.<Void>empty();
        }, Style.EMPTY);
        // Join lines (/viewprofile), mod tips and bestiary prompts are clickable too; they are noise here.
        if (!force && !DIALOGUE.matcher(text).find()
            && segments.values().stream().noneMatch(segment -> npcClick(segment.click()))) return null;

        StringBuilder line = new StringBuilder("chat \"").append(cut(text, 200)).append('"');
        for (Segment segment : segments.values()) {
            line.append(" | \"").append(KungDebugRecorder.compact(segment.text().toString())).append('"');
            if (segment.click() != null) line.append(' ').append(click(segment.click()));
            if (segment.hover() instanceof HoverEvent.ShowText(Component value)) {
                line.append(" hover=\"").append(cut(KungDebugRecorder.compact(value.getString()), 300)).append('"');
            } else if (segment.hover() != null) {
                line.append(" hover=").append(segment.hover().action().name());
            }
        }
        return line.toString();
    }

    /** Every packet the client sends. Only commands (typed or clicked) and dialogue answers are logged. */
    public static void sent(Packet<?> packet) {
        if (packet instanceof ServerboundChatCommandPacket command) {
            command(command.command());
        } else if (packet instanceof ServerboundChatCommandSignedPacket command) {
            command(command.command());
        } else if (packet instanceof ServerboundCustomClickActionPacket(Identifier id, Optional<Tag> payload)) {
            KungDebugRecorder.event(AREA, "answer CUSTOM=" + custom(id, payload));
        }
    }

    /** Right-click ("use", once per hand tried) or left-click ("attack") on an entity, before the packet. */
    public static void entity(String action, Player player, Entity entity, InteractionHand hand) {
        clickedId = entity.getId();
        clickedMillis = System.currentTimeMillis();
        UUID uuid = entity.getUUID();
        List<String> stands = nameStands(entity).stream()
            .map(stand -> '"' + KungDebugRecorder.compact(stand.getCustomName().getString()) + '"')
            .toList();
        KungDebugRecorder.event(AREA, String.format(Locale.ROOT,
            "%s hand=%s sneak=%s entity=%s id=%d name=\"%s\" uuidV=%d npcBody=%s dist=%.1f stands=%s location=\"%s\"",
            action, hand, player.isShiftKeyDown(), EntityType.getKey(entity.getType()).getPath(), entity.getId(),
            KungDebugRecorder.compact(entity.getDisplayName().getString()), uuid.version(),
            // Same test as DungeonDebuffFeature.sprayCandidate: real accounts have version-4 UUIDs.
            entity.getType() == McCompat.PLAYER && uuid.version() != 4, player.distanceTo(entity), stands,
            HypixelInstanceTracker.INSTANCE.instanceLine()));
    }

    /** Id of the entity clicked within the last {@code millis}, else -1. The one thing here the dialogue skip reads. */
    static int clickedWithin(long millis) {
        return System.currentTimeMillis() - clickedMillis <= millis ? clickedId : -1;
    }

    /** Each client tick from ScreenTracker: follows a reward screen opened right after an [NPC] line. */
    public static void screen(Screen screen) {
        AbstractContainerMenu menu = screen instanceof AbstractContainerScreen<?> container ? container.getMenu() : null;
        int containerId = menu == null ? -1 : menu.containerId;
        if (handOverContainer >= 0 && containerId != handOverContainer) {
            KungDebugRecorder.event(AREA, "hand-over closed container=" + handOverContainer);
            handOverContainer = -1;
        }
        if (menu == null) return;
        if (handOverContainer < 0) {
            if (System.currentTimeMillis() - lastNpcLineMillis > HAND_OVER_WINDOW_MILLIS) return;
            String title = KungDebugRecorder.compact(screen.getTitle().getString());
            if (!HAND_OVER_TITLE.matcher(title).matches()) return;
            handOverContainer = containerId;
            handOverSlots = "";
            KungDebugRecorder.event(AREA, "hand-over opened title=\"" + title + "\" container=" + containerId);
        }
        // Contents arrive after the open packet, so log whenever the non-filler set changes.
        StringJoiner slots = new StringJoiner(" | ", "[", "]");
        for (int slot = 0; slot < LoadoutsAutoCloseFeature.topSlotCount(menu); slot++) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!filler(stack)) slots.add(slot + "=" + LoadoutsAutoCloseFeature.itemDebug(stack));
        }
        if (!slots.toString().equals(handOverSlots)) {
            handOverSlots = slots.toString();
            KungDebugRecorder.event(AREA, "hand-over slots=" + handOverSlots);
        }
    }

    /** From MultiPlayerGameMode.handleContainerInput, before local prediction moves the stack. */
    public static void containerInput(int containerId, int slotId, int button, ContainerInput input) {
        Player player = Minecraft.getInstance().player;
        if (containerId != handOverContainer || player == null) return;
        KungDebugRecorder.event(AREA, "hand-over click slot=" + slotId + " button=" + button + " input=" + input
            + " item=" + LoadoutsAutoCloseFeature.itemDebug(LoadoutsAutoCloseFeature.slotStack(player.containerMenu, slotId)));
    }

    /** Dialogue answers and the prompt commands Hypixel attaches to NPC chat. */
    static boolean npcClick(ClickEvent click) {
        return switch (click) {
            case ClickEvent.Custom(Identifier id, Optional<Tag> ignored) -> id.equals(DIALOGUE_RESPONSE);
            case ClickEvent.RunCommand(String command) -> NPC_COMMAND.matcher(command).lookingAt();
            case null, default -> false;
        };
    }

    /** Named armor stands up to 3 blocks above the body: Hypixel's NPC name and "CLICK", highest first. */
    static List<ArmorStand> nameStands(Entity body) {
        return body.level().getEntitiesOfClass(ArmorStand.class,
                body.getBoundingBox().inflate(3, 0, 3).expandTowards(0, 3, 0),
                stand -> stand != body && stand.hasCustomName()).stream()
            .sorted(Comparator.comparingDouble(stand -> -stand.getY()))
            .toList();
    }

    private static String click(ClickEvent click) {
        String value = switch (click) {
            case ClickEvent.RunCommand(String command) -> command;
            case ClickEvent.SuggestCommand(String command) -> command;
            case ClickEvent.OpenUrl(URI uri) -> uri.toString();
            case ClickEvent.CopyToClipboard(String copied) -> copied;
            case ClickEvent.ChangePage(int page) -> Integer.toString(page);
            case ClickEvent.Custom(Identifier id, Optional<Tag> payload) -> custom(id, payload);
            default -> click.toString();
        };
        return click.action().name() + "=" + KungDebugRecorder.compact(value);
    }

    private static String custom(Identifier id, Optional<Tag> payload) {
        return id + " payload=" + payload.map(Tag::toString).orElse("none");
    }

    private static boolean filler(ItemStack stack) {
        return stack.isEmpty() || stack.getHoverName().getString().isBlank()
            && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("glass_pane");
    }

    private static void command(String command) {
        KungDebugRecorder.event(AREA, "command=/" + cut(command, 120));
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private record Segment(StringBuilder text, ClickEvent click, HoverEvent hover) {
    }
}
