# Steam Cloud Sync — Diagnostics, Per-World Sync-Status UI, and Archive Compression

Companion spec to `features/steam-cloud-sync/specification.md` (Group 6, world-save sync).
No `specs/` sub-directory convention existed in this module before this document; it is
introduced here to hold follow-up specs scoped narrower than the whole-feature
`specification.md`, without renumbering that file's own FR IDs. This document's own
requirements are numbered independently as **FRD (log Diagnostics)**, **FRU (UI)**, and
**FRC (Compression)**.

## Overview

Root cause of the "Steam Cloud world sync silently not working" bug is confirmed: the
Spacewar test App ID's default Steam Cloud quota is a few KB (visible directly via the
`isCloudEnabledForAccount`/`isCloudEnabledForApp`/`getQuota` diagnostic logged once at
construction in `SteamRemoteStorageWorldArchiveStore` — `features/steam-cloud-sync/src/main/java/de/lazuli/features/steamcloudsync/services/SteamRemoteStorageWorldArchiveStore.java:82-96`).
That diagnostic only ever reaches the console/log file. This spec covers three follow-up
improvements: (1) making a quota/write failure loud and specific in the log at the moment
it actually happens (not just at construction), (2) surfacing per-world sync status —
including error state — directly to the player in the Singleplayer world list, and (3)
strengthening world-archive compression, with an explicit backward-compatible read path
for archives already written in the current (uncompressed-`ZipOutputStream`-default)
format.

## Goals

- A player or developer reading the log after a failed sync can identify, without
  attaching a debugger, whether the failure was quota-related and, if so, exactly how many
  bytes were needed vs. available.
- A player can see, without reading any log, whether a given local world last synced
  successfully, has never synced, failed, or was skipped for being too large — and get a
  short reason if it failed — from the Singleplayer world list itself (the surface already
  used for the sync on/off toggle icon).
- World archives sent to Steam Cloud take meaningfully less quota than today's
  zero-compression default, without breaking the ability to read back a world archive that
  was already written to Cloud under the current format before this change ships.

## Non-goals

- A dedicated "Cloud Sync" settings/status screen — status stays scoped to the existing
  per-world row on the Singleplayer screen (Non-goals precedent, `specification.md:20`).
- Retroactively re-uploading/re-compressing already-synced archives to reclaim quota —
  migration is read-only backward compatibility, not a background re-compression pass
  (Future Extensions).
- Any change to the quota-shortfall `fileForget` eviction policy in
  `WorldSaveSyncService.ensureQuota` (`WorldSaveSyncService.java:302-324`) — this spec only
  changes what gets logged/surfaced around that existing behavior, not the behavior itself.
- Player-facing exposure of the construction-time Cloud-enablement diagnostic
  (`isCloudEnabledForAccount`/`isCloudEnabledForApp`) — that remains a log-only developer
  diagnostic; it fires once at startup, before any specific world's sync attempt, so there
  is no natural per-world UI surface for it.
- Choosing a specific real compression ratio/target — "heavily archived" is treated as "use
  `Deflater`'s maximum level," not a numeric target, since actual ratio is data-dependent
  (see FRC.1).
- A user-configurable compression level. FRC.1's `Deflater.BEST_COMPRESSION` (level 9) is a
  fixed constant, decided as final; no `SteamCloudSyncConfig` knob is in scope for this or
  any follow-up spec unless a future spec explicitly reopens this decision (see
  Configuration, Future Extensions).
- Switching the world-archive container/algorithm away from plain ZIP/DEFLATE (previously
  explored as "FRC.4," a stronger-but-format-changing approach). Rejected in favor of the
  backward-compatible level-9 DEFLATE bump (FRC.1) — see FRC.2 and Compatibility for the
  rationale and Future Extensions for the rejected alternative's disposition.

## Requirements

### FRD — Louder, specific failure logging

**Current behavior (confirmed by reading the code, not assumed):**

