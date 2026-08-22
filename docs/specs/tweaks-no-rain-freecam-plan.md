# Implementation Plan: No Rain (T13) and Freecam (T14)

Ground truth: `docs/specs/tweaks-no-rain-freecam.md` (approved spec), read in
full this pass including the **Addendum** section (AD-1 through AD-5) added
after T13/T14 shipped and saw live use. **This revision of the plan
supersedes the original T14 sections below only where a "Addendum"-labeled
subsection says so** — the original plan content (No Rain in full; Freecam's
camera-attachment/collision-sweep/entity-exclusion/movement-routing
mechanisms, both already-shipped post-ship fixes rows 119/120, interaction
suppression) is retained as-is below for historical/traceability value and is
**unchanged, already implemented, already shipped** — do not re-implement it.
This revision's own new work is entirely inside the "Addendum:
AD-1..AD-5 corrective plan" section near the end.

**Second revision (this pass), covering Addendum 2's AD-6/AD-7/AD-8**: ground
truth is `docs/specs/tweaks-no-rain-freecam.md`'s "Addendum 2" section
(AD-6/AD-7/AD-8), read in full this pass. AD-1..AD-5 are confirmed **already
shipped** (re-confirmed by directly reading the live `FreecamHook.java`,
`FreecamTicker.java`, `FreecamCameraEntity.java`, `TweakHooksImpl.java`,
`TweakDefinitions.java`, `ConfigSchemas.java`, `TweaksConfigIO.java`, and all
five existing Freecam mixins on `fabric-26.2` this pass — see "Existing
Implementation — as of AD-1..AD-5 ship" below) — nothing in AD-1..AD-5 is
reopened by this revision except where AD-7/AD-8 explicitly note an
interaction. This revision's new work is entirely inside the new "Addendum 2:
AD-6/AD-7/AD-8 plan" section at the end of this document.

## Existing Implementation

### As of original T14 ship (unchanged, retained for context)

Framework pieces this feature plugs into additively (spec Architecture —
Framework Fit; confirmed by direct read this pass, not just cited from the
spec):

- `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` — `NO_RAIN`,
  `FREECAM` constants already present (shipped).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
  lines 116-118 (confirmed read this pass): `FREECAM` definition currently
  `map("moveSpeed", 1.0, "sprintMultiplier", 2.0, "noclip", true,
  "showOwnBody", true)`, `hasSecondaryKeyBinding = false`; `ALL` list at
  line 123 includes `FREECAM`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  lines 114-119 (confirmed read this pass): `FREECAM` field list currently
  `numeric("moveSpeed", "Move Speed", 0.1, 10.0, 0.1)`,
  `numeric("sprintMultiplier", "Sprint Multiplier", 1.0, 5.0, 0.5)`,
  `bool("noclip", "Noclip")`, `bool("showOwnBody", "Show Own Body")`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java`
  — confirmed read this pass, exact shape: `isFreecamActive()`,
  `freecamMoveSpeed()` (returns `float`), `freecamSprintMultiplier()`,
  `freecamNoclip()`, `freecamShowOwnBody()`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`
  — confirmed read in full this pass. `parse(String content)` (lines 70-113):
  for each `TweakId`, seeds `configurables` from
  `TweaksConfig.DEFAULT.stateOf(id).configurables()` (a fresh
  `LinkedHashMap`) then overlays every key present in the file's
  `configurables` object (lines 102-106) — this is the exact seam AD-3's
  migration branch must extend, gated on `id == TweakId.FREECAM`. The raw
  per-tweak `MainMenuJson.JsonObject configurablesObject` (line 96) is
  available at that point and has a `.has(String key)` method
  (`common/src/main/java/de/lazuli/common/config/MainMenuJson.java:85-86`,
  confirmed present, `containsKey`-backed) — exactly what AD-3's "does the
  raw file contain a `moveSpeed`/`moveSpeedRescaled` key" check needs, no new
  JSON-reading primitive required. `serialize(...)` (lines 115-130) writes
  every key in a `TweakState`'s `configurables` map unconditionally, so once
  `moveSpeedRescaled` is added to `TweakDefinitions.FREECAM`'s default map it
  is written on every subsequent save with zero serializer changes.
- `platform/<module>/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java` (×3,
  confirmed on 26.2 this pass, e.g. lines 49/158/221/307-333) — implements
  `..., NoRainHook, FreecamHook`; the 5 `FreecamHook` methods are trivial
  `TweakRegistry` reads at lines 307-333. Existing precedent for **extra
  platform-only methods beyond a hook interface's contract**:
  `crosshairConfigurable(String)` (line 158), `setZoomActive(boolean)` (line
  221) — exactly the shape AD-2's new "is the camera inside the player's
  AABB" method should follow (a package/module-visible method on this same
  class, not on `FreecamHook`). Existing precedent for **resolving a raw
  `String` `mode`/`position` configurable into one or more plain booleans
  before crossing the `FreecamHook`-shaped interface** (directly relevant to
  AD-8's `onHurt` enum): `headBeforeName()` (lines 144-146,
  `!"AFTER".equals(state(TweakId.CHAT_PLAYER_HEADS).configurable("position"))`)
  and the shared `modeExcludes(TweakState, String)` helper (lines 174-186,
  used by `shouldAnimate`/`shouldSpawnParticle`/`shouldHideBossBar`) — both
  confirmed read this pass.
- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamTicker.java` (×3,
  confirmed near-identical on 26.2/1.21.11/26.1 this pass — 26.1/26.2 are
  Mojmap-identical per the file's own Javadoc, 1.21.11 differs only in
  Yarn-mapped types). Confirmed structure: `register(TweaksKeyBindings,
  TweakHooksImpl, TweakRegistry)`; `lazuli$tick` drives
  activate/deactivate/integrate + the 4-condition safety net
  (`lazuli$safetyNetTripped`, already covers disconnect/respawn/dimension-
  change/death, unaffected by this addendum); `lazuli$activate` seeds the
  camera's start position/yaw/pitch from the player's eye position and live
  yaw/pitch (`client.player.getYRot()/getXRot()`) — this seeding is
  unchanged by AD-1, only the *ongoing per-tick* copy is removed;
  `lazuli$integrate` (confirmed exact text on 26.2, lines 100-117) is the
  **single file for AD-1, AD-3 (partial), and AD-5** — it computes
  `strafe`/`forward`/`vertical` from `player.input.keyPresses`
  (`rawInput`), computes `speed = baseSpeed * hooks.freecamMoveSpeed() *
  (sprint ? sprintMultiplier : 1)`, and calls
  `cameraEntity.lazuli$integrate(delta, player.getYRot(), player.getXRot(),
  hooks.freecamNoclip())` — this last call is the confirmed AD-1 root cause
  (copies the player's live, mouse-driven yaw/pitch into the camera every
  tick unconditionally).
- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java`
  (×3, confirmed on 26.2 in full, 1.21.11 partially, this pass). Backed by
  `EntityTypes.MARKER` (26.1/26.2) / `EntityType.MARKER` (1.21.11) purely as
  a placeholder type — confirmed via each file's own Javadoc this is the
  root cause of AD-4 (zero-size bounding box, nothing for the block-
  collision sweep to act on). `lazuli$integrate(Vec3 desiredDelta, float
  yaw, float pitch, boolean noclip)` (26.2 lines 102-115, confirmed) is where
  AD-1's per-parameter yaw/pitch write (`this.setYRot(yaw);
  this.setXRot(pitch);`) lives, and is also where AD-4's larger bounding box
  actually gets swept against (`this.getBoundingBox().expandTowards(...)`,
  `Entity.collideBoundingBox(...)`) — so AD-4's fix (give the entity a
  non-zero box) requires no change to this method's own logic, only to
  wherever the entity's dimensions/bounding box are established. No
  `getDimensions`/`EntityDimensions` override exists yet on any platform
  (confirmed absent this pass) — AD-4 is new code, not a bug in existing
  code beyond the `EntityTypes.MARKER` choice itself.
- **AD-5's confirmed-identical strafe line, exact locations** (independently
  re-confirmed this pass, matching the spec's own citations exactly):
  `platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/FreecamTicker.java:124`,
  `platform/fabric-26.1/src/main/java/de/lazuli/tweaks/FreecamTicker.java:102`,
  `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java:104`
  — all three read `double strafe = (rawInput.right() ? 1.0 : 0.0) -
  (rawInput.left() ? 1.0 : 0.0);` verbatim.
- Show-body mixins (confirmed read in full for 26.2 this pass,
  `LevelExtractorFreecamShowBodyMixin.java`; 1.21.11/26.1 counterparts
  `WorldRendererFreecamShowBodyMixin.java`/`LevelRendererFreecamShowBodyMixin.java`
  not re-read this pass but same `@Redirect`-on-`camera.entity()`/
  `getFocusedEntity()` shape per `.claude/context/minecraft.md` row 113,
  already cited by the spec). Current condition (26.2, line 40):
  `hooks.isFreecamActive() && hooks.freecamShowOwnBody()`. AD-2 changes this
  one line's second half only — same `@Redirect`, same target method/
  ordinal, same fallback-to-`real`-entity shape, nothing else in this file
  changes.
- `TweaksPanel.java` (confirmed via grep this pass, 26.2): **zero** matches
  for `Freecam`/`freecam`/`showOwnBody` — confirms the spec's claim that
  config rows render entirely generically off `ConfigSchemas.fieldsFor(id)`
  with no per-tweak code. AD-2's row removal and AD-3's range/step/default
  change both need **zero changes to this file**.
- `.claude/context/minecraft.md` rows 112-120 (read in full this pass) —
  the already-`javap`-confirmed findings the spec's Addendum cites
  throughout; **rows 112/115/116/119/120 are directly relevant to this
  addendum's implementation** (movement-key routing gate, block-collision
  primitive signature/name divergence, `EntityType`/`EntityTypes` constant
  location divergence, the two old-position-field-trio and
  `sendMovementPackets`/`sendPosition` gating bugs already fixed and NOT
  reopened by this addendum). No existing row covers AD-1's mouse-look call
  site or AD-4's `getDimensions`/`EntityDimensions` override point — both
  are genuinely new `javap` targets, exactly as the spec's own "Addendum
  methodology note" and "open items for planning" list say.
- No existing platform-side unit tests for any tweak's mixin/ticker
  behavior (unchanged finding from original plan). `features/tweaks`' plain-
  Java layer (`TweaksConfigIOTest`) remains the only unit-tested layer and
  is exactly where AD-3's migration-branch logic is testable without any
  platform module at all.
- Jar paths for the mandatory `javap` pass (unchanged from original plan,
  still the correct paths):
  - 1.21.11 (Yarn): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-6dd721cd7d-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
  - 26.1 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/minecraft-merged-a26c9a9f3c-26.1.jar`
  - 26.2 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`

## Addendum: AD-1..AD-5 corrective plan

Sequencing/dependency note up front: **AD-2, AD-3, and AD-5 touch disjoint
files from each other and from AD-1/AD-4**, so those three can be
implemented and verified independently, in any order, with AD-5 first
(zero `javap` risk, single-line, spec explicitly says so) and AD-3's
plain-Java migration branch second (also zero `javap` risk, unit-testable in
isolation). **AD-1 and AD-4 both require their own dedicated `javap` spike
against a genuinely new vanilla target** (mouse-look call site; entity-
dimensions override point) and should be sequenced after AD-5/AD-3 land, in
either order relative to each other since they touch different files
(`FreecamCameraEntity.java`+possibly a new mouse mixin for AD-1;
`FreecamCameraEntity.java` again for AD-4 — **both AD-1 and AD-4 touch
`FreecamCameraEntity.java` on all three platforms, so batch their
`FreecamCameraEntity.java` edits together per platform to avoid two separate
review/compile passes over the same file**). AD-2 also reads
`FreecamCameraEntity`'s live position (via `FreecamTicker`) but only adds a
new method to `TweakHooksImpl`/a new field read in `FreecamTicker` — it does
not need to touch `FreecamCameraEntity.java` itself, so it is not part of
that batch.

### AD-5 — Inverted strafe (implement first: zero risk, no javap)

**Files to modify** (×3, one-line change each, exact locations confirmed
this pass):

- `platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/FreecamTicker.java:124`
- `platform/fabric-26.1/src/main/java/de/lazuli/tweaks/FreecamTicker.java:102`
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java:104`

