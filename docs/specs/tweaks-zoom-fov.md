# Spec: Wire Up T11 Zoom's Real FOV Effect

## Overview

The Tweaks framework (registry, config persistence, main-menu Tweaks tab,
vanilla key binding wiring — see `docs/specs/tweaks.md` /
`docs/specs/tweaks-plan.md`) is fully built and verified. None of its 12
tweaks currently have a real gameplay effect. This is a narrow follow-up
covering **only T11 Zoom**: making the client's actual field of view (FOV)
change when Zoom is active, on all three platforms
(`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`).

Everything upstream of "apply this FOV value" is already correct and
untouched by this spec:

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ZoomHook.java`
  — the Minecraft-agnostic interface: `isZoomActive()` and
  `applyFov(float baseFov)`.
- `platform/*/src/main/java/de/lazuli/tweaks/ZoomTicker.java` — per-tick
  hold/toggle key-state machine (per-platform key-API differences already
  handled; see `.claude/context/minecraft.md`'s existing
  `wasPressed()`/`isDown()`+`consumeClick()` row).
- `platform/*/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java` —
  `applyFov(float baseFov)` is already implemented correctly (lines 219-227
  of the 1.21.11 copy; identical on 26.1/26.2): returns `baseFov` unchanged
  when inactive, otherwise `baseFov / max(1, magnification)` where
  `magnification` comes from the `ZOOM` tweak's `magnification`
  configurable (default `4.0f`).
- `platform/*/src/main/java/de/lazuli/tweaks/TweakEngineHandoff.java` —
  publishes the single live `TweakHooksImpl` instance
  (`TweakEngineHandoff.require()`), constructed and published in each
  platform's `TweaksClientInitializer.onInitializeClient()`.

This spec covers **only** the missing last mile: where to call
`TweakEngineHandoff.require().applyFov(baseFov)` so the value actually
reaches vanilla's rendered FOV, per platform.

## Goals

- On all three platforms, when `TweakEngineHandoff.require().isZoomActive()`
  is true, the game's rendered/projected FOV for the current frame is
  `applyFov(vanillaBaseFov)` instead of the unmodified vanilla value.
- The hook fires every frame Zoom could plausibly affect the projection
  (main game-world rendering at minimum); it must not require a restart or
  screen re-open to take effect, since `ZoomTicker` already updates
  `zoomActive` every client tick.
- No change to vanilla's FOV in any other case (Zoom disabled, or the
  `ZOOM` tweak itself disabled in config) — `applyFov` already guarantees
  this by returning `baseFov` unchanged, so the mixin must not add any
  additional conditional logic of its own.

## Non-goals

- No changes to `ZoomHook`, `ZoomTicker`, `TweakHooksImpl.applyFov()`, or
  `TweakEngineHandoff` — their existing behavior is treated as correct and
  given.
- No smooth zoom-in/zoom-out transition, no "scroll to change magnification"
  (both mentioned only as ideas in `docs/idea-collection/tweaks/zoom.md`,
  not in scope here — `applyFov` today is an instant magnification swap).
- No work on any of the other 11 tweaks' hook call sites.
- No F.O.V.-effect-scale ("Options > Video > FOV Effects") interaction
  changes — vanilla's own effect-scale/`fovMultiplier` behavior (sprint
  FOV kick, elytra, nausea, etc.) is left completely alone; Zoom's
  magnification is layered on top of whatever vanilla FOV value already
  incorporates those effects, matching how `applyFov`'s signature
  (`float baseFov -> float`) is written today.

## Requirements

- **R1**: On 1.21.11, hook into the FOV computation such that every value
  vanilla would use as the actual rendered/projected FOV is passed through
  `applyFov()` first.
- **R2**: On 26.1 and 26.2, do the equivalent — despite FOV computation
  living in a structurally different place on these versions (see
  Architecture below).
- **R3**: The three platform implementations must be independent files
  under each platform's own `de.lazuli.mixin` package (matching this
  repo's existing per-platform mixin convention — see e.g.
  `ClientTitleScreenRedirectMixin`/`GuiTitleScreenRedirectMixin`, which are
  separate per-platform files for the same conceptual redirect), registered
  in that platform's own `lazuli.mixins.json`.
- **R4**: No mixinextras dependency is introduced — this repo's mixin usage
  today (confirmed by grep across all `platform/*/build.gradle` and
  `features/*/src/main/java/**/mixins/`) is plain Sponge Mixin only
  (`@Inject`, `@ModifyVariable`, `@Redirect`, invoker mixins), no
  `@ModifyReturnValue`/`@ModifyExpressionValue`. This feature must stay
  consistent with that and use a plain `@Inject(..., cancellable = true)`
  with `CallbackInfoReturnable<Float>`.

## Public API

No new public API surface. This batch only adds three new (platform-private)
mixin classes; nothing outside `de.lazuli.mixin` in each platform module
references them.

## Architecture — javap findings

All three merged, fully-mapped client jars used for this Minecraft version's
compile classpath were already present on disk in this repo's own
`.gradle/loom-cache` (no extra download/build needed):

- 1.21.11 (Yarn): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/...jar`
- 26.1 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/...jar`
- 26.2 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/...jar`

