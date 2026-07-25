# Server Join Presence — Specification

## Overview
Extends this mod's existing Steam Rich Presence "connect" advertising
(currently owned exclusively by `features/steam-world-hosting`'s
`HostingLifecycle` for **singleplayer, Steam-P2P-tunneled** worlds,
`features/rich-presence/specification.md`'s own Non-goals: "population of
Steam's `connect` Rich Presence key... remains exclusively
`HostingLifecycle`'s responsibility") to the case this task is actually about:
the local player joins a **real multiplayer server** (a saved server or a
`features/server-browser` listing) as an ordinary client. Today that case sets
no `"connect"` value at all, so a friend has no native "Join Game" button and
no in-mod "Join" action pointing at that server, and nothing in this codebase
counts how many of the local player's Steam friends are presently on a given
server address.

This feature adds that missing half: while connected to a real remote server,
publish a `"connect"` string encoding that server's real `host:port` (not a
`SteamID64` — unlike Steam World Hosting, no P2P tunnel is needed, since a real
server already has a real reachable address; the friend's client simply
performs a normal Minecraft connect, exactly as if they had typed the address
into Direct Connect). It also adds the read side: a scanner over the local
player's Steam friends' own `"connect"` values, so a friend count can be
computed per server address — the piece `features/main-menu`'s `ServersPanel`
(`platform/fabric-*/.../mainmenu/ServersPanel.java`) will consume in a later
pass to show "N friends online" per row.

**Relationship to `features/steam-world-hosting`.** The two features are
mutually exclusive at runtime by construction: a client is either running its
own singleplayer integrated server (`HostingLifecycle` owns `"connect"`) or
connected as a client to someone else's server/dedicated server (this feature
owns `"connect"`) — never both at once, since Minecraft itself only ever has
one active `ClientPacketListener` connection. This feature does not modify
`HostingLifecycle` or its Non-goal in `features/rich-presence/specification.md`;
it adds a second, narrowly-scoped owner of the same Rich Presence key for the
disjoint case that owner's own spec explicitly does not cover. See
Architecture/Compatibility for exactly how the two avoid stomping each other.

