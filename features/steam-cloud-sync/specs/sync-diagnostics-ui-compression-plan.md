# Implementation Plan — Diagnostics, Per-World Sync-Status UI, Compression

Companion to `features/steam-cloud-sync/specs/sync-diagnostics-ui-compression-spec.md`
(requirement IDs FRD.x / FRU.x / FRC.x referenced throughout). This plan is intentionally
brief: the spec already pins exact file/line references from direct code reading, and this
plan trusts those citations rather than re-deriving them, adding only the few extra
call-site details (below) needed to sequence edits precisely.

## Existing Implementation

- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncService.java`
  - Constructor at lines 101-122 takes `archiveStore`, `cloudFileStore`, `preferenceService`,
    `worker`, `fingerprintCachePath`, `deviceLabel`, `maxWorldArchiveSizeMb`,
    `allowSelectiveFallback`, `warningLogger` (`Consumer<String>`), `playerNotifier`
    (`Consumer<String>`) — no status-tracker collaborator today.
  - `syncWorldNow` (210-246): `SyncStrategy.SKIPPED` early return at 215-219;
    `buildWholeArchive`/`buildSelectiveArchive` calls at 221-223; `streamWrite` call and
    branch at 236-241 (`written` true → `updateFingerprint` at 238; false → generic
    `warningLogger.accept("Failed to sync world \"" + displayName + "\" to Steam Cloud.")`
    at 240); `catch (IOException e)` at 243-245.
  - `ensureQuota(int neededBytes, String worldSlugBeingWritten)` (302-324): guard at line
    305 is a single combined condition,
    `if (!archiveStore.getQuota(total, available) || available[0] >= neededBytes) { return; }`
    — **note for implementation**: this differs slightly in shape from the spec's
    line-305-307 framing (spec describes it as if the two sub-cases were already separable);
    FRD.1 requires splitting this into two branches (`getQuota` returned `false` → log and
    `return`; `getQuota` true and quota sufficient → silent `return`, unchanged) before the
    eviction loop at 313-323, which is otherwise unchanged. Candidate count for FRD.2 is the
    `candidates` list size (line 309-311) or a running `evicted` counter incremented at the
    `archiveStore.forget(...)` success branch (317-322) — plan recommends the latter (exact
    "how many were evicted" per FRD.2's wording, not "how many were candidates").
  - `buildWholeArchive`/`buildSelectiveArchive` (248-280): each opens
    `new ZipOutputStream(buffer)` in a `try`-with-resources (line 250 / 262) with no
    `setLevel`/`setMethod` call anywhere in the file — confirms spec's FRC current-behavior
    claim exactly.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/SteamRemoteStorageWorldArchiveStore.java`
  - `streamWrite(String fileName, byte[] data)` (104-133): three failure paths confirmed at
    the spec's cited lines — stream-open rejected (108), chunk-write-failed/cancelled
    (127, with local `offset` variable already tracked at 113/120), `RuntimeException`
    (130). `data.length` is already in scope at all three sites via the method parameter; no
    signature change needed per FRD.3.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncPreferenceService.java`
  — reference shape for the new tracker class: plain constructor-injected class, no
  Minecraft imports, `synchronized` methods over an in-memory `Map`, implements an
  `api/cloudsync` hook interface. `WorldSyncStatusTracker` should mirror this shape
  (in-memory `ConcurrentHashMap<String, ...>` is sufficient given FRU.3's "no persistence,
  session-only" requirement and the background-thread + tick-thread writers involved, so a
  concurrent map is safer than `WorldSyncPreferenceService`'s `synchronized`-methods-over-
  `LinkedHashMap` approach, since `WorldSaveSyncService` calls into it from both
  `CloudSyncWorker`'s background thread and `worker.enqueueTickThreadWork`'s tick-thread
  callback).
- `api/src/main/java/de/lazuli/api/cloudsync/WorldSyncToggleHook.java` (20-38): existing
  sibling interface shape to mirror for the new `WorldSyncStatusHook` — package
  `de.lazuli.api.cloudsync`, zero Minecraft imports.
- Per-platform static holder pattern, confirmed via
  `platform/fabric-26.1/src/main/java/de/lazuli/WorldSyncToggleHookHolder.java` (and its
  `fabric-1.21.11`/`fabric-26.2` siblings): `private static volatile T instance`,
  `publish(T)`, `getOrNull()`. `WorldSyncStatusHookHolder` mirrors this exactly, one copy per
  platform module, published from each `SteamCloudSyncClientInitializer.java` alongside the
  existing `WorldSyncToggleHookHolder.publish(...)` call.
- Row-icon mixin pattern, confirmed via
  `platform/fabric-26.1/src/main/java/de/lazuli/mixin/WorldListEntrySyncIconMixin.java`
  (`fabric-26.2` is structurally identical; `fabric-1.21.11`'s
  `WorldEntrySyncIconMixin.java` targets an older render-method name/signature but the same
  shape): `@Inject(method = "extractContent", at = @At("TAIL"))` draws via
  `graphics.fill(...)` (a `GuiGraphicsExtractor`, not vanilla `DrawContext`, on 26.1/26.2);
  reads world identity via `WorldListEntryReflection.getLevelSummary(this)` (not `@Shadow`,
  per the class-level doc comment explaining a prior crash — same reflection helper must be
  reused, not re-derived, for the status hook lookup); click handling is a separate
  `@Inject(method = "mouseClicked", ...)`. `ICON_SIZE = 8`, `ICON_MARGIN = 4`,
  `COLOR_ENABLED = 0xFF3399FF`, `COLOR_DISABLED = 0xFF808080` are private constants local to
  this class today (FRU.5 needs the toggle icon's left edge, computed today via
  `lazuli$iconLeft()` at line 90-92 — the new status icon reuses the same
  `WorldListEntryReflection.getX/getWidth` calls, offset one more `ICON_MARGIN + ICON_SIZE`
  to the left).
  - No existing tooltip-rendering call exists anywhere in this codebase (`grep` for
    `[Tt]ooltip` across the module found nothing, per the spec's own investigation) — this
    is a genuinely new API surface for this mixin's render method. **Must verify at
    implementation time**, per version, via `javap` against the exact
    `GuiGraphicsExtractor`/vanilla `DrawContext`/`GuiGraphics` class shipped for that
    platform module: the correct method to queue a tooltip from inside a Pattern-3 `@Inject`
    (likely a `setTooltipForNextFrame(...)`/`drawTooltip(...)`-family method taking
    `Font`/`List<Component>`/mouse coords, exact name TBD per Minecraft version) — this is
    listed as a Risk below, not resolved by this plan.
- `.claude/context/ui-guidelines.md:145-203` (Pattern 3) and `:188-192` (fixed-square-color
  precedent), `:199-201` (per-version API verification discipline) — process precedent this
  plan's mixin/tooltip steps follow; not re-quoted here.
- Reference-only, **do not touch**: `features/server-browser/*`, `features/friends-sidebar/*`
  (per parent-task instruction; also per spec's own note that friends-sidebar has no
  tooltip precedent to borrow and an unrelated in-progress dropdown-widget effort).

## Files to Create

- `api/src/main/java/de/lazuli/api/cloudsync/WorldSyncStatusHook.java` — new interface:
  `SyncStatus` enum (`NOT_SYNCED`, `SYNCED`, `SYNC_ERROR`, `SKIPPED_TOO_LARGE`) either nested
  or as a sibling `SyncStatus.java` in the same package (implementation's call; either
  satisfies FRU.1/FRU.2), `SyncStatus statusFor(String worldSlug)`,
  `String lastErrorFor(String worldSlug)` (nullable, non-null only when status is
  `SYNC_ERROR`). Zero Minecraft imports, matching `WorldSyncToggleHook.java`'s convention.
- `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/WorldSyncStatusTracker.java`
  — new plain-JVM class implementing `WorldSyncStatusHook`; in-memory
  `ConcurrentHashMap<String, SyncStatus>` plus `ConcurrentHashMap<String, String>` for
  last-error text; package-visible mutator methods (e.g. `markSynced(String)`,
  `markError(String, String message)`, `markSkippedTooLarge(String)`) called by
  `WorldSaveSyncService` at the four FRU.3 checkpoints; `statusFor`/`lastErrorFor` default to
  `NOT_SYNCED`/`null` for any unseen slug.
- `platform/fabric-1.21.11/src/main/java/de/lazuli/WorldSyncStatusHookHolder.java`
- `platform/fabric-26.1/src/main/java/de/lazuli/WorldSyncStatusHookHolder.java`
- `platform/fabric-26.2/src/main/java/de/lazuli/WorldSyncStatusHookHolder.java`
  — three copies mirroring `WorldSyncToggleHookHolder.java` exactly (`publish`/`getOrNull`
  over a `volatile WorldSyncStatusHook`).
- `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldSyncStatusTrackerTest.java`
  — new plain-JVM unit test for the tracker (see Test Strategy).

## Files to Modify

- `WorldSaveSyncService.java`
  - Constructor (101-122): add a new `WorldSyncStatusTracker statusTracker` parameter
    (`Objects.requireNonNull`), stored as a field, appended after `playerNotifier` to match
    the existing trailing-collaborator convention.
  - `ensureQuota` (302-324): split line 305's combined condition; add FRD.1's log line on
    `getQuota` failure; add FRD.2's post-eviction-loop log line (with an `evicted` counter
    introduced in the loop at 313-323); no change to eviction behavior itself (Non-goals).
  - `syncWorldNow` (210-246):
    - `SyncStrategy.SKIPPED` branch (215-219): add `statusTracker.markSkippedTooLarge(worldSlug)`
      alongside the existing `playerNotifier.accept(...)` call, unchanged wording.
    - `streamWrite` branch (236-241): on `written == true`, no tracker change beyond
      `statusTracker.markSynced(worldSlug)` alongside `updateFingerprint(...)`; on `false`,
      compute the FRD.4 revised message once, pass it to both
      `warningLogger.accept(...)` and `statusTracker.markError(worldSlug, message)` (FRU.3:
      "reusing the improved message, not inventing a second copy").
    - `catch (IOException e)` (243-245): add `statusTracker.markError(worldSlug, e.getMessage())`
      (or a wrapped equivalent) alongside the existing `warningLogger.accept(...)` call.
  - `buildWholeArchive`/`buildSelectiveArchive` (248-280): add
    `zip.setLevel(Deflater.BEST_COMPRESSION);` immediately after each
    `new ZipOutputStream(buffer)` construction (lines 250, 262); add
    `import java.util.zip.Deflater;`.
- `SteamRemoteStorageWorldArchiveStore.java`
  - `streamWrite` (104-133): revise the three `warn(...)` calls at 108, 127, 130 to include
    `data.length` (all three) and `offset` (127 only), per FRD.3's exact message-shape
    guidance. No signature change.
- `platform/fabric-1.21.11/.../mixin/WorldEntrySyncIconMixin.java`,
  `platform/fabric-26.1/.../mixin/WorldListEntrySyncIconMixin.java`,
  `platform/fabric-26.2/.../mixin/WorldListEntrySyncIconMixin.java`
  — each: add `WorldSyncStatusHookHolder`/`WorldSyncStatusHook`/`SyncStatus` imports; in the
  render inject, after the existing toggle-icon `fill(...)` call, look up
  `WorldSyncStatusHookHolder.getOrNull()`, compute `status = hook.statusFor(worldSlug)`,
  draw a second 8x8 `fill(...)` one `ICON_MARGIN + ICON_SIZE` to the left of the toggle icon
  when `status != NOT_SYNCED`, using the FRU.4 color constants
  (`COLOR_SYNCED = 0xFF33CC33`, `COLOR_SYNC_ERROR = 0xFFCC3333`,
  `COLOR_SKIPPED = 0xFFCCAA33`); when `mouseX`/`mouseY` (already available as inject
  parameters on the render hook) are within the new icon's bounds and status is
  `SYNC_ERROR`/`SKIPPED_TOO_LARGE`, queue a tooltip via whichever per-version API is
  confirmed at implementation time (see Risks).
- `platform/fabric-1.21.11/.../SteamCloudSyncClientInitializer.java`,
  `platform/fabric-26.1/.../SteamCloudSyncClientInitializer.java`,
  `platform/fabric-26.2/.../SteamCloudSyncClientInitializer.java`
  — each: construct one `WorldSyncStatusTracker`, pass it into the existing
  `WorldSaveSyncService` constructor call, and add
  `WorldSyncStatusHookHolder.publish(tracker)` alongside the existing
  `WorldSyncToggleHookHolder.publish(...)` call.
- `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldSaveSyncServiceTest.java`
  — update every existing `new WorldSaveSyncService(...)` construction site to pass a
  `WorldSyncStatusTracker` (or a lightweight fake `WorldSyncStatusHook`-backed test double,
  implementation's call), plus new assertions per Test Strategy.
- `features/steam-cloud-sync/src/test/java/de/lazuli/features/steamcloudsync/services/WorldRestoreServiceTest.java`
  — no behavior change expected (FRC.2/FRC.3), but re-run to confirm an archive built with
  `Deflater.BEST_COMPRESSION` still round-trips through the existing reader unchanged; add
  one new case if the existing fixtures don't already build via `WorldSaveSyncService`'s
  real archive-building methods (implementation to confirm during Existing Implementation
  re-check of this test file, not re-derived here).

## Sequencing / Dependencies

FRD, FRU, and FRC are independent of each other (per the parent task's framing) and can be
implemented/verified in any order or in parallel. Suggested order, lowest-risk first:

1. **FRC** (compression) — two-line change, zero new classes, zero new public API, easiest
   to verify in isolation (existing `WorldRestoreServiceTest` round-trip coverage). No
   dependency on FRD/FRU.
2. **FRD** (logging) — confined to the two existing service classes, no new class, no new
   public API surface beyond message text. No dependency on FRC/FRU, though FRU.3's
   `SYNC_ERROR` tracking is defined to reuse FRD.4's message text, so **FRD should land
   before or together with FRU's `streamWrite`-failure branch** to avoid FRU temporarily
   duplicating a soon-to-change message string.
3. **FRU** (status UI) — largest surface (new `api` interface, new service class, three new
   holder classes, three mixin edits, three initializer edits). Internally: land the new
   `api`/tracker/holder classes and `WorldSaveSyncService` wiring first (testable without
   touching Minecraft/mixin code at all, per FRU.3's plain-JVM-testability goal), then the
   three per-platform mixin/initializer edits last, once the tooltip-API risk (below) is
   resolved for at least one platform module and can be replicated to the other two.

## Risks

- **Tooltip-rendering API unknown per Minecraft/Fabric version (FRU.4).** No existing call
  in this codebase establishes the pattern (confirmed by the spec's own grep and this plan's
  re-read of the mixin files). The render inject is `@Inject(method = "extractContent", ...)`
  operating on a `GuiGraphicsExtractor` (26.1/26.2) or an as-yet-unread equivalent on
  1.21.11 — the correct tooltip-queuing method name/signature must be confirmed via `javap`
  per platform module before the tooltip half of FRU.4 can be implemented; if the method
  requires a `Font`/screen-reference not available inside this `@Inject`'s parameter list,
  a `@Shadow`/reflection workaround (mirroring the `WorldListEntryReflection` pattern
  already used for `getX`/`getY`/`getWidth`/`getLevelSummary`) may be needed, in the same
  style as the documented `@Shadow`-crash fix already recorded in this mixin's class
  comment. Mitigation: implement/verify tooltip rendering on one platform module first
  (recommend `fabric-26.1`, the module with the most recent, best-documented API), replicate
  once confirmed; if no in-inject tooltip API is viable on a given version within reasonable
  effort, the fallback (not preferred, flag if hit) is a colored-square-only icon with no
  tooltip on that version, which still satisfies FRU.4's icon-color requirement even if the
  tooltip sub-requirement needs a follow-up spec for that one version.
- **Cross-thread writes into `WorldSyncStatusTracker`.** `WorldSaveSyncService` calls it
  from both `CloudSyncWorker`'s background thread (`SKIPPED_TOO_LARGE`, `IOException` catch)
  and the tick-thread callback passed to `worker.enqueueTickThreadWork` (`SYNCED`/
  `SYNC_ERROR`), while the mixin's render inject reads it from the render thread. Mitigation:
  `ConcurrentHashMap`-backed tracker (no external synchronization needed for simple put/get);
  called out explicitly in Files to Create so implementation doesn't default to
  `WorldSyncPreferenceService`'s `synchronized`/`LinkedHashMap` shape, which is unnecessary
  here and would work but is a needless behavioral mismatch to copy without thinking.
- **`ensureQuota`'s existing single-line condition (line 305) doesn't cleanly split** without
  care to preserve the untouched "quota sufficient" silent-`return` sub-case exactly as
  today — flagged explicitly in Existing Implementation above so this isn't rediscovered
  mid-implementation as a surprise.
- **FRC's CPU-cost trade-off is accepted as final by the spec** (Non-goals, Performance) —
  no risk to mitigate, listed only so verification doesn't flag "level 9 is slower" as a
  regression; it is expected and intentional.
- **`WorldSaveSyncServiceTest` construction-site churn.** Adding a constructor parameter
  breaks every existing `new WorldSaveSyncService(...)` call in that test file — mechanical
  but must not be missed; Files to Modify already lists this file explicitly.

## Dependencies

- No new external (non-Fabric, non-JDK) dependency for any of FRD/FRU/FRC. FRC uses
  `java.util.zip.Deflater`, already transitively available via `java.util.zip.ZipOutputStream`
  (already imported in `WorldSaveSyncService.java`) — part of the JDK standard library, no
  Maven coordinate to verify. FRD/FRU add no library, only new classes/interfaces within
  this repo's own `api`/`features`/`platform` modules.

## Test Strategy

- **FRD** — extend `WorldSaveSyncServiceTest` (existing fake/stub collaborators, per its
  current structure) with cases asserting: (a) `warningLogger` receives the FRD.1 message
  when a fake `archiveStore.getQuota` returns `false`; (b) `warningLogger` receives the
  FRD.2 message, with correct `neededBytes`/`available`/`total`/evicted-count values, when
  eviction leaves quota insufficient; (c) the FRD.4 revised generic failure message includes
  `archiveBytes.length`. Extend a `SteamRemoteStorageWorldArchiveStore`-level test (or add
  one if none exists covering `streamWrite`'s failure branches directly — confirm at
  implementation time) asserting the three FRD.3 message variants include `data.length`
  and, for the chunk-failure path, `offset`.
- **FRU** — new `WorldSyncStatusTrackerTest` (plain JVM, no Minecraft/mixin dependency, per
  FRU.3's stated testability goal): default status is `NOT_SYNCED` for an unseen slug;
  `markSynced`/`markError`/`markSkippedTooLarge` each produce the expected `statusFor`/
  `lastErrorFor` result; a later call for the same slug overwrites the earlier one (session
  "last attempt" semantics). Extend `WorldSaveSyncServiceTest` with cases asserting the
  tracker receives the right call at each of the four FRU.3 checkpoints (skip, write-success,
  write-failure, IOException), using the same fake-collaborator pattern already used for
  `warningLogger`/`playerNotifier` assertions in that file. Mixin/tooltip rendering itself
  is not unit-testable in this codebase's existing setup (no headless-render test harness
  observed) — verification here is manual/in-game per the Verification phase, not automated;
  note this explicitly rather than silently skipping.
- **FRC** — extend `WorldRestoreServiceTest` (or confirm existing coverage already exercises
  this) with a round-trip case: build an archive via `WorldSaveSyncService`'s real
  `buildWholeArchive`/`buildSelectiveArchive` (now writing at level 9) and confirm
  `WorldRestoreService.extractAndFinish` reads it back byte-identical to the pre-change
  behavior; assert the compressed byte count is smaller than an equivalent archive built at
  the previous default level (sanity-check that `setLevel` actually took effect, not just
  that reading still works).
- Full module test suite (`features/steam-cloud-sync`, plus the three `platform/*` modules
  for compile-correctness of the new mixin/initializer edits) must pass before handoff to
  verification.

## Acceptance Criteria

- FRD.1: `ensureQuota` logs a specific `getQuota`-failure message (via `warningLogger`) when
  `archiveStore.getQuota(...)` returns `false`; the "quota sufficient" sub-case still logs
  nothing.
- FRD.2: after the eviction loop, if quota is still insufficient, exactly one log line fires
  containing world slug, `neededBytes`, final `available`, final `total`, and evicted count.
- FRD.3: all three `streamWrite` failure log lines include `data.length`; the chunk-write
  failure line additionally includes the `offset` reached.
- FRD.4: the generic `syncWorldNow` failure line includes `archiveBytes.length` and points
  the reader at the preceding specific-cause line.
- FRD.5: no new logging dependency or log-level distinction introduced; all new lines go
  through the existing `warningLogger`/`warn(...)` seams.
- FRU.1: `SyncStatus` has exactly the four named states, no more, no fewer.
- FRU.2: `WorldSyncStatusHook` is a new interface (not a `WorldSyncToggleHook` change) with
  `statusFor`/`lastErrorFor`, implemented by a class the composition root wires in.
- FRU.3: each of the four checkpoints (write-success, write-failure, skip-too-large,
  build-IOException) updates the tracker exactly as specified; state is in-memory only, not
  persisted across restart; `SYNC_ERROR`'s message is the same string FRD.4 produces, not a
  second independently-authored copy.
- FRU.4: in-game, a world row shows no second icon when `NOT_SYNCED`; green/red/amber icons
  for `SYNCED`/`SYNC_ERROR`/`SKIPPED_TOO_LARGE` respectively; hovering the icon in the
  `SYNC_ERROR`/`SKIPPED_TOO_LARGE` states shows the expected tooltip text; no tooltip in the
  other two states.
- FRU.5: the new icon sits immediately left of the existing toggle icon, same size, no
  visual overlap, on all three supported Minecraft versions.
- FRU.6: all three per-version mixins and all three `SteamCloudSyncClientInitializer.java`
  copies are updated consistently; no new mixin class introduced (both icons drawn from the
  same existing inject method).
- FRC.1: `zip.setLevel(Deflater.BEST_COMPRESSION)` is called in both archive-building
  methods immediately after `ZipOutputStream` construction; no config knob added.
- FRC.2/FRC.3: `WorldRestoreService` requires no code change; an archive built under the new
  level round-trips correctly through the existing unmodified reader.
- FRC.5: `SteamRemoteStorageWorldArchiveStore.streamWrite`'s chunking logic is untouched.
- No public method signature of `WorldSaveSyncService`, `SteamRemoteStorageWorldArchiveStore`,
  `WorldRestoreService`, or `WorldSyncPreferenceService` changes (only new constructor
  parameters/new classes), per the spec's Public API section.
- `features/server-browser` and `features/friends-sidebar` are untouched by this
  implementation.
