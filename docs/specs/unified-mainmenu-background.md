# Spec: Unified Main-Menu 3D Background Across Platform Modules

Status: specification only (no plan, no implementation code in this document).
Owner feature: `MainMenuBackgroundRenderer` in `platform/fabric-1.21.11`,
`platform/fabric-26.1`, `platform/fabric-26.2`.

## Goals

1. Replace three independent `MainMenuBackgroundRenderer` implementations
   (different geometry, different scene-vs-character split, one 2D fallback)
   with **one shared cuboid `ModelPart`/`MeshDefinition` hierarchy** consumed
   by thin per-version wrappers.
2. Remove `fabric-1.21.11`'s flat 2D `fill`/`fillGradient` scenery fallback
   entirely — render real 3D sky/sun/mountains/ground there too, matching
   26.1/26.2.
3. Keep exactly one mesh-building definition (cube positions/sizes/UVs) that
   all three platform modules bake from, instead of one per module.
4. Preserve current visual behavior (placeholder AI-authored geometry, same
   palette texture, same animation math via `IdleCharacterAnimator`) — this
   is an architecture/plumbing change, not a redesign of the art.

## Non-goals

- Not changing cube geometry values, texture atlas layout, or animation math.
- Not adding new visual features (lighting, shadows, time-of-day, camera
  movement) — `FR8.7`'s fixed-camera, no-dynamic-time-of-day constraint
  stands.
- Not touching `fabric-26.2`'s existing `Model.Simple`/`skin()` call shape —
  it already does the unified-hierarchy submission natively; 26.2's changes
  here are limited to sourcing its mesh from the new shared definition
  instead of its own private one.
- Not solving the `26.2` two-separate-`skin()`-calls-per-frame limitation
  (scene and character remain two independent picture-in-picture render
  passes on 26.2, since only 26.2 *can* combine them into one call and
  combining is out of scope for this change).
- Not implementing anything — no code changes are made or planned by this
  document.

## Background (already established, not re-verified here)

- **26.2**: `GuiGraphicsExtractor.skin(Model.Simple, Identifier, ...)` is a
  generic overload accepting any `ModelPart` hierarchy. Current code uses two
  separate `Model.Simple` instances (`sceneModel`, `characterModel`) built
  from two independent mesh definitions (`buildScene()` /
  `buildCharacterMesh()`), rendered via two `skin()` calls, scene-then-
  character, each its own camera/lighting pass.
- **26.1**: `skin()` exists only via a `PlayerModel`-restricted overload. A
  real `PlayerModel` is required, and its constructor (via
  `HumanoidModel`/`BipedEntityModel`) throws if the named children
  `head`/`hat`/`body`/`left_arm`/`right_arm`/`left_leg`/`right_leg`/
  `left_sleeve`/`right_sleeve`/`left_pants`/`right_pants`/`jacket` are
  missing. Current 26.1 code already reshapes its character mesh to satisfy
  this (see its own Javadoc) but has no 3D scenery — none was attempted.
- **1.21.11**: `DrawContext.addPlayerSkin(PlayerEntityModel, ...)` has the
  same biped-only restriction (`BipedEntityModel`'s named-child requirement).
  Current code reshapes the character mesh the same way as 26.1, but scenery
  is entirely 2D `fill`/`fillGradient` bands — no 3D geometry at all.
- Design source for the unifying idea: `docs/blockbench-mainmenu-models.md`
  ("The cross-version wrinkle" section) and `.claude/context/minecraft.md`'s
  "Generic-model 3D picture-in-picture GUI render call" row. Both already
  proposed the shared-hierarchy trick and both explicitly flagged it as
  **unconfirmed** pending a real `javap`/decompile pass — that pass is what
  this document reports below.

## Critical assumption under test

> Does `PlayerEntityModel`/`BipedEntityModel` (1.21.11, Yarn) and
> `PlayerModel`/`HumanoidModel` (26.1, Mojmap) — the classes required to
> submit a model through `addPlayerSkin`/`skin()` on those two versions —
> only check that the required named children **exist** (throwing if
> missing), while otherwise **rendering the entire attached `ModelPart` tree
> generically**, including extra sibling/child bones outside the required-
> names list? Or does their render logic only draw/pose the specifically-
> named fields and silently drop/reposition/crash on extra scenery bones?

### Method: real `javap`/bytecode inspection

Jars inspected (both are this repo's own real, project-resolved, mapped
Minecraft jars — not guesses, not upstream re-downloads):

- **1.21.11 (Yarn)**:
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-6dd721cd7d-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
- **26.1 (Mojmap)**:
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/minecraft-merged-a26c9a9f3c-26.1.jar`

(Note for future sessions: the top-level `%USERPROFILE%\.gradle\caches\fabric-loom\<version>\minecraft-client.jar` files are still obfuscated/intermediary-only in this environment — the actually-mapped jars this repo's own compiles resolve against live under
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-<hash>/...` inside the repo itself, not the global Gradle cache. Use those.)

