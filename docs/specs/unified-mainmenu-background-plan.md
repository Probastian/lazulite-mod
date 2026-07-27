# Implementation Plan: Unified Main-Menu 3D Background

Spec: `docs/specs/unified-mainmenu-background.md` (all section refs below are
to that document). This plan covers architecture, files, risks, and
acceptance criteria only — no implementation code.

## Existing Implementation

Findings recorded here so implementation/verification don't need to
re-derive them.

- **`platform/fabric-26.2/.../MainMenuBackgroundRenderer.java`**: builds two
  independent `MeshDefinition`s — `buildScene()` (top-level scenery bones:
  `sky_0..3`, `sun_glow`, `sun_core`, `mountain_far_0..4`,
  `mountain_near_0..3`, `ground_base/top/highlight`) and
  `buildCharacterMesh()` (character bones named `head`/`hair`/`torso`/
  `right_arm`/`left_arm`/`right_leg`/`left_leg` — **not** yet using the
  biped-required names). Each is baked into its own `Model.Simple` and
  submitted via its own `guiGraphics.skin(Model.Simple, ...)` call
  (`scale=18f,pivotY=-6f` for scene; `scale=22f,pivotY=0f` for character).
  Field names used for posing: `ModelPart.y`, `ModelPart.xRot` (Mojmap).
- **`platform/fabric-26.1/.../MainMenuBackgroundRenderer.java`**: **also
  currently 2D-scenery-only** (`renderSceneAsFlat2D`, `fill`/`fillGradient`)
  — this was not called out as needing migration in the spec's Goals section
  (which names only 1.21.11), but the spec's own "Resulting architecture" /
  "Per-version wrapper responsibilities" section (lines ~312-319) explicitly
  describes 26.1 replacing its 2D scenery with the merged 3D call. **This
  plan treats 26.1 as needing the identical scenery migration as 1.21.11**
  — flagged as a correction to the Goals section's wording, consistent with
  the rest of the spec body. Character mesh already uses the exact
  biped-required names (`head`/`hat`/`body`/`left_arm`/`right_arm`/
  `left_leg`/`right_leg`/`left_sleeve`/`right_sleeve`/`left_pants`/
  `right_pants`/`jacket`) via `PartDefinition`/`CubeListBuilder`/`PartPose`
  (Mojmap `net.minecraft.client.model.geom.*`), wrapped in
  `net.minecraft.client.model.player.PlayerModel`, submitted via
  `guiGraphics.skin(PlayerModel, ...)`. Posing fields: `ModelPart.y`/`xRot`
  (identical shape to 26.2 — same class family).
- **`platform/fabric-1.21.11/.../MainMenuBackgroundRenderer.java`**: 2D
  scenery (`renderSceneAsFlat2D`, `DrawContext.fill`/`fillGradient`).
  Character mesh already uses biped-required names, but built via a
  **structurally different builder API**: `net.minecraft.client.model.
  ModelData`/`ModelPartData`/`ModelPartBuilder`/`ModelTransform`/`Dilation`/
  `TexturedModelData` (Yarn), wrapped in `net.minecraft.client.render.entity.
  model.PlayerEntityModel`, submitted via `context.addPlayerSkin(...)`.
  Posing fields: `ModelPart.originY`/`pitch` (Yarn names, not `y`/`xRot`).
- **Class-name-level incompatibility confirmed by direct comparison of all
  three files' imports**: 26.1 and 26.2 use an **identical** builder-API
  class family (`MeshDefinition`/`PartDefinition`/`CubeListBuilder`/
  `PartPose`/`CubeDeformation`/`LayerDefinition`, all under
  `net.minecraft.client.model.geom*`) — genuinely shareable Java source.
  1.21.11 uses a **completely different, non-overlapping** class family
  (`ModelData`/`ModelPartData`/`ModelPartBuilder`/`ModelTransform`/
  `Dilation`/`TexturedModelData`) — no common supertype/method-shape a single
  source file could target across both families. This resolves the spec's
  "Open item 1" (shared-source vs. disciplined-copy) definitively: **no
  single Java source file can build the mesh for all three platform
  modules using the platforms' own Minecraft-provided builder types.**
- **`common` module** (`common/build.gradle`) currently has **zero source
  files** and **zero consumers** — no platform or feature module depends on
  `:common` today (confirmed via repo-wide grep for
  `project(':common')`). It depends only on `:api`, no Minecraft dependency,
  Java 21 floor. This makes it a safe, currently-unused home for **plain
  Java data** (no `net.minecraft.*` imports) shared cube-geometry
  constants — see Architecture Decision below.
