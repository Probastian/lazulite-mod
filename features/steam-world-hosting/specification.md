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
`C:\Users\<username>\Documents\Coding\Minecraft Modding\steamshare_client_mod`
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
- **No handshake/crypto-bypass special-casing for real (non-debug) Steam
  World Hosting sessions (added by the Auth-Mode Fix correction, 2026-07-21).**
  A real, non-debug Steam-hosted session must behave, for handshake/
  session-verification purposes, like a completely normal online-mode server
  login — real RSA key exchange, real Mojang/Yggdrasil session-hash
  verification, zero special-casing. FR1.3's Steam-friend gate still applies
  as it always has, but strictly as an *additional* layer on top of real
  session verification, never as a replacement for it. The only place any
  handshake bypass still exists is the Loom dev/debug-launch case (FR5.1),
  which is unconditional on whether Steam World Hosting is even active. See
  Requirements FR1.6/FR5.1 and the Amendment section for the full correction
  and its relationship to the original (now partially reversed) design.

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
  handshake. **This gate is an additional layer of trust on top of, never a
  replacement for, real Mojang session verification (FR1.6, corrected
  2026-07-21) — see the Amendment section for why an earlier draft of this
  spec briefly (and incorrectly) treated FR1.3 as sufficient on its own.**
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
- **FR1.6 (AMENDED, 2026-07-21 — Auth-Mode Fix; REVERSED same day —
  corrected version below supersedes the version originally shipped in this
  amendment).** ~~The originally-drafted version of this requirement said:
  "whenever Steam World Hosting is active, force the integrated server's
  online-mode/session-verification flag off for every player, real and debug
  alike."~~ **That version is wrong and is withdrawn.** The corrected
  requirement is the opposite:

  Real (non-debug) Steam World Hosting connections **must** undergo genuine
  Mojang/Yggdrasil session-hash verification, exactly as a normal online-mode
  server would. Concretely: `ServerLoginStubDigestMixin`
  (`platform/fabric-26.2/src/main/java/de/lazuli/mixin/ServerLoginStubDigestMixin.java:38-92`,
  mirrored on fabric-26.1/fabric-1.21.11) — which currently substitutes a
  fixed `new byte[20]` digest for the real Mojang session-hash on **every**
  `SteamAddress` connection unconditionally (Networking, "Handshake/auth
  bypass") — must be scoped so that this stub-digest/crypto-bypass behavior
  does **not** fire for a real, non-debug Steam World Hosting session. Its
  documented design rationale ("the Steam-P2P friend check is the real trust
  boundary, Mojang session verification is meant to be irrelevant for Steam
  peers") is explicitly reversed by this correction — see the Amendment
  section and the reversed annotation under Networking's "Handshake/auth
  bypass" and Resolved Open Questions item 6. FR1.3's Steam-friend gate
  remains in force as an *additional* layer, unconditionally, alongside this
  now-restored real session verification — it was never meant to, and no
  longer does, substitute for it.
- **FR1.7 (New, 2026-07-21 — Auth-Mode Fix correction).** The only condition
  under which handshake/session-verification bypass behavior may still occur
  is the Loom dev/debug-launch case defined by FR5.1, which is **independent
  of and unconditional on** whether Steam World Hosting is active at all. A
  real (non-debug) Steam World Hosting session run under a normal, non-dev
  Minecraft launch gets zero handshake special-casing beyond FR1.3's
  friend-gate at the transport layer.

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

**Debug/dev launches (AMENDED, 2026-07-21 — Auth-Mode Fix; scope corrected
same day)**
- **FR5.1 (corrected scope).** Independent of whether Steam World Hosting is
  active at all, a dev/debug launch of this mod (Fabric Loom's
  `runClient`/`runServer` dev environment, e.g. the `.vscode/launch.json`
  configurations that shell out to `net.fabricmc.devlaunchinjector.Main`/
  Knot) is the **only** case in which `ServerLoginStubDigestMixin`'s
  stub-digest/crypto-bypass behavior (Networking) may fire — gated strictly
  on `FabricLoader.getInstance().isDevelopmentEnvironment()` (Open Question
  10), and **not** on whether Steam World Hosting is active. Dev-environment
  Microsoft/offline accounts have no real Mojang session to verify regardless
  of Steam-hosting state, so this bypass is scoped purely to "is this a Loom
  dev environment," an OR'd condition independent of FR1.6/FR1.7's now-real
  session verification for actual Steam-hosted sessions. This may need **no
  online-mode/`usesAuthentication` setter call at all** — see the Amendment
  section's corrected mechanism, which reasons that the fix may reduce
  entirely to guarding `ServerLoginStubDigestMixin` itself on
  `isDevelopmentEnvironment()`, rather than touching the server's online-mode
  flag anywhere.

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
  new screen, only new translation keys. **Corrected note (Auth-Mode Fix):**
  since real Steam World Hosting sessions now undergo genuine session
  verification (FR1.6/FR1.7), a real player who fails Mojang session
  verification (e.g. a pirated/offline account attempting to join a real
  session) will now also see vanilla's own standard "Invalid session"/
  authentication-failure disconnect screen where previously the stubbed
  digest silently masked that failure mode until it surfaced as a confusing,
  unconditional login error for every player.

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
- **AMENDED (2026-07-21, Auth-Mode Fix), CORRECTED same day.** No new
  configuration field is added by the auth-mode fix, corrected or otherwise.
  The corrected fix needs, at most, a `FabricLoader.isDevelopmentEnvironment()`
  guard directly inside `ServerLoginStubDigestMixin` (FR5.1) — it does
  **not** force online-mode/`usesAuthentication` off for real Steam World
  Hosting sessions at all (FR1.6/FR1.7, reversed), so there is no longer any
  `enabled`-flag-derived online-mode toggle to configure or gate. See the
  Amendment section for the corrected mechanism.

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
**Confirmed by the plan/verification-report: only the legacy surface exists
in this repo's vendored jar; implementation targets it exclusively.**

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

**Handshake/auth bypass (original design — see the REVERSED annotation
immediately below; this paragraph is retained verbatim for traceability, not
because it is still correct as originally scoped).** Minecraft's normal login
handshake expects an RSA key exchange feeding a Mojang session-server hash
check. Since the Steam P2P channel already authenticates both peers by real
`SteamID` at the transport layer, and (per Non-goals) this feature does not
attempt a QUIC-style keying-material export the way `e4mc`'s Dialtone path
does (Steam's legacy P2P surface has no equivalent exportable session-key
primitive), this feature reuses the prototype's simpler approach:
server/client login-packet mixins substitute a null/empty RSA key and a
**fixed constant digest** in place of the real Mojang server-hash
computation, and disable the double-encryption Minecraft would otherwise
layer on top of an already-Steam-encrypted channel
(`ServerLoginPacketListenerImplMixin.java`,
`ClientHandshakePacketListenerImplMixin.java`, `ConnectionMixin.killDoubleEncryption`).
**This is a real, inherited security simplification** (a fixed stub digest is
weaker than deriving a per-session secret cryptographically bound to the
channel, the way `e4mc`'s QUIC-based approach or Mojang's own real auth flow
both do) — originally accepted here because (a) it exactly matches the prior
working prototype's proven design and (b) the actual gate that matters (who
may connect at all) was believed to already have happened one layer down, at
the Steam-P2P-session level via the friend-relationship check (FR1.3). **Item
(b) of this reasoning is now confirmed wrong (see below).**

**RESOLVED (Open Questions item 6), then PARTIALLY SUPERSEDED (2026-07-21 —
Auth-Mode Fix reversal):** the user originally acknowledged and accepted this
fixed-stub-digest handshake simplification as a known security weakening for
v1, explicitly noting "it will change later on." **That acceptance is now
withdrawn for real (non-debug) Steam World Hosting sessions.** The premise it
rested on — that FR1.3's Steam-friend check is the real trust boundary and
Mojang session verification is meant to be irrelevant for Steam peers — is
confirmed wrong: the Steam-friend check is an *additional* gate on top of
real session verification, never a substitute for it, and real players must
undergo genuine session verification exactly as they would on any other
online-mode server. This item's acceptance survives **only** for the Loom
dev/debug-launch case (FR5.1), where dev/offline accounts have no real
session to check regardless of Steam-hosting state. This is flagged
explicitly, per this repo's own "RESOLVED (Open Questions item N)" annotation
convention, but as a **reversal**, not a resolution, so it remains traceable
rather than silently overwritten — see the new Open Question 11 for the
explicit architectural-reversal sign-off this requires, and the Amendment
section for full reasoning.

**AMENDED then REVERSED (2026-07-21 — Auth-Mode Fix, corrected same day).**
An earlier draft of this amendment concluded that the confirmed
"Failed to log in: Invalid session" bug was fixed by *completing* the
original design's premise — i.e. by also forcing the server's online-mode
flag off globally whenever Steam World Hosting was active (a former FR1.6),
so that the already-faked handshake digest would no longer be checked
against a real online-mode session for anyone. **That conclusion is wrong and
is reversed.** The user has confirmed the Steam-friend check (FR1.3) is not
an acceptable substitute for verifying a player owns their Minecraft account.
The corrected fix runs in the opposite direction: real (non-debug) Steam
World Hosting sessions must not have their handshake bypassed at all —
`ServerLoginStubDigestMixin`'s stub-digest substitution must be scoped OFF
for those sessions, so genuine Mojang session-hash verification runs exactly
as it would for a normal server (FR1.6/FR1.7). The **only** legitimate
handshake-bypass condition remaining after this correction is a Loom
dev/debug launch (FR5.1), entirely independent of Steam World Hosting's own
active/inactive state. This reverses this subsection's original design and
Resolved Open Questions item 6's acceptance of it (immediately above) — see
the corrected Amendment section below and new Open Question 11 for the
explicit sign-off this architectural reversal requires.

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
  something this document can responsibly guess at. **Now confirmed and
  logged in `.claude/context/minecraft.md`'s table** (row "Integrated-server
  Netty/login networking stack") as part of implementation/verification.
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

**AMENDED (2026-07-21, Auth-Mode Fix, per-mapping online-mode API names) —
NARROWED/LARGELY MOOT per the same-day correction below.** FR1.6/FR5.1's
mechanism, as originally drafted, needed one additional pair of
`MinecraftServer` methods (online-mode getter/setter) and, for context,
vanilla's own "Open to LAN"/publish method, on top of the six classes the
original Compatibility section above already flagged. Research this pass
(Fabric Yarn javadoc for 1.21.11-adjacent Yarn builds, `WebFetch`/`WebSearch`
— no `javap`/decompiler run against this repo's own resolved jars in this
specification pass, consistent with this repo's own convention of treating
that as an implementation/planning-phase mandatory step, not a
specification-phase one):
- **Yarn (`platform/fabric-1.21.11`) — confirmed via direct fetch of Yarn's
  own generated Javadoc** (`maven.fabricmc.net/docs/yarn-1.21.5+build.1/net/minecraft/server/MinecraftServer.html`):
  `MinecraftServer.isOnlineMode()` / `MinecraftServer.setOnlineMode(boolean)`.
  Vanilla's own "Open to LAN" equivalent on this side is
  `IntegratedServer.openToLan(@Nullable GameMode, boolean cheatsAllowed, int port)`
  (confirmed via the same Javadoc source for `IntegratedServer`) — **not**
  used by this fix, recorded here only because it is the vanilla method whose
  side effects (real LAN Netty bind, `GameMode`/cheats-allowed
  reconfiguration) this fix deliberately avoids triggering.
- **Mojang mapping (`platform/fabric-26.1`/`platform/fabric-26.2`) — NOT yet
  `javap`-confirmed against this repo's own resolved jars.** Web research
  this pass (Fabric/Yarn Javadoc pages, GitHub/grep.app code search) could
  not positively confirm the exact official Mojang-mapped method names before
  this spec was written. Based on well-established, widely-cited
  Minecraft-modding community knowledge of Mojang's own official mapping, the
  expected names are `MinecraftServer.usesAuthentication()` /
  `MinecraftServer.setUsesAuthentication(boolean)`.
- **Same-day correction: this entire online-mode-API-name research item is
  now narrowed to "likely unnecessary" rather than "mandatory `javap` step,"
  and is retained here only for traceability.** The corrected Auth-Mode Fix
  (FR1.6/FR1.7/FR5.1) removes the global online-mode-forcing requirement
  entirely — real Steam World Hosting sessions must leave online-mode/
  `usesAuthentication` completely untouched, at whatever value vanilla/the
  logged-in Microsoft account already computed. The only remaining
  behavior-change surface (FR5.1's debug-launch bypass) most likely needs
  **no** online-mode getter/setter call at all; it is currently expected to
  reduce to a `FabricLoader.isDevelopmentEnvironment()` guard added directly
  inside the existing `ServerLoginStubDigestMixin`, which never needed to
  read or write the online-mode flag to begin with (see the Amendment
  section's corrected mechanism). If planning's own analysis finds a genuine
  remaining need to read/write `usesAuthentication`/`isOnlineMode` for the
  debug-launch case specifically, the per-mapping method-name research above
  is still valid and the same `javap -p` verification step against the
  resolved 26.1/26.2 jars still applies before writing that code — it is
  narrowed in *scope of applicability*, not deleted, since this document
  cannot fully rule out planning finding a residual need.
- Both `usesAuthentication()`/`isOnlineMode()` are plain `boolean` getters
  with no known cross-version signature divergence beyond the name itself
  (no overload, no additional parameter) per every source consulted — the
  setter is a single-`boolean`-argument void method on both sides. Retained
  for the same reason as above (residual-need contingency only).

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
- **AMENDED (2026-07-21 — Auth-Mode Fix), CORRECTED same day:** the corrected
  fix (FR1.6/FR1.7/FR5.1) adds, at most, a single
  `FabricLoader.isDevelopmentEnvironment()` boolean check inside
  `ServerLoginStubDigestMixin`'s existing guard, evaluated once per login
  attempt (not per-tick or per-packet) — negligible, and strictly smaller
  than the originally-drafted version of this fix (which would have added a
  getter/setter pair once per world load); real Steam World Hosting sessions
  now do strictly *more* work than the originally-shipped design (a full real
  handshake instead of a stubbed one), which is the intended, necessary cost
  of correct session verification, not a regression to optimize away.

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
  any such primitive exists in the surface planning ultimately selects. **Note
  (Auth-Mode Fix correction, 2026-07-21):** after the correction, this item
  applies only to the narrowed debug/dev-launch stub-digest path (FR5.1) —
  real Steam World Hosting sessions no longer use a stub digest at all
  (FR1.6/FR1.7), so there is nothing left to harden for real sessions on this
  axis; the remaining stub-digest surface is inherently low-stakes (dev-only
  accounts, never internet-facing).
- ~~**(Added by the Auth-Mode Fix amendment)** A genuinely per-session
  cryptographic hash bound to the Steam P2P channel (rather than the fixed
  stub digest, Networking's "Handshake/auth bypass") remains the natural
  follow-up hardening once online-mode is correctly forced off end-to-end.~~
  **Withdrawn by the same-day correction:** this item's premise (real
  sessions still using a stub digest, merely with online-mode forced off) no
  longer applies — real sessions use the genuine handshake entirely (no
  stub, no forced-off online-mode), so there is no "per-session hash bound to
  the Steam channel" gap left to close for real sessions. Only the
  restated bullet immediately above (scoped to the debug-only path) survives.

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
   gold-plating. **PARTIALLY SUPERSEDED (2026-07-21 — Auth-Mode Fix
   reversal):** this resolution's own stated premise ("the real gate is the
   Steam-friend check") is now confirmed **not** true, and the acceptance is
   withdrawn for real (non-debug) Steam World Hosting sessions — see item 11
   below for the explicit reversal sign-off this requires, and Networking's
   "Handshake/auth bypass" section for the corrected design. This item's
   acceptance survives only for the Loom dev/debug-launch stub-digest path
   (FR5.1).

7. **No live in-game verification of this feature at all in this workflow**
   (Non-goals, Compatibility) — confirming this is an accepted scope
   boundary for this iteration's planning/implementation/verification
   phases, not an oversight to raise later.

   **RESOLVED:** Confirmed by the user — no live in-game testing in this
   workflow (remote control constraint stands).

8. **(New, 2026-07-21 — Auth-Mode Fix) Mojang-mapped `usesAuthentication`/
   `setUsesAuthentication` method names — NARROWED/LIKELY MOOT by the
   same-day correction.** This item originally flagged that these
   Mojang-mapped names were a research-backed working assumption, not
   `javap`-confirmed, and needed verification before the (then-drafted)
   global online-mode-forcing fix could be implemented. **The corrected fix
   (FR1.6/FR1.7/FR5.1) removes the global online-mode-forcing requirement
   entirely** — real Steam World Hosting sessions must leave online-mode
   completely untouched, and the debug-launch case (FR5.1) most likely needs
   no online-mode getter/setter call at all, only a
   `FabricLoader.isDevelopmentEnvironment()` guard inside
   `ServerLoginStubDigestMixin`. This item is therefore narrowed to a
   contingency: **only if** planning's own analysis of FR5.1 finds a genuine
   remaining need to read/write `usesAuthentication`/`isOnlineMode`
   specifically (which is not currently expected), the mandatory `javap -p`
   verification against the resolved 26.1/26.2 jars from the original item
   still applies before writing that code. Otherwise this item can be
   treated as **moot** and closed without further action.

   **Not yet formally resolved — narrowed to a contingency per the
   correction above; no action expected unless planning finds a residual
   need.**

9. **(New, 2026-07-21 — Auth-Mode Fix) Mechanism choice for the (withdrawn)
   global online-mode-forcing fix — WITHDRAWN, no longer applicable.** This
   item originally asked planning to choose between a direct
   `setUsesAuthentication(false)`/`setOnlineMode(false)` call at the
   `IntegratedServerWorldHostingMixin` world-load hook versus routing through
   vanilla's own `publishServer(...)`/`openToLan(...)`. **Both alternatives
   are moot** — the corrected fix (FR1.6/FR1.7) does not force online-mode
   off for real Steam World Hosting sessions at all, so there is no longer
   any online-mode-forcing call, by either mechanism, to choose between. This
   item is closed/withdrawn by the same-day correction; no sign-off needed.

   **Withdrawn — superseded by the Auth-Mode Fix correction; no action
   needed.**

10. **(New, 2026-07-21 — Auth-Mode Fix) FR5.1's mechanism: reusing Fabric
    Loader's existing `FabricLoader.getInstance().isDevelopmentEnvironment()`
    flag, rather than a new `server.properties` file, a new JVM system
    property, or a Loom run-config property (the three mechanisms the task
    explicitly asked to be investigated).** This spec's finding, based on
    reading the actual repo state this pass (`.vscode/launch.json`; each
    platform module's `run/` directory, confirmed via `Glob` to contain only
    `options.txt`/`usercache.json`/`steam_appid.txt`/crash logs, no
    `server.properties` on any of the three modules today; each platform
    module's `build.gradle`, confirmed to have no custom Loom `runs { }`
    block beyond the pre-existing `generateSteamAppId` task), is that
    **`server.properties` is not a viable mechanism at all for this fix** —
    that file is read only by a *dedicated* server
    (`MinecraftDedicatedServer`), never by `IntegratedServer`, so writing one
    into `run/` would have zero effect on the singleplayer/integrated-server
    online-mode flag this fix actually needs to control. A new JVM system
    property or a Loom run-config property would work, but both require
    touching `.vscode/launch.json` and/or each module's `build.gradle`'s
    `loom { runs { ... } }` block to add a new, project-specific flag,
    whereas Fabric Loader already reliably exposes exactly this
    "is this a dev/debug launch" boolean with zero configuration and zero
    new files across all three platform modules today. This spec recommends
    `FabricLoader.getInstance().isDevelopmentEnvironment()` as the mechanism
    (see Amendment section) but flags it for explicit sign-off since the
    task named three specific alternative mechanisms to investigate and this
    recommendation is a fourth option not originally named. **Still fully
    applicable after the same-day correction** — this remains the mechanism
    for FR5.1's debug-launch gate; only the *target* of the gate changed
    (guarding `ServerLoginStubDigestMixin` directly, rather than gating an
    online-mode setter call that no longer exists for the real-session case).

    **Not yet resolved — awaiting user sign-off.**

11. **(New, 2026-07-21 — Auth-Mode Fix correction) Reversing
    `ServerLoginStubDigestMixin`'s existing shipped behavior for real,
    non-debug Steam World Hosting sessions is a real architectural
    reversal, not a bug-fix tweak, and requires explicit sign-off separate
    from (and replacing) the former item 9's now-withdrawn mechanism
    question.** As shipped, `ServerLoginStubDigestMixin` unconditionally
    stubs the crypto handshake/digest for **every** `SteamAddress`
    connection, on the explicit documented premise that "the Steam-P2P
    friend check is the real trust boundary, Mojang session verification is
    meant to be irrelevant for Steam peers" (Networking, Resolved Open
    Questions item 6). This correction requires that mixin's behavior to
    change so it no longer fires for real (non-debug) sessions — real
    players will now undergo the full real handshake and real Mojang session
    verification, something the shipped code has never done for a Steam-P2P
    connection before. This is architecturally the **opposite direction**
    from the fix this document previously described (which would have
    additionally forced online-mode off, doubling down on the bypass rather
    than reversing it), and changes already-shipped, already-reasoned-about
    security-relevant code. Given the scope of this reversal, explicit
    sign-off is requested before implementation proceeds:
    - Confirm that `ServerLoginStubDigestMixin` (and its
      `ClientHandshakeStubDigestMixin`/`ConnectionMixin.killDoubleEncryption`
      counterparts, to the extent they share the same real-vs-debug
      condition) should be scoped so their bypass behavior fires **only**
      when `FabricLoader.getInstance().isDevelopmentEnvironment()` is true
      (FR5.1's condition), and **never** merely because a connection is
      `SteamAddress`-typed/Steam-World-Hosting-active.
    - Confirm the corollary: a real (non-dev) Steam World Hosting session
      will now perform a full, real RSA/session-hash handshake over the
      Steam P2P channel exactly as it would over real TCP — this is *new*
      behavior relative to everything shipped and tested so far under this
      feature, and its interaction with the Steam-P2P transport (e.g.
      whether the real handshake's packet sizes/ordering behave correctly
      over the `SteamNettyChannel`/`SteamServerChannel` pipeline, which was
      never exercised with a real handshake before) is an implementation/
      verification-phase concern this correction newly introduces and this
      spec cannot itself validate (Non-goals' no-live-testing scope still
      applies).

    **Not yet resolved — awaiting explicit user sign-off on this
    architectural reversal before implementation.**

## Amendment: Auth-Mode Fix (2026-07-21, corrected same day)

This section is the authoritative detail for the "AMENDED"-tagged
requirements/sections above (FR1.6, FR1.7, FR5.1, and the corresponding
Networking/Compatibility/Configuration/Performance/Future-Extensions
annotations). It supersedes an earlier draft of this same amendment section
that reached the wrong conclusion (see "Corrected premise" below) and does
not re-litigate anything already RESOLVED elsewhere in this document except
where explicitly flagged as reversed (Open Questions items 6, 9, 11).

### Confirmed bug (root-caused prior to this spec; restated here only for
traceability, not re-investigated)
A friend joining a Steam-P2P-hosted world sees "Failed to log in: Invalid
session." Root cause chain:
- `ServerLoginStubDigestMixin` (`platform/fabric-26.2/src/main/java/de/lazuli/mixin/ServerLoginStubDigestMixin.java:38-92`,
  mirrored on fabric-26.1/fabric-1.21.11) substitutes a fixed `new byte[20]`
  digest for the real Mojang session-hash on `SteamAddress` connections
  (Networking's "Handshake/auth bypass"), on the stated premise that FR1.3's
  Steam-friend check is the real trust boundary.
- Nothing in the repo ever called `setUsesAuthentication(false)`/
  `setOnlineMode(false)` or vanilla's own `publishServer(...)`/
  `openToLan(...)` (confirmed via repo-wide `Grep`, zero hits for
  `usesAuthentication`/`publishServer`/`onlineMode` anywhere outside this
  amendment's own text). `IntegratedServerWorldHostingMixin`
  (`platform/fabric-26.2/src/main/java/de/lazuli/mixin/IntegratedServerWorldHostingMixin.java:31-46`,
  mirrored on the other two modules) bootstraps the Netty pipeline directly
  inside `initServer()`/`setupServer()`, bypassing the vanilla publish method
  entirely — so the server's online-mode flag stays at whatever it already
  was (`true` for a logged-in Microsoft-account host).
- Net effect: the crypto handshake is faked, but real Mojang session
  verification still runs afterward and legitimately fails against the fake
  digest — for every real player joining a Steam-hosted world, not merely a
  debug/dev-launch symptom.

### Corrected premise (2026-07-21, same day as the original amendment)
An earlier draft of this amendment concluded from the bug above that the fix
was to *complete* `ServerLoginStubDigestMixin`'s original design intent — by
also forcing the server's online-mode/`usesAuthentication` flag off whenever
Steam World Hosting was active, so real session verification would no longer
run at all for anyone, matching the already-faked digest. **The user has
corrected this: that conclusion is wrong.** The Steam-friend check (FR1.3) is
not, and was never intended to be, an acceptable substitute for verifying
that a joining player actually owns their Minecraft account. Doubling down on
the digest-stubbing design (by also disabling the flag that would otherwise
have caught it) would have shipped a mod that lets *anyone* who can pass the
Steam-friend check into a world without ever proving Minecraft-account
ownership — a strictly worse security posture than either "real
verification" or "no verification, but at least it's honest about it."

The corrected understanding: `ServerLoginStubDigestMixin`'s unconditional
stub-digest behavior is itself the actual bug (or at minimum, its documented
design rationale — "Mojang session verification is meant to be irrelevant
for Steam peers" — is now known to be wrong and is explicitly reversed by
this correction, Networking's "Handshake/auth bypass" section and Resolved
Open Questions item 6). Real (non-debug) Steam-hosted connections must go
through genuine Mojang session-hash verification exactly like a normal server
would. Only the debug/dev-launch case (FR5.1) bypasses it, and only because
dev accounts have no session to check in the first place — never because
"Steam already checked who's allowed."

### Requirement scope (corrected; restated as requirements FR1.6/FR1.7/FR5.1
above)
1. **Real (non-debug) Steam World Hosting sessions get zero handshake/
   crypto-bypass special-casing (FR1.6/FR1.7).** `ServerLoginStubDigestMixin`
   (and its client/`ConnectionMixin` counterparts, to the extent they share
   the same condition) must not fire for these sessions. They behave exactly
   like a normal online-mode server login for handshake/session-verification
   purposes: real RSA key exchange, real Mojang session-hash check, real
   double-encryption (no `killDoubleEncryption`). FR1.3's Steam-friend gate
   still applies, unconditionally, as an *additional* layer at the P2P
   transport level, alongside — never instead of — this real verification.
2. **Only Loom dev/debug launches bypass the handshake at all (FR5.1),
   independent of Steam World Hosting's own state.** Gated strictly on
   `FabricLoader.getInstance().isDevelopmentEnvironment()`. This condition is
   OR'd with nothing else — it does not matter whether Steam World Hosting is
   active or inactive for a dev/debug launch; the bypass fires purely because
   the dev/offline account has no real session to check.
3. **No global online-mode/`usesAuthentication` forcing anywhere.** The
   originally-drafted fix's mechanism (a boolean setter call inside
   `IntegratedServerWorldHostingMixin`, gated on
   `WorldHostingHookHolder.isEnabled() || isDevelopmentEnvironment()`) is
   withdrawn in its entirety. Real Steam World Hosting sessions need the
   online-mode flag left at whatever value it already has (normally `true`
   for a logged-in Microsoft-account host) — that is precisely what makes
   real session verification actually run. Dev/debug launches likely do not
   need the online-mode flag touched either (see Mechanism below) — the
   simpler, narrower fix is to gate the existing stub-digest mixin itself,
   not to toggle a separate flag that the mixin's own logic never actually
   depended on.
4. **Explicit non-goal/scoping boundary, restated and corrected:** a normal
   (non-debug, non-Steam-hosting) vanilla session run by this mod continues
   to see zero behavior change (unchanged from the original scope). A real,
   non-debug Steam World Hosting session now also sees zero handshake-level
   special-casing — it is treated exactly like a normal server login for
   crypto/session-verification purposes, with FR1.3's friend-check applied at
   the P2P-transport layer as an additional, independent gate. The **only**
   session type that still gets any handshake bypass at all is a Loom
   dev/debug launch, entirely regardless of whether Steam World Hosting
   happens to be active for it.

### Mechanism (corrected)
- **The fix is very likely simpler than originally scoped.** Rather than
  adding new logic to `IntegratedServerWorldHostingMixin` (online-mode
  forcing) as the earlier draft proposed, the corrected fix is expected to
  reduce to a single guard added to the existing
  `ServerLoginStubDigestMixin` (and its counterparts) itself:
  ```
  if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
      return; // real, non-debug session: let the real handshake/digest run untouched
  }
  // existing stub-digest substitution, now reachable only in a Loom dev/debug launch
  ```
  This directly replaces the mixin's previous unconditional-for-all-
  `SteamAddress`-connections behavior with a condition scoped purely to
  "is this a Loom dev environment," independent of `SteamAddress`/Steam World
  Hosting state entirely. No new field, no new class, no online-mode
  getter/setter call anywhere in this mechanism.
- **`IntegratedServerWorldHostingMixin` needs no change for this fix.** The
  original amendment's plan to add a `setUsesAuthentication`/`setOnlineMode`
  call at this mixin's existing `initServer`/`setupServer` inject point is
  withdrawn — that inject point's existing behavior (bootstrapping the
  ephemeral Netty pipeline, FR1.1) is unaffected by this correction.
- **Why not touch online-mode at all, even for the debug-launch case:** a
  Loom dev/debug launch's `IntegratedServer` genuinely has no real Mojang
  session backing its (often offline/dev) account, regardless of whatever
  `isOnlineMode()`/`usesAuthentication()` happens to report. The actual
  behavior that needs to change for a dev launch is "don't require a real
  digest during the handshake" — which is exactly what
  `ServerLoginStubDigestMixin` already does, it just needs to stop doing it
  unconditionally. There is no known reason FR5.1 additionally needs the
  online-mode flag itself flipped; if planning's own deeper investigation
  during implementation finds a genuine residual need (e.g. some other code
  path independently branches on `isOnlineMode()`/`usesAuthentication()` for
  a dev-launch integrated server in a way that breaks without also flipping
  it), Compatibility's retained per-mapping method-name research
  (`usesAuthentication()`/`setUsesAuthentication(boolean)` on 26.1/26.2,
  `isOnlineMode()`/`setOnlineMode(boolean)` on 1.21.11) remains available and
  the same `javap -p` verification step still applies before using it — but
  this is a contingency, not the expected primary mechanism (Open Question
  8, narrowed).
- **Scope of the reversal relative to `ClientHandshakePacketListenerImplMixin`/
  `ConnectionMixin.killDoubleEncryption`:** to the extent these mirror
  `ServerLoginStubDigestMixin`'s same unconditional-bypass condition (client
  side of the same handshake), they need the identical
  `isDevelopmentEnvironment()` guard applied for the same reason — planning
  should confirm during implementation exactly which of the mixin set shares
  this condition versus which pieces (e.g. the Steam-P2P-transport-specific
  parts, unrelated to the crypto bypass itself) are unaffected by this
  correction and should remain unconditional.

### Compatibility / verification notes for this correction
- No new mixin class, no new file — this correction narrows existing
  conditions inside `ServerLoginStubDigestMixin` (and, likely, its
  client-side counterpart), rather than adding new logic to
  `IntegratedServerWorldHostingMixin` as the original amendment proposed.
- **This is a real, shipped-behavior reversal, not an additive change** (Open
  Question 11) — unlike every other piece of this feature, which added new
  behavior, this correction removes/narrows behavior that was already
  implemented and (per the verification report referenced in the original
  FR1.6 text) already passed this feature's own prior verification pass under
  its original, now-reversed premise. Re-verification of
  `ServerLoginStubDigestMixin`'s corrected condition, and of the previously
  untested "real handshake over `SteamNettyChannel`" code path it now
  exposes for the first time, is required in the next implementation/
  verification pass.
- The per-mapping `usesAuthentication`/`isOnlineMode` research in
  Compatibility above is retained for traceability and as a contingency
  only — it is not expected to be needed by the corrected mechanism, and
  Open Question 8 is narrowed accordingly, not deleted.
- No change to Resolved Open Questions items 1-5, 7 — this correction only
  reverses/narrows items 6 and 9, and adds new item 11, per the annotations
  in place at each location above.
