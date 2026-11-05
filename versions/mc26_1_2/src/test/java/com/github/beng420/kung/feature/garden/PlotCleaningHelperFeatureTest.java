package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.config.KungSettings;
import com.github.beng420.kung.feature.garden.PlotCleaningHelperFeature.Plot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

public final class PlotCleaningHelperFeatureTest extends MinecraftBootstrapTest {
    private static BlockState sponge() { return Blocks.SPONGE.defaultBlockState(); }

    @Test public void leavesGrassAndAllFlowersCountButTheGrassBlockDoesNot() {
        for (Block block : new Block[] {Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.CHERRY_LEAVES,
                Blocks.AZALEA_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.SHORT_GRASS, Blocks.TALL_GRASS,
                Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY,
                Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY}) {
            assertTrue(block.getName().getString(), PlotCleaningHelperFeature.isClutter(block.defaultBlockState()));
        }
        for (Block block : new Block[] {Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.OAK_LOG,
                Blocks.WHEAT, Blocks.FARMLAND, Blocks.SPONGE, Blocks.WATER, Blocks.OAK_SAPLING}) {
            assertFalse(block.getName().getString(), PlotCleaningHelperFeature.isClutter(block.defaultBlockState()));
        }
    }

    @Test public void onlyTheOwnPlotBecomesSpongeAndOnlyItsClutter() {
        Plot plot = Plot.at(100, 60);
        assertEquals(new Plot(48, 48), plot);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        for (int[] inside : new int[][] {{48, 48}, {143, 143}, {100, 60}, {48, 143}}) {
            assertSame(sponge(), PlotCleaningHelperFeature.replace(inside[0], inside[1], leaves, plot));
        }
        for (int[] outside : new int[][] {{47, 60}, {144, 60}, {100, 47}, {100, 144}, {-100, -100}}) {
            assertSame(leaves, PlotCleaningHelperFeature.replace(outside[0], outside[1], leaves, plot));
        }
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        assertSame(dirt, PlotCleaningHelperFeature.replace(100, 60, dirt, plot));
        assertSame(leaves, PlotCleaningHelperFeature.replace(100, 60, leaves, null));
    }

    @Test public void aPlotIsThe96BlockSquareHoldingThePlayer() {
        assertEquals(new Plot(-240, -240), Plot.at(-240, -240));
        assertEquals(new Plot(-240, -240), Plot.at(-145, -145));
        assertEquals(new Plot(-144, -240), Plot.at(-144, -145));
        assertEquals(new Plot(144, 144), Plot.at(239, 239));
        assertEquals(new Plot(48, -144), Plot.at(48, -49));
    }

    @Test public void nothingOutsideTheFiveByFivePlotsAndNothingInTheBarn() {
        assertNull(Plot.at(-241, 0));
        assertNull(Plot.at(0, 240));
        assertNull(Plot.at(240, 240));
        // The middle plot is the Barn: -48..47 on both axes.
        for (int[] barn : new int[][] {{0, 0}, {-48, -48}, {47, 47}, {-48, 47}}) {
            assertNull(Plot.at(barn[0], barn[1]));
        }
        assertNotNull(Plot.at(48, 0));
        assertNotNull(Plot.at(0, -49));
    }

    @Test public void theSettingIsAGardenFeatureThatDefaultsOff() {
        var garden = KungSettings.defaults().stream().filter(category -> category.name().equals("Garden"))
            .findFirst().orElseThrow();
        var feature = garden.features().stream().filter(entry -> entry.name().equals("Plot Cleaning Helper"))
            .findFirst().orElseThrow();
        assertFalse(feature.enabled());
        assertTrue(feature.settings().isEmpty());
    }
}
