# Main Menu — Batch 4 Fixes Specification (Post-Launch Bug Report)

## Overview
Follow-up bug-fix pass to `specification-batch-3-fixes.md` (implemented,
verified). The user played the live batch-3-fixes build and reported five
further issues spanning the Achievements, Statistics, Friends-Sidebar, and
list-panel (Worlds/Servers) surfaces. Applies to all three platform modules:
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`
(package `de.lazuli`), except where noted. This is a **bug-fix pass, not a
redesign** — every item below is scoped to correcting a specific,
code-confirmed defect or gap in already-shipped batch-3/batch-3-fixes work.

## Goals
- Give the Achievements tab a single shared, non-duplicated icon-asset source
  and wire actual icon rendering into `AchievementsPanel` (Item BF-4-1).
- Root-cause why vanilla statistics are still not reliably tracked/displayed
  despite batch-3's BF2 disk-scan fix (Item BF-4-2).
- Remove the Statistics tab's "Tracked for &lt;player&gt; · across all worlds"
  subtitle entirely (Item BF-4-3).
- Root-cause the friends sidebar's delayed re-appearance during ordinary
  gameplay after batch-3's BF6 gating fix (Item BF-4-4).
- Apply the panel content left-inset consistently to Worlds/Servers panels'
  own header controls, matching their already-padded row content (Item
  BF-4-5).

## Non-goals
- Not transcribing/editing the real Spacewar (App ID 480) achievement icon
  artwork *in this specification document* — the user has the source PNG
  files in hand and will drop them into the target directory below
  separately; this spec fully specs the shared-location convention, the
  exact filenames each icon must use, and the rendering wiring, so
  implementation is not blocked on the files being present at spec time.
- Not redesigning `CrossWorldStatsService`'s merge-interval/flush architecture
  wholesale — BF-4-2 is scoped to the specific gap identified below (a missing
  flush hook), not a rearchitecture of the whole cross-world-stats feature.
- Not adding a user-facing settings/toggle for the friends-sidebar HUD overlay
  — BF-4-4 is a gating-logic correction, consistent with batch-3-fixes BF6's
  own non-goal.
- Not introducing a shared panel base class/"content area" helper for
  BF-4-5 — same per-panel-constant approach as batch-3-fixes BF3.

## Background/Investigation Findings

### Item BF-4-1 — Achievement icons: shared source + rendering wiring
**Per-platform duplication is real and confirmed.** `gui/cloud_only.png` (and
its siblings `sync_enabled.png`/`sync_disabled.png`) exist as byte-identical
copies under all three platforms'
`src/main/resources/assets/lazuli/textures/gui/` trees (confirmed via glob:
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2` all
contain the same relative path). Batch-3-fixes' own BF5 spec explicitly
mandated this same duplicated-per-module convention for achievement icons
(`specification-batch-3-fixes.md` FR-BF5.1/FR-BF5.5) — that is the "current,
unwanted convention" this item corrects for future assets of this kind.

**Shared-resource viability confirmed.** Every platform module's
`build.gradle` (`platform/fabric-26.1/build.gradle:15`, structurally identical
on the other two) declares `implementation project(':features:main-menu')` —
a normal Gradle project dependency, not a jar-in-jar/shadow/include
relationship (that mechanism is reserved for `steamworks4j`,
`build.gradle:37`). `features/main-menu/build.gradle` is a plain Java library
module (no resource-stripping, no custom `sourceSets` override) with a
standard `src/main/resources` tree already in use for non-Java assets. A
normal `implementation project(...)` dependency puts that project's compiled
output — classes **and** resources — on the depending module's compile/runtime
classpath as-is; Minecraft's own resource-pack/asset loader resolves
`assets/<namespace>/...` paths by classpath lookup, not by which physical jar
a class file happens to live in. Therefore a single copy of each new
achievement icon placed under
`features/main-menu/src/main/resources/assets/lazuli/textures/achievements/...`
is already, today, resolvable identically from all three platform modules —
**no resource-copy task, symlink, or generated resource set is needed**; the
existing project-dependency wiring is sufficient. This is the proposed single
shared source-of-truth location for these (and future) shared texture assets.

**`AchievementsPanel` icon-rendering gap confirmed.**
`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/AchievementsPanel.java`
(structurally identical across all three platforms per batch-3-fixes'
"applied identically" convention) has zero icon-drawing code: its per-row loop
(lines 81-102) only ever calls `guiGraphics.text(...)` for `displayName`/
`description`/status — there is no `guiGraphics.blit(...)` call anywhere in
the file, confirmed by reading the full class. `SpacewarAchievementMapping`
(`features/main-menu/src/main/java/de/lazuli/features/mainmenu/achievements/SpacewarAchievementMapping.java`)
already carries an `iconAssetPath` field on its `AchievementMetadata` record
(line 32), but every one of its 5 hardcoded entries passes `null` for that
field (lines 35-44), and its own Javadoc (lines 16-19) states this explicitly:
"No icon asset is bundled this pass... `iconAssetPath()` is `null` for every
entry." So both halves of the reported gap are confirmed: no real icon bytes
exist yet, and even if they did, `AchievementsPanel` has no code path that
would draw them.

