# Main Menu — Batch 3 Fixes Specification (Post-Launch Bug Report)

## Overview
Follow-up bug-fix pass to `specification-batch-2-fixes.md` (implemented,
verified, all three platform modules building). The user played the live
batch-2-fixes build and reported seven issues across the Statistics,
Achievements, Home, and Friends-Sidebar surfaces, plus a cross-panel layout
inconsistency. Applies to all three platform modules: `platform/fabric-1.21.11`,
`platform/fabric-26.1`, `platform/fabric-26.2` (package `de.lazuli`), except
where noted.

This is a **bug-fix pass, not a redesign** — every item below is scoped to
correcting a specific, code-confirmed defect or gap in already-shipped batch-2/
batch-2-fixes work.

## Goals
- Fix the Statistics tab's Items/Mobs column-header overflow and empty-gap
  layout bug (Item BF1).
- Root-cause and fix why the Statistics tab shows no live data
  ("No item statistics yet." even after play) and expand its data source to
  the full vanilla stat registries, not a curated subset, aggregated per the
  current Steam account (Item BF2).
- Add a consistent left-inset to every main-menu panel's content, matching the
  existing top/right/bottom insets (Item BF3).
- Replace Home's "Recent" section's server-ordering compromise (batch-2-fixes
  FR-F4.2's "existing saved-list order" caveat) with a real, persisted
  join-history record covering both servers and Steam friends played with
  (Item BF4).
- Finish the Achievements tab's icon/description/localized-name gap via a
  hardcoded static mapping resource (Item BF5).
- Gate the in-game friends-sidebar HUD overlay so it no longer renders
  unconditionally during ordinary gameplay (Item BF6).
- Style Home's greeting text to match the design mockup (bold, larger) (Item
  BF7).

## Non-goals
- Not redesigning the Statistics tab's overall layout/sub-tab structure
  (General/Items/Mobs categories, sortable-column convention) — only the
  column-position/width bug and the data-source/breadth/scoping gap.
- Not building a full "friends I've played with, ranked by total time" social
  feature — Item BF4 is scoped to a join-history *record* Home's Recent
  section can read, not a new UI surface beyond Recent itself.
- Not tracking remote (non-hosted) multiplayer servers' own stat data — a
  server's stats live in that server's own save, inaccessible to this client;
  Item BF2's "across all worlds" aggregation is singleplayer-worlds-only (see
  Background/Investigation Findings, Item BF2).
- Not adding a settings/toggle screen for the friends-sidebar HUD overlay
  unless Item BF6's investigation finds the simpler gating option
  insufficient.
- Not changing `FabricFriendsSidebarInjector`'s per-`Screen` allow-list
  (`ALLOW_LISTED_SCREENS`, batch-2-fixes FR-F3.1) — Item BF6 only concerns the
  separate `registerGlobalHudOverlay()` no-`Screen` gameplay path.
- Not implementing a dynamic Steamworks-API-driven source for Item BF5's
  localized names/descriptions/icons — the confirmed `javap` binding
  limitation (Background/Investigation Findings, Item BF5) means no such API
  exists in the resolved `steamworks4j` fork jar; BF5 is scoped to a static,
  hardcoded mapping resource instead (see Item BF5).
- Not transcribing the actual real-world Spacewar (appid 480) achievement
  name/description/icon content during this specification — that content
  lookup/transcription is explicitly deferred to the implementation phase
  (Item BF5).

## Background/Investigation Findings

### Item BF1 — Statistics tab column overflow
`StatisticsPanel.java` (all three platform modules, e.g.
`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/StatisticsPanel.java:184-251`)
hardcodes `COL_A_X_OFFSET = 260` and `COL_WIDTH = 60` as **absolute pixel
offsets from the panel's own left edge (`x`)**, independent of the panel's
actual `width`. `drawColValue`/`renderTableHeader` place column *N* at
`x + 260 + N*60`; the Items tab has 6 data columns, so the last column's right
edge sits at `x + 620`. `MainMenuScreen.panelWidth()`
(`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/MainMenuScreen.java:196-201`)
returns `Math.max(MIN_PANEL_WIDTH, width - panelX() - TAB_BAR_WIDTH - RIGHT_MARGIN)`
where `MIN_PANEL_WIDTH = 260` — i.e. the panel can be, and at typical window
widths after the reserved left-third background region and the 108px tab bar
are subtracted, frequently *is*, far narrower than 620-680px. The fixed
`COL_A_X_OFFSET`/`COL_WIDTH` values were evidently sized against a much wider
assumed panel and never made relative to the actual `width` parameter already
passed into `render`/`renderItems`/`renderMobs`/`drawColValue`/
`renderTableHeader`/`mouseClicked`. This produces both reported symptoms at
once: columns starting at a fixed `x+260` regardless of how little content
(item/mob name) occupies `x..x+260` (the "large empty gap"), and the last
column(s) landing at `x+560..x+620`, past `x+width` and therefore past the
panel's own right edge into/under the sidebar (the "overflow past the right
edge, overlapping the sidebar").

### Item BF2 — Vanilla statistics not collected/displayed
Two independent, compounding root causes found, both confirmed by reading
`StatisticsPanel.reload()` (`StatisticsPanel.java:56-115`),
`MainMenuScreen`'s panel wiring, and `MainMenuClientInitializer.java`/the
title-screen-redirect mixin referenced in its own Javadoc:

1. **Data source is already correct in shape, not curated.**
   `reload()` already iterates the *full* live vanilla registries —
   `BuiltInRegistries.ITEM` for every `ItemRow` (`Stats.ITEM_CRAFTED`/
   `ITEM_USED`/`ITEM_BROKEN`/`ITEM_PICKED_UP`/`ITEM_DROPPED`/`BLOCK_MINED`) and
   `BuiltInRegistries.ENTITY_TYPE` for every `MobRow`
   (`Stats.ENTITY_KILLED_BY`/`ENTITY_KILLED`) — not a curated hardcoded list.
   The General tab's 12 rows *are* a curated subset (batch-2-fixes FR-F5.3,
   by original design) of `Stats.CUSTOM`, but the user's screenshot
   ("No item statistics yet.") is about the Items tab, which is not curated.
   So this part of the complaint is **not** a curation bug.
2. **The actual bug: `StatisticsPanel` can only ever be viewed with
   `Minecraft.getInstance().player == null`, so it is always empty.**
   `reload()` calls `Minecraft.getInstance().player.getStats()`, guarded by a
   null check that falls back to three empty lists
   (`StatisticsPanel.java:57-65`) whenever `player == null`. `StatisticsPanel`
   is only ever rendered inside `MainMenuScreen`
   (`MainMenuScreen.java:239`/`358-361`), and `MainMenuScreen` — per
   `MainMenuClientInitializer`'s own Javadoc
   (`platform/fabric-26.1/src/main/java/de/lazuli/MainMenuClientInitializer.java:24-46`,
   96) — is constructed and shown **only** at `CLIENT_STARTED` (before any
   world is joined) and via `GuiTitleScreenRedirectMixin`'s disconnect/
   world-exit redirect (i.e. *after* leaving a world). It is never shown while
   a world is actually loaded (there is no in-game pause-menu path to it —
   confirmed by `FabricFriendsSidebarInjector`'s own `PauseScreen`
   allow-list entry, which is a *different*, still-vanilla pause screen, not
   `MainMenuScreen`). Consequently `Minecraft.getInstance().player` is **always
   null** at every point `StatisticsPanel.render()` can possibly run, so
   `reload()` **always** takes the null-fallback branch and every sub-tab is
   permanently empty, regardless of how much the user has actually played —
   this fully explains the reported "No item statistics yet." even after
   significant play.
