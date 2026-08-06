# Sync Tweaks Like Options — Specification

## Overview
`features/steam-cloud-sync`'s Group 1 ("Settings") mechanism already syncs two vanilla files as opaque byte blobs — `options.txt` and `servers.dat` — via small `CloudSyncable` adapter classes defined per-platform in each `SteamCloudSyncClientInitializer`
(`platform/fabric-26.2/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java:121-160,168-207`, duplicated identically in `platform/fabric-26.1/...` and `platform/fabric-1.21.11/...`). This mod's own Tweaks feature (`features/tweaks`) persists its per-tweak enabled/configurable state locally to `config/tweaks.json` (`platform/.../TweaksClientInitializer.java:35-38`, read/written via `features/tweaks/src/main/java/de/lazuli/features/tweaks/config/TweaksConfigIO.java`) but has **no** `CloudSyncable` adapter registered anywhere — it is never read from or written to Steam Cloud today. This spec adds a fourth adapter, `TweaksJsonCloudSyncAdapter`, so `tweaks.json` is synced through the exact same code path, at the exact same checkpoints, with the exact same reconciliation/gating semantics as `options.txt`.

## Goals
- Add `tweaks.json` to Steam Cloud sync using the identical mechanism `options.txt` already uses: a `CloudSyncable` adapter reading/writing the file as an opaque byte array, registered into each platform module's `cloudSyncables` list (`SteamCloudSyncClientInitializer.java:65-68`).
- Preserve every existing behavior of that mechanism unchanged for `tweaks.json`: same reconciliation rule (`CloudSyncableReconciler`/`CloudSyncCoordinator.reconcileAtStartup()`, `CloudSyncCoordinator.java:183-199`), same shutdown push (`CloudSyncCoordinator.syncOnShutdown()`, `CloudSyncCoordinator.java:235-247`), same Group 1 master/`syncSettings` gating (`settingsSyncEnabled = config.enabled() && config.syncSettings()`, `CloudSyncCoordinator.java:125,186,236`), same upload-dedup gate (`CloudSyncableUploadGate`), same Cloud filename convention (`"lazuli-cloudsync-" + cloudSyncId() + ".dat"`, `CloudSyncCoordinator.java:286-288`).
- Ship this identically across all three supported Fabric platform modules (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`), since `SteamCloudSyncClientInitializer` and `TweaksClientInitializer` are duplicated per-module, not shared.

## Non-goals
- Any change to `features/tweaks`' own local persistence format, `TweaksConfigIO`, or `TweaksConfig`/`TweakState`/`TweakId` — this feature's local read/write path is untouched; only a Cloud mirror is added, exactly as `options.txt`'s own vanilla read/write path was untouched by its adapter.
- A typed/semantic merge of tweak config (e.g. per-tweak field-level reconciliation). `options.txt` and `servers.dat` are both synced as raw last-write-wins byte blobs (`CloudSyncable.exportState()`/`importState(byte[])`); `tweaks.json` gets the same treatment, not the richer typed-merge treatment `cross-world-stats` uses (`CrossWorldStatsCloudSyncAdapter`, `SteamCloudSyncClientInitializer.java:221-272`), since the request is "synced exactly like options," not "like cross-world-stats."
- A new, independent per-category toggle (e.g. `syncTweaks`) distinct from the existing `syncSettings` boolean. `options.txt`/`servers.dat` have no per-adapter toggle of their own — both are gated only by the shared `syncSettings` flag applied uniformly across the whole `cloudSyncables` list (`CloudSyncCoordinator.java:40-46,186,236`); `tweaks.json` joins that same list and is gated the same uniform way. Introducing per-adapter granularity is out of scope and would be a divergence from "like options," not a match.
- Any change to `SteamCloudSyncConfig`/`SteamCloudSyncConfigIO`'s on-disk schema (`features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/config/SteamCloudSyncConfigIO.java`). No new field is added there.
- Live/hot-reload of tweak state while the client is running if a newer Cloud copy is pulled mid-session — inherits the same known caveat `options.txt` already documents (FR-B.5 in `features/steam-cloud-sync/specification.md:118`; `SteamCloudSyncClientInitializer.java:113-120`): a fresher Cloud copy pulled after `TweaksConfigIO.load()` has already run at startup takes effect on the *next* launch, not the current one. Not addressed here, since `options.txt` doesn't address it either.

## Requirements
- **FR1** Define `TweaksJsonCloudSyncAdapter implements CloudSyncable` as a private static nested class inside each platform module's `SteamCloudSyncClientInitializer`, structurally identical to `OptionsTxtCloudSyncAdapter` (`SteamCloudSyncClientInitializer.java:121-160`):
  - `cloudSyncId()` returns `"tweaks"` (yielding Cloud filename `lazuli-cloudsync-tweaks.dat`, per `cloudSyncableFileName()`, `CloudSyncCoordinator.java:286-288`).
  - `exportState()` reads `config/tweaks.json` in full via `Files.readAllBytes`, returning `new byte[0]` if the file does not exist or a read fails (logging a warning on failure, same as `OptionsTxtCloudSyncAdapter.exportState()`).
  - `importState(byte[] data)` writes the given bytes to `config/tweaks.json` via `Files.write`, logging a warning on failure — never validates/parses the JSON itself (parsing/fallback-to-defaults on malformed content is `TweaksConfigIO`'s own job, already exercised the next time `TweaksClientInitializer` loads the file).
  - `localLastModifiedMillis()` returns `Files.getLastModifiedTime(tweaksPath).toMillis()` if the file exists, else `-1L`, matching `OptionsTxtCloudSyncAdapter.localLastModifiedMillis()` exactly.
- **FR2** Resolve `tweaksPath` the same way `TweaksClientInitializer` does today: `FabricLoader.getInstance().getConfigDir().resolve("tweaks.json")` (`TweaksClientInitializer.java:35-36`) — not a new/different path.
- **FR3** Register one `new TweaksJsonCloudSyncAdapter(gameDir-or-configDir-resolved-path)` instance into the `cloudSyncables` list literal in `SteamCloudSyncClientInitializer.onInitializeClient()` (`SteamCloudSyncClientInitializer.java:65-68`), alongside the existing three entries, in each of the three platform modules (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`). No change to `CloudSyncCoordinator`, `CloudSyncableReconciler`, or `CloudSyncableUploadGate` is needed — the list is the only thing platform code adds to.
- **FR4** No initializer-ordering change required: `SteamCloudSyncClientInitializer` and `TweaksClientInitializer` are independent Fabric client entrypoints and already run without a declared dependency on each other today (`options.txt` sync has the same relationship to Minecraft's own options-loading code and already works); this spec introduces no new one either.
- **FR5** Tests: extend/mirror the existing coverage pattern for `CloudSyncableReconciler`/`CloudSyncCoordinator` (`features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/CloudSyncableReconcilerTest.java`) with a `TweaksJsonCloudSyncAdapter`-equivalent fixture if the reconciler tests are structured per-adapter; since the reconciler itself is adapter-agnostic (works off the `CloudSyncable` interface, not the concrete adapter type), no new reconciler logic needs testing — only that the new adapter class itself correctly reads/writes/returns `-1L` for a missing file, mirroring however `OptionsTxtCloudSyncAdapter` is (or isn't) directly unit-tested today (confirm at planning time; this adapter is a thin `Files` wrapper with no business logic of its own, same category as its two existing siblings).

## Public API
No new public/`api`-module types. `TweaksJsonCloudSyncAdapter` is a private, platform-module-local implementation detail of `CloudSyncable` (`api/src/main/java/de/lazuli/api/cloudsync/CloudSyncable.java`), exactly like `OptionsTxtCloudSyncAdapter` and `ServersDatCloudSyncAdapter` are today — not exposed outside `SteamCloudSyncClientInitializer`.

## Architecture
No architectural change. This slots into the already-established Group 1 pattern (`features/steam-cloud-sync/specification.md:38-46`, Requirements "Group 1 — Settings/Preferences Sync"): a platform-composition-root-owned `CloudSyncable` adapter bridges a Feature's local file into Cloud sync without `features/steam-cloud-sync` importing `features/tweaks` directly (preserving the Feature→Feature dependency prohibition, `architecture.md:74`) — same bridging rationale already used for `options.txt`/`servers.dat` (vanilla files, no owning Feature) and `cross-world-stats` (a real Feature, bridged the same way). `tweaks.json` follows the vanilla-file adapters' shape (raw bytes) rather than `cross-world-stats`'s shape (typed load/merge via the owning Feature's config IO class), per Non-goals.

Reconciliation/push flow (unchanged, now also covering `tweaks.json`):
```
CloudSyncCoordinator.reconcileAtStartup()
  -> for each CloudSyncable (options, servers-dat, cross-world-stats, tweaks):
       CloudSyncableReconciler.reconcileAtStartup(cloudFileStore, "lazuli-cloudsync-<id>.dat", syncable,
           settingsSyncEnabled, uploadGate, warningLogger, playerNotifier)

CloudSyncCoordinator.syncOnShutdown()
  -> for each CloudSyncable: CloudSyncableReconciler.pushOnShutdown(...)
```

## UI
None. Like `options.txt`/`servers.dat`, `tweaks.json` sync has no dedicated toggle, icon, or screen — it rides the existing, invisible Group 1 sync path gated only by the feature's master `enabled` + `syncSettings` config booleans (already-existing settings, no new UI surface per Non-goals).

## Configuration
No new config keys. `SteamCloudSyncConfig`/`config/steam-cloud-sync.json` (`SteamCloudSyncConfigIO.java:84-115`) is unchanged — `syncSettings` (existing boolean) continues to gate the entire `cloudSyncables` list uniformly, `tweaks.json` included, exactly as it already gates `options.txt`/`servers.dat`/`cross-world-stats`.

## Events
None new. Uses the same existing Fabric lifecycle hooks already wired in each `SteamCloudSyncClientInitializer`: `ClientLifecycleEvents.CLIENT_STOPPING` (shutdown push) and the coordinator's own `reconcileAtStartup()` call during `onInitializeClient()` (`SteamCloudSyncClientInitializer.java:80-83`).

## Networking
None. Client-only, via Steam Cloud (`ISteamRemoteStorage`), same as every other `CloudSyncable` today — no new network surface.

## Persistence
- New Cloud file: `lazuli-cloudsync-tweaks.dat` (flat, lowercase, per `cloudSyncableFileName()`'s existing naming convention, `CloudSyncCoordinator.java:286-288`), holding a raw copy of `config/tweaks.json`'s bytes at the time of last sync.
- Local "real" file, source of truth for single-device operation: `config/tweaks.json` (unchanged, `features/tweaks` continues to own read/write of this file for local operation via `TweaksConfigIO`).
- No change to `CloudSyncableUploadGate`'s local dedup-state file (`config/steam-cloud-sync/cloudsyncable-upload-state.json`) beyond it gaining one more tracked `cloudSyncId` ("tweaks") entry, same as any other registered adapter.

## Compatibility
- A device with an old build lacking this adapter and a device with this change coexist safely: `CloudSyncableReconciler`/`CloudSyncCoordinator` treat every `CloudSyncable` independently by `cloudSyncId()`; an old-build device simply never uploads/pulls the `tweaks` entry, and a new-build device pulling a Cloud world with no pre-existing `lazuli-cloudsync-tweaks.dat` file behaves the same way `options.txt`'s adapter already behaves on a brand-new Cloud account (no file yet) — a no-op pull, first real push happens on next shutdown from the new-build device.
- No Minecraft/Fabric-version-specific logic — this is a plain `java.nio.file.Files` read/write of a JSON text file, same as `options.txt`, no Version Adapter needed.
- `TweaksConfigIO`'s existing forward-compatible parsing (unknown `TweakId` entries ignored, missing entries backfilled with defaults, `TweaksConfigIO.java:32-36,85-91`) already handles a Cloud-pulled `tweaks.json` written by a differently-versioned build of this mod — no new compatibility logic is needed in the adapter itself, since `importState` only writes raw bytes; `TweaksConfigIO.load()` (already-existing, unmodified) is what actually parses that content the next time `TweaksClientInitializer` runs.

## Performance
Negligible and unchanged in kind from `options.txt`'s existing adapter: `tweaks.json` is expected to remain a small (single-digit-KB) JSON file (`ConfigSchemas`/`TweakDefinitions`-bounded number of tweaks/configurables); read/write happens only at the existing FR0.3-equivalent checkpoints (client startup reconcile, client shutdown push) — never per-tick, never on the render/hot path, no new thread hop beyond what `CloudSyncableReconciler` already does for every other adapter in the list.

## Future Extensions
- A typed/merge-aware sync (per-tweak last-write-wins instead of whole-file last-write-wins) if two devices ever edit different individual tweaks between syncs — deferred, since `options.txt`/`servers.dat` don't do this either and the request was explicitly "exactly like options."
- An independent `syncTweaks` toggle, if a future product decision wants tweak sync to be separately controllable from `options.txt`/`servers.dat`/other Group 1 members — not requested here.
