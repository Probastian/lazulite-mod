# Friends Sidebar — "Invite to Game" Specification (v1.6 amendment)

## Overview
The friend row context menu (`FriendContextMenuWidget`, one structural twin per
platform module) has always rendered a four-option menu — Open chat / Show
profile / **Invite to game** / Join game — but the third slot has been a
permanently-disabled placeholder since the Friends Sidebar's original v1 spec
(`features/friends-sidebar/specification.md` FR3.3): `FriendSidebarStateMachine
.isInviteEnabled(...)` always returns `false`
(`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendSidebarStateMachine.java:65-67`)
and `FriendActionListener.onInvite(long)`'s only real implementation
(`FriendsService.onInvite`,
`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java:167-169`)
is an empty method body. This amendment makes that slot real: when the local
player is hosting a Steam World Hosting session, "Invite to game" becomes
enabled and, when clicked, sends a real Steam invite (via
`ISteamFriends::InviteUserToGame`) to that friend for the current hosted
session — mirroring how the sibling "Join game" slot was already wired to
Steam World Hosting's `WorldJoinRequester`/`FriendHostingStatusReader` bridge
contracts (`features/steam-world-hosting/specification.md` FR4.1-FR4.3,
resolved to reuse the existing menu slot exactly rather than add a fifth
entry).

**Reused existing implementation, not a new one.** `FriendContextMenuWidget`
already has a slot, a label, and a click dispatch path for this
(`case 2 -> facade.stateMachine().isInviteEnabled(friend)` /
`case 2 -> facade.actions().onInvite(friend.steamId64())`,
`platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java:80,130`,
structurally identical on `fabric-26.1`/`fabric-1.21.11`). This amendment does
not add a new widget row; it wires the existing one to real logic, the same
shape the "Join game" slot's own wiring already established
(`features/steam-world-hosting/specification.md` FR4.1-FR4.3, "Join game"
resolution).

## Goals
- When the local player currently has an active Steam World Hosting session
  (`HostedWorldStatus.hosting() == true`), the "Invite to game" row is enabled
  for every friend the sidebar currently lists (all sidebar entries are, by
  construction, direct Steam friends already — `FriendsService` only ever
  populates direct friends).
- Clicking an enabled "Invite to game" row calls the real Steamworks API
  (`SteamFriends.inviteUserToGame(SteamID steamIDFriend, String
  connectString)`) with that friend's `SteamID` and the exact same
  `"+lazuli_join <hostSteamId64>"` connect string
  (`ConnectStringCodec.encode(...)`,
  `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/ConnectStringCodec.java:39-41`)
  already advertised via Rich Presence for the native-overlay join path
  (`features/steam-world-hosting/specification.md` FR2.1/FR2.3) — one single
  connect-string format serves both the passive (Rich Presence "Join Game"
  button) and active (in-mod "Invite to game" click) invite paths.
- "Invite to game" is disabled (non-interactive, greyed, per the existing
  `FriendContextMenuWidget.isEnabled(int)`/`textColor` mechanism) whenever the
  local player is not currently hosting — this is the overwhelmingly common
  case today (Steam World Hosting has no manual "start hosting" action; a
  world must be loaded, `features/steam-world-hosting/specification.md`
  FR1.1/FR1.2) and must read as clearly non-actionable, not merely silently
  no-op on click.
- Reuses the exact composition-root bridge shape (ADR-0003,
  `WorldHostingBridgeHandoff`) the sibling "Join game" slot already
  established, adding one small, symmetric new bridge contract rather than
  inventing a new pattern.

## Non-goals
- **No lobby/matchmaking-based invite.** This mod's Steam integration for
  hosted worlds is Rich-Presence-connect-string-based
  (`features/steam-world-hosting/specification.md` Networking/FR2), not
  `ISteamMatchmaking` lobby-based — `Server Browser`
  (`features/server-browser/specification.md`) is a separate, unrelated
  matchmaking-lobby-based feature (server discovery/listing) and is not
  touched or reused by this amendment; there is no lobby ID to attach an
  invite to here.
- **No new context-menu row, no new label copy.** The existing "Invite to
  game" label/slot/ordinal position is reused exactly as-is (Goals) — this is
  not a request to rename it, move it, or add a fifth menu option.
- **No group/multi-select invite, no "invite all friends" bulk action.**
  Exactly one friend per click, matching every other context-menu row's
  existing one-friend-per-invocation shape.
