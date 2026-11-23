package net.xolt.freecam.forge.ghost.design;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * Terrain survey of the chosen rectangle: the top "real ground" block of every column (ignoring
 * trees, plants and snow layers), plus water surfaces.
 */
final class Site {
    static final int NO_WATER = Integer.MIN_VALUE;

    final int x0, z0, x1, z1; // inclusive world bounds
    private final int[] ground;
    private final int[] water;
    final int minGround, maxGround;

    private Site(int x0, int z0, int x1, int z1) {
        this.x0 = x0;
        this.z0 = z0;
        this.x1 = x1;
        this.z1 = z1;
        ground = new int[width() * depth()];
        water = new int[width() * depth()];
        Arrays.fill(water, NO_WATER);
        minGround = 0;
        maxGround = 0;
    }

    private Site(Site s, int min, int max) {
        x0 = s.x0;
        z0 = s.z0;
        x1 = s.x1;
        z1 = s.z1;
        ground = s.ground;
        water = s.water;
        minGround = min;
        maxGround = max;
    }

    int width() {
        return x1 - x0 + 1;
    }

    int depth() {
        return z1 - z0 + 1;
    }

    boolean contains(int x, int z) {
        return x >= x0 && x <= x1 && z >= z0 && z <= z1;
    }

    /** Y of the top ground block at (x, z). */
    int ground(int x, int z) {
        return ground[(x - x0) * depth() + (z - z0)];
    }

    /** Y of the top water block at (x, z), or {@link #NO_WATER}. */
    int water(int x, int z) {
        return water[(x - x0) * depth() + (z - z0)];
    }

    boolean isWater(int x, int z) {
        return water(x, z) != NO_WATER;
    }

    static Site scan(ClientLevel level, BlockPos a, BlockPos b) {
        Site s = new Site(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = s.x0; x <= s.x1; x++) {
            for (int z = s.z0; z <= s.z1; z++) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int top = y;
                while (y > level.getMinBuildHeight()) {
                    if (isGround(level.getBlockState(p.set(x, y, z)))) {
                        break;
                    }
                    y--;
                }
                int idx = (x - s.x0) * s.depth() + (z - s.z0);
                s.ground[idx] = y;
                for (int wy = top; wy > y; wy--) {
                    if (level.getFluidState(p.set(x, wy, z)).is(FluidTags.WATER)) {
                        s.water[idx] = wy;
                        break;
                    }
                }
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        return new Site(s, min, max);
    }

    /** Solid natural-ish ground: not trees, leaves, plants, snow layers or fluids. */
    static boolean isGround(BlockState s) {
        return !s.isAir() && s.blocksMotion() && !s.liquid()
                && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS) && !s.canBeReplaced();
    }

    /** Tree parts we clear off a site. */
    static boolean isTree(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(net.minecraft.world.level.block.Blocks.VINE)
                || s.is(BlockTags.SAPLINGS) || s.is(net.minecraft.world.level.block.Blocks.BEE_NEST);
    }
}
