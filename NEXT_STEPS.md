# Next steps (written 2026-11-22)

Goal for the picture-to-build designer: get **closer than a hand-written style description**.
A plain description is the ceiling of a describe-then-design approach, and that ceiling isn't good enough.

## Where we are
- `/design pos1|pos2|front|build <style>|styles` generates a terrain-fitted blueprint from a style.
- Styles can be JSON in `.minecraft/freecam_ghosts/styles/` (re-read on every build, no restart needed).
- First test: `stone_cottage.json` from a Mattagua cottage picture. Right shape and stone colour, but it
  reads as a plain default house. The missing character is all in the details (see list 1).
- **Uncommitted:** ghost.15 (JSON styles) and ghost.16 (Give All Blocks in creative). Commit first.

## 1. Detail building pieces (needed first: the generator can only build what it has pieces for)
- Eave trim and front overhang: a log fascia under the eave, plus an optional roofed porch on posts.
- Roof material mix (weighted, like walls), moss accents, and a separate ridge-cap material.
- Window styles: wooden frame, trapdoor shutters, sill or planter with bushes.
- Door surround: log frame and a stone step.
- Chimneys: 0, 1 or 2, with a cap block.
- Decoration pass: vines on walls, leaf bushes along the base, lantern by the door, barrels/crates.
- Then update `stone_cottage.json` and regenerate on the same plot to compare.

## 2. Materials from the picture's real colours (agreed approach, no AI)
- Build a block colour table from the game's actual block textures (average colour, plus variance
  for texture "busyness"), for full blocks plus their stair/slab/wall variants.
- Sample the picture: dominant colours, ideally per region (roof / walls / trim / base).
- Match in a perceptual colour space (CIELAB), restricted to blocks that make sense for each part
  (roof = stairs-capable blocks, trim = logs/planks, and so on).
- Turn colour spread within a region into a weighted mix (e.g. cobble + mossy cobble + andesite).

## 3. Ways to beat the description ceiling (ideas to discuss)
- **Region-aware sampling:** get roof/wall/trim masks (simple segmentation, or ask a local vision model
  only *where* things are, not what block they are), then sample colours per region.
- **Closed loop:** render the generated build from the same angle as the picture, compare (colour
  histograms per region, silhouette), adjust materials and proportions, repeat. This is how it could
  get better than any one-shot description.
- **Multiple-choice local model** (Qwen2.5-VL via Ollama/LM Studio) only for structure: roof type,
  floors, which features are present. Materials come from colour matching, not from the model.
- **Tweak and regenerate UI:** swap one material or toggle one feature, then regenerate.

## Open bugs/notes
- The test client's Auto-Build key was rebound to plain M. Reset it to Ctrl+B in Controls.
- The ring of regraded ground around builds can leave visible 1-block ledges; it could be smoothed.