- **No invite-acceptance/decline feedback loop.** Steam's own native invite
  notification/accept-decline UI (the friend's own Steam client chrome) is
  entirely out of this mod's control and out of scope — this feature only
  covers *sending* the invite; what the recipient does with it (accept via
  Steam overlay → triggers their own `onGameRichPresenceJoinRequested`,
  already handled by `features/steam-world-hosting/specification.md` FR3.1
  path 1) is unchanged, pre-existing behavior.
- **No retry/queueing of a failed invite call.** A single, synchronous
  `inviteUserToGame` call per click; if it returns `false`, this is surfaced
  once (see Requirements) and the click is not automatically retried.
- **No change to "Join game"'s own existing wiring, enablement, or click
  behavior** (`features/steam-world-hosting/specification.md` FR4.1-FR4.3,
  already shipped) — this amendment is scoped entirely to the sibling
  "Invite to game" slot.
- **No change to the always-enabled own-profile-row special case** (FR2.8,
  `isOwnProfile` forces only "Show profile" enabled) — inviting yourself is
  meaningless and stays disabled exactly as today.

## Requirements

**Enablement (context-menu slot)**
- **FR-INV1.** `FriendSidebarStateMachine.isInviteEnabled(FriendSummary
  friend)` (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendSidebarStateMachine.java:65-67`)
  changes from an unconditional `false` to `true` exactly when the local
  player currently has an active Steam World Hosting session — i.e. reading a
  new, injected `boolean isLocalPlayerHosting()`-shaped query (see Public
  API), analogous to how `FriendHostingStatusReader.isFriendHosting(long)`
  already gates the "Join game" slot's own `isEnabled(3)` branch
  (`FriendContextMenuWidget.java:81-85`). `FriendSidebarStateMachine` itself
  stays plain-JVM/steamworks4j-free (NFR1) — the query is passed in as a
  constructor/method parameter or small functional interface, not a direct
  Steamworks call, mirroring `isJoinEnabled`'s existing shape once wired.
- **FR-INV2.** No additional per-friend gating beyond "local player is
  hosting" is required for v1 — every friend the sidebar lists is already a
  direct Steam friend (`FriendsService` only ever populates direct friends),
  so there is no extra "is this a friend" check needed the way "Join game"
  needed `FriendHostingStatusReader` (that check answers "is *this friend*
  hosting," a different, per-friend question; "Invite to game" only needs
  "am *I* hosting," a single global boolean). Whether to additionally gre 
  suppress the row for a friend already known to be in-game/already invited
  is an **open question** (see Open Questions) — v1's baseline behavior does
  not attempt this (Steamworks exposes no "already invited"/"already in this
  specific session" query short of re-reading that friend's own Rich
  Presence, which this mod does not currently correlate against its own
  local hosting session id in the outbound direction).
- **FR-INV3.** The own-profile row's forced-disabled behavior (FR2.8,
  `isOwnProfile == true` ⇒ only index 1 enabled) is unchanged and takes
  precedence over FR-INV1 — inviting yourself is never enabled regardless of
  hosting state.

**Sending the invite (business logic)**
- **FR-INV4.** Clicking an enabled "Invite to game" row invokes
  `FriendActionListener.onInvite(long steamId64)`
  (`api/src/main/java/de/lazuli/api/friends/FriendActionListener.java:29-33`,
  Javadoc updated — no longer "a disabled placeholder in v1, never
  reachable"). `FriendsService.onInvite(long steamId64)`
  (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java:167-169`)
  becomes a real implementation: it obtains the current hosted-world connect
  string via the new bridge contract (Public API) and calls
  `SteamFriendsGateway`'s new `inviteToGame(long friendSteamId64, String
  connectString): boolean` method (mirroring `setLocalRichPresence`'s own
  `boolean` success-return shape,
  `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java:105`),
  which is the sole new steamworks4j call site
  (`SteamFriends.inviteUserToGame(SteamID, String)`), added to
  `SteamworksSteamFriendsGateway` (the one class in the codebase permitted to
  import `com.codedisaster.steamworks.*` for friends/identity,
  `SteamFriendsGateway.java:19-22`) and to `NoopSteamFriendsGateway` (returns
  `false`, no-op).
- **FR-INV5.** If, at click time, the local player is not actually hosting
  (a race between the menu having been opened while hosting and hosting
  stopping before the click resolves — e.g. the world was quit in the same
  frame), `onInvite(...)` no-ops rather than calling `inviteUserToGame` with
  a stale/empty connect string. This mirrors `WorldJoinRequester
  .joinHostedWorld`'s own "never throws back to the caller" contract
  (`api/src/main/java/de/lazuli/api/worldhosting/WorldJoinRequester.java:23`)
  and `HostingLifecycle`'s existing idempotent start/stop shape.
- **FR-INV6.** `NoopFriendsService.onInvite(long steamId64)` (used whenever
  Steam is unavailable or the Friends Sidebar feature itself is disabled)
  stays a no-op — unreachable in that state since the sidebar itself is not
  rendered (FR0.2 precedent, unchanged).
- **FR-INV7.** Neither this feature nor the bridge introduces a new
  `features/steam-world-hosting` → `features/friends-sidebar` (or reverse)
  direct import — the new bridge contract lives in `api/`, and the platform
  composition root performs the wiring, exactly the `WorldJoinRequester`/
  `FriendHostingStatusReader` precedent (ADR-0003 shape,
  `features/steam-world-hosting/specification.md` Architecture's
  "Cross-feature bridging" subsection).

**Feedback on send**
- **FR-INV8.** A failed `inviteUserToGame` call (returns `false` — e.g. the
  Steam overlay is disabled/unavailable, per `SteamFriendsGateway
  .isOverlayEnabled()`'s existing precedent for a related overlay-dependent
  operation) is surfaced to the player in some lightweight, non-blocking way
  (a toast/chat-hotbar message via vanilla's existing
  `Minecraft.getInstance().gui.setOverlayMessage`-equivalent, or an
  equivalent already-established feedback mechanism elsewhere in this
  codebase) rather than silently doing nothing — exact copy/mechanism is a
  planning-phase decision (see Open Questions); this requirement only fixes
  that *some* non-silent feedback exists on failure.
- **FR-INV9.** A successful invite send requires **no** blocking
  confirmation dialog and no interruption to the host's own gameplay — the
  context menu simply closes (existing `onClosed.run()` behavior,
  `FriendContextMenuWidget.mouseClicked`, unchanged) exactly as every other
  menu-row click already does today.

## Public API

Illustrative shapes only; final names are a planning-phase decision (same
convention as sibling specs in this repo).

1. **`api/src/main/java/de/lazuli/api/worldhosting/`** — new interface,
   e.g. `WorldInviteSender`:
   ```java
   public interface WorldInviteSender {
       /**
        * @return true if the local player currently has an active hosted
        *         session that a friend could be invited to
        */
       boolean isHosting();

       /**
        * Sends a real Steam invite for the current hosted session to the
        * given friend. No-ops (returns false) if !isHosting(). Never throws.
        */
       boolean inviteFriend(long friendSteamId64);
   }
   ```
   Defined in `api/worldhosting` (not `api/friends`) for the same ownership
   reason `WorldJoinRequester` already gives — `steam-world-hosting` owns
   both the hosting-state truth and the actual Steamworks invite call; the
   Friends Sidebar only ever consumes it through this contract. Combining
   "am I hosting" and "send the invite" into one small interface (rather than
   two, mirroring `WorldJoinRequester`+`FriendHostingStatusReader`'s existing
   split) is a planning-phase decision — reusing `HostedWorldStatus` directly
   plus a separate one-method sender interface is an equally valid shape and
   is left open (Open Questions).
2. **`de.lazuli.api.friends.FriendActionListener#onInvite(long)`** — Javadoc
   updated to drop the "unreachable placeholder" framing (currently
   `api/src/main/java/de/lazuli/api/friends/FriendActionListener.java:29-33`);
   signature unchanged.
