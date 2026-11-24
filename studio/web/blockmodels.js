// Minecraft block model resolution: blockstates -> model parts (elements + textures + rotation).
// Reads the vanilla JSON served from /mc/ (extracted from the player's own client jar).

const jsonCache = new Map();

async function getJSON(path) {
  if (!jsonCache.has(path)) {
    jsonCache.set(path, fetch(`/mc/${path}`).then(r => (r.ok ? r.json() : null)).catch(() => null));
  }
  return jsonCache.get(path);
}

export function parseState(str) {
  const i = str.indexOf('[');
  let name = i < 0 ? str : str.slice(0, i);
  const props = {};
  if (i >= 0) {
    for (const kv of str.slice(i + 1, -1).split(',')) {
      if (!kv) continue;
      const [k, v] = kv.split('=');
      props[k] = v;
    }
  }
  if (name.startsWith('minecraft:')) name = name.slice(10);
  return { name, props };
}

function stripNs(s) {
  return s.startsWith('minecraft:') ? s.slice(10) : s;
}

const modelCache = new Map();

/** Model with the parent chain merged: { textures, elements, ambientocclusion }. */
async function loadModel(ref) {
  const name = stripNs(ref);
  if (modelCache.has(name)) return modelCache.get(name);
  const p = (async () => {
    if (name.startsWith('builtin/')) return { textures: {}, elements: null };
    const json = await getJSON(`models/${name}.json`);
    if (!json) return { textures: {}, elements: null };
    let parent = { textures: {}, elements: null };
    if (json.parent) parent = await loadModel(json.parent);
    return {
      textures: { ...parent.textures, ...(json.textures || {}) },
      elements: json.elements || parent.elements,
    };
  })();
  modelCache.set(name, p);
  return p;
}

/** '#side' -> 'block/stone' (follows texture variables). */
export function resolveTexture(textures, ref) {
  let t = ref;
  for (let i = 0; i < 10 && t && t.startsWith('#'); i++) t = textures[t.slice(1)];
  return t && !t.startsWith('#') ? stripNs(t) : null;
}

function matchesWhen(when, props) {
  if (!when) return true;
  if (when.OR) return when.OR.some(w => matchesWhen(w, props));
  if (when.AND) return when.AND.every(w => matchesWhen(w, props));
  return Object.entries(when).every(([k, v]) => String(v).split('|').includes(String(props[k])));
}

function variantMatches(key, props) {
  if (key === '' || key === 'normal') return true;
  return key.split(',').every(kv => {
    const [k, v] = kv.split('=');
    return String(props[k]) === v;
  });
}

const partCache = new Map();

/**
 * Model parts for a block state string. Each part: { model: {textures, elements}, x, y }.
 * Water/lava come back as { fluid: 'water' | 'lava' }.
 */
export async function resolveState(str) {
  if (partCache.has(str)) return partCache.get(str);
  const p = (async () => {
    const { name, props } = parseState(str);
    if (name === 'air' || name === 'cave_air' || name === 'void_air') return [];
    if (name === 'water' || name === 'bubble_column') return [{ fluid: 'water' }];
    if (name === 'lava') return [{ fluid: 'lava' }];
    const bs = await getJSON(`blockstates/${name}.json`);
    if (!bs) return [];
    const picks = [];
    if (bs.variants) {
      for (const [key, v] of Object.entries(bs.variants)) {
        if (variantMatches(key, props)) {
          picks.push(Array.isArray(v) ? v[0] : v);
          break;
        }
      }
    } else if (bs.multipart) {
      for (const part of bs.multipart) {
        if (matchesWhen(part.when, props)) picks.push(Array.isArray(part.apply) ? part.apply[0] : part.apply);
      }
    }
    const parts = [];
    for (const v of picks) {
      const model = await loadModel(v.model);
      if (model.elements) parts.push({ model, x: v.x || 0, y: v.y || 0, name });
    }
    return parts;
  })();
  partCache.set(str, p);
  return p;
}
