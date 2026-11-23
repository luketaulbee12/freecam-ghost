package net.xolt.freecam.forge.ghost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.xolt.freecam.Freecam;
import net.xolt.freecam.config.ModBindings;
import net.xolt.freecam.forge.ghost.builder.AutoBuilder;
import net.xolt.freecam.forge.mixins.BlockItemAccessor;
import net.xolt.freecam.util.FreeCamera;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-side "ghost" building layer.
 * <p>
 * While freecam is on, ghost edits are written straight into the client's copy of the world
 * (nothing is sent to the server), so you see exactly what the build would look like.
 * When freecam turns off every touched block is restored. Pressing Save commits the edits to a
 * persistent blueprint that is drawn as a translucent overlay (placements) and red outlines (digs).
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GhostBlocks {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int FLAGS_BULK = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int FLAGS_NOW = FLAGS_BULK | Block.UPDATE_IMMEDIATE;
    private static final int BULK_THRESHOLD = 64;
    private static final int REPEAT_TICKS = 4;
    static final double REACH = 64;

    // Working copy while freecam is on (edits the active blueprint).
    private static Blueprints.Blueprint sessionBlueprint;
    private static boolean sessionActive;
    private static ClientLevel sessionLevel;
    static final Map<BlockPos, BlockState> session = new HashMap<>();
    private static final Map<BlockPos, Original> originals = new HashMap<>();
    private static final Map<BlockPos, BlockState> written = new HashMap<>();
    private static final Deque<List<Change>> undo = new ArrayDeque<>();
    private static final Deque<List<Change>> redo = new ArrayDeque<>();
    private static boolean dirty;

    static boolean visible = true;
    private static int useDelay;
    private static int attackDelay;
    private static int tickCounter;

    private record Original(BlockState state, @Nullable BlockEntity blockEntity) {}

    private record Change(BlockPos pos, @Nullable BlockState before, @Nullable BlockState after) {}

    private GhostBlocks() {}

    // ------------------------------------------------------------------ state queries

    /** True while freecam is on and ghost editing should replace normal interaction. */
    public static boolean isEditing() {
        return sessionActive && Freecam.isEnabled() && !Freecam.isPlayerControlEnabled();
    }

    public static boolean isSessionActive() {
        return sessionActive;
    }

    /** The blueprint being edited in the current freecam session, if any. */
    @Nullable
    public static Blueprints.Blueprint sessionBlueprint() {
        return sessionActive ? sessionBlueprint : null;
    }

    /** Air, or a fluid that a block can simply be placed into. */
    public static boolean isEmpty(BlockState s) {
        return s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
    }

    static boolean isDirty() {
        return dirty;
    }

    /** The real (server) block at {@code pos}, looking past any ghost we wrote there. */
    static BlockState realState(ClientLevel level, BlockPos pos) {
        Original o = originals.get(pos);
        return o != null ? o.state() : level.getBlockState(pos);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Pre event) {
        ClientLevel level = MC.level;
        if (level == null || MC.player == null) {
            if (sessionActive) {
                dropSession();
            }
            return;
        }

        if (Blueprints.ensureLoaded(level) && sessionActive) {
            dropSession();
        }
        if (sessionActive && sessionLevel != level) {
            dropSession();
        }

        boolean freecam = Freecam.isEnabled();
        // While the auto-builder runs, freecam is just a spectator camera (no ghost editing).
        if (freecam && !sessionActive && !AutoBuilder.isRunning()) {
            beginSession(level);
        } else if (!freecam && sessionActive) {
            endSession(level);
        }

        tickCounter++;
        if (sessionActive) {
            if (tickCounter % 5 == 0) {
                reconcile(level);
            }
            if (isEditing() && MC.screen == null) {
                handleEditInput(level);
            }
        } else if (tickCounter % 10 == 0) {
            autoClear(level);
        }
        handleKeys(level);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        dropSession();
        Blueprints.unload();
    }

    /** Stops held use/attack/pick from reaching the real game while ghost editing. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (isEditing()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /** Mouse wheel scrolls the ghost hotbar instead of the real one. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (isEditing() && MC.screen == null && event.getScrollDeltaY() != 0) {
            if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
                GhostFlight.adjustSpeed((int) Math.signum(event.getScrollDeltaY()));
                event.setCanceled(true);
                return;
            }
            GhostHotbar.select(GhostHotbar.selected() - (int) Math.signum(event.getScrollDeltaY()));
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ session lifecycle

    private static void beginSession(ClientLevel level) {
        sessionActive = true;
        sessionLevel = level;
        sessionBlueprint = Blueprints.active();
        session.clear();
        session.putAll(sessionBlueprint.blocks);
        originals.clear();
        written.clear();
        undo.clear();
        redo.clear();
        dirty = false;
        for (Map.Entry<BlockPos, BlockState> e : session.entrySet()) {
            apply(level, e.getKey(), e.getValue(), FLAGS_BULK);
        }
    }

    private static void endSession(ClientLevel level) {
        for (BlockPos pos : new ArrayList<>(originals.keySet())) {
            restore(level, pos, FLAGS_BULK);
        }
        if (dirty && MC.player != null) {
            MC.player.displayClientMessage(Component.translatable("msg.freecam.ghostDiscarded"), true);
        }
        dropSession();
    }

    private static void dropSession() {
        sessionActive = false;
        sessionLevel = null;
        session.clear();
        originals.clear();
        written.clear();
        undo.clear();
        redo.clear();
        dirty = false;
    }

    // ------------------------------------------------------------------ world mutation

    private static void apply(ClientLevel level, BlockPos pos, BlockState target, int flags) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState cur = level.getBlockState(pos);
        if (!originals.containsKey(pos) || cur != written.get(pos)) {
            // Either first touch, or the server changed the block under us: that's the new real state.
            BlockEntity be = cur.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            originals.put(pos.immutable(), new Original(cur, be));
        }
        if (cur != target) {
            level.setBlock(pos, target, flags);
        }
        written.put(pos.immutable(), level.getBlockState(pos));
    }

    private static void restore(ClientLevel level, BlockPos pos, int flags) {
        Original o = originals.remove(pos);
        BlockState ours = written.remove(pos);
        if (o == null || !level.isLoaded(pos)) {
            return;
        }
        BlockState cur = level.getBlockState(pos);
        if (ours != null && cur != ours) {
            return; // server already replaced it with something real
        }
        level.setBlock(pos, o.state(), flags);
        if (o.blockEntity() != null && level.getBlockState(pos).hasBlockEntity()) {
            o.blockEntity().clearRemoved();
            level.setBlockEntity(o.blockEntity());
        }
    }

    /** Re-applies ghosts the server overwrote or that came back with a reloaded chunk. */
    private static void reconcile(ClientLevel level) {
        for (Map.Entry<BlockPos, BlockState> e : session.entrySet()) {
            BlockPos pos = e.getKey();
            if (level.isLoaded(pos) && level.getBlockState(pos) != e.getValue()) {
                apply(level, pos, e.getValue(), FLAGS_BULK);
            }
        }
    }

    private static void setTarget(ClientLevel level, BlockPos pos, @Nullable BlockState target, List<Change> log, int flags) {
        pos = pos.immutable();
        BlockState before = session.get(pos);
        if (Objects.equals(before, target)) {
            return;
        }
        if (target == null) {
            session.remove(pos);
            restore(level, pos, flags);
        } else {
            session.put(pos, target);
            apply(level, pos, target, flags);
        }
        log.add(new Change(pos, before, target));
    }

    private static void commit(List<Change> log) {
        if (log.isEmpty()) {
            return;
        }
        undo.push(log);
        if (undo.size() > 500) {
            undo.removeLast();
        }
        redo.clear();
        dirty = true;
    }

    // ------------------------------------------------------------------ outside freecam

    /** Drops blueprint entries the real world now satisfies. */
    private static void autoClear(ClientLevel level) {
        for (Blueprints.Blueprint bp : Blueprints.all()) {
            boolean changed = false;
            Iterator<Map.Entry<BlockPos, BlockState>> it = bp.blocks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, BlockState> e = it.next();
                if (!level.isLoaded(e.getKey())) {
                    continue;
                }
                if (isSatisfied(level.getBlockState(e.getKey()), e.getValue())) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                Blueprints.save(bp);
            }
        }
    }

    /** True if the real block already matches what the blueprint wants there. */
    public static boolean isSatisfied(BlockState real, BlockState target) {
        return target.isAir() ? isEmpty(real) : real.getBlock() == target.getBlock();
    }

    // ------------------------------------------------------------------ input

    private static void handleEditInput(ClientLevel level) {
        Options o = MC.options;
        boolean freecamComboHeld = ModBindings.KEY_TOGGLE.get().isDown() || ModBindings.KEY_TRIPOD_RESET.get().isDown();
        if (!freecamComboHeld) {
            for (int i = 0; i < GhostHotbar.SIZE; i++) {
                while (o.keyHotbarSlots[i].consumeClick()) {
                    GhostHotbar.select(i);
                }
            }
        }

        boolean openPicker = false;
        while (o.keyInventory.consumeClick()) {
            openPicker = true;
        }

        boolean useClicked = false;
        while (o.keyUse.consumeClick()) {
            useClicked = true;
        }
        boolean attackClicked = false;
        while (o.keyAttack.consumeClick()) {
            attackClicked = true;
        }
        boolean pickClicked = false;
        while (o.keyPickItem.consumeClick()) {
            pickClicked = true;
        }

        if (openPicker) {
            MC.setScreen(new GhostPickerScreen());
            return;
        }

        if (useClicked) {
            placeAtCrosshair(level);
            useDelay = REPEAT_TICKS;
        } else if (o.keyUse.isDown()) {
            if (--useDelay <= 0) {
                placeAtCrosshair(level);
                useDelay = REPEAT_TICKS;
            }
        }

        if (attackClicked) {
            removeAtCrosshair(level);
            attackDelay = REPEAT_TICKS;
        } else if (o.keyAttack.isDown()) {
            if (--attackDelay <= 0) {
                removeAtCrosshair(level);
                attackDelay = REPEAT_TICKS;
            }
        }

        if (pickClicked) {
            pickAtCrosshair(level);
        }
    }

    private static void handleKeys(ClientLevel level) {
        while (GhostKeys.FLIGHT_MODE.consumeClick()) {
            if (Freecam.isEnabled()) {
                GhostFlight.toggleMode();
            }
        }
        while (GhostKeys.AUTO_BUILD.consumeClick()) {
            if (!sessionActive) {
                AutoBuilder.toggle(Blueprints.activeOrNull());
            }
        }
        while (GhostKeys.BUILD_MODE.consumeClick()) {
            AutoBuilder.toggleMode();
        }
        while (GhostKeys.OPEN_MENU.consumeClick()) {
            if (MC.screen == null) {
                MC.setScreen(new BlueprintListScreen());
            }
        }
        while (GhostKeys.TOGGLE_VISIBLE.consumeClick()) {
            visible = !visible;
            message(visible ? "msg.freecam.ghostShown" : "msg.freecam.ghostHidden");
        }
        boolean save = false, undoKey = false, redoKey = false, clear = false;
        while (GhostKeys.SAVE.consumeClick()) save = true;
        while (GhostKeys.UNDO.consumeClick()) undoKey = true;
        while (GhostKeys.REDO.consumeClick()) redoKey = true;
        while (GhostKeys.CLEAR.consumeClick()) clear = true;
        if (!sessionActive) {
            return;
        }
        if (undoKey) {
            undoRedo(level, undo, redo, true);
        }
        if (redoKey) {
            undoRedo(level, redo, undo, false);
        }
        if (clear) {
            List<Change> log = new ArrayList<>();
            for (BlockPos pos : new ArrayList<>(session.keySet())) {
                setTarget(level, pos, null, log, FLAGS_BULK);
            }
            commit(log);
            message("msg.freecam.ghostCleared");
        }
        if (save) {
            saveSession();
        }
    }

    static void saveSession() {
        if (!sessionActive) {
            return;
        }
        sessionBlueprint.blocks.clear();
        sessionBlueprint.blocks.putAll(session);
        Blueprints.save(sessionBlueprint);
        dirty = false;
        if (MC.player != null) {
            MC.player.displayClientMessage(Component.translatable("msg.freecam.ghostSaved",
                    sessionBlueprint.name(), session.size()), true);
        }
    }

    /** Freecam exit guard: prompt instead of silently discarding unsaved edits. */
    public static boolean allowExit() {
        if (!sessionActive || !dirty) {
            return true;
        }
        MC.setScreen(new GhostExitScreen());
        return false;
    }

    private static void undoRedo(ClientLevel level, Deque<List<Change>> from, Deque<List<Change>> to, boolean isUndo) {
        List<Change> group = from.poll();
        if (group == null) {
            return;
        }
        int flags = group.size() > BULK_THRESHOLD ? FLAGS_BULK : FLAGS_NOW;
        List<Change> ignored = new ArrayList<>();
        List<Change> ordered = new ArrayList<>(group);
        if (isUndo) {
            Collections.reverse(ordered);
        }
        for (Change c : ordered) {
            setTarget(level, c.pos(), isUndo ? c.before() : c.after(), ignored, flags);
        }
        to.push(group);
        dirty = true;
    }

    // ------------------------------------------------------------------ actions

    @Nullable
    static BlockHitResult crosshairTarget(float partialTick) {
        FreeCamera cam = Freecam.getFreeCamera();
        if (cam == null) {
            return null;
        }
        HitResult hit = cam.pick(REACH, partialTick, false);
        return hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK ? bhr : null;
    }

    private static void placeAtCrosshair(ClientLevel level) {
        FreeCamera cam = Freecam.getFreeCamera();
        BlockHitResult hit = crosshairTarget(1.0F);
        ItemStack stack = GhostHotbar.selectedStack();
        if (cam == null || hit == null || !(stack.getItem() instanceof BlockItem item)) {
            return;
        }

        BlockPlaceContext ctx = item.updatePlacementContext(new BlockPlaceContext(cam, InteractionHand.MAIN_HAND, stack, hit));
        if (ctx == null || !ctx.canPlace()) {
            return;
        }
        BlockPos pos = ctx.getClickedPos();
        if (level.isOutsideBuildHeight(pos)) {
            return;
        }

        BlockState state = null;
        try {
            state = ((BlockItemAccessor) item).freecam$getPlacementState(ctx);
        } catch (Throwable ignored) {
        }
        if (state == null) {
            // Placement rules said no (e.g. unsupported torch, or you're standing there) - place anyway, it's a ghost.
            try {
                state = item.getBlock().getStateForPlacement(ctx);
            } catch (Throwable ignored) {
            }
        }
        if (state == null) {
            state = item.getBlock().defaultBlockState();
        }

        List<Change> log = new ArrayList<>();
        setTarget(level, pos, state, log, FLAGS_NOW);
        BlockPos partner = partnerPos(state, pos);
        if (partner != null) {
            setTarget(level, partner, partnerState(state), log, FLAGS_NOW);
        }
        updateNeighbourShapes(level, log);
        commit(log);

        SoundType sound = state.getSoundType();
        level.playLocalSound(pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
    }

    private static void removeAtCrosshair(ClientLevel level) {
        BlockHitResult hit = crosshairTarget(1.0F);
        if (hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState shown = level.getBlockState(pos);
        if (shown.isAir()) {
            return;
        }
        List<Change> log = new ArrayList<>();
        removeOne(level, pos, log);
        BlockPos partner = partnerPos(shown, pos);
        if (partner != null && level.getBlockState(partner).is(shown.getBlock())) {
            removeOne(level, partner, log);
        }
        updateNeighbourShapes(level, log);
        commit(log);

        SoundType sound = shown.getSoundType();
        level.playLocalSound(pos, sound.getBreakSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
    }

    /**
     * Re-runs vanilla shape updates on blocks next to what just changed, so panes, fences,
     * walls and stairs connect to ghosts. Only shows in the preview; real blocks are restored on exit.
     */
    private static void updateNeighbourShapes(ClientLevel level, List<Change> log) {
        List<BlockPos> changed = new ArrayList<>();
        for (Change c : log) {
            changed.add(c.pos());
        }
        for (BlockPos pos : changed) {
            BlockState placed = level.getBlockState(pos);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos np = pos.relative(dir);
                if (changed.contains(np) || !level.isLoaded(np)) {
                    continue;
                }
                BlockState ns = level.getBlockState(np);
                if (ns.isAir()) {
                    continue;
                }
                BlockState upd;
                try {
                    upd = ns.updateShape(dir.getOpposite(), placed, level, np, pos);
                } catch (Throwable t) {
                    continue;
                }
                if (upd != ns && !upd.isAir() && upd.is(ns.getBlock())) {
                    setTarget(level, np, upd, log, FLAGS_NOW);
                }
            }
            // and the changed block itself against its (possibly ghost) neighbours
            if (!placed.isAir()) {
                BlockState self = placed;
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos np = pos.relative(dir);
                    try {
                        BlockState upd = self.updateShape(dir, level.getBlockState(np), level, pos, np);
                        if (!upd.isAir() && upd.is(self.getBlock())) {
                            self = upd;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (self != placed) {
                    setTarget(level, pos, self, log, FLAGS_NOW);
                }
            }
        }
    }

    private static void removeOne(ClientLevel level, BlockPos pos, List<Change> log) {
        if (session.containsKey(pos) && !session.get(pos).isAir() && realState(level, pos).isAir()) {
            setTarget(level, pos, null, log, FLAGS_NOW); // a ghost on empty space: just remove it
        } else {
            setTarget(level, pos, Blocks.AIR.defaultBlockState(), log, FLAGS_NOW); // ghost-dig a real block
        }
    }

    private static void pickAtCrosshair(ClientLevel level) {
        BlockHitResult hit = crosshairTarget(1.0F);
        if (hit == null) {
            return;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        ItemStack stack = state.getBlock().getCloneItemStack(level, hit.getBlockPos(), state);
        if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
            GhostHotbar.pick(stack);
        }
    }

    // ------------------------------------------------------------------ multi-block helpers

    @Nullable
    private static BlockPos partnerPos(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
        }
        return null;
    }

    private static BlockState partnerState(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        }
        BedPart part = state.getValue(BlockStateProperties.BED_PART);
        return state.setValue(BlockStateProperties.BED_PART, part == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
    }

    private static void message(String key) {
        if (MC.player != null) {
            MC.player.displayClientMessage(Component.translatable(key), true);
        }
    }
}
