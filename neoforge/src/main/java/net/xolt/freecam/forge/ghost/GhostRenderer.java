package net.xolt.freecam.forge.ghost;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Map;

/**
 * Draws the saved blueprint while freecam is off: translucent blocks for placements, red boxes
 * for blocks to dig. While freecam is on (ghosts are live in the world) it only draws subtle
 * outlines so ghosts can be told apart from real blocks, plus the long-range crosshair target.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GhostRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final double MAX_DIST = 160;
    private static final float GHOST_ALPHA = 0.5F;
    private static final Direction[] DIRS = Direction.values();
    private static final RandomSource RANDOM = RandomSource.create();

    private GhostRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        ClientLevel level = MC.level;
        if (level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        Frustum frustum = event.getFrustum();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = MC.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        Blueprints.Blueprint editing = GhostBlocks.sessionBlueprint();
        if (GhostBlocks.visible) {
            if (editing != null) {
                renderSessionOutlines(level, pose, buffers, cam, frustum);
            }
            // Every other visible blueprint is drawn as an overlay (the one being edited is live in the world).
            for (Blueprints.Blueprint bp : Blueprints.all()) {
                if (bp != editing && bp.visible() && !bp.blocks.isEmpty()) {
                    renderBlueprint(level, pose, buffers, cam, frustum, bp.blocks);
                }
            }
            net.xolt.freecam.forge.ghost.builder.AutoBuilder.renderDebug(pose, buffers);
        }
        if (GhostBlocks.isEditing()) {
            renderCrosshairTarget(level, pose, buffers, event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        pose.popPose();
        buffers.endBatch(Sheets.translucentCullBlockSheet());
        buffers.endBatch(RenderType.lines());
    }

    private static boolean inView(BlockPos pos, Vec3 cam, Frustum frustum) {
        return pos.distToCenterSqr(cam) < MAX_DIST * MAX_DIST && frustum.isVisible(new AABB(pos));
    }

    private static void renderBlueprint(ClientLevel level, PoseStack pose, MultiBufferSource buffers, Vec3 cam, Frustum frustum,
                                        Map<BlockPos, BlockState> blueprint) {
        VertexConsumer blocks = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        BlockColors colors = MC.getBlockColors();

        for (Map.Entry<BlockPos, BlockState> e : blueprint.entrySet()) {
            BlockPos pos = e.getKey();
            BlockState target = e.getValue();
            if (!inView(pos, cam, frustum)) {
                continue;
            }
            BlockState real = level.getBlockState(pos);

            if (target.isAir()) {
                if (!real.isAir()) {
                    box(pose, buffers, pos, 0.002, 1.0F, 0.15F, 0.15F, 1.0F); // dig here
                }
                continue;
            }

            if (target.getRenderShape() == RenderShape.MODEL) {
                pose.pushPose();
                pose.translate(pos.getX(), pos.getY(), pos.getZ());
                renderModel(level, pose.last(), blocks, colors, blueprint, pos, target);
                pose.popPose();
            } else {
                // Chests, signs etc. have no static model - show their outline instead.
                shapeOutline(pose, buffers, pos, target, 0.55F, 0.85F, 1.0F, 1.0F);
            }

            if (!real.isAir() && !real.canBeReplaced()) {
                box(pose, buffers, pos, 0.004, 1.0F, 0.15F, 0.15F, 1.0F); // something must be dug first
            }
        }
    }

    private static void renderModel(ClientLevel level, PoseStack.Pose pose, VertexConsumer out, BlockColors colors,
                                    Map<BlockPos, BlockState> blueprint, BlockPos pos, BlockState state) {
        BakedModel model = MC.getBlockRenderer().getBlockModel(state);
        long seed = state.getSeed(pos);
        for (Direction dir : DIRS) {
            BlockPos np = pos.relative(dir);
            BlockState ghostNeighbour = blueprint.get(np);
            if (ghostNeighbour != null && !ghostNeighbour.isAir() && ghostNeighbour.isSolidRender(level, np)) {
                continue; // hidden by an adjacent opaque ghost
            }
            if (ghostNeighbour == null && !Block.shouldRenderFace(state, level, pos, dir, np)) {
                continue; // hidden by a real block
            }
            RANDOM.setSeed(seed);
            emit(level, pose, out, colors, pos, state, model.getQuads(state, dir, RANDOM));
        }
        RANDOM.setSeed(seed);
        emit(level, pose, out, colors, pos, state, model.getQuads(state, null, RANDOM));
    }

    private static void emit(ClientLevel level, PoseStack.Pose pose, VertexConsumer out, BlockColors colors,
                             BlockPos pos, BlockState state, java.util.List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            float r = 1, g = 1, b = 1;
            if (quad.isTinted()) {
                int c = colors.getColor(state, level, pos, quad.getTintIndex());
                r = (c >> 16 & 255) / 255.0F;
                g = (c >> 8 & 255) / 255.0F;
                b = (c & 255) / 255.0F;
            }
            float shade = quad.isShade() ? level.getShade(quad.getDirection(), true) : 1.0F;
            out.putBulkData(pose, quad, r * shade, g * shade, b * shade, GHOST_ALPHA,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    private static void renderSessionOutlines(ClientLevel level, PoseStack pose, MultiBufferSource buffers, Vec3 cam, Frustum frustum) {
        for (Map.Entry<BlockPos, BlockState> e : GhostBlocks.session.entrySet()) {
            BlockPos pos = e.getKey();
            if (e.getValue().isAir() || !inView(pos, cam, frustum) || pos.distToCenterSqr(cam) > 64 * 64) {
                continue;
            }
            shapeOutline(pose, buffers, pos, e.getValue(), 0.55F, 0.85F, 1.0F, 0.45F);
        }
    }

    private static void renderCrosshairTarget(ClientLevel level, PoseStack pose, MultiBufferSource buffers, float partialTick) {
        BlockHitResult hit = GhostBlocks.crosshairTarget(partialTick);
        if (hit == null) {
            return;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        shapeOutline(pose, buffers, hit.getBlockPos(), state, 1.0F, 1.0F, 1.0F, 0.8F);
    }

    private static void shapeOutline(PoseStack pose, MultiBufferSource buffers, BlockPos pos, BlockState state,
                                     float r, float g, float b, float a) {
        VoxelShape shape = state.getShape(MC.level, pos);
        if (shape.isEmpty()) {
            box(pose, buffers, pos, 0.002, r, g, b, a);
            return;
        }
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
                shape.bounds().move(pos).inflate(0.002), r, g, b, a);
    }

    public static void box(PoseStack pose, MultiBufferSource buffers, BlockPos pos, double inflate,
                            float r, float g, float b, float a) {
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), new AABB(pos).inflate(inflate), r, g, b, a);
    }
}
