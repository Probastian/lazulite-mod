# Steam World Hosting — Feature Specification

## Overview
Adds `features/steam-world-hosting`, which makes every singleplayer world the
player opens automatically joinable by their Steam friends, tunneled entirely
over Steam's peer-to-peer networking rather than requiring a forwarded port or
a manual "Open to LAN" click. Under the hood this reuses Minecraft's own
integrated-server Netty pipeline (the same `ServerConnectionListener`/
`Connection` machinery "Open to LAN" already drives) but swaps the transport
for a Steam P2P (`ISteamNetworking`) channel, so a joining friend connects the
same way they would to a LAN world, without any socket ever being exposed to
the internet.

This is a client-only, integrated-server feature (singleplayer only, never a
dedicated server — mirrors `services/specification.md:18`'s "purely
client-side, locally-running-Steam-client" framing already established for
Steam Cloud Sync and Friends Sidebar). It is the third feature in this repo
built on the shared `SteamworksService` bootstrap (`services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`),
and the first to touch Minecraft's own networking stack (`Connection`,
`ServerConnectionListener`) rather than only rendering/config/file I/O.

**Prior art.** A working, single-Minecraft-version (Mojang-mapped only)
prototype of this exact mechanism exists at
`C:\Users\duckb\Documents\Coding\Minecraft Modding\steamshare_client_mod`
(package `com.example.steamshare`, mod id `steamshare`), later broken by an
in-progress refactor. This spec reverse-engineers that prototype's proven
design (Architecture, Networking) and explicitly calls out where this
version's requirements deliberately diverge from it (Non-goals, Requirements,
Open Questions), rather than re-deriving the mechanism from scratch. That
prototype is **not** part of this repo and is never a build dependency — only
a design reference read during this specification/planning pass.

## Goals
- Every time an `IntegratedServer` is created for a singleplayer world (world
  load, not "Open to LAN"), automatically bootstrap the same Netty
  `childHandler`/`EventLoopGroup` pipeline `ServerConnectionListener` already
  builds for real TCP, and stand up a Steam P2P listener alongside it — no
  manual toggle, no separate "publish" action (see Non-goals/Open Questions
  for the explicit v1 simplification this encodes).
- Let a Steam friend join that world in either of two ways, both terminating
  in the same underlying connect path:
  1. **This mod's Friends Sidebar** (`features/friends-sidebar`) — a new
     "Join World" action reachable from a friend's row/context menu
     (`FriendContextMenuWidget`, `platform/fabric-*/.../friends/FriendContextMenuWidget.java`),
     enabled only when that friend is currently reported as hosting a
     Lazuli-tunneled world.
  2. **Steam's own native friends-list overlay "Join Game" button** — driven
     by Rich Presence's Valve-reserved `"connect"` key
     (`ISteamFriends::SetRichPresence`/`GetFriendRichPresence`), exactly the
     mechanism the prior prototype already proved works
     (`SteamManager.buildConnectString`/`onGameRichPresenceJoinRequested`,
     `steamshare_client_mod/.../steam/SteamManager.java:266-320`).
- Keep the actual Minecraft server/session logic completely unaware that a
  connection came from Steam P2P rather than TCP — the transport swap must be
  invisible above the Netty `Channel` layer, the same invisibility the prior
  prototype already achieved (`ConnectionMixin`, `SteamNettyChannel`).
- Gate who may join to the host's real Steam friends by default (no anonymous
  "everybody" mode in v1 — see Requirements/Open Questions).
- Keep all decision logic that does not require `net.minecraft.*` or Netty
  types (e.g. "is this remote SteamID allowed to join," connect-string
  parsing) in a plain-JVM-testable class, per this repo's existing NFR1
  precedent (`features/friends-sidebar/specification.md:NFR1`,
  `features/steam-cloud-sync/specification.md:NFR1`) — the Netty/mixin glue
  itself is inherently not unit-testable and is scoped accordingly (see
  Compatibility/Performance's "verification gap" note).

## Non-goals
- **No manual join-policy UI / no policy toggle in v1.** The prior prototype
  exposed a cycling `JoinPolicy` (`NOBODY`/`FRIENDS`/`FRIENDS_OF_FRIENDS`/
  `EVERYBODY`, `steamshare_client_mod/.../steam/JoinPolicy.java`) via a
  dedicated `SteamFriendListScreen`. v1 of this feature has **no such screen
  and no policy enum** — hosting is unconditional and always-on the instant a
  world loads, gated only by the fixed rule "must be a direct Steam friend"
  (see Requirements FR2). This is a deliberate simplification per the task's
  own instruction and is flagged again under Open Questions for explicit
  sign-off, since it removes the host's ability to ever say "nobody may join
  right now" short of quitting the world.
- **No Friends-of-Friends (FoF) relay/token mechanism.** The prototype's most
  complex piece — propagating a short-lived join token through a friend's own
  Rich Presence so *their* friends could join transitively
  (`SteamManager.fofToken`/`SteamServerChannel.acceptPeer`'s async
  ch1-token-verification thread) — is out of scope for v1. Only the host's
  own direct Steam friends may join.
- **No "Everybody"/direct-`steamid:`-address join mode and no Direct Connect
  UI changes.** The prototype's `ServerAddressMixin` also made
  `"steamid:<id>"` a valid, greyed-in address in vanilla's own Direct Connect
  screen. v1 does not touch Direct Connect; joining is only reachable via the
  two mechanisms in Goals (Friends Sidebar action, native overlay "Join
  Game"). A raw connect-string entry point may still exist internally (see
  Architecture) as the common plumbing both join paths funnel through, but no
  new player-facing UI exposes it directly.
- **No dedicated-server support, whitelist/ban management, or op-bypass
  mixins.** The prototype's `PlayerListMixin`/`BanPlayerCommandsMixin`/etc.
  (dedicated-server-style moderation commands usable from a LAN-opened
  singleplayer world) are not reproduced — this feature targets the
  integrated server's default singleplayer trust model only (the world owner
  is already the only operator; no additional moderation surface is added).
