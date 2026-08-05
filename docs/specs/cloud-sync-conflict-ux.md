# Cloud Sync Conflict UX Specification

## Overview

This spec builds on two just-landed pieces of work: `docs/specs/cloud-sync-status-ui-simplify.md` (which
consolidated the Worlds-tab row's sync indicators into one toggle square + one four-state consolidated
status square, including a Conflict state) and a bug fix to `WorldSaveSyncService.checkConflictFor` (removal
of a device-label short-circuit) plus the new RAM-only `WorldFingerprintCache` that replaced an on-disk
cloud-fingerprint cache — conflicts are now correctly detected in practice, which surfaces two UX gaps that
were previously mostly theoretical:

1. A world in the Conflict state can still be Played and Edited from the Worlds tab, racing the eventual
   conflict resolution and risking further divergence or data loss.
2. `WorldConflictScreen` (identical in shape across `platform/fabric-1.21.11`, `platform/fabric-26.1`,
   `platform/fabric-26.2`) shows only four facts total — local last-modified + size, cloud synced-at +
   device label — which is too thin a basis for a player to confidently choose "Keep Local" vs "Keep Cloud"
   when both copies are real, both changed, and the consequence of choosing wrong is silently discarding a
   play session.

This spec covers three requirement groups: (1) disabling Play/Edit during Conflict with a new third
per-row action button that opens the conflict screen, (2) a substantially richer metadata set surfaced by
`WorldConflictResolutionHook`/`ConflictDetail`, and (3) a full layout redesign of `WorldConflictScreen` into
two symmetric, field-aligned columns with match/mismatch value coloring.

This is a UI + one-hook-surface change. It does not alter `WorldSaveSyncService`'s conflict-detection
algorithm, `WorldFingerprintCache`, `WorldSyncAncestor` persistence, or the "Keep Local"/"Keep Cloud"
resolution actions' underlying behavior (upload-local vs restore-from-cloud) — only what is displayed
before the player chooses, and whether Play/Edit are reachable while a conflict is pending.

## Goals

- While a world's consolidated status (per `cloud-sync-status-ui-simplify` FR-1) is **Conflict**, that
  world's Play and Edit buttons are visually disabled and non-clickable, using the same disabled-button
  pattern already shipped for the Syncing state (muted fill, dimmed label, no hover-react, explanatory
  tooltip, click no-op).
- A third button, "Resolve Cloud Conflict", appears on an expanded world row **only** when that world is in
  the Conflict state, and opens `WorldConflictScreen` exactly as the existing consolidated-status-square
  click already does.
- `WorldConflictResolutionHook.ConflictDetail` (and its `detailFor` producer in `WorldSaveSyncService`) is
  extended with a materially richer, honestly-scoped set of local-vs-cloud metadata fields, each vetted for
  whether it is cheap/already-available or requires new plumbing, with expensive/fragile fields explicitly
  rejected rather than half-built.
- `WorldConflictScreen` is redesigned into two vertically-split, symmetric boxes ("Local save" / "Latest
  Steam Cloud save"), both showing the identical ordered field list, with per-field value coloring: grey
  when both sides' values for that field match exactly, yellow when they differ. Field *keys* are never
  colored.

## Non-goals

- No change to `WorldSaveSyncService.checkConflictFor`'s detection algorithm, `WorldFingerprintCache`, or
  `WorldSyncAncestor`/`world-sync-ancestor-cache.json` persistence — this spec is strictly about what is
  displayed and what is clickable once a conflict is already detected.
- No change to the "Keep Local"/"Keep Cloud" resolution actions' underlying effects (`resolveKeepLocal`
  re-uploads; "Keep Cloud" still drives `WorldRestoreHook.beginRestore` then
  `recordKeepCloudResolution`) — only the screen's presentation of the choice changes.
- No downloading, extracting, or partially reading a cloud-side archive's *contents* (chunk data, block
  data, entity data) merely to populate a richer metadata field for the conflict screen. Every new cloud-side
  field this spec adds must be obtainable from data already resident locally (a fingerprint/ancestor record)
  or from a *metadata-only* Steam Cloud call (`fileSize`, `fileTimestamp`) — never a `beginAsyncRead` of the
  archive body before the player has chosen a side. This is the spec's one hard scope boundary; see F9/F10
  for the fields this rules out.
- No change to the Worlds tab's consolidated-status-square rendering or its own tooltip content
  (`cloud-sync-status-ui-simplify` FR-1/FR-2/FR-5) — this spec only adds a new third button and a Play/Edit
  disable rule alongside it; the square itself, its color, and its own click-to-open-conflict-screen
  behavior are unchanged.
- No new Steam Cloud API surface beyond what `CloudFileStore`/`WorldArchiveCloudStore` already expose
  (`fileSize`, `fileTimestamp`, `getQuota`) — see F9 on why per-file cloud size for a single-archive-per-world
  model is already free, and why a "cloud file count" field is a non-field (always 1) rather than something
  to build.