**Icon asset sourcing — Decision.** No Spacewar achievement icon image files
exist in this repository yet (confirmed: no `achievements/` texture directory
exists under any platform module or `features/main-menu`), but the user has
the real Spacewar icon PNGs sourced and will drop them into the target
directory as a follow-up content-drop, not as part of implementing this spec.
This item's rendering-wiring and shared-location refactor requirements are
therefore unconditional and ship regardless of whether the PNG files are
physically present at implementation time: the target location and naming
convention (FR-4-1.1a below) are fixed now so the user can drop files in
without any further code change, and `AchievementsPanel`'s rendering wiring
(FR-4-1.3) is written to resolve whatever it finds at that path — present or
absent — falling back to the existing `iconAssetPath() == null`/no-icon
rendering (batch-3-fixes FR-BF5.3) whenever a specific icon file is missing at
runtime, not just when the mapping field itself is `null`.

### Item BF-4-2 — Vanilla statistics still not tracked/displayed
`StatisticsPanel`'s own disk-scan/JSON-parsing logic (batch-3-fixes BF2) is
**not** the remaining bug. Read in full
(`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/StatisticsPanel.java:73-195`,
structurally identical on `fabric-26.2`; `fabric-1.21.11` uses Yarn-mapped
equivalents of the same calls, same shape) and cross-checked against vanilla's
actual `stats/<uuid>.json` format:
- The JSON parse (`accumulateSaveStats`, lines 162-195) reads
  `root.stats.<category>.<key> = value` and the category/key strings it reads
  against (`"minecraft:custom"`, `"minecraft:mined"`, `"minecraft:crafted"`,
  `"minecraft:used"`, `"minecraft:broken"`, `"minecraft:picked_up"`,
  `"minecraft:dropped"`, `"minecraft:killed_by"`, `"minecraft:killed"`, plus
  per-stat keys like `"minecraft:play_time"`) are exactly vanilla's own
  namespaced `Stats` category/key identifiers, matching the real on-disk
  format byte-for-byte — **not a shape mismatch**, contrary to one of this
  item's investigation hypotheses.
