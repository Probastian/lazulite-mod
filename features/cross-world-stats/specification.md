# Cross-World Stats — Specification

## Overview
Adds `features/cross-world-stats`: a background tracker that accumulates
Minecraft's own per-world player statistics (`StatisticsManager`/
`stats/<uuid>.json`, vanilla's existing per-save stat-tracking mechanism)
**cumulatively across every world the local player has ever played**, keyed by
the local Steam account, rather than vanilla's own per-world-only scope. This
feature is the data/tracking layer only; the presentation surface (a
"Statistics" tab inside `features/main-menu`'s tab bar) belongs to that
feature and is out of scope here — this spec exposes a small, stable read
contract for that tab to consume (Public API), following this repo's
established `Feature -> Platform API -> Version Adapter -> Minecraft` layering
and its `features/main-menu`-consumes-other-features'-facades-via-composition-root
precedent (`features/main-menu/specification.md` Architecture).

Vanilla's own `StatisticsManager` is deliberately **per-save**: each world/save
directory has its own `stats/<player-uuid>.json`, reset to zero for a freshly
created world. This feature does not modify or replace that per-world file —
it adds a second, independent aggregation layer that reads each world's stat
values (not vanilla's own file format directly, see Requirements/Persistence)
and merges the *change since last read* into one global, cross-world total per
Steam account, stored under Fabric Loader's own config directory, following
the exact hand-rolled-JSON/fail-closed convention every other feature-owned
config file in this repo already uses (`SteamWorldHostingConfigIO`,
`features/server-join-presence/specification.md` Configuration).

## Goals
- Track a cumulative, all-time total per statistic (e.g. total blocks mined,
  total playtime, total mob kills, total deaths — whatever subset of
  vanilla's own `Stat`/`StatType` registry this feature chooses to track, see
  Requirements) that persists and grows across every world the local player
  creates/plays/deletes, rather than resetting per world.
- Key that cumulative total by the local Steam account
  (`SteamFriendsGateway.localSteamId64()`, the same accessor
  `HostingLifecycle` already uses, `features/steam-world-hosting/.../HostingLifecycle.java:57-58`),
  so a player who plays this mod's worlds under one Steam account sees one
  running total regardless of how many separate save directories they've
  used.
- Compute deltas correctly: reading the same world's stats more than once
  (every world-save tick, every session) must add only the **newly earned**
  amount since the last read for that specific world/stat pair, never
  double-counting a stat value that was already merged in a prior read.
- Store the aggregated total in a single, small, plain-JSON file under
  Fabric Loader's per-instance config directory (`FabricLoader.getInstance().getConfigDir()`,
  the same convention `features/server-join-presence`/`features/steam-world-hosting`
  already use for their own config files), not per-world, so it survives
  world deletion and is shared across every world for that account.
- Expose a small, stable, plain-JVM-testable read contract
  (`CrossWorldStatsFacade`/equivalent) for `features/main-menu`'s own
  Statistics tab to consume via the standard platform-composition-root
  handoff pattern this repo already uses for every other cross-feature
  integration (`WorldHostingBridgeHandoff`-shaped).

## Non-goals
- Not the main-menu Statistics tab's UI itself — that screen/panel is
  `features/main-menu`'s own scope (a future amendment to that feature's
  spec); this feature only guarantees the data exists and is readable.
- Not a rewrite or replacement of vanilla's own per-world `StatisticsManager`/
  `stats/<uuid>.json` file — that file continues to be written by vanilla
  exactly as it always has been; this feature only *reads* it (or the
  in-memory `StatisticsManager` object, see Requirements) to compute deltas,
  never writes to it.
