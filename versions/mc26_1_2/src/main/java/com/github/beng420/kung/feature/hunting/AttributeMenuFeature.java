package com.github.beng420.kung.feature.hunting;

import com.github.beng420.kung.config.category.AttributeMenuConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.MenuItems;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Attribute Menu: each attribute's level as the item's stack count, and a red slot background on every attribute
 * below level 10. The level comes from the item's name ({@link AttributeLevel}). The red background is drawn from a
 * mixin that runs after other mods' item backgrounds, so it covers their rarity colors. The trace area
 * {@code attribute-menu} records what each slot of the open menu was read as, once per item.
 */
public final class AttributeMenuFeature extends ConfigurableFeature<AttributeMenuConfig> {
    public static final AttributeMenuFeature INSTANCE = new AttributeMenuFeature();
    private static final String AREA = "attribute-menu";
    private static final int RED = 0xFFCC3333;
    private static final int LORE_LIMIT = 200;

    /** The item last traced in each slot of {@link #tracedMenu}, so a slot is traced again only when its item changes. */
    private static final Map<Integer, ItemStack> traced = new HashMap<>();
    private static AbstractContainerMenu tracedMenu;

    private AttributeMenuFeature() { super(config -> config.attributeMenu); }

    @Override
    protected void onInitialize() { }

    @Override
    protected void onReset() {
        traced.clear();
        tracedMenu = null;
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled(); }

    /** The number drawn on the item in this slot, from the slot's item-count hook; null for no number. */
    public static String slotLabel(AbstractContainerScreen<?> screen, Slot slot) {
        if (!INSTANCE.isEnabled() || !INSTANCE.config().showLevel()) return null;
        OptionalInt level = levelAt(screen, slot);
        boolean shows = level.isPresent() && AttributeLevel.showsNumber(level.getAsInt(), INSTANCE.config().levelOnlyBelowMax());
        return shows ? Integer.toString(level.getAsInt()) : null;
    }

    /** Fills the slot red under its item; called right before the item is drawn. */
    public static void drawBackground(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        if (!INSTANCE.isEnabled() || !INSTANCE.config().redBackground()) return;
        OptionalInt level = levelAt(screen, slot);
        if (level.isPresent() && AttributeLevel.isRed(level.getAsInt())) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, RED);
        }
    }

    /** The level of the attribute in this slot; empty for any other slot or screen. */
    private static OptionalInt levelAt(AbstractContainerScreen<?> screen, Slot slot) {
        AbstractContainerMenu menu = screen.getMenu();
        if (slot == null || slot.index >= MenuItems.topSlots(menu)) return OptionalInt.empty();
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !AttributeLevel.isMenuTitle(screen.getTitle().getString())) return OptionalInt.empty();
        String name = stack.getHoverName().getString();
        OptionalInt level = AttributeLevel.of(name, MenuItems.lore(stack));
        trace(menu, slot, stack, name, level);
        return level;
    }

    private static void trace(AbstractContainerMenu menu, Slot slot, ItemStack stack, String name, OptionalInt level) {
        if (tracedMenu != menu) {
            traced.clear();
            tracedMenu = menu;
        }
        if (traced.put(slot.index, stack) == stack || name.isBlank()) return;
        String lore = String.join(" | ", MenuItems.lore(stack));
        if (lore.length() > LORE_LIMIT) lore = lore.substring(0, LORE_LIMIT) + "...";
        KungDebugRecorder.event(AREA, "slot=" + slot.index + (level.isPresent() ? " attribute level=" + level.getAsInt() : " other")
            + " name=\"" + name + "\" lore=\"" + lore + "\"");
    }
}