- **No relay/fallback path when direct Steam P2P NAT traversal fails.** Unlike
  `e4mc` (`ANALYSIS_TLDR.md`/`ANALYSIS_DETAILED.md` — a *different*,
  QUIC-relay-based reference mod read only for contrast, not reused), there is
  no central relay server. Steam's own P2P layer (`ISteamNetworking`, or
  whichever surface planning confirms, see Open Questions) already performs
  its own NAT traversal/relay-through-Steam-datagram-relay internally per
  Valve's docs; this feature does not add a second-tier fallback beyond
  whatever that underlying API already does.
- **No cross-platform-module shared implementation beyond `api`/`services`.**
  Per this repo's Dependency Rules (`architecture.md:64-71`, "Forbidden:
  Feature → Feature"), the Netty/mixin glue is Minecraft-touching and
  therefore lives in each of the three `platform/fabric-*` modules
  (Compatibility), not shared as one common-Java implementation the way
  `SteamworksService` is — see Architecture for exactly what is/isn't
  shareable.
- **No live in-game connectivity testing during this workflow's verification
  phase.** The agent running verification cannot launch/observe a real
  Minecraft client session in this environment. Verification is limited to
  compilation, static analysis (`javap` against resolved jars, per this
  repo's established discipline), and unit tests of the plain-JVM logic
  (join-policy predicate, connect-string parsing). Actual Steam P2P
  connectivity, real NAT traversal, and "does a friend's client actually
  connect and play" are **not** verified by this iteration's own workflow —
  this is an accepted, known gap for v1, not a surprise (see also
  Compatibility, Performance).

## Requirements

**Cross-cutting**
- **FR0.1** Depends on the same shared `SteamworksService`/`SteamAvailability`
  bootstrap every other Steamworks-touching feature already depends on
  (`SteamworksServiceHandoff.require()`, the same per-platform-module
  hand-off pattern `FriendsSidebarClientInitializer`/
  `SteamCloudSyncClientInitializer` already use,
  `platform/fabric-26.2/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java:39`).
  Never re-initializes Steamworks itself.
- **FR0.2** If `SteamAvailability.isSteamAvailable()` is `false`, no hosting
  pipeline is bootstrapped, no Steam networking object is constructed, and no
  Rich Presence is set — the integrated server still starts and functions
  exactly as vanilla, with zero behavioral change, mirroring every existing
  feature's own no-op-when-unavailable discipline
  (`features/friends-sidebar/specification.md:FR0.2`).
- **FR0.3** A local JSON config (`config/steam-world-hosting.json`) carries at
  minimum a master `enabled` boolean (default `true`), following the same
  flat-under-config-dir convention as every other feature
  (`features/friends-sidebar/specification.md:FR0.3`). When `false`, behaves
  identically to Steam being unavailable (FR0.2) — the world hosts normally
  but with no Steam tunnel.

**Hosting lifecycle (business logic, `features/steam-world-hosting/services`)**
- **FR1.1** On every singleplayer world load (integrated server start,
  analogous to the prototype's `IntegratedServerMixin.onInitServer`,
  `steamshare_client_mod/.../client/IntegratedServerMixin.java:23-40`), the
  platform Version Adapter bootstraps Minecraft's own Netty
  `childHandler`/`EventLoopGroup` pair by invoking the integrated server's
  connection listener exactly as "Open to LAN" would, but bound to an
  ephemeral, never-advertised local port (port `0`) — this port is
  plumbing-only and is never shown to the player or exposed on the network;
  it exists solely so the real Netty pipeline object graph is captured for
  reuse over the Steam channel.
- **FR1.2** Immediately after FR1.1 captures that pipeline, this feature's own
  hosting service starts a Steam P2P listener bound to the local player's own
  `SteamID`, unconditionally — no manual "start hosting" action exists in v1
  (Non-goals). Hosting stops automatically when the integrated server itself
  stops (world unload/quit to title), mirroring the prototype's
  `onServerStopped`/`stop()` symmetry (`SteamManager.java:435-447`,
  `SteamSession.stop()`).
- **FR1.3** A remote Steam P2P connection attempt (`onP2PSessionRequest`,
  Valve's own callback for an inbound session request) is accepted only if
  the requesting `SteamID` is a **direct Steam friend** of the local player
  (`SteamFriends.getFriendRelationship(id) == Friend`, the same check shape
  as the prototype's `SteamManager.canJoin`/`isSteamFriend`,
  `SteamManager.java:329-353`) — this fixed rule replaces the prototype's
  full `JoinPolicy` enum (Non-goals). A non-friend's connection attempt is
  rejected (P2P session closed) without ever reaching the Minecraft
  handshake.
- **FR1.4** While at least one remote peer is connected, the integrated
  server must not pause when the game window loses focus/the pause menu
  opens — reuses the same mechanism the prototype already proved
  (`IntegratedServer.isPublished()` override returning `true` whenever a
  Steam session is active with connected peers,
  `IntegratedServerMixin.java:59-68`), independent of whether "Open to LAN"
  was ever pressed.
- **FR1.5** The hosting service exposes, to the platform composition root, at
  minimum: whether it is currently able to accept connections (Steam
  available + world loaded), the local player's own `SteamID64`, and a
  predicate `boolean canJoin(long friendSteamId64)` implementing FR1.3 —
  this predicate is the one piece of this feature's decision logic required
  to be plain-JVM-testable (Goals/NFR1), taking a friend-relationship lookup
  as an injected dependency rather than calling `SteamFriends` directly, the
  same seam shape `FriendSidebarStateMachine` already uses relative to
  `FriendsService` (`features/friends-sidebar/specification.md`'s
  Architecture section).

**Advertising the host (Rich Presence)**
- **FR2.1** While hosting (FR1.2) and at least the world is loaded, sets the
  Valve-reserved Rich Presence `"connect"` key
  (`ISteamFriends::SetRichPresence`) to a connect string encoding the host's
  own `SteamID64`, so Steam's own friends-list overlay renders a native "Join
  Game" button for every friend who can see that Rich Presence value — this
  is what makes Goal 2's native-overlay join path work with **zero**
  additional UI code in this mod; Steam draws that button itself once
  `"connect"` is non-empty (confirmed mechanism, prototype
  `SteamManager.onSessionStarted`/`buildConnectString`,
  `SteamManager.java:266-277,403-416`).
- **FR2.2** Clears the `"connect"` Rich Presence key (empty string) as soon as
  hosting stops (world unload/quit), so the native "Join Game" button
  disappears for friends immediately rather than pointing at a dead session —
  mirrors the prototype's `onSessionStopped`/`onServerStopped`
  (`SteamManager.java:418-447`).
- **FR2.3** The connect-string format itself (parsing/building) is owned by a
  plain-JVM-testable helper, not hand-rolled string slicing scattered across
  callers — the prototype's own format (`"+steamshare_join <steamId64>"`,
  `SteamManager.buildConnectString`) is a reasonable starting shape but the
  exact literal is a planning decision.

**Joining (client-side connect)**
- **FR3.1** Provides one client-side "connect to this Steam host" operation,
  parameterized only by the host's `SteamID64` (no UI dependency), that both
  join paths in Goals ultimately call:
  1. Steam's own `onGameRichPresenceJoinRequested`/`onGameLobbyJoinRequested`
     callback (fired when the player clicks "Join Game" in the native
     overlay) extracts the host `SteamID64` from the connect string (FR2.3)
     and invokes this operation.
  2. The Friends Sidebar's new "Join World" action (FR4) invokes the same
     operation directly with the target friend's already-known `steamId64`
     — no Rich Presence string round-trip needed for this path, since the
     sidebar already holds the `FriendSummary`.
- **FR3.2** The connect operation opens a real Minecraft connection whose
  underlying transport is the Steam P2P channel to that `SteamID`, reusing
  Minecraft's own `ConnectScreen`/`Connection.connect(...)` flow so all
  normal client-side connecting UI (progress screen, disconnect screens,
  cancel button) behaves identically to a normal server connect — the
  prototype's `SteamShareModClient.connectToSteamPeer` +
  `ConnectionMixin`'s bootstrap-hijack achieves exactly this
  (`SteamShareModClient.java:257-288`, `ConnectionMixin.java:34-157`) and is
  the reused mechanism (see Architecture/Networking).
- **FR3.3** If the joining player is not a direct Steam friend of the host
  (FR1.3 rejects them at the host), the client-side connect attempt fails
  with a clear, translated disconnect reason (not a generic "Internal
  Exception") — mirrors the prototype's clean-disconnect wiring
  (`SteamAmbientSession.triggerDisconnect`/`setDisconnectCallback`,
  `SteamAmbientSession.java:104-131`).

**Friends Sidebar integration**
- **FR4.1** Adds a **"Join World"** action, reachable from a friend's row
  context menu (`FriendContextMenuWidget`,
  `platform/fabric-*/.../friends/FriendContextMenuWidget.java`), enabled only
  when that friend is currently detected as hosting a Lazuli-tunneled world
  (FR4.2). This action is wired at the platform composition root, not inside
  `features/friends-sidebar` itself or inside `features/steam-world-hosting`
  itself — see Architecture's "cross-feature bridging" subsection, which
  reuses the pattern already accepted in
  `docs/adr/0003-cloudsyncable-cross-feature-bridging-via-api-contracts.md`.
  Whether this reuses the existing (currently-always-disabled-placeholder)
  "Join game" menu slot (`FriendContextMenuWidget`'s fourth option,
  `features/friends-sidebar/specification.md:FR3.4`) or is a visually
  distinct fifth "Join World" entry is an **open question for sign-off**
  (Open Questions) — both are compatible with the existing widget's
  `LABELS`/`isEnabled(index)` shape
  (`platform/fabric-26.2/.../FriendContextMenuWidget.java:32,60-72`).

  **RESOLVED (Open Questions item 5):** the user has approved reusing the
  existing disabled "Join game" context-menu slot exactly, not a new fifth
  entry — see Open Questions item 5 below.
- **FR4.2** Detecting "is this friend currently hosting" reads that friend's
  Rich Presence `"connect"` value (already fetched every refresh sweep by
  `FriendsService`'s existing `requestFriendRichPresence`/
  `getFriendRichPresence` calls for FR1.7 of the Friends Sidebar spec,
  `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java:146-156`)
  and checks whether it matches this feature's connect-string format
  (FR2.3) — this repurposes data `FriendsService` already collects for a
  different purpose (status text), it does not require a second Rich
  Presence poll. The exact wiring of "whose logic parses that string" is an
  Architecture decision (cross-feature bridging), not fixed further here.
- **FR4.3** Clicking "Join World" invokes FR3.1's connect operation with that
  friend's `steamId64` — no address string is typed by the player.

## Public API

Illustrative shapes only; final names/signatures are a planning-phase
decision (same convention as `features/hello-world-main-menu/specification.md:53`).

1. **`api` module** — new package `de.lazuli.api.worldhosting`, zero
   external/Minecraft dependencies, mirroring `SteamAvailability`'s own
   "stable, steamworks4j-free contract" rationale
   (`api/.../steamworks/SteamAvailability.java:14-17`):
   - `HostedWorldStatus { boolean hosting, long localSteamId64 }` (or
     equivalent) — a plain snapshot the platform composition root can query
     to decide whether to advertise Rich Presence / enable UI affordances.
   - `WorldJoinRequester` (interface) — `void joinHostedWorld(long hostSteamId64);`
     — the "Platform API" shape the Friends Sidebar's composition-root
     wiring calls into (FR4.1/FR4.3), analogous to `FriendSidebarHook`'s role
     for the Friends Sidebar itself (`api/.../friends/FriendSidebarHook.java`).
     Defined here (not in `de.lazuli.api.friends`) because this feature owns
     the join operation itself; Friends Sidebar's own `api` package is not
     modified to add this — the bridge is composition-root wiring only (see
     Architecture), consistent with ADR-0003's shape ("the contract... is
     defined... by the Feature that will *consume*" the bridged call — here,
     `steam-world-hosting` is naturally the contract owner since it is the
     one performing the join, not `friends-sidebar`).
   - Whether a `HostingPresenceReader`-shaped contract (parsing a friend's
     Rich Presence string to decide "is this friend hosting," FR4.2) also
     belongs in `api` so `friends-sidebar`'s composition-root glue can call
     it without a direct Feature→Feature import is a planning decision
     flagged in Architecture.

2. **`features/steam-world-hosting/api/`**:
   - `SteamWorldHostingConfig { boolean enabled }` — this feature's own local
     settings (Configuration).

3. **`features/steam-world-hosting/services/`**:
   - A hosting-lifecycle service (owns FR1.1-FR1.5's state machine glue,
     minus the actual Netty/mixin plumbing which cannot live below
     `platform/`) — exposes `canJoin(long friendSteamId64): boolean` (FR1.5)
     as a small, plain-JVM-testable class taking a friend-relationship
     lookup as a constructor-injected function rather than calling
     `SteamFriends` directly.
   - A connect-string encode/decode helper (FR2.3/FR4.2) — pure string
     parsing, fully unit-testable with no Steamworks/Minecraft dependency.
   - Whichever class ends up being "the one importing
     `com.codedisaster.steamworks.*`" for this feature (Rich Presence
     set/read, `SteamNetworking` P2P calls) follows the same "sole Steamworks
     seam" discipline `FriendsService` already established
     (`FriendsService.java:26-33`) — see Architecture for why this may need
     to be a **shared** seam with `friends-sidebar`'s existing one rather
     than a second, independent `SteamFriends` construction (Open Questions).

