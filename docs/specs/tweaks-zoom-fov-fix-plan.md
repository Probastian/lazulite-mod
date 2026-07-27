# Implementation Plan: Fix T11 Zoom's FOV Mixin (26.1/26.2 Retarget)

Source spec: `docs/specs/tweaks-zoom-fov-fix.md` (approved, frozen — this plan
does not alter it). This is a narrow correction of the original
`docs/specs/tweaks-zoom-fov.md` implementation (see
`docs/specs/tweaks-zoom-fov-plan.md` for that original plan's style/format,
followed here).

## Existing Implementation (unchanged by this fix)

- `features/tweaks/**` — `ZoomHook`, `ZoomTicker` — untouched (spec Non-goals).
- `platform/*/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java#applyFov(float baseFov)`
  — untouched. Confirmed signature (read directly, 26.1 copy, lines 220-227):
  ```java
  public float applyFov(float baseFov) {
      if (!isZoomActive()) {
          return baseFov;
      }
      Object raw = state(TweakId.ZOOM).configurable("magnification");
      float magnification = raw instanceof Number n ? n.floatValue() : 4.0f;
      return baseFov / Math.max(1.0f, magnification);
  }
  ```
  Confirms the pass-through-when-disabled semantics the fix relies on: when
  Zoom is inactive, `applyFov` returns `baseFov` unchanged, so cancelling the
  injection and substituting `applyFov(cir.getReturnValue())` is a no-op on
  the rendered value whenever the tweak is off.
- `platform/*/src/main/java/de/lazuli/tweaks/TweakEngineHandoff.java` —
  untouched. `require()` returns the published `TweakHooksImpl` singleton or
  throws `IllegalStateException` if called before
  `TweaksClientInitializer.onInitializeClient()` publishes it; no change to
  this contract.
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/GameRendererZoomFovMixin.java`
  — **confirmed correct by the spec, no change.** The spec's Root Cause
  section re-verified (fresh `javap -c -p`) that `GameRenderer#getFov(Camera,
  float, boolean)` is genuinely on 1.21.11's per-frame render path (two call
  sites inside `renderWorld(RenderTickCounter)` feeding the projection
  matrix). No discrepancy found versus the original spec's claim for this
  platform — this plan does not touch this file.

## Files to Modify

1. `platform/fabric-26.1/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`
2. `platform/fabric-26.2/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`

Both files are byte-for-byte identical today (confirmed by reading both) and
receive the identical edit. Current content (both files):

```java
package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ... (Javadoc references getFov() as the single choke point) ...
 */
@Mixin(Camera.class)
abstract class CameraZoomFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
```

Target content (both files), per the spec's "Corrected mixin design" section:

```java
package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ... (Javadoc updated to describe calculateFov as the choke point, and to
 * reference docs/specs/tweaks-zoom-fov-fix.md alongside the original spec) ...
 */
