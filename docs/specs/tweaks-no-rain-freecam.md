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

**Post-ship corrective revision note (see "Addendum" at the end of this
document):** T14 Freecam shipped (see `.claude/context/minecraft.md` rows
112/113/114/115/116/119/120 for the real `javap`-confirmed implementation
findings and post-ship bug fixes already folded into the live code) and five
further behavior corrections were requested after live use. Those five items
are specified in the **Addendum** section at the end of this document rather
than by rewriting the sections above in place, since the sections above still
accurately describe the original v1 design intent and its own resolved Open
Questions — the Addendum explicitly marks which specific statements above it
supersedes.

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
    time). **Superseded by Addendum AD-3** — see Addendum for the current
    range/step/default and the config-migration mechanism.
  - `sprintMultiplier` (numeric, 1.0-5.0, step 0.5, default `2.0`): extra
    speed multiplier applied while the vanilla Sprint keybinding is held,
    mirroring creative-flight's existing fast-fly convention (holding
    Sprint already speeds up creative flight in vanilla; Freecam reuses the
    same keybinding for the same purpose, not a new bind). Unchanged by the
    Addendum.
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
    camera. The zero-size collision-box bug that made `noclip = false`
    ineffective in practice is fixed by **Addendum AD-4**.
  - `showOwnBody` (bool, default `true`): whether the player's own body
    renders at its real, current position while the camera is detached
    (default on, so you can see your own character from outside — see
    Architecture for why this is actually the zero-extra-mixin default
    given the recommended architecture) or stays hidden the way vanilla
    hides it in ordinary first-person view (`false`). **Removed entirely by
    Addendum AD-2** — replaced with automatic geometric detection, no
    configurable, no config UI row.
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
- **Additional hard requirement per Addendum AD-1**: the real player
  entity's own yaw/pitch (`ClientPlayerEntity`/`LocalPlayer`'s rotation
  fields — what every other client/player and the server itself sees as
  "which way this player is facing") must never change as a result of any
  input processed while Freecam is active. Only the detached camera's own
  rotation may change from mouse input while Freecam is on. See Addendum
  AD-1 for the precise mechanism.
- **Additional hard requirement per Addendum AD-5**: A (left) and D (right)
  must strafe the camera in the direction their key names imply — this was
  shipped inverted (see Addendum AD-5 for the confirmed root cause and
  fix); W/S and Space/Sneak are unaffected by this item.

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

**As shipped, `FreecamHook` matches the interface above exactly (confirmed by
reading `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/FreecamHook.java:1-20`
this pass) — `freecamShowOwnBody()` is REMOVED by Addendum AD-2** (no
replacement method on this cross-platform interface; the auto-detection
logic needs an `Entity`/`AABB`-typed position comparison, which per this
interface's own "primitives/booleans only" constraint (below) cannot live
here — see Addendum AD-2 for exactly where it lives instead).

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
  spec's Non-goals. **Addendum AD-3 adds one small, tweak-scoped exception to
  "no migration code" — see Addendum for exactly why and its precise,
  narrowly-scoped mechanism (not a general schema-version bump).**
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

**Confirmed via `javap -p -s -c` against all three platforms' resolved
merged jars — see `.claude/context/minecraft.md` row 118 for the full,
per-platform confirmed class/method names (including a real 26.1-vs-26.2
divergence for the sound/splash-particle half: `WeatherEffectRenderer.tickRainParticles`
on 26.1 vs. `ClientLevel.tickWeatherEffects()` on 26.2). This entire
subsection is retained below only as the original pre-implementation
research pass; treat `.claude/context/minecraft.md` row 118 as the
authoritative, `javap`-confirmed source of truth where the two disagree.**

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
    a blocker. **Superseded — `.claude/context/minecraft.md` row 118 found a
    materially simpler alternative that needs neither workaround.**
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
  **Resolved — see row 118: `WeatherEffectRenderer` on both, with the
  tickRainParticles/tickWeatherEffects divergence noted above.**
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
implementation. **Both resolved — see row 118.**

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
**Confirmed as shipped — see `platform/*/src/main/java/de/lazuli/tweaks/FreecamTicker.java`
`lazuli$activate`/`lazuli$deactivate`.**

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
  is active with the configurable off. **Correction, confirmed via `javap -c`
  (`.claude/context/minecraft.md` row 113): this was backwards.
  `showOwnBody = false` is the free default; `showOwnBody = true` is the
  direction needing a mixin. As shipped, see `WorldRendererFreecamShowBodyMixin`
  (1.21.11) / `LevelRendererFreecamShowBodyMixin` (26.1) /
  `LevelExtractorFreecamShowBodyMixin` (26.2). Addendum AD-2 replaces the
  boolean-config trigger for that same mixin with a geometric one — the
  mixin's existence/shape/redirect target are unchanged, only the condition
  it evaluates changes.**
