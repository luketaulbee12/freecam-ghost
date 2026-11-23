package net.xolt.freecam.forge.ghost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paths and NBT encoding for ghost blueprints ({@code .minecraft/freecam_ghosts/...}). */
final class GhostStorage {
    private GhostStorage() {}

    static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("freecam_ghosts");
    }

    static String worldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            Path p = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "sp_" + sanitize(p.getFileName().toString());
        }
        ServerData server = mc.getCurrentServer();
        return "mp_" + sanitize(server != null ? server.ip : "unknown");
    }

    static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static Map<BlockPos, BlockState> decode(CompoundTag root) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i)));
        }
        int[] data = root.getIntArray("blocks");
        for (int i = 0; i + 3 < data.length; i += 4) {
            int idx = data[i + 3];
            if (idx >= 0 && idx < palette.size()) {
                out.put(new BlockPos(data[i], data[i + 1], data[i + 2]), palette.get(idx));
            }
        }
        return out;
    }

    static CompoundTag encode(Map<BlockPos, BlockState> blocks) {
        Map<BlockState, Integer> index = new HashMap<>();
        ListTag paletteTag = new ListTag();
        int[] data = new int[blocks.size() * 4];
        int i = 0;
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            Integer idx = index.get(e.getValue());
            if (idx == null) {
                idx = paletteTag.size();
                index.put(e.getValue(), idx);
                paletteTag.add(NbtUtils.writeBlockState(e.getValue()));
            }
            BlockPos p = e.getKey();
            data[i++] = p.getX();
            data[i++] = p.getY();
            data[i++] = p.getZ();
            data[i++] = idx;
        }
        CompoundTag root = new CompoundTag();
        root.put("palette", paletteTag);
        root.putIntArray("blocks", data);
        return root;
    }
}
