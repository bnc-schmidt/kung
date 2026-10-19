package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungHudEditorState;
import com.github.beng420.kung.config.KungHudLayout;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.config.category.MiscConfig.SackItem;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.ui.UiTooltip;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Tracks chosen sack items: the track key over an item in a sack adds it, SkyBlock's own [Sacks]
 * messages move the counts, and an open sack sets them to what it shows.
 */
public final class SackTrackerFeature extends ConfigurableFeature<MiscConfig> {
    public static final SackTrackerFeature INSTANCE = new SackTrackerFeature();
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern STORED = Pattern.compile("^Stored: ([\\d.,]+[kKmMbB]?)/");
    private static final Pattern CHANGE = Pattern.compile("^([+-][\\d,]+) (.+) \\((.+)\\)$");
    private static final int SYNC_TICKS = 10;
    private static final int LINE_HEIGHT = 11;
    private static final long MAX_GOAL = 999_999_999L;
    private static final int KEY_BACKSPACE = 259;
    private static final int MOD_CONTROL = 2;
    private static final UiTheme THEME = UiTheme.settings();
    private static final Component RESET = Component.literal("[Reset Display]").withColor(0xFF5555);
    private static final String GOAL_HINT = "Type a number to set a target amount (Backspace deletes, Ctrl+Backspace clears)";
    private int ticksUntilSync;

    private SackTrackerFeature() { super(config -> config.misc); }