- `WorldSaveSyncService.ensureQuota(int neededBytes, String worldSlugBeingWritten)`
  (`WorldSaveSyncService.java:302-324`) only ever *reacts* to a shortfall by evicting older
  cached fingerprints via `fileForget` and notifying the player which world was evicted
  (`playerNotifier.accept(...)`, line 318-320). It never logs anything itself if `getQuota`
  fails (`archiveStore.getQuota` returning `false` at line 305 is silently treated as "quota
  OK, proceed") or if evictions still leave `available[0] < neededBytes` after the loop —
  the loop at 313-323 simply exits and the subsequent `streamWrite` call is attempted
  anyway with no diagnosis of *why* it's likely to fail.
- The actual write happens on the tick thread inside `syncWorldNow`
  (`WorldSaveSyncService.java:235-242`): `archiveStore.streamWrite(...)` returns a plain
  `boolean`. On `false`, the only log line is `warningLogger.accept("Failed to sync world \""
  + displayName + "\" to Steam Cloud.")` (line 240) — a single generic sentence with no
  byte counts, no cause, no distinction between "quota exceeded," "Cloud disabled for this
  app/account," "write stream open rejected," or "chunk write failed."
- Inside `SteamRemoteStorageWorldArchiveStore.streamWrite` (`SteamRemoteStorageWorldArchiveStore.java:104-133`),
  three distinct failure paths already exist but are logged with the file name only, no
  size/quota context:
  - stream-open rejected (line 108): `"Steam Cloud rejected opening a write stream for
    \"" + fileName + "\"."`
  - chunk write failed, write cancelled (line 127): `"Failed to write one or more chunks of
    Steam Cloud world archive \"" + fileName + "\"; write cancelled."`
  - unexpected `RuntimeException` (line 130): includes the exception's `toString()`, but
    still no size/quota context.
  - None of these three call sites has access to `neededBytes`/quota numbers — that data
    only exists up in `WorldSaveSyncService.ensureQuota`, one layer up, and is never passed
    down or correlated with the eventual `streamWrite` failure log line.

**Required behavior:**

- **FRD.1** `ensureQuota` must log (via `warningLogger`, `warn` level, i.e. same channel as
  every other line already using `warningLogger.accept`/`warn(...)` in this module — there
  is no separate log-level plumbing in this codebase, see `services/specification.md`
  logging conventions) a specific line whenever `getQuota` itself fails to report
  (`archiveStore.getQuota(...)` returns `false`): e.g. `"Steam Cloud quota check failed for
  world \"" + worldSlugBeingWritten + "\" (Steam getQuota() call did not succeed); proceeding
  without a quota pre-check."` — replacing the current silent `return` at line 305-307 for
  that specific sub-case (the "quota genuinely sufficient" sub-case, `available[0] >=
  neededBytes`, still requires no log line).
- **FRD.2** After the eviction loop (`WorldSaveSyncService.java:313-323`) completes, if
  `available[0] < neededBytes` still holds, log one line before returning, including: the
  world slug, `neededBytes`, the final `available[0]`, the final `total[0]`, and how many
  candidate worlds were evicted this pass (i.e. did eviction have anything left to try, or
  was the candidate list simply empty/insufficient). Example message shape: `"Cloud quota
  still insufficient for world \"" + worldSlugBeingWritten + "\" after evicting " + evicted
  + " older world(s): need " + neededBytes + " bytes, have " + available[0] + " of " +
  total[0] + " bytes total. The write that follows is expected to fail."` This is the
  single most actionable new line for the reported bug (a tiny quota, e.g. total=1048576,
  makes the cause immediately obvious without cross-referencing the construction-time
  diagnostic).
- **FRD.3** `SteamRemoteStorageWorldArchiveStore.streamWrite` must accept enough
  information to log byte-count context on every one of its three existing failure paths
  (stream-open rejected, chunk-write-failed, `RuntimeException`) — specifically the
  archive's total `data.length` and, for the chunk-write-failed path, the `offset` reached
  before failure (already a local variable at line 113/120, currently discarded). No public
  signature change is required to carry quota numbers into this class — quota isn't known
  here today (this class has no cached quota state) and plumbing it through purely for a
  log line is not justified; `data.length`/`offset` alone (already available locally) are
  sufficient to make e.g. "wrote 4096 of 1245184 bytes before Steam rejected the next
  chunk" diagnosable, which combined with FRD.2's quota line (logged immediately before, in
  the same `syncWorldNow` call, since `ensureQuota` runs synchronously right before the
  `streamWrite` enqueue at line 232-236) lets a reader correlate the two without a new
  cross-class parameter.
