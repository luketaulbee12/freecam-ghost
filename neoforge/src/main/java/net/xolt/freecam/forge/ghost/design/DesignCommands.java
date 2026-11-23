package net.xolt.freecam.forge.ghost.design;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.xolt.freecam.forge.ghost.Blueprints;
import net.xolt.freecam.forge.ghost.GhostBlocks;
import net.xolt.freecam.forge.ghost.GhostRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * Step-1 test harness for the build designer (the in-game UI comes in step 3):
 * <pre>
 *   /design pos1            corner at the block you're looking at (works in freecam)
 *   /design pos2            opposite corner
 *   /design front &lt;dir&gt;     north/south/east/west, or auto (faces you)
 *   /design build &lt;style&gt;   generate a blueprint for the site
 *   /design styles          list styles
 *   /design clear           forget the site
 * </pre>
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class DesignCommands {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int MAX_SIDE = 64;

    @Nullable private static BlockPos pos1, pos2;
    @Nullable private static Direction frontOverride;

    private DesignCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("design")
                .then(Commands.literal("pos1").executes(c -> corner(c, 1)))
                .then(Commands.literal("pos2").executes(c -> corner(c, 2)))
                .then(Commands.literal("front")
                        .then(Commands.argument("dir", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"auto", "north", "south", "east", "west"}, b))
                                .executes(DesignCommands::front)))
                .then(Commands.literal("styles").executes(DesignCommands::styles))
                .then(Commands.literal("clear").executes(c -> {
                    pos1 = pos2 = null;
                    frontOverride = null;
                    say(Component.literal("Design site cleared."));
                    return 1;
                }))
                .then(Commands.literal("build")
                        .then(Commands.argument("style", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Style.PRESETS.keySet(), b))
                                .executes(DesignCommands::build))));
    }

    private static int corner(CommandContext<CommandSourceStack> c, int which) {
        BlockPos p = crosshairBlock();
        if (p == null) {
            say(Component.literal("Look at a block first.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (which == 1) {
            pos1 = p;
        } else {
            pos2 = p;
        }
        String size = pos1 != null && pos2 != null
                ? "  (site " + (Math.abs(pos1.getX() - pos2.getX()) + 1) + " x " + (Math.abs(pos1.getZ() - pos2.getZ()) + 1) + ")"
                : "";
        say(Component.literal("Corner " + which + " set to " + p.toShortString() + size));
        return 1;
    }

    private static int front(CommandContext<CommandSourceStack> c) {
        String d = StringArgumentType.getString(c, "dir");
        frontOverride = d.equals("auto") ? null : Direction.byName(d);
        if (!d.equals("auto") && (frontOverride == null || frontOverride.getAxis().isVertical())) {
            frontOverride = null;
            say(Component.literal("Use north, south, east, west or auto.").withStyle(ChatFormatting.RED));
            return 0;
        }
        say(Component.literal("Front: " + d));
        return 1;
    }

    private static int styles(CommandContext<CommandSourceStack> c) {
        for (Style s : Style.PRESETS.values()) {
            say(Component.literal(" - " + s.id() + ": " + s.displayName() + " (" + s.floors() + " floor"
                    + (s.floors() > 1 ? "s" : "") + ", " + s.roof().name().toLowerCase() + " roof)"));
        }
        return 1;
    }

    private static int build(CommandContext<CommandSourceStack> c) {
        if (MC.level == null || MC.player == null) {
            return 0;
        }
        if (pos1 == null || pos2 == null) {
            say(Component.literal("Set both corners first: look at the ground and run /design pos1, then /design pos2.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        int w = Math.abs(pos1.getX() - pos2.getX()) + 1, d = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
        if (w < 5 || d < 5 || w > MAX_SIDE || d > MAX_SIDE) {
            say(Component.literal("Site must be between 5x5 and " + MAX_SIDE + "x" + MAX_SIDE + " (it's " + w + "x" + d + ").")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        Style style = Style.PRESETS.get(StringArgumentType.getString(c, "style"));
        if (style == null) {
            say(Component.literal("Unknown style. Try /design styles").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (GhostBlocks.isSessionActive()) {
            say(Component.literal("Leave freecam editing first (the world is showing ghost edits).").withStyle(ChatFormatting.RED));
            return 0;
        }

        Site site = Site.scan(MC.level, pos1, pos2);
        Direction front = frontOverride != null ? frontOverride : facePlayer(site);
        Designer.Result result = Designer.design(MC.level, site, style, front);

        Blueprints.Blueprint bp = Blueprints.create();
        String name = style.displayName();
        int n = 2;
        while (Blueprints.find(name) != null) {
            name = style.displayName() + " " + n++;
        }
        Blueprints.rename(bp, name);
        bp.blocks.putAll(result.blocks());
        Blueprints.save(bp);
        Blueprints.setActive(bp);

        say(Component.literal("Designed \"" + name + "\": " + result.summary() + ". Front faces " + front.getName() + ".")
                .withStyle(ChatFormatting.GREEN));
        say(Component.literal("It's shown as ghosts now. F4 to look/edit, J for materials, Ctrl+B to auto-build.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** Front faces whichever side of the site you're standing on. */
    private static Direction facePlayer(Site site) {
        double cx = (site.x0 + site.x1 + 1) / 2.0, cz = (site.z0 + site.z1 + 1) / 2.0;
        double dx = MC.player.getX() - cx, dz = MC.player.getZ() - cz;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    @Nullable
    private static BlockPos crosshairBlock() {
        Entity cam = MC.getCameraEntity();
        if (cam == null) {
            return null;
        }
        HitResult hit = cam.pick(160, 1.0F, false);
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
    }

    private static void say(Component c) {
        if (MC.player != null) {
            MC.player.displayClientMessage(c, false);
        }
    }

    /** Yellow outline of the chosen site. Called from the ghost renderer. */
    public static void renderSelection(PoseStack pose, MultiBufferSource buffers) {
        if (pos1 == null) {
            return;
        }
        BlockPos b = pos2 != null ? pos2 : pos1;
        int minY = Math.min(pos1.getY(), b.getY()), maxY = Math.max(pos1.getY(), b.getY());
        AABB box = new AABB(Math.min(pos1.getX(), b.getX()), minY + 1, Math.min(pos1.getZ(), b.getZ()),
                Math.max(pos1.getX(), b.getX()) + 1, maxY + 2, Math.max(pos1.getZ(), b.getZ()) + 1);
        GhostRenderer.aabb(pose, buffers, box, 1.0F, 0.85F, 0.2F, 1.0F);
    }
}
