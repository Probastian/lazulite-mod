# Implementation Plan: Wire Up the Remaining 11 Tweaks' Gameplay Hooks

Ground truth: `docs/specs/tweaks-hooks-wiring.md` (approved spec). Per that
doc's own instruction, the section **"Findings — javap verification pass
(main-thread, real jars, 2026-07-27)"** supersedes the earlier per-tweak
Architecture sections wherever they conflict; this plan's batch design and
per-tweak targets are drawn from that section, not the earlier guesses.

## Existing Implementation

- **Hook interfaces** (Minecraft-agnostic contracts, already correct, not
  modified by this batch):
  `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/{AntiDropHook,ForceBrightnessHook,ChatFilterHook,ChatPlayerHeadsHook,CustomCrosshairHook,DisableAnimationsHook,DisableParticlesHook,HidePlayerNamesHook,ClearWaterHook,DisableCosmeticsHook,DisableBossBarsHook,ZoomHook}.java`.
- **State-reading logic**, identical across all 3 platform modules, already
  implements every one of the 12 hook interfaces:
  `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`.
  Its class Javadoc (lines ~29-43) documents the 11 remaining call sites as
  "Risk #1, deferred to a follow-up implementation batch" — this plan is
  that batch. No changes to `TweakHooksImpl`'s existing logic are in scope;
  mixins call into its already-correct methods.
- **Live-instance accessor**: `platform/*/src/main/java/de/lazuli/tweaks/TweakEngineHandoff.java`
  — `TweakEngineHandoff.require()`, published from each platform's
  `TweaksClientInitializer.onInitializeClient()` before any world/render
  code runs. Same accessor pattern this batch's mixins will use, exactly as
  T11 Zoom's mixins already do.
- **Established mixin convention** (from the already-implemented/verified
  T11 Zoom spec, `docs/specs/tweaks-zoom-fov.md`): one mixin class per
  platform under `platform/*/src/main/java/de/lazuli/mixin/`, package
  `de.lazuli.mixin`, registered by simple class name in that platform's
  `platform/*/src/main/resources/lazuli.mixins.json` `mixins` array. Plain
  Sponge Mixin only — `@Inject`/`@ModifyVariable`/`@Redirect`, no
  mixinextras (`@ModifyReturnValue`/`@ModifyExpressionValue` are not used
  anywhere in this repo; keep it that way). Confirmed current registration
  list (identical shape on all 3 platforms, only `GameRendererZoomFovMixin`
  vs `CameraZoomFovMixin` differs) via
  `platform/fabric-1.21.11/src/main/resources/lazuli.mixins.json`:
  `ExampleMixin`, `WorldEntrySyncIconMixin`/`WorldListEntrySyncIconMixin`,
  `IntegratedServerWorldHostingMixin`, `ServerConnectionListenerCaptureMixin`,
  `ConnectionSteamChannelMixin`, `ClientHandshakeStubDigestMixin`,
  `ServerLoginStubDigestMixin`, `ServerKeyPacketMixin`,
  `ClientTitleScreenRedirectMixin`/`GuiTitleScreenRedirectMixin`, and the
  Zoom mixin (`GameRendererZoomFovMixin` on 1.21.11,
  `CameraZoomFovMixin` on 26.1/26.2).