@Mixin(Camera.class)
abstract class CameraZoomFovMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(float tickDelta, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
```

Exact diff intent:
- **Imports**: no change — `Camera`, `TweakEngineHandoff`, and the three
  Sponge Mixin injection imports are already present and still needed. No new
  import required (`calculateFov` is invoked as a plain instance method
  target, same as `getFov` was — no `@Shadow` needed per the spec).
- **`@Shadow`**: none introduced, none removed. The spec explicitly states
  "No `@Shadow` fields needed" — `calculateFov` is targeted directly as an
  `@Inject` method selector string, not accessed as a field/invoker.
- **`@Inject` annotation**: `method = "getFov"` → `method = "calculateFov"`.
  `at = @At("RETURN")` and `cancellable = true` unchanged.
- **Handler method signature**: gains the target method's own parameter,
  `float tickDelta` (matching `calculateFov(float)`'s single parameter),
  inserted before the trailing `CallbackInfoReturnable<Float> cir` — Sponge
  Mixin requires `@Inject` handlers to mirror the target method's parameter
  list ahead of the callback-info parameter. `tickDelta` is otherwise unused
  in the body (spec's design does not reference it).
- **Body**: unchanged — still
  `cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));`.
- **Class Javadoc**: update prose that currently describes `getFov()` as "the
  single externally-visible accessor" choke point to instead describe
  `calculateFov(float)` as the per-frame choke point whose return value feeds
  `this.fov` and `setupPerspective`, and add a reference to
  `docs/specs/tweaks-zoom-fov-fix.md` alongside the existing reference to
  `docs/specs/tweaks-zoom-fov.md`. This is documentation only, no behavior
  change; implementation should keep it accurate but should not treat exact
  wording as a hard requirement.
- **Registration**: no change — both `lazuli.mixins.json` files already list
  `"CameraZoomFovMixin"`; the method-selector change inside an already
  -registered mixin class needs no registration edit.

## Signature/Cancellability Check (spec cross-reference)

The spec's Root Cause section states `calculateFov(float partialTick)` is:
- a **private** method,
- called **exactly once per frame** (from `Camera.update(DeltaTracker)`),
- whose **return value is assigned directly into `this.fov`**, consumed one
  statement later by `setupPerspective`,
- architecturally identical in shape to the existing working 1.21.11 pattern:
  "same `@Inject(..., at = @At("RETURN"), cancellable = true)` +
  `CallbackInfoReturnable<Float>` shape, just retargeted to the correct
  method."

This directly supports `cancellable = true` + `CallbackInfoReturnable<Float>`
at `@At("RETURN")`: Sponge Mixin can inject into and cancel private
instance methods with a `float` return type via this exact pattern (it is the
same annotation shape already compiling and working today for both the
current, wrong-target `getFov()` mixin and the confirmed-correct 1.21.11
`getFov(Camera, float, boolean)` mixin) — no visibility or cancellability
obstacle is indicated anywhere in the spec. The spec also confirms 26.1 and
26.2 `Camera` bytecode for `calculateFov(float)` is identical in shape
(`javap -c -p` diff, same instruction sequence, only constant-pool index
renumbering differs), so one identical file edit applies to both platforms,
consistent with today's established one-physical-copy-per-module pattern.

## Compile/Verification Steps

This repo's existing convention, per `docs/specs/tweaks-zoom-fov-plan.md`'s
Implementation Order section (the original T11 plan): compile each changed
platform module individually, then a full multi-platform build.

1. `./gradlew :platform:fabric-26.1:compileJava` — confirms the `@Inject`
   method selector `"calculateFov"` resolves against 26.1's real resolved/
   mapped Minecraft jar and that the handler's parameter list
   (`float, CallbackInfoReturnable<Float>`) type-checks.
2. `./gradlew :platform:fabric-26.2:compileJava` — same check for 26.2.
3. No `remapJar` step is required for correctness verification on 26.1/26.2
   specifically for this change (unlike 1.21.11, which the original plan
   flagged for remap risk on invoker-style generic-erased mixins — this
   change is a plain `@Inject` on a non-generic private method, the same
   pattern already used and working elsewhere on these platforms). Still,
   running each platform's full build is reasonable as a regression check:
   `./gradlew :platform:fabric-26.1:build :platform:fabric-26.2:build` (or
   `./gradlew build` for the whole multi-module project) if a broader check
   is wanted before verification.
4. `:platform:fabric-1.21.11:compileJava` / `remapJar` — **not needed**,
   since this fix does not touch the 1.21.11 module. Do not run 1.21.11-only
   tasks as part of verifying this change unless confirming no accidental
   edit landed there.

No unit tests are feasible for `@Inject` mixin classes against
obfuscated/merged Minecraft classes in this repo's existing setup (consistent
with `tweaks-zoom-fov-plan.md`'s Test Strategy for the original mixins) — rely
on compile-time checks plus manual in-game verification (below).

## Manual In-Game Verification (both 26.1 and 26.2)

1. Launch the platform, enable the `ZOOM` tweak (default
   `magnification = 4.0f`), use its bound key/trigger.
2. Confirm world-render FOV visibly narrows by roughly the configured
   magnification while zoom is active, and returns to normal immediately on
   release — this is the behavior the original (broken) mixin failed to
   produce, since `getFov()` was never called on the render path.
3. Confirm no restart or screen-reopen is required — `ZoomTicker` already
   updates `zoomActive` per-tick and `calculateFov` fires every frame via
   `Camera.update`, so the effect should track zoom state live.
4. Confirm held-item (HUD) FOV is unaffected (spec Non-goals — only
   `calculateFov`/`this.fov`/world-render projection changes; `calculateHudFov`/
   `hudFov` remain untouched), i.e. the first-person item does not appear to
   zoom independently of the world.
5. Confirm normal (non-zoom) FOV behavior is unaffected when Zoom is
   disabled — vanilla FOV slider and FOV Effects scale slider should behave
   exactly as before this change, since `applyFov` is a no-op pass-through
   when `isZoomActive()` is false (confirmed in `TweakHooksImpl.applyFov`
   above).

## Risks

- **R1 — Method selector match.** The plain string selector `"calculateFov"`
  assumes `Camera` has exactly one method by that name (spec's `javap -p`
  listing does not indicate an overload). If Mixin fails to resolve the
  target at runtime (as opposed to compile time — Sponge Mixin method
  selectors are largely resolved/validated at mixin-apply time, not purely
  javac time), the fix would silently no-op or hard-crash on mixin apply;
  watch for `MixinApplyError`/`InvalidInjectionException` in the log on first
  launch of each platform after this change, in addition to the compile
  check.
- **R2 — Parameter-list mismatch.** The handler's `float tickDelta` parameter
  must exactly match `calculateFov(float)`'s single parameter type for
  Sponge Mixin's `@Inject` parameter-matching to succeed; a mismatch (e.g.
  wrong type, or omitting/misordering it relative to
  `CallbackInfoReturnable`) causes the same class of injection-apply failure
  as R1, and would only surface as a runtime mixin-apply error, not a
  `compileJava` failure, since the handler is a private method Mixin invokes
  reflectively/via bytecode transform, not directly called from Java source.
- **R3 — Regression via javadoc-only edit.** The Javadoc rewrite (describing
  `calculateFov` instead of `getFov`) is prose-only and carries no functional
  risk, but should not be skipped — leaving stale, incorrect documentation in
  place (still describing `getFov()` as the choke point) would mislead future
  maintainers per the spec's own stated concern about this exact class of
  mistake.
- **R4 — No static check confirms actual FOV-changing behavior.** Everything
  above (compile, mixin-apply-time resolution) confirms the injection is
  wired correctly, but none of it proves the FOV visually changes correctly
  in-game — that requires the manual verification steps above. This is the
  same category of gap the original (wrong-target) mixin had: it compiled and
  applied cleanly, and this specific defect (injecting into a method that
  fires but is never called) would not have been caught by compile-time
  checks alone, only by either bytecode call-site tracing (as this spec did)
  or in-game observation. Verification phase should treat manual in-game
  confirmation as mandatory, not optional, before considering this fix
  closed.
- **R5 — `tickDelta` unused parameter.** The handler receives `tickDelta` but
  does not use it (matching the spec's design exactly). This may trigger an
  "unused parameter" lint/warning depending on this repo's static-analysis
  configuration; if so, it is expected and should not be treated as a defect
  requiring the parameter's removal (removing it would break the Mixin
  parameter-matching contract described in R2).

## Non-goals (carried from spec)

- No change to `calculateHudFov`/`hudFov` (held-item FOV).
- No change to `ZoomHook`, `ZoomTicker`, `TweakHooksImpl.applyFov()`, or
  `TweakEngineHandoff`.
- No change to the 1.21.11 mixin file or any 1.21.11 build task.
- No change to `docs/specs/tweaks-zoom-fov.md` or
  `docs/specs/tweaks-zoom-fov-fix.md` themselves.
- No implementation code written by this plan — planning only.
