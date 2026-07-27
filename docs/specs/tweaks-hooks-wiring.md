# Spec: Wire Up the Remaining 11 Tweaks' Gameplay Hooks

## Overview

`docs/specs/tweaks-zoom-fov.md` wired T11 Zoom's `applyFov()` to a real
per-platform mixin call site, using a `javap`-against-resolved-jars
methodology (extract the merged client jar already cached under
`.gradle/loom-cache/minecraftMaven/net/minecraft/...`, run
`javap -p` / `javap -c -p` / `javap -v -p` against the specific `.class`
entries, confirm exact signatures before designing any mixin). All 12
tweaks' *state-reading* logic already exists and is correct in
`platform/*/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java` (identical
across all 3 platform modules) — its class Javadoc (lines 29-43, all three
copies) explicitly documents that the actual vanilla mixin call sites for
the other 11 hooks (`AntiDropHook`, `ForceBrightnessHook`, `ChatFilterHook`,
`ChatPlayerHeadsHook`, `CustomCrosshairHook`, `DisableAnimationsHook`,
`DisableParticlesHook`, `HidePlayerNamesHook`, `ClearWaterHook`,
`DisableCosmeticsHook`, `DisableBossBarsHook`) were deferred as "Risk #1,
deferred to a follow-up implementation batch."

This spec is that follow-up's research phase: for each of the 11 tweaks, it
identifies the vanilla call site(s)/mixin target method(s) needed across all
3 platforms (`fabric-1.21.11` Yarn, `fabric-26.1`/`fabric-26.2` Mojmap),
classifies each as safe/small or risky/bigger, and surfaces open questions.
**It does not plan implementation batches** — that is the planning phase's
job, once the user has reviewed this document's findings and open questions.

### Methodology limitation — read before trusting the class/method names below

This document went through two specification passes:

1. **First pass** — ran in an environment without shell/`javap` access, only
   file read/search tools. Every target was sourced from stable,
   well-documented vanilla Minecraft/Yarn/Mojmap class and method names
   (the same naming families already confirmed elsewhere in
   `.claude/context/minecraft.md`) and general knowledge of vanilla's
   rendering/networking pipeline shape — offered as high-confidence
   starting points, not verified findings.
2. **Second pass (this revision)** — the user asked for the low-confidence
   items to be re-verified with real `javap`/shell access against the
   resolved jars, matching `tweaks-zoom-fov.md`'s methodology exactly.
   **That re-verification could not be performed**: this revision also ran
   in an agent invocation with only `Read`/`Glob`/`Grep`/`Write`/`WebFetch`/
   `WebSearch` tools available — no `Bash`/shell tool was provided, so
   `javap`/`unzip` could not be executed against
   `.gradle/loom-cache/minecraftMaven/...` even though those jars are
   confirmed present on disk (paths below, verified via `Glob`). Instead,
   this pass substituted **official Yarn/Mojmap javadoc lookups via web
   search** (`maven.fabricmc.net/docs/...`, NeoForge javadoc mirrors) for
   the specific tweaks the user flagged, checked against build versions as
   close to this repo's pinned versions as could be found. This is a real
   improvement over pass 1's pure recall-based guessing (it's checking an
   actual generated-from-mappings API reference, not memory), but **it is
   still not the literal `javap -p` run against this repo's exact resolved
   jars that the user asked for and that `tweaks-zoom-fov.md` used** —
   confidence ratings below reflect that distinction. **The mandatory
   `javap -p` / `javap -c -p` pass against this repo's own
   `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/**`
   jars must still be the literal first step of implementing any of these
   11 tweaks** — do not write a mixin against an unconfirmed signature from
   this document without that pass, regardless of the confidence rating
   shown. Confidence tiers used below: **high** = well-established vanilla
   shape unlikely to have changed, or confirmed against official mapping
   docs for a matching/adjacent build; **medium** = plausible but has known
   historical churn; **low** = a genuine guess flagged for extra scrutiny;
   **javap-blocked** = the user specifically asked this item be javap-
   verified and it could not be, because this agent invocation had no
   shell tool — flagged distinctly from the other tiers so it is not
   mistaken for a considered-and-rated guess.

## Goals

- Enumerate, per tweak, the concrete vanilla call site(s) a mixin would
  need to target on each of the 3 platforms, with per-platform divergences
  called out explicitly (matching `tweaks-zoom-fov.md`'s Architecture
  section style).
- Classify each of the 11 tweaks as safe/small or risky/bigger, so the
  planning phase can sequence batches by risk/size instead of guessing.
- Audit Zoom's own configurables end-to-end as a correctness check.
- Surface open questions/decisions for the user rather than assuming an
  implementation order.

## Non-goals

- No mixin code, no `@Inject`/`@Redirect` bodies, no `lazuli.mixins.json`
  edits — that is the planning/implementation phases' job once a batch is
  chosen.
- No batching/sequencing plan beyond confirming the recommended default
  ordering below — exact batch boundaries are still deferred to planning.
- No new hook-interface methods or `TweakHooksImpl` logic changes — the
  Zoom audit below is a correctness check of *existing* code, not new
  requirements (unless a real bug were found; none was).
- No implementation of a cosmetics-on-player-entity renderer (see T10
  below). Per the user's explicit decision, T10 is dropped from this
  batch's mixin/gameplay scope entirely and re-scoped to a tiny UI-only
  follow-up (a disabled/hint state in the Tweaks config screen) — it is
  **not** a mixin-wiring task and is not classified safe/risky in the
  mixin sense.
- **Chat Filter (T3) is explicitly scoped to substring replace/masking of
  the matched profane word(s) only** (e.g. replacing the matched substring
  in place with asterisks) — whole-message hiding is explicitly a
  non-goal, per the user's decision. If whole-message hiding is ever
  wanted, it needs a new hook contract (`String -> boolean` or similar),
  which is out of scope here.

## Requirements

- **R1**: For each of the 11 tweaks, document the vanilla class(es) and
  method(s) a mixin must target, per platform, sourced from the hook
  interface's existing contract (`features/tweaks/.../services/*Hook.java`)
  and `TweakHooksImpl`'s existing method it must feed.
- **R2**: Explicitly call out every place 1.21.11 (Yarn) structurally
  diverges from 26.1/26.2 (Mojmap), not just where names differ.
- **R3**: Classify each tweak safe/small vs risky/bigger with a one-line
  rationale.
