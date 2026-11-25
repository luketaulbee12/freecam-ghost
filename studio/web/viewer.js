import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { buildMeshes } from './mesher.js';
import { Terrain, design as runDesign, blockName } from './designer.js';

const $ = id => document.getElementById(id);
const status = msg => { $('status').textContent = msg; };

// ------------------------------------------------------------------------------- three.js
const view = $('view');
const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.outputColorSpace = THREE.SRGBColorSpace;
view.prepend(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x9ec8ff);
const camera = new THREE.PerspectiveCamera(55, 1, 0.1, 2000);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
const overlay = new THREE.Group();
scene.add(overlay);

function resize() {
  const r = view.getBoundingClientRect();
  renderer.setSize(r.width, r.height);
  camera.aspect = r.width / Math.max(1, r.height);
  camera.updateProjectionMatrix();
}
window.addEventListener('resize', resize);
resize();
(function loop() {
  controls.update();
  renderer.render(scene, camera);
  requestAnimationFrame(loop);
})();

// ------------------------------------------------------------------------------- state
const S = {
  worlds: [], exports: [], blueprints: [], styles: [],
  site: null, terrain: null, origin: [0, 0, 0],
  plot: null, picking: null,
  style: null, result: null, blueprint: null,
  meshes: null, framed: false,
};

async function getJSON(url) {
  const r = await fetch(url);
  const j = await r.json();
  if (j && j.error) throw new Error(j.error);
  return j;
}

async function init() {
  [S.worlds, S.exports, S.blueprints, S.styles] = await Promise.all([
    getJSON('/api/worlds'), getJSON('/api/sites'), getJSON('/api/blueprints'), getJSON('/api/styles')]);
  $('world').innerHTML = S.worlds.map((w, i) =>
    `<option value="${i}">${w.name}  ·  ${w.gameDir.includes('curseforge') ? 'CurseForge' : 'test client'}</option>`).join('');
  $('export').innerHTML = '<option value="">…or an export from /design export</option>'
    + S.exports.map((s, i) => `<option value="${i}">${s.name}</option>`).join('');
  $('bp').innerHTML = S.blueprints.map((b, i) => `<option value="${i}">${b.name} · ${b.world}</option>`).join('');
  $('style').innerHTML = S.styles.filter(s => !s.error).map((s, i) =>
    `<option value="${i}">${s.name || s.id}</option>`).join('');
  selectStyle(0);
  await refreshPictures();
}