- `reload()`'s consumption of `CrossWorldStatsBridgeHandoff.require()
  .localWorldIdsForCurrentAccount()` (line 74) and the per-save
  `LevelStorageSource.createAccess`/`stats/<uuid>.json` resolution (lines
  162-168) are both correctly wired and match `WorldsPanel`'s own
  `LevelStorageSource` usage pattern.

**Actual root cause: `worldBaselines` (the set BF2 depends on to know which
local saves are "in scope") is populated far less often, and far less
reliably, than the Statistics tab's normal usage pattern requires.** Read
`CrossWorldStatsService`
(`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/CrossWorldStatsService.java`)
and its platform-side driver,
`CrossWorldStatsMergeHook`/`CrossWorldStatsClientInitializer`
(`platform/fabric-26.1/src/main/java/de/lazuli/crossworldstats/CrossWorldStatsMergeHook.java`,
`platform/fabric-26.1/src/main/java/de/lazuli/CrossWorldStatsClientInitializer.java`):
- `localWorldIdsForCurrentAccount()` (`CrossWorldStatsService.java:136-146`)
  reads only `AccountStats.worldBaselines().keySet()` filtered to
  `"local:"`-prefixed entries — i.e. only worlds this service has itself
  already **written a baseline for**. `StatisticsPanel` never reads the raw
  local-saves directory itself; a world entirely absent from
  `worldBaselines` is invisible to Statistics regardless of how much real
  play happened in it.
- `worldBaselines` entries are written **only** from `mergeNow()`
  (`CrossWorldStatsService.java:116-128`), called from exactly two places:
  1. `CrossWorldStatsService.tick(...)` (lines 87-97), itself gated by a
     30-second interval (`DEFAULT_MERGE_INTERVAL_SECONDS = 30`,
     `CrossWorldStatsService.java:33`) — driven by
     `CrossWorldStatsMergeHook.tick(Minecraft)`
     (`CrossWorldStatsMergeHook.java:70-73`), registered against
     `ClientTickEvents.END_CLIENT_TICK`
     (`CrossWorldStatsClientInitializer.java:70`). This only records a
     baseline for a world if the player stays in that world for at least one
     full 30-second interval while this tick keeps firing.
  2. `CrossWorldStatsService.flush(...)` (lines 109-114), called **only** from
     `ClientLifecycleEvents.CLIENT_STOPPING`
     (`CrossWorldStatsClientInitializer.java:71`) — i.e. only when the entire
     game process is shutting down, not on returning to the main menu/leaving
     a world.
- **`CrossWorldStatsMergeHook.flush(Minecraft)`**
  (`CrossWorldStatsMergeHook.java:76-79`) resolves `worldId` via
  `resolveWorldId(client)` (lines 81-96) at the moment it's called — which
  requires `client.hasSingleplayerServer()` to still be `true` at that exact
  moment. **`MainMenuScreen` (where the Statistics tab lives) is only ever
  shown after a world has already been exited** (batch-3-fixes BF2's own
  confirmed finding, `specification-batch-3-fixes.md` lines 104-124) — by the
  time a player has left a world and is looking at the Statistics tab,
  `resolveWorldId()` already returns `null` for that world (no singleplayer
  server is running), so a subsequent `CLIENT_STOPPING` flush is a no-op for
  that session's data even if the game is later closed cleanly.
- **Net effect (the actual bug):** a local save's play session is recorded
  into `worldBaselines` — and therefore becomes visible to `StatisticsPanel`
  at all — **only if the player remains in that world continuously for at
  least one full 30-second tick-driven merge interval while still loaded**.
  Any session shorter than 30 seconds records nothing. Any session's *final*
  partial interval (the play that happened after the last 30-second tick
  fired, up until the player exits the world) is silently lost, since there is
  no flush-on-world-exit/disconnect hook anywhere in this feature — only a
  periodic in-world tick and an end-of-process flush that fires too late
  (after the world is already gone) to help. This fully explains a user
  seeing "no data" or stale/incomplete data even after substantial real play:
  the exact combination this repo's own main-menu flow requires (exit world →
  view Statistics) is precisely the one moment `flush()` cannot recover the
  session's tail.
- This is a **gap in `features/cross-world-stats`'s own write-side event
  coverage**, not a `StatisticsPanel`/BF2 read-side defect — `StatisticsPanel`
  correctly reads whatever `worldBaselines` happens to contain; the problem is
  that set is incomplete for the normal player flow.
- **Decision: cross-world stats tracking is made always-on/mandatory.**
  `CrossWorldStatsConfig(boolean enabled)`
  (`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/api/CrossWorldStatsConfig.java`)
  currently gates the entire feature behind a persisted `enabled` toggle; when
  `false`, `CrossWorldStatsClientInitializer` publishes a
  `NoopCrossWorldStatsFacade` (`CrossWorldStatsClientInitializer.java:53-56`)
  whose `localWorldIdsForCurrentAccount()` returns an empty set — a real,
  simpler contributor to "Statistics shows nothing" independent of the
  timing gap above, and one a user could hit with zero indication why. This
  pass removes the toggle entirely rather than special-casing its default:
  `CrossWorldStatsConfig.enabled` is deleted as a concept (not merely
  defaulted to `true`), `NoopCrossWorldStatsFacade` and the
  enabled/disabled branch in `CrossWorldStatsClientInitializer` that selects
  between it and the real facade are removed, and the real
  `CrossWorldStatsFacade` is published unconditionally on every platform.
  Cross-world stats tracking is mandatory, matching the fact that
  `StatisticsPanel` has no other data source and the feature has no
  meaningful "off" state a player would want.
- Verified structurally identical on all three platforms: `fabric-1.21.11`
  and `fabric-26.2`'s `CrossWorldStatsMergeHook`/`CrossWorldStatsClientInitializer`
  mirror `fabric-26.1`'s shape (same `ClientTickEvents`/
  `ClientLifecycleEvents` registration pattern, same `resolveWorldId`
  contract) — the missing-flush-on-world-exit gap applies identically to all
  three.

### Item BF-4-3 — Statistics subtitle removal
Exact current string, identical across all three platforms (only the
rendering call's method name differs by mapping):
- `fabric-26.1`/`fabric-26.2`
  (`.../mainmenu/StatisticsPanel.java:227`):
  ```java
  guiGraphics.text(font, Component.literal("Tracked for " + playerName + " · across all worlds"), x + CONTENT_LEFT_PAD, y + 12, 0xFF908C7F);
  ```
- `fabric-1.21.11` (`.../mainmenu/StatisticsPanel.java:236`):
  ```java
  context.drawText(font, Text.literal("Tracked for " + playerName + " · across all worlds"), x + CONTENT_LEFT_PAD, y + 12, 0xFF908C7F, false);
  ```
This entire line (and the now-unused `playerName` local it depends on, if
nothing else in `render()` uses it after removal — confirmed: `playerName` is
computed once at `StatisticsPanel.java:224-225` solely to build this string)
is to be deleted, not reworded.

### Item BF-4-4 — Friends sidebar delayed re-appearance during gameplay
Read `FabricFriendsSidebarInjector`'s full visibility-gating logic on
`fabric-26.1` (`platform/fabric-26.1/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`);
confirmed structurally identical on `fabric-1.21.11`
(`HIDE_AFTER_TICKS`/`withinHideWindow`/`lastScreenClosedTick` all present at
the same lines with the same logic, `platform/fabric-1.21.11/.../FabricFriendsSidebarInjector.java:124-293`)
and, per this repo's own "applied identically" convention for this class
across batches, expected identical on `fabric-26.2` as well.

**Root cause: batch-3-fixes BF6's gating condition is inverted from what
FR-BF6.1 actually required.** The class's own gate:
```java
private boolean withinHideWindow() {
    return tickCounter - lastScreenClosedTick <= HIDE_AFTER_TICKS;
}
```
is used, both in the HUD-render layer
(`registerGlobalHudOverlay()`, `FabricFriendsSidebarInjector.java:234`:
`if (minecraft.screen != null || withinHideWindow()) { return; }`) and in the
tick-driven click-forwarding path (`onClientTick`, line 289: `if (screenOpen
|| withinHideWindow()) { ...; return; }`), as a condition that **hides** (or
skips click-forwarding for) the overlay whenever `true`. But
`lastScreenClosedTick` starts at `Long.MIN_VALUE` (line 127) and is only ever
updated to the current tick at the exact moment a `Screen` transitions from
open to closed (`onClientTick`, lines 284-286: `if (wasScreenOpenLastTick &&
!screenOpen) { lastScreenClosedTick = tickCounter; }`). The practical
consequence:
1. Immediately after any screen-close transition (e.g. closing the pause
   menu), `withinHideWindow()` is `true` for the next `HIDE_AFTER_TICKS` (40)
   ticks — so the overlay is **hidden** for about two seconds right after
   closing a menu.
2. Once those 40 ticks elapse with no further screen-open/close transition
   (i.e. the player just keeps playing normally), `withinHideWindow()`
   permanently becomes `false` again (since `lastScreenClosedTick` is never
   updated again until the *next* screen-close event) — so the overlay
   **un-hides and renders continuously** for the rest of ordinary gameplay,
   until the player opens/closes a screen again.
This is the exact opposite of FR-BF6.1's stated intent (spec:
"`screen == null` **and** within a short fixed window of ticks since `screen`
last transitioned from non-null to null" should describe when it *is*
visible, not when it is hidden) — the implementation instead uses that window
as a hide period and defaults to visible outside it. This precisely matches
the user's report: no overlay right when gameplay resumes (post-menu-close),
then — once that short window elapses a couple of seconds later — the overlay
appears and stays for the remainder of the play session (the reported
"delayed onset"). No stale "just joined" flag, periodic friend-activity poll,
or cached-screen-reference bug was found to be involved — the entire
mechanism is this one inverted boolean's two call sites
(`registerGlobalHudOverlay()`'s render lambda and `onClientTick`'s
click-forwarding gate), both driven by the same `withinHideWindow()` helper.

### Item BF-4-5 — List row content left-padding (batch-3 BF3 follow-up)
Investigated every row-rendering and control-init code path in
`WorldsPanel.java` and `ServersPanel.java`
(`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/`, structurally
identical across all three platforms per each panel's own "applied
identically" convention).

**Correction to this item's premise:** both panels' own row-content
rendering (icon + name + subtitle/MOTD text) **already** applies
`CONTENT_LEFT_PAD` via a local `leftX = x + CONTENT_LEFT_PAD` (`WorldsPanel.java:127,130`;
`ServersPanel.java:325,328`) — this was already fixed as part of batch-3-fixes
BF3 and is not the bug. Row *background* fills
(`guiGraphics.fill(x, rowY, x + width, ...)`, e.g. `WorldsPanel.java:150`,
`ServersPanel.java:344`) intentionally start flush at raw `x` for a full-bleed
hover highlight — this matches every other panel's own row-background
convention (e.g. `AchievementsPanel.java:86`) and is correct, not a bug.

**The actual gap: each panel's own `init()`-registered vanilla `Button`/
`EditBox` header controls — a genuinely separate code path from the
manually-rendered row content above — are positioned directly off raw `x`,
never `x + CONTENT_LEFT_PAD`.** These controls sit in the header strip
directly above the row list and are visibly misaligned one `CONTENT_LEFT_PAD`
(8px) short of the row content beneath them:
- `ServersPanel.init()` (`ServersPanel.java:119-171`): `subViewToggle`
  (`.bounds(x, y - 24, ...)`, line 121), `searchBox`
  (`new EditBox(..., x, y, ...)`, line 146), `hideFullToggle`
  (`.bounds(x + 168, y, ...)`, line 156) — all computed from raw `x`, with no
  `CONTENT_LEFT_PAD` offset anywhere in `init()`. (`hidePasswordToggle`/
  `latencyToggle`/`refreshButton`/`directConnectButton`/`addServerButton`/
  `savedRefreshButton` are all right-anchored off `x + width - ...` and are
  correctly unaffected by this gap, consistent with FR-BF3.1's "right-aligned
  content... is unaffected" carve-out.)
- `WorldsPanel.init()` (`WorldsPanel.java:111-117`): `createButton` is
  right-anchored (`x + width - 160`) and therefore already unaffected —
  **`WorldsPanel` has no left-anchored `init()`-registered control at all**,
  so this item's fix for `WorldsPanel` is a no-op / confirmation-only; the
  gap is specific to `ServersPanel`.
- The corresponding hit-test/click-consumption for these `Button`/`EditBox`
  widgets is handled entirely by vanilla's own `Screen`/`AbstractWidget`
  dispatch off each widget's own stored `bounds(...)`, not by this panel's
  own `mouseClicked(...)` method — so fixing the `bounds(...)` call sites in
  `init()` is sufficient; no parallel hit-test drift exists here the way
  batch-3-fixes BF1 had to guard against for `StatisticsPanel`'s column math.

## Requirements

### Item BF-4-1 — Achievement icons: shared source + rendering wiring
- **FR-4-1.1** A new shared texture resource directory is introduced at
  `features/main-menu/src/main/resources/assets/lazuli/textures/achievements/`
  (single copy, no per-platform duplication) — the shared source-of-truth
  location for achievement icon assets, reusing the already-confirmed-viable
  project-dependency classpath mechanism (Background/Investigation Findings)
  rather than any new build-time copy/symlink/generated-resource-set
  mechanism.
- **FR-4-1.1a (Decision — target path and naming convention)** Each
  achievement's icon file is named `<apiName>.png`, where `<apiName>` is
  exactly the Spacewar API achievement name already used as the key into
  `SpacewarAchievementMapping.MAPPING` (e.g. an achievement with API name
  `WIN_GAME` has its icon at
  `features/main-menu/src/main/resources/assets/lazuli/textures/achievements/WIN_GAME.png`).
  `AchievementMetadata.iconAssetPath()` values are the full in-namespace
  resource path derived from that same convention:
  `lazuli:textures/achievements/<apiName>.png` (i.e. the string stored in
  `iconAssetPath()` is what `AchievementsPanel` passes straight into a
  `ResourceLocation`/texture identifier — see FR-4-1.3). The user will drop
  the sourced Spacewar PNG files directly into this directory using this
  filename convention as a follow-up content pass; no code change is needed
  when that happens.
- **FR-4-1.2** `SpacewarAchievementMapping.AchievementMetadata.iconAssetPath()`
  entries are populated with the `lazuli:textures/achievements/<apiName>.png`
  path (FR-4-1.1a) for every one of the 5 existing mapping entries; the field
  is populated unconditionally by this pass regardless of whether the backing
  PNG file physically exists in the resource directory yet — presence/absence
  of the actual file at runtime is handled by `AchievementsPanel`'s
  missing-texture fallback (FR-4-1.3), not by leaving `iconAssetPath()` `null`.
  Any future achievement added to `MAPPING` with no icon planned at all keeps
  `iconAssetPath() == null`, preserving the existing no-icon fallback contract
  (batch-3-fixes FR-BF5.3).
- **FR-4-1.3** `AchievementsPanel` gains an icon-draw call (a `guiGraphics.blit(...)`,
  mirroring the existing icon-blit pattern already used by `WorldsPanel`/
  `ServersPanel` for world/server icons) in its per-row loop, positioned
  alongside the existing display-name/description text. When `meta != null`
  and `meta.iconAssetPath() != null`, the panel resolves that path string to a
  `ResourceLocation` (namespace `lazuli`, path
  `textures/achievements/<apiName>.png` per FR-4-1.1a) and issues the `blit`
  call against it. Graceful fallback: if the resolved texture is not actually
  present on the classpath at render time (the PNG has not been dropped in
  yet, or a future mapping entry points at a file that doesn't exist), the
  row falls back to today's icon-less rendering for that row only — no
  placeholder/broken-image/missing-texture-checkerboard state, no exception
  propagated out of the row-render loop — mirroring the same
  `meta == null`/`iconAssetPath() == null` fallback pattern already specified
  for unmapped achievements (batch-3-fixes FR-BF5.3, FR-4-1.4 below). Rows
  with `meta == null` or a `null` `iconAssetPath()` keep today's icon-less
  rendering unchanged, as before.
- **FR-4-1.4** No behavior change to any achievement whose `apiName()` is not
  present in `SpacewarAchievementMapping.MAPPING` — unchanged raw-name-only
  fallback (batch-3-fixes FR-BF5.3), unaffected by this item.
- **FR-4-1.5** Applied identically across all three platform modules (a
  single shared resource + a single shared `AchievementsPanel`-per-platform
  code change, same shape as every other icon-blit call already duplicated
  per platform for `WorldsPanel`/`ServersPanel`).

### Item BF-4-2 — Statistics: close the world-baseline recording gap
- **FR-4-2.1 (Decision — flush hook)** `features/cross-world-stats` gains a
  flush trigger at the moment a singleplayer world is actually exited (i.e.
  before `resolveWorldId()` would start returning `null` for that world) —
  not only at `ClientLifecycleEvents.CLIENT_STOPPING`. The finalized hook is
  `net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT`,
  registered from each platform's `CrossWorldStatsClientInitializer`
  alongside the existing `CLIENT_STOPPING` registration
  (`CrossWorldStatsClientInitializer.java:71`). This is the same event this
  repo already uses for identical "world/session just ended, singleplayer
  server still resolvable" flush-on-exit needs elsewhere:
  `ServerJoinPresenceClientInitializer.java:120` and
  `SteamCloudSyncClientInitializer.java:74` both register
  `ClientPlayConnectionEvents.DISCONNECT` and both guard their exit-side
  logic with `client.hasSingleplayerServer()` — the identical pattern
  `CrossWorldStatsMergeHook.resolveWorldId()` already relies on
  (Background/Investigation Findings). `DISCONNECT` fires before the
  singleplayer server/level is torn down, i.e. while
  `resolveWorldId(client)` can still resolve the just-exited world's
  identifier, capturing that session's final partial merge interval. This
  hook is registered identically on all three platforms
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`),
  matching the existing per-platform `ClientPlayConnectionEvents` usage
  pattern confirmed present on each.
