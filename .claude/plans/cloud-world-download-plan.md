# Implementation Plan: Cloud World Download (Play Cloud-Only World)

Spec: `.claude/specs/cloud-world-download-spec.md` (all FR/section references below refer to this file).

## Existing Implementation (repo findings)

- **`api/src/main/java/de/lazuli/api/cloudsync/`** — Minecraft-free interfaces.
  `RestoreProgress` (record: `Phase phase` [`READING_FROM_CLOUD`/`EXTRACTING`],
  `long processedBytes`, `long totalBytes`), `RestoreProgressListener`
  (`onProgress`/`onComplete`/`onFailed`), `RestoreHandle`, `WorldRestoreHook`
  (`beginRestore`/`cancelRestore`), `WorldSyncStatusHook` (has
  `markDownloadPending`/`markDownloadFinished`/`isDownloadInProgress`, all
  default no-ops — see `WorldSyncStatusHook.java:86-117`). No test source set
  exists yet under `api/` but the root `build.gradle` provides JUnit
  5/AssertJ/Mockito to every subproject (confirmed via
  `features/steam-cloud-sync/build.gradle:18` comment and `api` having no
  build.gradle of its own — it inherits root config), so adding
  `api/src/test/java/...` is a zero-new-dependency addition.

- **`features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldRestoreService.java`**
  — implements `WorldRestoreHook`. Constructor takes `WorldArchiveCloudStore`,
  `WorldSyncPreferenceService`, `CloudSyncWorker`, `savesDirectory`,
  `warningLogger`, `infoLogger` (both `Consumer<String>`). `beginRestore`
  looks up archive size via `archiveStore.fileSize(...)`, logs
  `"Downloading world \"<slug>\" (<n> bytes) from Steam Cloud."`
  (line 124, FR6.1 — keep as-is), creates a `RestoreContext` (private static
  nested class: `worldSlug`, `listener`, `archiveBuffer`, `volatile boolean
  cancelled`) stored in `activeRestores` (`ConcurrentHashMap<String,
  RestoreContext>`), then calls `archiveStore.beginAsyncRead`. `onChunk`
  reports `RestoreProgress(READING_FROM_CLOUD, processed, totalSize)`.
  `onComplete` hands off to `worker.submitBackgroundWork(() ->
  extractAndFinish(...))`. `extractAndFinish` (line 163) computes
  `totalUncompressed` via `estimateUncompressedSize`, streams zip entries
  into a `.tmp-restore-<slug>` staging dir, reports
  `RestoreProgress(EXTRACTING, processed, totalUncompressed)` per entry,
  atomically `Files.move`s into place, calls
  `preferenceService.markEnabledAfterRestore`, logs
  `"Downloaded and restored world \"<slug>\" from Steam Cloud."` (line 217,
  FR6.3 — keep as-is), then `listener.onComplete`. Failure path (catch block,
  line 219) logs via `warningLogger` (FR6.4 — keep as-is) then
  `listener.onFailed`. `cancelRestore` just flips `context.cancelled = true`
  (checked in the chunk/extract loops) — **this method must NOT be called by
  the new screen's Cancel** per FR2.2.

