package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.feature.garden.GreenhouseCropLook.BlockSpec;
import com.github.beng420.kung.feature.garden.GreenhouseCropLook.Look;
import com.github.beng420.kung.feature.garden.GreenhouseCropLook.Rung;
import com.github.beng420.kung.feature.garden.GreenhouseHologramShapes.Placement;
import com.github.beng420.kung.feature.garden.GreenhouseScanLogic.State;
import com.github.beng420.kung.feature.garden.GreenhouseSoilCheck.Ghost;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * The semi-transparent models of the hologram: crop and mutation heads (the vanilla player-head model with the
 * crop's skin) and vanilla crop blocks (the block's own baked model), both translucent. {@link #prepare} resolves
 * the placements to game objects once per hologram/corner change; {@link #submit} only walks the result each
 * frame. Each cell tries head, then block, then the coloured cube; a rung that throws is switched off for the
 * session (logged once) and the cells are re-prepared without it.
 *
 * <p>Everything here runs on the client thread. Unverified in game: how the real Hypixel armor-stand heads are
 * sized and offset ({@link GreenhouseHologramShapes#CROP_HEAD_SCALE}); the dump command records the real ones.
 */
final class GreenhouseModels {
    /** A head to draw: {@code x}/{@code y}/{@code z} is the world position of the centre of its base. */
    record HeadDraw(double x, double y, double z, float scale, String hash, ResolvableProfile profile) { }

    /** A block model to draw: {@code x}/{@code y}/{@code z} is the world corner of the block's cell. */
    record BlockDraw(BlockPos pos, BlockState state, double x, double y, double z) { }

    /** A soil block the real floor lacks: {@code x}/{@code y}/{@code z} is the world corner of its cell. */
    record GhostDraw(BlockPos pos, BlockState state, double x, double y, double z) { }

    /**
     * What to submit. {@code tint} multiplies every head and block (blue-shifted, at the opacity of the settings),
     * {@code ghostTint} the soil blocks that are missing.
     */
    record Models(List<HeadDraw> heads, List<BlockDraw> blocks, List<GhostDraw> ghosts, int tint, int ghostTint) {
        static final Models EMPTY = new Models(List.of(), List.of(), List.of(), 0, 0);

        boolean isEmpty() { return heads.isEmpty() && blocks.isEmpty() && ghosts.isEmpty(); }
    }

    /** {@code cubes}: the coloured stand-ins for cells no model could be found for. */
    record Prepared(Models models, List<GreenhouseWorldRenderer.Box> cubes) { }

    /** Soil ghosts are a hair larger than the block they cover. */
    private static final float GHOST_SCALE = 1.004F;
    private static final long SKIN_REFRESH_NANOS = 500_000_000L;
    private static final long SEED = 42L;

    private static final SkullModelBase.State HEAD_STATE = new SkullModelBase.State();
    private static final Map<String, Skin> SKINS = new HashMap<>();

    private static boolean headBroken;
    private static boolean blockBroken;
    private static boolean ghostBroken;
    private static int epoch;
    private static SkullModelBase skull;
    private static ModelBlockRenderer blockRenderer;

    private GreenhouseModels() { }

    /** Changes whenever a rung is switched off, so prepared data built with it is rebuilt. */
    static int epoch() { return epoch; }

    /** The render type of a head: translucent, with the real skin once it has downloaded, else the default one. */
    private static final class Skin {
        RenderType type;
        long refreshAt;
    }

    /**
     * Resolves what to draw once. {@code states} is per placement (null or short: still to place): only a spot still
     * to place gets its model (head, block or cube); a correct, wrong or unrecognised one gets none (the boxes
     * {@link GreenhouseOverlay} builds say what is there). Of the soil ghosts only the missing blocks are drawn as
     * blocks, at {@code soilY}. Client thread.
     */
    static Prepared prepare(List<Placement> placements, List<State> states, List<Ghost> soilGhosts, int soilY,
                            BlockPos corner, GreenhousePalette palette) {
        List<HeadDraw> heads = new ArrayList<>();
        List<BlockDraw> blocks = new ArrayList<>();
        List<GhostDraw> ghosts = new ArrayList<>();
        List<GreenhouseWorldRenderer.Box> cubes = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            State planted = states == null || index >= states.size() ? State.EMPTY : states.get(index);
            if (planted == State.EMPTY) addModel(placement, corner, palette, heads, blocks, cubes);
        }
        if (soilY >= 0 && !ghostBroken) {
            for (Ghost ghost : soilGhosts) {
                if (ghost.kind() != GreenhouseSoilCheck.GhostKind.MISSING) continue;
                BlockState state = state(new BlockSpec(ghost.soil().blockId(), Map.of()));
                if (state == null) continue;
                int x = corner.getX() + ghost.dx();
                int z = corner.getZ() + ghost.dz();
                ghosts.add(new GhostDraw(new BlockPos(x, soilY, z), state, x, soilY, z));
            }
        }
        return new Prepared(new Models(List.copyOf(heads), List.copyOf(blocks), List.copyOf(ghosts),
            palette.modelTint(), palette.modelTint()), List.copyOf(cubes));
    }

    /** The first rung of the placement's ladder that works; the cube is the last. */
    private static void addModel(Placement placement, BlockPos corner, GreenhousePalette palette, List<HeadDraw> heads,
                                 List<BlockDraw> blocks, List<GreenhouseWorldRenderer.Box> cubes) {
        double worldX = corner.getX() + placement.x();
        double worldY = GreenhouseLocator.HOLOGRAM_Y + placement.y();
        double worldZ = corner.getZ() + placement.z();
        for (Rung rung : placement.look().ladder()) {
            if (rung == Rung.HEAD && !headBroken) {
                ResolvableProfile profile = profile(placement.crop());
                if (profile == null) continue;
                heads.add(new HeadDraw(worldX, worldY, worldZ, (float) placement.scale(), placement.look().head(),
                    profile));
                return;
            }
            if (rung == Rung.BLOCK && !blockBroken) {
                BlockState state = state(placement.look());
                if (state == null) continue;
                double originX = worldX - 0.5;
                double originZ = worldZ - 0.5;
                blocks.add(new BlockDraw(BlockPos.containing(originX, worldY, originZ), state, originX, worldY, originZ));
                return;
            }
            if (rung == Rung.CUBE) {
                var cube = GreenhouseOverlay.cube(placement, palette);
                if (cube != null) cubes.add(GreenhouseWorldRenderer.worldBox(cube, corner));
                return;
            }
        }
    }

    /** The profile the planner draws this crop's head with, or null when it has no head. */
    private static ResolvableProfile profile(String crop) {
        ItemStack stack = GreenhouseIcons.stack(crop);
        return stack.isEmpty() ? null : stack.get(DataComponents.PROFILE);
    }

    /** The grown state of the crop's block, else the icon table's stand-in item read as a block, else null. */
    private static BlockState state(Look look) {
        BlockState state = look.block() == null ? null : state(look.block());
        return state != null || look.standIn() == null ? state : state(look.standIn());
    }

    private static BlockState state(BlockSpec spec) {
        Identifier id = Identifier.tryParse(spec.id());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : spec.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property != null) state = withValue(state, property, entry.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    /** Called from COLLECT_SUBMITS with this frame's prepared models; the camera is the extraction camera. */
    static void submit(LevelRenderContext context, Models models, Vec3 camera) {
        if (!headBroken && !models.heads().isEmpty()) submitHeads(context, models.heads(), camera, models.tint());
        if (!models.blocks().isEmpty() || !models.ghosts().isEmpty()) submitBlocks(context, models, camera);
    }

    private static void submitHeads(LevelRenderContext context, List<HeadDraw> heads, Vec3 camera, int tint) {
        try {
            SkullModelBase model = skullModel();
            SubmitNodeCollector collector = context.submitNodeCollector();
            PoseStack pose = context.poseStack();
            // Vanilla's floor-skull transformation: moves the head's origin to the block centre, flips it upright.
            Transformation floorHead = SkullBlockRenderer.TRANSFORMATIONS.freeTransformations(0);
            long now = System.nanoTime();
            for (HeadDraw head : heads) {
                float scale = head.scale();
                pose.pushPose();
                try {
                    // The floor transformation centres the head 0.5 blocks in; scaling moves that, so shift back.
                    pose.translate(head.x() - 0.5 * scale - camera.x, head.y() - camera.y,
                        head.z() - 0.5 * scale - camera.z);
                    pose.scale(scale, scale, scale);
                    pose.mulPose(floorHead);
                    collector.submitModel(model, HEAD_STATE, pose, skinType(head, now), LightCoordsUtil.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, tint, null, 0, null);
                } finally {
                    pose.popPose();
                }
            }
        } catch (RuntimeException failure) {
            disable("head", failure);
            headBroken = true;
        }
    }

    private static void submitBlocks(LevelRenderContext context, Models models, Vec3 camera) {
        try {
            // A fresh identity pose is all the geometry needs: block positions are written camera-relative.
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.translucentMovingBlock(),
                (pose, buffer) -> drawBlocks(buffer, models, camera));
        } catch (RuntimeException failure) {
            disable("block", failure);
            blockBroken = true;
        }
    }

    /** Runs when the frame's submits flush, which on 26.2 is after the callback returned: it guards itself. */
    private static void drawBlocks(VertexConsumer buffer, Models models, Vec3 camera) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        BlockStateModelSet modelSet;
        try {
            // Setup runs here, at flush time, where nothing else catches it: a failure switches the rungs off.
            if (blockRenderer == null) blockRenderer = new ModelBlockRenderer(false, true, client.getBlockColors());
            modelSet = client.getModelManager().getBlockStateModelSet();
        } catch (RuntimeException failure) {
            disable("block", failure);
            blockBroken = true;
            disable("ghost", failure);
            ghostBroken = true;
            return;
        }
        if (!blockBroken) {
            try {
                // putBlockBakedQuad adds the block's offset to the quad; putBakedQuad would drop it.
                BlockQuadOutput output = (x, y, z, quad, instance) -> {
                    instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
                    instance.multiplyColor(models.tint());
                    buffer.putBlockBakedQuad(x, y, z, quad, instance);
                };
                for (BlockDraw block : models.blocks()) {
                    blockRenderer.tesselateBlock(output, (float) (block.x() - camera.x), (float) (block.y() - camera.y),
                        (float) (block.z() - camera.z), client.level, block.pos(), block.state(),
                        modelSet.get(block.state()), SEED);
                }
            } catch (RuntimeException failure) {
                disable("block", failure);
                blockBroken = true;
            }
        }
        if (!ghostBroken && !models.ghosts().isEmpty()) {
            try {
                drawGhosts(buffer, models.ghosts(), camera, client, modelSet, models.ghostTint());
            } catch (RuntimeException failure) {
                disable("ghost", failure);
                ghostBroken = true;
            }
        }
    }

    /** Each soil block slightly enlarged around its centre, so it covers the real block's faces without flicker. */
    private static void drawGhosts(VertexConsumer buffer, List<GhostDraw> ghosts, Vec3 camera, Minecraft client,
                                   BlockStateModelSet modelSet, int tint) {
        PoseStack stack = new PoseStack();
        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
            instance.multiplyColor(tint);
            buffer.putBakedQuad(stack.last(), quad, instance);
        };
        for (GhostDraw ghost : ghosts) {
            stack.pushPose();
            try {
                stack.translate(ghost.x() - camera.x + 0.5, ghost.y() - camera.y + 0.5, ghost.z() - camera.z + 0.5);
                stack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
                stack.translate(-0.5, -0.5, -0.5);
                blockRenderer.tesselateBlock(output, 0F, 0F, 0F, client.level, ghost.pos(), ghost.state(),
                    modelSet.get(ghost.state()), SEED);
            } finally {
                stack.popPose();
            }
        }
    }

    private static SkullModelBase skullModel() {
        if (skull == null) {
            skull = SkullBlockRenderer.createModel(Minecraft.getInstance().getEntityModels(), SkullBlock.Types.PLAYER);
            if (skull == null) throw new IllegalStateException("No player skull model");
        }
        return skull;
    }

    /** Looked up at most twice a second per head: the skin downloads in the background and swaps in when ready. */
    private static RenderType skinType(HeadDraw head, long now) {
        Skin skin = SKINS.computeIfAbsent(head.hash(), hash -> new Skin());
        if (skin.type == null || now >= skin.refreshAt) {
            skin.type = Minecraft.getInstance().playerSkinRenderCache().getOrDefault(head.profile()).renderType();
            skin.refreshAt = now + SKIN_REFRESH_NANOS;
        }
        return skin.type;
    }

    /** Logs once and bumps the epoch so the renderer prepares the cells again without this rung. */
    private static void disable(String rung, RuntimeException failure) {
        boolean first = switch (rung) {
            case "head" -> !headBroken;
            case "block" -> !blockBroken;
            default -> !ghostBroken;
        };
        if (first) {
            epoch++;
            KungMod.LOGGER.warn("Greenhouse hologram: {} models disabled for this session", rung, failure);
            KungDebugRecorder.event("greenhouse", rung + " model rung disabled: " + failure);
        }
    }
}
