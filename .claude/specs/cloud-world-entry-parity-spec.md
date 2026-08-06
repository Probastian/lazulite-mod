# Cloud-Only World Row Parity Specification

## Overview

Today each platform's `WorldsPanel` (`platform/fabric-1.21.11`,
`platform/fabric-26.1`, `platform/fabric-26.2`, package
`de.lazuli.mainmenu`) renders two visually and structurally different
kinds of Worlds-tab rows in `render()`/`mouseClicked()`:

- **Local rows**: one `for (WorldListWidget.WorldEntry entry : entries)`
  loop (all three platforms, near identical file line ranges) that reads
  a real `LevelSummary` (`entry.getLevel()`) and renders: a real
  icon.png-backed thumbnail (`IconTextureCache#forWorld`), the display
  name, a subtitle line (`"<GameMode> · <relativeTime(lastPlayed)>"`),
  the two-slot sync/status square pair at the row's right edge
  (`drawSyncIcons`), row hover/expand-collapse, and — only while
  expanded — Play/Edit pills (and a conditional "Resolve Cloud
  Conflict" pill).
- **Cloud-only rows**: a second, separate
  `for (CloudOnlyWorldSummary cloudOnly : cloudOnlyWorlds)` loop,
  appended after the local loop, that renders a visually simpler row: a
  real-icon-or-flat-blue-square thumbnail, the display name, and a
  single detail line (`"<deviceLabel> · <formatSyncedAt> [· <version>]
  [· Seed: <seed>]"`). It has no sync/status square, no expand/collapse,
  no Play/Edit pills, and no hover-highlight-driven affordances beyond
  the row fill color; a single click anywhere on the row calls
  `openRestoreFlow(cloudOnly)` directly (opens
  `de.lazuli.cloudsync.WorldRestoreScreen`, per the already-shipped
  `cloud-world-download-spec.md`).

Both loops are hand-rolled drawing code owned entirely by `WorldsPanel`
itself (row layout/text/icon-blit is this class's own code on both
paths — only the *actions* Play/Edit trigger, `entry.play()`/
`entry.edit()`, are real vanilla `WorldListWidget.WorldEntry` methods).
There is no vanilla row-widget being subclassed or reused for local
rows that would block reuse for cloud-only rows: unifying the two rows
is achievable entirely inside `WorldsPanel`, by having the cloud-only
loop assemble the same visual/data shape the local loop already
produces, backed by `CloudOnlyWorldSummary`'s fields (with the
documented fallbacks in Requirement 3 for what a cloud-only world
genuinely cannot supply pre-restore).

This is a companion feature to `cloud-world-download-spec.md` (the
already-shipped, already-merged "Play a cloud-only world" download/
progress-screen feature). This spec does not change the download/
progress-screen mechanics that feature ships (progress UI, Cancel's
background-continue semantics, byte/ETA formatting) — it changes the
row's own appearance/interactions leading up to a download being
triggered, *and* (Requirement 4) introduces two distinct pill actions
in place of today's single click-anywhere-to-Play trigger, one of which
newly launches the world once the download that same feature already
ships completes.

## Goals

1. A cloud-only row must be visually indistinguishable from a local
   row at the same expand state: same row height constants
   (`ROW_HEIGHT_COMPACT`/`ROW_HEIGHT_EXPANDED`), same hover fill colors,
   same icon size/position/margins, same display-name text position/
   color, same subtitle text position/color/format, same sync/status
   square pair position (or absence, per Requirement 2), same
   expand/collapse row-click behavior, same two-pill layout when
   expanded (two pills occupying the same position/size slots a local
   row's Play/Edit pair uses, per Requirement 4).
2. A cloud-only row must display the same *fields*, formatted the same
   way, as a local row: game mode + relative last-played time as the
   subtitle (not device label + absolute synced-at timestamp).
3. Fields a cloud-only world genuinely cannot supply before it is
   restored (see Requirement 3) get a specified, deterministic
   fallback that still fits inside the unified row shape — never a
   blank/broken cell, never a crash.
4. An expanded cloud-only row offers two pills in place of a local
   row's Play/Edit pair: "Download & Play" (downloads the world via the
   exact same flow `cloud-world-download-spec.md` already ships, then
   launches it once the download completes) and "Download" (starts the
   identical background download but returns immediately to the Worlds
   tab without launching, mirroring that same feature's existing
   Cancel-continues-in-background semantics). Neither pill is "Edit"
   (editing a world that doesn't exist locally yet is still
   meaningless) and there is no "Resolve Cloud Conflict" pill (a
   cloud-only world is never `CONFLICT`) — see Requirement 4.
