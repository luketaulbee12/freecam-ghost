package net.xolt.freecam.forge.ghost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a blueprint still needs, and (in creative) handing it all to the player. */
public final class BlueprintMaterials {
    private BlueprintMaterials() {}

    /** Items still needed to finish the blueprint (already-built spots don't count). */
    public static Map<Item, Integer> needed(@Nullable ClientLevel level, Blueprints.Blueprint bp) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        boolean editingThis = GhostBlocks.sessionBlueprint() == bp; // world shows ghosts, not reality
        for (Map.Entry<BlockPos, BlockState> e : bp.blocks.entrySet()) {
            BlockState target = e.getValue();
            if (target.isAir() || isSecondHalf(target)) {
                continue;
            }
            if (level != null && !editingThis && level.isLoaded(e.getKey())
                    && GhostBlocks.isSatisfied(level.getBlockState(e.getKey()), target)) {
                continue;
            }
            Item item = target.getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }
            int count = target.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
            out.merge(item, count, Integer::sum);
        }
        return out;
    }

    static boolean isSecondHalf(BlockState s) {
        return (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }

    public static boolean canGive() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.getAbilities().instabuild;
    }

    /**
     * Creative only: puts every block the build still needs into the inventory (topping up what you
     * already carry), plus a stack of dirt for scaffolding. Uses the same creative-inventory packets as
     * middle-click picking, so it works on servers where you're in creative.
     */
    public static void giveAll(Blueprints.Blueprint bp) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || !canGive()) {
            return;
        }
        Map<Item, Integer> need = needed(mc.level, bp);
        Inventory inv = player.getInventory();
        int given = 0;
        Map<Item, Integer> noRoom = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            Item item = e.getKey();
            int missing = e.getValue() - inv.countItem(item);
            while (missing > 0) {
                int slot = emptySlot(inv);
                if (slot < 0) {
                    noRoom.merge(item, missing, Integer::sum);
                    break;
                }
                int n = Math.min(missing, item.getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(item, n);
                inv.setItem(slot, stack.copy());
                // Inventory menu numbering: hotbar 0..8 -> 36..44, main 9..35 -> 9..35.
                mc.gameMode.handleCreativeModeItemAdd(stack, slot < 9 ? 36 + slot : slot);
                missing -= n;
                given += n;
            }
        }
        if (inv.countItem(Items.DIRT) < 32) {
            int slot = emptySlot(inv);
            if (slot >= 0) {
                ItemStack dirt = new ItemStack(Items.DIRT, 64);
                inv.setItem(slot, dirt.copy());
                mc.gameMode.handleCreativeModeItemAdd(dirt, slot < 9 ? 36 + slot : slot);
            }
        }
        player.displayClientMessage(Component.translatable("msg.freecam.gaveMaterials", given, need.size()), false);
        if (!noRoom.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            noRoom.forEach((item, n) -> sb.append(sb.length() > 0 ? ", " : "").append(n).append("x ")
                    .append(item.getDescription().getString()));
            player.displayClientMessage(Component.translatable("msg.freecam.gaveNoRoom", sb.toString()), false);
        }
    }

    /** First empty slot, main inventory first so the hotbar stays as it was. */
    private static int emptySlot(Inventory inv) {
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}