3. **The subtitle's "across all worlds & servers" promise is also unmet even
   if the null-player bug is fixed**, since a live `StatsCounter` off
   `player.getStats()` reflects at most one single already-loaded world's
   session, never an aggregate.

**Fix direction (confirmed feasible, no new dependency):** stop reading the
live in-memory `player.getStats()` entirely. Read persisted stat data
directly from disk instead, the same way vanilla's own singleplayer
save-selection UI and this repo's own `WorldsPanel`
(`platform/fabric-26.1/.../mainmenu/WorldsPanel.java`) already enumerate
local saves via `LevelStorageSource`, for every local singleplayer save
directory, parsing that save's own persisted `stats/<player-uuid>.json` file
(vanilla's own already-existing, already-correct per-world stat-persistence
format — the same file vanilla's in-game F3/Statistics screen and
`Stats`/`StatsCounter`'s own JSON (de)serialization already read/write) and
summing every stat key across the worlds included.

**Scope of "which worlds get summed" — corrected to Steam-account scoping.**
A blind "every local save directory on this machine" scan is wrong on a
shared computer or shared save library: it would mix another Steam account's
(or another player's) singleplayer progress into this account's aggregate
totals, and it duplicates the account-scoping problem `features/cross-world-
stats` already solved once. This repo already has a working, precedented
answer to "which worlds belong to the currently logged-in Steam account,"
and BF2 must reuse it rather than re-deriving its own per-UUID scope:

- `CrossWorldStatsConfigIO`
  (`features/cross-world-stats/src/main/java/de/lazuli/features/crossworldstats/config/CrossWorldStatsConfigIO.java`)
  persists `config/cross-world-stats.json` as a **map keyed by Steam account**
  — either the logged-in Steam user's account key, or the documented
  `"offline"` sentinel bucket when not logged into Steam
  (`CrossWorldStatsConfigIO.java:9-26`, FR1.2 of that feature's own spec) —
  not by local Mojang/offline-mode player UUID.
- Each account's persisted `AccountStats`
  (`features/cross-world-stats/.../config/AccountStats.java:9-21`) carries a
  `worldBaselines: Map<String, Map<TrackedStat, Long>>` — one entry per world
  identifier already observed *for that account*, populated exclusively via
  `CrossWorldStatsMergeHook.resolveWorldId`
  (`platform/fabric-26.1/src/main/java/de/lazuli/crossworldstats/CrossWorldStatsMergeHook.java:81-96`),
  which returns `"local:" + <save-folder-name>` for singleplayer worlds (and
  `"remote:" + serverIp` for remote servers, not applicable to BF2's
  singleplayer-only scope). The set of `"local:"`-prefixed keys in the current
  account's `worldBaselines` **is exactly the account-scoped list of local
  save folders this account has actually played**, already built and
  maintained by cross-world-stats with no new tracking logic required.
- **BF2's corrected design:** `StatisticsPanel`'s multi-world disk scan reads
  the current account's `AccountStats.worldBaselines` keys (via
  `CrossWorldStatsFacade`, the same read-only seam
  `CrossWorldStatsBridgeHandoff` already publishes,
  `api/src/main/java/de/lazuli/api/crossworldstats/CrossWorldStatsFacade.java`)
  to determine *which* local save-folder names are in scope, strips the
  `"local:"` prefix to recover each save's folder name, resolves that folder
  through the same `LevelStorageSource` enumeration `WorldsPanel` already
  uses, and only then parses that save's `stats/<player-uuid>.json` file for
  the full item/mob breakdown. Saves never observed under the current account
  (e.g. another Windows user's or another Steam account's singleplayer world
  sharing the same `saves/` directory) are excluded, exactly matching
  cross-world-stats' own already-shipped, already-correct account-scoping
  behavior — not re-derived independently. When Steam is not logged in, BF2
  falls back to the same `"offline"` sentinel account cross-world-stats
  itself falls back to (consistent scoping, not a special case).
- This is a **read-only, additive dependency** on `CrossWorldStatsFacade`'s
  existing `currentTotals()`-shaped seam (extended, per Public API below, with
  one additional read method for the current account's world-identifier set)
  — it does not change `cross-world-stats`' own write-side behavior,
  `AccountStats` schema, or `CrossWorldStatsConfigIO`'s persistence format.
  `cross-world-stats`' own curated 6-value `TrackedStat` enum
  (`api/src/main/java/de/lazuli/api/crossworldstats/TrackedStat.java`) is
  still **not** sufficient alone to drive Items/Mobs' full-registry
  requirement (unchanged from the prior finding) — only the account-to-
  worlds *scoping* is reused, not the stat totals themselves; the actual
  per-item/per-mob values still come from BF2's own `stats/<uuid>.json`
  disk parse, same as before.

Remote (non-hosted) multiplayer servers' stats remain **not** locally
readable at all (they live on that server's own save, never transmitted to
the client) — the "& servers" half of the subtitle is not achievable and must
be corrected to "across all worlds" only (a wording fix, not a scope
expansion).

### Item BF3 — Panel body left-padding inconsistency
No shared panel base class or common "content area" helper exists —
`HomePanel`, `WorldsPanel`, `ServersPanel`, `StorePanel`, `WardrobePanel`,
`AchievementsPanel`, `StatisticsPanel` are each an independent `final class`
with its own `render(GuiGraphicsExtractor, Font, int x, int y, int width, int
height, int mouseX, int mouseY)` method, called directly by
`MainMenuScreen.extractRenderState` with the same `x = panelX()`, `y =
panelY()`, `w = panelWidth()`, `h = panelHeight()` for every tab
(`MainMenuScreen.java:225-240`). `MainMenuScreen` itself already applies a
uniform outer margin via `panelX()`/`panelY()`/`RIGHT_MARGIN` before any panel
ever draws, and each panel already right-aligns value/status text with a
small explicit inset (e.g. `StatisticsPanel`'s General rows:
`x + width - valueWidth - 4`, `StatisticsPanel.java:179`;
`AchievementsPanel`'s status column: `x + width - statusWidth - 8`,
`AchievementsPanel.java:88`) and bounds bottom content against `y + height`
(e.g. `StatisticsPanel.java:173`, `AchievementsPanel.java:79`) — but every
panel's **left-anchored** content (labels, row backgrounds/fills, icons,
pill bars) starts flush at exactly `x`, with no equivalent left inset
anywhere (confirmed by grepping every panel's `render`/`mouseClicked` for
`x + `/`x,` literal starts — none add a left-padding constant before the
first draw call). Because no shared base exists, this must be fixed
per-panel, but with the same padding constant reused everywhere for visual
consistency.

### Item BF4 — Home "Recent" real join-history data source
Files read as directed:
- **`SteamJoinRequestDispatcher`**
  (`platform/fabric-26.1/src/main/java/de/lazuli/SteamJoinRequestDispatcher.java`):
  a composition-root-only dispatcher resolving the fact that
  `SteamFriendsGateway#setJoinRequestedListener` accepts only one listener,
  while `steam-world-hosting` and `server-join-presence` each need to react to
  the native overlay's "Join Game" callback for their own connect-string
  format. Each feature registers a `Route` (`tryHandle(friendSteamId64,
  connect)`); first match wins. This is the point where an *inbound* "friend
  invited/joined-with me" event is already observed — a natural hook for
  recording "played with friend X," but currently used only for routing, not
  recording.
- **`CrossWorldStatsBridgeHandoff`**
  (`platform/fabric-26.1/src/main/java/de/lazuli/CrossWorldStatsBridgeHandoff.java`):
  narrow publish/require handoff for `CrossWorldStatsFacade`
  (`currentTotals()` only, `api/src/main/java/de/lazuli/api/crossworldstats/CrossWorldStatsFacade.java`)
  — not itself a join-history source, but its sibling feature,
  `features/cross-world-stats`, establishes this repo's **persistence
  precedent** to reuse: a hand-rolled JSON config file under
  `FabricLoader.getInstance().getConfigDir()`
  (`config/cross-world-stats.json`, format documented in
  `features/cross-world-stats/.../config/CrossWorldStatsConfigIO.java:13-29`),
  loaded/saved via a dedicated `*ConfigIO` class with the repo-standard
  fail-closed-to-defaults-with-a-logged-warning convention (same shape as
  `ServerJoinPresenceConfigIO`, `SteamWorldHostingConfigIO`, this feature's own
  `WardrobeConfigIO`/`StoreCatalogConfigIO` already used by
  `MainMenuClientInitializer.java:71-82`).
- **`ServerJoinPresenceBridgeHandoff`**
  (`platform/fabric-26.1/src/main/java/de/lazuli/ServerJoinPresenceBridgeHandoff.java`):
  publishes `ServerJoinRequester` and `FriendServerPresenceReader` from
  `server-join-presence`. Its sibling `ServerSessionLifecycle`
  (`features/server-join-presence/.../services/ServerSessionLifecycle.java`)
  is the class that already observes real join/leave transitions —
  `onJoinedRemoteServer(host, port)` (sets Rich Presence `"connect"`) and
  `onLeftServer()` (clears it) — called by that feature's own composition
  root at the exact moments a real remote-server join/leave happens. This is
  the natural hook for recording a server join-history entry with a real
  timestamp (currently it only drives Rich Presence, not persistence).

**Batch-2-fixes' documented compromise** (FR-F4.2, `HomePanel.recentEntries()`,
`platform/fabric-26.1/.../mainmenu/HomePanel.java:106-109`): merges
`worldsPanel.recentEntries()` (real `LevelSummary.getLastPlayed()` timestamps,
correctly sorted) with `serversPanel.recentServers()` (saved-server list
order, **not** a real last-joined timestamp) — servers are appended after
worlds rather than interleaved by true recency, and there is no tracking of
recently-played-with Steam friends at all.

**Design for this pass:** a new, small, `features/main-menu`-owned
join-history record (own JSON config file, e.g.
`config/main-menu-join-history.json`, same `*ConfigIO`/fail-closed-with-warning
convention as `CrossWorldStatsConfigIO`) recording two append-only, capped
lists:
1. **Server joins**: `{ "ip": "...", "name": "...", "lastJoinedEpochMillis":
   ... }`, one entry per distinct saved-server IP, updated (not appended) on
   each real join. Recorded from the same real join transition
   `ServerSessionLifecycle.onJoinedRemoteServer` already observes — this
   pass's composition-root wiring adds a second consumer of that same
   real-join signal (or an equivalent hook exposed alongside it) that writes
   this record, without changing `ServerSessionLifecycle`'s own existing
   Rich-Presence-only contract.
2. **Friends played with**: `{ "steamId64": ..., "lastPlayedTogetherEpochMillis":
   ... }`, one entry per distinct friend, updated whenever a join happens that
   was friend-initiated (a `SteamJoinRequestDispatcher.Route` observing
   `friendSteamId64` — reusable without changing that dispatcher's own
   single-listener/first-match-wins contract, just adding a route, or a
   recording hook alongside whichever route already claims the join) **or**
   — lower-confidence, flagged for planning — cross-referenced against
   friends who were online on a server the local player also joined
   (`FriendServerPresenceReader`, already consumed by `ServersPanel`/
   `HomePanel`'s friend-avatar rendering) at the moment of that join.
3. `HomePanel.recentEntries()` is amended to source real per-server
   last-joined timestamps from this new record (replacing the "saved-list
   order" fallback) so worlds and servers can be correctly interleaved by a
   single real recency timestamp for both, per FR-F4.2's original,
   never-actually-met intent. The friends-played-with list is a second,
   separate small data source Home's Recent section may optionally surface
   (e.g. a friend avatar/name alongside a recently-joined server entry it
   matches, or left as a Future Extension if planning finds no clean UI slot
   for it beyond what `ServersPanel.renderFriendAvatars` already shows) —
   this pass's hard requirement is only the **persisted record itself** and
   the server-timestamp fix; how far the friends-list half surfaces into the
   UI this pass is a planning-time scope call, flagged in Open Questions.
4. Read-side: this new record is small (bounded, capped list sizes, e.g. last
   50 servers / 50 friends, oldest evicted first), loaded once at
   `MainMenuClientInitializer` composition-root time (same pattern as
   `WardrobeConfigIO`/`StoreCatalogConfigIO`), passed into `HomePanel`
   alongside `worldsPanel`/`serversPanel`.

### Item BF5 — Achievements panel gaps
`AchievementsPanel.java`
(`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/AchievementsPanel.java`)
renders only `a.apiName()` (the raw internal Steam API achievement identifier,
unlocalized, as returned by `getAchievementName(int)`) plus a locked/unlocked
status — no icon, no display name, no description.
`services/src/main/java/de/lazuli/services/steamworks/SteamAchievementsGateway.java`
is a narrow, plain-Java-typed seam (`achievements(): List<AchievementSummary>`)
with the real implementation isolated in
`SteamworksSteamAchievementsGateway` (the sole class importing
`com.codedisaster.steamworks.*` for this capability) and a `Noop` fallback,
per this repo's standing "one gateway per interface" convention.

**Root cause (`javap`-confirmed, unambiguous).** A direct `javap` inspection
of the actual resolved jar,
`com.github.Probastian.steamworks4j:v1.10.0-inventory.1`, confirms:
- Only `getNumAchievements()`, `getAchievementName(int)`, and
  `isAchieved(String, boolean)` are bound on `SteamUserStats`/
  `SteamUserStatsNative` for achievement *metadata* (the jar's other bound
  achievement-adjacent methods — `setAchievement`/`clearAchievement`/
  `indicateAchievementProgress`, plus leaderboard/global-stat methods — are
  unrelated to display metadata).
- A case-insensitive search for `displ|icon|localiz` across
  `SteamUserStatsNative`, `SteamApps`, and `SteamUtils` returns **zero
  matches** other than one unrelated `getLeaderboardDisplayType`.
- No `GetAchievementDisplayAttribute`, no `GetAchievementIcon`, and no
  unlock-timestamp accessor exist anywhere in this jar.
- `SteamworksSteamAchievementsGateway`'s existing implementation already
  calls the **entire** available native surface for this capability — there
  is no un-called binding being left on the table.

This is a **hard binding limitation of the resolved `steamworks4j` fork jar
itself**, not an integration gap in this repo's own gateway code, and is the
sole justification for why BF5 uses a static, hardcoded mapping instead of a
dynamic Steamworks API call: there is no native call to source localized
name/description/icon data from at runtime.

**Current Steam app ID context.** This game is currently configured against
Valve's public Steamworks test/dev App ID, `480` ("Spacewar") — confirmed via
`SteamAppIdResolver.DEFAULT_APP_ID = 480L`
(`services/src/main/java/de/lazuli/services/steamworks/SteamAppIdResolver.java:51-54`)
and the generated `steam_appid.txt` files under each platform module's `run/`
directory (e.g. `platform/fabric-26.1/run/steam_appid.txt`). Spacewar's
achievement set is a long-standing public Steamworks SDK sample app whose
achievement metadata (names, descriptions, icon images) is documented in
publicly available Steamworks SDK sample/documentation material. Sourcing and
transcribing that real content is explicitly deferred to the implementation
phase (see Requirements, FR-BF5.2) — this specification defines only the data
structure and lookup requirement, not the mapping's actual content.

**Fix direction:** a static, hardcoded mapping resource, keyed by the raw
achievement API name (`getAchievementName(int)`'s return value, the same
string `AchievementSummary.apiName()` already carries) to
`{ displayName, description }`, plus a bundled icon image asset per mapped
achievement, following this repo's existing icon/texture resource
convention — static image assets for this feature live under each platform
module's own `src/main/resources/assets/lazuli/textures/...` tree
(precedent: `sync_enabled.png`/`sync_disabled.png`/`cloud_only.png` under
`platform/fabric-26.1/src/main/resources/assets/lazuli/textures/gui/`) — e.g.
a new `textures/achievements/<apiName>.png` (or equivalent) subdirectory per
platform module, populated identically across all three. `AchievementsPanel`
looks up each achievement's raw `apiName()` in this mapping to render a
localized display name, description, and icon in place of the current
raw-name-only row, and falls back gracefully to the current raw-name-only,
no-icon, no-description rendering for any achievement whose raw name is not
found in the mapping — covering both (a) any achievement genuinely absent
from the hand-authored mapping, and (b) future-proofing if the configured App
ID is ever changed away from the Spacewar test ID (`480`) to a real
production App ID, at which point this Spacewar-specific mapping would no
longer apply to any of that production app's achievements and every row
would correctly fall back to raw-name-only rendering rather than showing
incorrect Spacewar metadata.

### Item BF6 — In-game friends-sidebar HUD overlay
`FabricFriendsSidebarInjector.registerGlobalHudOverlay()`
(`platform/fabric-26.1/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java:215-226`,
Javadoc at 200-214, batch-2 FR-BB1.3/1.4) registers a
`HudElementRegistry.addLast` layer that renders `activeSidebar` (via
`renderNow`/`renderDropdownOverlay`) whenever `Minecraft.getInstance().screen
== null` — i.e. **any time no `Screen` is open**, which includes ordinary,
unpaused, in-game gameplay (walking around, mining, fighting — not just a
paused/menu-adjacent moment). The batch-2 design intent, per that same
Javadoc and FR-BB1.3/1.4's own framing ("no-Screen/gameplay path," "Escape/
PauseScreen no-flicker requirement"), was specifically to avoid a visual
flicker/reset when the player presses Escape (`PauseScreen` opens — an
allow-listed `Screen`) and releases it again (`screen` becomes `null` again,
gameplay resumes) — i.e. the no-`Screen` HUD path was meant to keep the
*already-open* sidebar visually continuous only immediately around that
transition, not to make the sidebar a permanent gameplay HUD element. As
implemented, though, the gating condition (`screen == null`) is
indistinguishable between "just closed the pause menu a moment ago" and
"been playing normally for the last twenty minutes" — the sidebar (or its
collapsed handle) renders continuously throughout ordinary gameplay,
confirmed unwanted/distracting per the user's report and inconsistent with
FR-BB1.3/1.4's own stated flicker-avoidance framing, which never called for a
permanent gameplay overlay.

**Fix direction:** gate `registerGlobalHudOverlay()`'s render (and its
`onClientTick` input-forwarding counterpart) to a short-lived "recently had a
`Screen` open" window instead of an unconditional `screen == null` check —
e.g. track the client tick at which `screen` last transitioned from
non-null to null (a `PauseScreen`/allow-listed-screen close), and only render
the no-`Screen` HUD layer for a small fixed number of ticks after that
transition (long enough to cover the pause-menu-close animation/frame gap
FR-BB1.3 was written for, short enough that it visibly disappears well before
the player would call it "still showing during gameplay") — falling back to
fully hidden otherwise. This preserves the original no-flicker intent while
eliminating the permanent-gameplay-overlay regression, with no new
user-facing toggle needed. Exact tick-window constant is an implementation
detail; planning should pick a value on the order of a handful of ticks to a
couple of seconds and document the reasoning.

### Item BF7 — Home greeting text styling
`design_handoff_main_menu/Main Menu.dc.html:118` (`isHome` block): the
greeting renders as `<div style="font-size:26px;font-weight:600;">{{
greeting }}</div>` — i.e. **larger and semi-bold**, versus every other
label in that same block at 11.5-19px/400-600 weight but visually smaller.
`HomePanel.render()`
(`platform/fabric-26.1/src/main/java/de/lazuli/mainmenu/HomePanel.java:117`)
currently renders it as a single plain `guiGraphics.text(font,
Component.literal(greeting), x, y, 0xFFEAE8E1)` call — this repo's standard
Minecraft vanilla `Font`-rendered text call, with no bold styling and no
scale-up, identical in weight/size to every other label in the panel (e.g.
the "Recent"/"Activity" section headers, `HomePanel.java:120`, `147`, both
plain `guiGraphics.text` calls at the default font size, only distinguished
by color `0xFFC9A227`). No existing panel in this codebase currently uses
bold or a scaled-up font size for any header — `StatisticsPanel`'s own
"Statistics" header (`StatisticsPanel.java:146`) is likewise a plain,
default-size, non-bold `guiGraphics.text` call, so there is no established
precedent to reuse; this fix introduces the first bold/scaled text call in
this feature.

## Requirements

### Item BF1 — Statistics tab column layout fix
- **FR-BF1.1** `StatisticsPanel`'s Items/Mobs table column X-positions and
  widths become a function of the panel's actual `width` (already passed into
  every relevant method) instead of the current fixed `COL_A_X_OFFSET = 260`/
  `COL_WIDTH = 60` constants. Concretely: compute a right-aligned column
  block whose total width never exceeds `width`, with the item/mob
  name column occupying the remaining left-hand space (elastic, shrinking
  the empty gap to whatever the longest visible name in the current data set
  actually needs, or a small fixed minimum) — mirroring
  `StatisticsPanel`'s own General-tab row pattern, which already
  right-aligns its value column relative to `width`
  (`x + width - valueWidth - 4`, `StatisticsPanel.java:179`), rather than at
  a fixed offset.
- **FR-BF1.2** At minimum, whatever layout planning designs must guarantee:
  (a) no column's right edge ever exceeds `x + width` (no overflow into the
  sidebar/tab bar), and (b) no large fixed empty gap between the name column
  and the first data column when panel `width` is small — column start
  position must react to the same `width` value the panel already receives,
  not a magic constant sized for a wider assumed layout.