3. **`de.lazuli.features.friendssidebar.services.FriendSidebarStateMachine
   #isInviteEnabled(FriendSummary)`** — gains a new constructor/method
   parameter (a `boolean` or a small `BooleanSupplier`-shaped hosting-status
   query) so it can answer FR-INV1 while staying plain-JVM
   (`FriendSidebarStateMachine.java:65-67`); exact shape (constructor field
   vs. per-call parameter) is a planning decision, matching how
   `FriendHostingStatusReader` was threaded through as a per-call parameter
   for the sibling "Join game" slot rather than a stored field on
   `FriendSidebarStateMachine` itself
   (`FriendContextMenuWidget`'s own constructor,
   `platform/fabric-26.2/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java:61-70`).
4. **`de.lazuli.services.steamworks.SteamFriendsGateway`** — gains one new
   method, e.g.:
   ```java
   /**
    * Sends a real Steam invite for the given connect string to the given
    * friend ({@code ISteamFriends::InviteUserToGame}).
    *
    * @return true on success
    */
   boolean inviteToGame(long friendSteamId64, String connectString);
   ```
   Implemented for real in `SteamworksSteamFriendsGateway` (wraps
   `SteamFriends.inviteUserToGame(SteamID, String)`, confirmed present in
   this repo's pinned `steamworks4j-1.10.0.jar`,
   `features/friends-sidebar/plan.md:143`) and as a no-op `false` in
   `NoopSteamFriendsGateway`.
