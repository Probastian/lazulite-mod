# Implementation Plan: Cloud-Only World Row Parity

Spec: `.claude/specs/cloud-world-entry-parity-spec.md` (all Requirement/Goal
references below refer to this file, "the spec"). Builds on the
already-shipped `.claude/specs/cloud-world-download-spec.md` /
`.claude/plans/cloud-world-download-plan.md` ("the download feature").

## Existing Implementation (repo findings)

All three platforms (`platform/fabric-1.21.11`, `platform/fabric-26.1`,
`platform/fabric-26.2`, package `de.lazuli.mainmenu`/`de.lazuli.cloudsync`)
have near-identical `WorldsPanel.java`/`WorldRestoreScreen.java` copies;
findings below are grepped/read from the `fabric-1.21.11` copy and confirmed
structurally identical (via the download plan's own grep, and this plan's own
spot checks) across all three — per-platform API-name differences called out
explicitly where relevant.

### `WorldsPanel.java` (1.21.11 line numbers; 26.1/26.2 differ only in GUI-API calls)

- **`render()`** (line 352-509): local loop at line 370-466, cloud-only loop
  at line 468-508. Local loop per-row: `LevelSummary summary = entry.getLevel()`,
  `expanded = summary.getName().equals(state.expandedRowId())`, row height
  `ROW_HEIGHT_EXPANDED`(72)/`ROW_HEIGHT_COMPACT`, hover fill
  `0xFF2A2820`/`0xFF201E17`, icon via `iconCache.forWorld(summary.getName(),
  summary.getIconPath())` at `iconSize = (rowHeight - IMAGE_MARGIN*2)*2/3`
  (compact) or full-grid (expanded), name at `textX, rowY+4` color
  `0xFFEAE8E1`, subtitle `summary.getGameMode().getTranslatableName().getString()
  + " · " + relativeTime(summary.getLastPlayed())` at `textX, rowY+15` color
  `0xFF908C7F`, `drawSyncIcons(...)` call, then (if expanded) the Play/Edit/
  Resolve pill block (line 410-464) using `pillBounds(...)` (line 137-145),
  `computeShowResolveButton`, `computeBlocked`, `isRowSyncing`.
  Cloud-only loop per-row (line 468-508): fixed `ROW_HEIGHT_COMPACT`
  (no expand), same hover-fill computation, icon via
  `decodeIconBase64OrNull(cloudOnly.iconBase64())` then either
  `iconCache.forServer("cloud:" + cloudOnly.worldSlug(), decodedIconBytes)`
  or a flat `0xFF3399FF` fill (no vanilla-default fallback, no cloud badge —
  Requirement 3a gap), name via `cloudOnly.displayName()`, single detail line
  `deviceLabel · formatSyncedAt · [version] · [Seed: n]` (Requirement 2/3
  gap — no subtitle-format parity, no sync/status squares, no pills).
- **`mouseClicked()`** (line 856-957): local loop mirrors `render()`'s
  hit-tests (upload-in-progress whole-row block, expanded Play/Edit/Resolve
  hit-tests using the same `pillBounds`, toggle-square/status-square
  hit-tests via `syncIconLeft`, whole-row expand/collapse fallthrough).
  Cloud-only loop (line 942-955): single whole-row hit-test that calls
  `openRestoreFlow(cloudOnly)` directly — no expand state, no pill hit-tests
  (Requirement 1/4 gap).
- **`drawSyncIcons`** (line 605-647): takes a `LevelSummary summary` (not a
  worldSlug string) — reads `summary.getName()`. Draws toggle square (line
  613-622, `COLOR_SYNC_ENABLED`/`COLOR_SYNC_DISABLED`, tooltip, click-through
  to `hook.isSyncEnabled`) then status square (line 624-646, gated by
  `computeShowStatusIndicator`, drawn via `drawConsolidatedStatusIndicator`
  line 657-680 using `computeConsolidatedStatus` line 542-555). This method's
  signature is local-row-specific (`LevelSummary` parameter) — Requirement 2
  needs a cloud-only-row variant since a `CloudOnlyWorldSummary` is not a
  `LevelSummary` and none of `hook.isSyncEnabled`/`freshnessCache`/
  `conflictCache` are meaningful pre-restore.
- **`pillBounds`** (line 137-145): pure static function of
  `(font, x, width, showResolveButton)` → `int[6]` (`playX, playW, editX,
  editW, resolveX, resolveW`), already shared between `render`/`mouseClicked`
  — directly reusable verbatim for the two new cloud-only pills (Requirement
  4 slots), since it computes slot geometry independent of what the pills
  are labeled/do.
- **`openRestoreFlow`** (line 966-977): `WorldRestoreHookHolder.getOrNull()`
  early-return, `WorldSyncStatusHookHolder.getOrNull()`, constructs
  `new WorldRestoreScreen(summary, restoreHook, statusHook, () -> {
  reload(); MinecraftClient.getInstance().setScreen(owner); })` — today's
  4-arg constructor (already extended once by the download feature to add
  `statusHook`). This is the single call site to extend for Requirement 4's
  5th constructor arg (`onCompleted`).
- **`readLevelDatBatch`** (line 1020-1044): reads `seed`, `Difficulty`→
  `difficulty` string, `allowCommands`→`cheatsEnabled`, `DayTime`→`dayCount`,
  `Version.Name`→`minecraftVersion`, via one `session.readLevelProperties()`
  `Dynamic<?>` root — does **not** currently read `LastPlayed`, `GameType`,
  or `hardcore`. Used both by `WorldsPanel.openConflictScreen` (line
  985-1005, has a real `LevelSummary` in hand so passes real
  `gameModeDisplayName`/`lastPlayedMillis`/`hardcore` separately, not via
  this batch) and by `SteamCloudSyncClientInitializer.onPlayDisconnect`
  (below) as a `Supplier<LevelDatBatch>` passed into `onWorldUnload` — this
  second call site has **no** `LevelSummary` (world already
  disconnected/unloaded), so it is the natural place for Requirement 3b's
  new tag reads to live.
