# Implementation Plan: Cloud Sync Threshold, Full-Sync-Only, Un-sync Cloud Delete, and Sync-Off UI

Spec: `.claude/specs/cloud-sync-threshold-and-full-sync-only.md` (all facts/decisions
in its "Resolved Decisions" section are treated as settled here and not re-derived).

## Existing implementation (confirmed by reading, not re-litigated)

- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/api/SteamCloudSyncConfig.java` —
  9-component record; `maxWorldArchiveSizeMb` (component 8, default `1024`) and
  `allowSelectiveFallback` (component 9, default `true`) are both on-disk-persisted
  and both being removed (Requests 1 + 4).
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/config/SteamCloudSyncConfigIO.java` —
  `parse()` (lines 84-93) and `serialize()` (lines 107-118) each read/write both
  fields positionally.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java` —
  `SELECTIVE_FALLBACK_ENTRIES` (60-61), `SyncStrategy` enum (66-70, 3 values),
  constructor fields `maxWorldArchiveSizeMb`/`allowSelectiveFallback` (78-79,
  positional args 8/9 of a 12-arg constructor), `decideStrategy(long, int, boolean)`
  (623-629), `buildSelectiveArchive` (732-753), the `SELECTIVE_FALLBACK` branch in
  `syncWorldNow` (677-685). No `MAX_WORLD_ARCHIVE_SIZE_MB` constant exists yet.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/CloudSyncCoordinator.java` —
  constructs `WorldSaveSyncService` at lines 151-154 passing
  `config.maxWorldArchiveSizeMb(), config.allowSelectiveFallback()` positionally;
  wires `worldSyncPreferenceService.setOnSyncEnabledListener(...)` at 167-168 (the
  only existing listener wiring — no `setOnSyncDisabledListener` counterpart yet).
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncPreferenceService.java` —
  `toggleSync` (86-99) has an `if (newValue) { onSyncEnabledListener... }` with no
  `else`; `onSyncEnabledListener` field/setter (40, 115-117) is the structural model
  to mirror for the new disabled-listener.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldArchiveCloudStore.java` —
  interface has `forget(String)` (101-110, Cloud-pointer-only) but no delete method;
  `SteamRemoteStorageWorldArchiveStore.forget` (230-238) is the try/catch/log/return-false
  pattern every new method must copy, calling `remoteStorage.fileForget(...)`; the
  new method will call `remoteStorage.fileDelete(...)` instead (steamworks4j's
  `SteamRemoteStorage` already exposes `fileDelete(String)` per the spec's
  investigation — verify the exact signature when editing this file, since it isn't
  visible from source in this repo, only from steamworks4j's own jar).
  `NoopWorldArchiveCloudStore` (16-52) returns `false` from `forget` (49-51) — its
  no-op convention for the new method is the same: return `false`.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncStatusTracker.java` —
  has `markSkippedTooLarge`/`markSynced`/`markError` etc. but **no method to clear a
  world back to `NOT_SYNCED`** (the default returned by `statusFor` when the
  `statuses` map has no entry, line 222-223) — a new method is needed for Request 3.
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java` — read in
  full per the spec's own investigation; confirmed line ranges:
  `computeConsolidatedStatus` (443-456), `computeBlocked` (468-473),
  `computeShowResolveButton` (482-484), `drawSyncIcons` (494-534, `enabled` computed
  at line 501, `drawConsolidatedStatusIndicator` call at 533),
  `isRowConflicted`/`isSyncEnabledFor` (private methods at 689, 708 — both already
  called elsewhere in the row loop, e.g. lines 349-350/763-764), the status-square
  hit-test in `mouseClicked` (814-819, gated today only by the `ConflictStatus.CONFLICT`
  check at 816).
- Test files with direct hits: `WorldSaveSyncServiceTest.java` (threshold literals
  ~125/131/137, `SKIPPED_TOO_LARGE` assertion ~260, plus any `SELECTIVE_FALLBACK`/
  end-to-end selective-sync test methods — enumerate exact method names when editing),
  `SteamCloudSyncConfigIOTest.java:30` (only test in the repo constructing
  `SteamCloudSyncConfig` via its full 9-arg positional constructor —
  `new SteamCloudSyncConfig(1, true, false, true, false, true, false, 25, false)`).
  `CloudSyncCoordinatorTest.java` does **not** construct `SteamCloudSyncConfig`
  directly (confirmed via search) — no change needed there for the record-shrink
  itself, but it likely stubs/constructs `WorldSaveSyncService`/`WorldArchiveCloudStore`
  and must be checked for the constructor-arity and new-interface-method fallout
  (see Step 6).

## Ordering rationale

Requests 1 and 4 both touch `SteamCloudSyncConfig`/`SteamCloudSyncConfigIO`/
`WorldSaveSyncService`'s constructor and `decideStrategy`/`syncWorldNow` — the spec's
own "Notes for planning" says to land these as one combined pass, not two. Request 3
is independent (new interface method + new listener) and can land before or after,
but is sequenced after Requests 1+4 here because `handleSyncDisabled`'s Javadoc/tests
read more naturally once `SyncStrategy` is already the final 2-value shape (avoids
writing then rewriting comments referencing "over-threshold" states). Request 2 is
fully independent (UI-only, different files) and is sequenced last since it has zero
overlap with the other three and is the only one needing 3-platform parity.

Recommended commit sequence:
1. Combined Request 1 + Request 4 config-schema removal
   (`SteamCloudSyncConfig`/`SteamCloudSyncConfigIO`) + hardcoded constant.
2. `WorldSaveSyncService` simplification (`SyncStrategy`, `decideStrategy`,
   `syncWorldNow`, constructor) — depends on step 1's constant existing.
3. `CloudSyncCoordinator` call-site update — depends on steps 1+2.
4. Test updates for steps 1-3 (`SteamCloudSyncConfigIOTest`, `WorldSaveSyncServiceTest`,
   any `CloudSyncCoordinatorTest` fallout).
5. Request 3: `WorldArchiveCloudStore`/`SteamRemoteStorageWorldArchiveStore`/
   `NoopWorldArchiveCloudStore` new delete method + tests.
6. Request 3: `WorldSyncPreferenceService` disabled-listener + `WorldSaveSyncService
   .handleSyncDisabled` + `WorldSyncStatusTracker` clear method + `CloudSyncCoordinator`
   wiring + tests.
7. Request 2: `WorldsPanel.computeShowStatusIndicator` + both call sites, landed in
   `fabric-26.2` with new tests, then diffed onto `fabric-1.21.11`/`fabric-26.1`.

Steps 1-4 must land together in one commit (or immediately sequential commits with no
intermediate compilable-but-half-migrated state) because `WorldSaveSyncService`'s
constructor signature, `SteamCloudSyncConfig`'s record shape, and
`CloudSyncCoordinator`'s call site are mutually dependent — a partial change does not
compile. Steps 5-6 (Request 3) and step 7 (Request 2) are each independently
compilable and can land as separate commits after step 4.

---

## Step 1: Remove `maxWorldArchiveSizeMb` and `allowSelectiveFallback` from the config schema; introduce the hardcoded constant

**Files:**
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/api/SteamCloudSyncConfig.java`
  — remove both record components (positions 8 and 9), shrinking to a 7-component
  record; remove both from the `DEFAULT` literal (drop trailing `1024, true`); remove
  both from the class Javadoc (the `@param` lines for each, and the JSON example's
  `"maxWorldArchiveSizeMb": 1024` / `"allowSelectiveFallback": true` lines).
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/config/SteamCloudSyncConfigIO.java`
  — in `parse()`, drop the `root.getInt("maxWorldArchiveSizeMb")` and
  `root.getBoolean("allowSelectiveFallback")` positional arguments (and update the
  `new SteamCloudSyncConfig(...)` call to 7 args); in `serialize()`, drop the two
  `.putNumber("maxWorldArchiveSizeMb", ...)` / `.putBoolean("allowSelectiveFallback", ...)`
  calls. No other change to `parse`/`serialize`'s structure — an old on-disk file with
  either/both stale keys parses fine (extra keys silently ignored per
  `CloudSyncJson`'s existing object-reader behavior, confirmed as the assumption
  the spec already relies on) and both keys stop being written back out on next save.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`
  — add `public static final int MAX_WORLD_ARCHIVE_SIZE_MB = 1024;` near the top of
  the class (alongside `SELECTIVE_FALLBACK_ENTRIES`/`FINGERPRINT_CLOUD_FILE_NAME`,
  before that constant is removed in Step 2 — or add it directly in Step 2's same
  edit pass if doing this as a single commit; either ordering is fine since this is
  one combined commit per the plan above). Javadoc should explicitly state it is
  intentionally never read from or written to config (mirrors the spec's "new truth"
  framing) so a future reader doesn't try to make it configurable again.

**Tests:**
- `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/config/SteamCloudSyncConfigIOTest.java:30`
  — update the 9-arg `new SteamCloudSyncConfig(1, true, false, true, false, true, false, 25, false)`
  call to the new 7-arg shape (drop the trailing `25, false`). Re-check the rest of
  this test file for any assertion on `maxWorldArchiveSizeMb()`/`allowSelectiveFallback()`
  accessors or on the serialized JSON containing those two keys, and remove/update
  those assertions too.
- Add (or extend an existing) `SteamCloudSyncConfigIOTest` case asserting that parsing
  a JSON string still containing the now-obsolete `"maxWorldArchiveSizeMb"`/
  `"allowSelectiveFallback"` keys succeeds without error and the resulting config
  simply has neither field (compile-time guaranteed since the record no longer has
  them) — i.e. an old-config-file backward-compat regression test.

---

## Step 2: Simplify `WorldSaveSyncService`'s strategy logic to all-or-nothing

**File:** `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`

Changes:
1. Remove `SELECTIVE_FALLBACK_ENTRIES` (lines 60-61).
2. Shrink `SyncStrategy` enum to `{ WHOLE_ARCHIVE, SKIPPED }` (was 3 values).
3. Remove the `allowSelectiveFallback` field (line 79) and its constructor parameter
   (line 137) and Javadoc `@param` (112-114); remove the `maxWorldArchiveSizeMb`
   constructor parameter too **if** Step 1's hardcoded constant fully replaces it as
   a field — per the spec (Request 1, point 4/5), `maxWorldArchiveSizeMb` stays as a
   constructor-supplied `int` (the caller, `CloudSyncCoordinator`, now passes the
   hardcoded constant instead of a config-read value), it is only
   `allowSelectiveFallback` that disappears from the constructor entirely. So: keep
   the `maxWorldArchiveSizeMb` field/param as-is, remove only `allowSelectiveFallback`
   (field, param, Javadoc `@param`, and the constructor's `this.allowSelectiveFallback = ...`
   assignment).
4. Simplify `decideStrategy(long folderSizeBytes, int maxWorldArchiveSizeMb, boolean allowSelectiveFallback)`
   (line 623) to `decideStrategy(long folderSizeBytes, int maxWorldArchiveSizeMb)`:
   drop the `allowSelectiveFallback` param and its Javadoc `@param`; body becomes
   `return folderSizeBytes <= maxBytes ? SyncStrategy.WHOLE_ARCHIVE : SyncStrategy.SKIPPED;`
   (the existing `<=` comparison is unchanged, only the `else` branch simplifies).
5. Remove `buildSelectiveArchive` (lines 732-753) entirely.
6. In `syncWorldNow` (line 664 area): update the `decideStrategy` call to the new
   2-arg signature; remove the `strategy == SyncStrategy.WHOLE_ARCHIVE ? buildWholeArchive(...) : buildSelectiveArchive(...)`
   ternary (677-679) — since `SKIPPED` already `return`s early (669-675), the only
   remaining strategy that reaches archive-building is `WHOLE_ARCHIVE`, so this
   collapses to a plain `byte[] archiveBytes = buildWholeArchive(worldFolder);`
   (no branch needed at all); delete the `if (strategy == SyncStrategy.SELECTIVE_FALLBACK) { ... }`
   block (681-685) entirely, including its player-facing "reduced critical-files-only
   copy" message.
7. Leave the `SKIPPED` branch's message (669-674) and `statusTracker.markSkippedTooLarge(worldSlug)`
   (672) untouched — per the spec, this is already the correct final wording and the
   correct final status ("over threshold, not synced").
8. Update the class Javadoc line 38 (`{@link #decideStrategy(long, int, boolean)}`)
   to `{@link #decideStrategy(long, int)}`.

**Tests:** `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncServiceTest.java`
- Update the three `decideStrategy(...)` call-site assertions (~lines 125/131/137) to
  the new 2-arg signature — since the pure comparison logic is otherwise unchanged
  (at-or-under → `WHOLE_ARCHIVE`, over → `SKIPPED` unconditionally now), these become
  simpler (no `true`/`false` third arg to vary), but any test case that previously
  asserted `SELECTIVE_FALLBACK` when `allowSelectiveFallback=true` must be deleted or
  rewritten to assert `SKIPPED` instead (since that outcome no longer exists).
- Delete any test method(s) exercising the end-to-end selective-sync path (search the
  file for `SELECTIVE_FALLBACK`/`buildSelectiveArchive`/`SELECTIVE_FALLBACK_ENTRIES`
  hits and remove each fully — do not leave a dangling reference to a removed symbol,
  which would fail to compile).
- Keep/verify the `SKIPPED_TOO_LARGE` status assertion (~line 260) — should still pass
  unmodified since that status's trigger path is untouched.
- Any test constructing `WorldSaveSyncService` via its full positional constructor
  must drop the now-removed `allowSelectiveFallback` boolean argument (constructor
  shrinks by exactly one parameter, from 12 to 11).
- Add one new test asserting `decideStrategy(sizeBytes, maxMb)` returns `SKIPPED` for
  an over-threshold size with no third parameter to vary (covers the "no more
  fallback path exists at all" behavior explicitly, not just implicitly via the
  removal of the old fallback assertions).

---

## Step 3: Update `CloudSyncCoordinator`'s construction call site

**File:** `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/CloudSyncCoordinator.java`

At lines 151-154, change:
```java
this.worldSaveSyncService = new WorldSaveSyncService(
        archiveStore, cloudFileStore, worldSyncPreferenceService, worker, fingerprintCache, ancestorCachePath,
        deviceLabel, config.maxWorldArchiveSizeMb(), config.allowSelectiveFallback(), warningLogger, playerNotifier,
        worldSyncStatusTracker);
```
to pass `WorldSaveSyncService.MAX_WORLD_ARCHIVE_SIZE_MB` in place of
`config.maxWorldArchiveSizeMb()`, and drop the `config.allowSelectiveFallback()`
argument entirely (constructor now takes one fewer parameter, per Step 2).

**Tests:** Search `CloudSyncCoordinatorTest.java` for any assertion that depends on
`config.maxWorldArchiveSizeMb()`/`config.allowSelectiveFallback()` being threaded
through to sync behavior (e.g. a test that sets a small threshold via config to force
a `SKIPPED`/fallback outcome through the coordinator) — since the threshold is no
longer config-sourced, such a test can no longer parameterize the threshold this way;
if one exists, it must be rewritten to either accept the hardcoded 1024MB constant or
be deleted if it was only testing plumbing that Step 1-3 already covers more directly
at the `WorldSaveSyncService`/`decideStrategy` level. Confirm the exact test names by
reading the file at implementation time — none were found referencing these two
fields specifically in this planning pass's search, but `CloudSyncCoordinatorTest`
should still be compiled/run to catch any indirect breakage (e.g. mock/stub
`WorldSaveSyncService` construction elsewhere in that file).

---

## Step 4: Verify full build after Steps 1-3

Run the `features/steam-cloud-sync` module's test suite (and a full multi-module
compile, since `CloudSyncCoordinator`/`SteamCloudSyncConfig` are consumed by all three
platform modules' composition roots) before proceeding to Request 3/2. This is a
checkpoint, not a code change — confirms the combined config-schema-shrink + strategy
simplification compiles and passes across every module before layering the
independent Request 3/2 changes on top.

---

## Step 5: Add `deleteWorldArchive` to the archive-store seam

**Files:**
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldArchiveCloudStore.java`
  — add `boolean deleteWorldArchive(String fileName);` to the interface, with Javadoc
  explicitly contrasting it with `forget` (this one "also propagates a delete" per
  Valve's `fileDelete`, freeing quota deterministically, vs. `forget`'s
  "Cloud-pointer-only, quota housekeeping" semantics) — place it directly after
  `forget` (after line 110) so the two are read together.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/SteamRemoteStorageWorldArchiveStore.java`
  — implement it directly after `forget` (after line 238), copying `forget`'s exact
  try/catch/log/return-false shape:
  ```java
  @Override
  public boolean deleteWorldArchive(String fileName) {
      try {
          return remoteStorage.fileDelete(fileName);
      } catch (RuntimeException e) {
          warn("Failed to delete Steam Cloud world archive \"" + fileName + "\": " + e);
          return false;
      }
  }
  ```
  Verify at implementation time that steamworks4j's `SteamRemoteStorage` actually
  exposes a `fileDelete(String)` method with this exact signature (the spec's
  investigation asserts it exists but this repo's source doesn't include the
  steamworks4j jar's decompiled API — check the dependency's actual class, e.g. via
  IDE navigation or the jar's javadoc, before assuming the signature matches other
  methods' `String fileName` shape).
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/NoopWorldArchiveCloudStore.java`
  — add, mirroring `forget`'s no-op (line 49-51):
  ```java
  @Override
  public boolean deleteWorldArchive(String fileName) {
      return false;
  }
  ```

**Tests:** `features/steam-cloud-sync/src/test/java/...` — locate (or create, if none
covers `forget`/`SteamRemoteStorageWorldArchiveStore` directly, since it needs a real
`SteamRemoteStorage`/Steam runtime that unit tests likely can't exercise) whatever
test doubles implement `WorldArchiveCloudStore` for other services' tests (e.g.
`WorldSaveSyncServiceTest`'s own fake) and add a `deleteWorldArchive` implementation
to each so they keep compiling against the widened interface — check every existing
`implements WorldArchiveCloudStore` in the test tree (search
`grep -r "implements WorldArchiveCloudStore" features/steam-cloud-sync/src/test`).

---

## Step 6: Wire the enabled→disabled listener and `handleSyncDisabled`

**Files:**
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncPreferenceService.java`
  — add `private volatile Consumer<String> onSyncDisabledListener = worldSlug -> { };`
  (mirroring line 40); add `setOnSyncDisabledListener(Consumer<String> listener)`
  (mirroring `setOnSyncEnabledListener`, lines 115-117, including its Javadoc
  explaining the same construction-order-cycle rationale); in `toggleSync` (86-99),
  add an `else { onSyncDisabledListener.accept(worldSlug); }` to the existing
  `if (newValue) { onSyncEnabledListener.accept(worldSlug); }` (currently no `else` —
  confirmed at line 92-98), fired after `persist()` just like the enabled branch.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncStatusTracker.java`
  — add a new method, e.g. `clearStatus(String worldSlug)`, removing the world from
  both `statuses` and `lastErrors` maps (so `statusFor` falls back to its default
  `NOT_SYNCED`, per line 222-223's `getOrDefault`); place it near `markSkippedTooLarge`
  (after line 219) with Javadoc noting it's used by Request 3's un-sync-delete
  cleanup so a removed-from-Cloud world doesn't keep showing a stale `SYNCED`/error
  status.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`
  — add `public void handleSyncDisabled(String worldSlug, String displayName)`
  (naming/signature TBD exact form at implementation time; spec suggests
  `handleSyncDisabled(String worldSlug)` but the failure-notification message needs a
  display name — mirror `handleSyncReenabled`'s existing 3-param
  `(worldSlug, worldFolder, displayName)` shape's *availability* pattern: since
  deletion needs no local folder path, a 2-param `(worldSlug, displayName)` is
  sufficient; confirm against how `CloudSyncCoordinator` will call it in this same
  step). Implementation, run via `worker.submitBackgroundWork(...)`:
  ```java
  public void handleSyncDisabled(String worldSlug, String displayName) {
      worker.submitBackgroundWork(() -> {
          boolean deleted = archiveStore.deleteWorldArchive(archiveFileName(worldSlug));
          if (deleted) {
              List<WorldFingerprint> fingerprints = new ArrayList<>(readLocalFingerprintCache());
              fingerprints.removeIf(fp -> fp.worldSlug().equals(worldSlug));
              fingerprintCache.replaceAll(fingerprints);
              cloudFileStore.write(FINGERPRINT_CLOUD_FILE_NAME, fingerprintIO.serialize(fingerprints).getBytes(StandardCharsets.UTF_8));
              statusTracker.clearStatus(worldSlug);
          } else {
              String message = "Failed to remove world \"" + displayName + "\" from Steam Cloud; it may still be "
                      + "taking up Cloud storage. You can try turning sync off again to retry.";
              warningLogger.accept(message);
              playerNotifier.accept(message);
          }
      });
  }
  ```
  (mirrors `updateFingerprint`'s existing fingerprint-removal pattern, lines 812-818,
  per the spec's explicit instruction; does not touch the ancestor cache — un-synced
  worlds have no meaningful "ancestor" concept and Request 3 doesn't mention it).
  Exact wording of the failure message is left for implementation-time/user
  sign-off per the spec's own open question — the above is a reasonable default.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/CloudSyncCoordinator.java`
  — alongside the existing `setOnSyncEnabledListener` wiring (167-168), add:
  ```java
  this.worldSyncPreferenceService.setOnSyncDisabledListener(worldSlug ->
          worldSaveSyncService.handleSyncDisabled(worldSlug, worldSlug));
  ```
  (using `worldSlug` as the display-name argument, matching the existing
  `setOnSyncEnabledListener` wiring's own precedent of passing `worldSlug` as both
  the slug and display-name-placeholder args at line 168 — this coordinator layer
  has no richer display name available at listener-fire time either).

**Tests:**
- `WorldSyncPreferenceServiceTest` (locate exact file name/path at implementation
  time) — add a test asserting `toggleSync` on an enabled world fires
  `onSyncDisabledListener` (and not `onSyncEnabledListener`), mirroring whatever
  existing test covers the enabled-transition listener.
- `WorldSaveSyncServiceTest.java` — add tests for `handleSyncDisabled`: success path
  (fake `archiveStore.deleteWorldArchive` returns `true` → fingerprint removed,
  `cloudFileStore.write` called, status cleared) and failure path (`deleteWorldArchive`
  returns `false` → `warningLogger`/`playerNotifier` both invoked, fingerprint
  untouched, preference-disable itself is out of this method's scope so nothing to
  assert about it here).
- `CloudSyncCoordinatorTest.java` — if this test already exercises the
  `setOnSyncEnabledListener` wiring end-to-end (constructing a real
  `CloudSyncCoordinator` and calling `toggleSync`), add a symmetric case for the
  disabled transition invoking `handleSyncDisabled`.

---

## Step 7: `WorldsPanel` status-square visibility (3-platform)

**Primary file (write full changes + new tests here):**
`platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`

1. Add the pure static helper (per spec's exact suggested code), placed near
   `computeShowResolveButton` (after line 484) for discoverability alongside its
   sibling pure helpers:
   ```java
   static boolean computeShowStatusIndicator(boolean syncEnabled, boolean isConflicted) {
       return syncEnabled || isConflicted;
   }
   ```
2. Render side — in `drawSyncIcons` (494-534), guard the existing
   `drawConsolidatedStatusIndicator(...)` call at line 533:
   ```java
   if (computeShowStatusIndicator(enabled, isRowConflicted(worldSlug))) {
       drawConsolidatedStatusIndicator(guiGraphics, summary, statusHook, worldSlug, enabled, uploadInProgress, statusLeft, top, mouseX, mouseY, rowHovered);
   }
   ```
   (`enabled` already computed at line 501; `worldSlug` already available at line 500
   via `summary.getLevelId()`; `isRowConflicted` is an existing private method at
   line 689).
3. Hit-test side — in `mouseClicked` (810-819), replace the current unconditional
   `conflictCache.getOrDefault(summary.getLevelId(), ConflictStatus.NONE) == ConflictStatus.CONFLICT`
   condition at line 816 with a two-part guard:
   ```java
   if (mouseX >= statusLeft && mouseX < statusLeft + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE
           && computeShowStatusIndicator(isSyncEnabledFor(summary.getLevelId()), isRowConflicted(summary.getLevelId()))
           && conflictCache.getOrDefault(summary.getLevelId(), ConflictStatus.NONE) == ConflictStatus.CONFLICT) {
       openConflictScreen(summary);
       return true;
   }
   ```
   Per the spec's own analysis, the existing `== ConflictStatus.CONFLICT` check
   already means only the conflict case does anything on click today — adding the
   `computeShowStatusIndicator` guard does not change click behavior for that case
   (when conflicted, `computeShowStatusIndicator` is already `true`), it only
   prevents a click from registering against an invisible (non-conflicted,
   sync-off) square, which was already a no-op anyway; this guard mainly documents
   the invariant and future-proofs against a later added non-conflict click action.
   `isSyncEnabledFor` is an existing private method at line 708.

**Tests (fabric-26.2 only):** locate `WorldsPanelStatusTest` (referenced by the
existing `computeConsolidatedStatus`/`computeBlocked` Javadoc comments as the test
class calling these static helpers directly — confirm exact file name via search) and
add cases for `computeShowStatusIndicator`:
- `syncEnabled=true, isConflicted=false` → `true`
- `syncEnabled=false, isConflicted=false` → `false`
- `syncEnabled=false, isConflicted=true` → `true`
- `syncEnabled=true, isConflicted=true` → `true`

**Other two platforms (`fabric-1.21.11`, `fabric-26.1`):** apply the identical
3-part change (new helper + both call-site guards) to each module's own
`WorldsPanel.java`. Per repo convention, do **not** add new test files in these two
modules — instead:
1. Diff each platform's `WorldsPanel.java` against `fabric-26.2`'s post-change
   version, confirming the only differences are pre-existing module-specific import/
   mapping differences (not this change's logic).
2. Compile each platform module successfully (`./gradlew :platform:fabric-1.21.11:compileJava`,
   `./gradlew :platform:fabric-26.1:compileJava`, or equivalent per this repo's actual
   Gradle task names — confirm exact task names at implementation time).
3. Per `.claude/context/minecraft.md`'s "Known Cross-Version API Differences" table:
   the spec already checked this and found no recorded divergence affecting
   `GuiGraphicsExtractor.fill`/`text`, `WorldSyncToggleHook`, or `WorldSyncStatusHook`
   — re-verify this holds by re-reading that table's current state at implementation
   time (it may have grown new entries since the spec was written), but no adaptation
   is expected.

---

## Cross-cutting constraints checklist (verify at implementation and again at verification)

- [ ] **RAM-only cache invariant preserved**: no new on-disk persistence introduced
      anywhere in Steps 1-7. In particular, Step 6's `handleSyncDisabled` only removes
      from the existing RAM-only `fingerprintCache` and writes back to
      `cloudFileStore` (Cloud, not local disk) — exactly the same write target
      `updateFingerprint` already uses. `MAX_WORLD_ARCHIVE_SIZE_MB` (Step 1) is a
      `static final` JVM constant, never read from or written to any file.
- [ ] **3-platform `WorldsPanel` parity**: Step 7's helper + both call-site guards are
      identical (module-appropriate mapping aside) across `fabric-1.21.11`,
      `fabric-26.1`, `fabric-26.2`; only `fabric-26.2` gets new/updated automated
      tests, the other two are diff+compile-verified.
- [ ] **Yarn/Mojang mapping divergence**: none expected for Step 7 per the spec's own
      check of `.claude/context/minecraft.md`'s cross-version table against
      `GuiGraphicsExtractor.fill`/`text`, `WorldSyncToggleHook`, `WorldSyncStatusHook`
      — re-confirm the table hasn't grown a new relevant entry since. No Minecraft
      API surface at all is touched by Steps 1-6 (pure `features/steam-cloud-sync`
      module + steamworks4j), so no mapping concern applies there.
- [ ] **Breaking config schema change communicated as "new truth," not migration**:
      Step 1 must not add any migration/compat shim beyond "extra JSON keys are
      silently ignored, then stop being written" — no code path should attempt to
      read the old `maxWorldArchiveSizeMb`/`allowSelectiveFallback` keys into the new
      hardcoded constant or otherwise special-case a first-run-after-upgrade.
- [ ] **`decideStrategy`/`SyncStrategy` call sites fully enumerated**: `WorldSaveSyncService`
      (definition + `syncWorldNow` caller), `WorldSaveSyncServiceTest` (test callers) —
      confirmed as the complete list via the spec's own investigation; no other file
      in the repo references `SyncStrategy.SELECTIVE_FALLBACK` or
      `SELECTIVE_FALLBACK_ENTRIES` (verify with a repo-wide search at implementation
      time before considering Step 2 complete, since a missed reference would fail
      compilation anyway and surface immediately).
- [ ] **Stale partial archives left as-is (Option A)**: no sweep/tag/delete code is
      added anywhere in this plan for pre-existing `SELECTIVE_FALLBACK` archives —
      confirm no step above accidentally introduces one.

## Risks / open questions to flag before implementation starts

1. **`SteamRemoteStorage.fileDelete` exact signature is unverified against source**
   (Step 5) — the spec's claim that it exists and takes a `String fileName` is based
   on Valve/steamworks4j documentation, not on reading the actual dependency jar in
   this repo. If the real signature differs (e.g. returns `void`, or needs a
   different overload), `SteamRemoteStorageWorldArchiveStore.deleteWorldArchive`'s
   body will need adjusting at implementation time — low risk, but worth a fast
   confirmation via the IDE/dependency jar before writing that method.
2. **`handleSyncDisabled`'s exact parameter list and the failure-notification
   wording are both explicitly open per the spec** (Request 3's "Open question") —
   Step 6 proposes concrete defaults (`(worldSlug, displayName)` signature; the
   suggested failure string) but these are not yet user-confirmed. Recommend
   surfacing both concretely for a quick sign-off before/during implementation
   rather than treating them as settled.
3. **`CloudSyncCoordinatorTest` fallout from the constructor/record shrinks (Steps
   1-3) could not be fully enumerated in this planning pass** — a search found no
   direct `SteamCloudSyncConfig(...)` construction in that test file, but the file
   wasn't read in full; it may still construct or stub `WorldSaveSyncService`/
   `WorldArchiveCloudStore` in ways affected by the constructor-arity change (Step 2)
   or the new interface method (Step 5). Implementer should read that test file in
   full before Step 4's build-verification checkpoint, not rely solely on this plan's
   search-based estimate.
4. **Exact Gradle task names for per-platform compile verification (Step 7)** were
   not confirmed in this planning pass — implementer should run `./gradlew tasks`
   or check `settings.gradle`/module names before Step 7's compile-verification
   sub-step.