4. **`platform/fabric-<version>/...`**: per platform module, the
   Netty/mixin glue reproducing the prototype's design (Architecture):
   a `SteamAddress`/`SteamNettyChannel`/`SteamServerChannel`/hosting-session
   class (analogous to `SteamSession`/`SteamAmbientSession`), the mixin set
   (Networking), and one client composition-root entrypoint wiring this
   feature's service into `SteamworksServiceHandoff`, the existing Friends
   Sidebar's action-listener plumbing (FR4), and Rich Presence updates
   (FR2). This is **not** shared code between platform modules beyond `api`/
   `services` — see Compatibility for why each of the three modules needs its
   own copy.

## Architecture

Layering (per `architecture.md:64-71`): `features/steam-world-hosting`
depends on `api` and `services` only, never on `features/friends-sidebar`
directly. `platform/fabric-<version>` depends on `api` for business logic,
plus the ADR-0001/ADR-0002/ADR-0003-licensed composition-root exception for
constructing concrete Feature classes and bridging between them.

```
platform/fabric-<version>/.../SteamWorldHostingClientInitializer  (composition root)
  |-- constructs features/steam-world-hosting's hosting service
  |     (via SteamworksServiceHandoff.require(), same pattern as every
  |      other Steamworks-touching feature's initializer)
  |-- registers the world-load hook (IntegratedServerMixin-equivalent,
  |     platform/.../mixin/) that bootstraps the Netty pipeline + starts
  |     the Steam P2P listener every world load
  |-- updates Rich Presence "connect" on hosting start/stop (FR2.1/FR2.2)
  |-- bridges into features/friends-sidebar's existing FriendActionListener
        wiring (composition-root-only cross-feature bridge, ADR-0003 shape):
        constructs a small adapter whose onJoin(steamId64) calls this
        feature's WorldJoinRequester.joinHostedWorld(steamId64), and whose
        "is this friend hosting" check (FR4.2) reads the same Rich Presence
        value FriendsService already fetches
```