- No localization/i18n work — tooltip and button strings are plain literals, matching this codebase's
  existing convention throughout `WorldsPanel`/`WorldConflictScreen`.
- No redesign of `WorldConflictScreen`'s "Keep Local"/"Keep Cloud" buttons' own visual style, their position
  relative to each other, or the screen's title bar — only the metadata-display area above them is
  redesigned, and their vertical position shifts down to make room (see UI section).

## Requirements

### Current-state findings

- **F1** `WorldsPanel.java`'s expanded-row Play/Edit rendering (`platform/fabric-26.2/.../WorldsPanel.java:336-361`)
  already has a `rowSyncing` branch (added by `cloud-sync-status-ui-simplify` FR-4) that renders both
  buttons with a shared muted grey fill `0xFF4A4A4A`, dimmed label color `0xFF908C7F`, no hover-react, and a
  shared tooltip `"Cannot play or edit while syncing with Steam Cloud."` via `isRowSyncing(worldSlug)`
  (`WorldsPanel.java:605-611`, checks `isUploadInProgress`/`isDownloadInProgress`). This exact pattern is the
  direct precedent this spec's FR-1 reuses for Conflict — `isRowSyncing` deliberately does **not** check
  `WorldConflictHook` today (`cloud-sync-status-ui-simplify` FR-4.4 explicitly decided Conflict alone must
  not gate Play/Edit, since a pending-but-unopened conflict has no operation actively running) — this spec
  reverses that specific decision per the user's new explicit ask.
- **F2** `mouseClicked`'s Play/Edit hit-tests (`WorldsPanel.java:631-652`) both compute `rowSyncing` once and
  gate on it identically; the toggle-square and consolidated-status-square hit-tests are separate, later in
  the same method (`WorldsPanel.java:658-677`), including the existing Conflict-only click-to-open at
  `WorldsPanel.java:667-676` (`conflictCache.getOrDefault(...) == ConflictStatus.CONFLICT`), which calls
  `openConflictScreen(summary)` (`WorldsPanel.java:727-`).
- **F3** `pillBounds(Font, x, width)` (`WorldsPanel.java:119-`) returns a 4-element array `[playX, playW,
  editX, editW]`, computed by measuring `"Play"`/`"Edit"` text width plus `PILL_PADDING` on each side. It
  currently lays out exactly two pills; a third pill requires either widening this method's return shape
  (e.g. a `PillLayout` record/6-element array) or a parallel `resolvePillBounds` computed only for
  conflicted rows. Both render (`WorldsPanel.java:338`) and click (`WorldsPanel.java:633`) call this method,
  so both call sites need the extended shape.
