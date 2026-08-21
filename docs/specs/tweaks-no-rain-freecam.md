# Spec: Two New Tweaks — No Rain (T13) and Freecam (T14)

Status: specification only (no plan, no implementation code in this document).
Extends the existing Tweaks catalog (`docs/specs/tweaks.md`, T1-T12, framework
already implemented and live — `features/tweaks/`, `platform/*/tweaks/`,
`platform/*/mixin/*` per `docs/specs/tweaks-hooks-wiring.md`'s "Findings —
javap verification pass" and the mixins actually present today, e.g.
`platform/fabric-26.2/src/main/java/de/lazuli/mixin/ClientLevelDisableParticlesMixin.java`).
This spec adds two more tweaks, `NO_RAIN` and `FREECAM`, to the same
`TweakId`/`TweakDefinition`/`TweakRegistry`/`TweaksConfigIO` framework with
**zero framework code changes required** (see Architecture — Framework Fit).

**Revision note:** the Freecam section below was revised after user review of
this document's original Open Questions — `freezePlayer` was removed
entirely (Freecam no longer affects the real player entity's simulation in
any way), block collision (`noclip = false`) is now a hard v1 requirement
with a concrete recommended implementation approach, and entity collision is
now explicitly, unconditionally never applied to the detached camera
regardless of the `noclip` setting. See "Open Questions / Recommendations"
at the end for the full resolution log. The No Rain (T13) section is
unchanged from the original pass.

### Methodology limitation — read before trusting class/method names below

This specification pass had **no shell/`javap` tool available** (agent
invocation had only `Read`/`Glob`/`Grep`/`Write`/`WebFetch`/`WebSearch`),
unlike `tweaks-zoom-fov.md`'s methodology. Per this repo's own established
precedent for that situation (`docs/specs/tweaks-hooks-wiring.md`'s "Findings
— javap verification pass" section, which corrected several of its own
first-pass guesses once real `javap` access became available), every class/
method name below is confidence-tiered: **high** = confirmed present with
this exact shape via official Yarn/Fabric javadoc across multiple adjacent
versions, or a long-stable, frequently-modded target; **medium** = plausible,
sourced from a version-adjacent javadoc but not this repo's exact pinned
build; **low/design-only** = a genuine open design decision, not a fact to
verify. **A real `javap -p`/`javap -c -p` pass against this repo's own
resolved jars (`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/**`,
paths already enumerated in `tweaks-hooks-wiring.md`) must be the mandatory
first step of implementing either tweak**, exactly as every other tweak in
this catalog required.

## Overview

Two new client-side-only Tweaks:

- **T13 No Rain**: suppresses the client-side rendering of rain/snow
  precipitation and its ambient sound loop, while leaving lightning
  (strikes, flashes, thunder) completely untouched. Purely visual/audio; does
  not change the world's actual (server-authoritative) weather state.
- **T14 Freecam**: a detached, spectator-style free-flying camera the player
  can toggle on/off, with configurable movement speed and a block-collision
  ("noclip") toggle. Freecam is **purely a camera-detachment feature**: the
  real player entity is never moved, never has its gamemode changed, never
  has its own simulation (gravity, physics, AI interactions, damage, etc.)
  paused or altered in any way, and never sends any extra network traffic —
  only the client's render camera and the routing of movement-key *input*
  are affected.

Both follow every existing tweak's shape: one `TweakId` enum constant, one
`TweakDefinition` in `TweakDefinitions`, one `ConfigFieldSpec` list in
`ConfigSchemas`, one Minecraft-agnostic hook interface in
`features/tweaks/.../services/`, one `TweakHooksImpl` implementation per
platform, and platform-specific mixins/tickers wiring the hook to real
vanilla call sites — see `docs/specs/tweaks.md` Architecture for the
already-established framework these two tweaks plug into unchanged.

## Goals

1. **No Rain**: while enabled, rain and snow precipitation (the falling
   streak/particle overlay) does not render, and the continuous ambient
   rain/snow "patter" sound loop does not play — but lightning bolts still
   spawn, strike, flash, and play their thunderclap sound exactly as vanilla
   already does, unaffected. Purely client-side: the actual server-tracked
   weather/rain state (`level.isRaining()`/rain gradient) is never read as
   something to suppress at the *source* — only the client's render/audio
   *presentation* of that state is gated, matching every other Tweak's
   "filter what's rendered, not what's simulated" philosophy
   (`docs/specs/tweaks.md` F7).
2. **Freecam**: a bound hotkey (consistent with F3's vanilla `KeyMapping`
   mechanism, same as every other tweak) toggles a free-flying camera
   detached from the player's body. While active: WASD (+ vanilla's jump/
   sneak keys for up/down) fly the camera using the same look direction the
   mouse already controls; movement speed and a sprint-key speed multiplier
   are both configurable; a `noclip` configurable controls whether the
   camera's own flight passes through **blocks** or collides with world
   geometry — **entity collision never applies to the detached camera
   regardless of `noclip`**, it always passes through entities. The real
   player entity is completely unaffected by any of this: it keeps ticking
   exactly as if Freecam were off (gravity, fall damage, drowning, AI
   targeting, knockback, etc. all continue normally) — the only thing that
   changes is that WASD/jump/sneak input is routed to the camera instead of
   to the player while Freecam is active. Disabling the tweak (hotkey,
   checkbox, or an automatic safety trigger — see Requirements T14) always
   cleanly returns the camera and all input handling to the real player
   entity, with no dangling state.

