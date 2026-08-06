# Spec: Cloud Sync Threshold, Full-Sync-Only, Un-sync Cloud Delete, and Sync-Off UI

## Background

User observed this log line from an earlier build:

```
[steam-cloud-sync-worker/INFO] (lazuli) World "New World" (82,2 MB) exceeds the 50 MB Cloud sync threshold; syncing a reduced critical-files-only copy (no terrain) instead of the full world.
```

This spec covers four requested changes to the existing Group 6 (world save
sync) feature in `features/steam-cloud-sync`. Investigation found that **one
of the four (the threshold bump) is already implemented** in the current
working tree, ahead of this spec. See Request 1 below.

**Constraint applying to all four requests:** the feature's existing Steam
Cloud state (fingerprint cache, quota) is RAM-only per process
(`WorldFingerprintCache`, populated by `WorldSaveSyncService.pullFingerprints()`)
and is never persisted to a local file — this is a deliberate design
invariant documented in `WorldSaveSyncService`'s own Javadoc (so an external
backup/restore of the run folder can't resurrect stale Cloud state). None of
the four changes below require or should introduce any new on-disk cache of
Cloud data. The one existing local-only cache that *does* persist
(`world-sync-ancestor-cache.json`, used for two-sided-conflict detection) is
unrelated to Cloud state and out of scope for this spec.

**Constraint applying to Request 2:** any `WorldsPanel.java` change must be
applied identically (module-appropriate mapping differences aside) to all
three platform modules: `platform/fabric-1.21.11`, `platform/fabric-26.1`,
`platform/fabric-26.2`. Per repo convention, new/updated automated tests
belong only in `fabric-26.2`'s test tree; the other two platforms are
verified by diffing their `WorldsPanel.java` against fabric-26.2's (module
imports aside) and confirming both compile. A check of
`.claude/context/minecraft.md`'s "Known Cross-Version API Differences" table
found no recorded divergence affecting `GuiGraphicsExtractor.fill`/`text`,
`WorldSyncToggleHook`, or `WorldSyncStatusHook` — no cross-version API
adaptation is expected to be needed for this change.

---

## Request 1: Increase size threshold from 50 MB to 1 GB

### Current behavior

