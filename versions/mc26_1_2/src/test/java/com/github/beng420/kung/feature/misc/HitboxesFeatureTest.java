package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import com.github.beng420.kung.MinecraftBootstrapTest;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

public final class HitboxesFeatureTest extends MinecraftBootstrapTest {
    @Test
    public void nametagsReduceToTheirMobNameAndMatchWholeWords() {
        assertEquals("Zealot", HitboxesFeature.mobName("[Lv55] Zealot 13,000/13,000❤"));
        assertEquals("Voidgloom Seraph", HitboxesFeature.mobName("☠ Voidgloom Seraph 30M❤"));
        assertEquals("Skeleton Master", HitboxesFeature.mobName("✯ Skeleton Master 1.2M❤"));
        assertEquals("skyblock:voidgloom_seraph", HitboxesFeature.skyBlockId("☠ Voidgloom Seraph 30M❤"));
        assertNull(HitboxesFeature.skyBlockId("13,000/13,000❤"));
        // Modifiers drop from added names, so one entry covers every variant.
        assertEquals("skyblock:skeleton_master", HitboxesFeature.skyBlockId("✯ Speedy Skeleton Master 1.2M❤"));
        assertEquals("skyblock:zombie_knight", HitboxesFeature.skyBlockId("✯ Healthy Fortified Zombie Knight 900k❤"));
        assertEquals("skyblock:golden_ghoul", HitboxesFeature.skyBlockId("[Lv60] Golden Ghoul 45,000❤"));
        assertEquals("skyblock:healthy", HitboxesFeature.skyBlockId("Healthy"));

        Set<String> selected = Set.of("skyblock:zealot", "minecraft:zombie");
        assertEquals("skyblock:zealot", HitboxesFeature.skyBlockEntry(selected, "[Lv55] Zealot 13,000/13,000❤"));
        assertEquals("skyblock:zealot", HitboxesFeature.skyBlockEntry(selected, "[Lv55] Special Zealot 2,000❤"));
        // Dungeon prefixes: one entry covers every variant of the mob.
        Set<String> master = Set.of("skyblock:skeleton_master");
        assertEquals("skyblock:skeleton_master", HitboxesFeature.skyBlockEntry(master, "✯ Healthy Skeleton Master 1.2M❤"));
        assertEquals("skyblock:skeleton_master", HitboxesFeature.skyBlockEntry(master, "[Lv90] Skeleton Master 20,000❤"));
        assertNull(HitboxesFeature.skyBlockEntry(master, "✯ Skeleton Soldier 800k❤"));
        // Whole words only: a vanilla id or a longer word never matches by accident.
        assertNull(HitboxesFeature.skyBlockEntry(selected, "[Lv3] Zombie 100/100❤"));
        assertNull(HitboxesFeature.skyBlockEntry(Set.of("skyblock:ender"), "[Lv42] Enderman 4,500❤"));
    }

    @Test
    public void boxSizeScalesTheDrawnBoxAroundItsCenter() {
        AABB box = new AABB(-1, 0, -2, 1, 2, 2);
        assertSame(box, HitboxesFeature.scaled(box, 100));
        assertEquals(new AABB(-0.5, 0.5, -1, 0.5, 1.5, 1), HitboxesFeature.scaled(box, 50));
        assertEquals(new AABB(-2, -1, -4, 2, 3, 4), HitboxesFeature.scaled(box, 200));
    }

    @Test
    public void onlyAStandShowingANameItNeverGotDrawsTheTypeName() {
        var stand = new ArmorStand(EntityType.ARMOR_STAND, null);
        assertFalse(HitboxesFeature.drawsTypeName(stand));
        stand.setCustomNameVisible(true);
        assertTrue(HitboxesFeature.drawsTypeName(stand));
        stand.setCustomName(Component.literal("✧281,574✧"));
        assertFalse(HitboxesFeature.drawsTypeName(stand));
    }

    @Test
    public void aNameLabelsTheMobBelowItNeverTheLocalPlayerBesideIt() {
        var stand = new ArmorStand(EntityType.ARMOR_STAND, null);
        stand.setPos(0, 2.3, 0);
        var mob = new Zombie(EntityType.ZOMBIE, null);
        var self = new Zombie(EntityType.ZOMBIE, null);
        assertTrue(HitboxesFeature.mobCandidate(mob, stand, self));
        assertFalse(HitboxesFeature.mobCandidate(self, stand, self));
        assertFalse(HitboxesFeature.mobCandidate(new ArmorStand(EntityType.ARMOR_STAND, null), stand, self));
        mob.setPos(0, 3, 0);
        assertFalse(HitboxesFeature.mobCandidate(mob, stand, self));
    }
}
