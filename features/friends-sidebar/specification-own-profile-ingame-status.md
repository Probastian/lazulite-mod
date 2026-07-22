# Own-Profile "In Game" Status — Specification (v1.7 amendment)

## Overview
This is a v1.7 amendment to `features/friends-sidebar/specification.md`, filed
alongside the standalone `specification-invite-to-game.md` and
`specification-status-recolor-ingame.md` amendments per this directory's
established convention. It is one of two specs split out of a formerly
combined document (`specification-own-profile-presence.md`, now a pointer to
this file and to `specification-richpresence-publishing.md`). This spec is
scoped **narrowly** to a bug fix:

- The own-profile row must always render a generic **"In Game"** style
  status (never the raw Steam persona word Online/Away/Busy) whenever the
  local Minecraft client session is active, mirroring the "In Game" tier
  `specification-status-recolor-ingame.md` already added for *other* friends.
- No Rich Presence text integration, no biome/dimension detection, no new
  publishing pipeline — that is entirely out of scope here and covered
  separately (and currently on hold) in
  `specification-richpresence-publishing.md`.
- Also folds in a regression check on the friend-row context menu's
  `isOwnProfile` gate, confirmed already-correct with no code changes
  needed — kept here as an acceptance-criteria/test-coverage item only.

This spec is complete and ready to move to the planning phase.

## Goals
- Own-profile row always shows a green, "in-game"-style status while the mod
  session is active — never a raw persona-state word for the local player.
- Reuse the existing generic `"In Game"` label
  (`FriendSidebarStateMachine.statusLabel(int, boolean)`'s existing fallback
  string for in-game friends with no Rich Presence text,
  `specification-status-recolor-ingame.md` FR-L2) for the own row's label —
  no new string, no new component.
- Confirm (not re-decide) that the own-row context menu shows only "Show
  profile" — report actual current behavior and pin it down with explicit
  acceptance criteria so a future edit to `FriendContextMenuWidget` cannot
  regress it silently.

## Non-goals
- No Rich Presence **publishing** of any kind (dimension/biome/pause/menu
  detection, translation infrastructure, `setLocalRichPresence("status", ...)`
  usage) — entirely deferred to `specification-richpresence-publishing.md`,
  which is currently on hold pending a separate user decision.
- No change to `FriendContextMenuWidget`'s existing "Join game"/"Invite to
  game" enablement wiring (`specification-invite-to-game.md`) — this
  amendment only touches the own-row's status label/color.
- No change to how *other* friends' in-game tier / Rich Presence is read
  (`specification-status-recolor-ingame.md`, `FriendsService.resolveFriend`,
  `richPresenceById`) — that is a read path for friends; unaffected here.
- No change to `FriendSummary.inGame()`/`gameAppId()` for the own profile
  (`localProfile()`) — see FR-OP4 below; this is a rendering-layer fix only.
- No in-mod settings UI of any kind.

## Requirements

### Piece 1 — Own-row "in game" status bug fix

