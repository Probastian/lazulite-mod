# Implementation Plan: Wire Up T11 Zoom's Real FOV Effect

Source spec: `docs/specs/tweaks-zoom-fov.md` (approved, frozen — this plan does
not alter it). Cross-version findings already recorded in
`.claude/context/minecraft.md`'s "Per-frame FOV computation choke point" row
(last row of the divergence table as of this plan).

## Existing Implementation

Everything upstream of the mixin call site is already correct and untouched
by this feature (see spec's Overview for the full list); implementation only
adds the three new per-platform mixin classes plus their `lazuli.mixins.json`
registrations:

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ZoomHook.java`
  — `isZoomActive()`/`applyFov(float)` interface, already correct.
- `platform/*/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java` —
  `applyFov(float baseFov)` already implemented (lines 219-227 on the
  1.21.11 copy, identical on 26.1/26.2).
- `platform/*/src/main/java/de/lazuli/tweaks/TweakEngineHandoff.java` —
  `TweakEngineHandoff.require()` already publishes the live `TweakHooksImpl`
  singleton, guaranteed non-null before any render/world code runs (published
  during each platform's `TweaksClientInitializer.onInitializeClient()`).
- Each platform's `lazuli.mixins.json` currently registers 9 mixins each
  (`ExampleMixin`, world-entry sync icon, world-hosting/networking mixins,
  `ClientTitleScreenRedirectMixin`/`GuiTitleScreenRedirectMixin`) — confirmed
  by reading all three files directly; this is the file each platform's new
  entry gets appended to.
- javap findings (spec Architecture section, restated here only as pointers,
  not re-quoted): 1.21.11's choke point is
  `net.minecraft.client.render.GameRenderer#getFov(Camera, float, boolean)`
  (private); 26.1/26.2's is `net.minecraft.client.Camera#getFov()` (public,
  no-arg) — a real class relocation, not a rename, identical between 26.1
  and 26.2.

## Files to Create

1. `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/GameRendererZoomFovMixin.java`
   - `@Mixin(GameRenderer.class)`, `@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)`
     on a method matching `private float getFov(Camera, float, boolean)`,
     `CallbackInfoReturnable<Float>` parameter, body:
     `cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));`
   - No other logic — the spec (R4) requires this stay a bare passthrough,
     no extra conditionals.

2. `platform/fabric-26.1/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`
   - `@Mixin(Camera.class)` (`net.minecraft.client.Camera`),
     `@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)` on
     the public no-arg `getFov()`, same
     `cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));`
     body.

3. `platform/fabric-26.2/src/main/java/de/lazuli/mixin/CameraZoomFovMixin.java`
   - Identical file content to #2 (spec explicitly calls for two separate
     files, not a shared source set — none exists between platform modules
     today).

All three are plain Sponge Mixin (`@Inject` + `CallbackInfoReturnable<Float>`
+ `cancellable = true`), no mixinextras, matching spec R4 and this repo's
existing mixin style (e.g. `ClientTitleScreenRedirectMixin`).

## Files to Modify

