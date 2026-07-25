# Main Menu — Batch 2 Fixes Specification (Post-Launch Bug Report)

## Revision note
This spec was originally drafted assuming the "Statistics" tab the user
reported missing was the already-spec'd `MainMenuTab.ACHIEVEMENTS`. That
assumption was **wrong**: the design handoff directory
(`design_handoff_main_menu/`) was stale/incomplete at the time of that draft;
it has since been replaced with the authoritative design
(`design_handoff_main_menu/Main Menu.dc.html` + `support.js`, README.md no
longer present). Re-reading the authoritative file's `tabDefs` and per-tab
`isStats`/`isAchievements`/`isHome` blocks shows **Statistics and Achievements
are two separate, distinct tabs**, and the tab bar has **7** entries, not 6.
This revision replaces the prior draft's Items F1/F2/F4 findings accordingly.

## Overview
This is a **bug-fix + small-scope-amendment spec**, not a redesign, addressing
five items the user reported after playing the shipped "3D-scaffolded main
menu rework" (commit `0d71821`, which already includes the batch-2 work
specified in `features/main-menu/specification-batch-2.md` /
`implementation-plan-batch-2.md`). Applies to all three platform modules:
`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`
(package `de.lazuli`).

**Authoritative tab order and set**, from `design_handoff_main_menu/Main Menu.dc.html`'s
`tabDefs`:
```
home, worlds, servers, store, wardrobe, achievements, stats
```
i.e. **Home first**, then the four already-shipped tabs in their existing
order, then **Achievements**, then a new, distinct **Statistics** tab.

## Findings Summary

1. **"Statistics tab is missing entirely"** → **(b) genuine spec gap, a whole
   new item, not a rename of Achievements.** `Main Menu.dc.html`'s `isStats`
   block (~lines 459-525) specifies a tab titled "Statistics," subtitle
   "Tracked for {Playername} · across all worlds & servers" — this is
   **vanilla Minecraft per-player statistics** (`net.minecraft.stats.Stats`/
   `StatType`/`StatsCounter`), aggregated read-only display, **not** Steam
   achievements and **not** a new Steamworks surface. No `MainMenuTab` value
   for this exists yet at all (only `ACHIEVEMENTS` was ever added). This is
   the largest single item in this fixes pass — see Item F5.

