// Build Studio design generator: a port of the mod's Designer, running in the browser so a design
// regenerates instantly as you change its settings. Works on a terrain grid (from region files or an
// export) and returns block states keyed by absolute world position (air = dig).

// ------------------------------------------------------------------------------- block helpers

const NOT_GROUND = /(_leaves|_log|_wood|_stem|_hyphae|^short_grass$|^grass$|^tall_grass$|fern|flower|tulip|orchid|allium|bluet|daisy|cornflower|lily|dandelion|poppy|rose|bush|sapling|seagrass|kelp|vine|^snow$|torch|^.*mushroom$|sugar_cane|bamboo|carpet|pink_petals|glow_lichen|cobweb|^water$|^lava$|^air$|^cave_air$|^void_air$|bubble_column)/;
const TREE = /(_leaves|_log|_wood|_stem|_hyphae|^vine$|sapling|bee_nest|mushroom_block)/;

export function blockName(state) {
  let n = state.startsWith('minecraft:') ? state.slice(10) : state;
  const i = n.indexOf('[');
  return i < 0 ? n : n.slice(0, i);
}

/** 'oak_stairs', {facing:'north'} -> 'minecraft:oak_stairs[facing=north]' */
export function bs(name, props) {
  const n = name.includes(':') ? name : 'minecraft:' + name;
  if (!props || !Object.keys(props).length) return n;
  return n + '[' + Object.entries(props).map(([k, v]) => `${k}=${v}`).join(',') + ']';
}

const isEmpty = s => /^(air|cave_air|void_air|water|lava|bubble_column)$/.test(blockName(s));
const isLog = n => /(_log|_wood|_stem|_hyphae|basalt|pillar|quartz_pillar|purpur_pillar|bamboo_block)$/.test(n);
const isStairs = n => n.endsWith('_stairs');
const isSlab = n => n.endsWith('_slab');
const isTrapdoor = n => n.endsWith('_trapdoor');
const isLeaves = n => n.endsWith('_leaves');

