"""
Build Studio: a local web app to look at a plot of terrain and a design on it in 3D before building.

Run:   python studio.py            then open http://localhost:8765
Data:  - terrain exports from the mod (/design export) in <game dir>/freecam_ghosts/exports/*.json.gz
       - blueprints from the mod in <game dir>/freecam_ghosts/<world>/<dimension>/*.nbt
       - block models/textures extracted once from your own Minecraft client jar (not stored in the repo)
Standard library only.
"""
import gzip
import io
import json
import os
import struct
import sys
import urllib.parse
import zipfile
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
WEB = HERE / "web"
DATA = HERE / "data"
ASSETS = DATA / "mc"  # extracted vanilla assets (gitignored)
CONFIG = HERE / "config.json"

DEFAULT_CONFIG = {
    "port": 8765,
    "clientJar": r"C:\Users\Luke\Desktop\curseforge\minecraft\Install\versions\1.21.1\1.21.1.jar",
    "gameDirs": [
        str((HERE.parent / "neoforge" / "run").resolve()),
        r"C:\Users\Luke\Desktop\curseforge\minecraft\Instances\test",
    ],
    "worlds": [],  # extra world folders (e.g. your server's world) for reading region files
}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG.exists():
        cfg.update(json.loads(CONFIG.read_text()))
    else:
        CONFIG.write_text(json.dumps(DEFAULT_CONFIG, indent=2))
    return cfg


# ---------------------------------------------------------------------------------------- assets

ASSET_PREFIXES = (
    "assets/minecraft/blockstates/",
    "assets/minecraft/models/block/",
    "assets/minecraft/textures/block/",
    "assets/minecraft/textures/colormap/",
)


def ensure_assets(jar_path):
    marker = ASSETS / ".done"
    if marker.exists():
        return
    print(f"Extracting block models and textures from {jar_path} (one time)...")
    with zipfile.ZipFile(jar_path) as z:
        for name in z.namelist():
            if name.startswith(ASSET_PREFIXES) and not name.endswith("/"):
                rel = name[len("assets/minecraft/"):]
                out = ASSETS / rel
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(z.read(name))
    marker.write_text("ok")
    print("Done.")


# ---------------------------------------------------------------------------------------- NBT

def read_nbt(raw):
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    pos = 0

    def u(fmt):
        nonlocal pos
        v = struct.unpack_from(">" + fmt, data, pos)
        pos += struct.calcsize(">" + fmt)
        return v[0]

    def s():
        nonlocal pos
        n = u("H")
        v = data[pos:pos + n].decode("utf8", "replace")
        pos += n
        return v

    def payload(t):
        nonlocal pos
        if t == 1: return u("b")
        if t == 2: return u("h")
        if t == 3: return u("i")
        if t == 4: return u("q")
        if t == 5: return u("f")
        if t == 6: return u("d")
        if t == 7:
            n = u("i"); v = data[pos:pos + n]; pos += n; return v
        if t == 8: return s()
        if t == 9:
            et = u("b"); n = u("i"); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u("b")
                if tt == 0:
                    return d
                k = s()
                d[k] = payload(tt)
        if t == 11:
            n = u("i"); v = list(struct.unpack_from(">%di" % n, data, pos)); pos += 4 * n; return v
        if t == 12:
            n = u("i"); v = list(struct.unpack_from(">%dq" % n, data, pos)); pos += 8 * n; return v
        raise ValueError(f"bad NBT tag {t}")

    t = u("b")
    s()
    return payload(t)


def state_string(compound):
    """NbtUtils block state {Name, Properties} -> 'minecraft:oak_stairs[facing=north,half=bottom]'."""
    name = compound.get("Name", "minecraft:air")
    props = compound.get("Properties")
    if props:
        return name + "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"
    return name


def read_blueprint(path):
    root = read_nbt(Path(path).read_bytes())
    palette = [state_string(p) for p in root.get("palette", [])]
    b = root.get("blocks", [])
    blocks = [[b[i], b[i + 1], b[i + 2], b[i + 3]] for i in range(0, len(b) - 3, 4)]
    return {"name": root.get("name", Path(path).stem), "palette": palette, "blocks": blocks}