**Already implemented — no further change needed**, confirmed via
`git log --follow -p` on
`features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/api/SteamCloudSyncConfig.java`:
commit `0e0630f` ("refactor: Rework cloud-only world list restoration across
platforms") already changed the default from `50` to `1024` MB.

Current state, confirmed by reading the file directly:
- `SteamCloudSyncConfig.java:73-74` — `DEFAULT` record literal's
  `maxWorldArchiveSizeMb` field is `1024` (not `50`).
- `SteamCloudSyncConfig.java:21` (Javadoc JSON example) already documents
  `"maxWorldArchiveSizeMb": 1024`.
- `WorldSaveSyncService.decideStrategy(long, int, boolean)`
  (`WorldSaveSyncService.java:623-629`) is a pure function parameterized on
  `maxWorldArchiveSizeMb` — it has no hardcoded 50 MB anywhere; the value
  flows in from config via `CloudSyncCoordinator`
  (`CloudSyncCoordinator.java:153`, `config.maxWorldArchiveSizeMb()`).
- The one place `50` still appears is `WorldSaveSyncServiceTest.java` (lines
  125, 131, 137), but only as a literal test-input threshold value passed to
  the pure `decideStrategy` function to test its comparison logic in
  isolation — not a reference to a real default, and it exercises correct
  behavior regardless of what the real default is.

### Resolved: threshold must be a hardcoded constant, not user-configurable (decision, no longer open)

The user explicitly rejected any "migration" framing for the stale on-disk
value:

> "Maybe this needs a rewrite? The value should be hardcoded in some config.
> If the user loads the updated version he shouldn't have to migrate, this
> just should simply be the new truth. This is not something that the user
> should ever be able to configure or modify in a file."

**Investigation finding**: `maxWorldArchiveSizeMb` **is currently persisted
to and overridable from an on-disk config file** — confirmed by reading
`SteamCloudSyncConfig.java` and `SteamCloudSyncConfigIO.java` in full:
- It is a component of the `SteamCloudSyncConfig` record
  (`SteamCloudSyncConfig.java:63`), included in `DEFAULT` (`:74`), and
  documented in the class Javadoc's on-disk JSON example (`:21`).
- `SteamCloudSyncConfigIO.parse()` (`SteamCloudSyncConfigIO.java:84-93`)
  reads it straight from the JSON root via `root.getInt("maxWorldArchiveSizeMb")`
  — a value the user (or a stale prior build) can freely write into
  `config/steam-cloud-sync.json`.
- `SteamCloudSyncConfigIO.serialize()` (`:107-119`) writes it back out on
  every save via `putNumber("maxWorldArchiveSizeMb", config.maxWorldArchiveSizeMb())`.
- `SteamCloudSyncConfigIO.load()` (`:52-67`) only writes `DEFAULT` when the
  file is *absent*; an existing file with a stale value (e.g. the old `50`)
  is read as-is and never auto-corrected. This is exactly the "migration"
  problem class the user wants eliminated at the root, not patched.

Since it **is** on-disk-overridable, per the user's decision this expands
Request 1's scope beyond a simple default-value bump:

### Desired behavior (revised scope)

1. **Remove `maxWorldArchiveSizeMb` entirely from `SteamCloudSyncConfig`**
   (the record component, the `DEFAULT` literal's `1024` argument, and the
   Javadoc's JSON example/param doc).
2. **Remove it from `SteamCloudSyncConfigIO`** — both `parse()` (drop the
   `root.getInt("maxWorldArchiveSizeMb")` call/positional argument) and
   `serialize()` (drop the `putNumber("maxWorldArchiveSizeMb", ...)` call).
   An old on-disk config file containing the now-obsolete key parses fine
   (the key is simply no longer read) and the key silently stops being
   written back out on next save — same "extra key ignored, then drops out"
   handling already planned for `allowSelectiveFallback`'s removal in
   Request 4, so both keys disappear from the schema in the same pass.
3. **Introduce a hardcoded, non-configurable constant** — e.g.
   `WorldSaveSyncService.MAX_WORLD_ARCHIVE_SIZE_MB = 1024` (exact
   name/location TBD at planning time; a `public static final int` on
   `WorldSaveSyncService` itself is the natural home since that is the sole
   consumer today via `decideStrategy`) — that is never read from or
   written to any on-disk file. This is "the new truth" per the user's own
   framing: changing it in a future build simply changes behavior on next
   launch, with no stale on-disk value able to override or shadow it, ever.
4. **Update `CloudSyncCoordinator`** (`CloudSyncCoordinator.java:153`,
   currently `config.maxWorldArchiveSizeMb()`) to pass the new hardcoded
   constant instead of reading it off `config`.
5. `WorldSaveSyncService.decideStrategy`'s existing pure-function shape
   (parameterized on the threshold value rather than reading a field
   directly) is unaffected in spirit — Request 4 below already simplifies
   its signature to `decideStrategy(long, int)`; that `int` parameter is
   still supplied by the caller, just now sourced from the hardcoded
   constant instead of `config.maxWorldArchiveSizeMb()`.
6. Test changes: `WorldSaveSyncServiceTest`'s existing threshold literals
   (lines ~125/131/137, passed directly to the pure `decideStrategy`
   function) are unaffected by this change in isolation — they already test
   the comparison logic against an arbitrary passed-in threshold, not the
   real constant. Any test currently constructing a `SteamCloudSyncConfig`
   with a `maxWorldArchiveSizeMb` argument (e.g. in
   `CloudSyncCoordinatorTest`) will need that constructor argument removed
   as a mechanical side effect of the record shape change; combined with
   Request 4's own `allowSelectiveFallback` removal, both record-shape
   changes should land in the same commit since they touch the same
   constructor call sites.

**Scope note for planning**: this is materially larger than "bump 50 to
1024" — it is a schema change to `SteamCloudSyncConfig`/`SteamCloudSyncConfigIO`
(same two files/methods Request 4 already modifies for
`allowSelectiveFallback`), plus a `CloudSyncCoordinator` call-site update.
Recommend planning Request 1 and Request 4's config-schema edits as one
combined change to `SteamCloudSyncConfig.java`/`SteamCloudSyncConfigIO.java`
(both fields removed in the same pass) rather than two separate passes over
the same files.

---

## Request 2: Hide the per-row status indicator when sync is off

### Investigation

Read `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
in full (980 lines). There is no literal "left rectangle" — the closest
matching element, and the one the user is almost certainly referring to, is
**the consolidated sync-status square**, drawn by
`drawConsolidatedStatusIndicator` (`WorldsPanel.java:544-567`), which sits
immediately to the *left* of the existing on/off sync-toggle square (drawn by
`drawSyncIcons`, `WorldsPanel.java:494-534`) at the right edge of each world
row. Both are 8x8px filled squares (`SYNC_ICON_SIZE = 8`,
`WorldsPanel.java:62`), not literal "rectangles" in the sense of a panel, but
the status square is visually the "left" one of the two-square pair and is
the only one of the two that reflects the overall consolidated state
(`ConsolidatedStatus`: `UNSYNCED`/`SYNCING`/`SYNCED`/`CONFLICT`).

Key facts:
- `drawSyncIcons` (`WorldsPanel.java:494-534`) always draws the toggle
  square (right-hand one) whenever `WorldSyncToggleHook` is available,
  regardless of the world's own enabled/disabled state — it just changes
  color (`COLOR_SYNC_ENABLED` vs `COLOR_SYNC_DISABLED`, lines 64-65).
- It then unconditionally calls `drawConsolidatedStatusIndicator` (line 533)
  as long as `WorldSyncStatusHook` is available — **there is currently no
  gate on `enabled` (the sync-preference boolean) before drawing the status
  square.**
- `computeConsolidatedStatus` (`WorldsPanel.java:443-456`, package-private
  static, already pure/testable per the file's own established pattern) is
  passed `syncEnabled` as a parameter and already *uses* it to help decide
  between `SYNCED` and `UNSYNCED` (line 452), but currently always returns
  some real state (never "don't draw") — when sync is off it currently
  resolves to `ConsolidatedStatus.UNSYNCED` (red square,
  `COLOR_STATUS_UNSYNCED`) rather than not rendering at all.
- The click/hit-test for this square (`mouseClicked`,
  `WorldsPanel.java:810-819`) only actually *does* something when the state
  is `CONFLICT` (opens `WorldConflictScreen`); for every other state,
  including the to-be-hidden `UNSYNCED`-while-disabled case, the hit-test
  rectangle is currently still checked but is a no-op click already (no
  `return true` short circuit tied to `enabled`). This must be updated in
  lockstep with the render-side change, per Decision below.
- The toggle square (right-hand one, `COLOR_SYNC_ENABLED`/`DISABLED`) must
  **not** be affected by this change — it is how the player turns sync back
  on, and must always remain visible/clickable regardless of preference
  state.

### Desired behavior

When a world's per-world sync preference is disabled (`isSyncEnabledFor`
returns `false`, backed by `WorldSyncToggleHook.isSyncEnabled`), the
consolidated status square (left-hand of the pair) must not render at all
for that row — the row shows only the toggle square. When sync is re-enabled
for that world, the status square reappears showing its real state again
(computed the same way it is today).

### Resolved: still show the status square for an unresolved conflict, even with sync off (no longer open)

The user's decision reverses the earlier recommendation in this section: a
sync-off world that has an unresolved conflict left over from before sync
was turned off **must still show the status square** (as `CONFLICT`,
clickable), not hide it. Hiding it would silently remove the only
low-friction discovery path for a conflict a player might not otherwise
notice (the "Resolve Cloud Conflict" pill only appears in the row's
*expanded* state, per `computeShowResolveButton`'s gating,
`WorldsPanel.java:482-484` — a collapsed row with sync off and an
unresolved conflict would otherwise show nothing).

**Precedence, worked out against the file's existing helpers** (read in
full: `computeBlocked`, `WorldsPanel.java:468-473`;
`computeShowResolveButton`, `:482-484`; `computeConsolidatedStatus`,
`:443-456`):
- `computeConsolidatedStatus` already checks `conflict == ConflictStatus.CONFLICT`
  **first**, before it ever consults `syncEnabled` (`:446-448`) — a
  `CONFLICT` classification is already independent of the sync-enabled
  state today. This new helper must not contradict that precedence; it must
  make the *visibility* gate follow the same "conflict wins regardless of
  sync-enabled" rule the *color* computation already follows.
- `computeShowResolveButton(isConflicted, syncEnabled, freshness)` already
  takes a precomputed `isConflicted` boolean (from the caller's
  `isRowConflicted(worldSlug)`, which additionally excludes the
  download-in-progress window per its own Javadoc, `WorldsPanel.java:680-695`)
  as its first parameter, independent of `syncEnabled` — the new helper
  should follow the exact same shape for consistency, rather than taking a
  raw `ConflictStatus`/recomputing conflict logic itself.

Following the file's existing pure-static-helper-for-testability convention,
add one new pure static helper:

```java
/**
 * Request 2 (cloud-sync-threshold-and-full-sync-only): the consolidated
 * status square hides when sync is off for this world, EXCEPT when there is
 * still an unresolved conflict left over from before sync was disabled --
 * mirrors {@link #computeShowResolveButton}'s own isConflicted-first shape
 * so a hidden square and a hidden resolve pill never independently disagree
 * about whether a conflict is "live."
 */
static boolean computeShowStatusIndicator(boolean syncEnabled, boolean isConflicted) {
    return syncEnabled || isConflicted;
}
```

Wire it into both call sites:
1. **Render side** (`drawSyncIcons`, around `WorldsPanel.java:533`): guard
   the `drawConsolidatedStatusIndicator(...)` call with
   `if (computeShowStatusIndicator(enabled, isRowConflicted(worldSlug))) { ... }`
   (`enabled` is already computed at line 501 in that method; `worldSlug` is
   already available as `summary.getLevelId()`).
2. **Hit-test side** (`mouseClicked`, around `WorldsPanel.java:814-819`):
   guard the status-square hit-test block with
   `computeShowStatusIndicator(isSyncEnabledFor(worldSlug), isRowConflicted(worldSlug))`
   (both helpers already exist and are already called elsewhere in this
   method's row loop) in place of the current unconditional
   `conflictCache.getOrDefault(...) == ConflictStatus.CONFLICT` check at
   line 816 — note the existing hit-test condition already *effectively*
   requires `CONFLICT` to do anything (only `CONFLICT` opens
   `WorldConflictScreen`), so gating on `computeShowStatusIndicator` first
   does not change click behavior for the conflict case, it only changes
   whether a *non-conflicted, sync-off* row's square is drawn/clickable at
   all (it becomes fully absent, per the base "hide when sync off"
   behavior).

### Edge cases

- **Upload/download in progress while sync gets disabled mid-transfer**: not
  reachable — `mouseClicked`'s existing row-level guard
  (`WorldsPanel.java:751-759`) already makes an in-progress-upload row fully
  non-interactive, and disabling sync itself has no code path today that can
  race an in-flight upload's own completion callback. No new handling needed.
- **Conflict state while sync is off (resolved, see above)**: per
  `computeConsolidatedStatus`'s own precedence (`CONFLICT` first, checked
  before `syncEnabled` is even consulted, `WorldsPanel.java:446-448`), a
  `CONFLICT` classification is independent of `syncEnabled`. Decided: the
  status square must **still show** in this case via
  `computeShowStatusIndicator(syncEnabled, isConflicted)` returning `true`
  when `isConflicted` is `true` regardless of `syncEnabled` — see the
  "Resolved" subsection under Desired behavior above for the full
  logic/rationale. This is a reversal of this section's earlier
  recommendation (which had proposed always hiding on sync-off); the
  collapsed-row case is exactly why: the resolve pill only appears when a
  row is expanded, so hiding the square too would leave a collapsed,
  sync-off, conflicted row with zero visible indication of the conflict.

---

## Request 3: Delete world's Cloud data when sync is disabled ("un-sync")

### Investigation

- The disable trigger is `WorldSyncPreferenceService.toggleSync(String)`
  (`WorldSyncPreferenceService.java:86-99`): flips the boolean, persists
  locally, and — only on the disabled→enabled transition — fires
  `onSyncEnabledListener` (wired by `CloudSyncCoordinator.java:167-168` to
  `WorldSaveSyncService.handleSyncReenabled`). **There is currently no
  symmetric listener/hook fired on the enabled→disabled transition** — this
  needs to be added.
- `WorldsPanel.mouseClicked` (`WorldsPanel.java:801-808`) is the only UI call
  site for `toggleSync`, via `syncToggleHookDiag.toggleSync(summary.getLevelId())`.
  No changes needed there — the new deletion behavior should be driven from
  the service layer (`WorldSyncPreferenceService`/`CloudSyncCoordinator`),
  not from the UI, consistent with how `handleSyncReenabled` is already
  wired.
- The delete-capable API: `WorldArchiveCloudStore` (interface,
  `WorldArchiveCloudStore.java`) currently exposes `forget(String fileName)`
  (lines 101-110), backed by `SteamRemoteStorageWorldArchiveStore.forget`
  (`SteamRemoteStorageWorldArchiveStore.java:230-238`) which calls
  `remoteStorage.fileForget(fileName)`. The interface's own Javadoc
  (`WorldArchiveCloudStore.java:101-105`) is explicit that `forget` is
  Valve's "leave local copy untouched, remove Cloud pointer only" primitive,
  used today purely for quota housekeeping (FR6.7,
  `WorldSaveSyncService.ensureQuota`), and that **`fileDelete` (steamworks4j:
  `SteamRemoteStorage.fileDelete(String)`) is the one that "also propagates a
  delete"** and is explicitly noted as "never used for this purpose" today.
  For Request 3, `fileDelete` semantics are what's wanted — the user
  explicitly wants Cloud storage freed for a world they've opted out of, not
  merely a soft "Cloud pointer forgotten but still billed against quota
  until GC" state. **This is the key design decision this spec flags**: the
  existing `forget()` method must not be silently reused for un-sync
  deletion (it likely doesn't actually free quota deterministically per
  Valve's docs); a new method must be added.
- No existing method on `WorldArchiveCloudStore` wraps `fileDelete` today —
  confirmed by reading the full interface. `NoopWorldArchiveCloudStore`
  (referenced from `CloudSyncCoordinator.java:122`, not separately opened in
  this investigation but following the same no-op-implementation convention
  as `NoopCloudFileStore`) will also need the new method added as a no-op
  returning `true` or `false` per that file's existing no-op convention —
  confirm at planning time which the file's siblings use.

### Desired behavior

1. Add `boolean deleteWorldArchive(String fileName)` (naming TBD at planning
   time — should read distinctly from `forget` in both name and Javadoc) to
   `WorldArchiveCloudStore`, backed in `SteamRemoteStorageWorldArchiveStore`
   by `remoteStorage.fileDelete(fileName)`, following the exact
   try/catch-and-log-then-return-false pattern every other method in that
   class already uses (e.g. `forget`, lines 230-238).
2. Add an un-sync (enabled→disabled) listener hook to
   `WorldSyncPreferenceService`, mirroring `setOnSyncEnabledListener`
   structurally (e.g. `setOnSyncDisabledListener`), fired from inside
   `toggleSync` on the `newValue == false` branch (the `else` of the
   existing `if (newValue) { onSyncEnabledListener... }` at
   `WorldSyncPreferenceService.java:92-98`).
3. Wire that listener in `CloudSyncCoordinator`'s constructor (alongside the
   existing `setOnSyncEnabledListener` wiring at lines 167-168) to a new
   method on `WorldSaveSyncService`, e.g. `handleSyncDisabled(String
   worldSlug)`, which:
   - Calls the new `archiveStore.deleteWorldArchive(archiveFileName(worldSlug))`
     (reusing the existing `archiveFileName` helper,
     `WorldSaveSyncService.java:654-656`) on the background worker (this is
     Cloud I/O — must not run on whatever thread `toggleSync` is called from,
     matching every other Cloud-touching path in this class going through
     `worker.submitBackgroundWork`/`enqueueTickThreadWork`).
   - On success: removes this world's entry from the fingerprint cache
     (mirroring the removal pattern already in `updateFingerprint`,
     `WorldSaveSyncService.java:812-818` — `fingerprints.removeIf(...)`,
     then `cloudFileStore.write(...)` the updated fingerprint file) so the
     world stops appearing as Cloud-backed to other devices/the freshness
     UI. Also clear any tracked status for the world via `statusTracker`.
   - On failure: logs via `warningLogger` and surfaces a player-visible
     message via `playerNotifier` (e.g. `"Failed to remove world \"" +
     displayName + "\" from Steam Cloud; it may still be taking up Cloud
     storage. You can try turning sync off again to retry."`) — does **not**
     re-enable the sync preference or otherwise roll back the toggle; the
     local preference-disable already succeeded and should not be
     reverted just because the best-effort cleanup failed.

### Edge cases

- **World was never actually synced (no archive on Cloud yet) when
  disabled**: `fileDelete` on a nonexistent Cloud file is expected to be a
  safe no-op per Valve's docs (mirroring `forget`'s own tolerance) — no
  special-case check needed before calling it; rely on the try/catch
  pattern to swallow/log any resulting failure rather than pre-checking
  `fileSize(...) > 0` first (that would be an extra round trip for no real
  benefit).
- **Delete API call fails** (network blip, Steam Cloud down, quota-check
  service unavailable): covered above — log + notify, preference-disable
  stands, world becomes a "Cloud sync off, and Cloud may still hold a stale
  copy" state indefinitely until the user retries via a future
  disable→enable→disable cycle (each `handleSyncDisabled` call attempts
  deletion fresh). No automatic retry loop is in scope for this spec.
- **Rapid toggle on/off spam**: `handleSyncDisabled` runs on
  `worker.submitBackgroundWork`, same as every other Cloud call in this
  class — no explicit re-entrancy guard exists for other paths either (e.g.
  `onWorldUnload` has none), so this spec does not add one either, for
  consistency; a genuinely pathological spam case can queue multiple
  delete/upload calls to Cloud but each is independently safe (idempotent
  delete, last-write-wins upload).
- **This world had the Request 4 "over threshold, not synced" status
  (never actually uploaded because it exceeded the size cap) at the moment
  sync is disabled**: `deleteWorldArchive` is still safe to call
  unconditionally (see the "no archive on Cloud yet" case above) — no
  special-case skip needed just because the world was previously
  over-threshold.

### Open question

Exact wording of the failure-notification string, and the exact new method
names (`deleteWorldArchive` vs. some other name) are left open for the
planning phase / user sign-off — not load-bearing for the spec's shape.

---

## Request 4 (most important): Remove the partial-sync fallback entirely

### Current behavior

The critical-files-only fallback is fully implemented in
`WorldSaveSyncService.java`:
- `SELECTIVE_FALLBACK_ENTRIES` (lines 56-61): the fixed file/folder allowlist
  (`level.dat`, `playerdata`, `stats`, `advancements`, `icon.png`).
- `SyncStrategy` enum (lines 66-70): `WHOLE_ARCHIVE`, `SELECTIVE_FALLBACK`,
  `SKIPPED`.
- `allowSelectiveFallback` (constructor field, line 79; config-sourced,
  `SteamCloudSyncConfig.allowSelectiveFallback()`, default `true`,
  `SteamCloudSyncConfig.java:64,74`).
- `decideStrategy(long, int, boolean)` (lines 623-629, pure/static): returns
  `SELECTIVE_FALLBACK` for an over-threshold world only if
  `allowSelectiveFallback` is `true`; else `SKIPPED`.
- `buildSelectiveArchive(Path)` (lines 732-753): builds the reduced zip.
- `syncWorldNow` (lines 664-717): branches on `strategy` to call
  `buildWholeArchive` vs `buildSelectiveArchive` (line 677-679), and emits
  the exact log line the user quoted when `strategy ==
  SyncStrategy.SELECTIVE_FALLBACK` (lines 681-685).
- Tests: `WorldSaveSyncServiceTest.java` has direct coverage of
  `decideStrategy`'s `SELECTIVE_FALLBACK` branch (lines ~120-140) and of the
  end-to-end selective-sync path (search hits for `SKIPPED`,
  `SELECTIVE_FALLBACK` — confirm full list of affected test methods at
  planning time; at minimum the three `decideStrategy(...)` assertions at
  lines 125/131/137 and the `SKIPPED_TOO_LARGE` status assertion at line 260
  are directly affected).
- `WorldSyncStatusHook.SyncStatus.SKIPPED_TOO_LARGE`
  (`WorldSyncStatusHook.java:41-45`) already exists as a status value and is
  already surfaced today (its Javadoc already says "...and selective
  fallback was not allowed/available" — meaning the enum value itself
  already anticipates this spec's end state; no new enum value is needed).
  `WorldsPanel.unsyncedTooltipFor` (`WorldsPanel.java:586-604`) already
  branches on `SKIPPED_TOO_LARGE` today (line 593-594: `"(too large to sync
  automatically)"`).

### Desired behavior

Make sync strictly all-or-nothing:
1. **Remove** `SyncStrategy.SELECTIVE_FALLBACK`, `SELECTIVE_FALLBACK_ENTRIES`,
   `buildSelectiveArchive`, and the `allowSelectiveFallback`
   constructor/field entirely from `WorldSaveSyncService`. `SyncStrategy`
   becomes a two-value enum: `WHOLE_ARCHIVE`, `SKIPPED`.
2. **Simplify** `decideStrategy` to `decideStrategy(long folderSizeBytes,
   int maxWorldArchiveSizeMb)` (drop the `allowSelectiveFallback` parameter
   entirely — it no longer has a meaning): `WHOLE_ARCHIVE` at/under
   threshold, `SKIPPED` over it, unconditionally.
3. **Remove** `allowSelectiveFallback` from `SteamCloudSyncConfig` (the
   record component, the `DEFAULT` literal's trailing `true`, and the
   Javadoc's JSON example/param doc) and from `SteamCloudSyncConfigIO`
   (both `parse` and `serialize`). This is a **breaking config schema
   change** — see migration note below.
4. **Update** `CloudSyncCoordinator`'s `WorldSaveSyncService` construction
   (`CloudSyncCoordinator.java:151-154`) to drop the now-removed
   `config.allowSelectiveFallback()` argument.
5. **Update** the `SKIPPED` branch's player-facing message in `syncWorldNow`
   (currently lines 669-674) to be the sole "over threshold" message the
   player ever sees for this path (no more "syncing a reduced... copy"
   message, since that whole code path is deleted). Suggested wording,
   **open for user confirmation**: `"World \"" + displayName + "\" (" +
   formatMb(sizeBytes) + " MB) exceeds the " + maxWorldArchiveSizeMb + " MB
   Cloud sync threshold; not synced this session."` — this is actually
   already the exact existing `SKIPPED` message
   (`WorldSaveSyncService.java:670-671`); it needs no wording change, only
   for the `SELECTIVE_FALLBACK` branch/message above it (lines 677-685) to
   be deleted so `SKIPPED` is the only remaining over-threshold outcome.
6. **UI status text**: `WorldSyncStatusHook.SyncStatus.SKIPPED_TOO_LARGE` and
   `WorldsPanel`'s existing `"(too large to sync automatically)"` tooltip
   text (`WorldsPanel.java:593-594`) already correctly represent "over
   threshold, not synced" and require **no change** — confirming explicitly
   per the task's request to identify what UI state represents this,
   because it turns out to already exist and already be correctly wired to
   the `SKIPPED` path (`statusTracker.markSkippedTooLarge(worldSlug)`,
   `WorldSaveSyncService.java:672`).

### Edge cases

- **Stale previously-uploaded partial (critical-files-only) archives
  (resolved, no longer open — Option A)**: Before this change, a world that
  was over-threshold could have a `lazuli-world-<slug>.zip` archive on Cloud
  that is actually the *reduced* critical-files-only zip, not a full world
  backup — and Cloud has no way to distinguish "this archive is a full
  backup" from "this archive is a reduced fallback" (the archive file name
  and fingerprint entry are identical in both cases;
  `SELECTIVE_FALLBACK_ENTRIES` content is not tagged anywhere in the
  fingerprint format, confirmed by reading `WorldFingerprint`'s usage in
  this file — it only carries
  `worldSlug/displayName/deviceLabel/syncedAtTimestamp`, no strategy flag).
  This means an existing Cloud archive that resulted from a past
  `SELECTIVE_FALLBACK` upload is silently indistinguishable, after this
  change ships, from a legitimate full-world backup — a later "Keep Cloud"
  restore (`WorldRestoreService`) of that world would restore only the
  reduced critical-files-only content while believing it to be a complete
  world, with no terrain, until that world naturally re-syncs again.

  **Decision: Option A — leave as-is, no sweep/delete/tagging.** No new
  code is added to detect, migrate, delete, or tag pre-existing partial
  archives. A stale partial archive remains on Cloud, indistinguishable
  from a full backup, until the next time that world naturally re-syncs —
  which will either overwrite it with a full archive (if the world is now
  under the threshold) or leave it untouched forever (if still over
  threshold, since `SKIPPED` uploads nothing). This is accepted as a known,
  low-probability residual risk rather than something this spec's
  implementation needs to remediate; Options B (proactive sweep/delete) and
  C (fingerprint tagging + restore-time warning) are explicitly rejected as
  out of scope for this change.

- **A world sits exactly at the threshold**: unchanged behavior —
  `decideStrategy`'s existing `<=` comparison
  (`WorldSaveSyncService.java:625`) already treats "at or under" as
  `WHOLE_ARCHIVE`; this is preserved as-is, only the `else` branch simplifies
  from a fallback check to unconditional `SKIPPED`.
- **`allowSelectiveFallback: true/false` already present in a user's
  on-disk `config/steam-cloud-sync.json`**: since `SteamCloudSyncConfigIO.parse`
  (lines 84-93) constructs the record positionally from named JSON keys via
  `root.getBoolean("allowSelectiveFallback")`, removing that record
  component means `parse` simply stops reading that key — an old config
  file containing the now-obsolete key will parse successfully (extra JSON
  keys are presumably ignored by `CloudSyncJson`'s object reader; confirm
  at planning/implementation time) and `serialize` will stop writing it back
  out on next save, so the key silently disappears from the file over time.
  No explicit migration code is needed for this half of the schema change
  (unlike the Request 1 threshold-value question, this field is being
  deleted outright, not defaulted differently).

### Constraints

- This is a breaking API change to `WorldSaveSyncService.decideStrategy`'s
  signature (public static method) and to `SteamCloudSyncConfig`'s record
  shape (public record, used across `CloudSyncCoordinator` and both config
  IO/tests) — all call sites listed above must be updated in the same
  change; there is no deprecate-then-remove path taken here since this is
  an internal-only API with a small, fully-enumerated call-site list (all
  found above).
- Per repo test convention, all new/updated test coverage for these
  `features/steam-cloud-sync` classes lives in
  `features/steam-cloud-sync/src/test/...` (this feature module is
  platform-independent and has only one copy, unlike `WorldsPanel`) —
  no platform-specific test duplication is needed for Requests 1/3/4 (only
  Request 2's `WorldsPanel` change touches the three-platform-copy
  convention).

---

## Resolved Decisions (formerly Open Questions)

All three questions previously raised in this spec have been decided by the
user; the spec is final and ready for planning:

1. **Request 1 — threshold persistence**: `maxWorldArchiveSizeMb` was found
   to be on-disk-overridable (persisted via `SteamCloudSyncConfigIO`
   parse/serialize). Per the user's explicit rejection of any "migration"
   framing, it is being removed from `SteamCloudSyncConfig`/
   `SteamCloudSyncConfigIO` entirely and replaced with a hardcoded,
   non-configurable, non-persisted constant. See the "Resolved" subsection
   under Request 1 above for the full revised scope — this is larger than
   the original "bump the default" framing and should be planned alongside
   Request 4's config-schema edits to the same two files.
2. **Request 2 — status square visibility with an unresolved conflict**:
   decided the square must still show (and remain clickable) when sync is
   off but there is an unresolved conflict, via
   `computeShowStatusIndicator(syncEnabled, isConflicted) = syncEnabled || isConflicted`.
   See the "Resolved" subsection under Request 2 above for the full
   precedence logic and both call-site wirings.
3. **Request 4 — stale partial archives**: decided as Option A (leave
   as-is; no sweep/delete/tagging). See the "resolved" edge-case bullet
   under Request 4 above.

Request 3's method-naming/wording items (`deleteWorldArchive` et al., exact
failure-notification string) remain non-load-bearing implementation
details, unchanged from the original spec — left for planning-time
sign-off, not blocking approval of this spec.

## Notes for planning

- Requests 1 and 4 now both modify `SteamCloudSyncConfig.java` and
  `SteamCloudSyncConfigIO.java` (Request 1 removes `maxWorldArchiveSizeMb`,
  Request 4 removes `allowSelectiveFallback`) — plan these as a single
  combined edit to each file rather than two sequential passes, to avoid
  merge/diff noise across the same record constructor and
  parse/serialize bodies.
- Any existing test that constructs a `SteamCloudSyncConfig` via its full
  positional-argument constructor (e.g. in `CloudSyncCoordinatorTest`) will
  need updating for the record shrinking by two components
  (`maxWorldArchiveSizeMb` and `allowSelectiveFallback`) — confirm the full
  list of affected constructor call sites at planning time.
