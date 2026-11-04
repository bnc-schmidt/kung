package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import com.github.beng420.kung.util.ServerTickSequence;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.BeforeClass;
import org.junit.Test;

public final class BowDrawProgressTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void onlyAcceptedServerTicksAdvanceAndNewDrawsStartEmpty() {
        var draw = new BowDrawProgress();
        var sequence = new ServerTickSequence();
        draw.serverTick();
        assertFalse(draw.active());
        draw.start();
        for (int id : new int[] {-1, -1, 0, -2, -2, -3}) {
            if (sequence.accept(id, false)) draw.serverTick();
        }
        if (sequence.accept(-4, true)) draw.serverTick();
        assertEquals(3, draw.ticks());
        draw.reset();
        draw.serverTick();
        assertFalse(draw.active());
        draw.start();
        assertEquals(0, draw.ticks());
        for (int tick = 0; tick < 100; tick++) draw.serverTick();
        assertEquals(20, draw.ticks());
        draw.start();
        assertEquals(0, draw.ticks());
    }

    @Test
    public void vanillaPowerHasMinimumShotAndContinuousGrowthUntilFullDraw() {
        assertEquals(0F, BowDrawProgress.power(-1), 0F);
        assertTrue(BowDrawProgress.power(2) < 0.1F);
        assertTrue(BowDrawProgress.power(BowDrawProgress.MIN_SHOT_TICKS) >= 0.1F);
        assertEquals(0.5741667F, BowDrawProgress.power(13), 0.000001F);
        assertEquals(0.87F, BowDrawProgress.power(18), 0.000001F);
        for (int tick = 1; tick <= 20; tick++) {
            assertTrue(BowDrawProgress.power(tick) > BowDrawProgress.power(tick - 1));
        }
        assertEquals(1F, BowDrawProgress.power(BowDrawProgress.FULL_DRAW_TICKS), 0F);
        assertEquals(1F, BowDrawProgress.power(Integer.MAX_VALUE), 0F);
    }

    @Test
    public void subFrameDrawsRemainVisibleAndRedrawNeverInheritsReleasedCharge() {
        var draw = new BowDrawProgress();
        assertFalse(draw.visible(0));
        draw.start();
        assertTrue(draw.visible(0));
        draw.stop(1_000);
        draw.serverTick();
        assertFalse(draw.active());
        assertEquals(0, draw.ticks());
        assertTrue(draw.visible(1_001));
        assertTrue(draw.visible(1_000 + BowDrawProgress.RELEASE_HOLD_NANOS - 1));
        draw.stop(5_000); // Repeated stops cannot extend visibility.
        assertFalse(draw.visible(1_000 + BowDrawProgress.RELEASE_HOLD_NANOS));

        draw.start();
        for (int tick = 0; tick < 12; tick++) draw.serverTick();
        draw.stop(10_000);
        draw.serverTick();
        assertEquals(12, draw.ticks());
        draw.start();
        assertTrue(draw.active());
        assertEquals(0, draw.ticks());
        draw.reset();
        assertFalse(draw.visible(10_001));
    }

    @Test
    public void inventoryStackCopiesAndDamageUpdatesDoNotCancelTheSameBow() {
        ItemStack original = new ItemStack(Holder.direct(Items.BOW, DataComponentMap.EMPTY));
        ItemStack updated = original.copy();
        assertNotSame(original, updated);
        updated.set(DataComponents.DAMAGE, 10);
        assertTrue(BowDrawIndicatorFeature.sameDrawBow(original, updated));
        updated.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§6LEGENDARY SHORTBOW"))));
        assertFalse(BowDrawIndicatorFeature.sameDrawBow(original, updated));
        assertFalse(BowDrawIndicatorFeature.sameDrawBow(original, ItemStack.EMPTY));
        assertFalse(BowDrawIndicatorFeature.sameDrawBow(original,
            new ItemStack(Holder.direct(Items.CROSSBOW, DataComponentMap.EMPTY))));
    }

    @Test
    public void normalBowsChargeButShortbowsCrossbowsAndOtherItemsDoNot() {
        ItemStack bow = new ItemStack(Holder.direct(Items.BOW, DataComponentMap.EMPTY));
        assertTrue(BowDrawIndicatorFeature.chargeableBow(bow));
        bow.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§6LEGENDARY BOW"))));
        assertTrue(BowDrawIndicatorFeature.chargeableBow(bow));
        for (String line : List.of("§5EPIC SHORTBOW", "§6Shortbow: Instantly shoots!", "§aShortbow")) {
            bow.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(line))));
            assertFalse(BowDrawIndicatorFeature.chargeableBow(bow));
        }
        assertFalse(BowDrawIndicatorFeature.chargeableBow(new ItemStack(Holder.direct(Items.CROSSBOW, DataComponentMap.EMPTY))));
        assertFalse(BowDrawIndicatorFeature.chargeableBow(new ItemStack(Holder.direct(Items.APPLE, DataComponentMap.EMPTY))));
        assertFalse(BowDrawIndicatorFeature.chargeableBow(ItemStack.EMPTY));
    }
}
