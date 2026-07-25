# Implementation Plan — Cross-World Stats

Spec: `features/cross-world-stats/specification.md` (approved).

## Summary
Build a new `features/cross-world-stats` Gradle module (new-feature shape,
closely mirroring `features/server-join-presence`'s own recent
module-creation precedent: `api` package, `services` sub-package, plain
hand-rolled-JSON config, per-platform composition root + Version Adapter merge
hook), plus one small, soft (fallback-safe) integration point with
`features/steam-cloud-sync`'s `WorldFingerprint`. No implementation code is
written by this plan. `features/main-menu`'s own Statistics tab (out of scope
here, spec Non-goals) will later consume this feature's
`CrossWorldStatsFacade` — this plan flags the exact dependency edge and
recommended build order in Dependencies/Order below.

## Existing Implementation

### Precedent module shape (`features/server-join-presence`, most recent
comparable new-feature build)
- `features/server-join-presence/build.gradle` —
  `dependencies { api project(':api'); implementation project(':services') }`
  — this feature's own `build.gradle` follows the identical shape.
- `features/server-join-presence/services/ServerJoinPresenceConfigIO.java`
  (and `SteamWorldHostingConfigIO` before it) — the hand-rolled-JSON,
  fail-closed-to-defaults convention `CrossWorldStatsConfigIO` must follow
  verbatim (malformed file → empty defaults + logged warning, never throws).
- `platform/fabric-<version>/.../ServerJoinPresenceClientInitializer.java` —
  the composition-root shape (`SteamworksServiceHandoff.require()`/
  `SteamFriendsGatewayHandoff.require()`, config load, `Noop*`-vs-real
  construction gated on availability, `ClientTickEvents.END_CLIENT_TICK`/
  `ClientLifecycleEvents.CLIENT_STOPPING` registration, publish a bridge
  handoff) — `CrossWorldStatsClientInitializer` mirrors this shape, swapping
  the tick-gated friend scan for a tick/save-event-gated stats merge.
- `platform/fabric-<version>/.../ServerJoinPresenceBridgeHandoff.java` —
  the per-module handoff-broker shape (`publish`/`require`) —
  `CrossWorldStatsBridgeHandoff` (new, this feature) follows it identically
  for `CrossWorldStatsFacade`.
- `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`
  — already exposes `localSteamId64()` (confirmed accessor, also cited at
  `features/steam-world-hosting/.../HostingLifecycle.java:57-58` per the
  spec) — reused as-is for FR1.1's keying, no new gateway method.