- **FR-BF1.3** `drawColValue`, `renderTableHeader`, and the column
  hit-testing in `mouseClicked` (`StatisticsPanel.java:280-293`, which
  independently re-derives `colX = x + COL_A_X_OFFSET + i * COL_WIDTH`) must
  all derive column X-positions from the same single computation — no
  duplicated/independently-drifting offset math between render and
  hit-test (a maintainability fix riding along with the layout fix, since the
  current duplication is exactly how a future width-dependent change could
  silently desync hit-testing from rendering again).
- **FR-BF1.4** Applied identically across all three platform modules.

### Item BF2 — Statistics data source: full vanilla stats, account-scoped, from disk
- **FR-BF2.1** `StatisticsPanel.reload()` stops reading
  `Minecraft.getInstance().player.getStats()` (always null in this panel's
  only reachable context, see Background/Investigation Findings) and instead
  reads persisted stat data directly from disk, scoped to the local saves
  belonging to the **currently logged-in Steam account** (or the `"offline"`
  sentinel account when not logged into Steam):
  1. Read the current account's `AccountStats.worldBaselines` key set via
     `CrossWorldStatsFacade` (extended per Public API below), filtered to
     `"local:"`-prefixed entries (`CrossWorldStatsMergeHook.resolveWorldId`'s
     existing convention).
  2. For each such save-folder name, resolve it through the same
     `LevelStorageSource` enumeration `WorldsPanel` already uses, and parse
     that save's own `stats/<player-uuid>.json` file (the local player's UUID
     from `Minecraft.getInstance().getUser()`, used only to locate the
     correct file within an already account-scoped save, not as the scoping
     mechanism itself).
  3. Sum every stat key across every save found in step 1-2.
  No live `player`/loaded-world dependency remains, and no local save outside
  the current account's own `worldBaselines` scope is included.
