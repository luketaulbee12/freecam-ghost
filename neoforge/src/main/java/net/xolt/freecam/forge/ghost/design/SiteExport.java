package net.xolt.freecam.forge.ghost.design;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Saves the terrain around a design site for the Build Studio (works on any server: it's just what the
 * client can see). Format, gzipped JSON:
 * <pre>
 * { "name", "dimension", "origin": [x,y,z], "size": [w,h,d],
 *   "site": [x0,z0,x1,z1], "palette": ["minecraft:air", "minecraft:oak_stairs[facing=north,...]", ...],
 *   "blocks": [palette index per block, x-major then z then y] }
 * </pre>
 */
final class SiteExport {
    static final int MARGIN = 12;
    static final int MAX_HEIGHT = 128;

    private SiteExport() {}

    static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("freecam_ghosts").resolve("exports");
    }

    /** Returns the file written. */
    static Path export(ClientLevel level, BlockPos a, BlockPos b, String name) throws Exception {
        int sx0 = Math.min(a.getX(), b.getX()), sx1 = Math.max(a.getX(), b.getX());
        int sz0 = Math.min(a.getZ(), b.getZ()), sz1 = Math.max(a.getZ(), b.getZ());
        int x0 = sx0 - MARGIN, x1 = sx1 + MARGIN, z0 = sz0 - MARGIN, z1 = sz1 + MARGIN;

        // Vertical range: a bit below the lowest ground to just above the tallest thing (trees).
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                high = Math.max(high, top);
                int g = top;
                while (g > level.getMinBuildHeight() && !Site.isGround(level.getBlockState(new BlockPos(x, g, z)))) {
                    g--;
                }
                low = Math.min(low, g);
            }
        }
        int y0 = Math.max(level.getMinBuildHeight(), low - 8);
        int y1 = Math.min(level.getMaxBuildHeight() - 1, Math.max(high + 4, y0 + 24));
        if (y1 - y0 + 1 > MAX_HEIGHT) {
            y1 = y0 + MAX_HEIGHT - 1;
        }

        int w = x1 - x0 + 1, h = y1 - y0 + 1, d = z1 - z0 + 1;
        Map<BlockState, Integer> index = new HashMap<>();
        JsonArray palette = new JsonArray();
        int[] data = new int[w * h * d];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    Integer idx = index.get(s);
                    if (idx == null) {
                        idx = palette.size();
                        index.put(s, idx);
                        palette.add(BlockStateParser.serialize(s));
                    }
                    data[i++] = idx;
                }
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("dimension", level.dimension().location().toString());
        root.add("origin", arr(x0, y0, z0));
        root.add("size", arr(w, h, d));
        root.add("site", arr(sx0, sz0, sx1, sz1));
        root.add("palette", palette);
        JsonArray blocks = new JsonArray(data.length);
        for (int v : data) {
            blocks.add(v);
        }
        root.add("blocks", blocks);

        Files.createDirectories(dir());
        Path file = dir().resolve(GhostStorageAccess.sanitize(name) + ".json.gz");
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            out.write(root.toString());
        }
        return file;
    }

    private static JsonArray arr(int... v) {
        JsonArray a = new JsonArray();
        for (int x : v) {
            a.add(x);
        }
        return a;
    }

    /** Small local copy of the filename sanitiser (GhostStorage is package-private elsewhere). */
    static final class GhostStorageAccess {
        static String sanitize(String s) {
            return s.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
    }
}