## Non-goals

- **No Rain** does not alter, cancel, or fast-forward the server-authoritative
  weather cycle itself (no `/weather clear` equivalent, no change to
  `level.getRainGradient()`/`isRaining()`), and does not suppress sky
  darkening, fog, or any other ambient effect a storm causes beyond the
  precipitation render + its dedicated ambient sound — e.g. the sky staying
  darker during a storm is left alone (see Future Extensions if a "fully
  clear sky" mode is ever wanted).
- **No Rain** does not touch lightning in any way — no lightning-entity
  spawn suppression, no flash/screen-brightness suppression, no thunder-clap
  sound suppression. This is an explicit, load-bearing requirement (user's
  own framing), not just an unaffected side effect.
- **Freecam** does not change the player's actual gamemode (no client-side
  or server-side `/gamemode spectator`), does not spawn or despawn any real
  (server-known) entity, and sends no extra network packets — it is a purely
  local render-camera + local-input change, consistent with every other
  tweak's client-only philosophy (`docs/specs/tweaks.md` Non-goals).
- **Freecam never affects the real player entity's own simulation in any
  way** — no gravity pause, no fall-damage immunity, no collision freeze, no
  "safe while detached" behavior of any kind. This is a hard invariant, not
  a configurable default: the player entity ticks exactly as it would with
  the tweak disabled, at all times, including while Freecam is active. (An
  earlier draft of this spec proposed a `freezePlayer` configurable to
  suspend the player's physics while detached — removed per explicit user
  decision; see Open Questions.)
- **Freecam** does not attempt to preserve normal left/right-click block/
  entity interaction (mining, placing, attacking, using items) while active.
  Vanilla's own interaction raycast originates from the camera's position
  (see Architecture), so leaving interaction live while the camera is
  detached would let the player remotely mine/attack from wherever the
  camera currently is — explicitly out of scope and, per the default in
  Requirements T14, suppressed while active. Flagged as an Open Question
  below in case the user wants different behavior.
- **Freecam**'s noclip only ever governs the camera's own *block* collision.
  It never adds a "noclip for the real player" mode (i.e. this is not a
  movement-cheat/anti-cheat-evasion feature for the player's actual in-world
  position) — the real player entity's own collision is entirely untouched,
  always, regardless of the `noclip` setting.
- No new framework primitives, no new persistence file, no networking of any
  kind for either tweak (matching `docs/specs/tweaks.md`'s F7/Non-goals).

## Requirements

### T13 — No Rain

- Behavior: while enabled, the falling rain/snow precipitation overlay does
  not render, and the ambient rain/snow sound loop does not play. Lightning
  (entity spawn, visual strike/flash, thunderclap sound) is completely
  unaffected — see Architecture for why targeting the specific vanilla call
  site(s) below naturally leaves lightning untouched (lightning is a real,
  separately-simulated entity with its own renderer/sound, not part of the
  precipitation-overlay code path).
- Configurables:
  - `includeSnow` (bool, default `true`): whether snowy-biome precipitation
    is also suppressed, not just rain-biome precipitation. Vanilla's own
    precipitation call site does not cleanly pre-separate "rain" from "snow"
    at the single choke point this tweak targets (see Architecture); when
    `false`, the mixin must additionally resolve the biome's precipitation
    type per invocation to distinguish the two (a real per-implementation
    cost, not free — see Performance/Architecture confidence notes).
  - `includeSound` (bool, default `true`): whether the continuous ambient
    precipitation sound loop is suppressed together with the visual
    suppression, or left alone (visual-only suppression, sound still plays).
- Default: off (like every other tweak).

### T14 — Freecam

- Behavior: bound hotkey (or the Tweaks tab checkbox) toggles the camera
  between "attached to the player" (vanilla default) and "detached,
  free-flying." While detached:
  - The camera's look direction follows the mouse exactly as it already
    does today (no new mouse-handling code — see Architecture, this is a
    deliberate simplification).
  - WASD moves the camera relative to that look direction (standard
    fly-style movement, matching vanilla creative-mode flight's own
    forward/strafe math); vanilla's jump and sneak keybindings move the
    camera straight up/down instead of their normal player actions. These
    keys are *redirected* to the camera, not duplicated to the player — see
    below.
  - The real player entity does not move, does not turn, and does not fire
    its own movement packets **as a direct result of Freecam's WASD/jump/
    sneak input being read by the camera instead** — but the player entity
    otherwise keeps simulating completely normally in the background:
    gravity, fall damage, fluid drag, knockback, AI/mob interactions, etc.
    all continue exactly as they would with Freecam off. Freecam changes
    where movement *input* goes, nothing about the player's own state.
  - Toggling the tweak off (hotkey, checkbox, or an automatic safety
    trigger — disconnect, respawn, world/dimension change, or death — always
    disables Freecam defensively even if the player never pressed the
    hotkey) restores the camera to the player entity and restores normal
    movement-key handling with no leftover state.
- Configurables:
  - `moveSpeed` (numeric, 0.1-10.0, step 0.1, default `1.0`): a multiplier
    on a baseline fly speed (baseline = vanilla creative-mode flight speed,
    a well-known stable constant — exact value confirmed at implementation
    time).
  - `sprintMultiplier` (numeric, 1.0-5.0, step 0.5, default `2.0`): extra
    speed multiplier applied while the vanilla Sprint keybinding is held,
    mirroring creative-flight's existing fast-fly convention (holding
    Sprint already speeds up creative flight in vanilla; Freecam reuses the
    same keybinding for the same purpose, not a new bind).
  - `noclip` (bool, default `true`): controls **block** collision only for
    the detached camera. When `true`, the camera's own flight ignores block
    collision entirely (passes through walls/floors/ceilings). When
    `false`, the camera's flight collides with block geometry like a solid
    object (cannot pass through walls) — real collision math, in scope for
    v1 (see Architecture for the recommended implementation approach).
    **Entity collision is unconditionally disabled for the camera in both
    cases** — the detached camera always passes through mobs, players, and
    every other entity regardless of `noclip`, since entity-vs-camera
    collision was never asked for and adds no value for a spectating
    camera.
  - `showOwnBody` (bool, default `true`): whether the player's own body
    renders at its real, current position while the camera is detached
    (default on, so you can see your own character from outside — see
    Architecture for why this is actually the zero-extra-mixin default
    given the recommended architecture) or stays hidden the way vanilla
    hides it in ordinary first-person view (`false`).