**Current behavior (confirmed via code read, all three platform modules
structurally identical):**
- `FriendsService.localProfile()`
  (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java:136-149`)
  constructs the own-profile `FriendSummary` with `inGame = false` and
  `gameAppId = 0L` unconditionally (comment: "The own-profile row never uses
  inGame/joinable/connectHint... and never resolves Rich Presence").
- `FriendSidebarWidget.drawRow` (all three platform modules, e.g.
  `platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendSidebarWidget.java:556-582`)
  is the **same shared method** used for both the own row (called at
  `FriendSidebarWidget.java:416`, `own.ifPresent(profile -> drawRow(...))`)
  and every friend row (`FriendSidebarWidget.java:460`). It computes
  `statusColor`/`status` label from `friend.personaState()`/`friend.inGame()`
  and `facade.richPresenceStatus(friend.steamId64())`.
- Because `localProfile()` always sets `inGame = false`, and
  `facade.richPresenceStatus(ownSteamId64)` calls through to
  `SteamFriendsGateway.getFriendRichPresence`-backed lookup (a friend-relative
  read, not a self-read — confirmed via `FriendsService.richPresenceStatus`/
  `FriendsDataSource.richPresenceStatus`), the own row **always** falls
  through to `facade.stateMachine().statusLabel(friend.personaState(), false)`
  — i.e. the raw Steam persona word (Online/Away/Busy/etc.), exactly like a
  friend who is not in a game. **This is confirmed to be the bug**: the local
  player, while actively playing this modded client, never shows an
  "in-game"-style green status on their own row — it tracks their raw Steam
  client-wide persona state instead (which the user may have set to Away,
  or which Steam sets automatically after idle time, even while actively
  playing).

**Required behavior:**
- FR-OP1. Whenever the local Minecraft client has an active
  world/session — i.e. whenever `FriendsSidebarFacade.isSteamAvailable()` is
  true and there is a loaded world (singleplayer integrated server *or*
  connected to a remote server; the exact "session active" predicate is a
  planning-phase decision, but must be `true` any time gameplay could be
  happening, including while paused at the in-game pause menu, and `false`
  at the main menu) — the own-profile row's status color/label render via
  the same in-game-aware path other friends already use
  (`statusColorArgb(personaState, true)`/green,
  `statusLabel(personaState, true)`), never the plain persona-state word,
  regardless of the local player's raw `personaState`/away-timer state.
- FR-OP2. Label content: always the generic fallback `"In Game"` label (the
  exact string `FriendSidebarStateMachine.statusLabel(int, boolean)` already
  returns for other in-game friends with no Rich Presence text,
  `specification-status-recolor-ingame.md` FR-L2) — reusing that existing
  method/string rather than inventing a new constant. There is no tiered/
  richer label in this spec's scope; a future amendment
  (`specification-richpresence-publishing.md`, if/when it proceeds) may
  replace this fallback with a live computed string, but that is out of
  scope here and must not block this fix from shipping standalone.
- FR-OP3. `FriendSummary.inGame()`/`gameAppId()` for the own profile
  (`localProfile()`) are **not** changed to `true`/non-zero by this
  amendment — this fix is scoped to the **rendering** call site (`drawRow`'s
  own-row branch or a new parameter), not to `FriendSummary`'s own semantics,
  to avoid perturbing anything else that reads `FriendSummary.inGame()` for
  the own profile (there is currently nothing else that does, per
  `FriendSummary.java`'s own usage, but keeping the data-shape meaning "is
  this friend, per Steam, in a game" unchanged for the own row — which
  trivially never reports itself via `getFriendGamePlayed` — avoids a
  confusing self-referential semantic). Concretely: `drawRow` (or a small
  own-row-specific variant) must be told "treat this row as in-game" via a
  parameter/flag driven by FR-OP1's session predicate, independent of
  `friend.inGame()`.

### Piece 2 — Context-menu `isOwnProfile` gate: regression check

**Finding: already correct, not a regression, no bug.** Confirmed by reading
current code across all three platform modules (identical structure;
`platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java:87-91`
cited, mirrored on `fabric-26.1`/`fabric-1.21.11`):

```java
private boolean isEnabled(int index) {
    if (isOwnProfile) {
        // FR2.8: only "Show profile" is ever actionable for one's own row.
        return index == 1;
    }
    ...
```

This check runs **first**, unconditionally short-circuiting to "only index 1
(`Show profile`) enabled" whenever `isOwnProfile` is `true`, before any of the
per-friend `worldInviteSender`/`hostingStatusReader` gates added by
`specification-invite-to-game.md` are consulted. This means today's own-row
context menu already correctly shows only "Show profile" as clickable — Open
chat, Invite to game, and Join game are all disabled/greyed for the own row,
regardless of hosting state. The `specification-invite-to-game.md` Non-goals
citation ("FR2.8, isOwnProfile forces only 'Show profile' enabled") is
accurate as a description of current, already-correct behavior — it was not
a forward-looking TODO. No code change is required for Piece 2; this is a
test-coverage gap only.

**Required behavior (restated as an explicit, testable requirement, so a
future edit cannot silently regress this without a failing test):**
- FR-CM1. `FriendContextMenuWidget.isEnabled(int index)` must return `true`
  for `index == 1` ("Show profile") and `false` for every other index
  whenever the menu was constructed with `isOwnProfile = true`, regardless
  of any other constructor parameter (`worldInviteSender`,
  `hostingStatusReader`, `worldJoinRequester`, `toastService` — all
  irrelevant to the own row).
- FR-CM2. This must hold identically across all three platform modules
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`)
  — verified as structural twins during this investigation (all three files
  share the exact `isEnabled`/`isOwnProfile` shape as of this writing).

**Acceptance criteria (for the verification phase / a new or existing unit
test on `FriendContextMenuWidget` or an equivalent plain-JVM test target if
one exists):**
1. Construct/exercise `isEnabled(index)` with `isOwnProfile = true` for
   `index` in `{0, 1, 2, 3}`; assert `false, true, false, false`
   respectively, even when `worldInviteSender.isHosting()` would return
   `true` and `hostingStatusReader.isFriendHosting(...)` would return `true`
   (i.e. even in the "most permissive" non-own-row state, the own row must
   still show only Show profile enabled).
2. Construct/exercise `mouseClicked` on an own-profile menu at each row's
   coordinates; assert only clicking row 1 invokes
   `facade.actions().onShowProfile(...)`, and clicking rows 0/2/3 invokes
   nothing (no `onOpenChat`, no `worldInviteSender.inviteFriend`, no
   `worldJoinRequester.joinHostedWorld`) — the existing
   `if (... && isEnabled(index))` guard at
   `FriendContextMenuWidget.java:146` already structurally guarantees this
   given criterion 1, but an explicit click-level test guards against a
   future refactor of `mouseClicked` accidentally bypassing `isEnabled`.
3. No test currently exists asserting this (confirmed: no
   `FriendContextMenuWidget`-targeted test file was found in this
   investigation's search) — the verification phase should add one; this is
   flagged as a gap in *test coverage*, not a gap in *behavior*.

## Public API
Illustrative shapes only; final names are a planning-phase decision.

1. **`FriendSidebarWidget`'s own-row rendering** (all three platform
   modules) — the own-row branch of/around `drawRow` gains an "is own row,
   force in-game styling" path (FR-OP1/FR-OP3), sourcing its label from
   `facade.stateMachine().statusLabel(personaState, true)` (FR-OP2) — the
   existing generic in-game fallback string, unconditionally.
2. No change to `FriendContextMenuWidget`'s public surface (Piece 2 is a
   verification/test-coverage addition only).
