# Implementation Plan: Cloud Sync UUID Identity

Spec: `.claude/specs/cloud-sync-uuid-identity-spec.md` (final revision -- local
folders renamed to UUID, two independently-timed migration phases,
crash-safe breadcrumb). All section references below (`FRx.y`, `Public API`,
`Architecture`, etc.) refer to that document.

Out of scope, confirmed: Bug #3 (`gameMode` always "Unknown" for cloud-only
entries) -- do not touch any code or test related to it. Risk #4 (external
tools breaking on folder rename) is an accepted tradeoff, already resolved
by the user -- do not revisit.

## Existing implementation (verified against current repo state)

All of the following were read directly and match the spec's own file/line
citations, so implementation can trust the spec's line numbers as a starting
point (re-verify exact lines at implementation time, since the file has
uncommitted changes in flight per `git status`):

- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`
  -- Group 6 sync engine; `archiveFileName`/`metadataFileName` static
  helpers; implements `WorldFreshnessHook`, `WorldConflictHook`,
  `WorldConflictResolutionHook`; has a nested `KnownWorld` record
  (worldSlug/worldFolder/displayName-shaped, confirmed used by
  `CloudSyncCoordinator.listKnownWorlds()`).
- `features/steam-cloud-sync/.../services/WorldRestoreService.java` --
  `beginRestore(String worldSlug, RestoreProgressListener listener)`
  (line 104) resolves target folder as `savesDirectory.resolve(worldSlug)`
  (line 108), does the FR6.13 stale-folder/collision check (lines 109-124),
  calls `WorldSaveSyncService.archiveFileName(worldSlug)` (line 126),
  extracts via `extractAndFinish` (line 179), calls
  `preferenceService.markEnabledAfterRestore(context.worldSlug)` (line 236).
  `RestoreContext` (line 340) carries only `worldSlug`/listener/buffer/
  progress state -- confirmed it has no separate display-name field today,
  matching spec's note that no new field is needed there.
- `features/steam-cloud-sync/.../services/CloudOnlyWorldDetector.java` --
  `detect(List<String> localWorldFolderNames, List<WorldFingerprint>
  fingerprints)` (line 41) is a plain set-difference via `HashSet`,
  confirmed exactly as spec describes; will need the FR4.3 new parameter.
- `features/steam-cloud-sync/.../services/CloudSyncCoordinator.java` --
  `class CloudSyncCoordinator` (line 58) constructs
  `worldSaveSyncService`/`worldRestoreService`/`cloudOnlyWorldsFacade`
  (lines 151-157); `listKnownWorlds()` (line 214) builds
  `WorldSaveSyncService.KnownWorld` entries (line 222, `worldSlug` used
  as both slug and displayName arg, confirming FR7.1's placeholder
  description); `checkAndUploadStaleWorldsAtStartup(listKnownWorlds())` is
  called at line 198 -- this is the Phase B / `runPendingRenames()`
  qualifying checkpoint per FR2.2.
- `features/steam-cloud-sync/.../config/` contains the existing IO-class
  precedent to mirror for the new `WorldCloudMigrationIO`:
  `WorldSyncPreferencesIO.java` (confirmed: `ParseResult(list, warning)`
  record pattern, `CURRENT_SCHEMA_VERSION` constant, local-only,
  never-throws-on-malformed-content contract) plus sibling
  `WorldFingerprintIO`, `WorldSyncAncestorIO`, `WorldCloudMetadataIO`,
  `CloudSyncableUploadStateIO` all follow the same shape.
- Existing test files confirming per-class unit test precedent/location:
  `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/`
  contains `CloudOnlyWorldDetectorTest.java`, `WorldRestoreServiceTest.java`,
  `WorldSaveSyncServiceTest.java`, `WorldSyncPreferenceServiceTest.java`,
  `CloudSyncCoordinatorTest.java`; `.../config/` contains
  `WorldSyncPreferencesIOTest.java`, `WorldFingerprintIOTest.java`,
  `WorldSyncAncestorIOTest.java`, `WorldCloudMetadataIOTest.java` -- new
  classes get siblings in the same packages.
- `api/src/main/java/de/lazuli/api/cloudsync/` holds
  `CloudOnlyWorldSummary.java`, `WorldRestoreHook.java`,
  `WorldFreshnessHook.java`, `WorldConflictHook.java`,
  `WorldConflictResolutionHook.java`, `WorldSyncToggleHook.java`,
  `WorldSyncStatusHook.java`, and (uncommitted, new)
  `RestoreFailureMessages.java`.
- Platform modules each have their own `WorldsPanel.java`
  (`de.lazuli.mainmenu`) and `WorldRestoreScreen.java`
  (`de.lazuli.cloudsync`) under `platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2` -- three parallel copies,
  all currently showing as modified in `git status` from prior,
  already-landed work (conflict/status UI rework); this feature's changes
  land on top of that landed work, not in place of it.
- `git status` also shows in-flight, currently-uncommitted changes to
  `SteamRemoteStorageWorldArchiveStore.java`, `SteamworksService.java`,
  and `WorldRestoreServiceTest.java` -- unrelated prior work still pending
  commit; this feature's plan does not depend on those specific diffs
  landing first, but implementation should be aware the working tree is
  not clean and should avoid clobbering those changes.
- No `WorldCloudMigrationService`, `LevelDatNameReader`, or
  `WorldCloudMigrationIO` class exists yet (all new, confirmed via glob
  of `services/` and `config/` directories above).

## Files to create

1. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldCloudMigrationService.java`
   -- per spec's Public API section verbatim: `load()`,
   `existingCloudWorldId`, `resolveCloudWorldId`, `runPendingRenames`,
   `knownLocalCloudWorldIds`. Owns the in-memory breadcrumb map and drives
   FR2.1 (Phase A) and FR2.2 (Phase B).
2. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/LevelDatNameReader.java`
   -- static `readLevelName(Path, String fallback)` per FR7.2, Minecraft-type-
   free (raw `GZIPInputStream` + minimal NBT walk for `Data.LevelName`).
3. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/config/WorldCloudMigrationIO.java`
   -- load/save `world-cloud-migration.json`, mirroring
   `WorldSyncPreferencesIO`'s `ParseResult(entries, warning)` shape and
   never-throw-on-malformed contract.
4. Test files (see Test strategy):
   `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldCloudMigrationServiceTest.java`,
   `.../services/LevelDatNameReaderTest.java`,
   `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/config/WorldCloudMigrationIOTest.java`.

## Files to modify

- `features/steam-cloud-sync/.../services/WorldSaveSyncService.java` --
  constructor gains `WorldCloudMigrationService`; every public method
  currently taking `String worldSlug` as a Cloud-key argument calls
  `migrationService.resolveCloudWorldId(worldSlug)` at its top and uses the
  resolved value for Cloud-facing calls (`archiveFileName`/
  `metadataFileName` call sites at approx. lines 343, 632, 792-803, 849,
  928, 944, 991, 1074, 1096 -- re-locate exact lines at implementation time,
  file has moved since spec was written); `checkAndUploadStaleWorldsAtStartup`
  additionally calls `migrationService.runPendingRenames()` once.
- `features/steam-cloud-sync/.../services/WorldRestoreService.java` --
  `beginRestore` signature gains `displayName`
  (`beginRestore(String cloudWorldId, String displayName,
  RestoreProgressListener listener)`); target folder computed directly via
  `savesDirectory.resolve(cloudWorldId.toString())` (unchanged from current
  `resolve(worldSlug)` in shape, since caller now always passes a UUID
  string); log/toast strings (lines ~111, 121, 129, 136, 238) use
  `displayName` instead of raw slug; `markEnabledAfterRestore` call (line
  236) keeps using the folder name (already the UUID); after a successful
  restore, invoke the same Phase A migration codepath for the
  Compatibility case (old-style Cloud key restored from -- see FR-
  Compatibility) so the archive is migrated to the fresh `cloudWorldId`
  immediately post-restore.
