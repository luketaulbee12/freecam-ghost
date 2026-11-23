package net.xolt.freecam.forge.ghost.design;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A building "plan": what kind of building and which materials. This is the part the picture-reading
 * model will fill in later (step 2); for now there are hand-written presets.
 */
record Style(
        String id,
        String displayName,
        int floors,
        int floorHeight,      // blocks per storey including the beam/ceiling course
        Roof roof,
        boolean framed,       // timber frame: corner/mid posts + beam course in the frame material
        int windowSpacing,    // a window every N blocks along unframed walls
        Foundation foundation,
        boolean porch,
        boolean chimney,
        int maxWidth,         // 0 = fill the site
        int maxDepth,
        Mat foundationMat,
        Mat wallMat,
        Mat frameMat,
        Mat floorMat,
        Block roofStairs,
        Block roofSlab,
        Block roofFull,
        Block window,
        Block door,
        Block trim,           // porch railing / fence
        Mat chimneyMat
) {
    enum Roof { GABLE, HIP, FLAT }

    enum Foundation { AUTO, SOLID, STILTS }

    /** A material, optionally a weighted mix (e.g. 70% stone bricks, 30% mossy) picked per position. */
    record Mat(List<Block> blocks, List<Integer> weights) {
        static Mat of(Block b) {
            return new Mat(List.of(b), List.of(1));
        }

        static Mat mix(Object... blockWeightPairs) {
            List<Block> bs = new java.util.ArrayList<>();
            List<Integer> ws = new java.util.ArrayList<>();
            for (int i = 0; i < blockWeightPairs.length; i += 2) {
                bs.add((Block) blockWeightPairs[i]);
                ws.add((Integer) blockWeightPairs[i + 1]);
            }
            return new Mat(bs, ws);
        }

        Block pick(BlockPos pos) {
            if (blocks.size() == 1) {
                return blocks.get(0);
            }
            int total = 0;
            for (int w : weights) {
                total += w;
            }
            long h = pos.asLong() * 0x9E3779B97F4A7C15L;
            int r = (int) Math.floorMod(h ^ (h >>> 29), (long) total);
            for (int i = 0; i < blocks.size(); i++) {
                r -= weights.get(i);
                if (r < 0) {
                    return blocks.get(i);
                }
            }
            return blocks.get(0);
        }
    }

    static final Map<String, Style> PRESETS = new LinkedHashMap<>();

    static {
        add(new Style("timber_house", "Timber House", 2, 4, Roof.GABLE, true, 3, Foundation.AUTO, false, true, 0, 0,
                Mat.mix(Blocks.COBBLESTONE, 3, Blocks.MOSSY_COBBLESTONE, 1),
                Mat.of(Blocks.CALCITE),
                Mat.of(Blocks.DARK_OAK_LOG),
                Mat.of(Blocks.SPRUCE_PLANKS),
                Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_PLANKS,
                Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
                Mat.mix(Blocks.COBBLESTONE, 2, Blocks.STONE_BRICKS, 1)));

        add(new Style("stone_tower", "Stone Tower", 3, 5, Roof.HIP, false, 3, Foundation.SOLID, false, false, 7, 7,
                Mat.of(Blocks.COBBLED_DEEPSLATE),
                Mat.mix(Blocks.STONE_BRICKS, 7, Blocks.MOSSY_STONE_BRICKS, 2, Blocks.CRACKED_STONE_BRICKS, 1),
                Mat.of(Blocks.POLISHED_ANDESITE),
                Mat.of(Blocks.SPRUCE_PLANKS),
                Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES,
                Blocks.GLASS_PANE, Blocks.DARK_OAK_DOOR, Blocks.STONE_BRICK_WALL,
                Mat.of(Blocks.STONE_BRICKS)));

        add(new Style("stilt_cabin", "Stilt Cabin", 1, 4, Roof.GABLE, true, 3, Foundation.STILTS, true, false, 9, 7,
                Mat.of(Blocks.SPRUCE_LOG),
                Mat.of(Blocks.SPRUCE_PLANKS),
                Mat.of(Blocks.STRIPPED_SPRUCE_LOG),
                Mat.of(Blocks.OAK_PLANKS),
                Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_PLANKS,
                Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
                Mat.of(Blocks.COBBLESTONE)));
    }

    private static void add(Style s) {
        PRESETS.put(s.id(), s);
    }
}
