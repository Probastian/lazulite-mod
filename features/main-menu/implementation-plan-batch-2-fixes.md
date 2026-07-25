# Implementation Plan — Main Menu Batch 2 Fixes (Items F1-F5)

Spec: `features/main-menu/specification-batch-2-fixes.md` (approved).

## Summary
Five items, sequenced to minimize rework and risk:
1. **F3 first** (sidebar double-render) — smallest, lowest-risk, one deny-list
   entry, unblocks clean manual testing of everything else in this pass.
2. **F2 next** (tab reorder + Home default-selected) — small, mechanical,
   touches the same `MainMenuTab`/`MainMenuScreen` files every other item's
   UI sits inside.
3. **F4 next** (Home enrichment: greeting, merged recent section) — depends on
   F2 already being in place (Home is now the landing tab, so its content
   quality matters immediately).
4. **F1 next** (finish Achievements tab) — gated on the FR-BB3.1 `javap`
   check; sequenced before F5 since it reuses/mirrors the same
   "how do we add a real tab panel" wiring F5 also needs, and its branch
   outcome (A/B) may finish quickly either way.
5. **F5 last** (new Statistics tab) — the largest net-new scope item, wholly
   independent data source (vanilla stats, no Steamworks), least risk of
   being blocked by anything else in this pass.

No implementation code is written by this plan.

## Existing Implementation

### `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java`
Current declaration order: `WORLDS, SERVERS, STORE, WARDROBE, HOME,
ACHIEVEMENTS`. No `STATISTICS` value exists yet. Single shared `api` file —
one edit here covers ordering + the new value for all three platform modules.

### `platform/fabric-<version>/.../mainmenu/MainMenuScreen.java` (all 3 modules)
- `TABS = MainMenuTab.values()` (`fabric-1.21.11:63`) — tab bar iterates this
  array directly; both `renderTabBar()` and `mouseClicked()` use it, so
  reordering the enum's declaration reorders the bar with no other code
  change, *provided* no other code depends on ordinal order (checked below).
- `MainMenuStateMachine state = new MainMenuStateMachine()` (`:65`) —
  `MainMenuStateMachine.activeTab()` starts `null` (base spec FR1.3's
  intentional default); `MainMenuScreen`'s own constructor never seeds it.
- `render()`'s tab `switch` (`:223-231`) already has a `HOME` case
  (`homePanel.render(...)`) and an `ACHIEVEMENTS` case (static placeholder
  text) — a `STATISTICS` case must be added; F1's `ACHIEVEMENTS` case must be
  replaced with a real `AchievementsPanel` call (Branch A) or left as an
  accurate placeholder (Branch B).
- `mouseClicked()`'s tab-specific dispatch (`:320-343`) already handles
  `WORLDS`/`SERVERS`/`STORE`/`HOME`/`WARDROBE` — `ACHIEVEMENTS`/`STATISTICS`
  need their own branches once those panels exist (or can be no-ops if
  read-only, no clickable rows beyond scroll/filter-pill clicks, which do
  need a branch).
- Constructor (`:81-104`) already builds `worldsPanel`, `serversPanel`,
  `storePanel`, `wardrobePanel`, `homePanel` — a `statisticsPanel` (F5) and,
  if Branch A, `achievementsPanel` (F1) field/construction go here too.

### `platform/fabric-<version>/.../friends/FabricFriendsSidebarInjector.java` (all 3 modules)
- `DENY_LISTED_SCREENS = Set.of()` (`fabric-1.21.11:66`) — confirmed empty;
  F3 adds exactly one entry, `MainMenuScreen.class`.
- Root cause (spec Findings Summary item 3) fully confirmed by direct read:
  `MainMenuScreen` builds its own `FriendSidebarWidget` (`MainMenuScreen.java:96`,
  `addDrawableChild(sidebar)` at `:138`) *and* is not deny-listed, so
  `onScreenInit` (`:131-166`) also attaches the injector's own
  `activeSidebar` to it, rendered via `onAfterRender` (`:278-290`) —
  confirmed double-render, independent of any HUD-callback-path concern.