Tool: `javap -p` (signatures/fields) and `javap -p -c` (bytecode) from
`Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\javap`, run via Bash against
each jar directly with `-classpath`.

### Evidence — 1.21.11 (Yarn)

`net.minecraft.client.render.entity.model.PlayerEntityModel` (`javap -p`):

```
public class PlayerEntityModel extends BipedEntityModel<PlayerEntityRenderState> {
  public final ModelPart leftSleeve;
  public final ModelPart rightSleeve;
  public final ModelPart leftPants;
  public final ModelPart rightPants;
  public final ModelPart jacket;
  public PlayerEntityModel(ModelPart, boolean);
  ...
}
```

`BipedEntityModel` (`javap -p`):

```
public class BipedEntityModel<T extends BipedEntityRenderState> extends EntityModel<T> ... {
  public final ModelPart head;
  public final ModelPart hat;
  public final ModelPart body;
  public final ModelPart rightArm;
  public final ModelPart leftArm;
  public final ModelPart rightLeg;
  public final ModelPart leftLeg;
  public BipedEntityModel(ModelPart);
  ...
}
```

Both classes only *cache* named children into fields (the constructor calls
`ModelPart.getChild(String)`, which throws `NoSuchElementException`-style if
missing — this is the existing, already-confirmed "throws if missing"
behavior). Neither class stores or iterates any "list of the real render
parts" separate from these fields.

The actual render call chain, traced by bytecode:

1. `EntityModel` extends `net.minecraft.client.model.Model<S>`. `Model`'s
   `render(...)` methods are `public final` and (per `javap -p -c`) simply
   delegate to a 5-arg overload — no per-field logic:
   ```
   public final void render(MatrixStack, VertexConsumer, int, int);
     Code: aload_0; aload_1; aload_2; iload_3; iload 4; iconst_m1;
           invokevirtual render:(MatrixStack;VertexConsumer;III)V
   ```
2. That 5-arg `render` is `ModelPart.render(MatrixStack, VertexConsumer, int,
   int, int)` called on `Model.root` — i.e. the model's render entry point is
   always "render the root `ModelPart`," not "render these seven named
   fields."
3. `ModelPart.render(...)` bytecode (`javap -p -c`) confirms the generic
   traversal directly:
   ```
   0: aload_0; getfield visible; ifne 8; return         // skip if invisible
   8: cuboids.isEmpty() && children.isEmpty() -> return // skip if empty
   33: matrices.push()
   37: applyTransform(matrices)
   42: if (!hidden) renderCuboids(...)                   // this part's own cubes
   63: aload_0; getfield children:Ljava/util/Map;
   67: invokeinterface Map.values()
   72: invokeinterface Collection.iterator()
   79: [loop] iterator.hasNext() / iterator.next() -> recurse render(...) on each child
   ```
   This iterates **`children.values()`** — the whole backing `Map<String,
   ModelPart>` — and recurses into every child regardless of name. There is
   no name filtering anywhere in this method.
