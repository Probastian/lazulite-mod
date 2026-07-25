# Main Menu — Batch 2 Specification (Sidebar Relocation/Global Presence, Home/Activity Tab, Achievements Tab, Servers-Panel Friend Avatars)

## Overview
This is a **new-scope amendment** to `features/main-menu`, building on the base
feature (`features/main-menu/specification.md`) and the already-shipped
post-launch fix passes (`specification-post-launch-fixes.md`/`-2.md`/`-3.md`,
commits `0d71821`/`6fb32ec`). It covers four independent items, each with its
own Requirements/scope, sharing this one document because all four touch the
same `platform/fabric-<version>/.../mainmenu/` and `.../friends/` files:

1. **Sidebar relocation + global presence** — move the friend sidebar from the
   right edge to the **left** edge, and make it render on every screen,
   including the in-game HUD (not just an allow-listed set of menu `Screen`s).
2. **Home/Activity tab** — a new main-menu tab showing friends currently
   playing this game, scoped honestly to what Steamworks actually exposes
   (Rich Presence, not a real activity/history feed).
3. **Achievements tab** — a new main-menu tab listing every Steam achievement
   for this game via `ISteamUserStats`, contingent on a binding-availability
   check against this repo's custom steamworks4j fork.
4. **Servers panel friend avatars** — each `ServersPanel` row shows avatars of
   friends currently on that server, by consuming the already-built
   `features/server-join-presence` friend-count/presence query rather than
   inventing a new "connect" ownership scheme.

## Goals
- Sidebar visible from anywhere in the client (menus and in-game), docked to
  the screen's left edge, with `MainMenuScreen`'s own layout (tab bar, center
  panel) re-derived for the flipped edge.
- A Home/Activity tab surfacing "friends playing this game right now," built
  entirely from data `FriendsService`/`SteamFriendsGateway`/`FriendsSidebarFacade`
  already expose.
- An Achievements tab showing every achievement's icon, name, description, and
  unlocked/locked state, sourced from `ISteamUserStats`, with the binding's
  presence verified (not assumed) before scoping the read path.
- ServersPanel rows visually surfacing which friends (if any) are on that
  server, reusing `features/server-join-presence`'s existing
  `FriendServerPresenceReader` contract rather than re-deriving Rich Presence
  "connect" ownership.

## Non-goals
- **Not** a real Steam-client "Activity Feed"/library-activity history. Valve
  does not expose historical per-friend playtime/activity events to games via
  Steamworks (that UI is Steam-client-only, drawn from Steam's own backend, not
  a game-facing API) — Home/Activity is a live Rich-Presence snapshot only
  ("who is playing this game and what are they doing right now"), never a
  scrollable history/feed. This is a hard API ceiling, not a v1 scope
  trim — a "real" activity feed cannot be built later on Steamworks either,
  without Valve granting a different, non-existent capability.
- Not a redesign of `FriendSidebarWidget`'s internal state machine
  (`FriendSidebarStateMachine`), hover/expand mechanics, join-policy dropdown,
  or context menu — item 1 changes *where* the sidebar docks and *which
  screens* it is attached to, not how it behaves once shown.
- Not a rewrite of `FriendsSidebarFacade`/`FriendsService`'s existing Rich
  Presence read path — Home/Activity (item 2) is a new *presentation* of
  already-fetched friend data (persona state, `inGame`, `gameAppId`, Rich
  Presence `"status"`), not a new data-fetch mechanism.