- **FR-4-2.2** `CrossWorldStatsMergeHook`/`CrossWorldStatsService`'s existing
  `flush(...)` method (already correctly shaped to bypass the 30-second
  interval gate, `CrossWorldStatsService.java:109-114`) is reused for this new
  trigger rather than duplicated — this is an additional *call site*, not new
  merge/flush logic.
- **FR-4-2.3** The existing `CLIENT_STOPPING` flush registration
  (`CrossWorldStatsClientInitializer.java:71`) is retained unchanged (still
  useful for the case where a player quits the game while still inside a
  world) — FR-4-2.1 is additive, not a replacement.
- **FR-4-2.4 (Decision — remove the enabled toggle)** `CrossWorldStatsConfig`
  is changed from `record CrossWorldStatsConfig(boolean enabled)` to a
  zero-field/no-`enabled`-field type (or removed entirely if nothing else
  needs the type once `enabled` is gone — planning's call on the minimal
  shape), so cross-world stats tracking is always active. All downstream
  reads of `CrossWorldStatsConfig.enabled()` and the config-driven branch in
  `CrossWorldStatsClientInitializer` that currently selects between the real
  `CrossWorldStatsFacade` and `NoopCrossWorldStatsFacade`
  (`CrossWorldStatsClientInitializer.java:53-56`) are removed; the real
  facade is published unconditionally. `NoopCrossWorldStatsFacade` itself is
  deleted if this was its only caller (planning/implementation to confirm no
  other consumer exists). The `enabled` field/key is also removed from the
  persisted `config/cross-world-stats.json` schema (see Configuration,
  Persistence) — this is a removal of the concept, not a default-value
  change.