- **R4**: Re-verify T5 Custom Crosshair's `colorMode` "no confirmed call
  site" flag (see Findings below — confirmed still true, no new call site
  found).
- **R5**: Audit Zoom's configurables (`holdToZoom`, `magnification`,
  `transition`, `transitionDurationMs`, `scrollToAdjust`) against
  `ZoomTicker`/`TweakHooksImpl` for unread fields.
- **R6**: T3 Chat Filter's design must operate purely as substring
  replace/masking of matched word(s) in place, preserving the rest of the
  message and its formatting — not whole-message removal.
- **R7**: T4 Chat Player Heads must reuse vanilla's own existing player-
  skin-resolution API (the same one vanilla uses for tab-list heads /
  item-frame player-head rendering) rather than inventing new resolution
  logic — see the confirmed findings in the T4 section below.
- **R8**: T10 Disable Cosmetics' scope for this batch is UI-only: the
  Tweaks config screen must show the tweak as unavailable with an
  explanatory hint, no mixin/hook wiring included.

## Public API

None — this is a research document; no code changes are specified here.

## Architecture — per-tweak findings

Each subsection lists: the hook interface + method(s) it must feed
(`TweakHooksImpl`'s existing implementation, unchanged), the mixin
target(s) per platform, cross-version divergence (if any), and a safe/risky
classification.

Jar paths for the mandatory pre-implementation `javap` pass (confirmed
present on disk via `Glob` in this pass; same jars `tweaks-zoom-fov.md`
used):

- 1.21.11 (Yarn): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-6dd721cd7d-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
- 26.1 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/minecraft-merged-a26c9a9f3c-26.1.jar`
- 26.2 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`

---

### T1 — Anti-Drop (`AntiDropHook.shouldCancelDrop(itemId, shiftHeld)`)

Vanilla drop input is issued from the client's input handler when the
configured "Drop Item"/"Drop Stack" key is pressed, dispatching a
`ServerboundPlayerActionPacket`/`PlayerActionC2SPacket` with a `DROP_ITEM`/
`DROP_ALL_ITEMS` action — but a mixin can intercept earlier and more simply
at the client-side method that decides to send that packet at all, avoiding
any packet-format concerns.

- **1.21.11 (Yarn)**: `net.minecraft.client.network.ClientPlayerInteractionManager.dropItem(net.minecraft.item.ItemStack, boolean)` — Yarn's conventional name for the "drop currently held stack, single item or whole stack" client-side entry point, called from `ClientPlayerEntity`'s key-handling. **Confidence: medium, javap-blocked** (method exists in this shape across many past versions; exact boolean-arg meaning still needs a real `javap` confirmation this pass could not perform).
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.multiplayer.MultiPlayerGameMode.dropItem` — the exact method name on the Mojmap side is unresolved; a web-search pass this revision also attempted did not turn up a single confident current name (Mojang has renamed this method across versions). **Confidence: low, javap-blocked** — this is exactly the kind of case that needs a literal `javap -p net/minecraft/client/multiplayer/MultiPlayerGameMode` run, not a guess.
- **Divergence**: class itself renames (`ClientPlayerInteractionManager` -> `MultiPlayerGameMode`), consistent with the general "InteractionManager -> GameMode" Yarn/Mojmap rename pattern seen elsewhere in this codebase's prior `javap` findings (not yet in `.claude/context/minecraft.md`, should be added once confirmed).
- **shiftHeld**: `shiftForces` config path already reads a live key-state at cancel-time (not baked into the mixin) — the mixin only needs the item id + whatever shift-state accessor the interaction manager already has in scope (`Screen.hasShiftDown()` or the input system's own modifier state), no new plumbing.
- **Classification: risky/bigger** — the exact drop-entry-point method name is unconfirmed on the Mojmap side (unlike Zoom, where both sides had a single well-known accessor), and cancelling a drop mid-flight (rather than post-hoc) needs the `@Inject` to fire *before* any inventory mutation/packet send, which needs bytecode inspection to place correctly.

---

### T2 — Force Brightness (`ForceBrightnessHook.isForceBrightnessActive()`, `minBrightness()`)

Vanilla's ambient/block lightmap texture is computed once per frame in the
lighting-texture builder, then sampled per-vertex during world render — the
established "gamma boost"/fullbright mod pattern targets that lightmap
computation, not per-vertex color.

- **1.21.11 (Yarn)**: `net.minecraft.client.render.LightmapTextureManager.update(float)` — builds the 16x16 lightmap texture each frame from `GameOptions.getGamma()`/game time/effects; a mixin can `@ModifyVariable`/`@Redirect` the gamma value read inside this method. **Confidence: high** (this exact class/method is the standard target for "fullbright"-style mods across many MC versions, stable Yarn name).
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.renderer.LightTexture.updateLightTexture(float)` — Mojmap's equivalent, same per-frame responsibility. **Confidence: high** (equally standard/stable Mojmap name).
- **Divergence**: naming only (`LightmapTextureManager.update` vs `LightTexture.updateLightTexture`) — no structural relocation expected, unlike Zoom's FOV case; still needs `javap` to confirm the exact gamma-read bytecode offset for a `@ModifyVariable`/`@Redirect` (vs `@Inject`+field write, if gamma isn't a local variable in this version).
- **Classification: safe/small** — single mixin target per platform, one obvious numeric substitution (`max(vanillaGamma, minBrightness())`), reuses `TweakHooksImpl` logic verbatim, well-trodden pattern in the broader modding ecosystem so the method identity risk is lower than most others here.

---

### T3 — Chat Filter (`ChatFilterHook.filterText(plainText)`)

**Scope (per user decision): substring replace/masking of matched
profane word(s) only.** Whole-message hiding is explicitly a non-goal
(see Non-goals) — this removes the previous design ambiguity that made
this tweak's classification uncertain on scope grounds; the remaining risk
is purely about the text-tree reconstruction mechanics described below.

Needs to intercept an incoming chat message's plain text before it's stored
in `ChatHud`'s message log (so a masked message never shows the original
word even in scrollback) — not just at render time.