- **Per-platform `de.lazuli.cloudsync.WorldRestoreScreen`** (near-identical
  across `platform/fabric-1.21.11`, `platform/fabric-26.1`,
  `platform/fabric-26.2` — the 26.1 and 26.2 copies are currently
  byte-for-byte identical). Each: extends `Screen`, constructor
  `(CloudOnlyWorldSummary summary, WorldRestoreHook restoreHook, Runnable
  onReturn)`, title `"Restoring " + summary.displayName()`, one Cancel
  `ButtonWidget`/`Button` at `width/2-50, height/2+40, 100, 20`, an
  `AtomicReference<RestoreProgress> latestProgress`, an
  `AtomicReference<String> failureReason`, a `volatile boolean completed`,
  and a `RestoreHandle handle`. `init()` calls
  `restoreHook.beginRestore(summary.worldSlug(), listener)` where the
  anonymous listener sets `latestProgress`/`completed`/`failureReason`.
  Render method (`render(DrawContext,...)` on 1.21.11;
  `extractRenderState(GuiGraphicsExtractor,...)` on 26.1/26.2) draws a status
  line, a manually-filled progress-bar rectangle (`barWidth=200`, `barX =
  width/2 - barWidth/2`, `barY = height/2`, height 10px, background
  `0xFF555555`, fill `0xFF33AA33`), and on `completed` calls `onReturn.run()`.
  `onCancel()` (bottom of file) currently calls
  `restoreHook.cancelRestore(handle)` then `onReturn.run()` — this is exactly
  what FR2.2 says must change (drop the `cancelRestore` call).
  1.21.11 uses `net.minecraft.client.gui.DrawContext` /
  `context.drawCenteredTextWithShadow` / `context.fill` /
  `ButtonWidget.builder`. 26.1/26.2 use
  `net.minecraft.client.gui.GuiGraphicsExtractor` /
  `guiGraphics.centeredText(Minecraft.getInstance().font, ...)` /
  `guiGraphics.fill` / `Button.builder`.

- **`de.lazuli.cloudsync.WorldConflictScreen`** (per platform, "Keep Cloud"
  path, proven ownership model per spec Goal 2) — `onKeepCloud()` (see
  1.21.11 copy, lines 267-296): calls `statusHook.markDownloadPending(worldSlug)`
  (guarded by `if (statusHook != null)`) **before** `beginRestore`, and calls
  `statusHook.markDownloadFinished(worldSlug)` in **both** `onComplete` and
  `onFailed` of the listener passed to `beginRestore` — never calls
  `cancelRestore` and has no real Cancel button. This is the exact
  pending/finished bracketing pattern the new screen must replicate. Also
  note: `WorldConflictScreen`'s constructor already takes a nullable
  `WorldSyncStatusHook statusHook` as a plain field — same nullable-field
  pattern to copy into `WorldRestoreScreen`.

- **`WorldsPanel.openRestoreFlow`** (identical shape across all 3 platforms,
  see `platform/fabric-1.21.11/.../mainmenu/WorldsPanel.java:966-976`):
  resolves `WorldRestoreHook` via `WorldRestoreHookHolder.getOrNull()`
  (no-op if null), then `MinecraftClient.getInstance().setScreen(new
  WorldRestoreScreen(summary, restoreHook, () -> { reload();
  MinecraftClient.getInstance().setScreen(owner); }))`. This is the one call
  site per platform that must be updated to also resolve and pass
  `WorldSyncStatusHookHolder.getOrNull()` (already imported/used elsewhere in
  the same file, e.g. lines 624, 786, 806, 816, 869, 992 — same import,
  `de.lazuli.WorldSyncStatusHookHolder`, already present in this file per the
  grep). All 3 platforms' `WorldsPanel.java` use this exact
  `WorldSyncStatusHookHolder.getOrNull()` call already, and
  `LazuliMod.LOGGER.info`/`.warn` conventions (e.g. lines 201, 265, 285, 299,
  310, 313, 1040, 1055 in the 1.21.11 copy) — FR6.5's new cancel-continuing
  log must use this same `LazuliMod.LOGGER.info(...)` call style, from
  `WorldRestoreScreen`'s `onCancel()`, which means `WorldRestoreScreen` needs
  a `LazuliMod.LOGGER` import it doesn't currently have (verify per platform;
  the class currently has no `LazuliMod` import).

- **Test precedent**: `features/steam-cloud-sync/src/test/java/.../services/WorldRestoreServiceTest.java`
  uses a hand-written `FakeWorldArchiveCloudStore implements WorldArchiveCloudStore`
  (synchronous `beginAsyncRead` that calls `onChunk`/`onComplete` inline),
  JUnit 5 (`@Test`, `@TempDir`), AssertJ (`assertThat`). This is the pattern
  to extend for milestone-logging assertions. `SteamCloudSyncConfigIOTest.java`
  is the simple-JSON-IO precedent the user's ask cited; `WorldRestoreServiceTest.java`
  is actually the closer/more relevant precedent since it already exercises
  `WorldRestoreService` end-to-end with a fake store.