- Mouse-look needs **no new code at all**: mouse input keeps turning
  `client.player`'s own yaw/pitch exactly as it does today (Freecam never
  intercepts or redirects mouse input); the phantom camera object simply
  reads the player's live yaw/pitch each tick to know which way "forward"
  currently points for movement-key integration, and `Camera.update(...)`
  reads that same yaw/pitch back off the phantom object for the actual
  render orientation. This deliberately avoids re-implementing vanilla's
  own sensitivity/smoothing math. **Superseded by Addendum AD-1 — this
  "mouse keeps turning the real player, camera copies it" design is exactly
  the root cause of the AD-1 bug (the real player's body/head visibly
  turning to match the freecam camera's look direction). AD-1 requires new
  mouse-handling code after all: mouse input must turn the camera directly
  and must stop turning the real player while Freecam is active.**
- **Because the real player entity is never touched by any of this**
  (`setCameraEntity` only changes what the *camera* looks at — it has no
  effect whatsoever on the player entity's own tick/physics/AI processing,
  which vanilla runs completely independently), the "player keeps ticking
  normally" invariant (Non-goals/Goals) falls out of this architecture for
  free: there is no player-facing state for Freecam to freeze or leave
  frozen, because it was never touched in the first place. **Confirmed
  true for position/physics; NOT true for rotation as shipped — see
  Addendum AD-1. Also note a second, unrelated real bug found post-ship in
  the same "gate reached more than intended" category:
  `.claude/context/minecraft.md` row 120, `LocalPlayer.sendPosition()`/
  `ClientPlayerEntity.sendMovementPackets()` being silently gated off by the
  same `isControlledCamera()`/`isCamera()` check, fixed via
  `LocalPlayerFreecamKeepPositionSyncMixin`/
  `ClientPlayerEntityFreecamKeepPositionSyncMixin` — already shipped, not
  part of this Addendum's 5 items, listed here only for context since AD-1's
  fix touches the same rotation-adjacent territory.**

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
interaction, etc.) reduced to safe no-ops. **Confirmed as shipped — see
`platform/*/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java`, backed
by `EntityType.MARKER`/`EntityTypes.MARKER` (a real 26.1-vs-26.2 divergence
in where the constant lives, `.claude/context/minecraft.md` row 116), which
incidentally also gives it a zero-size bounding box — this is the confirmed
root cause fixed by Addendum AD-4.**

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
highest-risk item remaining for this tweak. **Resolved — much better than
assumed: `.claude/context/minecraft.md` row 112, vanilla's own
`isCamera()`/`isControlledCamera()` gate already handles this for free, no
mixin needed. Also see Addendum AD-5 for a real, separate bug found in
`FreecamTicker`'s own input-to-velocity math (not a vanilla-API-target
problem at all) — a sign inversion on the strafe axis only.**

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
`FreecamTicker`). **Confirmed and shipped exactly as recommended, with the
better-than-assumed finding that the collision-sweep primitive is `public
static`, not merely `protected` (`.claude/context/minecraft.md` row 115) —
see `FreecamCameraEntity.lazuli$integrate`. The mechanism works correctly;
Addendum AD-4 fixes a separate problem (the bounding box being sized zero,
so the mechanism has nothing to sweep against), not this collision-sweep
logic itself.**

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
exactly. **Confirmed as shipped — see each `FreecamCameraEntity`'s own
class Javadoc, which documents the specific rejected alternative
(`Entity.findCollisions`/`collectAllColliders`) and why (merges in entity
collision internally, confirmed via `javap -c`).**

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
pass of this spec. **Post-ship status: T14 shipped, and — beyond the 5 real
bugs already fixed post-ship (rows 119/120) and the 5 covered by this
Addendum — the original architecture held up. See Addendum for what's still
open.**

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
(`TweaksPanel.java:320-367`), no new widget kind needed. **Superseded by
Addendum AD-2 — Freecam now renders three configurable rows, not four: the
`showOwnBody` checkbox row is removed entirely (no replacement row; the
behavior becomes fully automatic with nothing for the user to configure).**

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

**Superseded by Addendum AD-2/AD-3 — see Addendum "Updated Configuration
shape" for the current, post-corrective-revision `FREECAM` JSON shape (no
`showOwnBody` key, `moveSpeed` rescaled with a migration marker key).**

New `TweakDefinitions.ALL`/`ConfigSchemas.ALL` entries (additive, following
the exact `of(...)`/`map(...)`/`ConfigFieldSpec.bool/numeric(...)` shape
already used for every other tweak — `features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:60-116`,
`ConfigSchemas.java:24-108`). **As shipped, confirmed by reading both files
this pass: `TweakDefinitions.FREECAM` at lines 116-118, `ConfigSchemas.java`
lines 114-119 — exact shape matches the JSON above.**

## Events

None — no new event bus/pub-sub layer, matching every other tweak
(`docs/specs/tweaks.md` Events). No Rain needs no per-tick polling at all
(pure `enabled()` reads inside the two mixins, same "no allocation when
disabled" discipline as every render-hook tweak). Freecam needs its own
`FreecamTicker` registered against Fabric API's `ClientTickEvents.END_CLIENT_TICK`
(the exact same event `ZoomTicker`/`TweaksToggleTicker` already use —
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/ZoomTicker.java:22`),
not a new event type. **Confirmed as shipped —
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java:36`.**

## Networking

None for either tweak — both are 100% client-side, matching every other
tweak's Non-goals (`docs/specs/tweaks.md` Non-goals, F7).

## Persistence

Both tweaks' enabled flag + configurables persist through the existing
`tweaks.json`/`TweaksConfigIO` mechanism with zero code changes (Architecture
— Framework Fit). Hotkey bindings persist through vanilla's own
`options.txt` `KeyMapping` save mechanism automatically, exactly like every
other tweak (`docs/specs/tweaks.md` Persistence). **Addendum AD-3 adds one
small, one-time, tweak-scoped exception to "zero code changes" — see
Addendum.**

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
  established convention. **Both tweaks have since shipped on all three
  platforms — see `.claude/context/minecraft.md` rows 112-120 for the full
  set of confirmed per-platform findings.**
- 26.1 vs 26.2 are **not** assumed identical without verification — this
  catalog has already found real 26.1-vs-26.2 divergences for adjacent
  render-pipeline work (the `Gui`/`Hud` split documented in
  `.claude/context/minecraft.md` row 66/T5/T9/T12 findings) — No Rain's
  `WeatherRendering`-equivalent class (if one exists at all on 26.x) must be
  independently confirmed on both, not assumed to match just because it's
  the "same" Mojmap family. Freecam's block-collision-sweep primitive is
  expected to be identical on 26.1/26.2 (a core entity-movement mechanic,
  not part of the render-state-extraction refactor), but not assumed
  without its own `javap` confirmation. **Confirmed: the block-collision
  primitive IS identical on 26.1/26.2 (row 115); the show-own-body render
  check is NOT (row 113, a genuine 26.1-vs-26.2 divergence); the built-in
  `EntityType.MARKER` constant's owning class is NOT (row 116).**
- No new dependency on any other shipped feature for either tweak.
- **Addendum items AD-1 through AD-5 all need their own per-platform
  `javap` confirmation before implementation — see each Addendum item and
  the Addendum's own "Open items for planning" list.**

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
  spec flagged. **Addendum AD-2's per-frame "is the camera inside the
  player's AABB" check adds one cheap `AABB.contains`-shaped point test per
  frame while Freecam is active — negligible, same order of cost as the
  existing per-frame `camera.entity()` redirect it replaces the condition
  of. Addendum AD-1's mouse-look redirect and AD-4's larger bounding box add
  no new allocation or per-frame cost beyond what the existing mechanisms
  already pay (a redirected field write / a differently-sized existing
  collision-shape query, not new work).**

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
- **Addendum AD-2's fixed 0.1-block hide/show margin could become a real
  hysteresis dead-band (different enter/exit thresholds) if live testing
  after implementation shows the single-margin approach still flickers at
  the boundary — not needed unless that's actually observed (see Addendum
  AD-2).**

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
   not designed here). **Shipped as suppression — see
   `MinecraftClientFreecamSuppressInteractionMixin`/`MinecraftFreecamSuppressInteractionMixin`,
   row 114 for the confirmed target methods
   (`doAttack()`/`doItemUse()` Yarn, `startAttack()`/`startUseItem()`
   Mojmap). Not reopened by this Addendum.**
