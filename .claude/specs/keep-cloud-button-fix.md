# "Keep Cloud" Button Fix Specification

## Overview

`de.lazuli.cloudsync.WorldConflictScreen`'s "Keep Cloud" button (shown
when a world diverged both locally and on Steam Cloud) is supposed to
download the Cloud version of the world and then play it -- the same
end-to-end outcome as the "Download & Play" pill on a cloud-only row in
`de.lazuli.mainmenu.WorldsPanel` (`downloadAndPlay()`). Today it downloads
the world (the restore machinery genuinely runs) but never launches it,
so from the player's point of view pressing the button "does nothing":
the screen closes/returns to the Worlds tab and the download silently
finishes in the background with no world ever opened.

This is confirmed identical across all three platform modules
(`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`); each platform's
`WorldConflictScreen.java` is functionally a line-for-line port of the
others for the relevant methods.

## Goals

1. Pressing "Keep Cloud" must, on successful download, launch the player
   directly into the just-downloaded world -- byte-for-byte the same
   effective behavior as `WorldsPanel.downloadAndPlay()`'s
   `onCompleted` callback (`() -> launchWorld(cloudOnly.worldSlug())` /
   `() -> launchWorld(worldSlug)`).
2. This must work identically (same call chain, same failure handling)
   on all three platforms.
3. No other "Keep Cloud" behavior changes: `WorldConflictResolutionHook
   .recordKeepCloudResolution` must still be called before/around
   navigating away, `WorldSyncStatusHook.markDownloadPending`/
   `markDownloadFinished` bracketing must be preserved exactly as today,
   and a failed restore must still show "Restore failed: <reason>" and
   must NOT attempt to launch the world.

## Non-goals