// ------------------------------------------------------------------------------- terrain
async function loadArea() {
  const w = S.worlds[$('world').value];
  const x = $('ax').value, z = $('az').value, r = $('ar').value || 32;
  if (!w || x === '' || z === '') { status('Pick a world and type the X and Z of the spot.'); return; }
  status('Reading terrain from the world files…');
  const site = await getJSON(`/api/area?world=${encodeURIComponent(w.path)}&x=${x}&z=${z}&r=${r}`);
  if (site.blocksB64) {  // compact transfer: little-endian uint16 palette indices
    const bin = atob(site.blocksB64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    site.blocks = new Uint16Array(bytes.buffer);
    delete site.blocksB64;
  }
  setSite(site, w);
}

// Superflat: flat grass sized to the selected blueprint, so a build can be judged without any terrain.
async function loadFlat() {
  const b = S.blueprints[$('bp').value];
  if (!b) { status('Pick a blueprint under "Show existing blueprint" first.'); return; }
  $('showBp').checked = true;
  S.blueprint = await getJSON('/api/blueprint?path=' + encodeURIComponent(b.path));
  const solid = S.blueprint.blocks.filter(([, , , pi]) => !/:(air|cave_air|void_air)$/.test(S.blueprint.palette[pi]));
  if (!solid.length) { status('That blueprint is empty.'); return; }
  let [x0, y0, z0, x1, y1, z1] = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
  for (const [x, y, z] of solid) {
    x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
    x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
  }
  const m = 12, ground = y0 - 1;
  const origin = [x0 - m, ground - 2, z0 - m];
  const w = x1 - x0 + 1 + 2 * m, d = z1 - z0 + 1 + 2 * m, h = y1 - ground + 6;
  const blocks = new Uint16Array(w * d * h);  // palette: 0 air, 1 dirt, 2 grass
  for (let x = 0; x < w; x++) for (let z = 0; z < d; z++) {
    const i = (x * d + z) * h;
    blocks[i] = 1; blocks[i + 1] = 1; blocks[i + 2] = 2;
  }
  await setSite({ name: 'superflat', origin, size: [w, h, d], site: [x0, z0, x1, z1],
            palette: ['minecraft:air', 'minecraft:dirt', 'minecraft:grass_block[snowy=false]'], blocks }, null);
  S.plot = { x0, z0, x1, z1 };
  status(`Superflat preview of "${b.name}" (${x1 - x0 + 1} x ${z1 - z0 + 1}, ${y1 - y0 + 1} tall).`);
}

async function loadExport() {
  const e = S.exports[$('export').value];
  if (!e) return;
  status('Loading export…');
  const site = await getJSON('/api/site?path=' + encodeURIComponent(e.path));
  setSite(site, null);
  const [x0, z0, x1, z1] = site.site;
  S.plot = { x0, z0, x1, z1 };
  showPlot();
  regenerate();
}

function setSite(site, world) {
  S.site = site;
  S.world = world;
  S.terrain = new Terrain(site);
  S.origin = site.origin;
  S.plot = null;
  S.result = null;
  S.framed = false;
  showPlot();
  return rebuild().then(() => status('Terrain loaded. Now "Pick plot" and click two corners on the ground.'));
}

// ------------------------------------------------------------------------------- design
function selectStyle(i) {
  const st = S.styles.filter(s => !s.error)[i];
  if (!st) return;
  S.style = JSON.parse(JSON.stringify(st));
  syncControls();
  $('bpName').value = S.style.name || S.style.id;
  regenerate();
}

function syncControls() {
  for (const k of ['floors', 'floorHeight', 'maxWidth', 'maxDepth', 'roof', 'foundation']) {
    const el = $('p_' + k);
    const v = S.style[k];
    el.value = v ?? (k === 'roof' ? 'gable' : k === 'foundation' ? 'auto' : k === 'floors' ? 1 : k === 'floorHeight' ? 4 : 0);
  }
  $('styleJson').value = JSON.stringify(S.style, null, 2);
}

function readControls() {
  for (const k of ['floors', 'floorHeight', 'maxWidth', 'maxDepth']) S.style[k] = Number($('p_' + k).value) || 0;
  S.style.roof = $('p_roof').value;
  S.style.foundation = $('p_foundation').value;
  $('styleJson').value = JSON.stringify(S.style, null, 2);
}

let regenTimer = null;
function regenerate() {
  clearTimeout(regenTimer);
  regenTimer = setTimeout(async () => {
    if (!S.terrain || !S.plot || !S.style) { await rebuild(); return; }
    try {
      const t0 = performance.now();
      S.result = runDesign(S.terrain, S.style, S.plot, $('front').value);
      await rebuild();
      status(`${S.style.name || S.style.id}: ${S.result.summary}\n(${Math.round(performance.now() - t0)} ms)`);
    } catch (e) {
      console.error(e);
      status('Design error: ' + e.message);
    }
  }, 120);
}

// ------------------------------------------------------------------------------- meshing
function makeGrid() {
  const site = S.site;
  const [w, h, d] = site.size;
  const [ox, oy, oz] = site.origin;
  const palette = site.palette.slice();
  const airLike = s => /:(air|cave_air|void_air)$/.test(s.split('[')[0]);
  const blocks = Int32Array.from(site.blocks, i => (airLike(palette[i]) ? -1 : i));
  const designCells = new Set();
  const lookup = new Map(palette.map((s, i) => [s, i]));
  const idOf = s => {
    if (airLike(s)) return -1;
    if (!lookup.has(s)) { lookup.set(s, palette.length); palette.push(s); }
    return lookup.get(s);
  };
  const apply = (x, y, z, s) => {
    const lx = x - ox, ly = y - oy, lz = z - oz;
    if (lx < 0 || ly < 0 || lz < 0 || lx >= w || ly >= h || lz >= d) return;
    const i = (lx * d + lz) * h + ly;
    blocks[i] = idOf(s);
    designCells.add(i);
  };
  if ($('showDesign').checked && S.result) {
    for (const [k, s] of S.result.blocks) { const [x, y, z] = k.split(',').map(Number); apply(x, y, z, s); }
  }
  if ($('showBp').checked && S.blueprint) {
    for (const [x, y, z, pi] of S.blueprint.blocks) apply(x, y, z, S.blueprint.palette[pi]);
  }
  return {
    w, h, d, palette,
    get(x, y, z) { return x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d ? -1 : blocks[(x * d + z) * h + y]; },
    isDesign(x, y, z) { return designCells.has((x * d + z) * h + y); },
  };
}

let building = false, pending = false;
async function rebuild() {
  if (!S.site) return;
  if (building) { pending = true; return; }
  building = true;
  try {
    const meshes = await buildMeshes(makeGrid(), { highlightDesign: $('tint').checked });
    if (S.meshes) for (const m of [S.meshes.solid, S.meshes.translucent]) { scene.remove(m); m.geometry.dispose(); }
    S.meshes = meshes;
    scene.add(meshes.solid, meshes.translucent);
    if (!S.framed) { frame(); S.framed = true; }
  } finally {
    building = false;
    if (pending) { pending = false; rebuild(); }
  }
}

// ------------------------------------------------------------------------------- plot picking
const raycaster = new THREE.Raycaster();
renderer.domElement.addEventListener('pointerdown', e => { S.down = [e.clientX, e.clientY]; });
renderer.domElement.addEventListener('pointerup', e => {
  if (!S.picking || !S.meshes || !S.down) return;
  if (Math.hypot(e.clientX - S.down[0], e.clientY - S.down[1]) > 5) return; // was a drag
  const r = renderer.domElement.getBoundingClientRect();
  raycaster.setFromCamera(new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1), camera);
  const hit = raycaster.intersectObject(S.meshes.solid)[0];
  if (!hit) return;
  const p = hit.point.clone().addScaledVector(hit.face.normal, -0.01);
  const wx = Math.floor(p.x) + S.origin[0], wz = Math.floor(p.z) + S.origin[2];
  S.picking.push([wx, wz]);
  if (S.picking.length === 1) {
    status(`Corner 1: ${wx}, ${wz}. Click the opposite corner.`);
  } else {
    const [[x0, z0], [x1, z1]] = S.picking;
    S.plot = { x0, z0, x1, z1 };
    S.picking = null;
    $('pickPlot').classList.remove('active');
    S.framed = false;
    showPlot();
    regenerate();
  }
});