4. The actual submission path for `addPlayerSkin` confirms the model's own
   `render()` is what gets called, not a hand-picked field list.
   `DrawContext.addPlayerSkin(...)` (bytecode) just constructs a
   `PlayerSkinGuiElementRenderState` record (a descriptor holding the
   `PlayerEntityModel` reference + camera params), consistent with the
   already-known "extraction, not immediate draw" model. The actual draw
   happens later in `net.minecraft.client.gui.render.PlayerSkinGuiElementRenderer.render(...)`,
   whose bytecode does:
   ```
   ...
   state.playerModel() -> getLayer(texture) -> RenderLayer
   state.playerModel() -> render(matrixStack, vertexConsumer, light, overlay)
                           // invokevirtual PlayerEntityModel.render(...)
   ```
   `PlayerEntityModel` does not override `render(...)` — it inherits `Model`'s
   final method, which (per steps 1-3 above) walks the whole root `ModelPart`
   tree, including any extra sibling bones attached elsewhere in the
   hierarchy that are not `head`/`body`/etc.

**Note on posing vs. rendering** (the distinction the task asked to spell
out): `BipedEntityModel.setAngles(T)` — the per-frame *pose* method — does
explicitly reference only the seven named fields (`head`, `body`,
`rightArm`, `leftArm`, `rightLeg`, `leftLeg`, plus `hat`/sleeves/pants
mirroring their parents in `PlayerEntityModel.setAngles`). Extra scenery
bones are **not** auto-posed by `BipedEntityModel`/`PlayerEntityModel` the
way the character's named parts are — this matches current 1.21.11/26.1 code
already, which calls `applyPose(...)` manually against explicit `ModelPart`
references (`characterRoot`, `head`, `rightArm`, ...) obtained via
`root.getChild(name)`, not through the biped model's own `setAngles`. Scenery
bones (sky bands, sun, mountains, ground) need no per-frame pose at all in
the current design (`FR8.7`: fixed camera, static scene) — they only need to
*render*, which step 3 above confirms happens regardless of name. If a
future feature wanted an *animated* scenery bone, that animation would have
to be driven manually the same way character posing already is (grab the
`ModelPart` by name off the shared root, mutate its transform fields
per-frame) — `setAngles`/`setupAnim` on the biped wrapper would not do it
automatically. This is a real but narrow limitation, not a blocker for the
current placeholder geometry (fully static scenery).

### Evidence — 26.1 (Mojmap)

Same shape, confirmed independently against 26.1's own resolved jar:

- `net.minecraft.client.model.player.PlayerModel extends HumanoidModel<AvatarRenderState>`
  — adds `leftSleeve`/`rightSleeve`/`leftPants`/`rightPants`/`jacket` fields,
  same as 1.21.11's `PlayerEntityModel`.
- `net.minecraft.client.model.HumanoidModel<T> extends EntityModel<T>` — adds
  `head`/`hat`/`body`/`rightArm`/`leftArm`/`rightLeg`/`leftLeg` fields, same
  as 1.21.11's `BipedEntityModel`.
- `net.minecraft.client.model.Model<S>` (26.1's base, analogous to Yarn's
  `Model<S>`) exposes `public final void renderToBuffer(PoseStack,
  VertexConsumer, int, int, int)` and `public final ModelPart root()` — same
  "final render method delegating to the root part" shape.