- `features/steam-cloud-sync/.../services/CloudOnlyWorldDetector.java` --
  `detect` gains a `Set<String> pendingRenameCloudWorldIds` (or similar)
  parameter per FR4.3; treat membership as "known locally" alongside the
  existing folder-name set-difference (FR4.2).
- `features/steam-cloud-sync/.../services/CloudOnlyWorldsFacade.java` --
  thread the new `detect` parameter through `listCloudOnlyWorlds`.
- `features/steam-cloud-sync/.../services/CloudSyncCoordinator.java` --
  construct/wire `WorldCloudMigrationService` (inject into
  `WorldSaveSyncService`/`WorldRestoreService`/`CloudOnlyWorldsFacade`);
  `listKnownWorlds()` and `onSyncEnabledListener`/`onSyncDisabledListener`
  wiring use `LevelDatNameReader` instead of the folder-name-as-displayName
  placeholder (FR7.3); `handleSyncReenabled` additionally calls
  `migrationService.runPendingRenames()` (second qualifying Phase B
  checkpoint, FR2.2).
- `features/steam-cloud-sync/.../services/WorldSyncPreferenceService.java`
  -- new `void renameKey(String oldFolderName, String newFolderName)`
  (FR3.4), no-op if no entry under `oldFolderName`, persists via existing
  IO plumbing.
- `features/steam-cloud-sync/.../api/WorldFingerprint.java`,
  `.../api/WorldCloudMetadata.java`, `.../api/WorldSyncAncestor.java` --
  javadoc-only, no field/shape changes.
- `api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldSummary.java`
  -- javadoc-only.
- `api/src/main/java/de/lazuli/api/cloudsync/WorldRestoreHook.java` --
  `beginRestore` signature gains `displayName` parameter.
- `platform/fabric-1.21.11/.../mainmenu/WorldsPanel.java`,
  `platform/fabric-26.1/.../mainmenu/WorldsPanel.java`,
  `platform/fabric-26.2/.../mainmenu/WorldsPanel.java` -- no change for
  Cloud-key resolution (FR6.2); each needs the Risk #3 cache-invalidation
  change: after a rename signal from `runPendingRenames()` (surfaced via a
  return value/callback -- exact shape decided at implementation time),
  force `summaries` re-scan and clear/re-key `freshnessCache`,
  `conflictCache`, `state.expandedRowId()`. `downloadAndPlay`/`downloadOnly`
  call sites updated for the `beginRestore` signature change (pass
  `displayName` through, already available at these call sites per spec).
- `platform/fabric-1.21.11/.../cloudsync/WorldRestoreScreen.java`,
  `platform/fabric-26.1/.../cloudsync/WorldRestoreScreen.java`,
  `platform/fabric-26.2/.../cloudsync/WorldRestoreScreen.java` --
  follow-through for the `beginRestore` signature change only (no new
  resolution logic); `WorldConflictScreen.java` (same three platforms,
  "Keep Cloud" path) likewise.
- `services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`
  -- check whether this class currently constructs
  `WorldSaveSyncService`/`WorldRestoreService`/`CloudSyncCoordinator`
  directly (composition root); if so, update the constructor call to wire
  the new `WorldCloudMigrationService` (breadcrumb file path under the
  same `featureConfigDir` as `WorldSyncPreferencesIO`'s file, savesDirectory,
  the existing `archiveStore`/`cloudFileStore`/`preferenceService`, and
  logger consumers). Confirm exact wiring shape by reading this file at
  implementation time (not re-read here to keep this plan lean since it is
  a mechanical wiring change, not a design decision).

Not modified: `SteamRemoteStorageWorldArchiveStore.java`'s currently-pending
uncommitted diff, `RestoreFailureMessages.java` (new, uncommitted, appears
orthogonal to this feature -- confirm no overlap at implementation start;
if it turns out to define restore-failure message strings this feature's
`beginRestore` changes also touch, reconcile then, not now).

## Sequencing / phases

