# Implementation Plan: No Rain (T13) and Freecam (T14)

Ground truth: `docs/specs/tweaks-no-rain-freecam.md` (approved spec). That
spec's own "Methodology limitation" note applies directly to this plan too:
**no `javap` access was available while writing either the spec or this
plan.** Every vanilla class/method name below is carried over from the
spec's own confidence tiers (high/medium/low, see spec's "Methodology
limitation" section) and is **not** independently re-verified here. Per this
repo's established convention (`docs/specs/tweaks-hooks-wiring-plan.md`'s own
opening note, and that plan's "Findings — javap verification pass" precedent
in the prior spec), a real `javap -p`/`javap -c -p` pass against this repo's
own resolved jars is a **mandatory first implementation step for both
tweaks**, not an optional nice-to-have — several file names/targets below are
explicitly marked provisional pending that pass.

## Existing Implementation

Framework pieces this feature plugs into additively (spec Architecture —
Framework Fit; confirmed by direct read this pass, not just cited from the
spec):

- `api/src/main/java/de/lazuli/api/tweaks/TweakId.java:15-28` — the 12
  existing enum constants (`ANTI_DROP` … `DISABLE_BOSS_BARS`). No
  `KeyBinding`/Minecraft dependency in this module (spec Public API).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java` —
  one `of(TweakId, description, defaultConfigurables, hasSecondaryKeyBinding)`
  static field per tweak (lines 60-110) plus `ALL` (lines 112-115) and
  `byId(TweakId)` (117-124). Adding a tweak is: one new `of(...)` field, one
  new entry in the `ALL` list literal.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java` —
  a static `EnumMap<TweakId, List<ConfigFieldSpec>>` populated in a static
  initializer (one `ALL.put(TweakId.X, List.of(ConfigFieldSpec...))` block per
  tweak, lines 24-107), read via `fieldsFor(TweakId)` (line 110). Existing
  factory methods in use elsewhere in this file: `ConfigFieldSpec.bool(key,
  label)`, `.numeric(key, label, min, max, step)`, `.enumField(key, label,
  List<String> options)`, `.stringList(key, label)`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakRegistry.java` —
  `stateOf(TweakId)`, `setEnabled(TweakId, boolean)`, `setConfigurable(TweakId,
  String, Object)`. I/O-agnostic; construction iterates `TweakDefinitions.ALL`
  (lines 49-52), so a newly added definition is automatically backed by a
  default `TweakState` with no registry code changes.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java` —
  per its own Javadoc (spec-cited lines 32-36, not re-read this pass since
  spec already quotes it directly and no planning decision here turns on
  re-verifying it) a newly added `TweakId` is backfilled with default state
  automatically; unknown keys in the JSON file are ignored, not fatal
  (confirmed independently via
  `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`'s
  `unknownTweakIdInFileIsIgnoredNotFatal` test). No code changes needed.
- `platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`
  (byte-identical shape on `fabric-26.1`/`fabric-26.2`) — one class
  implementing every hook interface, reading straight off
  `TweakRegistry.stateOf(id)` with no caching, e.g. `isForceBrightnessActive()`/
  `minBrightness()` at lines 86-94. `NoRainHook`/`FreecamHook` will be added
  to this same class's `implements` clause plus their methods, same pattern.
  Non-hook-interface helper methods already exist on this class beyond the
  interface contract when a mixin needs extra state (e.g.
  `crosshairConfigurable(String)` at line 161, `setZoomActive(boolean)` at
  line 224) — precedent for Freecam needing a similar extra method for its
  auto-disable safety net (see Files to Modify).
- `platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/ZoomTicker.java` —
  the direct precedent for `FreecamTicker`: a static `register(TweaksKeyBindings,
  TweakHooksImpl)` method that registers one `ClientTickEvents.END_CLIENT_TICK`
  callback. Byte-identical shape on 26.1/26.2 except `KeyBinding.wasPressed()`
  (Yarn) vs `KeyMapping.consumeClick()` (Mojmap) — see
  `.claude/context/minecraft.md`'s "Known Cross-Version API Differences"
  table (cited, not re-read this pass).