`javap -p` / `javap -c -p` / `javap -v -p` were run against the relevant
class file extracted from each jar (`unzip <jar> <path> -d ...` then
`javap` on the extracted `.class`).

### 1.21.11 (Yarn)

`net.minecraft.client.render.GameRenderer`:

```
private float getFov(net.minecraft.client.render.Camera, float, boolean);
```

Confirmed private (not accessible outside the class without a mixin — fine,
mixins can target private methods directly). `javap -v` on `GameRenderer`
shows this exact method (constant pool entry `getFov:(Lnet/minecraft/client/render/Camera;FZ)F`)
is the sole call site vanilla itself uses to obtain the frame's FOV — it is
invoked from four separate call sites inside `GameRenderer` itself (bytecode
offsets confirmed via `javap -c`, corresponding to
`getBasicProjectionMatrix`, `getProjectionMatrix`, `updateCameraState`, and
one more internal caller). `net.minecraft.client.render.Camera` on this
version has **no** FOV-related methods or fields at all — FOV computation
lives entirely inside `GameRenderer`, not `Camera`.

Because `getFov` is the single choke point all four vanilla call sites
already funnel through, the mixin only needs to touch this one method
regardless of which call site the user's exact vantage (first-person
world render vs. some other UI-facing projection) goes through.

### 26.1 / 26.2 (Mojmap)

`net.minecraft.client.renderer.GameRenderer` has **no `getFov`-shaped method
at all** — `javap -p` on this class in both jars shows the entire FOV
computation was moved to `net.minecraft.client.Camera`, which the class no
longer references for FOV in the way 1.21.11's `GameRenderer` does its own
field-level fov math. Confirmed via `javap -v -p` on `GameRenderer`, which
still shows exactly one `Camera.getFov()` call site
(`Methodref net/minecraft/client/Camera.getFov:()F`), reached from
`projectHorizonToScreen()` — a screen-space horizon helper, not the render
FOV assignment itself; the CameraRenderState's `hudFov` field is populated
via `Camera.extractRenderState(CameraRenderState, float)`, which is where
`Camera`'s own private FOV pipeline (`tickFov()` -> `calculateFov(float)` /
`calculateHudFov(float)` -> `getMaxZoom(float)` /
`modifyFovBasedOnDeathOrFluid(float, float)`) ultimately writes both the
private `fov` and `hudFov` fields.

`net.minecraft.client.Camera` (both 26.1 and 26.2 — identical signature set
in both jars):

```
private void tickFov();
private float calculateFov(float);
private float calculateHudFov(float);
private float modifyFovBasedOnDeathOrFluid(float, float);
private float getMaxZoom(float);
public  float getFov();
```