- **FR-BF2.2** The General/Items/Mobs sub-tabs keep their existing shape
  (batch-2-fixes FR-F5.3/5.4/5.5) but now read from this account-scoped
  multi-world disk-aggregate instead of a single live counter — General's
  already-curated 12-row list (FR-F5.3, unchanged scope) and Items/Mobs'
  already-full-registry iteration (FR-F5.4/5.5, unchanged —
  `BuiltInRegistries.ITEM`/`ENTITY_TYPE` iteration is kept, just re-sourced
  from the aggregated per-world sums instead of `stats.getValue(...)` on a
  live counter).
- **FR-BF2.3** The subtitle text ("Tracked for {Playername} · across all
  worlds & servers," `StatisticsPanel.java:147`) is corrected to
  "across all worlds" (drop "& servers") — remote server stats are not
  locally readable (Non-goals), and this wording must not overpromise.
- **FR-BF2.4** Reload timing: since this is now a disk scan across every
  in-scope local save rather than an in-memory read, `reload()`'s existing
  once-per-tab-open/idempotent-until-explicitly-reset caching
  (`loaded` boolean, `StatisticsPanel.java:53`) is kept and remains the only
  refresh trigger (no per-frame re-scan) — consistent with batch-2-fixes'
  own "state resets each fresh screen open" performance framing.
- **FR-BF2.5** Empty state: if the current account's `worldBaselines` set is
  empty (never played a local world under this account yet), or all resolved
  saves are unreadable, General/Items/Mobs all show their existing
  empty-state messaging (`StatisticsPanel.java:211`, `235`, and an equivalent
  for General) rather than erroring.
- **FR-BF2.6** Exact vanilla `stats/<uuid>.json` key format (raw
  `minecraft:custom`/`minecraft:mined`/etc. namespaced-ID strings vs. this
  panel's already-used `Stats.CUSTOM`/`Stats.BLOCK_MINED`/etc. registry
  objects) must be reconciled at implementation time per platform module —
  planning must confirm whether vanilla exposes a direct
  "parse this JSON file into a `StatsCounter`" API (parsing a save's own
  `stats/` file the same way the game itself does on world load) to reuse
  rather than hand-rolling JSON parsing against the raw key strings, since a
  hand-rolled parse would need to keep its own ID-to-`Stat`-object mapping in
  sync with whatever `Stats.CUSTOM`/`Stats.ITEM_*`/`Stats.ENTITY_*`/
  `Stats.BLOCK_*` already provide.
- **FR-BF2.7** `features/main-menu`'s platform composition root gains a
  read-only dependency on `CrossWorldStatsFacade` (via the same
  `CrossWorldStatsBridgeHandoff` publish/require handoff
  `cross-world-stats` already exposes) purely to resolve the current
  account's in-scope local world-identifier set — this does not create a
  compile-time dependency from `features/main-menu` onto
  `features/cross-world-stats`' internal packages, only onto the existing
  `api`-module `CrossWorldStatsFacade` interface, consistent with this
  repo's existing bridge-handoff pattern for cross-feature reads.
- **FR-BF2.8** Applied identically across all three platform modules; exact
  `LevelStorageSource`/stats-file-path/UUID-resolution API names confirmed
  independently per platform module per this repo's standing
  version-divergent-API discipline.

### Item BF3 — Uniform panel left-padding
- **FR-BF3.1** A single shared left-padding constant (planning to pick the
  exact value, e.g. matching whatever right-side inset the panels already use
  most consistently, such as 8px) is applied to every left-anchored content
  draw call's X coordinate across all seven panels (`HomePanel`,
  `WorldsPanel`, `ServersPanel`, `StorePanel`, `WardrobePanel`,
  `AchievementsPanel`, `StatisticsPanel`) — labels, row/pill backgrounds,
  icons, and their corresponding hit-test bounds in each panel's
  `mouseClicked`/`mouseScrolled`, wherever they currently start flush at `x`.
  Right-aligned content (which already subtracts its own inset from
  `x + width`) is unaffected — only left-anchored starts move.