function showPlot() {
  overlay.clear();
  if (!S.plot || !S.terrain) { $('plotInfo').textContent = 'No plot yet.'; return; }
  const p = S.plot, [ox, oy, oz] = S.origin;
  const x0 = Math.min(p.x0, p.x1), x1 = Math.max(p.x0, p.x1), z0 = Math.min(p.z0, p.z1), z1 = Math.max(p.z0, p.z1);
  const y = S.terrain.ground((x0 + x1) >> 1, (z0 + z1) >> 1) + 1 - oy;
  const box = new THREE.Box3(new THREE.Vector3(x0 - ox, y - 0.02, z0 - oz), new THREE.Vector3(x1 + 1 - ox, y + 0.1, z1 + 1 - oz));
  overlay.add(new THREE.Box3Helper(box, 0xffd34d));
  $('plotInfo').textContent = `Plot ${x1 - x0 + 1} × ${z1 - z0 + 1} at (${x0}, ${z0})`;
}

// ------------------------------------------------------------------------------- camera
function frame(kind = 'iso') {
  const [ox, oy, oz] = S.origin;
  let c, r;
  if (S.plot) {
    const p = S.plot;
    const cx = (p.x0 + p.x1) / 2 + 0.5, cz = (p.z0 + p.z1) / 2 + 0.5;
    const gy = S.terrain.ground(Math.round(cx), Math.round(cz));
    c = new THREE.Vector3(cx - ox, gy - oy + 4, cz - oz);
    r = Math.max(Math.abs(p.x1 - p.x0), Math.abs(p.z1 - p.z0)) * 0.9 + 8;
  } else {
    const [w, h, d] = S.site.size;
    c = new THREE.Vector3(w / 2, h / 2, d / 2);
    r = Math.max(w, d) * 0.7;
  }
  controls.target.copy(c);
  const f = { south: [0, 1], north: [0, -1], east: [1, 0], west: [-1, 0] }[$('front').value];
  if (kind === 'top') camera.position.copy(c).add(new THREE.Vector3(0.01, r * 2, 0.01));
  else if (kind === 'front') camera.position.copy(c).add(new THREE.Vector3(f[0] * r * 1.3, r * 0.3, f[1] * r * 1.3));
  else camera.position.copy(c).add(new THREE.Vector3((f[0] || 0.8) * r, r * 0.8, (f[1] || 0.8) * r));
  controls.update();
}