- Not a cross-device/Steam-Cloud-synced total in v1. Unlike
  `features/steam-cloud-sync` (which syncs actual world-save files across
  devices), this feature's aggregate file is a **local-only** config file, not
  uploaded to Steam Remote Storage — a player's cumulative stats on this
  machine do not follow them to a second machine running the same Steam
  account. Cloud-syncing this file is a documented Future Extension, not
  built now (mirrors `features/main-menu/specification.md`'s own identical
  deferral for the Wardrobe equip-map, "no established precedent... adding
  that is a Future Extension").
- Not a per-world breakdown/drill-down UI ("which world contributed how much
  to this total") — this feature's aggregate is a flat, single running total
  per stat, with no retained per-world provenance beyond what's needed
  internally to compute deltas correctly (Requirements, "Delta bookkeeping").
  A future "view by world" breakdown is a Future Extension if ever requested.
- Not a full mirror of every single vanilla `Stat`/`StatType`/custom-stat
  registry entry (there are hundreds, spanning mined/broken/crafted/used/
  picked-up/dropped counts per block/item, entity-killed/killed-by counts per
  entity type, and general "custom" stats like playtime/jumps/damage). This
  spec tracks a curated, config-extensible subset (Requirements) rather than
  attempting universal 1:1 coverage of vanilla's entire stat registry in v1.
- Not server-side/dedicated-server tracking. Like every other client-facing
  feature in this repo, this is 100% client-side: it reads the **local
  player's own** `StatisticsManager` on the client (available identically
  whether the client is the integrated-server host or a remote-joined
  client — vanilla already syncs the local player's own stats to their own
  client via the existing `ClientboundAwardStatsPacket`/equivalent, the same
  packet vanilla's own stats screen already reads from), never any other
  player's stats, and never anything requiring dedicated-server code.
- Not a leaderboard/friends-comparison feature — this is the local player's
  own cumulative total only; comparing against friends' totals (which would
  require a new sync/publish mechanism, since Steamworks has no generic
  "share arbitrary stat totals with friends" API beyond `ISteamUserStats`'
  own Steam-defined stats/leaderboards, itself a Future Extension candidate)
  is out of scope.
- Not an attempt to reconcile/merge historical totals retroactively from
  worlds played **before** this feature existed — the aggregate starts
  accumulating from the moment this feature first runs; a world's
  pre-existing stat values at that first read are treated as the delta
  baseline (Requirements, "First-read baseline"), not retroactively summed
  in whole, to avoid one-time double-counting of progress the player made
  before this feature shipped being added as if newly earned in a single
  burst.

## Requirements

### Steam-account keying and the offline/non-Steam case
- **FR1.1** The aggregate file is keyed by `localSteamId64()`
  (`SteamFriendsGateway.localSteamId64()`, confirmed accessor,
  `HostingLifecycle.java:57`) — one file per local Steam account, not a
  single global file shared across every Steam account that has ever used
  this Minecraft installation on this machine (relevant on a shared
  computer/multiple Steam accounts on one OS user profile).
- **FR1.2** If Steam is unavailable (`SteamAvailability.isSteamAvailable() ==
  false`) — offline mode, Steam not running, or a non-Steam distribution of
  this mod — this feature falls back to a fixed sentinel key (e.g. the
  literal string `"offline"` or the numeric `0L`, a planning-phase naming
  decision) representing "no identifiable Steam account for this session."
  All offline sessions on this machine share that one sentinel bucket
  (a reasonable, simple default — this feature has no other stable per-player
  identity to key by when Steam is unavailable, since Minecraft's own local
  player UUID is itself derived from/tied to online-mode auth in ways this
  repo does not currently have an offline-safe equivalent for). This is
  **flagged for confirm-or-override**: an alternative would be to key by the
  local player's own Minecraft UUID when Steam is unavailable instead of one
  shared sentinel bucket, if that UUID is stable across offline sessions on
  this machine — planning should confirm which is more useful/expected
  before implementation, this spec's default is the simpler shared-sentinel
  approach.
- **FR1.3** Switching Steam accounts on the same machine (logging out of one
  Steam account, into another, without reinstalling) naturally produces a
  second `localSteamId64()`-keyed bucket the next time this feature runs —
  no migration/merge logic between two different Steam accounts' buckets is
  provided (each account's cumulative total is independently tracked, by
  design, matching "per Steam account" literally).

### Merge hook (when deltas are read and folded in)
- **FR2.1** This feature hooks a world-save event: `ServerLifecycleEvents.BEFORE_SAVE`
  or the equivalent per-version "the level (including the local integrated
  server's player data) is about to write/has just written its data to disk"
  Fabric API hook — planning must confirm the exact per-version event name/
  timing (auto-save tick, explicit "Save and Quit," world-exit) via the
  standard `javap`/Fabric-API-surface check this repo already requires for
  every new event integration. A **tick-based** hook (polling every N ticks
  regardless of an actual save event) is an acceptable, simpler fallback if
  the save-event hook proves awkward to wire correctly to the local player's
  own client-visible `StatisticsManager` state — this spec requires "the
  merge runs periodically while a world is loaded, not only once at
  world-exit" (so a long play session's progress isn't lost entirely if the
  client crashes before a clean exit), but leaves the exact trigger
  granularity (every auto-save, every N ticks, both) to planning.
