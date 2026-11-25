"""
Read blocks straight out of Minecraft region files (.mca, 1.18+ chunk format) for an area, producing the
same "site" structure the mod's /design export writes. Works on any world folder you have on disk,
e.g. your own server's world.
"""
import math
import struct
import zlib
import gzip
from pathlib import Path

from studio import read_nbt, state_string  # noqa: E402  (shared NBT reader)


def region_dir(world, dimension):
    world = Path(world)
    if dimension in ("minecraft:the_nether", "minecraft_the_nether"):
        return world / "DIM-1" / "region"
    if dimension in ("minecraft:the_end", "minecraft_the_end"):
        return world / "DIM1" / "region"
    if dimension.startswith("minecraft") and "overworld" in dimension:
        return world / "region"
    # modded dimension: dimensions/<ns>/<path>/region
    ns, _, path = dimension.replace("_", ":", 1).partition(":")
    return world / "dimensions" / ns / path / "region"


class RegionReader:
    def __init__(self, folder):
        self.folder = Path(folder)
        self.files = {}
        self.chunks = {}

    def _region(self, rx, rz):
        key = (rx, rz)
        if key not in self.files:
            f = self.folder / f"r.{rx}.{rz}.mca"
            self.files[key] = f.read_bytes() if f.is_file() else None
        return self.files[key]

    def chunk(self, cx, cz):
        key = (cx, cz)
        if key in self.chunks:
            return self.chunks[key]
        data = self._region(cx >> 5, cz >> 5)
        result = None
        if data:
            i = (cx & 31) + (cz & 31) * 32
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
            if off:
                start = off * 4096
                length = struct.unpack(">I", data[start:start + 4])[0]
                comp = data[start + 4]
                raw = data[start + 5:start + 4 + length]
                if comp == 2:
                    raw = zlib.decompress(raw)
                elif comp == 1:
                    raw = gzip.decompress(raw)
                elif comp != 3:
                    raw = None  # LZ4 (rare, opt-in setting) not supported
                if raw:
                    result = self._sections(read_nbt(raw))
        self.chunks[key] = result
        return result

    def surface(self, x, z):
        """Y of the top non-air block from the chunk's WORLD_SURFACE heightmap (None if unknown)."""
        sections = self.chunk(x >> 4, z >> 4)
        if not sections:
            return None
        hm = sections.get("_hm")
        if not hm:
            return None
        bits, per = 9, 64 // 9
        idx = (z & 15) * 16 + (x & 15)
        word = hm[idx // per] & 0xFFFFFFFFFFFFFFFF
        v = (word >> ((idx % per) * bits)) & 511
        return v - 64 - 1

    @staticmethod
    def _sections(root):
        level = root.get("Level", root)
        out = {}
        hms = level.get("Heightmaps", {})
        if "WORLD_SURFACE" in hms:
            out["_hm"] = hms["WORLD_SURFACE"]
        for sec in level.get("sections", level.get("Sections", [])):
            bs = sec.get("block_states")
            if not bs:
                continue
            palette = [state_string(p) for p in bs.get("palette", [])]
            data = bs.get("data")
            out[sec["Y"] if sec["Y"] < 128 else sec["Y"] - 256] = (palette, data)
        return out

    def block(self, x, y, z):
        x, y, z = int(x), int(y), int(z)  # numpy ints would overflow on the 64-bit packed data
        sections = self.chunk(x >> 4, z >> 4)
        if not sections:
            return "minecraft:air"
        sec = sections.get(y >> 4)
        if not sec or not isinstance(sec, tuple):
            return "minecraft:air"
        palette, data = sec
        if len(palette) == 1 or not data:
            return palette[0]
        bits = max(4, math.ceil(math.log2(len(palette))))
        per = 64 // bits
        idx = ((y & 15) * 256) + ((z & 15) * 16) + (x & 15)
        word = data[idx // per] & 0xFFFFFFFFFFFFFFFF
        v = (word >> ((idx % per) * bits)) & ((1 << bits) - 1)
        return palette[v] if v < len(palette) else "minecraft:air"


def read_around(folder, cx, cz, radius, name="area", dimension="minecraft:overworld"):
    """Terrain in a square around (cx, cz), from a bit below the lowest surface to above the highest."""
    r = RegionReader(folder)
    x0, x1, z0, z1 = cx - radius, cx + radius, cz - radius, cz + radius
    lo, hi = 10_000, -10_000
    for x in range(x0, x1 + 1, 2):
        for z in range(z0, z1 + 1, 2):
            s = r.surface(x, z)
            if s is not None:
                lo, hi = min(lo, s), max(hi, s)
    if hi < lo:
        raise ValueError("no saved chunks there - is that spot explored, and has the world been saved?")
    y0, y1 = max(-64, lo - 12), min(319, max(hi + 6, lo + 30))
    return read_area(folder, x0, z0, x1, z1, y0, y1, name=name, dimension=dimension, reader=r)


def read_area(folder, x0, z0, x1, z1, y0, y1, name="site", dimension="minecraft:overworld", site=None, reader=None):
    r = reader or RegionReader(folder)
    index = {}
    palette = []
    blocks = []
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                s = r.block(x, y, z)
                i = index.get(s)
                if i is None:
                    i = len(palette)
                    index[s] = i
                    palette.append(s)
                blocks.append(i)
    return {
        "name": name, "dimension": dimension,
        "origin": [x0, y0, z0], "size": [x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1],
        "site": site or [x0, z0, x1, z1], "palette": palette, "blocks": blocks,
    }


# ------------------------------------------------------------------------------- fast (numpy) area reader

def _unpack(data, bits, count):
    import numpy as np
    per = 64 // bits
    words = np.asarray(data, dtype=np.int64).view(np.uint64)
    idx = np.arange(count)
    w = words[idx // per]
    return ((w >> ((idx % per) * bits).astype(np.uint64)) & np.uint64((1 << bits) - 1)).astype(np.int64)


def read_around_fast(folder, cx, cz, radius, name="area", dimension="minecraft:overworld"):
    """Same result as read_around, but decodes whole chunk sections with numpy (large areas in seconds).
    Blocks come back as a little-endian uint16 array, base64 encoded, in the same (x, z, y) order."""
    import base64
    import numpy as np
    r = RegionReader(folder)
    x0, x1, z0, z1 = cx - radius, cx + radius, cz - radius, cz + radius
    w, d = x1 - x0 + 1, z1 - z0 + 1
    # surface heights from the heightmaps
    heights = []
    for ccx in range(x0 >> 4, (x1 >> 4) + 1):
        for ccz in range(z0 >> 4, (z1 >> 4) + 1):
            secs = r.chunk(ccx, ccz)
            if secs and secs.get("_hm"):
                hm = _unpack(secs["_hm"], 9, 256) - 65
                heights.append(hm)
    if not heights:
        raise ValueError("no saved chunks there - is that spot explored, and has the world been saved?")
    hs = np.concatenate(heights)
    lo, hi = int(np.percentile(hs, 2)), int(hs.max())
    y0, y1 = max(-64, lo - 12), min(319, max(hi + 6, lo + 30))
    h = y1 - y0 + 1

    palette, index = ["minecraft:air"], {"minecraft:air": 0}
    out = np.zeros((w, d, h), np.uint16)
    for ccx in range(x0 >> 4, (x1 >> 4) + 1):
        for ccz in range(z0 >> 4, (z1 >> 4) + 1):
            secs = r.chunk(ccx, ccz)
            if not secs:
                continue
            # this chunk's overlap with the area
            ax0, ax1 = max(x0, ccx * 16), min(x1, ccx * 16 + 15)
            az0, az1 = max(z0, ccz * 16), min(z1, ccz * 16 + 15)
            for sy in range(y0 >> 4, (y1 >> 4) + 1):
                sec = secs.get(sy)
                if not isinstance(sec, tuple):
                    continue
                pal, data = sec
                gids = np.array([index.setdefault(s, len(index)) for s in pal], np.uint16)
                for s in pal:
                    if index[s] >= len(palette):
                        palette.append(s)
                if len(pal) == 1 or not data:
                    arr = np.full(4096, 0, np.int64)
                else:
                    arr = _unpack(data, max(4, math.ceil(math.log2(len(pal)))), 4096)
                    arr = np.minimum(arr, len(pal) - 1)
                cube = gids[arr].reshape(16, 16, 16)  # [y][z][x]
                ys0, ys1 = max(y0, sy * 16), min(y1, sy * 16 + 15)
                sub = cube[ys0 - sy * 16:ys1 - sy * 16 + 1, az0 - ccz * 16:az1 - ccz * 16 + 1, ax0 - ccx * 16:ax1 - ccx * 16 + 1]
                out[ax0 - x0:ax1 - x0 + 1, az0 - z0:az1 - z0 + 1, ys0 - y0:ys1 - y0 + 1] = sub.transpose(2, 1, 0)
    return {
        "name": name, "dimension": dimension,
        "origin": [x0, y0, z0], "size": [w, h, d],
        "site": [x0, z0, x1, z1], "palette": palette,
        "blocksB64": base64.b64encode(out.astype("<u2").tobytes()).decode(),
    }