5. **`WorldHostingBridgeHandoff`** (per-platform-module,
   `platform/fabric-<version>/src/main/java/de/lazuli/WorldHostingBridgeHandoff.java`)
   — gains a third published reference (the new `WorldInviteSender`,
   alongside the existing `WorldJoinRequester`/`FriendHostingStatusReader`
   pair), following the exact same `publish(...)`/`requireX()`/non-null
   `Noop*`-when-disabled discipline already established for the other two.
6. **`FriendContextMenuWidget`** (per-platform-module) — constructor gains
   one new parameter, e.g. `WorldInviteSender worldInviteSender`
   (nullable when Steam World Hosting is absent/disabled, same convention as
   the existing nullable `worldJoinRequester`/`hostingStatusReader`
   parameters), and `isEnabled(2)`/the `case 2` click branch read from it
   instead of `facade.stateMachine().isInviteEnabled(friend)` directly — or,
   if FR-INV1's plumbing threads the hosting-status boolean through
   `FriendSidebarStateMachine` instead (Public API item 3's open shape
   question), `isEnabled(2)` stays `facade.stateMachine()
   .isInviteEnabled(friend)` unchanged and only the constructor/composition
   root wiring changes. Which of these two shapes to use is a planning-phase
   decision (Open Questions) — both are compatible with this spec's
   Requirements.

## Architecture
Mirrors `features/steam-world-hosting/specification.md`'s existing
"Cross-feature bridging (Friends Sidebar ↔ Steam World Hosting)" subsection
exactly, adding one more bridge direction alongside the two already
documented there:

```
platform/fabric-<version>/.../SteamWorldHostingClientInitializer  (composition root)
  |-- (existing) publishes WorldJoinRequester + FriendHostingStatusReader
  |-- (new) also publishes WorldInviteSender, backed by HostingLifecycle's
  |     currentStatus().hosting() + ConnectStringCodec.encode(...) +
  |     SteamFriendsGateway.inviteToGame(...)

platform/fabric-<version>/.../FriendsSidebarClientInitializer  (composition root)
  |-- (existing) reads WorldHostingBridgeHandoff.requireJoinRequester()/
  |     requireHostingStatusReader(), wires them into FriendContextMenuWidget
  |     construction sites
  |-- (new) also reads WorldHostingBridgeHandoff.requireWorldInviteSender(),
  |     wires it into the same FriendContextMenuWidget construction sites
  |     and/or FriendSidebarStateMachine's isInviteEnabled(...) call site
```

No new `services/` extraction is needed beyond the already-existing
`SteamFriendsGateway` seam (`inviteToGame` is one more method on that
already-shared interface, not a second Steamworks seam) — `FriendsService`
itself never gains a direct steamworks4j import; it continues to depend only
on the bridge contract, same as it already does for "Join game."
`SteamWorldHostingClientInitializer` must continue to run **before**
`FriendsSidebarClientInitializer` in each module's `fabric.mod.json`
`"client"` array (existing load-bearing ordering,
`WorldHostingBridgeHandoff` Javadoc,
`platform/fabric-26.2/src/main/java/de/lazuli/WorldHostingBridgeHandoff.java:14-18`)
— unchanged by this amendment, just newly relevant to a third bridge
reference.

## UI
- **Enabled state**: "Invite to game" renders exactly like "Open chat"/"Show
  profile" today — full-brightness text (`0xFFFFFFFF`), hover highlight
  (`0x55FFFFFF` row fill), clickable — whenever `isEnabled(2)` is `true`
  (FR-INV1). No new visual treatment beyond the existing enabled/disabled
  `textColor` branch (`FriendContextMenuWidget.renderNow`,
  `platform/fabric-26.2/.../FriendContextMenuWidget.java:108`).
- **Disabled state**: unchanged from today — greyed text (`0xFF808080`), no
  hover highlight, click is a no-op (the existing
  `if (... && isEnabled(index))` guard,
  `FriendContextMenuWidget.mouseClicked:126`) — whenever the local player is
  not hosting.