Implementation should land in dependency order, each phase independently
buildable/testable:

1. **Foundation (no behavior change yet).** Create
   `WorldCloudMigrationIO` + test, `LevelDatNameReader` + test. Pure,
   no dependents yet, fastest to get right in isolation.
2. **Migration service core (FR2.1 Phase A, FR1.2/FR1.3 resolution, FR2.4
   breadcrumb).** Create `WorldCloudMigrationService` + test, covering
   `existingCloudWorldId`/`resolveCloudWorldId` against a fake
   `WorldArchiveCloudStore`/`CloudFileStore`. Depends on phase 1's IO class.
3. **Phase B (rename) + preference re-keying.** Add
   `WorldSyncPreferenceService.renameKey` + test; add
   `WorldCloudMigrationService.runPendingRenames` + test (depends on phase 2
   existing already, same class). This is the highest-risk phase per the
   spec's Risk section -- implement and test the failure-safety path
   (locked/failed `Files.move`) before anything wires it into a live
   checkpoint.
4. **Wire migration into `WorldSaveSyncService`.** Constructor dependency +
   `resolveCloudWorldId` call at the top of every Cloud-key method +
   `runPendingRenames()` call in `checkAndUploadStaleWorldsAtStartup`.
   Depends on phases 2-3. Update `WorldSaveSyncServiceTest.java`.
5. **`CloudOnlyWorldDetector`/`CloudOnlyWorldsFacade` FR4.2 fix.** Depends
   on phase 2 (`knownLocalCloudWorldIds()`), independent of phases 3-4.
   Can be done in parallel with phase 4.
6. **`CloudSyncCoordinator` wiring.** Construct/inject
   `WorldCloudMigrationService`; `LevelDatNameReader`-backed
   `listKnownWorlds()`/listener displayName fix (FR7.3, independent of
   migration mechanics, can land any time after phase 1); second
   `runPendingRenames()` call site at `handleSyncReenabled`. Depends on
   phases 2-4.
7. **`WorldRestoreService` + `WorldRestoreHook` signature change (FR5,
   Compatibility's old-key-restore migration).** Depends on phase 2 (reuses
   Phase A codepath) and phase 6 (coordinator wiring for the migration
   service instance restore service now needs).
8. **`api` module signature change fallout.** `WorldRestoreHook.beginRestore`
   new parameter -- update every implementer/caller. Must land together
   with (same commit boundary as) phase 7, since it is a breaking signature
   change across module boundaries.
9. **Platform modules (`WorldsPanel`, `WorldRestoreScreen`,
   `WorldConflictScreen`, x3 platforms).** Depends on phase 8 (compile
   breakage otherwise) and phase 6 (rename-signal callback shape). Do all
   three platform copies together per file to avoid one platform lagging
   and silently missing the Risk #3 cache-invalidation fix.
10. **`services/SteamworksService.java` composition-root wiring.** Depends
    on every service-layer class above existing with final constructor
    shapes; last, since it is pure wiring with no new logic of its own.

Each phase's own module's tests must pass before moving to the next phase
that depends on it; a later phase's compile failure is a signal an earlier
phase's public shape needs revisiting, not something to patch around in the
later phase.

## Risks

