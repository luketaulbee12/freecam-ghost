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

    @staticmethod
    def _sections(root):
        level = root.get("Level", root)
        out = {}
        for sec in level.get("sections", level.get("Sections", [])):
            bs = sec.get("block_states")
            if not bs:
                continue
            palette = [state_string(p) for p in bs.get("palette", [])]
            data = bs.get("data")
            out[sec["Y"] if sec["Y"] < 128 else sec["Y"] - 256] = (palette, data)
        return out

    def block(self, x, y, z):
        sections = self.chunk(x >> 4, z >> 4)
        if not sections:
            return "minecraft:air"
        sec = sections.get(y >> 4)
        if not sec:
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


def read_area(folder, x0, z0, x1, z1, y0, y1, name="site", dimension="minecraft:overworld", site=None):
    r = RegionReader(folder)
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
