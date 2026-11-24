import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { buildMeshes } from './mesher.js';

const $ = id => document.getElementById(id);
const status = msg => { $('status').textContent = msg; };

// ------------------------------------------------------------------------------- three.js setup
const view = $('view');
const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.outputColorSpace = THREE.SRGBColorSpace;
view.appendChild(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x9ec8ff);
const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 2000);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;

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

// ------------------------------------------------------------------------------- data
let sites = [], blueprints = [];
let current = null; // { site, design, meshes, centre, siteBox }

async function refreshLists() {
  sites = await (await fetch('/api/sites')).json();
  blueprints = await (await fetch('/api/blueprints')).json();
  $('site').innerHTML = '<option value="auto">Read terrain around the design from world files</option>'
    + sites.map((s, i) => `<option value="${i}">Export: ${s.name}</option>`).join('');
  $('bp').innerHTML = '<option value="">(none: terrain only)</option>'
    + blueprints.map((b, i) => `<option value="${i}">${b.name}  ·  ${b.world}</option>`).join('');
}

function makeGrid(site, design, showDesign) {
  const [w, h, d] = site.size;
  const [ox, oy, oz] = site.origin;
  const palette = site.palette.slice();
  const airLike = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air']);
  const blocks = Int32Array.from(site.blocks, i => (airLike.has(palette[i]) ? -1 : i));
  const designCells = new Set();
  if (design && showDesign) {
    const lookup = new Map(palette.map((s, i) => [s, i]));
    const idOf = s => {
      if (airLike.has(s)) return -1;
      if (!lookup.has(s)) { lookup.set(s, palette.length); palette.push(s); }
      return lookup.get(s);
    };
    for (const [x, y, z, pi] of design.blocks) {
      const lx = x - ox, ly = y - oy, lz = z - oz;
      if (lx < 0 || ly < 0 || lz < 0 || lx >= w || ly >= h || lz >= d) continue;
      const i = (lx * d + lz) * h + ly;
      blocks[i] = idOf(design.palette[pi]);
      designCells.add(i);
    }
  }
  return {
    w, h, d, palette,
    get(x, y, z) {
      if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) return -1;
      return blocks[(x * d + z) * h + y];
    },
    isDesign(x, y, z) {
      return designCells.has((x * d + z) * h + y);
    },
  };
}

async function load() {
  const si = $('site').value;
  const bpPath = $('bp').value !== '' ? blueprints[$('bp').value].path : null;
  let siteData, site;
  if (si === 'auto') {
    if (!bpPath) { status('Pick a design to read the terrain around it.'); return; }
    status('Reading terrain from the world files…');
    const res = await fetch('/api/design_site?path=' + encodeURIComponent(bpPath));
    siteData = await res.json();
    if (siteData.error) { status('Error: ' + siteData.error); return; }
    site = { name: siteData.name };
  } else {
    site = sites[si];
    status('Loading terrain…');
    siteData = await (await fetch('/api/site?path=' + encodeURIComponent(site.path))).json();
  }
  let design = null;
  if (bpPath) {
    status('Loading design…');
    design = await (await fetch('/api/blueprint?path=' + encodeURIComponent(bpPath))).json();
  }
  status('Building 3D model (block models + textures)…');
  const t0 = performance.now();
  const grid = makeGrid(siteData, design, $('showDesign').checked);
  const meshes = await buildMeshes(grid, { highlightDesign: $('highlight').checked });
  if (current) {
    for (const m of [current.meshes.solid, current.meshes.translucent]) {
      scene.remove(m);
      m.geometry.dispose();
    }
  }
  scene.add(meshes.solid, meshes.translucent);

  // Frame the chosen plot (the site rectangle inside the exported area).
  const [ox, oy, oz] = siteData.origin;
  const [sx0, sz0, sx1, sz1] = siteData.site;
  let centre, radius;
  if (design && design.blocks.length) {
    // Frame the design itself: centre of its bounding box.
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    for (const [x, y, z, pi] of design.blocks) {
      if (design.palette[pi].endsWith(':air')) continue;
      lo[0] = Math.min(lo[0], x); lo[1] = Math.min(lo[1], y); lo[2] = Math.min(lo[2], z);
      hi[0] = Math.max(hi[0], x); hi[1] = Math.max(hi[1], y); hi[2] = Math.max(hi[2], z);
    }
    centre = new THREE.Vector3((lo[0] + hi[0]) / 2 - ox + 0.5, (lo[1] + hi[1]) / 2 - oy - 3, (lo[2] + hi[2]) / 2 - oz + 0.5);
    radius = Math.max(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) * 0.9 + 4;
  } else {
    const groundY = estimateGround(grid, sx0 - ox, sz0 - oz, sx1 - ox, sz1 - oz);
    centre = new THREE.Vector3((sx0 + sx1) / 2 - ox + 0.5, groundY, (sz0 + sz1) / 2 - oz + 0.5);
    radius = Math.max(sx1 - sx0, sz1 - sz0) + 8;
  }
  current = { siteData, design, meshes, centre, radius };
  setView('iso');
  status(`${site.name}${design ? ' + ' + design.name : ''}\n${meshes.faceCount.toLocaleString()} faces · ${Math.round(performance.now() - t0)} ms`);
}

function estimateGround(grid, x0, z0, x1, z1) {
  const cx = Math.round((x0 + x1) / 2), cz = Math.round((z0 + z1) / 2);
  for (let y = grid.h - 1; y >= 0; y--) {
    if (grid.get(cx, y, cz) >= 0) return y + 1;
  }
  return grid.h / 2;
}

function setView(kind) {
  if (!current) return;
  const { centre, radius } = current;
  controls.target.copy(centre).add(new THREE.Vector3(0, 3, 0));
  const r = radius * 1.4;
  if (kind === 'top') camera.position.copy(centre).add(new THREE.Vector3(0.01, r * 1.6, 0.01));
  else if (kind === 'front') camera.position.copy(centre).add(new THREE.Vector3(0, r * 0.35, r * 1.2));
  else camera.position.copy(centre).add(new THREE.Vector3(r * 0.9, r * 0.75, r * 0.9));
  controls.update();
}

async function screenshot() {
  const name = (current ? current.siteData.name : 'view') + '_' + Date.now();
  renderer.render(scene, camera);
  const blob = await new Promise(r => renderer.domElement.toBlob(r, 'image/png'));
  const res = await (await fetch('/api/screenshot?name=' + encodeURIComponent(name), { method: 'POST', body: blob })).json();
  status('Saved ' + res.saved);
}

$('load').onclick = () => load().catch(e => status('Error: ' + e.message));
$('showDesign').onchange = $('highlight').onchange = () => current && load();
$('viewFront').onclick = () => setView('front');
$('viewTop').onclick = () => setView('top');
$('viewIso').onclick = () => setView('iso');
$('shot').onclick = () => screenshot();
refreshLists().catch(e => status('Error: ' + e.message));