Change `(rawInput.right() ? 1.0 : 0.0) - (rawInput.left() ? 1.0 : 0.0)` to
`(rawInput.left() ? 1.0 : 0.0) - (rawInput.right() ? 1.0 : 0.0)`. No other
line in any file changes. No new files.

### AD-3 — Move Speed rescale + config migration (implement second: no javap, unit-testable)

**Files to modify:**

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
  (line ~118 today) — `FREECAM` default configurables map becomes
  `map("moveSpeed", 1.0, "sprintMultiplier", 2.0, "noclip", true,
  "moveSpeedRescaled", true)` (drops `showOwnBody`, per AD-2 below — batch
  this one edit to cover both addenda's changes to this same line rather
  than editing it twice).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  (lines 114-119 today) — `FREECAM` field list becomes
  `numeric("moveSpeed", "Move Speed", 0.25, 5.0, 0.25)`,
  `numeric("sprintMultiplier", "Sprint Multiplier", 1.0, 5.0, 0.5)`,
  `bool("noclip", "Noclip")` (drops the `showOwnBody` bool row, per AD-2 —
  same batching note).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`
  — inside `parse(...)`'s per-`TweakId` loop (lines 85-108 today), after
  `configurablesObject` is resolved (line 96) and before `tweaks.put(id, new
  TweakState(...))` (line 107), add a block gated on `id ==
  TweakId.FREECAM`:
  1. If `configurablesObject.has("moveSpeed")` and NOT
     `configurablesObject.has("moveSpeedRescaled")`: divide the in-progress
     `configurables.get("moveSpeed")` value by `10.0` before it's placed into
     the final map (spec AD-3 mechanism step 3).
  2. Unconditionally set `configurables.put("moveSpeedRescaled", true)` in
     every case reached by this branch (spec AD-3 step 4) — already the
     default-map value in the common case, forced true after a migration.
  3. Defensive clamp: `configurables.put("moveSpeed", clamp(((Number)
     configurables.get("moveSpeed")).doubleValue(), 0.25, 5.0))` (spec AD-3
     step 5) — guards hand-edited/out-of-range JSON regardless of path taken.
  - This block runs *after* the generic overlay loop (lines 104-106) already
    populated `configurables` from the file, so it can inspect both the
    overlaid in-memory value and the raw `configurablesObject.has(...)`
    presence checks in one place — no restructuring of the existing generic
    loop needed, this is purely additive code inserted right before the
    `tweaks.put(...)` call, scoped with a single `if (id ==
    TweakId.FREECAM)`.
- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamTicker.java` (×3)
  — add `private static final float MOVE_SPEED_RUNTIME_SCALE = 10.0f;` and
  change `lazuli$integrate`'s speed computation to `speed = baseSpeed *
  hooks.freecamMoveSpeed() * MOVE_SPEED_RUNTIME_SCALE * (rawInput.sprint() ?
  hooks.freecamSprintMultiplier() : 1.0f)` (26.2 line 109-110 today; same
  line/shape on 1.21.11/26.1). No other line in this file changes for AD-3
  (AD-1/AD-5 touch adjacent lines in the same method — batch all three
  addenda's edits to this one method per platform into a single pass over
  the file, see sequencing note above).

**Files to create:** none for AD-3.

**Test strategy (AD-3-specific, unit-testable today):** extend
`features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
with cases covering all three `TweaksConfigIO.parse` branches: (a) a
`FREECAM.configurables` JSON object with no `moveSpeed` key at all → result
has default `1.0`/`moveSpeedRescaled = true`, no division; (b) an object
with `moveSpeed: 10.0` and no `moveSpeedRescaled` key → result has
`moveSpeed = 1.0`, `moveSpeedRescaled = true` (the migration path); (c) an
object with `moveSpeed: 0.5` and `moveSpeedRescaled: true` already present →
result has `moveSpeed = 0.5` unchanged (already-migrated/idempotency path);
(d) a re-`serialize`+`parse` round trip of case (b)'s *result* confirms the
second parse does **not** divide again (idempotency, since the marker is now
present) — this is the load-bearing regression test for the "never divide
twice" requirement. (e) an out-of-range hand-edited value (e.g. `moveSpeed:
99.0` with `moveSpeedRescaled: true` present) clamps to `5.0`.

### AD-2 — Show Own Body becomes automatic (no javap needed — Entity.getBoundingBox()/AABB.inflate/contains are stable long-standing APIs, but exact overload names should still get a quick javap spot-check per platform per this repo's standing convention)

**Files to modify:**

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java`
  — remove `freecamShowOwnBody()` from the interface entirely.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
  and `ConfigSchemas.java` — covered above under AD-3's file list (same
  lines, batched edit — dropping `showOwnBody` from both).