- `ModelPart.render(PoseStack, VertexConsumer, int, int, int)` bytecode
  confirms the identical `children` `Map` iteration pattern: `getfield
  children:Ljava/util/Map` → `containsKey`/`get`(name lookups used only by
  `hasChild`/`getChild`, not by `render`) — `render` itself walks
  `children.values()` the same generic way (confirmed via the same
  decompiled structure as the Yarn side; both `ModelPart` classes are
  Mojang's own near-identical code, only mapping names differ).
- The picture-in-picture submission path exists under
  `net.minecraft.client.gui.render.pip.GuiSkinRenderer` (26.1's analogue of
  1.21.11's `PlayerSkinGuiElementRenderer`), following the same
  `GuiSkinRenderState` descriptor → later-pass-renders-the-model-generically
  shape already documented for 26.2 in `.claude/context/minecraft.md`.

### Verdict

**TRUE — the design assumption holds.** `PlayerEntityModel`
(1.21.11)/`PlayerModel` (26.1) constructors only check that the required
named children exist (via `getChild(String)`, throwing if absent); their
inherited, `final` `render()` method walks the *entire* `ModelPart` tree
generically by iterating the backing `children` `Map`, with zero filtering
by name. Extra sibling/child bones attached anywhere in the same hierarchy
(scenery: sky bands, sun, mountains, ground) render exactly the same as they
would through 26.2's generic `Model.Simple` path — confirmed by tracing
bytecode all the way from `addPlayerSkin`/`skin()`'s descriptor construction
through to the actual later-pass renderer invoking the model's inherited
`render()`/`renderToBuffer()`.

The one caveat (not a blocker, documented above): extra scenery bones are
not auto-*posed* by the biped wrapper's `setAngles`/`setupAnim` — only
*rendered*. Since the current scenery is fully static (no per-frame
animation, `FR8.7`), this doesn't affect the current design. It should be
called out for any future feature that wants animated scenery on
26.1/1.21.11 specifically.

This is classified **javap/bytecode-verified, not yet in-game-verified** —
no compile or manual smoke test was run as part of this specification phase
(per the instructions, no implementation code was written). The
implementation phase should still do a real in-game visual check after
wiring the shared hierarchy through on 26.1 and 1.21.11, both because
bytecode tracing can't rule out every runtime edge case (e.g. GPU-side
scissor/culling behavior specific to the picture-in-picture render pass) and
because this repo's own convention (`.claude/context/minecraft.md`'s
existing rows) treats "javap-confirmed" and "in-game-confirmed" as separate
checkboxes.

## Resulting architecture (since the verdict is TRUE)

### Shared mesh module

- New shared code lives in a module reachable by all three platform modules
  — this repo's Java-version-floor rules put shared code in `common`/`api`/
  `services`/`libraries` (see `.claude/context/minecraft.md`'s Java Version
  Floor section), compiled at Java 21 (the 1.21.11 floor). Check whether the
  vanilla `ModelPart`/`MeshDefinition`/`PartDefinition`/`CubeListBuilder`
  types themselves are safely shared this way (they are Minecraft classes,
  present identically-named in each platform module's own Minecraft
  dependency, so a shared *source* file referencing them can compile
  independently against each platform module rather than needing a genuine
  shared *artifact* — confirm during planning whether the existing
  `common`/`api` modules already reference `net.minecraft.*` types this way
  or whether this is a new pattern for this codebase).
  - **Alternative, likely simpler**: keep the mesh-building code duplicated
    verbatim (copy-pasted, not shared-module) across the three
    `MainMenuBackgroundRenderer`s if the class/package names of
    `ModelPart`/`MeshDefinition`/etc. diverge enough between Yarn and Mojmap
    (or between 26.1 and 26.2) that true code sharing needs an abstraction
    layer not worth building for one hand-authored placeholder mesh. This
    tradeoff (shared source vs. disciplined copy) is a planning-phase
    decision, not resolved here — flag it explicitly for planning.
- One `MeshDefinition`-building function (equivalent to today's
  `buildScene()` + `buildCharacterMesh()`, merged into one `PartDefinition`
  tree) becomes the single source of cube geometry, replacing three
  separate/duplicated definitions.

### Naming convention within the one hierarchy

- **Required-biped names** (must exist, populated with real, non-trivial
  geometry): `head`, `body`, `left_arm`, `right_arm`, `left_leg`,
  `right_leg`.
