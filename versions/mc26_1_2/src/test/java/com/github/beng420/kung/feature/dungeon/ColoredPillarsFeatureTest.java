package com.github.beng420.kung.feature.dungeon;

import static org.junit.Assert.*;

import com.github.beng420.kung.KungBuild;
import com.github.beng420.kung.MinecraftBootstrapTest;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial;
import com.github.beng420.kung.skyblock.HypixelDungeonFloor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

public final class ColoredPillarsFeatureTest extends MinecraftBootstrapTest {
    @Test public void fourPillarsUseTheirOwnColorForEveryMaterial() {
        int[][] centers = {{46, 41}, {46, 65}, {100, 65}, {100, 41}};
        Block[][] expected = {
            {Blocks.LIME_WOOL, Blocks.LIME_STAINED_GLASS, Blocks.LIME_TERRACOTTA},
            {Blocks.YELLOW_WOOL, Blocks.YELLOW_STAINED_GLASS, Blocks.YELLOW_TERRACOTTA},
            {Blocks.PURPLE_WOOL, Blocks.PURPLE_STAINED_GLASS, Blocks.PURPLE_TERRACOTTA},
            {Blocks.RED_WOOL, Blocks.RED_STAINED_GLASS, Blocks.RED_TERRACOTTA}
        };
        for (int index = 0; index < centers.length; index++) {
            int x = centers[index][0], z = centers[index][1];
            for (PillarMaterial material : PillarMaterial.choices()) {
                for (Block original : new Block[] {Blocks.DIORITE, Blocks.POLISHED_DIORITE}) {
                    for (int dx : new int[] {-3, 0, 3}) {
                        for (int dz : new int[] {-3, 0, 3}) {
                            for (int y : new int[] {169, 187, 206}) {
                                assertSame(expected[index][material.ordinal()].defaultBlockState(),
                                    ColoredPillarsFeature.replace(x + dx, y, z + dz, original.defaultBlockState(), material));
                            }
                        }
                    }
                }
            }
            for (int[] outside : new int[][] {{x, 168, z}, {x, 207, z}, {x - 4, 180, z},
                    {x + 4, 180, z}, {x, 180, z - 4}, {x, 180, z + 4}}) {
                assertSame(Blocks.DIORITE.defaultBlockState(), ColoredPillarsFeature.replace(
                    outside[0], outside[1], outside[2], Blocks.DIORITE.defaultBlockState(), PillarMaterial.WOOL));
            }
        }
    }

    @Test public void crushAirAndUnrelatedBlocksAreNeverFilledOrReplaced() {
        for (Block block : new Block[] {Blocks.AIR, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.LEVER,
                Blocks.SEA_LANTERN, Blocks.DIORITE_SLAB, Blocks.LIME_WOOL, Blocks.WATER}) {
            for (PillarMaterial material : PillarMaterial.values()) {
                assertSame(block.defaultBlockState(), ColoredPillarsFeature.replace(46, 169, 41, block.defaultBlockState(), material));
            }
        }
        assertSame(Blocks.DIORITE.defaultBlockState(),
            ColoredPillarsFeature.replace(46, 169, 41, Blocks.DIORITE.defaultBlockState(), null));
        assertSame(Blocks.DIORITE.defaultBlockState(),
            ColoredPillarsFeature.replace(73, 180, 53, Blocks.DIORITE.defaultBlockState(), PillarMaterial.GLASS));
        // The Modrinth build never draws see-through pillars, even from a copied config.
        assertEquals(KungBuild.MODRINTH,
            ColoredPillarsFeature.replace(46, 169, 41, Blocks.DIORITE.defaultBlockState(), PillarMaterial.GLASS).isSolidRender());
    }

    @Test public void onlyConfirmedF7AndM7EnableRenderingIndependentlyOfMap() {
        var feature = new ColoredPillarsFeature();
        var config = new DungeonConfig();
        var floor = new HypixelDungeonFloor(7, false);
        assertNull(feature.selectedMaterial(config, true, floor, 35));
        config.setColoredPillarsEnabled(true);
        config.setPillarMaterial(PillarMaterial.TERRACOTTA);
        assertFalse(config.enabled());
        for (boolean master : new boolean[] {false, true}) {
            for (int number = -1; number <= 7; number++) {
                floor = new HypixelDungeonFloor(number, master);
                assertEquals(number == 7 ? PillarMaterial.TERRACOTTA : null,
                    feature.selectedMaterial(config, true, floor, 35));
                assertNull(feature.selectedMaterial(config, false, floor, 35));
            }
        }
        config.setColoredPillarsEnabled(false);
        assertNull(feature.selectedMaterial(config, true, floor, 35));
    }

    @Test public void september19MaxorMessageEnablesPillarsWithMissingFloorMetadata() {
        var feature = new ColoredPillarsFeature();
        var config = new DungeonConfig();
        config.setPillarMaterial(PillarMaterial.TERRACOTTA);
        assertFalse(config.enabled());
        // The 16:47:40 trace first identifies floor 7 at this original, later hidden message.
        feature.observeBossMessage("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", false, true, 35);
        assertNull(feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
        config.setColoredPillarsEnabled(true);
        assertEquals(PillarMaterial.TERRACOTTA,
            feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
        config.setPillarMaterial(PillarMaterial.WOOL);
        assertEquals(PillarMaterial.WOOL,
            feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
        for (int floor = 0; floor < 7; floor++) {
            assertNull(feature.selectedMaterial(config, true, new HypixelDungeonFloor(floor, false), 35));
        }
        assertNull(feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 36));
        assertNull(feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
    }

    @Test public void bossEvidenceRequiresOriginalDungeonGameMessagesAndExpiresOnExit() {
        var config = new DungeonConfig();
        config.setColoredPillarsEnabled(true);
        for (String boss : new String[] {"Maxor", "Storm", "Goldor", "Necron"}) {
            var feature = new ColoredPillarsFeature();
            String message = "§c[BOSS] " + boss + ": Test dialogue";
            feature.observeBossMessage(message, false, false, 35);
            feature.observeBossMessage(message, true, true, 35);
            feature.observeBossMessage("Party > Ben: " + message, false, true, 35);
            feature.observeBossMessage("[BOSS] Sadan: Test dialogue", false, true, 35);
            assertNull(feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
            feature.observeBossMessage(message, false, true, 35);
            assertEquals(PillarMaterial.WOOL,
                feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
            assertNull(feature.selectedMaterial(config, false, HypixelDungeonFloor.UNKNOWN, 35));
            assertNull(feature.selectedMaterial(config, true, HypixelDungeonFloor.UNKNOWN, 35));
        }
    }
}
