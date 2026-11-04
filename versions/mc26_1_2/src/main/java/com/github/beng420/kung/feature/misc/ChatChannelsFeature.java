package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.compat.McInput;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.mixin.ChatComponentAccessor;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Tabs under the open chat that switch what the chat shows and where your messages go, and Hypixel's channel as the
 * player has it. The All tab shows every line except the kinds (Guild, Party, Co-op, DMs) switched off in the flyout
 * that opens over it; another tab shows only its own kind and sends {@code /chat <channel>}. The kinds' lines stay
 * stored, so every switch brings them back. ChatComponentMixin asks {@link #hidden} for each message,
 * {@link ChatChannels} says which kind a line is. The channel is also what Current Chat Display shows.
 */
public final class ChatChannelsFeature extends ConfigurableFeature<MiscConfig> {
    public static final ChatChannelsFeature INSTANCE = new ChatChannelsFeature();
    private static final UiTheme THEME = UiTheme.settings();
    /** The tabs in order; null is the All tab. */
    private static final ChatChannels.Channel[] TABS = {
        null, ChatChannels.Channel.PARTY, ChatChannels.Channel.GUILD, ChatChannels.Channel.COOP, ChatChannels.Channel.DIRECT
    };
    private static final int BAR_HEIGHT = 12;
    private static final int BOX = 7;
    private static final int PAD = 5;
    private static final int GAP = 2;
    /** The band between the chat lines (40 up from the bottom) and the input; Chat Patches' search sits at its left. */
    private static final int BAR_FROM_BOTTOM = 31;
    private static final int EDGE = 2;
    /** "You're already in this channel!" names no channel, so it counts for the /chat sent just before it. */
    private static final long PENDING_MILLIS = 5_000L;
    private static final int CLASSIFIED_LIMIT = 40_000;

    /** Where your next message goes; Hypixel's own state, read from its notices. Client thread only. */
    private static ChatChannels.Send send = ChatChannels.Send.ALL;
    /** The shown tab: null for All. */
    private static ChatChannels.Channel view;
    private static ChatChannels.Send pending;
    private static long pendingAtMillis;
    /** Channel of each message by content identity, so a rebuild of thousands of lines classifies each only once. */
    private static final Map<Component, Optional<ChatChannels.Channel>> CLASSIFIED = new IdentityHashMap<>();

    private ChatChannelsFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        send = ChatChannels.storedSend(config().chatSend());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && tracking()) onLine(message.getString());
        });
        ClientSendMessageEvents.COMMAND.register(command -> {
            if (tracking()) onCommand(command);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> forgetChannel());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) ->
                render(client, current, graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !click(client, current, event));
        });
    }

    @Override
    protected void onReset() { forgetChannel(); }

    @Override
    public boolean isEnabled() { return initialized() && config().chatChannelsEnabled(); }

    /** The channel is followed for the tabs and for Current Chat Display, so either one being on is enough. */
    private boolean tracking() { return initialized() && (config().chatChannelsEnabled() || config().currentChatDisplayEnabled()); }

    /** Where your next message goes. */
    public static ChatChannels.Send send() { return send; }

    /** The sent-to channel is kept and saved, so a reconnect or restart still shows it; only the pending switch goes. */
    private static void forgetChannel() {
        pending = null;
        if (view != null) {
            view = null;
            refresh();
        }
    }

    // ---- Hypixel's channel -------------------------------------------------------------------------------------

    private static void onLine(String text) {
        ChatChannels.Send notice = ChatChannels.switchNotice(text);
        if (notice != null) {
            switched(notice);
        } else if (pending != null && ChatChannels.alreadyNotice(text)) {
            if (System.currentTimeMillis() - pendingAtMillis < PENDING_MILLIS) switched(pending);
            pending = null;
        }
    }

    private static void onCommand(String command) {
        ChatChannels.Send target = ChatChannels.chatCommand(command);
        if (target == null) return;
        pending = target;
        pendingAtMillis = System.currentTimeMillis();
    }

    private static void switched(ChatChannels.Send channel) {
        send = channel;
        pending = null;
        if (!INSTANCE.initialized()) return;
        INSTANCE.config().setChatSend(channel.name());
        if (INSTANCE.config().chatChannelsFollowChat()) setView(channel.channel());
    }

    // ---- What the chat shows -----------------------------------------------------------------------------------

    /** Whether the chat gives this message no lines. A hidden message is kept, so showing it again brings it back. */
    public static boolean hidden(GuiMessage message) {
        if (!INSTANCE.isEnabled()) return false;
        ChatChannels.Channel channel = channelOf(message);
        if (view != null) return channel != view;
        return channel != null && INSTANCE.config().chatChannelHidden(channel.name());
    }

    private static ChatChannels.Channel channelOf(GuiMessage message) {
        Component content = message.content();
        Optional<ChatChannels.Channel> known = CLASSIFIED.get(content);
        if (known == null) {
            if (CLASSIFIED.size() >= CLASSIFIED_LIMIT) CLASSIFIED.clear();
            known = Optional.ofNullable(ChatChannels.classify(content.getString()));
            CLASSIFIED.put(content, known);
        }
        return known.orElse(null);
    }

    private static boolean shown(ChatChannels.Channel channel) {
        return !INSTANCE.config().chatChannelHidden(channel.name());
    }

    private static void setView(ChatChannels.Channel tab) {
        if (view == tab) return;
        view = tab;
        refresh();
    }

    /** A click on a tab: show it at once, and sends the channel; DMs have none and only change the view. */
    private static void selectTab(Minecraft client, ChatChannels.Channel tab) {
        setView(tab);
        ChatChannels.Send target = ChatChannels.sendFor(tab);
        if (target == null || target == send || client.player == null) return;
        client.player.connection.sendCommand(target.command());
    }

    /**
     * Rebuilds the chat lines from all messages. Lines already built are kept, so only a message that was hidden
     * until now is wrapped again; vanilla's own rebuild wraps every one of up to thousands of messages, which hitched
     * every switch. The scroll position follows the message at the bottom of the shown page, so a switch does not
     * throw you to the newest line; when that message is gone, the nearest one that is left takes its place.
     */
    public static void refresh() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) return;
        long started = System.nanoTime();
        ChatComponent chat = McCompat.chat(client);
        ChatComponentAccessor access = (ChatComponentAccessor) chat;
        List<GuiMessage> all = access.kung$getAllMessages();
        List<GuiMessage.Line> lines = access.kung$getTrimmedMessages();
        // Line number 0 is the newest; the scroll position is the number of lines below the shown page.
        Anchor<GuiMessage> anchor = anchorAt(lines.size(), access.kung$getChatScrollbarPos(), index -> lines.get(index).parent());
        Map<GuiMessage, List<GuiMessage.Line>> built = new IdentityHashMap<>();
        for (GuiMessage.Line line : lines) built.computeIfAbsent(line.parent(), message -> new ArrayList<>(2)).add(line);
        int width = Mth.floor(access.kung$width() / access.kung$scale());
        List<GuiMessage.Line> rebuilt = new ArrayList<>(lines.size());
        Map<GuiMessage, int[]> placed = new IdentityHashMap<>();
        int wrapped = 0;
        for (GuiMessage message : all) {
            if (hidden(message)) continue;
            List<GuiMessage.Line> own = built.get(message);
            if (own == null) {
                own = wrap(client, message, width);
                wrapped++;
            }
            placed.put(message, new int[] {rebuilt.size(), own.size()});
            rebuilt.addAll(own);
        }
        lines.clear();
        lines.addAll(rebuilt);
        int target = anchoredLine(anchor, all, placed);
        // scrollChat adds to the position and cuts it back to what is left, so this is also the clamp.
        chat.scrollChat(target < 0 ? 0 : target - access.kung$getChatScrollbarPos());
        KungDebugRecorder.event("chat-channels", "rebuild messages=" + all.size() + " lines=" + rebuilt.size()
            + " wrapped=" + wrapped + " ms=" + (System.nanoTime() - started) / 1_000_000.0);
    }

    /** The message at the bottom of the shown page and which of its lines that is; line 0 is the newest. */
    record Anchor<M>(M message, int line) {}

    /** The anchor of a page scrolled {@code scrolled} lines up, or null at the bottom, where the chat stays. */
    static <M> Anchor<M> anchorAt(int lineCount, int scrolled, IntFunction<M> parentOf) {
        if (scrolled <= 0 || scrolled >= lineCount) return null;
        M message = parentOf.apply(scrolled);
        int first = scrolled;
        while (first > 0 && parentOf.apply(first - 1) == message) first--;
        return new Anchor<>(message, scrolled - first);
    }

    /**
     * The line number the anchor has after a rebuild, or -1 for none. {@code placed} holds each shown message's
     * first line and line count; a hidden anchor falls back to the nearest shown message in {@code all}.
     */
    static <M> int anchoredLine(Anchor<M> anchor, List<M> all, Map<M, int[]> placed) {
        if (anchor == null) return -1;
        int[] own = placed.get(anchor.message());
        if (own != null) return own[0] + Math.min(anchor.line(), own[1] - 1);
        int from = identityIndex(all, anchor.message());
        int near = from < 0 ? -1 : nearestVisible(from, all.size(), index -> placed.containsKey(all.get(index)));
        return near < 0 ? -1 : placed.get(all.get(near))[0];
    }

    private static <M> int identityIndex(List<M> messages, M wanted) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) == wanted) return index;
        }
        return -1;
    }

    /** The index, newest first, of the shown message nearest to {@code from}; the older one on a tie, -1 when none. */
    static int nearestVisible(int from, int size, IntPredicate visible) {
        for (int distance = 0; distance < size; distance++) {
            int older = from + distance;
            int newer = from - distance;
            if (older < size && visible.test(older)) return older;
            if (newer >= 0 && visible.test(newer)) return newer;
        }
        return -1;
    }

    /**
     * Chat Patches turns a clicked line into an index of allMessages by counting messages with lines, so every hidden
     * message in front of it throws the index off. ChatPatchesChatUtilMixin runs its two conversions through these.
     * An index counts shown messages only, newest first; the all-index is the one of allMessages.
     */
    public static int allIndexOfShown(int shown) {
        if (!INSTANCE.isEnabled()) return shown;
        List<GuiMessage> all = allMessages();
        return indexOfShown(shown, all.size(), index -> !hidden(all.get(index)));
    }

    /** The reverse of {@link #allIndexOfShown}: -1 for a hidden message, which has no line to mark. */
    public static int shownIndexOfAll(int index) {
        if (!INSTANCE.isEnabled()) return index;
        List<GuiMessage> all = allMessages();
        return shownBefore(index, all.size(), at -> !hidden(all.get(at)));
    }

    private static List<GuiMessage> allMessages() {
        return ((ChatComponentAccessor) McCompat.chat(Minecraft.getInstance())).kung$getAllMessages();
    }

    /** The index of the {@code shown}-th shown message, newest first; -1 when there is none. */
    static int indexOfShown(int shown, int size, IntPredicate visible) {
        if (shown < 0) return -1;
        int left = shown;
        for (int index = 0; index < size; index++) {
            if (visible.test(index) && left-- == 0) return index;
        }
        return -1;
    }

    /** How many shown messages are newer than {@code index}; -1 when that message is out of range or hidden itself. */
    static int shownBefore(int index, int size, IntPredicate visible) {
        if (index < 0 || index >= size || !visible.test(index)) return -1;
        int count = 0;
        for (int newer = 0; newer < index; newer++) {
            if (visible.test(newer)) count++;
        }
        return count;
    }

    /** The lines of one message the way vanilla stores them: the last one first, and it ends the entry. */
    private static List<GuiMessage.Line> wrap(Minecraft client, GuiMessage message, int width) {
        var parts = message.splitLines(client.font, width);
        List<GuiMessage.Line> lines = new ArrayList<>(parts.size());
        for (int index = parts.size() - 1; index >= 0; index--) {
            lines.add(new GuiMessage.Line(message, parts.get(index), index == parts.size() - 1));
        }
        return lines;
    }

    // ---- The tabs ----------------------------------------------------------------------------------------------

    /** One tab or one check of the flyout: its kind (null is the All tab) and where it sits, in GUI pixels. */
    record Slot(ChatChannels.Channel channel, int x, int y, int width, int height) {
        boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
    }

    /** The tabs in a row, right-aligned in the band under the chat so the left stays free for Chat Patches' search. */
    static List<Slot> tabs(int screenWidth, int screenHeight, ToIntFunction<String> textWidth) {
        int[] widths = new int[TABS.length];
        int total = GAP * (TABS.length - 1);
        for (int i = 0; i < TABS.length; i++) {
            widths[i] = PAD + textWidth.applyAsInt(tabLabel(TABS[i])) + PAD;
            total += widths[i];
        }
        int x = Math.max(EDGE, screenWidth - EDGE - total);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < TABS.length; i++) {
            slots.add(new Slot(TABS[i], x, screenHeight - BAR_FROM_BOTTOM, widths[i], BAR_HEIGHT));
            x += widths[i] + GAP;
        }
        return slots;
    }

    /** The checks that open over the All tab, one per kind, stacked upwards from the tab. */
    static List<Slot> flyout(Slot all, ToIntFunction<String> textWidth) {
        ChatChannels.Channel[] kinds = ChatChannels.Channel.values();
        int width = 0;
        for (ChatChannels.Channel kind : kinds) width = Math.max(width, PAD + BOX + PAD + textWidth.applyAsInt(kind.label()) + PAD);
        width = Math.max(width, all.width());
        List<Slot> rows = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            rows.add(new Slot(kinds[i], all.x(), all.y() - (i + 1) * BAR_HEIGHT, width, BAR_HEIGHT));
        }
        return rows;
    }

    private static String tabLabel(ChatChannels.Channel tab) { return tab == null ? "All" : tab.label(); }

    /** The flyout stays open while the pointer is over the All tab or over the flyout itself. */
    private static boolean flyoutOpen(Slot all, List<Slot> rows, double mouseX, double mouseY) {
        if (all.contains(mouseX, mouseY)) return true;
        Slot top = rows.getLast();
        return mouseX >= top.x() && mouseX < top.x() + top.width() && mouseY >= top.y() && mouseY < all.y();
    }

    private static void render(Minecraft client, Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!INSTANCE.isEnabled()) return;
        graphics.nextStratum();
        List<Slot> tabs = tabs(screen.width, screen.height, client.font::width);
        for (Slot tab : tabs) {
            boolean selected = tab.channel() == view;
            graphics.fill(tab.x(), tab.y(), tab.x() + tab.width(), tab.y() + tab.height(),
                selected ? THEME.accentDark() : tab.contains(mouseX, mouseY) ? THEME.controlHover() : THEME.control());
            if (tab.channel() == send.channel()) {
                // The tab Hypixel sends to (Officer counts for Guild): a line under it, whichever tab is shown.
                graphics.fill(tab.x(), tab.y() + tab.height() - 1, tab.x() + tab.width(), tab.y() + tab.height(), THEME.success());
            }
            graphics.text(client.font, tabLabel(tab.channel()), tab.x() + PAD, textY(tab), selected ? THEME.text() : THEME.muted(), true);
        }
        Slot all = tabs.getFirst();
        List<Slot> rows = flyout(all, client.font::width);
        if (!flyoutOpen(all, rows, mouseX, mouseY)) return;
        for (Slot row : rows) {
            boolean on = shown(row.channel());
            graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(),
                row.contains(mouseX, mouseY) ? THEME.controlHover() : THEME.panelDark());
            int boxX = row.x() + PAD;
            int boxY = row.y() + (row.height() - BOX) / 2;
            graphics.fill(boxX, boxY, boxX + BOX, boxY + BOX, THEME.muted());
            graphics.fill(boxX + 1, boxY + 1, boxX + BOX - 1, boxY + BOX - 1, on ? THEME.accent() : THEME.panelDark());
            graphics.text(client.font, row.channel().label(), boxX + BOX + PAD, textY(row), on ? THEME.text() : THEME.muted(), true);
        }
    }

    private static int textY(Slot slot) { return slot.y() + (slot.height() - 8) / 2 + 1; }

    /** A left click on a tab or a check; true means the click is taken, so the chat screen never sees it. */
    private static boolean click(Minecraft client, Screen screen, MouseButtonEvent event) {
        if (!INSTANCE.isEnabled()) return false;
        List<Slot> tabs = tabs(screen.width, screen.height, client.font::width);
        Slot all = tabs.getFirst();
        List<Slot> rows = flyout(all, client.font::width);
        boolean left = McInput.button(event) == 0;
        if (flyoutOpen(all, rows, event.x(), event.y())) {
            for (Slot row : rows) {
                if (!row.contains(event.x(), event.y())) continue;
                if (left) {
                    INSTANCE.config().setChatChannelHidden(row.channel().name(), shown(row.channel()));
                    refresh();
                }
                return true;
            }
        }
        for (Slot tab : tabs) {
            if (!tab.contains(event.x(), event.y())) continue;
            if (left) selectTab(client, tab.channel());
            return true;
        }
        return false;
    }
}