- The "Game mode" row showing "Unknown" in `WorldConflictScreen`'s
  Local-only-details section. This is a **separate, already-understood,
  pre-existing gap**, not part of this bug: `WorldConflictScreen`'s
  `LevelDatBatch` (built by each platform's own `readLevelDatBatch`,
  e.g. `platform/fabric-1.21.11/.../WorldsPanel.java:1314-1345`) always
  returns `gameMode = null` for the batch-derived `LocalDetail.gameMode()`
  path -- see the comment at
  `platform/fabric-1.21.11/.../WorldsPanel.java:1332-1338` /
  `platform/fabric-26.1/.../WorldsPanel.java` (same comment, matching
  line range) explaining this is intentional/known ("keeps returning
  'unavailable' for those 3 fields here"), and
  `WorldConflictScreen.java:239`/`unpairedRows()` renders it via
  `nullToUnknown(local.gameMode())`. Do not touch `readLevelDatBatch`,
  `LevelDatBatch`, or `unpairedRows()`'s game-mode row as part of this
  fix; implementation must not conflate this with the Keep Cloud no-op.
- "Keep Local" (`onKeepLocal()`) -- unaffected, out of scope.
- Any change to `WorldRestoreHook`, `WorldRestoreService`, or the
  restore/extraction pipeline itself -- the download half already works
  correctly; only the "and play" half is missing.
- Any change to `WorldRestoreScreen`/`downloadAndPlay()` themselves --
  they are the reference implementation to be reused, not modified,
  except where Public API below requires threading one new parameter
  through `WorldConflictScreen`'s constructor and its one call site per
  platform.
- Any change to the conflict-detection/`WorldConflictResolutionHook`
  logic that decides when a conflict screen is shown in the first place.

## Requirements

### FR1 -- Root cause (confirmed, same on all 3 platforms)

`WorldConflictScreen.onKeepCloud()`'s `RestoreProgressListener.onComplete`
only sets a `volatile boolean keepCloudCompleted = true`:

- `platform/fabric-1.21.11/.../WorldConflictScreen.java:280-286`
- `platform/fabric-26.1/.../WorldConflictScreen.java:318-324`
- `platform/fabric-26.2/.../WorldConflictScreen.java:318-324`

That flag is only consumed by the render loop:

- `platform/fabric-1.21.11/.../WorldConflictScreen.java:137-141`
  (`render`)
- `platform/fabric-26.1/.../WorldConflictScreen.java:175-179`
  (`extractRenderState`)
- `platform/fabric-26.2/.../WorldConflictScreen.java:175-179`
  (`extractRenderState`)

```java
if (keepCloudCompleted) {
    resolutionHook.recordKeepCloudResolution(worldSlug, detail.cloud().deviceLabel(), detail.cloud().syncedAtTimestamp());
    onReturn.run();
    return;
}
```

`onReturn` (constructed at each platform's `openConflictScreen()` call
site, e.g. `platform/fabric-1.21.11/.../WorldsPanel.java:1293-1298`,
`platform/fabric-26.1/.../WorldsPanel.java:1216-1221`,
`platform/fabric-26.2/.../WorldsPanel.java:1199-1204`) only does
`reload(); setScreen(owner)` -- it navigates back to the Worlds tab and
never launches the world. The download itself (`restoreHook
.beginRestore`, `WorldConflictScreen.java:274`/`312`/`312`) genuinely
runs to completion; the only missing piece is a "launch the world"
side effect on success, equivalent to `WorldRestoreScreen`'s separate
`onCompleted` Runnable (never `onReturn`) at
`platform/fabric-1.21.11/.../WorldRestoreScreen.java:135-141`.

**Root cause is identical on all three platforms**: `WorldConflictScreen`
has exactly one `Runnable onReturn` field/constructor parameter, used
for both "download finished" and (implicitly, via `onKeepLocal`)
"local re-upload started" navigation -- there is no second, completion-
only Runnable the way `WorldRestoreScreen` has `onCompleted` vs.
`onReturn`. This is an unwired/missing callback, not a disabled button,
wrong reference, or TODO -- the button correctly triggers the download,
it simply has no code path that plays the world afterward.

### FR2 -- Reference behavior: `WorldsPanel.downloadAndPlay()`

Per platform (all three are the same shape; only the Minecraft API calls
for launching a world differ):

- **`fabric-1.21.11`** (`WorldsPanel.java:1183-1196`): constructs
  `new WorldRestoreScreen(cloudOnly, restoreHook, statusHook,
  () -> launchWorld(cloudOnly.worldSlug()), () -> { reload();
  setScreen(owner); })`. `launchWorld(String worldSlug)`
  (`WorldsPanel.java:1258-1271`) opens a `LevelStorage.Session`, reads a
  real `LevelSummary` synchronously, builds a real
  `WorldListWidget.WorldEntry`, and calls `playWorld(entry)`
  (`WorldsPanel.java:1353-1359`, which itself calls the entry's real,
  public `entry.play()` -- vanilla's own play path, never re-implemented
  here). This exists because 1.21.11 has no `createWorldOpenFlows()`-
  equivalent taking just a save-folder id (comment at
  `WorldsPanel.java:1242-1257`).
- **`fabric-26.1`** (`WorldsPanel.java:1126-1139`, `launchWorld` at
  `:1192-1194`): identical shape, but `launchWorld(String worldSlug)` is
  a one-liner: `Minecraft.getInstance().createWorldOpenFlows()
  .openWorld(worldSlug, () -> { })`.
- **`fabric-26.2`** (`WorldsPanel.java:1109-1122`, `launchWorld` at
  `:1175-1177`): identical to `fabric-26.1`, same one-line
  `createWorldOpenFlows().openWorld(worldSlug, () -> { })` body.

In all three, `WorldRestoreScreen`'s constructor
(`platform/.../WorldRestoreScreen.java:82-94`) takes both `onCompleted`
(nullable, invoked only on natural completion, never Cancel) and
`onReturn` (invoked on Cancel, or as the completion fallback when
`onCompleted` is null) as **separate** parameters -- see
`render()`/`:135-141` (1.21.11) or the equivalent
`extractRenderState()` (26.x): `if (completed) { if (onCompleted !=
null) onCompleted.run(); else onReturn.run(); return; }`.
`WorldConflictScreen` needs the same two-Runnable split.

### FR3 -- Construction-context differences: `WorldConflictScreen` vs. `WorldsPanel`

- `WorldConflictScreen` already receives `worldSlug`, `restoreHook`, and
  `statusHook` as constructor fields/params (`WorldConflictScreen.java`
  fields at `:65-75`(1.21.11)/`:70-80`(26.x)) -- everything
  `beginRestore` needs is already present; no new hook/service reference
  is required for the download half (unchanged).
- `WorldConflictScreen` has **no access to a "launch this world" routine**
  of its own. `launchWorld`/`playWorld`/`createWorldOpenFlows()` calls
  today live only in `WorldsPanel` (a different class, one per
  platform), which is also the class that constructs
  `WorldConflictScreen` via `openConflictScreen(LevelSummary summary)`
  (`WorldsPanel.java:1279-1299` on 1.21.11,
  `:1202-1222`/`:1185-1205` on 26.1/26.2). `WorldConflictScreen` itself
  has no `MinecraftClient`/`Minecraft`-instance field, no
  `WorldListWidget`/`dataWidget` reference (1.21.11 needs `dataWidget`
  as the outer instance for `WorldEntry`'s constructor -- see
  `WorldsPanel.java:1266`), and no `LevelStorage`/`levelSource`
  reference (26.x's `launchWorld` doesn't need one, but 1.21.11's does).
  Therefore the exact same `launchWorld(...)` call **cannot** be dropped
  into `WorldConflictScreen` unchanged -- it must remain a `WorldsPanel`
  method, invoked via a `Runnable` passed into the screen, exactly the
  pattern `WorldRestoreScreen`'s `onCompleted` already establishes.
- Both classes already share the same nullable-`WorldSyncStatusHook`
  field/guard idiom (`if (statusHook != null) { ... }`), so FR2's
  `markDownloadPending`/`markDownloadFinished` bracketing needs no
  changes.
- `WorldConflictScreen`'s render-loop completion branch also does one
  thing `WorldRestoreScreen`'s does not: it calls
  `resolutionHook.recordKeepCloudResolution(...)` before navigating away
  (`WorldConflictScreen.java:138`/`176`/`176`). This call must remain
  and must still fire before/regardless of which of the two Runnables
  (`onKeepCloudCompleted` vs `onReturn`) is invoked next, since it
  updates the local ancestor record so the conflict doesn't immediately
  re-trigger -- unrelated to which Runnable plays the world.

### FR4 -- Required change shape (implementation-agnostic; no code here)

1. `WorldConflictScreen`'s constructor gains one additional `Runnable`
   parameter -- e.g. `onKeepCloudCompleted` -- mirroring
   `WorldRestoreScreen`'s `onCompleted`/`onReturn` split. Constructor
   parameter ordering/placement is an implementation-phase decision;
   this parameter must NOT be nullable-with-silent-no-op the way
   `statusHook` is (a missing launch action would silently reproduce
   this exact bug) -- it should always be supplied by the one call site
   per platform, matching `WorldsPanel.downloadAndPlay()`'s pattern
   where `onCompleted` is always a real lambda, never `null`, at that
   call site (only `WorldRestoreScreen`'s own field/type stays nullable
   for its other, unrelated call site if any exist -- confirm during
   planning whether any other constructor call site for
   `WorldConflictScreen` exists besides `openConflictScreen`; grep found
   exactly one per platform).
2. The `keepCloudCompleted` render-loop branch changes from
   unconditionally calling `onReturn.run()` to calling the new
   `onKeepCloudCompleted.run()` in its place (still after
   `resolutionHook.recordKeepCloudResolution(...)`), while
   `onKeepLocal()` and the Cancel/failure paths (there is no explicit
   Cancel button on this screen today -- confirm during planning
   whether one should exist; out of scope for this bug fix) continue to
   use `onReturn` unchanged.
3. Each platform's `openConflictScreen(LevelSummary summary)` passes a
   new lambda for this parameter that reproduces `downloadAndPlay()`'s
   `onCompleted` behavior for the same `worldSlug`:
   - `fabric-1.21.11`: `() -> launchWorld(worldSlug)` (reusing the
     existing private `launchWorld(String)` method, `WorldsPanel.java
     :1258-1271`, unchanged).
   - `fabric-26.1`/`fabric-26.2`: `() -> launchWorld(worldSlug)`
     (reusing the existing private `launchWorld(String)` one-liner,
     `WorldsPanel.java:1192-1194` / `:1175-1177`, unchanged).
   `onReturn`'s existing lambda (`reload(); setScreen(owner);`) is
   unchanged and continues to be passed as-is for the Cancel/Keep-Local
   navigation cases.
4. On restore failure (`onFailed`), behavior is unchanged: `failureReason`
   is set, `statusHook.markDownloadFinished` fires, and the screen keeps
   showing "Restore failed: <reason>" -- `keepCloudCompleted` is never
   set to `true` in this path today and must not become `true` as part
   of this fix, so `onKeepCloudCompleted`/launch must never fire on
   failure.

## Public API

- `de.lazuli.cloudsync.WorldConflictScreen` (one copy per platform):
  constructor signature gains one new `Runnable` parameter (see FR4.1).
  This is an internal, single-call-site-per-platform class (not a
  published/external API), so the one call site
  (`WorldsPanel.openConflictScreen`) must be updated in the same change
  on all three platforms.
- No changes to `WorldRestoreHook`, `WorldConflictResolutionHook`,
  `WorldSyncStatusHook`, `RestoreProgressListener`, `RestoreHandle`, or
  any `api/` module type.

## Architecture

### Data flow (target)

```
WorldsPanel.openConflictScreen(summary)
  -> new WorldConflictScreen(..., restoreHook, statusHook, ...,
         onKeepCloudCompleted = () -> launchWorld(worldSlug),
         onReturn = () -> { reload(); setScreen(owner); })
       -> onKeepCloud() [button press]
            -> statusHook.markDownloadPending(worldSlug)
            -> restoreHook.beginRestore(worldSlug, listener)
       -> listener.onComplete(slug) [background thread]
            -> statusHook.markDownloadFinished(worldSlug)
            -> keepCloudCompleted = true
       -> render()/extractRenderState() [client thread, every frame]
            -> if (keepCloudCompleted):
                 resolutionHook.recordKeepCloudResolution(...)
                 onKeepCloudCompleted.run()   // NEW: launches the world
                 return
       -> listener.onFailed(slug, reason) [background thread]
            -> statusHook.markDownloadFinished(worldSlug)
            -> failureReason.set(reason)      // unchanged: screen shows
                                               // "Restore failed: ..."
                                               // world is never launched
```

### Per-Platform Screens

No new rendering primitives or layout changes are needed -- this is a
callback-wiring fix only. Each platform's `WorldConflictScreen.java`
needs the identical one-parameter constructor addition and the identical
one-line change in its completion branch (`render()` on 1.21.11,
`extractRenderState()` on 26.1/26.2). Each platform's `WorldsPanel
.openConflictScreen()` needs the identical one-line addition of the new
lambda argument, reusing that platform's own already-existing
`launchWorld(String)` private method verbatim.

## UI

No visual/layout changes. The screen's existing "Restoring world from
Steam Cloud.../Extracting world files..." status text
(`WorldConflictScreen.java:188-195`/`226-233`/`226-233`) and "Restore
failed: <reason>" text are unchanged. The only observable behavior
change is: on success, the player is taken directly into the
just-downloaded world instead of back to the Worlds tab.

## Configuration

None. No new config keys.

## Events

No new event/callback types. `RestoreProgressListener` is reused
unchanged; the fix only adds a second `Runnable` distinguishing
"restore completed, now play" from "leave this screen" at the
`WorldConflictScreen` level, mirroring `WorldRestoreScreen`'s existing
`onCompleted`/`onReturn` split.

## Networking

Unchanged. Still routes exclusively through the existing
`WorldRestoreHook.beginRestore` -> `WorldArchiveCloudStore.beginAsyncRead`
chain; no new network calls.

## Persistence

Unchanged. The restore/extraction/atomic-move pipeline
(`WorldRestoreService.extractAndFinish`) is untouched; this fix only
adds a post-completion UI-thread side effect (launching the world) after
persistence has already completed successfully.

## Compatibility

- `WorldConflictScreen`'s constructor gains one required parameter --
  source-compatible only in the sense that it is this project's own
  internal class with exactly one call site per platform
  (`WorldsPanel.openConflictScreen`); not a published/external API, so
  ordinary semver/back-compat concerns don't apply, but that one call
  site per platform must be updated in the same change.
- No change to `WorldRestoreScreen`, `WorldsPanel.downloadAndPlay()`, or
  `WorldsPanel.launchWorld()` -- they remain the untouched reference
  implementation being reused.
- The pre-existing "Game mode: Unknown" display gap
  (`readLevelDatBatch`/`LevelDatBatch`/`unpairedRows()`) is explicitly
  out of scope (Non-goals) and must not be touched or "fixed" as a
  side effect of this change.

## Performance

No new performance considerations -- this fix adds one `Runnable`
invocation on an already-existing per-frame branch (`keepCloudCompleted`
check), not a new per-frame computation.

## Future Extensions

- A true Cancel button on `WorldConflictScreen`'s "Keep Cloud" restore
  (today there is no Cancel button on this screen at all while a restore
  is in flight, unlike `WorldRestoreScreen`) -- noted as a possible
  follow-up during FR4.2's planning question, but explicitly not
  required by this bug fix.
- Fixing the "Game mode: Unknown" gap in `WorldConflictScreen`'s
  Local-only-details section (Non-goals) is a separate, already-known
  issue that could be scoped as its own fix later.
