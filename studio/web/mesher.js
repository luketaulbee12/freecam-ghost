// Turns a block grid into three.js meshes using Minecraft's own block models and textures.
import * as THREE from 'three';
import { parseState, resolveState, resolveTexture } from './blockmodels.js';

const DIRS = {
  down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0],
};

// Tint colours for blocks whose model faces have a tintindex (plains-ish biome colours).
const GRASS = [0x91 / 255, 0xbd / 255, 0x59 / 255];
const FOLIAGE = [0x77 / 255, 0xab / 255, 0x2f / 255];
function tintFor(name) {
  if (name === 'water') return [0x3f / 255, 0x76 / 255, 0xe4 / 255];
  if (name === 'birch_leaves') return [0x80 / 255, 0xa7 / 255, 0x55 / 255];
  if (name === 'spruce_leaves') return [0x61 / 255, 0x99 / 255, 0x61 / 255];
  if (name === 'lily_pad') return [0x20 / 255, 0x80 / 255, 0x30 / 255];
  if (name.endsWith('leaves') || name === 'vine') return FOLIAGE;
  return GRASS;
}

// Blocks that hide the faces of an identical neighbour (glass panes against glass, water against water).
const SELF_CULL = /glass|ice|water|slime|honey|leaves/;

// ------------------------------------------------------------------------------- texture atlas