### `platform/fabric-<version>/.../mainmenu/HomePanel.java` (all 3 modules)
Current content (confirmed by direct read): Steam-availability gate, empty
state, then a scrollable list of friends currently playing this game
(`friendsPlayingThisGame()`, filtered/sorted, avatar+name+status per row,
row click opens `FriendContextMenuWidget` via the passed `RowClickListener`).
This *is* F4's entire "Activity section" scope (per spec FR-F4.3) — no
change needed to this part. F4 adds a greeting line and a "Recent" section
around/above it; F2 additionally requires `MainMenuScreen` to open with
`HOME` already selected.

### `platform/fabric-<version>/.../mainmenu/WorldsPanel.java` (all 3 modules)
- `relativeTime(long epochMillis)` (private, `:54-69`) — exact relative-time
  formatter F4's Recent section must reuse (not reimplement). Currently
  private; needs package-private or a small extracted shared utility (see
  Decision 4 below).
- `playWorld(WorldListWidget.WorldEntry entry)` (private, `:233-239`) — the
  real vanilla world-load action F4's Recent section must invoke for a world
  card click. Currently private, takes a `WorldListWidget.WorldEntry`
  (vanilla, headless, already loaded per-row in `WorldsPanel.entries`).
- `entries` (`private List<WorldListWidget.WorldEntry> entries`) already
  carries `LevelSummary.getLastPlayed()` per entry (used by `relativeTime`
  today) — the data source F4 needs for "recently played worlds," already
  loaded, no new read.
- `iconCache` (`IconTextureCache`) already produces a real-or-fallback world
  icon `Identifier` per row (`iconCache.forWorld(...)`) — reusable for the
  Recent section's thumbnail, per spec FR-F4.2's "reuse whatever
  thumbnail/icon mechanism WorldsPanel/ServersPanel already use."