1. **Windows file-lock timing on rename (Risk #1 in spec).** Mitigation:
   `runPendingRenames()` is only ever invoked from the two checkpoints the
   spec names (`checkAndUploadStaleWorldsAtStartup`, `handleSyncReenabled`);
   implementation must not add any other call site, and must not be
   tempted to call it from `onWorldUnload` even though that would make
   renames happen sooner -- this is explicitly forbidden by FR2.2. Verify
   via code review at verification phase that no other call site exists.
2. **Partial/interrupted rename leaving a world orphaned (Risk #2).**
   Mitigation: breadcrumb-first-then-act ordering (write breadcrumb before
   any Cloud I/O; only delete old-keyed Cloud data after new-keyed data is
   confirmed written; only mark `renamed=true` after `Files.move` returns
   successfully). Test strategy item 4/6 below cover this directly with
   simulated mid-sequence failures.
3. **Stale in-memory `WorldsPanel` caches after a rename (Risk #3).** Three
   parallel platform files must each get the same cache-invalidation fix;
   risk of one platform module being missed. Mitigation: phase 9 explicitly
   groups all three platforms into one work item, and verification phase
   should diff the three `WorldsPanel.java` files' rename-handling blocks
   against each other for parity.
4. **Signature-change ripple (`WorldRestoreHook.beginRestore`,
   `CloudOnlyWorldDetector.detect`).** Both are internal, non-published
   APIs (per spec's Compatibility section) but touch 3 platform modules +
   api + features -- a missed call site fails to compile, which is
   self-catching, not a silent risk, but increases the number of files
   touched in one logical change. Mitigation: phases 7-9 sequencing above
   keeps the breaking change and all its call-site fixes in adjacent,
   dependency-ordered work items rather than spread across unrelated
   commits.
5. **Working tree not clean at implementation start** (per `git status`:
   uncommitted changes already in `SteamRemoteStorageWorldArchiveStore.java`,
   `WorldRestoreServiceTest.java`, `SteamworksService.java`, three
   `WorldRestoreScreen.java`/`WorldsPanel.java` pairs, plus new untracked
   `RestoreFailureMessages.java`). Mitigation: implementation phase should
   confirm with the user whether those pending changes should be committed
   first or are meant to be carried forward together with this feature's
   changes, before editing the same files.
6. **`LevelDatNameReader`'s hand-rolled NBT/gzip parsing correctness**
   (no `NbtIo`/`CompoundTag` dependency, per the module's Minecraft-type-
   free constraint). Mitigation: test against real, minimal-but-valid
   gzipped NBT fixtures (can be generated once and checked into test
   resources) covering at least: normal `LevelName` present, missing
   `Data` tag, missing `LevelName` tag, corrupt gzip, truncated file.

## Dependencies

- No new external (non-Fabric, non-JDK) dependency is required by this
  feature: `WorldCloudMigrationService`/`WorldCloudMigrationIO` reuse the
  same plain-JSON-via-existing-project-convention approach as
  `WorldSyncPreferencesIO` (confirmed by reading that file -- no
  third-party JSON library import beyond whatever this module already
  uses); `LevelDatNameReader` uses only `java.util.zip.GZIPInputStream`
  (JDK). No Maven Central lookup is needed for this plan.
- Internal dependency graph mirrors the Sequencing section above: config-IO
  -> migration-service -> WorldSaveSyncService wiring -> coordinator wiring
  -> restore-service/api signature change -> platform modules -> composition
  root (`SteamworksService`).

## Test strategy

Per-module automated tests (JUnit, same style/location as existing sibling
tests cited in Existing Implementation):

- `WorldCloudMigrationIOTest` -- load/save round-trip, malformed-JSON
  fallback-with-warning, matches `WorldSyncPreferencesIOTest` shape.
- `LevelDatNameReaderTest` -- fixture-based cases per Risk #6 above.
- `WorldCloudMigrationServiceTest` -- spec's Testing/Acceptance items 2, 3,
  4, 5, 6 (FR1.2 zero-I/O fast path; Phase A success; Phase A failure-and-
  retry-same-UUID; Phase B success; Phase B failure-and-retry), using a
  fake `WorldArchiveCloudStore`/`CloudFileStore` with call-counting, and
  `@TempDir` for real folder rename assertions.
- `WorldSaveSyncServiceTest` (existing, extend) -- assert every public
  Cloud-key method now routes through `resolveCloudWorldId` (a fake/spy
  `WorldCloudMigrationService` returning a fixed UUID is sufficient; no
  need to re-test Phase A/B mechanics here, only that the call happens).
- `CloudOnlyWorldDetectorTest` (existing, extend) -- spec's items 7 and 8
  (steady-state set-difference unchanged; transient-window pending-rename
  case not reported cloud-only).
- `CloudOnlyWorldsFacadeTest` (existing, extend) -- new parameter threads
  through correctly.
- `WorldSyncPreferenceServiceTest` (existing, extend) -- `renameKey`
  moves/no-ops correctly, spec item 13 (un-sync/re-sync after migration is
  a no-op).
- `WorldRestoreServiceTest` (existing, extend -- note this file already has
  uncommitted changes pending, reconcile first) -- spec items 9, 10, 14
  (UUID-named folder created directly; toast/log use displayName not UUID;
  no further Phase A calls on next sync; old-style-key restore triggers
  Phase A migration against the just-extracted folder).
- `CloudSyncCoordinatorTest` (existing, extend) -- `listKnownWorlds()`/
  listener wiring use `LevelDatNameReader`-sourced display names instead of
  folder-name placeholder; `runPendingRenames()` invoked once at the
  `checkAndUploadStaleWorldsAtStartup` and `handleSyncReenabled` checkpoints.
- Any new platform-module logic (cache invalidation in `WorldsPanel`) --
  check whether platform modules currently have unit test coverage at all
  (verify at implementation time; if not, this is consistent with existing
  project practice and does not need new test infrastructure invented for
  this feature alone) -- otherwise cover with the same style of test as
  whatever the platform module already uses, if any.
- Full test suite for every touched module (`api`, `features/steam-cloud-sync`,
  `services`, all three `platform/fabric-*` modules) must be rerun (not
  just the new/changed test classes) before any work item in this feature
  is considered done, per standing project constraint.

Explicitly manual/live, user-only (never scheduled as an automated step,
and never triggered by the agent since Minecraft must never be launched
during remote control):

- Spec's own UI section: toggling sync on for an existing world and
  confirming its row in the Worlds tab behaves normally through and after
  its folder's physical rename (no visible glitch/disappearance/unclickable
  frame).
- End-to-end live verification that a real save folder survives a rename
  round-trip on the user's actual Windows filesystem (antivirus/OS-level
  locking behavior cannot be fully simulated by a `@TempDir` unit test).
- Verifying the two real local worlds with colliding folder names scenario
  end-to-end (spec's core acceptance item 1) against real Steam Cloud, not
  just fakes.
- Cross-device migration behavior, if the user has access to a second
  device/Steam account to test with (spec's Compatibility section --
  explicitly out of automated-test reach).

## Acceptance criteria

All 14 numbered items in the spec's "Testing / Acceptance Criteria"
section, each covered by the automated test mapped to it above (items 1
and part of 9/14's end-to-end Cloud behavior additionally need the
user's live verification, per Test strategy). In addition:

- No code path in this feature ever mutates `level.dat`'s `LevelName`
  (spec item 11) -- verify by grep/code-review at verification phase, not
  just by test absence-of-mutation assertions.
- No screen renders a raw UUID as a world's display name anywhere (spec
  item 11) -- verify by reviewing every changed screen file's string
  construction, not just the toast/log strings this plan explicitly lists.
- Bug #3 (`gameMode` "Unknown") remains untouched -- zero diff lines in any
  file whose sole purpose is that bug, confirmed at verification phase.
- Every touched module's full test suite passes, not just newly-added
  tests.
- Every implementation work item that changes code inside `services/` or
  any `platform/fabric-*` module is followed by a full rebuild that
  repackages jars, not merely `compileJava`: `:services:jar` after any
  `services/` change, and each affected platform module's
  `:platform:fabric-1.21.11:processIncludeJars` /
  `:platform:fabric-26.1:processIncludeJars` /
  `:platform:fabric-26.2:processIncludeJars` (or that module's equivalent
  jar-bundling task -- confirm exact task name from `build.gradle`/
  `settings.gradle` at implementation time if it differs) after any change
  to `features/steam-cloud-sync` or `api`, since those are bundled into the
  platform jars. A work item that only ran `compileJava` is not done.
- Working tree's pre-existing uncommitted changes (per Risks item 5) are
  either committed separately first or explicitly folded into this
  feature's commits with the user's knowledge -- not silently overwritten.
