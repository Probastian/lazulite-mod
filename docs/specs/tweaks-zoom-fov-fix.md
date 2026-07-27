# Spec: Fix T11 Zoom's FOV Mixin (26.1/26.2 Dead Choke Point)

## Overview

`docs/specs/tweaks-zoom-fov.md` wired `TweakEngineHandoff.require().applyFov(...)`
into a mixin per platform for T11 Zoom. Re-verification this session (fresh
`javap -c -p` bytecode reads against this repo's own resolved merged client
jars, this repo's established verification convention) found that the
**26.1 and 26.2 mixins fire but have zero effect on actual rendering**: they
target `Camera.getFov()`, which is not on the render path. This spec is a
narrow correction of those two mixins only.

- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/GameRendererZoomFovMixin.java`
  — **confirmed correct, re-verified this session, no change.**
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`
  and the fabric-26.2 file of the same name — **confirmed broken, fix
  specified below.**

## Root cause (26.1/26.2)

`Camera.getFov()` (`public float getFov() { return this.fov; }`) is real and
returns the real `fov` field, but **nothing on the render path calls it**.
`javap -c -p` on `GameRenderer` (26.2; 26.1 is byte-for-byte equivalent modulo
constant-pool indices, diffed and confirmed identical logic) shows exactly one
`Camera.getFov()` call site, inside `Camera.projectHorizonToScreen()` itself
(a screen-space horizon utility, not part of the per-frame render setup) —
`GameRenderer` never calls `Camera.getFov()` at all.

The value that actually reaches the screen is produced by
`Camera.update(DeltaTracker)`:

```
public void update(DeltaTracker):
    ...
    this.fov    = this.calculateFov(partialTick);   // PUTFIELD fov
    this.hudFov = this.calculateHudFov(partialTick); // PUTFIELD hudFov
    ...
    this.setupPerspective(0.05f, this.depthFar, this.fov, width, height);  // consumes this.fov immediately
```

`Camera.update(DeltaTracker)` is itself called from
`GameRenderer.update(DeltaTracker)` (confirmed via `javap -c -p` on
`GameRenderer`: `invokevirtual Camera.update:(Lnet/minecraft/client/DeltaTracker;)V`
inside `GameRenderer.update`) — a genuine per-frame render-setup method, not a
tick method. So `this.fov` (the value `getFov()` merely *exposes* but nothing
reads through) is what `setupPerspective` uses to build the real world
projection matrix every frame — and it is finalized and consumed *before*
`getFov()` would ever be called, and `getFov()` is in fact never called on
this path at all.

Separately, `tickFov()` (called once per **tick**, from `Camera.tick()`, not
per frame) does **not** assign `this.fov`/`this.hudFov` — it only smooths a
`fovModifier` field (`this.fovModifier = ...; this.oldFovModifier = ...`)
that `calculateFov(float)` later reads via `Mth.lerp(partialTick,
oldFovModifier, fovModifier)`. This contradicts the assumption in this
spec's originating task brief that `tickFov()` assigns the fields directly —
confirmed false by bytecode; `tickFov()` has no `PUTFIELD fov`/`PUTFIELD
hudFov` at all. Attempting to `@Shadow` and overwrite `fov`/`hudFov` inside a
`tickFov()` `RETURN` injection would run once per tick and then be
immediately overwritten again every frame by `update()`'s own
`calculateFov`/`calculateHudFov` calls — it would not work.

`calculateFov(float partialTick)` is the correct choke point instead: it is
a private method, called exactly once per frame (from `update()`), whose
return value is assigned straight into `this.fov` and consumed one statement
later by `setupPerspective`. Injecting at its `RETURN` and substituting
`applyFov(...)` of the vanilla return value is architecturally identical to
the existing (working) 1.21.11 mixin pattern — same `@Inject(..., at =
@At("RETURN"), cancellable = true)` + `CallbackInfoReturnable<Float>` shape,
just retargeted to the correct method. No `@Shadow` fields needed.

### `calculateHudFov` / `hudFov` — out of scope, matching original intent

`calculateHudFov(float partialTick)` computes a separate value
(`modifyFovBasedOnDeathOrFluid(partialTick, BASE_HUD_FOV /* 70.0f */)`,
independent of the player's configured FOV option and of `fovModifier`) that
feeds `hudFov` → `CameraRenderState.hudFov` → the first-person held-item
projection (`GameRenderer`'s `hudProjection.setupPerspective(...,
hudFov, ...)`, consumed by `renderItemInHand`). The original spec's design
(routing only `Camera.getFov()`, which returns `this.fov`, not `this.hudFov`)
never intended to touch the held-item projection either — `getFov()`'s
implementation only ever returned `this.fov`. This fix preserves that same
scope: only the world-render FOV (`this.fov`, sourced now from
`calculateFov`'s return value) is corrected; the held-item HUD FOV is left
alone, unchanged from today's (already-shipped, no complaint on this point)
behavior. Widening zoom to also affect the held-item FOV is a product
decision outside this bug-fix spec's scope (see Future Extensions).

## Verification method

Same convention as the original spec: `javap` against this repo's own
resolved merged client jars already present in `.gradle/loom-cache`, no
extra download:

- 26.1: `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/...jar`
- 26.2: `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/...jar`
- 1.21.11 (re-checked, unchanged conclusion): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/...jar`

`net/minecraft/client/Camera.class` was extracted from both 26.1 and 26.2
jars and run through `javap -p` (method/field listing — confirmed
byte-for-byte identical method and field set between the two versions) and
`javap -c -p` (bytecode). `net/minecraft/client/renderer/GameRenderer.class`
was extracted from the 26.2 jar and run through `javap -c -p` to confirm
`Camera.update`'s and `Camera.getFov`'s respective call sites; the equivalent
26.1 `Camera` bytecode was diffed against 26.2's and found identical modulo
constant-pool index renumbering (same instruction sequence, same field/method
references by name).

For 1.21.11, `net/minecraft/client/render/GameRenderer.class` was extracted
and run through `javap -c -p`; all `getFov(Camera, float, boolean)` call
sites were enumerated and their containing methods identified. Two sites
fall inside `renderWorld(RenderTickCounter)` (the real per-frame world-render
method) with a return value flowing into `getBasicProjectionMatrix`/the
projection setup; two more fall inside `getProjectionMatrix(float)` and
`project(Vec3d)`/`getPitch()` (a coordinate-projection utility, analogous to
26.x's `projectHorizonToScreen`, not itself the primary render call, but not
contradicting the primary-render finding either). This reconfirms the
original spec's conclusion for 1.21.11 unchanged: `getFov(Camera, float,
boolean)` is genuinely on the render path there, so **no change is needed
on fabric-1.21.11**.

## Goals

- On 26.1 and 26.2, `TweakEngineHandoff.require().applyFov(baseFov)` is
  applied to the actual value that ends up in `Camera`'s `fov` field and is
  consumed by `setupPerspective` for the world render, every frame, with no
  restart/UI-reopen required (unchanged requirement from the original spec).
- 1.21.11 is unaffected — already correct, verified again, no file touched.

## Non-goals

- No change to `hudFov`/`calculateHudFov` (held-item FOV) — see "out of
  scope" note above; matches original spec's scope exactly.
- No change to `ZoomHook`, `ZoomTicker`, `TweakHooksImpl.applyFov()`, or
  `TweakEngineHandoff` (same non-goal as the original spec — still correct
  and given).
- No change to the 1.21.11 mixin file.
- No change to `docs/specs/tweaks-zoom-fov.md` itself (left as historical
  record; this file documents the correction).

## Requirements

- **R1 (replaces original R2 for 26.1/26.2 only)**: On 26.1 and 26.2, hook
  into `Camera.calculateFov(float)` (not `Camera.getFov()`) such that its
  return value — the value actually assigned to `this.fov` and consumed by
  `setupPerspective` for the frame's world-render projection — is passed
  through `applyFov()` first.
- **R2 (unchanged)**: 1.21.11's existing `GameRendererZoomFovMixin` needs no
  change; re-verified this session as the correct, sole choke point.
- **R3 (unchanged)**: Independent per-platform files under
  `de.lazuli.mixin`, registered in that platform's own `lazuli.mixins.json`
  — the fix is a same-file edit to the two existing
  `CameraZoomFovMixin.java` files (26.1, 26.2), no new files, no
  registration change (already registered).
- **R4 (unchanged)**: No mixinextras — plain `@Inject(at = @At("RETURN"),
  cancellable = true)` + `CallbackInfoReturnable<Float>`, same shape as
  today's (broken) mixin and as the working 1.21.11 mixin.

## Public API

None — same two existing (platform-private) mixin classes are edited in
place; nothing outside `de.lazuli.mixin` in each platform module references
them.

## Corrected mixin design (26.1 and 26.2 — identical file content, one edit each)

Replace the `@Inject` target and method body in both
`platform/fabric-26.1/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`
and `platform/fabric-26.2/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`:

```java
@Mixin(Camera.class)
abstract class CameraZoomFovMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(float tickDelta, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
```

Differences from today's file: `method = "getFov"` → `method =
"calculateFov"`; the injected handler gains the target method's own
parameter (`float tickDelta`, matching `calculateFov(float)`'s single
parameter — Sponge Mixin `@Inject` handlers must mirror the target method's
parameters before the trailing `CallbackInfoReturnable`). No `@Shadow`
fields are introduced — `calculateFov` is invoked as a normal (private,
mixin-visible) instance method target, exactly like `getFov` was.

No change to the class-level Javadoc's overall shape is required beyond
correcting the "single choke point" claim to describe `calculateFov`
instead of `getFov` (see file's existing Javadoc referencing
`docs/specs/tweaks-zoom-fov.md`'s Architecture section — should now also
reference this file).

Registration: unchanged — both files are already listed in their
respective `lazuli.mixins.json` `mixins` arrays as `"CameraZoomFovMixin"`.

## UI

None — unchanged from original spec.

## Configuration

None — unchanged from original spec.

## Events

None — unchanged from original spec.

## Networking

None.

## Persistence

None.

## Compatibility

- 1.21.11 is untouched by this fix.
- 26.1 vs 26.2: identical `Camera` bytecode shape confirmed for
  `calculateFov(float)` via `javap -c -p` diff (same instruction sequence,
  only constant-pool index renumbering differs) — same mixin file content
  applies to both, matching the original spec's established pattern of one
  physical copy per platform module.
- This fix does not change `applyFov`'s call shape (`float baseFov -> float`
  in, out) or any upstream Tweaks-framework code, so no interaction with
  vanilla's FOV Options slider or FOV Effects scale slider changes from what
  the original spec already established: `calculateFov`'s return value
  already incorporates `GameOptions.fov()`/`Options.fov()`, the
  sprint/fluid/death `fovModifier`/`modifyFovBasedOnDeathOrFluid` chain, etc,
  before `applyFov` sees it — Zoom's magnification still composes on top of
  the fully vanilla-computed value, unchanged in principle from the original
  (broken) design's intent.

## Performance

Same negligible cost as originally specced — one extra `@Inject` firing once
per frame (previously it fired but was silently discarded on 26.1/26.2 since
nothing consumed `getFov()`'s return; now it fires exactly once, from the
one real call site, per frame).

## Future Extensions

- Whether Zoom's magnification should also affect the first-person held-item
  FOV (`hudFov`/`calculateHudFov`) is an open product question, deliberately
  left out of this bug-fix's scope (see "out of scope" note above) — if
  wanted later, it is a second, independent `@Inject(method =
  "calculateHudFov", at = @At("RETURN"), cancellable = true)` in the same
  mixin class, same `applyFov` call.
- The other 11 tweaks' still-unwired call sites remain deferred, per the
  original spec's Future Extensions note — this fix does not change that
  backlog, only corrects T11's own wiring.