- Default: off (like every other tweak).
- Safety requirement: Freecam must never leave the client in a state where
  the real camera stays detached from the player with no way back (e.g. if
  the bound key gets unbound mid-session) — the Tweaks tab checkbox must
  always be able to disable it even with no hotkey bound, matching every
  other tweak's existing checkbox-always-works guarantee, plus the
  auto-disable-on-disconnect/respawn/dimension-change/death safety net above
  (this safety net is purely about not leaving the *camera* stranded; it has
  no bearing on the player's own simulation, which per the Non-goals above
  is never touched by this tweak in the first place).

## Public API

New types under `api/src/main/java/de/lazuli/api/tweaks/`: **none** — this
spec adds two `TweakId` enum constants only, no new API-module types (see
`de.lazuli.api.tweaks.TweakId`, `api/src/main/java/de/lazuli/api/tweaks/TweakId.java:15-28`).

```java
public enum TweakId {
    ANTI_DROP, FORCE_BRIGHTNESS, CHAT_FILTER, CHAT_PLAYER_HEADS, CUSTOM_CROSSHAIR,
    DISABLE_ANIMATIONS, DISABLE_PARTICLES, HIDE_PLAYER_NAMES, CLEAR_WATER,
    DISABLE_COSMETICS, ZOOM, DISABLE_BOSS_BARS,
    NO_RAIN, FREECAM   // <- new
}
```

New Minecraft-agnostic hook interfaces in `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/`
(same package/shape as `ClearWaterHook`/`ForceBrightnessHook`/`ZoomHook` —
`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ClearWaterHook.java`,
`.../ZoomHook.java`):

```java
public interface NoRainHook {
    /** @return true if rain/snow precipitation rendering should currently be suppressed. */
    boolean isNoRainActive();
    /** @return true if snowy-biome precipitation should also be suppressed (T13's includeSnow). */
    boolean noRainIncludesSnow();
    /** @return true if the ambient precipitation sound loop should also be suppressed (T13's includeSound). */
    boolean noRainIncludesSound();
}

public interface FreecamHook {
    /** @return true if Freecam is currently enabled and toggled on. */
    boolean isFreecamActive();
    /** @return the configured move-speed multiplier (T14's moveSpeed). */
    float freecamMoveSpeed();
    /** @return the configured sprint speed multiplier (T14's sprintMultiplier). */
    float freecamSprintMultiplier();
    /** @return true if the camera's own flight should ignore block collision (T14's noclip). Entity collision is never applied to the camera regardless of this value. */
    boolean freecamNoclip();
    /** @return true if the player's own body should render at its real position while detached (T14's showOwnBody). */
    boolean freecamShowOwnBody();
}
```

Deliberately primitives/booleans-only (no `Entity`/`Vec3d`/`Camera` types),
matching every existing hook interface's Minecraft-agnostic constraint (see
`TweakDefinition`'s own Javadoc, `api/src/main/java/de/lazuli/api/tweaks/TweakDefinition.java:17-35`,
for why `features/tweaks` and `:api` stay free of any Minecraft-jar
dependency). All of Freecam's actual position/rotation/phantom-entity state
lives entirely platform-side (Architecture), never in this interface or in
`TweakState`'s configurables map (which only ever holds the four plain
configurable values above, exactly like every other tweak's `TweakState`).
Note `FreecamHook` has no `freecamFreezePlayer()`-shaped method — Freecam
never suspends the player's own simulation (Non-goals), so there is nothing
for such a method to report.

