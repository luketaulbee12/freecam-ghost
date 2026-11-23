package net.xolt.freecam.forge.ghost.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.xolt.freecam.Freecam;
import net.xolt.freecam.forge.ghost.Blueprints;
import net.xolt.freecam.forge.ghost.GhostBlocks;
import net.xolt.freecam.forge.ghost.GhostRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Walks the real player around and builds a saved blueprint in survival with what's in the inventory.
 * <ol>
 *   <li>Mines red-marked blocks (top-down), then places blocks (bottom-up, nearest first).</li>
 *   <li>Uses dirt as scaffolding: pillars up / bridges across to reach things, and builds a dirt
 *       chain to give floating blocks something to be placed against.</li>
 *   <li>Removes all the dirt it placed at the end (unless removing it would break the build).</li>
 * </ol>
 * You can watch from freecam while it works.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class AutoBuilder {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Item SCAFFOLD_ITEM = Items.DIRT;
    private static final BlockState SCAFFOLD_STATE = Blocks.DIRT.defaultBlockState();
    private static final int MAX_RETRIES = 3;
    private static final int BREAK_TIMEOUT = 20 * 30;
    private static final int SUPPORT_SEARCH = 6;

    private enum Phase { PLAN, WALK, SETTLE, PLACE, VERIFY, BREAK }

    private record Task(BlockPos pos, BlockState target, boolean dig, boolean scaffold) {}

    private static boolean running;
    @Nullable private static Blueprints.Blueprint bp;
    private static Phase phase = Phase.PLAN;
    private static BotInput input;
    @Nullable private static Task task;
    private static List<PathFinder.Step> path = List.of();
    private static boolean pathPartial;
    @Nullable private static Task pendingTask;
    @Nullable private static Task partialTask;
    private static double partialBestDist = Double.MAX_VALUE;
    private static int partialStalls;
    private static BlockPos pathStart = BlockPos.ZERO;
    private static int pathIdx;
    private static int stepTicks;
    private static boolean stepPlaced;
    private static int phaseTicks;
    private static int retries;
    private static Vec3 stuckCheckPos = Vec3.ZERO;
    private static int stuckTicks;
    private static int stuckStrikes;
    private static int lastHurtTime;
    private static int ticks;
    private static boolean progressSinceRetryRound;
    private static int retryRounds;

    @Nullable private static PlacementPlanner.Plan placePlan;
    private static Direction breakFace = Direction.UP;

    /** Dirt we placed and still need to remove. */
    private static final Set<BlockPos> scaffolds = new HashSet<>();
    /** Dirt placements queued to give a floating block something to attach to. */
    private static final Deque<BlockPos> supportQueue = new ArrayDeque<>();
    /** pos -> why it was skipped ("unreachable", "failed", "floating", or "item:<name>"). */
    private static final Map<BlockPos, String> skipped = new HashMap<>();
    private static final Map<BlockPos, BlockState> skippedBlocks = new HashMap<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("freecam-ghost");

    private AutoBuilder() {}

    public static boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ start/stop

    /** Starts building {@code blueprint}, or stops if already running. */
    public static void toggle(@Nullable Blueprints.Blueprint blueprint) {
        if (running) {
            stop("msg.freecam.buildReasonKey");
        } else if (blueprint != null) {
            start(blueprint);
        } else if (MC.player != null) {
            MC.player.displayClientMessage(Component.translatable("msg.freecam.buildNothing"), true);
        }
    }

    private static void start(Blueprints.Blueprint blueprint) {
        LocalPlayer player = MC.player;
        if (player == null || MC.level == null || GhostBlocks.isSessionActive()) {
            return;
        }
        bp = blueprint;
        skipped.clear();
        skippedBlocks.clear();
        pendingTask = null;
        partialTask = null;
        scaffolds.clear();
        supportQueue.clear();
        retryRounds = 0;
        int pending = countPending(MC.level);
        if (pending == 0) {
            player.displayClientMessage(Component.translatable("msg.freecam.buildNothing"), true);
            bp = null;
            return;
        }
        running = true;
        input = new BotInput();
        player.input = input;
        lastHurtTime = player.hurtTime;
        setPhase(Phase.PLAN);
        player.displayClientMessage(Component.translatable("msg.freecam.buildStarted", blueprint.name(), pending), false);
    }

    private static void stop(@Nullable String reasonKey) {
        if (!running) {
            return;
        }
        running = false;
        LocalPlayer player = MC.player;
        if (MC.gameMode != null && MC.gameMode.isDestroying()) {
            MC.gameMode.stopDestroyBlock();
        }
        if (player != null) {
            player.input = new KeyboardInput(MC.options);
            if (reasonKey != null) {
                player.displayClientMessage(Component.translatable("msg.freecam.buildStopped",
                        Component.translatable(reasonKey)), false);
            } else {
                player.displayClientMessage(Component.translatable("msg.freecam.buildDone"), false);
            }
            report(player);
        }
        task = null;
        path = List.of();
        bp = null;
    }

    private static void report(LocalPlayer player) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        int unreachable = 0;
        for (String why : skipped.values()) {
            if (why.startsWith("item:")) {
                missing.merge(why.substring(5), 1, Integer::sum);
            } else {
                unreachable++;
            }
        }
        if (!missing.isEmpty()) {
            StringJoiner j = new StringJoiner(", ");
            missing.forEach((name, n) -> j.add(n + "x " + name));
            player.displayClientMessage(Component.translatable("msg.freecam.buildMissing", j.toString()), false);
        }
        if (unreachable > 0) {
            player.displayClientMessage(Component.translatable("msg.freecam.buildUnreachable", unreachable), false);
            // Spell out the first few so it's clear what went wrong and where.
            int shown = 0;
            for (Map.Entry<BlockPos, String> e : skipped.entrySet()) {
                if (e.getValue().startsWith("item:")) {
                    continue;
                }
                if (shown++ >= 4) {
                    break;
                }
                BlockState b = skippedBlocks.get(e.getKey());
                player.displayClientMessage(Component.literal(" - " + (b == null ? "?" : b.getBlock().getName().getString())
                        + " at " + e.getKey().toShortString() + ": " + e.getValue()).withStyle(net.minecraft.ChatFormatting.GRAY), false);
            }
        }
        if (!scaffolds.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.freecam.buildScaffoldLeft", scaffolds.size()), false);
        }
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        running = false;
        task = null;
        bp = null;
        scaffolds.clear();
    }

    /** Ignore the user's own clicks while building (they'd fight the bot). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (running) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Pre event) {
        if (!running) {
            return;
        }
        LocalPlayer player = MC.player;
        ClientLevel level = MC.level;
        if (player == null || level == null || MC.gameMode == null || bp == null) {
            running = false;
            return;
        }
        // In freecam the movement keys fly the camera, so only treat them as "take over" outside it.
        if (!Freecam.isEnabled() && MC.screen == null && (MC.options.keyUp.isDown() || MC.options.keyDown.isDown()
                || MC.options.keyLeft.isDown() || MC.options.keyRight.isDown() || MC.options.keyJump.isDown())) {
            stop("msg.freecam.buildReasonUser");
            return;
        }
        if (player.hurtTime > lastHurtTime) {
            stop("msg.freecam.buildReasonHurt");
            return;
        }
        lastHurtTime = player.hurtTime;
        if (player.input != input) {
            player.input = input; // Freecam (or something else) swapped it out
        }

        input.clear();
        if (MC.screen != null && !Freecam.isEnabled()) {
            return; // paused while a menu is open
        }

        ticks++;
        if (ticks % 40 == 0) {
            player.displayClientMessage(Component.translatable("msg.freecam.buildProgress", countPending(level)), true);
        }

        switch (phase) {
            case PLAN -> plan(level, player);
            case WALK -> walk(level, player);
            case SETTLE -> settle(level, player);
            case PLACE -> place(level, player);
            case VERIFY -> verify(level, player);
            case BREAK -> breakBlock(level, player);
        }
    }

    private static void setPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    // ------------------------------------------------------------------ task selection

    private static int countPending(ClientLevel level) {
        int n = scaffolds.size();
        if (bp != null) {
            for (Map.Entry<BlockPos, BlockState> e : bp.blocks.entrySet()) {
                if (level.isLoaded(e.getKey()) && classify(level, e.getKey(), e.getValue()) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** null = nothing to do here; TRUE = dig; FALSE = place. */
    @Nullable
    private static Boolean classify(ClientLevel level, BlockPos pos, BlockState target) {
        BlockState real = level.getBlockState(pos);
        boolean empty = GhostBlocks.isEmpty(real);
        if (target.isAir()) {
            return empty ? null : Boolean.TRUE;
        }
        if (real.is(target.getBlock()) || isSecondHalf(target)) {
            return null;
        }
        return empty ? Boolean.FALSE : Boolean.TRUE;
    }

    private static boolean isSecondHalf(BlockState s) {
        return (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }

    @Nullable
    private static Task nextTask(ClientLevel level, LocalPlayer player) {
        for (int guard = 0; guard < 64; guard++) {
            // 1. Finish any support chain we started.
            while (!supportQueue.isEmpty()) {
                BlockPos s = supportQueue.peek();
                if (!GhostBlocks.isEmpty(level.getBlockState(s))) {
                    supportQueue.poll();
                    continue;
                }
                if (skipped.containsKey(s)) {
                    supportQueue.clear();
                    break;
                }
                return new Task(s, SCAFFOLD_STATE, false, true);
            }

            // 2. Blueprint work: digs first (top-down), then supported placements (bottom-up, nearest).
            Task best = null, floating = null;
            double bestScore = Double.MAX_VALUE, floatScore = Double.MAX_VALUE;
            Vec3 me = player.position();
            for (Map.Entry<BlockPos, BlockState> e : bp.blocks.entrySet()) {
                BlockPos pos = e.getKey();
                if (skipped.containsKey(pos) || !level.isLoaded(pos)) {
                    continue;
                }
                Boolean dig = classify(level, pos, e.getValue());
                if (dig == null) {
                    continue;
                }
                if (dig && level.getBlockState(pos).getDestroySpeed(level, pos) < 0) {
                    markSkipped(pos, e.getValue(), "unbreakable"); // unbreakable
                    continue;
                }
                double dist = pos.distToCenterSqr(me);
                if (!dig && !PlacementPlanner.hasSupport(level, pos)) {
                    double fs = pos.getY() * 10_000.0 + dist;
                    if (fs < floatScore) {
                        floatScore = fs;
                        floating = new Task(pos.immutable(), e.getValue(), false, false);
                    }
                    continue;
                }
                double score = dig ? (-pos.getY() * 10_000.0) + dist : 1e9 + pos.getY() * 10_000.0 + dist;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Task(pos.immutable(), e.getValue(), dig, false);
                }
            }
            if (best != null) {
                return best;
            }

            // 3. Something floating: lay a dirt chain to it from the nearest solid block.
            if (floating != null) {
                int dirt = player.getInventory().countItem(SCAFFOLD_ITEM);
                List<BlockPos> chain = supportChain(level, player, floating.pos());
                if (chain != null && dirt >= chain.size()) {
                    supportQueue.addAll(chain); // short dirt chain from something nearby
                    continue;
                }
                // High up with nothing nearby: pillar up beside it (see plan()). That needs roughly its height in dirt.
                int need = pillarHeight(level, floating.pos()) + 2;
                if (dirt < need) {
                    markSkipped(floating.pos(), floating.target(), "floating " + (need - 2) + " blocks up - needs about "
                            + need + " dirt for a scaffold pillar, you have " + dirt);
                    continue;
                }
                return floating;
            }

            // 4. Clean up scaffolding, highest first.
            BlockPos top = null;
            for (BlockPos s : new ArrayList<>(scaffolds)) {
                if (!level.isLoaded(s)) {
                    continue;
                }
                if (!level.getBlockState(s).is(Blocks.DIRT)) {
                    scaffolds.remove(s); // already gone (or replaced by the build)
                    continue;
                }
                if (skipped.containsKey(s)) {
                    continue;
                }
                if (top == null || s.getY() > top.getY()
                        || (s.getY() == top.getY() && s.distToCenterSqr(me) < top.distToCenterSqr(me))) {
                    top = s;
                }
            }
            if (top != null) {
                if (removalBreaksBuild(level, top)) {
                    scaffolds.remove(top); // leave it: something in the build hangs off it
                    continue;
                }
                return new Task(top, Blocks.AIR.defaultBlockState(), true, true);
            }
            return null;
        }
        return null;
    }

    /**
     * Shortest chain of empty, non-blueprint cells from {@code target} to a cell that touches
     * something solid. Returned in placement order (the supported end first).
     */
    @Nullable
    private static List<BlockPos> supportChain(ClientLevel level, LocalPlayer player, BlockPos target) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos feet = player.blockPosition();
        for (Direction d : order) {
            BlockPos n = target.relative(d);
            if (chainCell(level, n, target, feet)) {
                parent.put(n, n);
                queue.add(n);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            if (PlacementPlanner.hasSupport(level, c)) {
                List<BlockPos> chain = new ArrayList<>();
                for (BlockPos x = c; ; x = parent.get(x)) {
                    chain.add(x);
                    if (parent.get(x).equals(x)) {
                        break;
                    }
                }
                return chain;
            }
            if (c.distManhattan(target) >= SUPPORT_SEARCH) {
                continue;
            }
            for (Direction d : order) {
                BlockPos m = c.relative(d);
                if (!parent.containsKey(m) && chainCell(level, m, target, feet)) {
                    parent.put(m, c);
                    queue.add(m);
                }
            }
        }
        return null;
    }

    /** How far {@code pos} is above the first non-empty block beneath it. */
    private static int pillarHeight(ClientLevel level, BlockPos pos) {
        int h = 0;
        BlockPos p = pos.below();
        while (p.getY() > level.getMinBuildHeight() && GhostBlocks.isEmpty(level.getBlockState(p))) {
            h++;
            p = p.below();
        }
        return h + 1;
    }

    private static boolean chainCell(ClientLevel level, BlockPos p, BlockPos target, BlockPos feet) {
        return !p.equals(target) && !p.equals(feet) && !p.equals(feet.above())
                && GhostBlocks.isEmpty(level.getBlockState(p)) && !bp.blocks.containsKey(p) && level.isLoaded(p);
    }

    /** Would removing this scaffold block drop/pop something next to it? (Checked client-side, then undone.) */
    private static boolean removalBreaksBuild(ClientLevel level, BlockPos s) {
        BlockState old = level.getBlockState(s);
        int flags = Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE;
        boolean breaks = false;
        level.setBlock(s, Blocks.AIR.defaultBlockState(), flags);
        try {
            for (Direction d : Direction.values()) {
                BlockPos n = s.relative(d);
                BlockState ns = level.getBlockState(n);
                if (ns.isAir() || scaffolds.contains(n)) {
                    continue;
                }
                if (!ns.canSurvive(level, n) || (d == Direction.UP && ns.getBlock() instanceof FallingBlock)) {
                    breaks = true;
                    break;
                }
            }
        } finally {
            level.setBlock(s, old, flags);
        }
        return breaks;
    }

    private static double reach(LocalPlayer player) {
        return player.blockInteractionRange() - 0.3;
    }

    private static Vec3 eyeAt(BlockPos feet, LocalPlayer player) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + player.getEyeHeight(Pose.STANDING), feet.getZ() + 0.5);
    }

    private static boolean canWorkFrom(ClientLevel level, BlockPos feet, Task t, LocalPlayer player) {
        if (feet.equals(t.pos()) || feet.above().equals(t.pos())) {
            return false;
        }
        if (t.dig() && feet.below().equals(t.pos())) {
            // Mining what we stand on is only OK for scaffolding with ground right under it (we drop 1).
            if (!t.scaffold() || !PathFinder.standable(level, t.pos())) {
                return false;
            }
        }
        return eyeAt(feet, player).distanceTo(Vec3.atCenterOf(t.pos())) <= reach(player) - 0.4;
    }

    // ------------------------------------------------------------------ phases

    private static void plan(ClientLevel level, LocalPlayer player) {
        // Continue a long trip to the same job instead of re-picking (which might choose something else).
        task = pendingTask != null && stillPending(level, pendingTask) ? pendingTask : nextTask(level, player);
        pendingTask = null;
        if (task == null) {
            if (!skipped.isEmpty() && progressSinceRetryRound && retryRounds < 3) {
                // New blocks may have made skipped spots reachable/placeable - give them another go.
                skipped.entrySet().removeIf(e -> !e.getValue().startsWith("item:"));
                retryRounds++;
                progressSinceRetryRound = false;
                return;
            }
            stop(null);
            return;
        }

        if (!task.dig()) {
            Item item = task.target().getBlock().asItem();
            if (item == Items.AIR) {
                markSkipped(task.pos(), task.target(), "block has no item form");
                return;
            }
            if (findSlot(player.getInventory(), item) < 0) {
                markSkipped(task.pos(), task.target(), "item:" + item.getDescription().getString());
                return;
            }
        }

        Task t = task;
        BlockPos start = BlockPos.containing(player.getX(), player.getY() + 0.5, player.getZ());
        Set<BlockPos> avoid = new HashSet<>();
        if (!t.dig()) {
            avoid.add(t.pos());
        }
        double r = reach(player) - 0.4;
        Vec3 center = Vec3.atCenterOf(t.pos());
        int dirt = player.getInventory().countItem(SCAFFOLD_ITEM);
        if (!t.dig() && t.target().is(Blocks.DIRT)) {
            dirt--; // keep one for the job itself
        }
        PathFinder.Scaffold scaffold = dirt > 0 ? new PathFinder.Scaffold(dirt, bp.blocks.keySet()) : null;
        // A block with nothing to place against: we must end up standing on a scaffold block that touches it
        // (e.g. the top of a dirt pillar right beside it).
        boolean floating = !t.dig() && !PlacementPlanner.hasSupport(level, t.pos());
        PathFinder.Result found = PathFinder.find(level, start,
                (feet, plannedSupport) -> PathFinder.clear(level, feet) && canWorkFrom(level, feet, t, player)
                        && (!floating || (feet.below().distManhattan(t.pos()) == 1
                        && (plannedSupport || PlacementPlanner.isSupport(level.getBlockState(feet.below()))))),
                feet -> Math.max(0, eyeAt(feet, player).distanceTo(center) - r),
                avoid, scaffold, floating ? 3.0 : 1.1);
        LOG.info("[auto-build] {} {} at {} from {} (dist {}, dirt {}): {}", t.dig() ? "dig" : "place",
                t.target().getBlock().getName().getString(), t.pos().toShortString(), start.toShortString(),
                (int) player.position().distanceTo(center), dirt,
                found == null ? "NO PATH" : (found.partial() ? "partial " : "path ") + found.steps().size() + " steps");
        if (found == null) {
            skip("no walkable path found");
            return;
        }
        if (found.partial()) {
            // Long trip: walk the first segment, then plan again from there. Give up if we stop closing in.
            double d = player.position().distanceTo(center);
            if (!t.equals(partialTask)) {
                partialTask = t;
                partialBestDist = d;
                partialStalls = 0;
            } else if (d > partialBestDist - 2.0) {
                if (++partialStalls >= 3) {
                    partialTask = null;
                    skip("long trip stopped getting closer");
                    return;
                }
            } else {
                partialBestDist = d;
                partialStalls = 0;
            }
        } else {
            partialTask = null;
            partialStalls = 0;
            partialBestDist = Double.MAX_VALUE;
        }
        pathPartial = found.partial();
        path = found.steps();
        pathStart = start;
        pathIdx = 0;
        resetStep();
        stuckCheckPos = player.position();
        stuckTicks = 0;
        stuckStrikes = 0;
        setPhase(Phase.WALK);
    }

    private static void resetStep() {
        stepTicks = 0;
        stepPlaced = false;
    }

    private static void advanceStep() {
        pathIdx++;
        resetStep();
        stuckTicks = 0;
        stuckStrikes = 0;
    }

    private static void walk(ClientLevel level, LocalPlayer player) {
        if (task == null || !stillPending(level, task)) {
            setPhase(Phase.PLAN);
            return;
        }
        if (pathIdx >= path.size()) {
            // End of a long-trip segment: keep the same job and search onward from here.
            setPhase(pathPartial ? Phase.PLAN : Phase.SETTLE);
            if (pathPartial) {
                pendingTask = task;
            }
            return;
        }
        PathFinder.Step step = path.get(pathIdx);
        switch (step.kind()) {
            case WALK -> walkStep(level, player, step.pos(), false);
            case PILLAR -> pillarStep(level, player, step.pos());
            case BRIDGE -> bridgeStep(level, player, step.pos());
        }
    }

    private static BlockPos previousFeet() {
        return pathIdx == 0 ? pathStart : path.get(pathIdx - 1).pos();
    }

    private static void walkStep(ClientLevel level, LocalPlayer player, BlockPos wp, boolean sneak) {
        double dx = wp.getX() + 0.5 - player.getX();
        double dz = wp.getZ() + 0.5 - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean last = pathIdx == path.size() - 1;
        if (horiz < (last || sneak ? 0.25 : 0.45) && Math.abs(player.getY() - wp.getY()) < 0.9) {
            advanceStep();
            return;
        }

        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        player.setYRot(approach(player.getYRot(), yaw, 40.0F));
        player.setYHeadRot(player.getYRot());
        player.setXRot(approach(player.getXRot(), 15.0F, 10.0F));
        float off = Math.abs(Mth.wrapDegrees(player.getYRot() - yaw));
        input.forward = off < 60.0F && !(last && horiz < 0.4);
        input.sneak = sneak;

        boolean higher = wp.getY() > Mth.floor(player.getY() + 0.05);
        if ((higher && horiz < 1.6 && player.onGround()) || (player.isInWater() && wp.getY() >= player.getY() - 0.2)) {
            input.jump = true;
        }

        // Stuck detection: if we haven't moved much in 1.5s, re-plan; give up after a few tries.
        if (++stuckTicks >= 30) {
            if (player.position().distanceTo(stuckCheckPos) < 0.4) {
                if (++stuckStrikes >= 2) {
                    retryOrSkip("got stuck while walking");
                    return;
                }
                input.jump = true;
            }
            stuckTicks = 0;
            stuckCheckPos = player.position();
        }
    }

    /** Jump and drop a dirt block where our feet were. {@code to} is the new feet position. */
    private static void pillarStep(ClientLevel level, LocalPlayer player, BlockPos to) {
        BlockPos base = to.below();
        if (++stepTicks > 80) {
            retryOrSkip("pillar step timed out");
            return;
        }
        if (!holdScaffold(player)) {
            skip("item:" + SCAFFOLD_ITEM.getDescription().getString() + " (scaffolding)");
            return;
        }
        double dx = base.getX() + 0.5 - player.getX();
        double dz = base.getZ() + 0.5 - player.getZ();
        if (!stepPlaced && Math.sqrt(dx * dx + dz * dz) > 0.25 && player.onGround()) {
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            player.setYRot(yaw);
            input.forward = true;
            input.sneak = true; // creep to the centre without walking off
            return;
        }
        player.setXRot(90.0F);
        if (!stepPlaced) {
            if (PlacementPlanner.isSupport(level.getBlockState(base))) {
                stepPlaced = true; // already solid
                return;
            }
            if (player.onGround()) {
                input.jump = true;
            }
            if (player.getY() > base.getY() + 1.05) {
                BlockHitResult hit = new BlockHitResult(new Vec3(base.getX() + 0.5, base.getY(), base.getZ() + 0.5),
                        Direction.UP, base.below(), false);
                use(player, hit);
                stepPlaced = true;
            }
        } else if (PlacementPlanner.isSupport(level.getBlockState(base))) {
            if (player.onGround() && player.getY() >= base.getY() + 0.99) {
                if (level.getBlockState(base).is(Blocks.DIRT) && !bp.blocks.containsKey(base)) {
                    scaffolds.add(base.immutable());
                }
                advanceStep();
            }
        } else if (player.onGround()) {
            stepPlaced = false; // landed without the block appearing - try again
        }
    }

    /** Place dirt under the next cell (against the block we're standing on), then sneak onto it. */
    private static void bridgeStep(ClientLevel level, LocalPlayer player, BlockPos to) {
        BlockPos under = to.below();
        BlockPos from = previousFeet();
        if (!stepPlaced) {
            if (PlacementPlanner.isSupport(level.getBlockState(under))) {
                stepPlaced = true;
                return;
            }
            if (!holdScaffold(player)) {
                skip("item:" + SCAFFOLD_ITEM.getDescription().getString() + " (scaffolding)");
                return;
            }
            Direction dir = Direction.getNearest(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
            BlockPos support = from.below();
            Vec3 hitPos = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(dir.getNormal()).scale(0.5));
            input.sneak = true;
            lookAt(player, hitPos);
            if (++stepTicks >= 3) {
                use(player, new BlockHitResult(hitPos, dir, support, false));
                stepPlaced = true;
                stepTicks = 0;
            }
            return;
        }
        if (!PlacementPlanner.isSupport(level.getBlockState(under))) {
            if (++stepTicks > 10) {
                retryOrSkip("bridge block never appeared");
            }
            input.sneak = true;
            return;
        }
        if (level.getBlockState(under).is(Blocks.DIRT) && !bp.blocks.containsKey(under)) {
            scaffolds.add(under.immutable());
        }
        walkStep(level, player, to, true);
    }

    private static boolean holdScaffold(LocalPlayer player) {
        if (player.getMainHandItem().is(SCAFFOLD_ITEM)) {
            return true;
        }
        int slot = findSlot(player.getInventory(), SCAFFOLD_ITEM);
        if (slot < 0) {
            return false;
        }
        player.getInventory().selected = moveToHotbar(player, slot);
        return true;
    }

    private static void use(LocalPlayer player, BlockHitResult hit) {
        InteractionResult result = MC.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction() && result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static boolean stillPending(ClientLevel level, Task t) {
        if (t.scaffold()) {
            return t.dig() ? !GhostBlocks.isEmpty(level.getBlockState(t.pos())) : GhostBlocks.isEmpty(level.getBlockState(t.pos()));
        }
        return classify(level, t.pos(), t.target()) != null;
    }

    /** Stop drifting, then line up the action. */
    private static void settle(ClientLevel level, LocalPlayer player) {
        phaseTicks++;
        Vec3 v = player.getDeltaMovement();
        boolean still = Math.abs(v.x) < 0.03 && Math.abs(v.z) < 0.03 && (player.onGround() || player.isInWater());
        if (!still && phaseTicks < 15) {
            input.sneak = true;
            return;
        }
        Task t = task;
        if (t == null || !stillPending(level, t)) {
            setPhase(Phase.PLAN);
            return;
        }
        double r = reach(player);
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(t.pos())) > r + 0.5) {
            retryOrSkip("arrived but target out of reach");
            return;
        }

        if (t.dig()) {
            selectBestTool(player, level.getBlockState(t.pos()));
            Vec3 eye = player.getEyePosition();
            breakFace = Direction.getNearest(eye.x - (t.pos().getX() + 0.5), eye.y - (t.pos().getY() + 0.5), eye.z - (t.pos().getZ() + 0.5));
            lookAt(player, Vec3.atCenterOf(t.pos()).add(Vec3.atLowerCornerOf(breakFace.getNormal()).scale(0.5)));
            MC.gameMode.startDestroyBlock(t.pos(), breakFace);
            player.swing(InteractionHand.MAIN_HAND);
            setPhase(Phase.BREAK);
            return;
        }

        Item item = t.target().getBlock().asItem();
        int slot = findSlot(player.getInventory(), item);
        if (slot < 0) {
            skip("item:" + item.getDescription().getString());
            return;
        }
        int hotbar = moveToHotbar(player, slot);
        player.getInventory().selected = hotbar;
        ItemStack stack = player.getInventory().getItem(hotbar);
        placePlan = PlacementPlanner.plan(level, player, t.pos(), t.target(), stack, r);
        if (placePlan == null) {
            retryOrSkip("no valid click found to place it with the right orientation");
            return;
        }
        player.setYRot(placePlan.yaw());
        player.setYHeadRot(placePlan.yaw());
        player.setXRot(placePlan.pitch());
        input.sneak = true;
        setPhase(Phase.PLACE);
    }

    private static void place(ClientLevel level, LocalPlayer player) {
        Task t = task;
        PlacementPlanner.Plan plan = placePlan;
        if (t == null || plan == null) {
            setPhase(Phase.PLAN);
            return;
        }
        // Hold rotation + sneak for a couple of ticks so the server has them before the click.
        input.sneak = true;
        player.setYRot(plan.yaw());
        player.setYHeadRot(plan.yaw());
        player.setXRot(plan.pitch());
        if (++phaseTicks < 3) {
            return;
        }
        if (!player.getMainHandItem().is(t.target().getBlock().asItem())) {
            setPhase(Phase.SETTLE);
            return;
        }
        use(player, plan.hit());
        setPhase(Phase.VERIFY);
    }

    private static void verify(ClientLevel level, LocalPlayer player) {
        Task t = task;
        if (t == null) {
            setPhase(Phase.PLAN);
            return;
        }
        input.sneak = phaseTicks < 2;
        // Wait for the server to confirm (or roll back) the placement before judging it.
        if (++phaseTicks < 8) {
            return;
        }
        if (level.getBlockState(t.pos()).is(t.target().getBlock())) {
            if (t.scaffold()) {
                scaffolds.add(t.pos());
            }
            done();
        } else {
            retryOrSkip("clicked but the block did not appear (server rejected?)");
        }
    }

    private static void breakBlock(ClientLevel level, LocalPlayer player) {
        Task t = task;
        if (t == null) {
            setPhase(Phase.PLAN);
            return;
        }
        if (GhostBlocks.isEmpty(level.getBlockState(t.pos()))) {
            if (t.scaffold()) {
                scaffolds.remove(t.pos());
            }
            done();
            return;
        }
        if (++phaseTicks > BREAK_TIMEOUT) {
            MC.gameMode.stopDestroyBlock();
            retryOrSkip("mining timed out");
            return;
        }
        lookAt(player, Vec3.atCenterOf(t.pos()).add(Vec3.atLowerCornerOf(breakFace.getNormal()).scale(0.5)));
        if (!MC.gameMode.isDestroying()) {
            MC.gameMode.startDestroyBlock(t.pos(), breakFace);
        } else {
            MC.gameMode.continueDestroyBlock(t.pos(), breakFace);
        }
        player.swing(InteractionHand.MAIN_HAND);
    }

    private static void done() {
        retries = 0;
        progressSinceRetryRound = true;
        task = null;
        setPhase(Phase.PLAN);
    }

    private static void retryOrSkip(String why) {
        if (++retries >= MAX_RETRIES) {
            skip(why);
        } else {
            if (task != null) {
                LOG.info("[auto-build] retry {}/{} for {} at {}: {}", retries, MAX_RETRIES,
                        task.target().getBlock().getName().getString(), task.pos().toShortString(), why);
            }
            pendingTask = task; // retry the same job, don't wander off to another
            setPhase(Phase.PLAN);
        }
    }

    private static void markSkipped(BlockPos pos, BlockState target, String why) {
        skipped.put(pos.immutable(), why);
        skippedBlocks.put(pos.immutable(), target);
        LOG.info("[auto-build] skipped {} at {}: {}", target.getBlock().getName().getString(), pos.toShortString(), why);
    }

    private static void skip(String why) {
        if (task != null) {
            markSkipped(task.pos(), task.target(), why);
            if (task.scaffold() && !task.dig()) {
                supportQueue.clear();
            }
        }
        retries = 0;
        task = null;
        setPhase(Phase.PLAN);
    }

    // ------------------------------------------------------------------ inventory

    private static int findSlot(Inventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /** Brings an inventory slot into the hotbar (vanilla swap click) and returns the hotbar index. */
    private static int moveToHotbar(LocalPlayer player, int slot) {
        if (slot < 9) {
            return slot;
        }
        Inventory inv = player.getInventory();
        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            target = 8;
        }
        // Inventory slots 9..35 have the same index in the player's inventory menu.
        MC.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, target, ClickType.SWAP, player);
        return target;
    }

    private static void selectBestTool(LocalPlayer player, BlockState state) {
        Inventory inv = player.getInventory();
        int best = -1;
        float bestSpeed = 1.0F;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best >= 0) {
            inv.selected = moveToHotbar(player, best);
        }
    }

    // ------------------------------------------------------------------ rendering (for watching)

    /** Yellow box on the current job, brown boxes on scaffolding. Called from the ghost renderer. */
    public static void renderDebug(PoseStack pose, MultiBufferSource buffers) {
        if (!running) {
            return;
        }
        if (task != null) {
            GhostRenderer.box(pose, buffers, task.pos(), 0.01, 1.0F, 0.9F, 0.2F, 1.0F);
        }
        for (BlockPos s : scaffolds) {
            GhostRenderer.box(pose, buffers, s, 0.006, 0.6F, 0.4F, 0.2F, 0.8F);
        }
    }

    // ------------------------------------------------------------------ rotation helpers

    private static void lookAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
    }

    private static float approach(float cur, float target, float maxStep) {
        float diff = Mth.wrapDegrees(target - cur);
        return cur + Mth.clamp(diff, -maxStep, maxStep);
    }
}