- Not achievement *unlocking*/*setting* logic (`ISteamUserStats::SetAchievement`)
  — this pass is **read-only** display of the local player's existing
  unlock state; the mod does not grant/revoke achievements itself.
- Not a redesign of `features/server-join-presence` or `features/rich-presence`
  — item 4 is a pure consumer of `FriendServerPresenceReader`'s existing,
  already-specified `friendsOnServer(String hostPort): int`-shaped contract
  (`features/server-join-presence/specification.md` FR3.2); this document does
  not reopen that feature's "connect" key ownership design, which is already
  fully resolved (see Requirements, item 4, "Conflict resolution").
- Not extending the friend context-menu's "Join game"/"Invite to game" rows to
  cover multiplayer-server friends (`features/server-join-presence/specification.md`
  Non-goals/Future Extensions already defers this explicitly) — out of scope
  here too.
- No mobile/pause-menu-specific layout variant beyond what "renders on all
  screens" (item 1) already implies — one sidebar rendering path, one set of
  bounds-computation rules, used everywhere.

## Requirements

### Item 1 — Sidebar relocation (left edge) + global presence (all screens, including gameplay)

**Current state, confirmed by reading the cited files directly:**
- `FabricFriendsSidebarInjector.isAllowListed(Screen)` (`platform/fabric-26.2/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java:91-98`)
  gates sidebar attachment to exactly six `Screen` subtypes: `TitleScreen`,
  `SelectWorldScreen`, `JoinMultiplayerScreen`, `OptionsScreen`, `PauseScreen`,
  `RealmsMainScreen`, attached via `ScreenEvents.AFTER_INIT.register(this::onScreenInit)`
  (`:88`). There is no hook of any kind for the ordinary in-game HUD (no
  `Screen` at all is open while playing) — `ScreenEvents.AFTER_INIT` structurally
  cannot fire for "no screen," so achieving "renders during actual gameplay"
  requires a **second**, independent render path, not a widened allow-list.
- `FriendSidebarWidget` docks to the **right** edge in three places, confirmed:
  `handleX()` returns `screenWidth - HANDLE_WIDTH` (`FriendSidebarWidget.java:256-258`);
  `renderNow()`'s `isMouseOver`-consistent hit-test candidate computes
  `testX = screenWidth - testWidth` (`:389-390`); the actual per-frame draw
  call is `setX(screenWidth - width)` (`:450`). All three must flip to a
  left-edge equivalent (`testX = 0`, `setX(0)`, `handleX()` returns `0`).
- `MainMenuScreen` derives its own layout from the sidebar's live-collapsed
  width and assumes the sidebar sits at the *same* edge as the tab bar:
  `reservedWidth()` (`MainMenuScreen.java:151-155`) computes
  `maxAllowed = width - TAB_BAR_WIDTH - sidebarCollapsedWidth() - RIGHT_MARGIN - MIN_PANEL_WIDTH`;
  `panelX()` returns `reservedWidth()` (`:157-159`); `panelWidth()`
  (`:165-167`) and the tab bar's own `barX` (`:226`, `:274`) all subtract
  `TAB_BAR_WIDTH + sidebarCollapsedWidth() + RIGHT_MARGIN` from `width` on the
  **right** side, placing the reserved region (spacer + panel) on the left and
  the tab bar/sidebar stacked together on the right. Moving the sidebar to the
  *opposite* edge from the tab bar breaks this "reserve on the left, dock tab
  bar+sidebar together on the right" arithmetic; it must be re-derived so the
  sidebar reserves space on the **left** (`panelX()` starts after
  `sidebarCollapsedWidth() + LEFT_MARGIN`, not at `0`) while the tab bar keeps
  its own existing right-edge dock (`barX = width - TAB_BAR_WIDTH`, no
  longer needing to also subtract `sidebarCollapsedWidth()` since the two are
  no longer adjacent).

**FR-BB1.1 (dock flip).** `FriendSidebarWidget`'s `handleX()`, the hit-test
`testX` computation, and the per-frame `setX(...)` call all dock to the left
edge (`x = 0`), not `screenWidth - width`. `getY()`/vertical positioning is
unaffected. This must be applied identically in all three platform modules'
`FriendSidebarWidget.java` copies.

**FR-BB1.2 (MainMenuScreen re-derivation).** `MainMenuScreen`'s `panelX()`,
`reservedWidth()`, and `barX`'s computation (both the render call site and the
`mouseClicked`/`mouseScrolled` call sites, `MainMenuScreen.java:226`, `:274`)
are updated so the sidebar's reserved column sits at the **left** edge
(`x = 0` through `sidebarCollapsedWidth() + LEFT_MARGIN`) and the tab bar keeps
its existing right-edge dock, independent of the sidebar. `panelWidth()`'s
formula changes from "subtract tab bar + sidebar + margin from the right" to
"subtract sidebar + margin from the left, and tab bar + margin from the
right," since the two are no longer adjacent on the same edge. `panelY()`/
`panelHeight()` (vertical) are unaffected — this item is a horizontal-axis-only
change.

**FR-BB1.3 (global, screen-independent rendering).** A new mechanism, separate
from `FabricFriendsSidebarInjector`'s `ScreenEvents.AFTER_INIT`-driven
attach/detach, renders the sidebar every client frame regardless of whether any
`Screen` is open — i.e. a `HudRenderCallback`-driven overlay (Fabric API's
`net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback`, the standard
mechanism for drawing persistent HUD elements independent of the current
`Screen`). Concretely:
- One long-lived `FriendSidebarWidget` instance (or an equivalent
  screen-independent rendering wrapper around the same rendering/hit-test
  code) is constructed once at composition-root time (not per-`Screen`, unlike
  today's per-`onScreenInit` construction) and driven from
  `HudRenderCallback.HUD_RENDER_CALLBACK`'s registered callback every frame,
  in addition to (not instead of) whatever `Screen`-specific instance/overlay
  the six existing allow-listed `Screen`s still use today (see FR-BB1.4 for
  how those two paths are reconciled so the sidebar is never drawn twice in
  the same frame).
- Mouse input (hover-expand, click, scroll) while no `Screen` is open must be
  read from Minecraft's own raw mouse-position/button state
  (`Minecraft.getInstance().mouseHandler`/equivalent per version) rather than
  from a `Screen`'s `mouseClicked`/`mouseScrolled` dispatch, since there is no
  `Screen` to dispatch through during ordinary gameplay — planning must
  research and confirm the exact per-version raw-input read path (a new,
  currently-unexercised integration point for this codebase, since every
  prior UI feature is `Screen`-widget-based) before implementation; this spec
  fixes the requirement (sidebar must be hoverable/clickable during gameplay
  with no `Screen` open) but not the exact API call, which is a
  planning-phase, per-version `javap`/API-surface-confirmed detail.
- Escape/Options-menu opening (pressing Escape brings up `PauseScreen`, which
  is itself already an allow-listed `Screen`, FR-BB1.4) must not cause a
  visible flicker/duplicate/gap in the sidebar's rendering as the client
  transitions from "no screen, HUD-driven" to "`PauseScreen` open,
  `Screen`-driven" — the same underlying `FriendSidebarWidget` state (expanded/
  collapsed, dropdown-open, scroll offset) must carry across that transition
  rather than resetting, since both paths should share the same widget
  instance/state object (FR-BB1.4).

**FR-BB1.4 (reconciling the two render paths — single shared instance, not two).**
Rather than maintaining two independent `FriendSidebarWidget` instances (one
`HudRenderCallback`-driven for "no screen"/in-game HUD, one
`ScreenEvents.AFTER_INIT`-driven for the six existing allow-listed `Screen`s),
this spec requires **one shared instance** whose render/hit-test methods are
invoked from whichever of the two triggers currently applies each frame:
- When `Minecraft.getInstance().screen == null` (in-game, no menu open): the
  `HudRenderCallback` path renders/hit-tests it.
- When a `Screen` is open: if that `Screen` is one of the six previously
  allow-listed types (or, per this item's own "all screens" goal, **any**
  `Screen` at all — see below), `FabricFriendsSidebarInjector`'s existing
  `ScreenEvents.AFTER_INIT`/`afterExtract`/`onAllowMouseClick`/
  `onAllowMouseScroll` wiring continues to drive it, unchanged in mechanism,
  just no longer gated by `isAllowListed(Screen)`'s six-type check (FR-BB1.5).
- The two triggers are mutually exclusive per frame by construction
  (`Minecraft.getInstance().screen` is either `null` or a concrete `Screen`
  instance, never both), so there is no double-render/double-hit-test risk
  once the `HudRenderCallback` path explicitly early-returns whenever
  `Minecraft.getInstance().screen != null` (letting the `Screen`-driven path
  own that frame instead).

**FR-BB1.5 (allow-list removal — literally all screens).** `isAllowListed(Screen)`
(`FabricFriendsSidebarInjector.java:91-98`) is removed/replaced by an
always-true predicate (or the method itself is deleted and `onScreenInit`
attaches unconditionally) — the sidebar now attaches to **every** `Screen`
Fabric's `ScreenEvents.AFTER_INIT` fires for, not just the prior six. Planning
must audit for screens where this is undesirable/conflicting (e.g. Steam
World Hosting's join-failure/password-prompt custom `Screen`s, any full-screen
modal that should not have a persistent sidebar drawn over it) and may
introduce a small, explicit **deny-list** (inverted from today's allow-list)
only for screens with a concretely identified conflict — this spec's default
expectation is "on by default everywhere," with exclusions being the
exception requiring a documented reason, not the rule.

**FR-BB1.6 (handle-only default preserved).** The existing `handleOnly`
distinction (`TitleScreen`/`PauseScreen` get the always-visible avatar strip by
default, every other screen starts as a small click-to-open handle,
`FabricFriendsSidebarInjector.java:113-116`) is preserved and extended: the new
in-game-HUD path (FR-BB1.3) defaults to `handleOnly = true` (a small
left-edge handle during gameplay, matching "every other" screen's existing
convention, not the always-visible strip) — an always-visible sidebar strip
covering gameplay view at all times was not requested and would be a
significant, unrequested UX change; flagged here as this spec's own default
choice, open to override.

**FR-BB1.7 (`MainMenuScreen`'s own sidebar instance).** `MainMenuScreen`
currently constructs/hosts its own `FriendSidebarWidget`-reuse instance per
FR7.6 of the base spec (`addRenderableWidget(sidebar)`,
`MainMenuScreen.java:127`) — separate from `FabricFriendsSidebarInjector`'s
own per-`Screen` instances. Once the sidebar renders globally (FR-BB1.3), the
main menu is itself just another `Screen`, so planning should decide whether
`MainMenuScreen` keeps its own dedicated instance (current shape, simplest,
no regression risk) or is folded into the same shared-instance mechanism
FR-BB1.4 introduces — this spec's default is **keep `MainMenuScreen`'s own
instance as today** (lowest risk, matches its existing FR7.6 "graduate a
widget" precedent) unless planning finds a concrete duplication problem.

### Item 2 — Home/Activity tab (friends currently playing this game)

**Confirmed data already available (no new Steamworks surface needed):**
`FriendSummary`/`FriendsService`/`FriendsSidebarFacade` already expose
per-friend persona state, `inGame`/`gameAppId`-shaped in-game status, and Rich
Presence `"status"` string reads (`FriendSidebarStateMachine.statusLabel`,
`FriendsSidebarFacade.richPresenceStatus(steamId64)` — both cited in
`features/main-menu/specification.md` FR7.3). "Friends currently playing this
game" is exactly "friends whose `inGame`/`gameAppId` matches this mod's own
App ID," a filter over data already fetched for the sidebar's friends list —
no new Steamworks call, no new gateway method.

- **FR-BB2.1** New `MainMenuTab.HOME` (or `ACTIVITY`) value, added to the
  existing tab bar (`MainMenuTab` enum, `api/src/main/java/de/lazuli/api/mainmenu/`)
  alongside `WORLDS`/`SERVERS`/`STORE`/`WARDROBE`. Its panel lists every friend
  from `FriendsSidebarFacade.friends()` whose `gameAppId` equals this mod's own
  App ID (or equivalent "playing this same game" signal already used
  elsewhere in this codebase for the friends-sidebar's own in-game/online
  status coloring, `specification-status-recolor-ingame.md`), each row showing
  avatar, name, and current Rich-Presence `"status"` string (same source as
  the sidebar's own friend rows, FR7.3), sorted by whatever ordering
  `FriendSidebarStateMachine.sortForDisplay` already produces (reuse, don't
  reinvent).
- **FR-BB2.2** Empty state ("No friends are playing right now") when the
  filtered list is empty — matching this repo's existing empty-state
  convention for other panels (e.g. `ServersPanel`'s saved-list-empty state).
- **FR-BB2.3** No pagination/paging needed in v1 — friend lists in this
  codebase are small enough (bounded by Steam's own friends-list size) that a
  single scrollable list, matching the sidebar's own scrollable friend-row
  list pattern, suffices.
- **FR-BB2.4** Clicking a row opens the same friend context-menu the sidebar
  already provides (`FriendContextMenuWidget`, reused, not duplicated) —
  "Join game"/"Invite to game"/"View profile," whichever the friend's own
  hosting/join-policy state already gates, exactly as the sidebar's own row
  click already does (`features/friends-sidebar/specification.md`'s existing
  context-menu wiring) — this tab is a second presentation surface for the
  same friend list/menu, not a new interaction model.
- **FR-BB2.5** Real historical/library "activity feed" (what a friend played
  yesterday, total playtime trends, etc.) is explicitly **not** built — see
  Non-goals; this tab only ever shows a live snapshot of friends currently
  in-session with this game.

### Item 3 — Achievements tab

**Binding-availability check (must-do-first, not assumed).** This repo's
custom steamworks4j fork (`gradle.properties:44-51`,
`services/steamworks-inventory-bindings/specification.md`) is confirmed to
wrap `SteamInventory` (new) plus one added `SteamUser` callback
(`onMicroTxnAuthorizationResponse`) on top of upstream's existing wrapped
interfaces (`SteamRemoteStorage`, `SteamFriends`, `SteamUGC`,
`SteamMatchmaking`/`SteamMatchmakingServers`, `SteamApps`, `SteamUser`,
`SteamUtils`, per that spec's Overview). Whether `SteamUserStats` (the
interface `GetNumAchievements`/`GetAchievementName`/
`GetAchievementAndUnlockTime`/`GetAchievementDisplayAttribute` live on) is
already wrapped by upstream steamworks4j (and therefore present in this fork
without further work) is **not established by any file read so far in this
repo** and must be the first concrete step of implementing this item:

- **FR-BB3.1** Before any other work on this item, `javap` (or direct source
  enumeration of the fork's `java-wrapper/src/main/java/com/codedisaster/steamworks/`
  tree, the same method this repo's `ISteamInventory` gap-finding spec already
  used) the actually-resolved steamworks4j jar
  (`steamworks4j_version=v1.10.0-inventory.1`, `gradle.properties:51`) to
  confirm whether a `SteamUserStats`/`SteamUserStatsNative` class pair exists,
  and if so, which of `GetNumAchievements`, `GetAchievementName`,
  `GetAchievementAndUnlockTime` (or an equivalent unlock-time+bool accessor
  pair), and `GetAchievementDisplayAttribute` (`"name"`/`"desc"`/`"hidden"`
  attribute keys, per Valve's own `ISteamUserStats` documentation convention)
  it exposes at the Java level.
- **FR-BB3.2a (if already wrapped).** Build a thin gateway
  (`SteamAchievementsGateway`, following the existing one-gateway-per-interface
  convention, e.g. `services/src/main/java/de/lazuli/services/steamworks/`)
  exposing a plain `List<AchievementSummary>` read (id, display name,
  description, icon handle/texture reference, unlocked boolean, unlock
  timestamp if unlocked) built from the confirmed method set. No fork change
  needed; proceed straight to the tab's UI/Public API (below).
- **FR-BB3.2b (if not wrapped).** Scope a binding addition to the same fork
  following the **exact established pattern**
  `services/steamworks-inventory-bindings/specification.md` already used for
  `SteamInventory`: a new `SteamUserStats`/`SteamUserStatsNative` Java/JNI
  class pair (mirroring the existing `SteamUGC.java`/`SteamUGCNative.java`
  file-pair convention that spec's own Public API section cites as its
  structural template), scoped narrowly to the read-only achievement-listing
  method subset this tab needs (`GetNumAchievements`, `GetAchievementName`,
  `GetAchievementAndUnlockTime`, `GetAchievementDisplayAttribute`,
  `RequestCurrentStats`/the `UserStatsReceived_t` callback needed before any
  of the above are valid to call) — explicitly **not** the write-side
  methods (`SetAchievement`, `StoreStats`, `ClearAchievement`,
  `IndicateAchievementProgress`), consistent with this item's read-only,
  no-unlock-logic Non-goal. This sub-item is itself a standalone
  infrastructure task analogous to the Inventory-bindings fork work (same
  rebase-friendliness/native-rebuild/CI caveats that spec's Non-goals/NFR
  sections already document) — planning should treat it as its own
  prerequisite pass, not folded silently into this feature's implementation
  plan, mirroring how the Inventory-bindings fork itself was sequenced ahead
  of the Store panel's own dependent work
  (`services/steamworks-inventory-bindings/specification.md` Overview:
  "no other work... proceeds until this fork is ready").
- **FR-BB3.3** Achievement icons: `ISteamUserStats::GetAchievementIcon`
  returns a Steam-internal image handle (an `int`), which this repo's existing
  `AvatarTextureCache` precedent (`FabricFriendsSidebarInjector.java:87`,
  used for friend avatars sourced from a similar Steam-internal image-handle
  mechanism) is the nearest structural analog for turning into a renderable
  Minecraft texture — planning should confirm whether `GetAchievementIcon`
  is itself confirmed present (same FR-BB3.1 `javap` pass covers it) and, if
  so, reuse/extend the same image-handle-to-texture conversion pattern
  `AvatarTextureCache` already established, rather than inventing a second
  one. If icon retrieval turns out to be a materially larger binding gap than
  the text-only achievement fields, displaying achievements with a generic
  placeholder icon (name/description/unlocked-state text only, no real icon)
  is an acceptable **fallback scope reduction** for v1, not a blocking issue —
  flagged as a planning-phase call, not decided here.
- **FR-BB3.4** New `MainMenuTab.ACHIEVEMENTS` value, panel shows a scrollable
  grid/list of every achievement (`GetNumAchievements`-bounded), each entry:
  icon (or placeholder, FR-BB3.3), display name, description, and a visually
  distinct locked/unlocked state (matching this repo's existing "clearly
  distinguish enabled/disabled visual state" convention used elsewhere, e.g.
  `FR5.2`'s Store-panel owned/not-owned distinction) plus unlock date (from
  `GetAchievementAndUnlockTime`) when unlocked.
- **FR-BB3.5** Hidden achievements (Valve's own `"hidden"` display attribute)
  render with name/description withheld (a generic "Hidden Achievement"
  placeholder) until unlocked, matching Steam's own client convention for
  hidden achievements — read the `"hidden"` attribute via
  `GetAchievementDisplayAttribute` (FR-BB3.1) to decide this per-entry.
- **FR-BB3.6** If Steam is unavailable (`SteamAvailability.isSteamAvailable()
  == false`), the tab shows the same status-message fallback convention every
  other Steam-gated panel in this feature already uses (base spec FR7.5/FR4.6
  precedent).

### Item 4 — Servers panel friend avatars

**Conflict resolution (fully resolved, no ambiguity remains).** The task's
own framing assumed `features/rich-presence`'s "connect" ownership
(`HostingLifecycle`-only, singleplayer/P2P-hosted worlds) would need to be
reworked to also cover joining arbitrary dedicated/listed servers. Reading
`features/server-join-presence/specification.md` (already-approved,
pre-existing feature in this repo, confirmed via `settings.gradle:27` and its
own spec file) shows this work **already exists and is already fully
specified**: that feature (a) publishes Rich Presence `"connect"` as
`host:port` (its own distinct string format, FR1.4) whenever the local player
is connected as a client to any real multiplayer server — not just
P2P-hosted singleplayer worlds — and clears it on disconnect (FR1.1/FR1.2);
(b) arbitrates "connect" key ownership between itself and
`HostingLifecycle` by game-state exclusivity (a client is never
simultaneously running its own integrated server *and* connected to a remote
one, `features/server-join-presence/specification.md` Architecture,
"Ownership arbitration"); and (c) already specifies exactly the read-side
query this item needs: `FriendServerPresenceReader#friendsOnServer(String
hostPort): int` (FR3.2), explicitly called out in that spec's own Non-goals/
Future Extensions as "the data `features/main-menu`'s `ServersPanel` will
need... though wiring that display itself is scoped as Future Extensions" —
this batch-2 item **is** that follow-up wiring pass. **This spec does not
redesign "connect" ownership** — it consumes the already-decided, already-owned
mechanism as-is.

- **FR-BB4.1** `ServersPanel` obtains a `FriendServerPresenceReader` instance
  at construction (handed in by `MainMenuClientInitializer`'s composition
  root, following the same platform-composition-root handoff shape
  `WorldHostingBridgeHandoff`/`FriendsSidebarFacade` already use for this
  feature's other cross-feature data, base spec Architecture) — a Noop
  implementation (always returns `0`) when `features/server-join-presence` is
  disabled or Steam is unavailable, matching that feature's own FR0.2
  inert-when-unavailable contract.
- **FR-BB4.2** Each server row (both Saved and Browser sub-views, FR4.2/FR4.3
  of the base spec) additionally renders up to a small fixed number (2, per
  the task's own framing) of friend avatars for friends currently on that
  server: the row's own resolved address (`ServerData`'s host/port for Saved
  rows; the corresponding `ServerBrowserTableModel` row's address for Browser
  rows) is passed to `friendsOnServer(hostPort)`; if the count is `> 0`,
  planning must also decide how to obtain the *specific* friend identities (not
  just the count) to render avatars for — `FriendServerPresenceReader`'s
  currently-specified public contract (`features/server-join-presence/specification.md`
  Public API item 1) is `int friendsOnServer(String hostPort)`, a **count**,
  not a friend-identity list. **Gap flagged for planning**: either (a) extend
  `FriendServerPresenceReader`'s contract with a second accessor
  (`List<Long> friendSteamIdsOnServer(String hostPort)` or equivalent,
  a small, backward-compatible addition to that feature's already-built
  scanner, which already maintains a `Map<String, Set<Long>>`-shaped cache
  internally per that spec's FR3.1 — the identity data already exists inside
  the scanner, it just isn't exposed yet), or (b) this feature's own
  composition root separately queries `FriendsSidebarFacade.friends()`'
  already-available per-friend Rich Presence `"connect"` value directly
  (bypassing the count-only accessor) and decodes it with
  `server-join-presence`'s own connect-string codec to determine identity.
  Option (a) is the cleaner, less-duplicative approach (reuses the existing
  scanner's already-computed cache instead of a second per-friend Rich
  Presence poll) and is this spec's recommended default; the actual
  `api`-surface change (adding one accessor to an already-shipped feature's
  public interface) is a small, planning-phase-sequenced amendment to
  `features/server-join-presence/specification.md`, not a redesign of that
  feature.
- **FR-BB4.3** Avatars render using the same `AvatarTextureCache` mechanism
  the sidebar/friend rows already use (no new avatar-texture-loading code) —
  friend avatar, small, positioned adjacent to the row's existing
  ping-status dot/player-count area (exact pixel placement a planning/UI
  decision, consistent with this feature's existing "manual per-panel
  discrepancy fix" precedent, `specification-post-launch-fixes-3.md` FR-B3
  series).
- **FR-BB4.4** When more than 2 friends are on the same server, the third and
  beyond overflow into a single aggregate "+N" avatar/badge (N = total count
  − 2), positioned immediately after the two shown avatars — clicking it is
  not required to do anything in v1 (no expand-to-full-list popover required,
  though planning may add one if trivial); the count itself always comes from
  `friendsOnServer(hostPort)` (FR-BB4.1), so "+N" stays correct even if the
  identity-list gap (FR-BB4.2) is deferred and only the count is wired in an
  initial pass (in which case 0 actual avatars are shown and "N friends
  online" reads as a text badge instead — an acceptable phased-rollout
  fallback, not a blocking dependency between the count and identity halves
  of this item).
- **FR-BB4.5** No new Rich Presence key, no new "connect"-ownership
  arbitration logic, no change to `HostingLifecycle` or
  `features/server-join-presence`'s existing set/clear lifecycle — this item
  is a pure **consumer** of already-published data.

## Public API
Illustrative shapes only; final names/signatures are a planning-phase
decision, consistent with the base spec's own convention.

1. **Item 1** — no new `api` types; `FriendSidebarWidget`'s constructor/fields
   gain no new parameters (dock-side is an internal constant flip, not a
   configurable option in v1). `FabricFriendsSidebarInjector` loses
   `isAllowListed(Screen)` (or it becomes a small, explicit deny-list) and
   gains a `HudRenderCallback` registration alongside its existing
   `ScreenEvents.AFTER_INIT` registration (FR-BB1.3/FR-BB1.4).
2. **Item 2** — `MainMenuTab.HOME`/`ACTIVITY` new enum value
   (`api/src/main/java/de/lazuli/api/mainmenu/MainMenuTab.java`); no other new
   `api` type (reuses `FriendSummary`/`FriendsSidebarFacade` verbatim).
3. **Item 3** —
   - `api/src/main/java/de/lazuli/api/mainmenu/`: `MainMenuTab.ACHIEVEMENTS`;
     `AchievementSummary` plain record (`id`, `displayName`, `description`,
     `hidden` (boolean), `unlocked` (boolean), `unlockedAtEpochSeconds`
     (nullable/`OptionalLong`), `iconHandle`/texture-reference-or-null).
   - `services/src/main/java/de/lazuli/services/steamworks/SteamAchievementsGateway`
     (new, one-gateway-per-interface convention) — `List<AchievementSummary>
     achievements()`, backed by `SteamUserStats` once FR-BB3.1/3.2 confirm/add
     the binding; a `NoopSteamAchievementsGateway` (`List.of()`, no Steam)
     mirroring every other gateway's Noop-when-unavailable pattern.
4. **Item 4** —
   - `features/server-join-presence`'s `api.FriendServerPresenceReader` gains
     one new accessor (FR-BB4.2, option (a)):
     `List<Long> friendSteamIdsOnServer(String hostPort)` (or equivalent) —
     the sole cross-feature `api` surface change this batch introduces,
     scoped inside an already-shipped feature's own interface, not a new
     feature.
   - No new `main-menu`-owned `api` type beyond `ServersPanel`'s internal
     rendering of the returned identity list into avatars via the existing
     `AvatarTextureCache`.

## Architecture
- Item 1's global-rendering path is a new integration point for this
  codebase — the first feature to render a persistent widget via
  `HudRenderCallback` rather than exclusively via `Screen`-scoped
  `ScreenEvents`/widget-list injection. It sits alongside (does not replace)
  `FabricFriendsSidebarInjector`'s existing `Screen`-scoped mechanism
  (FR-BB1.4); both continue to live in the same `platform/fabric-<version>/.../friends/`
  Version Adapter package, no new module.
- Item 2 is presentation-only, reusing `FriendsSidebarFacade` exactly as the
  base spec's FR7 already does for the sidebar — no new `services`/`api`
  cross-feature edge beyond what FR7's existing composition-root handoff
  already establishes.
- Item 3 introduces one new gateway class
  (`SteamAchievementsGateway`/`SteamworksSteamAchievementsGateway`) in
  `services/src/main/java/de/lazuli/services/steamworks/`, following the
  existing one-gateway-per-Steamworks-interface layering
  (`architecture.md`'s "one gateway/service class, not scattered" discipline,
  already cited in the base spec's Store-panel section) — `features/main-menu`
  depends on it via `api`/`services` only, same layering rule as every other
  cross-feature integration in this repo.
- Item 4 depends on `features/server-join-presence`'s `api` package only
  (`de.lazuli.api.serverjoinpresence`, per that feature's own Public API
  section) — obtained via the platform composition root's existing
  handoff-broker pattern (`WorldHostingBridgeHandoff`-shaped), exactly as the
  base spec's Architecture section already does for `FriendsSidebarFacade`/
  `ServerBrowserSessionFactory`. No feature-to-feature Java import is
  introduced; `features/main-menu`'s own classes never import
  `de.lazuli.features.serverjoinpresence.*`.

## UI
- Item 1: the sidebar's left-edge dock is a visible layout change on every
  screen it appears on (all six previously-allow-listed screens, `MainMenuScreen`,
  and now every other screen plus in-game gameplay) — manual per-screen,
  per-platform-module verification is required (this repo's standing
  UI-verification discipline, `features/main-menu/specification.md` UI
  section), specifically checking: no overlap with any screen's own
  left-anchored vanilla content (e.g. `PauseScreen`'s own button column,
  `OptionsScreen`'s own layout) that the sidebar's previous right-edge dock
  never had to consider — this is new visual-collision risk this item
  introduces and must be checked screen-by-screen, not assumed clear.
- Item 1: the new in-game-HUD sidebar (collapsed handle by default,
  FR-BB1.6) must not obstruct crosshair/hotbar/vanilla HUD elements at the
  left edge — verify at multiple GUI scales.
- Item 2/3: new tabs follow the existing tab bar's established visual pattern
  (base spec FR2) — icon + label, active/inactive/hover states matching the
  existing four tabs' Design Tokens.
- Item 4: avatar/badge additions to `ServersPanel` rows must not visually
  crowd the row's existing ping-dot/player-count/lock-icon elements
  (`specification-post-launch-fixes-3.md` FR-B3.4-3.6 already flags this
  panel's tight per-row layout) — a per-panel manual verification pass is
  required, consistent with that document's own discipline.
- Color literals: any new fill/text this batch introduces (achievement
  locked/unlocked tint, Home-tab status text, avatar-badge background) must
  carry a full `0xFF` alpha byte, per the base spec's standing caution.

## Configuration
- No new config file for item 1 (sidebar dock side/global-render is not
  user-configurable in v1).
- No new config file for item 2 (Home/Activity tab has no persisted state).
- No new config file for item 3 (achievement data is always live-read from
  Steam, never cached to disk).
- No new config file for item 4 beyond `features/server-join-presence`'s
  existing `config/server-join-presence.json` `enabled` flag (already
  specified, unchanged) — this batch adds no new config surface.

## Events
- Item 1: consumes `net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback`
  (new integration point for this codebase, FR-BB1.3) in addition to the
  existing `ScreenEvents.AFTER_INIT`/`afterExtract`/`allowMouseClick`/
  `allowMouseScroll` registrations `FabricFriendsSidebarInjector` already
  uses.
- Items 2-4: no new event source — presentation reads on existing
  per-tick-refreshed facade/gateway state (`FriendsSidebarFacade`,
  `FriendServerPresenceReader`'s own scanner, both already ticking
  independently of this feature).

## Networking
- Item 1: no new networking — same Steamworks IPC (`SteamFriends`) the
  sidebar already uses, now invoked from a persistent per-frame path instead
  of a per-`Screen`-instance one; no new call frequency concern (same
  underlying facade, same tick-driven refresh).
- Item 2: no new networking — same Rich Presence read path (`richPresenceStatus`)
  already polled for the sidebar.
- Item 3: new Steamworks IPC surface — `ISteamUserStats::RequestCurrentStats`
  (must be called and its `UserStatsReceived_t` callback awaited before any
  achievement accessor is valid, standard Valve requirement) plus the
  per-achievement read calls (FR-BB3.1) — local Steam-client IPC only, no raw
  network I/O, consistent with every other Steamworks call in this repo.
- Item 4: no new networking — consumes `features/server-join-presence`'s
  already-specified scanner output (itself local Steam-client IPC only, no
  new network surface).

## Persistence
No new persistent state in this batch. Item 3's achievement unlock state is
Steam's own persisted state (read-only from this mod's perspective, never
written/cached to a local file). Items 1/2/4 have no persistence at all.

## Compatibility
- All four items must land identically across `platform/fabric-1.21.11`,
  `platform/fabric-26.1`, `platform/fabric-26.2`, per this repo's standing
  three-platform-module rule — none of this batch's changes are expected to
  be shareable verbatim across the Yarn/Mojang mapping boundary, same as
  every prior custom-`Screen`/HUD-rendering feature in this repo.
- Item 1's raw-mouse-input read path for the no-`Screen` case (FR-BB1.3) is
  this batch's single largest cross-version-divergence risk — the exact
  Fabric/Minecraft API for "current mouse position/button state with no
  `Screen` open" must be independently `javap`/API-surface-confirmed per
  platform module before implementation, per this repo's mandatory
  pre-implementation discipline; this spec does not assume a specific method
  name/signature.
- Item 3's `SteamUserStats` binding-presence question (FR-BB3.1) must be
  confirmed once against the resolved fork jar (not per-platform-module,
  since the jar is shared across all three modules via the same Jar-in-Jar
  mechanism, `gradle.properties:44-51`) — if a fork change is needed
  (FR-BB3.2b), that change happens once at the fork/binding layer, not
  per-platform-module.
- Item 4's `api`-surface addition to `features/server-join-presence`
  (FR-BB4.2 option (a)) is a backward-compatible interface addition (new
  method on an existing interface) — planning must confirm this does not
  break any existing implementer of `FriendServerPresenceReader` (currently
  only that feature's own scanner and its Noop counterpart, per that spec's
  own Public API section) by adding a default method or updating both
  implementers in the same change.

## Performance
- Item 1: rendering the sidebar every client frame during gameplay (not just
  while a menu `Screen` is open) is new, continuous per-frame cost that did
  not exist before — the existing `FriendSidebarWidget`/`FriendSidebarStateMachine`
  render/hit-test cost is already small (base spec's own Performance
  precedent for the sidebar being cheap enough to run per-frame on every
  allow-listed screen already established this), so no new performance risk
  is expected, but this is the first time that cost runs continuously during
  actual gameplay rather than only on menu screens — flag for a frame-time
  spot-check during manual verification, not assumed free.
- Item 2/4: pure reads of already-ticking facade/scanner state, no new
  per-tick cost.
- Item 3: achievement data is fetched once per Achievements-tab open (or
  cached for the `MainMenuScreen` session, matching FR1.3's "state resets
  each fresh screen open" convention) — not re-queried every frame while the
  tab is open.

## Future Extensions
- Item 1: a user-facing left/right dock-side config toggle, if any user
  feedback prefers the sidebar's prior right-edge position.
- Item 2: extending Home/Activity with per-friend "invite me to what they're
  doing" affordances beyond the existing context-menu Join/Invite rows, if a
  concrete use case emerges.
- Item 3: achievement *progress* bars for stat-tracked (not just boolean)
  achievements (`ISteamUserStats::GetAchievementProgressLimits`), and/or a
  "recently unlocked" sort/highlight, once the base read-only list is
  shipped and proven.
- Item 4: an expand-to-full-list popover when clicking the "+N" aggregate
  avatar (FR-BB4.4), and extending the friend context-menu with a
  "Join this friend's server" row once `features/server-join-presence`'s own
  already-flagged Future Extension (context-menu Join wiring for
  multiplayer-server friends) is built.
