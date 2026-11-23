package net.xolt.freecam.forge.ghost.design;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.xolt.freecam.forge.ghost.GhostBlocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link Style} + a surveyed {@link Site} into a blueprint, fitted to the land:
 * picks the floor height, cuts into slopes / fills foundations (or raises it on stilts over
 * water and steep ground), clears trees, regrades a ring around it, adds steps and a path.
 */
final class Designer {
    record Result(Map<BlockPos, BlockState> blocks, String summary) {}

    private final ClientLevel level;
    private final Site site;
    private final Style st;
    private final Direction front; // the side the door faces
    private final Map<BlockPos, BlockState> out = new LinkedHashMap<>();
    private final Set<Long> reserved = new HashSet<>(); // columns used by porch/steps/path

    private int bx0, bz0, bx1, bz1; // building walls (inclusive)
    private int y0;                 // ground-floor walking level (floor block is at y0 - 1)
    private int roofBase;           // first layer above the top wall course
    private int roofTop;
    private boolean stilts;
    private BlockPos door;          // lower door block
    private int porchDepth;

    private Designer(ClientLevel level, Site site, Style st, Direction front) {
        this.level = level;
        this.site = site;
        this.st = st;
        this.front = front;
    }

    static Result design(ClientLevel level, Site site, Style style, Direction front) {
        Designer d = new Designer(level, site, style, front);
        return d.run();
    }

    private Result run() {
        footprint();
        chooseLevels();
        planFrontage();
        clearTrees();
        if (!stilts) {
            landscape();
        }
        foundation();
        floors();
        walls();
        roof();
        ladder();
        if (st.chimney() && !stilts && st.roof() == Style.Roof.GABLE) {
            chimney();
        }
        if (porchDepth > 0) {
            porch();
        }
        stepsAndPath();
        clearInterior();
        normalizeShapes();
        out.entrySet().removeIf(e -> level.getBlockState(e.getKey()) == e.getValue());

        int digs = 0;
        for (BlockState s : out.values()) {
            if (s.isAir()) {
                digs++;
            }
        }
        String summary = String.format("%s %dx%d, floor y=%d, %s, %d blocks to place, %d to dig",
                st.displayName(), bx1 - bx0 + 1, bz1 - bz0 + 1, y0,
                stilts ? "on stilts" : "solid foundation", out.size() - digs, digs);
        return new Result(out, summary);
    }

    // ------------------------------------------------------------------ layout

    private boolean frontIsZ() {
        return front.getAxis() == Direction.Axis.Z;
    }

    private void footprint() {
        int minSide = Math.min(site.width(), site.depth());
        int margin = minSide >= 11 ? 2 : minSide >= 7 ? 1 : 0;
        porchDepth = st.porch() ? 2 : 0;

        int availX = site.width() - 2 * margin;
        int availZ = site.depth() - 2 * margin;
        if (frontIsZ()) {
            availZ -= porchDepth;
        } else {
            availX -= porchDepth;
        }
        // maxWidth runs along the front, maxDepth front-to-back
        int capX = frontIsZ() ? st.maxWidth() : st.maxDepth();
        int capZ = frontIsZ() ? st.maxDepth() : st.maxWidth();
        int w = Math.max(5, capX > 0 ? Math.min(availX, capX) : availX);
        int d = Math.max(5, capZ > 0 ? Math.min(availZ, capZ) : availZ);

        int cx = (site.x0 + site.x1) / 2, cz = (site.z0 + site.z1) / 2;
        bx0 = cx - (w - 1) / 2;
        bx1 = bx0 + w - 1;
        bz0 = cz - (d - 1) / 2;
        bz1 = bz0 + d - 1;
        // Leave room for the porch on the front side.
        int shift = (porchDepth + 1) / 2;
        switch (front) {
            case NORTH -> { bz0 += shift; bz1 += shift; }
            case SOUTH -> { bz0 -= shift; bz1 -= shift; }
            case WEST -> { bx0 += shift; bx1 += shift; }
            case EAST -> { bx0 -= shift; bx1 -= shift; }
            default -> { }
        }
    }

