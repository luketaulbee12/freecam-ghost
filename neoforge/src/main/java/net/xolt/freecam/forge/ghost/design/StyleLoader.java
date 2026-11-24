package net.xolt.freecam.forge.ghost.design;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Styles written as JSON in {@code .minecraft/freecam_ghosts/styles/*.json}. Re-read every time a
 * design is generated, so new or edited styles work without restarting the game.
 * <p>
 * This is the same "style description" the picture-reading step will produce later.
 */
final class StyleLoader {
    /** Problems from the last load, shown by {@code /design styles}. */
    static final List<String> errors = new ArrayList<>();

    private StyleLoader() {}

    static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("freecam_ghosts").resolve("styles");
    }

    /** Built-in presets plus every JSON style (a JSON style with the same id replaces the preset). */
    static Map<String, Style> all() {
        errors.clear();
        Map<String, Style> out = new LinkedHashMap<>(Style.PRESETS);
        Path d = dir();
        try {
            if (!Files.isDirectory(d)) {
                Files.createDirectories(d);
                Files.writeString(d.resolve("example_cottage.json"), EXAMPLE);
            }
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    try {
                        Style s = parse(f);
                        out.put(s.id(), s);
                    } catch (Exception e) {
                        errors.add(f.getFileName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            errors.add("styles folder: " + e.getMessage());
        }
        return out;
    }

    private static Style parse(Path file) throws Exception {
        JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        String stem = file.getFileName().toString().replaceFirst("\\.json$", "");
        String id = str(o, "id", stem).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        JsonObject m = o.getAsJsonObject("materials");
        if (m == null) {
            throw new IllegalArgumentException("missing \"materials\"");
        }
        return new Style(
                id,
                str(o, "name", id),
                clamp(num(o, "floors", 1), 1, 6),
                clamp(num(o, "floorHeight", 4), 3, 7),
                Style.Roof.valueOf(str(o, "roof", "gable").toUpperCase(Locale.ROOT)),
                bool(o, "framed", false),
                clamp(num(o, "windowSpacing", 3), 2, 8),
                Style.Foundation.valueOf(str(o, "foundation", "auto").toUpperCase(Locale.ROOT)),
                bool(o, "porch", false),
                bool(o, "chimney", false),
                Math.max(0, num(o, "maxWidth", 0)),
                Math.max(0, num(o, "maxDepth", 0)),
                mat(m, "foundation"),
                mat(m, "wall"),
                mat(m, "frame"),
                mat(m, "floor"),
                block(m, "roofStairs"),
                block(m, "roofSlab"),
                block(m, "roofFull"),
                block(m, "window"),
                block(m, "door"),
                block(m, "trim"),
                m.has("chimney") ? mat(m, "chimney") : mat(m, "foundation"));
    }

    /** "minecraft:stone" or {"minecraft:stone_bricks": 7, "minecraft:mossy_stone_bricks": 2}. */
    private static Style.Mat mat(JsonObject m, String key) {
        JsonElement e = m.get(key);
        if (e == null) {
            throw new IllegalArgumentException("missing material \"" + key + "\"");
        }
        if (e.isJsonPrimitive()) {
            return Style.Mat.of(lookup(e.getAsString(), key));
        }
        List<Block> blocks = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (Map.Entry<String, JsonElement> w : e.getAsJsonObject().entrySet()) {
            blocks.add(lookup(w.getKey(), key));
            weights.add(Math.max(1, w.getValue().getAsInt()));
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("material \"" + key + "\" is empty");
        }
        return new Style.Mat(blocks, weights);
    }

    private static Block block(JsonObject m, String key) {
        JsonElement e = m.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing block \"" + key + "\"");
        }
        return lookup(e.getAsString(), key);
    }

    private static Block lookup(String id, String key) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new IllegalArgumentException("unknown block \"" + id + "\" for " + key);
        }
        return BuiltInRegistries.BLOCK.get(rl);
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final String EXAMPLE = """
            {
              "name": "Example Cottage",
              "floors": 1,
              "floorHeight": 4,
              "roof": "gable",
              "framed": true,
              "windowSpacing": 3,
              "foundation": "auto",
              "porch": true,
              "chimney": true,
              "maxWidth": 9,
              "maxDepth": 7,
              "materials": {
                "foundation": { "minecraft:cobblestone": 3, "minecraft:mossy_cobblestone": 1 },
                "wall": "minecraft:white_terracotta",
                "frame": "minecraft:oak_log",
                "floor": "minecraft:oak_planks",
                "roofStairs": "minecraft:brick_stairs",
                "roofSlab": "minecraft:brick_slab",
                "roofFull": "minecraft:bricks",
                "window": "minecraft:glass_pane",
                "door": "minecraft:oak_door",
                "trim": "minecraft:oak_fence",
                "chimney": "minecraft:stone_bricks"
              }
            }
            """;
}