`getFov()` is public, no-arg, and is the single externally-visible "what FOV
is this camera using right now" accessor — matching `applyFov(float
baseFov)`'s intended per-frame call shape (feed it the vanilla value,
substitute the returned value). All of the private
`tickFov`/`calculate*`/`getMaxZoom`/`modifyFovBasedOnDeathOrFluid` methods
that feed into `getFov()`'s backing `fov` field are internal to `Camera`
and not suitable individual mixin targets for this feature (they compute
components — death shake, fluid distortion, sprint transition — not the
final usable value); `getFov()` itself is the correct, minimal, stable
choke point.

**Cross-version divergence found (new — see below for the
`.claude/context/minecraft.md` entry added for this):** on 1.21.11, FOV
computation is a private method of `GameRenderer` taking `Camera` as a
parameter; on 26.1/26.2, it moved entirely into `Camera` itself as a public
no-arg method. This is a real architectural relocation, not just a rename —
the mixin target class differs (`GameRenderer` vs `Camera`), not just the
method name.

### Mixin design (both families)

**1.21.11** — `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/GameRendererZoomFovMixin.java`:

```java
@Mixin(GameRenderer.class)
abstract class GameRendererZoomFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(Camera camera, float tickDelta, boolean changingFov,
            CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
```

Registered in `platform/fabric-1.21.11/src/main/resources/lazuli.mixins.json`'s
`mixins` array (alongside the existing `ClientTitleScreenRedirectMixin` etc.
entries).

**26.1 and 26.2** (identical file content, separate files per R3) —
`platform/fabric-26.{1,2}/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`:

```java
@Mixin(Camera.class)
abstract class CameraZoomFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
```

Registered in each platform's own `lazuli.mixins.json`. Both use plain
`@Inject(at = @At("RETURN"), cancellable = true)` +
`CallbackInfoReturnable<Float>` (no mixinextras), consistent with every
existing mixin in this repo.

`TweakEngineHandoff.require()` is guaranteed non-null by the time either
mixin fires: `TweaksClientInitializer.onInitializeClient()` publishes the
`TweakHooksImpl` during `ClientModInitializer` init, which Fabric Loader
runs before any world/render code (and by extension before `GameRenderer`/
`Camera` ever compute a real frame's FOV) can execute.

## UI

None — Zoom's tab UI, key binding, and configurables (`holdToZoom`,
`magnification`) already exist and are unaffected.

## Configuration

No new configurables. Existing `ZOOM` tweak's `magnification` configurable
(already read by `TweakHooksImpl.applyFov`) is the only tunable involved.

## Events

None — no new event bus / lifecycle hooks; the mixin's `@Inject` is the only
new "event" and it is purely a Mixin injection point, not a Fabric API
event.

## Networking

None.

## Persistence

None — no change to `tweaks.json` schema or `TweaksConfigIO`.

## Compatibility

- Each platform's mixin targets a different vanilla class (`GameRenderer` on
  1.21.11, `Camera` on 26.1/26.2) per the javap findings above — this is
  intentional and matches how the two mapping families structurally diverge
  for this exact computation, not an inconsistency to reconcile.
  `applyFov`'s call shape (`float in -> float out`) is identical either way,
  so `TweakHooksImpl`/`ZoomHook` need no per-platform variation.
- 26.1 vs 26.2: identical `Camera` method set was confirmed by `javap -p` on
  both jars (byte-for-byte same signature list reproduced above), so the
  same mixin file content applies to both without any version-specific
  branching — only the file's physical location differs (one copy per
  platform module, per this repo's per-module mixin source-set convention;
  there is no shared mixin source set between platform modules today).
- No interaction with vanilla's own FOV Options slider or "FOV Effects"
  scale slider: `applyFov` is applied to the already-fully-computed vanilla
  value (i.e. after vanilla's own multiplier chain from
  `GameOptions.getFov()`/`Options.fov()`, `getFovMultiplier`, nausea, sprint,
  etc.), so Zoom composes correctly on top of whatever the player already
  has configured, without inspecting or duplicating vanilla's own FOV math.

## Performance

Negligible: one extra virtual-dispatch-free `@Inject` firing once per FOV
computation per frame (at most a few times per frame given the 1.21.11 call
site count), delegating to `TweakHooksImpl.applyFov`, which does a single
`TweakRegistry` state lookup plus one branch and one division. No allocation
introduced.

## Future Extensions

- The smooth zoom transition and "scroll to change magnification"
  configurables mentioned in `docs/idea-collection/tweaks/zoom.md` remain
  future work; when built, they'd live entirely inside
  `TweakHooksImpl.applyFov()`/`ZoomHook`, requiring no change to the mixins
  specified here (the mixin only ever forwards whatever `applyFov` returns).
- The same `javap`-first, per-platform-mixin-file pattern established here
  should be repeated for the other 11 tweaks' still-unwired call sites
  (`TweakHooksImpl`'s own Javadoc already flags this as deferred Risk #1
  work).