3. No change to `FriendsSidebarFacade`, `FriendsService`, or `FriendSummary`'s
   public surface.

## Architecture
```
platform/fabric-<version>/.../FriendSidebarWidget
  |-- own-row branch: forces in-game-styled color + generic "In Game" label
        (FR-OP1/FR-OP2), independent of friend.inGame()/personaState()
```
No new class, no new cross-feature bridge. The fix is entirely local to each
platform module's `FriendSidebarWidget.drawRow` call site for the own row.

## UI
- Own-profile row: status dot/text always renders in the "in game" green
  (`0xFF5BA32F`, the same constant `specification-status-recolor-ingame.md`
  already established) whenever a session is active, never the raw
  persona-state palette, per FR-OP1.
- Own-profile row label text: always the existing generic `"In Game"` string
  (FR-OP2), never blank, matching the sidebar's existing "never rendered
  blank" invariant.
- Context menu for the own row: unchanged visual behavior (Piece 2 confirms,
  does not change) — greyed/non-interactive for Open chat / Invite to game /
  Join game, full-brightness/clickable for Show profile only.
- No new screen, no new sidebar chrome beyond the label-content change above.

## Configuration
None. Always-on whenever a session is active, matching this row's existing
always-rendered behavior.

## Events
No new event-bus entries (this repo has no generic event bus). This is a
pure rendering-layer read of already-available session state, evaluated at
draw time — no new poll/sweep cadence introduced.

## Networking
None. No new Steamworks calls of any kind — this fix touches only local
rendering logic, reusing data already read for other rows.

## Persistence
None. Status label/color is computed at draw time, never persisted.

## Compatibility
- Must land identically across all three platform modules
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`)
  — `drawRow`/the own-row call site are structural twins across all three as
  of this writing.
- `FriendSummary`'s own-profile construction (`localProfile()`) is
  **unchanged** by this amendment (FR-OP3) — no new record component, no
  constructor-arity change.
- `FriendContextMenuWidget`, `FriendSidebarWidget` are confirmed
  actively-evolving, shared files (git status at time of writing shows all
  three platform copies of both already modified by the in-flight
  dropdown-polish work, `implementation-plan-dropdown-polish.md`) — re-run
  `git status`/`git diff` immediately before editing either, per this
  repo's established discipline, and confirm no line-number drift from the
  citations in this document.

## Performance
Negligible — a label-string/color branch evaluated at draw time, same cost
class as the existing per-row status computation for every other friend.

## Future Extensions
- Replacing the generic `"In Game"` fallback with a live, richer status
  string is the entire subject of `specification-richpresence-publishing.md`
  (currently on hold) — if/when that spec is approved and implemented, its
  own-row consumption point should slot into FR-OP2's fallback chain without
  requiring further changes to this spec's own scope.