2. **No Rain's 26.1/26.2 target class** is genuinely unresolved by this
   pass (Architecture) — flagged as the mandatory first `javap` step for
   that tweak's implementation, same treatment every other catalog tweak's
   javap-blocked items already received in `docs/specs/tweaks-hooks-wiring.md`.
   **Resolved — row 118.**
3. **Freecam's movement-key routing target method** (which per-tick method
   to gate so WASD reaches the camera instead of the player while Freecam
   is active) is genuinely unresolved by this pass — the single biggest
   remaining risk item for this tweak's implementation (Architecture),
   needing its own `javap` pass at implementation time, same category of
   work Anti-Drop's `LocalPlayer.drop(boolean)` discovery already
   represents precedent for in this catalog. **Resolved — row 112, no
   dedicated mixin needed at all.**
4. **Freecam's phantom-camera-object exact class shape** is now more
   narrowly scoped than the prior revision (a custom `Entity` subclass is
   the clear recommendation, driven by the collision-reuse requirement —
   Architecture) but the exact set of methods that need overriding to
   safely stay a no-op non-world-resident entity (and the exact name/
   signature of the inherited collision-sweep method to call) remain a
   real implementation-time spike, not resolved here. **Resolved — see each
   platform's shipped `FreecamCameraEntity.java`, a small, contained
   override surface confirmed via `javap`.**

## Addendum: T14 Freecam corrective revision (post-ship behavior fixes)

This addendum specifies five behavior corrections requested after Freecam
shipped and saw live use. It is a **corrective/behavioral rework of already-
shipped code**, not new-feature scope — every item below fixes or replaces a
specific piece of the design described above, cross-referenced by item
number from wherever it supersedes prior text. **Do not treat the sections
above as stale or wrong except where a "Superseded by Addendum AD-N" note
explicitly says so** — everything else above (No Rain in full; Freecam's
camera-attachment mechanism, block-collision-sweep reuse, entity-collision
exclusion, movement-key routing via `isCamera()`/`isControlledCamera()`, the
two already-shipped post-ship bug fixes in rows 119/120, and interaction
suppression) is unchanged and still accurately describes the shipped,
working design.

### Addendum methodology note

This pass, like the original spec pass, had **no shell/`javap` tool
available** to the specification agent (`Read`/`Glob`/`Grep`/`Write`/
`WebFetch`/`WebSearch` only). Unlike the original pass, this one *does* have
direct `Read` access to the actual shipped source (`FreecamCameraEntity.java`,
`FreecamTicker.java`, `FreecamHook.java`, the show-body/keep-position-sync
mixins, `TweakDefinitions.java`, `ConfigSchemas.java`, `TweaksConfigIO.java`
on all three platforms plus `.claude/context/minecraft.md`'s already-`javap`-
confirmed rows 112-120) — so items AD-2, AD-4 and AD-5's *root causes* are
grounded in reading the real shipped code and its own Javadoc, not guesswork.
Where a genuinely new vanilla API target is introduced (AD-1's mouse-look
redirect; AD-4's exact `EntityDimensions`-shaped override point/factory
method/`Pose` enum name per platform), those are marked **medium
confidence, javap-blocked** below, same tiering convention as the original
spec, and **must** get their own `javap -p`/`javap -c -p` pass against each
platform's resolved merged jar as the mandatory first implementation step,
per this repo's established convention (`.claude/context/minecraft.md`'s own
rows are the precedent for what that confirmation should look like once
done).

### AD-1 — Player rotation must not follow the freecam camera