- **FR-4-2.5** No change to `StatisticsPanel`'s own read-side logic
  (disk-scan, JSON parsing, account-scoping) — confirmed correct per
  Background/Investigation Findings; this item is entirely a
  `features/cross-world-stats` write-side fix.
- **FR-4-2.6** Applied identically across all three platform modules' own
  composition-root wiring (`CrossWorldStatsClientInitializer.java` equivalents).

### Item BF-4-3 — Remove Statistics subtitle
- **FR-4-3.1** The `"Tracked for " + playerName + " · across all worlds"`
  text-draw call (Background/Investigation Findings, Item BF-4-3, exact
  strings quoted per platform) is deleted from `StatisticsPanel.render()` on
  all three platforms, along with the now-unused `playerName` local variable
  if nothing else in that method reads it after removal.
- **FR-4-3.2** No replacement text is substituted — the header row simply
  loses this line; the "Statistics" title text immediately above it
  (`StatisticsPanel.java:226`) is unaffected.
- **FR-4-3.3** No layout/vertical-offset change to the pill row or content
  below is required unless removing this line leaves an visually-empty gap
  planning judges worth closing up (implementation's call, not a hard
  requirement — the subtitle removal itself is the only mandatory change).

### Item BF-4-4 — Fix friends-sidebar gating inversion
- **FR-4-4.1** `withinHideWindow()`'s boolean sense (or its call sites'
  usage of it) is corrected so the no-`Screen` HUD overlay and its
  click-forwarding counterpart render/forward **only** within the short
  post-screen-close tick window (`HIDE_AFTER_TICKS`), and are **hidden**
  otherwise (i.e. hidden by default during ordinary gameplay, briefly visible
  only around a screen-close transition) — the reverse of the current
  behavior. This may be implemented either by inverting the returned boolean
  and renaming it to reflect a "should show" rather than "should hide"
  semantic, or by inverting both call sites' conditionals — planning's choice,
  as long as the net rendered behavior matches this requirement.