**Cross-feature bridging (Friends Sidebar ↔ Steam World Hosting).** Per
`architecture.md`'s "Forbidden: Feature → Feature" and the ADR-0003 precedent
(the same shape Steam Cloud Sync already uses to bridge in
`hello-world-main-menu`'s config via `CloudSyncable`), neither feature
imports the other's classes. Two composition-root-owned bridge points are
needed:
1. **Friends Sidebar → World Hosting** ("Join World" click, FR4.1/FR4.3):
   the platform composition root constructs the concrete
   `FriendActionListener` (or a decorator around `FriendsService`'s own
   implementation) that `FabricFriendsSidebarInjector` wires into
   `FriendContextMenuWidget`, and that listener's `onJoin`/new-menu-slot
   handler calls `WorldJoinRequester.joinHostedWorld(...)` on this feature's
   service. This is the smaller, already-ADR-0003-covered shape ("Platform
   constructs one adapter bridging Feature A's action into Feature B's `api`
   contract").
2. **World Hosting → Friends Sidebar** ("is this friend hosting," FR4.2): the
   inverse direction — this feature's connect-string parser needs a
   `String` (that friend's Rich Presence `"connect"` value) that
   `FriendsService` already fetches for its own purposes
   (`FriendsService.java:146-156`, `richPresenceStatus(long)`). Rather than
   `steam-world-hosting` importing `FriendsService`, the composition root
   reads `richPresenceStatus(long)` (or a lower-level raw Rich Presence
   accessor if `richPresenceStatus` turns out to already be
   status-text-shaped rather than raw-value-shaped — a planning-time check)
   and passes the string into this feature's own parser. No new Steamworks
   call is added purely for this — it reuses data already being fetched.

**Steamworks-seam consolidation (open architectural question, not decided
here).** `FriendsService` already declares itself "the **sole** class in this
feature importing `com.codedisaster.steamworks.*`"
(`FriendsService.java:26-33`) and separately constructs its own `SteamFriends`
instance for Friends Sidebar's needs. This feature also needs `SteamFriends`
(Rich Presence set/read, `activateGameOverlayToUser` is not needed here but
the same object type is) **and** a `SteamNetworking` instance (P2P). Two
options exist and planning must pick one (Open Questions):
- **(a)** This feature constructs its own, independent second `SteamFriends`
  instance (steamworks4j/native Steam API behavior with multiple concurrent
  `SteamFriends` wrapper objects in one process needs to be confirmed safe —
  not yet verified against this repo's own resolved
  `steamworks4j-1.10.0.jar`).
- **(b)** Per the `services/` "graduate-on-second-use" rule
  (`architecture.md:26` — "only when a *second* feature needs the same
  capability does it get extracted into `services/`... with an ADR recording
  the extraction"), a shared `SteamFriends`-owning wrapper is extracted into
  `services/` now that a second feature needs it, and both
  `FriendsService`/this feature's own service consume that shared instance
  instead of each constructing their own. This is the architecturally
  "correct per this repo's own stated rule" choice but is a larger, riskier
  change (touching the already-shipped Friends Sidebar) than this spec
  should silently decide on its author's behalf.

**RESOLVED (Open Questions item 4):** the user approved option (b) — a
shared, `SteamFriends`-owning wrapper is to be extracted per the
graduate-on-second-use rule now that this feature is a second consumer.
Note: the user's own wording said extract it "into `api/`", while this
spec's Architecture section (above) and this repo's existing precedent
(`SteamworksService` living in `services/`, alongside which this concrete,
Steamworks-touching wrapper would naturally sit) both point to `services/`
as the layer for this kind of extraction, not `api/` (which per this spec's
own "Public API" section is meant to stay steamworks4j-free). This
single-word discrepancy (`api/` vs `services/`) is **not** resolved by this
annotation and is flagged for the planning phase to settle explicitly against
actual repo convention before extracting anything — see Open Questions
item 4 for the full note.

**Netty/mixin glue placement.** Everything below the `Connection`/
`ServerConnectionListener` layer (custom `Channel`/`ServerChannel`
implementations, the P2P read/write pump, all mixins) must live under each
`platform/fabric-<version>/.../mixins/` and a platform-module-local package
(e.g. `de.lazuli.worldhosting`), never in `features/steam-world-hosting`
itself — this is not shareable common code the way `SteamworksService` is,
because it directly subclasses/targets `net.minecraft.*` and Netty
`Channel`/`AbstractChannel` types tied to that version's own Minecraft/Netty
jar (`feature-guidelines.md:16-18`'s "mixins/ is permanently a placeholder"
rule). Each of the three platform modules needs its **own** copy of: a
`SteamAddress` (`SocketAddress` subclass carrying a raw `SteamID64`), a
`SteamNettyChannel`/`SteamServerChannel` pair (Netty `AbstractChannel`/
`AbstractServerChannel` implementations backed by `SteamNetworking`
send/receive calls), a client-side ambient session object (owns the
client-side `SteamNetworking` instance + poller thread), and the mixin set
(Networking section) — this is a large amount of near-identical code
duplicated three times, which is an accepted cost of this repo's own
"Platform contains version glue" rule (`architecture.md:44-62`), not an
oversight; planning may still look for ways to share source via a Gradle
source-set trick, but that is a planning-level concern, not a spec
requirement.

## UI
- **Friends Sidebar "Join World" action** (FR4.1) — see
  `platform/fabric-*/.../friends/FriendContextMenuWidget.java` for the
  existing four-option menu shape this either extends (fifth option) or
  reuses (existing "Join game" slot, FR4.1's open question). No new standalone
  screen is introduced by this feature.

  **RESOLVED (Open Questions item 5):** the user has confirmed "Join World"
  reuses the existing disabled "Join game" context-menu slot exactly (not a
  new fifth entry) — the "either extends... or reuses" language above is
  superseded by this decision; see Open Questions item 5.
- **No new dedicated screen.** Unlike the prototype's `SteamFriendListScreen`
  (a full policy-toggle/manual-connect screen reachable from the Multiplayer
  screen and pause menu), v1 has no equivalent — hosting is unconditional
  (Non-goals) and joining is reachable only via the Friends Sidebar and the
  native Steam overlay (Goals), so no in-mod screen is needed to expose a
  policy or a manual SteamID entry box. If a future extension reintroduces a
  policy toggle, it would most naturally reuse the Friends Sidebar's existing
  screen-injection precedent rather than a new standalone screen — not
  designed further here.
- **Disconnect messaging** (FR3.3) reuses vanilla's own `DisconnectedScreen`
  with a translated reason, the same mechanism the prototype already
  validated (`SteamAmbientSession`'s clean-disconnect callback wiring) —  no
  new screen, only new translation keys.

## Configuration
`config/steam-world-hosting.json`, flat under the config directory (same
convention as `friends-sidebar.json`/`steam-cloud-sync`'s own config file):
```json
{
  "enabled": true
}
```
- `enabled` (boolean, default `true`) — master switch (FR0.3); has no effect
  unless `SteamAvailability.isSteamAvailable()`. No per-friend allow-list, no
  join-policy field, and no port/connection-tuning fields in v1 (Non-goals) —
  if planning finds a genuine need for one (e.g. a connect-string-format
  version field for forward compatibility), that is an additive, non-load-bearing
  extension of this same file, not a redesign.

## Events
No new cross-feature event bus entries — this repo has no generic event bus
yet (`architecture.md`'s Services list mentions "Event Bus" as a category,
not something already built; per the graduate-on-second-use rule, this
feature does not introduce one speculatively). State changes this feature
cares about (world load/unload, friend join/leave) are observed directly via
Fabric's own lifecycle/tick events at the platform composition-root level
(`ClientLifecycleEvents`, `ClientTickEvents`, `ServerLifecycleEvents`, exact
choice a planning decision), the same pattern every other feature in this
repo already uses instead of a custom event bus.

## Networking

**Transport surface (open question, flagged for sign-off — see Open
Questions).** The prior prototype tunnels traffic using steamworks4j's
`com.codedisaster.steamworks.SteamNetworking` wrapper around Valve's
**legacy** `ISteamNetworking` P2P API (`SendP2PPacket`/`IsP2PPacketAvailable`/
`ReadP2PPacket`/`AcceptP2PSessionWithUser`/`CloseP2PSessionWithUser`,
channel-numbered datagrams — `SteamManager.java`, `SteamSession.java`,
`SteamNettyChannel.java`, `SteamServerChannel.java` throughout). This is
**not** Valve's newer `ISteamNetworkingSockets`/`ISteamNetworkingMessages`
surface (the API Valve has been steering developers toward since
~2020-2021). Whether `steamworks4j-1.10.0.jar` (the exact version this repo
already vendors, confirmed present at
`platform/fabric-26.2/build/processIncludeJars/steamworks4j-1.10.0.jar`) also
exposes the newer classes, and whether reusing the prototype's proven legacy
`SteamNetworking` surface (least risk, already demonstrated working
end-to-end) versus targeting the newer API (more future-proof, but
undemonstrated in this repo and requiring new design work) is planning's
call, must be resolved by a real `javap -p` pass against that jar as the
first concrete implementation step, per this repo's own established
"`javap`-verify before implementing" discipline
(`.claude/context/minecraft.md:19-30`, and the friends-sidebar spec's own
explicit citation-confidence framing). **This spec's own default
recommendation is to reuse the legacy `SteamNetworking` surface exactly as
the prototype did**, since it is the only piece of this whole design with a
real, working precedent — but this is stated as a recommendation for
sign-off, not a fixed decision (Open Questions item 3).

**RESOLVED (Open Questions item 3):** the user has confirmed the required
process — perform the `javap -p` (or equivalent) verification pass against
the vendored `steamworks4j-1.10.0.jar` first; if the newer
`ISteamNetworkingSockets`/`ISteamNetworkingMessages` surface is genuinely
present and usable, use it instead of the legacy `ISteamNetworking` API
described above. The user's own expectation is that the newer surface is
probably **not** present, making the legacy surface (as described in this
section) the likely fallback, but the `javap` check must be done before
committing to either — this is no longer merely "this spec's default
recommendation," it is the required verification step planning must execute.

**Data-plane design** (assuming the legacy `SteamNetworking` surface,
reused from the prototype almost unchanged):
- Host side: a `SteamServerChannel` (Netty `AbstractServerChannel`, no real
  OS bind) accepts P2P session requests
  (`SteamNetworkingCallback.onP2PSessionRequest`), applies the
  friend-relationship gate (FR1.3), and on acceptance fires a new
  `SteamNettyChannel` child into the same pipeline `ServerConnectionListener`
  already wired for real TCP (`childHandler`) — from Minecraft's perspective
  each Steam peer looks like a normal accepted TCP connection.
- Client side: `Connection`'s own Netty bootstrap is redirected (three-point
  `@ModifyArg`/`@WrapOperation` hijack on `Bootstrap.channel(...)`/
  `.group(...)`/`.connect(...)`) to use a `SteamNettyChannel` instead of
  `NioSocketChannel`, exactly as the prototype's `ConnectionMixin` does
  (`ConnectionMixin.java:105-144`).
- A dedicated poller thread on each side drains `IsP2PPacketAvailable`/
  `ReadP2PPacket` per channel-0 (data) and fires `channelRead`/
  `channelReadComplete` onto the correct Netty `EventLoop`, since Steam's own
  callback delivery only happens via the render-thread `SteamAPI.runCallbacks()`
  pump and cannot itself push reads — same design as
  `SteamSession.pollPackets()`/`SteamNettyChannel.pollRead()`.
- All native `SteamNetworking` calls on a given side are guarded by one
  shared lock object (the SDK is documented not thread-safe across
  read/write/close from different threads) — reuses the prototype's
  `steamLock` discipline (`SteamSession.java:51-57`,
  `SteamNettyChannel.java:42-50`).
- A lightweight disconnect signal (a dedicated channel number carrying a
  single sentinel byte, "FIN") lets either side signal a clean close before
  tearing down the P2P session, avoiding relying purely on Steam's own P2P
  timeout to detect a disconnect — reuses the prototype's
  `SteamDisconnectProtocol` design (`SteamSession.stop()`,
  `SteamNettyChannel.doClose()`).

**Handshake/auth bypass.** Minecraft's normal login handshake expects an RSA
key exchange feeding a Mojang session-server hash check. Since the Steam P2P
channel already authenticates both peers by real `SteamID` at the transport
layer, and (per Non-goals) this feature does not attempt a QUIC-style
keying-material export the way `e4mc`'s Dialtone path does (Steam's legacy
P2P surface has no equivalent exportable session-key primitive), this
feature reuses the prototype's simpler approach: server/client login-packet
mixins substitute a null/empty RSA key and a **fixed constant digest** in
place of the real Mojang server-hash computation, and disable the
double-encryption Minecraft would otherwise layer on top of an
already-Steam-encrypted channel (`ServerLoginPacketListenerImplMixin.java`,
`ClientHandshakePacketListenerImplMixin.java`, `ConnectionMixin.killDoubleEncryption`).
**This is a real, inherited security simplification** (a fixed stub digest is
weaker than deriving a per-session secret cryptographically bound to the
channel, the way `e4mc`'s QUIC-based approach or Mojang's own real auth flow
both do) — accepted here because (a) it exactly matches the prior working
prototype's proven design and (b) the actual gate that matters (who may
connect at all) already happened one layer down, at the Steam-P2P-session
level via the friend-relationship check (FR1.3) — but it is called out
explicitly rather than silently ported, and is listed again under Open
Questions for conscious sign-off rather than an implicit carry-over.

**RESOLVED (Open Questions item 6):** the user has acknowledged and accepted
this fixed-stub-digest handshake simplification as a known security
weakening for v1, explicitly noting "it will change later on" — proceed with
it as specified above; no gold-plating of the auth mechanism is required for
this feature.

## Persistence
None. This feature has no save file, no per-world state, and nothing synced
to Steam Cloud — hosting state is entirely in-memory, scoped to the lifetime
of the currently-loaded integrated server, and is recomputed fresh on every
world load (mirrors `features/friends-sidebar/specification.md`'s own
"no save state of its own" framing).

## Compatibility
- Must be implemented in each of `platform/fabric-1.21.11` (Yarn/obfuscated),
  `platform/fabric-26.1`, and `platform/fabric-26.2` (both Mojang-mapped),
  per this repo's standing three-platform-module requirement.
- **Unlike every other Steamworks-touching feature shipped in this repo so
  far, the prototype this spec reverse-engineers was built against exactly
  one Minecraft version, exclusively with Mojang mappings**
  (`steamshare_client_mod/build.gradle:1`,
  `mappings loom.officialMojangMappings()`; no Yarn build ever existed for
  it). That means **none** of its class/method names have ever been
  confirmed against this repo's own Yarn-mapped 1.21.11 jars — every single
  mixin target this feature needs (`ServerConnectionListener`, `Connection`,
  `ServerAddress`, `IntegratedServer`, `ClientHandshakePacketListenerImpl`,
  `ServerLoginPacketListenerImpl`, and their respective method names like
  `startTcpServerListener`/`initServer`/`handleHello`/`handleKey`) must be
  independently `javap`-confirmed for 1.21.11's Yarn mapping before
  implementation, per this repo's own established discipline
  (`.claude/context/minecraft.md`'s "Known Cross-Version API Differences"
  table and its own stated confirmation methodology) — this is flagged as
  the single largest concrete unknown this spec carries forward, not
  something this document can responsibly guess at.
- The 26.1/26.2 side is comparatively lower-risk (both Mojang-mapped, and the
  prototype was itself built against a recent Mojang-mapped Minecraft
  version close in shape to these two) but must still be independently
  re-verified against **this repo's own resolved jars** rather than assumed
  identical to the prototype's exact Minecraft version, since Mojang's own
  internal class shapes still shift between game versions (e.g. this repo's
  own prior findings on `GuiGraphicsExtractor`, `MouseButtonEvent`, etc.,
  `.claude/context/minecraft.md`'s table).
- No live/manual in-game verification of actual Steam P2P connectivity, real
  NAT traversal, or a second real Steam account successfully joining is
  performed by this workflow (Non-goals) — verification is limited to
  compilation success on all three platform modules and unit tests of the
  plain-JVM logic (friend-gate predicate, connect-string parsing). This is an
  explicitly accepted gap for this iteration.

## Performance
- All native Steam P2P calls are documented by Valve as local, low-latency
  IPC to the Steam client process (not raw network I/O at the call-site
  level) — the same reasoning already relied on for Friends Sidebar's
  per-refresh-sweep Steamworks calls
  (`features/friends-sidebar/specification.md:FR1.4`). The actual P2P
  transport itself, however, carries real Minecraft protocol traffic
  end-to-end over the internet (not local IPC) once a session is
  established — its real-world latency/throughput characteristics cannot be
  measured in this workflow (Non-goals' verification-gap note) and are a
  live-testing concern for a future manual pass, not something this spec can
  bound numerically.
- The host/client poller threads (draining `IsP2PPacketAvailable`) run a
  tight `sleep(1)`-style loop per the prototype's design
  (`SteamSession.pollerLoop`, `SteamAmbientSession.pollerLoop`) — a
  pragmatic, already-proven approach, but planning should note the
  per-millisecond wake-up cost as a known, accepted trade-off (busy-ish
  polling vs. a native blocking-read primitive Valve's legacy P2P API does
  not appear to expose) rather than an oversight.
- No additional per-tick Minecraft-render-thread work is added beyond what
  `SteamworksService.pumpCallbacks()` already performs each client tick
  (existing shared cost, not new).

## Future Extensions
- Manual join-policy control (Friends / Friends-of-Friends / Everybody /
  Nobody), reintroducing a dedicated settings surface analogous to the
  prototype's `SteamFriendListScreen`, once "always-hosted, friends-only" is
  judged insufficient for real use.
- Friends-of-Friends relay/token propagation (the prototype's most complex
  already-solved mechanism, deliberately deferred here — Non-goals).
- A direct-connect `"steamid:<id>"` address form usable from vanilla's own
  Direct Connect / Multiplayer server-list screens (the prototype's
  `ServerAddressMixin`/`JoinMultiplayerScreenMixin` auto-populated-LAN-list
  behavior, `steamshare_client_mod/.../client/JoinMultiplayerScreenMixin.java`),
  giving joining a second, address-book-style entry point beyond the Friends
  Sidebar and the native overlay.
- Evaluating a migration from the legacy `ISteamNetworking` P2P surface to
  Valve's newer `ISteamNetworkingSockets`/`ISteamNetworkingMessages` API, if
  planning's `javap` pass finds the vendored steamworks4j version supports it
  and a future iteration wants the more modern, Valve-recommended surface.
- Stronger session authentication than the fixed-stub digest (Networking) —
  e.g. deriving a shared secret from something Steam P2P itself exposes, if
  any such primitive exists in the surface planning ultimately selects.

## Open Questions (require explicit user sign-off)

1. **"Always host" as the unconditional v1 behavior.** Every singleplayer
   world load starts a Steam P2P listener automatically, with no manual
   "start hosting"/"stop hosting" control and no way to temporarily close
   the world to new friend joins short of quitting it entirely (Non-goals,
   FR1.2). The prior prototype treated this as a *given* fact for the Netty
   pipeline bootstrap (it always ran `startTcpServerListener` on world load)
   but still layered a manual `JoinPolicy` (default `NOBODY`) on top before
   any peer could actually connect. This spec removes that layer entirely
   and replaces it with the fixed rule "any direct Steam friend may connect,
   always" (FR1.3) — please confirm this is the intended v1 behavior, not
   just "bootstrap the pipeline always" with join gating still manual.

   **RESOLVED:** Accepted as-is by the user — "always host" is fully
   unconditional in v1, with no manual stop short of quitting.

2. **Reusing Steam Cloud Sync/Friends Sidebar's existing
   `SteamworksService` bootstrap and availability-gating convention.**
   Confirmed as the intended integration point (Architecture, FR0.1) —
   flagged for sign-off only because it is a load-bearing assumption this
   entire spec is built on, not because there's a real ambiguity in the
   existing code to resolve.

   **RESOLVED:** Accepted by the user — reuse the existing
   `SteamworksService`/`SteamAvailability` bootstrap as specified.

3. **Reusing the prior prototype's exact networking surface** (legacy
   `com.codedisaster.steamworks.SteamNetworking`/`ISteamNetworking` P2P
   sessions — channel-numbered datagrams, `SendP2PPacket`/`ReadP2PPacket`)
   rather than Valve's newer `ISteamNetworkingSockets`/
   `ISteamNetworkingMessages` surface. This spec's default recommendation is
   to reuse the legacy surface exactly as proven (Networking section), but
   this is the single largest technical-direction choice in this document
   and should be explicitly confirmed rather than assumed, especially since
   it has not yet been confirmed which surfaces the pinned
   `steamworks4j-1.10.0` version actually exposes.

   **RESOLVED:** The user's decision is to do a `javap` (or equivalent)
   check on the vendored steamworks4j jar first — if the newer
   `ISteamNetworkingSockets`/`ISteamNetworkingMessages` API is actually
   present and usable, use that instead of the legacy `ISteamNetworking` API.
   The user's expectation is that it's probably **not** present, so the
   legacy surface is the likely fallback, but this must be verified before
   committing to either surface (see Networking section).

4. **Steamworks-seam consolidation** — whether this feature constructs its
   own independent `SteamFriends`/`SteamNetworking` instances, or whether
   `FriendsService`'s existing `SteamFriends` ownership should be extracted
   into a shared `services/`-layer wrapper per the "graduate-on-second-use"
   rule now that a second feature needs the same capability (Architecture).
   The latter is more consistent with this repo's own stated architectural
   rule but is a larger, riskier change touching an already-shipped feature;
   the former is smaller/safer but needs independent confirmation that
   steamworks4j/the native Steam API tolerates multiple concurrent
   `SteamFriends` wrapper objects in one process.

   **RESOLVED:** Approved — extract a shared Steam-friends wrapper now that
   a second feature needs it (per the repo's "graduate on second use" rule),
   i.e. option (b) in the Architecture section above. **Discrepancy flagged
   for planning:** the user's own wording said extract this "into `api/`",
   but this spec's Architecture section framed option (b) as extraction
   into `services/`, consistent with this repo's existing precedent
   (`SteamworksService` already lives in `services/`, not `api/`, and this
   spec's own Public API section frames `api/` as steamworks4j-free). This
   single-word discrepancy (`api/` vs `services/`) is **not** silently
   resolved here — planning must confirm against actual repo convention
   which layer the new shared wrapper belongs in before extracting it.

5. **Which Friends Sidebar context-menu slot "Join World" occupies** (FR4.1)
   — reusing the existing, currently-always-disabled "Join game" placeholder
   `FriendContextMenuWidget` already renders (`features/friends-sidebar/specification.md:FR3.4`,
   explicitly deferred there to "a Future Extension... planned together with
   [Invite]"), versus adding a visually distinct fifth menu entry. Reusing
   the existing slot is smaller and arguably completes exactly the deferred
   work that spec anticipated; a distinct entry avoids conflating this
   feature's real join mechanism with the Friends Sidebar spec's own
   "Invite to game" placeholder, which remains unimplemented either way.

   **RESOLVED:** Approved — "Join World" reuses the Friends Sidebar's
   existing disabled "Join game" context-menu slot exactly, not a new fifth
   entry (see also FR4.1 and UI section above, which are annotated
   accordingly).

6. **Fixed-stub session authentication** (Networking) — a known, real
   security simplification inherited from the prototype (a constant digest
   replacing a per-session cryptographic hash), accepted here only because
   Steam P2P's own peer authentication is judged the actual security
   boundary that matters. Flagged for explicit acknowledgment rather than
   silent inheritance.

   **RESOLVED:** Acknowledged and accepted by the user as a known, accepted
   security weakening for now — the user notes "it will change later on";
   proceed with this feature's inherited approach without further
   gold-plating.

7. **No live in-game verification of this feature at all in this workflow**
   (Non-goals, Compatibility) — confirming this is an accepted scope
   boundary for this iteration's planning/implementation/verification
   phases, not an oversight to raise later.

   **RESOLVED:** Confirmed by the user — no live in-game testing in this
   workflow (remote control constraint stands).