- **FR2.2** On each merge-hook firing, this feature reads the local client's
  own in-memory `StatisticsManager` (the same object vanilla's own Statistics
  screen already reads from, `Minecraft.getInstance().player.getStats()`/
  equivalent per version — **not** a raw re-parse of the world's
  `stats/<uuid>.json` file from disk, since the in-memory object is already
  the authoritative, always-current source the running client has, and
  reading it avoids any file-locking/partial-write race with vanilla's own
  concurrent save of that same file) for each tracked `Stat` (Requirements,
  "Tracked stat set"), and computes `delta = currentValue -
  lastKnownValueForThisWorldAndStat` (Requirements, "Delta bookkeeping").
- **FR2.3** Each positive delta is added to the global cumulative total for
  that stat, in the current Steam account's bucket (FR1.1/FR1.2), and the
  aggregate file is rewritten (or the pending write is debounced/batched to
  avoid a full-file rewrite on every single tick — a planning-phase
  performance decision, Performance section).
- **FR2.4** A world's stat value going *down* (unexpected under normal
  vanilla play, since stats are monotonically increasing counters, but
  defensively possible if a player restores an older backup of the same
  world, `features/steam-cloud-sync`'s own restore flow) must never produce
  a negative delta subtracted from the global total — clamp any computed
  delta to a minimum of `0`, and re-baseline "last known value" to the new
  (lower) value so future increases from that point are still counted
  correctly, rather than the world's stat value having to first "catch back
  up" past its pre-restore high-water mark before any further delta is
  recognized again.

### Delta bookkeeping (avoiding double-counting)
- **FR3.1** For each distinct **(world identifier, tracked stat)** pair, this
  feature retains a small "last known value" record — the same per-world
  value it last folded into the global total. World identifier follows the
  same convention `features/steam-cloud-sync`'s own `WorldFingerprint`
  already establishes for stably identifying a specific save directory
  across renames/moves (`features/steam-cloud-sync/.../api/WorldFingerprint.java`)
  — reusing that existing identifier concept (via composition-root handoff,
  not a direct feature-to-feature dependency, Architecture) avoids this
  feature inventing a second, parallel "which world is this" identity
  scheme.
- **FR3.2** This per-world-per-stat bookkeeping table is itself persisted
  (Persistence) alongside (or within the same file as) the global cumulative
  totals — losing it would not corrupt the global total's *existing* value,
  but would risk a one-time over-count on the next merge if "last known
  value" resets to `0` (every stat's current value would then read as a
  brand-new delta) — so it must survive a normal client restart exactly like
  the global totals themselves do.
- **FR3.3** **First-read baseline (avoiding a one-time burst on first
  install):** the very first time this feature ever observes a given
  (world, stat) pair (no existing "last known value" record for it), the
  delta folded into the global total for that first observation is `0`, and
  the world's *current* stat value becomes the initial "last known value"
  baseline — i.e., a player's pre-existing progress in a world they'd already
  been playing before this feature shipped is **not** retroactively summed
  into the global total in one lump sum on first run; only *future* increases
  from that point forward count. This directly resolves the "avoid
  double-counting on repeated reads of the same world" requirement's
  first-run edge case, and is consistent with this spec's own Non-goals
  ("not an attempt to reconcile historical totals retroactively").
- **FR3.4** A deleted world's bookkeeping entry (FR3.1) is not actively
  pruned from the aggregate file by this feature (no cross-check against
  which save directories still exist on disk) — a stale entry for a deleted
  world simply never updates again, at negligible storage cost (a handful of
  longs per world/stat pair); pruning stale entries is a Future Extension if
  file size ever becomes a real concern, not required for correctness.

### Tracked stat set
- **FR4.1** This feature tracks a fixed, curated subset of vanilla's `Stat`/
  `StatType` registry in v1 — exact list a planning-phase decision informed
  by what a Statistics tab would plausibly want to show (e.g. total playtime,
  total blocks mined, total distance traveled by category, total mob kills,
  total deaths, total items crafted) — not an attempt at exhaustive 1:1
  coverage of every registered stat (hundreds of entries, mostly narrow
  per-block/per-item/per-entity counters). The exact set is intentionally
  left open here since it is a product/UI decision (what the Statistics tab
  actually wants to display) more than a tracking-mechanism decision; this
  feature's merge/delta logic (FR2/FR3) works identically regardless of
  which specific `Stat` keys are in the tracked set.