- **`playWorld`** (line 1052-1058): package-private, calls
  `entry.play()` (vanilla `WorldListWidget.WorldEntry#play()`, Yarn-mapped).
  This is 1.21.11's real-Play mechanism; per the spec's Requirement 4
  investigation, 26.1/26.2 use `Minecraft.getInstance().createWorldOpenFlows()
  .openWorld(levelId, () -> {})` instead (their own `WorldsPanel.java:997-999`
  per the spec's citation) — 1.21.11's `entry.play()` call is on an existing
  `WorldListWidget.WorldEntry`, which cannot be constructed for a
  newly-downloaded cloud-only world without loading a fresh `LevelSummary`
  first. **This is the one Requirement-4/Requirement-5 API gap needing
  implementation-time confirmation** (see Risks): 1.21.11 needs its own
  `openWorld`-equivalent callable from just a save-folder-id string, not an
  existing `WorldEntry`.

### `WorldConflictResolutionHook.LevelDatBatch` (`api/.../WorldConflictResolutionHook.java:268-280`)

```java
record LevelDatBatch(Long seed, String difficulty, Boolean cheatsEnabled,
        long dayCount, String minecraftVersion, boolean readable) {
    public static LevelDatBatch unreadable() { ... }
}
```
No `lastPlayedMillis`/`gameMode`/`hardcore` fields today. Two call sites in
`features/steam-cloud-sync/.../WorldSaveSyncService.java`:
`buildAndUploadMetadata` (line 818-849, reads `levelDatBatch.minecraftVersion()`/
`.seed()`/`.difficulty()`, hardcodes `"Unknown"` gameMode and `false` hardcore
at line 839/841, and uses `syncedAtTimestamp` as the `lastPlayedMillis` proxy
at line 836) and `syncWorldNow` (line 949-966, two overloads — the 3-arg one
defaults to `LevelDatBatch.unreadable()`, the 4-arg one threads a real batch
through when the caller has one).

### `syncWorldNow` call sites (all in `WorldSaveSyncService.java`)

1. `onWorldUnload(worldSlug, worldFolder, displayName)` (line 170-172) →
   3-arg `syncWorldNow` → `LevelDatBatch.unreadable()`. Unused today (no
   platform caller found — all three platforms' `onPlayDisconnect` call the
   4-arg overload below).
2. `onWorldUnload(..., Supplier<LevelDatBatch> levelDatBatchSupplier)` (line
   195-202) → `worker.submitBackgroundWork(() -> syncWorldNow(..., levelDatBatchSupplier.get()))`.
   **Real call site**: all three platforms'
   `SteamCloudSyncClientInitializer.onPlayDisconnect` (fabric-1.21.11 line
   320-326, fabric-26.1/26.2 line ~336) call this with
   `() -> readLevelDatBatch(info.worldSlug())` — `readLevelDatBatch` here is
   each platform's own copy in `SteamCloudSyncClientInitializer` (separate
   from `WorldsPanel`'s copy of the same name/logic), invoked lazily on the
   background worker thread, well after the world has fully unloaded/
   disconnected on the client thread. **This is the checkpoint Requirement
   3b's real level.dat reads should extend** — safe (per the existing
   Javadoc at `WorldSaveSyncService.java:174-186`: "the world has already
   been unloaded/disconnected by the time this fires, making a fresh
   `LevelStorageAccess` read safe there").
3. `onWorldSaved` (line 215-228) → 3-arg `syncWorldNow` (sentinel batch) —
   explicitly documented (`WorldSaveSyncService.java:183-186`) as **not**
   safe to extend with a real read: the world is still loaded at this
   checkpoint (`WorldSaveHookMixin` fires mid-session, without unload), so a
   fresh `LevelStorage.Session` read here would race the live session.
   **Stays on the sentinel** — Requirement 3b's fallback path.
4. `checkAndUploadStaleWorldsAtStartup` (line 241-266) → 3-arg `syncWorldNow`
   (sentinel batch), explicit code comment (line 252-262) already discusses
   and defers wiring a real batch through here (would need a new
   `Supplier<LevelDatBatch>` per `KnownWorld`, out of scope per that
   comment). No world is loaded at this checkpoint, so a future real read
   would be safe, but doing so requires a public signature change to
   `checkAndUploadStaleWorldsAtStartup`/`KnownWorld` — **out of scope for
   this plan** (Requirement 3b only asks to fix `syncWorldNow`'s caller,
   which per the Open Question is confirmed-per-platform at planning time;
   this plan's answer: only checkpoint 2 above is upgraded, checkpoints 3/4
   keep degrading to sentinels, exactly as today, which the spec's
   Compatibility section already allows).
5. `handleSyncReenabled` (line 294-317) → 3-arg `syncWorldNow` (sentinel
   batch), same rationale/comment (line 303-311) — **stays on sentinel**,
   same reasoning as (4): no `LevelSummary`/real batch available without a
   public signature change beyond this plan's scope.

**Resolution of the spec's Open Question**: only call site 2
(`onWorldUnload`'s existing `Supplier<LevelDatBatch>` overload, already
wired end-to-end on all three platforms) gets real `lastPlayedMillis`/
`gameMode`/`hardcore` values. Call sites 3/4/5 (`onWorldSaved`,
`checkAndUploadStaleWorldsAtStartup`, `handleSyncReenabled`) keep using
`LevelDatBatch.unreadable()` and its existing sentinel fallbacks — this is
not a regression (they already only get `minecraftVersion`/`seed`/
`difficulty` as `null` today too) and matches the spec's explicit
Compatibility allowance ("just won't show a real game-mode/last-played
until it is re-synced"). A world synced only via `onWorldSaved`/startup/
re-enable will show accurate data the next time it is unloaded normally
(checkpoint 2).

### `CloudOnlyWorldSummary` (`api/.../CloudOnlyWorldSummary.java`)

Record already has every field the spec needs
(`worldSlug, displayName, deviceLabel, syncedAtTimestamp, lastPlayedMillis,
minecraftVersion, seed, gameMode, difficulty, hardcore, iconBase64`) — no
shape change required (spec's Public API section already says so).

### `WorldRestoreScreen` (per platform, e.g. `platform/fabric-1.21.11/src/main/java/de/lazuli/cloudsync/WorldRestoreScreen.java`)

Per the download feature's own plan, constructor today is
`(CloudOnlyWorldSummary summary, WorldRestoreHook restoreHook,
WorldSyncStatusHook statusHook, Runnable onReturn)` — `onReturn` is invoked
at exactly two call sites: natural-completion inside `render()` once
`completed` flips true, and `onCancel()`. This is the class Requirement 4
adds a 5th nullable `Runnable onCompleted` parameter to, per the spec's
"Completion-callback investigation" section — replacing `onReturn.run()`
at the natural-completion site only, when non-null.

### Test precedent

No test files exist for any `*Screen.java` (confirmed by the download
feature's own plan) — `WorldsPanel`/`WorldRestoreScreen` GUI code has zero
existing automated test coverage; the only relevant precedent is
`features/steam-cloud-sync/src/test/java/.../WorldRestoreServiceTest.java`
(JUnit 5 + AssertJ + hand-written fakes, e.g. `FakeWorldArchiveCloudStore`)
for the non-GUI `WorldSaveSyncService`/`WorldRestoreService` layer, and
`SteamCloudSyncConfigIOTest.java` for simple-JSON-IO-style tests. No pure
function in `WorldsPanel` is currently unit-tested in isolation (e.g.
`computeConsolidatedStatus`, `computeBlocked`, `pillBounds` have no test
file despite being `static`/testable) — this plan does not need to invent
new test infrastructure, just extend `WorldSaveSyncServiceTest`-style
coverage for the one behavior change with real test value (Requirement 3b).

## Files to Create

None. Every requirement is satisfied by modifying existing files — no new
class, consistent with the spec's Public API section stating "No new public
API in the `api` module."

## Files to Modify

For **each** of `platform/fabric-1.21.11`, `platform/fabric-26.1`,
`platform/fabric-26.2` (Requirement 5 — same edit pattern per platform,
GUI-API-call differences aside):

### 1. `.../mainmenu/WorldsPanel.java`

**Requirement 1 — shared row-view + shared row-drawing helper.**
- Introduce a small private record, `RowView`, capturing exactly the fields
  both a local and a cloud-only row need to feed into one shared
  drawing method:
  ```java
  private record RowView(
          String worldSlug,          // key for expand-state/hooks
          String displayName,
          String subtitle,           // pre-formatted "<gameMode> · <relativeTime>"
          Identifier iconId,         // resolved icon (real, cloud-server-cache, or fallback)
          boolean isCloudOnly,       // gates the cloud badge overlay + Req 2/4 branches
          Object payload) {}         // WorldListWidget.WorldEntry or CloudOnlyWorldSummary,
                                      // used only by the pill-action callbacks
  ```
  Two small private builder methods construct one per row:
  `RowView forLocal(WorldListWidget.WorldEntry entry)` (reads `LevelSummary`
  fields — `summary.getName()`, `entry.getLevelDisplayName()`,
  `summary.getGameMode().getTranslatableName().getString() + " · " +
  relativeTime(summary.getLastPlayed())`, `iconCache.forWorld(...)`) and
  `RowView forCloudOnly(CloudOnlyWorldSummary cloudOnly)` (Requirement 3
  fallbacks — see below). `render()`'s two loops become: build the `RowView`
  once per row (unchanged iteration order/appending), then call one shared
  `drawRow(context, font, view, x, rowY, rowWidth, rowHeight, expanded,
  mouseX, mouseY)` method containing every drawing statement Requirement 1
  lists (hover fill, icon blit at the existing `iconSize`/`gridSize`
  geometry using `view.iconId()`, name text, subtitle text,
  `drawSyncIcons`-equivalent call, expand/collapse state via
  `state.expandedRowId()` keyed by `view.worldSlug()`, and the pill row).
  The one intentional per-kind branch inside `drawRow` is the cloud badge
  overlay (`if (view.isCloudOnly())`, Requirement 3a) and the pill
  labels/colors/actions (Requirement 4, `if (view.isCloudOnly())` branch
  choosing "Download & Play"/"Download" vs. "Play"/"Edit"/"Resolve").
  `mouseClicked()`'s two loops similarly build the same `RowView` per row
  (or reuse a per-frame-cached list, implementation's choice, since
  `render`/`mouseClicked` are called on the same frame with the same data)
  and funnel into one shared hit-test method mirroring `drawRow`'s branches
  (toggle/status square hit-tests, expand/collapse fallthrough, pill
  hit-tests dispatching to `entry.play()`/`entry.edit()`/`openConflictScreen`
  for a local row or the two new Requirement 4 handlers for a cloud-only
  row).
  This directly satisfies the spec's Requirement 1 closing paragraph
  ("expected to collapse into one shared private render-row helper
  parameterized by a small per-row view record... eliminating the current
  copy-pasted layout constants/branches") without dictating more structure
  than the spec requires — the exact field list/method split above is this
  plan's concrete answer to that "planning-phase decision."

**Requirement 2 — `drawSyncIcons` cloud-only branch.**
- Overload `drawSyncIcons` (currently `(DrawContext, LevelSummary, int rowX,
  int rowY, int rowWidth, int mouseX, int mouseY, boolean rowHovered)`) with
  a `String`-worldSlug variant used only for a cloud-only row, e.g.
  `drawCloudOnlySyncIcons(DrawContext context, String worldSlug, int rowX,
  int rowY, int rowWidth, int mouseX, int mouseY, boolean rowHovered)`,
  reusing the exact same `left`/`top`/`SYNC_ICON_SIZE`/`SYNC_ICON_MARGIN`
  geometry as the existing method (call `syncIconLeft(rowX, rowWidth)`
  verbatim):
  - Toggle square: always `context.fill(..., COLOR_SYNC_DISABLED)`, no
    `hook.isSyncEnabled` read, tooltip `"This world has not been downloaded
    yet."` on hover, no click-through (the existing `mouseClicked`'s toggle
    hit-test for this row branch is simply omitted, per Requirement 2's "no
    `toggleSync` call").
  - Status square: only rendered if
    `WorldSyncStatusHookHolder.getOrNull() != null &&
    statusHook.isDownloadInProgress(worldSlug)` — same
    `COLOR_STATUS_SYNCING_BASE`/`_HIGHLIGHT` animated pair (reuse the
    existing inline phase-computation snippet from
    `drawConsolidatedStatusIndicator`'s `SYNCING` case, lines 666-669, or
    factor that 4-line snippet into a tiny shared `syncingColor()` helper —
    implementation's choice, either satisfies "no duplicated logic" at this
    small a scale), same "Downloading from Steam Cloud..." tooltip text
    (reuse the exact string literal from line 687). Hidden entirely
    otherwise — no call to `computeConsolidatedStatus` (that function's
    `UNSYNCED`/`SYNCED`/`CONFLICT` states are never applicable pre-restore
    per Requirement 2's Non-goals note).
  - Called from `drawRow` in place of the local-row `drawSyncIcons` call
    when `view.isCloudOnly()`.
- `mouseClicked`'s cloud-only-row branch adds no toggle/status hit-tests at
  all (both squares are non-interactive for a cloud-only row per
  Requirement 2's closing bullet) — simplest correct implementation: the
  shared hit-test method just skips the toggle/status hit-test block
  entirely when `view.isCloudOnly()`.

**Requirement 3a — icon fallback + cloud badge, in `RowView.forCloudOnly`.**
- Replace today's `decodeIconBase64OrNull(...)` → flat-`0xFF3399FF`-fill
  fallback (render() line 487-494) with: decode succeeds →
  `iconCache.forServer("cloud:" + cloudOnly.worldSlug(), decodedIconBytes)`
  (unchanged); decode fails/absent → the same `Identifier` a brand-new
  local world with no `icon.png` already resolves to. Concretely: call
  `iconCache.forWorld(cloudOnly.worldSlug(), null)` (or whatever
  `IconTextureCache#forWorld`'s actual no-icon-path signature is —
  **confirm exact `IconTextureCache.forWorld`/`WorldIcon.forWorld` signature
  and null-handling at implementation time**, since this plan's own repo
  read did not open `IconTextureCache.java`; the spec's own Requirement 3a
  text already asserts this fallback path exists and is reachable via
  `LevelSummary.getIconPath()` returning nothing, so the concrete call
  shape needs one implementation-time confirmation, not a redesign).
  This becomes `RowView.iconId()`, so `drawRow`'s icon-blit statement is
  identical for both row kinds (Requirement 1).
- Cloud badge: in `drawRow`, after the shared icon blit, if
  `view.isCloudOnly()`, draw one small (8x8, `SYNC_ICON_SIZE`-scale per the
  spec) overlay `context.fill` (or a tiny dedicated cloud-tinted texture, if
  one exists in this project's asset set — otherwise a flat-fill square in
  a distinct cloud-blue tint, e.g. reusing `0xFF3399FF` — the color the
  fallback used to use, repurposed as the badge tint rather than the whole
  icon) at the icon's bottom-right corner, both in compact and expanded
  icon geometry (two coordinate branches mirroring the existing
  compact-vs-expanded icon-position branching in `render()`).

**Requirement 3b — real level.dat fields at the sync source (not
`WorldsPanel`, see `WorldConflictResolutionHook`/`WorldSaveSyncService`
below).** No `WorldsPanel.java` change needed for 3b itself, but
`SteamCloudSyncClientInitializer.onPlayDisconnect`'s own `readLevelDatBatch`
copy (see below) is a sibling file in the same platform module.

**Requirement 4 — two-pill "Download & Play"/"Download" + detail line.**
- In `drawRow`'s expanded-pill branch, when `view.isCloudOnly()`: reuse
  `pillBounds(font, x, width, /*showResolveButton=*/false)` verbatim (no
  resolve pill ever for a cloud-only row) to get `playX/playW/editX/editW`
  slots; draw left slot as `"Download & Play"` using the existing Play
  green convention (`0xFF528A54`/hover `0xFF64A066`), right slot as
  `"Download"` in a new distinct color constant, e.g.
  `COLOR_DOWNLOAD_ONLY = 0xFF4A6FA5` (a blue distinct from Play-green
  `0xFF528A54`, Edit-gray `0xFF2E2E2E`, and Resolve-purple `0xFFAA33CC` —
  final exact shade is a small cosmetic implementation-time call, this
  value is this plan's concrete proposal) with its own hover shade (e.g.
  `0xFF5C84C2`). `blocked` gate: `statusHook.isDownloadInProgress(worldSlug)`
  (Requirement 2's condition) — when true, both pills render in the
  existing `0xFF4A4A4A` muted style with the existing `"Cannot play or edit
  while syncing with Steam Cloud."` tooltip (reuse `blockedTooltipFor`'s
  literal string, or call a trivial shared constant — this project already
  has that exact string at line 841/852, extract to a `private static
  final String BLOCKED_SYNCING_TOOLTIP` constant referenced from both
  places to guarantee verbatim reuse rather than copy-pasting the literal a
  third time).
- `mouseClicked`'s cloud-only-pill hit-test (mirroring the local row's
  `playX`/`editX` checks at line 888-901): left-slot click → if not
  blocked, call a new private `downloadAndPlay(CloudOnlyWorldSummary
  cloudOnly)` method; right-slot click → if not blocked, call a new private
  `downloadOnly(CloudOnlyWorldSummary cloudOnly)` method. Neither hit-test
  needs a resolve-pill branch (never shown).
- `downloadAndPlay`: mirrors `openRestoreFlow` but passes a non-null
  `onCompleted`:
  ```java
  private void downloadAndPlay(CloudOnlyWorldSummary cloudOnly) {
      WorldRestoreHook restoreHook = WorldRestoreHookHolder.getOrNull();
      if (restoreHook == null) return;
      WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
      MainMenuScreen.playClickSound();
      MinecraftClient.getInstance().setScreen(new WorldRestoreScreen(cloudOnly, restoreHook, statusHook,
              () -> launchWorld(cloudOnly.worldSlug()),                 // onCompleted
              () -> { reload(); MinecraftClient.getInstance().setScreen(owner); })); // onReturn, unchanged
  }
  ```
  (exact constructor parameter order TBD to match the modified
  `WorldRestoreScreen` signature below — this plan places `onCompleted`
  immediately before `onReturn`, matching the spec's own ordering in its
  Public API section).
- `downloadOnly`: starts the identical background restore without ever
  constructing `WorldRestoreScreen` — reuses
  `WorldSyncStatusHook.markDownloadPending`/`beginRestore`/
  `markDownloadFinished` the same way `WorldRestoreScreen.init()`/its
  listener already do (this plan's concrete mechanism: extract that
  pending/begin/finished bracketing, currently inline in
  `WorldRestoreScreen`, into a small reusable private helper *on
  `WorldsPanel`* rather than trying to reuse `WorldRestoreScreen`'s private
  internals from outside the class — i.e. `downloadOnly` duplicates the
  ~10-line `markDownloadPending` → `beginRestore(worldSlug, listener)` →
  listener's `markDownloadFinished` in `onComplete`/`onFailed` shape
  directly in `WorldsPanel`, since `WorldRestoreScreen`'s equivalent logic
  is private to that class and this call path never wants a screen at all):
  ```java
  private void downloadOnly(CloudOnlyWorldSummary cloudOnly) {
      WorldRestoreHook restoreHook = WorldRestoreHookHolder.getOrNull();
      if (restoreHook == null) return;
      WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
      String worldSlug = cloudOnly.worldSlug();
      if (statusHook != null) statusHook.markDownloadPending(worldSlug);
      MainMenuScreen.playClickSound();
      restoreHook.beginRestore(worldSlug, new RestoreProgressListener() {
          @Override public void onProgress(RestoreProgress progress) { /* no-op, no screen watching */ }
          @Override public void onComplete() {
              if (statusHook != null) statusHook.markDownloadFinished(worldSlug);
              reload();
          }
          @Override public void onFailed(String reason) {
              if (statusHook != null) statusHook.markDownloadFinished(worldSlug);
              LazuliMod.LOGGER.warn("Background-only download of cloud-only world \"" + worldSlug + "\" failed: " + reason);
          }
      });
  }
  ```
  (mirrors `WorldConflictScreen.onKeepCloud`'s already-proven
  pending/begin/finished bracketing pattern, per the download plan's own
  "Existing Implementation" findings — this is the same ownership model,
  just invoked with no screen at all rather than from `WorldRestoreScreen`).
- `launchWorld(String worldSlug)`: per-platform (Requirement 5) —
  1.21.11 needs its own resolution (see Risks); 26.1/26.2 call
  `Minecraft.getInstance().createWorldOpenFlows().openWorld(worldSlug, ()
  -> {})` per the spec's own citation of `WorldsPanel.java:997-999` in
  those modules.
- Device-label/synced-at detail line (demoted from the old cloud-only
  subtitle): shown as one extra `context.drawText` line in the expanded
  body only, below the pill row or in the space where a local row has none
  (this plan's choice, per the spec's "either satisfies" allowance — a
  tooltip on hover is a viable alternative but a static extra line is
  simpler to implement and test visually, and only needs one more `if
  (expanded && view.isCloudOnly())` branch in `drawRow`), formatted
  `deviceLabel + " · Synced " + formatSyncedAt(syncedAtTimestamp)`, reusing
  the existing `formatSyncedAt` helper (line 318-320) verbatim.

### 2. `.../cloudsync/WorldRestoreScreen.java`

- Constructor gains a 5th parameter, `Runnable onCompleted` (nullable),
  inserted before the existing trailing `Runnable onReturn`:
  `WorldRestoreScreen(CloudOnlyWorldSummary summary, WorldRestoreHook
  restoreHook, WorldSyncStatusHook statusHook, Runnable onCompleted,
  Runnable onReturn)`. Store as a new field `this.onCompleted = onCompleted;`
  (nullable, no `Objects.requireNonNull`, mirroring the existing nullable
  `statusHook` field's own no-guard-at-construction pattern).
- Natural-completion call site (inside `render()`, guarded by `completed`,
  per the download plan's finding at "line ~124" for 1.21.11): change
  `onReturn.run();` to:
  ```java
  if (onCompleted != null) {
      onCompleted.run();
  } else {
      onReturn.run();
  }
  ```
- `onCancel()` call site: **unchanged** — still always calls `onReturn.run()`
  (after the existing FR6.5 log call, download-feature-shipped), never
  `onCompleted`.
- No change to the existing `markDownloadPending`/`markDownloadFinished`
  bracketing already added by the download feature — `onCompleted` is
  purely an additional "what to do after the world exists locally" hook,
  orthogonal to the pending/finished status tracking.

### 3. `features/steam-cloud-sync/.../services/WorldSaveSyncService.java`

- No public-signature change (internal-only, per spec's Public API
  section). `buildAndUploadMetadata` (line 818-849): replace the hardcoded
  `"Unknown"`/`false` at line 839/841 with `levelDatBatch.gameMode()`/
  `levelDatBatch.hardcore()` (new accessors on the extended `LevelDatBatch`,
  see below), each falling back to `"Unknown"`/`false` when the batch is
  `LevelDatBatch.unreadable()` or the new fields are otherwise null/absent
  (i.e. the accessor itself can return the raw nullable value and
  `buildAndUploadMetadata` applies `levelDatBatch.gameMode() != null ?
  levelDatBatch.gameMode() : "Unknown"`, same pattern already used for
  `minecraftVersion`/`seed`/`difficulty` at lines 837-840). Replace the
  `syncedAtTimestamp` proxy at line 836 (the `lastPlayedMillis` field
  position in the `WorldCloudMetadata` constructor call) with
  `levelDatBatch.lastPlayedMillis() >= 0 ? levelDatBatch.lastPlayedMillis()
  : syncedAtTimestamp` (fallback to today's proxy when the batch has no
  real value, mirroring `CloudOnlyWorldSummary`'s own documented `-1`
  sentinel convention for "no metadata").

### 4. `api/src/main/java/de/lazuli/api/cloudsync/WorldConflictResolutionHook.java`

**Design decision (spec's Open Question / Public API section choice):
additive fields on the existing `LevelDatBatch` record, not a sibling
type.** Rationale: `LevelDatBatch` is already a small, flat, Minecraft-free
record used as a single unit by exactly two production call sites
(`buildAndUploadMetadata` and `WorldConflictScreen`'s `detailFor`-adjacent
construction at `WorldsPanel.openConflictScreen`, which builds its own
`lastPlayedMillis`/`gameMode`/`hardcore` from a real `LevelSummary` today,
separately from the batch) plus `unreadable()`'s sentinel factory. Adding 3
fields keeps one type, one `unreadable()` sentinel, and avoids a second
parallel type that would need its own conversion/adapter wherever both are
in scope. The extended record:
```java
record LevelDatBatch(
        Long seed,
        String difficulty,
        Boolean cheatsEnabled,
        long dayCount,
        String minecraftVersion,
        boolean readable,
        long lastPlayedMillis,   // new; -1 = unavailable, mirrors CloudOnlyWorldSummary's own convention
        String gameMode,         // new; null = unavailable
        boolean hardcore) {      // new; false = unavailable (same "false is also the real default" caveat CloudOnlyWorldSummary already documents)

    public static LevelDatBatch unreadable() {
        return new LevelDatBatch(null, null, null, -1L, null, false, -1L, null, false);
    }
}
```
This is a source-incompatible change to every existing call site that
constructs a `LevelDatBatch` with the old 6-arg constructor — grep shows
exactly one non-`unreadable()` construction site
(`WorldsPanel.readLevelDatBatch`, one per platform, 3 total) plus 3×
`unreadable()` call sites already handled by the factory method update
above — all 3 platforms' `readLevelDatBatch` in `WorldsPanel.java` need
their `return new LevelDatBatch(...)` call updated to the 9-arg form,
passing `-1L, null, false` (this call site has no `LastPlayed`/`GameType`/
`hardcore` reads of its own today — `WorldsPanel.openConflictScreen`
already sources those three fields separately from a real `LevelSummary`,
not from this batch — so `WorldsPanel`'s own `readLevelDatBatch` keeps
returning "unavailable" for the 3 new fields; only
`SteamCloudSyncClientInitializer`'s separate `readLevelDatBatch` copy,
below, gains real reads).

### 5. Per platform — `SteamCloudSyncClientInitializer.java`'s own `readLevelDatBatch` method

(fabric-1.21.11 line ~324's call site `() -> readLevelDatBatch(info.worldSlug())`
— confirm this class's own `readLevelDatBatch` method body per platform,
likely near-identical to `WorldsPanel`'s copy since both do the same
`session.readLevelProperties()` `Dynamic<?>` read): extend the existing
`Dynamic<?> data = root.get("Data")...` read (already open in this method)
with three more field reads, reusing the exact same DataFixerUpper
`Dynamic<?>` API already in use one line above (per Requirement 3b's
explicit direction to reuse "the existing DataFixerUpper `Dynamic<?>` read
machinery... rather than introducing a second parsing approach"):
- `LastPlayed`: `data.get("LastPlayed").asNumber().result().map(Number::longValue).orElse(-1L)`.
- Game mode: `data.get("GameType").asNumber().result().map(Number::intValue)`,
  mapped to the same display strings `LevelSummary.getGameMode()` would
  produce (`Survival`/`Creative`/`Adventure`/`Spectator` — **confirm the
  exact `GameType` int-to-name mapping and capitalization against
  `LevelSummary`'s actual `getGameMode().getTranslatableName().getString()`
  output at implementation time**, since this plan's repo read did not open
  vanilla's `GameType`/`GameMode` enum source — likely
  `0=Survival,1=Creative,2=Adventure,3=Spectator` per vanilla's known NBT
  convention, but the exact display-string casing used elsewhere in this
  codebase, e.g. `WorldsPanel`'s own subtitle format, must match exactly
  for Requirement 2's "same fields, formatted the same way" to hold when a
  cloud-only world with real data is later compared side-by-side with a
  local one). Falls back to `null` on any parse failure, consistent with
  every other field in this batch.
- `hardcore`: `data.get("hardcore").asBoolean().result().orElse(false)`.
- Pass all three into the extended `LevelDatBatch` constructor's new
  trailing 3 args at this method's existing `return new LevelDatBatch(...)`
  statement, in the same `try` block as the existing 6 fields (one read,
  same `Dynamic<?>` root, per Requirement 3b's explicit reuse direction —
  no second `LevelStorage.Session` open).
- The existing `catch (Exception e)` fallback to `LevelDatBatch.unreadable()`
  already covers all 3 new fields' failure mode for free (Requirement 3b's
  "any read failure... falls back to today's sentinels").

## Risks

- **1.21.11's "Download & Play" launch mechanism is not yet confirmed
  against a real `WorldEntry`-free API.** 26.1/26.2 have a proven
  `createWorldOpenFlows().openWorld(levelId, () -> {})` call needing only a
  save-folder-id string (already used by `HomePanel`'s Recent-play action
  per the spec's own citation). 1.21.11's only proven real-Play mechanism
  found in this repo is `entry.play()` on an existing
  `WorldListWidget.WorldEntry` (`WorldsPanel.playWorld`, line 1052-1058) —
  which requires an already-constructed list entry, not just a slug string,
  and a cloud-only world has no such entry until after `reload()` re-scans
  the saves folder. The spec itself flags this as needing implementation-
  time confirmation ("must be confirmed against that module's own resolved
  jar during planning/implementation"); this plan's own repo read did not
  locate 1.21.11's `createWorldOpenFlows`-equivalent (likely
  `net.minecraft.client.gui.screen.world.WorldOpenFlows` under Yarn
  mapping, given 26.1/26.2's own class name is already close to a Yarn-ish
  name) — **implementation must `javap`/decompile-check this module's own
  resolved Minecraft jar for a `WorldOpenFlows`-equivalent class before
  writing `launchWorld` for this platform**, and if none exists, fall back
  to: after `restoreHook`'s `onComplete` fires, call `reload()` to
  re-populate `entries` with the newly-real world, find the matching
  `WorldListWidget.WorldEntry` by slug, and call `entry.play()` on it (a
  slightly heavier but definitely-available fallback using only already-
  proven APIs).
- **Game-mode int→display-string mapping for the new `readLevelDatBatch`
  read** (Files to Modify #5) is asserted from general vanilla NBT
  knowledge, not confirmed against this repo's actual `LevelSummary`/
  `GameMode` usage — must match `summary.getGameMode().getTranslatableName()
  .getString()`'s exact output strings for a re-synced cloud-only world's
  subtitle to read identically to a local world's, confirmed at
  implementation time.
- **`IconTextureCache.forWorld`'s exact no-icon fallback call shape**
  (Requirement 3a) is asserted from the spec's own investigation, not
  independently re-verified by this plan's repo read (the file itself
  wasn't opened) — low risk since the spec already cites the confirming
  investigation, but implementation should re-confirm the exact
  `forWorld(String, <icon-path-type>)` signature and what "no icon" looks
  like as an argument (likely `null` or an already-established sentinel)
  before calling it with a synthetic cloud-only world.
- **`LevelDatBatch`'s additive-field change is source-breaking for all
  existing constructors of the type** (6-arg → 9-arg) — contained (3
  `WorldsPanel.readLevelDatBatch` call sites + `unreadable()`'s own
  internal construction, all identified above and all inside this repo, no
  out-of-tree consumers since this is an internal API), but every one of
  the 3 platform `WorldsPanel.java` files' `readLevelDatBatch` method must
  be updated in the same change or that platform fails to compile.
- **Shared `RowView`/`drawRow` refactor is the largest mechanical change**
  (touches every drawing/hit-test statement in `WorldsPanel.render()`/
  `mouseClicked()`) — highest regression risk to *local* row rendering
  (which must remain pixel-identical to today), not just cloud-only rows.
  Mitigated by doing this refactor first (see Order of Implementation) and
  manually re-verifying every existing local-row behavior (hover, expand/
  collapse, Play/Edit/Resolve pills, toggle/status squares, blocked-tooltip
  states) before layering cloud-only-specific branches on top.
- **"Download" pill's screen-less background restore duplicates a small
  amount of logic already in `WorldRestoreScreen`'s `init()`/listener**
  (the `markDownloadPending`→`beginRestore`→`markDownloadFinished`
  bracketing) rather than extracting a shared helper both call — accepted
  as a small, bounded duplication (mirrors `WorldConflictScreen.onKeepCloud`'s
  own already-existing independent copy of the same pattern, per the
  download plan's findings, so this is consistent with, not a new
  departure from, this codebase's existing convention of each screen/
  trigger owning its own copy of this short bracketing idiom).
- **`isDownloadInProgress` keying assumption**: Requirement 2/4 both assume
  `worldSlug` is the correct/only key `WorldSyncStatusHook` uses for a
  cloud-only world's in-progress state — already true per the download
  feature's own FR2.5 confirmation (its plan explicitly verified this), so
  low risk, but worth a quick re-confirmation during implementation since
  this is now read by two new call sites (`drawCloudOnlySyncIcons`, the new
  pills' `blocked` computation) rather than one.

## Dependencies

No new external (non-Fabric, non-project) dependencies. Every type used
(`Identifier`, `DrawContext`/`GuiGraphicsExtractor`, `RestoreProgressListener`,
`WorldSyncStatusHook`, `WorldRestoreHook`, `LevelDatBatch`,
`CloudOnlyWorldSummary`) already exists in this repo's `api`/platform
modules; no `build.gradle` changes needed for any of the 3 platform modules
or `features/steam-cloud-sync`.

## Test Strategy

1. **`WorldSaveSyncServiceTest`-style unit test (new test method, extending
   the existing `features/steam-cloud-sync/src/test/java/.../WorldSaveSyncServiceTest.java`
   if one exists, else `WorldRestoreServiceTest`'s sibling pattern)** — the
   one genuinely unit-testable behavior change: construct a `LevelDatBatch`
   with real `lastPlayedMillis`/`gameMode`/`hardcore` values, call
   `syncWorldNow(worldSlug, worldFolder, displayName, levelDatBatch)`
   against a fake `CloudFileStore`, and assert the serialized
   `WorldCloudMetadata` written contains those exact values (not
   `"Unknown"`/`false`/the synced-at proxy) — plus a companion test with
   `LevelDatBatch.unreadable()` asserting the existing sentinel fallback
   behavior is preserved unchanged (regression coverage for the "else"
   branch this plan adds).
2. **`LevelDatBatch` unit test** (new or extended small test, `api/src/test/java/...`
   if a test source set exists there per the download plan's own note that
   this is `api`'s first test file — otherwise a `features/steam-cloud-sync`
   test referencing the type is sufficient): `unreadable()` still returns
   all-sentinel values including the 3 new fields; the 9-arg constructor
   compiles and round-trips.
3. **Pure-function tests for any newly-extracted static helpers** — if
   `pillBounds` reuse or a new small `computeShowStatusIndicator`-style
   cloud-only status-visibility function is extracted as a `static`
   package-private function (per this plan's Requirement 2 design), it can
   be tested the same way `computeConsolidatedStatus`/`computeBlocked`
   already are structured to allow (package-private static, pure) — though
   note neither of those currently has a test file in this repo either, so
   this is "possible, not precedented" rather than filling an existing gap.
4. **Manual/visual verification (primary strategy for `WorldsPanel`/
   `WorldRestoreScreen` — no automated GUI test harness exists in this repo
   for any `Screen`/panel class, confirmed by both this plan's and the
   download plan's own findings)**, per platform (all 3):
   - Side-by-side visual diff: a local row and a cloud-only row at the same
     expand state look pixel-identical except the cloud badge (Requirement
     1/3a).
   - Cloud-only row's toggle square always gray/non-interactive; status
     square appears only mid-download with the existing syncing
     animation/tooltip, and is absent otherwise (Requirement 2).
   - A cloud-only world synced by this feature's updated code shows its
     real game mode and a real relative last-played time (not "Unknown"/
     "just now" for an old world) after being re-synced once (Requirement
     3b) — verify by unloading a world, checking the uploaded metadata
     file's contents (or the row after another device downloads it).
   - Expand a cloud-only row: two pills read "Download & Play"/"Download",
     no Edit, no Resolve Cloud Conflict pill ever (Requirement 4).
   - Press "Download": returns immediately to the Worlds tab, row shows
     blocked/syncing state until the background download finishes, world
     never launches, `WorldRestoreScreen` is never opened (verify via log
     absence of that screen's own render-loop side effects, or simply that
     no screen transition is visually observed).
   - Press "Download & Play": opens the existing progress screen
     (`WorldRestoreScreen`), on natural completion the world launches
     directly (no return to Worlds tab first); press Cancel instead
     mid-download on a separate run: returns to Worlds tab immediately,
     download continues in background, world does *not* launch when it
     finishes later (must reload the tab to see it as a real row).
   - Press either pill while a download for that slug is already running:
     both blocked/gray with the existing tooltip, no-op.
   - Confirm the demoted device-label/synced-at info is still visible
     somewhere (expanded detail line, per this plan's choice).
5. **Static verification**: `git diff` review confirming `WorldConflictScreen.java`
   (all 3 platforms) is untouched (spec's Non-goals — this feature doesn't
   touch conflict-resolution flow at all), and that no `CloudOnlyWorldsHook`/
   `CloudOnlyWorldDetector`/`WorldCloudMetadata`/`WorldCloudMetadataIO` file
   changed (spec's Non-goals — detection/schema unchanged).

## Acceptance Criteria

- All 5 Requirements in the spec are satisfied on all 3 platforms
  (`fabric-1.21.11`, `fabric-26.1`, `fabric-26.2`):
  - Requirement 1: cloud-only and local rows share one drawing code path
    (`drawRow`/`RowView` or this plan's equivalent), verified by `git diff`
    showing the old duplicated cloud-only drawing block removed, not just
    added-to.
  - Requirement 2: cloud-only row's toggle square always disabled/
    non-interactive; status square shown only while
    `isDownloadInProgress`, with the existing syncing color/tooltip.
  - Requirement 3a: broken/absent icon falls back to vanilla's real
    default-icon texture (not a flat placeholder), always with a cloud
    badge overlay on a cloud-only row regardless of icon source.
  - Requirement 3b: `WorldSaveSyncService.buildAndUploadMetadata` receives
    and uses real `lastPlayedMillis`/`gameMode`/`hardcore` at the
    `onWorldUnload` checkpoint (call site 2 above); other checkpoints
    unchanged (still sentinel-based), matching this plan's resolved Open
    Question answer.
  - Requirement 4: expanded cloud-only row shows exactly "Download & Play"/
    "Download" (never Edit/Resolve); "Download & Play" launches the world
    on natural completion only (never on Cancel); "Download" never opens
    `WorldRestoreScreen`; both pills share the existing blocked-while-
    downloading gate/tooltip.
  - Requirement 5: identical behavior/appearance across all 3 platforms,
    with each platform's own real API calls for icon rendering, sync
    status, and world-launch (1.21.11 vs. 26.1/26.2's `createWorldOpenFlows`
    difference resolved per the Risks section).
- `LevelDatBatch`'s additive fields compile and are used consistently
  across all 3 platforms' `readLevelDatBatch`-named methods (`WorldsPanel`'s
  copy stays sentinel-only for the 3 new fields; `SteamCloudSyncClientInitializer`'s
  copy gains real reads) and in `WorldSaveSyncService`.
- `WorldRestoreScreen`'s constructor change (new nullable `onCompleted`
  parameter) is applied identically across all 3 platforms, with
  `WorldsPanel`'s `openRestoreFlow`/new `downloadAndPlay` call sites passing
  the correct argument (`null`/no 5-arg overload use for `openRestoreFlow`
  if it remains a plain click-to-restore path with no launch semantics —
  **implementation-time decision**: `openRestoreFlow` itself is superseded
  by `downloadAndPlay`/`downloadOnly` per Requirement 4's two-pill design,
  so it may be deleted rather than kept as a third code path — see note
  below).
- `WorldConflictScreen` (all 3 platforms) is byte-for-byte unmodified.
- No change to `CloudOnlyWorldsHook`/`CloudOnlyWorldDetector`/
  `CloudOnlyWorldsFacade`/`WorldFingerprintCache`/`WorldCloudMetadata`/
  `WorldCloudMetadataIO` (Non-goals).
- All new/modified files compile per-platform independently; existing test
  suites continue to pass unmodified except the new/extended
  `WorldSaveSyncServiceTest` coverage for Requirement 3b.

**Implementation-time note on `openRestoreFlow`'s fate**: this plan's
Requirement 4 design replaces the single-click-anywhere `openRestoreFlow`
trigger with two explicit pills (`downloadAndPlay`/`downloadOnly`), per
Requirement 1's "expand/collapse instead of click-anywhere" and
Requirement 4's two-pill design. `openRestoreFlow` itself should be deleted
(not left as dead code) once no call site references it, with
`downloadAndPlay` absorbing its exact `WorldRestoreScreen` construction
shape (plus the new `onCompleted` argument) — flagged here rather than left
implicit, since removing a method is a slightly more visible diff than
this plan's other purely-additive changes.