# ---------------------------------------------------------------------------------------- listing

def list_sites(cfg):
    out = []
    for gd in cfg["gameDirs"]:
        d = Path(gd) / "freecam_ghosts" / "exports"
        if d.is_dir():
            for f in sorted(d.glob("*.json.gz"), key=lambda p: -p.stat().st_mtime):
                out.append({"name": f.name[:-8], "path": str(f), "source": "export", "gameDir": gd})
    return out


def list_blueprints(cfg):
    out = []
    for gd in cfg["gameDirs"]:
        root = Path(gd) / "freecam_ghosts"
        if not root.is_dir():
            continue
        for world in root.iterdir():
            if not world.is_dir() or world.name in ("exports", "styles"):
                continue
            for dim in world.iterdir():
                if dim.is_dir():
                    for f in sorted(dim.glob("*.nbt"), key=lambda p: -p.stat().st_mtime):
                        out.append({"name": f.stem.replace("_", " "), "path": str(f), "world": world.name,
                                    "dimension": dim.name})
    return out


def sanitize(s):
    return "".join(c if c.isalnum() or c in "._-" else "_" for c in s)


def world_folder_for(blueprint_path, cfg):
    """freecam_ghosts/<worldId>/<dim>/x.nbt -> the world save folder holding its region files."""
    bp = Path(blueprint_path)
    world_id = bp.parent.parent.name
    game_dir = bp.parent.parent.parent.parent
    mapped = cfg.get("serverWorlds", {}).get(world_id)
    if mapped:
        return Path(mapped)
    if world_id.startswith("sp_"):
        saves = game_dir / "saves"
        if saves.is_dir():
            for w in saves.iterdir():
                if sanitize(w.name) == world_id[3:]:
                    return w
    return None


def design_site(blueprint_path, cfg, margin=12):
    """Terrain around a blueprint, read from the world's region files on disk."""
    from region import read_area, region_dir
    bp = read_blueprint(blueprint_path)
    if not bp["blocks"]:
        raise ValueError("this blueprint has nothing left in it (it's fully built) - pick another, "
                         "or use /design export to view the area")
    world = world_folder_for(blueprint_path, cfg)
    if world is None:
        raise ValueError("can't find this world's files - add it under serverWorlds in config.json")
    dim = Path(blueprint_path).parent.name
    xs = [b[0] for b in bp["blocks"]]; ys = [b[1] for b in bp["blocks"]]; zs = [b[2] for b in bp["blocks"]]
    x0, x1, z0, z1 = min(xs) - margin, max(xs) + margin, min(zs) - margin, max(zs) + margin
    y0, y1 = max(-64, min(ys) - 12), min(319, max(ys) + 12)
    return read_area(region_dir(world, dim), x0, z0, x1, z1, y0, y1, name=bp["name"],
                     dimension=dim, site=[min(xs), min(zs), max(xs), max(zs)])


def list_worlds(cfg):
    """Singleplayer worlds (and configured server worlds) that have region files."""
    out = []
    for gd in cfg["gameDirs"]:
        saves = Path(gd) / "saves"
        if saves.is_dir():
            for w in sorted(saves.iterdir(), key=lambda p: -p.stat().st_mtime):
                if (w / "region").is_dir():
                    out.append({"name": w.name, "path": str(w), "gameDir": gd, "worldId": "sp_" + sanitize(w.name)})
    for world_id, path in cfg.get("serverWorlds", {}).items():
        out.append({"name": f"Server: {world_id}", "path": path, "gameDir": cfg["gameDirs"][0], "worldId": world_id})
    return out


def world_allowed(path, cfg):
    return any(Path(path).resolve() == Path(w["path"]).resolve() for w in list_worlds(cfg))


# ---- NBT writing (just what blueprints need)

def _nbt_str(s):
    b = s.encode("utf8")
    return struct.pack(">H", len(b)) + b


