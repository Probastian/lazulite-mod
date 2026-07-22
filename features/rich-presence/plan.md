# Implementation Plan — Rich Presence Publishing

## Summary
Build `features/rich-presence` as a brand-new, standalone Gradle feature
module, following exactly the `features/steam-world-hosting` /
`features/server-browser` structural precedent: an `api`-layer + `services`
layer plain-JVM core (tier computation, precedence resolution, debounce,
translation-key selection) plus three structurally-parallel platform-module
implementations for the `net.minecraft.*`-typed signal reads (dimension,
biome, pause/menu, vehicle, movement/placement history, Villager/Bell
proximity). The feature's only Steam-facing call is a new caller of the
already-existing `SteamFriendsGateway.setLocalRichPresence`/
`clearLocalRichPresence` (both already added to that interface during
`steam-world-hosting`'s planning, per that plan's Decision 1) — no gateway
signature change needed. No implementation code is written by this plan.

## Existing Implementation

### `services/SteamFriendsGateway` — already carries everything this feature needs
Confirmed via direct read of `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`
(added by `steam-world-hosting`'s plan, Decision 1, already shipped per this
session's git history — `HostingLifecycle` is its current sole caller for
`"connect"`):
```java
boolean setLocalRichPresence(String key, String value);
void clearLocalRichPresence();
```
Both already implemented for real in `SteamworksSteamFriendsGateway`
(wraps `SteamFriends.setRichPresence`/`clearRichPresence`, both confirmed
present in the pinned `steamworks4j-1.10.0.jar` per
`.claude/context/minecraft.md`'s existing verification rows) and as no-ops in
`NoopSteamFriendsGateway`. This feature is a **new caller only** — `key =
"status"` exclusively, never `"connect"` (FR-RP5) — no interface change, no
new gateway method, no risk of a merge conflict with `HostingLifecycle`'s own
`"connect"` write since the two calls target different keys on the same
already-shared `SteamFriendsGateway` instance (already hand-off-published by
`SteamworksClientInitializer` in every platform module via
`SteamFriendsGatewayHandoff.require()` — this feature reuses that existing
hand-off, does not add a new one).

### `HostingPresenceScanner` — the scan-then-map split precedent this plan mirrors
`features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostingPresenceScanner.java`
already establishes the shape this feature's core component copies: a
plain-JVM class that (a) is handed already-gathered raw signal values (there,
`gateway.friendRichPresenceValue(id, "connect")` strings; here, a small
signal-bundle value object) and (b) does pure precedence/decoding logic
against them (there, `ConnectStringCodec.decode` + set membership; here, tier
precedence + string composition), with zero `net.minecraft.*` import of its
own. This plan's `PresenceStatusResolver` (see Decisions) follows the same
split: platform-module code gathers `net.minecraft.*`-typed raw signals into
a plain data object every tick, and hands that object to the plain-JVM
resolver, which never touches Minecraft classes.

### `features/steam-world-hosting` — directory-layout/Gradle-wiring precedent
Confirmed via `Glob`/`Read` of that module's actual `build.gradle` and
`src/main/java/.../worldhosting/` package tree:
- `build.gradle`: `api project(':api')` (its own `api`-sub-package types leak
  into public service signatures) + `implementation project(':services')`
  (consumes `SteamFriendsGateway` but never re-exposes a steamworks4j type).
- Package tree: `de.lazuli.features.worldhosting.{api,config,services,events,gui,mixins}`,
  the latter three as `package-info.java`-only placeholders when a feature
  has no bundled resources needing them yet (Netty/mixin glue there lives in
  `platform/`, per that plan's Architecture note) — an equivalent placeholder
  choice applies to this feature's `gui`/`mixins`/`events` packages (this
  feature needs none of the three for real code, only `api`/`config`/`services`).
- `Noop*` pairing convention: every real service has a `Noop*` twin
  constructed whenever `!SteamAvailability.isSteamAvailable()` (or config
  disabled), reused here for `NoopLocalPresenceTracker`/
  `NoopRichPresencePublisher`.
- `settings.gradle` registers each feature as `include 'features:<name>'`,
  alphabetically-adjacent to existing entries — `features:rich-presence` is
  inserted as a new line (exact position: after `features:friends-sidebar`
  and before `features:server-browser`, matching the file's roughly
  alphabetical existing ordering, though the file is not strictly sorted
  today so any consistent insertion point is acceptable).

### Own-profile "In Game" fallback — current state (FR-RP6's integration point)
`features/friends-sidebar/specification-own-profile-ingame-status.md` (a
**separate, already-approved but not-yet-confirmed-implemented** spec per its
own header) fixes the own-row's status color/label to always show a generic
`"In Game"` string via `FriendSidebarStateMachine.statusLabel(personaState, true)`,
independent of `friend.inGame()`. That spec's own Future Extensions section
explicitly reserves the exact slot this feature's FR-RP6 fills: "Replacing
the generic 'In Game' fallback with a live, richer status string is the
entire subject of `specification-richpresence-publishing.md`... its own-row
consumption point should slot into FR-OP2's fallback chain without requiring
further changes to this spec's own scope." **This plan does not modify that
spec's file or its implementation** — FR-RP6's `RichPresenceFacade.localPresenceStatus()`
accessor is built and exposed by this plan (Files to Create), but wiring it
into `FriendSidebarWidget`'s own-row branch is left as a follow-up amendment
to that spec's own implementation, per the specification's own Non-goals
framing ("this feature's Rich Presence publishing itself... has no hard
dependency on that spec and can ship independently"). This plan's Acceptance
Criteria therefore scope FR-RP6 to "the accessor exists and is hand-off
publishable," not "the sidebar row visibly changes."

## Decisions on the Open Questions (resolved during planning)

### 1. Rolling-window thresholds — proceed with spec's proposed defaults, no override
150s window / 250-block Exploring distance / 32-block Staying radius /
20-placements-per-60s Building threshold / 24-block bell radius / 32-block
3-villager Near-a-Village thresholds — all taken as-is from the specification
(Open Question 1). These are plain `int`/`long` constants on the plain-JVM
resolver's signal-bundle input, trivially unit-testable at their exact
boundary values (Test Strategy) and trivially tunable later without any
platform-module change, since the platform side only ever reports raw
measured values (distance moved, placement count, villager count, bell
presence) — never pre-classifies them itself.

### 2. Digging-around vs Exploring-while-underground sub-ordering — proceed with spec's proposed ordering
Exploring (great distance) wins over Digging-around when both signals are
present simultaneously (spec Tier Priority, level 7) — implemented as a
resolver-internal check ("is Exploring true? if so, ignore the
underground flag entirely"), not a separate precedence level. No override.

### 3. Session-active predicate — reuse `specification-own-profile-ingame-status.md`'s FR-OP1 predicate, independently implemented
The spec's own Open Question 3 assumes parity with FR-OP1 ("world loaded,
including while at the pause menu"). This plan implements that predicate
**independently** in each platform module's own signal-gathering code
(`Minecraft.getInstance().level != null` / Mojang, `MinecraftClient.getInstance().world != null` /
Yarn — exact accessor to be confirmed at implementation per the existing
divergence rows in `.claude/context/minecraft.md`), rather than depending on
`friends-sidebar`'s own `FriendsSidebarFacade.isSteamAvailable()`/session
logic at compile time — this feature has no project dependency on
`features:friends-sidebar` in either direction (per the specification's
Architecture section), so the predicate is duplicated, not shared. This is an
accepted, intentional small duplication (two features independently deciding
"is a world loaded"), not a bug — consistent with the specification's own
"no dependency on friends-sidebar... beyond the one optional read accessor"
framing.

### 4. Phasing — ship independently of friends-sidebar's own-row consumption
Per Open Question 4 and the specification's own Non-goals/Compatibility
framing, this plan builds and ships `features/rich-presence`'s Steam-facing
publishing (FR-RP1–FR-RP5, FR-RP7) as a complete, independently mergeable
unit. FR-RP6 (`RichPresenceFacade`) is built as part of this same plan (it is
this feature's own public API, not a cross-feature edit), but the
**consuming** edit inside `friends-sidebar`'s `FriendSidebarWidget` is
explicitly out of scope for this plan's Files to Modify — it is a follow-up,
separately planned change once both specs' implementations exist. Recorded
here so the user can override if they'd rather land both together.

### 5. Core component naming and package shape — `LocalPresenceTracker` (interface) / `PresenceStatusResolver` (plain resolver) / `RichPresencePublisher` (debounced-write wrapper)
Per the spec's own Public API section (illustrative-only), this plan commits
to three distinct plain-JVM classes rather than one monolith, splitting
"compute the tier" from "decide whether to write" from "gather raw signals"
along the same scan-then-map line `HostingPresenceScanner` established:

- `features/rich-presence/services/PresenceSignals.java` — a plain, immutable
  data-carrier record populated once per platform-module tick by
  `net.minecraft.*`-touching code, holding **only primitive/plain types**:
  ```java
  public record PresenceSignals(
      boolean sessionActive,     // FR-RP7 / Decision 3
      boolean paused,
      boolean spectating,
      boolean nether,
      boolean end,
      String biomeTranslationKey,     // e.g. "biome.minecraft.plains", empty if none
      VehicleKind vehicleKind,        // enum: NONE, MINECART, BOAT
      boolean underground,            // Overworld-only Y/sky-light heuristic, already computed by platform code
      double displacement150s,        // straight-line, blocks
      double stayRadius150s,          // max distance from window-start anchor
      int blockPlacements60s,
      boolean nearVillageBell,        // bell within 24 blocks
      int nearVillagerCount           // villagers within 32 blocks
  ) {}
  ```
  Zero Minecraft import — this is the seam between "gather" (platform) and
  "resolve" (plain-JVM), mirroring `HostingPresenceScanner`'s own raw-string
  input shape.
- `features/rich-presence/services/PresenceStatusResolver.java` — pure
  function `PresenceTier resolve(PresenceSignals)` implementing the entire
  precedence ladder (spec's Tier Priority section, levels 1–7 plus the
  Digging-around/Exploring sub-rule, Decision 2) and returning a small
  `PresenceTier` value (a sealed-interface-shaped enum-plus-arguments record,
  e.g. `record PresenceTier(TierKind kind, Optional<String> biomeTranslationKey, boolean nether, boolean end)`)
  — **never itself producing a translated String** (that step needs
  `Text`/`Component`, a platform concern) — only which lang key(s) and
  arguments apply. This is the single most important unit-test target in
  this feature (Test Strategy).
- `features/rich-presence/services/LocalPresenceTracker.java` — the public
  interface from the spec's Public API item 1, `Optional<String>
  currentStatus()`. Its real implementation
  (`LocalPresenceTrackerImpl`, still plain-JVM) composes
  `PresenceStatusResolver` with a small, injected `TierTextFormatter`
  functional interface (`String format(PresenceTier)`) — the **only** seam
  where platform code plugs in a `Text.translatable(...).getString()`-based
  formatter. This keeps `LocalPresenceTrackerImpl` itself free of
  `net.minecraft.*` imports (only its injected formatter, supplied by
  platform code, touches `Text`/`Component`), matching the spec's own
  Public API illustrative shape while satisfying NFR-style plain-JVM
  testability (mirrored from `steam-world-hosting`'s Goals/NFR1 framing —
  this specification has no explicitly numbered NFR section, but this plan
  applies the same discipline by analogy, per the task's own instruction to
  be explicit about the unit-testable/manual split).
- `features/rich-presence/services/RichPresencePublisher.java` — owns the
  debounce (FR-RP4): holds `lastWrittenStatus: Optional<String>`, exposes
  `void tick()` which calls `tracker.currentStatus()`, compares against the
  last-written value, and only on an actual change calls
  `gateway.setLocalRichPresence("status", value)` (present) or
  `gateway.clearLocalRichPresence()` (empty, FR-RP7) — **but only clears if
  the previous state was non-empty**, avoiding a redundant `clearRichPresence()`
  IPC call every tick while already at the main menu. Constructor-injected
  `SteamFriendsGateway` (reused hand-off, Existing Implementation) and
  `LocalPresenceTracker`.
- `NoopLocalPresenceTracker`/`NoopRichPresencePublisher` — standard disabled-
  state pair (Steam unavailable), consistent with the repo-wide convention.

### 6. `RichPresenceFacade` (FR-RP6) — thin read-only wrapper, no new logic
`features/rich-presence/api/richpresence/RichPresenceFacade.java` (public
API item 2) is a **one-method** interface, `Optional<String>
localPresenceStatus()`, backed directly by the already-constructed
`LocalPresenceTracker` instance (same object `RichPresencePublisher` polls —
"single source of truth," per the specification's Goals). Implemented as a
trivial pass-through (`RichPresenceFacadeImpl(LocalPresenceTracker tracker)`)
— no caching, no separate poll cadence; a caller invoking it between this
feature's own tick sweeps simply gets the same value the last sweep
computed, acceptable per the spec's own framing (a per-tick-recomputed
transient value, Persistence section).

### 7. Per-platform signal-gathering shape — one `PresenceSignalGatherer` per module, tick-driven
Each platform module gets a single new class,
`platform/fabric-<version>/src/main/java/de/lazuli/richpresence/PresenceSignalGatherer.java`,
registered on `ClientTickEvents.END_CLIENT_TICK` (same hook shape
`FriendsSidebarClientInitializer` already uses for its own per-tick sweep),
producing one `PresenceSignals` per tick and feeding it to a shared,
per-platform-owned `LocalPresenceTrackerImpl`/`RichPresencePublisher` pair via
a plain setter (`PresenceSignals current` field, no queue needed — single-
threaded client-tick access only, unlike `steam-world-hosting`'s genuine
cross-thread Netty poller case). This class is the **sole** owner of every
`net.minecraft.*`-typed read this feature needs:
- Session-active/paused/dimension: `Minecraft.getInstance()` (Mojang) /
  `MinecraftClient.getInstance()` (Yarn) — `.level`/`.world` null-check,
  `.isPaused()`(likely same name both sides, **not yet `javap`-confirmed**),
  `.player.level().dimension()` (Mojang) / `.player.getWorld().getRegistryKey()` (Yarn)
  compared against `Level.NETHER`/`Level.END` (Mojang) or
  `World.NETHER`/`World.END` (Yarn) — **exact accessor names not yet
  `javap`-confirmed**, flagged as this plan's largest concrete unknown (Risks).
- Biome translation key: player position → `Level.getBiome(BlockPos)`
  (Mojang)/`World.getBiome(BlockPos)` (Yarn) → registry key → 
  `"biome.<namespace>.<path>"` string, following the spec's own FR-RP3
  convention (registry-key-derived, same idiom this repo already uses
  elsewhere per `.claude/context/minecraft.md`'s biome/registry rows —
  **exact `Holder<Biome>`/registry-key unwrap method not yet `javap`-confirmed**).
- Spectating: `player.isSpectator()` — spec confirms this signal as-is
  (Requirements, "Spectating" row), likely identical across all three
  modules (same convention as other simple boolean gamemode checks already
  in this codebase), still to be `javap`-confirmed.
- Vehicle kind: `player.getVehicle() instanceof <Minecart type>` /
  `instanceof <Boat type>` — exact minecart/boat base-class names (Mojang:
  `AbstractMinecart`/`Boat`; Yarn: `AbstractMinecartEntity`/`BoatEntity`) to
  be `javap`-confirmed per module.
- Underground heuristic: player Y-position vs. a fixed threshold and/or
  `Level.canSeeSky(BlockPos)` (Mojang)/`World.isSkyVisible(BlockPos)` (Yarn)
  — exact name divergence to be `javap`-confirmed; Overworld-only gate reuses
  the same dimension check above.
- Movement/placement rolling window: a small ring-buffer/rolling-aggregate
  owned by this same gatherer class (per spec Performance section, "small
  ring buffer... not a full history rescan every tick") — sampled once per
  tick from `player.getX()/getZ()` (position) and a `UseBlockCallback`/
  `PlayerBlockBreakEvents`-analogous **block-place** hook. Block-placement
  detection is the one genuinely new signal source this feature needs beyond
  simple per-tick polling — likely via Fabric API's
  `net.fabricmc.fabric.api.event.player.UseBlockCallback` (fires on right-click
  block-use, an imperfect proxy for "placed a block" since it also fires on
  non-placing right-clicks) or, more precisely, a `ServerBlockEvents`-style
  hook if one exists client-side, or (fallback) diffing
  `Level.getBlockState(pos)` around the player's reach each tick is too
  expensive — **the exact placement-detection mechanism is not yet
  `javap`/API-confirmed and is flagged as an open implementation-time
  question** (Risks) rather than assumed; a `BlockPlaceCallback`-analogous
  Fabric API v1 hook is the working assumption, but this repo has no
  existing precedent for hooking block placement (checked: no prior feature
  in this codebase does this), so there is no established idiom to copy from
  the way `ClientTickEvents.END_CLIENT_TICK` already is for per-tick polling.
- Near-a-Village: `Level.getEntitiesOfClass(Villager.class, AABB)` (Mojang) /
  `World.getEntitiesByClass(VillagerEntity.class, Box, predicate)` (Yarn),
  centered on player position, radius 32; bell lookup via a bounded
  loaded-chunk block-entity scan (spec's own recommendation: "prefer scanning
  the level's own loaded block-entity list per nearby chunk over a raw
  per-block scan") — exact "get all loaded block entities near position"
  accessor (there is no single obvious vanilla method for this; likely
  iterating `Level.getChunk(...).getBlockEntities()` per nearby chunk, sub
  from the player's chunk position outward by radius/16) is **not yet
  confirmed to exist as a clean API on either mapping side** — flagged as
  this plan's second-largest concrete unknown (Risks), with a documented
  fallback (a bounded `BlockPos.betweenClosed` iteration checking
  `getBlockEntity(pos) instanceof BellBlockEntity` for a 24-block radius is
  always correct, just potentially slower — up to ~49*49*(height) positions
  in the worst case if no cheaper accessor is found; acceptable given this
  check's cadence is capped at the tracker's own recompute interval, not
  per-frame).

### 8. Recompute interval — every tick for direct signals, but debounced write (not "once per tick" for the expensive Near-a-Village check)
The specification says "once per tick/sweep (exact interval a planning-phase
decision)" (FR-RP1). This plan sets: cheap signals (pause/dimension/spectator/
vehicle/biome/movement-ring-buffer-sample) recomputed every client tick
(negligible cost, matches spec Performance section); the Near-a-Village
bounded-radius entity/block-entity scan is throttled to once per **20 ticks
(~1 second)**, matching the general cadence class of every other per-tick-
gated feature sweep in this codebase (`FriendsSidebarConfig.refreshIntervalSeconds()`,
`HostingPresenceScanner`'s own interval-gating) rather than every tick — an
explicit, named interval constant (`NEAR_VILLAGE_SCAN_INTERVAL_TICKS = 20`)
on `PresenceSignalGatherer`, proposed default, tunable, not spec-mandated
(flagged for the user, consistent with how the spec itself flagged its own
thresholds as proposed defaults).

## Files to Create

### `api` module (top-level, zero dependencies)
- `api/src/main/java/de/lazuli/api/richpresence/RichPresenceFacade.java` —
  `Optional<String> localPresenceStatus();` (spec Public API item 2, Decision 6).

### `features/rich-presence` module (new Gradle subproject)
- `features/rich-presence/build.gradle` — `api project(':api')` (the new
  `RichPresenceFacade`/`PresenceTier`-adjacent types leak into this
  feature's own public accessor signatures) + `implementation project(':services')`
  (consumes `SteamFriendsGateway`, never re-exposes a steamworks4j type) —
  identical shape/rationale-comment convention to `steam-world-hosting`'s and
  `server-browser`'s own `build.gradle` files (Existing Implementation).
- `features/rich-presence/README.md`

**`api/` sub-package** (`de.lazuli.features.richpresence.api`, feature-internal):
- `RichPresenceConfig.java` — record: `enabled` (default `true`) + `DEFAULT`
  constant, matching every other feature's config-shape convention (even
  though the specification's own Configuration section says "no in-mod
  settings UI" — the `enabled` flag still gates whether this feature's real
  vs. `Noop*` services are constructed, same as every other feature's
  always-`true`-by-default config record; no UI is bound to it, per spec).

**`config/` sub-package**:
- `RichPresenceConfigIO.java` — `config/rich-presence.json` load/parse/
  serialize; malformed → defaults + warning, never throws (same
  `HelloWorldMainMenuConfigIO`-shaped precedent every prior feature reuses).

**`services/` sub-package** (Decision 5/6):
- `PresenceSignals.java` — plain signal-bundle record.
- `VehicleKind.java` — enum: `NONE, MINECART, BOAT`.
- `PresenceTier.java` — resolved-tier value type (kind + optional biome key +
  nether/end flags).
- `TierKind.java` — enum: `MAIN_MENU, PAUSED, SPECTATING, RIDING_MINECART,
  RIDING_BOAT, NEAR_VILLAGE, EXPLORING, STAYING, BUILDING, DIGGING_AROUND`.
- `PresenceStatusResolver.java` — pure `PresenceTier resolve(PresenceSignals)`.
- `LocalPresenceTracker.java` — public interface (spec Public API item 1).
- `LocalPresenceTrackerImpl.java` — real impl, composes
  `PresenceStatusResolver` + injected `TierTextFormatter`.
- `TierTextFormatter.java` — functional interface, `String format(PresenceTier)`
  (the sole seam platform code implements with `Text`/`Component`).
- `RichPresencePublisher.java` — debounced-write wrapper (FR-RP4/FR-RP5/FR-RP7).
- `NoopLocalPresenceTracker.java`, `NoopRichPresencePublisher.java` —
  disabled-state pair.
- `RichPresenceFacadeImpl.java` — implements `api`'s `RichPresenceFacade`,
  backed by the shared `LocalPresenceTracker` instance (Decision 6).

**`events/`, `gui/`, `mixins/` sub-packages** — each a `package-info.java`
placeholder, mirroring `steam-world-hosting`'s identical placeholder
convention for feature packages with no real code this iteration (this
feature needs no mixin — every signal is read via ordinary client-tick
polling, no Netty/mixin glue at all, a materially simpler feature than
`steam-world-hosting` in this respect).
**`resources/`** — `.gitkeep` (no bundled assets in this module itself; lang
keys live in `platform/*/assets`, per FR-RP2).

**`tests/`** (`src/test/java/de/lazuli/features/richpresence/...`):
- `PresenceStatusResolverTest` — the primary unit-test target (Test Strategy):
  given a hand-built `PresenceSignals` for each precedence level and each
  documented tie-break (Main Menu > Paused > Spectating > Riding > Near
  Village > movement-derived > Digging-around-as-specialization, plus the
  Exploring-wins-over-Digging-around sub-rule, Decision 2), assert the
  expected `PresenceTier`. Also covers exact rolling-window boundary values
  (e.g. exactly 250 blocks displacement, exactly 21 vs 20 placements in 60s,
  exactly 3 vs 2 villagers, exactly 24-block bell distance) as explicit
  edge-case assertions, since these are the specification's own
  "confirm-or-override, proposed default" thresholds (Decision 1) — pinning
  their exact boundary behavior in a test is the cheapest way to make a
  future threshold tweak a single-constant change with immediate regression
  coverage.
- `RichPresencePublisherTest` — given a fake `LocalPresenceTracker` (returns
  a scripted sequence of `Optional<String>` values across successive
  `tick()` calls) and a fake/mock `SteamFriendsGateway` (Mockito, already
  available per every other feature's test dependency set), assert
  `setLocalRichPresence("status", ...)` is called only on an actual value
  change (FR-RP4), `clearLocalRichPresence()` is called only on a
  present-to-empty transition (not repeatedly while already empty, Decision 5),
  and `setLocalRichPresence` is **never** called with `key = "connect"`
  (FR-RP5 — a direct, cheap regression guard for the one non-goal this
  feature must never violate).
- `RichPresenceConfig`/`RichPresenceConfigIO` round-trip + malformed-fallback
  tests (mirrors every prior feature's own config-test shape).

### Platform modules — one signal gatherer + composition-root wiring per module (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/RichPresenceClientInitializer.java`
  — new `ClientModInitializer`; composition root. Calls
  `SteamworksServiceHandoff.require()` + `SteamFriendsGatewayHandoff.require()`
  (both already-existing hand-offs, reused as-is), loads
  `RichPresenceConfig`, constructs the real or `Noop*` service set (gated on
  `SteamAvailability.isSteamAvailable() && config.enabled()`), constructs
  this module's `PresenceSignalGatherer` + its `TierTextFormatter`
  implementation, registers `ClientTickEvents.END_CLIENT_TICK` (gather → feed
  into `LocalPresenceTrackerImpl` → `RichPresencePublisher.tick()`),
  publishes a new hand-off, `RichPresenceFacadeHandoff` (same
  publish/require shape as every other hand-off in this codebase), carrying
  the constructed `RichPresenceFacade`/`NoopRichPresenceFacade` for FR-RP6's
  future cross-feature consumption.
- `platform/fabric-<version>/src/main/java/de/lazuli/richpresence/PresenceSignalGatherer.java`
  — the sole `net.minecraft.*`-touching signal-gathering class (Decision 7),
  owns the movement/placement rolling-window ring buffer and the throttled
  Near-a-Village scan.
- `platform/fabric-<version>/src/main/java/de/lazuli/richpresence/MinecraftTierTextFormatter.java`
  — implements `TierTextFormatter`, the sole `Text`/`Component`-touching
  class in this feature: `Text.translatable("lazuli.presence.<key>", args...).getString()`
  (Mojang: `Component.translatable(...)`), composing the dimension suffix
  (spec's "orthogonal modifier," Tier Priority) and biome/entity nested
  arguments per FR-RP3.
- `platform/fabric-<version>/src/main/java/de/lazuli/RichPresenceFacadeHandoff.java`
  — new per-module hand-off (Decision 6), byte-identical shape to every
  existing hand-off class in this codebase.
- `platform/fabric-<version>/src/main/resources/assets/lazuli/lang/en_us.json`
  (merged into whatever this module's file already is) — new
  `lazuli.presence.*` keys (FR-RP2): `lazuli.presence.exploring`,
  `lazuli.presence.staying`, `lazuli.presence.building`,
  `lazuli.presence.digging_around`, `lazuli.presence.driving`,
  `lazuli.presence.sailing`, `lazuli.presence.near_village`,
  `lazuli.presence.dimension_suffix` (or two keys,
  `lazuli.presence.dimension_suffix.nether`/`.end`, exact structure a small
  implementation-time formatting choice), `lazuli.presence.spectating`.

## Files to Modify
- `settings.gradle` — add `include 'features:rich-presence'` (Existing
  Implementation's insertion-point note).
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/resources/fabric.mod.json`
  — `"client"` array gains a new entry,
  `"de.lazuli.RichPresenceClientInitializer"`, positioned **after**
  `"de.lazuli.SteamworksClientInitializer"` (needs
  `SteamFriendsGatewayHandoff`) — exact position relative to
  `SteamWorldHostingClientInitializer`/`SteamCloudSyncClientInitializer`/
  `FriendsSidebarClientInitializer` is **not load-bearing** (no ordering
  dependency exists between this feature and any of those three — it only
  needs the two Steamworks hand-offs, already published second in the
  array), but this plan places it immediately after
  `SteamworksClientInitializer` for minimal risk/clearest intent.
- `platform/fabric-{26.2,26.1,1.21.11}/build.gradle` — each gains
  `implementation project(':features:rich-presence')`.

**Not modified by this plan** (per Decision 4/Existing Implementation):
`features/friends-sidebar/.../FriendSidebarWidget.java`,
`FriendsSidebarClientInitializer.java` — FR-RP6's consumption wiring is
explicitly deferred to a follow-up amendment, not part of this plan's scope.

## Interfaces
- `api/.../richpresence/RichPresenceFacade` — the sole new top-level `api`
  contract (Decision 6), the only cross-feature-visible surface.
- `features/rich-presence/services/{LocalPresenceTracker, PresenceStatusResolver, RichPresencePublisher}`
  — the three-way split core (Decision 5), mirroring
  `HostingPresenceScanner`'s scan-then-map precedent.
- `features/rich-presence/services/TierTextFormatter` — the single seam
  platform code implements to supply `Text`/`Component`-based localization
  (Decision 5).

## Services
- No `services/`-layer change at all — this is the first feature since
  `steam-world-hosting`'s own Decision 1 to consume `SteamFriendsGateway`
  without needing to extend its interface (both `setLocalRichPresence`/
  `clearLocalRichPresence` already exist, added for `HostingLifecycle`'s
  own use). No graduate-on-second-use trigger, no new `services/` file.

## Feature Classes
Enumerated fully under Files to Create (`api`, `config`, `services`
sub-packages of `features/rich-presence`). `PresenceStatusResolver`,
`LocalPresenceTracker`/`LocalPresenceTrackerImpl` (excluding its injected
`TierTextFormatter` dependency, which is platform-supplied),
`RichPresencePublisher`, `PresenceSignals`, `PresenceTier`, `TierKind`,
`VehicleKind`, `RichPresenceConfig`/`ConfigIO` are all plain Java with zero
`net.minecraft.*`/steamworks4j-native-call import — the plain-JVM-testable
core this feature's Test Strategy targets.

## Tests

### Test Strategy — explicit unit-testable vs. manual-only split
**Unit-testable in plain JVM** (Files to Create's `tests/` list):
1. `PresenceStatusResolverTest` — the entire tier-precedence ladder (spec
   Tier Priority, levels 1–7 plus both sub-rules) given a mocked/hand-built
   `PresenceSignals` input, including every documented threshold's exact
   boundary value (Decision 1). This is this feature's single most important
   test target, exactly analogous to `steam-world-hosting`'s own
   `HostGatewayTest`/`ConnectStringCodecTest` precedent for "the plain
   business-logic core is the thing this repo's automated tests can
   actually cover."
2. `RichPresencePublisherTest` — debounce correctness (FR-RP4), non-clobbering
   guard (FR-RP5, asserted directly as "never calls `setLocalRichPresence`
   with key `\"connect\"`"), and clear-once-not-repeatedly behavior (FR-RP7),
   against a fake tracker + mocked gateway.
3. `RichPresenceConfig`/`ConfigIO` round-trip + malformed-fallback tests.

**Not unit-testable, manual/in-game verification only** (explicit, not
glossed over, consistent with `steam-world-hosting`'s own Test Strategy
framing for its analogous Netty/mixin gap):
1. Every real `net.minecraft.*` signal read inside `PresenceSignalGatherer`
   (dimension/biome/pause/spectator/vehicle/underground/movement-and-
   placement-tracking/Villager-and-Bell-proximity) — these require a real
   running client and cannot be exercised on a plain JVM at all. Verification
   is manual: load a world, walk into each documented tier's trigger
   condition (stand still 150s for Staying, run 250+ blocks for Exploring,
   place 21+ blocks in 60s for Building, descend below Y=40 for Digging
   Around, board a minecart/boat, approach a village on foot), and confirm
   the correct string appears — cross-checked against Steam's own friends-
   list UI (per the specification's Overview, the actual, real-world
   observation surface for this feature) on at least one of the three
   platform modules; spot-check the other two for compile-and-boot only,
   per this repo's usual three-module verification asymmetry (see
   `steam-world-hosting`'s own Risk 1 framing on Yarn being strictly
   higher-risk than the two Mojang-mapped modules).
2. **The remote-joined-client parity claim for Near-a-Village** (spec's own
   flagged verification, Compatibility: "spawn/approach a village on both a
   hosting session and a joined-as-guest session and confirm the label
   appears in both") — genuinely requires two real Steam accounts / a real
   Steam-World-Hosting or LAN session to exercise, same class of limitation
   `steam-world-hosting`'s own plan recorded for its own end-to-end P2P
   claims (Acceptance Criteria, "Explicitly out of scope... any claim that a
   real Steam P2P connection... actually works end to end").
3. `MinecraftTierTextFormatter`'s actual localized string output (nested
   `Text.translatable` arguments, biome/entity name resolution) — needs a
   real running client; the plain-JVM tests above only assert which
   `TierKind`/arguments the resolver picked, not the final rendered string.
4. Compilation across all three platform modules (`gradlew build` /
   per-module task) is the one automatable cross-check beyond plain-JVM unit
   tests, verifying the new entrypoint, hand-off, dependency edge, and lang
   keys are all wired correctly.

## Dependencies
- **No new external Maven/Gradle dependency.** This feature needs no new
  steamworks4j surface beyond `setRichPresence`/`clearRichPresence`, both
  already confirmed present in the pinned `steamworks4j-1.10.0.jar`
  (`.claude/context/minecraft.md`'s existing verification rows,
  `friends-sidebar`/`steam-world-hosting` citations) — no registry lookup
  needed for this plan; the only dependency risk is confirming the
  block-placement-detection Fabric API v1 event this plan assumes
  (`UseBlockCallback` or an equivalent) actually exists in the already-
  vendored `fabric-api` version pinned in each module's own `gradle.properties`
  — this is an existing, already-resolved dependency (`fabric-api` is
  already a dependency of every platform module for every prior feature),
  not a new coordinate to add, so no registry verification is required by
  this plan's own Dependencies-section discipline (that discipline applies
  to *new* external coordinates, not to using another class already present
  in an already-vendored jar) — but the exact event class/method **is**
  flagged as an implementation-time `javap`/API-doc-confirmation item
  (Risks), since this repo has no prior feature using any block-placement
  hook to copy from.
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:rich-presence` → `api` (`api` configuration)
  - `features:rich-presence` → `services` (`implementation` configuration)
  - `platform:fabric-26.2` → `features:rich-presence` (`implementation`)
  - `platform:fabric-26.1` → `features:rich-presence` (`implementation`)
  - `platform:fabric-1.21.11` → `features:rich-presence` (`implementation`)
- This feature does **not** depend on `features:friends-sidebar` or
  `features:steam-world-hosting` in either direction (specification
  Architecture section, restated) — its only cross-feature-visible surface
  is the new `RichPresenceFacade` accessor in `api`, which
  `features:friends-sidebar` may optionally consume in a future, separate
  amendment (Decision 4).

## Risks
1. **Exact `net.minecraft.*` accessor names for dimension/pause/biome/vehicle/
   underground/sky-light are not `javap`-confirmed by this planning pass**
   (no Bash/decompiler tool available this session, same limitation every
   prior plan in this repo has recorded for analogous unknowns) —
   implementation's mandatory first step, per this repo's own established
   discipline (`.claude/context/minecraft.md:19-30`), is a real `javap -p`
   pass against all three resolved Minecraft jars for
   `Minecraft`/`MinecraftClient`, `Level`/`World`, `Player`/`PlayerEntity`,
   `Villager`/`VillagerEntity`, `BellBlock`/`BellBlockEntity`,
   `AbstractMinecart`/`AbstractMinecartEntity`, `Boat`/`BoatEntity`, logging
   results in `minecraft.md`'s table per its own convention before writing
   any platform-module signal-gathering code.
2. **Block-placement detection has no established idiom in this codebase to
   copy from** (Existing Implementation/Decision 7) — this is a genuinely
   new category of client-side hook for this repo (every prior feature only
   ever polled state or used already-established Fabric API v1 screen/tick
   events); the exact right Fabric API v1 event, or whether a mixin is
   needed instead if no clean event exists, is an open implementation-time
   question flagged here rather than assumed. If no clean per-placement
   event exists client-side, a fallback (a mixin on the block-placement
   packet handler, or diffing a small bounding box around the player each
   tick) would add real new-platform-layer complexity this plan does not
   currently scope for.
3. **The Near-a-Village "bounded-radius, already-loaded block entities near a
   position" accessor is not confirmed to exist as a single clean API call**
   on either mapping side (Decision 7) — the documented fallback (a bounded
   `BlockPos` iteration checking each position for a `BellBlockEntity`) is
   always correct but potentially the single most expensive per-scan
   operation this feature performs; throttled to once per ~20 ticks
   (Decision 8) to bound worst-case cost, but the exact real-world cost is
   unverified until implementation.
4. **No fake/test-double seam exists for `PresenceSignalGatherer`,
   `MinecraftTierTextFormatter`, or any other `net.minecraft.*`-touching
   class in this feature** — by design (same accepted trade-off class
   `steam-world-hosting`'s own Risk 5 and `friends-sidebar`'s own Risk 8
   already recorded), verified only by manual/in-game testing per Test
   Strategy, not by this plan's automated test suite.
5. **FR-RP6's real payoff (the sidebar label actually changing) is not
   delivered by this plan** (Decision 4) — a reasonable, spec-endorsed
   phasing choice, but worth flagging plainly: after this plan lands, the
   only user-visible change is the local player's real Steam friends-list
   entry showing a richer status string; the in-mod sidebar's own-row label
   continues showing the generic "In Game" fallback until a separate,
   follow-up change wires `RichPresenceFacadeHandoff` into
   `FriendSidebarWidget`.
6. **Threshold values are entirely untuned against real play** (Decision 1) —
   this plan ships the specification's own proposed defaults verbatim; the
   specification itself frames every one of them as "proposed, tunable, not
   fixed," so a post-ship tuning pass (adjusting constants only, no resolver
   redesign, per the plain-JVM test suite's own boundary-value coverage) is
   an expected, cheap follow-up, not a design risk in the usual sense.

## Acceptance Criteria
Mapped to the specification's functional requirements:

- **FR-RP1** — `PresenceSignalGatherer` (platform) feeds a `PresenceSignals`
  to `LocalPresenceTrackerImpl` every client tick; `PresenceStatusResolverTest`
  passes for every documented tier and every documented precedence tie-break.
- **FR-RP2** — every `lazuli.presence.*` key exists in all three platform
  modules' `en_us.json` (code review/compile-time asset-presence check); no
  key collision with any existing `lazuli.*` key.
- **FR-RP3** — code review confirms `MinecraftTierTextFormatter` produces its
  final string via `Text`/`Component`'s own `getString()` idiom, using
  registry-key-derived biome/entity translation keys, never a hand-built
  string; manual in-game check confirms nested arguments (biome name)
  resolve correctly (Test Strategy, manual-only item 3).
- **FR-RP4** — `RichPresencePublisherTest` asserts `setLocalRichPresence` is
  called only on an actual computed-string change across a scripted
  multi-tick sequence.
- **FR-RP5** — `RichPresencePublisherTest` asserts `setLocalRichPresence` is
  never invoked with `key = "connect"`; code review confirms
  `RichPresencePublisher` has no reference to `ConnectStringCodec`/
  `HostingLifecycle` at all.
- **FR-RP6** — `RichPresenceFacade`/`RichPresenceFacadeImpl` exist, are
  hand-off-published via the new `RichPresenceFacadeHandoff` in all three
  platform modules, and `localPresenceStatus()` returns the same value
  `RichPresencePublisher` most recently computed (code review + a direct
  unit test asserting the facade delegates to the same tracker instance).
  **Not** covered by this plan's acceptance criteria: any change to
  `FriendSidebarWidget`'s rendered label (Decision 4/Risk 5).
- **FR-RP7** — `RichPresencePublisherTest` covers the session-inactive path:
  given a tracker returning `Optional.empty()`, asserts `clearLocalRichPresence()`
  is called exactly once on the present-to-empty transition and not again on
  subsequent empty ticks.
- **Compatibility** — `gradlew build` succeeds for all three platform modules
  with the new dependency edge, entrypoint, and lang-key additions in place;
  `.claude/context/minecraft.md` gains new rows recording the real,
  `javap`-confirmed shape of every class named in Risk 1 before this
  criterion is considered met.
- **Explicitly out of scope for this plan's own acceptance sign-off**: any
  claim that a real Steam friends-list entry visibly shows the computed
  string end-to-end (Test Strategy manual-only items), or that Near-a-Village
  fires identically for a real remote-joined client (Test Strategy manual-only
  item 2) — both require real multi-account/in-game verification this
  workflow does not perform.

## Open Questions
- None remaining from the specification's own explicitly-flagged
  confirm-or-override items that block planning outright — Open Questions
  1–2 are carried forward as proposed defaults with full boundary-value test
  coverage (Decision 1/2), Open Question 3 is resolved as an independent,
  duplicated predicate rather than a shared dependency (Decision 3), and
  Open Question 4 is resolved as independent phasing (Decision 4). Any
  further questions should surface during implementation as concrete
  `javap`-confirmation findings (Risk 1) or as the block-placement-detection
  mechanism's real answer (Risk 2), not as open design questions.
</content>

## Implementation Notes (post-implementation addendum)

### Near-a-Village performance mechanism (task's mandatory constraint)
Risk 3 flagged that "bounded-radius, already-loaded block entities near a
position" was not confirmed to exist as a single clean API call. A real
`javap -p` pass against all three modules' resolved Minecraft jars confirmed
it **does** exist on both mapping sides, so the documented per-block-position
fallback was not needed:

- **Bells**: `Level.getChunk(int, int)` / `World.getChunk(int, int)` returns
  the chunk object directly, and `LevelChunk.getBlockEntities()` /
  `WorldChunk.getBlockEntities()` returns that chunk's own small,
  already-materialized `Map<BlockPos, BlockEntity>`. `PresenceSignalGatherer`
  iterates a 5x5 chunk neighborhood around the player (chunk radius
  `ceil(24/16)+1 = 2`, i.e. 25 chunk lookups) and filters each chunk's block-entity
  map by `instanceof BellBlockEntity` plus an exact `distSqr`/`getSquaredDistance`
  check against the 24-block radius. No raw per-block-position scan.
- **Villagers**: `Level.getEntities(EntityTypeTest, AABB, Predicate)` /
  `World.getEntitiesByType(TypeFilter, Box, Predicate)` is the vanilla
  entity-tracking system's own spatial-partition query -- it walks only the
  loaded chunk sections the given bounding box overlaps, not every loaded
  entity in the world. A 32-block-radius box overlaps at most a handful of
  sections.
- **Throttle**: both checks run only once every `NEAR_VILLAGE_SCAN_INTERVAL_TICKS`
  = 20 ticks (~1 second at 20 TPS) inside `PresenceSignalGatherer.tick()`,
  matching this codebase's existing cadence for comparable per-tick sweeps
  (`HostingPresenceScanner`'s own interval-gating, `DEFAULT_SCAN_INTERVAL_SECONDS = 5`
  is even coarser). 20 ticks was kept as the plan's own proposed default,
  rather than tightened or loosened, because Near-a-Village is a coarse
  "is the player generally near this feature" signal -- villages don't move,
  villagers wander slowly -- so 1-second staleness is invisible in the
  debounced (FR-RP4), human-readable Rich Presence string it ultimately
  feeds.
- **Rough cost estimate**: 25 chunk-map lookups (each a scan of a handful of
  entries -- real villages/worlds typically have 0-3 block entities per
  chunk) + 1 bounded spatial-partition entity query, once per second. This is
  well under a client tick's ~50ms budget and does not scale with world size,
  only with the small, fixed 5x5-chunk/32-block neighborhood -- bounded,
  infrequent, and documented per the task's constraint.

This reasoning is duplicated as class-level Javadoc on each platform module's
own `PresenceSignalGatherer` (the Mojang-mapped copy carries the full text;
the Yarn copy cross-references it, since the mechanism and throttle are
identical on both sides).

### Underground heuristic simplification
The specification allowed "Y-position below a threshold and/or no sky light
reaches the player" as an Overworld-only proxy signal, with the exact signal
left to implementation. This implementation uses **Y-position only**
(`pos.getY() <= 40`), not a sky-light/`canSeeSky` check, to avoid a second,
unconfirmed cross-version accessor for a heuristic the specification already
allows to be approximate ("digging around" is a playful label, not a
precise cave-detection feature). No cross-version divergence was researched
for a sky-light check as a result; a future refinement could add it as a
Future Extension if the Y-only heuristic proves too coarse in practice
(e.g. false-firing in a surface ravine below Y=40).

### Block-placement detection (Risk 2 resolution)
Confirmed via `javap`: `net.fabricmc.fabric.api.event.player.UseBlockCallback`
(module `fabric-events-interaction-v0`, already a transitive dependency of
`fabric-api` in every platform module -- no new Gradle coordinate) exists
identically in shape on both mapping sides (only the parameter types differ:
`PlayerEntity`/`World`/`Hand`/`BlockHitResult` on Yarn,
`Player`/`Level`/`InteractionHand`/`BlockHitResult` on Mojang mapping).
`PresenceSignalGatherer.onBlockPlacementAttempt()` is invoked whenever this
event fires **and** the player's held-item-in-hand is a `BlockItem`
(`player.getStackInHand(hand)` / `player.getItemInHand(hand)`), regardless of
the interaction's actual outcome. This is an intentionally accepted
imperfect proxy (as the plan's own Risk 2 anticipated) -- it will
over-count in the rare case a player right-clicks a block-item against a
block that consumes the interaction without actually placing (e.g. a
composter/furnace GUI opening instead), but under normal building play this
correlates strongly with real placements and needs no new mixin or packet
hook. The event handler always returns `PASS`/`ActionResult.PASS`, never
consuming the interaction.