- **FR-4-4.2** The initial-state case (`lastScreenClosedTick = Long.MIN_VALUE`
  before any screen has ever closed, i.e. from client start until the first
  menu open/close) must resolve to **hidden** (not visible), consistent with
  FR-4-4.1's "hidden by default" behavior — the current `Long.MIN_VALUE`
  sentinel's arithmetic must be re-validated against the corrected boolean
  sense so it doesn't accidentally show the overlay indefinitely at cold
  start.
- **FR-4-4.3** No change to `HIDE_AFTER_TICKS`'s value (40, ~2s) or to the
  `lastScreenClosedTick`-update logic itself
  (`onClientTick`, `FabricFriendsSidebarInjector.java:284-287`) — only the
  gating condition's applied sense changes, not the tick-window's length or
  when it gets (re)armed.
- **FR-4-4.4** No change to `ALLOW_LISTED_SCREENS`/the `Screen`-driven render
  path — unaffected, same as batch-3-fixes FR-BF6.5.
- **FR-4-4.5** Applied identically across all three platform modules'
  `FabricFriendsSidebarInjector.java`.

### Item BF-4-5 — Fix ServersPanel header-control left-padding
- **FR-4-5.1** `ServersPanel.init()`'s left-anchored controls
  (`subViewToggle`, `searchBox`, `hideFullToggle` — Background/Investigation
  Findings, Item BF-4-5) have their `x`-coordinate origin shifted by the same
  `CONTENT_LEFT_PAD` constant already used by this panel's own row-content
  rendering, so the header strip's left edge visually aligns with the row
  list's own left-padded content beneath it.
