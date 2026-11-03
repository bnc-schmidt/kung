package com.github.beng420.kung.mixin;

import com.github.beng420.kung.config.category.DungeonConfig.PillarMaterial;
import com.github.beng420.kung.feature.dungeon.ColoredPillarsFeature;
import com.github.beng420.kung.feature.garden.PlotCleaningHelperFeature;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin {
    @Shadow @Final private ClientLevel level;
    @Unique private PillarMaterial kung$pillarMaterial;
    @Unique private PlotCleaningHelperFeature.Plot kung$cleaningPlot;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kung$capturePillarMaterial(CallbackInfo ci) {
        // Keep each worker's render snapshot consistent across a settings/context change.
        kung$pillarMaterial = ColoredPillarsFeature.materialFor(level);
        kung$cleaningPlot = PlotCleaningHelperFeature.plotFor(level);
    }

    @ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
    private BlockState kung$sponge(BlockState original, BlockPos pos) {
        return PlotCleaningHelperFeature.replace(pos.getX(), pos.getZ(), original, kung$cleaningPlot);
    }

    @ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
    private BlockState kung$colorPillar(BlockState original, BlockPos pos) {
        return ColoredPillarsFeature.replace(pos.getX(), pos.getY(), pos.getZ(), original, kung$pillarMaterial);
    }
}