def _nbt_payload(v):
    if isinstance(v, bool):
        return b"\x01", struct.pack(">b", 1 if v else 0)
    if isinstance(v, str):
        return b"\x08", _nbt_str(v)
    if isinstance(v, dict):
        body = b""
        for k, x in v.items():
            t, p = _nbt_payload(x)
            body += t + _nbt_str(k) + p
        return b"\x0a", body + b"\x00"
    if isinstance(v, tuple) and v and v[0] == "intarray":
        arr = v[1]
        return b"\x0b", struct.pack(">i", len(arr)) + struct.pack(">%di" % len(arr), *arr)
    if isinstance(v, list):
        if not v:
            return b"\x09", b"\x0a" + struct.pack(">i", 0)
        t0, _ = _nbt_payload(v[0])
        return b"\x09", t0 + struct.pack(">i", len(v)) + b"".join(_nbt_payload(x)[1] for x in v)
    raise TypeError(type(v))


def write_nbt(root):
    t, p = _nbt_payload(root)
    return gzip.compress(t + _nbt_str("") + p)


def parse_state_string(s):
    name, props = s, {}
    if "[" in s:
        name, rest = s.split("[", 1)
        for kv in rest.rstrip("]").split(","):
            if "=" in kv:
                k, v = kv.split("=", 1)
                props[k] = v
    if ":" not in name:
        name = "minecraft:" + name
    return name, props


def export_blueprint(req, cfg):
    """Write a studio design as a mod blueprint: <gameDir>/freecam_ghosts/<worldId>/<dimension>/<name>.nbt"""
    game_dir = req["gameDir"]
    if game_dir not in cfg["gameDirs"]:
        raise ValueError("unknown game dir")
    folder = Path(game_dir) / "freecam_ghosts" / sanitize(req["worldId"]) / sanitize(req.get("dimension", "minecraft_overworld"))
    folder.mkdir(parents=True, exist_ok=True)
    index, palette, data = {}, [], []
    for x, y, z, s in req["blocks"]:
        if s not in index:
            index[s] = len(palette)
            name, props = parse_state_string(s)
            entry = {"Name": name}
            if props:
                entry["Properties"] = props
            palette.append(entry)
        data += [int(x), int(y), int(z), index[s]]
    root = {"palette": palette, "blocks": ("intarray", data), "name": req["name"], "visible": True}
    f = folder / (sanitize(req["name"]) + ".nbt")
    f.write_bytes(write_nbt(root))
    return {"saved": str(f), "blocks": len(req["blocks"])}


def list_styles(cfg):
    out = {}
    dirs = [HERE / "styles"] + [Path(gd) / "freecam_ghosts" / "styles" for gd in cfg["gameDirs"]]
    for d in dirs:
        if d.is_dir():
            for f in sorted(d.glob("*.json")):
                try:
                    s = json.loads(f.read_text())
                    s.setdefault("id", f.stem)
                    out[s["id"]] = s
                except Exception as e:
                    out[f.stem] = {"id": f.stem, "error": str(e)}
    return list(out.values())


def allowed(path, cfg):
    """Only serve files under the configured game dirs."""
    p = Path(path).resolve()
    for gd in cfg["gameDirs"]:
        try:
            p.relative_to(Path(gd).resolve())
            return True
        except ValueError:
            pass
    return False


# ---------------------------------------------------------------------------------------- server

