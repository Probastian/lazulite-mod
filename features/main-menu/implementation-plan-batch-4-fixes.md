# Implementation Plan — Main Menu Batch 4 Fixes (Items BF-4-1 through BF-4-5)

Spec: `features/main-menu/specification-batch-4-fixes.md` (approved).

## Summary
Five items, sequenced to minimize rework and cross-item interference:
1. **BF-4-3 first** (delete Statistics subtitle) — trivial, one-line deletion
   per platform, touches `StatisticsPanel.java` before BF-4-2 changes that
   same file's data layer, avoiding a rebase.
2. **BF-4-2 next** (cross-world-stats flush-on-exit + remove `enabled`
   toggle) — largest-risk item (config schema removal + new event
   registration across `features/cross-world-stats` and all three platform
   composition roots); sequenced early since it is the most structurally
   invasive and independent of the UI-only items.
3. **BF-4-4 next** (friends-sidebar gating inversion) — fully isolated to
   `FabricFriendsSidebarInjector`, no dependency on any other item.
4. **BF-4-5 next** (`ServersPanel` header left-padding) — small, isolated,
   no dependency on any other item.
5. **BF-4-1 last** (achievement icon shared resource + rendering wiring) —
   sequenced last since it is net-new (a new shared resource module + new
   render code path) rather than a fix to existing logic, and its artwork
   content-drop is explicitly out of scope/asynchronous per the spec.

No implementation code is written by this plan.

## Existing Implementation