    private void chooseLevels() {
        List<Integer> heights = new ArrayList<>();
        int maxG = Integer.MIN_VALUE, maxWater = Integer.MIN_VALUE;
        boolean anyWater = false;
        for (int x = bx0; x <= bx1; x++) {
            for (int z = bz0; z <= bz1; z++) {
                if (!site.contains(x, z)) {
                    continue;
                }
                int g = site.ground(x, z);
                heights.add(g + 1);
                maxG = Math.max(maxG, g);
                if (site.isWater(x, z)) {
                    anyWater = true;
                    maxWater = Math.max(maxWater, site.water(x, z));
                }
            }
        }
        heights.sort(Integer::compare);
        int median = heights.get(heights.size() / 2);
        int slope = maxG - (heights.get(0) - 1);

        stilts = switch (st.foundation()) {
            case STILTS -> true;
            case SOLID -> false;
            case AUTO -> anyWater || slope > 4;
        };
        if (stilts) {
            y0 = Math.max(median + 3, maxG + 2);
            if (anyWater) {
                y0 = Math.max(y0, maxWater + 3);
            }
        } else {
            y0 = median;
            if (anyWater) {
                y0 = Math.max(y0, maxWater + 2);
            }
        }
        roofBase = y0 + st.floors() * st.floorHeight();
    }

    /** Door position, porch and the steps/path columns (so landscaping leaves them alone). */
    private void planFrontage() {
        int dx, dz;
        switch (front) {
            case NORTH -> { dx = (bx0 + bx1) / 2; dz = bz0; }
            case SOUTH -> { dx = (bx0 + bx1) / 2; dz = bz1; }
            case WEST -> { dx = bx0; dz = (bz0 + bz1) / 2; }
            default -> { dx = bx1; dz = (bz0 + bz1) / 2; }
        }
        door = new BlockPos(dx, y0, dz);
        BlockPos c = door.relative(front);
        while (site.contains(c.getX(), c.getZ())) {
            reserved.add(col(c.getX(), c.getZ()));
            c = c.relative(front);
        }
        for (BlockPos p : porchCells()) {
            reserved.add(col(p.getX(), p.getZ()));
        }
    }

    private List<BlockPos> porchCells() {
        List<BlockPos> cells = new ArrayList<>();
        if (porchDepth == 0) {
            return cells;
        }
        for (int k = 1; k <= porchDepth; k++) {
            if (frontIsZ()) {
                int z = front == Direction.NORTH ? bz0 - k : bz1 + k;
                for (int x = bx0; x <= bx1; x++) {
                    cells.add(new BlockPos(x, y0 - 1, z));
                }
            } else {
                int x = front == Direction.WEST ? bx0 - k : bx1 + k;
                for (int z = bz0; z <= bz1; z++) {
                    cells.add(new BlockPos(x, y0 - 1, z));
                }
            }
        }
        return cells;
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private boolean inBuilding(int x, int z) {
        return x >= bx0 && x <= bx1 && z >= bz0 && z <= bz1;
    }

    private boolean onPerimeter(int x, int z) {
        return inBuilding(x, z) && (x == bx0 || x == bx1 || z == bz0 || z == bz1);
    }

    // ------------------------------------------------------------------ terrain

    private void clearTrees() {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = site.x0; x <= site.x1; x++) {
            for (int z = site.z0; z <= site.z1; z++) {
                int g = site.ground(x, z);
                for (int y = g + 1; y <= g + 32; y++) {
                    if (Site.isTree(level.getBlockState(p.set(x, y, z)))) {
                        dig(p.immutable());
                    }
                }
            }
        }
    }