- **Tweaks tab config-screen UI** (for T10's UI-only scope): shared-shape,
  per-platform file `platform/*/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`.
  Confirmed structure (1.21.11 copy, read this pass): `renderRow(...)` (line
  121) draws each tweak's checkbox row via `drawCheckbox(context, cbX, cbY,
  state.enabled())` (line 131); a separate `renderConfigRow(...)` (line 296)
  handles per-configurable widgets inside a tweak's expanded config screen.
  Row click-to-toggle happens at line ~403 via
  `bundle.registry().setEnabled(row.id(), !bundle.registry().stateOf(row.id()).enabled())`.
  No existing "disabled/hint" row state exists yet in this file on any
  platform — T10's UI task adds one.
- **Jar paths for any further/confirmatory `javap` work**, already resolved
  on disk (same as both prior specs used):
  - 1.21.11 (Yarn): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-6dd721cd7d-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
  - 26.1 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/minecraft-merged-a26c9a9f3c-26.1.jar`
  - 26.2 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`

## Dependencies

No new external (non-Fabric) dependencies are needed for any batch — every
mixin target identified in the spec's javap-verification pass is a vanilla
class already on each platform's existing Minecraft/Yarn/Mojmap compile
classpath (`net.minecraft.client.*`), and mixin authoring uses the Sponge
Mixin API already declared in each `platform/*/build.gradle` (same
dependency set T11 Zoom's mixins already use — no `build.gradle` changes
required for any batch below).

Inter-batch dependencies (build/merge ordering, not library dependencies):

- All batches are independent of each other at the code level (separate
  mixin classes, separate `TweakId`s, no shared new state) and can be
  implemented/merged in any order or in parallel by different sessions.
  This plan's batch order (A -> C -> B, with T10 any time) is a *risk-based
  sequencing recommendation*, not a hard technical dependency.
- Within a batch, per-platform files for the same tweak are independent of
  each other (no shared source set between platform modules today, per
  `tweaks-zoom-fov.md`'s Compatibility section, confirmed still true) but
  should land together in the same PR/commit per tweak so a tweak is never
  half-wired (e.g. working on 1.21.11 but silently inert on 26.x).
- Batch C (T5 Custom Crosshair) should implement 26.1 and 26.2 as two
  distinct files even though 1.21.11 already established the pattern,
  because of the confirmed `Gui`-vs-`Hud` class split (see Batch C below) —
  do not assume 26.1's file can be copy-pasted to 26.2 unmodified the way
  T11 Zoom's `CameraZoomFovMixin` was.

## Files to Create

Per-platform mixin classes, one per tweak per platform (or per tweak per
divergent-class variant, where a platform needs more than one target — see
per-batch notes). All under package `de.lazuli.mixin`, directory
`platform/<module>/src/main/java/de/lazuli/mixin/`.

### Batch A (safe-first)

- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/LightmapForceBrightnessMixin.java` (T2, targets `LightmapTextureManager.update(float)`)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/LightTextureForceBrightnessMixin.java` (T2, targets `LightTexture.updateLightTexture(float)`)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/LightTextureForceBrightnessMixin.java` (T2, same target as 26.1 — confirm identical via a quick `javap -p net/minecraft/client/renderer/LightTexture` diff between the 26.1 and 26.2 jars before assuming, per Compatibility note that 26.1/26.2 are no longer safe to assume identical without checking)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/ClientWorldDisableParticlesMixin.java` (T7, targets `ClientWorld.addParticle(...)`, both overloads)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/ClientLevelDisableParticlesMixin.java` (T7, targets `ClientLevel.addParticle(...)`)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/ClientLevelDisableParticlesMixin.java` (T7, same target as 26.1)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/EntityRendererHidePlayerNamesMixin.java` (T8, targets `EntityRenderer.renderLabelIfPresent(S, MatrixStack, OrderedRenderCommandQueue, CameraRenderState)` — confirmed render-command-queue signature, NOT the old immediate-draw guess)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/EntityRendererHidePlayerNamesMixin.java` (T8, targets `EntityRenderer.shouldShowName(T, double)` as the preferred cancel-by-`false` gate method, per spec's recommendation over fighting `submitNameDisplay`)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/EntityRendererHidePlayerNamesMixin.java` (T8, same target as 26.1 — spec confirms 26.1/26.2 identical for this specific class)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/ClientPlayerEntityAntiDropMixin.java` (T1, targets `ClientPlayerEntity.dropSelectedItem(boolean)`)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/LocalPlayerAntiDropMixin.java` (T1, targets `LocalPlayer.drop(boolean)`)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/LocalPlayerAntiDropMixin.java` (T1, same target — spec confirms identical on 26.1/26.2)

### Batch B (risky, safe-scoped)

- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/ChatHudChatFilterMixin.java` (T3, targets `ChatHud.addMessage(Text)` and/or the signed overload `addMessage(Text, MessageSignatureData, MessageIndicator)`)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/ChatComponentChatFilterMixin.java` (T3, targets `ChatComponent.addMessage(Component)` equivalent — confirm exact Mojmap signature/overload set with `javap` before coding; spec did not run javap against this specific class in the verification pass)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/ChatComponentChatFilterMixin.java` (T3, same target as 26.1 pending confirmation)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/ChatHudPlayerHeadsMixin.java` (T4, hooks the same `ChatHud.addMessage(Text, MessageSignatureData, MessageIndicator)` confirmed entry point, resolves sender via `PlayerListEntry`/`ClientPlayNetworkHandler`'s player-info map, draws via `PlayerSkinDrawer.draw(DrawContext, SkinTextures, int, int, int[, int])` using `net.minecraft.entity.player.SkinTextures` — corrected package per the javap findings)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/ChatComponentPlayerHeadsMixin.java` (T4, redesigned around `PlayerSkinRenderCache.getOrDefault(ResolvableProfile)`/`lookup(ResolvableProfile)` returning `PlayerSkinRenderCache$RenderInfo`, backed by `SkinManager` — NOT the earlier-guessed `PlayerFaceRenderer`, which does not exist)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/ChatComponentPlayerHeadsMixin.java` (T4, same `PlayerSkinRenderCache`-based design as 26.1 pending a same-class-shape confirmation, since this class was not directly javap-diffed between 26.1 and 26.2 in the spec's pass)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/SpriteContentsAnimatorDisableAnimationsMixin.java` (T6, targets `SpriteContents$Animator.tick()` — confirmed class name, corrected from the earlier "Ticker" guess)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/AnimationStateDisableAnimationsMixin.java` (T6, targets `SpriteContents$AnimationState.tick()` — confirmed the tick lives on `AnimationState`, not `AnimatedTexture`)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/AnimationStateDisableAnimationsMixin.java` (T6, same two-class split as 26.1, spec confirms identical between 26.1/26.2 for this pair)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/InGameHudClearWaterMixin.java` (T9, targets `InGameHud.renderOverlay(DrawContext, Identifier, float)`, discriminating by the `Identifier` arg to isolate the water overlay from portal/spyglass/nausea)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/GuiClearWaterMixin.java` (T9, targets `Gui.extractTextureOverlay(GuiGraphicsExtractor, Identifier, float)`, same `Identifier`-discrimination requirement)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/HudClearWaterMixin.java` (T9, targets `Hud.extractTextureOverlay(...)` — note the target **class** is `Hud`, not `Gui`, per the confirmed 26.1-vs-26.2 `Gui`/`Hud` split; do not reuse the 26.1 file unmodified)
- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/BossBarHudDisableBossBarsMixin.java` (T12, base hiding: targets `BossBarHud.render(DrawContext)`/`renderBossBar(...)`; `keepRaidBarsVisible` heuristic added in same file, see Risks)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/BossHealthOverlayDisableBossBarsMixin.java` (T12, targets `BossHealthOverlay.extractRenderState(GuiGraphicsExtractor)` + private `extractBar(...)` overloads — note this is NOT a `render(...)` method, corrected from the earlier guess)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/BossHealthOverlayDisableBossBarsMixin.java` (T12, same target as 26.1 — spec confirms `BossHealthOverlay` itself is unaffected by the `Gui`/`Hud` split, only its owning top-level class differs)

### Batch C (dedicated investigation: T5 Custom Crosshair)

- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/InGameHudCustomCrosshairMixin.java` (targets `InGameHud.renderCrosshair(DrawContext, RenderTickCounter)` — confirmed, cancel-and-redraw)
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/GuiCustomCrosshairMixin.java` (targets `Gui.extractCrosshair(GuiGraphicsExtractor, DeltaTracker)` — confirmed; must inject into the extraction step and modify what's written to the extracted render state, a materially different mixin shape from 1.21.11's cancel-and-redraw)
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/HudCustomCrosshairMixin.java` (targets `Hud.extractCrosshair(...)` on the new `Hud` class — confirmed NOT present on `Gui` for 26.2; method name/signature otherwise identical to 26.1's `Gui.extractCrosshair`, only the owning class differs)

### T10 (UI-only, no mixin, can ship any time)

- No new files strictly required; implemented as a modification to
  `platform/*/src/main/java/de/lazuli/mainmenu/TweaksPanel.java` (see Files
  to Modify). If the disabled/hint-row rendering logic ends up nontrivial
  enough to warrant extraction, a small shared helper could live in
  `features/main-menu/src/main/java/de/lazuli/features/mainmenu/...`, but
  start by keeping it inline in each platform's `TweaksPanel.java` to match
  that file's existing per-platform-duplicated pattern; only extract if the
  three copies would otherwise diverge.

## Files to Modify

- `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/resources/lazuli.mixins.json`
  — append each new mixin's simple class name to the `mixins` array, once
  per platform, as each tweak's mixin(s) land (batch-by-batch, not all at
  once, so partially-landed batches don't reference not-yet-written classes).
- `platform/{fabric-1.21.11,fabric-26.1,fabric-26.2}/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`
  — T10 only: in `renderRow(...)` (and/or wherever the row's checkbox/click
  handling lives, ~line 121/403 on the 1.21.11 copy), add a disabled/hint
  branch specifically for `TweakId.DISABLE_COSMETICS` (or whatever its
  constant is named — confirm via `features/tweaks/.../TweakId.java` at
  implementation time) that renders the row non-interactive with hint text
  ("Coming soon — requires an in-world cosmetics renderer that doesn't
  exist yet.", per spec R8/T10) instead of a normal enable checkbox, and
  suppresses the click-to-toggle handler for that one row. No
  `TweakHooksImpl`/`DisableCosmeticsHook` changes.
- `TweakHooksImpl.java` (all 3 platforms): **not modified** — its state-
  reading logic is confirmed correct and complete for all 12 hooks; this
  batch only adds mixins that call into its existing public methods. If
  implementation of any tweak surfaces a genuine bug in `TweakHooksImpl`
  (not expected, per spec's Non-goals), that is an out-of-plan finding to
  flag back to the user, not a silent fix.

## Risks

- **T6 `animatedTextureId` back-reference (all platforms)**: the spec
  explicitly leaves unresolved how a `SpriteContents$Animator`/
  `AnimationState` instance recovers its owning sprite's `Identifier` from
  inside the injected `tick()` method. Implementation of T6 must start with
  a `javap -c -p` (bytecode-level, not just signature-level) pass on
  `SpriteContents` and its inner classes to find a `@Shadow`-able
  back-reference field before any mixin code is written; if none exists,
  T6 may need a companion `@Inject` at `SpriteContents`'s animation-state
  construction site to capture and stash the id externally (e.g. an
  `IdentityHashMap` keyed by ticker/state instance) — a heavier design than
  a single-method mixin. Flag to user if this turns out to require that
  fallback.
- **T9 Clear Water discrimination bug risk (all platforms)**: `renderOverlay`/
  `extractTextureOverlay` is shared with portal, spyglass, and nausea
  overlays; a mistake in the `Identifier`-equality check would suppress or
  dim unrelated overlays. Mitigation: unit/manual test matrix must
  explicitly include entering a nether portal and using a spyglass while
  Clear Water is active, not just being underwater (see Test Strategy).
- **T12 `keepRaidBarsVisible` has no clean vanilla flag (all platforms,
  confirmed absent, not just unconfirmed)**: any implementation is a
  name-text/color heuristic (e.g. matching vanilla's raid-bar translation
  key and/or `BossBar.Color.RED`) against the server-side `Raid` class's
  boss-bar packet, which could false-positive against a third-party plugin/
  datapack boss bar that happens to also be red. Recommend implementing the
  base whitelist/blacklist/all-mode hiding first and landing
  `keepRaidBarsVisible` as a clearly-labeled best-effort heuristic, with a
  code comment noting the false-positive risk, rather than blocking the
  rest of T12 on a perfect solution (resolves spec's still-open sequencing
  question in favor of "ship the safe majority, flag the heuristic as
  best-effort" — see Acceptance Criteria for how this is verified).
- **26.1 vs 26.2 divergence is no longer safe to assume away.** The spec's
  own Compatibility section previously assumed 26.1/26.2 usually match; the
  javap pass falsified this for the `Gui`/`Hud` split affecting T5/T9/T12's
  *owning* top-level class. This plan already treats T5/T9 as 3 distinct
  files per tweak reflecting that, but **other classes this plan assumes
  are 26.1/26.2-identical without a direct javap diff** (`LightTexture`,
  `ClientLevel.addParticle`, `EntityRenderer.shouldShowName`, `ChatComponent`,
  `PlayerSkinRenderCache`, `SpriteContents$AnimationState`) were only
  confirmed identical by the spec for some of these (`EntityRenderer`,
  `SpriteContents$AnimationState` — explicitly confirmed; the rest were not
  directly diffed). Each tweak's 26.2 file should get a quick confirmatory
  `javap -p` diff against 26.1's target class before assuming the file can
  be a byte-for-byte copy, even though this plan lists them as presumed-
  identical for planning purposes.
- **T3/T4 shared `ChatHud`/`ChatComponent` injection point.** Both tweaks
  inject at the same chat-add call site on each platform. If implemented in
  separate PRs/sessions without coordination, there's a risk of two mixins
  targeting the same method with conflicting `@ModifyVariable`/`@Inject`
  ordering (T3 mutates the text, T4 only reads sender identity and draws
  separately, so they shouldn't functionally conflict, but Mixin injection
  ordering across two classes targeting one method is worth a quick manual
  check — e.g. `@Inject`'s default unordered application — during Batch B's
  test pass). Recommend implementing T3 and T4 in the same batch pass
  (both are in Batch B already) so this interaction is checked once, not
  twice.
- **T4 26.x `PlayerSkinRenderCache` is async/cache-oriented**, a materially
  different design shape than 1.21.11's synchronous static draw call. A
  first-fetch-miss (skin not yet cached) must degrade gracefully (skip
  drawing the head that frame, or draw a placeholder) rather than block/
  stall chat rendering — this needs an explicit design decision during
  26.x implementation, not assumed to mirror 1.21.11's flow.
- **General**: every mixin target in this plan is drawn from the spec's
  javap-verification pass, which is authoritative for class/method
  *existence and shape* — but none of these mixins have been written or
  compiled yet. Standard mixin-authoring risk (exact `@At` injection point,
  local-variable-capture ordinal for `@ModifyVariable`, whether a target
  method is `final`/needs `@Unique` companion state) still applies at
  implementation time for all batches, same as it did for T11 Zoom.

## Dependencies (build/library)

No new external dependencies for any batch (see Dependencies section above
under Files to Create's lead-in — restated per template requirement): every
target is vanilla Minecraft classpath already present, and all mixin
authoring uses the existing Sponge Mixin setup. If implementation later
finds a genuine gap (e.g. an unforeseen need for MixinExtras to express a
`@ModifyExpressionValue` cleanly on the `PlayerSkinRenderCache` async path),
that would be a real new-dependency proposal requiring the standard Maven
Central verification step before adoption — not anticipated by this plan,
flagged here only so implementation doesn't silently add it without that
check.

## Test Strategy

No existing automated test harness covers mixin-level gameplay behavior in
this repo (confirmed by `tweaks-zoom-fov.md`'s own Test approach, which was
manual/in-game verification per platform — same approach applies here,
there is no unit-testable seam for `@Inject` render/tick behavior without a
full Minecraft client environment).

Per batch:

- **Batch A**: for each of T2/T7/T8/T1, manually verify on all 3 platforms
  (launch each platform's dev client): toggle the tweak on/off in the
  Tweaks tab and confirm the in-game effect appears/disappears immediately
  (brightness floor, particles suppressed by type/mode, name tags hidden
  past configured distance, drop key no-ops). Confirm toggling the tweak
  off restores fully vanilla behavior (no residual state).
- **Batch B**: same manual per-platform toggle test, plus targeted scenario
  tests: T3 — chat containing a filtered word from multiple senders,
  including a message with mixed formatting/click-events (hover a
  hyperlink-containing chat message) to confirm masking doesn't strip
  unrelated formatting; T4 — chat from a player currently in render
  distance vs. out of it vs. a system/unsigned message (should show no
  head); T6 — a known animated vanilla texture (e.g. lava, fire, prismarine)
  with the tweak on/off; T9 — underwater, in a nether portal, and using a
  spyglass, confirming only the water overlay is affected; T12 — a normal
  boss bar (e.g. wither) and, if feasible to trigger in a test world, an
  actual raid, to check `keepRaidBarsVisible`'s heuristic doesn't
  false-positive/false-negative on the one real vanilla case available.
- **Batch C**: T5 on all 3 platforms with each of the exposed crosshair
  configurables (`outline`/`gap`/`length`/`thickness`/`centerDot`/RGB)
  varied, confirming visual match to configured values; explicit
  side-by-side screenshot comparison between 26.1 and 26.2 given the
  confirmed `Gui`/`Hud` class-split risk, to catch any behavioral
  divergence the class split might introduce beyond the class name itself.
- **T10**: manual check that the Disable Cosmetics row renders as
  disabled/hinted (not a clickable checkbox) on all 3 platforms' Tweaks
  tab, and that clicking it does not toggle any state.
- **Cross-batch regression check**: after each batch, re-verify T11 Zoom
  still works (its mixins are untouched, but `lazuli.mixins.json` edits in
  the same file are a realistic source of accidental breakage via JSON
  syntax errors or ordering issues).

## Acceptance Criteria

- **Batch A** ships when: T1/T2/T7/T8 each have a working, toggleable
  gameplay effect on all 3 platforms, mixins registered in all 3
  `lazuli.mixins.json` files, and the manual test matrix above passes with
  no vanilla-behavior regression when each tweak is disabled.
- **Batch B** ships when: T3/T4/T6/T9/T12 each have their *safe-scoped*
  behavior (word-masking, head-drawing for in-scope senders, animation
  freeze, water-overlay-only clearing, boss-bar mode hiding) working on all
  3 platforms per the same criteria as Batch A, **and** T12's
  `keepRaidBarsVisible` heuristic is implemented and documented as
  best-effort (code comment + a short note added to
  `docs/specs/tweaks-hooks-wiring.md`'s Future Extensions or a new short
  follow-up note, at implementer's discretion) rather than silently
  shipped as if guaranteed-correct.
- **Batch C** ships when: T5 has a working crosshair override on all 3
  platforms including the confirmed `Gui`-vs-`Hud` 26.1/26.2 split handled
  as two distinct files, and the side-by-side 26.1/26.2 screenshot check
  shows no unintended visual divergence.
- **T10** ships when: the Disable Cosmetics row shows the disabled/hint
  state on all 3 platforms and cannot be toggled, with no
  `TweakHooksImpl`/hook-interface changes.
- **Overall**: this plan is fully delivered when all 11 tweaks (T1-T10, T12)
  plus already-shipped T11 have real gameplay effects wired, `TweakHooksImpl`'s
  class Javadoc's "Risk #1, deferred" note is updated/removed to reflect
  completion, and no platform module has a tweak silently inert (config UI
  present but no effect) except where the spec explicitly scoped one out
  (none remain after this plan, since T10's UI-only scope is itself the
  full intended deliverable for that tweak).