- **FR4.2** The tracked-stat set is a static, code-defined list (not
  user-configurable in v1) — adding/removing a tracked stat is a code change,
  not a config option, consistent with this feature's own config file only
  ever storing *values*, not *which stats to track* (Configuration).

## Public API
Illustrative shapes only; final names/signatures are a planning-phase
decision, consistent with this repo's convention.

1. **`api/src/main/java/de/lazuli/api/crossworldstats/`** (zero Minecraft
   imports):
   - `TrackedStat` — enum or small registry-key-wrapping record identifying
     one of this feature's curated stat set (FR4.1) in a
     Minecraft-registry-agnostic way consumable by `features/main-menu`'s
     Statistics tab without that tab needing to import vanilla `Stat`/
     `StatType` classes directly.
   - `CrossWorldStatsSnapshot` — plain record: `Map<TrackedStat, Long>
     cumulativeTotals`, `long steamId64` (or the offline sentinel, FR1.2) —
     the read-only hand-off shape the Statistics tab consumes.
   - `CrossWorldStatsFacade` — small read-only interface: `CrossWorldStatsSnapshot
     currentTotals()` — the sole cross-feature integration surface this
     feature exposes, following the exact `FriendsSidebarFacade`-shaped
     "small facade interface, published via composition-root handoff"
     precedent every other feature-to-feature integration in this repo
     already uses.

2. **`features/cross-world-stats/services/`**:
   - `CrossWorldStatsAggregator` — plain-JVM-testable core logic: given a
     `Map<TrackedStat, Long>` of a world's *current* stat values plus this
     feature's own persisted bookkeeping state (FR3.1), computes deltas
     (FR2.2-FR2.4), applies the first-read baseline rule (FR3.3), and returns
     the updated cumulative totals plus updated bookkeeping state — no
     Minecraft/Steamworks imports, fully unit-testable against fake input
     maps (mirrors `WorldSyncPreferenceService`'s own plain-JVM-testable
     shape in `features/steam-cloud-sync`).
   - `CrossWorldStatsConfigIO` — hand-rolled JSON read/write for the
     aggregate file (Configuration/Persistence), following
     `SteamWorldHostingConfigIO`'s exact fail-closed-to-defaults convention.
   - `CrossWorldStatsService` (or equivalent) — implements
     `CrossWorldStatsFacade`, owns the loaded/in-memory state, exposes
     `currentTotals()`, and is invoked by the platform Version Adapter's
     merge-hook callback (FR2.1) to trigger a merge-and-persist cycle.

3. **`platform/fabric-<version>/.../crossworldstats/`** (Version Adapters):
   - `CrossWorldStatsMergeHook` — the Fabric-API event registration (FR2.1),
     reading `Minecraft.getInstance().player.getStats()`/equivalent per
     version for the tracked-stat subset's current values and the current
     world's `WorldFingerprint`-equivalent identifier (obtained via the same
     composition-root handoff `features/steam-cloud-sync` already publishes
     its own identifier-resolution logic through, Architecture), then calls
     `CrossWorldStatsService`'s merge entry point.
   - `CrossWorldStatsClientInitializer` — composition root: constructs
     `CrossWorldStatsService`/`CrossWorldStatsConfigIO`, obtains
     `SteamFriendsGateway`/`SteamworksServiceHandoff` (FR1.1) and
     `features/steam-cloud-sync`'s world-identifier resolution (Architecture)
     via the existing handoff pattern, registers `CrossWorldStatsMergeHook`,
     and publishes `CrossWorldStatsFacade` for `features/main-menu`'s own
     composition root to consume (mirroring `FriendsSidebarClientInitializer`'s
     own publish-a-facade-for-main-menu-to-consume shape).

## Architecture
Layering (`architecture.md:64-71`): `features/cross-world-stats` depends on
`api`/`services` only, never on `features/main-menu` or `features/steam-cloud-sync`
directly — the same composition-root-broker pattern every other cross-feature
integration in this repo already uses.

