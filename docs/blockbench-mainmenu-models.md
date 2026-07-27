# Authoring main-menu 3D background models in Blockbench

The main menu's 3D background (`MainMenuBackgroundRenderer` in each
`platform/fabric-*` module) is rendered through Minecraft's
"picture-in-picture" GUI extraction mechanism (`GuiGraphicsExtractor.skin(...)`
on 26.x, `DrawContext.addPlayerSkin(...)` on 1.21.11 — see below). There is no
version of this mechanism that loads an arbitrary mesh (glTF/OBJ/FBX) or a
custom shader/material at runtime — every model must ultimately become a
`ModelPart` hierarchy built from `CubeListBuilder`/`PartPose` calls (the same
system vanilla uses for mob models): **boxes only**, textured from **one flat
UV atlas**. Blender has no export path that produces this directly, so the
recommended tool is **Blockbench**, purpose-built around this exact
cuboid-model format. (Nothing below is Blockbench-specific in principle — any
tool that outputs a named cuboid hierarchy + box-UV atlas works — Blockbench
is just the practical choice today.)

This guide covers the asset-authoring half of the workflow: getting a
Blockbench design into the shape the shipped renderer already expects. The
underlying system — one shared cube hierarchy driving all three supported
Minecraft versions — is real, shipped, and in production today, not a
proposal. If you're only changing geometry/texture (not touching renderer
logic), you never need to open a `platform/fabric-*` renderer file at all —
see "Get the cube data into Java" below.

## The cross-version trick: how one model runs on three incompatible APIs

This repo supports three Minecraft/Fabric versions, and they do **not** offer
the same picture-in-picture capability (see `.claude/context/minecraft.md`'s
cross-version table, the "Generic-model 3D picture-in-picture GUI render
call" row and its follow-up row):

| Version | What it accepts |
|---|---|
| **26.2** | `GuiGraphicsExtractor.skin(Model.Simple, Identifier, ...)` — a genuinely generic overload. Any hand-authored `ModelPart` hierarchy, any texture. |
| **26.1** | Same `skin()` call exists, but only the `PlayerModel`-restricted overload — it requires a real `PlayerEntityModel`, which throws in its constructor if specific named children are missing. |
| **1.21.11** | `DrawContext.addPlayerSkin(PlayerEntityModel, ...)` — same biped-only restriction. |

`PlayerEntityModel`'s constructor requires exactly these named children to
exist somewhere in the hierarchy (it resolves each via `getChild(String)` and
throws if missing): `head`, `hat`, `body`, `left_arm`, `right_arm`,
`left_leg`, `right_leg`, `left_sleeve`, `right_sleeve`, `left_pants`,
`right_pants`, `jacket`. The authoritative list of these names lives in code,
not just in this doc — see
`common/src/main/java/de/lazuli/common/mainmenu/MainMenuPartNames.java`.

**The unifying trick, now confirmed and shipped**: that constructor only
checks that those *names* exist — nothing about it forbids the hierarchy from
having additional sibling/child parts beyond them, and vanilla's own render
traversal walks the whole `ModelPart`/children `Map` from root with **zero
name filtering**, not just the specifically-named list. This was verified by
a full bytecode trace (`javap`/disassembly against each module's own resolved
Minecraft jar — see `.claude/context/minecraft.md`'s follow-up row to the
picture-in-picture table): `Model`/`EntityModel`'s `render(...)` delegates to
`ModelPart.render(...)` on the model's own root, which iterates the entire
backing `children` map regardless of name. The required-name check only gates
*construction*, not *rendering*.

So one hierarchy contains the real idle character *named* to satisfy the
biped requirement, plus arbitrary extra bones for scenery (sky, sun,
mountains, ground) attached elsewhere in the same tree — and the identical
underlying model renders the same shaped content on all three versions:

- **26.2**: the hierarchy is submitted directly via the generic
  `skin(Model.Simple, ...)` call, two separate `skin()` calls (scenery, then
  character) — intentional, not an oversight; 26.2's generic overload doesn't
  need everything merged into one biped-shaped model the way 26.1/1.21.11 do.
- **26.1**: scenery and character are merged into one `MeshDefinition` wrapped
  in a real `PlayerModel`, submitted via a single `skin()` call.
- **1.21.11**: the same shape via Yarn's `ModelData`/`ModelPartData`/
  `ModelPartBuilder`/`ModelTransform`, submitted via a single
  `addPlayerSkin()` call.

The old 2D flat-scenery fallback (sky/mountains/ground drawn as flat
`fill`/gradient bands on 1.21.11 and 26.1) is gone — scenery on every version
is now real 3D bones sharing the same hierarchy as the character, with a
shared camera and draw order.

One caveat carried over from the bytecode trace: extra scenery bones are not
auto-*posed* by the biped animation code (`setAngles`/`setupAnim` only moves
the named biped parts) — they render every frame, but if a scenery bone
needs per-frame motion, that still needs a manual `getChild(name)` +
field-mutate in the renderer, same as character posing already does. Static
scenery (the current sky/sun/mountains/ground) doesn't need this at all.

## Constraints to design within

- **Boxes only.** No subdivision, booleans, or organic/non-manifold shapes —
  Blockbench's "Generic Model" format is cuboid-based and this is what maps
  onto `CubeListBuilder.addBox(...)`.
- **One texture atlas.** A single flat image, box-UV mapped (each cube gets a
  rectangular region via `texOffs(u, v)`), not per-face free UV and not
  multi-material/PBR. The shipped atlas is 384×384 laid out in a 4×4 grid of
  96×96 cells (`MainMenuMeshDefinitions.TEX_SIZE`/`CELL`).
- **One shared hierarchy for scene + character, on every version.** Sky,
  mountains, ground, and the character are all bones in the *same* project,
  so they share a camera and draw order everywhere.
- **Name the character's bones to satisfy `PlayerEntityModel`.** The real
  animated parts (head, body, each arm, each leg) must use exactly the
  vanilla biped names — see `MainMenuPartNames` for the canonical constants.
  Any of the four purely-cosmetic slots your design doesn't need (`hat`,
  `left_sleeve`, `right_sleeve`, `left_pants`, `right_pants`, `jacket`) can be
  zero-size/empty cubes — they just need to exist. (`hat` is the one
  exception in the shipped model — it carries real hair geometry.)