- **F4** `openConflictScreen(LevelSummary)` (`WorldsPanel.java:727-`) already does exactly what a "Resolve
  Cloud Conflict" button needs: reads `WorldConflictResolutionHook`/`WorldRestoreHook` via their Holders,
  no-ops if either is null (mirrors `FR-A.6`'s null-hook convention), and constructs `WorldConflictScreen`.
  The new button's click handler is a direct call to this existing method with no new plumbing.
- **F5** `WorldConflictResolutionHook.ConflictDetail` (`api/src/main/java/de/lazuli/api/cloudsync/WorldConflictResolutionHook.java:83-90`)
  is currently a 6-field record: `worldSlug`, `displayName`, `localLastModifiedMillis`, `localSizeBytes`,
  `cloudDeviceLabel`, `cloudSyncedAtTimestamp`. `detailFor(...)` is implemented in
  `WorldSaveSyncService` (not read in this pass in full, but its two current fields' provenance is
  self-evident: `localLastModifiedMillis`/`localSizeBytes` from a folder walk of the world save directory;
  `cloudDeviceLabel`/`cloudSyncedAtTimestamp` from the current `WorldFingerprint` for this slug via
  `WorldFingerprintCache`).
- **F6** `WorldConflictScreen.java` (all three platforms, confirmed identical package/shape;
  `fabric-26.2` read in full, `fabric-26.1`/`fabric-1.21.11` assumed to mirror per this feature's
  established per-module-parity convention — to be diffed at implementation time same as prior specs) renders,
  in `extractRenderState` (`WorldConflictScreen.java:110-154`): a centered one-line summary text, then (if
  `detail != null`) two centered single-line strings — `"Local: last changed {date} ({size} MB)"` and
  `"Cloud: last synced {date} by \"{deviceLabel}\""` — stacked at `height/2 - 20` and `height/2`, followed
  by an optional in-progress phase line at `height/2 + 24`. This is **not** currently a two-column layout at
  all — it is two single centered lines, one per side, sequential top-to-bottom, not left/right. "Redesign
  into two symmetric side-by-side boxes" is new layout work, not a refinement of an existing two-column
  scheme.
- **F7** `Button.builder(...).bounds(width/2 - 110, height/2 + 50, 100, 20)` /
  `.bounds(width/2 + 10, height/2 + 50, 100, 20)` (`WorldConflictScreen.java:102-107`) place "Keep Local"
  and "Keep Cloud" at a fixed `y = height/2 + 50`, symmetric around center with a 20px gap. This vertical
  offset assumes the metadata block above it ends well before `height/2 + 50` — the redesign's taller
  field list (F9's expanded field count) requires recomputing this offset relative to the new box height,
  not keeping it pinned to `height/2 + 50` literally (see UI section).
- **F8** `formatInstant`/`formatMb` (`WorldConflictScreen.java:193-202`) are the only two formatting helpers
  today; a richer field set needs a few more (duration/playtime formatting, boolean-to-"Yes"/"No", a
  no-value placeholder such as `"Unknown"` for fields that legitimately cannot be read on one side, e.g. a
  cloud-only world's local fields before it has ever synced down — not expected to occur for a genuine
  two-sided conflict, since a conflict by definition requires both a local and a cloud fingerprint to exist,
  but implementation should not crash if a field read throws/returns a sentinel).

### New metadata fields (per user requirement 2)

- **F9 (fields with existing, already-cheap data — add now).**
  | Field | Local source | Cloud source | Notes |
  |---|---|---|---|
  | Last-modified / last-synced-at timestamp | already have (`localLastModifiedMillis`) | already have (`cloudSyncedAtTimestamp`) | keep, reformat into new layout |
  | Size (bytes/MB) | already have (`localSizeBytes`, folder walk) | **new but trivial**: `WorldArchiveCloudStore.fileSize(fileName)` for this world's one archive file — already-implemented interface method, just not currently threaded into `ConflictDetail` | cloud size is the *compressed archive* size, not the uncompressed folder size — label clearly as "Cloud archive size" vs "Local folder size" to avoid an apples-to-oranges-looking mismatch that isn't really a discrepancy |
  | World display name | `LevelSummary.getLevelName()` (already read for `displayName` param) | `WorldFingerprint.displayName()` (already stored) | trivial, both sides already flow through existing params |
  | Device label (who produced this side) | current device's own label, via the same `DeviceLabelResolver` `WorldSyncStatusHook`/`WorldSaveSyncService` already use for outbound fingerprints | already have (`cloudDeviceLabel`) | local side's own label isn't currently in `ConflictDetail` at all — trivial add, same resolver call already made elsewhere in this feature |
  | Last-synced-by-this-device / ancestor timestamp | `WorldSyncAncestor.syncedAtTimestamp()` for this slug, already persisted and read elsewhere in this feature (`WorldSyncAncestorIO`) | n/a (this is inherently a local-only fact: "when did *this device* last agree with the cloud") | genuinely useful for judging staleness — shown as a single extra local-only line, not a paired field (see UI section for how unpaired fields are handled) |
  | Game mode | `LevelSummary.getGameMode()` (already read for the row subtitle, `WorldsPanel.java:330`) | **not available without new plumbing** — no per-fingerprint game-mode field exists today | local-only field unless new cloud-side plumbing is added (see F10) — pair only if/when cloud side is added; otherwise show local-only |
  | Last-played timestamp | `LevelSummary.getLastPlayed()` (already read for the row subtitle) | not applicable to the cloud archive itself (the archive has a sync timestamp, not a "last played inside Minecraft" timestamp unless read from the archived level.dat, which is new plumbing, see F10) | local-only field, useful context even unpaired |
  | Hardcore flag | `LevelSummary.isHardcore()` (vanilla API, no new read cost — `LevelSummary` is already loaded for every row) | not applicable/available without new plumbing | local-only |

- **F10 (fields that would need new plumbing — evaluated, most rejected for this pass on cost/fragility
  grounds, a few accepted as small and safe).**
  | Field | Why it needs new plumbing | Verdict |
  |---|---|---|
  | Seed | Not exposed by `LevelSummary`; requires opening the world's `level.dat` via `LevelStorageSource.LevelStorageAccess`/`PrimaryLevelData` (the same access `EditWorldScreen`/`editWorld` already briefly opens) to read the seed out of the world-gen settings NBT. Feasible but non-trivial per-render-frame-adjacent cost (screen `init()`-time only, not per-frame, so cost is a single extra file open, acceptable) | **Accept, local side only** — read once in `WorldConflictResolutionHook.detailFor`, not per-frame; no cloud-side seed exists without downloading and extracting the archive (rejected per Non-goals), so this is an unpaired local-only field |
  | Minecraft version the save was created/last played with | `LevelSummary` may expose a version string already (`getSaveVersion()`/similar, vanilla-API-dependent per Minecraft version — needs confirming per platform module at implementation time since `fabric-1.21.11` and `fabric-26.x` may differ) — if it does, this is F9-tier (already cheap); if it requires a raw NBT read, this is F10-tier (accept, cheap, same `level.dat` open as seed) | **Accept, local side only**, cloud side rejected (would require extracting the archive) |
  | Difficulty | Same shape as game mode/seed — likely readable from `level.dat`'s `Difficulty`/`WorldGenSettings` NBT via the same single `LevelStorageAccess` open used for seed/version, batched into one read rather than three separate file opens | **Accept, local side only, batched with seed/version into one `LevelStorageAccess` read** — cloud side rejected |
  | Cheats-enabled flag | Same `level.dat` NBT source (`allowCommands`), same batched read | **Accept, local side only, batched with the above** |
  | Day count / in-game time played | Same `level.dat` NBT source (`Time`/`DayTime`), same batched read, needs a duration-formatting helper (F8) | **Accept, local side only, batched with the above** — genuinely useful "how much progress would I lose" signal |
  | File/chunk count | Would require either walking the local region-file directory (`region/*.mca`) and counting files (cheap, a `Files.list` on one subdirectory — acceptable), or, for the cloud side, downloading and inspecting the archive's internal entry list before extraction (a `ZipInputStream` central-directory read *could* avoid downloading the full body if partial-range reads were exposed by `WorldArchiveCloudStore`, but no such partial-read API exists today, only whole-file `beginAsyncRead`) | **Local file count: accept (cheap, `region/*.mca` count is a reasonable proxy for "explored area", not chunk-accurate but honest as labeled "region files: N").** **Cloud-side chunk/file count: reject** — would require either a new partial-read Steam Cloud API (none exists) or downloading the whole archive merely to show a number before the player has decided whether to keep it, which is the exact anti-pattern this spec's Non-goals rule out |
  | Cloud-side "file count" as in number of Cloud files backing this world | Already answered by the existing one-archive-per-world model (`WorldSaveSyncService` writes exactly one `lazuli-world-{slug}.zip` per world, confirmed by `WorldArchiveCloudStore`'s single-`fileName`-per-call shape) | **Non-field — always 1, not worth displaying**; note this explicitly in the screen design so nobody re-proposes it later assuming it's a real variable |
  | Sync history beyond "last synced at / by which device" (e.g. a full audit log of every past sync) | No such history is persisted anywhere (`WorldFingerprint`/`WorldSyncAncestor` are both single-latest-value records, not append-only logs) | **Reject** — would require a new persisted append-only log, a scope well beyond a conflict screen; F9's single "last synced by this device at T" and "cloud's current fingerprint is from device D at T" already cover the one-hop history that exists |

- **F11 (final field list decision).** Combining F9/F10's accepted fields, the two symmetric boxes (per FR-3)
  show, in this fixed order, for every field that has both a local and cloud value:
  1. World display name
  2. Last-modified (local) / Last-synced-at (cloud) — kept as one paired row even though the underlying
     semantics differ slightly (local = filesystem mtime of the save folder; cloud = the fingerprint's
     `syncedAtTimestamp`), since this is exactly what the existing screen already paired and the two really
     do answer the same practical question ("how fresh is this side")
  3. Size — "Local folder size" vs "Cloud archive size" (explicitly distinct labels per F9's caveat)
  4. Device — "Device" (local: this device's own resolved label) vs "Device" (cloud: `cloudDeviceLabel`)

  Followed by a second, visually distinguished "Local-only details" sub-section rendered **inside the Local
  box only** (the Cloud box has no counterpart row at these positions — see UI section for exactly how an
  unpaired row is rendered without breaking the two-column value-comparison model), listing, in this order:
  5. Game mode
  6. Last played
  7. Hardcore (Yes/No)
  8. Cheats enabled (Yes/No)
  9. Difficulty
  10. Seed
  11. Minecraft version
  12. Day count / time played
  13. Region file count
  14. This device's last-synced-at (the `WorldSyncAncestor` timestamp — "you last synced this world at T")

  Rows 1-4 are the only rows eligible for the match/mismatch coloring rule (FR-3.3); rows 5-14 have no cloud
  counterpart to compare against and are rendered in the existing neutral text color only (no yellow/grey
  distinction applies to a field that isn't a comparison).

### Play/Edit disable + new resolve button (per user requirement 1)

- **FR-1 (Play/Edit disabled during Conflict).** `isRowSyncing(worldSlug)` (or a sibling boolean computed
  alongside it) is extended so that Play and Edit render and behave as disabled (F1's existing muted-fill /
  dimmed-label / no-hover-react / tooltip / click-no-op pattern, verbatim reused) whenever
  `WorldConflictHook.checkConflictFor(...) == ConflictStatus.CONFLICT` for that row's world, **in addition
  to** the existing Syncing condition — i.e. the combined gating condition becomes `isUploadInProgress ||
  isDownloadInProgress || isConflicted`, and both render (`WorldsPanel.java:345`) and click
  (`WorldsPanel.java:638`) call sites read this single combined boolean, preserving the "one shared boolean,
  computed once per row" discipline the prior spec (`cloud-sync-status-ui-simplify` FR-4.3) already
  established.
- **FR-1.1 (distinct tooltip text for the Conflict case).** The disabled tooltip must distinguish *why*
  Play/Edit is blocked, since "syncing" and "conflicted" are different facts a player needs different next
  steps for: while blocked purely by Conflict (no upload/download active), the tooltip reads **"Cannot play
  or edit while this world has an unresolved Steam Cloud conflict. Resolve it first."**; while blocked by
  Syncing (with or without a simultaneous conflict, though per `cloud-sync-status-ui-simplify` FR-1 precedent
  Conflict and Syncing are not expected to overlap in practice), the existing **"Cannot play or edit while
  syncing with Steam Cloud."** text is unchanged. Implementation checks Conflict first when composing the
  tooltip string precisely because Conflict is the higher-precedence, more actionable state (matches
  `cloud-sync-status-ui-simplify` FR-1's own precedence ordering, Conflict > Syncing).
- **FR-2 ("Resolve Cloud Conflict" third button, Conflict-only).** When an expanded row's world is in the
  Conflict state, a third pill button, labeled **"Resolve Cloud Conflict"**, renders alongside Play and Edit
  on the same button row. It does not render at all (not merely disabled — absent, freeing its horizontal
  space) for any other state, matching the existing "square only differs by state, no fourth always-drawn
  slot" philosophy `cloud-sync-status-ui-simplify` established for indicator squares, now applied to this
  button.
  - **FR-2.1 (layout).** `pillBounds` (F3) is extended to compute a third pill's bounds when a boolean
    `showResolveButton` parameter is true, laid out left of Play/Edit with the same `PILL_PADDING`
    convention (exact left-to-right vs right-to-left ordering, and whether Play/Edit shrink/shift or the
    resolve button is appended in previously-unused row width, is a planning/implementation-time layout
    call — this spec requires only that all three pills remain fully visible and non-overlapping within the
    existing expanded-row width, and that Play/Edit's own bounds do not silently change width/position for
    non-conflicted rows).
  - **FR-2.2 (color).** A new fill color distinct from Play's green, Edit's dark grey, and the disabled-grey
    `0xFF4A4A4A` used for Play/Edit while blocked (to avoid the new button visually reading as "also
    disabled") — reusing or closely matching the existing Conflict-state color already chosen for the
    consolidated status square in `cloud-sync-status-ui-simplify` FR-2 is recommended (ties the button
    visually to the same concept), exact hex a planning/implementation-time decision.
  - **FR-2.3 (click).** Clicking the button calls the existing `openConflictScreen(summary)`
    (`WorldsPanel.java:727-`, F4) — no new method needed, this is a second call site for an already-shipped
    method.
  - **FR-2.4 (relationship to the existing consolidated-status-square click, F2).** Both the new button and
    the existing consolidated-status-square's Conflict-only click (`WorldsPanel.java:667-676`) remain valid,
    independent entry points to the same `openConflictScreen` call — the button does **not** supersede or
    remove the square's click handler. Rationale: the square's click-to-resolve behavior is a subtle,
    undiscoverable affordance for a first-time player (a small 8px square with no visible label), while the
    button is an explicit, labeled, always-visible-when-relevant affordance; keeping both costs nothing (they
    call the identical method) and serves two different discovery paths. This resolves the spec prompt's
    explicit open question in favor of "both stay."
- **FR-2.5 (row-syncing precedence for the resolve button itself).** If a Keep-Cloud restore is actively
  running (the `isDownloadInProgress` case, which can only begin *after* the player has already opened
  `WorldConflictScreen` and chosen "Keep Cloud") the consolidated status transitions to Syncing before
  Conflict per `cloud-sync-status-ui-simplify` FR-1's precedence — during that specific window the "Resolve
  Cloud Conflict" button must not render (the conflict is already being actively resolved, re-opening the
  screen mid-restore is not a useful action and the screen's own `keepLocalStarted`/`RestoreProgress`
  handling assumes a single in-flight resolution). This is a natural consequence of gating FR-2's visibility
  on the same `ConflictStatus.CONFLICT` check the square already uses, not a new special case, but is called
  out here explicitly since it is easy to get backwards when implementing FR-1's combined boolean.

### `WorldConflictScreen` redesign (per user requirement 3)

- **FR-3 (two-column symmetric layout).**
  - **FR-3.1 (structure).** The screen's content area (below the title, above the action buttons) is split
    vertically down the horizontal center into two equal-width boxes: a left box titled, in bold,
    **"Local save"**, and a right box titled, in bold, **"Latest Steam Cloud save"**. Each box independently
    draws a light border/background distinct from the screen's own background (consistent with this
    codebase's existing panel-box visual language elsewhere, e.g. `WorldsPanel`'s row backgrounds
    `0xFF201E17`/`0xFF2A2820` — exact treatment a planning/implementation-time call, but the two boxes must
    be visually separated from each other and from the screen background, not merely two columns of bare
    text).
  - **FR-3.2 (identical field ordering, both boxes).** Both boxes render the exact same ordered list of field
    *keys* per F11 rows 1-4 (the paired/comparable fields), each key rendered once per box (i.e. the key
    "Size" appears in both the Local box and the Cloud box, at the same vertical offset in each, with each
    box showing only its own side's value beside its own copy of the key). This is a genuine structural
    change from F6's current single-centered-line-per-side layout — each field becomes its own row, present
    in both columns at matching Y offsets, rather than one full sentence per side.
  - **FR-3.3 (unpaired local-only fields, F11 rows 5-14).** Rendered as additional rows in the Local box only,
    below a visual sub-heading (e.g. "Local-only details", non-bold, muted color) separating them from the
    paired rows above. The Cloud box's corresponding vertical space is simply left blank (not filled with a
    "N/A" placeholder row for every unpaired key — an empty box region reads more honestly as "this box has
    fewer facts" than a wall of "N/A"s, and avoids implying a real absence-of-data on the cloud side rather
    than a hasn't-been-plumbed-for-the-cloud-side design choice).
  - **FR-3.4 (value color rule — the core new behavior).** For each of F11's four paired rows: compare the
    Local box's value string and the Cloud box's value string for that field.
    - If the two values are **exactly equal** (string equality after formatting, e.g. two identical
      formatted-timestamp strings, or two identical `"12.3 MB"`-style size strings — note this means a
      Local/Cloud size difference of even one byte that rounds to the same displayed MB string is
      correctly treated as "matching" for display purposes, since the player is comparing what they can
      see, not raw bytes), render **both sides'** values in a neutral/grey color (reuse the existing muted
      text color `0xFF908C7F`, consistent with this codebase's established "muted = unremarkable" convention
      seen throughout `WorldsPanel`).
    - If the two values **differ**, render **both sides'** values in **yellow** (a new color constant, e.g.
      `0xFFFFD700` or this codebase's nearest existing yellow if one already exists elsewhere — planning to
      confirm no existing yellow constant is being duplicated under a different name).
    - The field **key** text (e.g. the literal word "Size") is never colored by this rule — keys always
      render in the screen's normal/default text color (`0xFFEAE8E1`, matching existing body text elsewhere
      in this codebase), in both boxes, regardless of whether that field's values match.
    - Row 1 (World display name) is expected to always match in practice (both sides derive from the same
      logical world) — included in the comparison rule anyway for consistency and because a mismatch here
      would actually be a meaningful signal worth flagging (e.g. a renamed world) rather than an edge case
      to special-case away.
  - **FR-3.5 (unpaired-row color).** F11 rows 5-14 (Local-only details, FR-3.3) are never colored
    grey/yellow by this rule — they have no comparison to make, so both key and value render in the normal
    default text color, distinguishing them visually from the paired rows above even without reading the
    sub-heading.
  - **FR-3.6 (action buttons reposition).** "Keep Local" and "Keep Cloud" (`WorldConflictScreen.java:102-107`,
    F7) move to below both boxes (not at a fixed `height/2 + 50` literal, since the two-box layout is
    materially taller than the current two-line layout) — computed relative to the taller box's actual
    bottom edge plus a fixed margin, remaining horizontally centered/symmetric around the screen's
    horizontal center exactly as today. If the combined content no longer fits the screen without
    scrolling at typical GUI scales, planning must decide between a scrollable content area versus trimming
    which F11 rows 5-14 render (this spec's Non-goals do not forbid scrolling, but implementation should
    prefer fitting the full field list without scrolling if the field count and font size allow it at
    common GUI scales, given how few fields this actually is per box — 4 paired + up to 10 unpaired local
    rows).
  - **FR-3.7 (in-progress/failure states unchanged in substance).** The existing `keepCloudCompleted` /
    `failureReason` / `keepLocalStarted` / `RestoreProgress` phase-text handling (`WorldConflictScreen.java:114-153`)
    keeps its current behavior and trigger conditions; only its on-screen position needs to move to remain
    sensible against the new taller layout (e.g. the failure/progress lines currently drawn at fixed
    `height/2`-relative offsets move to render above or below the two boxes rather than overlapping them —
    a planning/implementation-time layout detail, not a behavior change).

## Public API

- `WorldConflictHook`: no signature change. `WorldsPanel` already holds a reference via
  `WorldConflictHookHolder`; FR-1 only adds one more call site (`checkConflictFor`) into the same combined
  boolean already computed for `isRowSyncing`.
- `WorldConflictResolutionHook.ConflictDetail`: extended from its current 6 fields to include, at minimum,
  per F9/F10/F11: local device label, local ancestor ("this device's last synced-at") timestamp, cloud
  archive size in bytes (via `WorldArchiveCloudStore.fileSize`, threaded through `WorldSaveSyncService`'s
  existing `detailFor` implementation, which already has access to that store), and the F10-batch of
  `level.dat`-derived fields (game mode is already available via `LevelSummary` and does not need this
  record at all if `WorldConflictScreen` is instead handed the `LevelSummary` directly — planning to decide
  whether every field flows through one enlarged `ConflictDetail` record or whether `WorldConflictScreen`
  additionally reads its already-available `LevelSummary`/`summary` parameter directly for the fields that
  don't need any new backend read, to avoid needlessly duplicating already-available data through a new
  record field). Exact final field list and whether it is one flat record or a nested
  `LocalDetail`/`CloudDetail` pair (arguably cleaner given FR-3's box-per-side structure) is a
  planning-time decision — this spec fixes the *content* (F11) and the *display rule* (FR-3.4), not the
  Java shape.
- `WorldsPanel.pillBounds`: signature grows to accept/return a third pill's bounds conditionally (F3/FR-2.1)
  — exact shape (widened array, new record type, or a new sibling method) is a planning-time decision.
- `WorldConflictScreen`'s constructor: unchanged in this spec's scope unless the `ConflictDetail`
  reshaping (above) requires passing additional already-available context (e.g. the `LevelSummary`) that it
  does not currently receive — if so, its constructor gains one additional parameter; planning to confirm
  against `WorldsPanel.openConflictScreen`'s current call site (`WorldsPanel.java:727-`, not read in full
  this pass — confirm exact existing parameter list before adding to it).

## Architecture

No new services. The new metadata fields are additions to `WorldSaveSyncService`'s existing
`detailFor(...)` implementation (F5), reading from already-owned collaborators
(`WorldFingerprintCache`, `WorldSyncAncestorIO`, the already-injected `WorldArchiveCloudStore`, and a new
one-time `LevelStorageSource.LevelStorageAccess` open for the F10 `level.dat` NBT batch — seed, difficulty,
cheats-enabled, day count/time-played read together in a single open+close, not four separate opens).
`WorldsPanel`'s FR-1 combined-boolean and FR-2 button-visibility both remain pure reads of already-held hook
references, following the same pattern `isRowSyncing` already established. `WorldConflictScreen`'s layout
change is presentation-only; no new backend calls are introduced by the layout redesign itself (FR-3) beyond
the metadata already delivered via the enlarged `ConflictDetail` (FR-1/FR-2 content-side changes are the only
source of new backend reads in this spec).

## UI

- Worlds tab expanded row: Play, Edit, and (Conflict-only) "Resolve Cloud Conflict" pills on one row per
  FR-2.1; Play/Edit muted per FR-1/FR-1.1 while Syncing or Conflicted, with a tooltip whose text depends on
  which condition applies (FR-1.1).
- `WorldConflictScreen`: two bordered boxes side by side, "Local save" (bold title) / "Latest Steam Cloud
  save" (bold title), each listing the F11 field list at matching row offsets; paired rows 1-4 colored per
  FR-3.4 (grey = match, yellow = differ, key text always default color); unpaired rows 5-14 in the Local box
  only, under a "Local-only details" sub-heading, always default color (FR-3.5); "Keep Local"/"Keep Cloud"
  buttons below both boxes, still horizontally symmetric around center (FR-3.6); progress/failure text
  repositioned to not overlap the boxes (FR-3.7).

## Configuration

No new configuration. No per-world or global preference is introduced by this spec.

## Events

No new Fabric API event registrations. All new reads (F9/F10) are pull-based, performed at
`WorldConflictResolutionHook.detailFor(...)` call time (screen `init()`), exactly matching how the existing
two fields are already produced today.

## Networking

- One new Steam Cloud metadata call per conflict-screen open: `WorldArchiveCloudStore.fileSize(fileName)`
  for the cloud archive size field (F9) — already-implemented interface method, metadata-only (Valve's
  `GetFileSize`, not a content read), matching this spec's Non-goals boundary against reading archive
  contents.
- No new `beginAsyncRead` calls, no new `streamWrite` calls, no change to upload/restore networking paths.

## Persistence

No new persisted (cross-restart) state. All new fields are either read live from already-persisted sources
(`WorldFingerprint`, `WorldSyncAncestor`, `level.dat`) or computed on the fly (region-file count, folder
size) — nothing new is written to disk by this spec.

## Compatibility

- All three platform modules' `WorldConflictScreen.java` receive the identical redesign, in the same shape,
  matching this feature's established per-module-parity practice — a diff pass across all three at the end
  of implementation is required, as with every prior spec in this feature area. `fabric-1.21.11`'s vanilla
  `LevelSummary`/`LevelStorageAccess` API surface must be re-confirmed against `fabric-26.1`/`fabric-26.2`
  before assuming the F10 batch's exact NBT keys/accessor method names are identical across all three
  Minecraft versions — this is flagged as a real risk (per the codebase's own prior noted risk pattern
  around `@Shadow`/`@Final` mixin fragility across `fabric-26.1`/`fabric-26.2`), not assumed away.
- `WorldConflictResolutionHook.ConflictDetail`'s field-count growth is a source-incompatible but
  binary-irrelevant change for a record type used only within this mod's own module boundary (not a public
  third-party extension point in the same sense as `WorldSyncStatusHook`'s default-method precedent) — no
  backward-compatibility shim is required, unlike `cloud-sync-status-ui-simplify`'s `isDownloadInProgress`
  default-method addition to a genuinely pluggable hook interface.
- Existing `CloudSyncCoordinatorTest`, `CloudSyncableReconcilerTest`, `WorldSaveSyncServiceTest`,
  `WorldSyncStatusTrackerTest` are expected to need only additive test coverage for the new `detailFor`
  fields (if `WorldSaveSyncServiceTest` already covers `detailFor`), not modification of existing assertions
  — no change to conflict-detection logic itself.

## Performance

- FR-1's added `checkConflictFor` call into the combined Play/Edit-gating boolean reuses the same
  `conflictCache` that `cloud-sync-status-ui-simplify` already populates at `reload()`/the existing
  recompute cadence (per that spec's F1/FR-1) — no new per-frame filesystem or Steam Cloud read is
  introduced by FR-1/FR-2's visibility checks.
- The F10 `level.dat` NBT batch read happens exactly once per `WorldConflictScreen.init()` (i.e. once per
  conflict-screen open, not per frame, not per Worlds-tab row-render) — negligible cost, matching the
  existing `localLastModifiedMillis`/`localSizeBytes` folder-walk's own one-time-per-screen-open cost.
- The new `fileSize` Steam Cloud metadata call (Networking section) is similarly one call per screen open,
  not per frame — consistent with Valve's documented cheap-metadata-call guidance already relied on
  elsewhere in this feature.

## Future Extensions

- A genuine cloud-side chunk/file count or content-level diff (e.g. "3 chunks changed") would require either
  a new partial-range Steam Cloud read API (does not exist today) or a willingness to download the full
  archive speculatively before the player decides — both rejected in this pass (F10); revisit only if Valve
  ever exposes ranged Cloud file reads.
- A persisted, append-only sync-history log (beyond the single latest fingerprint + single local ancestor
  this feature already tracks) would let a richer "sync history" field group be added later, but is a
  meaningfully larger persistence feature on its own, not a small addition to this spec's scope (F10).
- Extending the resolve-button/disabled-Play-Edit pattern to also gate the cloud-only synthetic-world rows'
  own click behavior (`openRestoreFlow`) if a future conflict-like state is ever added to that row type — no
  such state exists today, purely speculative.
- Surfacing the F11 "Local-only details" fields' cloud-side counterparts if/when new plumbing (F10's
  rejected batch for the cloud side) becomes cheap enough to justify (e.g. if a lightweight "cloud-side
  level.dat-only partial read" became available without downloading the full archive) — not pursued now.

## Open Questions

1. **`ConflictDetail` shape** — one enlarged flat record vs. nested `LocalDetail`/`CloudDetail` records —
   recommend nested given FR-3's inherently two-sided box structure, but not blocking; planning's call
   (Public API section).
2. **Exact yellow/grey hex values** and confirmation no existing yellow constant is being duplicated under a
   different name elsewhere in this codebase — not blocking.
3. **Third-pill layout mechanics** (shrink Play/Edit vs. append in freed space vs. wrap to a second row at
   narrow GUI scales) — FR-2.1 intentionally leaves this open; planning's call, only constraint is
   non-overlapping, fully-visible pills.
4. **Whether F11's "Local-only details" list needs to be user-scrollable** if it doesn't fit at small GUI
   scales together with the paired rows and both boxes' borders — FR-3.6 prefers no-scroll if it fits;
   planning to confirm against actual rendered heights at common GUI scales before deciding.
5. **`fabric-1.21.11` vs `fabric-26.1`/`fabric-26.2` API parity for the F10 `level.dat` NBT batch** — flagged
   as a real risk in Compatibility, not resolved here; planning/implementation must verify the exact
   accessor path (likely via `LevelStorageSource.LevelStorageAccess.getDataTag()`/`getSummary()` or
   equivalent per-version API) on all three platform modules before assuming identical code across them.