- `platform/<module>/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  (×3) — remove the `freecamShowOwnBody()` method (no longer part of the
  interface); add a new platform-only method (not on any interface),
  following the `crosshairConfigurable`/`setZoomActive` precedent (Existing
  Implementation), e.g. `boolean freecamCameraInsidePlayerBounds()` backed
  by a field this class exposes a setter for, OR — simpler, avoiding a new
  mutable field on `TweakHooksImpl` — have `FreecamTicker` compute the
  boolean itself each tick (it already holds direct references to both
  entities, per spec's own "Where this lives" recommendation) and pass it
  straight to a small **static** accessor `FreecamTicker` itself exposes
  (e.g. `FreecamTicker.isCameraInsidePlayerBounds()`), which the show-body
  mixins call directly instead of going through `TweakHooksImpl` at all —
  either placement satisfies the spec's constraint ("cannot live in
  `FreecamHook`/`features/tweaks`"); recommend the `FreecamTicker`-static
  option since it avoids adding a new field/setter pair to
  `TweakHooksImpl` purely to shuttle a value computed one call away, and
  `TweaksToggleTicker`/mixins already reach `TweakEngineHandoff.require()`
  for `TweakHooksImpl` state but nothing prevents a mixin also referencing
  `FreecamTicker` directly (same package, `de.lazuli.tweaks`, both already
  public/package-visible to `de.lazuli.mixin` the same way
  `TweakEngineHandoff` is) — **final placement choice (TweakHooksImpl field
  vs. FreecamTicker static) is a small implementation-time judgment call,
  not gated on anything else in this plan.**
- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamTicker.java`
  (×3) — inside `lazuli$integrate` (or `lazuli$tick`, wherever both entity
  references are already in scope each tick), compute `boolean hide =
  cameraEntity.getBoundingBox() /* actually player's box, see below */`...
  precisely: `AABB playerBox = player.getBoundingBox(); boolean cameraInside
  = playerBox.inflate(0.1).contains(cameraEntity.getX(), cameraEntity.getY(),
  cameraEntity.getZ());` (Mojmap `AABB`/`inflate`/`contains(double,double,double)`;
  Yarn `Box`/`expand`/`contains` — exact overload names to confirm via a
  quick `javap` spot-check per spec's Addendum "open items for planning" #2,
  low risk, both are long-stable core geometry APIs) — store the result
  somewhere the show-body mixin can read it each frame (per the placement
  decision above).
- `platform/<module>/src/main/java/de/lazuli/mixin/{WorldRendererFreecamShowBodyMixin,LevelRendererFreecamShowBodyMixin,LevelExtractorFreecamShowBodyMixin}.java`
  (×3, one per platform, files already exist and are confirmed-shipped) —
  change the `@Redirect` handler's condition only (26.2 confirmed exact
  line: `LevelExtractorFreecamShowBodyMixin.java:40`,
  `hooks.isFreecamActive() && hooks.freecamShowOwnBody()`) to
  `hooks.isFreecamActive() && !<newInsideCheck>()` — same `@Redirect`
  target/ordinal/fallback-to-`real` shape, this one condition line only, on
  all three files.

**Files to create:** none for AD-2.

**Test strategy (AD-2-specific, manual only — geometric/render behavior, no
unit-testable seam):** with Freecam active, fly the camera slowly outward
from directly inside the player's model until the body appears — confirm it
appears close to the (inflated) hitbox boundary, not at some other distance;
fly back in and confirm it disappears again with no visible single-frame
flicker at moderate flight speed; repeat while the player is sneaking (0.6×
1.5 box) and swimming (0.6×0.6 box) to confirm the check uses the player's
*live*, not activation-time-cached, bounding box (spec's explicit
requirement) — e.g. activate Freecam while standing, then have the player
start swimming (auto behavior, no input needed since player sim continues
normally) and confirm the hide/show boundary visibly shrinks to match.

### AD-1 — Player rotation decoupling (javap-blocked: mouse-look call site)

**Mandatory first step:** `javap -c -p` each platform's resolved merged jar
for the real per-frame mouse-look integration call site, resolving the
spec's own flagged fork:

- **Branch (a) — hard-codes the player/local entity, needs a new mixin.**
  Candidate targets per spec: Yarn `net.minecraft.client.Mouse.updateMouse(double)`
  (private); Mojmap `net.minecraft.client.MouseHandler.turnPlayer()`
  (public) — **confirm independently on 26.1 and 26.2, do not assume
  identical** (spec's own repeated caution, rows 69/104/113/116 precedent).
- **Branch (b) — already keys off `getCameraEntity()`/`entity()`, no mixin
  needed.** If confirmed, the fix is entirely inside
  `FreecamCameraEntity.java` (a new `turn(double dx, double dy)`-shaped
  method the mouse handler calls when the camera entity is the active
  focus) plus removing the `player.getYRot()/getXRot()` copy from
  `FreecamTicker.lazuli$integrate`'s call into `cameraEntity.lazuli$integrate(...)`.

**Files to modify regardless of branch (×3):**

- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java`
  — `lazuli$integrate`'s signature drops the `float yaw, float pitch`
  parameters (26.2 today: `lazuli$integrate(Vec3 desiredDelta, float yaw,
  float pitch, boolean noclip)`, lines 102-105 setting
  `this.setYRot(yaw)`/`this.setXRot(pitch)` every call) — the camera instead
  owns its own persistent yaw/pitch, mutated only by the new mouse-turn path
  (branch a or b above), never by `FreecamTicker` per-tick anymore. Seeded
  once at `lazuli$activate` time (unchanged — `FreecamTicker.java` already
  does `camera.setYRot(client.player.getYRot()); camera.setXRot(...)` at
  activation, this specific call stays).
- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamTicker.java` —
  `lazuli$integrate`'s call to `cameraEntity.lazuli$integrate(delta,
  player.getYRot(), player.getXRot(), hooks.freecamNoclip())` drops the two
  rotation arguments: `cameraEntity.lazuli$integrate(delta,
  hooks.freecamNoclip())`. **Batch this edit with AD-3/AD-5's edits to the
  same method** (sequencing note above) to avoid three separate diffs
  touching adjacent lines of the same ~15-line method.

**Files to create (only if branch (a) is confirmed, ×3, provisional names):**

- `platform/<module>/src/main/java/de/lazuli/mixin/<TBD>FreecamMouseLookRedirectMixin.java`
  — `@Redirect`/`@ModifyArgs`-shaped, mirroring
  `LocalPlayerFreecamKeepPositionSyncMixin`'s existing precedent shape
  (already-shipped file in the same `de.lazuli.mixin` package) — while
  Freecam is active, routes that frame's raw mouse delta to
  `FreecamCameraEntity`'s new turn method and suppresses the real player's
  own turn for that same delta. Exact target method/redirect point is fully
  `javap`-blocked per platform (mandatory first step, this item's own
  biggest risk).
- `platform/<module>/src/main/resources/lazuli.mixins.json` (×3) — append
  the new mixin's simple class name, only once branch (a) is confirmed and
  the file above actually exists (same "don't reference not-yet-written
  classes" sequencing discipline the original plan already established).

**Files to modify (only if branch (a), additionally):** `TweakHooksImpl.java`
is not expected to need a new method for AD-1 (the new mixin reads
`TweakEngineHandoff.require().isFreecamActive()`, already exposed) — no
change expected there, called out only to rule it in/out during
implementation.

**Test strategy (AD-1-specific, manual only):** activate Freecam, move the
mouse in all directions, and from a second vantage point (a second client
session, or `/spectate`-equivalent, or simply watching the player's own
body/head model from outside via `showOwnBody`'s now-automatic on-state)
confirm the player's body/head **do not turn** while the camera's own view
freely looks around; deactivate Freecam and confirm mouse-look immediately
resumes controlling the real player's rotation with no visible snap
(continuing from the pinned snapshot value, per spec's exact "no
discontinuity" requirement); confirm the previously-shipped row 120 fix is
not regressed (no anti-cheat rubber-banding after a Freecam session — the
pinned rotation should still be networked correctly per spec's explicit
"does not reopen row 120" note).

### AD-4 — Noclip-off collision: real bounding box (javap-blocked: dimensions override point)

**Mandatory first step:** `javap -c -p` each platform's resolved merged jar
to confirm whether `Entity.getDimensions(Pose)` (Mojmap) /
`Entity.getDimensions(EntityPose)` (Yarn) is actually the live source of
`getBoundingBox()`'s size for an `Entity` that is constructed, positioned via
`setPos(...)`, but never added to the world/ticked by vanilla's own loop —
per spec's own flagged uncertainty, this is a genuinely new question no
existing row answers. Confirm independently on 26.1 and 26.2 (standing "do
not assume identical" caution).

**Files to modify (×3):**

- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java`
  — **primary branch (if `getDimensions` confirmed live):** override
  `protected EntityDimensions getDimensions(Pose pose)` (Mojmap) /
  `protected EntityDimensions getDimensions(EntityPose pose)` (Yarn) to
  return a fixed `0.45F × 0.45F` box (exact factory method —
  `EntityDimensions.fixed(float, float)` or equivalent overload — to confirm
  via the same `javap` pass). **Fallback branch (if not live for a
  never-ticked entity):** add a one-time `this.setBoundingBox(...)`-shaped
  call in the constructor computing a fixed `0.45×0.45×0.45` `AABB`/`Box`
  from the entity's current position, OR recompute it at the top of
  `lazuli$integrate` each tick from the entity's current position before the
  collision sweep runs (spec's own named fallback) — this branch touches the
  same method AD-1 already edits (`lazuli$integrate`), so **batch AD-1 and
  AD-4's edits to `FreecamCameraEntity.java` into one pass per platform**
  (sequencing note above), regardless of which AD-4 branch is confirmed.
- Update each file's class Javadoc to remove the now-stale "this
  incidentally also gives it MarkerEntity's own zero-size bounding box... a
  deliberate v1 simplification" passage (26.2/1.21.11 both currently contain
  this text, confirmed this pass) and replace with a short note describing
  the fixed 0.45-block box and which override mechanism was actually
  confirmed live.