**Confirmed root cause (read directly from shipped code, high confidence).**
`FreecamTicker.lazuli$integrate` (e.g.
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java:100-117`)
calls `cameraEntity.lazuli$integrate(delta, player.getYRot(), player.getXRot(), ...)`
**every tick**, and `FreecamCameraEntity.lazuli$integrate` unconditionally
does `this.setYRot(yaw); this.setXRot(pitch);` from those parameters (e.g.
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java:102-105`).
Mouse input itself was deliberately left untouched (Architecture's original
"Mouse-look needs no new code at all" reasoning, now superseded — see above)
— it keeps turning `client.player`'s real yaw/pitch fields exactly as it
does with Freecam off, completely unconditionally (no `isCamera()`/
`isControlledCamera()`-shaped gate reaches vanilla's mouse-turn call site;
that gate, per row 112, only reaches movement-key consumers, not mouse
input). Net effect as shipped: the camera's rotation is a *copy* of the
real player's live, mouse-driven rotation every tick, and since the player's
real rotation is what every other client and the server see (also
networked every tick regardless of Freecam state, per row 120's fix), the
real player's body/head visibly turns to match wherever the user is looking
in freecam — exactly the reported bug.

**Target behavior.** While Freecam is active:

- The freecam camera's own yaw/pitch is driven **directly and exclusively**
  by mouse input for that tick/frame — not copied from the player.
- The real player entity's yaw/pitch fields do not change as a result of any
  mouse input processed while Freecam is active. Concretely: snapshot the
  player's yaw/pitch at the instant Freecam activates (this snapshot is also
  the camera's own starting yaw/pitch, so there is no visual jump on
  activation, matching today's activation behavior exactly); the player's
  yaw/pitch fields stay pinned at that snapshot value for Freecam's entire
  active duration; the camera's own yaw/pitch, from that same starting
  point, is thereafter advanced independently by each frame's mouse delta.
- On deactivation, mouse input immediately regains control of the real
  player's rotation, continuing from that same still-pinned snapshot value
  (not a snap/discontinuity, since the player's actual fields never moved
  while Freecam was active).
- This is a rotation-only invariant — it does not reopen or change AD-1's
  sibling, already-shipped fix (row 120, `sendPosition()`/
  `sendMovementPackets()` position/rotation networking staying live during
  Freecam): the *networked* rotation while Freecam is active is simply
  whatever the pinned snapshot value is (unchanging), which is itself
  correct/desired — other players/the server should see the real player
  facing one fixed direction while its owner is off flying around in
  freecam, not swiveling to match the freecam view.

**Mechanism.**

1. `FreecamCameraEntity`/`FreecamTicker` stop reading `player.getYRot()`/
   `player.getXRot()` into the camera every tick. The camera instead owns
   its own persistent yaw/pitch state, seeded once from the player's
   yaw/pitch at `lazuli$activate` time (this part is unchanged — activation
   already does exactly this, e.g.
   `platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java:87-88`)
   and never re-synced from the player again while active.
2. **New mixin needed (genuinely new target, not previously analyzed by
   this spec or its Addendum's own row 112/113/114/115/116 precedent
   findings) — the vanilla mouse-look call site that turns the currently-
   relevant entity's yaw/pitch from raw cursor delta each frame must be
   redirected while Freecam is active, so it turns the freecam camera
   entity instead of `client.player`, and must not turn `client.player` at
   all for that same frame's delta.** Candidate target (medium confidence,
   sourced from official Yarn/Forge javadoc across several past versions,
   not yet `javap`-confirmed against this repo's exact three pins):
   - **Yarn (1.21.11)**: `net.minecraft.client.Mouse`, private
     `updateMouse(double timeDelta)` — confirmed present with this exact
     signature via official Yarn javadoc for 1.21.11+build.3 (fetched this
     pass); its body is not itself confirmed (private method, javadoc has
     no body), but this is the long-standing, stable per-frame mouse-delta-
     to-look-direction integration point across many past Minecraft
     versions.
   - **Mojmap (26.1/26.2)**: `net.minecraft.client.MouseHandler`, public
     `turnPlayer()` — a long-stable, widely-referenced Mojmap method name
     (confirmed present via a 1.18.2 Forge javadoc mirror this pass; not
     yet confirmed for this repo's exact 26.1/26.2 pins, and per this
     repo's own repeated finding that 26.1 and 26.2 sometimes diverge from
     each other despite both being "26.x Mojmap" — e.g. rows 69/104/113/116
     — **do not assume 26.1 and 26.2 share this method's exact shape/owning
     class without independently `javap`-confirming both**).
   - Whichever the confirmed method turns out to be, the fix is either (a)
     a `@Redirect`/`@ModifyArgs`-shaped mixin that, while Freecam is active,
     applies the frame's delta to the freecam camera entity's yaw/pitch and
     suppresses (returns early from, or redirects the inner turn-call away
     from) the real player's own turn, mirroring the `@Redirect` shape
     already used by `LocalPlayerFreecamKeepPositionSyncMixin`/row 120's
     fix and the show-body mixins; or (b) if this call site turns out to
     already read `MinecraftClient.getCameraEntity()`/`Minecraft.getCameraEntity()`
     rather than hard-coding `client.player` (the same kind of pleasant
     surprise row 112 already found for movement-key routing), then **no
     mixin is needed at all** and the fix reduces to simply making the
     camera entity itself the thing that receives and integrates the raw
     mouse delta each frame (i.e., `FreecamCameraEntity` gains its own
     `turn`-shaped method, called from wherever `FreecamTicker`/the mouse
     handler already computes the frame's delta). **This exact question —
     does the mouse-look call site already key off the camera-entity
     accessor, matching row 112's finding, or does it hard-code the player
     unconditionally, matching this Addendum's working assumption — is the
     single most important thing to confirm via `javap` before starting
     implementation of AD-1**, since it determines whether AD-1 needs a new
     mixin at all or is a much smaller, mixin-free change.
3. Whichever branch AD-1's `javap` pass lands on, the acceptance behavior
   is identical: the real player's own yaw/pitch fields do not move while
   Freecam is active; the camera's do, driven by mouse.

### AD-2 — "Show Own Body" becomes automatic (config toggle removed)

**Change to Public API/Configuration.** `FreecamHook.freecamShowOwnBody()`
is removed from the cross-platform hook interface entirely (Public API
above already updated to reflect this). `TweakDefinitions.FREECAM`'s default
configurables map drops the `"showOwnBody"` entry
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:118`
today includes it — removed). `ConfigSchemas.FREECAM`'s field list drops the
`bool("showOwnBody", "Show Own Body")` entry
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java:118`
today — removed), so `TweaksPanel` stops rendering that checkbox row
automatically (no `TweaksPanel` code change needed, per the existing
generic-rendering architecture). No migration is needed for the removed
`showOwnBody` key in old `tweaks.json` files — `TweaksConfigIO`'s existing
"unknown keys are ignored, not fatal" behavior
(`features/tweaks/src/test/java/de/lazuli/features/tweaks/config/TweaksConfigIOTest.java`'s
`unknownTweakIdInFileIsIgnoredNotFatal`-shaped precedent) already covers an
old file having a now-unrecognized `configurables` key.

**Target auto-detection rule.** Hide the player's own body when the freecam
camera is positioned inside the player's own hitbox; always show it
otherwise. Precisely:

- **"Player's own hitbox"** = the real player entity's own current, live
  bounding box (`Entity.getBoundingBox()`-shaped, high confidence this
  method/its equivalent is a long-stable, core `Entity` accessor present
  identically across all three platforms, still to be `javap`-confirmed per
  this repo's convention), evaluated **fresh every time the check runs**
  (see below) — never a cached snapshot taken at Freecam-activation time.
  This matters because the real player keeps simulating normally while
  Freecam is active (Non-goals), so its pose — and therefore its bounding
  box's actual width/height — can legitimately change mid-session
  (standing 0.6×1.8, sneaking 0.6×1.5, swimming/elytra-flying 0.6×0.6,
  sleeping a short wide box, etc.); the check must track whichever box is
  currently correct for the player's live pose, not the pose at Freecam
  activation.
- **"Freecam camera is positioned inside"** = the freecam camera entity's
  own current world position (`FreecamCameraEntity`'s live `getX()/getY()/
  getZ()`, the same position `Camera` renders from — not the player's eye
  position, not a cached activation-time position) is contained within an
  **inflated** copy of the player's current bounding box: inflate the box
  by a fixed margin of **0.1 blocks in every direction**
  (`AABB.inflate(0.1)`/`Box.expand(0.1)`-shaped, a standard, long-stable
  vanilla `Box`/`AABB` method — exact per-platform overload name still to
  be `javap`-confirmed), then test whether the camera's position point
  falls inside that inflated box
  (`AABB.contains(double,double,double)`/`Box.contains(...)`-shaped, same
  confirmation caveat). The margin exists specifically to avoid single-
  frame flicker when the camera is moving slowly right at the hitbox's
  surface (without it, floating-point/interpolated positions landing
  exactly on the boundary many times per second would toggle the body
  on/off rapidly); 0.1 blocks was chosen as comfortably larger than typical
  per-frame positional jitter while still being visually unnoticeable as an
  "early hide" margin. If live testing after implementation still shows
  flicker, upgrading to a proper hysteresis dead-band (a larger inflate
  amount to *start* hiding, a smaller one to *stop* hiding) is a natural,
  low-risk follow-up (Future Extensions) — not required for v1 of this
  correction.
- **Rule**: `hide = playerBoundingBox.inflate(0.1).contains(cameraPosition)`;
  render the body normally whenever `hide` is false.
- **Where this lives.** Per the existing hook interface's own hard
  constraint ("Deliberately primitives/booleans-only... no `Entity`/`Vec3d`/
  `Camera` types," Public API above), this geometric comparison cannot live
  in the cross-platform `FreecamHook`/`features/tweaks` layer at all — it
  needs a live `Entity`/`AABB`/`Vec3` comparison, which is exactly the kind
  of state the existing Architecture already keeps entirely platform-side.
  Recommended placement: `FreecamTicker` already holds direct references to
  both the phantom camera entity and the real player entity every tick
  (`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java`'s
  `lazuli$integrate`) — it computes this boolean once per tick and exposes
  it to the show-body mixins via a small **platform-only** method on
  `TweakHooksImpl` (not part of the `FreecamHook` interface), mirroring this
  same class's existing precedent for extra non-interface state beyond a
  hook's cross-platform contract (the plan's own cited precedent:
  `crosshairConfigurable(String)`/`setZoomActive(boolean)` — see
  `docs/specs/tweaks-no-rain-freecam-plan.md`'s Existing Implementation
  section). The show-body mixins' existing `@Redirect` condition
  (`hooks.isFreecamActive() && hooks.freecamShowOwnBody()`, e.g.
  `platform/fabric-26.2/src/main/java/de/lazuli/mixin/LevelExtractorFreecamShowBodyMixin.java:40`)
  becomes `hooks.isFreecamActive() && !hooks.<newMethod>()` (show the body
  when the camera is *not* inside the player, matching the inverted
  boolean's inverted meaning) — the mixin's redirect target/shape/ordinal
  are otherwise completely unchanged.
- **Behavior at activation**: the camera starts at the player's eye
  position (unchanged, `lazuli$activate`'s existing
  `camera.setPos(startPos.x, ...)` from `client.player.getEyePosition()`),
  which is always inside the player's own standing/current bounding box by
  construction — so the body starts hidden immediately on activation
  (matching vanilla's own default first-person "you don't see your own
  body" expectation) and becomes visible automatically once the camera
  flies far enough outside the (inflated) box. No special-casing needed for
  this — it falls out of the rule directly.

### AD-3 — Move Speed rescale by 10x (with config migration)

**Current shipped shape (confirmed by reading the live code this pass).**
`TweakDefinitions.FREECAM` defaults `moveSpeed` to `1.0`
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/TweakDefinitions.java:118`);
`ConfigSchemas.FREECAM`'s field is `numeric("moveSpeed", "Move Speed", 0.1,
10.0, 0.1)` — min `0.1`, max `10.0`, step `0.1`
(`features/tweaks/src/main/java/de/lazuli/features/tweaks/services/ConfigSchemas.java:115`).
Runtime, `FreecamTicker.lazuli$integrate` computes
`speed = baseSpeed * hooks.freecamMoveSpeed() * (sprint ? sprintMultiplier :
1)` with no other scale factor
(`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamTicker.java:108-110`).

**Target new shape.** `moveSpeed`: min `0.25`, max `5.0`, step `0.25`,
default `1.0`. Every bound is `0.25`-aligned by construction (min, step, and
max are all exact multiples of the step, giving a clean 20-position stepper
from `0.25` to `5.0`) — there is no analogue of a prior draft's "is the
minimum aligned to the step?" concern here, since `0.25` (the min) is
trivially aligned to a step of `0.25`. `ConfigFieldSpec`/`TweaksPanel`'s
existing `clamp(value + step, min, max)`-shaped increment logic
(`platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/TweaksPanel.java:540-543`)
does not require `min`/`max`/`step` to be aligned to a common grid in
general, but this range/step combination happens to be cleanly aligned
regardless.

**Runtime formula compensates so felt speed is unchanged for migrated
users.** A fixed compensating scale constant
(`MOVE_SPEED_RUNTIME_SCALE = 10.0f`) is introduced into `FreecamTicker`'s
speed computation:
`speed = baseSpeed * hooks.freecamMoveSpeed() * MOVE_SPEED_RUNTIME_SCALE *
(sprint ? sprintMultiplier : 1)`. Combined with the value migration below,
this means: for a user whose stored `moveSpeed` is migrated from old-scale
`10.0` to new-scale `1.0`, `baseSpeed * 1.0 * 10.0` produces the exact same
real-world flight speed as the old `baseSpeed * 10.0` did — the number
displayed/edited in the UI shrinks by 10x, but nothing about how fast the
camera actually flies changes for an existing user's already-tuned setting;
this holds for any migrated value, not just this one example, since
dividing by 10 on load and multiplying by 10 at runtime are exact inverses.
**A brand-new install's new default (`1.0`) is not derived by dividing the
old shipped default (also, coincidentally, `1.0`) by ten — it is a fresh
default chosen directly for the new `0.25`-`5.0` range** — so it does *not*
reproduce the old shipped default's felt speed
(`baseSpeed * 1.0 * 10.0 = baseSpeed * 10.0` under the new default, versus
the old shipped code's unscaled `baseSpeed * 1.0`). Only already-existing
users' own stored, previously-tuned settings are guaranteed unchanged felt
speed via the migration path below; a brand-new install simply starts at
the new default's own (faster) felt speed, same as any other tweak's
default value being a fresh design choice rather than a preserved legacy
behavior. `sprintMultiplier` is completely unaffected by this item
(unchanged range, step, default, and runtime role).

**Migration mechanism — chosen approach and why.** Per the two named
alternatives in the task: **(a) migrate stored values on load** is the
correct choice here, not (b) "leave stored values as-is, just change the
runtime multiplier." Reasoning: this framework's `TweaksConfigIO.parse`
already always applies the new schema's `min`/`max` semantics going forward
(there is no way for the UI to ever display/edit a value outside
`[0.25, 5.0]` again after this ships), so an old stored value like `10.0`
would either need silent clamping (jarring, and actively wrong here:
clamping `10.0` straight to the new max `5.0` without dividing would give
`baseSpeed * 5.0 * 10.0`, 5x their real old speed of `baseSpeed * 10.0` —
not a match by any coincidence, unlike an old-max-to-new-max mapping might
suggest), or a mid-range stored value like `5.0` (comfortably under the old
max, not needing clamping) would get **no** conversion at all under
approach (b) and would suddenly fly 10x faster than before
(`baseSpeed * 5.0 * 10.0` vs. the old `baseSpeed * 5.0`) — a real, silent,
surprising behavior change for every existing user regardless of what value
they had set, not just an edge case at the old max. Approach (a), migrating
the actual stored number (divide by 10 on load), avoids this for every
stored value: an old `10.0` becomes `1.0` (within the new range, and equal
to the new default purely as a worked-example coincidence, not by design),
an old `5.0` becomes `0.5`, and so on — each migrated value reproduces its
owner's exact old felt speed once the runtime compensation above is
applied.

**Precise mechanism (scoped to this one tweak/field, not a general schema-
version bump — consistent with this framework's existing "no schema version
bump, no migration code" design principle, Architecture — Framework Fit).**
A new internal (non-UI, no `ConfigFieldSpec` entry) boolean marker
configurable, `moveSpeedRescaled`, is added to `TweakDefinitions.FREECAM`'s
default configurables map with default value `true`. In
`TweaksConfigIO.parse`, when building `FREECAM`'s configurables map from a
loaded file (the existing "start from default configurables, overlay
whatever the file has" logic,
`features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java:96-107`):

1. If the raw parsed JSON's `FREECAM.configurables` object does not contain
   a `moveSpeed` key at all: no migration needed (either a brand-new file,
   or this tweak's configurables were never customized) — the default-
   overlay merge already yields the new default `1.0` with `moveSpeedRescaled`
   already `true` from the default map. Nothing further to do.
2. If it does contain a `moveSpeed` key **and** the same raw object also
   contains a `moveSpeedRescaled` key (any value — its mere presence is the
   signal): this file was already written by a build that has already
   migrated this tweak's `moveSpeed` — use the stored value as-is (already
   new-scale).
3. If it contains a `moveSpeed` key but **no** `moveSpeedRescaled` key: this
   file predates this corrective release. Divide the stored `moveSpeed`
   value by `10.0` before placing it into the in-memory `TweakState`, so it
   now represents the same real-world speed under the new scale/runtime
   formula.
4. In every case (whether migrated in step 3 or already-current in step 2),
   the resulting in-memory configurables map has `moveSpeedRescaled = true`
   set explicitly (already true by default-overlay in the common case;
   forced true after a step-3 migration) — the *next* time this config is
   serialized back to `tweaks.json` (any subsequent config-screen edit/save
   triggers a normal write, per the existing framework's normal
   write-through path), the marker is persisted, so this exact migration
   branch is a one-time, idempotent conversion per save file — a real file
   is never re-divided by 10 twice.
5. Defensive clamp: after the above, clamp the resulting `moveSpeed` value
   into `[0.25, 5.0]` regardless of path taken (guards against a
   hand-edited/malformed JSON value outside either scale's expected range).

This is a small, self-contained, `FREECAM`-only special case inside
`TweaksConfigIO.parse` (a handful of lines gated on `id == TweakId.FREECAM`),
not a general config-file schema-version field — deliberately narrower than
a full version bump, consistent with this feature area's established "no
schema version bump" design principle, while still fully and unambiguously
avoiding the silent-speed-change risk described above for every existing
stored value, not just an edge case that happens to clamp correctly.

**Updated `ConfigSchemas`/`TweakDefinitions` entries:**

```java
// TweakDefinitions.FREECAM default configurables
map("moveSpeed", 1.0, "sprintMultiplier", 2.0, "noclip", true, "moveSpeedRescaled", true)
// (showOwnBody entry removed per AD-2)

// ConfigSchemas.FREECAM field list
List.of(
    ConfigFieldSpec.numeric("moveSpeed", "Move Speed", 0.25, 5.0, 0.25),
    ConfigFieldSpec.numeric("sprintMultiplier", "Sprint Multiplier", 1.0, 5.0, 0.5),
    ConfigFieldSpec.bool("noclip", "Noclip")
    // no moveSpeedRescaled row (internal-only, no ConfigFieldSpec entry)
    // no showOwnBody row (removed per AD-2)
)
```

### Updated Configuration shape (post-AD-2/AD-3)

```json
{
  "tweaks": {
    "FREECAM": {
      "enabled": false,
      "configurables": {
        "moveSpeed": 1.0,
        "sprintMultiplier": 2.0,
        "noclip": true,
        "moveSpeedRescaled": true
      }
    }
  }
}
```

(`showOwnBody` no longer appears; `moveSpeedRescaled` is new and internal —
present in the file but not rendered in `TweaksPanel`.)

### AD-4 — Noclip-off collision bug: zero-size bounding box

**Confirmed root cause (read directly from shipped code and its own
Javadoc, high confidence, no new `javap` needed to establish this part).**
`FreecamCameraEntity` is backed by `EntityType.MARKER`/`EntityTypes.MARKER`
in its constructor (`super(EntityType.MARKER, level)`/`super(EntityTypes.MARKER,
level)` across all three platforms) purely as a convenient placeholder type,
per each file's own class Javadoc — e.g. the 1.21.11 file explicitly states
"this incidentally also gives it MarkerEntity's own zero-size bounding box;
the block-collision sweep below therefore acts on a point rather than a
body-sized box, a deliberate v1 simplification"
(`platform/fabric-1.21.11/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java:32-37`).
A zero-size (point) bounding box passed into the (otherwise-correct, per
Architecture and row 115) block-collision-sweep primitive has nothing to
collide against in practice for most block shapes — this is the confirmed
cause of `noclip = false` failing to stop the camera from passing through
blocks.

**Target fix.** Give `FreecamCameraEntity` a fixed, non-zero, small
"head-sized" bounding box, applied on all three platforms, instead of
inheriting `EntityType.MARKER`/`EntityTypes.MARKER`'s zero-size dimensions.

**Target dimensions.** A cube with width = height = depth ≈ **0.45 blocks**.
Derivation (grounded in the standard, version-stable Minecraft player skin/
model proportions, not a Minecraft-jar API constant — this is a design
target, not something `javap` can itself reveal, since it is baked into
`PlayerModel`/`PlayerEntityModel`'s mesh-builder call, not stored as a
runtime field): a standing player model is conventionally 32 pixels tall
(8px head + 12px torso + 12px legs) mapped onto the vanilla standing hitbox
height of 1.8 blocks, i.e. 1 pixel ≈ 1.8 / 32 = 0.05625 blocks; the head
cube is 8×8×8 pixels, i.e. 8 × 0.05625 ≈ 0.45 blocks per side. This gives a
box meaningfully smaller than the full player body (0.6×1.8), so the camera
can fit through openings a full player couldn't — matching the intuitive
"it's a floating head/eye, not a floating body" framing implied by
"roughly the width/height of a player head hitbox" — while still being
large enough to reliably register collision against ordinary full-block
shapes (a literal zero-size point, today's bug, is the failure mode being
fixed; a small non-zero cube is the fix, and 0.45 blocks is comfortably
larger than any floating-point/interpolation jitter that could cause a
near-zero-size box to occasionally miss a block face).

**Mechanism (medium confidence, javap-blocked per-platform — this is a
genuinely new override point, not covered by any of rows 112-116/119/120's
existing confirmations).** Override the entity's dimensions to this fixed
box, letting vanilla's own existing position-to-bounding-box bookkeeping do
the rest for free: `Entity` instances already recompute their bounding box
from their own `dimensions` field every time position changes via
`setPos(...)`/`setPosition(...)` (the same call `lazuli$integrate` already
makes every tick, e.g.
`platform/fabric-26.2/src/main/java/de/lazuli/tweaks/FreecamCameraEntity.java:114`)
— so overriding just the dimensions source, once, should keep the bounding
box correctly sized and positioned automatically without any additional
per-tick bookkeeping in `lazuli$integrate`. Candidate override point per
platform (all **medium confidence, to be `javap`-confirmed as the mandatory
first implementation step for this item**, exactly as this repo's
established convention requires for every new vanilla API target):

- **Yarn (1.21.11)**: `Entity.getDimensions(EntityPose pose)` (public,
  overridable, returns `net.minecraft.entity.EntityDimensions`) — override
  to return `EntityDimensions.fixed(0.45F, 0.45F)`-shaped (a `float width,
  float height` fixed, non-eye-height-scaling box; exact factory method
  name/overload to confirm via `javap` against this repo's own resolved
  1.21.11 jar).
- **Mojmap (26.1/26.2)**: `Entity.getDimensions(Pose pose)` (public,
  overridable, returns `net.minecraft.world.entity.EntityDimensions`) —
  same shape, Mojmap-renamed; confirm independently on both 26.1 and 26.2
  per this repo's own repeated finding that the two sometimes diverge
  despite both being "26.x Mojmap" (rows 69/104/113/116) — do not assume
  identical without checking.
- If `javap` reveals `getDimensions(Pose)` is not the actual live source of
  `getBoundingBox()`'s size for this constructor path (e.g. if
  `EntityType.MARKER`/`EntityTypes.MARKER`'s own registered dimensions are
  read once at construction into a different field that `getDimensions`
  does not actually drive for a never-refreshed entity), the fallback is a
  direct one-time `this.setBoundingBox(...)`-shaped call in the constructor
  or in `lazuli$integrate` recomputing the box from the entity's current
  position each tick using a fixed `0.45×0.45` `AABB`/`Box` — a strictly
  more manual but equally valid alternative if the cleaner override point
  doesn't pan out. Either way the *target size* (0.45-block cube) and
  *target outcome* (the block-collision-sweep primitive now has a real,
  non-zero box to sweep, so `noclip = false` actually stops the camera at
  block boundaries) are unambiguous; only the exact mechanism is
  `javap`-pending.

**Findings to add to `.claude/context/minecraft.md`** once confirmed at
implementation time: the exact `getDimensions`/`EntityDimensions.fixed(...)`
(or equivalent) signatures actually used per platform, and whether
`getDimensions(Pose)` is in fact live for a manually-driven, never-ticked
`Entity` subclass's `getBoundingBox()` result (a genuinely new question this
spec raises, not yet answered by any existing row).

### AD-5 — Inverted strafe

**Confirmed root cause (read directly from shipped code, high confidence —
identical bug present verbatim on all three platforms).**
`FreecamTicker.lazuli$integrate` computes the strafe axis as:

```java
double strafe = (rawInput.right() ? 1.0 : 0.0) - (rawInput.left() ? 1.0 : 0.0);
```

on all three platforms (confirmed identical text at
`platform/fabric-1.21.11/.../FreecamTicker.java:124`,
`platform/fabric-26.1/.../FreecamTicker.java:102`,
`platform/fabric-26.2/.../FreecamTicker.java:104`), then feeds the resulting
`Vec3d`/`Vec3`'s `x` component (`new Vec3d(strafe, 0.0, forward)`/`new
Vec3(strafe, 0.0, forward)`) into `Entity`'s own inherited
`movementInputToVelocity`/`getInputVector` helper (Architecture, row 115) —
the same yaw-relative input-to-world-space-velocity conversion every living
entity's own `travel(Vec3d)` uses for WASD movement. That vanilla helper's
`relative.x` parameter is, by vanilla's own long-standing convention (the
same convention every player's normal WASD walking already relies on, and
therefore already correctly exercised elsewhere in this exact codebase —
`forward`/`vertical` on the adjacent lines are unaffected and correctly
signed), **positive for left, negative for right** — i.e. the two operands
in the subtraction above are backwards: `right` should be subtracted from
`left`, not the other way around. This single-line sign inversion is the
entire bug; it does not touch `forward`
(`rawInput.forward() - rawInput.backward()`, correct as shipped) or
`vertical` (`rawInput.jump() - rawInput.sneak()/shift()`, correct as
shipped) at all.

**Target fix.** Swap the two operands on that one line, on all three
platforms, and nowhere else:

```java
double strafe = (rawInput.left() ? 1.0 : 0.0) - (rawInput.right() ? 1.0 : 0.0);
```

**Target behavior.** Pressing A (left) strafes the camera to its own left;
pressing D (right) strafes it to its own right, matching the key names and
matching vanilla creative-flight/walking's own A/D behavior exactly. W/S
(forward axis) and Space/Sneak (vertical axis) are provably unaffected by
this fix (different lines, not touched). No other file changes — this is a
single-line, three-platform, mechanically-identical fix, and per this
item's own task framing does not itself require a fresh `javap` pass (the
fix is derived directly from the already-shipped, already-`javap`-confirmed
`row 115` finding about which vanilla helper consumes this value and how,
combined with the empirically-reported inverted behavior) — though the
standard manual in-game verification (press A, confirm camera strafes left;
press D, confirm it strafes right) remains part of this item's acceptance
check, same as every other tweak's manual verification discipline.

### Addendum — updated Public API summary

```java
public interface FreecamHook {
    boolean isFreecamActive();
    float freecamMoveSpeed();       // now reads the new-scale (0.25-5.0) value; AD-3
    float freecamSprintMultiplier(); // unchanged
    boolean freecamNoclip();         // unchanged
    // freecamShowOwnBody() REMOVED — AD-2, replaced by an internal,
    // platform-only TweakHooksImpl method (not part of this interface),
    // computed geometrically each tick by FreecamTicker.
}
```

### Addendum — open items for planning (javap-blocked, mandatory first step)

1. **AD-1's mouse-look call site** — exact class/method per platform, and
   critically whether it already keys off the camera-entity accessor
   (no-mixin-needed branch) or hard-codes `client.player` (mixin-needed
   branch) — the single most consequential unknown left by this Addendum,
   since it determines AD-1's implementation shape and size.
2. **AD-2's exact `AABB`/`Box` `inflate`/`expand` and `contains` overload
   names per platform** — both expected high-confidence/stable, but
   unconfirmed against this repo's exact three pins.
3. **AD-4's exact bounding-box override mechanism** — `getDimensions(Pose)`-
   shaped override vs. a manual `setBoundingBox` fallback; exact
   `EntityDimensions` factory method name/signature; whether 26.1 and 26.2
   diverge here (check independently, per this repo's own repeated
   precedent for that pairing surprising past features).
4. **AD-3's `TweaksConfigIO.parse` migration branch** is plain Java logic
   (no vanilla/Minecraft-jar dependency at all) — not `javap`-blocked, fully
   implementable and unit-testable (extend
   `TweaksConfigIOTest`) without any platform-specific work first.
5. **AD-5** is not `javap`-blocked at all (root cause and fix both already
   fully determined by reading the shipped source this pass) — safe to
   implement first, independent of the other four items' `javap` spikes.