**Relationship to `features/rich-presence`.** Unchanged — that feature owns
only the `"status"` key (FR-RP5's explicit "must not overwrite or clear
`connect`") and is not modified by this spec.

**Relationship to `features/friends-sidebar`.** Reuses the same
`SteamFriendsGateway` (`services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`)
seam already shared by `friends-sidebar` and `steam-world-hosting` — no new
`SteamFriends` construction, no new steamworks4j import site beyond
`SteamworksSteamFriendsGateway` (the sole permitted importer, unchanged rule).
The existing "Join game"/"Invite to game" context-menu slots
(`features/steam-world-hosting/specification.md` FR4, `features/friends-sidebar/specification-invite-to-game.md`)
are **not** modified by this spec — those remain wired to Steam World
Hosting's own `WorldJoinRequester`/`FriendHostingStatusReader` bridge for the
singleplayer case only; whether a future pass extends those same menu rows to
also cover "this friend is on a multiplayer server, let me join them there" is
listed under Future Extensions, not built here (see Non-goals).

## Goals
- **Advertise.** While the local player is connected as a client to any real
  multiplayer server (saved-server list entry or a server-browser row —
  anything reached via `ConnectScreen`, not the integrated server), set Rich
  Presence `"connect"` to a value encoding that server's real address, so
  Steam's own friends-list overlay renders a native "Join Game" button for
  friends who can see it — the same zero-extra-UI mechanism Steam World
  Hosting's FR2.1 already established for the singleplayer case, applied here
  to the multiplayer-client case instead.
- **Clear.** Clear `"connect"` the moment that connection ends (disconnect,
  server kick, quit to title, or the player starts/loads a singleplayer world
  instead — at which point `HostingLifecycle` becomes the key's owner again),
  mirroring FR2.2's existing clear-on-stop discipline.
- **Join.** Reuse the existing `SteamFriendsGateway.setJoinRequestedListener`
  callback (already built and used by Steam World Hosting's FR3.1 path 1,
  `services/.../SteamFriendsGateway.java:163-173`) so clicking a friend's
  native "Join Game" button for *this* feature's connect-string shape performs
  a normal Minecraft connect to that server's real address via vanilla's own
  `ConnectScreen`/`Connection.connect(...)` flow — no Steam P2P channel, no
  custom Netty transport, no mixin on `Connection`/`Bootstrap` (the biggest
  simplification relative to Steam World Hosting, see Non-goals).
- **Count.** Provide a plain-JVM-testable query, `int friendsOnServer(String
  hostPort)` (or equivalent), scanning the local player's friends' own
  `"connect"` Rich Presence values (already-fetched pattern, mirrors
  `features/steam-world-hosting/services/HostingPresenceScanner.java`'s own
  per-friend `"connect"`-value poll, applied to this feature's own
  connect-string shape instead of `ConnectStringCodec`'s `steamId64` one) —
  this is the data `features/main-menu`'s `ServersPanel` will need for the
  "how many friends are on this server" display called out in the task, though
  wiring that display itself is scoped as Future Extensions (see Non-goals) —
  this spec's Goal is only to make the number computable and exposed via a
  stable `api`-layer contract, not to build the main-menu UI for it yet.

## Non-goals
- **No main-menu UI changes in this pass.** `ServersPanel`'s actual per-row
  friend-count rendering is explicitly deferred to a follow-up feature/amendment
  once this feature's read-side query exists and is proven — this spec only
  defines and builds the query itself (Goals, "Count"), plus the `api`
  contract `ServersPanel`'s own composition root will eventually consume. This
  mirrors this repo's own precedent of shipping a query/bridge contract one
  pass before the consuming UI wires it up (e.g. Steam World Hosting's FR4
  bridge landed before `friends-sidebar`'s consuming widget change in the same
  plan — here the two are deliberately split across passes instead, since
  `ServersPanel` is a large, actively-changing screen with its own in-flight
  post-launch-fixes work — see `features/main-menu/specification-post-launch-fixes-3.md`,
  untouched by this spec).
- **No Steam P2P tunnel, no custom Netty transport, no `Connection`/`Bootstrap`
  mixin.** Unlike `features/steam-world-hosting`, a real multiplayer server
  already has a real, independently reachable address (that is precisely what
  makes it a server the local player could already join); this feature's join
  operation is a **normal** Minecraft connect, identical in every respect to
  the player typing that address into vanilla's Direct Connect screen. None of
  Steam World Hosting's `SteamAddress`/`SteamNettyChannel`/`SteamServerChannel`/
  handshake-stub-digest machinery is reused, needed, or extended here.
- **No hosting-side gating/friend-only access control.** Steam World Hosting's
  FR1.3 "must be a direct Steam friend to connect" gate exists because *that*
  feature is tunneling a normally-unreachable singleplayer world; a real
  server's own access control (whitelist, online-mode auth, password —
  `features/server-browser`'s existing password-prompt flow,
  `ServerBrowserPasswordPromptScreen`) is unchanged and untouched by this
  feature. This feature only gets a friend from "sees a Join Game button /
  clicks Join in the sidebar" to "a normal connect attempt is issued" — whether
  that attempt then succeeds is entirely the target server's own business, same
  as any other join attempt.
- **No dedicated-server-side code.** Purely client-side, mirroring every other
  Steamworks-touching feature's own established "client-only, real Steam client
  running locally" framing (`services/specification.md:18`).
- **No change to `features/steam-world-hosting` or `features/rich-presence`'s
  own specs/code.** This feature adds a second, disjoint owner of the shared
  `"connect"` key for the case those features explicitly do not cover
  (Overview) — it does not modify `HostingLifecycle`, `ConnectStringCodec`, or
  `RichPresencePublisher`.
- **No extension of the existing "Join game"/"Invite to game" friend
  context-menu rows to this feature's multiplayer-server case in this pass.**
  Those two rows stay wired exactly as `features/steam-world-hosting/specification.md`
  FR4 and `features/friends-sidebar/specification-invite-to-game.md` already
  specify (singleplayer Steam World Hosting only). Whether a friend currently
  on a multiplayer server should also surface a "Join" affordance somewhere in
  the sidebar is listed under Future Extensions — building it now would
  require deciding how to disambiguate "this friend's connect string is a
  Steam-World-Hosting steamId64" from "this friend's connect string is this
  feature's host:port" at every read site, which is exactly the kind of
  two-format-collision design work this spec defers rather than rushes.
- **No relay/NAT-traversal concern of any kind** — a real server's own
  reachability (or lack of it, e.g. behind a firewall with no forwarded port)
  is entirely orthogonal to this feature; it neither improves nor worsens that
  server's reachability, it only makes an already-reachable address
  discoverable/one-click-joinable via Steam for friends who have permission to
  see the local player's Rich Presence.
- **No historical/offline friend-count tracking.** The friend count (Goals,
  "Count") is a live snapshot recomputed on each scan tick, matching every
  other Rich-Presence-derived value in this codebase (`FriendsService`,
  `HostingPresenceScanner`) — no persistence, no time-series.

## Requirements

**Cross-cutting**
- **FR0.1** Depends on the existing `SteamworksService`/`SteamFriendsGateway`
  hand-offs (`SteamworksServiceHandoff.require()`, `SteamFriendsGatewayHandoff.require()`
  — the latter already published by `SteamworksClientInitializer` per the
  Steam World Hosting plan's Decision 1). Never re-initializes Steamworks or
  constructs a second `SteamFriends`.
- **FR0.2** If `SteamAvailability.isSteamAvailable()` is `false`, this feature
  is fully inert: no Rich Presence set, no friend-scan performed, `friendsOnServer(...)`
  always returns `0` — mirrors every existing feature's no-op-when-unavailable
  discipline.
- **FR0.3** A local JSON config (`config/server-join-presence.json`) carries at
  minimum a master `enabled` boolean (default `true`), same flat-under-config-dir
  convention as every sibling feature. When `false`, behaves identically to
  Steam being unavailable (FR0.2).

**Advertising (writing "connect" for a multiplayer-client session)**
- **FR1.1** The moment the local player's client completes connecting to a
  real multiplayer server (saved-server or server-browser entry — any
  `ConnectScreen`-driven connect that is not the integrated/singleplayer
  server), this feature sets Rich Presence `"connect"` to a value encoding
  that server's resolved `host:port` (FR1.4's format), via
  `SteamFriendsGateway.setLocalRichPresence("connect", ...)`.
- **FR1.2** The moment that connection ends for any reason (disconnect screen,
  clean quit-to-title, client crash-recovery back to title), this feature
  clears `"connect"` via `SteamFriendsGateway.clearLocalRichPresence()` —
  **unless** the local player has, in the same moment, started/loaded a
  singleplayer world instead, in which case `HostingLifecycle`'s own FR2.1
  immediately re-sets `"connect"` to its own singleplayer-hosting value; this
  feature must not race `HostingLifecycle` by clearing a value the *other*
  feature just set for a different, newly-started session (see Architecture,
  "Ownership arbitration").
- **FR1.3** If the local player is already in an active Steam World Hosting
  session (`HostingLifecycle`'s own `"connect"` value is currently set) at the
  moment a multiplayer connect somehow also begins, this is not a real
  reachable state in vanilla Minecraft (only one connection exists at a time)
  and needs no special-case handling beyond FR1.1/FR1.2's own natural
  ordering — flagged here only so implementation/planning does not invent
  unneeded arbitration logic for a state that cannot actually occur.
- **FR1.4** The connect-string format is owned by a plain-JVM-testable helper,
  analogous to `ConnectStringCodec` but encoding a `host:port` pair (or a
  `ServerAddress`-shaped string) rather than a `steamId64` — exact literal
  format (e.g. `"+lazuli_connect <host>:<port>"`) is a planning decision, but
  must be trivially distinguishable from Steam World Hosting's own
  `"+lazuli_join <steamId64>"` prefix so a single shared read path (FR3.1,
  friend-count scan) can tell which of the two formats — if either — a given
  friend's raw `"connect"` string is (see Architecture).

**Joining (client-side connect to a friend's server)**
- **FR2.1** Provides one client-side "connect to this server address"
  operation, parameterized only by a resolvable address string, invoked by:
  1. Steam's own `onGameRichPresenceJoinRequested` callback
     (`SteamFriendsGateway.setJoinRequestedListener`, already built) when the
     decoded connect string matches this feature's format (FR1.4) rather than
     Steam World Hosting's — the composition root inspects the prefix first
     and dispatches to whichever feature's connect operation matches,
     ignoring the callback entirely if neither format matches.
  2. Optionally (Future Extensions only, not built this pass, see Non-goals) a
     future in-mod "Join" action.
- **FR2.2** The connect operation reuses vanilla's own
  `ConnectScreen.startConnecting(...)`/`Connection.connect(...)` flow exactly
  as a normal Direct Connect / saved-server click already does in this
  codebase (`ServersPanel`'s own existing connect wiring,
  `platform/fabric-*/.../mainmenu/ServersPanel.java`) — no new networking
  code, no mixin, this is the single largest scope reduction relative to Steam
  World Hosting's FR3.2.
- **FR2.3** If the resolved address cannot be reached (host down, wrong port,
  firewalled), the failure surfaces exactly as any other failed Direct Connect
  attempt already does in vanilla — no new disconnect-reason translation key
  is needed (unlike Steam World Hosting's FR3.3, which needed one because its
  failure mode — "not a direct friend" — has no vanilla equivalent; a
  real-server connect failure already has one).

**Friend count (reading friends' "connect" values)**
- **FR3.1** A scanner (analogous to `HostingPresenceScanner`, its own
  rate-limited per-friend `"connect"`-value poll pattern reused) iterates the
  local player's friends via the shared `SteamFriendsGateway`
  (`friendCount()`/`friendSteamId64At(i)`/`requestFriendRichPresence(id)`/
  `friendRichPresenceValue(id, "connect")`), decodes each with FR1.4's codec,
  and maintains a `Map<String hostPort, Set<Long> friendSteamIds>`-shaped
  cache (or equivalent) refreshed on its own interval (default matching
  `HostingPresenceScanner`'s own, e.g. 5s).
- **FR3.2** Exposes `int friendsOnServer(String hostPort): int` (normalizing
  the input the same way FR1.4's codec normalizes what it encodes, so a caller
  passing a saved server's raw address string gets a correct match regardless
  of minor formatting differences, e.g. trailing default-port `:25565`) —
  this is the query `features/main-menu`'s `ServersPanel` will eventually call
  per row (Non-goals, deferred wiring).
- **FR3.3** A friend whose `"connect"` value decodes as Steam World Hosting's
  own format (`"+lazuli_join <steamId64>"`, a singleplayer session) is never
  counted by `friendsOnServer(...)` — only this feature's own multiplayer
  connect-string shape counts, since "hosting a singleplayer world" and "on
  server X" are different facts and must not be conflated in the count.

## Public API
Illustrative shapes only; final names/signatures are a planning-phase decision.

1. **`api` module** — new package `de.lazuli.api.serverjoinpresence` (or
   colocated under an existing neutral package if planning finds a closer
   home), zero external/Minecraft dependencies:
   - `ServerJoinRequester` — `void joinServer(String hostPort);` (FR2.1/FR2.2),
     the multiplayer-client analogue of Steam World Hosting's
     `WorldJoinRequester`.
   - `FriendServerPresenceReader` — `int friendsOnServer(String hostPort);`
     (FR3.2) — the contract `ServersPanel`'s eventual composition-root wiring
     will consume, published via a per-platform hand-off the same shape as
     `WorldHostingBridgeHandoff`.
2. **`features/server-join-presence/api/`**: `ServerJoinPresenceConfig { boolean
   enabled }` (Configuration).
3. **`features/server-join-presence/services/`**:
   - A connect-string codec (FR1.4) — pure string parsing, zero Minecraft/
     steamworks4j import, the primary unit-test target alongside the scanner.
   - A lifecycle class owning FR1.1/FR1.2's set/clear-on-connect/disconnect
     transitions (constructor-injected `SteamFriendsGateway`, no direct
     `net.minecraft.*` dependency beyond whatever thin signal the platform
     composition root feeds it — e.g. a plain `onConnected(String hostPort)`/
     `onDisconnected()` pair the platform's own connect/disconnect event
     hook calls).
   - The friend-presence scanner (FR3.1-FR3.3), implementing
     `FriendServerPresenceReader`.

## Architecture
Layering (`architecture.md:64-71`): `features/server-join-presence` depends on
`api` and `services` only, never on `features/steam-world-hosting`,
`features/rich-presence`, `features/friends-sidebar`, or `features/main-menu`
directly — all cross-feature wiring is composition-root-only (ADR-0003 shape,
already-established pattern).

```
platform/fabric-<version>/.../ServerJoinPresenceClientInitializer (composition root)
  |-- SteamworksServiceHandoff.require() / SteamFriendsGatewayHandoff.require()
  |-- constructs this feature's lifecycle + scanner (or Noop pair)
  |-- hooks the platform's own client connect/disconnect lifecycle
  |     (e.g. ClientPlayConnectionEvents.JOIN/DISCONNECT, or the same
  |     ClientLifecycleEvents-shaped hook Steam World Hosting/rich-presence
  |     already use, exact choice a planning decision) to call
  |     lifecycle.onConnected(hostPort) / lifecycle.onDisconnected()
  |-- registers the scanner's tick (ClientTickEvents.END_CLIENT_TICK)
  |-- registers the composition-root-level "connect" callback dispatcher
        (inspects the decoded connect-string's format prefix and routes to
        either this feature's ServerJoinRequester or Steam World Hosting's
        WorldJoinRequester, never both) into SteamFriendsGateway.setJoinRequestedListener
```

**Ownership arbitration ("connect" key, two features, one platform-owned
dispatcher).** Since `SteamFriendsGateway.setJoinRequestedListener` accepts
"at most one listener; a later call replaces the earlier one"
(`SteamFriendsGateway.java:168`), only **one** composition-root-level listener
may ever be registered across both features. This spec's Architecture
requires that dispatcher to live at the platform composition root (not inside
either feature), decoding the raw string once and trying each feature's own
codec's `decode(...)` in turn (both return empty/`Optional`-shaped on a
non-matching prefix, FR1.4/`ConnectStringCodec`'s existing convention) —
whichever one successfully decodes wins; if neither does, the callback is
ignored. This is the same "composition root owns the one shared registration"
shape already implicit in `SteamWorldHostingClientInitializer` being the sole
current registrant; this feature's initializer must not itself call
`setJoinRequestedListener` a second time and silently clobber Steam World
Hosting's own registration (or vice versa, depending on `fabric.mod.json`
ordering) — planning must pick one composition-root owner for this combined
dispatcher (most naturally a small new shared class both initializers
contribute to, or whichever initializer is guaranteed to run last), not leave
it to entrypoint-array-order luck.

Symmetrically, **only one feature may hold `"connect"` at a time** (FR1.2/FR1.3)
— since the two conditions (singleplayer world loaded vs. multiplayer client
connected) are mutually exclusive in vanilla Minecraft, no runtime arbitration
object is needed beyond each feature independently setting/clearing on its own
begin/end events; this is flagged as "provably safe by game-state exclusivity,"
not left as an unresolved race, but implementation should still avoid the
*specific* ordering bug of one feature's `clear` racing the other's `set` in
the same tick (FR1.2's explicit carve-out).

## UI
No new screen. No changes to `ServersPanel` or the friend context-menu widgets
in this pass (Non-goals) — this feature is plumbing-only; the eventual
`ServersPanel` friend-count display and any future context-menu "Join" wiring
for a friend-on-a-server are both Future Extensions.

## Configuration
`config/server-join-presence.json`:
```json
{
  "enabled": true
}
```
- `enabled` (boolean, default `true`) — master switch (FR0.3); no effect
  unless `SteamAvailability.isSteamAvailable()`.

## Events
No generic event bus (repo has none, per established convention). State
transitions this feature cares about (connect/disconnect to a multiplayer
server) are observed via whichever client-lifecycle/connection Fabric event
this platform's Minecraft version exposes — exact hook a planning-phase
`javap`/API-surface check, not fixed here (Compatibility flags this as the
one concrete unknown carried forward, mirroring Steam World Hosting's own
"exact mixin targets not yet javap-confirmed" precedent, though this feature's
own surface is expected to be a plain Fabric API event rather than a mixin,
since connect/disconnect lifecycle hooks already exist in Fabric API for other
purposes in this repo).

## Networking
None beyond the existing `SteamFriendsGateway.setLocalRichPresence`/
`friendRichPresenceValue` Steamworks IPC calls (no new native surface) and
vanilla's own unmodified `Connection`/`ConnectScreen` connect flow (FR2.2) —
this is the defining simplification relative to `features/steam-world-hosting`
(Non-goals): no P2P transport, no custom Netty channel, no handshake changes.

## Persistence
None — in-memory only, recomputed fresh each session/scan tick, same framing
as every sibling Steamworks-touching feature.

## Compatibility
- Must be implemented in each of `platform/fabric-1.21.11`, `platform/fabric-26.1`,
  `platform/fabric-26.2`, per this repo's standing three-platform-module rule.
- The one concrete cross-version unknown: the exact Fabric API event (or
  vanilla hook) signaling "client just finished connecting to a remote
  server" / "client just disconnected," and how to read the connected
  server's resolved `host:port` from it, must be independently confirmed per
  platform module (some of this repo's other features already observe
  connection-adjacent state — e.g. `ServersPanel`'s own existing
  `ConnectScreen`/`ServerData` usage — so this is expected to be a
  low-risk, already-precedented lookup rather than a novel mixin, but is
  flagged for planning to confirm before implementation, not assumed here).
- No P2P/Netty-layer cross-version risk exists at all (Networking) — the
  entire risk surface Steam World Hosting's own Compatibility section
  carries (six Minecraft/Netty classes needing `javap` confirmation) does not
  apply to this feature.

## Performance
- The friend-presence scanner (FR3.1) is the same shape/cost as
  `HostingPresenceScanner`'s own already-accepted per-tick-gated,
  every-few-seconds friend sweep — no new performance concern beyond what
  that precedent already established.
- Setting/clearing one Rich Presence key on connect/disconnect (FR1.1/FR1.2)
  is a single, infrequent (session-boundary-only, not per-tick) native call,
  negligible cost.

## Future Extensions
- Wiring `friendsOnServer(...)` (FR3.2) into `features/main-menu`'s
  `ServersPanel` per-row display (Goals/Non-goals) — the task's own
  stated eventual goal, deliberately not built in this pass.
- Extending the friend context-menu's "Join game" row (or a new row) to also
  cover "this friend is currently on multiplayer server X, join them there" —
  requires resolving the two-connect-string-format read-site question flagged
  in Non-goals.
- A "Recent servers my friends are on" surface, aggregating FR3.1's scan
  results across all known servers rather than one address at a time.
- Sending an explicit Steam invite (`SteamFriendsGateway.inviteToGame`,
  already built for Steam World Hosting's "Invite to game" slot) for this
  feature's own connect-string shape, mirroring
  `features/friends-sidebar/specification-invite-to-game.md`'s pattern but for
  a multiplayer-server session instead of a singleplayer-hosted one.