2. **"Home should be the first tab"** → **(b) spec gap**, confirmed by
   `tabDefs`'s authoritative order (`home` is index 0). `MainMenuScreen.TABS
   = MainMenuTab.values()` currently reflects the `MainMenuTab` enum's
   declaration order (`WORLDS, SERVERS, STORE, WARDROBE, HOME, ACHIEVEMENTS`),
   never reordered when `HOME`/`ACHIEVEMENTS` were appended.

3. **"Friends sidebar renders twice (once collapsed, once as a handle)"** →
   **(a) confirmed regression, root-caused — unaffected by the design-file
   correction, stands as originally found.** `MainMenuScreen` constructs and
   renders its own dedicated `FriendSidebarWidget` (FR-BB1.7's stated
   default). Separately, `FabricFriendsSidebarInjector`'s `onScreenInit` — since
   FR-BB1.5 removed the six-type allow-list for an (empty) deny-list — now
   attaches its own shared `activeSidebar` instance to **every** `Screen`
   including `MainMenuScreen` itself (defaulting to `handleOnly = true` since
   it's not `TitleScreen`/`GameMenuScreen`). Both instances end up attached to
   the same screen simultaneously — exactly the reported "collapsed + handle"
   double render. **Fix (unchanged from the original draft): add
   `MainMenuScreen.class` to `FabricFriendsSidebarInjector.DENY_LISTED_SCREENS`**
   in all three platform modules.

4. **"Home tab is very lacking"** — re-derived from `Main Menu.dc.html`'s
   `isHome` block (~lines 117-208), three sub-parts:
   - **Default-selected on open, not merely reordered first.** The design's
     own `state.selectedTab` defaults to `'home'` at mount — i.e. Home isn't
     just tab-bar position 0, `MainMenuScreen` must open with Home **already
     active**, matching `componentDidMount`'s behavior in the reference file.
     This reverses the base spec's own intentional "no tab active by
     default" (FR1.3/FR1.4) for `MainMenuScreen`'s own construction only.
   - **Randomized greeting**, confirmed exact pool from the design file:
     `['Welcome back, {Playername}', 'Ready to dig in?', 'The vale missed
     you', 'Good to see you again', 'Adventure awaits', 'Back for more, are
     we?']` (`{Playername}` substituted with the real local player's
     name) — one random pick per `MainMenuScreen` construction.
   - **"Recent" section** — corrected scope per the design file's
     `recentDefs`: a **merged, interleaved list of both recently-played
     singleplayer worlds and multiplayer servers, ordered by recency
     together** (not two separate lists) — each card: 2×2 thumbnail, name,
     subtitle, last-played relative time, and (if applicable) small avatars of
     friends currently online there. This directly corrects the original
     draft's assumption of a worlds-only "Recently Played" section.
   - **"Activity" section** — the design file's `activityData` feed includes
     dev-team announcements, friend achievement-unlock events, and friend
     screenshot-share events. Per this repo's own already-established finding
     (batch-2 spec's Non-goals: Steamworks does not expose per-friend
     historical activity/library events to games), the achievement-unlock and
     screenshot-share row *kinds* are **not buildable** with real data this
     mod has access to — no confirmed grep hit anywhere in this repo for an
     existing announcements/news source either (checked: no
     announcement/news-feed service exists in `services`/`features`). **This
     spec scopes the Activity section down to what batch-2's own Home/Activity
     item (Item 2, FR-BB2.1-2.5) already ships** — friends currently playing
     this game, a live Rich-Presence snapshot — and does **not** add
     announcement or friend-history rows, consistent with the honesty
     framing the base batch-2 spec already committed to. If real dev
     announcements become available from some other source in the future,
     that is a Future Extension, not scoped here.
   - **Featured-item promo banner carousel** (bottom of Home, rotating every
     4.5s through featured Store items with dot indicators) — included as an
     explicitly lower-priority, deferrable nice-to-have (see Item F4's own
     scope note).

## Goals
- Add a new **Statistics** tab (Item F5): vanilla Minecraft stat display,
  General/Items/Mobs sub-category pills, sourced entirely from
  `net.minecraft.stats.Stats`, no Steamworks involvement.
- Reorder the tab bar to `HOME, WORLDS, SERVERS, STORE, WARDROBE,
  ACHIEVEMENTS, STATISTICS` and make `MainMenuScreen` open with Home already
  selected (Items F2).
- Eliminate the duplicate sidebar render on `MainMenuScreen` (Item F3,
  unchanged from the original draft).
- Enrich the Home tab: randomized greeting, and a merged/interleaved
  worlds+servers "Recently Played" section sorted by recency, reusing
  already-available friend-presence data for the online-friend avatars
  (Item F4).
- Finish batch-2 Item 3's Achievements tab for real (Item F1, unchanged from
  the original draft — still gated on the FR-BB3.1 `javap` check that was
  never actually run).

## Non-goals
- Not building a real dev-announcement/news-feed system, nor a real
  per-friend historical-activity feed (achievement-unlock/screenshot-share
  events) — no such data source exists in this codebase today and Steamworks
  does not expose one to games; Home's Activity section stays scoped to the
  already-shipped "friends currently playing this game" live snapshot.
- Not building the featured-item promo carousel in this pass unless it turns
  out to be trivial reuse of `StorePanel`'s existing featured-item data — see
  Item F4's own scope note; may be deferred to Future Extensions without
  blocking the rest of this fixes pass.
- Not redesigning `FriendSidebarWidget`'s dock/global-render mechanism — only
  the `MainMenuScreen` double-attachment gap needs a deny-list entry.