const DIRS = { north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
const OPP = { north: 'south', south: 'north', west: 'east', east: 'west', up: 'down', down: 'up' };
const CW = { north: 'east', east: 'south', south: 'west', west: 'north' };
const CCW = { north: 'west', west: 'south', south: 'east', east: 'north' };

// Deterministic 0..1 per position (so a design regenerates identically).
function chance(x, y, z, salt) {
  let h = Math.imul(x | 0, 0x27d4eb2d) ^ Math.imul(y | 0, 0x165667b1) ^ Math.imul(z | 0, 0x9e3779b1) ^ Math.imul(salt, 0x85ebca6b);
  h ^= h >>> 15; h = Math.imul(h, 0x2c1b3c6d); h ^= h >>> 12; h = Math.imul(h, 0x297a2d39); h ^= h >>> 15;
  return (h >>> 0) / 4294967296;
}

/** "minecraft:stone" or { "minecraft:stone": 3, "minecraft:andesite": 1 } -> picker(x,y,z) */
function mat(spec, salt = 1) {
  if (!spec) return null;
  if (typeof spec === 'string') return () => blockName(spec);
  const entries = Object.entries(spec);
  const total = entries.reduce((s, [, w]) => s + w, 0);
  return (x, y, z) => {
    let r = chance(x, y, z, salt) * total;
    for (const [b, w] of entries) { r -= w; if (r < 0) return blockName(b); }
    return blockName(entries[0][0]);
  };
}

// ------------------------------------------------------------------------------- terrain

export class Terrain {
  /** site: { origin, size, palette, blocks } from /api/area or an export. */
  constructor(site) {
    this.site = site;
    [this.ox, this.oy, this.oz] = site.origin;
    [this.w, this.h, this.d] = site.size;
  }
  at(x, y, z) {
    const lx = x - this.ox, ly = y - this.oy, lz = z - this.oz;
    if (lx < 0 || ly < 0 || lz < 0 || lx >= this.w || ly >= this.h || lz >= this.d) return 'minecraft:air';
    return this.site.palette[this.site.blocks[(lx * this.d + lz) * this.h + ly]];
  }
  /** Y of the top real ground block (ignoring trees, plants, water). */
  ground(x, z) {
    for (let y = this.oy + this.h - 1; y > this.oy; y--) {
      if (!NOT_GROUND.test(blockName(this.at(x, y, z)))) return y;
    }
    return this.oy;
  }
  water(x, z) {
    const g = this.ground(x, z);
    for (let y = this.oy + this.h - 1; y > g; y--) if (blockName(this.at(x, y, z)) === 'water') return y;
    return null;
  }
}

// ------------------------------------------------------------------------------- designer

/**
 * style: same JSON as the mod's style files (+ optional "details").
 * plot: { x0, z0, x1, z1 } (inclusive world coords), front: 'north'|'south'|'east'|'west'
 * Returns { blocks: Map("x,y,z" -> state), summary, bounds }
 */
export function design(terrain, style, plot, front) {
  const D = new Designer(terrain, style, plot, front);
  return D.run();
}

class Designer {
  constructor(t, st, plot, front) {
    this.t = t;
    this.st = st;
    this.d = st.details || {};
    this.m = st.materials;
    this.plot = { x0: Math.min(plot.x0, plot.x1), x1: Math.max(plot.x0, plot.x1), z0: Math.min(plot.z0, plot.z1), z1: Math.max(plot.z0, plot.z1) };
    this.front = front;
    this.out = new Map();
    this.reserved = new Set();
    this.windows = [];
    this.eaveTrimCells = [];
    this.floors = st.floors ?? 1;
    this.fh = st.floorHeight ?? 4;
    this.wallMat = mat(this.m.wall, 2);
    this.frameMat = mat(this.m.frame, 3);
    this.floorMat = mat(this.m.floor, 4);
    this.foundMat = mat(this.m.foundation, 5);
    this.chimMat = mat(this.m.chimney || this.m.foundation, 6);
    this.roofMix = mat(this.d.roofStairs, 7);
  }

  key(x, y, z) { return x + ',' + y + ',' + z; }
  put(x, y, z, state) { this.out.set(this.key(x, y, z), state); }
  has(x, y, z) { return this.out.has(this.key(x, y, z)); }
  get(x, y, z) { return this.out.get(this.key(x, y, z)); }
  dig(x, y, z) {
    if (!this.has(x, y, z) && !isEmpty(this.t.at(x, y, z))) this.out.set(this.key(x, y, z), 'minecraft:air');
  }
  inPlot(x, z) { const p = this.plot; return x >= p.x0 && x <= p.x1 && z >= p.z0 && z <= p.z1; }
  inBuilding(x, z) { return x >= this.bx0 && x <= this.bx1 && z >= this.bz0 && z <= this.bz1; }
  onPerimeter(x, z) { return this.inBuilding(x, z) && (x === this.bx0 || x === this.bx1 || z === this.bz0 || z === this.bz1); }
  outward(x, z) { return z === this.bz0 ? 'north' : z === this.bz1 ? 'south' : x === this.bx0 ? 'west' : 'east'; }

  run() {
    this.footprint();
    this.chooseLevels();
    this.planFrontage();
    this.clearTrees();
    if (!this.stilts) this.landscape();
    this.foundation();
    this.floorsPass();
    this.walls();
    this.roof();
    this.ladder();
    const chimneys = this.d.chimneys ?? (this.st.chimney ? 1 : 0);
    if (!this.stilts && (this.st.roof || 'gable') === 'gable') for (let e = 0; e < chimneys; e++) this.chimney(e);
    if (this.porchDepth > 0) this.porch();
    this.stepsAndPath();
    this.details();
    this.clearInterior();
    this.applyRoofMix();
    this.connectShapes();
    for (const [k, s] of [...this.out]) {
      const [x, y, z] = k.split(',').map(Number);
      if (this.t.at(x, y, z) === s) this.out.delete(k);
    }
    let digs = 0;
    for (const s of this.out.values()) if (blockName(s) === 'air') digs++;
    return {
      blocks: this.out,
      summary: `${this.bx1 - this.bx0 + 1}×${this.bz1 - this.bz0 + 1}, floor y=${this.y0}, ${this.stilts ? 'on stilts' : 'solid foundation'}, ${this.out.size - digs} blocks, ${digs} to dig`,
    };
  }

  // ---------------------------------------------------------------- layout
  footprint() {
    const p = this.plot;
    const sw = p.x1 - p.x0 + 1, sd = p.z1 - p.z0 + 1;
    const minSide = Math.min(sw, sd);
    const margin = minSide >= 11 ? 2 : minSide >= 7 ? 1 : 0;
    this.porchDepth = this.st.porch ? 2 : 0;
    const frontZ = this.front === 'north' || this.front === 'south';
    let availX = sw - 2 * margin, availZ = sd - 2 * margin;
    if (frontZ) availZ -= this.porchDepth; else availX -= this.porchDepth;
    const capX = frontZ ? this.st.maxWidth : this.st.maxDepth;
    const capZ = frontZ ? this.st.maxDepth : this.st.maxWidth;
    const w = Math.max(5, capX ? Math.min(availX, capX) : availX);
    const d = Math.max(5, capZ ? Math.min(availZ, capZ) : availZ);
    const cx = Math.floor((p.x0 + p.x1) / 2), cz = Math.floor((p.z0 + p.z1) / 2);
    this.bx0 = cx - Math.floor((w - 1) / 2); this.bx1 = this.bx0 + w - 1;
    this.bz0 = cz - Math.floor((d - 1) / 2); this.bz1 = this.bz0 + d - 1;
    const shift = Math.floor((this.porchDepth + 1) / 2);
    if (this.front === 'north') { this.bz0 += shift; this.bz1 += shift; }
    if (this.front === 'south') { this.bz0 -= shift; this.bz1 -= shift; }
    if (this.front === 'west') { this.bx0 += shift; this.bx1 += shift; }
    if (this.front === 'east') { this.bx0 -= shift; this.bx1 -= shift; }
  }

  chooseLevels() {
    const hs = [];
    let maxG = -1e9, maxWater = -1e9, anyWater = false;
    for (let x = this.bx0; x <= this.bx1; x++) for (let z = this.bz0; z <= this.bz1; z++) {
      const g = this.t.ground(x, z);
      hs.push(g + 1);
      maxG = Math.max(maxG, g);
      const w = this.t.water(x, z);
      if (w !== null) { anyWater = true; maxWater = Math.max(maxWater, w); }
    }
    hs.sort((a, b) => a - b);
    const median = hs[hs.length >> 1];
    const slope = maxG - (hs[0] - 1);
    const f = this.st.foundation || 'auto';
    this.stilts = f === 'stilts' ? true : f === 'solid' ? false : (anyWater || slope > 4);
    if (this.stilts) {
      this.y0 = Math.max(median + 3, maxG + 2);
      if (anyWater) this.y0 = Math.max(this.y0, maxWater + 3);
    } else {
      this.y0 = median;
      if (anyWater) this.y0 = Math.max(this.y0, maxWater + 2);
    }
    this.roofBase = this.y0 + this.floors * this.fh;
  }

  planFrontage() {
    const mx = Math.floor((this.bx0 + this.bx1) / 2), mz = Math.floor((this.bz0 + this.bz1) / 2);
    this.door = this.front === 'north' ? [mx, this.y0, this.bz0] : this.front === 'south' ? [mx, this.y0, this.bz1]
      : this.front === 'west' ? [this.bx0, this.y0, mz] : [this.bx1, this.y0, mz];
    const f = DIRS[this.front];
    let [cx, , cz] = this.door;
    cx += f[0]; cz += f[2];
    while (this.inPlot(cx, cz)) { this.reserved.add(cx + ',' + cz); cx += f[0]; cz += f[2]; }
    for (const [x, , z] of this.porchCells()) this.reserved.add(x + ',' + z);
  }

  porchCells() {
    const cells = [];
    for (let k = 1; k <= this.porchDepth; k++) {
      if (this.front === 'north' || this.front === 'south') {
        const z = this.front === 'north' ? this.bz0 - k : this.bz1 + k;
        for (let x = this.bx0; x <= this.bx1; x++) cells.push([x, this.y0 - 1, z]);
      } else {
        const x = this.front === 'west' ? this.bx0 - k : this.bx1 + k;
        for (let z = this.bz0; z <= this.bz1; z++) cells.push([x, this.y0 - 1, z]);
      }
    }
    return cells;
  }

  // ---------------------------------------------------------------- terrain
  clearTrees() {
    const p = this.plot;
    for (let x = p.x0; x <= p.x1; x++) for (let z = p.z0; z <= p.z1; z++) {
      const g = this.t.ground(x, z);
      for (let y = g + 1; y <= g + 32; y++) if (TREE.test(blockName(this.t.at(x, y, z)))) this.dig(x, y, z);
    }
  }

  landscape() {
    const ring = 3, p = this.plot;
    for (let x = p.x0; x <= p.x1; x++) for (let z = p.z0; z <= p.z1; z++) {
      if (this.inBuilding(x, z) || this.reserved.has(x + ',' + z) || this.t.water(x, z) !== null) continue;
      const d = Math.max(this.bx0 - x, x - this.bx1, this.bz0 - z, z - this.bz1);
      if (d > ring) continue;
      const g = this.t.ground(x, z);
      const tt = d / (ring + 1);
      const target = Math.round((this.y0 - 1) * (1 - tt) + g * tt);
      if (target > g) {
        for (let y = g + 1; y < target; y++) this.put(x, y, z, 'minecraft:dirt');
        this.put(x, target, z, bs('grass_block', { snowy: false }));
        this.dig(x, target + 1, z);
      } else if (target < g) {
        for (let y = target + 1; y <= g + 1; y++) this.dig(x, y, z);
        this.put(x, target, z, bs('grass_block', { snowy: false }));
      }
    }
  }

  // ---------------------------------------------------------------- structure
  axis(name, a) { return isLog(name) ? bs(name, { axis: a }) : bs(name); }

  foundation() {
    for (let x = this.bx0; x <= this.bx1; x++) for (let z = this.bz0; z <= this.bz1; z++) {
      const g = this.t.ground(x, z);
      if (this.stilts) {
        if (this.isStiltPost(x, z)) for (let y = g + 1; y <= this.y0 - 2; y++) this.put(x, y, z, this.axis(this.foundMat(x, y, z), 'y'));
      } else if (this.onPerimeter(x, z)) {
        for (let y = g + 1; y <= this.y0 - 2; y++) this.put(x, y, z, bs(this.foundMat(x, y, z)));
      }
    }
  }

  isStiltPost(x, z) {
    const cx = x === this.bx0 || x === this.bx1, cz = z === this.bz0 || z === this.bz1;
    if (cx && cz) return true;
    if (cz) return (x - this.bx0) % 3 === 0;
    if (cx) return (z - this.bz0) % 3 === 0;
    return false;
  }

  levels() { return this.floors + ((this.st.roof || 'gable') === 'flat' ? 0 : 1); }
  hasLadder() { return this.levels() > 1 && this.bx1 - this.bx0 >= 4 && this.bz1 - this.bz0 >= 4; }
  ladderCell() {
    const back = OPP[this.front];
    if (back === 'north') return [this.bx0 + 2, this.bz0 + 1];
    if (back === 'south') return [this.bx1 - 2, this.bz1 - 1];
    if (back === 'west') return [this.bx0 + 1, this.bz1 - 2];
    return [this.bx1 - 1, this.bz0 + 2];
  }

  floorsPass() {
    const [lx, lz] = this.ladderCell();
    const flat = (this.st.roof || 'gable') === 'flat';
    for (let f = 0; f <= this.floors; f++) {
      const y = this.y0 - 1 + f * this.fh;
      const ceiling = f === this.floors;
      for (let x = this.bx0; x <= this.bx1; x++) for (let z = this.bz0; z <= this.bz1; z++) {
        if (f === 0 && this.onPerimeter(x, z)) {
          const along = (z === this.bz0 || z === this.bz1) ? 'x' : 'z';
          this.put(x, y, z, this.stilts ? this.axis(this.foundMat(x, y, z), along) : bs(this.foundMat(x, y, z)));
          continue;
        }
        if (this.onPerimeter(x, z)) continue;
        if (f > 0 && this.hasLadder() && x === lx && z === lz) { this.dig(x, y, z); continue; }
        this.put(x, y, z, bs(ceiling && flat ? blockName(this.m.roofFull) : this.floorMat(x, y, z)));
      }
    }
  }

  walls() {
    const fh = this.fh;
    for (let f = 0; f < this.floors; f++) {
      const yb = this.y0 + f * fh;
      for (let x = this.bx0; x <= this.bx1; x++) for (let z = this.bz0; z <= this.bz1; z++) {
        if (!this.onPerimeter(x, z)) continue;
        const corner = (x === this.bx0 || x === this.bx1) && (z === this.bz0 || z === this.bz1);
        const alongX = z === this.bz0 || z === this.bz1;
        const idx = alongX ? x - this.bx0 : z - this.bz0;
        const len = alongX ? this.bx1 - this.bx0 + 1 : this.bz1 - this.bz0 + 1;
        const post = corner || (this.st.framed && (idx % 4 === 0 || idx === len - 1));
        const doorCol = f === 0 && x === this.door[0] && z === this.door[2];
        for (let dy = 0; dy < fh; dy++) {
          const y = yb + dy, top = dy === fh - 1;
          if (doorCol && dy <= 1) this.putDoor(x, y, z, dy === 0);
          else if (post) this.put(x, y, z, this.axis(this.frameMat(x, y, z), 'y'));
          else if (top && this.st.framed) this.put(x, y, z, this.axis(this.frameMat(x, y, z), alongX ? 'x' : 'z'));
          else if (this.isWindow(idx, len, dy, x, z)) {
            this.put(x, y, z, bs(blockName(this.m.window)));
            this.windows.push({ pos: [x, y, z], out: this.outward(x, z), along: alongX ? 'east' : 'south' });
          } else this.put(x, y, z, bs(this.wallMat(x, y, z)));
        }
      }
    }
  }

  isWindow(idx, len, dy, x, z) {
    if (dy < 1 || dy > 2 || len < 4 || idx <= 0 || idx >= len - 1) return false;
    if (Math.abs(x - this.door[0]) + Math.abs(z - this.door[2]) <= 1) return false;
    if (this.hasLadder()) {
      const [lx, lz] = this.ladderCell(), b = DIRS[OPP[this.front]];
      if (lx + b[0] === x && lz + b[2] === z) return false;
    }
    const wide = (this.d.windowWidth || 1) >= 2;
    if (this.st.framed) return idx % 4 === 2 || (wide && idx % 4 === 1);
    const sp = Math.max(wide ? 3 : 2, this.st.windowSpacing || 3);
    const m = idx % sp;
    return m === (sp >> 1) || (wide && m === (sp >> 1) + 1 && idx + 1 < len - 1);
  }

  putDoor(x, y, z, lower) {
    this.put(x, y, z, bs(blockName(this.m.door), { facing: OPP[this.front], half: lower ? 'lower' : 'upper', hinge: 'left', open: false, powered: false }));
  }

  ladder() {
    if (!this.hasLadder()) return;
    const [lx, lz] = this.ladderCell();
    const top = this.y0 - 1 + (this.levels() - 1) * this.fh;
    for (let y = this.y0; y <= top; y++) this.put(lx, y, lz, bs('ladder', { facing: this.front, waterlogged: false }));
  }

  // ---------------------------------------------------------------- roofs
  stair(facing, half = 'bottom') {
    return bs(blockName(this.m.roofStairs), { facing, half, shape: 'straight', waterlogged: false });
  }

  roof() {
    const r = this.st.roof || 'gable';
    if (r === 'hip') this.hipRoof();
    else if (r === 'flat') this.flatRoof();
    else this.gableRoof();
  }

  gableRoof() {
    const alongX = this.bx1 - this.bx0 >= this.bz1 - this.bz0;
    const spanMin = alongX ? this.bz0 - 1 : this.bx0 - 1;
    const spanLen = alongX ? this.bz1 - this.bz0 + 3 : this.bx1 - this.bx0 + 3;
    const runMin = alongX ? this.bx0 - 1 : this.bz0 - 1, runMax = alongX ? this.bx1 + 1 : this.bz1 + 1;
    const up = alongX ? 'south' : 'east', down = OPP[up];
    const pos = (run, span, y) => (alongX ? [run, y, span] : [span, y, run]);
    const rows = (spanLen - 1) >> 1;
    for (let i = 0; i <= rows; i++) {
      const y = this.roofBase - 1 + i, sA = i, sB = spanLen - 1 - i;
      for (let t = runMin; t <= runMax; t++) {
        if (sA === sB) this.put(...pos(t, spanMin + sA, y), bs(blockName(this.m.roofFull)));
        else if (sA < sB) {
          this.put(...pos(t, spanMin + sA, y), this.stair(up));
          this.put(...pos(t, spanMin + sB, y), this.stair(down));
        }
      }
      this.roofTop = y;
    }
    if (spanLen % 2 === 0) {
      const y = this.roofBase + rows;
      for (let t = runMin; t <= runMax; t++) {
        this.put(...pos(t, spanMin + rows, y), bs(blockName(this.m.roofSlab), { type: 'bottom', waterlogged: false }));
        this.put(...pos(t, spanMin + rows + 1, y), bs(blockName(this.m.roofSlab), { type: 'bottom', waterlogged: false }));
      }
      this.roofTop = y;
    }
    // gable-end triangles
    for (const end of alongX ? [this.bx0, this.bx1] : [this.bz0, this.bz1]) {
      for (let k = 1; k <= rows; k++) {
        const y = this.roofBase - 1 + k;
        for (let s = k + 1; s <= spanLen - 2 - k; s++) {
          const [x, yy, z] = pos(end, spanMin + s, y);
          const centre = s === ((spanLen - 1) >> 1) || s === (spanLen >> 1);
          if (k === 1 && centre && spanLen >= 7) this.put(x, yy, z, bs(blockName(this.m.window)));
          else if (this.st.framed && centre) this.put(x, yy, z, this.axis(this.frameMat(x, yy, z), 'y'));
          else this.put(x, yy, z, bs(this.wallMat(x, yy, z)));
        }
      }
    }
    const frontLow = alongX ? this.front === 'north' : this.front === 'west';
    const frontHigh = alongX ? this.front === 'south' : this.front === 'east';
    let eaveLow = 0, eaveHigh = spanLen - 1;
    if (this.d.porchRoof && (frontLow || frontHigh)) {
      const s = frontLow ? -1 : spanLen, y = this.roofBase - 1;
      for (let t = runMin; t <= runMax; t++) this.put(...pos(t, spanMin + s, y), this.stair(frontLow ? up : down));
      const len = runMax - runMin, doorT = alongX ? this.door[0] : this.door[2];
      for (let t = runMin; t <= runMax; t++) {
        const end = t === runMin || t === runMax;
        const mid = len > 10 && t === runMin + (len >> 1) && Math.abs(t - doorT) > 1;
        if (!end && !mid) continue;
        const [cx, , cz] = pos(t, spanMin + s, y);
        const g = this.t.ground(cx, cz);
        for (let py = Math.min(g + 1, this.y0); py <= y - 1; py++) this.put(cx, py, cz, this.fence(blockName(this.m.trim)));
      }
      if (frontLow) eaveLow = -1; else eaveHigh = spanLen;
    }
    if (this.d.eaveTrim) {
      for (const s of [eaveLow, eaveHigh]) {
        const out = s <= 0 ? down : up;
        for (let t = runMin; t <= runMax; t++) this.eaveTrimCells.push({ pos: pos(t, spanMin + s, this.roofBase - 2), out });
      }
    }
    if (this.d.ridgeCap) {
      const cap = this.thin(blockName(this.d.ridgeCap), 'north', 'bottom');
      if (spanLen % 2 === 1) {
        for (let t = runMin; t <= runMax; t++) this.put(...pos(t, spanMin + rows, this.roofTop + 1), cap);
        this.roofTop++;
      } else {
        for (let t = runMin; t <= runMax; t++) {
          this.put(...pos(t, spanMin + rows, this.roofTop), cap);
          this.put(...pos(t, spanMin + rows + 1, this.roofTop), cap);
        }
      }
    }
  }

  hipRoof() {
    for (let k = 0; ; k++) {
      const y = this.roofBase - 1 + k;
      const ax = this.bx0 - 1 + k, bx = this.bx1 + 1 - k, az = this.bz0 - 1 + k, bz = this.bz1 + 1 - k;
      if (ax > bx || az > bz) break;
      this.roofTop = y;
      if (bx - ax <= 1 || bz - az <= 1) {
        for (let x = ax; x <= bx; x++) for (let z = az; z <= bz; z++) this.put(x, y, z, bs(blockName(this.m.roofFull)));
        this.put((ax + bx) >> 1, y + 1, (az + bz) >> 1, bs(blockName(this.m.roofSlab), { type: 'bottom', waterlogged: false }));
        this.roofTop = y + 1;
        break;
      }
      const trim = k === 0 && this.d.eaveTrim;
      for (let x = ax; x <= bx; x++) {
        this.put(x, y, az, this.stair('south')); this.put(x, y, bz, this.stair('north'));
        if (trim) { this.eaveTrimCells.push({ pos: [x, y - 1, az], out: 'north' }); this.eaveTrimCells.push({ pos: [x, y - 1, bz], out: 'south' }); }
      }
      for (let z = az + 1; z < bz; z++) {
        this.put(ax, y, z, this.stair('east')); this.put(bx, y, z, this.stair('west'));
        if (trim) { this.eaveTrimCells.push({ pos: [ax, y - 1, z], out: 'west' }); this.eaveTrimCells.push({ pos: [bx, y - 1, z], out: 'east' }); }
      }
    }
  }

  flatRoof() {
    const y = this.roofBase;
    for (let x = this.bx0; x <= this.bx1; x++) for (let z = this.bz0; z <= this.bz1; z++) {
      if (this.onPerimeter(x, z)) this.put(x, y, z, this.fence(blockName(this.m.trim)));
    }
    this.roofTop = y;
  }

  /** Thin trim: trapdoors closed in the top half, slabs as top slabs, logs lying along. */
  thin(name, out, half = 'top') {
    if (isTrapdoor(name)) return bs(name, { facing: out, half, open: false, powered: false, waterlogged: false });
    if (isSlab(name)) return bs(name, { type: half, waterlogged: false });
    if (isLog(name)) return bs(name, { axis: out === 'north' || out === 'south' ? 'x' : 'z' });
    return bs(name);
  }

  fence(name) { return bs(name); } // connections are filled in by connectShapes()

  // ---------------------------------------------------------------- details
  chimney(end) {
    const alongX = this.bx1 - this.bx0 >= this.bz1 - this.bz0;
    const x = alongX ? (end === 0 ? this.bx0 - 1 : this.bx1 + 1) : (this.bx0 + this.bx1) >> 1;
    const z = alongX ? (this.bz0 + this.bz1) >> 1 : (end === 0 ? this.bz0 - 1 : this.bz1 + 1);
    const g = this.t.ground(x, z), top = this.roofTop + 2;
    for (let y = Math.min(g + 1, this.y0 - 1); y <= top; y++) this.put(x, y, z, bs(this.chimMat(x, y, z)));
    if (this.d.chimneyCap) this.put(x, top + 1, z, bs(blockName(this.d.chimneyCap)));
  }

  porch() {
    const cells = this.porchCells();
    const fl = new Set(cells.map(c => c.join(',')));
    const has = (x, y, z) => fl.has([x, y, z].join(','));
    const f = DIRS[this.front], cw = DIRS[CW[this.front]], ccw = DIRS[CCW[this.front]];
    for (const [x, y, z] of cells) this.put(x, y, z, bs(this.floorMat(x, y, z)));
    for (const [x, y, z] of cells) {
      const outer = !has(x + f[0], y, z + f[2]);
      const side = !has(x + cw[0], y, z + cw[2]) || !has(x + ccw[0], y, z + ccw[2]);
      const doorLine = (this.front === 'north' || this.front === 'south') ? x === this.door[0] : z === this.door[2];
      if ((outer && !doorLine) || (side && !outer)) this.put(x, y + 1, z, this.fence(blockName(this.m.trim)));
      if (outer && side) {
        const g = this.t.ground(x, z);
        for (let yy = g + 1; yy < this.y0 - 1; yy++) this.put(x, yy, z, this.axis(this.foundMat(x, yy, z), 'y'));
      }
    }
  }

  stepsAndPath() {
    const f = DIRS[this.front];
    let cx = this.door[0] + f[0] * (1 + this.porchDepth), cz = this.door[2] + f[2] * (1 + this.porchDepth);
    while (this.inPlot(cx, cz) && this.t.ground(cx, cz) >= this.y0) {
      for (let y = this.y0; y <= this.t.ground(cx, cz) + 1; y++) this.dig(cx, y, cz);
      this.pathAt(cx, this.y0 - 1, cz);
      cx += f[0]; cz += f[2];
    }
    let k = 0;
    const found = blockName(typeof this.m.foundation === 'string' ? this.m.foundation : Object.keys(this.m.foundation)[0]);
    const stepBlock = /cobble/.test(found) && !/deepslate/.test(found) ? 'cobblestone_stairs'
      : /deepslate/.test(found) ? 'cobbled_deepslate_stairs' : blockName(this.m.roofStairs);
    while (this.inPlot(cx, cz)) {
      const g = this.t.ground(cx, cz), y = this.y0 - 1 - k;
      if (y <= g) break;
      this.put(cx, y, cz, bs(stepBlock, { facing: OPP[this.front], half: 'bottom', shape: 'straight', waterlogged: false }));
      for (let fy = g + 1; fy < y; fy++) this.put(cx, fy, cz, bs(this.foundMat(cx, fy, cz)));
      this.dig(cx, y + 1, cz); this.dig(cx, y + 2, cz);
      k++; cx += f[0]; cz += f[2];
    }
    while (this.inPlot(cx, cz)) {
      if (this.t.water(cx, cz) === null) this.pathAt(cx, this.t.ground(cx, cz), cz);
      cx += f[0]; cz += f[2];
    }
  }

  pathAt(x, y, z) {
    const cur = blockName(this.get(x, y, z) || this.t.at(x, y, z));
    if (/^(grass_block|dirt|coarse_dirt|podzol)$/.test(cur)) {
      this.put(x, y, z, 'minecraft:dirt_path');
      this.dig(x, y + 1, z);
    }
  }

  leaves(name) { return isLeaves(name) ? bs(name, { persistent: true, distance: 7, waterlogged: false }) : bs(name); }

  details() {
    const dt = this.d, along = CW[this.front];
    const winSet = new Set(this.windows.map(w => w.pos.join(',')));
    for (const w of this.windows) {
      const [x, y, z] = w.pos, o = DIRS[w.out];
      if (dt.shutters) {
        for (const side of [w.along, OPP[w.along]]) {
          const s = DIRS[side], bx = x + s[0], bz = z + s[2];
          if (winSet.has([bx, y, bz].join(','))) continue;
          const px = bx + o[0], pz = bz + o[2];
          if (!this.has(px, y, pz)) {
            this.put(px, y, pz, bs(blockName(dt.shutters), { facing: w.out, half: 'bottom', open: true, powered: false, waterlogged: false }));
          }
        }
      }
      if (dt.planter && !winSet.has([x, y - 1, z].join(','))) {
        const px = x + o[0], pz = z + o[2];
        if (!this.has(px, y - 1, pz)) this.put(px, y - 1, pz, this.leaves(blockName(dt.planter)));
      }
    }
    if (dt.eaveTrim) {
      for (const c of this.eaveTrimCells) if (!this.has(...c.pos)) this.put(...c.pos, this.thin(blockName(dt.eaveTrim), c.out));
    }
    const [dx, dy, dz] = this.door, a = DIRS[along], f = DIRS[this.front];
    if (dt.doorFrame) {
      for (let k = -1; k <= 1; k++) this.put(dx + a[0] * k, dy + 2, dz + a[2] * k, this.axis(blockName(dt.doorFrame), along === 'east' || along === 'west' ? 'x' : 'z'));
    }
    if (dt.doorStep) {
      const sx = dx + f[0] * (1 + this.porchDepth), sz = dz + f[2] * (1 + this.porchDepth), sy = this.y0 - 1;
      const cur = this.get(sx, sy, sz);
      if (!cur || !isStairs(blockName(cur))) this.put(sx, sy, sz, this.thin(blockName(dt.doorStep), this.front, 'top'));
    }
    if (dt.lantern) {
      const hx = dx + f[0] + a[0] * 2, hz = dz + f[2] + a[2] * 2, hy = this.roofBase - 2;
      const above = this.get(hx, hy + 1, hz);
      if (above && blockName(above) !== 'air' && !this.has(hx, hy, hz)) {
        this.put(hx, hy, hz, bs(blockName(dt.lantern), { hanging: true, waterlogged: false }));
      } else if (!this.stilts) {
        const bx = dx + f[0] * (1 + this.porchDepth) + a[0] * 2, bz = dz + f[2] * (1 + this.porchDepth) + a[2] * 2;
        if (!this.has(bx, this.y0, bz) && this.inPlot(bx, bz)) {
          this.put(bx, this.y0, bz, this.fence(blockName(this.m.trim)));
          this.put(bx, this.y0 + 1, bz, bs(blockName(dt.lantern), { hanging: false, waterlogged: false }));
        }
      }
    }
    if (dt.props?.length && !this.stilts) {
      const left = CCW[this.front], l = DIRS[left], back = DIRS[OPP[this.front]];
      const cx = this.front === 'west' ? this.bx0 : this.front === 'east' ? this.bx1 : left === 'west' ? this.bx0 : this.bx1;
      const cz = this.front === 'north' ? this.bz0 : this.front === 'south' ? this.bz1 : left === 'north' ? this.bz0 : this.bz1;
      dt.props.forEach((p, i) => {
        const x = cx + l[0] + back[0] * i, z = cz + l[2] + back[2] * i;
        if (!this.has(x, this.y0, z) && this.inPlot(x, z)) this.put(x, this.y0, z, bs(blockName(p)));
      });
    }
    if (dt.bushes && (dt.bushAmount ?? 0.35) > 0 && !this.stilts) {
      for (let x = this.bx0 - 1; x <= this.bx1 + 1; x++) for (let z = this.bz0 - 1; z <= this.bz1 + 1; z++) {
        if (this.inBuilding(x, z) || this.reserved.has(x + ',' + z) || !this.inPlot(x, z) || this.t.water(x, z) !== null) continue;
        if (this.has(x, this.y0, z) || chance(x, this.y0, z, 11) >= (dt.bushAmount ?? 0.35)) continue;
        this.put(x, this.y0, z, this.leaves(blockName(dt.bushes)));
      }
    }
    if (dt.vines > 0) {
      const topY = this.roofBase - 2;
      for (let x = this.bx0; x <= this.bx1; x++) for (let z = this.bz0; z <= this.bz1; z++) {
        const corner = (x === this.bx0 || x === this.bx1) && (z === this.bz0 || z === this.bz1);
        if (!this.onPerimeter(x, z) || corner || chance(x, topY, z, 23) >= dt.vines) continue;
        const o = this.outward(x, z), ov = DIRS[o];
        let start = topY;
        while (start > this.y0 && this.has(x + ov[0], start, z + ov[2])) start--;
        const len = 1 + Math.floor(chance(x, topY, z, 29) * 3);
        for (let y = start; y > start - len && y >= this.y0; y--) {
          if (winSet.has([x, y, z].join(',')) || this.has(x + ov[0], y, z + ov[2])) break;
          const props = { north: false, east: false, south: false, west: false, up: false };
          props[OPP[o]] = true;
          this.put(x + ov[0], y, z + ov[2], bs('vine', props));
        }
      }
    }
    if (dt.roofAccent && (dt.roofAccentAmount ?? 0.15) > 0) {
      const tops = [];
      for (const [k, s] of this.out) {
        const [x, y, z] = k.split(',').map(Number);
        if (isStairs(blockName(s)) && y >= this.roofBase - 1) tops.push([x, y + 1, z]);
      }
      for (const [x, y, z] of tops) {
        if (!this.has(x, y, z) && chance(x >> 1, y, z >> 1, 31) < (dt.roofAccentAmount ?? 0.15)) this.put(x, y, z, this.leaves(blockName(dt.roofAccent)));
      }
    }
  }

  clearInterior() {
    for (let x = this.bx0 - 1; x <= this.bx1 + 1; x++) for (let z = this.bz0 - 1; z <= this.bz1 + 1; z++) {
      const from = this.inBuilding(x, z) ? this.y0 : this.roofBase - 1;
      for (let y = from; y <= this.roofTop; y++) if (!this.has(x, y, z)) this.dig(x, y, z);
    }
  }

  applyRoofMix() {
    if (!this.roofMix) return;
    const base = blockName(this.m.roofStairs);
    for (const [k, s] of this.out) {
      if (blockName(s) !== base) continue;
      const [x, y, z] = k.split(',').map(Number);
      const pick = this.roofMix(x, y, z);
      if (isStairs(pick)) this.out.set(k, 'minecraft:' + pick + s.slice(s.indexOf('[')));
    }
  }

  // ---------------------------------------------------------------- connections
  /** What the final world will have at a spot (design over terrain). */
  finalAt(x, y, z) { return this.get(x, y, z) || this.t.at(x, y, z); }

  connectShapes() {
    for (const [k, s] of [...this.out]) {
      const n = blockName(s);
      const [x, y, z] = k.split(',').map(Number);
      if (isStairs(n)) this.out.set(k, this.stairShape(s, x, y, z));
      else if (/_fence$|_pane$|^glass_pane$|^iron_bars$|_wall$/.test(n)) this.out.set(k, this.connect(s, n, x, y, z));
    }
  }

  props(s) {
    const i = s.indexOf('[');
    const p = {};
    if (i >= 0) for (const kv of s.slice(i + 1, -1).split(',')) { const [a, b] = kv.split('='); p[a] = b; }
    return p;
  }

  stairShape(s, x, y, z) {
    const p = this.props(s), facing = p.facing, half = p.half;
    const look = (dir) => {
      const v = DIRS[dir], o = this.finalAt(x + v[0], y, z + v[2]);
      if (!isStairs(blockName(o))) return null;
      const q = this.props(o);
      return q.half === half ? q.facing : null;
    };
    let shape = 'straight';
    const behind = look(facing);
    if (behind && behind !== facing && behind !== OPP[facing]) {
      const side = OPP[behind];
      if (look(side) !== facing) shape = behind === CCW[facing] ? 'outer_left' : 'outer_right';
    }
    if (shape === 'straight') {
      const front = look(OPP[facing]);
      if (front && front !== facing && front !== OPP[facing]) {
        if (look(front) !== facing) shape = front === CCW[facing] ? 'inner_left' : 'inner_right';
      }
    }
    p.shape = shape;
    return bs(blockName(s), p);
  }

  connect(s, n, x, y, z) {
    const p = this.props(s);
    const isWall = n.endsWith('_wall');
    for (const dir of ['north', 'east', 'south', 'west']) {
      const v = DIRS[dir], o = blockName(this.finalAt(x + v[0], y, z + v[2]));
      const sameKind = (n.endsWith('_fence') && o.endsWith('_fence')) || (/pane|iron_bars/.test(n) && /pane|iron_bars/.test(o))
        || (isWall && o.endsWith('_wall'));
      const solid = !isEmpty('minecraft:' + o) && !NOT_GROUND.test(o) && !/_fence|_pane|iron_bars|_wall|door|stairs|slab|trapdoor|lantern|ladder|leaves/.test(o);
      const c = sameKind || solid || (o.endsWith('fence_gate') && n.endsWith('_fence'));
      p[dir] = isWall ? (c ? 'low' : 'none') : c;
    }
    if (isWall) p.up = true;
    p.waterlogged = false;
    return bs(n, p);
  }
}