- All three platform modules already depend on `implementation project(
  ':features:main-menu')`, which is where `IdleCharacterAnimator`/
  `CharacterPose` (used by all three renderers) live — but `main-menu`
  itself is Minecraft-agnostic plain Java, not a place to put
  version-specific mesh-building code, and adding a `:common` dependency to
  each platform module is a small, isolated one-line change per
  `build.gradle`.

## Architecture Decision (resolves spec Open Item 1)

Because 1.21.11's builder API is a different class family from 26.1/26.2's
(see above), true shared *source* covering all three is not possible without
inventing a new abstraction layer disproportionate to one placeholder mesh
(explicitly out of scope — Non-goals: "not adding new visual features").
Instead:

1. **New `:common` data module** (`common/src/main/java/de/lazuli/common/
   mainmenu/`) holds the **single canonical cube-geometry definition** as
   plain-data records — no Minecraft types at all, so it compiles
   identically regardless of Yarn/Mojmap or Minecraft version:
   - `MeshCubeSpec` (record): `name`, `parentName` (nullable — null means
     "top-level child of root"), `pivotX/Y/Z`, box `originX/Y/Z`,
     `sizeX/Y/Z`, `uvCol`, `uvRow`, `isBipedRequiredPlaceholder` (boolean —
     true for `hat`/`left_sleeve`/`right_sleeve`/`left_pants`/
     `right_pants`/`jacket`, which some platforms build as zero-cube boxes).
   - `MainMenuPartNames`: `String` constants for every required-biped name,
     required-placeholder name, and scenery bone name, so all three
     renderers reference one literal source instead of retyping strings
     (removes a class of typo bugs where e.g. `torso` vs `body` silently
     diverge, as already happened once between 26.2's old `torso` naming
     and 26.1/1.21.11's `body` naming).
   - `MainMenuMeshDefinitions`: `TEX_SIZE`/`CELL` constants, `cellU(col)`/
     `cellV(row)` helpers, and two `List<MeshCubeSpec>` constants —
     `CHARACTER_PARTS` and `SCENERY_PARTS` — encoding **exactly** today's
     26.2 cube geometry (positions/sizes/UVs unchanged, per spec Non-goal
     "not changing cube geometry values"), with the character parts
     renamed from 26.2's `torso`/`hair` to the shared `body`/`hat` (matching
     26.1/1.21.11's existing naming, since those are the ones constrained
     by `PlayerModel`/`PlayerEntityModel`'s required names) plus the
     placeholder-only parts (`left_sleeve` etc.) added as zero-size boxes.
2. **Each platform module's `MainMenuBackgroundRenderer`** iterates
   `MainMenuMeshDefinitions.CHARACTER_PARTS`/`SCENERY_PARTS` and translates
   each `MeshCubeSpec` into that platform's own native builder calls
   (`CubeListBuilder`/`PartPose` for 26.1/26.2; `ModelPartBuilder`/
   `ModelTransform` for 1.21.11) inside a small private loop/helper method —
   this is genuinely per-platform code (different method names/types) but
   now driven by one shared data source, so a future geometry change is a
   one-file edit in `:common` plus no manual re-typing of numbers on the
   other two platforms.
3. 26.1 and 26.2 remain two independent platform modules (not merged) since
   they compile against different Minecraft versions/jars — sharing the
   *data* via `:common` is the full extent of cross-module reuse; this
   plan does not introduce a 26.1↔26.2 compile-time dependency.

This satisfies spec Goals #3 ("exactly one mesh-building definition... that
all three platform modules bake from") at the geometry-data level, which is
the part that actually varies today (today three independent numeric cube
lists exist); it does not claim one shared *builder-invocation* source file,
which is not achievable given the confirmed class-family divergence.

## Files to Create

In dependency order:

1. `common/src/main/java/de/lazuli/common/mainmenu/MeshCubeSpec.java` —
   plain record, no Minecraft imports.
2. `common/src/main/java/de/lazuli/common/mainmenu/MainMenuPartNames.java`
   — `String` constants (`HEAD`, `HAT`, `BODY`, `LEFT_ARM`, `RIGHT_ARM`,
   `LEFT_LEG`, `RIGHT_LEG`, `LEFT_SLEEVE`, `RIGHT_SLEEVE`, `LEFT_PANTS`,
   `RIGHT_PANTS`, `JACKET`, plus scenery names `SKY_PREFIX`, `SUN_GLOW`,
   `SUN_CORE`, `MOUNTAIN_FAR_PREFIX`, `MOUNTAIN_NEAR_PREFIX`,
   `GROUND_BASE`, `GROUND_TOP`, `GROUND_HIGHLIGHT`).
3. `common/src/main/java/de/lazuli/common/mainmenu/MainMenuMeshDefinitions.java`
   — `TEX_SIZE`/`CELL` constants, `cellU`/`cellV` helpers,
   `CHARACTER_PARTS`/`SCENERY_PARTS` lists (transcribed from 26.2's current
   `buildScene()`/`buildCharacterMesh()` numeric values, renaming `torso`→
   `body`/`hair`→`hat` and adding the five empty placeholder parts).

No new files in `features/main-menu` or `api` — `CharacterPose`/
`IdleCharacterAnimator` are unchanged (spec Non-goal: animation math
untouched).

## Files to Modify

1. `common/build.gradle` — no change expected (already `api project(':api')`,
   no Minecraft dependency needed for plain data classes); confirm during
   implementation that adding source files alone is sufficient.
2. `platform/fabric-26.2/build.gradle`,
   `platform/fabric-26.1/build.gradle`,
   `platform/fabric-1.21.11/build.gradle` — each add
   `implementation project(':common')` to their `dependencies {}` block
   (all three currently omit it).
3. `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/MainMenuBackgroundRenderer.java`
   — replace `buildScene()`/`buildCharacterMesh()` bodies with loops over
   `MainMenuMeshDefinitions.SCENERY_PARTS`/`CHARACTER_PARTS`; rename
   `torso`/`hair` field/getChild lookups to `body`/`hat`; keep the existing
   two-`skin()`-call structure (Non-goal: not unifying 26.2's two calls)
   but re-tune if the renamed/added placeholder parts shift the character
   mesh's bounding box (unlikely, since placeholder boxes are zero-size,
   but verify empirically per Non-goal's "not a redesign" intent).
4. `platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/MainMenuBackgroundRenderer.java`
   — add scenery-bone construction (new `buildSceneMesh()` analogous to
   26.2's, looping `MainMenuMeshDefinitions.SCENERY_PARTS` through
   `CubeListBuilder`/`PartPose`), merge scenery + character into **one**
   `MeshDefinition`/one baked root (since 26.1's `skin()` only accepts
   `PlayerModel`, there is only one call site here, unlike 26.2's two),
   delete `renderSceneAsFlat2D` and its call site, re-tune the single
   `skin(PlayerModel, ...)` call's `scale`/`rotationX`/`rotationY`/`pivotY`
   for the combined scene+character bounding box (see Risks).
5. `platform/fabric-1.21.11/src/main/java/de/lazuli/mainmenu/MainMenuBackgroundRenderer.java`
   — same shape as 26.1's change but through 1.21.11's own builder API
   (`ModelPartBuilder`/`ModelTransform`/`Dilation`), reading the same
   `:common` data source; delete `renderSceneAsFlat2D` and its call site;
   re-tune the single `context.addPlayerSkin(...)` call's camera params.
6. `.claude/context/minecraft.md` — append a follow-up note to the existing
   "Generic-model 3D picture-in-picture GUI render call" row (or a new row)
   recording: (a) the confirmed javap-verified "extra unnamed children
   still render generically" fact from the spec's bytecode findings, with
   an explicit "in-game confirmed: yes/no" flag updated once verification
   (below) runs; (b) the confirmed class-family incompatibility between
   1.21.11's `ModelData` family and 26.1/26.2's `MeshDefinition` family
   (new finding from this plan, not previously recorded).

No changes to `docs/blockbench-mainmenu-models.md` (per task instructions,
already consistent; this plan does not find a factual gap in it).

## Dependencies

No new external (non-Fabric, non-`:common`) dependencies are introduced by
this plan — it is a pure refactor of existing in-repo code plus one new
intra-repo module dependency edge (`platform/*` → `:common`). Per the
planner convention's Maven-verification requirement: not applicable, since
no new Maven coordinate is added anywhere in this plan.

## Risks

1. **Camera re-tuning for 26.1 and 1.21.11's merged scene+character single
   call** (spec's own flagged risk, lines 336-351) — 26.2's existing
   two-calls-with-different-params (`scale=18f,pivotY=-6f` scene;
   `scale=22f,pivotY=0f` character) cannot be reused directly for a single
   call spanning both bounding boxes. This is real numeric-tuning work,
   likely several in-game iterations (consistent with 26.2's own recorded
   FX8 tuning history). Mitigate by computing an initial guess from the
   combined mesh's actual min/max Y extents (sky top ~-40 to ground bottom
   ~+26 per current 26.2 numbers) rather than guessing blind, then
   iterating visually.
2. **26.1 currently being 2D-scenery** is a scope correction versus the
   spec's Goals section wording (which names only 1.21.11) — if the user
   intended 26.1's 2D scenery to stay as-is, this plan's file-count and
   risk estimate for 26.1 would shrink to "just consume `:common` for
   character-only, no camera re-tuning." Flagged explicitly for approval
   before implementation starts, since it changes 26.1's scope materially.
3. **Renaming 26.2's `torso`/`hair` to `body`/`hat`** touches every
   `getChild("torso")`/`getChild("hair")` call site in that file — a
   missed rename is a silent `NullPointerException` at first frame, not a
   compile error (Java `getChild` likely returns null or throws
   `NoSuchElementException` at runtime, not a compile-time signal) —
   mitigate via the shared `MainMenuPartNames` constants (a typo in one
   place surfaces as a real `getChild` runtime exception immediately in
   dev, not a silently-wrong string comparison).
4. **`isBipedRequiredPlaceholder` zero-size boxes on 26.2**: 26.2's
   `Model.Simple` has no biped-name constructor constraint, so these boxes
   are unnecessary weight there — confirm they render as true zero-size
   (invisible) cubes and don't introduce a stray visible artifact from a
   degenerate `addBox(0,0,0,0,0,0,...)` call (some Minecraft versions log
   warnings or skip zero-size cuboids differently) — verify per-platform,
   not just per the two constrained platforms.
5. **Posing-field-name divergence remains real and unresolved by
   `:common`** (spec's own caveat, lines 180-198): `:common` only unifies
   geometry data, not per-frame pose application (`y`/`xRot` vs. `originY`/
   `pitch`) — each renderer's `applyPose` method stays hand-written per
   platform; no risk of drift here since these are three short, already-
   reviewed methods, but flag so implementation doesn't attempt to also
   "unify" posing code (out of scope per spec Non-goals).
6. **In-game visual verification is mandatory, not optional** — the spec's
   own verdict is explicitly "bytecode-verified, not yet in-game-verified."
   A real launch of all three platform modules is required before this
   feature can be considered done, per spec closing paragraph.

## Test Strategy

This is a rendering feature with no meaningful unit-test surface beyond
`:common`'s plain-data classes (which could get a trivial JUnit check that
`CHARACTER_PARTS`/`SCENERY_PARTS` contain the expected required names and
no duplicates — cheap, worth adding, not a substitute for visual
verification). Primary verification is manual, in-game, per platform
module:

1. **Compile check**: `:common:build`, then each of
   `:platform:fabric-26.2:build`, `:platform:fabric-26.1:build`,
   `:platform:fabric-1.21.11:build` (the last via `remapJar`, per
   `.claude/context/minecraft.md`'s Obfuscation Boundary table) — confirms
   no class-family/type mismatch slipped through.
2. **In-game visual pass, each of the three platforms** (launch via each
   module's `runClient`, per user's no-launch-during-remote-control
   constraint from prior session memory — coordinate with the user on
   when/how this is actually run):
   - Character renders with correct proportions/animation (idle bob/arm
     swing/leg sway) — no regression from today's already-working
     character rendering.
   - Scenery (sky bands, sun glow/core, mountains, ground) renders in 3D
     on **all three** platforms, filling the reserved background region,
     no longer flat-2D on 26.1/1.21.11.
   - No `NullPointerException`/`NoSuchElementException` at startup (would
     indicate a missed rename or missing required part).
   - No GPU crash (`GL_INVALID_VALUE`) at small GUI scales/narrow windows —
     re-confirm the existing inset-clamping logic (already present in all
     three files) still holds after any camera re-tuning changes the
     destination-rect math.
   - Camera framing reads as intentional (horizon roughly mid-region, not
     clipped/tiny/pinned to an edge) on 26.1 and 1.21.11's newly-merged
     single call, matching the qualitative bar 26.2's FX8 tuning already
     established.
3. **Cross-platform parity check**: compare screenshots/visual impression
   across all three — same palette texture, same relative sky/sun/mountain/
   ground layout, same character silhouette — since they now share one
   geometry source, any visible divergence indicates a translation bug in
   one platform's builder-call loop, not a data problem.

## Acceptance Criteria

1. `:common` module contains exactly one geometry source
   (`MainMenuMeshDefinitions`) for all character + scenery cube data; no
   platform module hand-encodes cube positions/sizes/UVs independently.
2. All three platform modules render 3D sky/sun/mountains/ground scenery —
   `renderSceneAsFlat2D` (and its 2D `fill`/`fillGradient` call sites) is
   fully removed from both 1.21.11 and 26.1 (pending Risk 2's scope
   confirmation for 26.1).
3. 26.2's rendering behavior is visually unchanged (same two-call
   scene+character split, same palette texture, same animation) aside from
   the internal `torso`/`hair`→`body`/`hat` rename, which is not
   user-visible.
4. No compile errors on any of the three platform modules' `build`/
   `remapJar` tasks.
5. No runtime exceptions or GPU crashes on first launch of any of the three
   platform modules' main menu.
6. `.claude/context/minecraft.md`'s relevant row(s) updated to reflect the
   in-game-confirmed status of the "extra unnamed children render
   generically" finding and the new class-family-incompatibility finding.
7. User has explicitly resolved Risk 2 (26.1 scope) before implementation
   proceeds on that platform's scenery migration.