- **FRD.4** Revise the generic `syncWorldNow` failure line (`WorldSaveSyncService.java:240`)
  to include `archiveBytes.length`: `"Failed to sync world \"" + displayName + "\" (" +
  archiveBytes.length + " bytes) to Steam Cloud; see the preceding Steam Cloud log line for
  the specific cause."` — pointing the reader at the more specific line(s) from FRD.1-3
  that will have already fired first (quota check happens before the write is enqueued;
  the store's own specific-cause line fires synchronously inside `streamWrite`, which
  returns before this line runs).
- **FRD.5** All new/changed log lines use the existing `warningLogger`/`warn(...)`
  `Consumer<String>` seams already threaded through both classes — no new logging
  dependency, no new log level distinction (this module has no `error` vs `warn` split
  today; introducing one is out of scope).

### FRU — Per-world sync-status UI

**Existing UI surface (confirmed by reading the code):** the per-world sync-*toggle* icon
is already implemented as a Pattern-3 (`ui-guidelines.md:145-203`) `@Mixin` into the real
vanilla world-row class, one copy per supported Minecraft version:
`platform/fabric-1.21.11/.../mixin/WorldEntrySyncIconMixin.java`,
`platform/fabric-26.1/.../mixin/WorldListEntrySyncIconMixin.java`,
`platform/fabric-26.2/.../mixin/WorldListEntrySyncIconMixin.java`. Each draws a single
`context.fill(...)` colored square (blue = enabled, gray = disabled — no error/synced
distinction of any kind today) at a fixed corner of the row
(`WorldEntrySyncIconMixin.java:48-51,62-65`), reads state via the `WorldSyncToggleHookHolder`
static holder bridging to `WorldSyncToggleHook`
(`api/src/main/java/de/lazuli/api/cloudsync/WorldSyncToggleHook.java:20-38`), which exposes
only `isSyncEnabled(String)`/`toggleSync(String)` — a boolean preference, not a sync
*result*. There is currently no concept anywhere in this feature of "did the last sync
attempt for this world succeed," only "is this world's sync preference on."

Reference-only patterns consulted for widget/tooltip conventions (read-only; no files in
either module are to be modified by this effort):
- `features/friends-sidebar` — has no tooltip rendering precedent either (`grep` for
  `[Tt]ooltip` in `FriendSidebarWidget.java` across all three platform modules returned no
  matches); it has an unstarted "generic dropdown widget" effort planned
  (per orchestrator brief) — not reusable yet, do not depend on it.
- `features/server-browser` — has in-progress unrelated work; not consulted further,
  per explicit instruction to treat it read-only/out of scope.