5. Applied identically across `fabric-1.21.11`, `fabric-26.1`,
   `fabric-26.2`.

## Non-goals

- Any change to `WorldRestoreHook`, or the underlying transfer/
  extraction/progress-UI/byte-and-ETA-formatting mechanics shipped by
  `cloud-world-download-spec.md`. This feature only adds a second entry
  point into that same mechanism (the "Download" pill, which starts the
  identical background restore without opening the progress screen) and
  a new completion behavior for the first entry point (the "Download &
  Play" pill's post-completion world launch) — see Requirement 4's
  `WorldRestoreScreen` constructor addition for the one small, scoped
  exception to "no change" this requires.
- Any change to how a world is *detected* as cloud-only
  (`CloudOnlyWorldDetector`, `CloudOnlyWorldsFacade`,
  `WorldFingerprintCache`) or to the Cloud metadata file's on-disk
  schema (`WorldCloudMetadata`, `WorldCloudMetadataIO`) — this feature
  only changes how already-available `CloudOnlyWorldSummary` fields are
  displayed, plus (Requirement 3) genuinely improves the accuracy of
  fields the upload path currently only fills with sentinels, since
  that's required for real parity rather than "same layout, wrong
  data."
- Sync/status square real functionality for a cloud-only world (toggle
  sync on/off, upload/download-in-progress color, conflict color) — a
  cloud-only world has no local sync-enabled state, no local upload
  activity, and no local `WorldFingerprint`-cache-comparable freshness
  until it exists locally. Requirement 2 specifies the row still
  reserves the same visual slot rather than omitting it (for layout
  parity) but describes its one meaningful state (queued/in-progress
  download) plus its otherwise-inert default.
- Row reordering/sorting or merging cloud-only and local rows into one
  interleaved, sorted list — cloud-only rows remain appended after all
  local rows, unchanged from today's ordering. (Not requested; changing
  sort order is a larger behavioral change than "look identical.")
- Any change to `WorldConflictScreen` or conflict-resolution flow —
  irrelevant to a world with no local copy yet.

## Requirements

### Requirement 1 — Unified row rendering, single code path

`WorldsPanel.render()`'s cloud-only loop (all three platforms) is
rewritten to reuse the exact same drawing statements/helpers the local
loop uses, rather than a hand-duplicated simplified version, so the two
can never visually drift apart again:

- Icon: same `iconSize`/`iconX`/`iconY` computation
  (`(rowHeight - IMAGE_MARGIN * 2) * 2 / 3`, vertically centered), same
  `context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, ...)` call
  shape, same `ICON_TEX_SIZE` constant. Only the `Identifier` source
  differs (`IconTextureCache#forWorld` for a local row vs.
  `IconTextureCache#forServer("cloud:" + worldSlug, decodedIconBytes)`
  or the Requirement 3 placeholder for a cloud-only row) — kept as the
  one intentional per-kind branch, everything else shared.
- Display name: same `textX`/`rowY + 4` position, same color
  (`0xFFEAE8E1`), same `context.drawText` call.
- Subtitle: same `textX`/`rowY + 15` position, same color
  (`0xFF908C7F`), same `"<gameMode> · <relativeTime>"` format (see
  Requirement 2 for the underlying values used for a cloud-only row).
- Row hover fill: same `hovered ? 0xFF2A2820 : 0xFF201E17` computed
  from the same whole-row hit-test.
- Expand/collapse: a cloud-only row becomes expand/collapse-capable
  exactly like a local row (`state.expandedRowId()` keyed by
  `worldSlug`, `ROW_HEIGHT_EXPANDED` when expanded, same enlarged-icon
  branch), rather than the current single-click-anywhere-opens-restore
  behavior. See Requirement 4 for what the expanded pill row becomes
  for a cloud-only world.