### `platform/fabric-<version>/.../mainmenu/ServersPanel.java` (all 3 modules)
- `savedServers` (`ServerList`) already holds every saved `ServerInfo`, which
  (per vanilla's own `ServerInfo`, `net.minecraft.client.network.ServerInfo`)
  does **not** itself carry a last-connected timestamp field (confirmed:
  vanilla's `ServerInfo` has no "last played" concept, unlike
  `LevelSummary.getLastPlayed()` for worlds) — **planning gap flagged**: F4's
  "recently played... servers... ordered by recency" requires *some*
  timestamp per saved server, which vanilla does not provide out of the box.
  See Decision 3 for how this plan resolves it.
- `connect(ServerInfo server)` (private, `:648-651`) — the real vanilla
  connect action F4's Recent section must invoke for a server card click.
- `friendServerPresenceReader`/`friendsSidebarFacade`/`avatarTextureCache`
  (already-wired fields) plus the existing `renderFriendAvatars(...)` private
  helper (`:425-457`) — F4's "avatars of friends online on that server" reuses
  this exact method (needs exposure, see Decision 4).
- `iconCache.forServer(rowId, server.getFavicon())` — reusable thumbnail
  source for server Recent-cards.

### `services/steamworks-inventory-bindings` fork (F1's prerequisite question)
Unchanged from `implementation-plan-batch-2.md`'s own findings — still not
established whether `SteamUserStats` is wrapped; `gradle.properties:51`
(`steamworks4j_version=v1.10.0-inventory.1`) is the coordinate to inspect.
FR-F1.1's `javap` check is this plan's mandatory first *implementation* step
for Item F1, same as batch-2 originally required (never actually executed).

### Vanilla stats API (F5's data source — not yet used anywhere in this repo)
No existing file in this repo reads `net.minecraft.stats.Stats`/
`StatType`/`StatsCounter` — confirmed via grep, zero hits. This is a wholly
new integration point; FR-F5.2's per-version accessor confirmation
(`player.getStatHandler()` or equivalent) is this plan's Risk 5, not
resolved by this planning pass (no per-version `javap`/source access
performed here — same "confirm at implementation time" posture this repo's
other version-divergent-API items already take, e.g. batch-2's own
raw-mouse-input risk).

## Decisions

### 1. Item F3 — deny-list entry (FR-F3.1-3.3)
- Add `MainMenuScreen.class` to `DENY_LISTED_SCREENS` in all three platform
  modules' `FabricFriendsSidebarInjector.java`. One-line change ×3 files.
  `MainMenuScreen` already has its own dedicated `FriendSidebarWidget`
  instance (unaffected) — this only stops the injector's *separate* instance
  from also attaching there.
- No other file changes. No test needed beyond manual verification (this is
  a rendering/attachment behavior, same class of fix as post-launch-fixes-3).

### 2. Item F2 — reorder + default-selected (FR-F2.1-2.3)
- **Ordering approach**: reorder `MainMenuTab`'s enum declaration directly
  (`HOME, WORLDS, SERVERS, STORE, WARDROBE, ACHIEVEMENTS, STATISTICS`) rather
  than introducing a separate `TABS` literal — grep confirms
  (`MainMenuScreen.java`, all three modules) the only consumers of
  `MainMenuTab.values()`/ordinal order are `MainMenuScreen.TABS` itself and
  the `switch` statements in `render()`/`mouseClicked()`, which are
  order-independent (`switch` doesn't care about ordinal). No config/
  persistence serializes `MainMenuTab` by ordinal (Wardrobe's `WardrobeSlot`
  is the only persisted enum in this feature, confirmed unrelated). Safe to
  reorder directly.
- **Default-selected approach**: add a `MainMenuStateMachine(MainMenuTab
  initialTab)` constructor overload (defaulting the existing no-arg
  constructor to `null` for every other call site, to avoid touching
  anything outside `MainMenuScreen`), and have `MainMenuScreen`'s own
  constructor call `new MainMenuStateMachine(MainMenuTab.HOME)` instead of
  `new MainMenuStateMachine()`. `worldsPanel.setTabActive(...)`/
  `serversPanel.setTabActive(...)` calls in `init()` already read
  `state.activeTab() == MainMenuTab.WORLDS`/`SERVERS` — these naturally
  become `false` at construction now (since `HOME` is active), no change
  needed there; `homePanel` doesn't have its own `setTabActive` today (its
  `render`/`mouseClicked` are only invoked when `active == HOME` in
  `MainMenuScreen` already) — no change needed.
- Applied across `api/.../MainMenuTab.java` (single shared file, one edit,
  ×1) and `MainMenuStateMachine.java` (single shared file in
  `features/main-menu`, ×1) and each platform's `MainMenuScreen.java`
  constructor call site (×3).

### 3. Item F4 — Home enrichment (FR-F4.1-4.5)
- **Greeting (FR-F4.1)**: a `private static final List<String> GREETINGS`
  constant in `HomePanel` (or `MainMenuScreen`, implementation's choice —
  this plan recommends `HomePanel` since it's the only consumer) with the
  six exact strings from the spec; `{Playername}` substitution reads the
  local player's name via `MinecraftClient.getInstance().getSession().getUsername()`
  (already the established "local player display name" source in this
  codebase — confirmed pattern, same accessor class used elsewhere for
  Steam/session identity) — picked once via `new Random().nextInt(...)` (or
  `ThreadLocalRandom`) at `HomePanel` construction (which happens once per
  `MainMenuScreen` construction, matching the design file's
  "componentDidMount" semantics).
- **Recent section — the timestamp-source gap (Decision, resolves the
  Existing Implementation flag above)**: vanilla `ServerInfo` has no
  last-connected timestamp. Rather than inventing new persistence (a
  Non-goal), this plan's recommended default is: **worlds contribute their
  real `LevelSummary.getLastPlayed()` timestamp; saved servers are ordered
  after all worlds, in their existing saved-list order (most-recently-added/
  edited first, i.e. `ServerList`'s own stored order, which already reflects
  recency of the user's own list-management actions), with no real
  per-server last-connected timestamp available** — an explicit, documented
  scope reduction (not a silent gap): the merged list is "recency-sorted
  where real timestamps exist (worlds), append saved servers in existing
  list order where they don't (servers)," not a byte-for-byte interleave-by-
  identical-precision-timestamp as the design mock implies. This is flagged
  to the user for visibility (see Risk 3) — if precise per-server recency
  is wanted later, `server-join-presence`'s own connect/disconnect
  lifecycle (which *does* observe real connect events) could be extended to
  persist a last-connected-at timestamp per host:port as a small follow-up,
  out of scope here per the spec's own no-new-persistence Non-goal.
- **Data plumbing**: `HomePanel` gains two new constructor dependencies:
  a `Supplier<List<WorldListWidget.WorldEntry>>` (or a small
  `RecentWorldsSource` interface) from `WorldsPanel`, and a
  `Supplier<List<ServerInfo>>` (or `RecentServersSource`) from `ServersPanel`
  — `MainMenuScreen`'s constructor wires both from its already-constructed
  `worldsPanel`/`serversPanel` instances. `WorldsPanel.playWorld(...)` and
  `ServersPanel.connect(...)` both change from `private` to
  package-private (same `de.lazuli.mainmenu` package as `HomePanel`), and
  each panel gains a small public accessor exposing what's needed (e.g.
  `WorldsPanel.recentEntries(): List<WorldListWidget.WorldEntry>`,
  `ServersPanel.recentServers(): List<ServerInfo>`) rather than exposing raw
  internal fields directly — `HomePanel` never reaches into `entries`/
  `savedServers` fields itself.
- **Friend avatars on server recent-cards**: `HomePanel` already receives a
  `FriendsSidebarFacade`/`AvatarTextureCache` (constructor, existing) — needs
  the same `FriendServerPresenceReader` `MainMenuScreen`/`ServersPanel`
  already have, threaded into `HomePanel`'s constructor (one new parameter);
  reuses `ServersPanel`'s existing `renderFriendAvatars`-shaped logic (small
  duplication of ~15 lines is acceptable here per this repo's existing
  per-panel-independent-render-code convention, rather than extracting a
  shared utility class not otherwise justified by this pass's scope) OR
  `ServersPanel.renderFriendAvatars(...)` is promoted from private to
  package-private and called directly by `HomePanel` — **this plan's
  recommended default**, since it's the exact same visual element (small
  avatar row + "+N" badge) and duplicating it risks drift.
- **Rendering**: `HomePanel.render(...)` gains, above (or below — exact
  vertical order an implementation-time call, this plan defaults to
  "greeting, then Recent, then friends-playing-list" matching the design
  file's own top-to-bottom order) the existing friends-list content: a
  greeting text line, then a "Recent" section (bounded to ~5-6 cards per
  spec FR-F4.2, each: thumbnail, name, subtitle, relative time, optional
  friend avatars), each card clickable to invoke the corresponding
  play/connect action. Uses the existing row/card visual conventions already
  established in `HomePanel`/`WorldsPanel`/`ServersPanel` (fills, hover
  states, `0xFF`-alpha colors).
- **Featured-item carousel (FR-F4.4)**: **deferred to Future Extensions this
  pass** — `StorePanel`'s current featured-banner code (post-launch-fixes-3
  FR-B3.7) is static (a single always-shown featured item, no
  rotation/timer/dot-indicator state) — building actual timed rotation is
  non-trivial new animation-state infrastructure per the spec's own escape
  hatch, not "materially small effort." This plan defers it; flagged for the
  user's visibility as a deliberate scope cut, not an oversight.

### 4. Item F1 — finish Achievements tab (FR-F1.1-1.4)
Unchanged in shape from `implementation-plan-batch-2.md`'s own Decision 4
(Branch A/B structure) — this plan's only addition is that the check must
actually run this time, as implementation's mandatory first step, and its
result recorded in this plan's own "Existing Implementation" section (via a
plan addendum/implementation note) before any UI code is written. If Branch
A, additionally wire the progress-bar row variant (spec FR-F1.2) using
`GetAchievementProgressLimits`-shaped data if FR-F1.1's check confirms it's
available; otherwise ship locked/unlocked/checkmark-only rows (no progress
bar) as an accepted v1 reduction, per the spec's own explicit allowance.