- `services/src/main/java/de/lazuli/services/steamworks/SteamAvailability.java`
  (or equivalent existing accessor — exact class name to confirm at
  implementation time by grep, referenced as `SteamAvailability.isSteamAvailable()`
  throughout this repo's specs) — reused for FR1.2's offline-sentinel branch.

### `features/steam-cloud-sync` (soft dependency target, FR3.1)
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/api/WorldFingerprint.java`
  — confirmed to exist at this path (glob-matched); this is the identifier
  concept this feature reuses via composition-root handoff (Architecture),
  never via a direct Gradle module dependency (`features/cross-world-stats`'
  `build.gradle` does not depend on `features:steam-cloud-sync`).
  Implementation must read this file's exact public shape (fields/equality
  semantics) before wiring the composition-root handoff, since the exact
  accessor `features/steam-cloud-sync`'s own composition root publishes it
  through (if any is already published) is not confirmed by this planning
  pass — flagged as Risk 2.
- No existing composition-root handoff class was located in this planning
  pass that already publishes `WorldFingerprint`-resolution as a
  cross-feature-consumable facade (unlike `FriendsSidebarFacade`/
  `FriendServerPresenceReader`, which are already-published bridges) — this
  is a gap `features/steam-cloud-sync` itself may need a small, additive
  publish-side change for (a new `WorldIdentifierResolver`-shaped facade), or
  this feature's own composition root reads `steam-cloud-sync`'s already
  existing internal resolution logic through whatever handoff mechanism that
  feature's own implementation actually used — this planning pass could not
  fully resolve which, since it requires reading `features/steam-cloud-sync`'s
  own composition-root/handoff source in more depth than this pass's scope
  covered; flagged as Risk 2/Open Question, not silently assumed.

### Fabric API event surface for the merge hook (FR2.1)
- No existing hook of this shape (world-save/tick-based stats merge) exists
  anywhere in this repo today — this is a genuinely new integration point,
  same "new to this codebase" framing `features/main-menu/specification-batch-2.md`
  Item 1 already carries for `HudRenderCallback`. The two realistic Fabric
  API candidates are `ServerLifecycleEvents.BEFORE_SAVE` (per-save-tick,
  fires on the integrated server) or a straightforward
  `ClientTickEvents.END_CLIENT_TICK`-gated poll (already used by every
  sibling feature's own tick-driven scanner, e.g.
  `ServerPresenceScanner`/`HostingPresenceScanner`) reading
  `Minecraft.getInstance().player.getStats()` every N seconds regardless of
  an actual save event — spec FR2.1 explicitly accepts the simpler
  tick-based fallback, so this plan defaults to it (Decision 2) rather than
  committing to the unconfirmed save-event hook.

## Decisions

### 1. Module layout (mirrors `features/server-join-presence` exactly)
```
features/cross-world-stats/
  build.gradle                         (api project(':api'); implementation project(':services'))
  README.md
  src/main/java/de/lazuli/features/crossworldstats/
    api/CrossWorldStatsConfig.java     (record: enabled default true)
    config/CrossWorldStatsConfigIO.java
    services/
      TrackedStat.java                 (enum, FR4.1 -- see Decision 4 for the exact curated set)
      CrossWorldStatsAggregator.java    (plain-JVM core, FR2.2-FR2.4/FR3.1-3.4)
      CrossWorldStatsService.java       (implements CrossWorldStatsFacade)
      NoopCrossWorldStatsFacade.java
  src/test/java/de/lazuli/features/crossworldstats/...
```
- `api/src/main/java/de/lazuli/api/crossworldstats/` (zero Minecraft
  imports, per spec Public API item 1): `TrackedStat` reference type,
  `CrossWorldStatsSnapshot`, `CrossWorldStatsFacade`. **Naming overlap note**:
  the spec's Public API section places `TrackedStat` in the `api` module,
  while its Architecture section's `features/cross-world-stats/services/`
  listing implies stat-tracking logic lives in `services/`. This plan
  resolves it as: `TrackedStat` (the plain enum identity, zero Minecraft
  imports) lives in `api/`, consumable by `features/main-menu` without a
  vanilla `Stat`/`StatType` import (spec's own stated goal for it); the
  **mapping** from each `TrackedStat` to a concrete vanilla `Stat`/`StatType`
  registry key lives in the platform Version Adapter (`CrossWorldStatsMergeHook`,
  since that mapping necessarily imports `net.minecraft.stats.Stat`), not in
  `features/cross-world-stats/services/` at all — keeping that module's own
  `services/` package free of any vanilla import, consistent with
  `CrossWorldStatsAggregator`'s own stated "no Minecraft/Steamworks imports"
  requirement (spec Public API item 2).

### 2. Merge-hook trigger: tick-gated poll, not a save event (FR2.1)
Default to a `ClientTickEvents.END_CLIENT_TICK`-registered handler in
`CrossWorldStatsMergeHook`, rate-limited to a fixed interval (this plan
recommends 30s, matching "periodic while a world is loaded, not only at
world-exit" without meaningfully increasing per-tick cost — a planning-phase
default, adjustable at implementation time) — reads
`Minecraft.getInstance().player.getStats()`/equivalent per version, guarded
by a null-check for "no world loaded" (menu screens, between worlds). This
sidesteps FR2.1's flagged unknown (`ServerLifecycleEvents.BEFORE_SAVE`'s
exact per-version timing/availability, not `javap`-confirmed this pass) by
using the same tick-gated-scan shape every sibling feature
(`ServerPresenceScanner`, `HostingPresenceScanner`) already proves works
reliably in this codebase — lower-risk than introducing this repo's first
save-event hook. Implementation may still confirm and adopt
`ServerLifecycleEvents.BEFORE_SAVE` as an *additional* trigger (not a
replacement) if it resolves to a real, simple win, per FR2.1's "both" option
— not required for this plan's own acceptance criteria.

### 3. World identifier resolution: soft dependency with save-path fallback (FR3.1)
`CrossWorldStatsMergeHook` (platform layer) attempts to obtain a
`WorldFingerprint`-equivalent identifier via whatever composition-root handoff
`features/steam-cloud-sync` already publishes (Risk 2 — exact mechanism to be
confirmed at implementation time by reading that feature's own composition
root source); if unavailable (feature disabled, handoff not published, or
simply absent), falls back to the current world's own save-directory
name/path string as the identifier (spec's own explicitly-accepted
degradation — a world rename would then reset that one world's baseline, not
corrupt the global total). This resolution logic lives entirely in the
platform Version Adapter, not in `CrossWorldStatsAggregator` (which only ever
receives an opaque `String`/identifier value, never resolves one itself) —
keeps the plain-JVM core decoupled from both Minecraft and the cross-feature
handoff mechanism.

### 4. Tracked stat set (FR4.1) — v1 curated list
This plan proposes the following fixed `TrackedStat` enum, chosen for direct
relevance to a plausible Statistics tab display (per spec's own framing,
"informed by what a Statistics tab would plausibly want to show") and for
being simple, well-known vanilla `Stat`/`StatType` custom-stat entries (not
per-block/per-item breakdowns, avoiding FR4.1's explicitly-declined
exhaustive-coverage scope):
- `PLAY_TIME_TICKS` (`Stats.PLAY_TIME`/`CUSTOM.MINUTES_PLAYED` equivalent per
  version — exact registry key name to confirm per platform module, Yarn vs.
  Mojang mapping divergence expected)
- `BLOCKS_MINED` (sum of `Stats.BLOCK_MINED` counts, i.e. a single aggregate
  across all mined-block types, not per-block-type breakdown)
- `MOB_KILLS` (sum of `Stats.ENTITY_KILLED` counts across all entity types)
- `DEATHS` (`Stats.DEATHS` custom stat)
- `ITEMS_CRAFTED` (sum of `Stats.ITEM_CRAFTED` counts across all item types)
- `DISTANCE_TRAVELED_CM` (sum of all distance-by-category custom stats
  vanilla tracks, e.g. walking/sprinting/swimming/flying — summed into one
  aggregate figure for v1, per FR4.1's "not attempting exhaustive coverage")

This exact list is this plan's own proposal, flagged for the user's
visibility/override per this repo's standing convention (spec explicitly
leaves it open as a product decision) — not a blocking design ambiguity,
since `CrossWorldStatsAggregator`'s merge/delta logic (FR2/FR3) is provably
identical regardless of which specific `TrackedStat` keys exist (spec FR4.1's
own closing sentence). Confirming the exact vanilla `Stat`/`StatType` registry
key names/summing semantics per platform module (the "sum of all
per-block/per-entity/per-item variants into one aggregate" mapping) is an
implementation-time `javap`/source-check task per module, not resolved here.

### 5. Config file shape and debounced writes (FR2.3, Persistence)
`CrossWorldStatsConfigIO` follows the exact JSON shape the spec's own
Configuration section illustrates (`accounts` map keyed by `localSteamId64()`
string or `"offline"`, each holding `totals`/`worldBaselines`). Writes are
debounced to at most once per merge-hook firing (already rate-limited to the
merge interval, Decision 2) — no additional debounce layer needed since the
merge interval itself (30s default) already bounds write frequency
adequately (Performance).

### 6. Offline sentinel key: `"offline"` string literal (FR1.2, spec's stated default)
This plan adopts the spec's own stated default (a fixed sentinel string,
`"offline"`) rather than the flagged alternative (keying by local Minecraft
UUID when Steam is unavailable) — the alternative would require confirming
Minecraft's own offline-mode UUID derivation is actually stable across
sessions on this machine, which is an unconfirmed, non-trivial claim this
plan is not in a position to verify without further research; the simpler
shared-sentinel default carries no such unconfirmed assumption and is
explicitly spec-sanctioned as the default. Flagged for the user's visibility
per the spec's own "confirm-or-override" framing — not treated as a blocking
question.

## Files to Create

### `api` module
- `api/src/main/java/de/lazuli/api/crossworldstats/TrackedStat.java`
- `api/src/main/java/de/lazuli/api/crossworldstats/CrossWorldStatsSnapshot.java`
- `api/src/main/java/de/lazuli/api/crossworldstats/CrossWorldStatsFacade.java`

### `features/cross-world-stats` module (new Gradle subproject)
- `features/cross-world-stats/build.gradle`
- `features/cross-world-stats/README.md`
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/api/CrossWorldStatsConfig.java`
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIO.java`
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/CrossWorldStatsAggregator.java`
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/CrossWorldStatsService.java`
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/NoopCrossWorldStatsFacade.java`
- `features/cross-world-stats/src/test/java/de/lazuli/features/crossworldstats/services/CrossWorldStatsAggregatorTest.java`
- `features/cross-world-stats/src/test/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIOTest.java`

### Platform modules (×3: `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`)
- `platform/fabric-<version>/src/main/java/de/lazuli/crossworldstats/CrossWorldStatsMergeHook.java`
  — Decision 2/3, includes the per-version `TrackedStat`-to-vanilla-`Stat`
  mapping.
- `platform/fabric-<version>/src/main/java/de/lazuli/CrossWorldStatsBridgeHandoff.java`
  — publish/require broker for `CrossWorldStatsFacade` (mirrors
  `ServerJoinPresenceBridgeHandoff`).
- `platform/fabric-<version>/src/main/java/de/lazuli/CrossWorldStatsClientInitializer.java`
  — composition root: config load, `Noop*`-vs-real construction, obtains
  `SteamFriendsGatewayHandoff`/`SteamworksServiceHandoff`, attempts
  `steam-cloud-sync`'s world-identifier handoff (Decision 3, falls back
  gracefully if absent), registers `CrossWorldStatsMergeHook` on
  `ClientTickEvents.END_CLIENT_TICK`, registers a `CLIENT_STOPPING` final
  flush, publishes `CrossWorldStatsBridgeHandoff`.