- Sync/status square pair: see Requirement 2.

Concretely, this is expected to collapse into one shared private
render-row helper parameterized by a small per-row view record (or
equivalent) built once per row from either a `LevelSummary`+
`WorldListWidget.WorldEntry` or a `CloudOnlyWorldSummary`, so
`render()`'s two loops become "build the view value, call the shared
row-drawing method" — eliminating the current copy-pasted layout
constants/branches between the two loops. The exact refactor shape
(helper method signature, whether a shared row-view type is introduced)
is a planning-phase decision; this spec only requires the two loops
converge on shared code for every drawing statement listed above, not
a specific Java structure.

### Requirement 2 — Sync/status square pair for a cloud-only row

The two right-edge squares (`drawSyncIcons`) are drawn in the same
position for a cloud-only row (layout parity), with these semantics
in place of the real `WorldSyncToggleHook`/`WorldSyncStatusHook` reads
a local row uses (which require a real local world folder and are not
meaningful pre-restore):

- Toggle square (`COLOR_SYNC_ENABLED`/`COLOR_SYNC_DISABLED`): always
  rendered in the disabled color (`COLOR_SYNC_DISABLED`,
  `0xFF808080`), non-interactive (its `mouseClicked` hit-test is
  skipped for cloud-only rows — no `toggleSync` call, since there is no
  local world to toggle sync for yet). Tooltip on hover: "This world
  has not been downloaded yet."
- Status square: rendered only while this cloud-only world's download
  is in progress (i.e. the same `WorldSyncStatusHook#isDownloadInProgress`
  check the local-row path already uses, keyed by `worldSlug`, since
  `cloud-world-download-spec.md`'s background-continuing download uses
  that same hook state), using the existing `COLOR_STATUS_SYNCING_BASE`/
  `COLOR_STATUS_SYNCING_HIGHLIGHT` animated pair and the existing
  "Downloading from Steam Cloud..." tooltip text. Hidden otherwise
  (mirrors `computeShowStatusIndicator`'s existing hide-when-nothing-
  to-show precedent) — there is no "unsynced"/"synced"/"conflict" state
  meaningful for a world that doesn't exist locally yet.
- Neither square is clickable for a cloud-only row (no `toggleSync`, no
  `openConflictScreen` — a cloud-only world cannot be in `CONFLICT`).

### Requirement 3 — Data availability and fallbacks

Per-field determination, based on `CloudOnlyWorldSummary`'s current
contents (`api/src/main/java/de/lazuli/api/cloudsync/CloudOnlyWorldSummary.java:50-61`)
and how `features/steam-cloud-sync` populates them
(`CloudOnlyWorldsFacade.attachMetadata`,
`WorldSaveSyncService.buildAndUploadMetadata`):