**Files to create:** none expected for AD-4 (a `FreecamCameraEntity.java`
method addition/override, not a new class or mixin) — confirm during the
`javap` spike that no mixin onto vanilla's own dimension-resolution
machinery is needed (expected: none, since `FreecamCameraEntity` is our own
subclass with ordinary Java override access to `getDimensions`, per the same
"genuine subclass, no mixin needed" precedent row 115 already established
for the collision-sweep primitive).

**Findings to record:** once confirmed, add a new row to
`.claude/context/minecraft.md` documenting the exact `getDimensions`/
`EntityDimensions` factory signatures per platform and whether the override
is in fact live for a manually-driven entity — the spec explicitly calls
this out as a new fact this addendum's implementation should feed back into
that table (no existing row covers it).

**Test strategy (AD-4-specific, manual only):** with `noclip = false`,
fly directly at a solid wall/floor/ceiling from multiple angles and confirm
the camera stops at the surface instead of passing through (the confirmed
bug being fixed); fly through a 1-block gap a full player couldn't fit
through but that's wider than 0.45 blocks, confirming the smaller head-sized
box (not a full 0.6-wide body box) is what's actually being swept; with
`noclip = true`, confirm collision is still fully bypassed (unaffected by
this item); fly the camera close to/through a mob or another player in both
noclip states and confirm entity collision is still never applied (this
item's fix must not regress the already-shipped, unconditional entity-
collision exclusion).

## Dependencies

No new external (non-Fabric) dependency for any of the 5 addendum items —
all five are implemented entirely with:

- Vanilla Minecraft classes already on each platform module's existing
  Yarn/Mojmap compile classpath (no `build.gradle` change) — `Entity`,
  `AABB`/`Box`, `EntityDimensions`, and whichever mouse-input class AD-1's
  `javap` pass confirms.
- The existing Sponge Mixin setup (plain `@Inject`/`@Redirect`,
  `cancellable = true` where relevant, no MixinExtras — same standing
  convention the original plan already established and this repo's shipped
  Freecam mixins already follow).
- Plain Java (`features/tweaks` module) for AD-3's migration branch — no
  Minecraft-jar dependency at all.

No Maven Central verification is needed for this plan (no new coordinate
proposed for any of the 5 items). If AD-1's `javap` spike somehow surfaces a
need for a library-level dependency (not expected — this is a mixin/vanilla-
API question, not a missing-library question), that would require the
standard Maven Central check before adoption, same standing rule as the
original plan.

## Risks