- **No tooltip is added to this menu row in v1** — `FriendContextMenuWidget`
  has no tooltip mechanism today (unlike `DropdownWidget`'s newly-added
  native tooltip, `platform/ui/specification.md` v2) and adding one is out of
  scope for this amendment; a disabled row simply reads as non-interactive
  the same way "Join game" already does when that friend isn't hosting. If a
  future pass adds hover-tooltip support to `FriendContextMenuWidget`
  generically, "why is this disabled" copy ("You are not hosting a world")
  would be a natural fit then — not designed further here (Future
  Extensions).
- **Send feedback** (FR-INV8): exact placement/copy is a planning decision;
  this document only requires *some* non-silent, non-blocking signal on
  failure.

## Configuration
No new configuration file or field. `config/friends-sidebar.json` is
unchanged; `config/steam-world-hosting.json`'s existing `enabled` flag
(`features/steam-world-hosting/specification.md` Configuration) already
governs whether hosting/the new bridge exists at all — when that feature is
disabled or Steam is unavailable, `WorldInviteSender` is published as a
no-op (`isHosting()` always `false`), so "Invite to game" simply stays
disabled, identical in effect to today's unconditional placeholder.

## Events
No new cross-feature event bus entries (this repo has no generic event bus,
per the same reasoning `features/steam-world-hosting/specification.md`
Events already documents) — enablement is read fresh on every context-menu
render frame (`isEnabled(2)` called per-frame in `renderNow`, per-click in
`mouseClicked`, both already the existing pattern for `isEnabled(3)`/"Join
game"), not pushed via any notification mechanism.

## Networking
- The one new Steamworks call, `SteamFriends.inviteUserToGame(SteamID
  steamIDFriend, String connectString)`, is confirmed present in this repo's
  pinned `steamworks4j-1.10.0.jar` per a prior `WebFetch` of the real
  upstream source at `code-disaster/steamworks4j` tag `1.10.0`
  (`features/friends-sidebar/plan.md:127-143`) — this citation is
  specification-time-confidence only; implementation must still re-confirm
  the exact signature via `javap -p` against this repo's own resolved jar
  before writing the call, per this repo's established
  "`javap`-verify before implementing" discipline
  (`.claude/context/minecraft.md`'s citation-confidence convention, reused
  verbatim by every sibling spec in this feature area).
- Semantically, `InviteUserToGame` triggers Steam's own native invite
  notification UI in the recipient's Steam client/overlay (a toast +
  persistent invite entry) carrying the supplied connect string — this is
  Valve's own "rich-presence-based invite" mechanism, distinct from (a) a
  lobby invite (`ISteamMatchmaking::InviteUserToLobby`, not used — no lobby
  exists in this mod's Steam World Hosting design, Non-goals) and (b) the
  passive "Join Game" button the same connect string already drives via Rich
  Presence (`ISteamFriends::SetRichPresence`, already implemented,
  `HostingLifecycle.start()`). Sending an explicit invite via
  `InviteUserToGame` is additive/active (a proactive push to one specific
  friend) rather than a replacement for the existing passive advertisement
  (which stays running unconditionally while hosting, unaffected by whether
  any explicit invite was ever also sent).
- No new outbound network call beyond the existing local Steam-client IPC
  pattern every other Steamworks call in this codebase already uses (local,
  low-latency IPC to the Steam client process, not raw network I/O at the
  call site — same reasoning `features/steam-world-hosting/specification.md`
  Performance already documents for its own Steamworks calls).

## Persistence
None. No new config field, no save state — identical to every existing
Friends Sidebar/Steam World Hosting action (Open chat, Show profile, Join
game all already persist nothing).

## Compatibility
- Must land identically across all three platform modules
  (`platform/fabric-1.21.11`, `platform/fabric-26.1`, `platform/fabric-26.2`)
  — `FriendContextMenuWidget`'s constructor-parameter change (Public API item
  6) and each module's `FriendsSidebarClientInitializer`/
  `SteamWorldHostingClientInitializer` composition-root wiring change must be
  applied as structural twins, mirroring every prior amendment's own
  three-platform-module discipline in this feature area.
- `SteamFriendsGateway`/`SteamworksSteamFriendsGateway`/
  `NoopSteamFriendsGateway` live in `services/` (one shared copy, not
  per-platform-module) — the new `inviteToGame(...)` method is added once
  there, not duplicated three times.
