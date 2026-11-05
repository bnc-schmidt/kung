package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.Test;

public class VisitorCropTrackerTest extends MinecraftBootstrapTest {
    @Test
    public void flowerHalvesAndGardenPumpkinsAreHarvestable() {
        for (var block : List.of(Blocks.ROSE_BUSH, Blocks.SUNFLOWER)) {
            for (var half : DoubleBlockHalf.values()) {
                assertTrue(block + " " + half, GardenCropTracker.harvestable(
                    block.defaultBlockState().setValue(DoublePlantBlock.HALF, half)));
            }
        }
        assertTrue(GardenCropTracker.harvestable(Blocks.CARVED_PUMPKIN.defaultBlockState()));
        assertTrue(GardenCropTracker.harvestable(Blocks.PUMPKIN.defaultBlockState()));
    }

    @Test
    public void existingCropsKeepTheirMaturityChecks() {
        for (var block : List.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES)) {
            var crop = (CropBlock) block;
            assertFalse(GardenCropTracker.harvestable(crop.getStateForAge(0)));
            assertFalse(GardenCropTracker.harvestable(crop.getStateForAge(crop.getMaxAge() - 1)));
            assertTrue(GardenCropTracker.harvestable(crop.getStateForAge(crop.getMaxAge())));
        }
        for (int age = 0; age <= 3; age++) {
            assertEquals(age == 3, GardenCropTracker.harvestable(Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, age)));
        }
        for (int age = 0; age <= 2; age++) {
            assertEquals(age == 2, GardenCropTracker.harvestable(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, age)));
        }
        for (var block : List.of(Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.MELON, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM)) {
            assertTrue(block.toString(), GardenCropTracker.harvestable(block.defaultBlockState()));
        }
        for (var block : List.of(Blocks.AIR, Blocks.DIRT, Blocks.STONE, Blocks.OAK_LOG, Blocks.POPPY)) {
            assertFalse(block.toString(), GardenCropTracker.harvestable(block.defaultBlockState()));
        }
    }

    @Test
    public void repeatedMiningAndUnrelatedClicksDoNotCountTheSameCropAgain() {
        var crops = new GardenCropTracker();
        var pos = new BlockPos.MutableBlockPos(1, 70, 1);
        var block = Blocks.ROSE_BUSH.defaultBlockState();
        assertTrue(crops.observe(pos, block));
        assertFalse(crops.observe(pos, block));
        assertFalse(crops.observe(pos.above(), Blocks.AIR.defaultBlockState()));
        assertFalse(crops.observe(pos, block));
        pos.set(2, 70, 1);
        assertTrue("Mutable input positions must be copied", crops.observe(pos, block));
        assertFalse(crops.observe(pos, block));
        assertTrue("Returning after another harvested crop may count again", crops.observe(new BlockPos(1, 70, 1), block));
        crops.reset();
        assertTrue("A new world may reuse the same position", crops.observe(new BlockPos(1, 70, 1), block));
    }
}