- **FR-BF3.2** No shared base class/helper is introduced in this pass unless
  planning finds it trivial to retrofit without behavior risk (each panel is
  independently structured today, per Background/Investigation Findings) —
  applying the same constant per-panel is an acceptable, lower-risk
  implementation choice; introducing a shared "content area" helper as a
  larger refactor is a Future Extension if planning prefers it, not required
  here.
- **FR-BF3.3** `MainMenuScreen`'s own translucent panel-background fill
  (`guiGraphics.fill(x, y - 12, x + w + 12, y + h + 12, ...)`,
  `MainMenuScreen.java:231`) is unaffected — this item is about each panel's
  own content inset within that already-drawn box, not the box's own bounds.
- **FR-BF3.4** Applied identically across all three platform modules.

### Item BF4 — Persisted join-history record for Home's Recent section
- **FR-BF4.1** New `features/main-menu`-owned persisted JSON file (e.g.
  `config/main-menu-join-history.json`), read/written by a new
  `*ConfigIO`-shaped class following `CrossWorldStatsConfigIO`'s exact
  fail-closed-to-empty-defaults-with-logged-warning convention. Two
  top-level, capped, most-recent-first lists: server-join entries (`ip`,
  `name`, `lastJoinedEpochMillis`) and friend-played-with entries
  (`steamId64`, `lastPlayedTogetherEpochMillis`). Exact cap size (e.g. 50 per
  list) is an implementation-time choice; oldest entries evicted first once
  the cap is reached.