`TweakHooksImpl` (one copy per platform module, e.g.
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweakHooksImpl.java`)
gains `implements ... NoRainHook, FreecamHook` and the six methods above,
all trivial `TweakRegistry` reads (`state(TweakId.NO_RAIN).enabled()` /
`.configurable("includeSnow")` etc.), following the exact pattern already
used for every other hook in that class (e.g. `isForceBrightnessActive()`/
`minBrightness()`, lines 86-94 of the file).

## Architecture

### Framework fit (no framework code changes)

Every part of the existing framework already generalizes over
`TweakId.values()` with no per-tweak special-casing except Zoom's own
dedicated `ZoomTicker` (itself precedent for Freecam's own ticker, below):

- `TweaksKeyBindings` (`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweaksKeyBindings.java:42-49`)
  registers one primary `KeyMapping` per `TweakId` in a loop — `NO_RAIN` and
  `FREECAM` get a bindable hotkey for free, no code change.
- `TweaksToggleTicker` (`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/TweaksToggleTicker.java:29-39`)
  edge-triggers `TweakRegistry.setEnabled` for every `TweakId` except `ZOOM`
  in a loop — `NO_RAIN`'s hotkey toggle works for free with no code change.
  `FREECAM` also gets its on/off toggle for free from this same loop (its
  *continuous per-tick behavior while on* needs its own ticker, same as
  Zoom — see below — but the plain enable/disable toggle itself does not).
- `TweaksConfig`/`TweaksConfigIO` (`features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfig.java`,
  `TweaksConfigIO.java`) both iterate `TweakId.values()`/`TweakDefinitions.ALL`
  generically; `TweaksConfigIO`'s own Javadoc (lines 32-36) already documents
  that a newly-added `TweakId` is backfilled with its default state
  automatically — no schema version bump, no migration code, matching this
  spec's Non-goals.
- `TweaksPanel`/`ConfigSchemas`/`TweakDefinitions` need exactly the additive
  entries described in Public API/Configuration below — no changes to their
  existing generic rendering/click-handling code
  (`platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`
  already renders whatever `ConfigSchemas.fieldsFor(id)` returns for any
  `TweakId`, including a currently-unregistered one it doesn't special-case).

The only genuinely new platform-side classes needed are: two new
`TweakHooksImpl` interface implementations (Public API), two new mixins for
No Rain, and — mirroring `ZoomTicker`'s existing precedent exactly — one new
`FreecamTicker` for Freecam's continuous per-tick camera/input logic (a
plain `ClientTickEvents.END_CLIENT_TICK` registration, no mixin needed for
the ticking itself, same as `ZoomTicker`).

### T13 No Rain — vanilla render/sound call site

**High-confidence finding, sourced from official Yarn API docs across many
adjacent versions (`maven.fabricmc.net/docs/yarn-*/net/minecraft/client/render/WorldRenderer.html`,
consistent from 1.16 through 1.21.9) plus Forge/NeoForge Mojmap javadoc
mirrors for the equivalent Mojmap name — not a literal `javap` run against
this repo's own pinned jars, which remains mandatory before writing any
mixin:**

Vanilla renders and "sounds" precipitation through a small, dedicated set of
methods, not scattered call sites:

- **Yarn (1.21.11, this repo's obfuscated platform)**: `WorldRenderer` owns a
  `WeatherRendering` field/component (confirmed present as of Yarn
  1.21.9+build.1 — this repo's pinned 1.21.11 is close enough in the same
  1.21.x line that the same class is expected to exist, but this specific
  point is **medium confidence**, not yet confirmed for the literal
  `1.21.11+build.6` pin). `net.minecraft.client.render.WeatherRendering` has:
  - `public void buildPrecipitationPieces(World, int, float, Vec3d, WeatherRenderState)`
    and `public void renderPrecipitation(VertexConsumerProvider, Vec3d, WeatherRenderState)`
    — the two methods that together build and draw the falling rain/snow
    streak geometry. Cancelling both (or just `buildPrecipitationPieces`,
    which would leave `renderPrecipitation` nothing to draw) suppresses the
    visual.
  - `public void addParticlesAndSound(ClientWorld, Camera, int, ParticlesMode)`
    — **this single method is the one place vanilla both spawns the
    rain-splash-on-ground particles *and* plays/updates the ambient
    precipitation sound loop.** Cancelling this one call when
    `NoRainHook.isNoRainActive() && noRainIncludesSound()` suppresses the
    sound (and incidentally the splash particles too, which is desirable —
    a splash particle with no rain falling would look wrong regardless).
  - `private Biome.Precipitation getPrecipitationAt(World, BlockPos)` —
    confirms per-position rain-vs-snow discrimination *is* possible in
    principle (needed for `includeSnow = false`), but it is `private`
    inside `WeatherRendering` — a mixin wanting the same discrimination
    either needs an `@Invoker` accessor onto this exact private method (this
    repo has real, hard-won precedent for invoker/accessor pitfalls with
    protected/private members — see `.claude/context/minecraft.md` rows
    82/85, the `AbstractSelectionList.Entry` saga) or must duplicate the
    biome-precipitation lookup itself using the already-public
    `Biome.getPrecipitationAt(BlockPos)`/`Biome.getPrecipitation(BlockPos)`-
    shaped vanilla API (exact accessor to confirm via `javap`) rather than
    reuse the private one. Either is a small, contained piece of work, not
    a blocker.
  - This class replaces an older, simpler `WorldRenderer.renderWeather(...)`
    method that existed as recently as Yarn 1.21.1 (confirmed via javadoc:
    `private void renderWeather(LightmapTextureManager, float, double,
    double, double)` in 1.21.1/1.20.2 API docs) — **by 1.21.9 that method's
    signature had already changed to a `FrameGraphBuilder`-based shape
    (`renderWeather(FrameGraphBuilder, Vec3d, GpuBufferSlice)`) that only
    wires up the frame graph, delegating the actual geometry/sound work to
    the new `WeatherRendering` class** — this is a real structural
    relocation across the 1.21.x line itself (not just 26.x's already-
    documented extraction-model churn), so **this repo's exact 1.21.11 pin
    must be `javap`-checked directly** before assuming either the old or the
    new shape applies.
- **26.1/26.2 (Mojmap)**: **unresolved this pass** — no version-matched
  Mojmap javadoc or migration-primer reference to a `WeatherRendering`- or
  `WeatherEffectRenderer`-shaped equivalent was found via web search. Given
  this repo's own repeated finding that recent Minecraft versions
  increasingly split monolithic render methods into small, purpose-built
  classes (the `Gui`/`Hud` split, the `LightmapRenderStateExtractor` split,
  both documented in `.claude/context/minecraft.md`), a similarly-named or
  similarly-shaped dedicated weather class is the most likely outcome, but
  this is a **genuine guess, javap-blocked**, not a finding. Flagged as the
  single highest-priority `javap` target for this tweak's implementation
  (search `net.minecraft.client.renderer` and its subpackages for a class
  with `renderPrecipitation`/`addParticlesAndSound`-shaped methods, or worst
  case fall back to tracing `LevelRenderer`'s own weather field(s)).
- **Why lightning is naturally unaffected**: lightning
  (`LightningBolt`/`LightningEntity`) is a real, separately-simulated
  vanilla entity, synced from the server like any other entity spawn, with
  its own dedicated entity renderer and its own sound event
  (`SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER`/`_IMPACT`) — it is not part
  of `WeatherRendering`'s precipitation-overlay code path at all. Cancelling
  only `buildPrecipitationPieces`/`renderPrecipitation`/`addParticlesAndSound`
  cannot touch lightning by construction, since those methods never
  reference lightning in any version this pass found documentation for.
  This should still be **manually verified in-game** at the verification
  phase (a real storm with lightning, tweak on, confirming both the flash
  and thunderclap still occur) rather than trusted from static analysis
  alone, since "no code path currently touches X" is not the same guarantee
  as "toggling this flag has zero visual/audio side effect on X."

**Classification: split.** Visual suppression
(`buildPrecipitationPieces`/`renderPrecipitation`) and sound suppression
(`addParticlesAndSound`) are each a single, well-scoped cancellable
`@Inject` **on 1.21.11** (once the 1.21.11-vs-1.21.9 exact-shape question is
confirmed) — safe/small once confirmed. **26.1/26.2 are fully javap-blocked**
with no confident starting point — the real risk item for this tweak's
implementation.

### T14 Freecam — architecture decision and vanilla surface

**Recommended architecture: reuse vanilla's own "camera entity" indirection
(`MinecraftClient.setCameraEntity(Entity)`/`getCameraEntity()`), not a raw
`Camera`-position-override mixin.** Confirmed present via official Yarn
javadoc (1.21.9+build.1): `void setCameraEntity(Entity)`, `Entity
getCameraEntity()`, a `@Nullable Entity cameraEntity` field — this is the
same long-standing vanilla mechanism used for things like `/spectate` and
mounted-camera scenarios, present across many past versions (medium-high
confidence it exists identically on 1.21.11 and on 26.1/26.2's `Minecraft`
equivalent, since this is core client plumbing unrelated to the render-
state-extraction churn documented elsewhere in `.claude/context/minecraft.md`
— still **javap-blocked** for a literal confirmation on all three pins).

Why this beats directly mixin-overriding `Camera`'s position/rotation
(the approach `tweaks-zoom-fov.md` used for FOV, which could superficially
seem like the obvious precedent):

- `Camera.update(BlockView, Entity focusedEntity, boolean thirdPerson,
  boolean inverseView, float tickProgress)` (confirmed via Yarn 1.21.9
  javadoc) already pulls position/rotation from whichever entity is handed
  to it as `focusedEntity` — which vanilla derives from
  `MinecraftClient.getCameraEntity()` each frame. Pointing that accessor at
  a different (non-player) entity gets correct position/rotation for free,
  with zero `Camera`-class mixin needed at all.
- **This also solves the "should the player's own body render" question
  (Requirements T14 `showOwnBody`) as a free side effect for the default
  case.** Vanilla's own "hide my own body in first-person view" check is
  keyed off comparing the entity being considered for render against the
  camera's *focused* entity (medium confidence on the exact check's
  location/shape — not yet `javap`-confirmed) — normally that's
  `client.player` itself, which is why the player's own body never renders
  in ordinary first-person view. Once `setCameraEntity` points the focused
  entity at Freecam's own phantom camera object instead of `client.player`,
  that comparison naturally stops matching the real player, so vanilla
  starts rendering the player's own body normally with **no extra mixin
  needed** — this is `showOwnBody = true`'s entire implementation.
  `showOwnBody = false` (forcing the body hidden even though the camera is
  no longer focused on the player) is the one direction that needs a small
  additional mixin patched onto that same check, to also hide when Freecam
  is active with the configurable off.
- Mouse-look needs **no new code at all**: mouse input keeps turning
  `client.player`'s own yaw/pitch exactly as it does today (Freecam never
  intercepts or redirects mouse input); the phantom camera object simply
  reads the player's live yaw/pitch each tick to know which way "forward"
  currently points for movement-key integration, and `Camera.update(...)`
  reads that same yaw/pitch back off the phantom object for the actual
  render orientation. This deliberately avoids re-implementing vanilla's
  own sensitivity/smoothing math.
- **Because the real player entity is never touched by any of this**
  (`setCameraEntity` only changes what the *camera* looks at — it has no
  effect whatsoever on the player entity's own tick/physics/AI processing,
  which vanilla runs completely independently), the "player keeps ticking
  normally" invariant (Non-goals/Goals) falls out of this architecture for
  free: there is no player-facing state for Freecam to freeze or leave
  frozen, because it was never touched in the first place.

**The phantom camera object should be a real, custom `Entity` subclass (not
a repurposed vanilla entity type), specifically so it has genuine, direct
(non-reflective, non-invoker) access to `Entity`'s own protected block-
collision-sweep machinery for `noclip = false`** — see the collision
sub-section below. This narrows what was an open question in the prior
revision of this spec (Open Questions, resolved) into a concrete
recommendation: implement Freecam's camera object as
`de.lazuli.tweaks.FreecamCameraEntity extends Entity` (or platform-
equivalent package), constructed fresh each time Freecam toggles on, never
added to the world (`world.spawnEntity`/`addEntity` is never called, so it
is never sent to the server, never visible to other players, never ticked
by the world's own tick loop), with every simulation method not relevant to
being a camera/collision carrier (`readNbt`/`writeNbt`, damage handling,
interaction, etc.) reduced to safe no-ops.

**Movement-key routing (still genuinely open, javap-blocked).** WASD/jump/
sneak must reach the phantom object's own tick-integration logic *instead
of* the real player's normal movement-key processing while Freecam is
active — otherwise pressing forward would both fly the camera *and* walk
the real player, since vanilla's key-to-velocity translation runs against
`client.player` independently of whichever entity the camera happens to be
focused on. **This is not a physics freeze** (Non-goals) — it only
redirects which object reads the movement keys as *input* for one tick;
every other autonomous simulation step for the real player (gravity, fall
damage, fluid drag, knockback, AI/entity interactions with the player, food/
saturation, etc.) must continue completely unaffected, since Freecam does
not suspend anything about the player at all. The exact per-tick method to
gate (analogous to how `tweaks-hooks-wiring.md` had to find
`LocalPlayer.drop(boolean)` the hard way for Anti-Drop, see
`.claude/context/minecraft.md` row 63) is unconfirmed — candidate target is
wherever `ClientPlayerEntity`/`LocalPlayer` reads its own movement-input
keys into a pressing-forward/left/back/right/jump/sneak state each tick,
cancelled/zeroed for that read (and only that read — nothing downstream of
it) while Freecam is active. **Risky/bigger, javap-blocked**, the single
highest-risk item remaining for this tweak.

**Block collision for `noclip = false` — now in scope for v1, recommended
approach.** Because the phantom camera object is a genuine `Entity`
subclass (constructed and owned entirely by our own code, per above), it
has ordinary Java subclass access — no mixin, no `@Invoker`, no reflection
— to `Entity`'s own protected collision-sweep helper (the same
block-shape-vs-bounding-box sweep vanilla itself uses to move every entity
in the game each tick, e.g. `Entity.adjustMovementForCollisions(Vec3d)`-
shaped in spirit; exact current method name/signature to confirm via
`javap` — this general shape has existed across many past versions and is
the standard "move this entity by a delta, clipped against nearby block
collision shapes" primitive every vanilla entity's own `move(...)` already
calls internally). The recommended implementation: each tick, compute the
camera's intended movement delta from input as normal, and when
`noclip = false`, pass that delta through the phantom entity's own
inherited collision-sweep method (called directly as `this.<method>(...)`
from inside our own `Entity` subclass, using the subclass's own bounding
box) before applying the corrected result to the phantom's position —
exactly mirroring how vanilla moves a boat, minecart, or any other
non-player entity, just without ever adding the phantom to the world's own
tick loop (we drive its "tick" ourselves, once per client tick, from
`FreecamTicker`).

**Entity collision is excluded by construction, not by extra suppression
code.** The block-collision-sweep primitive above only ever queries block
collision shapes (`World.getBlockCollisions(...)`-shaped, not
`World.getEntityCollisions(...)`-shaped) — it is a strict subset of the
full `Entity.move(MovementType, Vec3d)` pipeline vanilla runs for a real,
ticking entity, which separately also queries nearby entities for push-
apart/stepping-on-top-of behavior. Since Freecam's `FreecamTicker` calls
only the block-collision-sweep primitive directly (never the full `move(...)`
pipeline, and never any entity-vs-entity query method), the camera
mechanically cannot collide with entities — there is no separate "ignore
entities" flag to set or code path to cancel, it is simply never invoked.
This holds regardless of `noclip`'s value, matching Requirements T14
exactly.

**Classification: still split, but narrower than the prior revision.** The
camera-attachment mechanism itself (`setCameraEntity`) remains confirmed,
stable, low-risk vanilla API. Block-collision for `noclip = false` moves
from "open design question, possibly deferrable" to "in-scope, concrete
recommended approach identified, exact method name/signature javap-blocked
but the general primitive's existence across many past versions is
high-confidence" — a real but bounded implementation task, not an unbounded
one. Movement-key routing remains this tweak's single biggest unresolved
risk item (unconfirmed target method, same category of risk Anti-Drop
originally had before its own `javap` pass). `showOwnBody = false` and the
phantom-entity subclass itself are both small, contained, low-risk
implementation tasks once the entity-attachment mechanism is confirmed.
Overall still a larger, more involved tweak than any of T1-T13, and still a
reasonable candidate for its own follow-up implementation-planning pass
rather than being batched casually alongside No Rain's much smaller,
better-precedented change — but the removal of `freezePlayer` (one entire
risky sub-item, see prior revision's Architecture) and the resolution of
the collision approach both meaningfully shrink its scope versus the first
pass of this spec.

## UI

No new UI mechanism — both tweaks render through the existing `TweaksPanel`/
`ConfigSchemas`/`ConfigFieldSpec` machinery exactly like every other tweak
(`platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java`,
already generic over any registered `TweakId`, see Architecture — Framework
Fit). No Rain's two boolean configurables render as two checkbox rows;
Freecam's four configurables render as two numeric-stepper rows
(`moveSpeed`, `sprintMultiplier`) and two boolean checkbox rows (`noclip`,
`showOwnBody`) — same generic per-`ConfigFieldSpec.Kind` rendering
`TweaksPanel.renderConfigRow` already implements for every other tweak
(`TweaksPanel.java:320-367`), no new widget kind needed.

## Configuration

Both tweaks live in the existing single `tweaks.json` file, no schema
change beyond new entries appearing under the existing `"tweaks"` object key
(`TweaksConfigIO`'s own Javadoc already documents this exact forward-
compatible shape — `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java:32-36`):

```json
{
  "tweaks": {
    "NO_RAIN": {
      "enabled": false,
      "configurables": { "includeSnow": true, "includeSound": true }
    },
    "FREECAM": {
      "enabled": false,
      "configurables": {
        "moveSpeed": 1.0, "sprintMultiplier": 2.0,
        "noclip": true, "showOwnBody": true
      }
    }
  }
}
```

New `TweakDefinitions.ALL`/`ConfigSchemas.ALL` entries (additive, following
the exact `of(...)`/`map(...)`/`ConfigFieldSpec.bool/numeric(...)` shape
already used for every other tweak — `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:60-116`,
`ConfigSchemas.java:24-108`).

## Events

None — no new event bus/pub-sub layer, matching every other tweak
(`docs/specs/tweaks.md` Events). No Rain needs no per-tick polling at all
(pure `enabled()` reads inside the two mixins, same "no allocation when
disabled" discipline as every render-hook tweak). Freecam needs its own
`FreecamTicker` registered against Fabric API's `ClientTickEvents.END_CLIENT_TICK`
(the exact same event `ZoomTicker`/`TweaksToggleTicker` already use —
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/ZoomTicker.java:22`),
not a new event type.

