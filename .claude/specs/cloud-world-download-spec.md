# Cloud World Download (Play Cloud-Only World) Specification

## Overview

Today, pressing "Play" on a cloud-only world row in the Worlds tab
(`WorldsPanel.openRestoreFlow`, all three platforms:
`platform/fabric-1.21.11`, `platform/fabric-26.1`,
`platform/fabric-26.2`, package `de.lazuli.mainmenu`) opens
`de.lazuli.cloudsync.WorldRestoreScreen`, which drives
`WorldRestoreHook.beginRestore` (implemented by
`features/steam-cloud-sync`'s `WorldRestoreService`). The transfer
itself already works end-to-end (chunked async Steam Cloud read via
`WorldArchiveCloudStore.beginAsyncRead`, background zip extraction via
`CloudSyncWorker`, atomic move into the saves folder). However:

- The screen is framed as "Restoring.../Extracting..." with only a raw
  progress bar -- no percentage, no byte counts, no ETA.
- Its Cancel button calls `WorldRestoreHook.cancelRestore`, which
  **aborts** the in-flight transfer/extraction outright
  (`WorldRestoreService.cancelRestore` sets `RestoreContext.cancelled`,
  which is checked in the chunk/extract loops and turns into an
  `onFailed` callback) -- it does not let the world finish downloading
  in the background.

This feature reframes that same flow as an explicit, transparent
"Downloading '<world_name>' from Steam Cloud..." experience with a
percentage, human-readable current/total size, and a once-per-second
ETA, and changes Cancel's semantics so the download keeps running to
completion in the background while the player is returned to the main
menu.

## Goals

1. Real progress UI: title `Downloading '<world_name>' from Steam
   Cloud...`, a progress bar, percentage text, human-readable
   current-size/total-size text, and an ETA recalculated once per
   second -- present identically (visually and behaviorally) on all
   three platforms.
2. Cancel navigates back to the main menu immediately; the download
   (Steam Cloud read + extraction + atomic move into the saves folder)
   continues running to completion in the background, exactly like the
   existing "Keep Cloud" conflict-resolution restore already does
   (`WorldConflictScreen.onKeepCloud`, which never calls
   `cancelRestore` and instead relies on `WorldSyncStatusHook`'s
   `markDownloadPending`/`markDownloadFinished` pair) -- i.e. re-use
   that already-proven ownership model rather than inventing a new one.
3. A single, non-duplicated (shared, Minecraft-free) piece of logic
   computes percentage / human-readable sizes / ETA from the existing
   `RestoreProgress` stream, consumed identically by all three
   platform screens.
4. Full lifecycle logging (start, periodic progress, completion,
   failure, cancel-but-continuing) via each platform's
   `LazuliMod.LOGGER`, matching existing call-site conventions (e.g.
   `WorldsPanel.java:299-313`, `WorldRestoreService`'s injected
   `infoLogger`/`warningLogger`).
5. No behavior change to the unrelated "Keep Cloud" conflict-resolution
   restore flow (`WorldConflictScreen`) beyond whatever it picks up for
   free by depending on the same `WorldRestoreHook`/`RestoreProgress`
   types (it does not use the new screen or the new true-cancel
   semantics below).

## Non-goals

- Any change to how a world becomes "cloud-only" in the first place
  (`CloudOnlyWorldsHook`, `CloudOnlyWorldDetector`,
  `CloudOnlyWorldsFacade`) -- out of scope.
- Any change to the upload half of Cloud sync (`WorldSaveSyncService`,
  `CloudSyncCoordinator`, `CloudSyncableReconciler`) -- out of scope.
- A true "abort/discard the download" action. The user's explicit
  request is that Cancel means "stop watching, keep downloading in the
  background" -- not "delete the partial download." A genuine abort
  button is a Future Extension, not part of this feature.
- Multiple concurrent cloud-world downloads with a combined/queued UI.
  Nothing today prevents two different cloud-only worlds from being
  downloaded back-to-back (each is independent, keyed by its own world
  slug), but this spec does not add a multi-download tracking surface
  in the Worlds list beyond the existing single-world
  `isDownloadInProgress`/blocked-row gating already used for "Keep
  Cloud" restores.
- Networking-layer retry/resume of a failed/partial Steam Cloud read.
  `WorldArchiveCloudStore.beginAsyncRead` failure semantics are
  unchanged; this feature only changes what the player sees and how
  Cancel behaves.

## Requirements

### FR1 -- Real download screen content
FR1.1. The screen's title must read exactly `Downloading '<world_name>'
from Steam Cloud...` where `<world_name>` is
`CloudOnlyWorldSummary.displayName()`.
FR1.2. A horizontal progress bar reflecting the combined download
fraction (see FR3) must be drawn below the title.
FR1.3. A percentage (`0`-`100`, integer, e.g. `"42%"`) must be
displayed.
FR1.4. Human-readable current/total size must be displayed, e.g.
`"42.3 MB / 118.0 MB"` (binary units, see FR4).
FR1.5. An ETA string must be displayed, e.g. `"About 12s remaining"` /
`"About 2m remaining"`, or `"Calculating..."` before the first estimate
is available; recalculated at most once per second (FR5), never
recalculated more often (jitter avoidance) and never left stale for
more than ~1 second while the download is progressing.
FR1.6. A Cancel button must remain present at all times the download
is running.
FR1.7. All of FR1.1-FR1.6 must render and behave identically (module
math, thresholds, and rounding) on `fabric-1.21.11`, `fabric-26.1`, and
`fabric-26.2` -- only the Minecraft rendering API calls differ per
platform (see Architecture -- Per-Platform Screens).

### FR2 -- Cancel = background, not abort
FR2.1. Pressing Cancel (or otherwise closing the screen, e.g. Escape,
if the platform's `Screen` allows it) must immediately return the
player to the main menu's Worlds tab (mirroring `WorldRestoreScreen`'s
existing `onReturn` callback, reused unchanged).
FR2.2. Cancel must **not** call `WorldRestoreHook.cancelRestore`. The
in-flight `RestoreProgressListener` (owned by `WorldRestoreService`'s
`RestoreContext`, not by the screen) must keep receiving
`onProgress`/`onComplete`/`onFailed` callbacks and keep driving the
Steam Cloud read, extraction, and atomic move to completion exactly as
if the screen were still open.
FR2.3. Because no screen is listening anymore after Cancel, the
listener's `onProgress` after Cancel becomes a no-op (the screen
instance is discarded); `onComplete`/`onFailed` must still perform
their existing side effects (`preferenceService.markEnabledAfterRestore`
on success; nothing extra required on failure beyond existing
`warningLogger` usage) plus the new `WorldSyncStatusHook.markDownloadFinished`
call (FR2.4), so a background-completed or -failed download is not left
in a stuck "download in progress" state.
FR2.4. Reuse `WorldSyncStatusHook.markDownloadPending`/
`markDownloadFinished` (already defined in
`api/src/main/java/de/lazuli/api/cloudsync/WorldSyncStatusHook.java:86-117`,
already used by `WorldConflictScreen.onKeepCloud`) around the new
screen's `beginRestore` call, so that:
  - the cloud-only row (or, once the world lands locally, the real
    row) reflects "download in progress" via the same blocked-row gate
    `WorldsPanel` already applies for `isDownloadInProgress` (FR2.5),
  - a second "Play" press on the same cloud-only world slug while a
    background download is still running is blocked (same mechanism
    that already blocks Play/Edit during an in-flight "Keep Cloud"
    restore).
FR2.5. `WorldsPanel`'s existing blocked-row logic (already checks
`isDownloadInProgress`, `isUploadInProgress`, `isConflictCheckPending`
per `api/.../WorldSyncStatusHook.java` and its call sites in
`WorldsPanel.java`) requires no changes beyond the new screen calling
`markDownloadPending`/`markDownloadFinished` at the right times --
confirm during implementation that the cloud-only row's slug is the
same key (`worldSlug`) the tracker is keyed on so the existing gate
picks it up with zero `WorldsPanel` code changes.

### FR3 -- Unified download-fraction model
FR3.1. `RestoreProgress` already reports two sequential phases,
`READING_FROM_CLOUD` and `EXTRACTING`, each with its own
`processedBytes`/`totalBytes`. For this feature's single progress bar
and percentage, the two phases must be combined into one 0-100% scale
so the player never sees the bar/percentage jump backwards or restart
at the phase boundary.
FR3.2. Combination rule: weight `READING_FROM_CLOUD` and `EXTRACTING`
by their own `totalBytes` (compressed archive size and estimated
uncompressed size respectively, both already computed by
`WorldRestoreService`/`WorldArchiveCloudStore.fileSize`), i.e.
`overallFraction = (readingWeight * readingFraction) + (extractingWeight
* extractingFraction)` where `readingWeight = readingTotal /
(readingTotal + extractingTotal)` and `extractingWeight = 1 -
readingWeight`, each phase's own fraction clamped to `[0,1]` and
assumed `0` for a phase not yet started / `1` for a phase already
finished. This keeps the combined bar monotonically non-decreasing.
FR3.3. The human-readable current/total size (FR1.4, FR4) is shown
per-phase's own bytes (i.e. "reading" shows Cloud-read bytes vs.
archive size; "extracting" shows extracted bytes vs. estimated
uncompressed size) -- not a synthetic combined byte count -- so the
numbers stay truthful and directly traceable to what
`WorldRestoreService` actually reports; only the percentage/progress
bar use the combined fraction from FR3.2.

### FR4 -- Human-readable byte formatting
FR4.1. A single shared, Minecraft-free formatting function must render
a byte count as a human-readable binary-unit string: `B` for < 1024,
`KB`/`MB`/`GB`/`TB` above that (1024-based), one decimal place for
`KB` and above (e.g. `"512 B"`, `"4.2 KB"`, `"118.0 MB"`, `"1.3 GB"`),
matching the format the user's request already exemplifies
(percentage + "current file-size & total file-size").
FR4.2. This function must be used for both the current-size and
total-size halves of FR1.4's display string, guaranteeing consistent
units are used together (e.g. never "42.3 MB / 0.1 GB").

### FR5 -- ETA computation, recalculated every second
FR5.1. ETA is computed from the same combined byte accounting as FR3
(i.e. based on overall weighted bytes processed vs. total bytes across
both phases, so the ETA reflects "time until this screen would show
100%", not just the current phase).
FR5.2. Rate estimate: average throughput since the download started
(`bytesProcessedSoFar / secondsElapsedSoFar`), recomputed only when at
least 1 second has elapsed since the last recomputation (a per-screen
`lastEtaUpdateMillis` field checked every render call, using
`System.currentTimeMillis()` or the platform's existing render-delta
clock -- whichever this project already conventionally uses in similar
polling code, e.g. `WorldRestoreScreen`'s existing `AtomicReference`
polling pattern).
FR5.3. `ETA seconds = (totalBytes - bytesProcessedSoFar) / averageRate`,
rendered as `"Calculating..."` if fewer than ~2 seconds of data have
been collected or `averageRate <= 0`; otherwise rendered as `"About
<N>s remaining"` for `< 60` seconds or `"About <N>m remaining"`
(rounded to nearest minute, minimum displayed value `"About 1m
remaining"`) for `>= 60` seconds.
FR5.4. Note (documented in code, not just this spec): because Steam
Cloud already fully downloads a user's Cloud files to the local
machine before the game launches (`WorldRestoreScreen`'s and
`RestoreProgress`'s existing javadoc), the `READING_FROM_CLOUD` phase
is typically fast relative to `EXTRACTING` for a large world; the
combined-average-rate approach in FR5.2 is intentionally simple
(no windowed/smoothed rate) because the two phases have different
throughput profiles and a smoothed instantaneous rate would be noisier
across the phase boundary, not less. A windowed rate estimator is a
Future Extension if this proves too jumpy in practice.

### FR6 -- Logging
All log calls follow this project's existing convention of routing
through each platform's `LazuliMod.LOGGER` (client-side) and
`features/steam-cloud-sync`'s constructor-injected
`Consumer<String> infoLogger`/`warningLogger` (platform-agnostic
service code), matching exact call sites already in
`WorldRestoreService` (`infoLogger.accept("Downloading world ...")`,
`infoLogger.accept("Downloaded and restored world ...")`,
`warningLogger.accept("Failed to restore world ...")`) and
`WorldsPanel` (`LazuliMod.LOGGER.info`/`.warn`).

FR6.1. Start: `WorldRestoreService.beginRestore` already logs
`"Downloading world \"<slug>\" (<n> bytes) from Steam Cloud."` at info
level -- keep this call site as-is (already matches the new framing;
no change needed here).
FR6.2. Milestone progress: add an info-level log every time the
combined percentage (FR3.2) crosses a new 25% boundary (25/50/75/100),
logged once each, e.g. `"Cloud world download \"<slug>\": 50% (<current
human size> / <total human size>)."` -- logged from
`WorldRestoreService` (has access to raw bytes already) rather than
from the screen, so the milestone log fires even after Cancel (FR2)
once the screen is gone.
FR6.3. Completion: `WorldRestoreService.extractAndFinish` already logs
`"Downloaded and restored world \"<slug>\" from Steam Cloud."` at info
level -- keep as-is.
FR6.4. Failure: `WorldRestoreService`'s existing
`warningLogger.accept("Failed to restore world ...")` and the
`onFailed` branch's `activeRestores.remove` -- keep as-is; the only new
requirement is that this path also fires `markDownloadFinished` (FR2.4)
now that a listener can outlive the screen.
FR6.5. Cancel-but-continuing: when the player presses Cancel while a
download is in flight, log once at info level from the screen/platform
code (mirrors `WorldsPanel.java:299-313`'s per-platform
`LazuliMod.LOGGER.info` convention), e.g. `"Player left the download
screen for cloud-only world \"<slug>\"; download continues in the
background."` This is a platform-side log (the screen knows the player
navigated away); it is distinct from FR6.2-FR6.4's service-side logs
which continue regardless of whether a screen is watching.

## Public API

### `api/src/main/java/de/lazuli/api/cloudsync/`

No changes to `WorldRestoreHook`, `RestoreHandle`, or
`RestoreProgressListener`'s method signatures -- the existing
begin/cancel/callback contract is reused as-is (FR2.2 explicitly avoids
calling `cancelRestore` for the new flow; that method remains available
unchanged for any future genuine-abort feature).

New addition, `RestoreProgress` (existing record) -- no field changes;
`Phase` enum stays `READING_FROM_CLOUD`/`EXTRACTING` (FR3 combination
logic is display-layer, not part of this record).

New class: `DownloadProgressPresenter` (final, Minecraft-free, new file
`api/src/main/java/de/lazuli/api/cloudsync/DownloadProgressPresenter.java`)
-- the single shared implementation of FR3/FR4/FR5, instantiated once
per screen instance (one per in-flight download the player is
currently watching) and fed every `RestoreProgress` snapshot the screen
polls, e.g.:

```java
public final class DownloadProgressPresenter {
    public DownloadProgressPresenter(long readingTotalBytes, long extractingEstimatedTotalBytes);
    public void onProgress(RestoreProgress progress); // called every render frame with the latest snapshot
    public DownloadDisplayStats currentStats(long nowMillis); // recomputes ETA only if >=1s since last call
}

public record DownloadDisplayStats(
        float overallFraction,      // FR3.2, clamped [0,1]
        int percentage,             // FR1.3, 0-100
        String currentSizeText,     // FR1.4/FR4, phase-scoped
        String totalSizeText,       // FR1.4/FR4, phase-scoped
        String etaText) {}          // FR1.5/FR5
```

This class owns no threading/Minecraft dependencies (constructible and
unit-testable on a plain JVM, consistent with every other type in this
package), matching this module's existing "Minecraft-free" convention
called out in `CloudOnlyWorldSummary`'s and `WorldRestoreHook`'s
javadoc.

### `features/steam-cloud-sync`

`WorldRestoreService`: no public-method signature changes. Internal
addition only: the 25/50/75/100% milestone log (FR6.2), computed
inline from bytes already available in `RestoreContext`/`onProgress`
and `extractAndFinish`.

### Per-platform (`de.lazuli.cloudsync.WorldRestoreScreen`, one copy
per platform module)

Modified in place (same class name/package, since
`WorldsPanel.openRestoreFlow` and `WorldRestoreHookHolder` construct it
by that name today) -- constructor signature unchanged
(`WorldRestoreScreen(CloudOnlyWorldSummary summary, WorldRestoreHook
restoreHook, Runnable onReturn)`); one new constructor parameter is
required: the `WorldSyncStatusHook` needed for FR2.4's
`markDownloadPending`/`markDownloadFinished` calls (nullable, mirroring
`WorldConflictScreen`'s existing nullable `statusHook` field and its
`if (statusHook != null)` guard pattern). `WorldsPanel.openRestoreFlow`
must pass `WorldSyncStatusHookHolder.getOrNull()` (already imported/used
elsewhere in the same file) at the one call site per platform.

## Architecture

### Data flow (unchanged skeleton, new presentation layer)

```
WorldsPanel.openRestoreFlow(cloudOnly)
  -> new WorldRestoreScreen(summary, restoreHook, statusHook, onReturn)
       -> init(): statusHook.markDownloadPending(worldSlug)
                  handle = restoreHook.beginRestore(worldSlug, listener)
       -> listener.onProgress(RestoreProgress) [background thread]
            -> screen's AtomicReference<RestoreProgress> updated (existing pattern)
       -> render() [client thread, every frame]
            -> presenter.onProgress(latestProgress.get())
            -> stats = presenter.currentStats(now)
            -> draw title/bar/percentage/sizes/eta/Cancel using `stats`
       -> Cancel pressed
            -> onReturn.run()  [navigate to Worlds tab immediately]
            -> restoreHook.cancelRestore(...) is NOT called
            -> listener keeps running inside WorldRestoreService,
               eventually calls onComplete/onFailed, both of which call
               statusHook.markDownloadFinished(worldSlug) (new)
```

### Per-Platform Screens

One subsection per platform, since each has its own GUI/rendering API
(`.claude/context/minecraft.md`'s Known Cross-Version API Differences
table) but must produce byte-for-byte identical *numbers* (FR1.7) --
only the drawing calls differ.

#### `platform/fabric-1.21.11` (`de.lazuli.cloudsync.WorldRestoreScreen`)
- Uses `DrawContext` immediate-mode primitives, same as today
  (`context.fill`, `context.drawCenteredTextWithShadow`,
  `ButtonWidget.builder`).
- Title: `Text.literal("Downloading '" + summary.displayName() + "' from Steam Cloud...")`
  passed to `super(...)`, replacing today's `"Restoring " +
  summary.displayName()`.
- Add three more `context.drawCenteredTextWithShadow` calls below the
  existing bar for percentage, size text, and ETA text (stacked, using
  the same `width/2` centering and a consistent vertical rhythm as the
  existing title/bar/Cancel layout).
- Cancel button's `onCancel()` handler rewritten per FR2 (no
  `cancelRestore` call; add `statusHook` field + guard).

#### `platform/fabric-26.1` (`de.lazuli.cloudsync.WorldRestoreScreen`)
- Uses the 26.x renamed/refactored `GuiGraphicsExtractor`/
  `extractRenderState` rendering model (per `minecraft.md`'s
  cross-version table) -- confirm exact method names against this
  module's own resolved Minecraft jar during planning/implementation
  (not guessed here), following the same pattern already used
  elsewhere in this platform's own `WorldsPanel.java` (`guiGraphics.text`,
  `guiGraphics.centeredText`).
- Same title string, same four additional text draws, same Cancel
  semantics as 1.21.11.

#### `platform/fabric-26.2` (`de.lazuli.cloudsync.WorldRestoreScreen`)
- Same GUI API family as `fabric-26.1` (both already share the same
  `guiGraphics.text`/`centeredText` calls per the earlier grep of their
  `WorldsPanel.java`); same title string, same four additional text
  draws, same Cancel semantics.

### Threading

Unchanged from today: `RestoreProgressListener` callbacks may arrive
from `CloudSyncWorker`'s background thread; the screen only ever reads
the latest snapshot via `AtomicReference`, never blocking the render
thread (existing `WorldRestoreScreen` javadoc, kept verbatim as the
governing rule). `DownloadProgressPresenter.onProgress`/`currentStats`
are only ever called from the render thread in this feature (the
screen remains the single place bridging background-thread snapshots
to render-thread presentation); the presenter itself is not required to
be thread-safe as a result.

## UI

- Title bar: `Downloading '<world_name>' from Steam Cloud...`
  (verbatim per the user's request, including the single-quotes around
  the world name).
- Below title: existing filled-rectangle progress bar
  (`context.fill`-based on 1.21.11; platform-equivalent on 26.x), now
  driven by `DownloadDisplayStats.overallFraction()` (FR3.2) instead of
  a single phase's raw fraction.
- Below the bar, three lines of centered text (top to bottom):
  1. Percentage, e.g. `"42%"`.
  2. Size, e.g. `"42.3 MB / 118.0 MB"`.
  3. ETA, e.g. `"About 12s remaining"` / `"Calculating..."`.
- Cancel button: unchanged position/size/label from today's
  `WorldRestoreScreen` (`ButtonWidget.builder(Text.literal("Cancel"),
  ...)`), only its click handler's behavior changes (FR2).
- Failure state: unchanged from today -- if `failureReason` is set,
  replace the whole progress display with the existing red "Restore
  failed: <reason>" text (still shown live if the player is still
  watching; if they've already navigated away via Cancel, this text is
  never seen -- only the FR6.4 log fires).

## Configuration

No new user-facing configuration. No changes to
`SteamCloudSyncConfig`/`SteamCloudSyncConfigIO`.

## Events

No new event bus / callback types beyond what's listed in Public API
(`RestoreProgressListener` reused as-is). The new
"cancel-but-continuing" transition is not a distinct callback -- it is
simply the screen discarding its own reference to the listener context
while the already-running background listener continues independently
(FR2.2/FR2.3).

## Networking

Unchanged. Still routes exclusively through
`WorldArchiveCloudStore.beginAsyncRead`
(`SteamRemoteStorageWorldArchiveStore` in production, backed by
steamworks4j's `fileReadAsync`/`fileReadAsyncComplete` chunked loop,
1 MB `READ_CHUNK_BYTES`). No new network/Cloud calls are introduced by
this feature; it only changes what is displayed and how Cancel behaves
around the same existing calls.

## Persistence

Unchanged. Still extracts into a `.tmp-restore-<slug>` staging
directory under the saves folder and atomically `Files.move`s it into
place only once every zip entry has extracted without error
(`WorldRestoreService.extractAndFinish`, FR6.12 of the original
steam-cloud-sync spec). A background-continuing download (FR2) uses
this exact same staging/atomic-move path -- no new persistence
behavior, since the underlying service call was already designed to
run to completion independent of any UI.

## Compatibility

- `WorldConflictScreen`'s "Keep Cloud" restore flow is unaffected: it
  does not construct the new/modified `WorldRestoreScreen` at all, and
  its own inline progress UI, `markDownloadPending`/`markDownloadFinished`
  usage, and lack of a real Cancel button are all untouched.
- Any out-of-tree `WorldSyncStatusHook` implementer predating this
  feature keeps compiling: `markDownloadPending`/`markDownloadFinished`
  already have no-op default methods (existing
  `WorldSyncStatusHook.java:102-116`); this feature is simply a second,
  additional call site for both.
- `WorldRestoreScreen`'s constructor gains one parameter
  (`WorldSyncStatusHook`, nullable) -- this is a source-compatible
  addition only in the sense that it's the same project's own internal
  class with a single call site per platform (`WorldsPanel`); it is not
  a published/external API, so ordinary semver/back-compat concerns
  don't apply, but the one call site per platform must be updated in
  the same change.

## Performance

- `DownloadProgressPresenter.currentStats` must be cheap enough to call
  unconditionally every render frame (it internally gates ETA
  recomputation to once/second, per FR5.2, but percentage/size text can
  cheaply be recomputed every frame since they're pure arithmetic on
  already-known bytes -- no allocation-heavy work beyond building the
  handful of short strings, which is is consistent with what
  `WorldRestoreScreen` already does every frame for its progress bar
  fill computation).
- The 25/50/75/100% milestone logging (FR6.2) must not fire more than
  once per boundary per download (track "last logged milestone" in
  `RestoreContext`), to avoid log spam across many progress callbacks
  crossing the same boundary repeatedly.
- No new background threads: extraction already runs on
  `CloudSyncWorker`'s existing background thread; this feature adds no
  additional concurrency, only changes what happens to already-existing
  threads' callback results once the screen is gone (FR2.2/FR2.3).

## Future Extensions

- A true "Abort Download" action (separate from Cancel/background),
  which would need to actually call `WorldRestoreHook.cancelRestore`
  and clean up any partial local state -- explicitly out of scope here
  (Non-goals) since the user's request defines Cancel as
  background-continue, not abort.
- A persistent (survives navigating around the main menu, or even
  restarting the client mid-download) list of in-progress background
  cloud downloads, e.g. a small toast/notification when a
  background-continuing download completes while the player is
  elsewhere in the main menu. Today, completion is only visible when
  the player next opens/reloads the Worlds tab and sees the world as a
  real (no longer cloud-only) row.
- A windowed/smoothed throughput estimator (rather than FR5.2's simple
  average-since-start) if the average-rate ETA proves visibly jumpy in
  practice, especially right at the `READING_FROM_CLOUD` ->
  `EXTRACTING` phase boundary.
- Supporting multiple simultaneous in-progress cloud-world downloads
  with a combined overview screen, rather than one download screen at a
  time.
