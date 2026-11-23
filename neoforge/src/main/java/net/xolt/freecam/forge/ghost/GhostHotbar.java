package net.xolt.freecam.forge.ghost;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The 9-slot "creative" hotbar used while in freecam. Separate from the real inventory. */
public final class GhostHotbar {
    public static final int SIZE = 9;
    private static final ItemStack[] SLOTS = new ItemStack[SIZE];
    private static int selected = 0;
    private static boolean loaded = false;

    static {
        Arrays.fill(SLOTS, ItemStack.EMPTY);
    }

    private GhostHotbar() {}

    public static ItemStack get(int slot) {
        ensureLoaded();
        return SLOTS[slot];
    }

    public static ItemStack selectedStack() {
        return get(selected);
    }

    public static int selected() {
        return selected;
    }

    public static void select(int slot) {
        selected = Math.floorMod(slot, SIZE);
    }

    public static void set(int slot, ItemStack stack) {
        ensureLoaded();
        SLOTS[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        save();
    }

    /** Vanilla creative pick-block behaviour: select if present, else fill an empty slot, else replace selected. */
    public static void pick(ItemStack stack) {
        ensureLoaded();
        for (int i = 0; i < SIZE; i++) {
            if (!SLOTS[i].isEmpty() && SLOTS[i].is(stack.getItem())) {
                selected = i;
                return;
            }
        }
        if (!SLOTS[selected].isEmpty()) {
            for (int i = 0; i < SIZE; i++) {
                if (SLOTS[i].isEmpty()) {
                    selected = i;
                    break;
                }
            }
        }
        set(selected, stack);
    }

    private static Path file() {
        return GhostStorage.root().resolve("hotbar.txt");
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) {
                return;
            }
            List<String> lines = Files.readAllLines(f);
            for (int i = 0; i < SIZE && i < lines.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(lines.get(i).trim());
                Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
                SLOTS[i] = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (ItemStack s : SLOTS) {
                lines.add(s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            }
            Files.createDirectories(file().getParent());
            Files.write(file(), lines);
        } catch (Exception ignored) {
        }
    }
}