```
platform/fabric-<version>/.../CrossWorldStatsClientInitializer (composition root)
  |-- SteamworksServiceHandoff.require() / SteamFriendsGatewayHandoff.require()
  |     (FR1.1 -- localSteamId64() keying)
  |-- obtains features/steam-cloud-sync's existing WorldFingerprint-equivalent
  |     identifier resolution (already published by that feature's own
  |     composition root, `SteamCloudSyncClientInitializer`, per its own
  |     established handoff shape -- reused here rather than a second,
  |     parallel "identify this save directory" mechanism, FR3.1)
  |-- constructs CrossWorldStatsService/CrossWorldStatsConfigIO
  |-- registers CrossWorldStatsMergeHook (FR2.1) against the platform's own
  |     save/tick event
  |-- publishes CrossWorldStatsFacade for features/main-menu's own
        composition root to consume (its Statistics tab, out of this
        feature's own scope)
```

**Reuse of `features/steam-cloud-sync`'s world-identifier concept.** Rather
than this feature inventing a second "how do I stably identify a save
directory across renames" mechanism, it reuses that feature's own
`WorldFingerprint`-shaped identifier via the same composition-root handoff
broker pattern already established for cross-feature identifier/facade
sharing in this repo — this is a **read-only, best-effort reuse**, not a hard
compile-time dependency (`features/cross-world-stats` still only imports
`api`/`services`, never `features/steamcloudsync.*` directly); if the
identifier-resolution handoff is unavailable for any reason (feature disabled,
handoff not yet published at composition-root wiring time), this feature falls
back to the world's own save-directory name/path as its identifier, a
strictly-worse-but-functional fallback (renaming a world's folder would then
be misread as "a different world," resetting that one world's baseline per
FR3.3 rather than corrupting the global total — an acceptable, bounded
degradation, not a crash risk).

**No dependency on `features/main-menu`.** Exactly like every other
feature `features/main-menu` composes (`friends-sidebar`, `server-browser`,
`server-join-presence`), the dependency direction is one-way:
`features/main-menu`'s own composition root will obtain this feature's
`CrossWorldStatsFacade` once its own Statistics tab is built — this feature
has zero knowledge of `features/main-menu`'s existence.

## UI
No UI of its own — this feature is a background tracker/service only. The
consuming Statistics tab (built as a `features/main-menu` amendment, out of
scope here) is expected to render `CrossWorldStatsSnapshot.cumulativeTotals()`
as a simple label/value list (one row per `TrackedStat`), following that
feature's own existing tab-panel visual conventions — no rendering
requirement is specified by this document.

## Configuration
One new small, hand-rolled-JSON config file under Fabric Loader's per-instance
config directory, following the exact convention every sibling feature's
config file already uses:

- **`config/cross-world-stats.json`**:
  ```json
  {
    "accounts": {
      "76561197960287930": {
        "totals": {
          "BLOCKS_MINED": 48213,
          "PLAY_TIME_TICKS": 1904200,
          "MOB_KILLS": 372,
          "DEATHS": 14
        },
        "worldBaselines": {
          "a1b2c3d4-world-fingerprint-or-path": {
            "BLOCKS_MINED": 1200,
            "PLAY_TIME_TICKS": 30000,
            "MOB_KILLS": 8,
            "DEATHS": 1
          }
        }
      },
      "offline": {
        "totals": { "...": "..." },
        "worldBaselines": { "...": "..." }
      }
    }
  }
  ```
  - Top-level `accounts` map is keyed by `localSteamId64()` as a string (or
    the `"offline"` sentinel, FR1.2) — supports multiple Steam accounts
    having independently tracked totals on one machine (FR1.3).
  - `totals` is the cumulative, all-time, cross-world sum per tracked stat
    (FR4.1) for that account — this is what `CrossWorldStatsFacade.currentTotals()`
    returns.
  - `worldBaselines` is the per-(world identifier, stat) "last known value"
    bookkeeping table (FR3.1/FR3.2) used to compute deltas correctly on the
    next merge — internal to this feature, not part of the public
    `CrossWorldStatsSnapshot` shape.
  - If the file is missing, it is created empty (`{"accounts": {}}`) on first
    run; malformed content falls back to an empty aggregate with a logged
    warning, never crashing — same fail-closed convention as every sibling
    config file.
  - Which specific `Stat`/`TrackedStat` keys appear (FR4.1/FR4.2) is
    determined by code, not by this file — the file only ever stores
    *values* for whatever the current code's tracked-stat set is; a future
    code change adding a new tracked stat simply starts accumulating a new
    key from `0` the next time it's observed (no migration needed, matching
    FR3.3's own first-read-baseline behavior for genuinely new (world, stat)
    pairs).

## Events
- Consumes a world-save or periodic-tick Fabric API event (FR2.1) — exact
  event a planning-phase, per-version `javap`/API-surface-confirmed decision,
  not fixed here.