- **FR-4-5.2** Right-anchored controls in the same `init()` method
  (`refreshButton`, `savedRefreshButton`, `directConnectButton`,
  `addServerButton`, `hidePasswordToggle`, `latencyToggle`) are unaffected —
  they already compute their position from `x + width - ...` and require no
  change (consistent with batch-3-fixes FR-BF3.1's right-aligned-content
  carve-out).
- **FR-4-5.3** `WorldsPanel` requires no code change for this item — its sole
  `init()`-registered control (`createButton`) is already right-anchored
  (Background/Investigation Findings); this item's scope for `WorldsPanel` is
  confirmation-only, not a fix.
- **FR-4-5.4** Applied identically across all three platform modules'
  `ServersPanel.java`.

## Public API
No new/changed `api`- or `services`-module type for any item in this pass.
BF-4-2's fix is confined to `features/cross-world-stats`' internal
`CrossWorldStatsService`/`CrossWorldStatsMergeHook`/composition-root wiring
(no change to the already-published `CrossWorldStatsFacade` interface itself
— `localWorldIdsForCurrentAccount()`'s signature is unchanged, only what gets
written into the set it reads from). BF-4-1's new icon-asset path field
(`AchievementMetadata.iconAssetPath()`) already exists on that
`features/main-menu`-internal record (batch-3-fixes) — this pass only
populates/consumes it, no signature change.

## Architecture
- BF-4-1: no new architectural seam — a `features/main-menu`-owned shared
  resource directory (reusing the existing project-dependency classpath
  mechanism, Background/Investigation Findings) plus an in-place
  `AchievementsPanel` rendering addition per platform, same Version Adapter
  layering as every other panel.
- BF-4-2: one new call site into `CrossWorldStatsService`'s existing
  `flush(...)` method, triggered from `ClientPlayConnectionEvents.DISCONNECT`
  at the platform composition-root layer (FR-4-2.1) — no new class, no new
  cross-feature edge (the `CrossWorldStatsBridgeHandoff`/`CrossWorldStatsFacade`
  seam `features/main-menu` already depends on for reads, batch-3-fixes
  FR-BF2.7, is unaffected; this fix is entirely inside
  `features/cross-world-stats` and its own platform wiring). Additionally,
  the `enabled`-gated facade-selection branch in
  `CrossWorldStatsClientInitializer` is removed (FR-4-2.4) — the real
  `CrossWorldStatsFacade` is now published unconditionally, simplifying that
  composition root rather than adding to it.
- BF-4-3/BF-4-4/BF-4-5: pure in-place fixes to existing classes, no
  architectural change.

## UI
- BF-4-1: Achievements rows gain a real icon image wherever the mapping has a
  non-null `iconAssetPath`; rows without one are visually unchanged (today's
  icon-less rendering).
- BF-4-2: no direct UI change from this item alone — its effect is that
  Statistics tab data (already-shipped BF2 UI shape) becomes reliably
  populated for realistic play sessions, including short ones and the normal
  "just exited a world" flow.
- BF-4-3: Statistics tab loses its subtitle line; everything else unchanged.
- BF-4-4: purely a visibility-timing correction — the sidebar/handle is
  hidden by default during ordinary gameplay and only briefly visible around
  a menu-close transition, the reverse of today's behavior.
- BF-4-5: `ServersPanel`'s header strip (sub-view toggle, search box, "Hide
  Full" toggle) shifts right by `CONTENT_LEFT_PAD`, aligning with the row
  list beneath it — a small, uniform visual shift, no structural change.
  `WorldsPanel` is visually unchanged.