### 5. Item F5 — new Statistics tab (FR-F5.1-5.7)
- New `MainMenuTab.STATISTICS` value (added in the same Item F2 enum edit,
  last position).
- New `platform/fabric-<version>/.../mainmenu/StatisticsPanel.java` (×3),
  structurally mirroring `WorldsPanel`/`ServersPanel`'s existing
  constructor-takes-state-and-owner shape, `init(...)`/`render(...)`/
  `mouseClicked(...)` method shape, and (per FR-F5.4/5.5) a small internal
  sub-tab filter (`GENERAL`/`ITEMS`/`MOBS`, a local enum inside
  `StatisticsPanel`, not `MainMenuStateMachine`-tracked, matching
  `ServersSubView`'s existing precedent of a panel-owned sub-view enum
  stored in `MainMenuStateMachine` if cross-construction persistence is
  wanted, or panel-local if not — this plan defaults to **panel-local**,
  simplest, since there's no stated requirement the sub-tab selection
  persist across tab switches).
- **Data access**: `MinecraftClient.getInstance().player.getStatHandler()`
  (or the confirmed per-version equivalent, FR-F5.2 — this plan does not
  assume the exact accessor name; implementation's first step for this item
  mirrors F1's "confirm the real API surface before writing UI code"
  discipline) exposes `getStat(StatType<T>, T)`-shaped reads. General-tab
  rows map directly to a fixed list of `(label, Stat, formatter)` tuples
  (spec FR-F5.3's twelve named stats); Items/Mobs tabs iterate
  `Registries.ITEM`/`Registries.ENTITY_TYPE` (or per-version equivalent)
  filtering to nonzero-stat entries (FR-F5.6's "omit all-zero rows" default,
  this plan's recommended choice — matches "don't clutter" convention,
  flagged as overridable at implementation time per the spec's own explicit
  non-decision).
- **Sortable table reuse**: `ServersPanel`'s Browser-sub-view column-header
  click-to-sort pattern (`drawColumnHeader`/`ServerBrowserColumn`-shaped
  click-region dispatch, `ServersPanel.java:549-553`/`591-614`) is the
  structural template — `StatisticsPanel` implements its own small
  `ItemStatColumn`/`MobStatColumn`-shaped local sort-column enum (not shared
  with `ServerBrowserColumn`, a different, unrelated data domain) and mirrors
  the same click-region/ascending-toggle logic locally, not a shared utility
  class (consistent with this repo's existing "duplicate small per-panel
  logic rather than force a shared abstraction across unrelated domains"
  convention, e.g. `WorldsPanel`/`ServersPanel`'s independent row-expand
  logic already duplicated instead of unified).
- **No Steam-availability gate** (FR-F5.7) — panel always renders real data
  once a world/player context exists; if there's genuinely no player (e.g.
  main menu with zero worlds ever played, no active session) — planning
  flags this as needing a "no data yet" empty state, not a Steam-unavailable
  message (different failure mode, matching FR-F5.6's spirit).

## Files to Create
- `platform/fabric-<version>/src/main/java/de/lazuli/mainmenu/StatisticsPanel.java` (×3, Item F5).
- If Branch A (Item F1): `api/.../mainmenu/AchievementSummary.java`,
  `services/.../steamworks/SteamAchievementsGateway.java` (+ real/Noop
  impls), `platform/fabric-<version>/.../mainmenu/AchievementsPanel.java`
  (×3) — unchanged from `implementation-plan-batch-2.md`'s own Files to
  Create for Item 3.

## Files to Modify
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` — reorder
  enum, add `STATISTICS` (Items F2/F5, one shared edit).
- `features/main-menu/src/main/java/de/lazuli/features/mainmenu/services/MainMenuStateMachine.java`
  — new constructor overload seeding initial `activeTab` (Item F2).
- `platform/fabric-<version>/.../mainmenu/MainMenuScreen.java` (×3) —
  construct with seeded `MainMenuStateMachine(MainMenuTab.HOME)` (F2); wire
  new `statisticsPanel` field/construction/`init`/`render`/`mouseClicked`
  cases (F5); wire `AchievementsPanel` if Branch A (F1); thread
  `FriendServerPresenceReader` into `HomePanel`'s constructor call and pass
  `worldsPanel`/`serversPanel` recent-data accessors into `HomePanel` (F4).
- `platform/fabric-<version>/.../friends/FabricFriendsSidebarInjector.java`
  (×3) — add `MainMenuScreen.class` to `DENY_LISTED_SCREENS` (F3).
- `platform/fabric-<version>/.../mainmenu/HomePanel.java` (×3) — greeting
  constant/render, Recent-section render/click dispatch, new constructor
  parameters (recent-worlds/recent-servers suppliers,
  `FriendServerPresenceReader`) (F4).
- `platform/fabric-<version>/.../mainmenu/WorldsPanel.java` (×3) —
  `playWorld` visibility `private` → package-private; add a small
  `recentEntries(): List<WorldListWidget.WorldEntry>` accessor (F4).
- `platform/fabric-<version>/.../mainmenu/ServersPanel.java` (×3) —
  `connect`/`renderFriendAvatars` visibility `private` → package-private;
  add a small `recentServers(): List<ServerInfo>` accessor (F4).

## Interfaces
- `api/.../mainmenu/MainMenuTab` — reordered, gains `STATISTICS`.
- `features/mainmenu/services/MainMenuStateMachine` — new constructor
  overload `MainMenuStateMachine(MainMenuTab initialTab)`.
- `api/.../mainmenu/AchievementSummary` — new record (Item F1, Branch A
  only), unchanged shape from batch-2.
- `services/.../SteamAchievementsGateway` — new interface (Item F1, Branch A
  only), unchanged shape from batch-2.

## Services
- New (Item F1, Branch A only): `SteamAchievementsGateway`/
  `SteamworksSteamAchievementsGateway`/`NoopSteamAchievementsGateway` — same
  as batch-2's own plan.
- No new service for F2/F3/F4/F5 — F5 reads vanilla client state directly in
  the Version Adapter layer (Architecture, no `services` module involvement,
  per the spec's own Architecture section).

## Test Strategy
Per this repo's standing convention (manual, in-game, per-platform-module
verification for rendering/layout; unit tests for plain-JVM-testable logic):

1. **Unit tests (plain JVM)**:
   - `MainMenuStateMachineTest` (existing or new) — assert
     `new MainMenuStateMachine(MainMenuTab.HOME).activeTab() ==
     MainMenuTab.HOME`, and that `selectTab(MainMenuTab.HOME)` afterward
     deselects it (existing toggle behavior unaffected by the new
     constructor).
   - If F4's recent-list merge logic is extracted into a small, pure,
     plain-JVM-testable helper (e.g. a static merge/sort method taking two
     already-typed lists + timestamps) — recommended, since it's pure data
     logic — unit-test it directly with fake world/server data to confirm
     correct ordering per Decision 3's documented "worlds by real timestamp,
     servers appended in list order" behavior.
   - If F5's stat-value formatting (distance/time human-readable conversion)
     is extracted into small static methods, unit-test the formatting logic
     directly (pure functions, no Minecraft-client dependency once given raw
     stat values).
2. **Compilation** — `gradlew build`/`compileJava` succeeds on all three
   platform modules with every new file/enum value/constructor overload in
   place, minimum bar per the orchestrator's own verification instruction.
3. **Manual in-game verification, per platform module** (all three):
   - F3: opening the main menu shows exactly one sidebar; every
     previously-working screen (`PauseScreen`, in-game HUD, etc.) still
     shows the injector's sidebar unaffected.
   - F2: tab bar order is `Home, Worlds, Servers, Store, Wardrobe,
     Achievements, Statistics`; Home is already open/selected the instant
     the main menu appears with no click needed; clicking Home again closes
     it, matching every other tab's toggle behavior.
   - F4: greeting text varies across repeated menu re-opens; Recent section
     shows real recently-played worlds (correct relative time) followed by
     saved servers (per Decision 3's documented ordering), each clickable to
     actually load the world/connect to the server; friend avatars/badge
     appear on server cards with friends currently present; empty state with
     zero worlds/servers.
   - F1: Achievements tab shows real data (Branch A) including
     progress-bar rows if available, or an accurate placeholder (Branch B).
   - F5: Statistics tab's General/Items/Mobs sub-tabs show correct values
     matching vanilla's own in-game stats/achievements screen for the same
     world/player; Items/Mobs tables sort correctly per column click; values
     match after playing (mining a block, killing a mob, etc.) and
     reopening the menu.

## Dependencies
No new external Maven/Gradle dependency for F2/F3/F4/F5 —
`net.minecraft.stats.*` (F5) is part of the base game, already on every
platform module's classpath. F1 (Branch A) uses the already-resolved
steamworks4j fork jar, unchanged from batch-2's own Dependencies section;
F1 (Branch B) would need a new internal fork tag, also unchanged.

## Risks
1. **F1's branch outcome still unknown** until the `javap` check actually
   runs this time (carried forward, unchanged from batch-2's own Risk 3).
2. **F2's enum reorder** — this plan's own grep found no ordinal dependency
   beyond `MainMenuScreen.TABS`/the `switch` statements (both
   order-independent), but implementation should re-confirm this hasn't
   changed since this planning pass, per this repo's standing discipline for
   editing already-shipped enums.
3. **F4's recent-servers timestamp gap (Decision 3)** — vanilla `ServerInfo`
   has no last-connected timestamp; this plan's chosen fallback (worlds
   ordered by real recency, servers appended in existing list order) is a
   documented, explicit scope reduction from the design mock's implied
   single unified recency sort — flagged for the user's visibility; a more
   precise fix (persisting real per-server last-connected timestamps via
   `server-join-presence`'s connect/disconnect lifecycle) is a reasonable
   follow-up but out of scope for this pass (no new persistence, per spec
   Non-goals).
4. **F4's exposure of `WorldsPanel.playWorld`/`ServersPanel.connect`/
   `renderFriendAvatars` from `private` to package-private** — low risk
   (same package, same file family, no external API surface change) but
   must be verified not to break either panel's own existing call sites.
5. **F5 is a wholly new integration point** (first read of
   `net.minecraft.stats.*` in this repo) — exact per-version accessor names
   (`getStatHandler()` or equivalent, `Stats.CUSTOM`/per-stat constant names)
   must be confirmed per platform module before implementation, mirroring
   this repo's standing pre-implementation-verification discipline for any
   version-divergent Minecraft-native API (same risk class as batch-2's own
   raw-mouse-input risk, but for a different API family).
6. **F4's featured-item carousel is deferred** (Decision 3/spec FR-F4.4) —
   flagged so the user isn't surprised it's absent from this pass's
   deliverable.

## Acceptance Criteria
- **FR-F1.1-1.4** — `javap` check actually run and result documented;
  Branch A: `AchievementsPanel` compiles and shows real data including
  progress bars if available; Branch B: accurate placeholder, rest of Item
  F1 re-scoped as a follow-up pass.
- **FR-F2.1-2.3** — tab bar order matches `home, worlds, servers, store,
  wardrobe, achievements, statistics`; `MainMenuStateMachineTest` passes;
  manual verification confirms Home is pre-selected on menu open.
- **FR-F3.1-3.3** — code review confirms the one-line deny-list addition in
  all three modules; manual verification confirms exactly one sidebar on
  `MainMenuScreen`, no regression elsewhere.
- **FR-F4.1-4.5** — manual verification confirms greeting variation, correct
  merged Recent section per Decision 3's documented ordering, working
  play/connect actions, friend avatars on eligible server cards, and that
  the Activity section remains exactly the already-shipped
  friends-playing-this-game content (no new announcement/history rows
  added).
- **FR-F5.1-5.7** — `StatisticsPanel` compiles on all three platform
  modules; manual verification confirms General/Items/Mobs data correctness
  and sortable-table behavior.
- **Compatibility** — `gradlew build` succeeds on all three platform modules
  with every new/changed file in place.

## Open Questions
- None blocking this plan's own sign-off. F1's branch outcome, F4's
  recent-servers timestamp-gap resolution (Decision 3), F5's exact
  per-version stat-accessor names, and FR-F5.6's empty-row-omission default
  are implementation-phase discoveries/judgment calls this plan explicitly
  defers to (consistent with how batch-2's own plan treated its analogous
  open items), not unresolved design ambiguities needing user input before
  implementation can start.