- Not adding any new persistence/config file — Statistics data is read live
  from vanilla Minecraft's own already-persisted per-world/per-player stat
  files; the greeting string and recent-list merge are computed fresh each
  `MainMenuScreen` construction from already-available in-memory/persisted
  data.
- Not resolving the Achievements tab beyond whichever branch FR-BB3.1 (run
  for real, this pass) determines — unchanged scope boundary from the
  original draft.
- Not changing `MainMenuStateMachine`'s general "no tab active is valid, and
  clicking the active tab deselects it" semantics as a rule — only
  `MainMenuScreen`'s own construction-time initial value changes (seeded to
  `HOME`); once the player interacts with the tab bar, Home behaves exactly
  like every other tab (including toggling closed on a second click).

## Requirements

### Item F1 — Finish the Achievements tab (batch-2 FR-BB3.1-3.6, for real)
*(Unchanged from the original draft — this item was never affected by the
design-file mixup.)*
- **FR-F1.1** Implementation's mandatory first step: locate the resolved
  `steamworks4j` jar (`gradle.properties`'s `steamworks4j_version`) and run
  `javap -p` (or direct source enumeration) to confirm whether
  `SteamUserStats`/`SteamUserStatsNative` exist, and which of
  `GetNumAchievements`, `GetAchievementName`, `GetAchievementAndUnlockTime`,
  `GetAchievementDisplayAttribute`, `GetAchievementIcon`,
  `RequestCurrentStats` are present — this check must actually run this time,
  result recorded before any UI code is written.
- **FR-F1.2 (Branch A — already wrapped).** Build
  `SteamAchievementsGateway`/`SteamworksSteamAchievementsGateway`/
  `NoopSteamAchievementsGateway` per batch-2's FR-BB3.2a/Public API item 3,
  then replace `MainMenuScreen`'s placeholder `ACHIEVEMENTS` case with a real
  `AchievementsPanel` (batch-2's FR-BB3.4/3.5/3.6) in all three platform
  modules. Per the design file's `isAchievements` block: filter pills
  All/Unlocked/Locked; each row shows icon/name/description plus **either** a
  lock icon (not yet unlocked), **or** a progress bar for tracked stat-based
  achievements (e.g. "Master Builder: 3820/5000" — `GetAchievementProgressLimits`-shaped
  data, previously only flagged as a Future Extension in batch-2's own
  FR-BB3 discussion; planning must confirm whether this is in the confirmed
  binding set from FR-F1.1's check, or whether it's a v1 scope reduction to
  locked/unlocked-only if progress data isn't readily available), **or** a
  checkmark (unlocked).
- **FR-F1.3 (Branch B — not wrapped).** Stop at that finding; Achievements
  tab keeps an accurate placeholder until a separate fork-binding prerequisite
  pass lands.
- **FR-F1.4** Placeholder wording (if Branch B, or transiently before
  FR-F1.2 lands) is an implementation-time judgment call.

### Item F2 — Home tab: reorder first AND default-selected on open
- **FR-F2.1** `MainMenuTab`'s tab-bar iteration order becomes `HOME, WORLDS,
  SERVERS, STORE, WARDROBE, ACHIEVEMENTS, STATISTICS`, matching
  `design_handoff_main_menu/Main Menu.dc.html`'s `tabDefs` order exactly.
  Concretely: either reorder the `MainMenuTab` enum's own declaration (`HOME`
  first, `STATISTICS` last) — simplest, since `MainMenuScreen.TABS =
  MainMenuTab.values()` already derives directly from declaration order — or
  an explicit `TABS` array literal if planning finds an ordinal-order
  dependency elsewhere. Planning must grep for every `MainMenuTab.values()`/
  ordinal use site before choosing.
- **FR-F2.2** `MainMenuScreen`'s `MainMenuStateMachine` is seeded with `HOME`
  as the initial active tab at construction (a constructor overload or a
  post-construction `state.selectTab(MainMenuTab.HOME)` call) — matching the
  design file's `state.selectedTab` defaulting to `'home'` at mount. Clicking
  the already-active Home tab still deselects it (FR2.2's existing toggle
  behavior, unchanged) once the tab bar is interacted with.
- **FR-F2.3** Applied identically across all three platform modules (a
  shared `MainMenuTab` enum change covers the ordering in one edit; the
  seeded-initial-tab change is per-platform `MainMenuScreen.java`).

### Item F3 — Eliminate the duplicate sidebar render on `MainMenuScreen`
*(Unchanged from the original draft.)*
- **FR-F3.1** `FabricFriendsSidebarInjector.DENY_LISTED_SCREENS` (currently
  `Set.of()` in all three platform modules) gains one entry:
  `MainMenuScreen.class`.
- **FR-F3.2** Applied identically in all three platform modules'
  `FabricFriendsSidebarInjector.java`.
- **FR-F3.3** Manual verification: opening the main menu shows exactly one
  sidebar; every other previously-working screen's injector-driven sidebar is
  unaffected.

### Item F4 — Home tab enrichment (greeting, merged recent section, activity scope)
- **FR-F4.1 (randomized greeting).** `HomePanel`/`MainMenuScreen` renders a
  greeting string above its content, one random pick per construction from
  exactly this pool (confirmed from the design file, `{Playername}`
  substituted with the real local player's Minecraft/Steam display name):
  `"Welcome back, {Playername}"`, `"Ready to dig in?"`, `"The vale missed
  you"`, `"Good to see you again"`, `"Adventure awaits"`, `"Back for more, are
  we?"`. No persistence, no network/Steam gating (purely local/cosmetic,
  unlike the friends-list section which gates on `isSteamAvailable()`).
- **FR-F4.2 (merged recently-played section — corrected scope).** `HomePanel`
  gains a "Recent" section listing a **single, merged, interleaved list of
  both recently-played singleplayer worlds and multiplayer (saved) servers**,
  sorted descending by a common recency timestamp — **not** two separate
  lists. Each entry: 2×2 thumbnail (reusing whatever thumbnail/icon mechanism
  `WorldsPanel`/`ServersPanel` already use per-row), name, subtitle, relative
  "X ago" last-played/last-connected time (reuse `WorldsPanel`'s existing
  relative-time formatting, post-launch-fixes-3 FR-B3.2, not a second
  implementation), and — if applicable — small avatars of friends currently
  online on that specific server (reusing batch-2 Item 4's
  `FriendServerPresenceReader`/`friendSteamIdsOnServer` data, already
  consumed by `ServersPanel`; worlds naturally have no "friends online"
  concept and omit this). Clicking a world entry performs the same
  play-action `WorldsPanel`'s own row click already does; clicking a server
  entry performs the same connect-action `ServersPanel`'s own saved-row click
  already does — reusing existing plumbing (a small, planning-scoped
  refactor to expose these actions to `HomePanel`, not a new load/connect
  code path). Bounded to a small fixed count (e.g. up to 5-6 entries,
  implementation's exact number) with a scrollable/overflow behavior matching
  this feature's existing list-panel convention if more exist. Empty state
  ("No recently played worlds or servers yet") when both sources are empty.
- **FR-F4.3 (Activity section scope, corrected/reduced).** Home's existing
  "friends currently playing this game" section (batch-2 FR-BB2.1-2.5, already
  shipped as `HomePanel`'s current content) **is** this pass's entire Activity
  section — no announcement rows, no friend achievement-unlock rows, no
  friend screenshot-share rows are added, since no real data source exists
  for any of those in this codebase and Steamworks doesn't expose per-friend
  historical activity to games (consistent with batch-2's own Non-goals).
  This is a deliberate, explicit scope reduction from the design file's own
  `activityData` mock content, not an oversight — flagged here for the user's
  visibility.
- **FR-F4.4 (featured-item carousel — deferrable).** The bottom-of-Home
  rotating featured-item promo banner (4.5s rotation, dot indicators) is
  included as **optional/deferrable** for this pass: if `StorePanel`'s
  existing featured-item data/carousel-adjacent code (batch-2/base spec FR5,
  "featured banner" per post-launch-fixes-3 FR-B3.7) can be reused with
  materially small effort, planning may include it; if it requires
  non-trivial new animation/timing infrastructure, it is deferred to Future
  Extensions without blocking sign-off on the rest of Item F4.
- **FR-F4.5** Applied identically across all three platform modules'
  `HomePanel.java`/`MainMenuScreen.java`.

### Item F5 — New Statistics tab (vanilla Minecraft stats, not Steamworks)
**Confirmed scope, from `design_handoff_main_menu/Main Menu.dc.html`'s
`isStats` block (~lines 459-525):** title "Statistics," subtitle "Tracked for
{Playername} · across all worlds & servers." Three sub-category filter pills
(`statCategoryDefs`): **General**, **Items**, **Mobs**.

- **FR-F5.1** New `MainMenuTab.STATISTICS` enum value (naming: `STATISTICS`,
  matching the existing `ACHIEVEMENTS`/`WARDROBE` full-word convention in this
  enum rather than an abbreviated `STATS`), added to the tab bar per Item
  F2's ordering (last position).
- **FR-F5.2 (data source — confirmed, no Steamworks work).** All data is
  read from vanilla Minecraft's own built-in stat-tracking system
  (`net.minecraft.stats.Stats`/`StatType`/`StatsCounter`, accessed via
  `MinecraftClient.getInstance().player.getStatHandler()` or the
  equivalent per-version accessor) — **read-only**, already-persisted by
  vanilla Minecraft itself (per-world `stats/<uuid>.json`), no new
  persistence, no new Steam call. Planning must confirm the exact
  per-version accessor name/shape (`StatsCounter`/`ClientStatsCounter` or
  equivalent) independently per platform module, consistent with this
  repo's standing pre-implementation-verification discipline for
  version-divergent APIs.
- **FR-F5.3 (General sub-tab).** Simple label/value rows, each with a small
  color-tile icon (matching this panel's existing simple-icon convention, no
  new icon-loading mechanism needed for this sub-tab): Play Time, Distance
  Walked, Distance Sprinted, Distance Flown, Jumps, Damage Dealt, Damage
  Taken, Deaths, Mob Kills, Player Kills, Times Slept, Raids Won — each
  mapped to its corresponding vanilla `Stats.CUSTOM`/`Stats.*` key (planning
  must enumerate the exact vanilla stat identifiers per entry, e.g.
  `Stats.PLAY_TIME`, `Stats.WALK_ONE_CM`, `Stats.SPRINT_ONE_CM`,
  `Stats.AVIATE_ONE_CM`, `Stats.JUMP`, `Stats.DAMAGE_DEALT`,
  `Stats.DAMAGE_TAKEN`, `Stats.DEATHS`, `Stats.MOB_KILLS`,
  `Stats.PLAYER_KILLS`, `Stats.SLEEP_IN_BED`... `Stats.RAID_WIN` or
  equivalent per-version identifier — exact constant names confirmed at
  implementation time per platform module, since Yarn vs. Mojang mappings
  may differ). Distance/time values formatted human-readably (e.g. km/blocks,
  h/m), not raw tick/centimeter counts.
- **FR-F5.4 (Items sub-tab).** A sortable table, columns: icon, Mined,
  Broken, Crafted, Used, Picked Up, Dropped — one row per item type the
  player has any nonzero stat for (vanilla's per-item `Stats.MINED`/
  `Stats.BROKEN`/`Stats.CRAFTED`/`Stats.USED`/`Stats.PICKED_UP`/
  `Stats.DROPPED` custom stat family, item-keyed). Sorting behavior (by
  which column, ascending/descending, click-to-sort UI) mirrors this panel's
  existing sortable-column convention already established for
  `ServersPanel`'s Browser sub-view (post-launch-fixes-3 FR-B3.6's own
  column-header/sort precedent) rather than inventing a new one.
- **FR-F5.5 (Mobs sub-tab).** A sortable table, columns: mob icon+name,
  Killed By, Killed — one row per mob type the player has any nonzero
  interaction stat for (vanilla's `Stats.KILLED`/`Stats.KILLED_BY`
  entity-keyed stat family). Same sortable-column reuse as FR-F5.4.
- **FR-F5.6** Empty/zero-value rows: implementation's judgment call whether
  to omit item/mob rows with all-zero values (matching a "don't clutter with
  irrelevant rows" convention) or show every possible item/mob type — flagged
  as a planning-phase decision, not fixed here, since the design file's own
  mock content doesn't resolve this ambiguity one way or the other.
- **FR-F5.7** No Steam-availability gating for this tab (unlike Achievements/
  Home's friends section) — vanilla stats are available whenever a world/
  player profile exists, independent of Steam.

## Public API
- `api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java` gains
  `STATISTICS` (Item F5), in addition to the already-existing `HOME`/
  `ACHIEVEMENTS` — enum member order also changes per FR-F2.1.
- No other new `api` types for Items F2/F3/F4 — internal to
  `platform/fabric-<version>/.../mainmenu/` and `.../friends/` classes.
- Item F1 (if Branch A): exactly batch-2's already-specified Public API item
  3 (`AchievementSummary`, `SteamAchievementsGateway`/
  `NoopSteamAchievementsGateway`).
- Item F5: no new `api`/`services` cross-feature type — vanilla
  `net.minecraft.stats.*` is read directly inside a new
  `platform/fabric-<version>/.../mainmenu/StatisticsPanel.java` (×3, Version
  Adapter layer only, since it's a pure Minecraft-client API read, not a
  cross-feature `services` concern).

## Architecture
- F2/F3/F4: no new architectural pattern — same Version Adapter layering as
  batch-2.
- F5: reads vanilla Minecraft client state directly (`player.getStatHandler()`
  or equivalent) inside the platform module's own `mainmenu` package — no new
  `services`/`api` module edge, consistent with "Version Adapter reads
  Minecraft-version-specific APIs directly" being this repo's existing
  pattern for version-divergent Minecraft-native (non-Steamworks) reads.
- F1 (Branch A): exactly batch-2's already-described one new `services`
  gateway.

## UI
- F2: tab-bar visual reorder (button positions shift); Home opens active by
  default on menu construction (a visible behavior change, not just a layout
  shift).
- F3: removes an incorrect duplicate visual — pure subtraction.
- F4: greeting text line; a "Recent" section showing merged/interleaved
  world+server cards with 2×2 thumbnails, last-played time, and (servers
  only) online-friend avatars; Activity section stays exactly as already
  shipped (friends-playing-this-game rows only).
- F5: new Statistics tab — General (label/value rows with color-tile icons),
  Items (sortable icon+numeric-column table), Mobs (sortable icon+name+
  numeric-column table), matching the design file's layout intent; any new
  fill/text color literal must carry full `0xFF` alpha per this repo's
  standing caution.
- F1 (Branch A): exactly batch-2's own UI section for Item 3, plus the
  progress-bar row variant noted in FR-F1.2.

## Configuration
No new config file for F2/F3/F4/F5. F4's recent-list merge and greeting are
computed fresh each `MainMenuScreen` construction from already-available
in-memory/persisted data (world save metadata, saved-server list, vanilla
stat files) — no new file. F5 reads vanilla's own already-persisted
per-world `stats/<uuid>.json`, never writes to it.

## Events
No new event source for F2/F3/F4/F5 beyond what batch-2/base spec already
established. F5 is a pure read of already-ticking/already-loaded client
state, no new event hook.

## Networking
No new networking for any item in this pass — F5 is entirely local
(vanilla Minecraft client state), F4's recent-section friend-avatar reuse is
exactly batch-2 Item 4's already-specified local Steamworks IPC (no new call).

## Persistence
No new persistence for any item. F5 reads, never writes, vanilla's own
existing per-world stats file.

## Compatibility
- All five items land identically across `platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2`.
- F5's exact vanilla `Stats`/`StatType`/stat-handler accessor names must be
  independently confirmed per platform module (Yarn 1.21.11 vs. Mojang-mapped
  26.x divergence expected, same class of risk as every other
  Minecraft-native API this repo already flags) — this spec does not assume a
  specific method/constant name.
- F2's reorder, if via `MainMenuTab` enum-declaration order, must be
  double-checked against any other `MainMenuTab.values()`/ordinal-dependent
  code first.

## Performance
No new performance concern. F5's stat reads happen once per Statistics-tab
open (or per-session-cached), not per-frame, matching this feature's existing
"state resets each fresh screen open" convention (base spec FR1.3). F4's
recent-list merge is a small, bounded sort over already-in-memory world/server
lists, cheap. F2/F3 have no new per-frame cost (F3 removes one widget's
per-frame draw).

## Test Strategy
1. **Manual verification, all three platform modules**:
   - F1: Achievements tab per its own already-established criteria.
   - F2: Home tab is both first in the tab bar and already open/selected the
     instant the main menu appears, with no click required.
   - F3: exactly one sidebar renders on the main menu.
   - F4: greeting varies across repeated menu opens; Recent section shows a
     single merged, recency-sorted list mixing worlds and servers, with
     friend avatars on server entries that have friends currently connected;
     empty state when no worlds/servers exist.
   - F5: Statistics tab's General/Items/Mobs sub-tabs show real, correct
     values matching what vanilla Minecraft's own stats/achievements screen
     would show for the same world/player; Items/Mobs tables sort correctly
     on column click.
2. **Compilation** — `gradlew build`/`compileJava` succeeds on all three
   platform modules, plus `features/main-menu`'s own module tests.
3. If `MainMenuStateMachine` gains a seeded-initial-tab constructor path
   (FR-F2.2), a plain-JVM unit test asserts `activeTab() ==
   MainMenuTab.HOME` immediately after that construction path, and that the
   existing toggle-to-deselect behavior still applies afterward.
4. If F4's merged recent-list sort logic is extracted into a plain-JVM-
   testable helper (recommended, given it's pure sort/merge logic over two
   already-typed data sources), unit-test it directly with fake world/server
   last-played timestamps to confirm correct interleaved ordering.

## Dependencies
No new external dependency for F2/F3/F4/F5 — F5 uses only already-present
vanilla Minecraft client classes (`net.minecraft.stats.*`), no new Maven
coordinate. F1 unchanged from the original draft.

## Risks
1. F1's branch outcome still unknown until the `javap` check actually runs.
2. F2's reorder must not silently break any other `MainMenuTab.values()`
   consumer.
3. F3 is low-risk (one deny-list entry) but must be verified not to regress
   any other screen's sidebar.
4. F4's merged recent-list requires exposing currently-private play/connect
   actions from `WorldsPanel`/`ServersPanel` to `HomePanel` (or centralizing
   them in `MainMenuScreen`) — a small planning-scoped refactor, not a new
   subsystem.
5. **F5 is the largest net-new scope item in this pass** — vanilla stat
   enumeration (which exact `Stats.*` keys map to each General row, and the
   full Items/Mobs per-entity/per-item stat families) must be verified
   against the actual vanilla registry per platform module before
   implementation; a sortable-table UI pattern must be built (or the existing
   `ServersPanel` Browser-sub-view sortable-header pattern reused) for
   Items/Mobs, which is more structurally involved than any of F1-F4.

## Future Extensions
- F1 Branch B's fork-binding addition, if that's the outcome.
- A real dev-announcement/news-feed source, and/or genuine friend
  achievement-unlock/screenshot-share history, if Steamworks or some other
  data source for either becomes available — Home's Activity section would
  then be revisited to include them (explicitly out of this pass per
  FR-F4.3).
- The featured-item promo carousel (FR-F4.4), if deferred this pass.
- F5's item/mob stat tables could grow sort-persistence (remembering the last
  chosen sort column) or CSV export, if requested later — not scoped here.