class Handler(SimpleHTTPRequestHandler):
    cfg = None

    def log_message(self, fmt, *args):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path, ctype, gz=False):
        data = Path(path).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        if gz:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(url.query)
        cfg = self.cfg
        try:
            if url.path in ("/", "/index.html"):
                return self.send_file(WEB / "index.html", "text/html")
            if url.path.startswith("/web/"):
                f = (WEB / url.path[5:]).resolve()
                if WEB.resolve() in f.parents and f.is_file():
                    ctype = "text/javascript" if f.suffix == ".js" else "text/css" if f.suffix == ".css" else "application/octet-stream"
                    return self.send_file(f, ctype)
            if url.path.startswith("/mc/"):
                f = (ASSETS / urllib.parse.unquote(url.path[4:])).resolve()
                if ASSETS.resolve() in f.parents and f.is_file():
                    ctype = "image/png" if f.suffix == ".png" else "application/json"
                    return self.send_file(f, ctype)
                return self.send_json({"error": "not found"}, 404)
            if url.path == "/api/sites":
                return self.send_json(list_sites(cfg))
            if url.path == "/api/blueprints":
                return self.send_json(list_blueprints(cfg))
            if url.path == "/api/site":
                p = q["path"][0]
                if allowed(p, cfg):
                    return self.send_file(p, "application/json", gz=True)
            if url.path == "/api/blueprint":
                p = q["path"][0]
                if allowed(p, cfg):
                    return self.send_json(read_blueprint(p))
            if url.path == "/api/worlds":
                return self.send_json(list_worlds(cfg))
            if url.path == "/api/styles":
                return self.send_json(list_styles(cfg))
            if url.path == "/api/area":
                from region import read_around_fast, region_dir
                world = q["world"][0]
                if not world_allowed(world, cfg):
                    return self.send_json({"error": "unknown world"}, 403)
                dim = q.get("dim", ["minecraft_overworld"])[0]
                r = max(8, min(256, int(q.get("r", ["32"])[0])))
                data = read_around_fast(region_dir(world, dim), int(float(q["x"][0])), int(float(q["z"][0])), r,
                                   name=f"{Path(world).name} @ {q['x'][0]},{q['z'][0]}", dimension=dim)
                return self.send_json(data)
            if url.path == "/api/pictures":
                pics = DATA / "pictures"
                files = sorted(pics.glob("*"), key=lambda p: -p.stat().st_mtime) if pics.is_dir() else []
                return self.send_json([{"name": f.name, "url": "/pictures/" + f.name} for f in files])
            if url.path.startswith("/meshes/"):
                f = (DATA / "meshes" / urllib.parse.unquote(url.path[8:])).resolve()
                if (DATA / "meshes").resolve() in f.parents and f.is_file():
                    return self.send_file(f, "application/octet-stream")
            if url.path == "/mesh":
                return self.send_file(WEB / "mesh.html", "text/html")
            if url.path.startswith("/pictures/"):
                f = (DATA / "pictures" / urllib.parse.unquote(url.path[10:])).resolve()
                if (DATA / "pictures").resolve() in f.parents and f.is_file():
                    ext = f.suffix.lower()
                    ctype = {".png": "image/png", ".webp": "image/webp", ".gif": "image/gif"}.get(ext, "image/jpeg")
                    return self.send_file(f, ctype)
            if url.path == "/api/design_site":
                p = q["path"][0]
                if allowed(p, cfg):
                    return self.send_json(design_site(p, cfg))
            self.send_json({"error": "not found"}, 404)
        except Exception as e:
            self.send_json({"error": str(e)}, 500)

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        try:
            if url.path == "/api/export_blueprint":
                n = int(self.headers.get("Content-Length", 0))
                req = json.loads(self.rfile.read(n))
                return self.send_json(export_blueprint(req, self.cfg))
            if url.path == "/api/picture":
                n = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(n)
                name = urllib.parse.parse_qs(url.query).get("name", ["picture.png"])[0]
                name = "".join(c if c.isalnum() or c in "-_." else "_" for c in name)[-80:]
                pics = DATA / "pictures"
                pics.mkdir(parents=True, exist_ok=True)
                (pics / name).write_bytes(raw)
                return self.send_json({"name": name, "url": "/pictures/" + name})
        except Exception as e:
            return self.send_json({"error": str(e)}, 500)
        if url.path == "/api/screenshot":
            n = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(n)
            shots = DATA / "screenshots"
            shots.mkdir(parents=True, exist_ok=True)
            name = urllib.parse.parse_qs(url.query).get("name", ["shot"])[0]
            name = "".join(c if c.isalnum() or c in "-_" else "_" for c in name)
            f = shots / f"{name}.png"
            f.write_bytes(raw)
            return self.send_json({"saved": str(f)})
        self.send_json({"error": "not found"}, 404)


def main():
    cfg = load_config()
    jar = Path(cfg["clientJar"])
    if not jar.is_file():
        sys.exit(f"Minecraft client jar not found: {jar}  (set clientJar in {CONFIG})")
    ensure_assets(jar)
    Handler.cfg = cfg
    server = ThreadingHTTPServer(("127.0.0.1", cfg["port"]), Handler)
    print(f"Build Studio running at http://localhost:{cfg['port']}")
    server.serve_forever()


if __name__ == "__main__":
    main()