## Configuration
No new configuration for BF-4-1/BF-4-3/BF-4-4/BF-4-5. BF-4-2 **removes**
configuration: the user-facing `enabled` toggle on
`config/cross-world-stats.json`/`CrossWorldStatsConfig` is deleted entirely
(FR-4-2.4) — cross-world stats tracking becomes mandatory/always-on, with no
setting to disable it. This is a schema change (field removed), not just a
default-value change.

## Events
BF-4-2 taps `ClientPlayConnectionEvents.DISCONNECT` (FR-4-2.1) as an
additional flush trigger, registered identically on all three platforms
alongside the existing `ClientLifecycleEvents.CLIENT_STOPPING` registration —
the same event already used for equivalent flush-on-exit needs by
`ServerJoinPresenceClientInitializer`/`SteamCloudSyncClientInitializer`. This
is the only new event consumption in this pass. No new event source for
BF-4-1/BF-4-3/BF-4-4/BF-4-5.

## Networking
No new networking for any item — BF-4-1's icon assets (if sourced this pass)
are bundled at build time, same as every other texture asset in this repo; no
network call.

## Persistence
- BF-4-2: `config/cross-world-stats.json` loses its `enabled` field (FR-4-2.4)
  — a schema change removing the toggle, not merely re-defaulting it. Beyond
  that, the write path itself (`CrossWorldStatsService.mergeNow()`/`flush()`)
  is unchanged in format; this pass only adds an additional trigger
  (`ClientPlayConnectionEvents.DISCONNECT`, FR-4-2.1) that calls the same
  existing `flush()` method.
- BF-4-1's icon assets (if sourced) are bundled, read-only, build-time
  content, same as batch-3-fixes BF5's mapping resource.
- No new persistence for BF-4-3/BF-4-4/BF-4-5.

## Compatibility
- All five items land identically across `platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2`.
- BF-4-2's flush hook (`ClientPlayConnectionEvents.DISCONNECT`, FR-4-2.1) is
  a Fabric API type, not a Yarn/Mojang-mapped vanilla type, so it is
  source-identical across `fabric-1.21.11`/`fabric-26.1`/`fabric-26.2` with
  no per-platform mapping divergence to confirm — same as the existing
  `ServerJoinPresenceClientInitializer`/`SteamCloudSyncClientInitializer`
  registrations of the same event on all three platforms today.
- BF-4-1's shared-resource-location approach depends on every platform
  module continuing to declare a plain `implementation project(':features:main-menu')`
  dependency (confirmed true today on all three, Background/Investigation
  Findings) — if that dependency shape ever changes to a shadow/jar-in-jar
  relationship for `features/main-menu` specifically, this shared-location
  assumption would need to be re-verified.

## Performance
No item in this pass introduces new per-frame cost of note: BF-4-1 adds one
`blit` call per achievement row with a mapped icon (negligible, same class of
cost as `WorldsPanel`/`ServersPanel`'s existing per-row icon blits); BF-4-2's
new flush call fires once per world-exit event (already-infrequent), not
per-frame; BF-4-3 removes a text-draw call (net negative cost); BF-4-4/BF-4-5
are pure conditional/positional changes with no new work.

## Future Extensions
- BF-4-1's shared-resource-location convention
  (`features/main-menu/src/main/resources/assets/lazuli/...`) could be
  adopted retroactively for the currently-still-duplicated
  `gui/cloud_only.png`/`sync_enabled.png`/`sync_disabled.png` assets, if a
  later pass wants to de-duplicate those too (out of scope for BF-4-1 itself,
  which only concerns new/future assets per the task framing).
- BF-4-2's flush-on-world-exit fix could be paired with a broader look at
  whether other `features/cross-world-stats` consumers (not just
  `StatisticsPanel`) have the same "final partial interval lost" exposure.

## Open Questions
None. All three items previously flagged here are resolved as finalized
decisions in this specification:
1. **BF-4-1 icon artwork sourcing/path** — resolved by FR-4-1.1a: the target
   directory and `<apiName>.png` naming convention are fixed now; the user
   will drop the sourced Spacewar PNG files in as a follow-up content pass,
   and `AchievementsPanel`'s rendering wiring (FR-4-1.3) ships regardless,
   with a graceful per-row fallback if a given icon file isn't present yet.
2. **BF-4-2 `enabled` toggle** — resolved by FR-4-2.4: the toggle is removed
   entirely (not defaulted), making cross-world stats tracking mandatory.
3. **BF-4-2 flush-trigger event** — resolved by FR-4-2.1: finalized as
   `ClientPlayConnectionEvents.DISCONNECT`, registered identically on all
   three platforms, matching the identical pattern already used by
   `ServerJoinPresenceClientInitializer`/`SteamCloudSyncClientInitializer`.