| Field | Genuinely available today? | Row treatment |
|---|---|---|
| `displayName` | Yes (real, from `WorldFingerprint`/metadata) | Used as-is, same position/color as a local row's `entry.getLevelDisplayName()`. |
| `minecraftVersion` | Yes (real, read from `level.dat` via `LevelDatBatch` at the sync that produced the metadata file) | Available for a future badge (Future Extensions) but not part of the local row's own subtitle format, so not surfaced in the unified subtitle line (parity means *matching* the local row's fields, not adding new ones it doesn't have). |
| `seed`, `difficulty` | Yes (real, same `LevelDatBatch` source) | Not part of a local row's own displayed fields either (a local row never shows seed/difficulty in the list) — not surfaced, for the same parity reason. |
| `iconBase64` | Conditionally real: populated from the world's actual `icon.png` when one exists in the synced world folder (`WorldSaveSyncService.readIconBase64OrNull`, already wired end-to-end per `cloud-world-metadata-file` spec Requirement 5); `null` when the world has no custom icon, or when it was synced before that feature shipped (no metadata file at all). | **Requirement 3a** (icon fallback, below). |
| `lastPlayedMillis` | **Not genuinely real today.** `buildAndUploadMetadata` (`WorldSaveSyncService.java:818-848`) always passes its own `syncedAtTimestamp` as this field's value — there is no code path today that threads a world's true last-played time (from `level.dat`'s `LastPlayed` tag) into the metadata upload. The one call site with a real `LevelSummary`/last-played value in hand today is `WorldConflictScreen`'s `openConflictScreen` → `WorldConflictResolutionHook.detailFor`, a different (conflict-detail) code path, not the ordinary background sync. | **Requirement 3b** (real-data fix, below) — this is close enough to "genuinely available, just not wired up" that this spec fixes it at the source rather than accepting the proxy value, since a proxy "last played" is a visible, easily-noticed parity defect (dates would read as "just synced" instead of the world's real last-played time). |
| `gameMode` | **Not genuinely real today.** `buildAndUploadMetadata` always passes the literal string `"Unknown"` (`WorldSaveSyncService.java:839`) — the code comment there explicitly notes gameMode is "sourced from a platform `LevelSummary`, not the Minecraft-client-type-free `LevelDatBatch` this checkpoint has available." | **Requirement 3b** (real-data fix). |
| `hardcore` | **Not genuinely real today.** Always `false` (`WorldSaveSyncService.java:841`), same root cause as `gameMode`. | Folded into the Requirement 3b game-mode read (`level.dat`'s `Data.hardcore` tag is a single additional boolean read alongside the other `LevelDatBatch`-style fields — see Requirement 3b). |
| `deviceLabel`, `syncedAtTimestamp` | Yes (real) | No longer shown in the unified subtitle (a local row's subtitle has no device/synced-at concept) — moved to the row's expanded-state detail area or a hover tooltip (Requirement 4) so this real, useful information isn't simply discarded. |

**Requirement 3a — Icon fallback.** When `iconBase64` is present and
decodes successfully, use it exactly as today
(`IconTextureCache#forServer("cloud:" + worldSlug, decodedIconBytes)`).
When it is absent or fails to decode, render vanilla's own default
world icon in its place — investigated: vanilla ships a built-in
"unknown pack"/default icon texture (`WorldIcon`'s
`DEFAULT_ICON`/no-favicon path already used for a *local* world with no
`icon.png`, confirmed via `IconTextureCache#forWorld` delegating to
`WorldIcon.forWorld`, which itself already falls back to vanilla's
default icon when `LevelSummary.getIconPath()` has nothing to load —
this is the same fallback a brand-new local world with no icon.png
shows). Reusing that exact default keeps the fallback pixel-identical
to what a real world with no custom icon already shows, rather than
inventing a new flat-color placeholder — satisfying "look identical"
more literally than the currently-shipped flat blue square. A small
cloud badge (e.g. an 8x8 cloud-tinted corner mark, reusing the existing
`SYNC_ICON_SIZE`-scale visual language) is overlaid in the icon's
bottom-right corner *only* for a cloud-only row regardless of whether
its icon is real or the fallback default — this is the one intentional,
deliberate visual difference this spec keeps (see Requirement 5 on why
some difference must remain), kept as small/unobtrusive as the existing
sync-status squares.

**Requirement 3b — Real last-played/game-mode/hardcore at the
source.** `WorldSaveSyncService.buildAndUploadMetadata`'s caller
(`syncWorldNow`, `WorldSaveSyncService.java:949-966`) is extended to
read the same three `level.dat` fields `WorldsPanel.readLevelDatBatch`
already reads for the conflict-detail path (`LastPlayed`, `GameType`/
game-mode tag, `hardcore`) as part of the ordinary background-sync
metadata build, rather than only at conflict-resolution time — reusing
the existing DataFixerUpper `Dynamic<?>` read machinery
(`session.readLevelProperties()`) already proven at
`WorldsPanel.readLevelDatBatch` (`platform/fabric-1.21.11/.../WorldsPanel.java:1020-1044`,
mirrored per-platform), rather than introducing a second parsing
approach. `WorldConflictResolutionHook.LevelDatBatch` is extended with
`lastPlayedMillis`/`gameMode`/`hardcore` fields (or a sibling batch
type is introduced — a planning-phase decision) so `syncWorldNow`'s
existing `LevelDatBatch` parameter can carry them through to
`buildAndUploadMetadata` without a second file read. Any read failure
(corrupt/missing `level.dat`, mid-write race) falls back to today's
sentinels (`"Unknown"`/`false`/`syncedAtTimestamp` proxy) exactly as
now — this is an accuracy improvement on the happy path, not a new
hard dependency.

### Requirement 4 — Expanded-state "Download & Play"/"Download" pills and detail area

When a cloud-only row is expanded (Requirement 1), its bottom pill row
shows **two pills**, occupying the exact same two-pill position/size
convention a local row's Play/Edit pair uses (`pillBounds`'s
`playX`/`playW` and `editX`/`editW` slots, reused verbatim for
position/size/hover behavior — only the labels/colors/actions differ).
As before, there is never a "Resolve Cloud Conflict" pill (a cloud-only
world is never `CONFLICT`), and neither pill is "Edit" (editing a world
that doesn't exist locally yet remains meaningless):

- **"Download & Play"** pill (left/`playX`/`playW` slot, same
  `COLOR` `0xFF528A54`/hover `0xFF64A066` convention as today's single
  Play pill): opens the exact same `WorldRestoreScreen` construction
  `openRestoreFlow(cloudOnly)` already builds per
  `cloud-world-download-spec.md` — identical progress UI, identical
  Cancel-navigates-back-immediately-while-continuing-in-background
  semantics if the player presses Cancel mid-download — except that
  when the download completes *without* the player having cancelled,
  the completion callback additionally launches the now-locally-present
  world instead of merely returning to the Worlds tab. See "Completion-
  callback investigation and chosen approach" below.
- **"Download"** pill (right/`editX`/`editW` slot, its own distinct
  label and a color distinct from both Play-green and Edit's color — a
  planning-phase decision on the exact shade, consistent with this
  project's existing pill-color conventions, chosen so it does not read
  as "this is the Edit action"): starts the identical background
  download — the same `markDownloadPending` + `beginRestore` call
  `cloud-world-download-spec.md`'s FR2.4 already performs — but never
  opens `WorldRestoreScreen` at all. The player remains on (returns
  immediately to, if already expanded elsewhere) the Worlds tab exactly
  as if they had opened the progress screen and immediately pressed
  Cancel (FR2.1/FR2.2 of `cloud-world-download-spec.md`), without ever
  seeing that screen. The restore proceeds to completion in the
  background exactly like today's Cancel-continues behavior;
  `onComplete`/`onFailed` still call `markDownloadFinished` so the
  row's blocked state (below) clears once it finishes, and the world
  is never launched by this pill.

Both pills share the row's single `blocked` gate: while a download for
this world's slug is already in progress (Requirement 2's
`isDownloadInProgress` condition), both pills render in the existing
"blocked" gray/muted style with the existing "Cannot play or edit while
syncing with Steam Cloud." tooltip, reused verbatim identically for
both pills, so pressing either while a download is already running is a
no-op — exactly like a local row's blocked state applying to its Play
and Edit pills simultaneously. The row is never in a `blocked` state
for any other reason (no conflict/stale/unknown gate applies
pre-restore).

**Completion-callback investigation and chosen approach.**
`WorldRestoreScreen` (all three platforms, e.g.
`platform/fabric-1.21.11/.../WorldRestoreScreen.java:95-163`) today has
exactly one `Runnable onReturn` field, invoked from two separate call
sites: (a) `render()`, once `onComplete` has set `completed = true`
(natural finish, line ~124), and (b) `onCancel()` (Cancel button/screen
close, line ~162). Both call sites currently invoke the *same* Runnable
(today, `WorldsPanel.openRestoreFlow`'s `() -> { reload();
setScreen(owner); }`), which was sufficient for the existing
single-pill design where completion and cancel both simply mean "go
back to the Worlds tab." That single-Runnable design is **not**
sufficient for "Download & Play": launching the world must happen only
on genuine successful completion, never when the player presses Cancel
mid-download (which must still just return to the Worlds tab while the
download keeps running in the background, per
`cloud-world-download-spec.md`'s FR2, completely unchanged). Reusing
`onReturn` for both would incorrectly launch a possibly-incomplete
world when the player only meant to cancel out of watching it.

Chosen approach — a small, tightly scoped addition:
`WorldRestoreScreen`'s constructor gains one additional parameter,
`Runnable onCompleted` (nullable), used only at the natural-completion
call site (replacing today's `onReturn.run()` there), while `onReturn`
keeps its exact current meaning and behavior at the Cancel/close call
site, unchanged. The "Download & Play" pill's call to
`openRestoreFlow`-equivalent construction passes `onCompleted = () ->
<launch the just-restored world>` and `onReturn = () -> { reload();
setScreen(owner); }` (unchanged from today), so pressing Cancel during
a "Download & Play" run behaves identically to today's shipped
behavior: return to the Worlds tab, download continues in the
background, no launch. If `onCompleted` is null, natural completion
falls back to `onReturn.run()`, preserving today's exact behavior for
any caller that doesn't need to launch. `WorldConflictScreen`'s
separate "Keep Cloud" restore path is unaffected — it never constructs
`WorldRestoreScreen` (per `cloud-world-download-spec.md`'s
Compatibility section) and is untouched by this addition.

Launching the just-restored world reuses each platform's existing
real-Play mechanism rather than inventing a new one: `fabric-26.1`/
`fabric-26.2` already have
`Minecraft.getInstance().createWorldOpenFlows().openWorld(levelId, ()
-> {})` in production use for `HomePanel`'s Recent-section play action
(`WorldsPanel.playWorld`, `platform/fabric-26.2/.../WorldsPanel.java:997-999`)
— the "Download & Play" `onCompleted` callback calls the equivalent
`openWorld(cloudOnly.worldSlug(), () -> {})` (the world's save-folder
id, which equals `worldSlug` per `CloudOnlyWorldSummary`'s existing
contract) once the restore has landed the world on disk. No
`LevelSummary` needs to be constructed for the newly-downloaded world
first — `openWorld` only needs the save-folder id, which
`CloudOnlyWorldSummary.worldSlug()` already supplies even before the
restore completes. `fabric-1.21.11`'s Yarn-mapped equivalent (today's
local-row `entry.play()` at `WorldsPanel.java:1054` resolves to the
same underlying vanilla open-world call under a different mapped name)
must be confirmed against that module's own resolved jar during
planning/implementation — consistent with how this spec already defers
other exact per-platform API-name confirmations to planning
(Requirement 5).

Real device-label/synced-at info (demoted out of the subtitle per
Requirement 3's table) is shown as a small extra text line in the
expanded body (mirroring where a local row's expanded state has room
for extra detail) or as a hover tooltip on the row — a planning-phase
layout decision; either satisfies "the real data isn't discarded."

### Requirement 5 — Per-platform application

`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2` each apply Requirements
1-4 to their own `WorldsPanel.java` independently (each platform
already hand-duplicates this whole class per the existing repo
convention noted in each file's own class Javadoc — this spec does not
introduce cross-module sharing of `WorldsPanel` itself, consistent with
how `cloud-world-download-spec.md` and prior features treated it).
Confirmed via `Grep` that all three files share effectively identical
structure/line-shape for every method this spec touches (`render`,
`mouseClicked`, `drawSyncIcons`, `openRestoreFlow`, `decodeIconBase64OrNull`),
so the same edit pattern applies near-verbatim to each, module-specific
API-shape differences aside (see `.claude/context/minecraft.md`'s Known
Cross-Version API Differences table for anything relevant, e.g.
`fabric-1.21.11`'s Yarn-mapped `LevelStorage.Session.readLevelProperties()`
vs. the other two modules' equivalent, and Requirement 4's
`createWorldOpenFlows().openWorld(...)`-equivalent name).

## Public API

No new public API in the `api` module. Internal-only changes, all
confined to `features/steam-cloud-sync` and the three
`platform/fabric-*` modules:

- `WorldsPanel` (all three platforms): rewritten `render()`/
  `mouseClicked()` cloud-only-row handling per Requirements 1-4;
  no change to its own public methods (`init`, `setTabActive`, `reload`,
  `render`, `mouseClicked`, `recentEntries`). New private helper(s) for
  the "Download" pill's screen-less background-only restore trigger and
  for the "Download & Play" pill's post-completion launch call
  (Requirement 4) — internal, no new public method.
- `de.lazuli.cloudsync.WorldRestoreScreen` (one copy per platform,
  modified in place, same class name/package since
  `WorldsPanel.openRestoreFlow` constructs it by that name today):
  constructor gains one new parameter, `Runnable onCompleted`
  (nullable), per Requirement 4's completion-callback investigation.
  `onReturn`'s existing meaning/behavior at the Cancel/close call site
  is unchanged; `onCompleted` is invoked instead of `onReturn` only at
  the natural-completion call site, falling back to `onReturn` when
  null. This class has exactly one call site per platform
  (`WorldsPanel.openRestoreFlow`), so this is a source-compatible,
  tightly scoped addition with a single call site to update per
  platform — no published/external API is affected (same rationale
  `cloud-world-download-spec.md`'s own Compatibility section already
  gave for that screen's prior constructor-parameter addition).
- `WorldConflictResolutionHook.LevelDatBatch` (or a new sibling type):
  extended with `lastPlayedMillis`/`gameMode`/`hardcore` per
  Requirement 3b — a planning-phase decision on whether this is an
  additive change to the existing record or a new parallel type used
  only by `syncWorldNow`.
- `WorldSaveSyncService.syncWorldNow`/`buildAndUploadMetadata`: internal
  wiring only, per Requirement 3b; no public signature change (both are
  package-private/private already).
- `CloudOnlyWorldSummary`, `WorldCloudMetadata`,
  `WorldCloudMetadataIO`: **unchanged** — every field this spec needs
  already exists on both records; Requirement 3b only changes what
  *values* get written into the existing `lastPlayedMillis`/`gameMode`/
  `hardcore` fields, not their shape or JSON keys.

## Architecture

```
WorldsPanel.render()
 ├─ local-row loop      ─┐
 │                        ├─ shared row-drawing helper(s)   (Requirement 1)
 └─ cloud-only-row loop ─┘        │
                                  ├─ icon: real-or-fallback+badge (Req 3a)
                                  ├─ name/subtitle: shared format (Req 1/2)
                                  ├─ sync/status squares: cloud-only semantics (Req 2)
                                  └─ expand/pill row: "Download & Play" + "Download"
                                     pills, shared pill-slot code (Req 4)

WorldRestoreScreen (per platform)
 ├─ onReturn    (unchanged) — Cancel/close call site
 └─ onCompleted (new, nullable) — natural-completion call site (Req 4);
                                  falls back to onReturn when null

WorldSaveSyncService.syncWorldNow()
 └─ buildAndUploadMetadata()  ← now also receives real
                                 lastPlayedMillis/gameMode/hardcore
                                 (Requirement 3b), read via the same
                                 level.dat Dynamic<?> approach
                                 WorldsPanel.readLevelDatBatch already
                                 uses, wired through an extended
                                 LevelDatBatch (or sibling type)
```

No new modules or cross-module dependencies — this is entirely
contained within `features/steam-cloud-sync` (Requirement 3b) and the
three `platform/fabric-*` `WorldsPanel`/`WorldRestoreScreen` classes
(Requirements 1, 2, 4).

## UI

- Compact cloud-only row: identical to a compact local row, except (a)
  a small cloud badge overlay on the icon's bottom-right corner (always
  present on a cloud-only row, whether the icon itself is real or the
  vanilla-default fallback) and (b) the toggle square is always
  rendered in its disabled/gray color and is non-interactive.
- Expanded cloud-only row: identical to an expanded local row's larger-
  icon layout, except its two pills read "Download & Play" (left slot,
  Play's green convention) and "Download" (right slot, its own
  distinct color/label) instead of "Play"/"Edit" — never an Edit pill,
  never a Resolve Cloud Conflict pill — plus the device-label/
  synced-at line or tooltip (Requirement 4).
- No change to the panel's own header text ("Singleplayer Worlds"),
  the "No saved worlds yet." empty-state text, or the "+ Create New
  World" button.

## Configuration

None. No new config keys; `SteamCloudSyncConfig` is untouched by this
feature.

## Events

None new. Reuses existing `WorldSyncStatusHook#isDownloadInProgress`
polling (already per-frame in `drawSyncIcons`) for the Requirement 2
status-square condition; no new hook methods.

## Networking

None new. No change to `WorldArchiveCloudStore`,
`SteamRemoteStorageWorldArchiveStore`, or any Steam Cloud read/write
path — Requirement 3b's extra `level.dat` fields are a local disk read
already happening at the same sync checkpoint (`syncWorldNow` already
opens a `LevelStorage.Session`-equivalent read for `LevelDatBatch` at
call sites that pass one; for call sites that currently pass
`LevelDatBatch.unreadable()`, see the Open Question below), not a new
network call, and the resulting `iconBase64`/other fields are already
uploaded inside the existing metadata-file write.

## Persistence

No schema change to the Cloud metadata file
(`lazuli-world-meta-<slug>.json`, `WorldCloudMetadataIO`) — same field
names, same JSON shape; Requirement 3b only changes what values a
future upload writes into already-existing fields. Fully backward/
forward compatible: an old metadata file (or none at all) written
before this feature ships still degrades to today's existing sentinel
values (`"Unknown"`/`false`/synced-at proxy) exactly as it does now,
per `CloudOnlyWorldSummary`'s own existing Javadoc-documented
Compatibility contract.

## Compatibility

- A cloud-only world synced by an older mod build (no metadata file,
  or a metadata file predating Requirement 3b's real last-played/game-
  mode/hardcore fields) still renders correctly under the unified row
  layout, using the existing sentinel values — it just won't show a
  real game-mode/last-played until it is re-synced by an updated
  client.
- No change to the archive format, restore flow, or fingerprint file —
  this feature is additive/cosmetic plus one accuracy fix to already-
  planned-for optional fields, plus Requirement 4's scoped
  `WorldRestoreScreen` constructor addition (single call site per
  platform, updated in the same change).
- All three platform modules updated together; this feature is not
  meaningful shipped on only one platform (would reintroduce the
  parity gap this spec exists to close).

## Performance

- Requirement 1's shared-row-helper refactor does not add any new
  per-frame I/O — the icon badge overlay is one extra `context.fill`/
  `drawTexture` call per cloud-only row per frame, the same order of
  magnitude as the sync-icon squares already drawn per row today.
- Requirement 3b's extra `level.dat` reads happen only at existing
  `syncWorldNow` checkpoints (world unload, periodic/threshold sync,
  startup re-sync) — not per-frame, not on the render thread, no change
  to `WorldsPanel`'s own P3 freshness-cache-at-reload-only discipline.
- No new Steam Cloud I/O, no new per-frame hook calls beyond the
  existing `isDownloadInProgress` poll already made for local rows.
  Requirement 4's "Download" pill's screen-less background restore uses
  the same `beginRestore`/listener machinery already running on
  `CloudSyncWorker`'s existing background thread — no new threads.

## Future Extensions

- Surfacing `minecraftVersion` as a small version tag/badge on *all*
  rows (local and cloud-only) — out of scope here since it would be a
  new field on local rows too, not a parity fix.
- A genuine local-icon cache warm/pre-decode for cloud-only rows'
  `iconBase64` (currently decoded/cached the same lazy way as today,
  via `IconTextureCache#forServer`) if profiling ever shows it matters.
- Extending `WorldConflictResolutionHook.LevelDatBatch` reuse (or its
  Requirement 3b sibling) to other checkpoints beyond `syncWorldNow` if
  more accurate metadata timing is ever needed elsewhere.
- A true "abort download" affordance for a cloud-only row's in-progress
  status square (mirrors `cloud-world-download-spec.md`'s own deferred
  Future Extension of the same shape).

## Open Question (for plan/implementation phase, not blocking spec approval)

`syncWorldNow` has two call sites: one passing a real `LevelDatBatch`
(from `WorldConflictResolutionHook.detailFor`'s conflict-detail path)
and one defaulting to `LevelDatBatch.unreadable()`
(`WorldSaveSyncService.java:949-951`, used by ordinary background sync
checkpoints that don't already have a batch in hand). Requirement 3b's
real last-played/game-mode/hardcore values are only as good as whether
each such checkpoint can cheaply obtain a `LevelStorage.Session` read
at that point (client thread vs. background-sync thread implications
per platform) — the planning phase should confirm, per platform,
which `onWorldUnload`/periodic-sync/startup-resync call sites can
practically thread through a real batch read without becoming a new
main-thread stall, and fall back to sentinels at any checkpoint where
that isn't practical (this is already the existing degrade-gracefully
contract, just confirming where it naturally applies).