// ------------------------------------------------------------------------------- pictures
async function refreshPictures(select) {
  const pics = await getJSON('/api/pictures');
  $('picture').innerHTML = '<option value="">(none)</option>' + pics.map(p => `<option value="${p.url}">${p.name}</option>`).join('');
  if (select) { $('picture').value = select; showPicture(select); }
}

function showPicture(url) {
  $('pic').style.display = url ? 'block' : 'none';
  if (url) $('picImg').src = url;
}

async function uploadPicture(file) {
  const res = await (await fetch('/api/picture?name=' + encodeURIComponent(file.name), { method: 'POST', body: file })).json();
  await refreshPictures(res.url);
}

// ------------------------------------------------------------------------------- picture -> blueprint
async function buildFromPicture() {
  const pic = $('picture').value;
  if (!pic) { status('Pick or upload an inspiration picture first.'); return; }
  const w = S.world || S.worlds[$('world').value];
  const body = { picture: pic, width: +$('reconWidth').value || 16, name: (pic.split('/').pop() || 'Recon').replace(/\.[^.]+$/, '') };
  if (S.plot && S.world) {  // put it on the picked plot, on the real ground
    body.at = [Math.round((S.plot.x0 + S.plot.x1) / 2), Math.round((S.plot.z0 + S.plot.z1) / 2)];
    body.worldPath = S.world.path;
  }
  if (w) { body.gameDir = w.gameDir; body.worldId = w.worldId; }
  const job = await (await fetch('/api/recon', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })).json();
  if (job.error) { status('Error: ' + job.error); return; }
  const t0 = Date.now();
  for (;;) {
    await new Promise(r => setTimeout(r, 3000));
    const st = await getJSON('/api/recon_status?id=' + job.id);
    const secs = Math.round((Date.now() - t0) / 1000);
    if (st.state === 'running') { status(`Building "${job.name}" from the picture... ${st.stage} (${secs}s)`); continue; }
    if (st.state === 'failed') { status('Picture build failed:\n' + st.log.slice(-600)); return; }
    break;
  }
  S.blueprints = await getJSON('/api/blueprints');
  $('bp').innerHTML = S.blueprints.map((b, i) => `<option value="${i}">${b.name} · ${b.world}</option>`).join('');
  const i = S.blueprints.findIndex(b => b.name === job.name);
  if (i >= 0) $('bp').value = i;
  $('showBp').checked = true;
  if (S.plot && S.world) { S.blueprint = await getJSON('/api/blueprint?path=' + encodeURIComponent(S.blueprints[i].path)); await rebuild(); }
  else await loadFlat();
  status(`"${job.name}" is ready - shown here, and listed in game under J.`);
}