## Files to Create

1. `api/src/main/java/de/lazuli/api/cloudsync/DownloadProgressPresenter.java`
   — final, Minecraft-free class per spec's Public API section verbatim
   signature:
   - Constructor `DownloadProgressPresenter(long readingTotalBytes, long extractingEstimatedTotalBytes)`.
   - `void onProgress(RestoreProgress progress)` — stores latest
     processed/total bytes per phase (two `volatile`/plain long pairs, no
     threading guarantees needed per spec's Threading section — always
     called from the render thread).
   - `DownloadDisplayStats currentStats(long nowMillis)` — computes FR3.2's
     weighted `overallFraction`, FR1.3 `percentage`, FR3.3 phase-scoped
     `currentSizeText`/`totalSizeText` (via the FR4 byte formatter, recomputed
     every call — cheap), and FR5's `etaText`, gated to recompute the ETA
     value only if `nowMillis - lastEtaUpdateMillis >= 1000` (else return the
     previous ETA string), tracking `startMillis` (set on first `onProgress`
     call or first `currentStats` call) for FR5.2's average-rate calculation.
   - Nested `record DownloadDisplayStats(float overallFraction, int
     percentage, String currentSizeText, String totalSizeText, String
     etaText)` per spec.
   - A package-visible or public static `formatBytes(long bytes)` method
     (FR4.1/FR4.2) — binary units, one decimal place at KB and above, e.g.
     `"512 B"`, `"4.2 KB"`, `"118.0 MB"`, `"1.3 GB"` — exposed as a testable
     static method (mirrors `WorldConflictScreen.valuesMatch`'s
     package-private-static-for-testing precedent).
   - ETA formatting per FR5.3: `"Calculating..."` if <2s of data or rate<=0;
     `"About <N>s remaining"` for <60s; `"About <N>m remaining"` (rounded,
     min "About 1m remaining") for >=60s.

2. `api/src/test/java/de/lazuli/api/cloudsync/DownloadProgressPresenterTest.java`
   — new test source set under `api/` (first test in this module; relies on
   root `build.gradle`'s JUnit5/AssertJ already applying to every subproject
   — confirm at implementation time that `api`'s effective build config
   (root build.gradle's subprojects block) actually wires a `test` source set
   the same way `features/steam-cloud-sync` gets one, since `api` currently
   has zero test files to prove this empirically). Covers: FR3.2's weighted
   combination (monotonic, no backward jump at the phase boundary), FR4's
   `formatBytes` boundary cases (999 B vs 1024 B vs 1 MB vs 1 GB), FR5.3's
   ETA thresholds (Calculating vs seconds vs minutes vs the 1-minute floor),
   and FR5.2's once-per-second gating (two `currentStats` calls <1s apart
   return the identical `etaText`).

## Files to Modify