    /** Regrade a ring around the building so the ground meets the floor smoothly. */
    private void landscape() {
        final int ring = 3;
        for (int x = site.x0; x <= site.x1; x++) {
            for (int z = site.z0; z <= site.z1; z++) {
                if (inBuilding(x, z) || reserved.contains(col(x, z)) || site.isWater(x, z)) {
                    continue;
                }
                int d = Math.max(Math.max(bx0 - x, x - bx1), Math.max(bz0 - z, z - bz1));
                if (d > ring) {
                    continue;
                }
                int g = site.ground(x, z);
                double t = d / (ring + 1.0);
                int target = (int) Math.round((y0 - 1) * (1 - t) + g * t);
                if (target > g) {
                    for (int y = g + 1; y < target; y++) {
                        put(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
                    }
                    put(new BlockPos(x, target, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    dig(new BlockPos(x, target + 1, z)); // flowers/grass sitting on the old surface
                } else if (target < g) {
                    for (int y = target + 1; y <= g + 1; y++) {
                        dig(new BlockPos(x, y, z));
                    }
                    put(new BlockPos(x, target, z), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        }
    }

    // ------------------------------------------------------------------ structure

    private void foundation() {
        for (int x = bx0; x <= bx1; x++) {
            for (int z = bz0; z <= bz1; z++) {
                int g = site.contains(x, z) ? site.ground(x, z) : y0 - 2;
                boolean edge = onPerimeter(x, z);
                if (stilts) {
                    if (isStiltPost(x, z)) {
                        for (int y = g + 1; y <= y0 - 2; y++) {
                            put(new BlockPos(x, y, z), axis(st.foundationMat().pick(new BlockPos(x, y, z)), Direction.Axis.Y));
                        }
                    }
                } else if (edge) {
                    for (int y = g + 1; y <= y0 - 2; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        put(p, st.foundationMat().pick(p).defaultBlockState());
                    }
                }
            }
        }
    }

    private boolean isStiltPost(int x, int z) {
        boolean cornerX = x == bx0 || x == bx1, cornerZ = z == bz0 || z == bz1;
        if (cornerX && cornerZ) {
            return true;
        }
        if (cornerZ) {
            return (x - bx0) % 3 == 0;
        }
        if (cornerX) {
            return (z - bz0) % 3 == 0;
        }
        return false;
    }

    private int levels() {
        return st.floors() + (st.roof() == Style.Roof.FLAT ? 0 : 1); // +1 = attic floor under a pitched roof
    }

    private BlockPos ladderCell() {
        // Inside the back wall, one in from the left end.
        Direction back = front.getOpposite();
        int x, z;
        switch (back) {
            case NORTH -> { x = bx0 + 2; z = bz0 + 1; }
            case SOUTH -> { x = bx1 - 2; z = bz1 - 1; }
            case WEST -> { x = bx0 + 1; z = bz1 - 2; }
            default -> { x = bx1 - 1; z = bz0 + 2; }
        }
        return new BlockPos(x, 0, z);
    }

    private boolean hasLadder() {
        return levels() > 1 && (bx1 - bx0) >= 4 && (bz1 - bz0) >= 4;
    }

    private void floors() {
        BlockPos lad = ladderCell();
        for (int f = 0; f <= st.floors(); f++) {
            int y = y0 - 1 + f * st.floorHeight();
            boolean ceiling = f == st.floors();
            if (ceiling && st.roof() != Style.Roof.FLAT && levels() <= st.floors()) {
                continue;
            }
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (f == 0 && onPerimeter(x, z)) {
                        put(p, stilts ? axis(st.foundationMat().pick(p), alongWall(x, z)) : st.foundationMat().pick(p).defaultBlockState());
                        continue;
                    }
                    if (onPerimeter(x, z)) {
                        continue; // walls own their beam course
                    }
                    if (f > 0 && hasLadder() && x == lad.getX() && z == lad.getZ()) {
                        dig(p); // hole for the ladder
                        continue;
                    }
                    Block b = ceiling && st.roof() == Style.Roof.FLAT ? st.roofFull() : st.floorMat().pick(p);
                    put(p, b.defaultBlockState());
                }
            }
        }
    }

    private Direction.Axis alongWall(int x, int z) {
        return (z == bz0 || z == bz1) ? Direction.Axis.X : Direction.Axis.Z;
    }

    private void walls() {
        int fh = st.floorHeight();
        for (int f = 0; f < st.floors(); f++) {
            int yb = y0 + f * fh;
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    if (!onPerimeter(x, z)) {
                        continue;
                    }
                    boolean corner = (x == bx0 || x == bx1) && (z == bz0 || z == bz1);
                    boolean alongX = z == bz0 || z == bz1;
                    int idx = alongX ? x - bx0 : z - bz0;
                    int len = alongX ? bx1 - bx0 + 1 : bz1 - bz0 + 1;
                    boolean post = corner || (st.framed() && idx % 4 == 0) || (st.framed() && idx == len - 1);
                    boolean isDoorColumn = f == 0 && x == door.getX() && z == door.getZ();
                    for (int dy = 0; dy < fh; dy++) {
                        int y = yb + dy;
                        BlockPos p = new BlockPos(x, y, z);
                        boolean top = dy == fh - 1;
                        if (isDoorColumn && dy <= 1) {
                            putDoor(p, dy == 0);
                        } else if (post) {
                            put(p, axis(st.frameMat().pick(p), Direction.Axis.Y));
                        } else if (top && st.framed()) {
                            put(p, axis(st.frameMat().pick(p), alongX ? Direction.Axis.X : Direction.Axis.Z));
                        } else if (isWindow(idx, len, dy, x, z)) {
                            put(p, st.window().defaultBlockState());
                        } else {
                            put(p, st.wallMat().pick(p).defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    private boolean isWindow(int idx, int len, int dy, int x, int z) {
        if (dy < 1 || dy > 2 || len < 4 || idx <= 0 || idx >= len - 1) {
            return false;
        }
        // keep the door's neighbours solid
        if (Math.abs(x - door.getX()) + Math.abs(z - door.getZ()) <= 1) {
            return false;
        }
        // a ladder needs a solid block behind it, not glass
        if (hasLadder()) {
            BlockPos behind = ladderCell().relative(front.getOpposite());
            if (behind.getX() == x && behind.getZ() == z) {
                return false;
            }
        }
        if (st.framed()) {
            return idx % 4 == 2;
        }
        return idx % st.windowSpacing() == st.windowSpacing() / 2;
    }

    private void putDoor(BlockPos p, boolean lower) {
        BlockState s = st.door().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, front.getOpposite())
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, lower ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
        put(p, s);
    }

    private void ladder() {
        if (!hasLadder()) {
            return;
        }
        BlockPos lad = ladderCell();
        int top = y0 - 1 + (levels() - 1) * st.floorHeight();
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, front);
        for (int y = y0; y <= top; y++) {
            put(new BlockPos(lad.getX(), y, lad.getZ()), ladder);
        }
    }

    // ------------------------------------------------------------------ roofs

    private void roof() {
        switch (st.roof()) {
            case GABLE -> gableRoof();
            case HIP -> hipRoof();
            case FLAT -> flatRoof();
        }
    }

    private BlockState stair(Direction facing) {
        return st.roofStairs().defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, Half.BOTTOM);
    }

    private void gableRoof() {
        boolean ridgeAlongX = (bx1 - bx0) >= (bz1 - bz0);
        int spanMin = ridgeAlongX ? bz0 - 1 : bx0 - 1;
        int spanLen = ridgeAlongX ? (bz1 - bz0 + 3) : (bx1 - bx0 + 3);
        int runMin = ridgeAlongX ? bx0 - 1 : bz0 - 1;
        int runMax = ridgeAlongX ? bx1 + 1 : bz1 + 1;
        Direction up = ridgeAlongX ? Direction.SOUTH : Direction.EAST;   // stairs on the low-s side face +s
        Direction down = up.getOpposite();

        int rows = (spanLen - 1) / 2;
        for (int i = 0; i <= rows; i++) {
            int y = roofBase - 1 + i;
            int sA = i, sB = spanLen - 1 - i;
            for (int t = runMin; t <= runMax; t++) {
                if (sA == sB) {
                    put(roofPos(ridgeAlongX, t, spanMin + sA, y), st.roofFull().defaultBlockState());
                } else if (sA < sB) {
                    put(roofPos(ridgeAlongX, t, spanMin + sA, y), stair(up));
                    put(roofPos(ridgeAlongX, t, spanMin + sB, y), stair(down));
                }
            }
            roofTop = y;
        }
        // Even span: cap the ridge where the two slopes meet.
        if (spanLen % 2 == 0) {
            int y = roofBase - 1 + rows + 1;
            for (int t = runMin; t <= runMax; t++) {
                put(roofPos(ridgeAlongX, t, spanMin + rows, y), st.roofSlab().defaultBlockState());
                put(roofPos(ridgeAlongX, t, spanMin + rows + 1, y), st.roofSlab().defaultBlockState());
            }
            roofTop = y;
        }

        // Gable-end triangles (walls under the roof at both ends of the ridge).
        int[] ends = ridgeAlongX ? new int[]{bx0, bx1} : new int[]{bz0, bz1};
        for (int end : ends) {
            for (int k = 1; k <= rows; k++) {
                int y = roofBase - 1 + k;
                for (int s = k + 1; s <= spanLen - 2 - k; s++) {
                    BlockPos p = roofPos(ridgeAlongX, end, spanMin + s, y);
                    boolean centre = s == (spanLen - 1) / 2 || s == spanLen / 2;
                    if (k == 1 && centre && spanLen >= 7) {
                        put(p, st.window().defaultBlockState()); // attic window
                    } else if (st.framed() && centre) {
                        put(p, axis(st.frameMat().pick(p), Direction.Axis.Y));
                    } else {
                        put(p, st.wallMat().pick(p).defaultBlockState());
                    }
                }
            }
        }
    }

    private static BlockPos roofPos(boolean ridgeAlongX, int run, int span, int y) {
        return ridgeAlongX ? new BlockPos(run, y, span) : new BlockPos(span, y, run);
    }

    private void hipRoof() {
        int xa = bx0 - 1, xb = bx1 + 1, za = bz0 - 1, zb = bz1 + 1;
        for (int k = 0; ; k++) {
            int y = roofBase - 1 + k;
            int ax = xa + k, bxx = xb - k, az = za + k, bzz = zb - k;
            if (ax > bxx || az > bzz) {
                break;
            }
            roofTop = y;
            if (bxx - ax <= 1 || bzz - az <= 1) {
                for (int x = ax; x <= bxx; x++) {
                    for (int z = az; z <= bzz; z++) {
                        put(new BlockPos(x, y, z), st.roofFull().defaultBlockState());
                    }
                }
                put(new BlockPos((ax + bxx) / 2, y + 1, (az + bzz) / 2), st.roofSlab().defaultBlockState());
                roofTop = y + 1;
                break;
            }
            for (int x = ax; x <= bxx; x++) {
                put(new BlockPos(x, y, az), stair(Direction.SOUTH));
                put(new BlockPos(x, y, bzz), stair(Direction.NORTH));
            }
            for (int z = az + 1; z < bzz; z++) {
                put(new BlockPos(ax, y, z), stair(Direction.EAST));
                put(new BlockPos(bxx, y, z), stair(Direction.WEST));
            }
        }
    }

    private void flatRoof() {
        int y = roofBase;
        for (int x = bx0; x <= bx1; x++) {
            for (int z = bz0; z <= bz1; z++) {
                if (onPerimeter(x, z)) {
                    put(new BlockPos(x, y, z), st.trim().defaultBlockState());
                }
            }
        }
        roofTop = y;
    }

    // ------------------------------------------------------------------ details

    private void chimney() {
        boolean ridgeAlongX = (bx1 - bx0) >= (bz1 - bz0);
        int x, z;
        if (ridgeAlongX) {
            x = bx0 - 1;
            z = (bz0 + bz1) / 2;
        } else {
            x = (bx0 + bx1) / 2;
            z = bz0 - 1;
        }
        int g = site.contains(x, z) ? site.ground(x, z) : y0 - 1;
        for (int y = Math.min(g + 1, y0 - 1); y <= roofTop + 2; y++) {
            BlockPos p = new BlockPos(x, y, z);
            put(p, st.chimneyMat().pick(p).defaultBlockState());
        }
    }

    private void porch() {
        List<BlockPos> cells = porchCells();
        Set<BlockPos> floor = new HashSet<>(cells);
        for (BlockPos p : cells) {
            put(p, st.floorMat().pick(p).defaultBlockState());
        }
        // Railing on the outer edge and the two short sides, open in front of the door.
        for (BlockPos p : cells) {
            BlockPos outward = p.relative(front);
            boolean outerEdge = !floor.contains(outward);
            boolean sideEdge = !floor.contains(p.relative(front.getClockWise())) || !floor.contains(p.relative(front.getCounterClockWise()));
            boolean doorLine = frontIsZ() ? p.getX() == door.getX() : p.getZ() == door.getZ();
            if ((outerEdge && !doorLine) || (sideEdge && !outerEdge)) {
                put(p.above(), st.trim().defaultBlockState());
            }
            boolean corner = outerEdge && sideEdge;
            if (corner || (stilts && outerEdge && isStiltPostAlong(p))) {
                int g = site.contains(p.getX(), p.getZ()) ? site.ground(p.getX(), p.getZ()) : y0 - 2;
                for (int y = g + 1; y < y0 - 1; y++) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    put(q, axis(st.foundationMat().pick(q), Direction.Axis.Y));
                }
            }
        }
    }

    private boolean isStiltPostAlong(BlockPos p) {
        int idx = frontIsZ() ? p.getX() - bx0 : p.getZ() - bz0;
        return idx % 3 == 0;
    }

    /** Steps down from the door (or porch) to the ground, then a dirt path to the edge of the site. */
    private void stepsAndPath() {
        BlockPos c = door.relative(front, 1 + porchDepth);
        int k = 0;
        // Ground higher than the floor in front: cut a walkway.
        while (site.contains(c.getX(), c.getZ()) && site.ground(c.getX(), c.getZ()) >= y0) {
            for (int y = y0; y <= site.ground(c.getX(), c.getZ()) + 1; y++) {
                dig(new BlockPos(c.getX(), y, c.getZ()));
            }
            pathAt(c.getX(), y0 - 1, c.getZ());
            c = c.relative(front);
        }
        // Ground lower: stairs down.
        while (site.contains(c.getX(), c.getZ())) {
            int g = site.ground(c.getX(), c.getZ());
            int y = y0 - 1 - k;
            if (y <= g) {
                break;
            }
            put(new BlockPos(c.getX(), y, c.getZ()), stepStair());
            for (int fy = g + 1; fy < y; fy++) {
                BlockPos f = new BlockPos(c.getX(), fy, c.getZ());
                put(f, st.foundationMat().pick(f).defaultBlockState());
            }
            dig(new BlockPos(c.getX(), y + 1, c.getZ()));
            dig(new BlockPos(c.getX(), y + 2, c.getZ()));
            k++;
            c = c.relative(front);
        }
        // Path to the edge of the site.
        while (site.contains(c.getX(), c.getZ())) {
            if (!site.isWater(c.getX(), c.getZ())) {
                pathAt(c.getX(), site.ground(c.getX(), c.getZ()), c.getZ());
            }
            c = c.relative(front);
        }
    }

    private BlockState stepStair() {
        // Use a stone stair matching the foundation feel; facing back toward the building.
        Block b = st.foundationMat().pick(door).defaultBlockState().is(Blocks.COBBLESTONE)
                || st.foundationMat().pick(door).defaultBlockState().is(Blocks.MOSSY_COBBLESTONE)
                ? Blocks.COBBLESTONE_STAIRS
                : st.foundationMat().pick(door).defaultBlockState().is(Blocks.COBBLED_DEEPSLATE)
                ? Blocks.COBBLED_DEEPSLATE_STAIRS
                : st.roofStairs();
        return b.defaultBlockState().setValue(StairBlock.FACING, front.getOpposite()).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    private void pathAt(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState cur = out.getOrDefault(p, level.getBlockState(p));
        if (cur.is(Blocks.GRASS_BLOCK) || cur.is(Blocks.DIRT) || cur.is(Blocks.COARSE_DIRT) || cur.is(Blocks.PODZOL)) {
            put(p, Blocks.DIRT_PATH.defaultBlockState());
            dig(p.above());
        }
    }

    /** Anything natural left inside the building's volume gets dug out. */
    private void clearInterior() {
        for (int x = bx0 - 1; x <= bx1 + 1; x++) {
            for (int z = bz0 - 1; z <= bz1 + 1; z++) {
                boolean inside = inBuilding(x, z);
                int from = inside ? y0 : roofBase - 1;
                for (int y = from; y <= roofTop; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!out.containsKey(p)) {
                        dig(p);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void put(BlockPos p, BlockState s) {
        out.put(p.immutable(), s);
    }

    private void dig(BlockPos p) {
        if (!out.containsKey(p) && !GhostBlocks.isEmpty(level.getBlockState(p))) {
            out.put(p.immutable(), Blocks.AIR.defaultBlockState());
        }
    }

    private static BlockState axis(Block b, Direction.Axis a) {
        BlockState s = b.defaultBlockState();
        return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, a) : s;
    }

    /**
     * Let stairs, panes, fences and walls connect to each other (corner stairs, joined panes) by
     * running vanilla shape updates with the design temporarily written into the client world.
     */
    private void normalizeShapes() {
        int flags = Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE;
        Map<BlockPos, BlockState> originals = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : out.entrySet()) {
            BlockPos p = e.getKey();
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState cur = level.getBlockState(p);
            if (cur.hasBlockEntity()) {
                continue;
            }
            originals.put(p, cur);
            level.setBlock(p, e.getValue(), flags);
        }
        try {
            for (Map.Entry<BlockPos, BlockState> e : out.entrySet()) {
                BlockState s = e.getValue();
                Block b = s.getBlock();
                if (!(b instanceof StairBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock
                        || b instanceof FenceBlock || b instanceof IronBarsBlock)) {
                    continue;
                }
                BlockPos p = e.getKey();
                if (!originals.containsKey(p)) {
                    continue;
                }
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    try {
                        s = s.updateShape(d, level.getBlockState(n), level, p, n);
                    } catch (Throwable ignored) {
                    }
                }
                if (s.is(b)) {
                    e.setValue(s);
                }
            }
        } finally {
            for (Map.Entry<BlockPos, BlockState> e : originals.entrySet()) {
                level.setBlock(e.getKey(), e.getValue(), flags);
            }
        }
    }
}