- **Keep every animated part as its own bone**, named-or-not, so Java code
  can grab it (`root.getChild("right_arm")`) and rotate/offset it every
  frame — don't weld animated parts into a single mesh.

## Step-by-step

1. **Install Blockbench** — free, from blockbench.net.
2. **New Project → Generic Model.** Set the texture size to 384×384 to match
   the shipped atlas (a 4×4 grid of 96×96 cells), unless you're deliberately
   resizing the whole atlas.
3. **Block out geometry as boxes**, all in one project:
   - Character parts named exactly `head`, `body`, `left_arm`, `right_arm`,
     `left_leg`, `right_leg` (plus the six cosmetic/placeholder slots, even
     if empty) — see `MainMenuPartNames` for the exact strings.
   - Background elements (sky bands, sun, mountains, ground) as additional
     bones anywhere else in the same hierarchy — names don't matter
     mechanically for these (though the shipped model uses descriptive
     prefixes like `sky_`, `mountain_far_`, `mountain_near_` — see
     `MainMenuPartNames` for the scenery constants already in use).
4. **UV-map with box UV** (not per-face) so each cube auto-maps to a
   rectangular texture region.
5. **Texture** — paint directly in Blockbench's texture editor, or import an
   image and let Blockbench lay out UVs from the cube sizes.
6. **Get the cube data into Java — and only into one place.** The single
   canonical geometry source for this whole feature is
   `common/src/main/java/de/lazuli/common/mainmenu/MainMenuMeshDefinitions.java`,
   specifically its `CHARACTER_PARTS` and `SCENERY_PARTS` lists of
   `MeshCubeSpec` entries (`common/.../mainmenu/MeshCubeSpec.java`). Each
   entry is plain data — name, parent name, pivot, box origin/size, UV cell,
   and a flag for the zero-size placeholder bones — with no Minecraft
   imports at all, so it's shared unchanged across all three platform
   modules.
   - Check Blockbench's plugin marketplace for a Java entity/geometry
     exporter — availability changes between Blockbench versions, so verify
     what's current rather than assuming a specific plugin name.
   - **Reliable fallback that always works**: Blockbench's cube inspector
     panel shows the exact origin, size, and UV offset for every box.
     Transcribe each box into a new `MeshCubeSpec(...)` entry in
     `MainMenuMeshDefinitions` (character bones go in `CHARACTER_PARTS`,
     scenery bones in `SCENERY_PARTS`), using `cellU(col)`/`cellV(row)` for
     the UV offset if your box lines up with one of the atlas's 96×96 cells.
   - **For a pure content change (new/adjusted geometry or texture layout),
     this file is the only Java file you should need to touch.** Each
     platform's `MainMenuBackgroundRenderer`
     (`platform/fabric-26.2`/`fabric-26.1`/`fabric-1.21.11`) already reads
     `MainMenuMeshDefinitions` and translates every `MeshCubeSpec` into that
     platform's own native builder calls (`CubeListBuilder`/`PartPose` on
     26.x, `ModelData`/`ModelPartBuilder`/`ModelTransform` on 1.21.11) — you
     don't need to touch, or even understand, that translation code just to
     add or resize a cube. Editing the renderer files directly is only
     needed for changes to *behavior* (animation, camera, draw order), not
     geometry — and that's real cross-module implementation work that should
     go through this repo's orchestrator workflow (specification → plan →
     implement → verify) rather than ad hoc edits.
7. **Hand off the result** (`.bbmodel` file and/or the transcribed
   `MeshCubeSpec` entries) — if you did the transcription yourself in step 6,
   a normal PR/commit touching only `MainMenuMeshDefinitions.java` is enough
   for a pure content change. If the change also needs new animation,
   different scenery behavior, or anything beyond geometry/texture, route
   that part through the orchestrator instead of editing the renderers
   directly.

## Why not just load a Blender export at runtime?

This was investigated early on (see the Javadoc on `MainMenuBackgroundRenderer`
and `.claude/context/minecraft.md`'s picture-in-picture rows): MC 26.2
replaced the old immediate-mode `Screen.render` pipeline with a render-state-
*extraction* model, and `GuiGraphicsExtractor` was enumerated method-by-method
(via `javap` against the resolved jar) to confirm `skin(...)` is the only
public extraction call that accepts custom 3D geometry with its own camera —
and on 26.1/1.21.11 it (and its `DrawContext` equivalent) is further
restricted to biped-shaped `PlayerEntityModel`s only. This constraint is a
property of Minecraft's own client API, not something this mod's design
chose — there is no version of any supported Minecraft release that loads an
arbitrary mesh (glTF/OBJ/FBX, a raw Blender export, etc.) at runtime for this
kind of in-game 3D content. Unless a future Minecraft version adds a
different extraction entry point, any new main-menu 3D model needs to fit the
cuboid `ModelPart`/`MeshCubeSpec` format described here.
