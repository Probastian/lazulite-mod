# Implementation Plan — Main Menu Batch 3 Fixes (Items BF1-BF7)

Spec: `features/main-menu/specification-batch-3-fixes.md` (approved).

## Summary
Seven items, sequenced to minimize rework and risk:
1. **BF3 first** (uniform left-padding) — smallest, purely additive constant
   applied per-panel; touches every panel file BF1's own edit also touches
   (`StatisticsPanel`), so landing it first avoids a second pass over the same
   render methods.
2. **BF1 next** (Statistics column layout) — isolated to `StatisticsPanel`'s
   Items/Mobs column math; independent of BF2's data-source rework (different
   concern: column X-position vs. row content), but touches the same file, so
   sequenced immediately after BF3 to finish `StatisticsPanel`'s rendering
   layer before BF2 reworks its data layer underneath it.
3. **BF2 next** (Statistics data source) — the largest-risk item (spec Risks
   #1); depends on BF1 already being in place only in the sense that both
   touch `StatisticsPanel`, not a functional dependency — sequenced third so
   the per-version `stats/<uuid>.json` parsing API is confirmed while the
   file is already open from BF1.
4. **BF6 next** (friends-sidebar HUD gating) — fully isolated to
   `FabricFriendsSidebarInjector`, no dependency on any other item.
5. **BF7 next** (Home greeting styling) — small, isolated to `HomePanel`'s
   greeting line; sequenced before BF4 so BF4's larger `HomePanel` edit
   doesn't need to rebase around a concurrent unrelated styling change.
6. **BF4 next** (join-history persistence) — the largest cross-feature-touch
   item (spec Risk #3); must land before any further `HomePanel.recentEntries()`
   changes since it replaces that method's server-timestamp source.
7. **BF5 last** (Achievements static mapping) — fully independent net-new
   resource + lookup, no dependency on any other item; sequenced last since
   its content-sourcing sub-task (FR-BF5.2) is the least certain in duration.

No implementation code is written by this plan.

## Existing Implementation

### Platform modules (confirmed in scope)
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2` all
exist (confirmed via direct glob on each module's `build.gradle` and, for
26.2, `friends/FabricFriendsSidebarInjector.java`). All three carry the same
package (`de.lazuli`) and file layout referenced by the spec; all edits below
apply ×3 unless noted.

### `platform/fabric-26.1/.../mainmenu/StatisticsPanel.java` (mirrored ×3, package differs by Yarn/Mojang mapping only)
Confirmed by direct read:
- `COL_A_X_OFFSET = 260`/`COL_WIDTH = 60` (`:184-185`) are absolute
  pixel constants, independent of `width`. `drawColValue` (`:239-243`) computes
  `colX = x + COL_A_X_OFFSET + colIndex * COL_WIDTH`; `renderTableHeader`
  (`:245-252`) computes the identical formula independently; `mouseClicked`
  (`:280-293`) re-derives the same formula a **third** time
  (`colX = x + COL_A_X_OFFSET + i * COL_WIDTH`, `:283`) — three independently
  drifting copies of the same math, confirming FR-BF1.3's concern exactly.
- `reload()` (`:56-115`) reads `Minecraft.getInstance().player.getStats()`
  (`:57-58`), null-falls-back to three empty lists (`:59-65`) — confirmed
  always-null in this panel's only reachable context per spec Background
  Findings (BF2). `General` rows (`:67-80`) read `Stats.CUSTOM` constants
  directly off the live `StatsCounter`; `Items`/`Mobs` (`:82-111`) iterate
  `BuiltInRegistries.ITEM`/`ENTITY_TYPE` and call `stats.getValue(...)` per
  entry — all of this must be re-pointed at a summed-across-saves value
  instead of a live counter, without changing the row-shape/omit-zero logic
  (`:94-96`, `:106-108`).
- `loaded` boolean (`:53`) already gates `reload()` to once-per-tab-open
  (`:141-143`) — FR-BF2.4's caching requirement is already satisfied
  structurally; no change needed to this gate itself.
- Subtitle string at `:147`: `"Tracked for " + playerName + " · across all worlds & servers"` — FR-BF2.3's wording fix target.
- No left-padding constant anywhere in `render`/`renderGeneral`/`renderItems`/
  `renderMobs`/`mouseClicked` — every left-anchored draw starts flush at `x`
  (`:146-179`, `:199`, `:227`) confirming BF3's finding for this panel.
- Sort/category-pill logic (`:117-138`, `:260-295`) is unrelated to BF1/BF2's
  scope and stays unchanged.

### `platform/fabric-26.1/.../mainmenu/AchievementsPanel.java` (mirrored ×3)
Confirmed by direct read: `render()` (`:53-91`) draws only `a.apiName()`
(`:84`) plus a locked/unlocked status (`:85-88`) — no icon, no display name,
no description, matching spec Background Findings exactly. `all()`/`filtered()`
(`:38-51`) cache `gateway.achievements(): List<AchievementSummary>` — the
static-mapping lookup (BF5) is purely additive inside `render()`'s per-row
loop, keyed off `a.apiName()`, no change to `all()`/`filtered()`/the gateway
seam. No left-padding constant here either (`:84`, `:88` both flush against
`x`/`x + width`) — BF3 touches this file too.

### `platform/fabric-26.1/.../friends/FabricFriendsSidebarInjector.java` (mirrored ×3)
Confirmed by direct read: `registerGlobalHudOverlay()` (`:215-226`) registers
a `HudElementRegistry.addLast` layer gated only on `minecraft.screen != null`
(early-return at `:218-220`) — unconditional whenever no `Screen` is open,
confirmed exactly as spec Background Findings describes. `onClientTick`
(`:265-279`) forwards a raw left-click edge-trigger under the same
`screen != null` early-return (`:266-269`) with no additional window check.
`ALLOW_LISTED_SCREENS` (`:91-97`) and `onScreenInit` (`:163-198`) are the
separate, untouched Screen-driven path (FR-BF6.5) — no change there. No
existing "last screen-closed tick" field exists yet (grep-confirmed, this
class has no tick-tracking field beyond `lastRawLeftPressed`, `:116`).

### `platform/fabric-26.1/.../mainmenu/HomePanel.java` (mirrored ×3)
Confirmed by direct read:
- Greeting: `render()`'s first line (`:117`) is a single plain
  `guiGraphics.text(font, Component.literal(greeting), x, y, 0xFFEAE8E1)` call
  — no bold, no scale. `greeting` field (`:57`, populated by `pickGreeting()`
  `:80-84`) is unchanged by BF7 — only the render call at `:117` changes.
- Recent section (`:96-114`, batch-2-fixes FR-F4.2, already implemented):
  `recentEntries()` builds `RecentEntry` records — worlds from
  `worldsPanel.recentEntries()` (real `LevelSummary` order, already
  most-recent-first) then appends `serversPanel.recentServers(): List<ServerData>`
  in that list's own saved order (`:106-109`), each server entry currently
  carries no real timestamp (`subtitle = "Saved server"`, `:108`, no
  recency field on `RecentEntry` at all — confirmed by the record shape at
  `:93-94`, which has no `lastJoinedEpochMillis`/similar field). BF4 must add
  a timestamp source to `RecentEntry` (or an equivalent lookup) and change the
  merge/sort to interleave both lists by that single real timestamp instead of
  "worlds first, then servers in list order."
- `worldsPanel`/`serversPanel` are already-constructed constructor
  dependencies (`:54-55`, `:75-76`) — BF4's new join-history record is a
  **third** small dependency of the same shape (a small facade/record passed
  in at construction), not a change to these two existing ones.
- `mouseClicked()` (`:189-229`) already invokes `worldsPanel.playWorld(...)`/
  `serversPanel.connect(...)` per Recent-card click (`:201-205`) — unchanged
  by BF4 (only the sort/timestamp source changes, not the click action).

### `platform/fabric-26.1/.../mainmenu/WorldsPanel.java` / `ServersPanel.java` (mirrored ×3)
- `WorldsPanel.recentEntries()` (`:228-230`) already returns
  `List<LevelSummary>`, already sorted (natural `LevelSummary` ordering via
  `sorted.sort(null)` at `reload()` `:99-100`) — reused unchanged by BF4.
- `ServersPanel.connect(ServerData)` (`:647`), `recentServers(): List<ServerData>`
  (`:659`), `renderFriendAvatars(...)` (`:423`) are already package-private
  (confirmed by direct grep) — no visibility change needed for BF4, unlike
  batch-2-fixes' own F4 pass which had to change these from `private`.
  `ServerData` (vanilla) still has no last-connected timestamp field of its
  own (unchanged from batch-2-fixes' own finding) — BF4's new join-history
  record is exactly the missing timestamp source, keyed by server IP.

### `features/server-join-presence/.../services/ServerSessionLifecycle.java`
Confirmed by direct read: `onJoinedRemoteServer(String host, int port)`
(`:49-51`) currently only calls `gateway.setLocalRichPresence(CONNECT_KEY, ...)`
— no persistence call, no other side effect. Its sole caller is
`platform/fabric-26.1/.../ServerJoinPresenceClientInitializer.java:105`
(inside `ClientPlayConnectionEvents.JOIN.register(...)`, `:96-107`), which
already has `server.ip`/`split.host()`/`split.port()` in scope at that exact
call site — the natural place to add a second call (a new join-history
write) alongside the existing `lifecycle.onJoinedRemoteServer(...)` call,
without changing `ServerSessionLifecycle`'s own method signature/contract.

### `platform/fabric-26.1/.../SteamJoinRequestDispatcher.java` + call sites
Confirmed by direct read: `Route.tryHandle(long friendSteamId64, String connect)`
(`:41`) already receives `friendSteamId64` as its first parameter — the
join-history friend-write needs no new data plumbing, only a recording call
added inside whichever route already returns `true` for a given format.
Two existing routes call `addRoute` today:
`ServerJoinPresenceClientInitializer.java:80-87` (decodes
`ServerConnectStringCodec`, calls `ServerJoinOperation.INSTANCE.connectToServer(...)`
then returns `true`) and `SteamWorldHostingClientInitializer.java:131` (own
format, not read in full this pass — out of BF4's own connect-string format,
Steam-World-Hosting joins are a different "join a friend's hosted world," not
a saved-server join; spec FR-BF4.3 scopes "friend-initiated join" broadly
enough to plausibly include both, but this plan's default is **only the
`server-join-presence` route** records a friend-played-with entry, since that
route's decoded target is an actual joinable `host:port`, matching FR-BF4.1's
server-oriented shape most directly — flagged in Open Questions for the
user's confirmation, not a blocking ambiguity).

### `api/.../crossworldstats/CrossWorldStatsFacade.java` + `CrossWorldStatsBridgeHandoff.java`
Confirmed by direct read: `CrossWorldStatsFacade` (`api` module) currently
exposes only `currentTotals(): CrossWorldStatsSnapshot` (`:21`) — FR-BF2's
Public API item requires one additive method,
e.g. `Set<String> localWorldIdsForCurrentAccount()`. `CrossWorldStatsBridgeHandoff`
(platform layer, mirrored ×3) is a plain `publish`/`require` static holder,
identical shape to `ServerJoinPresenceBridgeHandoff` — `require()` throws
`IllegalStateException` if called before `CrossWorldStatsClientInitializer`
publishes (composition-root ordering risk, spec Risk #1 — see Risks below).
`CrossWorldStatsConfigIO`/`AccountStats` (`features/cross-world-stats`
module) confirm the exact schema BF2 must read: `AccountStats.worldBaselines():
Map<String, Map<TrackedStat, Long>>` (record, `AccountStats.java:21`) — BF2
only needs this map's **key set**, filtered to `"local:"`-prefixed entries
(`CrossWorldStatsMergeHook.resolveWorldId`, `:81-96`, confirms this exact
prefix convention: `"local:" + saveFolderName`). The new facade method must
be backed by whichever class in `features/cross-world-stats` currently holds
the in-memory `Map<String, AccountStats> accounts` + "current account key"
resolution (not read in this pass — the service class implementing
`CrossWorldStatsFacade` was not opened; implementation's first step for BF2
is locating it, since this plan's own read stopped at the `api` interface
and platform bridge, per the "read only what's needed" discipline — flagged
as a small pre-implementation lookup, not a design risk).

### `MainMenuClientInitializer.java` (mirrored ×3) — composition-root wiring precedent
Confirmed by direct read (`:47-97`): this class already obtains
`FriendServerPresenceReader` via `ServerJoinPresenceBridgeHandoff.requirePresenceReader()`
(`:61`) and `SteamAchievementsGateway` via `SteamAchievementsGatewayHandoff.require()`
(`:66`) — both simple `require()` calls before any config load — and already
loads/warns two `*ConfigIO` files (`StoreCatalogConfigIO`, `WardrobeConfigIO`,
`:71-82`) via the exact fail-closed-with-logged-warning pattern BF4's new
`MainMenuJoinHistoryConfigIO` must follow. `buildScreen(...)` (`:99-118`)
threads every dependency into `new MainMenuScreen(...)` — BF2's
`CrossWorldStatsBridgeHandoff.require()` call and BF4's new config load both
insert here, mirroring the existing calls exactly (same file, same method,
additive parameters only).

### Texture/resource convention (BF5)
Confirmed precedent: `platform/fabric-26.1/src/main/resources/assets/lazuli/textures/gui/sync_enabled.png`
et al. — each platform module bundles its own copy of static image assets
under `src/main/resources/assets/lazuli/textures/...`. No existing
JSON/properties data-resource precedent for a name→metadata mapping was
found in this repo (grep-confirmed no comparable "static localized text
mapping" file exists yet) — BF5 is the first of this shape; this plan
defaults to a plain Java data class (see Decision 5) rather than a bundled
JSON resource, since it avoids a new hand-rolled JSON parser for a small,
build-time-fixed, non-configurable dataset (consistent with `TrackedStat`'s
own precedent as a plain Java enum rather than a JSON-backed lookup).

## Decisions

### 1. Item BF3 — uniform left-padding
- New `private static final int CONTENT_LEFT_PAD = 8;` constant, declared
  independently in each of the seven panel classes (no shared base class,
  per FR-BF3.2's explicit allowance) — same value (`8`) chosen to match the
  existing right-side inset convention already used consistently across
  panels (`StatisticsPanel`'s `x + width - valueWidth - 4` general rows use
  `4`; `AchievementsPanel`'s status column uses `8` — this plan picks `8` to
  match the more common precedent).
- Applied to every left-anchored draw call's X coordinate (`x` → `x + CONTENT_LEFT_PAD`)
  and the corresponding hit-test bounds in `mouseClicked`/`mouseScrolled` in:
  `HomePanel`, `WorldsPanel`, `ServersPanel`, `StorePanel`, `WardrobePanel`,
  `AchievementsPanel`, `StatisticsPanel` — right-aligned content (already
  computed relative to `x + width`) is untouched, per FR-BF3.1.
- Exact call sites to touch per panel are enumerated at implementation time
  by grepping each panel for its own `x +`/`x,`-prefixed draw/hit-test calls
  (same method this plan's own "Existing Implementation" research used for
  `StatisticsPanel`/`AchievementsPanel` above) — not fully re-enumerated here
  for `HomePanel`/`WorldsPanel`/`ServersPanel`/`StorePanel`/`WardrobePanel`
  to avoid re-deriving content already producible mechanically at
  implementation time; flagged as implementation's first sub-step for this
  item.

### 2. Item BF1 — Statistics column layout fix
- Introduce one private static helper, e.g.
  `private static int colX(int x, int width, int columnCount, int colIndex)`,
  computing a right-aligned column block: fixed `COL_WIDTH = 60` retained
  (visual width per column unchanged), but the block's total width
  (`columnCount * COL_WIDTH`) is anchored to `x + width - columnCount * COL_WIDTH`
  (clamped to never start left of a minimum name-column width, e.g.
  `x + CONTENT_LEFT_PAD + MIN_NAME_COL_WIDTH`, satisfying FR-BF1.2(a)'s "never
  exceeds `x + width`" and FR-BF1.2(b)'s "no large fixed gap" simultaneously
  — if the panel is narrower than `columnCount * COL_WIDTH + MIN_NAME_COL_WIDTH`,
  the column block is compressed proportionally rather than overflowing,
  implementation's exact clamp formula to confirm against FR-BF1.2's two
  guarantees at review time).
- `drawColValue`, `renderTableHeader`, and `mouseClicked`'s column hit-test
  all call this one helper instead of independently computing
  `x + COL_A_X_OFFSET + i * COL_WIDTH` (FR-BF1.3) — removes
  `COL_A_X_OFFSET` entirely as a named constant.
- Applied identically ×3 platform modules (FR-BF1.4) — same helper shape,
  same call-site replacement pattern in each module's own `StatisticsPanel.java`.

### 3. Item BF2 — Statistics data source: disk scan, account-scoped
- **New `api` method** (Public API, spec-mandated):
  `CrossWorldStatsFacade.localWorldIdsForCurrentAccount(): Set<String>` —
  returns the current account's `AccountStats.worldBaselines()` key set,
  already stripped of the `"local:"` prefix (returning bare save-folder
  names directly, since every consumer needs the bare name to resolve via
  `LevelStorageSource` — stripping once at the facade boundary avoids every
  consumer re-deriving the same substring operation). Implemented in
  whichever class in `features/cross-world-stats` already resolves "current
  account key" for `currentTotals()` (implementation's first step: locate
  that class, per Existing Implementation note above).
- **`StatisticsPanel.reload()` rework**: replace the single
  `Minecraft.getInstance().player.getStats()` read with:
  1. `Set<String> saveFolderNames = CrossWorldStatsBridgeHandoff.require().localWorldIdsForCurrentAccount();`
  2. For each name, resolve a `LevelStorageSource.LevelStorageAccess` via the
     same `LevelStorageSource` instance `WorldsPanel` already holds
     (`Minecraft.getInstance().getLevelSource()`, `WorldsPanel.java:79`) —
     `StatisticsPanel` gains its own `LevelStorageSource` field the same way,
     rather than reaching into `WorldsPanel`'s private field (keeps the two
     panels independent, consistent with this repo's own per-panel
     independence convention already noted in the batch-2-fixes plan).
  3. Parse that save's persisted `stats/<uuid>.json` — **FR-BF2.6's
     mandatory pre-implementation check**: confirm whether vanilla exposes a
     direct "load a `StatsCounter` from a save's stats file" API (e.g. via
     `net.minecraft.stats.ServerStatsCounter`'s constructor/`parseLocal`-shaped
     method, or an equivalent this repo has not yet used) per platform
     module, before falling back to a hand-rolled raw-key JSON parse this
     plan does not commit to in advance (Risk #1, carried from spec).
     The local player's UUID for the file path comes from
     `Minecraft.getInstance().getUser()` (already used at `StatisticsPanel.java:144-145`
     for display, reused here for file-path resolution too, per FR-BF2.1
     step 2's explicit note that this UUID is not the scoping mechanism,
     only the file-locator).
  4. Sum every stat key across every resolved save into the same
     `GeneralRow`/`ItemRow`/`MobRow` shapes `reload()` already builds
     (`:67-111`), preserving the existing omit-all-zero-row logic (`:94-96`,
     `:106-108`) and existing `applySort()` call (`:114`) unchanged.
  5. Empty/unreadable-save handling (FR-BF2.5): an empty `saveFolderNames`
     set or every resolved save failing to parse falls through to the
     existing empty-list assignment (`:60-64`'s shape, reused) rather than a
     new error path.
- Subtitle wording fix (FR-BF2.3): `:147`'s string literal drops
  `"& servers"` → `"Tracked for {playerName} · across all worlds"`.
- **Composition-root ordering** (Risk #1's second half): `MainMenuClientInitializer`
  must call `CrossWorldStatsBridgeHandoff.require()` only after
  `CrossWorldStatsClientInitializer` has run — implementation must confirm
  each platform module's `fabric.mod.json` `"client"` entrypoint array
  already orders `CrossWorldStatsClientInitializer` before
  `MainMenuClientInitializer` (mirroring the already-confirmed
  `ServerJoinPresenceClientInitializer`-before-`MainMenuClientInitializer`
  ordering `MainMenuClientInitializer.java:58-61`'s own comment documents) —
  if not already so ordered, reordering `fabric.mod.json` is an in-scope,
  low-risk part of this item, not a separate item.
- `features/main-menu`'s module gains a compile-time dependency on
  `api`'s `CrossWorldStatsFacade` type only (already a shared module every
  feature can depend on) — no dependency on `features/cross-world-stats`
  internals, matching spec Architecture.
- Applied identically ×3 platform modules (FR-BF2.8); exact
  `LevelStorageSource`/stats-file-parsing API names confirmed independently
  per module (Yarn 1.21.11 vs. Mojang-mapped 26.x, per this repo's standing
  discipline — `.claude/context/minecraft.md`'s Known Cross-Version API
  Differences table has no existing entry for stats-file parsing; adding one
  once confirmed is an implementation-time housekeeping step, not required
  by this plan).

### 4. Item BF6 — gate the friends-sidebar HUD overlay
- New `private long lastScreenClosedTick = Long.MIN_VALUE;` field (or
  equivalent) in `FabricFriendsSidebarInjector`, updated inside a small
  addition to the existing tick-driven logic: track the previous tick's
  `minecraft.screen` non-null/null state (a new `private boolean
  wasScreenOpenLastTick;` field), and when it transitions from `true` to
  `false` within `onClientTick` (or a small new tick hook alongside it),
  record the current tick count (`minecraft.level != null ?
  minecraft.level.getGameTime() : ...` — implementation confirms the
  cheapest available "current tick" source per platform module; a simple
  monotonically-incrementing counter local to this class, incremented once
  per `onClientTick` call, is an acceptable, simpler substitute that avoids
  any per-version tick-source API risk, this plan's recommended default).
- **Window length**: `HIDE_AFTER_TICKS = 40` (2 seconds at 20 ticks/sec) —
  chosen as a value comfortably covering a pause-menu open/close animation
  frame gap (FR-BF6.3's "handful of ticks" floor) while disappearing well
  before a player would call it "still showing during gameplay" (FR-BF6.3's
  "couple of seconds" ceiling) — a judgment call per spec's own allowance,
  flagged in Risks for post-ship tuning (spec Risk #5, carried forward).
- `registerGlobalHudOverlay()`'s render condition (`:218-220`) becomes
  `if (minecraft.screen != null || ticksSinceScreenClosed() > HIDE_AFTER_TICKS) { return; }`.
- `onClientTick`'s click-forwarding early-return (`:266-269`) gets the
  identical additional condition, per FR-BF6.2.
- `ALLOW_LISTED_SCREENS`/`onScreenInit` (`:91-198`) fully untouched (FR-BF6.5).
- Applied identically ×3 modules (FR-BF6.6).

### 5. Item BF7 — Home greeting: bold, larger
- `HomePanel.render()`'s greeting line (`:117`) wraps the existing
  `guiGraphics.text(...)` call in a `guiGraphics.pose().pushMatrix()`/`scale(...)`/
  `popMatrix()` triple (exact method names confirmed per platform module at
  implementation time, per FR-BF7.1's own flagged risk — this plan does not
  assume Mojang-mapped vs. Yarn-mapped naming matches 1:1 across modules),
  scaling by `1.3-1.5×` (targeting the mockup's 26px against this repo's
  default ~9-11px vanilla font size — exact factor implementation's call,
  since only discrete/continuous scale is available, not literal pixel
  sizing) and passing `Component.literal(greeting).withStyle(Style.EMPTY.withBold(true))`
  in place of the current plain `Component.literal(greeting)`.
- Text X/Y positioning after the scale must compensate for the scaled
  bounding box (e.g. dividing target X/Y by the scale factor before the
  scaled `text()` call, standard vanilla scaled-text idiom) so the greeting
  doesn't drift from its current left-aligned position.
- Only this one call site changes — `"Recent"`/`"Activity"` headers (`:120`,
  `:147`) explicitly untouched (FR-BF7.2).
- Applied identically ×3 modules (FR-BF7.3); exact scale-wrapping call shape
  confirmed independently per module.

### 6. Item BF4 — persisted join-history record
- **New `features/main-menu`-internal files** (not `api`/`services`, per
  spec Public API BF4 note):
  - `features/main-menu/.../config/MainMenuJoinHistoryConfig.java` — a small
    record, e.g. `record MainMenuJoinHistoryConfig(List<ServerJoinEntry> servers,
    List<FriendJoinEntry> friends)` with nested
    `record ServerJoinEntry(String ip, String name, long lastJoinedEpochMillis)`
    and `record FriendJoinEntry(long steamId64, long lastPlayedTogetherEpochMillis)`.
  - `features/main-menu/.../config/MainMenuJoinHistoryConfigIO.java` —
    hand-rolled JSON read/write, same `ParseResult(config, warning)`
    fail-closed-to-empty-defaults-with-logged-warning shape as
    `CrossWorldStatsConfigIO`'s own `ParseResult` (`CrossWorldStatsConfigIO.java:50-59`,
    `70-87`) — reusing that class's own hand-rolled JSON parser class
    (`CrossWorldStatsJson`) is **not** proposed here since it lives inside
    `features/cross-world-stats` (a different feature module) — this plan
    defaults to `features/main-menu` writing its own small equivalent
    parser (mirroring `WardrobeConfigIO`'s existing JSON approach in this
    same module, not read in this pass but the correct in-module precedent
    to copy from at implementation time) rather than introducing a
    cross-feature-module compile dependency for a JSON utility.
  - Upsert-by-key + cap-at-50-oldest-evicted-first logic (FR-BF4.1) as a
    small, pure, plain-JVM-testable static helper (e.g.
    `MainMenuJoinHistoryConfig.upsertServer(...)`/`upsertFriend(...)`
    returning a new immutable record instance) — per spec Test Strategy
    item 4's recommendation.
- **Write side, server joins (FR-BF4.2)**: `ServerJoinPresenceClientInitializer.java`'s
  existing `ClientPlayConnectionEvents.JOIN.register(...)` block (`:96-107`)
  gains one additional line alongside the existing
  `lifecycle.onJoinedRemoteServer(split.host(), split.port())` call
  (`:105`) — a call into a small new join-history-writer callback/interface
  threaded in from `MainMenuClientInitializer` (since
  `ServerJoinPresenceClientInitializer` has no existing dependency on
  `features/main-menu` and must not gain a compile-time one; instead
  `MainMenuClientInitializer` publishes a small write-side callback — e.g. a
  `Consumer<ServerJoinEntry>`-shaped functional interface — via a **new**
  narrow bridge handoff class, `MainMenuJoinHistoryWriteHandoff` (mirroring
  `CrossWorldStatsBridgeHandoff`'s exact publish/require shape), which
  `ServerJoinPresenceClientInitializer` then calls at its own composition-root
  time — this requires `ServerJoinPresenceClientInitializer` to run **after**
  `MainMenuClientInitializer` publishes, which is currently the reverse of
  today's confirmed order (`MainMenuClientInitializer` already depends on
  `ServerJoinPresenceClientInitializer` having run first,
  `MainMenuClientInitializer.java:58-61`) — **this is a real sequencing
  conflict, flagged as a planning-time blocker resolved below, not deferred**:
  this plan's resolution is to have the write-side callback registered
  **lazily** (`MainMenuJoinHistoryWriteHandoff.publish(...)` called by
  `MainMenuClientInitializer` as today, but `ServerJoinPresenceClientInitializer`'s
  `JOIN` event handler calls `MainMenuJoinHistoryWriteHandoff.ifPublished(...)`
  — a null-tolerant variant, not `require()` — since by the time any real
  `JOIN` event fires, the game has already reached the main menu at least
  once, meaning `MainMenuClientInitializer` has already run regardless of
  raw entrypoint-array order (both are `client` entrypoints resolved once at
  mod-init time, well before any player ever joins a world) — no
  entrypoint-order change is actually needed, only a null-tolerant accessor
  on the new handoff class, which this plan's `MainMenuJoinHistoryWriteHandoff`
  design adopts from the start (unlike every existing `*Handoff.require()`
  which intentionally throws).
- **Write side, friend joins (FR-BF4.3)**: same
  `MainMenuJoinHistoryWriteHandoff`, a second `ifPublished(...)`-tolerant
  callback slot, invoked from inside `ServerJoinPresenceClientInitializer.java`'s
  existing `SteamJoinRequestDispatcher.addRoute(...)` route (`:80-87`,
  the route that already receives `friendSteamId64` and successfully decodes
  a `server-join-presence`-format connect string) — added as one extra line
  inside that route's `return true;` branch, not a new route, and not
  touching `SteamWorldHostingClientInitializer`'s own separate route (per
  this plan's Decision documented in Existing Implementation above — flagged
  in Open Questions).
- **Read side / `HomePanel` amendment (FR-BF4.4)**: `HomePanel`'s constructor
  gains one new parameter, the loaded `MainMenuJoinHistoryConfig` (read once
  at `MainMenuClientInitializer` composition-root time, same pattern as
  `WardrobeConfigIO`'s load at `MainMenuClientInitializer.java:77-82`).
  `RecentEntry` (`HomePanel.java:93-94`) gains a `long timestampEpochMillis`
  field; `recentEntries()` (`:96-114`) is rewritten to: build one
  `RecentEntry` per world using `summary.getLastPlayed()` (unchanged source),
  one `RecentEntry` per saved server using the join-history config's
  `servers` list looked up by `server.ip` (falling back to
  `Long.MIN_VALUE`/current-list-order-preserving sentinel for a saved server
  never found in the record, per FR-BF4.4's explicit fallback allowance),
  concatenate both lists, sort descending by `timestampEpochMillis`, then
  truncate to `RECENT_MAX_ENTRIES` (unchanged constant, `:38`).
- **Friend-played-with UI surfacing (FR-BF4.5 scope call)**: **not surfaced
  into Home's Recent section UI this pass** — persisted-but-not-yet-displayed,
  per the spec's own explicitly allowed default (FR-BF4.5's second option) —
  this plan's chosen scope reduction, since no clean UI slot for a
  standalone "friends played with" list exists in `HomePanel`'s current
  layout without a larger UI-design pass out of this bug-fix batch's own
  framing; recorded as a Future Extension (already listed in the spec).
- Config file: `config/main-menu-join-history.json`, loaded/saved via
  `MainMenuJoinHistoryConfigIO`, same directory (`FabricLoader.getInstance().getConfigDir()`)
  and lifecycle as `main-menu-wardrobe.json`/`main-menu-store-catalog.json`.
- Applied identically ×3 platform modules (FR-BF4.7) — same new
  `MainMenuJoinHistoryWriteHandoff` class, same two call-site insertions in
  each module's own `ServerJoinPresenceClientInitializer.java`, same
  `HomePanel`/`MainMenuClientInitializer` edits.

### 7. Item BF5 — Achievements static mapping
- **New shared data resource**: a plain Java class in
  `features/main-menu`'s own module (not `api`/`services`, per spec Public
  API note — this plan's default location, since the mapping is a
  platform/UI-layer concern per spec Architecture but a single shared,
  version-independent Java class avoids duplicating the *content*
  (name/description text) ×3 platform modules, while the *icon assets*
  themselves still duplicate ×3 per this repo's existing per-module
  resource convention), e.g.
  `features/main-menu/.../achievements/SpacewarAchievementMapping.java`:
  a `static final Map<String, AchievementMetadata>` where
  `record AchievementMetadata(String displayName, String description, String iconAssetPath)`
  — `iconAssetPath` an `assets/lazuli/textures/achievements/<apiName>.png`-shaped
  relative path each platform module resolves via its own `Identifier`
  construction (mirroring `IconTextureCache`'s existing pattern already
  used by `WorldsPanel`/`HomePanel` for other icons).
- **Content sourcing (FR-BF5.2, deferred sub-task)**: implementation's first
  step for this item is locating/transcribing real Spacewar (App ID `480`)
  achievement metadata from publicly available Steamworks SDK sample
  material — this plan does not pre-populate any entries; a partial/thin
  mapping is an accepted possible outcome (spec Risk #4), with FR-BF5.3's
  fallback ensuring no broken/incorrect rendering results either way.
- **Icon assets**: one `.png` per mapped achievement under each platform
  module's own `src/main/resources/assets/lazuli/textures/achievements/<apiName>.png`,
  identical bytes ×3 modules (matching the existing `sync_enabled.png` et
  al. duplication convention) — a small platform-specific `IconTextureCache`-style
  lookup (or direct `Identifier.fromNamespaceAndPath` construction, since
  these are static, always-present bundled assets, not upload-cached
  favicons — no cache miss/fallback texture logic needed, simpler than
  `IconTextureCache`) resolves `iconAssetPath` to a real `Identifier` per
  platform module.
- **`AchievementsPanel` lookup (FR-BF5.3)**: inside the existing per-row loop
  (`:78-90`), `SpacewarAchievementMapping.MAPPING.get(a.apiName())` — found:
  render `displayName`/`description`/icon in place of the current
  `a.apiName()`-only line (`:84`); not found: current rendering unchanged,
  byte-for-byte (no new branch's side effect on the not-found path beyond
  the existing code already there).
- No `AchievementSummary`/`SteamAchievementsGateway` change (Public API,
  confirmed no new field needed).
- Applied identically ×3 platform modules (FR-BF5.5) — same shared
  `features/main-menu` mapping-content class, ×3 duplicated icon asset sets.

## Files to Create
- `features/main-menu/.../config/MainMenuJoinHistoryConfig.java` (BF4, ×1 shared).
- `features/main-menu/.../config/MainMenuJoinHistoryConfigIO.java` (BF4, ×1 shared).
- `platform/fabric-<version>/.../MainMenuJoinHistoryWriteHandoff.java` (BF4, ×3, new narrow bridge-handoff class, one per platform module's own composition-root package).
- `features/main-menu/.../achievements/SpacewarAchievementMapping.java` (BF5, ×1 shared).
- `platform/fabric-<version>/src/main/resources/assets/lazuli/textures/achievements/<apiName>.png` (BF5, ×3, one file per mapped achievement, identical content per module, exact count/names determined once FR-BF5.2's content sourcing completes).

## Files to Modify
- `api/src/main/java/de/lazuli/api/crossworldstats/CrossWorldStatsFacade.java`
  — add `localWorldIdsForCurrentAccount(): Set<String>` (BF2, ×1 shared).
- `features/cross-world-stats/.../<the class implementing CrossWorldStatsFacade>`
  (exact file located at implementation time, per Existing Implementation
  note) — implement the new method (BF2, ×1 shared, but see Risk note on
  whether this class is per-platform or shared).
- `platform/fabric-<version>/.../mainmenu/StatisticsPanel.java` (×3) — BF1
  column-math consolidation; BF2 `reload()` disk-scan rework + subtitle
  wording; BF3 left-padding constant applied throughout.
- `platform/fabric-<version>/.../mainmenu/AchievementsPanel.java` (×3) — BF5
  mapping lookup in the row loop; BF3 left-padding constant applied.
- `platform/fabric-<version>/.../mainmenu/HomePanel.java` (×3) — BF7 greeting
  bold/scale; BF4 new constructor parameter + `RecentEntry`/`recentEntries()`
  rework; BF3 left-padding constant applied.
- `platform/fabric-<version>/.../mainmenu/WorldsPanel.java`,
  `ServersPanel.java`, `StorePanel.java`, `WardrobePanel.java` (×3 each) — BF3
  left-padding constant applied (no other change to these four files this
  pass).
- `platform/fabric-<version>/.../friends/FabricFriendsSidebarInjector.java`
  (×3) — BF6 tick-window gating on both `registerGlobalHudOverlay()` and
  `onClientTick`.
- `platform/fabric-<version>/.../ServerJoinPresenceClientInitializer.java`
  (×3) — BF4: one new join-history write call inside the existing `JOIN`
  event handler; one new join-history write call inside the existing
  `server-join-presence` `SteamJoinRequestDispatcher` route.
- `platform/fabric-<version>/.../MainMenuClientInitializer.java` (×3) — BF2:
  `CrossWorldStatsBridgeHandoff.require()` call + threading into
  `StatisticsPanel`'s construction; BF4: `MainMenuJoinHistoryConfigIO` load +
  `MainMenuJoinHistoryWriteHandoff.publish(...)` call + threading the loaded
  config into `HomePanel`'s construction.
- Each platform module's own `fabric.mod.json` (×3, only if BF2's
  composition-root-ordering check finds `CrossWorldStatsClientInitializer`
  not already ordered before `MainMenuClientInitializer` — conditional,
  confirmed at implementation time).

## Interfaces
- `api/.../crossworldstats/CrossWorldStatsFacade` — additive method,
  `localWorldIdsForCurrentAccount(): Set<String>` (BF2).
- `platform/fabric-<version>/.../MainMenuJoinHistoryWriteHandoff` — new,
  `publish(...)`/`ifPublished(...)` (null-tolerant, unlike every existing
  `require()`-throwing handoff in this repo — a deliberate, documented
  deviation per Decision 6's sequencing resolution) (BF4).
- `features/main-menu/.../config/MainMenuJoinHistoryConfig` — new record
  (BF4).
- `features/main-menu/.../achievements/SpacewarAchievementMapping` — new
  data holder (BF5).

## Services
No new `services`-module type for any item — BF2 stays in the Version
Adapter layer per spec Architecture (disk-scan logic, no Steamworks
involvement); BF5's mapping is a platform/UI-layer lookup only.

## Test Strategy
Per this repo's standing convention (manual, in-game, per-platform-module
verification for rendering/layout; unit tests for plain-JVM-testable logic):

1. **Unit tests (plain JVM)**:
   - BF2: if the multi-save stat-summation logic is extracted into a small
     pure helper (recommended, taking a `List<Map<Stat,Long>>`-shaped input
     rather than live `StatsCounter`/`LevelStorageSource` objects), unit-test
     correct cross-world summation and correct account-scoped
     inclusion/exclusion with fake per-world stat maps.
   - BF4: `MainMenuJoinHistoryConfig`'s upsert-by-key + cap/eviction helper,
     unit-tested directly with fake entries (confirms most-recent-first
     ordering and oldest-evicted-first behavior at the cap).
   - BF5: the mapping lookup (found/not-found branching), unit-tested with a
     small fake map and a handful of `apiName()` values (present and
     absent), independent of the mapping's real Spacewar content.
   - BF2's new `CrossWorldStatsFacade` method's implementation, if it has any
     non-trivial filtering logic beyond a direct field read, unit-tested in
     `features/cross-world-stats`'s own existing test suite.
2. **Compilation** — `gradlew build`/`compileJava` succeeds on all three
   platform modules, plus `features/main-menu`, `features/cross-world-stats`
   (`CrossWorldStatsFacade` gains a method), and `features/server-join-presence`
   (no interface change there per this plan's Decision 6, so likely
   unaffected — confirmed at implementation time).
3. **Manual in-game verification, per platform module** (all three), mirrored
   from spec Test Strategy:
   - BF1: Items/Mobs columns stay within the panel's right edge at multiple
     window widths (including near `MIN_PANEL_WIDTH`), no large empty gap.
   - BF2: after playing a singleplayer world and returning to the main menu,
     General/Items/Mobs show real non-zero data; multi-save summation and
     account-exclusion both verified; subtitle reads "across all worlds."
   - BF3: all seven panels' content starts with the same left inset as their
     other insets.
   - BF4: join a real saved server, reopen the main menu — Recent section
     reflects the real join timestamp, correctly interleaved with worlds;
     persisted file survives a restart.
   - BF5: mapped achievements show localized name/description/icon; unmapped
     achievements show the existing raw-name-only rendering, no artifacts.
   - BF6: sidebar/handle fully hidden a couple of seconds into ordinary
     gameplay; no flicker/reset around a quick Escape-open/close.
   - BF7: greeting text visibly bold and larger than "Recent"/"Activity"
     headers.

## Dependencies
No new external Maven/Gradle dependency for any of the seven items —
BF1/BF3/BF6/BF7 are pure in-place logic/rendering changes; BF2 reuses
`net.minecraft.stats.*` (already on every platform module's classpath, same
as batch-2's own F5) plus the already-in-repo `CrossWorldStatsFacade`
interface; BF4's persistence follows the existing hand-rolled-JSON
`*ConfigIO` convention (no JSON library dependency, matching every other
`*ConfigIO` in this repo); BF5's mapping is a bundled static resource, no
library. Confirmed via direct read of every relevant file above — no Maven
coordinate lookup was needed for this plan.

## Risks
1. **BF2 remains the largest-risk item** (carried from spec Risk #1):
   (a) the exact per-version API to parse a save's persisted
   `stats/<uuid>.json` into usable `Stat` values is not yet confirmed — if no
   reusable "load `StatsCounter` from file" API exists, a hand-rolled parse
   is meaningfully larger than this plan's own default assumption; (b) this
   plan did not locate the concrete class implementing `CrossWorldStatsFacade`
   inside `features/cross-world-stats` during planning (grep/read scoped to
   the `api` interface, platform bridge, and config/model classes only, per
   this plan's "read only what's needed" discipline) — implementation's
   first step for BF2 must locate it before adding the new method; (c)
   composition-root ordering between `CrossWorldStatsClientInitializer` and
   `MainMenuClientInitializer` must be confirmed per platform module's
   `fabric.mod.json` (this plan's `require()` call assumes it is, or can be
   made, ordered — if genuinely un-orderable for some reason not yet found,
   `CrossWorldStatsBridgeHandoff` would need the same `ifPublished(...)`
   null-tolerant treatment this plan already applies to
   `MainMenuJoinHistoryWriteHandoff`, a small design adjustment, not a
   blocker).
2. **BF1's fix must not regress the sort-column hit-test** — FR-BF1.3's
   single-helper consolidation is specifically designed to prevent this, but
   implementation must verify `mouseClicked`'s existing sort-toggle behavior
   (`sortAscending = sortColumn == clicked && !sortAscending`) still fires
   correctly against the new column-position formula.
3. **BF4's cross-feature write-side hooks** touch
   `ServerJoinPresenceClientInitializer.java` (a different feature's
   composition-root file) twice — this plan's `MainMenuJoinHistoryWriteHandoff`
   null-tolerant-accessor design (Decision 6) resolves the sequencing
   conflict this plan found between `MainMenuClientInitializer`'s existing
   dependency on `ServerJoinPresenceClientInitializer` having already run and
   BF4's need for the reverse direction — this resolution should be
   re-verified against each platform module's actual `fabric.mod.json`
   entrypoint array at implementation time, since this plan's reasoning
   (both entrypoints resolve before any player ever joins a world) is sound
   but not literally read from any one module's `fabric.mod.json` in this
   pass.
4. **BF4's scope decision to exclude `SteamWorldHostingClientInitializer`'s
   own route from friend-join-history recording** (Existing Implementation
   note, Decision on FR-BF4.3) is this plan's own default, not explicitly
   dictated by the spec — flagged in Open Questions for the user's
   confirmation before implementation, since the spec's FR-BF4.3 wording
   ("whichever route already claims that join") could be read either way.
5. **BF5's content-sourcing sub-task** (FR-BF5.2) remains unsourced/
   unverified at planning time (spec Risk #4, carried forward) — a
   thin/partial mapping is an accepted possible outcome; the fallback
   behavior ensures no broken rendering regardless.
6. **BF6's tick-window length (`HIDE_AFTER_TICKS = 40`)** is a judgment call
   with no hard precedent (spec Risk #5, carried forward) — may need
   post-ship tuning based on user feedback.
7. **BF3's per-panel constant introduces a small uniform visual shift** across
   all seven panels simultaneously — low functional risk, but worth a single
   combined visual pass (not per-panel) during manual verification to catch
   any panel where the shift causes an unexpected wrap/overflow at narrow
   widths (interacts loosely with BF1's own width-sensitivity fix in
   `StatisticsPanel` specifically — verify both together in that one panel).

## Acceptance Criteria
- **FR-BF1.1-1.4** — `StatisticsPanel`'s Items/Mobs column positions derive
  from one shared helper across render and hit-test; no column overflows
  `x + width` at any tested panel width; no large fixed gap at narrow
  widths; identical across all three platform modules.
- **FR-BF2.1-2.8** — `StatisticsPanel.reload()` no longer reads
  `player.getStats()`; General/Items/Mobs populate with real, non-zero,
  multi-save-summed, account-scoped data after play; subtitle reads "across
  all worlds"; empty/unreadable-save states show existing empty messaging,
  not an error; `CrossWorldStatsFacade` gains the new method with no
  behavior change to `currentTotals()`'s existing consumers; identical
  across all three platform modules.
- **FR-BF3.1-3.4** — all seven panels' left-anchored content shifts by the
  same shared constant; right-aligned content and `MainMenuScreen`'s own
  panel-background fill are unaffected; identical across all three platform
  modules.
- **FR-BF4.1-4.7** — `config/main-menu-join-history.json` persists real
  server-join and friend-join entries at real join-transition moments
  without changing `ServerSessionLifecycle`'s or `SteamJoinRequestDispatcher`'s
  existing contracts for their existing consumers; `HomePanel.recentEntries()`
  interleaves worlds and servers by one real recency timestamp, falling back
  to existing behavior only for a server never found in the record; capped,
  most-recent-first, oldest-evicted-first; loaded once at composition-root
  time; identical across all three platform modules.
- **FR-BF5.1-5.5** — static mapping resource (name/description/icon) keyed
  by raw `apiName()`; `AchievementsPanel` shows mapped metadata where present,
  unchanged raw-name-only rendering where absent, no error/placeholder
  artifact; no `AchievementSummary`/gateway change; identical across all
  three platform modules (each bundling its own icon asset copies).
- **FR-BF6.1-6.6** — `registerGlobalHudOverlay()`'s render and
  `onClientTick`'s click-forwarding are both gated by the same short
  post-screen-close tick window instead of unconditional `screen == null`;
  `ALLOW_LISTED_SCREENS`/`onScreenInit` unchanged; no new settings/toggle;
  identical across all three platform modules.
- **FR-BF7.1-7.3** — Home's greeting line renders bold and visibly larger
  than "Recent"/"Activity" headers, which remain unchanged; identical across
  all three platform modules.
- **Compatibility** — `gradlew build` succeeds on all three platform modules
  with every new/changed file in place.

## Open Questions
1. **BF4's friend-join-history scope** (Risk #4): should
   `SteamWorldHostingClientInitializer`'s own `SteamJoinRequestDispatcher`
   route also write a friend-played-with entry, or only
   `server-join-presence`'s route (this plan's default)? Needs the user's
   confirmation before implementation of that specific sub-task, though it
   does not block starting implementation on any other item in this pass.
2. **BF2's concrete `CrossWorldStatsFacade`-implementing class location** —
   not identified during this planning pass (see Risk #1(b)); implementation's
   first BF2 sub-step, not a design ambiguity needing the user's input.
3. Everything else this plan resolved with an explicit default (BF1's exact
   clamp formula, BF3's `8`px constant, BF6's `40`-tick window, BF7's exact
   scale factor, BF5's Java-class-vs-JSON-resource choice) is flagged as
   implementation's judgment call within the spec's own stated tolerances,
   not blocking sign-off on this plan.
</content>