- `FriendContextMenuWidget`, `FriendsSidebarClientInitializer`, and
  `SteamWorldHostingClientInitializer` are all actively-evolving, shared
  files per this feature area's own established convention — re-run
  `git status`/`git diff` before editing any of them, per the same discipline
  every sibling amendment in this feature area already calls out
  (`features/friends-sidebar/implementation-plan-dropdown-polish.md`'s own
  Risk 6, reused verbatim here).
- No change to the `FriendsSidebarZOrder` enum or the `DROPDOWN_OVERLAY`/
  `CONTEXT_MENU` z-order work in progress (see the sibling dropdown-polish
  plan currently in flight, `features/friends-sidebar/implementation-plan-dropdown-polish.md`)
  — this amendment touches `FriendContextMenuWidget`'s enablement/click logic
  and construction-site parameters only, not its rendering/z-order mechanism.
  Implementers should confirm no merge conflict with that in-flight work
  before landing this amendment (both touch `FriendContextMenuWidget`'s
  constructor region, though for unrelated parameters).

## Performance
Negligible. `isEnabled(2)`'s new hosting-status read is a single
already-in-memory boolean field read (`HostingLifecycle.hosting`, volatile,
already read once per frame for other purposes) — no new per-tick or
per-frame Steamworks call is introduced; the one new Steamworks call
(`inviteUserToGame`) fires exactly once per player click, the same
local-IPC-cost class as every other click-triggered Steamworks call in this
codebase (`activateOverlayChat`/`activateOverlayProfile`, already accepted
as negligible per `features/steam-world-hosting/specification.md`
Performance's own reasoning).

## Future Extensions
- Hover-tooltip support for `FriendContextMenuWidget` rows generally (e.g.
  "You are not hosting a world" on a disabled "Invite to game" row), if a
  future pass gives this widget the same native-tooltip treatment
  `DropdownWidget` recently gained (`platform/ui/specification.md` v2) — not
  designed further here (UI section).
- Per-friend "already invited"/"already in this session" suppression
  (FR-INV2's flagged gap) if a reliable signal for it is ever added (e.g.
  correlating a friend's own Rich Presence against the local hosting session
  once outbound invite state is tracked).
- A visible, in-mod confirmation (beyond FR-INV8's minimal failure-only
  feedback) that an invite was successfully sent, if user testing finds the
  current "menu just closes" behavior on success too silent.
- Bulk/"invite all online friends" action, if single-friend invites prove
  insufficient for real use (Non-goals, this revision).

## Open Questions (require user sign-off before planning)
1. **Bridge contract shape** (Public API item 1): one combined
   `WorldInviteSender` (`isHosting()` + `inviteFriend(...)`) vs. reusing the
   existing `HostedWorldStatus` snapshot plus a separate one-method sender
   interface — both satisfy this spec's Requirements; pick whichever reads
   more consistently with the existing `WorldJoinRequester`/
   `FriendHostingStatusReader` pair.
2. **Where the hosting-status boolean is threaded through**
   (Public API item 3/6): via `FriendSidebarStateMachine.isInviteEnabled(...)`
   gaining a parameter (keeping the state-machine as the single source of
   per-row enablement truth, consistent with how it already owns
   `isOpenChatEnabled`/`isShowProfileEnabled`) vs. `FriendContextMenuWidget`
   reading the new bridge reference directly and bypassing the state machine
   for this one row (the shape "Join game" actually uses today,
   `hostingStatusReader != null && hostingStatusReader.isFriendHosting(...)`,
   bypassing `FriendSidebarStateMachine.isJoinEnabled` entirely — that method
   is dead code today). Recommend matching "Join game"'s actual precedent
   (bypass the state machine) for consistency, but flagging since it means
   `isInviteEnabled(FriendSummary)` would also become effectively
   superseded/dead code the same way `isJoinEnabled` already is.
3. **Failure-feedback exact mechanism/copy** (FR-INV8) — toast, action bar
   message, chat message, or something else; exact copy.
4. **Per-friend suppression scope for v1** (FR-INV2) — confirm the "no
   already-invited/already-in-game suppression in v1" baseline is acceptable,
   or whether a simple heuristic (e.g. suppress if that friend's own Rich
   Presence `"connect"` value already decodes to the local player's own
   `SteamID64`, meaning they already joined) should be in v1 scope rather
   than deferred.
5. **Whether "Invite to game" should also require the friend to currently
   be online** (Steam already prevents inviting an offline friend
   client-side in some surfaces, but this mod's own menu does not currently
   gate on `personaState`/online-ness for any row) — confirm whether v1
   should add an explicit online check or rely on `inviteUserToGame`'s own
   return value (FR-INV8) to surface that failure generically.