- **1.21.11 (Yarn)**: `net.minecraft.client.gui.hud.ChatHud.addMessage(net.minecraft.text.Text)` (and/or the timestamped overlay overload) — the single choke point all incoming chat text funnels through before being queued for render. **Confidence: high** (`ChatHud.addMessage` is a long-stable Yarn name).
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.gui.components.ChatComponent.addMessage(net.minecraft.network.chat.Component)` — Mojmap's equivalent. **Confidence: high.**
- Because `filterText(String)` takes a plain `String`, the mixin needs to
  extract plain text from the `Text`/`Component` argument
  (`Text.getString()`/`Component.getString()`), run it through the hook
  to get the masked string back, and reconstruct the argument
  (`@ModifyVariable` building a new `Text.literal(...)`/
  `Component.literal(...)` with the masked string) before it reaches
  `addMessage`.
- **Divergence**: naming/package only (`gui.hud.ChatHud` vs
  `gui.components.ChatComponent`), consistent with the general
  `hud.*`->`components.*` HUD-widget-package-rename pattern already seen
  elsewhere in `.claude/context/minecraft.md` (row 74's `ButtonWidget` vs
  `Button`).
- **Classification: risky/bigger** — reconstructing a `Text`/`Component`
  tree with in-place substring replacement while preserving formatting/
  click-events is nontrivial (chat text is usually a tree of styled
  siblings, not one flat string); a naive approach would flatten
  formatting when masking a word inside a styled span. Needs a real design
  decision on the reconstruction strategy during implementation, but the
  *scope* question (mask vs hide) is now resolved.

---

### T4 — Chat Player Heads (`ChatPlayerHeadsHook.isShowPlayerHeadsActive()`, `headBeforeName()`)

Needs a render-time hook (not a text-mutation hook, since it draws a face
icon, not text) at the same `ChatHud`/`ChatComponent` message-render call,
plus a way to resolve the sender's skin texture.

**Skin-resolution findings (per the user's decision to reuse vanilla's
existing skin-resolution API rather than invent new logic):** vanilla
already resolves player skins for exactly this "draw a small player face"
use case in its tab list, and this pass located that API via official
Yarn/Mojmap javadoc (web-fetched from `maven.fabricmc.net`/NeoForge
javadoc mirrors, not a literal `javap` run against this repo's jars — see
Methodology limitation above):

- **1.21.11 (Yarn)**: `net.minecraft.client.gui.PlayerSkinDrawer` (package
  `net.minecraft.client.gui`), confirmed present with this exact name and
  package in the **1.21.11+build.4** Yarn javadoc (one point release above
  this repo's pinned `1.21.11+build.6`, so treated as high-but-not-
  identical-build confidence pending literal `javap`). Overloads found:
  - `draw(DrawContext context, SkinTextures textures, int x, int y, int size)`
  - `draw(DrawContext context, SkinTextures textures, int x, int y, int size, int color)`
  - `draw(DrawContext context, Identifier texture, int x, int y, int size, boolean hatVisible, boolean upsideDown, int color)`
  `SkinTextures` (package `net.minecraft.client.util`) is the record
  vanilla's own tab list and player renderers already use; it is obtained
  per-player via `AbstractClientPlayerEntity.getSkinTextures()` (on the
  in-world player entity, if present) or `PlayerListEntry.getSkinTextures()`
  (on the tab-list entry, which exists for every connected player
  regardless of render distance — the more reliable source for a chat
  message, which may reference a player not currently in view).
  **Confidence: high (javadoc-confirmed for an adjacent build, not this
  repo's exact jar — literal `javap` still required before implementation).**
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.gui.components.PlayerFaceRenderer`
  is vanilla's Mojmap equivalent (confirmed via NeoForge javadoc mirror for
  1.21.x-neoforge, plus a NeoForge 1.21.2 migration primer explicitly
  documenting the rename from the older `PlayerSkinTexture`-based API to
  this class). Overloads found: `draw(GuiGraphics, PlayerSkin, int, int, int)`
  (no color arg) and other overloads taking a `ResourceLocation` +
  additional `int` args including color. `PlayerSkin` is Mojmap's
  equivalent record to Yarn's `SkinTextures`, obtained the same way via
  `AbstractClientPlayer`/`PlayerInfo` (Mojmap names for
  `AbstractClientPlayerEntity`/`PlayerListEntry`). **Confidence: medium**
  — confirmed via a NeoForge (not vanilla Fabric) javadoc mirror and a
  migration-primer summary rather than a version-matched official Mojmap
  reference for 26.1/26.2 specifically, and **not** confirmed this exact
  shape still holds on 26.1/26.2 (this class/record pairing was introduced
  around 1.21.2; 26.1/26.2 are far enough ahead that names/overloads could
  have shifted again). **javap-blocked** for this repo's exact jars.
- **Sender-to-skin resolution for an arbitrary chat line**: the still-open
  part is not "how do I draw a face given a `SkinTextures`/`PlayerSkin`"
  (now answered above) but "given an arbitrary `ChatHudLine`/chat message,
  how do I get the sender's `PlayerListEntry`/`AbstractClientPlayerEntity`
  in the first place." Signed player chat messages carry a sender
  `UUID`/profile in vanilla's `SignedMessage`/`MessageSignatureData` chain
  (Yarn) or `PlayerChatMessage` (Mojmap), which can be looked up against
  the client's `PlayerListEntry` map (`ClientPlayNetworkHandler`/
  `ClientPacketListener`'s player-info map) to get a `SkinTextures`/
  `PlayerSkin`. System messages, command feedback, and other non-player
  chat have no sender UUID and should simply not draw a head — this is a
  design default, not a vanilla API gap, and is a reasonable, low-risk
  default answer to the deferred open question below.
- **Divergence**: same `hud.*`->`components.*` package rename as T3, plus
  the `PlayerSkinDrawer`/`PlayerFaceRenderer` and `SkinTextures`/`PlayerSkin`
  naming pairs above.
- **Classification: risky/bigger** — the render-side mixin itself is
  moderate and the skin-draw API is now identified with reasonable
  confidence, but the sender-identity-to-`PlayerListEntry` plumbing (looking
  up the signed message's sender UUID against the client's player-info map
  at render time, or capturing it at message-receive time instead) still
  needs to be designed, and the 26.1/26.2 `PlayerFaceRenderer` signature
  needs literal `javap` confirmation before implementation.

---

### T5 — Custom Crosshair (`CustomCrosshairHook.isCustomCrosshairActive()`)