- `platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/TweaksToggleTicker.java` —
  loops `TweakId.values()` except `ZOOM`, edge-triggering
  `registry.setEnabled(id, !registry.stateOf(id).enabled())` per spec
  Architecture — Framework Fit, `NO_RAIN` and `FREECAM` both get a working
  on/off hotkey from this loop with **no change to this file** (`FREECAM`'s
  continuous per-tick flight behavior is `FreecamTicker`'s separate concern,
  same split Zoom already has between this ticker's toggle and
  `ZoomTicker`'s hold/toggle state machine).
- `platform/fabric-1.21.11/src/main/java/de/lazuli/TweaksClientInitializer.java`
  — composition root; constructs `TweaksKeyBindings`, loads `tweaks.json`,
  constructs `TweakRegistry`, constructs `TweakHooksImpl`, then
  `TweakEngineHandoff.publish(hooks)`, `ZoomTicker.register(keyBindings,
  hooks)` (line 53), `TweaksToggleTicker.register(keyBindings, registry)`
  (line 54). `FreecamTicker.register(...)` is added here, same spot.
  Byte-identical shape on 26.1/26.2.
- `platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/TweakEngineHandoff.java` —
  static `require()`/`publish(TweakHooksImpl)` accessor mixins use to reach
  the live hooks instance (e.g. `ClientWorldDisableParticlesMixin`'s
  `TweakEngineHandoff.require().shouldSpawnParticle(id)`). Same accessor No
  Rain's mixins will use.
- **Mixin registration**: `platform/<module>/src/main/resources/lazuli.mixins.json`,
  one flat `mixins` array of simple class names, package fixed to
  `de.lazuli.mixin`, `injectors.defaultRequire: 1`,
  `overwrites.requireAnnotations: true`. Confirmed current lists on
  1.21.11 (20 entries) and 26.2 (20 entries, `compatibilityLevel: JAVA_25` vs
  1.21.11's `JAVA_21` — pre-existing, unrelated to this feature). Reference
  mixin shape (`ClientWorldDisableParticlesMixin.java`, T7 Disable Particles):
  package `de.lazuli.mixin`, `@Mixin(TargetClass.class) abstract class`,
  `@Inject(method = "...", at = @At("HEAD"), cancellable = true)` reading
  `TweakEngineHandoff.require().someHookMethod(...)` and calling `ci.cancel()`,
  helper logic factored into a `@Unique private static` method. Both No
  Rain's visual/sound mixins and Freecam's interaction/movement/show-body
  mixins should follow this exact shape.
- Jar paths for the mandatory `javap` pass (already resolved on disk, same
  paths `tweaks-hooks-wiring-plan.md` used):
  - 1.21.11 (Yarn): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-6dd721cd7d/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-6dd721cd7d-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
  - 26.1 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-a26c9a9f3c/26.1/minecraft-merged-a26c9a9f3c-26.1.jar`
  - 26.2 (Mojmap): `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`
- No existing platform-side unit tests for any tweak's mixin/ticker behavior
  (`platform/*/src/test/` has no `*Tweak*` test files, confirmed this pass) —
  `tweaks-hooks-wiring-plan.md`'s "no unit-testable seam without a full
  client environment" finding still holds; only `features/tweaks`' plain-Java
  layer (`TweaksConfigIOTest`, `TweakRegistryTest`) is unit-tested today.

## Files to Create

### Shared (`features/tweaks`, both tweaks, low risk — no javap needed)

- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/NoRainHook.java`
  — interface, exact shape per spec Public API (`isNoRainActive()`,
  `noRainIncludesSnow()`, `noRainIncludesSound()`).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java`
  — interface, exact shape per spec Public API (`isFreecamActive()`,
  `freecamMoveSpeed()`, `freecamSprintMultiplier()`, `freecamNoclip()`,
  `freecamShowOwnBody()`).

### Freecam platform classes (×3 platforms, camera-attachment part — low risk, confirmed API)

- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java`
  — the phantom camera object (spec Architecture recommendation): a custom
  `Entity` subclass, never `world.spawnEntity`/added to the world, driven
  manually once per client tick by `FreecamTicker`. Reduces
  `readNbt`/`writeNbt`/damage/interaction methods to no-ops. Exposes package-
  visible position/rotation integration + (when `noclip == false`) a call
  into its own **inherited** block-collision-sweep method — exact method
  name/signature is javap-blocked (spec Architecture, "Block collision for
  `noclip = false`"), so this file's body cannot be finalized before that
  javap step, only its shape (per-platform, since the base `Entity` class and
  its collision-sweep primitive's exact name must each be independently
  confirmed — spec Compatibility explicitly declines to assume 26.1/26.2
  identical without checking).
- `platform/<module>/src/main/java/de/lazuli/tweaks/FreecamTicker.java` —
  the `ZoomTicker`-precedent `ClientTickEvents.END_CLIENT_TICK` registration.
  Proposed signature `register(TweaksKeyBindings, TweakHooksImpl hooks,
  TweakRegistry registry)` (registry included, unlike `ZoomTicker`, so this
  ticker can call `registry.setEnabled(TweakId.FREECAM, false)` directly for
  the auto-disable safety net — mirrors `TweaksToggleTicker`'s existing
  `register(keyBindings, registry)` shape rather than adding a new
  `TweakHooksImpl` mutator method). Responsibilities: on toggle-on, construct
  one `FreecamCameraEntity` and call `MinecraftClient.setCameraEntity(...)`;
  each tick while active, read movement keybindings + the player's live yaw/
  pitch, integrate the phantom entity's position (via the collision-sweep
  primitive when `noclip == false`), apply `moveSpeed`/`sprintMultiplier`;
  detect the safety-net triggers (disconnect, respawn, dimension change,
  death — exact per-tick vanilla state to poll for each is not yet
  identified, see Risks) and force-disable via `registry.setEnabled` if any
  fire; on toggle-off (including forced), call
  `setCameraEntity(client.player)` and drop the phantom reference.

### Freecam mixins (×3 platforms each, all three genuinely javap-blocked — see Risks)

- `platform/<module>/src/main/java/de/lazuli/mixin/<TBD>FreecamMovementRoutingMixin.java`
  — gates the per-tick movement-key read that currently feeds
  `ClientPlayerEntity`/`LocalPlayer`'s own walk state, so WASD/jump/sneak
  reach `FreecamTicker`'s integration instead of the player while active
  (spec Architecture, "Movement-key routing" — the spec's own single
  highest-risk item for this tweak). Target class/method name: **unconfirmed,
  provisional filename only**.
- `platform/<module>/src/main/java/de/lazuli/mixin/<TBD>FreecamHideBodyMixin.java`
  — only needed to implement `showOwnBody == false` (the `true` default is a
  free side effect of `setCameraEntity`, per spec Architecture). Target:
  whatever "is this the entity the camera is focused on" check normally
  hides the local player's own body in first-person — **unconfirmed,
  provisional filename only**.
- `platform/<module>/src/main/java/de/lazuli/mixin/<TBD>FreecamSuppressInteractionMixin.java`
  — implements the spec's Non-goals default (block left/right-click
  interaction entirely while Freecam is active — see Open Questions below,
  this is the still-unconfirmed-by-user default). Target: wherever
  `MinecraftClient` processes the attack/use-item keys each tick (candidate
  shape `MinecraftClient.handleInputEvents()`/`doAttack()`/`doItemUse()`, not
  javap-verified this pass — **this exact target was not analyzed in the
  spec at all** and is a genuinely new risk item this plan is surfacing, see
  Risks).

### No Rain mixins (×3 platforms — split visual/sound, medium-to-fully-unconfirmed target)

- `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/WeatherRenderingNoRainMixin.java`
  — provisional name/target (`net.minecraft.client.render.WeatherRendering`,
  medium confidence per spec for the literal `1.21.11+build.6` pin, not yet
  javap-confirmed). Three `@Inject(..., cancellable = true)` methods: on
  `buildPrecipitationPieces(...)` and/or `renderPrecipitation(...)` (visual,
  gated by `NoRainHook.isNoRainActive()` and, when `noRainIncludesSnow() ==
  false`, a per-invocation biome-precipitation-type check — see Risks for the
  private-method accessor question) and on `addParticlesAndSound(...)` (sound
  + splash particles, gated additionally by `noRainIncludesSound()`).
- `platform/fabric-26.1/src/main/java/de/lazuli/mixin/<TBD>NoRainMixin.java` —
  **fully unresolved target class** per spec Architecture ("26.1/26.2 (Mojmap):
  unresolved this pass"); filename is a placeholder only, to be renamed to
  match whatever class the mandatory javap pass finds.
- `platform/fabric-26.2/src/main/java/de/lazuli/mixin/<TBD>NoRainMixin.java` —
  same caveat as 26.1; spec explicitly declines to assume 26.1/26.2 share a
  class here without independent confirmation (spec Compatibility).

## Files to Modify

- `api/src/main/java/de/lazuli/api/tweaks/TweakId.java` — add `NO_RAIN`,
  `FREECAM` constants (spec Public API).
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java`
  — one new `of(...)` field each for `NO_RAIN` (`map("includeSnow", true,
  "includeSound", true)`) and `FREECAM` (`map("moveSpeed", 1.0,
  "sprintMultiplier", 2.0, "noclip", true, "showOwnBody", true)`), both
  `hasSecondaryKeyBinding = false`; append both to `ALL`.
- `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java`
  — `ALL.put(TweakId.NO_RAIN, List.of(bool("includeSnow", ...), bool
  ("includeSound", ...)))`; `ALL.put(TweakId.FREECAM, List.of(numeric
  ("moveSpeed", ..., 0.1, 10.0, 0.1), numeric("sprintMultiplier", ..., 1.0,
  5.0, 0.5), bool("noclip", ...), bool("showOwnBody", ...)))` — ranges/steps/
  defaults exactly per spec Requirements T14.
- `platform/<module>/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java` (×3)
  — add `NoRainHook, FreecamHook` to the `implements` clause; add the 7
  trivial state-reading methods (3 for `NoRainHook`, 5 for `FreecamHook`,
  minus `isFreecamActive()` reused as `state(TweakId.FREECAM).enabled()`, no
  extra field needed — unlike Zoom, Freecam has no separate hold-vs-toggle
  state to track in this class, per spec's Non-goals removing
  `freezePlayer`). No new mutable fields expected on this class for either
  tweak (contrast with Zoom's `zoomActive`/`zoomFactor` fields, which exist
  only because of Zoom's hold/transition behavior that Freecam does not
  have).
- `platform/<module>/src/main/java/de/lazuli/TweaksClientInitializer.java`
  (×3) — add `de.lazuli.tweaks.FreecamTicker.register(keyBindings, hooks,
  registry);` alongside the existing `ZoomTicker.register(...)`/
  `TweaksToggleTicker.register(...)` calls (line ~53-54 today). No Rain needs
  no ticker registration (its mixins read `NoRainHook` state directly at each
  vanilla call site, no per-tick polling, per spec Events).
- `platform/<module>/src/main/resources/lazuli.mixins.json` (×3) — append
  each new mixin's simple class name once that file is actually created
  (batch-by-batch, matching `tweaks-hooks-wiring-plan.md`'s own sequencing
  note about not referencing not-yet-written classes).
- `features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`
  — extend `parseRoundTripsSerializedDefault` (or add a sibling assertion) to
  also cover `TweakId.NO_RAIN`/`TweakId.FREECAM` default-config round-tripping,
  matching the existing per-tweak-spot-check pattern already used for `ZOOM`/
  `ANTI_DROP` (small, low-risk, no new test infrastructure).

## Dependencies

No new external (non-Fabric) dependency for either tweak. Both are built
entirely on:

- Vanilla Minecraft classes already on each platform module's existing
  Yarn/Mojmap compile classpath (`net.minecraft.client.*` / `net.minecraft.world.entity.*`
  equivalents) — same classpath every other tweak's mixins already use, no
  `build.gradle` change.
- The existing Sponge Mixin setup (plain `@Inject`/`cancellable = true`, no
  MixinExtras — matching `tweaks-hooks-wiring-plan.md`'s explicit "keep it
  that way" convention, confirmed still true by this pass's mixin-file read).
- Existing Fabric API `ClientTickEvents.END_CLIENT_TICK` (already a
  dependency, used by `ZoomTicker`/`TweaksToggleTicker` today).

No Maven Central verification needed per this plan's own Dependencies
instruction (no new coordinate proposed). If implementation later needs a
genuinely new dependency (unlikely — the spec's own Architecture section
finds no such need), that requires the standard Maven Central check before
adoption, same as `tweaks-hooks-wiring-plan.md` flagged for its own
hypothetical case.

## Risks

- **No Rain's Mojmap (26.1/26.2) target class is fully unconfirmed** (spec
  Architecture, Compatibility) — the single largest risk in this plan. If no
  `WeatherRendering`/`WeatherEffectRenderer`-shaped dedicated class exists on
  26.x, the fallback (tracing whichever renderer class owns the weather
  field(s), per spec) is a materially different, larger mixin design than the
  clean two/three-method cancel this plan assumes for 1.21.11. Recommend
  running the 26.1 `javap` pass **before** starting 1.21.11 implementation,
  so if the fallback design is needed, it's discovered early rather than
  after 1.21.11's mixin is already written and reviewed.
- **No Rain's 1.21.11 target is also only medium confidence** for the exact
  `1.21.11+build.6` pin (spec: confirmed class shape as of Yarn
  1.21.9+build.1, not this literal pin; a real structural relocation from an
  older `WorldRenderer.renderWeather(...)` shape happened somewhere in the
  1.21.x line, per spec). A `javap` pass on 1.21.11's own jar is mandatory
  before writing this mixin, not just recommended.
- **`includeSnow = false`'s private-method problem** (spec Architecture): the
  natural rain-vs-snow discriminator (`getPrecipitationAt`) is `private`
  inside the target class. This repo has documented precedent for
  invoker/accessor pitfalls on non-public members (`.claude/context/minecraft.md`
  rows 82/85, the `AbstractSelectionList.Entry` saga, cited not re-read this
  pass). Implementation must pick one of the spec's two named alternatives
  (an `@Invoker` accessor, or duplicating the lookup via the public
  `Biome.getPrecipitationAt`/`getPrecipitation`-shaped API) at `javap` time,
  not guess in advance.
- **Freecam's movement-key routing target is fully unconfirmed** (spec
  Architecture's own "single highest-risk item" for this tweak) — same
  category of risk `tweaks-hooks-wiring-plan.md` flagged for Anti-Drop's
  `LocalPlayer.drop(boolean)` discovery, but with no prior candidate method
  name at all here (Anti-Drop's spec at least guessed the eventual real
  target; this spec has none).
- **Freecam's interaction-suppression target was never analyzed by the spec
  at all** — this plan is the first pass to name it as a required mixin (see
  Files to Create). It carries the same javap-blocked risk as movement-key
  routing but with even less prior investigation, and depends on the
  still-open Open Question 1 below (whether suppression is even the wanted
  final behavior). Recommend treating this as its own small spike separate
  from the movement-key-routing spike, since the two target different input
  paths (movement keys vs. attack/use keys) and may not share a choke point.
- **Freecam auto-disable safety-net triggers are unenumerated at the vanilla
  API level** (disconnect, respawn, dimension change, death — spec
  Requirements T14). `FreecamTicker`'s polling-based design (checking
  per-tick, since Freecam intentionally uses no new event type per spec
  Events) needs each of these four conditions mapped to something pollable
  from `MinecraftClient`/`ClientPlayerEntity` each tick (e.g. `client.world
  == null`, a dimension-changed comparison against the last-seen
  `RegistryKey<World>`, `client.player.isDead()`/health <= 0) — not
  javap-blocked in the same way as the other risks (these are all
  reasonably well-known accessors) but still unconfirmed and untested this
  pass; a missed trigger (e.g. respawn without a dimension change) would
  leave the camera detached, violating the spec's hard safety requirement.
- **`FreecamCameraEntity`'s safe-no-op method surface is a real spike, not a
  known quantity** (spec Open Questions #4): which `Entity` methods must be
  overridden to keep a never-world-added entity safe (avoiding NPEs from
  assumed-non-null world/registry state in inherited methods) is only
  discoverable by trial once the class skeleton exists.
- **General mixin-authoring risk applies to every target above once
  confirmed** (exact `@At` injection point, whether a target method is
  `final`, local-capture ordinals for `@ModifyVariable` if that shape ends up
  needed instead of plain `@Inject`) — same standing risk
  `tweaks-hooks-wiring-plan.md` already carries for its own batches.
- **Scope/size**: per spec's own Architecture classification, Freecam alone
  is larger and more failure-prone than any single tweak in the T1-T13
  catalog (3 javap-blocked mixin targets plus a new Entity subclass, versus
  every other tweak's 0-1 javap-blocked target). Recommend sequencing Freecam
  as its own implementation pass separate from No Rain, landing No Rain
  first (spec's own framing: "much smaller, better-precedented change").

## Test Strategy

No automated test harness in this repo covers mixin/tick-level gameplay
behavior (Existing Implementation) — same conclusion
`tweaks-hooks-wiring-plan.md` reached, still true. Both tweaks are
manual/in-game verification at the platform level, plus the small amount of
plain-Java logic that *is* unit-testable.

- **Unit-testable (features/tweaks only)**:
  - `TweaksConfigIOTest` extended per Files to Modify: round-trip
    `NO_RAIN`/`FREECAM` default state through `serialize`/`parse`, and a
    non-default value for at least one configurable each (mirrors existing
    `ZOOM`/`ANTI_DROP` spot checks).
  - Optional: a `TweakDefinitionsTest`/`ConfigSchemasTest` assertion that
    every `TweakId.values()` entry has a `ConfigSchemas.fieldsFor(id)` entry
    with no exception — cheap regression guard against forgetting to wire a
    new tweak's schema (no such test exists today; nice-to-have, not
    required by the spec).
- **No Rain, manual, per platform (×3)**:
  - Toggle on during an active rain storm: precipitation visual stops,
    ambient patter sound stops (when `includeSound = true`); toggle off
    restores both immediately with no residual state.
  - `includeSound = false`: visual suppressed, sound still plays.
  - `includeSnow = false` in a snowy biome during snowfall: snow still
    renders/sounds; rain in a rain biome is still suppressed (discriminator
    check).
  - **Lightning regression check, every platform, every configurable
    combination**: during an active storm with lightning, confirm the
    flash, the bolt entity, and the thunderclap sound all still occur
    exactly as vanilla — this is the spec's own explicit "must be manually
    verified in-game, not trusted from static analysis alone" requirement
    (spec Architecture), and the single most load-bearing manual check in
    this whole plan given the user's explicit framing that lightning must
    stay fully untouched.
- **Freecam, manual, per platform (×3)**:
  - Toggle on/off via hotkey and via the Tweaks tab checkbox with no hotkey
    bound (safety requirement).
  - While active: WASD+mouse-look flies the camera correctly; jump/sneak
    move it straight up/down; `moveSpeed` and `sprintMultiplier` (holding
    Sprint) visibly change speed; the real player's body is visible from
    outside when `showOwnBody = true` and hidden when `false`.
  - `noclip = true` passes through blocks; `noclip = false` collides with
    block geometry (walls/floor/ceiling) but still passes through every
    entity in both cases (spawn a mob/other player nearby and fly through
    it).
  - **Player-simulation-untouched check** (the spec's core invariant):
    while Freecam is active, confirm off-screen that the real player
    continues taking fall damage, drowning, being targeted/attacked by
    mobs, and losing hunger — e.g. stand the player over lava/in a mob's
    aggro range right before toggling Freecam on, and confirm the player's
    health/hunger still changes while the camera is detached and not
    looking at the player's body.
  - Each safety-net trigger individually: disconnect from a server,
    die, respawn, and change dimension (Nether portal) while Freecam is
    active — confirm the camera snaps back to the player in every case with
    no way to end up permanently detached.
  - Whatever the resolved answer to Open Question 1 turns out to be
    (interaction suppressed vs. allowed): confirm left/right-click behaves
    exactly as that resolution specifies while Freecam is active.

## Acceptance Criteria

- **No Rain** ships when: on all 3 platforms, toggling the tweak on
  suppresses rain/snow precipitation rendering and (when `includeSound`)
  its ambient sound, `includeSnow`/`includeSound` each independently behave
  per spec Requirements T13, lightning is manually confirmed fully unaffected
  on every platform, and toggling off restores stock vanilla behavior with no
  residual state.
- **Freecam** ships when: on all 3 platforms, the hotkey and Tweaks-tab
  checkbox both toggle a detached, free-flying camera with mouse-look
  identical to vanilla, WASD/jump/sneak fly the camera per spec Requirements
  T14, `moveSpeed`/`sprintMultiplier`/`noclip`/`showOwnBody` each behave
  exactly as configured, entity collision never applies to the camera
  regardless of `noclip`, the real player entity is confirmed still
  simulating normally (fall damage/drowning/AI/hunger) while Freecam is
  active, all four safety-net triggers reliably restore the camera, and the
  Tweaks-tab checkbox alone (no hotkey bound) can always disable it.
- **Overall**: both tweaks persist correctly through `tweaks.json` with zero
  `TweaksConfigIO`/schema-version code changes (per Existing Implementation),
  both render through the existing generic `TweaksPanel` UI with no
  `TweaksPanel` code changes, and no platform module ships either tweak
  silently inert (config UI present but no gameplay effect).

## Open Questions (carried from spec, not resolved by this plan)

1. **Freecam + block/entity interaction** (spec Open Question 1): this plan
   assumes the spec's stated default — interaction fully suppressed while
   Freecam is active — and plans a dedicated mixin for it (Files to Create).
   If the user instead wants raycasting to originate from the real player's
   position regardless of camera location, that is a materially different,
   larger mixin (a position override on the interaction raycast rather than
   an outright suppression) and would need its own follow-up planning pass,
   not a drop-in swap of this plan's proposed file.
2. **No Rain's 26.1/26.2 target class** (spec Open Question 2) — unresolved,
   mandatory first `javap` step, see Risks.
3. **Freecam's movement-key routing target** (spec Open Question 3) —
   unresolved, mandatory `javap` spike, see Risks.
4. **`FreecamCameraEntity`'s exact safe-no-op method surface** (spec Open
   Question 4) — unresolved, implementation-time spike, see Risks.