- **FR-BF4.2** Server-join entries are updated (upserted, not naively
  appended — one entry per distinct IP, `lastJoinedEpochMillis` overwritten
  on each new join) at the same real join-transition point
  `features/server-join-presence`'s `ServerSessionLifecycle
  .onJoinedRemoteServer(host, port)` already observes — this pass adds a
  second consumer of that signal (either a new method on
  `ServerSessionLifecycle` itself, or a sibling hook wired alongside it at the
  platform composition root, planning's call) that writes this record,
  without changing that class's existing Rich-Presence-only public contract
  for its existing caller(s).
- **FR-BF4.3** Friend-played-with entries are updated (upserted, keyed by
  `steamId64`) whenever a join is attributed to a specific friend via
  `SteamJoinRequestDispatcher`'s existing `Route` mechanism (a friend-initiated
  "Join Game" from the Steam overlay) — added as either a new route or a
  recording hook alongside whichever route already claims that join, without
  changing `SteamJoinRequestDispatcher`'s existing first-match-wins/
  single-listener contract.
- **FR-BF4.4** `HomePanel.recentEntries()` (batch-2-fixes FR-F4.2) is amended:
  server entries now carry a real `lastJoinedEpochMillis` sourced from this
  new record (falling back to the current saved-list-order behavior only for
  a saved server never found in the join-history record, e.g. one added but
  never yet actually joined) instead of relying solely on saved-list order —
  the merged worlds+servers list is genuinely interleaved by a single real
  recency timestamp for both sources, fulfilling FR-F4.2's original,
  previously-unmet intent.
- **FR-BF4.5** Whether/how far the friend-played-with half of this record
  surfaces directly into Home's Recent section UI this pass (versus being
  persisted-but-not-yet-displayed, ready for a later pass) is a planning-time
  scope decision — flagged in Open Questions/Risks, not fixed here.
- **FR-BF4.6** This record is read once at `MainMenuClientInitializer`
  composition-root time (same pattern as `WardrobeConfigIO`/
  `StoreCatalogConfigIO` loads already in that class) and passed into
  `HomePanel` (and wherever `ServerSessionLifecycle`/
  `SteamJoinRequestDispatcher` routes are wired, for the write side).
- **FR-BF4.7** Applied identically across all three platform modules; the
  `*ConfigIO` class itself may live in `features/main-menu`'s own module
  (not `api`/`services`, since it is not a cross-feature-consumed contract —
  only this feature's own `HomePanel` reads it).

### Item BF5 — Achievements: localized names, icons, descriptions via static mapping
Per the confirmed, `javap`-verified binding limitation (Background/
Investigation Findings, Item BF5), the resolved
`com.github.Probastian.steamworks4j:v1.10.0-inventory.1` jar has **no bound
native call** for localized achievement display name, description, or icon —
this is the sole background justification for the static-mapping approach
below; there is no dynamic/native-API path available.

- **FR-BF5.1** A new static mapping resource is added to the codebase:
  raw achievement API name (the string returned by `getAchievementName(int)`,
  already carried by `AchievementSummary.apiName()`) → `{ displayName,
  description }`, plus a corresponding bundled icon image asset per mapped
  achievement. This mapping is hardcoded/checked into the repo (not fetched
  at runtime, not user-editable) and lives alongside this repo's existing
  icon/texture resource convention — static image assets under each platform
  module's own `src/main/resources/assets/lazuli/textures/...` tree
  (precedent: `platform/fabric-26.1/src/main/resources/assets/lazuli/
  textures/gui/sync_enabled.png` et al.); the name/description text portion
  of the mapping is a plain data resource (e.g. a JSON/properties file under
  the same `assets/lazuli/` tree, or a small Java data class — implementation's
  choice of exact format/location within that established convention).
- **FR-BF5.2 (implementation-phase task, not resolved by this
  specification).** The actual content of the mapping — the real Spacewar
  (Steam App ID `480`, confirmed current per Background/Investigation
  Findings) achievement names, descriptions, and icon image assets — is not
  transcribed or finalized during specification. Finding and transcribing
  this content from publicly available Steamworks SDK sample/documentation
  material (Spacewar is a long-standing public Steamworks test app with
  well-documented achievement metadata) is explicitly deferred to the
  implementation phase. This spec defines only: the mapping's key shape (raw
  API name), its value shape (`displayName`, `description`, icon asset
  reference), and the lookup/fallback behavior (FR-BF5.3) — not the mapping's
  actual entries.
- **FR-BF5.3** `AchievementsPanel` looks up each achievement's raw
  `a.apiName()` in the static mapping (FR-BF5.1) at render time:
  - **Found:** renders the mapped `displayName` and `description` plus the
    mapped icon image, in place of the current raw-name-only row.
  - **Not found:** falls back gracefully to the current, already-shipped
    raw-name-only rendering (no icon, no description) — no error, no
    placeholder/broken-image state. This fallback covers both an
    individual achievement missing from an otherwise-populated mapping, and
    the case where the configured Steam App ID is changed away from the
    Spacewar test ID (`480`) to a real production App ID in the future, at
    which point this Spacewar-specific mapping would no longer match any of
    that production app's achievement API names and every row would
    correctly fall back to raw-name-only rendering.
- **FR-BF5.4** Progress-bar rows for stat-tracked achievements
  (batch-2-fixes FR-F1.2's flagged-but-unresolved
  `GetAchievementProgressLimits`-shaped data) remain out of scope for this
  pass — the confirmed `javap` re-check found no such binding either; not
  reintroduced here.
- **FR-BF5.5** Applied identically across all three platform modules (each
  platform module bundles its own copy of the icon assets, following this
  repo's existing per-module resource-duplication convention already used
  for `icon.png`/`sync_enabled.png`/etc.).

### Item BF6 — Gate the in-game friends-sidebar HUD overlay
- **FR-BF6.1** `FabricFriendsSidebarInjector.registerGlobalHudOverlay()`'s
  render condition changes from unconditional "`Minecraft.getInstance().screen
  == null`" to "`screen == null` **and** within a short fixed window of ticks
  since `screen` last transitioned from non-null to null" — implemented via a
  tracked "last screen-closed tick" field, updated in the existing
  `onClientTick`/a new tick hook, compared against the current tick each
  frame the HUD layer is asked to render.
- **FR-BF6.2** `onClientTick`'s raw-click-forwarding logic
  (`FabricFriendsSidebarInjector.java:265-279`) is gated by the same window,
  not just `screen != null` — a click should not be forwarded to the sidebar
  during ordinary gameplay outside that short window either, consistent with
  the sidebar being fully hidden/non-interactive there.
- **FR-BF6.3** The exact window length (implementation's choice, on the
  order of a handful of ticks to roughly one second) must be long enough to
  cover the existing FR-BB1.3 flicker-avoidance case (Escape-close/reopen
  transition) without a visible gap, and short enough that a player who
  stays in ordinary gameplay for more than a couple of seconds after closing
  a menu sees the overlay fully disappear — verified manually per platform
  module.
- **FR-BF6.4** No new user-facing settings/toggle screen is added — the fix
  is purely the gating-window change; if user testing after this fix still
  finds it objectionable, a toggle is a Future Extension.
- **FR-BF6.5** `FabricFriendsSidebarInjector`'s existing `ALLOW_LISTED_SCREENS`
  Screen-driven path (batch-2-fixes FR-F3.1) is untouched — this item only
  concerns `registerGlobalHudOverlay()`'s own no-`Screen` gating.
- **FR-BF6.6** Applied identically across all three platform modules'
  `FabricFriendsSidebarInjector.java`.

### Item BF7 — Home greeting text: bold, larger
- **FR-BF7.1** `HomePanel`'s greeting line (`HomePanel.java:117`) renders
  bold and visibly larger than the panel's default text size, matching the
  design mockup's `font-size:26px;font-weight:600` intent relative to every
  other label in the same block (`design_handoff_main_menu/Main Menu.dc.html:118`).
  Exact mechanism is implementation's call given vanilla `Font`/
  `GuiGraphicsExtractor` only support discrete integer text scaling (typically
  via a `guiGraphics.pose()` push/scale wrapped around the `text(...)` call,
  since no existing panel in this codebase has a scaled-text precedent to
  reuse per Background/Investigation Findings) plus
  `Component.literal(greeting).withStyle(Style.EMPTY.withBold(true))` for the
  bold weight (standard vanilla `Style` API) — planning confirms the exact
  `GuiGraphicsExtractor`/`Font` scale-wrapping call shape per platform module
  (Mojang-mapped vs. Yarn-mapped naming may differ, same class of risk this
  repo already flags for every other version-divergent rendering call).
- **FR-BF7.2** Only the greeting line changes — the "Recent"/"Activity"
  section headers (`HomePanel.java:120`, `147`) and every other panel's
  header text are unaffected by this item (no scope creep into a broader
  "restyle all headers" pass).
- **FR-BF7.3** Applied identically across all three platform modules.

## Public API
- Item BF5: no change to `api/src/main/java/de/lazuli/api/mainmenu/AchievementSummary.java`
  — the static mapping (FR-BF5.1) is looked up by `AchievementsPanel` at the
  platform layer keyed off `apiName()`, which that type already exposes; no
  new field is needed on `AchievementSummary` itself.
- Item BF2: `api/src/main/java/de/lazuli/api/crossworldstats/CrossWorldStatsFacade.java`
  gains one new read method exposing the current account's set of `"local:"`-
  prefixed world identifiers from `AccountStats.worldBaselines` (exact method
  signature, e.g. `Set<String> localWorldIdsForCurrentAccount()`, is
  planning's call) — an additive, backward-compatible change to that
  interface's existing `currentTotals()`-shaped read-only contract; no
  behavior change to any existing consumer of that interface.
- Item BF4: no new `api`/`services`-module type — the join-history record and
  its `*ConfigIO` are internal to `features/main-menu`, consumed only by that
  feature's own `HomePanel`/platform composition root. `ServerSessionLifecycle`
  (`features/server-join-presence`) and `SteamJoinRequestDispatcher`
  (platform composition-root layer) may each gain one new method/hook for the
  write side, but neither's existing public contract for its existing
  consumer(s) changes.
- Items BF1/BF3/BF6/BF7: no new `api`/`services` type — all internal to
  `platform/fabric-<version>/.../mainmenu/` and `.../friends/` classes.

## Architecture
- BF1/BF3/BF6/BF7: no new architectural pattern — same Version Adapter
  layering as batch-2/batch-2-fixes, pure in-place fixes to existing classes.
- BF2: `StatisticsPanel` moves from a live-`player`-state read to a
  multi-save disk-scan read, still entirely inside the platform module's own
  `mainmenu` package (Version Adapter layer, no new `services`/`api` edge for
  the disk-parsing itself) — consistent with FR-F5.2's original
  "Minecraft-native, non-Steamworks read stays in the Version Adapter"
  framing. The one new cross-feature edge is a read-only dependency on
  `CrossWorldStatsFacade` (via `CrossWorldStatsBridgeHandoff`, the same
  publish/require pattern already used elsewhere in this repo) purely to
  determine which local saves are in scope for the current Steam account —
  `features/main-menu` does not otherwise depend on `features/cross-world-
  stats`.
- BF4: one new small `features/main-menu`-internal persistence class
  (`*ConfigIO` + a plain-data record, same shape as this feature's own
  existing `WardrobeConfigIO`/`StoreCatalogConfigIO`), plus two small
  write-side hooks added at the platform composition-root layer alongside
  `ServerSessionLifecycle`/`SteamJoinRequestDispatcher`'s existing wiring.
- BF5: `SteamAchievementsGateway`/`SteamworksSteamAchievementsGateway`
  (batch-2 Public API item 3) are unchanged, since there is no new native
  call to wire — the static mapping is a platform-layer (`AchievementsPanel`)
  concern only, looked up against the already-exposed `apiName()` field; no
  new architectural seam.

## UI
- BF1: Items/Mobs tables no longer overflow the panel or leave a large empty
  gap; visually the same table shape (icon+name+numeric columns), just
  correctly bounded to the actual panel width.
- BF2: subtitle text changes ("across all worlds," servers dropped); rows now
  populate with real, non-empty data after any play, scoped to the current
  Steam account's own local worlds.
- BF3: every panel's content shifts right by the new shared left-padding
  constant — a small, uniform visual shift, no structural change.
- BF4: no new UI element required by this item alone (Home's Recent section
  keeps its existing card shape, batch-2-fixes FR-F4.2) — only its underlying
  sort-timestamp source changes; if planning chooses to also surface
  friend-played-with data directly (FR-BF4.5), that is an additive,
  planning-scoped UI decision.
- BF5: `AchievementsPanel` rows gain a localized display name, description,
  and icon wherever the static mapping has an entry for that achievement's
  raw API name; rows without a mapping entry are visually unchanged from
  today (raw-name-only, no icon).
- BF6: purely a visibility-timing change — the sidebar/handle no longer
  lingers through ordinary gameplay, no new visual element.
- BF7: greeting text renders bold and larger; no other visual change.

## Configuration
- BF4 is the only item in this pass introducing new persistence: a new
  `config/main-menu-join-history.json` file (or equivalent name, planning's
  call), same directory/lifecycle as this feature's existing
  `main-menu-wardrobe.json`/`main-menu-store-catalog.json`.
- BF2 reads existing vanilla per-world `stats/<uuid>.json` files (already
  persisted by vanilla itself) across every in-scope local save for the
  current Steam account, and reads (does not write) `cross-world-stats`'
  existing `config/cross-world-stats.json` indirectly via
  `CrossWorldStatsFacade` — no new file, but a new *read pattern* (multi-file
  scan, scoped by an existing cross-feature config, vs. single live counter).
- BF5's mapping resource is a static, build-time-bundled repo asset, not
  runtime configuration — no user-facing config.
- No new configuration for BF1/BF3/BF6/BF7.

## Events
No new event source for BF1/BF2/BF3/BF5/BF6/BF7. BF4 taps two already-firing
signals (`ServerSessionLifecycle`'s real join/leave transitions,
`SteamJoinRequestDispatcher`'s already-dispatched friend-join routing) as new
*consumers*, not new event sources.

## Networking
No new networking for any item — BF5's static mapping (FR-BF5.1) is bundled
at build time, not fetched at runtime; no network call.

## Persistence
- BF4: new `config/main-menu-join-history.json`, read/written per Item BF4's
  design above.
- BF2: read-only access to vanilla's own existing per-world stat files,
  across every local save in scope for the current Steam account (previously:
  read-only access to one live in-memory counter, functionally equivalent
  read-only guarantee, just wider and now account-scoped rather than
  unscoped or per-local-UUID); also read-only access to `cross-world-stats`'
  existing `config/cross-world-stats.json` via `CrossWorldStatsFacade`.
- BF5: the static mapping resource is bundled, read-only, build-time content
  — not written or updated at runtime.
- No new persistence for BF1/BF3/BF6/BF7.

## Compatibility
- All seven items land identically across `platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2`.
- BF2's exact save-directory-enumeration and stats-file-parsing API names
  must be independently confirmed per platform module (Yarn 1.21.11 vs.
  Mojang-mapped 26.x divergence, same standing risk class as every other
  version-divergent Minecraft-native API in this repo).
- BF5's static mapping is Spacewar-App-ID-(`480`)-specific by construction;
  if the configured Steam App ID is ever changed to a real production App ID,
  the mapping will no longer match any achievement (every row falls back to
  raw-name-only rendering, FR-BF5.3) — a new mapping would need to be
  authored for that production app's own achievements at that time (Future
  Extensions).
- BF7's exact scaled-text rendering call shape must be confirmed per platform
  module (Mojang-mapped vs. Yarn-mapped `Font`/`GuiGraphicsExtractor` naming).

## Performance
- BF2 changes Statistics-tab data collection from an O(1) live-counter read
  to an O(number of in-scope local saves × stats-per-save) disk scan (plus
  one small, already-cheap read of `cross-world-stats`' own already-loaded
  in-memory config to resolve scope) — still gated by the existing
  once-per-tab-open `loaded` cache (FR-BF2.4), not per-frame, so the cost is
  paid once per Statistics-tab open, not continuously; flagged as the one
  item in this pass with a materially different cost profile than before,
  though still bounded by however many local saves the current account
  actually has (expected small in practice, and now correctly excludes any
  other account's saves rather than scanning them needlessly).
- No other item in this pass changes per-frame cost meaningfully: BF1/BF3 are
  pure layout-math changes; BF4's write-side hooks fire only at real
  join/leave-transition moments (already-infrequent events), not per-frame;
  BF5's mapping lookup is a single hash-map get per rendered row, negligible;
  BF6/BF7 have no new per-frame work beyond what already existed.

## Test Strategy
1. **Manual verification, all three platform modules**:
   - BF1: Items/Mobs tab column headers and values stay fully within the
     panel's right edge at multiple window widths (including a narrow one
     near `MIN_PANEL_WIDTH`), with no large empty gap before the first data
     column.
   - BF2: after playing in a singleplayer world (mining, crafting, fighting
     mobs) and returning to the main menu, the Statistics tab's General/
     Items/Mobs sub-tabs show real, non-zero data matching that play
     session; with multiple local saves under the same Steam account, values
     are summed across all of them; a local save belonging to a different
     Steam account (or created while offline under the `"offline"` sentinel,
     when currently logged in) is correctly excluded; subtitle reads
     "across all worlds" (no "& servers").
   - BF3: every one of the seven panels' content visibly starts with the
     same left inset as its top/right/bottom insets.
   - BF4: join a real saved server, then reopen the main menu — Home's
     Recent section reflects that server's real join timestamp relative to
     any recently-played worlds, correctly interleaved (not simply appended
     after all worlds). Persisted file survives a game restart.
   - BF5: Achievements rows for achievements present in the static mapping
     show a localized display name, description, and icon; rows for
     achievements absent from the mapping show the existing apiName-only
     rendering unchanged, with no error/placeholder artifact.
   - BF6: play normally in a loaded world for several seconds after last
     closing any menu — the friends sidebar/handle is fully hidden; opening
     and quickly closing the pause menu shows no visible flicker/reset of
     the sidebar's expanded/scroll state around that transition.
   - BF7: Home's greeting text is visibly bold and larger than the
     "Recent"/"Activity" section headers.
2. **Compilation** — `gradlew build`/`compileJava` succeeds on all three
   platform modules, plus any touched `features/*` module's own tests
   (`features/main-menu`, `features/cross-world-stats` if
   `CrossWorldStatsFacade` gains a method, `features/server-join-presence` if
   `ServerSessionLifecycle` gains a method).
3. If BF2's multi-world stat-aggregation logic is extracted into a
   plain-JVM-testable helper (recommended, given it is pure summation logic
   over parsed per-world stat maps), unit-test it directly with fake
   per-world stat maps to confirm correct cross-world summation and correct
   account-scoped inclusion/exclusion.
4. If BF4's join-history upsert/eviction logic (cap size, most-recent-first
   ordering) is extracted into a plain-JVM-testable helper (recommended, same
   reasoning as `CrossWorldStatsAggregatorTest`'s existing precedent for
   `cross-world-stats`), unit-test it directly.
5. BF5's mapping lookup (found/not-found branching, FR-BF5.3) is
   straightforward to unit-test with a small fake mapping and a handful of
   `apiName()` values (present and absent), independent of the mapping's
   real Spacewar content.

## Dependencies
No new external dependency for BF1/BF2/BF3/BF4/BF6/BF7. BF5 introduces no new
Maven coordinate either — its scope is a bundled static data/image resource
plus a UI lookup, no new library.

## Risks
1. **BF2 is the largest-risk item in this pass** — confirming the exact API
   to parse a save's persisted `stats/<uuid>.json` file into usable
   `Stat`/`StatType` values (ideally reusing vanilla's own file-to-
   `StatsCounter` loading code rather than hand-parsing raw JSON keys) must
   happen before implementation; if no such reusable API exists per platform
   module, a hand-rolled raw-key-to-`Stat`-object mapping is a meaningfully
   larger and more error-prone undertaking than originally scoped in
   batch-2-fixes' FR-F5.2, and must stay in sync with whichever
   `Stats.CUSTOM`/`ITEM_*`/`ENTITY_*`/`BLOCK_*` constants this panel already
   depends on. Additionally, the new read-only dependency on
   `CrossWorldStatsFacade` for account-scoping must not introduce a load-order
   problem (`cross-world-stats`' own config must already be loaded/available
   by the time `StatisticsPanel.reload()` runs) — planning must confirm
   composition-root ordering.
2. BF1's fix must not regress the already-shipped sort-column click hit-test
   (`mouseClicked`'s column-click detection) — FR-BF1.3 exists specifically to
   prevent the render/hit-test math from re-diverging.
3. BF4 requires adding write-side hooks into two other features'
   (`server-join-presence`, and the platform composition root's
   `SteamJoinRequestDispatcher` usage) existing classes without disturbing
   their current contracts for their existing consumers (`steam-world-hosting`,
   `server-join-presence` itself) — a cross-feature touch-point that must be
   reviewed carefully in planning, not a purely `features/main-menu`-internal
   change like the rest of this pass.
4. BF5's actual mapping content (real Spacewar achievement names/
   descriptions/icons) has not been sourced or verified during this
   specification — implementation must locate accurate, correctly-licensed
   public Steamworks SDK sample/documentation material for this content; if
   that content proves hard to source accurately, the fallback behavior
   (FR-BF5.3) ensures the panel degrades to today's status-quo rendering
   rather than showing incorrect data, but a thin/incomplete mapping is a
   plausible partial-completion outcome for this item.
5. BF6's exact tick-window length is a judgment call with no hard vanilla
   precedent to measure against — may need one round of user feedback after
   shipping to tune.

## Future Extensions
- BF4's friend-played-with record, if not fully surfaced into Home's Recent
  section UI this pass (FR-BF4.5), as a dedicated "Played with recently"
  row/section.
- BF5's mapping could later be extended to cover a real production App ID's
  achievements if/when this game's Steam App ID moves off the Spacewar test
  ID (`480`), authored the same way (a new hardcoded mapping resource) once
  that production app's achievement metadata is available.
- BF6's tick-window gating could become a user-configurable setting if the
  fixed default still doesn't satisfy every player's preference.
- BF2's per-world stat aggregation could later show a per-world breakdown
  (not just a summed total) if requested, or extend to remote-server stats if
  some future data source for those ever becomes available (currently a
  Non-goal/hard limitation).
</content>
