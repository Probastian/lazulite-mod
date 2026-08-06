# Implementation Plan: Sync Tweaks Like Options

Spec: `.claude/specs/sync-tweaks-like-options.md`

## Existing Implementation

- Each of the three platform modules (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`) has its own `src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java`, structurally identical across modules. Verified byte-for-byte identical for the relevant slice (lines ~50-68) in `fabric-26.2` and `fabric-26.1`; per spec section "Overview" the same holds for `fabric-1.21.11`.
- `onInitializeClient()` (line 50) builds `cloudSyncables` as `List.of(...)` (lines 65-68) containing `OptionsTxtCloudSyncAdapter`, `ServersDatCloudSyncAdapter`, `CrossWorldStatsCloudSyncAdapter`, all private static nested classes of `SteamCloudSyncClientInitializer` declared later in the same file (lines 121-160, 168-207, ~209+).
- `OptionsTxtCloudSyncAdapter` (lines 121-160) is the exact shape to mirror:
  - `cloudSyncId()` returns a short literal id (`"options"`).
  - `exportState()`: `Files.exists(path) ? Files.readAllBytes(path) : new byte[0]`, catch `IOException`, log via `LazuliMod.LOGGER.warn(...)`, return `new byte[0]`.
  - `importState(byte[] data)`: `Files.write(path, data)`, catch `IOException`, log warning, no rethrow.
  - `localLastModifiedMillis()`: `Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L`, catch `IOException` → `-1L`.
  - Constructor takes a single `Path` field, set once, no other state.
- `gameDir`/`configDir` are both already resolved as local variables in `onInitializeClient()` before the `cloudSyncables` list literal (lines 53, 64). `configDir.resolve("cross-world-stats.json")` (line 68) is the existing precedent for resolving a `configDir`-relative file inline in the list literal — the same pattern applies for `configDir.resolve("tweaks.json")`.
- `TweaksClientInitializer.java` (same three platform modules, identical structure) independently resolves `tweaksConfigPath = configDir.resolve("tweaks.json")` (line 36) via its own `FabricLoader.getInstance().getConfigDir()` call — confirms the path spec FR2 requires is `<configDir>/tweaks.json`, matching `CrossWorldStatsCloudSyncAdapter`'s sibling file rather than `gameDir`-rooted `options.txt`/`servers.dat`.
- `CloudSyncCoordinator`, `CloudSyncableReconciler`, `CloudSyncableUploadGate`, `CloudSyncable` (the `api` module interface) are all adapter-agnostic — none reference concrete adapter types, only the `CloudSyncable` interface and each adapter's `cloudSyncId()` string. No change needed to any of them (confirms spec FR3/FR4).
- No existing unit test directly exercises `OptionsTxtCloudSyncAdapter`, `ServersDatCloudSyncAdapter`, or `CrossWorldStatsCloudSyncAdapter` as concrete classes: `platform/fabric-26.2/src/test/java/de/lazuli/` contains only `friends/FriendContextMenuWidgetTest.java`, `cloudsync/CrossWorldStatsOfflineBucketFilterTest.java` (tests the pure-function filter, not the adapter), `mainmenu/WorldsPanelStatusTest.java`, `cloudsync/WorldConflictScreenValuesMatchTest.java`. `features/steam-cloud-sync/src/test/java/.../services/CloudSyncableReconcilerTest.java` and `CloudSyncCoordinatorTest.java` exercise the reconciler/coordinator against hand-written test doubles of `CloudSyncable`, not the platform-module adapters themselves (the adapters are private nested classes of a platform-module composition root, inaccessible from `features/steam-cloud-sync`'s test source set). This settles spec FR5's "confirm at planning time" question: no adapter-level unit test exists for any of the three current siblings, so none is added for the fourth — consistent with the existing precedent, not a coverage regression.

## Files to Modify

For each of the three platform modules — `platform/fabric-1.21.11/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java`, `platform/fabric-26.1/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java`, `platform/fabric-26.2/src/main/java/de/lazuli/SteamCloudSyncClientInitializer.java` — apply the identical two-part change:

1. Add a fourth entry to the `cloudSyncables` list literal (currently lines ~65-68 in each file):
   `new TweaksJsonCloudSyncAdapter(configDir.resolve("tweaks.json"))`, alongside the existing three entries. No new local variable needed — `configDir` is already in scope.
2. Add a new private static nested class `TweaksJsonCloudSyncAdapter implements CloudSyncable`, placed after `OptionsTxtCloudSyncAdapter` (or adjacent to `ServersDatCloudSyncAdapter`, whichever reads more naturally in the existing file's ordering) in each of the three files, structurally identical to `OptionsTxtCloudSyncAdapter` (spec FR1):
   - Field: `private final Path tweaksPath;`
   - Constructor: `private TweaksJsonCloudSyncAdapter(Path tweaksPath) { this.tweaksPath = tweaksPath; }`
   - `cloudSyncId()` → `"tweaks"`.
   - `exportState()` → `Files.exists(tweaksPath) ? Files.readAllBytes(tweaksPath) : new byte[0]`, catch `IOException`, `LazuliMod.LOGGER.warn("Failed to read tweaks.json for Cloud export: {}", e.toString())`, return `new byte[0]`.
   - `importState(byte[] data)` → `Files.write(tweaksPath, data)`, catch `IOException`, `LazuliMod.LOGGER.warn("Failed to write tweaks.json from Cloud import: {}", e.toString())`.
   - `localLastModifiedMillis()` → `Files.exists(tweaksPath) ? Files.getLastModifiedTime(tweaksPath).toMillis() : -1L`, catch `IOException` → `-1L`.
   - A short doc comment mirroring `OptionsTxtCloudSyncAdapter`'s style (spec FR-reference + one-line rationale), consistent with the existing file's documentation convention.

No changes to imports are anticipated beyond what's already imported in each file (`CloudSyncable`, `Files`, `IOException`, `Path` are already imported for the existing adapters).

## Files to Create

None. This change is additive within three existing files; no new source files, no new test files (see Existing Implementation, last bullet), no new config/schema files.

## Risks

- **R1 — Drift across the three platform modules.** The three `SteamCloudSyncClientInitializer.java` files are hand-duplicated, not shared via a common module. A copy-paste change applied to one file and forgotten in another would leave sync behavior inconsistent per-platform. Mitigation: apply the identical edit to all three files in the same change; verification phase should diff the three files' `cloudSyncables` list and new nested class for parity.
- **R2 — Cloud filename collision or typo in `cloudSyncId()`.** `cloudSyncId()` directly determines the Cloud filename (`"lazuli-cloudsync-" + cloudSyncId() + ".dat"`). Using anything other than the exact literal `"tweaks"` would produce an unintended filename with no compile-time check. Mitigation: match spec FR1's literal exactly; verification phase should confirm the string.
- **R3 — Placement relative to `TweaksClientInitializer`'s own load.** Spec FR4 already establishes no new ordering dependency is required or introduced; this is a documentation-only risk (a future reader might assume one is needed). Mitigation: keep the doc comment on the new adapter explicit that no init-order coupling exists, mirroring `OptionsTxtCloudSyncAdapter`'s own FR-B.5 caveat comment.
- **R4 — Malformed/partial Cloud-pulled `tweaks.json`.** Since `importState` writes raw bytes with no validation, a corrupted or truncated pull could leave `config/tweaks.json` invalid until `TweaksConfigIO.load()` runs at next launch. This is explicitly out of scope per spec Non-goals/Compatibility (matches `options.txt`'s existing behavior; `TweaksConfigIO`'s already-existing forward-compatible parsing handles it). No mitigation needed beyond what already exists.

## Dependencies

None. No new external (non-Fabric) library dependency is introduced — this change uses only `java.nio.file.Files`/`Path` (already used by the sibling adapters in the same files) and the existing `CloudSyncable` interface from the `api` module. No Maven Central lookup required.

## Test Strategy

- No new automated test is added, matching the existing precedent that none of the three sibling adapters (`OptionsTxtCloudSyncAdapter`, `ServersDatCloudSyncAdapter`, `CrossWorldStatsCloudSyncAdapter`) has a dedicated unit test today (see Existing Implementation). The reconciliation/gating logic these adapters plug into (`CloudSyncableReconciler`, `CloudSyncCoordinator`) is already covered by `CloudSyncableReconcilerTest`/`CloudSyncCoordinatorTest` against `CloudSyncable`-typed test doubles, and is unmodified by this change (spec FR3).
- Manual/functional verification (to be performed during the verification phase, not automated):
  1. Build all three platform modules; confirm no compile errors from the new nested class / list entry in each.
  2. With Steam Cloud sync enabled and `syncSettings` true, run a client with existing `config/tweaks.json` content, allow shutdown to push, confirm (via existing logging or Steam's local Cloud cache) a `lazuli-cloudsync-tweaks.dat` file is produced.
  3. Simulate a second device (or delete local `tweaks.json` and re-launch) to confirm `importState` restores it and `TweaksConfigIO.load()` parses the pulled content correctly on next launch.
  4. Confirm `syncSettings=false` (or `enabled=false`) suppresses tweaks sync identically to how it already suppresses `options.txt`/`servers.dat` sync (shared gate, no new code path to test in isolation).
- If, during implementation, the assigned agent discovers a project-standard "adapter smoke test" pattern this plan's repo scan missed, prefer that convention consistently for all four adapters rather than introducing test coverage for only the new one — otherwise no new test file is warranted.

## Acceptance Criteria

- All three platform modules' `SteamCloudSyncClientInitializer.java` files register a fourth `CloudSyncable` — `TweaksJsonCloudSyncAdapter` — in their `cloudSyncables` list, resolved against `configDir.resolve("tweaks.json")`.
- `TweaksJsonCloudSyncAdapter.cloudSyncId()` returns exactly `"tweaks"` in all three files.
- `TweaksJsonCloudSyncAdapter`'s `exportState()`, `importState(byte[])`, and `localLastModifiedMillis()` are structurally identical (same exists-check, same exception handling, same warning-log-and-degrade behavior) to `OptionsTxtCloudSyncAdapter`'s implementation, differing only in the target path and log message text, across all three files.
- No change to `CloudSyncCoordinator`, `CloudSyncableReconciler`, `CloudSyncableUploadGate`, `CloudSyncable` (api module), `SteamCloudSyncConfig`/`SteamCloudSyncConfigIO`, `TweaksConfigIO`, `TweaksConfig`, `TweakState`, `TweakId`, or `TweaksClientInitializer.java` in any module.
- No new config keys, UI elements, or public API surface introduced (matches spec's Public API / UI / Configuration sections — none).
- The three platform modules' relevant edits (list entry + nested class) are identical to each other modulo nothing (byte-for-byte, same as the pre-existing three adapters already are).
- Project builds successfully (all three platform modules) after the change.