async function loadImage(src) {
  return new Promise(resolve => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

async function buildAtlas(names) {
  const list = [...names];
  const cols = 64;
  const rows = Math.max(1, Math.ceil(list.length / cols));
  let H = 16;
  while (H < rows * 16) H *= 2;
  const W = cols * 16;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingEnabled = false;
  const uv = new Map();
  const kind = new Map();
  const imgs = await Promise.all(list.map(n => loadImage(`/mc/textures/${n}.png`)));
  list.forEach((name, i) => {
    const img = imgs[i];
    const cx = (i % cols) * 16, cy = Math.floor(i / cols) * 16;
    if (img) {
      // Animated textures are vertical strips: use the first frame.
      ctx.drawImage(img, 0, 0, img.width, img.width, cx, cy, 16, 16);
      const px = ctx.getImageData(cx, cy, 16, 16).data;
      let cut = false, semi = false;
      for (let a = 3; a < px.length; a += 4) {
        if (px[a] === 0) cut = true;
        else if (px[a] < 255) semi = true;
      }
      kind.set(name, semi ? 'translucent' : cut ? 'cutout' : 'opaque');
    } else {
      ctx.fillStyle = '#ff00ff';
      ctx.fillRect(cx, cy, 16, 16);
      kind.set(name, 'opaque');
    }
    uv.set(name, [cx / W, cy / H, 16 / W, 16 / H]);
  });
  const tex = new THREE.CanvasTexture(canvas);
  tex.magFilter = THREE.NearestFilter;
  tex.minFilter = THREE.NearestFilter;
  tex.generateMipmaps = false;
  tex.flipY = false;
  tex.colorSpace = THREE.SRGBColorSpace;
  return { tex, uv, kind };
}

// ------------------------------------------------------------------------------- face geometry

// For each face: top-left corner, "right" (u) axis and "down" (v) axis as seen from outside.
function faceFrame(dir, f, t) {
  switch (dir) {
    case 'north': return { tl: [t[0], t[1], f[2]], u: [-1, 0, 0], w: t[0] - f[0], v: [0, -1, 0], h: t[1] - f[1] };
    case 'south': return { tl: [f[0], t[1], t[2]], u: [1, 0, 0], w: t[0] - f[0], v: [0, -1, 0], h: t[1] - f[1] };
    case 'west': return { tl: [f[0], t[1], f[2]], u: [0, 0, 1], w: t[2] - f[2], v: [0, -1, 0], h: t[1] - f[1] };
    case 'east': return { tl: [t[0], t[1], t[2]], u: [0, 0, -1], w: t[2] - f[2], v: [0, -1, 0], h: t[1] - f[1] };
    case 'up': return { tl: [f[0], t[1], f[2]], u: [1, 0, 0], w: t[0] - f[0], v: [0, 0, 1], h: t[2] - f[2] };
    default: return { tl: [f[0], f[1], t[2]], u: [1, 0, 0], w: t[0] - f[0], v: [0, 0, -1], h: t[2] - f[2] };
  }
}

function defaultUV(dir, f, t) {
  switch (dir) {
    case 'down': return [f[0], 16 - t[2], t[0], 16 - f[2]];
    case 'up': return [f[0], f[2], t[0], t[2]];
    case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
    case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
    case 'west': return [f[2], 16 - t[1], t[2], 16 - f[1]];
    default: return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
  }
}

function shade(n) {
  if (n.y > 0.5) return 1.0;
  if (n.y < -0.5) return 0.5;
  if (Math.abs(n.z) > 0.5) return 0.8;
  return 0.6;
}

function nearestDir(v) {
  let best = null, bd = -2;
  for (const [k, d] of Object.entries(DIRS)) {
    const dot = v.x * d[0] + v.y * d[1] + v.z * d[2];
    if (dot > bd) { bd = dot; best = k; }
  }
  return best;
}

/** Pre-bake every face of a block state into block-local quads (positions 0..1, atlas uvs). */
function bakeState(parts, atlas, name) {
  const faces = [];
  let fullOpaque = parts.length === 1;
  for (const part of parts) {
    const rot = new THREE.Matrix4()
      .makeRotationY(-part.y * Math.PI / 180)
      .multiply(new THREE.Matrix4().makeRotationX(-part.x * Math.PI / 180));
    const els = part.model.elements;
    if (els.length !== 1) fullOpaque = false;
    for (const el of els) {
      const f = el.from, t = el.to;
      if (f[0] !== 0 || f[1] !== 0 || f[2] !== 0 || t[0] !== 16 || t[1] !== 16 || t[2] !== 16 || el.rotation) {
        fullOpaque = false;
      }
      let elRot = null, elOrigin = null;
      if (el.rotation) {
        const a = el.rotation.angle * Math.PI / 180;
        elRot = el.rotation.axis === 'x' ? new THREE.Matrix4().makeRotationX(a)
          : el.rotation.axis === 'y' ? new THREE.Matrix4().makeRotationY(a) : new THREE.Matrix4().makeRotationZ(a);
        elOrigin = new THREE.Vector3(...el.rotation.origin);
      }
      for (const [dir, face] of Object.entries(el.faces || {})) {
        const texName = resolveTexture(part.model.textures, face.texture);
        if (!texName || !atlas.uv.has(texName)) { fullOpaque = false; continue; }
        const k = atlas.kind.get(texName);
        if (k !== 'opaque') fullOpaque = false;
        const fr = faceFrame(dir, f, t);
        const corners = [];
        for (const [a, b] of [[0, 0], [1, 0], [1, 1], [0, 1]]) {
          const p = new THREE.Vector3(
            fr.tl[0] + fr.u[0] * fr.w * a + fr.v[0] * fr.h * b,
            fr.tl[1] + fr.u[1] * fr.w * a + fr.v[1] * fr.h * b,
            fr.tl[2] + fr.u[2] * fr.w * a + fr.v[2] * fr.h * b);
          if (elRot) p.sub(elOrigin).applyMatrix4(elRot).add(elOrigin);
          p.divideScalar(16).subScalar(0.5).applyMatrix4(rot).addScalar(0.5);
          corners.push(p);
        }
        const uvr = face.uv || defaultUV(dir, f, t);
        const uvc = [[uvr[0], uvr[1]], [uvr[2], uvr[1]], [uvr[2], uvr[3]], [uvr[0], uvr[3]]];
        const r = ((face.rotation || 0) / 90) | 0;
        const [u0, v0, uw, vh] = atlas.uv.get(texName);
        const uvs = [];
        for (let i = 0; i < 4; i++) {
          const [uu, vv] = uvc[(i - r + 4) % 4];
          // Keep a hair inside the tile so neighbours in the atlas don't bleed in.
          uvs.push(u0 + Math.min(15.98, Math.max(0.02, uu)) / 16 * uw, v0 + Math.min(15.98, Math.max(0.02, vv)) / 16 * vh);
        }
        const n = new THREE.Vector3(...DIRS[dir]);
        if (elRot) n.applyMatrix4(new THREE.Matrix4().extractRotation(elRot));
        n.applyMatrix4(rot);
        let cull = null;
        if (face.cullface) {
          const cv = new THREE.Vector3(...DIRS[face.cullface]).applyMatrix4(rot);
          cull = nearestDir(cv);
        }
        faces.push({
          pos: corners.flatMap(c => [c.x, c.y, c.z]),
          uv: uvs,
          shade: shade(n),
          tint: face.tintindex !== undefined ? tintFor(name) : null,
          cull,
          translucent: k === 'translucent',
        });
      }
    }
  }
  return { faces, fullOpaque: fullOpaque && faces.length >= 6 };
}

// ------------------------------------------------------------------------------- meshing

/**
 * grid: { w, h, d, palette: [stateString], get(x, y, z) -> palette index or -1 (air/outside),
 *         isDesign(x, y, z) -> bool }
 */
export async function buildMeshes(grid, opts = {}) {
  const parsed = grid.palette.map(parseState);
  const parts = await Promise.all(grid.palette.map(s => resolveState(s)));

  const texNames = new Set(['block/water_still', 'block/lava_still']);
  for (const ps of parts) {
    for (const p of ps) {
      if (p.fluid) continue;
      for (const el of p.model.elements) {
        for (const face of Object.values(el.faces || {})) {
          const t = resolveTexture(p.model.textures, face.texture);
          if (t) texNames.add(t);
        }
      }
    }
  }
  const atlas = await buildAtlas(texNames);
  const infos = parts.map((ps, i) => {
    if (ps.length === 1 && ps[0].fluid) return { fluid: ps[0].fluid, name: parsed[i].name, faces: [], fullOpaque: false };
    const b = bakeState(ps, atlas, parsed[i].name);
    return { ...b, name: parsed[i].name, selfCull: SELF_CULL.test(parsed[i].name) };
  });

  const buf = { solid: newBuf(), translucent: newBuf() };
  const { w, h, d } = grid;
  const highlight = opts.highlightDesign;

  for (let x = 0; x < w; x++) {
    for (let z = 0; z < d; z++) {
      for (let y = 0; y < h; y++) {
        const id = grid.get(x, y, z);
        if (id < 0) continue;
        const info = infos[id];
        const designTint = highlight && grid.isDesign(x, y, z) ? [0.85, 1.0, 1.15] : null;
        if (info.fluid) {
          fluid(buf, grid, infos, x, y, z, info, atlas);
          continue;
        }
        for (const fc of info.faces) {
          if (fc.cull) {
            const dv = DIRS[fc.cull];
            const nid = grid.get(x + dv[0], y + dv[1], z + dv[2]);
            if (nid >= 0) {
              const ni = infos[nid];
              if (ni.fullOpaque) continue;
              if (info.selfCull && ni.name === info.name) continue;
            }
          }
          const target = fc.translucent ? buf.translucent : buf.solid;
          let c = fc.tint ? fc.tint.map(v => v * fc.shade) : [fc.shade, fc.shade, fc.shade];
          if (designTint) c = c.map((v, k) => Math.min(1, v * designTint[k]));
          quad(target, fc.pos, fc.uv, c, x, y, z);
        }
      }
    }
  }

  const solid = new THREE.Mesh(toGeometry(buf.solid), new THREE.MeshBasicMaterial({
    map: atlas.tex, vertexColors: true, alphaTest: 0.5, side: THREE.DoubleSide }));
  const translucent = new THREE.Mesh(toGeometry(buf.translucent), new THREE.MeshBasicMaterial({
    map: atlas.tex, vertexColors: true, transparent: true, opacity: 1, depthWrite: false, side: THREE.DoubleSide }));
  translucent.renderOrder = 1;
  return { solid, translucent, faceCount: (buf.solid.idx.length + buf.translucent.idx.length) / 6 };
}

function fluid(buf, grid, infos, x, y, z, info, atlas) {
  const isWater = info.fluid === 'water';
  const tex = atlas.uv.get(isWater ? 'block/water_still' : 'block/lava_still');
  const tint = isWater ? tintFor('water') : [1, 1, 1];
  const target = isWater ? buf.translucent : buf.solid;
  const same = (dx, dy, dz) => {
    const n = grid.get(x + dx, y + dy, z + dz);
    return n >= 0 && infos[n].fluid === info.fluid;
  };
  const solidAt = (dx, dy, dz) => {
    const n = grid.get(x + dx, y + dy, z + dz);
    return n >= 0 && infos[n].fullOpaque;
  };
  const top = same(0, 1, 0) ? 1 : 0.875;
  const [u0, v0, uw, vh] = tex;
  const uv = [u0, v0, u0 + uw, v0, u0 + uw, v0 + vh, u0, v0 + vh];
  if (!same(0, 1, 0)) {
    quad(target, [0, top, 0, 1, top, 0, 1, top, 1, 0, top, 1], uv, tint.map(v => v), x, y, z);
  }
  const sides = [
    [[0, 0, -1], [1, top, 0, 0, top, 0, 0, 0, 0, 1, 0, 0], 0.8],
    [[0, 0, 1], [0, top, 1, 1, top, 1, 1, 0, 1, 0, 0, 1], 0.8],
    [[-1, 0, 0], [0, top, 0, 0, top, 1, 0, 0, 1, 0, 0, 0], 0.6],
    [[1, 0, 0], [1, top, 1, 1, top, 0, 1, 0, 0, 1, 0, 1], 0.6],
  ];
  for (const [dv, pos, s] of sides) {
    if (!same(...dv) && !solidAt(...dv)) quad(target, pos, uv, tint.map(v => v * s), x, y, z);
  }
}

function newBuf() {
  return { pos: [], uv: [], col: [], idx: [] };
}

function quad(b, pos, uv, c, x, y, z) {
  const base = b.pos.length / 3;
  for (let i = 0; i < 4; i++) {
    b.pos.push(pos[i * 3] + x, pos[i * 3 + 1] + y, pos[i * 3 + 2] + z);
    b.uv.push(uv[i * 2], uv[i * 2 + 1]);
    b.col.push(c[0], c[1], c[2]);
  }
  b.idx.push(base, base + 3, base + 2, base, base + 2, base + 1);
}

function toGeometry(b) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(b.pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(b.uv, 2));
  g.setAttribute('color', new THREE.Float32BufferAttribute(b.col, 3));
  g.setIndex(b.idx);
  return g;
}