### Platform modules (confirmed in scope)
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2` all
exist with matching `de.lazuli` package/file layout. All edits below apply
×3 unless noted. `features/cross-world-stats` and `features/main-menu` are
the two feature modules touched.

### `SpacewarAchievementMapping.java` (confirmed by direct read)
`features/main-menu/src/main/java/de/lazuli/features/mainmenu/achievements/SpacewarAchievementMapping.java`
— a `Map.of(...)` with exactly 5 entries (`ACH_WIN_ONE_GAME`,
`ACH_WIN_100_GAMES`, `ACH_TRAVEL_FAR_ACCUM`, `ACH_TRAVEL_FAR_SINGLE`,
`ACH_SPECIAL_ACHIEVEMENT`), each currently passing `null` as the third
`AchievementMetadata(String displayName, String description, String
iconAssetPath)` constructor argument. BF-4-1 changes only these 5 `null`
values to `"lazuli:textures/achievements/<apiName>.png"` strings (FR-4-1.1a/
FR-4-1.2) and updates the class Javadoc (lines 16-19), which currently
explicitly states no icon is bundled. No other change to this file (no
signature change, no new field).

### `AchievementsPanel.java` icon-blit precedent (`WorldsPanel.java`, confirmed by direct read)
`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/WorldsPanel.java`
imports `net.minecraft.resources.Identifier` and calls
`guiGraphics.blit(RenderPipelines.GUI_TEXTURED, iconId, gridX, gridY, 0f, 0f, ...)`
(lines 152, 160, 172) via a `private final IconTextureCache iconCache` field
(line 80). `IconTextureCache` is purpose-built for dynamically-uploaded
favicon-style textures (per-world/per-server dynamic bytes with cache-miss
handling) — not the right fit for BF-4-1's statically-bundled, build-time
`.png` assets. `AchievementsPanel`'s icon path (FR-4-1.3) instead needs a
simpler, static-asset-appropriate existence check: resolve
`Identifier.fromNamespaceAndPath("lazuli", "textures/achievements/<apiName>.png")`
and confirm the resource is actually present on the classpath before
`blit`-ing, via `Minecraft.getInstance().getResourceManager().getResource(id)`
returning empty `Optional` (standard vanilla idiom for "does this identifier
resolve to a real resource-pack entry", avoiding the missing-texture
checkerboard `TextureManager`/`MissingTextureAtlasSprite` would otherwise
substitute if `blit` were called against a genuinely absent identifier) —
implementation confirms the exact per-version method name/shape
(`ResourceManager.getResource(ResourceLocation)` on Mojang-mapped
26.1/26.2, Yarn-mapped equivalent on 1.21.11), consistent with this repo's
per-version-API-confirmation discipline (`.claude/context/minecraft.md`).

### `AchievementsPanel.java` render-loop gap (confirmed per spec Background Findings)
Per-row loop (`platform/fabric-26.1/.../mainmenu/AchievementsPanel.java:81-102`,
structurally identical ×3) currently draws only `displayName`/`description`/
status text, zero `blit` calls. FR-4-1.3's new icon-draw call inserts
alongside the existing text draws in this same loop, gated on
`meta != null && meta.iconAssetPath() != null` then the resource-existence
check above; on either gate failing, falls through to today's icon-less
rendering unchanged (FR-4-1.4).

### `features/main-menu/build.gradle` + `platform/*/build.gradle` project-dependency shape
Confirmed per spec Background Findings (BF-4-1): every platform module
declares a plain `implementation project(':features:main-menu')` dependency
(`platform/fabric-26.1/build.gradle:15`, structurally identical ×3); no
resource-stripping/shadow/jar-in-jar mechanism applies to this project
(that mechanism is reserved for `steamworks4j`, `build.gradle:37`). A single
copy of new PNGs under
`features/main-menu/src/main/resources/assets/lazuli/textures/achievements/`
is therefore resolvable identically from all three platform modules with
zero build-file changes — this plan requires no `build.gradle` edits for
BF-4-1.

### `CrossWorldStatsClientInitializer.java` (confirmed by direct read, `fabric-26.1`; structurally identical ×3 per spec)
```
CrossWorldStatsConfigIO.ParseResult loaded = configIO.load(configFilePath);
CrossWorldStatsConfig config = loaded.config();
...
if (!config.enabled()) {
    CrossWorldStatsBridgeHandoff.publish(new NoopCrossWorldStatsFacade());
    return;
}
long steamId64 = ...;
CrossWorldStatsService service = new CrossWorldStatsService(accountKey, config, initialAccounts, configFilePath, configIO, LazuliMod.LOGGER::warn);
CrossWorldStatsMergeHook mergeHook = new CrossWorldStatsMergeHook(service);
CrossWorldStatsBridgeHandoff.publish(service);
ClientTickEvents.END_CLIENT_TICK.register(mergeHook::tick);
ClientLifecycleEvents.CLIENT_STOPPING.register(mergeHook::flush);
```
FR-4-2.4's removal deletes the `if (!config.enabled()) { ...; return; }`
branch entirely, so `CrossWorldStatsService` construction and both
registrations always run. FR-4-2.1 adds one more registration line,
`ClientPlayConnectionEvents.DISCONNECT.register(mergeHook::flush)` (reusing
the same `mergeHook::flush` method reference already used for
`CLIENT_STOPPING`, satisfying FR-4-2.2/FR-4-2.3 without any new method).
`net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents` is
a new import needed in this file (already imported/used elsewhere in this
repo per spec: `ServerJoinPresenceClientInitializer.java:120`,
`SteamCloudSyncClientInitializer.java:74`).

### `CrossWorldStatsConfig.java` + `CrossWorldStatsConfigIO.java` (confirmed by direct read)
`CrossWorldStatsConfig` (`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/api/CrossWorldStatsConfig.java`)
is `public record CrossWorldStatsConfig(boolean enabled)` (line 21) with
`DEFAULT = new CrossWorldStatsConfig(true)` (line 27). FR-4-2.4 changes this
to a zero-field type — this plan's chosen minimal shape is **delete the
record entirely** rather than keep an empty record: grep-confirmed (see
below) the type's only real field-level consumers are
`CrossWorldStatsConfigIO`'s read/write (`enabled` get/put, lines 130, 143,
198) and `CrossWorldStatsClientInitializer`'s gating branch (both removed by
this same item) plus `CrossWorldStatsService`'s constructor, which currently
receives `config` as a parameter (`new CrossWorldStatsService(accountKey,
config, initialAccounts, configFilePath, configIO, ...)`) — implementation's
first BF-4-2 sub-step is reading `CrossWorldStatsService`'s constructor body
to confirm what (if anything) it actually reads off `config` beyond passing
it through/ignoring it, since this plan did not open that class this pass;
if `CrossWorldStatsService` reads nothing from `config` beyond existence, the
parameter itself is also removed (cascading signature change); if it is
threaded through for some other still-needed reason, planning's fallback is
to keep an empty marker type. Either resolution stays inside
`features/cross-world-stats`'s internals — no `api`/`services` module
surface changes.

`CrossWorldStatsConfigIO.java`
(`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIO.java`)
reads/writes the `enabled` field at lines 130 (`getBooleanOrDefault("enabled", true)`),
143 (`new CrossWorldStatsConfig(enabled)`), and 198
(`root.putBoolean("enabled", config.enabled())`) — all three call sites are
deleted (FR-4-2.4's "field also removed from the persisted
`config/cross-world-stats.json` schema"); `ParseResult.ok(...)`'s second
constructor argument (the config instance) either becomes a no-arg
placeholder or is dropped from `ParseResult`'s own shape depending on the
`CrossWorldStatsConfig`-deletion resolution above — implementation's call,
confirmed against `ParseResult`'s full definition (not read this pass).
`CrossWorldStatsConfigIOTest.java`
(`features/cross-world-stats/src/test/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIOTest.java`)
almost certainly asserts on `enabled` round-tripping today (file exists,
grep-confirmed, not read this pass) — flagged as a required test-file edit
under Files to Modify.

### `NoopCrossWorldStatsFacade.java` (confirmed grep, not read in full)
`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/NoopCrossWorldStatsFacade.java`
exists; grep confirms its only production references are the 3 platform
`CrossWorldStatsClientInitializer.java` files (one `new
NoopCrossWorldStatsFacade()` call each, all inside the `if (!config.enabled())`
branch being deleted) plus this spec/plan document itself and this feature's
own README/implementation-plan docs (non-code). No other production caller
found — FR-4-2.4 deletes this class once all three gating branches are
removed, per spec's own "deleted if this was its only caller" allowance.

### `StatisticsPanel.java` subtitle line (confirmed per spec Background Findings, exact text/line numbers)
- `fabric-26.1`/`fabric-26.2`
  (`.../mainmenu/StatisticsPanel.java:227`):
  `guiGraphics.text(font, Component.literal("Tracked for " + playerName + " · across all worlds"), x + CONTENT_LEFT_PAD, y + 12, 0xFF908C7F);`
- `fabric-1.21.11` (`.../mainmenu/StatisticsPanel.java:236`):
  `context.drawText(font, Text.literal("Tracked for " + playerName + " · across all worlds"), x + CONTENT_LEFT_PAD, y + 12, 0xFF908C7F, false);`
`playerName` is computed once immediately before this call
(`StatisticsPanel.java:224-225`) solely to build this string — deleted
alongside the draw call if nothing else in `render()` reads it afterward
(implementation confirms via a same-method grep at edit time, per FR-4-3.1).
The "Statistics" title text immediately above (`StatisticsPanel.java:226`,
untouched per FR-4-3.2) sits one line before this call.

### `FabricFriendsSidebarInjector.java` gating inversion (confirmed per spec Background Findings, exact shape)
```java
private boolean withinHideWindow() {
    return tickCounter - lastScreenClosedTick <= HIDE_AFTER_TICKS;
}
```
Two call sites: `registerGlobalHudOverlay()`
(`FabricFriendsSidebarInjector.java:234`: `if (minecraft.screen != null ||
withinHideWindow()) { return; }`) and `onClientTick` (line 289: `if
(screenOpen || withinHideWindow()) { ...; return; }`). `lastScreenClosedTick`
starts at `Long.MIN_VALUE` (line 127), updated only on a screen-open→closed
transition (lines 284-286). `HIDE_AFTER_TICKS = 40`. Confirmed structurally
identical on `fabric-1.21.11` (lines 124-293) and expected identical on
`fabric-26.2` (spec's own "applied identically" convention for this class).

### `ServersPanel.java` header-control left-padding gap (confirmed per spec Background Findings, exact shape)
`ServersPanel.init()` (`ServersPanel.java:119-171`), three left-anchored
controls computed off raw `x`: `subViewToggle` (`.bounds(x, y - 24, ...)`,
line 121), `searchBox` (`new EditBox(..., x, y, ...)`, line 146),
`hideFullToggle` (`.bounds(x + 168, y, ...)`, line 156). `CONTENT_LEFT_PAD`
already exists in this file (introduced by batch-3-fixes BF3, used by the
row-content rendering per spec's own "Correction to this item's premise"
note) — FR-4-5.1 reuses that existing constant, not a new one. Six other
`init()`-registered controls (`hidePasswordToggle`, `latencyToggle`,
`refreshButton`, `directConnectButton`, `addServerButton`,
`savedRefreshButton`) are right-anchored off `x + width - ...` and are
explicitly unaffected (FR-4-5.2). Click-dispatch for all of these is handled
by vanilla `Screen`/`AbstractWidget` off each widget's own stored
`bounds(...)` — no parallel `mouseClicked(...)` hit-test drift to fix
(spec's own confirmation). `WorldsPanel.init()`
(`WorldsPanel.java:111-117`)'s sole control, `createButton`, is already
right-anchored (`x + width - 160`) — no code change for `WorldsPanel`
(FR-4-5.3, confirmation-only).

## Files to Create
- `features/main-menu/src/main/resources/assets/lazuli/textures/achievements/`
  (BF-4-1, ×1 shared directory; the 5 actual `.png` files
  `ACH_WIN_ONE_GAME.png`, `ACH_WIN_100_GAMES.png`, `ACH_TRAVEL_FAR_ACCUM.png`,
  `ACH_TRAVEL_FAR_SINGLE.png`, `ACH_SPECIAL_ACHIEVEMENT.png` are supplied by
  the user as a follow-up content drop per spec Non-goals — this plan's
  directory/naming convention is fixed now so implementation does not block
  on their presence; `AchievementsPanel`'s fallback (FR-4-1.3) covers their
  absence at implementation/verification time).

## Files to Modify
- `features/main-menu/src/main/java/de/lazuli/features/mainmenu/achievements/SpacewarAchievementMapping.java`
  — BF-4-1: populate all 5 `iconAssetPath()` values, update class Javadoc
  (×1 shared).
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/AchievementsPanel.java`
  (×3) — BF-4-1: new icon-blit call + resource-existence check in the
  per-row loop.
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/api/CrossWorldStatsConfig.java`
  — BF-4-2: delete `enabled` field (delete type entirely or reduce to a
  marker, per implementation's confirmation of `CrossWorldStatsService`'s
  actual use of `config`) (×1 shared).
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIO.java`
  — BF-4-2: remove `enabled` read/write (lines 130, 143, 198) (×1 shared).
- `features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/NoopCrossWorldStatsFacade.java`
  — BF-4-2: deleted (no remaining production caller once all 3 gating
  branches are removed) (×1 shared).
- `features/cross-world-stats/src/test/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIOTest.java`
  — BF-4-2: remove/update any assertion on `enabled` round-tripping (×1
  shared, exact assertions confirmed at implementation time).
- `features/cross-world-stats/src/test/java/de/lazuli/features/crossworldstats/services/CrossWorldStatsServiceTest.java`
  — BF-4-2: update any test construction of `CrossWorldStatsConfig`/
  `CrossWorldStatsService` that currently passes an `enabled` value (×1
  shared, confirmed at implementation time).
- `platform/fabric-<version>/src/main/java/de/lazuli/CrossWorldStatsClientInitializer.java`
  (×3) — BF-4-2: remove the `enabled`-gated `Noop` branch, always construct
  the real `CrossWorldStatsService`/publish it; add
  `ClientPlayConnectionEvents.DISCONNECT.register(mergeHook::flush)`
  alongside the existing `CLIENT_STOPPING` registration; new import.
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/StatisticsPanel.java`
  (×3) — BF-4-3: delete the subtitle draw call + unused `playerName` local
  (if confirmed unused after removal).
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`
  (×3) — BF-4-4: invert `withinHideWindow()`'s sense (or its two call sites)
  so the overlay/click-forwarding is visible only within the post-close
  window and hidden otherwise, including cold-start (`Long.MIN_VALUE`)
  resolving to hidden.
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/ServersPanel.java`
  (×3) — BF-4-5: shift `subViewToggle`/`searchBox`/`hideFullToggle`'s `x`
  origin by the existing `CONTENT_LEFT_PAD` constant in `init()`.
- `config/cross-world-stats.json` (runtime-generated, not a repo file) — no
  direct edit, but BF-4-2's schema change means any developer's existing
  local file with an `enabled` key will have that key silently ignored/
  dropped on next save (documented under Risks, not a migration step this
  plan implements — no user-facing settings existed for this toggle to
  migrate away from).

## Decisions

### 1. Item BF-4-3 — delete Statistics subtitle
Straightforward single-line deletion ×3 (exact line numbers/text in
Existing Implementation above); no helper/refactor needed. FR-4-3.3's
optional vertical-gap cleanup: this plan's default is **no additional
layout change** — leaving the resulting gap as-is is explicitly allowed by
the spec and keeps this item a minimal, low-risk diff; flagged for
verification's visual judgment call, not mandated here.

### 2. Item BF-4-2 — cross-world-stats flush-on-exit + remove `enabled`
- **New event registration** (FR-4-2.1/4-2.2/4-2.3): one additional line per
  platform's `CrossWorldStatsClientInitializer.onInitializeClient()`,
  `ClientPlayConnectionEvents.DISCONNECT.register(mergeHook::flush)`,
  placed immediately after the existing `CLIENT_STOPPING` registration.
  Reuses the existing `mergeHook::flush` method reference — no new method on
  `CrossWorldStatsMergeHook`/`CrossWorldStatsService`.
- **Toggle removal** (FR-4-2.4): implementation's first sub-step is reading
  `CrossWorldStatsService`'s constructor
  (`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/services/CrossWorldStatsService.java`,
  not opened this pass) to confirm exactly what it does with its `config`
  parameter beyond storage/pass-through, before deciding whether
  `CrossWorldStatsConfig` is deleted outright or reduced to an empty marker
  type retained for signature stability. This plan's default preference is
  outright deletion (simpler, matches FR-4-2.4's "or removed entirely if
  nothing else needs the type" framing) unless that constructor read reveals
  a still-needed field.
- **Composition-root simplification**: each platform's
  `CrossWorldStatsClientInitializer` drops its `if (!config.enabled())`
  early-return/`Noop`-publish branch; `CrossWorldStatsBridgeHandoff.publish(service)`
  becomes unconditional, matching the file's existing single-`publish`-call
  shape once the branch is gone (no functional change to
  `CrossWorldStatsBridgeHandoff` itself).
- **`NoopCrossWorldStatsFacade` deletion**: confirmed (grep, Existing
  Implementation) to have no other production caller; deleted once all
  three platform gating branches are removed. If implementation's final
  grep pass (after the other two files are edited) turns up an
  unanticipated fourth caller, this sub-step is skipped and flagged in the
  verification report rather than silently left half-done.
- **Persisted schema**: `enabled` key removed from both read
  (`getBooleanOrDefault`) and write (`putBoolean`) paths in
  `CrossWorldStatsConfigIO`; an existing on-disk file with a stray
  `"enabled": false` key is harmlessly ignored by the read path once that
  line is gone (JSON parsers here already tolerate unknown keys per this
  repo's existing hand-rolled-JSON convention — confirmed structurally
  consistent with every other `*ConfigIO` in this repo, not re-derived here).

### 3. Item BF-4-4 — invert friends-sidebar gating sense
This plan's chosen implementation shape (of the two options FR-4-4.1
explicitly allows): rename the helper to reflect a "should currently show"
semantic and negate it at both call sites, rather than negating the method
body itself. The comparison expression itself
(`tickCounter - lastScreenClosedTick <= HIDE_AFTER_TICKS`, "how recently did
a screen close") is retained unchanged inside the renamed method — only the
two call sites' applied sense flips:
`withinHideWindow()` → `withinShowWindow()` (same body), and:
- `registerGlobalHudOverlay()`: `if (minecraft.screen != null ||
  withinHideWindow()) { return; }` becomes `if (minecraft.screen != null ||
  !withinShowWindow()) { return; }`.
- `onClientTick`: `if (screenOpen || withinHideWindow()) { ...; return; }`
  becomes `if (screenOpen || !withinShowWindow()) { ...; return; }`.
This reads correctly at each call site ("skip/hide unless currently within
the show window") without a double-negative in the method body itself.
`HIDE_AFTER_TICKS` (kept as-is, or renamed to e.g. `SHOW_AFTER_CLOSE_TICKS`
— cosmetic, implementation's call, value unchanged at 40 per FR-4-4.3) and
`lastScreenClosedTick`'s own update logic (`onClientTick`, lines 284-287)
are untouched.
- **Cold-start correctness** (FR-4-4.2): with `lastScreenClosedTick =
  Long.MIN_VALUE`, `tickCounter - Long.MIN_VALUE` is a large positive number
  well above `HIDE_AFTER_TICKS`, so the renamed `withinShowWindow()`
  correctly evaluates `false` at cold start, and the inverted call-site
  condition (`!withinShowWindow()` is `true`) correctly hides the overlay —
  no special-case sentinel handling needed; implementation must simply
  verify this subtraction doesn't overflow (`tickCounter` is a small
  positive monotonic counter per batch-3-fixes' own design, so
  `tickCounter - Long.MIN_VALUE` stays within `long`'s range, confirmed by
  inspection, not requiring a runtime guard).
- Applied identically ×3 (FR-4-4.5); `ALLOW_LISTED_SCREENS`/`onScreenInit`
  untouched (FR-4-4.4).

### 4. Item BF-4-5 — `ServersPanel` header left-padding
Three `x` → `x + CONTENT_LEFT_PAD` edits in `ServersPanel.init()`
(`subViewToggle.bounds(...)`, `new EditBox(..., x, y, ...)`,
`hideFullToggle.bounds(x + 168, ...)` → `x + CONTENT_LEFT_PAD + 168`) —
reusing the constant already declared in this file (batch-3-fixes BF3), no
new constant. Applied identically ×3 (FR-4-5.4). `WorldsPanel`: no edit
(FR-4-5.3, confirmation-only — implementation/verification should still
visually confirm `WorldsPanel`'s header remains aligned as expected, without
changing any file).

### 5. Item BF-4-1 — shared achievement icon location + rendering wiring
- **Resource location** (FR-4-1.1/4-1.1a): new directory
  `features/main-menu/src/main/resources/assets/lazuli/textures/achievements/`,
  file-per-achievement named `<apiName>.png` exactly matching
  `SpacewarAchievementMapping.MAPPING`'s keys (e.g. `ACH_WIN_ONE_GAME.png`).
  No build-file change needed (Existing Implementation, project-dependency
  classpath confirmed sufficient).
- **Mapping population** (FR-4-1.2): all 5 `iconAssetPath()` fields set to
  `"lazuli:textures/achievements/" + apiName + ".png"` (a literal string per
  entry, matching this file's existing `Map.of(...)` literal style — no
  string-building helper needed for only 5 fixed entries).
- **Rendering wiring** (FR-4-1.3): in `AchievementsPanel`'s existing per-row
  loop, when `meta != null && meta.iconAssetPath() != null`:
  1. Parse `iconAssetPath()`'s `"lazuli:textures/achievements/<apiName>.png"`
     string into an `Identifier`/`ResourceLocation` via
     `Identifier.fromNamespaceAndPath("lazuli", "textures/achievements/<apiName>.png")`
     (splitting on the existing `:` per FR-4-1.1a's stated namespace:path
     shape).
  2. Existence check: `Minecraft.getInstance().getResourceManager().getResource(id).isPresent()`
     (exact per-version method/type name confirmed at implementation time,
     Yarn-mapped on `fabric-1.21.11` vs. Mojang-mapped on `fabric-26.1`/
     `fabric-26.2`) before issuing `blit` — this is the graceful-fallback
     mechanism (no exception, no missing-texture checkerboard).
  3. If present: `guiGraphics.blit(RenderPipelines.GUI_TEXTURED, id, iconX,
     iconY, 0f, 0f, iconSize, iconSize, iconSize, iconSize)` (mirroring
     `WorldsPanel`'s existing call shape at `WorldsPanel.java:160`), sized/
     positioned alongside the row's existing text draws (exact pixel
     offsets/icon size implementation's call, consistent with this row's
     existing text layout — no numeric spec value given, matching this
     repo's existing per-panel layout-constant convention).
  4. If absent, or `meta == null`/`iconAssetPath() == null`: fall through to
     today's icon-less rendering for that row, unchanged (FR-4-1.4).
- No caching layer (unlike `WorldsPanel`'s `IconTextureCache`) — these are
  static, always-on-classpath-or-not bundled assets with no dynamic
  upload/refresh need, so a direct per-row existence check plus `blit` is
  sufficient; a repeated `getResource(...)` lookup per row per frame is
  low-cost (small `Map`/classpath lookup, same class of cost as this repo's
  other per-frame panel work) — if implementation/verification finds this a
  measurable per-frame cost in practice (unlikely at 5 mapped achievements),
  a one-time `Set<String> presentIcons` computed at `AchievementsPanel`
  construction is a low-risk follow-up optimization, not required by this
  plan.
- Applied identically ×3 (FR-4-1.5): same shared mapping content, same
  per-platform `AchievementsPanel` edit shape (mirroring each platform's
  existing icon-blit call already duplicated for `WorldsPanel`/
  `ServersPanel`).

## Interfaces
No new/changed `api`- or `services`-module type (spec Public API: none for
this pass). `CrossWorldStatsConfig`'s field removal (BF-4-2) is an internal
`features/cross-world-stats` type change, not an `api`/`services` surface.
`AchievementMetadata.iconAssetPath()` (BF-4-1) already exists as a field on
an existing `features/main-menu`-internal record — this pass only populates/
consumes it, no signature change.

## Services
No new `services`-module type for any item.

## Dependencies
No new external Maven/Gradle dependency for any of the five items. BF-4-1
reuses the existing `implementation project(':features:main-menu')`
project-dependency (already present ×3, Existing Implementation) and the
already-in-repo `net.minecraft.resources.Identifier`/`guiGraphics.blit`/
`RenderPipelines.GUI_TEXTURED` APIs (same class already used by
`WorldsPanel`/`ServersPanel`). BF-4-2 reuses
`net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents`
(Fabric API, already a transitive dependency of every platform module and
already imported/used identically by
`ServerJoinPresenceClientInitializer.java`/`SteamCloudSyncClientInitializer.java`
per spec Compatibility — no new coordinate, no version lookup needed).
BF-4-3/BF-4-4/BF-4-5 are pure in-place logic/deletion changes with no
dependency implications. No Maven Central lookup was required for this plan.

## Test Strategy
Per this repo's standing convention (manual, in-game, per-platform-module
verification for rendering/layout; unit tests for plain-JVM-testable logic):

1. **Unit tests (plain JVM)**:
   - BF-4-2: `CrossWorldStatsConfigIOTest`/`CrossWorldStatsServiceTest`
     updated to drop any `enabled`-field assertions/constructor arguments;
     confirm round-trip persistence still succeeds for the remaining fields
     with the `enabled` key entirely absent from written JSON, and that an
     on-disk file containing a stray legacy `"enabled": false` key still
     parses without error (backward-read tolerance).
   - BF-4-4: if the tick-window comparison is extractable as a small pure
     function of `(tickCounter, lastScreenClosedTick, windowTicks) ->
     boolean`, unit-test the inverted sense directly, including the
     `Long.MIN_VALUE` cold-start case resolving to hidden — otherwise this
     item is manual-only, consistent with batch-3-fixes BF6's own precedent
     (no unit test existed for the pre-inversion logic either).
2. **Compilation** — `gradlew build`/`compileJava` succeeds on all three
   platform modules, `features/main-menu`, and `features/cross-world-stats`
   (constructor/type-removal ripples through `CrossWorldStatsService`,
   `CrossWorldStatsConfigIO`, and all three `CrossWorldStatsClientInitializer.java`
   files — a compile pass is this item's primary regression guard for the
   removal itself, beyond the two updated unit test files).
3. **Manual in-game verification, per platform module** (all three):
   - BF-4-1: with icon files present, mapped achievement rows show the real
     icon at a sane size/position; with icon files absent (pre-content-drop
     state), rows render exactly as today (no checkerboard/exception/log
     spam); unmapped achievements unaffected.
   - BF-4-2: play a short (<30s) singleplayer session, exit to the main
     menu (not full game quit), open Statistics — data for that session is
     now present (previously would have shown nothing); play multiple short
     sessions across app restarts, confirm cumulative summation still
     correct; confirm no `config/cross-world-stats.json` `"enabled"` key is
     written by a fresh file.
   - BF-4-3: Statistics tab header shows only the "Statistics" title, no
     subtitle line, no stray vertical gap regression worth flagging.
   - BF-4-4: overlay/handle hidden immediately on resuming gameplay after
     closing a menu; reaches steady-state hidden and stays hidden during
     ordinary play; briefly visible only right around a screen-close
     transition; hidden at fresh client start before any menu has ever been
     opened.
   - BF-4-5: `ServersPanel`'s sub-view toggle/search box/"Hide Full" toggle
     visually align with the row list's left edge beneath them;
     right-anchored controls unaffected; `WorldsPanel` visually unchanged.

## Risks
1. **BF-4-2 is the largest-risk item in this pass**: (a) this plan did not
   open `CrossWorldStatsService.java`'s constructor during planning (per
   "read only what's needed" discipline) — implementation's first sub-step
   must confirm whether `CrossWorldStatsConfig` can be deleted outright or
   needs a marker-type retention, per Decision 2; (b) removing `enabled`
   from the persisted schema is a one-way, unversioned change — any existing
   local `config/cross-world-stats.json` with `"enabled": false` silently
   starts tracking again on next launch post-update with no migration
   notice; acceptable per the spec's explicit "always-on" decision, but
   worth a one-line release-note-style mention if this repo publishes such
   notes (out of this plan's scope to author). (c) `NoopCrossWorldStatsFacade`
   deletion assumes the grep performed during planning (Existing
   Implementation) found every production caller — implementation should
   re-grep immediately before deleting the file as a final confirmation.
2. **BF-4-1's per-version resource-existence check** (`ResourceManager.getResource(...)`
   or equivalent) is not yet confirmed identical in method name/return shape
   across Yarn-mapped `fabric-1.21.11` vs. Mojang-mapped `fabric-26.1`/
   `fabric-26.2` — flagged for implementation's per-platform confirmation
   (consistent with this repo's `.claude/context/minecraft.md` cross-version
   API discipline); low risk since this is a well-established vanilla idiom,
   but the exact call shape was not verified during this planning pass.
3. **BF-4-4's inversion is a pure boolean-logic change with no unit test
   today** (carried from batch-3-fixes BF6's own precedent, which also
   shipped without one) — manual in-game verification is the only guard
   against a sign error re-introducing the original bug in a new form;
   implementation should consider adding the small pure-function unit test
   described in Test Strategy item 1 to reduce this risk going forward,
   though it is not mandated by the spec.
4. **BF-4-1's actual icon artwork remains unsourced at planning time**
   (spec Non-goals) — this item's own acceptance criteria are scoped to the
   shared-location convention and rendering wiring, not the artwork's
   presence; verification must explicitly test the fallback (files absent)
   path, not only assume the user will have dropped files in by review time.

## Acceptance Criteria
- **FR-4-1.1-4-1.5** — a single shared `features/main-menu`-owned
  `assets/lazuli/textures/achievements/<apiName>.png` resource location
  exists with no per-platform duplication; `SpacewarAchievementMapping`'s 5
  entries carry populated `iconAssetPath()` values; `AchievementsPanel`
  draws a real icon when the mapped file is present, falls back to today's
  icon-less rendering (no crash, no placeholder) when absent or unmapped;
  identical across all three platform modules.
- **FR-4-2.1-4-2.6** — `ClientPlayConnectionEvents.DISCONNECT` triggers
  `CrossWorldStatsService.flush(...)` on all three platforms in addition to
  the unchanged `CLIENT_STOPPING` registration; `CrossWorldStatsConfig.enabled()`
  and every consumer of it (including `NoopCrossWorldStatsFacade` and its
  gating branch) are removed; the real `CrossWorldStatsFacade` publishes
  unconditionally; `config/cross-world-stats.json` no longer contains an
  `enabled` field; `StatisticsPanel`'s read-side logic is unchanged; a short
  (<30s) or immediately-exited singleplayer session's stats become visible
  in the Statistics tab after returning to the main menu.
- **FR-4-3.1-4-3.3** — the "Tracked for ... across all worlds" subtitle line
  and its now-unused `playerName` local (if applicable) are removed from
  `StatisticsPanel.render()` on all three platforms; no replacement text; the
  "Statistics" title is unaffected.
- **FR-4-4.1-4-4.5** — the friends-sidebar HUD overlay and its
  click-forwarding are visible only within the short post-screen-close
  window and hidden otherwise, including at cold start; `HIDE_AFTER_TICKS`'s
  value and update logic are unchanged; `ALLOW_LISTED_SCREENS`/
  `onScreenInit` are unaffected; identical across all three platform
  modules.
- **FR-4-5.1-4-5.4** — `ServersPanel`'s `subViewToggle`/`searchBox`/
  `hideFullToggle` are shifted by `CONTENT_LEFT_PAD` and visually align with
  the row list's left edge; right-anchored controls are unaffected;
  `WorldsPanel` requires and receives no code change; identical across all
  three platform modules.
- **Compatibility** — `gradlew build` succeeds on all three platform
  modules plus `features/main-menu` and `features/cross-world-stats` with
  every changed/deleted file in place, including updated unit tests.

## Open Questions
None outstanding for this plan. The spec resolved all three items
previously flagged as open (icon path/naming, `enabled` toggle removal,
flush-trigger event choice); this plan's own remaining unknowns (exact
`CrossWorldStatsConfig`-deletion-vs-marker-type shape, exact per-version
resource-existence-check method name) are implementation-time confirmations
flagged under Risks, not blocking questions for the user.