## Networking

None for either tweak — both are 100% client-side, matching every other
tweak's Non-goals (`docs/specs/tweaks.md` Non-goals, F7).

## Persistence

Both tweaks' enabled flag + configurables persist through the existing
`tweaks.json`/`TweaksConfigIO` mechanism with zero code changes (Architecture
— Framework Fit). Hotkey bindings persist through vanilla's own
`options.txt` `KeyMapping` save mechanism automatically, exactly like every
other tweak (`docs/specs/tweaks.md` Persistence).

## Compatibility

- All three platform modules (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`)
  need both tweaks. No Rain's exact vanilla call site is medium-confidence
  on 1.21.11 and fully unconfirmed on 26.1/26.2 (Architecture); Freecam's
  camera-attachment mechanism is expected to be stable across all three
  (core, long-standing client API, unrelated to the render-state-extraction
  churn documented elsewhere in `.claude/context/minecraft.md`), but its
  remaining implementation questions (movement-key routing, exact
  collision-sweep method name) are per-platform-unconfirmed and must each
  get their own `javap` pass before implementation, per this repo's
  established convention.
- 26.1 vs 26.2 are **not** assumed identical without verification — this
  catalog has already found real 26.1-vs-26.2 divergences for adjacent
  render-pipeline work (the `Gui`/`Hud` split documented in
  `.claude/context/minecraft.md` row 66/T5/T9/T12 findings) — No Rain's
  `WeatherRendering`-equivalent class (if one exists at all on 26.x) must be
  independently confirmed on both, not assumed to match just because it's
  the "same" Mojmap family. Freecam's block-collision-sweep primitive is
  expected to be identical on 26.1/26.2 (a core entity-movement mechanic,
  not part of the render-state-extraction refactor), but not assumed
  without its own `javap` confirmation.
- No new dependency on any other shipped feature for either tweak.

## Performance

- No Rain: a single cheap boolean branch (`isNoRainActive()`, an `enabled()`
  read) at the head of each cancelled method, no allocation when disabled —
  same discipline `docs/specs/tweaks.md`'s Performance section already
  requires of every render-hook tweak. If `includeSnow = false` requires a
  per-invocation biome-precipitation lookup (Architecture), that lookup only
  runs while the tweak is enabled and only as often as vanilla itself would
  already call the target method (no new per-tick/per-frame overhead beyond
  what vanilla already pays to decide precipitation type today).
- Freecam: `FreecamTicker`'s per-tick work (reading movement keybindings,
  integrating the phantom object's position, borrowing the player's live
  yaw/pitch) is comparable in cost to `ZoomTicker`'s existing per-tick
  hold/toggle polling (`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/ZoomTicker.java`)
  — cheap, no allocation on the hot path once the phantom camera object
  itself is constructed once at toggle-on time (not reconstructed every
  tick). `noclip = false`'s per-tick collision sweep now has a concrete
  recommended implementation (Architecture): reusing the same block-
  collision-sweep primitive vanilla already runs once per tick for every
  other moving entity in the game, so its cost is bounded and well
  understood (one entity's worth of collision-shape queries against its
  immediate surroundings, not a new or open-ended cost), not the
  "unbounded until an approach is chosen" risk the prior revision of this
  spec flagged.

## Future Extensions

- A "fully clear sky" mode for No Rain (also suppressing storm-driven sky
  darkening/fog, explicitly out of scope per Non-goals here).
- A dedicated "hide lightning too" *separate* tweak, if ever wanted — this
  spec deliberately keeps lightning untouched by No Rain per the user's own
  explicit framing, so a future "Disable Lightning" tweak would be a new,
  independent `TweakId`, not a configurable added to `NO_RAIN`.
- Freecam: a saved list of camera bookmarks/waypoints, a smoothed camera-
  path/cinematic mode, or an on-screen speed/position readout — none
  requested, all natural extensions of the same phantom-camera-object
  architecture once it exists.
- Freecam: reconsidering whether left/right-click interaction should work
  from the detached camera's position instead of being suppressed outright
  (see Non-goals/Open Questions) — deferred pending the user's answer to
  Open Question 1 below.
- Freecam: a possible future `freezePlayer`-shaped toggle (explicitly
  removed from this spec's v1 scope per user decision, see Open Questions)
  could be reconsidered as a genuinely separate, independent tweak/
  configurable later if wanted — but is not part of this feature's design
  going forward unless re-requested.

## Open Questions / Recommendations (for user approval, not decided here)

Resolved by the user's review of this document's first pass (kept here for
traceability, not as open items):

- **Freecam block collision / `noclip` semantics: resolved.** `noclip`
  governs block collision only; entity collision is unconditionally
  disabled for the detached camera regardless of `noclip`'s value (see
  Requirements T14, Architecture). This also resolves the prior revision's
  Open Question 2 ("`freezePlayer` vs. the literal task wording") in favor
  of the two-concept split being *narrowed to one concept*: there is no
  player-physics-freeze toggle at all — only `noclip`, which is purely
  about the camera's own block collision.
- **`freezePlayer`: resolved — removed entirely.** Freecam must not affect
  the real player entity's behavior/state in any way beyond routing
  movement-key *input* to the camera instead of the player while active;
  the player entity ticks normally (gravity, physics, AI, damage, etc.) at
  all times, exactly as if Freecam were disabled. See Non-goals, Goals, and
  Public API (no `freecamFreezePlayer()` method) above.
- **`noclip = false`'s real collision implementation: resolved — hard v1
  requirement, not deferrable.** A concrete recommended approach is now
  documented in Architecture (reuse the phantom `Entity` subclass's own
  inherited block-collision-sweep primitive, the same one every vanilla
  entity's own movement already uses) — no longer flagged as an open
  "defer to a later pass?" question.

Still genuinely open:

1. **Freecam + block/entity interaction.** This spec's default (Non-goals)
   is to suppress normal left/right-click interaction entirely while
   Freecam is active, since vanilla's own interaction raycast follows the
   camera and would otherwise let the player remotely mine/attack from the
   camera's current position. Confirm this is the wanted behavior, versus
   e.g. always raycasting from the real player's position regardless of
   where the camera currently is (a materially more complex alternative,
   not designed here).
2. **No Rain's 26.1/26.2 target class** is genuinely unresolved by this
   pass (Architecture) — flagged as the mandatory first `javap` step for
   that tweak's implementation, same treatment every other catalog tweak's
   javap-blocked items already received in `docs/specs/tweaks-hooks-wiring.md`.
3. **Freecam's movement-key routing target method** (which per-tick method
   to gate so WASD reaches the camera instead of the player while Freecam
   is active) is genuinely unresolved by this pass — the single biggest
   remaining risk item for this tweak's implementation (Architecture),
   needing its own `javap` pass at implementation time, same category of
   work Anti-Drop's `LocalPlayer.drop(boolean)` discovery already
   represents precedent for in this catalog.
4. **Freecam's phantom-camera-object exact class shape** is now more
   narrowly scoped than the prior revision (a custom `Entity` subclass is
   the clear recommendation, driven by the collision-reuse requirement —
   Architecture) but the exact set of methods that need overriding to
   safely stay a no-op non-world-resident entity (and the exact name/
   signature of the inherited collision-sweep method to call) remain a
   real implementation-time spike, not resolved here.
