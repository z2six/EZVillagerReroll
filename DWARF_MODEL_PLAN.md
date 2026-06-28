# Dwarf Model Assessment And Render Plan

## Source File

`C:\Users\Z2SIX\Documents\Blockbench\VillagerOverhaul\Factions\Dwarf\Dwarf.geo.json`

Corrected test copy:

`C:\Users\Z2SIX\Documents\Blockbench\VillagerOverhaul\Factions\Dwarf\Dwarf.vo_fixed.geo.json`

## Current Model Format

The file is valid JSON, but it is not a vanilla Java model export. It uses the Bedrock/GeckoLib-style geometry format:

- `format_version`: `1.12.0`
- Top-level key: `minecraft:geometry`
- Geometry identifier: `geometry.unknown`
- Texture size: `16x16`

This means we cannot directly use it as a vanilla `VillagerModel` / `LayerDefinition` without either converting it or writing a runtime loader/adapter for this geometry format.

The corrected test copy keeps the same Bedrock/GeckoLib-style format for now, but fixes the VO race-model contract enough for loading/conversion experiments.

## Current Hierarchy

```text
root
+-- body
|   +-- belly
|   +-- breasts
+-- arms
|   +-- arm_left
|   +-- arm_right
+-- left_leg
+-- right_leg
+-- head
    +-- beard
    +-- hat
        +-- hat_rim
```

## Target VO Race Model Hierarchy

```text
root
+-- body
|   +-- jacket
|   +-- belly
|   +-- breasts
+-- arms
+-- right_leg
+-- left_leg
+-- head
    +-- hat
    |   +-- hat_rim
    +-- nose
    +-- beard
```

## Usability Check

The model is a useful visual draft, but it is not ready for the VO race rendering plan.

Issues to fix:

1. `body` has no cube.
   The torso geometry appears to be in `breasts`, which is not a good base anchor. `body` should contain the main dwarf torso.

2. `jacket` is missing.
   We need `body.jacket` as the vanilla-dynamic overlay target.

3. `hat` and `hat_rim` exist but have no cubes.
   These names are present, but there is no overlay geometry for vanilla hat/profession textures to render onto.

4. `nose` is missing as a named bone.
   The head contains several cubes, likely including nose-like geometry, but VO needs a clear `head.nose` part if we want to preserve the vanilla-compatible naming contract.

5. Arm naming does not match the current contract.
   The file has `arm_left` and `arm_right` under `arms`. If we want separate race arms, we should standardize on `left_arm` and `right_arm`, or document `arm_left` / `arm_right` as the official VO convention. The current vanilla villager only has one `arms` part.

6. Texture size is probably too small.
   `16x16` is very tight for a race model with body, head, beard, belly, breasts, arms, and legs. Vanilla villagers use `64x64`. We should use `64x64` unless there is a strong reason not to.

7. UVs include negative coordinates.
   Several cubes use UVs such as `[-9, -9]`, `[4, -4]`, and `[5, -3]`. This is risky for Minecraft rendering and conversion. UVs should be made explicit and valid within the texture size.

8. One head cube has zero width.
   The cube with `size: [0, 4, 5]` may be intentional as a flat plane, but it needs confirmation. If not intentional, it should be replaced with real thickness.

9. Geometry identifier is generic.
   `geometry.unknown` should become something stable, for example `geometry.villageroverhaul.dwarf`.

## Modeler Requirements

For the next pass, ask the modeler to:

1. Keep the target hierarchy exactly.
2. Move the main torso cube or cubes into `body`.
3. Add `body.jacket` geometry for vanilla-dynamic overlays.
4. Add `head.hat` and `head.hat.hat_rim` geometry if dwarf hats should support vanilla profession hats.
5. Add `head.nose` as a named part.
6. Use a `64x64` texture canvas.
7. Remove negative UV coordinates.
8. Keep race-owned geometry separate from vanilla-overlay geometry.
9. Export a vanilla Java model if possible, or otherwise keep the `.geo.json` only as an intermediate source that we convert.

## Render Architecture Options

### Option 1: One Active Vanilla-Compatible Race Model

Replace the renderer's active villager model with a race-specific model that still follows the vanilla-compatible skeleton.

Expected flow:

1. The entity remains a normal villager.
2. VO stores or derives a race value, such as `dwarf`.
3. The villager renderer uses a dwarf `VillagerModel`-compatible model for dwarf villagers.
4. Vanilla `setupAnim` runs directly on the dwarf model's `head`, `left_leg`, `right_leg`, and other compatible anchors.
5. VO renders race-owned parts with the dwarf texture.
6. VO renders `jacket`, `hat`, and `hat_rim` in separate overlay passes with vanilla-selected profession/type/level textures.

Pros:

- Cleanest animation path.
- No per-frame pose copying from a hidden model.
- Vanilla walking and head animation apply directly to the custom model anchors.
- Good long-term model contract for dwarves, elves, and other factions.

Cons:

- Requires the custom model to be compatible with vanilla `VillagerModel` expectations.
- Requires careful renderer integration so vanilla layers do not render unwanted base parts.
- Requires either Java model export or a conversion step from Blockbench geometry to `LayerDefinition`.

### Option 2: Two-Root Driver And Visible Race Model

Keep the vanilla model as an invisible animation driver and render a separate race model that copies final poses from vanilla anchors.

Expected flow:

1. Vanilla villager model animates normally.
2. VO hides the vanilla visible parts.
3. VO copies final transforms from vanilla `head`, `left_leg`, `right_leg`, etc. to the dwarf model.
4. VO renders the dwarf model and overlay parts.

Pros:

- More tolerant of custom model internals.
- Does not require the race model to literally extend/behave like `VillagerModel`.

Cons:

- More complex render logic.
- Per-frame pose synchronization.
- Easier to get wrong with render order, armor, held items, and custom arms.

### Option 3: Runtime Mutation Of Vanilla Model Parts

Use the vanilla villager model and resize/reposition its parts for a race.

Expected flow:

1. Vanilla model remains the visible model.
2. VO modifies part positions, sizes, or visibility for dwarf villagers.
3. Extra pieces are injected or rendered as layers.

Pros:

- Vanilla animates the same core parts directly.
- Potentially less model replacement work.

Cons:

- Poor fit for real race silhouettes.
- Hard to support beards, bellies, breasts, ears, custom heads, and faction-specific UVs cleanly.
- Likely to become a pile of special cases.

## First Approach Implemented

The first implementation uses Option 2 as a controlled render-layer approach, not a full renderer model swap.

Reasoning:

1. Existing VO rendering already depends on multiple villager render layers for custom arms, armor, held items, holstered items, and visibility gating.
2. A render layer lets the dwarf model coexist with those systems without replacing the whole villager renderer yet.
3. The vanilla base model is hidden for dwarf-faction villagers, so only the dwarf model is visible.
4. The existing VO custom arms layer is still used for combat/eating/held-item behavior when current render flags request custom arms.
5. The dwarf layer renders the dwarf base model and applies vanilla type/profession/level overlay textures to `jacket`, `hat`, and `hat_rim`.

Implemented spike:

1. Add a server-synced faction byte to villagers.
2. Add a hotloaded server config chance for dwarf assignment.
3. Assign dwarf faction on server-side villager join/spawn.
4. Add a Java `LayerDefinition` dwarf model based on the corrected Blockbench hierarchy.
5. Hide the vanilla villager base model for dwarf-faction villagers.
6. Render the dwarf model as a villager render layer.
7. Reuse the existing custom arms path unchanged.

Option 1 is still a possible future cleanup if we decide to fully replace the active renderer model, but it is not necessary for the current end-to-end test path.
