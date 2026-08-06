# Implementation Plan: Proactive Stale Save-Folder Healing

Spec: `.claude/specs/proactive-stale-save-folder-healing.md`

## Existing Implementation

- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldRestoreService.java`
  - `beginRestore()` (lines 104-169) does the reactive heal at lines 108-124:
    if target folder exists and `!isRealSaveFolder(...)`, logs via `infoLogger`
    then calls `deleteRecursively(...)`.
  - `private static boolean isRealSaveFolder(Path candidate)` (lines 314-321):
    `false` if not a directory, else `Files.isRegularFile(candidate.resolve("level.dat"))`.
  - `private static void deleteRecursively(Path path)` (lines 323-338):
    no-op if `Files.notExists`; else `Files.walk` + reverse-order sort +
    `Files.deleteIfExists` per entry, swallowing all `IOException` (best-effort).
  - Constructor already takes `Path savesDirectory` and `Consumer<String> warningLogger`
    (a `Consumer<String> infoLogger` too, defaulted to no-op in the 5-arg overload).
- `features/steam-cloud-sync/src/test/java/.../WorldRestoreServiceTest.java`:
  `staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds` (line ~195) covers the
  reactive heal end-to-end via `beginRestore()`'s observable behavior only (not
  the two private methods directly) — confirmed safe to leave unchanged since it
  never references method visibility.
- Three near-identical `WorldsPanel.java` files (`fabric-1.21.11` line offsets
  differ slightly from `fabric-26.1`/`fabric-26.2`, which are identical to each
  other):
  - Constructor calls `reload()` once as its only statement.
  - `void reload()` (package-private) is the single re-entrant refresh point,
    called from the constructor and from `WorldRestoreScreen`/`WorldConflictScreen`
    completion callbacks.
  - `private void refreshCloudOnlyWorlds()` calls `private static List<String>
    listRealLocalSaveFolderNames()` (already present on all three, added by a
    separate, already-completed fix for the cloud-only-misdetection bug). This
    method: lists immediate children of `FabricLoader.getInstance().getGameDir().resolve("saves")`,
    and for each, if `Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("level.dat"))`,
    adds `candidate.getFileName().toString()` to the result list. Catches `IOException`
    from `Files.newDirectoryStream`, logs a warning, returns partial results.
  - All three files already import `net.fabricmc.loader.api.FabricLoader` and
    `de.lazuli.api.cloudsync.*` types (no `de.lazuli.features.steamcloudsync.*`
    import anywhere).
  - `WorldSyncStatusHookHolder` (per-platform static holder class) is already
    used elsewhere in each file's imports/usages for other hooks (holder class
    itself already exists per spec's research — this plan reuses it, does not
    create it).
- `api` module (`de.lazuli.api.cloudsync` package) already contains
  `DownloadProgressPresenter`, a concrete `public final class` utility with its
  own test `api/src/test/java/de/lazuli/api/cloudsync/DownloadProgressPresenterTest.java`
  — direct precedent for the new class's location, shape, and test placement.
- Module deps (`settings.gradle` + per-module `build.gradle`): `api` module is
  Minecraft-free, Java 21, no per-module override needed for a new plain class.
  Every `platform/*/build.gradle` already has `api project(':api')` + `include
  project(':api')` (Loom Jar-in-Jar), and `implementation project(':features:steam-cloud-sync')`
  + `include project(':features:steam-cloud-sync')`. `features/steam-cloud-sync/build.gradle`
  already has `api project(':api')`. **No `settings.gradle`/`build.gradle` changes
  needed anywhere.**

## listRealLocalSaveFolderNames() Refactor Decision

**Decision: refactor all three `WorldsPanel.listRealLocalSaveFolderNames()`
methods to call `StaleSaveFolderHealer.isRealSaveFolder(candidate)` in place of
their inline `Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("level.dat"))`
condition, instead of leaving the duplication in place.**

Reasoning:
- The two conditions are semantically and textually identical checks ("is this
  a directory containing a real, regular-file `level.dat`") — this is exactly
  the predicate FR1 extracts into `StaleSaveFolderHealer.isRealSaveFolder(Path)`.
  Leaving `listRealLocalSaveFolderNames()`'s copy un-refactored means the same
  logic exists in 4 places (1 new shared utility + 3 platform copies) instead
  of being consolidated behind the one utility the spec is explicitly
  introducing for this exact purpose (spec Goal 2: "Share the exact same 'what
  is a real save folder'... logic").
- Risk is low and the change is mechanical: `isRealSaveFolder` takes a `Path`
  and returns `boolean` with identical semantics to the inline check; swapping
  it in is a one-line condition replacement per file, not a restructuring of
  `listRealLocalSaveFolderNames()`'s directory-listing/name-collection logic
  or its own `IOException` handling (which stays untouched).
- This is distinct from (and does not touch) the unrelated cloud-only-misdetection
  fix's actual purpose (using an independent filesystem scan instead of
  `entries`/`summaries`) — only the inner "is this folder real" test line
  changes, and its return value is unchanged for every input.
- Per the codebase's own established "near-identical copy per platform, no
  shared module" pattern for this whole panel (confirmed by the
  `WorldsPanelStatusTest` doc comment cited in the spec), refactoring all three
  copies identically (not just one) is required to keep them from drifting.

This refactor is added as an explicit task (Task 6 below), verified by keeping
`listRealLocalSaveFolderNames()`'s own observable behavior (which folder names
it returns, for which fixtures) unchanged — no existing test asserts on this
method directly per current grep, so this is a behavior-preserving internal
refactor confirmed by inspection plus the new shared unit tests covering
`isRealSaveFolder` itself.

## Chosen Safety-Margin Constant (SR2.2 / Open Question 1)

**45 seconds.** Declared as a `private static final long
SAFETY_MARGIN_MILLIS = 45_000L;` constant in `StaleSaveFolderHealer`, with a
rationale comment at the declaration site: comfortably inside the spec's
suggested 30-60s range, generous enough to cover typical Steam Cloud reads of
a large world archive plus zip extraction (the window between
`markDownloadPending` and the folder actually existing/being touched) without
requiring a live-timed benchmark, while still healing a truly stale folder
promptly (well under a minute) the next time the Worlds tab opens after the
folder went stale. No spec text pins a different number, so this is a planning
decision made explicitly per the Open Questions section.

## Files to Create

1. `api/src/main/java/de/lazuli/api/cloudsync/StaleSaveFolderHealer.java`
   — new `public final class`, package `de.lazuli.api.cloudsync`, private
   constructor, per the spec's "Public API" section signature exactly:
   - `public static boolean isRealSaveFolder(Path candidate)` — moved verbatim
     from `WorldRestoreService`.
   - `public static void deleteRecursively(Path path)` — moved verbatim from
     `WorldRestoreService`.
   - `public static List<String> healStaleFolders(Path savesDirectory,
     Predicate<String> worldSlugIsBusy, Consumer<String> warningLogger)`:
     - SR4: if listing `savesDirectory` throws, catch, report via
       `warningLogger`, return `List.of()`. Treat `Files.notExists`/non-directory
       as trivial empty result (Compatibility section).
     - For each immediate child directory (`Files.newDirectoryStream` or
       `Files.list`, non-recursive per SR3): skip if `isRealSaveFolder(candidate)`;
       skip if `worldSlugIsBusy.test(candidate.getFileName().toString())` is
       `true` (FR4); skip if most-recent modification timestamp (candidate's own
       `Files.getLastModifiedTime`, plus max across its immediate children per
       SR2.2's defensive one-level check) is within `SAFETY_MARGIN_MILLIS` of
       "now" — any `IOException`/exception while computing this or listing
       children for a single candidate causes that candidate to be skipped
       (SR2.3), not the whole scan aborted.
     - Otherwise: call `deleteRecursively(candidate)`, add its folder name to
       the returned list.
     - Never throws (SR4); returns `List.of()` (non-null) if nothing healed.
   - Package-private or private helper(s) as needed for the timestamp check
     (e.g. `mostRecentModificationMillis(Path candidate)`), not part of the
     public API surface beyond the three methods above.

2. `api/src/test/java/de/lazuli/api/cloudsync/StaleSaveFolderHealerTest.java`
   — new test class, parallel to `DownloadProgressPresenterTest.java`. Covers:
   - `isRealSaveFolder`: real folder (dir + `level.dat` regular file) → true;
     missing `level.dat` → false; plain file at path (not a directory) → false;
     `level.dat` present but as a directory, not a regular file → false.
   - `deleteRecursively`: non-existent path → no-op (no throw); populated
     nested directory → fully removed; a path it cannot delete (best-effort) →
     does not throw (simulate via a read-only file if practical on the CI
     platform, else document as a known gap and rely on the moved method's
     prior implicit coverage via `WorldRestoreServiceTest`).
   - `healStaleFolders`:
     - Empty/non-existent `savesDirectory` → returns empty list, no throw.
     - A real save folder (has `level.dat`) among candidates → never deleted,
       never in returned list.
     - A stale folder (no `level.dat`) with an old modification timestamp
       (test sets it via `Files.setLastModifiedTime` to well outside the
       safety margin) and `worldSlugIsBusy` returning `false` → deleted, name
       returned.
     - A stale folder where `worldSlugIsBusy` returns `true` for its slug →
       never deleted (FR4/SR2 guard 1), regardless of timestamp.
     - A stale folder whose modification timestamp is set to "now" (or within
       the safety margin) and `worldSlugIsBusy` returns `false` → never
       deleted (SR2 guard 2, the independent time-based guard) — this is the
       key new-behavior test distinguishing this method from the reactive
       heal's own test fixture.
     - A stale folder containing a child file whose own modification timestamp
       is recent even though the folder's own timestamp is old → never deleted
       (verifies the one-level child-timestamp check).
     - `warningLogger` invoked (not thrown) when a listing-level or
       per-candidate error is simulated, if practically simulable in this test
       environment (e.g. an unreadable/points-nowhere path); otherwise this
       specific branch may be covered by direct unit-level reasoning/documented
       as best-effort if not feasibly simulable in this repo's test harness —
       confirm feasibility during implementation and note any gap in the PR.

## Files to Modify

1. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldRestoreService.java`
   - Add import `de.lazuli.api.cloudsync.StaleSaveFolderHealer`.
   - Line 110: `isRealSaveFolder(targetWorldFolder)` →
     `StaleSaveFolderHealer.isRealSaveFolder(targetWorldFolder)`.
   - Line 123: `deleteRecursively(targetWorldFolder)` →
     `StaleSaveFolderHealer.deleteRecursively(targetWorldFolder)`.
   - Line 187, 241: same two `deleteRecursively(stagingDirectory)` call sites
     inside `extractAndFinish` — also update to
     `StaleSaveFolderHealer.deleteRecursively(...)` since the private method is
     being deleted entirely (both call sites must move together; the spec's
     "two call sites" reference is to the collision-check ones specifically,
     but all internal callers of the now-deleted private method must be
     updated).
   - Delete the private `isRealSaveFolder` (lines 314-321) and
     `deleteRecursively` (lines 323-338) methods entirely.
   - Remove now-unused imports if any become unused (`java.util.Comparator`,
     `java.util.stream.Stream` — check whether still used elsewhere in the
     file after the move; likely safe to remove since both were only used
     inside the deleted `deleteRecursively`).
   - No change to `WorldRestoreHook` surface, constructors, or any other
     behavior (per spec Non-goals).

2. `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldRestoreServiceTest.java`
   - No behavioral change expected. Read the current diff already present
     (`git status` shows this file modified) before touching it further, to
     avoid clobbering unrelated in-flight edits — confirm at implementation
     time whether `staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds` still
     compiles/passes unchanged (it should, since it only asserts on
     `beginRestore()`'s observable behavior, not the moved methods).

3. `platform/fabric-1.21.11/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
4. `platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
5. `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
   (identical shape of edit on all three, per FR6):
   - Add import `de.lazuli.api.cloudsync.StaleSaveFolderHealer`.
   - In `void reload()`, insert as the first statement (before the existing
     async `LevelStorage`/`LevelStorageSource` summary load kick-off), per the
     spec's "Public API" example shape:
     ```
     List<String> healed = StaleSaveFolderHealer.healStaleFolders(
             FabricLoader.getInstance().getGameDir().resolve("saves"),
             worldSlug -> {
                 WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
                 return statusHook != null && statusHook.isDownloadInProgress(worldSlug);
             },
             warning -> LazuliMod.LOGGER.warn(warning));
     for (String slug : healed) {
         LazuliMod.LOGGER.info("Healed stale local save folder \"{}\" (no level.dat, not mid-download).", slug);
     }
     ```
   - Add import `de.lazuli.api.cloudsync.WorldSyncStatusHook` and confirm
     `WorldSyncStatusHookHolder` is already imported/accessible (per spec
     research, the holder class already exists and is used elsewhere in this
     file for other hooks — verify the exact holder-class import/package at
     implementation time on each of the 3 files individually, since this plan
     does not re-derive that from a full read).
   - In `listRealLocalSaveFolderNames()`, replace the inline condition
     `Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("level.dat"))`
     with `StaleSaveFolderHealer.isRealSaveFolder(candidate)` (Task 6, decision
     above). No other change to that method (its `IOException` handling,
     `DirectoryStream` usage, and return-list building stay as-is).

## Task Breakdown

1. Create `StaleSaveFolderHealer.java` in `api` module (FR1, FR2), including
   the 45s `SAFETY_MARGIN_MILLIS` constant with rationale comment.
2. Create `StaleSaveFolderHealerTest.java` covering `isRealSaveFolder`,
   `deleteRecursively`, and `healStaleFolders` (all branches listed above).
3. Update `WorldRestoreService.java`: swap all 4 internal call sites to the
   new utility, delete the two now-dead private methods, prune now-unused
   imports.
4. Confirm `WorldRestoreServiceTest.java` still passes unchanged (read-only
   check first, given it already shows as modified in git status from prior
   work; do not overwrite unrelated pending changes).
5. Add the `reload()`-start proactive-heal call to all three `WorldsPanel.java`
   files (FR3, FR4, FR5, FR6), matching the spec's exact call shape.
6. Refactor all three `listRealLocalSaveFolderNames()` methods to call
   `StaleSaveFolderHealer.isRealSaveFolder(...)` instead of their inline
   duplicate condition (decision above).
7. Manual three-way diff pass across the three `WorldsPanel.java` edits (Tasks
   5 and 6 combined) to confirm identical shape, per this codebase's
   established `WorldsPanelStatusTest`-precedent convention (Open Question 3 —
   no new per-platform integration test added; shared logic is unit-tested
   once in `api`).
8. Run the full test/build verification pass (see below).

## Risks

- **Missed internal call site in `WorldRestoreService`**: `deleteRecursively`
  is called from 4 places (2 in `beginRestore`, 2 in `extractAndFinish`), not
  just the 2 the spec's "Public API" section explicitly calls out. Mitigation:
  Task 3 explicitly enumerates all 4 for update; a stray reference to the
  deleted private method will fail compilation immediately (`:features:steam-cloud-sync`
  compile step), making this low-severity/self-detecting.
- **Holder-class/import drift across the three `WorldsPanel.java` files**:
  the spec's research confirms `WorldSyncStatusHookHolder` already exists but
  this plan does not re-verify its exact import statement per file. Mitigation:
  Task 7's three-way diff pass, plus each platform's own `compileJava` task
  will fail loudly on a wrong import/package.
- **Unused-import removal in `WorldRestoreService.java` (`Comparator`,
  `Stream`) breaking if either is still used elsewhere in the file**:
  low risk — grep confirms both are currently only referenced inside the
  method being deleted, but this must be re-verified at implementation time
  (checkstyle/compiler will flag true-unused imports as a warning, not
  necessarily a build failure, so a stale unused import could survive
  silently if not checked).
- **Timestamp-guard test flakiness**: `Files.setLastModifiedTime`/`getLastModifiedTime`
  precision and behavior can vary slightly across filesystems/CI runners.
  Mitigation: use generous margins in test fixtures (e.g. set "old" timestamps
  well beyond 45s, e.g. 5+ minutes in the past, and "recent" timestamps at
  literal "now") to avoid boundary flakiness; avoid asserting on exact
  millisecond boundaries.
- **Concurrent SteamworksService/progress-bar-stall work on `fabric-26.2`**:
  explicit do-not-touch constraint. Mitigation: this plan's only `fabric-26.2`
  edits are the two `WorldsPanel.java` changes (Tasks 5-6), which have no
  overlap with `SteamworksService.java` or progress-bar rendering code; no
  file in that area is opened or modified.
- **`WorldRestoreServiceTest.java` already shows modified in `git status`**
  from prior, unrelated work in this session's repo state. Mitigation: Task 4
  requires reading its current on-disk content before any edit, to avoid
  silently reverting or conflicting with that in-flight change; this plan's
  own change to that file is expected to be zero lines (confirm-only), unless
  reading it reveals an actual conflict, in which case escalate rather than
  guess.

## Dependencies

No new external (non-Fabric) dependencies. All new code uses only
`java.nio.file.*`, `java.util.*`, `java.util.function.*` (JDK standard
library) and existing in-repo `api`-module/Fabric-loader types already present
in the touched files. No `build.gradle`/`settings.gradle` changes (confirmed
in Existing Implementation section above — every consuming module already has
the required `api project(':api')`/`include` dependency edge on `api`).

## Test Strategy

- New unit tests: `StaleSaveFolderHealerTest.java` (Task 2), covering all
  three public methods per the branch list above.
- Full existing suite re-run (not just new tests), per governing constraint:
  - `:api:test` — includes the new `StaleSaveFolderHealerTest` plus every
    pre-existing `api`-module test (e.g. `DownloadProgressPresenterTest` and
    any others currently present), confirming no regression from the new
    class's addition.
  - `:features:steam-cloud-sync:test` — includes the full
    `WorldRestoreServiceTest` suite (not just the one stale-folder test),
    confirming the internal refactor (Task 3) introduces no behavioral change
    anywhere in that module's tests.
  - No test module exists today for `platform/*` panel logic other than
    `WorldsPanelStatusTest` (fabric-26.2 only, per spec research, covering
    `computeConsolidatedStatus`, unrelated to this feature's own logic) — no
    new per-platform test added (Open Question 3 resolution above); that
    existing test is unaffected by this feature's edits and should be
    confirmed still passing as part of the fabric-26.2 module's own test task
    if one exists in that module's `build.gradle` (verify at implementation
    time whether `platform/fabric-26.2` has a `test` Gradle task at all, since
    platform modules are primarily Minecraft-client code, not typically unit
    tested; if no `test` task exists there, this is a non-issue).
- Manual verification (no automated test): three-way diff pass across the
  edited `WorldsPanel.java` files (Task 7) — confirm the inserted proactive-heal
  block and the `listRealLocalSaveFolderNames()` one-line swap are textually
  identical in shape across `fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`
  (accounting only for each platform's own pre-existing surrounding code
  differences noted in the spec's research).
- No live Minecraft launch by the implementer or verifier (per governing
  constraint and spec Non-goals) — the user tests live themselves later.

## Build / Packaging Verification (required, not optional)

Because this feature touches `api` and `features:steam-cloud-sync` (both
`include`d into each platform module's jar via Loom Jar-in-Jar), plus all
three `platform/*` modules directly, verification must run at minimum:

1. `:api:test`
2. `:features:steam-cloud-sync:test`
3. `:platform:fabric-1.21.11:compileJava` and
   `:platform:fabric-1.21.11:processIncludeJars` (or equivalent full
   `:platform:fabric-1.21.11:build`/`assemble`)
4. `:platform:fabric-26.1:compileJava` and
   `:platform:fabric-26.1:processIncludeJars` (or equivalent full build/assemble)
5. `:platform:fabric-26.2:compileJava` and
   `:platform:fabric-26.2:processIncludeJars` (or equivalent full build/assemble)

`compileJava` alone is insufficient — `processIncludeJars` (or a full
build/assemble that transitively runs it) is required so the updated `api`
and `features:steam-cloud-sync` classes actually reach each platform's bundled
output jar, per this repo's established Jar-in-Jar constraint.

## Acceptance Criteria

1. `StaleSaveFolderHealer` exists in `api` module with exactly the public
   signature specified (spec "Public API" section): `isRealSaveFolder(Path)`,
   `deleteRecursively(Path)`, `healStaleFolders(Path, Predicate<String>,
   Consumer<String>)`.
2. `WorldRestoreService.beginRestore()`'s reactive-heal behavior is bit-for-bit
   unchanged (verified by `WorldRestoreServiceTest` passing unchanged,
   including `staleNonSaveLocalFolderIsAutoHealedAndRestoreProceeds`); its two
   old private helper methods no longer exist in that file.
3. All three `WorldsPanel.reload()` methods call `StaleSaveFolderHealer.healStaleFolders(...)`
   as their first statement, wired with the `WorldSyncStatusHook`-backed busy
   predicate and INFO-level per-healed-folder logging, before the existing
   async summary load begins (FR3).
4. All three `listRealLocalSaveFolderNames()` methods use
   `StaleSaveFolderHealer.isRealSaveFolder(...)` instead of an inline
   duplicate condition, with no change to that method's own observable output
   for any given filesystem state.
5. A folder with no `level.dat`, not busy per the hook, and untouched for
   longer than 45 seconds is deleted on the next `reload()` and its name is
   logged at INFO level; no popup/UI element appears (SR1, FR5, Non-goals).
6. A real save folder (has `level.dat`) is never deleted by the proactive
   scan, regardless of any other condition (SR1).
7. A folder reported busy by `WorldSyncStatusHook.isDownloadInProgress(...)`,
   or modified within the last 45 seconds, is never deleted even if it lacks
   `level.dat` (SR2, both guards independently verified).
8. The scan only inspects immediate children of the resolved saves root; no
   recursion into grandchildren for candidate discovery, no path outside that
   root touched (SR3).
9. A missing/absent saves directory, or any I/O error while scanning, never
   throws out of `reload()` or `healStaleFolders()` (SR4).
10. `Bug #3` (`gameMode` "Unknown"), `SteamworksService.java`, and any
    fabric-26.2 progress-bar-stall-related code are untouched by this feature
    (verified by the final diff touching only the files listed in "Files to
    Create"/"Files to Modify" above).
11. All build/packaging verification tasks listed above pass (`:api:test`,
    `:features:steam-cloud-sync:test`, and each platform's `compileJava` +
    `processIncludeJars`/build).
12. No live Minecraft launch was performed during implementation or
    verification.