- `.claude/context/ui-guidelines.md:188-192` — this codebase's one established precedent
  for a status icon is a plain `graphics.fill(x1, y1, x2, y2, argbColor)` colored square
  (used by both the sync-toggle icon and `CloudOnlyWorldListEntry`), specifically to avoid
  the extra `RenderPipeline` argument `blitSprite` requires on ≥26.1. No tooltip-rendering
  call has been established anywhere in this codebase yet — this spec is the first to need
  one; the planning phase must locate and verify the correct per-version tooltip API
  (likely `DrawContext`/`GuiGraphics`'s own `drawTooltip`/`setTooltip`-family method, name
  TBD per version, following the same "confirm via `javap`, log any divergence in
  `minecraft.md`" discipline `ui-guidelines.md:199-201` already mandates for this mixin).

**Required behavior:**

- **FRU.1** Define a new `SyncStatus` enum with exactly four states: `NOT_SYNCED`,
  `SYNCED`, `SYNC_ERROR`, `SKIPPED_TOO_LARGE`. `NOT_SYNCED` covers both "preference is off"
  and "preference is on but no successful sync has completed yet this device has record
  of." `SKIPPED_TOO_LARGE` is its own distinct state — decided final — covering the
  `SyncStrategy.SKIPPED` size-threshold-exceeded case (see FRU.3); it is not folded into
  `NOT_SYNCED`.
- **FRU.2** Backend: extend the existing `api/cloudsync` hook surface with a new method
  rather than repurposing `WorldSyncToggleHook` (keeps the existing on/off-preference
  contract's meaning unchanged for existing callers) — e.g. a new
  `WorldSyncStatusHook.statusFor(String worldSlug): SyncStatus` plus
  `WorldSyncStatusHook.lastErrorFor(String worldSlug): String|null` (human-readable, only
  non-null when `statusFor(...) == SYNC_ERROR`). `WorldSyncPreferenceService` is the natural
  implementer (it already implements the sibling `WorldSyncToggleHook`,
  `WorldSyncPreferenceService.java:33`), but status/error state does not exist in it or
  anywhere else today — see FRU.3 for what must be added to make this real, not stubbed.
- **FRU.3** New state that must be tracked, in-memory, keyed by `worldSlug`, and updated by
  `WorldSaveSyncService`/`SteamRemoteStorageWorldArchiveStore` at the existing checkpoints
  they already reach:
  - On `updateFingerprint(...)` being reached (`WorldSaveSyncService.java:238`, i.e. the
    `streamWrite` call at line 236 returned `true`) → record `SYNCED` for that slug.
  - On the `else` branch at line 239-241 (`streamWrite` returned `false`) → record
    `SYNC_ERROR` for that slug, with the last-error string being the specific message FRD.4
    just produced (reusing the improved message, not inventing a second copy).
  - On the `SyncStrategy.SKIPPED` early return (line 215-218, size-threshold exceeded, no
    selective fallback allowed) → record `SKIPPED_TOO_LARGE` for that slug (decided final:
    this is its own distinct UI state, not folded into `NOT_SYNCED` and not treated as
    `SYNC_ERROR`). The existing player notification for this case
    (`syncWorldNow` line 216-217) is unchanged by this spec; the UI icon additionally
    reflects the skip persistently on the row until a future sync attempt for that world
    changes its status again.
  - On the `IOException` catch at line 243-245 (archive-build failure, before any Cloud
    call) → also record `SYNC_ERROR`, with that exception's message as the last-error text.
  - This tracked state does not need to survive a client restart (it reflects "this
    session's last attempt"); a freshly-launched client shows `NOT_SYNCED` for every world
    until a sync checkpoint has actually run for it this session. Persisting it across
    restarts is not required by this spec (Future Extensions) since the underlying
    fingerprint cache (`WorldFingerprintIO`) already gives a coarser "has this world ever
    successfully synced from any device" signal on disk if a future spec wants to seed
    initial UI state from that instead of blank.
  - Owning class for this new in-memory map: recommend a small new
    `WorldSyncStatusTracker` (or equivalent) inside `features/steam-cloud-sync/services`,
    constructed once by the composition root and handed to `WorldSaveSyncService` as an
    additional collaborator (mirrors the existing `warningLogger`/`playerNotifier`
    `Consumer<String>` injection shape already used in that constructor,
    `WorldSaveSyncService.java:101-122`) — keeps `WorldSaveSyncService` itself free of a
    second responsibility beyond "call the tracker at the same points it already calls
    `warningLogger`/`playerNotifier`." This keeps the tracker plain-JVM-unit-testable per
    this module's existing testability goal (`specification.md:14`), independent of any
    Minecraft/mixin code.
- **FRU.4** UI rendering, one row-icon per world (same row, same corner already used by the
  existing sync-toggle icon — see FRU.5 for how the two icons coexist):
  - `SYNCED`: icon renders in a distinct "success" color (e.g. green,
    `0xFF33CC33`, picked to be visually distinct from the toggle icon's existing blue
    `0xFF3399FF`/gray `0xFF808080` pair — exact color TBD at implementation, avoid reusing
    either existing color to prevent confusing "toggle is on" with "sync succeeded").
  - `NOT_SYNCED`: no additional status icon drawn at all (the existing toggle icon alone
    already communicates "sync is off," and a freshly-toggled-on-but-not-yet-synced world
    has nothing meaningful to report yet) — avoids a fourth/fifth `fill()` call cluttering
    every row for the common case.
  - `SYNC_ERROR`: icon renders in a distinct "error" color (e.g. red, `0xFFCC3333`).
  - `SKIPPED_TOO_LARGE`: icon renders in a distinct "warning" color (e.g. amber/yellow,
    `0xFFCCAA33`, picked to be visually distinct from all three other status colors and
    from the toggle icon's own blue/gray pair), so the four sync states each have their own
    unambiguous visual: no icon (`NOT_SYNCED`), green (`SYNCED`), red (`SYNC_ERROR`), amber
    (`SKIPPED_TOO_LARGE`).
  - Tooltip: for `SYNC_ERROR`, hovering the status icon shows `lastErrorFor(worldSlug)` as
    tooltip text (the FRD-produced message, e.g. "Failed to sync world ... (1245184 bytes)
    to Steam Cloud; see the preceding Steam Cloud log line..." — recommend trimming this to
    the single most specific FRD line, e.g. the FRD.2 quota-shortfall message when that's
    the cause, rather than the generic FRD.4 wrapper, since the wrapper's "see log"
    instruction is meaningless in an in-game tooltip with no log access; planning phase
    should settle on exactly which string variant is threaded through vs. logged only). For
    `SKIPPED_TOO_LARGE`, hovering the status icon shows a short fixed tooltip (e.g. "World
    is too large to sync automatically" — exact copy TBD at implementation, consistent in
    tone with the existing skip player-notification text at `syncWorldNow` line 216-217) —
    this does not require any new tracked "last error" string since the reason is constant,
    unlike `SYNC_ERROR`'s per-attempt message. No tooltip for `SYNCED`/`NOT_SYNCED` (nothing
    actionable to say).
- **FRU.5** Icon placement: the existing sync-toggle icon already claims the row's
  top-right corner (`WorldEntrySyncIconMixin.java:84-89`, `ICON_SIZE=8`, `ICON_MARGIN=4`).
  The new status icon must not overlap it — place immediately to its left (same top
  margin, `x = existing toggle icon's left edge - ICON_MARGIN - ICON_SIZE`), same 8x8 size,
  same fixed-square-color rendering approach (`ui-guidelines.md:188-192`) for consistency
  and to avoid the `RenderPipeline`/`blitSprite` complexity on ≥26.1. This is still a single
  feature (`steam-cloud-sync`) extending a single row it already owns exclusively, so the
  "two features, one screen" coordinator trigger (`ui-guidelines.md:218-229`) does not
  apply — no new coordination class needed.
- **FRU.6** This requires touching all three existing per-version mixin classes
  (`WorldEntrySyncIconMixin.java` / the two `WorldListEntrySyncIconMixin.java` copies) plus
  a new per-version `WorldSyncStatusHookHolder`-style static bridge (mirroring
  `WorldSyncToggleHookHolder.java`) published once by each platform module's composition
  root alongside the existing `WorldSyncToggleHookHolder.publish(...)` call
  (`SteamCloudSyncClientInitializer.java`, all three platform modules) — not a new mixin
  class, since both icons live in the same row-render method and should be drawn from the
  same `@Inject(method = "render", at = @At("TAIL"))` handler already present.

### FRC — World-archive compression

**Current behavior (confirmed by reading the code):**

- `WorldSaveSyncService.buildWholeArchive`/`buildSelectiveArchive`
  (`WorldSaveSyncService.java:248-280`) build the archive with plain
  `new ZipOutputStream(buffer)` (line 250/262) and `addZipEntry` (line 282-287) does a bare
  `zip.putNextEntry(new ZipEntry(entryName))` with no explicit method/level call at all.
  `java.util.zip.ZipOutputStream`'s documented default is `DEFLATED` at the *default*
  `Deflater` level (`Deflater.DEFAULT_COMPRESSION`, level `-1`, which resolves to zlib's own
  default, level 6 of 9) — so today's archives are **not** literally uncompressed, but they
  are not maximally compressed either; there is no explicit level selection anywhere in
  this file, confirmed by the absence of any `setLevel`/`setMethod` call in
  `WorldSaveSyncService.java`.
- `WorldRestoreService.extractAndFinish` (`WorldRestoreService.java:138-199`) reads back via
  plain `new ZipInputStream(...)` (line 153) with no format/version detection of any kind —
  it assumes every archive it reads is a standard `ZIP`/`DEFLATE` stream. Since
  `ZipOutputStream`/`ZipInputStream` are a matched read/write pair regardless of the
  `Deflater` *level* used to write (level only affects compression ratio/CPU cost, not the
  container format or the reader's ability to decompress it — `Deflater` level is not
  written per-entry as a value the reader must match, standard `DEFLATE`/zlib decoding is
  level-agnostic), a level-only change on the write side is naturally backward-compatible
  with the existing reader: **no version/format header is required for a level-only
  compression increase**, because `ZipInputStream` already correctly decompresses entries
  written at any `Deflater` level, including entries from already-synced archives written
  under today's default level.

**Decision (final):** the backward-compatible DEFLATE level-9 bump (FRC.1 below) is the
sole compression requirement for this spec. The alternative format-changing approach
previously explored (an outer-container/algorithm switch, referred to as "FRC.4" during
drafting) is rejected and out of scope — see Future Extensions for its disposition if
revisited later.

**Required behavior:**

- **FRC.1** Increase compression strength by explicitly calling
  `zip.setLevel(Deflater.BEST_COMPRESSION)` (level 9) immediately after constructing the
  `ZipOutputStream` in both `buildWholeArchive` and `buildSelectiveArchive`
  (`WorldSaveSyncService.java:250`/`262`) — this is the "heavily archived" requirement,
  using the same `java.util.zip` dependency already in use (no new dependency, consistent
  with this module's existing "no new dependency" design note,
  `WorldSaveSyncService.java:24`). `Deflater.BEST_COMPRESSION` is a fixed constant, decided
  final — not a configurable option (see Non-goals, Configuration). Trade-off, accepted as
  final: level 9 costs more CPU time per sync (world-unload checkpoint, already off the
  render thread per `CloudSyncWorker`/`worker.submitBackgroundWork`,
  `WorldSaveSyncService.java:138`) in exchange for a smaller archive — given sync only
  happens at world-unload (not per-tick) and already runs on a background thread, the CPU
  cost is accepted as a fixed trade-off with no config toggle.
- **FRC.2** Because FRC.1 is read-compatible with the existing `WorldRestoreService` reader
  (see analysis above — `Deflater` level is not part of the format `ZipInputStream` must be
  told to match), **no archive format/version header is required** for this change, and no
  migration/detection logic needs to be added to `WorldRestoreService.extractAndFinish`.
  This is decided final: FRC.1's plain-DEFLATE, level-9-within-the-existing-ZIP-container
  approach is the sole compression requirement, fully compatible with
  `WorldRestoreService`'s existing reader as-is, with no version header and no migration
  path of any kind.
- **FRC.3** No change to `WorldRestoreService`'s `ZipInputStream`-based extraction is
  required by FRC.1; `estimateUncompressedSize`
  (`WorldRestoreService.java:206-222`) already reads `entry.getSize()` (the *uncompressed*
  size, unaffected by compression level) for its progress-bar math, so FRC.1 does not touch
  restore-progress reporting either.
- **FRC.4 (rejected, out of scope)** A materially stronger compression strategy —
  switching container/algorithm entirely (e.g. wrapping the `ZipOutputStream`'s output in
  an outer `GZIPOutputStream`/`XZOutputStream`, or using `STORED`+external compression, or a
  non-`java.util.zip` codec) would compress better than raising the `Deflater` level within
  the existing ZIP container, but **would** require an explicit format/version header (e.g.
  a short magic-byte/version prefix on the archive bytes before the ZIP payload, or a
  distinct Cloud file-name suffix) plus real branch-on-version detection logic in
  `WorldRestoreService.extractAndFinish`, since existing already-synced archives are plain
  ZIP streams a differently-wrapped reader would no longer be able to open transparently.
  This has been explicitly rejected in favor of FRC.1 (level-9 DEFLATE within the existing
  ZIP container), which avoids this complexity while still meaningfully reducing archive
  size for the common case (world saves are largely region-file/NBT data, which compresses
  well under plain DEFLATE). Retained here only as a record of the rejected alternative;
  see Future Extensions for its disposition if this decision is ever revisited.
- **FRC.5** No change required to the `WRITE_CHUNK_BYTES`/100MB-per-call chunking logic in
  `SteamRemoteStorageWorldArchiveStore.streamWrite` (`SteamRemoteStorageWorldArchiveStore.java:52-53,104-133`)
  — that logic already operates on the already-built `byte[]` regardless of how compressed
  it is; a smaller archive simply means fewer chunks, not a code change.

## Public API

- `api/cloudsync` gains `WorldSyncStatusHook` (new interface, `SyncStatus` enum with
  `NOT_SYNCED`/`SYNCED`/`SYNC_ERROR`/`SKIPPED_TOO_LARGE`, `statusFor(String)`,
  `lastErrorFor(String)`), sibling to the existing `WorldSyncToggleHook` — zero Minecraft
  imports, matching that interface's own existing convention (`WorldSyncToggleHook.java:1-3`).
- No changes to any existing public method signature in `WorldSaveSyncService`,
  `SteamRemoteStorageWorldArchiveStore`, `WorldRestoreService`, or `WorldSyncPreferenceService`
  — FRD/FRU/FRC additions are new constructor parameters (an injected status-tracker
  collaborator) and new private helper logic, not breaking changes to existing callers.

## Architecture

- FRD changes stay entirely inside the existing `WorldSaveSyncService`/
  `SteamRemoteStorageWorldArchiveStore` classes — no new class needed, only new
  parameters/log lines at the failure sites already identified.
- FRU introduces one new plain-JVM-testable class (`WorldSyncStatusTracker` or equivalent,
  exact name TBD at planning), constructed by the composition root, injected into
  `WorldSaveSyncService` alongside the existing `warningLogger`/`playerNotifier` collaborators,
  and implementing the new `WorldSyncStatusHook` for the platform mixins to read via a new
  per-version static holder (mirroring `WorldSyncToggleHookHolder`). This follows the same
  services/api/platform layering already established for the toggle icon
  (`WorldSyncPreferenceService` → `WorldSyncToggleHook` → `WorldSyncToggleHookHolder` →
  per-version `@Mixin`).
- FRC changes are confined to `WorldSaveSyncService`'s two archive-building private methods
  — no architectural change, no new class.

## UI

Covered in full under FRU above. Summary: a second 8x8 colored-square status icon
immediately left of the existing sync-toggle icon on each local world's row in the vanilla
Singleplayer world list, rendered via the same `@Mixin`/`DrawContext.fill` pattern already
in place: green for `SYNCED`, red with a hover tooltip (error text) for `SYNC_ERROR`, amber
with a hover tooltip (fixed skip-reason text) for `SKIPPED_TOO_LARGE`, no icon drawn for
`NOT_SYNCED`.

## Configuration

- No new required configuration. FRC.1's `Deflater.BEST_COMPRESSION` is a fixed constant,
  decided final — not exposed via `SteamCloudSyncConfig` or any other configuration
  surface, and not user-configurable (see Non-goals).
- No new configuration for FRD (log-only, always on) or FRU (status tracking is always on
  whenever the sync-toggle icon itself is already shown, i.e. whenever Steam Cloud is
  available — `FR0.1`).

## Events

No new cross-feature event/callback types. FRU's status updates are pushed synchronously
by `WorldSaveSyncService` into its injected tracker at the same points it already calls
`warningLogger`/`playerNotifier` — no pub/sub mechanism needed since there is exactly one
reader (the row-render mixin, polling `statusFor(...)` per-frame the same way it already
polls `isSyncEnabled(...)`).

## Networking

No new networking. All three requirement areas operate on data already flowing through the
existing `ISteamRemoteStorage` calls (`streamWrite`/`getQuota`/`fileForget`) — FRD adds
logging around existing calls, FRU tracks existing call outcomes, FRC changes only the
`Deflater` level of the bytes handed to the existing `streamWrite` call.

## Persistence

- FRU's per-world status map is in-memory only for v1 (explicitly not persisted across
  client restarts — see FRU.3 discussion of why the existing on-disk fingerprint cache is
  a coarser, already-available substitute if a future spec wants persisted initial state).
- FRC's archives remain persisted as standard ZIP/DEFLATE bytes under the existing Cloud
  file-naming scheme (`WorldSaveSyncService.archiveFileName`, unchanged) — no new
  persisted file, no schema/version field added to any existing JSON persistence format
  (`WorldFingerprintIO`, etc.) as part of this spec, per FRC.2's no-header decision.

## Compatibility

- FRD: purely additive log lines; no compatibility concerns.
- FRU: additive new `api` interface; existing `WorldSyncToggleHook` callers/implementers
  are unaffected. The new `SKIPPED_TOO_LARGE` enum constant is additive to the `SyncStatus`
  enum defined by this same spec, so there is no prior external consumer to break.
- FRC: designed and decided (FRC.1/FRC.2) to remain read-compatible with every archive
  already written to Steam Cloud under the current default-`Deflater`-level format — this
  is the key compatibility property of this spec, and it is what avoids needing any
  format/version migration logic at all. The stronger-but-incompatible alternative
  (FRC.4) is rejected specifically because it would have required a version header and
  `WorldRestoreService` branch-on-version logic; that approach is not part of this spec.

## Performance

- FRD: negligible — a handful of additional string-building/log calls only on the
  already-uncommon failure path.
- FRU: negligible — one additional `ConcurrentHashMap`-or-similar lookup per row per frame
  on the Singleplayer screen (already-established pattern, same cost class as the existing
  `isSyncEnabled` lookup drawn every frame today).
- FRC: `Deflater.BEST_COMPRESSION` (level 9) costs more CPU time than the current default
  (level 6) per archive build, proportional to world-folder size; this work already runs on
  `CloudSyncWorker`'s background thread (`WorldSaveSyncService.java:138`), off the render
  thread, and only at the world-unload checkpoint (not per-tick), so the added CPU cost is
  not expected to be player-visible. This trade-off is accepted as final, with no config
  toggle (see FRC.1, Configuration).

## Future Extensions

- Persisting FRU's sync-status map across client restarts, seeded from the existing
  on-disk fingerprint cache (`WorldFingerprintIO`) rather than starting blank every launch.
- FRC.4's stronger, format-incompatible compression strategy (rejected for this spec, see
  FRC.4/Compatibility), if the level-9-DEFLATE requirement (FRC.1) proves insufficient in
  practice against real-world Cloud quotas. Revisiting this would require adding a version
  header and `WorldRestoreService` branch-on-version logic that this spec deliberately does
  not include.
- A background re-compression pass that re-uploads already-synced archives at the new,
  stronger compression level to reclaim quota sooner than the next natural world-unload
  sync (explicitly out of scope, Non-goals).
- A configurable compression-level knob in `SteamCloudSyncConfig` (rejected for this spec,
  see Non-goals/Configuration), if the fixed `BEST_COMPRESSION` default proves too
  CPU-costly on lower-end hardware in practice.