## Files to Modify
- `settings.gradle` — add `include 'features:cross-world-stats'`.
- `platform/fabric-{1.21.11,26.1,26.2}/build.gradle` — each gains
  `implementation project(':features:cross-world-stats')`.
- `platform/fabric-{1.21.11,26.1,26.2}/src/main/resources/fabric.mod.json` —
  `"client"` array gains one new entry,
  `"de.lazuli.CrossWorldStatsClientInitializer"`, placed after
  `SteamworksClientInitializer` (needs its handoff) and after
  `SteamCloudSyncClientInitializer` (soft-consumes its handoff, if present at
  wiring time — order matters only for the optional handoff to be available
  when this initializer runs, not a hard requirement given the fallback,
  Decision 3) and before `MainMenuClientInitializer` (that feature will later
  consume this one's own published facade for its Statistics tab).
- **No modification to `features/steam-cloud-sync`'s own files** unless
  Risk 2 resolves to "no existing publish-side handoff for
  `WorldFingerprint`-equivalent resolution exists yet" — in that case, a
  small additive change (a new small facade interface published via that
  feature's own composition root, mirroring every other cross-feature
  facade in this repo) is needed to `features/steam-cloud-sync`'s
  composition root, flagged here as a **possible** Files-to-Modify entry
  contingent on Risk 2's resolution, not committed to as a certainty by this
  plan.

## Interfaces
- `api/.../crossworldstats/{TrackedStat, CrossWorldStatsSnapshot, CrossWorldStatsFacade}`
  — the three new top-level `api` contracts (spec Public API item 1).
- `features/cross-world-stats/services/{CrossWorldStatsAggregator, CrossWorldStatsService}`
  — plain-JVM-testable core plus the facade implementation.

## Services
No new `services/`-layer (top-level `services` Gradle module) class —
`SteamFriendsGateway.localSteamId64()` and `SteamAvailability.isSteamAvailable()`
already expose everything this feature needs from that layer (Existing
Implementation); this feature is an additional consumer, not a new
graduation trigger.

## Test Strategy
Mirrors `features/server-join-presence/implementation-plan.md`'s own accepted
shape (spec Non-goals equivalent: no live in-game testing performed by this
workflow for rendering-adjacent code, but this feature has none — it's a
background tracker, so most of its logic *is* plain-JVM-testable):

1. **Unit tests (plain JVM) — the primary verification surface for this
   feature**, since `CrossWorldStatsAggregator` has zero Minecraft/Steamworks
   imports by design:
   - Round-trip delta computation: given a fake current-values map and a
     fake prior-baseline map, confirm `delta = current - baseline`, summed
     correctly into cumulative totals (FR2.2/FR2.3).
   - First-read baseline (FR3.3): a `(world, stat)` pair never seen before
     yields a `0` delta and the current value becomes the new baseline —
     the single most important behavioral test in this feature, directly
     preventing the "one-time double-count burst on first install" bug the
     spec calls out by name.
   - Negative-delta clamp (FR2.4): a lower current value than the stored
     baseline (simulating a restored backup) produces a `0`-clamped delta,
     never a negative subtraction from the cumulative total, and re-baselines
     to the new lower value.
   - Repeated-read idempotence: reading the same unchanged world/stat values
     twice in a row produces a second delta of `0` (no double-counting on
     redundant merge-hook firings).
   - Multi-account isolation (FR1.1/FR1.3): two different `localSteamId64()`
     buckets accumulate independently; no cross-account bleed.
   - `CrossWorldStatsConfigIO` round-trip + malformed-fallback tests (empty
     file, corrupt JSON, missing `accounts` key) — same convention as every
     sibling config-file test.
2. **Compilation across all three platform modules** — `gradlew build` must
   succeed with the new `features:cross-world-stats` dependency, new
   `fabric.mod.json` entrypoint, and each module's own
   `CrossWorldStatsMergeHook`'s `TrackedStat`-to-vanilla-`Stat` mapping
   compiling against that module's own Minecraft/Yarn or Mojang mappings.
3. **Manual in-game smoke check** (no live-testing infrastructure exists in
   this repo beyond this convention): load a world, play briefly (mine a
   block, take damage/die once if safe to do so), wait past the merge
   interval, confirm `config/cross-world-stats.json` is created/updated with
   plausible non-zero deltas; reload the same world and confirm no
   double-counted jump on the next merge; delete the world and confirm the
   global total is unaffected (FR3.4). Repeat on all three platform modules
   given the per-version `Stat` mapping (Decision 4) is the only genuinely
   per-module-divergent code in this feature.
4. **No fake-seam test for the `ClientTickEvents`/`StatisticsManager` glue
   itself** in the platform Version Adapter — same accepted-gap framing
   `server-join-presence`'s own Test Strategy item 3 already establishes for
   analogous glue code; covered only by the manual smoke check above.

## Dependencies
- **No new external Maven/Gradle dependency.**
  `net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents`/
  `ClientLifecycleEvents` are already declared (`fabric-api`, `"*"` pin,
  already used by every sibling feature's composition root) — no version
  bump, no new Maven coordinate. This plan does not introduce any dependency
  requiring a `search.maven.org` verification pass.
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:cross-world-stats` → `api` (`api` configuration)
  - `features:cross-world-stats` → `services` (`implementation` configuration)
  - `platform:fabric-26.2`/`fabric-26.1`/`fabric-1.21.11` →
    `features:cross-world-stats` (`implementation`)
- **Does not** depend on `features:steam-cloud-sync` or
  `features:main-menu` at the Gradle-module level in either direction — the
  `steam-cloud-sync` world-identifier reuse is a runtime, composition-root
  handoff lookup only (Decision 3), never a compile-time import (Architecture,
  spec's own "read-only, best-effort reuse... still only imports api/services"
  framing).
- **Downstream dependency this plan does not build**: `features/main-menu`'s
  Statistics tab will consume `CrossWorldStatsFacade` via
  `CrossWorldStatsBridgeHandoff` once that tab is built (out of scope here,
  spec Non-goals) — `features/main-menu`'s platform-module `build.gradle`
  will need `implementation project(':features:cross-world-stats')` added at
  that time, not now.

## Suggested Build Order Relative to `features/main-menu/specification-batch-2.md`
This feature has **no dependency** on anything in the batch-2 main-menu plan
and can be built fully independently and in parallel with it. The only
ordering constraint that exists is the reverse direction: **this feature must
land, publish `CrossWorldStatsFacade`, and be verified (Test Strategy) before
`features/main-menu` builds its own Statistics tab** (a future amendment, not
part of either plan reviewed here) — that tab is this plan's sole known
consumer and does not exist yet in either spec. Recommended overall sequence,
if both are being staged by the same team:
1. This plan (`features/cross-world-stats`) — no external UI dependency,
   safe to build first, proves out the delta/baseline logic in isolation via
   unit tests before any UI consumes it.
2. `features/main-menu/specification-batch-2.md`'s four items — fully
   independent of this plan; may be built in parallel or before/after with no
   coordination needed (confirmed no shared file overlap between the two
   plans' Files to Create/Modify lists).
3. A future `features/main-menu` Statistics-tab amendment (not yet
   specified) — depends on step 1's `CrossWorldStatsFacade` being complete
   and verified.

## Risks
1. **FR2.1's merge-hook trigger is resolved by this plan to a tick-gated poll
   (Decision 2), not the spec-suggested `ServerLifecycleEvents.BEFORE_SAVE`**
   — lower-risk given this repo's existing tick-gated-scanner precedent, but
   means a client crash between merge intervals loses at most one interval's
   worth of progress (bounded, acceptable per spec's own framing: "not only
   once at world-exit"), not zero loss. If tighter save-event alignment is
   wanted later, adding `BEFORE_SAVE` as an *additional* trigger is additive,
   not a redesign.
2. **`features/steam-cloud-sync`'s exact publish-side mechanism for
   `WorldFingerprint`-equivalent resolution is not confirmed by this planning
   pass** (Existing Implementation) — this plan could not fully verify
   whether a ready-to-consume composition-root handoff already exists for
   it, or whether a small additive change to that feature's own composition
   root is needed first. Implementation's first step for this feature should
   be reading `features/steam-cloud-sync`'s composition-root source
   in full to resolve this before writing `CrossWorldStatsMergeHook`'s
   identifier-resolution branch — if a handoff addition to
   `steam-cloud-sync` is needed, that is a small, backward-compatible,
   additive change (new facade interface, no existing behavior changed),
   analogous in shape to `server-join-presence`'s own
   `FriendServerPresenceReader` accessor addition precedent
   (`features/main-menu/implementation-plan-batch-2.md` Decision 2), not a
   redesign of that feature.
3. **`TrackedStat`'s exact vanilla `Stat`/`StatType` registry key mapping
   (Decision 4) is not `javap`/source-confirmed per platform module by this
   planning pass** — a genuinely new (never-before-touched-by-this-repo)
   API surface (`StatisticsManager`, `Stats.*` constants), expected to
   diverge in class/constant names between Yarn (fabric-1.21.11) and Mojang
   mappings (fabric-26.1/26.2), per this repo's standing pre-implementation
   discipline. Flagged as implementation's mandatory first per-module step
   for the `CrossWorldStatsMergeHook` class, same discipline as every
   sibling feature's own analogous flagged unknown.
4. **The proposed `TrackedStat` set (Decision 4) is this plan's own
   product-shaped guess**, not a confirmed requirement — the spec explicitly
   leaves this open; if the eventual Statistics tab wants a different subset,
   changing `TrackedStat`'s enum values later is a low-risk, additive-or-
   renaming change (FR4.2's own "adding a new tracked stat simply starts
   accumulating from 0" convention already accommodates this).
5. **Offline-sentinel keying (Decision 6)** means every non-Steam/offline
   session on one machine shares one bucket — acceptable per spec's own
   stated default, but worth re-confirming with the user before
   implementation locks it in, since the spec itself flags this as
   "confirm-or-override," not fully settled.
6. **No existing precedent in this repo for a background feature with zero
   UI of its own being independently, meaningfully smoke-tested in-game**
   (every prior feature's manual-verification step involves visible UI) —
   the manual smoke check (Test Strategy item 3) relies on reading the
   resulting JSON config file directly rather than any in-game visual
   confirmation, a slightly weaker verification signal than this repo's
   other features get, though the unit-test coverage (item 1) is
   correspondingly stronger here than in most sibling features (this
   feature's core logic has zero Minecraft imports, unlike most).

## Acceptance Criteria
Mapped to the specification's functional requirements:

- **FR1.1-FR1.3** — `CrossWorldStatsAggregatorTest`'s multi-account-isolation
  case passes; code review confirms `CrossWorldStatsService` keys its
  in-memory/persisted state by `localSteamId64()` (or the offline sentinel),
  never a single global bucket.
- **FR2.1-FR2.4** — Code review confirms `CrossWorldStatsMergeHook` registers
  on `ClientTickEvents.END_CLIENT_TICK` (Decision 2), gated to the chosen
  interval; `CrossWorldStatsAggregatorTest`'s delta/clamp/idempotence cases
  all pass.
- **FR3.1-FR3.4** — Code review confirms per-`(world, stat)` bookkeeping is
  persisted alongside global totals; `CrossWorldStatsAggregatorTest`'s
  first-read-baseline case passes explicitly; a deleted-world's stale entry
  is confirmed (by code review, not a corruption bug) to simply stop
  updating, never pruned automatically.
- **FR4.1-FR4.2** — `TrackedStat` enum exists as a fixed, code-defined list
  (Decision 4); no config-file mechanism exists for toggling which stats are
  tracked (confirmed by `CrossWorldStatsConfigIOTest` schema review).
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules with every new dependency edge, entrypoint, and
  `CrossWorldStatsMergeHook`'s per-module `Stat` mapping in place;
  `.claude/context/minecraft.md` gains new rows recording the
  `javap`/source-confirmed `Stat`/`StatType` mapping per platform module
  (Risk 3) before this criterion is considered met.
- **Explicitly out of scope for this workflow's own acceptance sign-off**
  (spec Non-goals): the main-menu Statistics tab's own existence/correctness
  — this plan only guarantees `CrossWorldStatsFacade.currentTotals()` returns
  correct data, not that any UI renders it.

## Open Questions
- **Risk 2** (steam-cloud-sync's exact handoff mechanism) and **Risk 3**
  (per-module `Stat` mapping) are implementation-phase confirmation steps,
  not design ambiguities blocking this plan's own sign-off, consistent with
  this repo's established convention for this class of unknown.
- **Decision 4's exact `TrackedStat` set** and **Decision 6's offline-sentinel
  keying** are both explicitly flagged, spec-sanctioned-default planning
  calls, surfaced here for the user's visibility/override per the task's own
  instruction — not treated as blocking, but worth a quick explicit
  confirm-or-override from the user before implementation locks them in.
