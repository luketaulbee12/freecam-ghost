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
        Mat chimneyMat,
        Details details       // optional decorative pieces (all off by default)
) {
    enum Roof { GABLE, HIP, FLAT }

    enum Foundation { AUTO, SOLID, STILTS }

    /**
     * Decorative pieces that give a build its character. Every field is optional; null / 0 means off.
     *
     * @param eaveTrim      log/planks beam under the roof edge along the eaves
     * @param porchRoof     extend the roof over the front by one more block, held up by posts
     * @param roofStairs    weighted mix of stair blocks for the roof (overrides the single roof stair)
     * @param roofAccent    block sprinkled on top of the roof in patches (e.g. azalea leaves = moss)
     * @param roofAccentAmount 0..1 how much of the roof gets accents
     * @param ridgeCap      block along the ridge (slab or trapdoor)
     * @param windowWidth   1 or 2 blocks wide
     * @param shutters      trapdoor block for open shutters beside each window
     * @param planter       block under each window on the outside (leaves = flower box look)
     * @param doorFrame     log around the door
     * @param doorStep      block in front of the door (a slab is placed as a top slab, flush with the floor)
     * @param chimneys      -1 = use the style's chimney flag, else 0, 1 or 2 (one at each roof end)
     * @param chimneyCap    block on top of each chimney
     * @param vines         0..1 how much of the outer walls gets vines
     * @param bushes        leaves block for bushes along the base of the walls
     * @param bushAmount    0..1 how many bushes
     * @param lantern       lantern by the door (hangs from the eave if there is one, else on a post)
     * @param props         blocks set out beside the house (barrels, crates...)
     */
    record Details(
            Block eaveTrim, boolean porchRoof, Mat roofStairs, Block roofAccent, double roofAccentAmount,
            Block ridgeCap, int windowWidth, Block shutters, Block planter, Block doorFrame, Block doorStep,
            int chimneys, Block chimneyCap, double vines, Block bushes, double bushAmount, Block lantern,
            List<Block> props
    ) {
        static final Details NONE = new Details(null, false, null, null, 0, null, 1, null, null, null, null,
                -1, null, 0, null, 0, null, List.of());
    }

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
                Mat.mix(Blocks.COBBLESTONE, 2, Blocks.STONE_BRICKS, 1), Details.NONE));

        add(new Style("stone_tower", "Stone Tower", 3, 5, Roof.HIP, false, 3, Foundation.SOLID, false, false, 7, 7,
                Mat.of(Blocks.COBBLED_DEEPSLATE),
                Mat.mix(Blocks.STONE_BRICKS, 7, Blocks.MOSSY_STONE_BRICKS, 2, Blocks.CRACKED_STONE_BRICKS, 1),
                Mat.of(Blocks.POLISHED_ANDESITE),
                Mat.of(Blocks.SPRUCE_PLANKS),
                Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES,
                Blocks.GLASS_PANE, Blocks.DARK_OAK_DOOR, Blocks.STONE_BRICK_WALL,
                Mat.of(Blocks.STONE_BRICKS), Details.NONE));

        add(new Style("stilt_cabin", "Stilt Cabin", 1, 4, Roof.GABLE, true, 3, Foundation.STILTS, true, false, 9, 7,
                Mat.of(Blocks.SPRUCE_LOG),
                Mat.of(Blocks.SPRUCE_PLANKS),
                Mat.of(Blocks.STRIPPED_SPRUCE_LOG),
                Mat.of(Blocks.OAK_PLANKS),
                Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_PLANKS,
                Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
                Mat.of(Blocks.COBBLESTONE), Details.NONE));
    }

    private static void add(Style s) {
        PRESETS.put(s.id(), s);
    }
}