1. `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldRestoreService.java`
   — internal-only change (no public signature change, per spec's Public
   API section):
   - Add a `lastLoggedMilestone` field to the private `RestoreContext` nested
     class (e.g. `volatile int lastLoggedMilestone` starting at 0).
   - In `onChunk` (READING_FROM_CLOUD progress) and in `extractAndFinish`'s
     per-entry progress emission (EXTRACTING progress), after constructing
     the `RestoreProgress` to hand to `listener.onProgress`, compute the same
     FR3.2 combined-weighted fraction (reading total = `totalSize` from
     `beginRestore`'s closure; extracting total = `totalUncompressed` from
     `extractAndFinish`) and check whether a new 25/50/75/100 boundary was
     crossed vs. `context.lastLoggedMilestone`; if so, update the field and
     call `infoLogger.accept("Cloud world download \"<slug>\": <pct>%
     (<human current> / <human total>).")` (FR6.2) using the same
     human-readable formatter as the presenter — see Dependency note below
     on where that formatter lives so it isn't duplicated.
   - **Design decision needed at implementation time**: FR6.2's milestone log
     needs the *same* combined-fraction math as `DownloadProgressPresenter`
     (FR3.2) and the *same* byte formatter (FR4). Since
     `DownloadProgressPresenter` lives in `api/` and `features/steam-cloud-sync`
     already depends on `api` via `api project(':api')` (`build.gradle:6`),
     `WorldRestoreService` can directly construct/reuse
     `DownloadProgressPresenter`'s static `formatBytes` helper and a small
     shared weighted-fraction helper (either expose the weighting formula as
     a second public static method on `DownloadProgressPresenter`, e.g.
     `static float combinedFraction(long readingProcessed, long readingTotal,
     long extractingProcessed, long extractingTotal, Phase currentPhase)`, or
     just duplicate the ~4-line formula inline with a comment referencing
     FR3.2 — plan recommends the static-method approach to satisfy the
     spec's "single, non-duplicated" Goal 3 literally). This should be
     finalized during implementation, not guessed further here, but the
     interface point is: `WorldRestoreService` takes a compile-time
     dependency on `DownloadProgressPresenter`'s statics, not the other way
     around.
   - No change to `beginRestore`/`cancelRestore` signatures; no change to the
     FR6.1/FR6.3/FR6.4 existing log lines.

2. Per platform — `platform/fabric-1.21.11/src/main/java/de/lazuli/cloudsync/WorldRestoreScreen.java`,
   `platform/fabric-26.1/src/main/java/de/lazuli/cloudsync/WorldRestoreScreen.java`,
   `platform/fabric-26.2/src/main/java/de/lazuli/cloudsync/WorldRestoreScreen.java`
   (modified in place, same class name/package per spec's Public API section
   — not replaced with a new class name):
   - Constructor gains one nullable trailing parameter:
     `WorldRestoreScreen(CloudOnlyWorldSummary summary, WorldRestoreHook
     restoreHook, WorldSyncStatusHook statusHook, Runnable onReturn)` (field
     added, same nullable-field-with-guard pattern as
     `WorldConflictScreen.statusHook`). Add `import
     de.lazuli.api.cloudsync.WorldSyncStatusHook;`.
   - Title changed to `"Downloading '" + summary.displayName() + "' from
     Steam Cloud..."` (FR1.1), replacing `"Restoring " + summary.displayName()`.
   - Add `import de.lazuli.LazuliMod;` (or platform-correct package — confirm
     exact `LazuliMod` FQN per platform at implementation time from existing
     `WorldsPanel.java` imports) for FR6.5's cancel log.
   - Add a `DownloadProgressPresenter presenter` field
     (`import de.lazuli.api.cloudsync.DownloadProgressPresenter;` and its
     nested `DownloadDisplayStats`), constructed in `init()` once the archive
     size is known. **Open question flagged for implementation**: the
     current `beginRestore` call does not give the screen the reading-phase
     total size up front except via the first `onProgress` callback's
     `progress.totalBytes()` for `READING_FROM_CLOUD`, and the
     extracting-phase total only becomes known on the first `EXTRACTING`
     progress callback. Plan: construct `DownloadProgressPresenter` lazily,
     e.g. `presenter = new DownloadProgressPresenter(readingTotal,
     extractingTotalOrReadingTotalAsPlaceholderUntilKnown)`, and have
     `onProgress`'s handling on the render thread (already the pattern: only
     `latestProgress.get()` is read in `render`) update the presenter's
     known totals as each phase's `RestoreProgress.totalBytes()` first
     arrives — this requires `DownloadProgressPresenter.onProgress` to be
     tolerant of a totalBytes value it hasn't seen before per phase (already
     implied by the constructor being called once with both, but the
     "extracting total is estimated late" ordering must be handled either by
     constructing the presenter after first `EXTRACTING` progress notifies a
     total, or by giving `DownloadProgressPresenter` an `updateExtractingTotal(long)`
     method). Resolve exact mechanism during implementation against the
     actual `RestoreProgress` callback ordering in `WorldRestoreService`
     (both totals are in fact knowable in `beginRestore`'s scope --
     `totalSize` immediately, `totalUncompressed` only after the archive is
     fully read in `extractAndFinish` -- so the presenter genuinely cannot be
     fully constructed until then; simplest correct approach: keep
     `presenter` as `null` until the first progress snapshot of each type
     tells us both totals, i.e. construct it lazily on the first
     `RestoreProgress` with `phase() == EXTRACTING` OR reconstruct-in-place
     is unnecessary if `DownloadProgressPresenter`'s constructor is changed
     to accept updates -- final call left to implementation, called out
     explicitly as a risk below).
   - `render`/`extractRenderState`: replace the current single status-line +
     bar with: title (already set via `super(...)`, unchanged rendering call
     otherwise), bar driven by `stats.overallFraction()` instead of the raw
     single-phase fraction, and three new centered text draws below the bar
     (percentage, size text, ETA text) using the same
     `context.drawCenteredTextWithShadow`/`guiGraphics.centeredText` calls
     and centering convention already used, per spec's Per-Platform Screens
     and UI sections (stacked vertical rhythm consistent with existing
     title/bar/Cancel spacing, e.g. bar at `height/2`, then 3 lines at
     `height/2+14`, `+26`, `+38`, Cancel unchanged at `height/2+40` — exact
     pixel offsets to be finalized at implementation time to avoid overlap
     with the Cancel button, not guessed further here).
   - `onCancel()`: remove the `restoreHook.cancelRestore(handle)` call
     (FR2.2); add `LazuliMod.LOGGER.info("Player left the download screen for
     cloud-only world \"" + summary.worldSlug() + "\"; download continues in
     the background.")` (FR6.5) before `onReturn.run()`. Do NOT call
     `statusHook.markDownloadFinished` here — FR2.3 requires that call to
     happen only in the listener's `onComplete`/`onFailed`, which keep firing
     after Cancel since the screen no longer clears `handle`/the listener
     reference (they're owned by `WorldRestoreService`'s `RestoreContext`,
     not the screen, per spec's Architecture section — no code change needed
     to keep them alive, just don't cancel).
   - `init()`: add `statusHook.markDownloadPending(summary.worldSlug())`
     (guarded `if (statusHook != null)`, mirroring `WorldConflictScreen.onKeepCloud`)
     immediately before calling `restoreHook.beginRestore(...)`; in the
     anonymous listener's `onComplete`/`onFailed`, add
     `statusHook.markDownloadFinished(summary.worldSlug())` (same null guard)
     alongside the existing `completed = true;` / `failureReason.set(reason);`
     assignments (FR2.3/FR2.4).
   - fabric-26.1/26.2 note: confirm the exact `GuiGraphicsExtractor` method
     names for drawing multiple stacked centered text lines against this
     module's actually-resolved Minecraft jar before writing the three new
     draw calls (spec explicitly defers this, "confirm exact method names
     against this module's own resolved Minecraft jar during
     planning/implementation... following the same pattern already used
     elsewhere in this platform's own WorldsPanel.java" — implementation
     step, not a planning blocker, since the existing file already
     demonstrates the one call (`guiGraphics.centeredText(...)`) that needs
     to be repeated 3x with different y-offsets and text).

3. Per platform — `platform/fabric-1.21.11/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`,
   `platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`,
   `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
   — `openRestoreFlow` (line ~966 in the 1.21.11 copy; confirm exact line per
   platform, same method name/shape in all three) updated to resolve
   `WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();`
   (same call already used elsewhere in the same file, e.g. lines 624, 786,
   806, 816, 869, 992) and pass it as the new third constructor argument:
   `new WorldRestoreScreen(summary, restoreHook, statusHook, () -> {
   reload(); MinecraftClient.getInstance().setScreen(owner); })`. No other
   change to this method or file.

## Risks

- **Presenter total-bytes lifecycle mismatch** (flagged above): the
  extracting-phase total (`totalUncompressed`) is only known inside
  `WorldRestoreService.extractAndFinish`, not at `beginRestore` call time, so
  `DownloadProgressPresenter`'s single two-arg constructor (as literally
  specified) cannot be fully populated until the first `EXTRACTING`
  `RestoreProgress` arrives. Needs a concrete resolution at implementation
  time (lazy construction vs. mutable total field) — does not block planning
  but is the single largest design ambiguity between the spec's Public API
  sketch and `WorldRestoreService`'s actual data availability timing.
  Whichever approach is chosen must preserve FR3.2's "never jumps
  backwards" guarantee across that transition (e.g. assume `extractingTotal`
  is unknown/0-weighted until first known, per FR3.2's "assumed 0 for a
  phase not yet started" rule, which already covers this case if
  `DownloadProgressPresenter` treats `extractingTotalBytes == 0` as "phase
  not started, weight later revised" rather than dividing by zero).
- **`fabric-26.1`/`fabric-26.2` exact `GuiGraphicsExtractor` text-draw API**
  is asserted from an existing single call site in the same files; adding 3
  more stacked calls is low-risk but must be verified against the actually
  resolved Minecraft jar at implementation time (spec explicitly defers
  this, not a blocker).
- **Milestone-logging duplication risk** (Goal 3's "single,
  non-duplicated... logic"): if `DownloadProgressPresenter`'s
  combined-fraction formula isn't exposed as a reusable static method,
  `WorldRestoreService`'s FR6.2 milestone check risks a second,
  independently-drifting implementation of the same FR3.2 math. Plan calls
  for exposing it as a static helper on `DownloadProgressPresenter`
  (`api` module) that `features/steam-cloud-sync` already can depend on.
- **`LazuliMod` import path differs slightly per platform** (each platform
  module has its own `LazuliMod` class in its own package) — must confirm
  the exact existing import already used in each platform's `WorldsPanel.java`
  rather than assuming one shared FQN across all three platform modules.
- **Test-source-set-doesn't-exist-yet risk for `api/`**: since `api` has no
  existing `src/test/java` tree or its own `build.gradle`, verify (at
  implementation time, one `gradle :api:test` dry run) that the root
  `build.gradle`'s subprojects configuration actually wires JUnit 5 for
  `api` the same way it evidently does for `features/steam-cloud-sync`
  before assuming the new test class will be picked up with zero build
  config changes.
- **Behavioral regression risk to `WorldConflictScreen`**: none expected
  (Non-goals + Compatibility sections both explicitly confirm this screen is
  untouched and only benefits for free from `WorldSyncStatusHook` default
  methods already existing), but verification should include a
  no-diff/`git diff` check on all three `WorldConflictScreen.java` files
  after implementation to confirm they were never touched.

## Dependencies

- No new external (non-Fabric, non-project) dependencies. `DownloadProgressPresenter`
  and its test use only `java.lang`/`java.util` and JUnit 5/AssertJ, both
  already provided project-wide per the root `build.gradle` (confirmed via
  `features/steam-cloud-sync/build.gradle:18`'s comment and existing tests
  under that module using `org.junit.jupiter.api.Test` and
  `org.assertj.core.api.Assertions`). `features/steam-cloud-sync` already
  declares `api project(':api')` (`features/steam-cloud-sync/build.gradle:6`),
  so no build.gradle change is needed for `WorldRestoreService` to reference
  the new `api`-module `DownloadProgressPresenter` class.

## Test Strategy

1. **`DownloadProgressPresenterTest`** (new, `api/src/test/java/...`,
   JUnit 5 + AssertJ, following `WorldRestoreServiceTest`'s style of a
   plain-JVM fake/direct-construction test with no Minecraft/Steamworks
   dependency):
   - FR3.2 weighted combination: feed a sequence of `RestoreProgress`
     snapshots (all `READING_FROM_CLOUD` first, then all `EXTRACTING`) and
     assert `overallFraction`/`percentage` is monotonically non-decreasing
     across the whole sequence, and lands at exactly 100% once `EXTRACTING`
     reports `processedBytes == totalBytes`.
   - FR4 `formatBytes`: table-driven assertions for `0`, `1023`, `1024`,
     `1048576`(1 MB), `1073741824`(1 GB) boundaries, and the exact examples
     from the spec (`"512 B"`, `"4.2 KB"`, `"118.0 MB"`, `"1.3 GB"`).
   - FR5.3 ETA text: `"Calculating..."` before 2s of data; `"About Ns
     remaining"` under 60s; `"About Nm remaining"` at/above 60s with the "About
     1m remaining" floor; verify via a fake/controlled `nowMillis` clock
     rather than real `Thread.sleep`.
   - FR5.2 once-per-second gating: two `currentStats` calls with
     `nowMillis` deltas <1000ms apart yield identical `etaText`; a third call
     >=1000ms later is allowed to differ.
2. **`WorldRestoreServiceTest`** (existing file, extended) — add a test
   using the existing `FakeWorldArchiveCloudStore` pattern asserting the
   FR6.2 milestone log fires exactly once per 25/50/75/100 boundary (inject
   a capturing `Consumer<String> infoLogger` and assert the captured
   messages/count), and a test confirming `cancelRestore` is never
   consulted/needed for this feature's own listener-continues-after-cancel
   behavior (i.e. that calling `beginRestore` then simply dropping the
   caller's reference to the listener still runs `onComplete`, proving the
   background-continuation contract this feature relies on already holds at
   the service layer — this is a pre-existing guarantee, but a regression
   test here documents that this feature depends on it).
3. **Manual/live verification** (no automated UI test harness in this repo
   for `Screen` subclasses, consistent with existing precedent — no test
   files found for any `*Screen.java`): for each of the 3 platforms, launch
   with a cloud-only world, verify title/bar/percentage/size/ETA text render
   and update once per second, press Cancel mid-download, confirm immediate
   return to Worlds tab, confirm the world's row is blocked
   (`isDownloadInProgress`) until the background download completes, confirm
   the world becomes a normal playable row afterward, and confirm the FR6.2/
   FR6.5 log lines appear in the client log in the right order. Also
   manually re-verify the untouched "Keep Cloud" `WorldConflictScreen` flow
   still works unchanged on at least one platform (Compatibility section).
4. **Static verification**: `git diff` review to confirm no changes leaked
   into `WorldConflictScreen.java`, `SteamCloudSyncConfig*.java`,
   `CloudSyncCoordinator.java`, `WorldSaveSyncService.java`, or any
   `CloudOnlyWorlds*` file (all explicitly Non-goals).

## Acceptance Criteria

- All of FR1-FR6 in the spec are satisfied, verified per the Test Strategy
  above; in particular:
  - FR1.1-FR1.7: title/bar/percentage/size/ETA render identically (same
    numbers) on all three platforms; Cancel always present.
  - FR2.1-FR2.5: Cancel returns to the Worlds tab immediately without
    calling `cancelRestore`; the background download completes or fails
    normally with `markDownloadFinished` always eventually called; a second
    Play press on the same slug is blocked while the background download is
    in flight, verified with zero `WorldsPanel` blocked-row-logic changes.
  - FR3.1-FR3.3, FR4.1-FR4.2, FR5.1-FR5.4: covered by
    `DownloadProgressPresenterTest`.
  - FR6.1-FR6.5: FR6.1/FR6.3/FR6.4 log lines unchanged; FR6.2 milestone log
    added and fires exactly once per boundary even with no screen attached;
    FR6.5 cancel-continuing log fires from the screen on Cancel.
- `WorldConflictScreen` (all 3 platforms) is byte-for-byte unmodified.
- No new/changed public method signatures on `WorldRestoreHook`,
  `RestoreHandle`, `RestoreProgressListener`, or `RestoreProgress`.
- `WorldRestoreScreen`'s constructor signature change (new nullable
  `WorldSyncStatusHook` parameter) is applied consistently across all 3
  platforms, and each platform's single `WorldsPanel.openRestoreFlow` call
  site is updated to match.
- All new/modified files compile per-platform (`fabric-1.21.11`,
  `fabric-26.1`, `fabric-26.2` each build independently) and existing test
  suites (`WorldRestoreServiceTest`, `SteamCloudSyncConfigIOTest`, etc.)
  continue to pass unmodified except for the one new milestone-log test
  added to `WorldRestoreServiceTest`.