- **AD-1's mouse-look call site is the single largest unknown in this
  revision** (spec's own framing) — until the `javap` spike resolves branch
  (a) vs. (b), the size of this item (new mixin + new redirect target vs. a
  small in-class method addition) is unknown. Recommend running this spike
  first among the two javap-blocked items, since it also determines whether
  `FreecamCameraEntity`'s public method surface needs a new `turn(...)`-
  shaped entry point that AD-4's own edits to the same file should be aware
  of (batch order: AD-1's spike result should land before finalizing AD-4's
  edits to the same file, even though both are "batched" into one pass per
  the sequencing note — spike AD-1 first, then make both edits together).
- **AD-1 cross-platform divergence risk**: per the spec's own explicit
  caution and this repo's repeated real precedent (rows 69/104/113/116), do
  not assume 26.1 and 26.2 share the same mouse-handler class/method shape
  even though both are "26.x Mojmap" — each needs its own independent
  `javap` confirmation, not a single check reused for both.
- **AD-2 margin/hysteresis risk**: the spec's own fixed 0.1-block margin is
  explicitly flagged as possibly needing a real hysteresis dead-band if live
  testing shows flicker at the boundary (spec Future Extensions) — not
  required for this pass, but the manual test plan above specifically
  exercises the boundary at "moderate flight speed" to surface this early
  if it's going to be a problem, rather than only discovering it much later.
- **AD-2 placement choice (`TweakHooksImpl` field vs. `FreecamTicker`
  static)** is an open, small implementation-time judgment call (see AD-2's
  Files to Modify) — does not block planning but should be resolved
  consistently across all three platforms (pick one shape, use it on all
  three, don't let 1.21.11 diverge from 26.1/26.2 here for no reason).
- **AD-3 migration idempotency is the most safety-critical piece of this
  entire revision** — a bug that re-divides an already-migrated value on a
  second load would silently and repeatedly halve a user's chosen speed
  every time the game restarts. This is exactly why the unit test plan
  above includes an explicit "serialize the migrated result, then parse it
  again, confirm no second division" case (case (d)) as a first-class
  regression test, not an afterthought — recommend treating that specific
  test as a hard gate before this item is considered done, independent of
  the rest of the acceptance criteria below.
- **AD-4 dimensions-override-liveness risk**: the spec itself flags this as
  a genuinely open question ("whether `getDimensions(Pose)` is in fact live
  for a manually-driven, never-ticked `Entity` subclass's `getBoundingBox()`
  result") with a named fallback if it isn't. Budget time for both branches
  during implementation rather than assuming the cleaner override-based
  branch will pan out.
- **AD-4/AD-1 shared-file batching risk**: since both items edit
  `FreecamCameraEntity.java`'s `lazuli$integrate` method (AD-1 removes
  parameters, AD-4 either adds a `getDimensions` override elsewhere in the
  class or adds bounding-box recomputation inside this same method), doing
  them as two fully separate, sequential PRs against the same method
  invites a trivial but annoying merge/rebase conflict — recommend a single
  combined edit pass per platform for this one file, even though the two
  items' `javap` spikes and acceptance criteria remain independently
  trackable.
- **General mixin-authoring risk** (AD-1's new mixin, if branch (a) is
  confirmed): exact `@At` injection point, whether the target method is
  `final`, local-capture ordinals if `@ModifyArgs`/`@ModifyVariable` ends up
  needed instead of a plain `@Redirect` — same standing risk category the
  original plan already carries for every mixin target in this feature.
- **No live-launch verification by the implementing/verifying agent this
  round** — the user is on remote control and cannot see/close a launched
  Minecraft window right now (`feedback_no_launch_minecraft_remote.md`).
  Every manual test listed above (AD-1 through AD-5's "Test strategy"
  subsections) must be executed by the **user**, later, not simulated or
  assumed passing by any agent. This round's agent-side verification is
  strictly limited to: compiling all three platform modules
  (`./gradlew :platform:fabric-1.21.11:compileJava
  :platform:fabric-26.1:compileJava :platform:fabric-26.2:compileJava`, or
  equivalent full `build`/`check` task per module), running the extended
  `TweaksConfigIOTest` suite (AD-3), and a careful re-read of the final
  diffs against each addendum item's target behavior described above — not
  an in-game pass. This is a hard process constraint for this round, not
  merely a preference.

## Test Strategy (summary — see each AD-N subsection above for the detailed version)

- **Automated/unit** (the only agent-executable verification this round):
  `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
  extended per AD-3's 5 new cases (a-e above); existing test suite must
  continue passing unmodified for every other `TweakId`.
- **Compile-only, per platform** (the only platform-level agent-executable
  verification this round, per the Risks note above): a full Gradle
  compile/check of `platform:fabric-1.21.11`, `platform:fabric-26.1`,
  `platform:fabric-26.2` after each addendum item's edits land, confirming
  no mixin-registration errors (`lazuli.mixins.json` entries, if AD-1 adds
  one, must resolve), no type errors from the `FreecamHook` interface change
  (AD-2) rippling through `TweakHooksImpl`, `TweaksConfigIOTest`, and any
  other caller.
- **Manual, in-game, per platform (×3) — deferred to the user, not run by
  any agent this round.** A consolidated checklist (superset of each AD-N
  subsection's own "Test strategy" above, organized for a single Freecam
  session per platform rather than five separate sessions):
  1. AD-5: press A, confirm camera strafes left; press D, confirm right.
  2. AD-3: open the Tweaks tab, confirm `Move Speed` now steps in `0.25`
     increments from `0.25` to `5.0`; for a pre-existing install with a
     previously-tuned `moveSpeed`, confirm the felt flight speed after
     upgrading is unchanged from before (the core migration promise); for a
     fresh install, confirm the new default's felt speed is reasonable (not
     required to match the old default's feel, per spec).
  3. AD-2: confirm the `Show Own Body` checkbox row no longer appears in the
     Tweaks tab at all; confirm the body auto-hides/auto-shows per the
     camera-inside-hitbox rule, including while the player's pose changes
     (sneaking/swimming) mid-session.
  4. AD-4: confirm `noclip = false` now actually stops the camera at block
     boundaries (previously did not); confirm `noclip = true` and entity-
     passthrough are both unaffected.
  5. AD-1: confirm the player's body/head do not turn to follow the freecam
     view; confirm mouse-look resumes controlling the real player smoothly
     (no snap) on deactivation; confirm no anti-cheat/rubber-banding
     regression (row 120's fix still holds).
  6. Regression pass on already-shipped behavior this addendum must not
     break: the 4 safety-net triggers (disconnect/respawn/dimension-change/
     death) still restore the camera; interaction is still suppressed while
     active; the real player's own simulation (fall damage, drowning, AI
     targeting, hunger) is still fully unaffected by Freecam being active.

## Acceptance Criteria

- **AD-1**: while Freecam is active, the real player entity's yaw/pitch
  fields never change as a result of mouse input (verified both visually
  from a second vantage point and, if feasible, by the user directly
  inspecting network/position state); the camera's own look direction is
  driven directly and exclusively by mouse input; deactivation hands mouse
  control back to the player continuing from the pinned snapshot with no
  discontinuity; row 120's already-shipped position/rotation-networking fix
  is not regressed.
- **AD-2**: the `showOwnBody` configurable, its `TweakDefinitions`/
  `ConfigSchemas` entries, and its `FreecamHook` method are all fully
  removed with no compile errors anywhere in the codebase; the player's body
  automatically hides exactly when the freecam camera's position is inside
  the player's own live (inflated-by-0.1) bounding box and shows otherwise,
  tracking the player's live pose (not an activation-time snapshot) on all
  three platforms.
- **AD-3**: `moveSpeed`'s UI range/step/default are `0.25`-`5.0`/`0.25`/
  `1.0`; an old, pre-addendum `tweaks.json` with a previously-tuned
  `moveSpeed` value loads with an unchanged felt flight speed (migration
  correctness); the migration runs at most once per save file
  (idempotency, the hard-gated regression test); a brand-new install starts
  at the new default with no migration branch taken; `sprintMultiplier` is
  provably unaffected (unchanged bounds/behavior).
- **AD-4**: `noclip = false` now actually prevents the camera from passing
  through block geometry on all three platforms (the confirmed regression
  this item fixes), using a fixed ~0.45-block cube, without affecting
  `noclip = true`'s full pass-through or the unconditional entity-collision
  exclusion.
- **AD-5**: A strafes left, D strafes right, on all three platforms; W/S and
  Space/Sneak are unaffected (regression-checked, not just assumed from the
  single-line diff).
- **Overall**: all three platform modules compile cleanly after every item
  lands; `TweaksConfigIOTest` (including AD-3's 5 new cases) passes; no
  agent-side claim of "verified in-game" is made for any of the 5 items —
  in-game verification is explicitly the user's own follow-up step this
  round, per the Risks section's remote-control constraint; a plain-text
  checklist mirroring the "Manual, in-game" summary above is handed back to
  the user at the end of implementation so they know exactly what to check
  when they're next able to launch the game themselves.

## Open Questions (carried from spec's Addendum, not resolved by this plan)

1. **AD-1's mouse-look call site — branch (a) vs. (b)** (spec Addendum "open
   items for planning" #1) — the single most consequential unresolved
   question in this revision, determines whether a new mixin is needed at
   all.
2. **AD-2's exact `inflate`/`expand`/`contains` overload names per
   platform** (spec #2) — low risk, quick `javap` spot-check.
3. **AD-4's exact bounding-box override mechanism** (spec #3) —
   `getDimensions(Pose)` override vs. manual `setBoundingBox` fallback; also
   whether 26.1/26.2 diverge here.
4. **AD-2's `TweakHooksImpl`-field vs. `FreecamTicker`-static placement
   choice** (this plan's own addition, not in the spec) — small,
   non-blocking implementation-time judgment call, see Risks.

**All four items above are now resolved — see `.claude/context/minecraft.md`
rows 122/123 and the "Existing Implementation — as of AD-1..AD-5 ship"
section below.** Kept here only for historical traceability; nothing further
to plan for AD-1..AD-5.

## Existing Implementation — as of AD-1..AD-5 ship (confirmed this pass, ground truth for Addendum 2 below)

All five items above (AD-1..AD-5) are confirmed **live, shipped code** —
directly re-read this pass, not merely cited from the spec's own
Addendum text:

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java`
  — confirmed current shape: `isFreecamActive()`, `freecamMoveSpeed()`,
  `freecamSprintMultiplier()`, `freecamNoclip()`. **No `freecamShowOwnBody()`**
  (AD-2 removal shipped). This is the exact interface Addendum 2 extends
  with three more methods (AD-7's `freecamHideHudWhileActive()`, AD-8's
  `freecamOnHurtDisablesFreecam()`/`freecamOnHurtShowsHurtIndicator()`).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:131-133`
  — `FREECAM` default configurables: `map("moveSpeed", 1.0,
  "sprintMultiplier", 2.0, "noclip", true, "moveSpeedRescaled", true)`
  (AD-3's marker present, AD-2's `showOwnBody` gone, confirmed). `ALL` list
  (line 140-143) includes `FREECAM` alongside a new `COMPASS` tweak (an
  unrelated, already-shipped feature from a different spec — irrelevant to
  this plan, noted only so its presence in `ALL`/`ConfigSchemas` isn't
  mistaken for stale/uncommitted work).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java:114-119`
  — `FREECAM` field list: `numeric("moveSpeed", 0.25, 5.0, 0.25)`,
  `numeric("sprintMultiplier", 1.0, 5.0, 0.5)`, `bool("noclip", "Noclip")`
  — exactly three rows today (AD-3's rescale + AD-2's row removal both
  shipped). **AD-7/AD-8 add a 4th (bool) and 5th (enum) row here.**
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java`
  — confirmed current shape (full file read this pass): `cameraEntity()`
  and `isCameraInsidePlayerBounds()` are both public static accessors
  (AD-1/AD-2's placement choice: `FreecamTicker`-static, not a
  `TweakHooksImpl` field — resolved per Open Question 4 above). `lazuli$tick`
  drives the existing 4-condition safety net
  (`lazuli$safetyNetTripped`, lines 106-117) — **AD-8's `DISABLE_FREECAM`
  option extends this exact method with a 5th condition.**
  `lazuli$integrate` (lines 140-160) is where `strafe`/`forward`/`vertical`
  are read from `player.input.keyPresses` each tick, and where
  `cameraInsidePlayerBounds` is recomputed each tick (lines 158-159) — **the
  established pattern AD-7/AD-8's own new cross-tick state (HUD-reveal
  timer, hurt latch) should follow**, added as new private static fields +
  public static accessors on this same class, mirroring
  `isCameraInsidePlayerBounds()`'s exact existing shape.
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java`
  — confirmed current shape (full file read this pass): backed by
  `EntityTypes.MARKER`, `getDimensions(Pose)` overridden to a fixed
  `0.45F×0.45F` box (AD-4), `refreshDimensions()` called once in the
  constructor (AD-4's confirmed-necessary second step, row 123),
  `lazuli$integrate(Vec3 desiredDelta, boolean noclip)` no longer takes
  yaw/pitch (AD-1), rotation is mutated exclusively via inherited
  `Entity.turn(double, double)`. **Not touched by AD-6/AD-7/AD-8** — none of
  Addendum 2's three items need any change to this file (confirmed by
  reading the file: AD-6 targets `LocalPlayer`/`ClientPlayerEntity`
  directly, not the camera entity; AD-7 targets the HUD render class; AD-8
  targets `LivingEntity`/`Player`).
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/` — five existing
  Freecam mixins confirmed present and read this pass:
  `MinecraftFreecamSuppressInteractionMixin`,
  `LocalPlayerFreecamKeepPositionSyncMixin` (row 120 fix — `@Redirect` on
  `LocalPlayer.sendPosition`'s `isControlledCamera()` call, `@Shadow`s the
  protected check method, single-purpose, scoped to
  `TweakEngineHandoff.require().isFreecamActive()` — **this file is the
  closest existing precedent for AD-6's own new mixin**, same target class
  family, same `@Shadow`+guard shape),
  `LevelExtractorFreecamShowBodyMixin` (AD-2's `@Redirect` on
  `LevelExtractor.extractVisibleEntities`'s ordinal-3 `Camera.entity()`
  call, reads `FreecamTicker.isCameraInsidePlayerBounds()` directly — **this
  file is the closest existing precedent for AD-7's HUD-gate mixin**, same
  "read a `FreecamTicker` static accessor from inside a `@Redirect`" shape),
  `MouseHandlerFreecamLookRedirectMixin` (AD-1, row 122 — `@Redirect` on
  `MouseHandler.turnPlayer()`'s inner `Entity.turn` call),
  `PlayerFreecamSuppressSneakMixin` (a real bug fix shipped alongside
  AD-1..AD-5: `@Redirect` on `Player.getDesiredPose()`'s
  `isShiftKeyDown()` call, `instanceof LocalPlayer` + `isFreecamActive()`
  guard — a second, simpler precedent for a small single-call-site
  `@Redirect` scoped to the local player specifically).
- `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  — `FreecamHook`'s four methods confirmed at lines 307-326 (`state(TweakId.FREECAM)`
  reads, no `freecamShowOwnBody()` present); the `headBeforeName()`
  (lines 144-146) and `modeExcludes(TweakState, String)` (lines 173-186)
  methods are the confirmed, directly-reusable precedent for AD-8's
  "resolve `onHurt`'s raw String into two booleans" requirement — same
  class, same `state(TweakId.X).configurable(key)` access pattern.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java:107-109`
  — the `id == TweakId.FREECAM` migration branch (`migrateFreecamMoveSpeed`)
  confirmed present and scoped exactly as AD-3 planned; **AD-7/AD-8 need NO
  changes here** — both new keys (`hideHudWhileActive`, `onHurt`) are purely
  additive with no old-scale value to migrate, covered for free by the
  existing default-backfill overlay loop (same reasoning already established
  for every other tweak's config, Architecture — Framework Fit).
- `.claude/context/minecraft.md` rows 112/113/115/116/119/120/122/123 —
  all read this pass; **rows 122/123 are AD-1/AD-4's own confirmed findings**
  (mouse-look hard-codes `client.player` on all three platforms, no
  camera-entity-accessor shortcut; `getDimensions` override needs an
  explicit `refreshDimensions()`/`calculateDimensions()` call to take
  effect). No existing row covers AD-6's movement-accumulator field names,
  AD-7's HUD-gate call sites, or AD-8's `animateHurt` hook — all three are
  genuinely new `javap` targets for Addendum 2's implementation, exactly as
  the spec's own Addendum 2 methodology note says.

## Addendum 2: AD-6/AD-7/AD-8 plan

**Sequencing/dependency note up front.** AD-6 is fully independent of AD-7/
AD-8 — it touches only `LocalPlayer`/`ClientPlayerEntity` (a new mixin) and
adds no new configurable/UI/`FreecamHook` surface, so it can be implemented
and verified in any order relative to the other two, including in parallel.
**AD-7 and AD-8 share real state and should be sequenced AD-7 first, then
AD-8**, per the spec's own explicit "Interaction with AD-8" note (AD-7's own
Target Behavior section) and AD-8's own "reveal window" mechanism: both need
a `FreecamTicker`-owned cross-tick boolean feeding the same HUD-gate mixin(s)
AD-7 creates (AD-7's own `hideHudWhileActive() == false` condition, OR'd with
AD-8's timer-driven reveal-window condition) — implementing AD-7's mixin(s)
first establishes the exact condition-composition shape (mirroring
`LevelExtractorFreecamShowBodyMixin`'s existing "read a `FreecamTicker`
static accessor from inside the `@Redirect`" pattern) that AD-8's own
reveal-timer condition then simply extends, rather than writing the same
HUD-gate mixin(s) twice. Each of AD-6/AD-7/AD-8 requires its own dedicated
`javap` spike against a genuinely new vanilla target (Addendum 2's own
methodology note) — none of the three should start implementation before its
own spike completes, per this repo's established convention.

### AD-6 — Real player stale-movement-input fix (javap-blocked: movement-accumulator field names)

**Mandatory first step:** `javap -p`/`javap -c -p` against `LivingEntity`/
`Player`/`LocalPlayer` (Mojmap, 26.1 and 26.2 independently) and the Yarn
equivalent (1.21.11) on each platform's resolved merged jar to confirm (a)
the exact field names (candidate Mojmap `LivingEntity.xxa`/`yya`/`zza`,
candidate Yarn `sidewaysSpeed`/`upwardSpeed`/`forwardSpeed` — spec's own
medium-confidence framing), (b) that `applyInput()`/`tickMovementInput()`
(the same method row 112 already names, declared on `LocalPlayer`/
`ClientPlayerEntity`) is genuinely where they get written each tick for the
local player, and (c) the exact declaring/overriding class per platform.

**Files to create (×3, provisional names):**

- `platform/<module>/src/main/java/de/lazuli/mixin/{LocalPlayerFreecamZeroMovementInputMixin,ClientPlayerEntityFreecamZeroMovementInputMixin}.java`
  — modeled directly on `LocalPlayerFreecamKeepPositionSyncMixin`'s existing
  shape (same target-class family, same `@Shadow`+guard pattern): `@Mixin(LocalPlayer.class)`
  (26.1/26.2) / `@Mixin(ClientPlayerEntity.class)` (1.21.11), `@Inject(method
  = "applyInput"/"tickMovementInput", at = @At("HEAD"))` (unconditional —
  fires before vanilla's own internal `isControlledCamera()`/`isCamera()`
  early-return, per the spec's own confirmed bytecode-shape reasoning),
  `@Shadow`s the three movement-accumulator fields (add `@Mutable` only if
  `javap` shows they're declared `final` — expected not, per spec, but
  confirm), and when `TweakEngineHandoff.require().isFreecamActive()` sets
  all three to `0.0F` before vanilla's own (about-to-no-op) method body runs.

**Files to modify:** `platform/<module>/src/main/resources/lazuli.mixins.json`
(×3) — append the new mixin's simple class name, once the file above exists
(same "don't reference not-yet-written classes" sequencing discipline
already established for AD-1's mixin).

**Findings to record:** once confirmed, add a new `.claude/context/minecraft.md`
row for the exact field names/declaring class per platform (Addendum 2's own
methodology note says no new row exists yet for this).

**Test strategy (AD-6-specific, manual only — no unit-testable seam, this is
real physics behavior):** walk forward (W held), activate Freecam mid-stride
without releasing W, and confirm the real player's body decelerates via
normal ground friction exactly as if W had been released at that instant
(no continued sliding/gliding — the reported bug); repeat while sprinting,
strafing, and mid-air (jump then activate Freecam before landing) to confirm
the fix holds across all three accumulator fields, not just forward; confirm
the fix holds for the tweak's entire active duration, not just the first
tick after activation (stand still with Freecam active for several seconds,
confirm no drift); deactivate Freecam and confirm normal WASD movement
resumes immediately and correctly (row 112's own gate-reopens-cleanly
finding, unaffected by this fix); regression-check movement-key routing
itself is untouched (WASD while Freecam is active still flies the camera
only, never moves the real player — row 112's core finding).

### AD-7 — HUD hidden-by-default regression fix + new `hideHudWhileActive` toggle (javap-blocked: shared HUD-visibility gate)

**Mandatory first step:** `javap -p`/`javap -c -p` per platform to (a)
locate the exact shared `getCameraPlayer()`-shaped gate/helper and every
call site among the hotbar/health/hunger/armor/air render/extract methods
(`Gui`/`InGameHud` on 1.21.11; per row 66's already-confirmed `Gui`/`Hud`
split, `Gui` on 26.1, `Hud` on 26.2 — **do not assume 26.1 and 26.2 share
this gate's exact shape without independently confirming both**, per the
spec's own caution and this table's repeated precedent for that pairing),
(b) confirm the `ContextualBar`/`ExperienceBar` family (row 121) genuinely
sits outside this gate (spec's own explanation for the XP bar surviving —
if `javap` finds otherwise, flag for a scope discussion before proceeding,
since the XP bar is explicitly out of scope per the spec's own framing), and
(c) confirm whether a single shared choke point exists (preferred, per the
spec's own T13-precedent-driven preference) or whether each element needs
its own redirect.

**Public API / Configuration / UI (no javap dependency, safe to land ahead
of or alongside the javap spike):**

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java`
  — add `boolean freecamHideHudWhileActive();`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:133`
  — `FREECAM` default configurables map gains `"hideHudWhileActive", false`
  (batch with AD-8's `"onHurt", "NOTHING"` addition to this same line into
  one edit, since both addenda touch the same map literal).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java:114-119`
  — `FREECAM` field list gains `ConfigFieldSpec.bool("hideHudWhileActive",
  "Hide HUD While Active")` as a 4th row (batch with AD-8's `enumField`
  addition as the 5th row into the same edit).
- `platform/<module>/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  (×3) — add `freecamHideHudWhileActive()` returning
  `Boolean.TRUE.equals(state(TweakId.FREECAM).configurable("hideHudWhileActive"))`,
  same trivial-read shape as `freecamNoclip()` immediately above it.
- **No `TweaksPanel.java` change** — the new bool row renders automatically
  (Architecture — Framework Fit, already re-confirmed this pass via the
  zero-`Freecam`-matches grep finding above).
- **No `TweaksConfigIO.java` change** — purely additive key, covered by the
  existing default-backfill overlay loop.

**Files to create (×3, provisional names, javap-blocked):**

- `platform/<module>/src/main/java/de/lazuli/mixin/{InGameHudFreecamHudMixin,GuiFreecamHudMixin,HudFreecamHudMixin}.java`
  (exact target class per platform per the javap spike above: `InGameHud`
  1.21.11, `Gui` 26.1, `Hud` 26.2) — modeled on
  `LevelExtractorFreecamShowBodyMixin`'s exact shape (`@Redirect` on the
  confirmed shared gate/helper's own camera-entity-vs-player check,
  substituting `client.player` for the freecam camera entity when Freecam is
  active AND `!hooks.freecamHideHudWhileActive()` — mirrors AD-2's spoof-the-
  return-value approach, "there is only ever one local player instance"
  reasoning) — **prefer this single shared-choke-point shape if `javap`
  confirms one exists** (per the mandatory-first-step guidance above); if
  `javap` instead finds N independent call sites, fall back to N separate
  `@Redirect`s (or `@ModifyExpressionValue`s) in this same file, one per
  confirmed element (hotbar/health/hunger/armor/air), each using the
  identical guard condition.
- **AD-8 interaction, land as part of this same file (do not write it
  twice — see sequencing note above):** the guard condition becomes
  `!hooks.freecamHideHudWhileActive() || FreecamTicker.isHurtRevealActive()`
  once AD-8's reveal-timer accessor exists (added to `FreecamTicker` by
  AD-8 below) — if AD-7 is implemented and shipped before AD-8, this OR
  clause is simply added in AD-8's own pass over this same file rather than
  AD-7 needing to stub it out in advance.

**Files to modify:** `platform/<module>/src/main/resources/lazuli.mixins.json`
(×3) — append the new mixin's simple class name.

**Findings to record:** a new `.claude/context/minecraft.md` row for the
confirmed gate/helper method name, its call sites, and the 26.1-vs-26.2
divergence outcome (confirmed identical or confirmed to diverge, either way
a real finding this table currently lacks).

**Test strategy (AD-7-specific, manual only):** with the new toggle at its
default (`false`), activate Freecam and confirm hotbar/health/hunger/armor/
air all render exactly as in ordinary first-person view (the regression
fix); toggle `hideHudWhileActive` to `true`, reactivate Freecam, confirm
those same elements hide (vanilla's own natural behavior, now opt-in);
confirm the crosshair and XP bar are unaffected by the toggle in either
state (out of scope per spec, should be provably untouched); confirm
deactivating Freecam always restores normal HUD rendering regardless of the
toggle's value.

### AD-8 — New "On Hurt" 3-option dropdown (javap-blocked: `animateHurt` detection hook)

**Mandatory first step:** `javap -p`/`javap -c -p` per platform to confirm
`LivingEntity.animateHurt(float yaw)`'s exact signature/visibility (or its
Yarn/Mojmap-divergent equivalent name if any) and confirm it genuinely fires
for the local player's own entity instance via the same S2C traffic the
client already processes for itself (spec's own explicit open item —
candidate general method has not been independently verified for local-
player firing on any of the three pins).

**Config schema / UI (no javap dependency, reuses the existing `ENUM` widget
kind — no new `ConfigFieldSpec.Kind`, no `TweaksPanel` code change, per the
spec's own precedent citation, `TweaksPanel.java` lines 340-345/506-512):**

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  — `FREECAM` field list gains `ConfigFieldSpec.enumField("onHurt", "On Hurt",
  List.of("DISABLE_FREECAM", "HURT_INDICATOR", "NOTHING"))` as the 5th row
  (batched with AD-7's bool row addition, see AD-7 above).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:133`
  — `FREECAM` default configurables map gains `"onHurt", "NOTHING"`
  (batched with AD-7's addition, same line).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java`
  — add two methods (not a raw String, per the interface's own established
  "resolve mode/position strings into plain booleans before crossing this
  interface" precedent, `headBeforeName()`/`modeExcludes(...)`):
  `boolean freecamOnHurtDisablesFreecam();` and `boolean
  freecamOnHurtShowsHurtIndicator();`.
- `platform/<module>/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  (×3) — implement both new methods by comparing
  `state(TweakId.FREECAM).configurable("onHurt")` against `"DISABLE_FREECAM"`/
  `"HURT_INDICATOR"` respectively, same `String.valueOf(...)`-then-`equals`
  shape `headBeforeName()` already uses (lines 144-146).

**Files to create (×3, provisional names, javap-blocked):**

- `platform/<module>/src/main/java/de/lazuli/mixin/{LivingEntityFreecamOnHurtMixin,PlayerFreecamOnHurtMixin}.java`
  (exact target class to confirm — spec's own framing expects
  `LivingEntity`) — `@Inject(method = "animateHurt", at = @At("HEAD"))`,
  scoped to `entity instanceof LocalPlayer/ClientPlayerEntity && entity ==
  client.player && TweakEngineHandoff.require().isFreecamActive()` (same
  guard shape `PlayerFreecamSuppressSneakMixin` already establishes for a
  local-player-scoped, Freecam-gated `@Inject`/`@Redirect`), non-cancelling
  (per spec: vanilla's own hurt-flash animation must fire completely
  unaffected). On firing, sets a small `FreecamTicker`-owned "hurt this
  tick"/reveal-timer latch (see below) — does not itself call
  `TweakRegistry.setEnabled`/mutate any HUD state directly, keeping the
  mixin a thin detection layer.

**Files to modify:**

- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamTicker.java`
  (×3) — add two new pieces of cross-tick state, following
  `isCameraInsidePlayerBounds()`'s exact existing precedent shape (private
  static field(s) + public static accessor(s), computed/decremented inside
  `lazuli$tick`):
  1. A `hurtRevealUntilTick`-shaped `long`/`int` field, set to `currentTick +
     60` (the spec's fixed 60-client-tick/3-second window) whenever the new
     mixin's latch fires, and a public static `isHurtRevealActive()`
     accessor comparing it against the current tick — consumed by AD-7's HUD
     mixin(s) per that item's own "OR in this second condition" note.
     Resets/extends (not stacks) on repeated hits within the window, per
     spec — a plain overwrite of `hurtRevealUntilTick` on every firing
     already gives this for free (no separate "already active" branching
     needed).
  2. `lazuli$safetyNetTripped` (existing method, lines 106-117) gains a 5th
     `||` condition: the "hurt this tick" latch is set AND
     `hooks.freecamOnHurtDisablesFreecam()` — disabling Freecam within one
     client tick of the hurt signal, per spec's `DISABLE_FREECAM` option.
     This reuses the existing safety-net mechanism/call site exactly (no new
     `registry.setEnabled` call site needed elsewhere), per the spec's own
     "recommended mechanism" note.
- `platform/<module>/src/main/resources/lazuli.mixins.json` (×3) — append
  the new mixin's simple class name.

**Findings to record:** a new `.claude/context/minecraft.md` row for
`animateHurt`'s confirmed signature/visibility per platform and confirmation
it fires for the local player's own entity instance.

**Test strategy (AD-8-specific, manual only for the in-game timing/visual
behavior; unit-testable for the config-plumbing half):**

- Unit (extend `TweaksConfigIOTest`): a `FREECAM.configurables` object with
  no `onHurt`/`hideHudWhileActive` keys at all loads with defaults
  `"NOTHING"`/`false` (default-backfill, no migration branch touched); an
  object with `onHurt: "HURT_INDICATOR"` round-trips through
  serialize/parse unchanged (plain-value passthrough, no special handling
  needed for these two purely-additive keys, confirming AD-7/AD-8 truly need
  zero `TweaksConfigIO.parse` changes as planned above).
- Manual: with `onHurt = NOTHING` (default), take damage while Freecam is
  active and confirm nothing Freecam-specific happens (vanilla hurt-flash
  fires normally, Freecam stays active, HUD state unaffected) — the
  pre-AD-8 baseline; with `onHurt = DISABLE_FREECAM`, take damage while
  Freecam is active and confirm Freecam disables within roughly one tick,
  camera returns to the player; with `onHurt = HURT_INDICATOR` and
  `hideHudWhileActive = true`, take damage while Freecam is active and
  confirm the HUD reveals for ~3 seconds then re-hides automatically if no
  further damage occurs; take repeated damage within that window (e.g.
  stand in fire) and confirm the HUD stays continuously visible (timer
  resets, not stacks); confirm `HURT_INDICATOR` with `hideHudWhileActive =
  false` is a no-op for HUD purposes (already shown) but still leaves
  Freecam active and vanilla's hurt-flash unaffected.

## Addendum 2 — Dependencies

No new external (non-Fabric) dependency for AD-6/AD-7/AD-8 — all three are
implemented entirely with vanilla Minecraft classes already on each
platform's existing compile classpath (`LivingEntity`/`Player`/`LocalPlayer`/
`ClientPlayerEntity`, whichever HUD render class AD-7's `javap` spike
confirms, `animateHurt`), the existing Sponge Mixin setup (same
`@Inject`/`@Redirect` conventions already used throughout this feature), and
plain Java/`TweakRegistry` reads for the config-plumbing halves of AD-7/AD-8.
No Maven Central verification needed — no new coordinate proposed anywhere
in this addendum.

## Addendum 2 — Risks

- **AD-6's exact field names/declaring class are only medium confidence**
  (spec's own tiering) — the mechanism shape (unconditional call, internal
  early-return, three float accumulator fields) is high confidence, grounded
  in rows 112/120's already-`javap`-confirmed sibling gates on the same
  class family, but the literal field names must still be independently
  confirmed per platform before the mixin's `@Shadow` declarations can be
  written.
- **AD-7's shared-gate assumption is the single largest unknown in this
  addendum** (spec's own framing, echoed in its "open items for planning"
  #2) — if `javap` finds N independent call sites instead of one shared
  helper, AD-7 becomes a materially bigger item (N mixins/redirects instead
  of one), and if the `ContextualBar`/`ExperienceBar` family turns out to
  share the same gate after all, the "XP bar survives" explanation needs
  revisiting and this item's scope may need to expand — budget time for
  either outcome, do not assume the single-choke-point shape before
  confirming it.
- **AD-7's 26.1-vs-26.2 divergence risk**: row 66 already established a real
  `Gui`/`Hud` split between 26.1 and 26.2 for adjacent HUD-family methods
  (crosshair, overlays) — do not assume AD-7's own gate/helper shares the
  same shape on both without independently confirming each, per this
  table's repeated precedent for exactly this pairing surprising past
  features (rows 69/104/113/116/66).
- **AD-7/AD-8 shared-state sequencing risk**: since both items' HUD-gate
  condition lives in the same mixin file(s) (AD-7 creates them, AD-8 extends
  their guard condition with an OR clause), implementing them out of the
  recommended AD-7-then-AD-8 order risks either AD-7 needing a throwaway
  stub condition or AD-8 needing to re-open and restructure AD-7's
  already-landed mixin — follow the sequencing note at the top of this
  section.
- **AD-8's `animateHurt`-fires-for-the-local-player assumption is
  unverified** (spec's own explicit open item) — if `javap`/in-game testing
  finds it does not fire for the local player's own entity instance (e.g.
  if the client suppresses redundant self-directed hurt-animation packets),
  AD-8's entire detection layer needs a different hook, which would be a
  materially different (and currently unscoped) implementation — flag this
  as the first thing to confirm once the mixin is written, before building
  out the rest of AD-8's plumbing around it.
- **AD-8's 60-tick reveal-timer state must reset/extend, never stack** — per
  spec's own explicit rejection of both named alternatives (re-hide when
  vanilla's animation ends; stay shown until Freecam deactivates) — the
  plain "overwrite `hurtRevealUntilTick` on every firing" implementation
  above is intentionally the simplest correct approach; a more complex
  stacking/accumulating implementation would be a regression against the
  spec's own explicit design decision, not an improvement.
- **No live-launch verification by the implementing/verifying agent this
  round**, same standing constraint as the AD-1..AD-5 round (remote-control,
  `feedback_no_launch_minecraft_remote.md`) — every manual test listed above
  must be executed by the **user**, later. This round's agent-side
  verification is strictly limited to: compiling all three platform modules,
  running the extended `TweaksConfigIOTest` suite, and a careful re-read of
  the final diffs against each item's target behavior — not an in-game pass.

## Addendum 2 — Test Strategy (summary — see each AD-N subsection above for the detailed version)

- **Automated/unit**: `TweaksConfigIOTest` extended per AD-8's two new
  default-backfill/passthrough cases above; existing suite (including
  AD-3's 5 cases) continues passing unmodified.
- **Compile-only, per platform**: a full Gradle compile/check of all three
  platform modules after each item's edits land, confirming the `FreecamHook`
  interface's three new methods (AD-7 one, AD-8 two) are implemented
  everywhere they must be (`TweakHooksImpl` ×3), no mixin-registration
  errors for the new `lazuli.mixins.json` entries (AD-6/AD-7/AD-8, up to 3
  new mixin classes ×3 platforms = up to 9 new mixin files total, exact
  count depends on whether AD-7 lands as one shared-choke-point mixin or N
  per-element mixins per the javap spike outcome).
- **Manual, in-game, per platform (×3) — deferred to the user.** A
  consolidated checklist (superset of each AD-N subsection's own "Test
  strategy" above):
  1. AD-6: walk forward, activate Freecam mid-stride, confirm the real
     player decelerates via normal friction instead of continuing to
     slide/glide; repeat for strafe and mid-air; confirm no drift for the
     tweak's entire active duration, not just the first tick.
  2. AD-7 (toggle default, `false`): activate Freecam, confirm hotbar/
     health/hunger/armor/air all render normally (the regression fix);
     toggle to `true`, confirm they hide (opt-in vanilla behavior); confirm
     crosshair/XP bar unaffected either way.
  3. AD-8: with `onHurt = NOTHING` (default), confirm damage has no
     Freecam-specific effect; with `DISABLE_FREECAM`, confirm Freecam
     disables within about one tick of taking damage; with
     `HURT_INDICATOR` + `hideHudWhileActive = true`, confirm the HUD
     reveals for ~3 seconds on hit and re-hides automatically, and that
     repeated hits extend rather than stack the window.
  4. Regression pass on AD-1..AD-5 (this addendum touches movement/HUD/
     damage code adjacent to several of those): A/D still strafe correctly
     (AD-5); Move Speed still steps 0.25-5.0 with unchanged felt speed for
     a previously-migrated user (AD-3); Show Own Body auto-hide/show still
     tracks the player's live pose (AD-2); `noclip = false` still stops the
     camera at block boundaries with entity-passthrough unaffected (AD-4);
     the player's body/head still do not turn to follow the freecam view,
     and mouse-look still resumes cleanly on deactivation (AD-1); the 4
     original safety-net triggers (disconnect/respawn/dimension-change/
     death) still restore the camera; interaction is still suppressed while
     active; the real player's own simulation (fall damage, drowning, AI
     targeting, hunger) is still fully unaffected by Freecam being active.

## Addendum 2 — Acceptance Criteria

- **AD-6**: once Freecam activates, the real player's existing momentum
  decays via exactly vanilla's own normal friction/deceleration curve (as if
  every movement key had been released at that instant), for the tweak's
  entire active duration, on all three platforms; movement-key routing
  itself (row 112) is unaffected — WASD still reaches only the freecam
  camera while active, never the real player.
- **AD-7**: `hideHudWhileActive` defaults to `false`; at the default,
  hotbar/health/hunger/armor/air render normally while Freecam is active on
  all three platforms (the regression fixed); at `true`, those same elements
  hide (vanilla's own natural behavior, now opt-in only); the crosshair and
  XP bar are unaffected by the toggle in either state; the new checkbox row
  renders automatically in the Tweaks tab with no `TweaksPanel` code change.
- **AD-8**: `onHurt` defaults to `NOTHING` (zero behavior change for
  existing users until explicitly opted in); `DISABLE_FREECAM` disables
  Freecam within about one client tick of the local player's own hurt
  signal; `HURT_INDICATOR` leaves vanilla's own hurt-flash/knockback-tilt
  animation completely untouched and, when combined with
  `hideHudWhileActive = true`, reveals the HUD for a 3-second window per hit
  that resets (not stacks) on repeated hits within the window; `NOTHING`
  leaves Freecam/HUD state completely unaffected by damage; the new `ENUM`
  pill row renders automatically with no `TweaksPanel` code change.
- **Overall**: all three platform modules compile cleanly after every item
  lands; `TweaksConfigIOTest` (including AD-8's 2 new cases, on top of
  AD-3's existing 5) passes; no agent-side claim of "verified in-game" is
  made for AD-6/AD-7/AD-8 — in-game verification is explicitly the user's
  own follow-up step, per the Risks section's remote-control constraint; a
  plain-text checklist mirroring the "Manual, in-game" summary above is
  handed back to the user at the end of implementation; the AD-1..AD-5
  regression pass (item 4 in the manual checklist above) is treated as a
  first-class part of this round's acceptance, not an afterthought, since
  AD-6 in particular touches movement code directly adjacent to AD-1/AD-5.

## Addendum 2 — Open Questions (carried from spec's Addendum 2, not resolved by this plan)

1. **AD-6's exact movement-accumulator field names/declaring class** (spec
   Addendum 2 "open items for planning" #1) — mechanism shape is high
   confidence, only the literal names need `javap` confirmation.
2. **AD-7's exact shared HUD-visibility gate** (spec #2) — the single most
   consequential unknown in this addendum; determines whether AD-7 is a
   one-mixin or N-mixin fix, and whether the XP-bar-survives explanation
   needs revisiting.
3. **AD-8's exact `animateHurt`-shaped detection hook** (spec #3) —
   signature/visibility per platform, and whether it genuinely fires for the
   local player's own entity instance (this addendum's single riskiest
   unverified assumption).
4. **AD-8's `TweakHooksImpl`/`TweaksConfigIO` plumbing** (spec #4) — not
   itself `javap`-blocked, plain Java/`TweakRegistry` logic, implementable
   and unit-testable independent of items 1-3 above once the raw damage
   signal is wired up (already reflected in this plan's own sequencing
   above: the config/UI/hook-interface halves of AD-7/AD-8 can land ahead of
   or alongside their respective `javap` spikes).