- **1.21.11 (Yarn)**: `net.minecraft.client.gui.hud.InGameHud.renderCrosshair(net.minecraft.client.gui.DrawContext, net.minecraft.client.render.RenderTickCounter)` — vanilla's own crosshair draw call; when the hook is active, cancel this and draw a custom crosshair using `outline`/`gap`/`length`/`thickness`/`centerDot`/`colorR`/`G`/`B` (all already read directly from `TweakRegistry` per `CustomCrosshairHook`'s own Javadoc, lines 6-10 — no new hook methods needed). **Confidence: high** (`InGameHud.renderCrosshair` is a long-stable, frequently-modded Yarn target).
- **26.1/26.2 (Mojmap)**: unresolved this pass. A web-search attempt for the current Mojmap crosshair render entry point did not turn up a single confirmed method name/shape for this repo's pinned 26.1/26.2 builds — 26.x's broader render-state-extraction refactor (documented in `.claude/context/minecraft.md` row 66/96) means the crosshair draw may live behind an `extractCrosshair`/similarly-named state-extraction method rather than an immediate-draw method like 1.21.11's. **Confidence: low, javap-blocked** — this specific target needs a literal `javap -p net/minecraft/client/gui/Gui` (and any `*RenderState`/extractor class it delegates to) run before any mixin design; do not assume it mirrors 1.21.11's shape.
- **colorMode finding (R4)**: re-confirmed via reading `ConfigSchemas.java`
  lines 48-52 and `TweakHooksImpl` — `colorMode` is still not read by any
  hook or `TweakHooksImpl` method, and `CustomCrosshairHook`'s Javadoc
  (lines 4-11) explicitly documents this as an intentional reduced-v1-scope
  gap ("No confirmed call site reads colorMode... shipping only the default
  value"), not a bug. The config screen ships only a single enum option
  (`VANILLA`) so the widget is a safe no-op today. **Still true — no new
  call site was found in this pass.** If `colorMode` is meant to support
  e.g. a "dynamic" (target-based) crosshair color, that needs a genuinely
  new design (what determines the color — block/entity under crosshair?
  health? sneaking state?), which is out of scope for a pure hooks-wiring
  batch.
- **Classification: safe/small** for the outline/gap/length/thickness/
  centerDot/RGB rendering itself on 1.21.11 (single render-cancel-and-
  redraw mixin, reuses existing config reads) — but **risky/low-confidence
  specifically on 26.1/26.2's exact target method**, unresolved even after
  the web-search re-verification pass, given the extraction-model
  divergence pattern already seen elsewhere in this repo. The 26.x side
  should be treated as needing its own dedicated `javap` investigation
  before this tweak is batched.

---

### T6 — Disable Animations (`DisableAnimationsHook.shouldAnimate(animatedTextureId)`)

Vanilla animates textures via `SpriteContents.Ticker`/`Animation`-driven
per-frame sprite updates during texture-atlas upload, not per-render — the
established mixin target is the per-sprite tick method.

- **1.21.11 (Yarn)**: `net.minecraft.client.texture.SpriteContents$Ticker.tick()` (an inner class of `SpriteContents`) — the per-sprite-instance animation advance called once per client tick from `SpriteAtlasTexture`/`Sprite.tick()`. **Confidence: medium, javap-blocked** (the `Ticker` inner-class shape is a well-known, long-stable Yarn pattern for "disable texture animation" mods; a web-search re-verification this pass did not turn up a version-matched javadoc page confirming the exact inner-class name/nesting for this repo's pinned 1.21.11 build, so this remains an educated-guess-with-precedent, not a confirmed finding).
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.renderer.texture.SpriteContents$Ticker.tick()` (or `TextureAtlasSprite.cycleAnimationFrames()`) — Mojmap equivalent shape. **Confidence: low, javap-blocked**, same caveat, and this side has more historical churn.
- **animatedTextureId resolution**: the hook takes a `String` id
  (presumably the sprite's `Identifier`/texture path) — the ticker itself
  needs a way to look up its owning sprite's id (`SpriteContents.getId()`
  or similar) from inside the injected method; needs `javap` to confirm a
  usable accessor exists (`@Shadow`-able field/method) from the `Ticker`
  inner class back to its enclosing `SpriteContents`.
- **Divergence**: naming/package only expected (`client.texture.*` vs
  `client.renderer.texture.*`), no structural relocation expected, but
  unconfirmed.
- **Classification: risky/bigger** — the inner-class mixin shape (`Ticker`
  nested inside `SpriteContents`) is more delicate than a flat top-level
  method target (Sponge Mixin inner-class targeting has its own syntax/
  pitfalls), and the id-resolution-from-ticker-instance path is unconfirmed
  and specifically flagged as needing a literal `javap -p -c` disassembly
  (not just a signature list) to find a usable field/accessor.

---

### T7 — Disable Particles (`DisableParticlesHook.shouldSpawnParticle(particleTypeId)`)

- **1.21.11 (Yarn)**: `net.minecraft.client.world.ClientWorld.addParticle(net.minecraft.particle.ParticleEffect, double, double, double, double, double, double)` (and the "always spawn" overload with a `boolean force` — vanilla has two: a client-settings-respecting one and a force one) — the single client-side entry point essentially all particle spawns funnel through, cancellable at entry with `particleTypeId` derived from `ParticleEffect.getType()`'s registry id. **Confidence: high** (`ClientWorld.addParticle` is a very well-established, frequently-modded Yarn target for exactly this purpose).
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.multiplayer.ClientLevel.addParticle(net.minecraft.core.particles.ParticleOptions, double, double, double, double, double, double)` — Mojmap equivalent, same two-overload (respects-settings vs force) shape. **Confidence: high.**
- **Divergence**: naming only (`ClientWorld`/`ParticleEffect` vs
  `ClientLevel`/`ParticleOptions`), consistent with the general World->Level
  rename family already documented multiple times in
  `.claude/context/minecraft.md`.
- **Classification: safe/small** — single well-known choke-point method per
  platform, single obvious cancellable `@Inject` at method entry, id
  derivation from the particle-type registry is a standard registry
  lookup, reuses existing `modeExcludes` logic verbatim.

---

### T8 — Hide Player Names (`HidePlayerNamesHook.shouldHideName(distanceToPlayer)`)

- **1.21.11 (Yarn)**: `net.minecraft.client.render.entity.PlayerEntityRenderer.renderLabelIfPresent(...)` (inherited from `LivingEntityRenderer`/`EntityRenderer.renderLabelIfPresent`) — the nametag draw call, already receiving the entity and camera distance is derivable from `entity.squaredDistanceTo(camera.getPos())` or similar. **Confidence: high** (`renderLabelIfPresent` is one of the most commonly mixin-targeted vanilla methods in the entire modding ecosystem, extremely stable name across versions).
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.renderer.entity.EntityRenderer.renderNameTag(...)` — Mojmap's equivalent (note: Mojmap's name diverges more than usual here, `renderLabelIfPresent` vs `renderNameTag` is a genuine name difference, not just a rename-of-same-word). **Confidence: high**, same "extremely common target" caveat applies — but the exact word should still be `javap`-confirmed since Mojang has renamed this specific method across versions in the past (it was `renderNameTag` for some releases, `renderNametag` in others).
- **distanceToPlayer**: computed inside the injected handler from
  `entity.position()`/`.getPos()` vs the local player's camera position —
  no plumbing needed beyond what the render method already has in scope.
- **Classification: safe/small** — one of the most standard, best-precedented mixin targets in the entire Fabric modding ecosystem; single cancellable `@Inject` at method entry.

---

### T9 — Clear Water (`ClearWaterHook.underwaterOverlayOpacityMultiplier()`)

- **1.21.11 (Yarn)**: `net.minecraft.client.gui.hud.InGameHud.renderUnderwaterOverlay(...)` (called from `InGameHud.render(...)`) — draws the blue tint/vignette when the camera is submerged; the alpha/opacity value fed into the overlay's color draw is the target. **Confidence: high** (`renderUnderwaterOverlay` is a long-stable, well-known Yarn name, same modding-ecosystem precedent as T8).
- **26.1/26.2 (Mojmap)**: unresolved this pass — a web-search re-verification attempt did not find a version-matched, confidently-current method name; this name has historically alternated between a dedicated `renderUnderwaterOverlay`-equivalent and a shared generic `renderTextureOverlay(Identifier, float)` used for multiple overlays (underwater, pumpkin head, powder snow). **Confidence: low, javap-blocked** — needs a literal `javap -p net/minecraft/client/gui/Gui` (or wherever the render-state-extraction refactor moved this) run to pick the real target and confirm it is not shared with unrelated overlays.
- **Classification: safe/small** if the dedicated-method form is confirmed
  (single obvious alpha multiply); **risky/bigger** if it turns out to be
  folded into a shared generic overlay method on 26.x, since the mixin
  would then need to discriminate "is this specifically the underwater
  overlay" before applying the multiplier. Flag as **conditionally risky,
  javap-blocked** — this is the literal first thing implementation should
  resolve for this tweak's 26.x side.

---

### T10 — Disable Cosmetics (UI-only follow-up; dropped from this batch's mixin/gameplay scope)

**Per the user's explicit decision, T10 is out of scope for this
implementation batch's mixin/gameplay wiring entirely.** No cosmetics
renderer is designed here, and none should be designed as part of
implementing this spec's other 10 tweaks.

A repo-wide search (`platform/**/*.java`, all 3 modules) for any
`FeatureRenderer`/entity-layer/cosmetic-on-player-entity rendering code
found none — the only place this mod currently draws Wardrobe cosmetics is
`WardrobePanel` (`platform/*/src/main/java/de/lazuli/mainmenu/WardrobePanel.java`),
a main-menu preview widget, not an in-world `PlayerEntityRenderer`/
`LivingEntityRenderer` feature layer. `DisableCosmeticsHook`'s own Javadoc
(lines 3-7) already frames this as "gates this mod's own Wardrobe renderer,
not a vanilla one" — but that renderer does not exist yet.

**Re-scoped task for this batch (UI-only, not a mixin task):** in the
Tweaks config screen, the Disable Cosmetics tweak's row/toggle should show
a disabled/hint state instead of a normal enable toggle — e.g. rendered as
disabled with hint text such as "Coming soon — requires an in-world
cosmetics renderer that doesn't exist yet." This is pure config-screen UI
work in the same module that already renders the Tweaks tab
(`features/main-menu`/`platform/*` main-menu screen code, per the existing
Tweaks tab implementation) — no `TweakHooksImpl`/`DisableCosmeticsHook`
logic changes, no mixin, no `lazuli.mixins.json` entry.

- **Classification: N/A (not a mixin-wiring task).** This is a UI-only
  follow-up, not classified safe/risky in the sense the other 10 tweaks
  are — it belongs in this batch's plan only as a small, isolated UI task,
  independent of and much lower-risk than any of the mixin work above. The
  actual in-world cosmetics renderer remains tracked as a separate future
  feature (see Future Extensions).

---

### T12 — Disable Boss Bars (`DisableBossBarsHook.shouldHideBossBar(bossBarName, isRaidBar)`)

- **1.21.11 (Yarn)**: `net.minecraft.client.gui.hud.BossBarHud.render(net.minecraft.client.gui.DrawContext)` (iterates `ClientBossBarManager`'s active `ClientBossBar` entries) — a mixin can `@Inject` inside the per-entry render loop (or `@Redirect`/`@ModifyVariable` the iteration) to skip rendering entries whose name/type match. Name text via `BossBar.getName()`/`ClientBossBar`'s own text field. **Confidence: medium, javap-blocked** for the render method itself — a web-search re-verification pass did not turn up anything to raise this above the prior pass's rating.
- **`keepRaidBarsVisible` / raid-bar detection**: vanilla raid bars
  conventionally use `BossBar.Color.RED`, but this is a heuristic, not a
  guaranteed unique flag — a web search this pass specifically for a
  dedicated "is this boss bar a raid bar" vanilla API did not find one;
  vanilla's raid-warning boss bar is created server-side by the `Raid`
  class and sent down as a generic boss-bar packet with no client-visible
  "this is a raid" tag beyond color/name-text conventions. **No call site
  exists for a clean raid-bar boolean — confirmed absent, not just
  unconfirmed.** Any `keepRaidBarsVisible` implementation will need a
  name-text and/or color-based heuristic (e.g. match vanilla's raid bar's
  known translation key / red color) rather than a real flag; this is a
  design decision to make explicitly during planning, not a `javap` gap to
  close later.
- **26.1/26.2 (Mojmap)**: `net.minecraft.client.gui.components.BossHealthOverlay.render(...)` — Mojmap equivalent (`LerpingBossEvent`/`ClientBossEvent` for the per-bar data). **Confidence: medium, javap-blocked**, same raid-flag-absence finding applies identically (server-side `Raid` class behavior is shared logic, not per-mapping).
- **Classification: safe/small** for the whitelist/blacklist/all-mode
  hiding (reuses `modeExcludes` verbatim, single render-loop skip); **the
  `keepRaidBarsVisible` sub-feature is risky** — now confirmed (not just
  suspected) to have no clean vanilla "is raid bar" flag, so it needs a
  name/text/color-based heuristic by design, which the planning phase
  should treat as a real design decision rather than a research gap.

---

## Findings — Zoom (T11) configurable audit

Per the user's task 2 instruction, this is a correctness check of *existing*
code, reported as findings, not new requirements.

- **`holdToZoom`**: read correctly. `ZoomTicker.register(...)`
  (`platform/*/src/main/java/de/lazuli/tweaks/ZoomTicker.java`, lines 20-34,
  identical logic across all 3 platforms) reads it via
  `hooks.holdToZoomConfigurable()` every tick and branches hold-vs-toggle
  behavior correctly, defaulting to `true` if the configurable is missing/
  wrong type.
- **`magnification`**: read correctly. `TweakHooksImpl.applyFov(float)`
  (lines 219-227 of the 1.21.11 copy, identical on 26.1/26.2) reads it via
  `state(TweakId.ZOOM).configurable("magnification")`, defaults to `4.0f`,
  and divides the base FOV by `max(1, magnification)` — matches
  `tweaks-zoom-fov.md`'s own documented behavior exactly, no bug found.
- **`transition` / `transitionDurationMs`**: defined in both
  `ConfigSchemas.java` (lines 84-90) and `TweakDefinitions.java` (lines
  102-106, defaults `true` / `150.0`) and exposed in the Tweaks config
  screen, but **read by nothing** — grepped `TweakHooksImpl`,
  `ZoomTicker`, and `ZoomHook` for `"transition"`/`"transitionDurationMs"`,
  no match anywhere in any of the 3 platform modules. `applyFov` is an
  instant magnification swap today (matches `tweaks-zoom-fov.md`'s own
  Non-goals, "No smooth zoom-in/zoom-out transition... not in scope"). This
  is a config-screen field with no backing behavior — not a bug introduced
  by this pass, but worth surfacing since these two fields were confirmed
  present in the current config schema and confirmed unread.
- **`scrollToAdjust`**: same situation — defined, defaulted `true`, exposed
  in the config screen, **read by nothing**. No scroll-wheel input handling
  for Zoom exists anywhere in `ZoomTicker`/`TweakHooksImpl`.
- **Conclusion**: no bug in what *is* implemented (holdToZoom/magnification
  are both correct); `transition`/`transitionDurationMs`/`scrollToAdjust`
  are three genuinely dead config fields — cosmetic to the user today (the
  widgets themselves render/persist fine, they just have zero gameplay
  effect), consistent with `tweaks-zoom-fov.md`'s own explicit Non-goals
  already scoping the transition/scroll-adjust features out. Left as-is
  per the prior pass; not reopened by this revision (the user's course
  correction did not touch this item).

## UI

None for the 10 gameplay-hook tweaks — no config-screen or Tweaks-tab UI
changes; their UI already exists and is unaffected by this spec.

**T10 Disable Cosmetics is the one exception**: this batch should include
a small UI change to the Tweaks config screen showing that tweak's row as
disabled with hint text (see T10 section above). No other UI changes.

## Configuration

No new configurables. All configurable fields referenced above already
exist in `ConfigSchemas`/`TweakDefinitions` and are already read (or, per
the Zoom findings above, already known-and-scoped-out as unread) by
existing `TweakHooksImpl` code.

## Events

None — no new event bus/lifecycle hooks; each tweak's eventual wiring is a
plain Sponge Mixin `@Inject`/`@Redirect`/`@ModifyVariable`, matching this
repo's existing convention (`tweaks-zoom-fov.md` Requirements R4 — no
mixinextras).

## Networking

None directly — T3 Chat Filter and T4 Chat Player Heads both touch chat
message *handling*, but per their existing hook contracts operate on
already-received client-side text/render state, not on the network layer
itself. No packet-format changes. T4's sender-identity resolution (see T4
section) reads already-received `PlayerListEntry`/signed-message data, it
does not add or change any packet.

## Persistence

None — no change to `tweaks.json` schema or `TweaksConfigIO`.

## Compatibility

- Per-tweak platform divergence is called out individually above; the
  general pattern established by `tweaks-zoom-fov.md` (1.21.11 = Yarn,
  frequently the more stable/precedented modding-target names; 26.1/26.2 =
  Mojmap, occasionally structurally different from 1.21.11 and sometimes
  even from each other per the render-state-extraction rows in
  `.claude/context/minecraft.md`) is expected to recur.
- 26.1 vs 26.2 are expected to be identical for most of these targets
  (matching Zoom's own finding that `Camera`'s method set was byte-for-byte
  identical on both), but this is **not** verified for any of the 11 tweaks
  in this pass and must be confirmed per-tweak by whichever `javap` pass
  precedes its implementation.

## Performance

Not assessed per-tweak in this research pass — each of these mixin targets
(particle spawn, name-tag render, lightmap update, boss-bar render, chat
message add) is a hot per-frame/per-tick call site, so the implementation
phase should apply the same "cheap branch + existing `TweakRegistry` lookup,
no allocation" discipline `tweaks-zoom-fov.md`'s Performance section
documented for Zoom's FOV mixin.

## Future Extensions

- A real in-world cosmetics render layer (prerequisite for T10's actual
  gameplay wiring, now fully deferred out of this batch — see T10 above)
  is the clearest concrete future-feature candidate surfaced by this pass.
- T4's sender-identity-resolution design (mapping an arbitrary chat line
  back to a `PlayerListEntry`, defaulting to "no head for unsigned/system
  messages") may be worth confirming as a written design note once
  implementation starts, even though this spec proposes a reasonable
  low-risk default.
- Zoom's `transition`/`transitionDurationMs`/`scrollToAdjust` dead fields
  (see Findings) could become their own small follow-up spec once/if the
  user wants that behavior for real, matching `tweaks-zoom-fov.md`'s own
  Future Extensions note that they'd live entirely inside
  `TweakHooksImpl.applyFov()`/`ZoomHook` with no mixin changes needed.
- A dedicated `javap`-equipped research pass (run in an agent invocation
  with actual shell/Bash tool access) should re-verify every item flagged
  **javap-blocked** above before its tweak is implemented — see Open
  Questions below.

## Findings — javap verification pass (main-thread, real jars, 2026-07-27)

Per the user's decision, the `javap-blocked` items above were re-run for real
against this repo's own resolved jars (`javap -p` on classes extracted from
the exact `.gradle/loom-cache` jars listed above). **Several of the earlier
web-search-sourced guesses were wrong** — corrected findings below supersede
the per-tweak sections above wherever they conflict; the per-tweak sections
are left as-is for narrative history but implementation must use this table.

- **T1 Anti-Drop**: `ClientPlayerInteractionManager` has no `dropItem`
  method at all on 1.21.11. The real target is
  `net.minecraft.client.network.ClientPlayerEntity.dropSelectedItem(boolean)`.
  Mojmap 26.1/26.2: `MultiPlayerGameMode` has no drop method either — the
  real target is `net.minecraft.client.player.LocalPlayer.drop(boolean)`
  (confirmed identical on 26.1 and 26.2). Both sides now confirmed **high
  confidence** — this downgrades T1 from "risky, unconfirmed method name"
  to **safe/small** (single well-known entry point per platform, same
  `(boolean)` shape as the hook's `shiftHeld` param suggests, e.g. `drop`
  taking "drop full stack" bool).
- **T5 Custom Crosshair, 1.21.11**: confirmed as guessed —
  `InGameHud.renderCrosshair(DrawContext, RenderTickCounter)`, private,
  direct render call. **High confidence, confirmed.**
- **T5 Custom Crosshair, 26.1**: confirmed structurally different as
  predicted — `Gui` has no `renderCrosshair`; the target is
  `Gui.extractCrosshair(GuiGraphicsExtractor, DeltaTracker)`, part of the
  render-state-extraction model (matches `.claude/context/minecraft.md`'s
  documented pattern). A mixin here needs to inject into the *extraction*
  step and modify what gets written to the extracted render state, not
  cancel/redraw like 1.21.11.
- **T5 Custom Crosshair, 26.2**: **new divergence found, not predicted by
  the spec** — 26.2's `Gui` class no longer contains `extractCrosshair` at
  all. The crosshair/overlay/boss-bar extraction methods (`extractCrosshair`,
  `extractTextureOverlay`, `extractBossOverlay`, etc.) moved to a **new
  class**, `net.minecraft.client.gui.Hud`, which 26.2's (now much smaller)
  `Gui` class holds as a `public final Hud hud` field. **26.1 and 26.2 are
  NOT identical here** — contradicts the spec's general assumption
  (Compatibility section) that 26.1/26.2 usually match. The mixin target
  class differs: `Gui` on 26.1, `Hud` on 26.2, even though method
  names/signatures inside are otherwise identical between the two.
- **T9 Clear Water, all 3 platforms**: no dedicated "underwater overlay"
  method exists anywhere. All three platforms funnel water/portal/
  spyglass/nausea overlays through one shared method: 1.21.11 —
  `InGameHud.renderOverlay(DrawContext, Identifier, float)`; 26.1 —
  `Gui.extractTextureOverlay(GuiGraphicsExtractor, Identifier, float)`;
  26.2 — `Hud.extractTextureOverlay(...)` (same class-split as T5). This
  **confirms the "shared generic overlay method" risk case was correct on
  all 3 platforms, not just 26.x as originally guessed** — a mixin must
  discriminate by the `Identifier` argument (the water-overlay texture id)
  to avoid also suppressing portal/spyglass/nausea overlays. Reclassify
  T9 as **risky/bigger on all 3 platforms** (was "safe on 1.21.11,
  uncertain on 26.x").
- **T8 Hide Player Names**: `renderLabelIfPresent` does not exist on any of
  the 3 platforms as a directly-cancellable render call. 1.21.11's actual
  method is `EntityRenderer.renderLabelIfPresent(S, MatrixStack,
  OrderedRenderCommandQueue, CameraRenderState)` (name matches the spec's
  guess, but the signature is render-command-queue-based, not an immediate
  draw — 1.21.11 already uses a render-state/command-queue model for this
  specific call, separate from the general 26.x extraction pattern). 26.1
  and 26.2 (identical between each other here) use
  `EntityRenderer.submitNameDisplay(S, PoseStack, SubmitNodeCollector,
  CameraRenderState[, int])` — a genuinely different method name from the
  guessed `renderNameTag`, plus a `protected boolean shouldShowName(T,
  double)` gate method that is likely the cleaner mixin target (cancel by
  returning `false` conditionally, rather than fighting the render/submit
  call). Reclassify: still **safe/small** (all targets are stable,
  purpose-built gate/render methods), but implementers must use
  `shouldShowName`/`submitNameDisplay`, not `renderLabelIfPresent`/
  `renderNameTag`.
- **T12 Disable Boss Bars**: 1.21.11's `BossBarHud.render(DrawContext)` and
  `renderBossBar(...)` confirmed as guessed, high confidence. 26.1's actual
  class is `BossHealthOverlay` (as guessed) but the method is
  `extractRenderState(GuiGraphicsExtractor)` plus private `extractBar(...)`
  overloads, not a `render(...)` method — same extraction-model correction
  as T5/T9. 26.2 confirmed identical to 26.1 for this specific class (no
  `Gui`/`Hud` split affects `BossHealthOverlay` itself, only the
  top-level Gui/Hud classes that own it — see T5 finding). `Raid`-based
  raid-bar-flag absence finding (from the prior pass) stands; not
  re-litigated here.
- **T6 Disable Animations**: the guessed `SpriteContents$Ticker` class does
  not exist on any platform. 1.21.11's real inner class is
  `SpriteContents$Animator` (not `Ticker`) with a public `tick()` method,
  confirmed. 26.1/26.2 (identical between each other) use a **two-class
  split**: `SpriteContents$AnimatedTexture` (immutable per-sprite animation
  definition, no tick method) produces a
  `SpriteContents$AnimationState` via `createAnimationState(...)`, and
  **`AnimationState.tick()`** (not `AnimatedTexture`) is the actual
  per-frame advance method — a real structural difference from 1.21.11's
  single-class `Animator`, not just a rename. `animatedTextureId` resolution
  (mapping a `Ticker`/`Animator`/`AnimationState` instance back to its
  owning sprite's `Identifier`) still needs a `javap -c` bytecode pass to
  find a usable `@Shadow`-able back-reference field, which this pass did
  not do (out of scope for a class/method identification pass). Stays
  **risky/bigger**, but the concrete target classes are now confirmed
  instead of guessed.
- **T4 Chat Player Heads**: 1.21.11's `PlayerSkinDrawer` confirmed exactly
  as guessed — `net.minecraft.client.gui.PlayerSkinDrawer.draw(DrawContext,
  SkinTextures, int, int, int[, int])` static methods, taking
  `net.minecraft.entity.player.SkinTextures` (package correction: the prior
  pass guessed `net.minecraft.client.util.SkinTextures`; the confirmed
  package is `net.minecraft.entity.player.SkinTextures`). **26.1/26.2: the
  guessed `PlayerFaceRenderer`/`PlayerSkin.draw(...)` API does not exist at
  all** — there is no such class in either jar. The real 26.x skin-render
  path is `net.minecraft.client.renderer.PlayerSkinRenderCache`, a
  profile-keyed async cache: `getOrDefault(ResolvableProfile)` /
  `lookup(ResolvableProfile)` return a `PlayerSkinRenderCache$RenderInfo`
  (texture + `RenderType`, obtained via `playerSkinRenderType(PlayerSkin)`),
  backed by `net.minecraft.client.resources.SkinManager`. This is a
  materially different (and more asynchronous/cache-oriented) design than
  1.21.11's synchronous static-draw call, and than the previously-guessed
  `PlayerFaceRenderer` API — **T4's 26.x skin-render integration must be
  redesigned around `PlayerSkinRenderCache`, not adapted from the 1.21.11
  approach.** `ChatHud.addMessage` (1.21.11) confirmed exactly as guessed,
  including the signed-message overload
  (`addMessage(Text, MessageSignatureData, MessageIndicator)`), which is a
  good candidate injection point for both T3 and T4 since it already
  carries signature data for sender resolution. T4 stays **risky/bigger**,
  arguably more so on 26.x than previously thought given the cache-based
  redesign; the 1.21.11 side is now fully confirmed/low-risk.

**Batch-ordering implication of these findings**: T1 moves from
risky-to-safe. T9 moves from split-safe to fully risky. T8 stays safe but
implementers must not copy the old method names. T5/T12 confirm the
extraction-model split is real and now also diverges 26.1-vs-26.2 (Gui vs
Hud), which the planning phase should treat as a 3-way (not 2-way)
per-platform branch for any tweak touching `Gui`'s former
crosshair/overlay/boss-bar responsibilities. Revised default grouping:
**safe-first**: T2, T7, T8, T1; **risky**: T3, T4, T6, T9, T12's raid-flag
sub-part; **split/needs its own investigation**: T5 (3-way class split);
**UI-only**: T10.

## Open Questions / Decisions for the User

Resolved by the user's course correction (kept here for traceability, not
as open items):

- T10 Disable Cosmetics: **resolved** — dropped from this batch's mixin
  scope, re-scoped to a UI-only disabled/hint state (see T10 above).
- T3 Chat Filter: **resolved** — substring replace/masking only, whole-
  message hiding is a non-goal (see Non-goals/R6).
- Batch sequencing: **resolved** — the existing safe-first/risky-last
  grouping stands as the recommended default. Concretely: **T2 Force
  Brightness, T7 Disable Particles, and T8 Hide Player Names** are the
  strongest "safe/small, ship first" candidates (all high-confidence,
  single well-known choke-point method per platform); **T1 Anti-Drop, T3
  Chat Filter, T4 Chat Player Heads, and T6 Disable Animations** are the
  strongest "risky, needs more research or a design decision first"
  candidates; **T5 Custom Crosshair and T9 Clear Water** are split (safe/
  confirmed on 1.21.11, javap-blocked/uncertain on 26.1/26.2); **T12
  Disable Boss Bars** is split (safe base hiding, confirmed-no-clean-API
  for `keepRaidBarsVisible`); **T10 is UI-only**, independent of and much
  lower-risk than all the above, and can ship any time. The planning phase
  should use this ordering as the default batch shape unless it finds a
  reason to deviate.
- T4 Chat Player Heads skin resolution: **partially resolved** — the
  actual "draw a head given a resolved skin" API is now identified
  (`PlayerSkinDrawer`/`SkinTextures` on Yarn, `PlayerFaceRenderer`/
  `PlayerSkin` on Mojmap, see T4 above), reusing vanilla's own tab-list
  skin machinery per the user's instruction. The remaining open piece —
  how exactly to resolve an arbitrary chat line back to a `PlayerListEntry`
  at render vs. receive time — has a proposed low-risk default (signed
  messages only, silently skip system/unsigned messages) rather than a
  confirmed vanilla API, since none exists for that specific lookup
  direction.

Still genuinely open:

1. **Resolved** — a real `javap -p` pass was run against this repo's own
   resolved jars on 2026-07-27 (see "Findings — javap verification pass"
   above). Every previously javap-blocked item now has a confirmed class/
   method, several of which corrected earlier web-search-sourced guesses
   (T1, T5, T8, T9, T12, T4's 26.x side, T6's class names). One residual
   gap: T6's `animatedTextureId`-to-owning-sprite back-reference still
   needs a `javap -c` bytecode-level pass (not just a signature list) to
   find a usable `@Shadow`-able field — flagged in the T6 finding above,
   scoped as an implementation-time task for whoever picks up T6, not a
   blocker for the other tweaks.
2. **T9 Clear Water and T12 Disable Boss Bars' "conditionally risky"
   sub-parts remain genuinely unresolved by research alone** (T9: shared
   vs dedicated overlay method on 26.x is javap-blocked; T12's
   `keepRaidBarsVisible` is now confirmed to have no clean vanilla flag, so
   it needs a heuristic design choice regardless of further research) —
   should these ship in the same batch as their tweak's safe majority
   behavior (with the risky sub-part simply deferred/disabled), or should
   the risky sub-part block the whole tweak from shipping? No new
   information changes this from an open sequencing/product question.
