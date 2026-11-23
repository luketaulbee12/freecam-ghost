package net.xolt.freecam.forge.ghost.builder;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.xolt.freecam.forge.mixins.BlockItemAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out how to click so vanilla placement produces the planned block state.
 * <p>
 * Instead of hard-coding rules per block type, it tries every combination of
 * (support face, click height, facing yaw, pitch) and runs the block's real placement logic
 * with the player temporarily rotated that way, keeping whichever matches best.
 */
final class PlacementPlanner {
    private static final float[] YAWS = {0F, 90F, 180F, 270F};
    private static final double[] SIDE_HEIGHTS = {0.25, 0.75};

    record Plan(BlockHitResult hit, float yaw, float pitch, int mismatches) {}

    private PlacementPlanner() {}

    /** True if something we can click against is next to {@code pos}. */
    static boolean hasSupport(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isSupport(level.getBlockState(pos.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    static boolean isSupport(BlockState s) {
        return !s.isAir() && !s.canBeReplaced() && !(s.getBlock() instanceof LiquidBlock);
    }

    @Nullable
    static Plan plan(Level level, LocalPlayer player, BlockPos target, BlockState desired, ItemStack stack, double reach) {
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        float oldYaw = player.getYRot(), oldPitch = player.getXRot();
        // LocalPlayer.isShiftKeyDown() reads the movement input, so that's what we flip.
        boolean oldShift = player.input.shiftKeyDown;
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        try {
            player.input.shiftKeyDown = true; // we sneak while placing so clicked blocks don't open
            for (Direction toSupport : Direction.values()) {
                BlockPos support = target.relative(toSupport);
                if (!isSupport(level.getBlockState(support))) {
                    continue;
                }
                Direction face = toSupport.getOpposite(); // face of the support that touches the target
                double[] heights = face.getAxis().isHorizontal() ? SIDE_HEIGHTS : new double[]{0.5};
                for (double h : heights) {
                    Vec3 hitPos = hitPoint(target, toSupport, h);
                    double dist = eye.distanceTo(hitPos);
                    if (dist > reach) {
                        continue;
                    }
                    BlockHitResult hit = new BlockHitResult(hitPos, face, support, false);
                    boolean visible = eye.subtract(hitPos).dot(Vec3.atLowerCornerOf(face.getNormal())) > 0;
                    // Look straight at the click point; only bend the view as far as orientation needs.
                    float lookYaw = yawTo(eye, hitPos), lookPitch = pitchTo(eye, hitPos);
                    for (float[] rot : candidateRotations(lookYaw, lookPitch)) {
                        player.setYRot(rot[0]);
                        player.setXRot(rot[1]);
                        BlockState result = simulate(level, player, item, stack, hit);
                        if (result == null) {
                            continue;
                        }
                        int miss = mismatches(result, desired);
                        if (miss < 0) {
                            continue; // wrong block entirely (e.g. standing vs wall torch)
                        }
                        double deviation = Math.abs(net.minecraft.util.Mth.wrapDegrees(rot[0] - lookYaw)) + Math.abs(rot[1] - lookPitch);
                        double score = miss * 1000 + (visible ? 0 : 20) + deviation * 0.2 + dist;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new Plan(hit, rot[0], rot[1], miss);
                        }
                    }
                }
            }
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            player.input.shiftKeyDown = oldShift;
        }
        return best;
    }

    /**
     * The exact look-at rotation first, then for each compass quadrant the closest yaw still inside
     * it (so horizontal facing can be chosen with minimal head turn), each with the real pitch and with
     * steep up/down pitches (for blocks that face where you look, like observers and pistons).
     */
    private static List<float[]> candidateRotations(float lookYaw, float lookPitch) {
        List<float[]> out = new ArrayList<>();
        out.add(new float[]{lookYaw, lookPitch});
        for (float c : YAWS) {
            float diff = net.minecraft.util.Mth.wrapDegrees(lookYaw - c);
            float yaw = c + net.minecraft.util.Mth.clamp(diff, -44.0F, 44.0F);
            for (float pitch : new float[]{lookPitch, Math.max(lookPitch, 60.0F), Math.min(lookPitch, -60.0F)}) {
                out.add(new float[]{yaw, pitch});
            }
        }
        return out;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (net.minecraft.util.Mth.atan2(to.z - from.z, to.x - from.x) * net.minecraft.util.Mth.RAD_TO_DEG) - 90.0F;
    }

    private static float pitchTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        return (float) -(net.minecraft.util.Mth.atan2(to.y - from.y, Math.sqrt(dx * dx + dz * dz)) * net.minecraft.util.Mth.RAD_TO_DEG);
    }

    private static Vec3 hitPoint(BlockPos target, Direction toSupport, double height) {
        double x = target.getX() + 0.5 + toSupport.getStepX() * 0.5;
        double z = target.getZ() + 0.5 + toSupport.getStepZ() * 0.5;
        double y;
        if (toSupport == Direction.DOWN) {
            y = target.getY();
        } else if (toSupport == Direction.UP) {
            y = target.getY() + 1.0;
        } else {
            y = target.getY() + height;
        }
        return new Vec3(x, y, z);
    }

    @Nullable
    private static BlockState simulate(Level level, LocalPlayer player, BlockItem item, ItemStack stack, BlockHitResult hit) {
        try {
            BlockPlaceContext ctx = item.updatePlacementContext(new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit));
            if (ctx == null || !ctx.canPlace()) {
                return null;
            }
            return ((BlockItemAccessor) item).freecam$getPlacementState(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    /** -1 if a different block; otherwise how many properties differ. */
    private static int mismatches(BlockState a, BlockState b) {
        if (a.getBlock() != b.getBlock()) {
            return -1;
        }
        int n = 0;
        for (Property<?> p : a.getProperties()) {
            if (!a.getValue(p).equals(b.getValue(p))) {
                n++;
            }
        }
        return n;
    }
}
