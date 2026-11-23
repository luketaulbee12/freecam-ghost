package net.xolt.freecam.forge.ghost.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * A* over "feet" positions. Moves: walk (incl. diagonals), step up one, drop up to 3, and - when the
 * player carries scaffold blocks - pillar up (jump + place below) and bridge (place a block to walk on).
 */
final class PathFinder {
    private static final int MAX_NODES = 40_000;
    private static final int MAX_RANGE = 96; // per segment; longer trips are walked in segments
    private static final int[][] DIRS8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    enum Kind { WALK, PILLAR, BRIDGE }

    /** One step of a path: where the feet end up and how we get there. */
    record Step(BlockPos pos, Kind kind) {}

    /** Scaffolding allowance for a search. {@code forbidden} cells must never get a scaffold block. */
    record Scaffold(int blocks, Set<BlockPos> forbidden) {}

    private PathFinder() {}

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        Node parent;
        Kind kind = Kind.WALK;
        boolean supported; // standing on a scaffold block that will exist once we get here
        int used;          // scaffold blocks used to reach this node
        double g, f;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private record Move(BlockPos to, double cost, Kind kind, boolean supported, int used) {}

    /**
     * A path to the goal, or - when the goal is too far to search in one go - a {@code partial} path to
     * the explored spot closest to it. Walk it, then search again from there.
     */
    record Result(List<Step> steps, boolean partial) {}

    @Nullable
    static Result find(Level level, BlockPos start, java.util.function.BiPredicate<BlockPos, Boolean> goal, ToDoubleFunction<BlockPos> heuristic,
                       Set<BlockPos> avoid, @Nullable Scaffold scaffold, double weight) {
        if (goal.test(start, false)) {
            return new Result(List.of(), false);
        }
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node s = new Node(start);
        double startH = heuristic.applyAsDouble(start);
        s.f = startH;
        nodes.put(start.asLong(), s);
        open.add(s);
        Node closest = s;
        double closestH = startH;

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node cur = open.poll();
            if (cur.closed) {
                continue;
            }
            cur.closed = true;
            if (cur != s && goal.test(cur.pos, cur.supported)) {
                return new Result(rebuild(cur), false);
            }
            // Remember the closest standable spot (not mid-pillar) for a partial path.
            double h = heuristic.applyAsDouble(cur.pos);
            if (cur != s && h < closestH && cur.kind == Kind.WALK && !cur.supported) {
                closestH = h;
                closest = cur;
            }
            for (Move m : moves(level, cur, scaffold)) {
                if (avoid.contains(m.to) || m.to.distManhattan(start) > MAX_RANGE) {
                    continue;
                }
                Node n = nodes.computeIfAbsent(m.to.asLong(), k -> new Node(m.to));
                if (n.closed || n == s) {
                    continue;
                }
                double g = cur.g + m.cost;
                if (n.parent == null || g < n.g) {
                    n.parent = cur;
                    n.kind = m.kind;
                    n.supported = m.supported;
                    n.used = m.used;
                    n.g = g;
                    n.f = g + heuristic.applyAsDouble(n.pos) * weight;
                    open.add(n);
                }
            }
        }
        // Couldn't reach the goal within the search budget/range: head for the closest spot found,
        // as long as it gets us meaningfully nearer.
        if (closest != s && closestH < startH - 4.0) {
            return new Result(rebuild(closest), true);
        }
        return null;
    }

    private static List<Step> rebuild(Node end) {
        List<Step> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            out.add(new Step(n.pos, n.kind));
        }
        Collections.reverse(out);
        return out;
    }

    private static List<Move> moves(Level level, Node node, @Nullable Scaffold scaffold) {
        BlockPos p = node.pos;
        List<Move> out = new ArrayList<>(14);
        boolean headroomForJump = passable(level, p.above(2));
        boolean onGround = node.supported || standable(level, p);
        for (int[] d : DIRS8) {
            int dx = d[0], dz = d[1];
            boolean diag = dx != 0 && dz != 0;
            BlockPos flat = p.offset(dx, 0, dz);

            if (diag && (!clear(level, p.offset(dx, 0, 0)) || !clear(level, p.offset(0, 0, dz)))) {
                continue; // would clip a corner
            }

            if (clear(level, flat)) {
                if (standable(level, flat)) {
                    out.add(new Move(flat, (diag ? 1.414 : 1.0) * costAt(level, flat), Kind.WALK, false, node.used));
                } else if (!diag) {
                    boolean dropped = false;
                    for (int drop = 1; drop <= 3; drop++) {
                        BlockPos down = flat.below(drop);
                        if (!passable(level, down)) {
                            break;
                        }
                        if (standable(level, down)) {
                            out.add(new Move(down, 1.0 + drop * 0.5, Kind.WALK, false, node.used));
                            dropped = true;
                            break;
                        }
                    }
                    // Bridge: put a block under the next cell and walk onto it.
                    BlockPos under = flat.below();
                    if (!dropped && scaffold != null && onGround && node.used < scaffold.blocks()
                            && replaceable(level, under) && !scaffold.forbidden().contains(under)
                            && (node.supported || PlacementPlanner.isSupport(level.getBlockState(p.below())))) {
                        out.add(new Move(flat, 3.0, Kind.BRIDGE, true, node.used + 1));
                    }
                }
            } else if (!diag && headroomForJump) {
                BlockPos up = flat.above();
                if (clear(level, up) && standable(level, up)) {
                    out.add(new Move(up, 2.0 * costAt(level, up), Kind.WALK, false, node.used));
                }
            }
        }
        // Pillar: jump and place a block where our feet were.
        if (scaffold != null && onGround && headroomForJump && node.used < scaffold.blocks()
                && !scaffold.forbidden().contains(p) && !level.getFluidState(p).is(FluidTags.LAVA)) {
            out.add(new Move(p.above(), 2.5, Kind.PILLAR, true, node.used + 1));
        }
        return out;
    }

    /** Feet and head space free. */
    static boolean clear(BlockGetter level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above());
    }

    /** Solid ground below that is safe to stand on. */
    static boolean standable(Level level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        if (dangerous(below)) {
            return false;
        }
        VoxelShape shape = below.getCollisionShape(level, feet.below());
        if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.8) {
            // Allow standing in shallow water on top of something solid.
            return level.getFluidState(feet).is(FluidTags.WATER) && !level.getBlockState(feet.below()).isAir();
        }
        return true;
    }

    static boolean passable(BlockGetter level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) {
            return true;
        }
        if (dangerous(s) || s.is(Blocks.COBWEB) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SWEET_BERRY_BUSH)) {
            return false;
        }
        if (s.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        VoxelShape shape = s.getCollisionShape(level, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
    }

    private static boolean replaceable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return (s.isAir() || s.canBeReplaced()) && !s.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean dangerous(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(BlockTags.FIRE) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS)
                || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.WITHER_ROSE)
                || s.getFluidState().is(FluidTags.LAVA);
    }

    private static double costAt(Level level, BlockPos feet) {
        return level.getFluidState(feet).is(FluidTags.WATER) ? 3.0 : 1.0;
    }
}