- **Required-placeholder names** (must exist per `PlayerEntityModel`/
  `PlayerModel`'s constructor, can be empty/zero-size cubes):
  `hat`, `left_sleeve`, `right_sleeve`, `left_pants`, `right_pants`,
  `jacket`. (Matches current per-version code exactly — `hat` today already
  carries real hair geometry, a deliberate choice worth preserving since
  `hat` is required to exist anyway.)
- **Scenery/cosmetic names** (any name not in the above two lists): sky
  bands, sun glow/core, near/far mountains, ground layers — attached as
  additional top-level children of the same root `PartDefinition`, exactly
  as 26.2's current `buildScene()` already does. These render generically
  per the verdict above and need no special naming.

### Per-version wrapper responsibilities

- **26.2**: builds the one shared `MeshDefinition`, bakes it once, wraps the
  baked root in a single `Model.Simple`, submits via one `skin(Model.Simple,
  ...)` call per frame instead of today's two (`sceneModel` +
  `characterModel`). This is a simplification relative to today's 26.2 code,
  not just a port.
- **26.1 / 1.21.11**: build the same shared `MeshDefinition`, bake it once,
  wrap the baked root in a real `PlayerModel`/`PlayerEntityModel` (satisfying
  the named-part constructor requirement using the shared hierarchy's
  required-biped + required-placeholder parts), submit via one
  `skin(PlayerModel, ...)`/`addPlayerSkin(PlayerEntityModel, ...)` call per
  frame — replacing today's split (3D character via `skin()`/
  `addPlayerSkin()` + 2D scenery via `fill`/`fillGradient`) with a single 3D
  call for everything.
- All three wrappers keep their own per-frame `applyPose(CharacterPose)`
  logic exactly as today (grabbing named `ModelPart`s off the baked root via
  `getChild(name)` and mutating rotation/offset fields) — posing is
  unaffected by this change, per the verdict's caveat above.

### Migration notes: removing 1.21.11's 2D fallback

- Delete `renderSceneAsFlat2D(...)` and its call site in
  `fabric-1.21.11/.../MainMenuBackgroundRenderer.render(...)`.
- Replace the character-only `ModelData`/`TexturedModelData` construction
  with the shared mesh (scene + character combined), and change
  `characterModel` (currently `PlayerEntityModel` built from just the
  character sub-tree) to be built from the full shared root instead.
- The single `context.addPlayerSkin(characterModel, ...)` call's camera
  params (`scale`, `rotationX`, `rotationY`, `pivotY`, destination rect) need
  new tuning once the model spans both scene and character geometry in one
  space — 26.2's existing two-calls-with-different-params (`scale=18f,
  pivotY=-6f` for scene; `scale=22f, pivotY=0f` for character) won't
  translate directly into a single call's single camera. This is real
  numeric-tuning work for the implementation phase, not just a plumbing
  change — flag it as a concrete risk/unknown for planning, likely needing
  an in-game visual pass to get right (consistent with 26.2's own FX8
  history of empirical scale/pivot tuning already recorded in that class's
  Javadoc).
- Same camera-unification concern applies to 26.1 (today also splits 3D
  character + 2D scenery, needs to merge into one 3D call) and to 26.2
  itself (today already 3D+3D but as two separate calls/cameras — unifying
  into one call, if desired, is explicitly out of scope per Non-goals, so
  26.2 may keep two `skin()` calls, just both now sourced from the one
  shared mesh's sub-parts, unless planning decides unifying into one call is
  in scope after all).

### Open items for the planning phase

1. Decide shared-source-module vs. disciplined-copy for the mesh-building
   code (see "Shared mesh module" above) — depends on whether
   `ModelPart`/`MeshDefinition`/`PartDefinition`/`CubeListBuilder`
   class/package names are identical enough across Yarn/Mojmap and 26.1/26.2
   to compile one shared source file against all three platform modules'
   own Minecraft dependency, or whether per-version type differences make
   that impractical.
2. Camera re-tuning (scale/rotation/pivot/destination-rect) for the merged
   scene+character single-call submission on 1.21.11 and 26.1, and
   optionally for 26.2 if its two-call split is also unified.
3. A real in-game smoke test after wiring, per the verdict's caveat — this
   spec's evidence is bytecode-level, not a live render confirmation.
