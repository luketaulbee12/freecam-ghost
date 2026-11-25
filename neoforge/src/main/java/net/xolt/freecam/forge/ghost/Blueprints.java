package net.xolt.freecam.forge.ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Named blueprints for the current world + dimension, stored as
 * {@code freecam_ghosts/<world>/<dimension>/<name>.nbt}. One is "active": that's the one
 * freecam edits and the auto-build key builds.
 */
public final class Blueprints {
    private static final Logger LOG = LoggerFactory.getLogger("freecam-ghost");

    public static final class Blueprint {
        private String name;
        public final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private boolean visible = true;
        private Path file;
        private long loadedAt = System.currentTimeMillis();

        public String name() {
            return name;
        }

        public boolean visible() {
            return visible;
        }

        @Nullable
        public BlockPos center() {
            if (blocks.isEmpty()) {
                return null;
            }
            long x = 0, y = 0, z = 0;
            for (BlockPos p : blocks.keySet()) {
                x += p.getX();
                y += p.getY();
                z += p.getZ();
            }
            int n = blocks.size();
            return new BlockPos((int) (x / n), (int) (y / n), (int) (z / n));
        }
    }

    private static Path dir;
    private static final List<Blueprint> list = new ArrayList<>();
    private static Blueprint active;

    private Blueprints() {}

    /** Loads the blueprint set for this level if it changed. Returns true when it did. */
    static boolean ensureLoaded(Level level) {
        Path d = GhostStorage.root().resolve(GhostStorage.worldId())
                .resolve(GhostStorage.sanitize(level.dimension().location().toString()));
        if (d.equals(dir)) {
            return false;
        }
        dir = d;
        list.clear();
        active = null;
        migrateLegacy(d);
        if (Files.isDirectory(d)) {
            try (Stream<Path> files = Files.list(d)) {
                files.filter(p -> p.toString().endsWith(".nbt")).sorted().forEach(p -> {
                    Blueprint bp = read(p);
                    if (bp != null) {
                        list.add(bp);
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to list blueprints in {}", d, e);
            }
        }
        list.sort(Comparator.comparing(b -> b.name.toLowerCase()));
        String activeName = readActiveName();
        for (Blueprint b : list) {
            if (b.name.equals(activeName)) {
                active = b;
            }
        }
        if (active == null && !list.isEmpty()) {
            active = list.get(0);
        }
        return true;
    }

    /** Pick up blueprint files added since loading (e.g. sent from Build Studio), keeping the ones we have. */
    public static void rescan() {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                boolean known = list.stream().anyMatch(b -> f.equals(b.file));
                if (!known) {
                    Blueprint bp = read(f);
                    if (bp != null) {
                        list.add(bp);
                    }
                } else {
                    // Re-read a blueprint the studio overwrote (same name sent again).
                    for (Blueprint b : list) {
                        if (f.equals(b.file) && Files.getLastModifiedTime(f).toMillis() > b.loadedAt) {
                            Blueprint fresh = read(f);
                            if (fresh != null) {
                                b.blocks.clear();
                                b.blocks.putAll(fresh.blocks);
                                b.loadedAt = fresh.loadedAt;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to rescan blueprints in {}", dir, e);
        }
        list.sort(Comparator.comparing(b -> b.name.toLowerCase()));
    }

    static void unload() {
        dir = null;
        list.clear();
        active = null;
    }

    public static List<Blueprint> all() {
        return list;
    }

    /** The blueprint freecam edits; created on demand. */
    public static Blueprint active() {
        if (active == null) {
            active = create();
        }
        return active;
    }

    @Nullable
    public static Blueprint activeOrNull() {
        return active;
    }

    public static void setActive(Blueprint bp) {
        active = bp;
        writeActiveName();
    }

    public static Blueprint create() {
        int n = list.size() + 1;
        String name;
        do {
            name = "Blueprint " + n++;
        } while (find(name) != null);
        Blueprint bp = new Blueprint();
        bp.name = name;
        bp.file = fileFor(name);
        list.add(bp);
        if (active == null) {
            active = bp;
        }
        save(bp);
        writeActiveName();
        return bp;
    }

    @Nullable
    public static Blueprint find(String name) {
        for (Blueprint b : list) {
            if (b.name.equalsIgnoreCase(name)) {
                return b;
            }
        }
        return null;
    }

    public static boolean rename(Blueprint bp, String newName) {
        newName = newName.trim();
        if (newName.isEmpty() || newName.equals(bp.name)) {
            return false;
        }
        Blueprint clash = find(newName);
        if (clash != null && clash != bp) {
            return false;
        }
        try {
            Files.deleteIfExists(bp.file);
        } catch (Exception ignored) {
        }
        bp.name = newName;
        bp.file = fileFor(newName);
        save(bp);
        if (bp == active) {
            writeActiveName();
        }
        list.sort(Comparator.comparing(b -> b.name.toLowerCase()));
        return true;
    }

    public static void setVisible(Blueprint bp, boolean visible) {
        bp.visible = visible;
        save(bp);
    }

    public static void delete(Blueprint bp) {
        list.remove(bp);
        try {
            Files.deleteIfExists(bp.file);
        } catch (Exception ignored) {
        }
        if (bp == active) {
            active = list.isEmpty() ? null : list.get(0);
            writeActiveName();
        }
    }

    public static void save(Blueprint bp) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            CompoundTag root = GhostStorage.encode(bp.blocks);
            root.putString("name", bp.name);
            root.putBoolean("visible", bp.visible);
            Path tmp = bp.file.resolveSibling(bp.file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, bp.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            bp.loadedAt = System.currentTimeMillis() + 1000; // our own write isn't "new from the studio"
        } catch (Exception e) {
            LOG.error("Failed to save blueprint {}", bp.name, e);
        }
    }

    private static Path fileFor(String name) {
        return dir.resolve(GhostStorage.sanitize(name) + ".nbt");
    }

    @Nullable
    private static Blueprint read(Path file) {
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Blueprint bp = new Blueprint();
            String stem = file.getFileName().toString().replaceFirst("\\.nbt$", "");
            bp.name = root.contains("name") ? root.getString("name") : stem;
            bp.visible = !root.contains("visible") || root.getBoolean("visible");
            bp.blocks.putAll(GhostStorage.decode(root));
            bp.file = file;
            return bp;
        } catch (Exception e) {
            LOG.error("Failed to read blueprint {}", file, e);
            return null;
        }
    }

    /** Version 1 stored one blueprint per dimension at {@code <world>/<dimension>.nbt}. */
    private static void migrateLegacy(Path d) {
        Path legacy = d.resolveSibling(d.getFileName() + ".nbt");
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            Files.createDirectories(d);
            Files.move(legacy, d.resolve("Blueprint_1.nbt"));
        } catch (Exception e) {
            LOG.error("Failed to migrate {}", legacy, e);
        }
    }

    private static String readActiveName() {
        try {
            Path f = dir.resolve("active.txt");
            return Files.isRegularFile(f) ? Files.readString(f).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeActiveName() {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("active.txt"), active == null ? "" : active.name);
        } catch (Exception ignored) {
        }
    }
}