    @Override
    protected void onInitialize() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) observeMessage(message);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::syncOpenSack);
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST,
            Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "sack_tracker"), (graphics, delta) -> {
                if (McCompat.screen(Minecraft.getInstance()) == null) render(graphics);
            });
        // Menus draw over the HUD, so the tracker draws again on top of them - there it also takes clicks.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterExtract(screen).register(this::renderOverScreen);
            ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> !click(current, event));
            // Runs before the screen's own keyPressed, so a digit over a row never swaps the slot below with the hotbar.
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> !typeGoal(current, event));
        });
    }

    @Override
    public boolean isEnabled() { return initialized() && config().sackTrackerEnabled(); }

    /** The track key over an item in a sack: tracks it, or stops tracking it when it already is. */
    public static boolean handleKey(AbstractContainerScreen<?> screen, Slot hovered, KeyEvent event) {
        return INSTANCE.isEnabled() && LoadoutsAutoCloseFeature.keybindMatchesKey(INSTANCE.config().sackTrackerKeybind(), event)
            && INSTANCE.toggle(screen, hovered);
    }

    public static boolean handleMouse(AbstractContainerScreen<?> screen, Slot hovered, MouseButtonEvent event) {
        return INSTANCE.isEnabled() && event != null
            && LoadoutsAutoCloseFeature.keybindMatchesMouse(INSTANCE.config().sackTrackerKeybind(), event.button())
            && INSTANCE.toggle(screen, hovered);
    }

    private boolean toggle(AbstractContainerScreen<?> screen, Slot hovered) {
        if (hovered == null || !isSack(plain(screen.getTitle()))) return false;
        ItemStack stack = hovered.getItem();
        Long stored = stored(lore(stack));
        if (stored == null) return false;
        String name = plain(stack.getHoverName());
        boolean tracked = config().toggleSackTrackerItem(name, color(stack.getHoverName()), stored);
        KungMessages.send(Minecraft.getInstance(), KungMessages.Type.SUCCESS, "Sack Tracker",
            (tracked ? "Tracking " : "Stopped tracking ") + name);
        return true;
    }

    private void syncOpenSack(Minecraft client) {
        if (--ticksUntilSync > 0) return;
        ticksUntilSync = SYNC_TICKS;
        if (!isEnabled() || config().sackTrackerItems().isEmpty()
            || !(McCompat.screen(client) instanceof AbstractContainerScreen<?> screen) || !isSack(plain(screen.getTitle()))) return;
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            SackItem item = stack.isEmpty() ? null : config().sackTrackerItem(plain(stack.getHoverName()));
            Long stored = item == null ? null : stored(lore(stack));
            if (stored == null) continue;
            if (item.amount() != stored) KungDebugRecorder.event("sack-tracker",
                "sack shows " + item.name() + " " + stored + " (tracked " + item.amount() + ")");
            config().setSackTrackerAmount(item, stored);
        }
    }

    private void observeMessage(Component message) {
        if (!isEnabled() || config().sackTrackerItems().isEmpty()) return;
        String text = plain(message);
        if (!text.startsWith("[Sacks] ")) return;
        // "+130 items" and "-10 items" each carry their item list as hover text.
        Set<String> hovers = new LinkedHashSet<>();
        message.visit((style, part) -> {
            if (style.getHoverEvent() instanceof HoverEvent.ShowText(Component value)) hovers.add(plain(value));
            return Optional.<Void>empty();
        }, Style.EMPTY);
        // Every message counts. A message can overlap what an open sack already showed, which counts that
        // part twice; opening the sack sets the count right again.
        changes(hovers).forEach((name, delta) -> {
            SackItem item = config().sackTrackerItem(name);
            if (item == null) return;
            KungDebugRecorder.event("sack-tracker", "message " + name + " " + (delta > 0 ? "+" : "") + delta
                + " -> " + (item.amount() + delta));
            config().setSackTrackerAmount(item, item.amount() + delta);
        });
    }

    /** Net change per item across the hover lists: "+1,280 Wheat (Agronomy Sack)". */
    static Map<String, Long> changes(Collection<String> hovers) {
        Map<String, Long> changes = new LinkedHashMap<>();
        for (String hover : hovers) {
            for (String line : hover.split("\n")) {
                Matcher matcher = CHANGE.matcher(line.strip());
                if (matcher.matches()) changes.merge(matcher.group(2), Long.parseLong(matcher.group(1).replace(",", "")), Long::sum);
            }
        }
        return changes;
    }

    static boolean isSack(String title) { return title.endsWith(" Sack"); }

    /** "Stored: 1,234/20.2k" -> 1234; null for anything that is no sack item (gemstones list qualities instead). */
    static Long stored(List<String> lore) {
        for (String line : lore) {
            Matcher matcher = STORED.matcher(line.strip());
            if (matcher.find()) return parseAmount(matcher.group(1));
        }
        return null;
    }

    /** "1,234" -> 1234, "20.2k" -> 20200; null when it is no number. */
    public static Long parseAmount(String text) {
        String value = text.strip().replace(",", "").toLowerCase(Locale.ROOT);
        long factor = value.endsWith("k") ? 1_000L : value.endsWith("m") ? 1_000_000L : value.endsWith("b") ? 1_000_000_000L : 1L;
        if (factor > 1) value = value.substring(0, value.length() - 1);
        try {
            return Math.round(Double.parseDouble(value) * factor);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<String> lore(ItemStack stack) {
        return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(SackTrackerFeature::plain).toList();
    }

    private static String plain(Component component) { return FORMATTING.matcher(component.getString()).replaceAll(""); }

    /** The rarity color of the item's name. */
    private static int color(Component name) {
        return name.visit((style, part) -> part.isBlank() || style.getColor() == null
            ? Optional.<Integer>empty() : Optional.of(style.getColor().getValue()), Style.EMPTY).orElse(0xFFFFFF);
    }

    private void render(GuiGraphicsExtractor graphics) {
        if (visible() && McCompat.screen(Minecraft.getInstance()) == null) draw(graphics, config(), lines(config().sackTrackerItems(), false));
    }

    /** In a world only: menus over the title screen or while joining have no game to track. */
    private boolean visible() {
        var client = Minecraft.getInstance();
        return isEnabled() && !config().sackTrackerItems().isEmpty() && client.level != null
            && !McCompat.hudHidden(client) && !KungHudEditorState.externalEditing();
    }

    /** Over a menu the tracker is interactive; screens of Kung and of other mods (waypoint editors) keep their space. */
    private boolean overScreen(Screen screen) {
        return visible() && config().sackTrackerInMenus() && vanilla(screen.getClass());
    }

    static boolean vanilla(Class<?> screenClass) { return screenClass.getName().startsWith("net.minecraft."); }

    private void renderOverScreen(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!overScreen(screen)) return;
        graphics.nextStratum();
        draw(graphics, config(), lines(config().sackTrackerItems(), true));
        int line = lineAt(mouseX, mouseY);
        if (line >= 1 && line <= config().sackTrackerItems().size()) {
            List<String> tooltip = typesGoals(screen)
                ? List.of("Click to remove from tracker", GOAL_HINT) : List.of("Click to remove from tracker");
            UiTooltip.draw(graphics, Minecraft.getInstance().font, tooltip, mouseX + 8, mouseY, screen.width, screen.height, THEME);
        }
    }

    /**
     * Goal typing: inventories and containers only (chat and Kung's own screens keep their keys), and not
     * while a text field such as an anvil's name box has focus.
     */
    private boolean typesGoals(Screen screen) {
        return overScreen(screen) && screen instanceof AbstractContainerScreen<?> && !(screen.getFocused() instanceof EditBox);
    }

    /** True when the key edited the goal of the hovered tracker row; the screen must then not see it. */
    private boolean typeGoal(Screen screen, KeyEvent event) {
        if (!typesGoals(screen)) return false;
        var client = Minecraft.getInstance();
        int line = lineAt(client.mouseHandler.getScaledXPos(client.getWindow()), client.mouseHandler.getScaledYPos(client.getWindow()));
        List<SackItem> items = config().sackTrackerItems();
        if (line < 1 || line > items.size()) return false;
        SackItem item = items.get(line - 1);
        Long goal = nextGoal(item.goal(), event.key(), (event.modifiers() & MOD_CONTROL) != 0);
        if (goal == null) return false;
        config().setSackTrackerGoal(item, goal);
        return true;
    }

    /**
     * The goal after a key press: a digit (top row or numpad) appends, 1 then 5 is 15, up to nine digits;
     * Backspace drops the last digit, Ctrl+Backspace clears the goal (0 = none). Null for every other key.
     */
    static Long nextGoal(long goal, int key, boolean control) {
        int digit = key >= 48 && key <= 57 ? key - 48 : key >= 320 && key <= 329 ? key - 320 : -1;
        if (digit >= 0) return goal > (MAX_GOAL - digit) / 10 ? goal : goal * 10 + digit;
        if (key == KEY_BACKSPACE) return control ? 0L : goal / 10;
        return null;
    }

    /** Which drawn line the cursor is on: 1..items is an item, one past them is Reset Display, -1 is none. */
    private int lineAt(double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        List<Component> lines = lines(config().sackTrackerItems(), true);
        float scale = config().sackTrackerScale() / 100F;
        for (int index = 1; index < lines.size(); index++) {
            float left = config().sackTrackerX() + 8 * scale;
            float top = config().sackTrackerY() + (4 + index * LINE_HEIGHT) * scale;
            if (mouseX >= left && mouseX <= left + font.width(lines.get(index)) * scale
                && mouseY >= top && mouseY <= top + (LINE_HEIGHT - 1) * scale) return index;
        }
        return -1;
    }

    private boolean click(Screen screen, MouseButtonEvent event) {
        if (!overScreen(screen)) return false;
        int line = lineAt(event.x(), event.y());
        List<SackItem> items = config().sackTrackerItems();
        if (line >= 1 && line <= items.size()) config().removeSackTrackerItem(items.get(line - 1));
        else if (line == items.size() + 1) config().clearSackTrackerItems();
        else return false;
        return true;
    }

    /** "Enchanted Wheat: 1,234/10,000" - red until the goal, green from it; no goal shows the count alone. */
    static List<Component> lines(List<SackItem> items, boolean interactive) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Sack Tracker").withColor(0xFFFF55).withStyle(ChatFormatting.BOLD));
        for (SackItem item : items) {
            MutableComponent line = Component.literal(item.name()).withColor(item.color())
                .append(Component.literal(": ").withColor(0xFFFFFF));
            if (item.goal() <= 0) line.append(Component.literal(format(item.amount())).withColor(0xFFFF55));
            else line.append(Component.literal(format(item.amount())).withColor(item.amount() >= item.goal() ? 0x55FF55 : 0xFF5555))
                .append(Component.literal("/" + format(item.goal())).withColor(0xFFFFFF));
            lines.add(line);
        }
        if (interactive) lines.add(RESET);
        return lines;
    }

    public static String format(long amount) { return String.format(Locale.ROOT, "%,d", amount); }

    private static void draw(GuiGraphicsExtractor graphics, MiscConfig config, List<Component> lines) {
        var font = Minecraft.getInstance().font;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(config.sackTrackerX(), config.sackTrackerY());
            float scale = config.sackTrackerScale() / 100F;
            graphics.pose().scale(scale, scale);
            for (int index = 0; index < lines.size(); index++) {
                graphics.text(font, lines.get(index), index == 0 ? 4 : 8, 4 + index * LINE_HEIGHT, 0xFFFFFFFF, true);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static List<Component> previewLines(MiscConfig config) {
        return lines(config.sackTrackerItems().isEmpty()
            ? List.of(new SackItem("Enchanted Wheat", 0x55FF55, 1234, 10000), new SackItem("Wheat", 0xFFFFFF, 56789, 0))
            : config.sackTrackerItems(), false);
    }

    public static void drawPreview(GuiGraphicsExtractor graphics, MiscConfig config) {
        draw(graphics, config, previewLines(config));
    }

    public static KungHudLayout.Bounds overlayBounds(MiscConfig config) {
        var client = Minecraft.getInstance();
        List<Component> lines = previewLines(config);
        int width = client == null ? 150 : lines.stream().mapToInt(line -> client.font.width(line)).max().orElse(0) + 16;
        float scale = config.sackTrackerScale() / 100F;
        return new KungHudLayout.Bounds(config.sackTrackerX(), config.sackTrackerY(),
            Math.round(width * scale), Math.round((lines.size() * LINE_HEIGHT + 6) * scale));
    }
}