// ------------------------------------------------------------------------------- send to game
async function sendToGame() {
  if (!S.result) { status('Make a design first.'); return; }
  const w = S.world || S.worlds[$('world').value];
  if (!w) { status('Pick the world to send it to.'); return; }
  const blocks = [...S.result.blocks].map(([k, s]) => [...k.split(',').map(Number), s]);
  const res = await (await fetch('/api/export_blueprint', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ gameDir: w.gameDir, worldId: w.worldId, dimension: 'minecraft_overworld', name: $('bpName').value || 'Studio design', blocks }),
  })).json();
  if (res.error) { status('Error: ' + res.error); return; }
  status(`Sent ${res.blocks} blocks to the game.\nIn game, open J: "${$('bpName').value}" is listed there.\n${res.saved}`);
}

async function screenshot() {
  renderer.render(scene, camera);
  const blob = await new Promise(r => renderer.domElement.toBlob(r, 'image/png'));
  const name = (S.style?.id || 'view') + '_' + Date.now();
  const res = await (await fetch('/api/screenshot?name=' + encodeURIComponent(name), { method: 'POST', body: blob })).json();
  status('Saved ' + res.saved);
}

// ------------------------------------------------------------------------------- wiring
const guard = fn => () => fn().catch(e => { console.error(e); status('Error: ' + e.message); });
$('loadArea').onclick = guard(loadArea);
$('flat').onclick = guard(loadFlat);
$('recon').onclick = guard(buildFromPicture);
$('export').onchange = guard(loadExport);
$('pickPlot').onclick = () => {
  if (!S.site) { status('Load terrain first.'); return; }
  S.picking = [];
  $('pickPlot').classList.add('active');
  status('Click the first corner of the plot on the ground.');
};
$('front').onchange = () => { showPlot(); regenerate(); };
$('style').onchange = () => selectStyle(Number($('style').value));
for (const k of ['floors', 'floorHeight', 'maxWidth', 'maxDepth', 'roof', 'foundation']) {
  $('p_' + k).onchange = () => { readControls(); regenerate(); };
}
$('styleJson').onchange = () => {
  try { S.style = JSON.parse($('styleJson').value); syncControls(); regenerate(); }
  catch (e) { status('JSON error: ' + e.message); }
};
$('tint').onchange = $('showDesign').onchange = () => rebuild();
$('showBp').onchange = $('bp').onchange = guard(async () => {
  S.blueprint = $('showBp').checked && S.blueprints[$('bp').value]
    ? await getJSON('/api/blueprint?path=' + encodeURIComponent(S.blueprints[$('bp').value].path)) : null;
  await rebuild();
});
$('picture').onchange = () => showPicture($('picture').value);
$('upload').onchange = e => e.target.files[0] && uploadPicture(e.target.files[0]);
$('picHide').onclick = () => { $('picture').value = ''; showPicture(''); };
$('picSize').onclick = () => { const p = $('pic'); p.style.width = p.style.width === '60%' ? '' : '60%'; };
$('send').onclick = guard(sendToGame);
$('viewFront').onclick = () => S.site && frame('front');
$('viewTop').onclick = () => S.site && frame('top');
$('viewIso').onclick = () => S.site && frame('iso');
$('shot').onclick = guard(screenshot);
init().catch(e => status('Error: ' + e.message));

// for quick testing from the console
window.studio = { S, regenerate, rebuild, frame, loadArea, loadFlat };