1. `platform/fabric-1.21.11/src/main/resources/lazuli.mixins.json`
   - Append `"GameRendererZoomFovMixin"` to the `mixins` array (after the
     existing `"ClientTitleScreenRedirectMixin"` entry, consistent with
     append-at-end ordering already used for that file's prior additions).

2. `platform/fabric-26.1/src/main/resources/lazuli.mixins.json`
   - Append `"CameraZoomFovMixin"` to the `mixins` array (after
     `"GuiTitleScreenRedirectMixin"`).

3. `platform/fabric-26.2/src/main/resources/lazuli.mixins.json`
   - Same append as #2, this platform's own copy of the file.

No changes to `common/`, `features/tweaks/**`, any `build.gradle`, or any
other platform file — the spec's Public API/Configuration/Persistence
sections all confirm zero new surface beyond the three mixin classes.

## Implementation Order

1. 1.21.11 first (simpler signature: mixin target `GameRenderer`, 3-arg
   method) — write `GameRendererZoomFovMixin`, append to that platform's
   `lazuli.mixins.json`, compile (`:platform:fabric-1.21.11:compileJava`) to
   confirm the `@Inject` target resolves against the real merged jar (this
   platform runs a real `remapJar` step, per the "Open item (1.21.11 only)"
   row in `.claude/context/minecraft.md` — a compile-time check here doesn't
   fully rule out remap-time issues, but `getFov` is a plain private
   instance method with no generic-erased parameter, unlike that row's
   `addEntry` case, so no analogous remap warning is expected).
2. 26.1 — write `CameraZoomFovMixin` (targeting `Camera`, no-arg), append to
   `lazuli.mixins.json`, compile
   (`:platform:fabric-26.1:compileJava`).
3. 26.2 — copy the identical file content from step 2 into this platform's
   own `de.lazuli.mixin` package, append to this platform's own
   `lazuli.mixins.json`, compile (`:platform:fabric-26.2:compileJava`).
4. Full multi-platform build (`./gradlew build` or platform-scoped
   equivalent per this repo's existing build workflow) to catch any
   cross-module regression before handing off to verification.

Do the three mixins independently in this order rather than in parallel —
each is a small, low-risk, single-file change, and sequencing lets a
1.21.11-specific remap surprise (if any) get caught before writing the
(identical, so less likely to fail) 26.1/26.2 copies.

## Risks / Ambiguities Inherited from the Spec

- **R1 — 1.21.11 remap risk (inherited, not new):** the spec's Architecture
  section confirms `getFov` is a private method with no generic-erased
  parameter type, which is the specific condition that broke the `addEntry`
  invoker mixin on this same platform in `steam-cloud-sync` (see
  `.claude/context/minecraft.md`'s "Open item (1.21.11 only)" row). A plain
  `@Inject` on a private method is a much more common/well-supported Mixin
  pattern than an `@Invoker` on a generic-erased parameter, so this risk is
  believed low, but implementation should still watch the
  `:platform:fabric-1.21.11:remapJar` output for any new warning mentioning
  `getFov`/`GameRendererZoomFovMixin`, and flag it to verification if one
  appears.
- **R2 — Method selector ambiguity:** 1.21.11's `getFov` takes 3 parameters
  (`Camera, float, boolean`); if `GameRenderer` has any other private method
  also named `getFov` with a different signature (not indicated by the
  spec's javap findings, which show only one), the plain `method = "getFov"`
  string selector would need to become a full descriptor. The spec's javap
  transcript shows only one `getFov` entry in the constant pool, so this is
  not expected, but is a fast thing to double check if the mixin fails to
  apply.
- **R3 — Verifying "every frame" behavior (spec Goals):** the spec asserts
  no restart/screen-reopen is needed because `ZoomTicker` already updates
  `zoomActive` per-tick and the mixin fires on every `getFov`/`Camera.getFov`
  call. Implementation doesn't need to add anything for this (it falls out
  of the `@Inject(at = @At("RETURN"))` firing on every real invocation), but
  verification should confirm this in-game (toggle zoom mid-session, confirm
  FOV changes without reopening any screen) since it's a runtime behavior
  claim, not something a compile can confirm.
- **R4 — No double-application risk:** because 1.21.11's `getFov` is the
  sole choke point reached by all four internal `GameRenderer` call sites
  (per spec), and 26.1/26.2's `Camera.getFov()` is the sole external
  accessor, there is no risk of `applyFov` running twice per frame on the
  same value through two different injected paths — this was a design
  question the spec already resolved (Architecture section), implementation
  should not add a second injection point "to be safe."
- **Ambiguity carried forward, not to be resolved by this implementation:**
  the spec's Future Extensions section explicitly defers smooth zoom
  transitions and scroll-to-change-magnification; implementation must not
  add either, even opportunistically, since both are called out as
  out-of-scope and would belong in `TweakHooksImpl`/`ZoomHook`, not the
  mixins built here.

## Test Strategy

- No unit tests are feasible for `@Inject` mixin classes against obfuscated/
  merged Minecraft classes in this repo's existing setup (consistent with
  how prior mixins like `ClientTitleScreenRedirectMixin` are verified) — rely
  on compile-time checks (`compileJava` per platform + `remapJar` on
  1.21.11) plus manual in-game verification.
- Manual in-game verification (per platform, all three):
  1. Launch, enable the `ZOOM` tweak (default `magnification = 4.0f`), bind/
     use its key.
  2. Confirm FOV visibly narrows by roughly the configured magnification
     factor while held/toggled active, and returns to normal when Zoom is
     released/toggled off — no restart or screen reopen needed.
  3. Confirm FOV is unaffected when the `ZOOM` tweak itself is disabled in
     config (mixin passthrough must not fire any override logic itself —
     `applyFov` already guarantees inactive-passthrough, this just confirms
     the mixin doesn't break that guarantee).
  4. Confirm no interaction/regression with vanilla's own "FOV" and "FOV
     Effects" options sliders (spec Compatibility section) — sprint FOV
     kick, elytra, nausea should all still layer correctly under Zoom.
- Cross-platform: since 26.1/26.2 use byte-identical mixin file content,
  verifying on one of the two thoroughly and doing a lighter smoke-test pass
  on the other (launch + toggle zoom once) is sufficient given the spec's
  confirmed-identical `Camera` method set between them.

## Acceptance Criteria

- All three new mixin files exist at the exact paths listed above, each
  registered in that platform's own `lazuli.mixins.json`.
- `getFov`/`Camera.getFov()` on all three platforms returns
  `TweakEngineHandoff.require().applyFov(<vanilla value>)` instead of the
  raw vanilla value, with no other logic added in the mixin itself (spec
  R4/Non-goals).
- All three platform modules compile cleanly (`compileJava`, plus
  `remapJar` on 1.21.11) with the new mixins registered.
- Manual in-game check on at least one platform (ideally all three, per Test
  Strategy) confirms FOV changes live when Zoom is toggled/held, with no
  restart needed, and no change to FOV when Zoom/the `ZOOM` tweak is
  inactive.
- No changes made to `ZoomHook`, `ZoomTicker`, `TweakHooksImpl`,
  `TweakEngineHandoff`, or `docs/specs/tweaks-zoom-fov.md` itself.