- No new event-bus entries (this repo has no generic event bus, per
  established convention) — `CrossWorldStatsMergeHook`'s callback is a direct
  method call into `CrossWorldStatsService`, not a published event.

## Networking
None. This feature is 100% local: reads local in-memory `StatisticsManager`
state, reads/writes a local config file, and reads `SteamFriendsGateway.localSteamId64()`
(already-established, already-running Steamworks IPC this feature does not
add any new call frequency to — `localSteamId64()` is a cheap, already-cached
local read, not a network round-trip). No client-server packet, no new
protocol, no dedicated-server code.

## Persistence
- `config/cross-world-stats.json` (Configuration) — the sole persisted
  artifact: cumulative totals plus per-world delta-bookkeeping baselines, per
  Steam account. Written on each merge-hook firing (FR2.1), debounced/batched
  per Performance.
- No new NBT/world-save data — this feature never writes into any world's
  own save directory or `stats/<uuid>.json` file; it only reads the client's
  in-memory `StatisticsManager` object (FR2.2).
- Not cloud-synced in v1 (Non-goals) — purely a local machine's own config
  directory content, same persistence tier as `features/main-menu`'s own
  Wardrobe equip-map.

## Compatibility
- Same three platform modules as every other feature in this repo
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`)
  — `CrossWorldStatsMergeHook`'s exact `StatisticsManager`/save-event API
  surface must be independently `javap`-confirmed per module (Yarn vs. Mojang
  mapping divergence expected in class/method names, not in shape), per this
  repo's standing pre-implementation discipline.
- Depends (soft, fallback-safe) on `features/steam-cloud-sync`'s world-
  identifier concept being available at composition-root wiring time
  (Architecture) — functions correctly, with a documented, bounded
  degradation, if that feature is absent/disabled (falls back to save-
  directory name/path as the world identifier).
- No compatibility impact on any existing feature — this is new, additive
  code with no modification to vanilla's own `StatisticsManager`/stat-save
  file format, and no modification to any other feature's existing files.

## Performance
- The merge-hook (FR2.1) reads a small, fixed-size map of tracked-stat
  current values from the already-in-memory `StatisticsManager` (no disk I/O
  for the read itself) — O(1) relative to the size of vanilla's full stat
  registry, since only the curated `TrackedStat` subset (FR4.1) is read, not
  every registered stat.
- The config-file rewrite (FR2.3) is a small JSON file (bytes to low
  kilobytes even with many worlds' baselines accumulated over time, FR3.4) —
  cheap to rewrite in full on each merge tick, but implementation should
  still avoid rewriting it every single client tick if the merge-hook's
  chosen trigger (FR2.1) is a high-frequency one — debounce to at most once
  per auto-save interval or once per N seconds, whichever the planning-phase
  trigger choice makes more natural, avoiding unnecessary disk I/O.
- No per-frame rendering cost of any kind — this feature has no UI, no
  render-thread involvement at all beyond whatever the (out-of-scope)
  Statistics tab itself costs when it reads `currentTotals()`.

## Future Extensions
- Cloud-syncing `config/cross-world-stats.json` via `features/steam-cloud-sync`'s
  existing Steam Remote Storage mechanism, so a player's cumulative stats
  follow their Steam account across machines (Non-goals) — not built now,
  same "no established precedent for non-world-save config syncing yet"
  deferral `features/main-menu`'s own Wardrobe equip-map spec already
  documents.
- A per-world breakdown/drill-down view (which specific worlds contributed
  how much to the running total), using the already-retained
  `worldBaselines` bookkeeping data as its data source without needing any
  new tracking mechanism.
- Pruning stale `worldBaselines` entries for deleted worlds (FR3.4), if file
  size ever becomes a real concern.
- Expanding `TrackedStat` coverage toward a larger subset of vanilla's full
  stat registry, or making the tracked set user-configurable (FR4.2), if the
  Statistics tab's product direction calls for it.
- Steam-defined stats/leaderboards (`ISteamUserStats`' own stat-setting/
  leaderboard API, distinct from the achievement-reading surface
  `features/main-menu/specification-batch-2.md`'s Achievements tab uses read-
  only) as a way to publish/compare this feature's cumulative totals against
  friends — a materially larger scope (writing Steam-side stats, not just
  reading achievements) than anything this spec builds, flagged only as a
  long-range possibility.
