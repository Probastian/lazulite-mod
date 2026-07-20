# Implementation Plan — Steam World Hosting

## Summary
Build `features/steam-world-hosting` (FR0–FR4) reusing the prior
`steamshare_client_mod` prototype's proven Netty/Steam-P2P mechanism
(specification's Architecture/Networking sections), on top of the shared
`SteamworksService` bootstrap. This is the **third** Steamworks-touching
feature and the **second consumer** of `SteamFriends`, which triggers this
repo's own "graduate-on-second-use" rule (`architecture.md:26`): a shared,
`SteamFriends`/`SteamUser`-owning wrapper is extracted into `services/`
(resolving the spec's flagged `api/`-vs-`services/` discrepancy in favor of
`services/` — see Decision 1) and `features/friends-sidebar`'s already-shipped
`FriendsService` is refactored to consume it instead of constructing its own
`SteamFriends`/`SteamUtils`/`SteamUser`.

This plan also resolves the spec's other concrete planning-phase action item:
a real bytecode-presence check against the vendored `steamworks4j-1.10.0.jar`
confirms the **legacy** `SteamNetworking`/`ISteamNetworking` P2P surface is
the only one available (Decision 2) — the newer
`ISteamNetworkingSockets`/`ISteamNetworkingMessages` classes do not exist in
this jar at all. The Netty/mixin design below therefore targets the legacy
surface exactly as the spec's own Networking section and the prototype
describe.

No implementation code is written as part of this plan.

## Existing Implementation

### Steamworks bootstrap (reused as-is)
- `services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`
  — `create(appId, nativeLibraryDirectory, warningLogger)`, `pumpCallbacks()`,
  `shutdown()`, implements `api/.../steamworks/SteamAvailability`. Never
  re-initialized.
- Per platform module: `SteamworksClientInitializer` (registered **second** in
  `fabric.mod.json`'s `"client"` array, after `HelloWorldMainMenuClientInitializer`)
  constructs it and calls `SteamworksServiceHandoff.publish(...)` immediately
  after construction; `SteamworksServiceHandoff.require()` is the established
  hand-off contract every later consumer uses (`SteamworksServiceHandoff.java`,
  identical file in all three modules). Currently the **third** entry is
  `SteamCloudSyncClientInitializer`, the **fourth** is
  `FriendsSidebarClientInitializer` (confirmed by reading
  `platform/fabric-26.2/src/main/resources/fabric.mod.json`, identical shape
  on the other two modules per both prior plans' own Existing Implementation
  sections).

### Friends Sidebar's current (uncommitted, in-progress-refactor) state — read directly off disk, not git history
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java`
  is **today** "the sole class in this feature importing
  `com.codedisaster.steamworks.*`": it directly constructs its own
  `SteamFriends`, `SteamUtils`, `SteamUser` (three separate steamworks4j
  wrapper objects, three no-op/near-no-op callback inner classes), owns the
  per-tick refresh sweep (`FriendsSidebarConfig.refreshIntervalSeconds()`-gated),
  and only ever requests/reads **one** Rich Presence key today: `"status"`
  (`RICH_PRESENCE_STATUS_KEY`), exposed as `richPresenceStatus(long): Optional<String>`
  on the `FriendsDataSource` interface. **This confirms the spec's own flagged
  ambiguity (Architecture, "World Hosting → Friends Sidebar" bridging item):
  `richPresenceStatus(long)` is status-text-shaped, not a raw/generic Rich
  Presence accessor** — it can never return this feature's own `"connect"`
  key value, so FR4.2 cannot simply call it. A new, independent read path is
  needed (Decision 4).
- `FriendContextMenuWidget.java` (all three platform modules, identical
  shape) already renders the exact four-slot menu FR4.1 targets:
  `LABELS = {"Open chat", "Show profile", "Invite to game", "Join game"}`,
  index **3** is "Join game". `isEnabled(int index)` currently routes index 3
  to `facade.stateMachine().isJoinEnabled(friend)`, which
  `FriendSidebarStateMachine.isJoinEnabled(FriendSummary)` hard-codes to
  `false` always (v1 friends-sidebar spec FR3.4). `mouseClicked` routes a
  click on index 3 to `facade.actions().onJoin(friend.steamId64())`, which
  `FriendsService.onJoin(long)` implements as an empty, commented no-op.
  `FriendContextMenuWidget` also already supports a fifth constructor
  parameter, `isOwnProfile` (forces only "Show profile" enabled for the
  pinned own-profile row) — not relevant to this feature, recorded only so
  the new constructor parameters this plan adds don't collide with it.
- `FabricFriendsSidebarInjector.java` (all three modules) is a Pattern-1
  (`ScreenEvents.AFTER_INIT` + `Screens.getWidgets`) injector that manually
  draws the sidebar and context menu (via `FriendsSidebarZOrder`,
  `api/src/main/java/de/lazuli/api/friends/FriendsSidebarZOrder.java`, a new
  uncommitted enum: `SIDEBAR` then `CONTEXT_MENU`, ordinal order = draw
  order) on `ScreenEvents.afterExtract`, and intercepts outside-clicks/Escape
  via `ScreenMouseEvents`/`ScreenKeyboardEvents` (no mixin). It constructs
  `FriendContextMenuWidget` in `openContextMenu(...)`.
- `FriendsSidebarClientInitializer.java` (all three modules) is the
  composition root: `SteamworksServiceHandoff.require()`, loads
  `FriendsSidebarConfig`, builds `FriendsService` or `NoopFriendsService`
  (gated on `steamworksService.isSteamAvailable() && config.enabled()`),
  wraps both in `FriendsSidebarFacade`, registers
  `ClientTickEvents.END_CLIENT_TICK`, constructs `FabricFriendsSidebarInjector`.
- `api/src/main/java/de/lazuli/api/friends/` today: `FriendSummary`,
  `FriendSidebarHook`, `FriendActionListener`, `FriendsSidebarZOrder`. Zero
  Minecraft/steamworks4j import in any of them (unchanged convention).

### steamworks4j 1.10.0 — networking-surface verification (spec's required first concrete step, Networking/Open Question 3)
Performed exactly as the spec's resolution requires, using the tools actually
available this pass (`Grep`, `WebFetch` — no Bash/`javap` binary available in
this planning session; the ZIP-entry-pathname-presence technique is the same
one `steam-cloud-sync/implementation-plan.md`'s own Existing Implementation
already documented and used for an analogous jar-presence question):
- **Jar located** (already built, no `./gradlew` run needed) at all three
  paths the spec anticipated:
  `platform/fabric-1.21.11/build/processIncludeJars/steamworks4j-1.10.0.jar`,
  `platform/fabric-26.1/build/processIncludeJars/steamworks4j-1.10.0.jar`,
  `platform/fabric-26.2/build/processIncludeJars/steamworks4j-1.10.0.jar`
  (byte-identical jar, Jar-in-Jar'd into all three, per existing convention).
- **`Grep` against the real jar's binary content** (ZIP local-file-header
  entry names are stored uncompressed, so a plain-text pattern search finds
  real class-file pathnames even though the compiled bytecode itself is
  DEFLATE-compressed and unreadable this way): a `SteamNetworking` entry
  **is** present; `SteamNetworkingSockets`, `SteamNetworkingMessages`,
  `SteamNetworkingUtils` (and the broader `NetworkingSockets`/
  `NetworkingMessages`/`NetworkingUtils`/`NetworkingIdentity` substrings) are
  **absent** — zero matches for any of them.
- **Corroborated independently via `WebFetch`** of the real GitHub source
  tree at the exact pinned tag (`code-disaster/steamworks4j`, tag `1.10.0`,
  `java-wrapper/src/main/java/com/codedisaster/steamworks/`): only
  `SteamNetworking.java`, `SteamNetworkingNative.java`,
  `SteamNetworkingCallback.java`, `SteamNetworkingCallbackAdapter.java` exist;
  no `SteamNetworkingSockets`/`SteamNetworkingMessages`/`SteamNetworkingUtils`/
  `ISteamNetworkingSockets`/`ISteamNetworkingMessages` file exists anywhere in
  that directory.
- **Conclusion (Decision 2): the newer surface is not present in this repo's
  pinned steamworks4j version.** Per the spec's own resolution, this makes
  the legacy `SteamNetworking` surface the required design target, not merely
  the fallback — there is no other option available.
- **Exact legacy API confirmed via a second direct `WebFetch`** of
  `SteamNetworking.java`/`SteamNetworkingCallback.java` at the same pinned
  tag:
  ```java
  // SteamNetworking(SteamNetworkingCallback callback)
  boolean sendP2PPacket(SteamID steamIDRemote, ByteBuffer data, P2PSend sendType, int channel)
  boolean isP2PPacketAvailable(int channel, int[] msgSize)
  int readP2PPacket(SteamID steamIDRemote, ByteBuffer dest, int channel)
  boolean acceptP2PSessionWithUser(SteamID steamIDRemote)
  boolean closeP2PSessionWithUser(SteamID steamIDRemote)
  boolean closeP2PChannelWithUser(SteamID steamIDRemote, int channel)
  boolean getP2PSessionState(SteamID steamIDRemote, P2PSessionState connectionState)
  boolean allowP2PPacketRelay(boolean allow)
  // enum P2PSend { Unreliable, UnreliableNoDelay, Reliable, ReliableWithBuffering }
  // enum P2PSessionError { None, NotRunningApp, NoRightsToApp, DestinationNotLoggedIn, Timeout }

  // SteamNetworkingCallback (both default/no-op-able)
  default void onP2PSessionRequest(SteamID steamIDRemote)
  default void onP2PSessionConnectFail(SteamID steamIDRemote, SteamNetworking.P2PSessionError sessionError)
  ```
  This matches the spec's Networking section's own citations exactly — no
  correction needed here (unlike the friends-sidebar plan's
  `activateGameOverlayToUser`/`OverlayToUserDialog` correction).
- **`SteamFriends` methods this feature additionally needs** (beyond what
  `FriendsService` already uses), confirmed the same way (`WebFetch` of
  `SteamFriends.java` at tag `1.10.0`):
  ```java
  boolean setRichPresence(String key, String value)   // FR2.1
  void clearRichPresence()                            // FR2.2
  String getFriendRichPresence(SteamID steamIDFriend, String key)  // generic, FR4.2/Decision 4
  void requestFriendRichPresence(SteamID steamIDFriend)            // already used by FriendsService
  FriendRelationship getFriendRelationship(SteamID steamIDFriend)  // FR1.3's canJoin gate
  // enum FriendRelationship { None, Blocked, Recipient, Friend, RequestInitiator,
  //                           Ignored, IgnoredFriend, Suggested_DEPRECATED, Max }
  ```
  `getFriendRelationship(id) == FriendRelationship.Friend` is exactly the
  spec's own cited check shape (FR1.3).

### Resolved Minecraft jars (present, ready for implementation's mandatory `javap` pass — not run this planning pass, no Bash tool available in this session)
Confirmed present via `Glob` (paths match `.claude/context/minecraft.md`'s own
prior citations, reused verbatim):
- `~/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-.../1.21.11-...+build.6-v2/....jar` (Yarn)
- `~/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-.../26.1/....jar` (Mojang)
- `~/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-.../26.2/....jar` (Mojang)

**This is the single largest concrete unknown this plan carries forward**
(spec Compatibility's own explicit flag, Risk 1): none of
`ServerConnectionListener`/`Connection`/`ServerAddress`/`IntegratedServer`/
`ClientHandshakePacketListenerImpl`/`ServerLoginPacketListenerImpl`'s exact
method names/signatures/visibility are `javap`-confirmed by this planning
pass (no Bash/decompiler tool available this session, same honest limitation
`steam-cloud-sync/implementation-plan.md` and
`friends-sidebar/plan.md` both already recorded for their own
Minecraft-jar-inspection needs). Implementation's mandatory first step, per
this repo's own established discipline
(`.claude/context/minecraft.md:19-30`), is a real `javap -p` pass against all
three jars above for exactly those six classes before writing any mixin body,
logging results in `minecraft.md`'s table per its own convention.

## Decisions on the Open Questions (resolved during planning)

### 1. `api/` vs `services/` for the shared Steam-friends wrapper — resolved as `services/`, per repo convention (Open Question 4)
The user's own wording said extract the shared wrapper "into `api/`," but this
repo's actual layering convention (`architecture.md`'s Dependency Rules;
`api/.../steamworks/SteamAvailability.java`'s own stated rationale, "a
stable, steamworks4j-free contract... never touches
`com.codedisaster.steamworks.*`") makes `api/` structurally the wrong layer
for a class whose entire reason to exist is owning real `SteamFriends`/
`SteamUser` objects and making live native calls — exactly the same
reasoning that already put `SteamworksService` in `services/`, not `api/`.
**This plan resolves the discrepancy in favor of `services/`,
flagging it here explicitly per the task's own instruction so the user can
object if "api/" was meant literally.** Concretely:

- New class: `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`
  (interface) + `SteamworksSteamFriendsGateway.java` (real impl, the
  **only** class in `services/` importing `com.codedisaster.steamworks.*`
  beyond `SteamworksService` itself) + `NoopSteamFriendsGateway.java`
  (constructed whenever `!SteamAvailability.isSteamAvailable()`, mirroring
  every other `Noop*` in this repo). Owns the single `SteamFriends`/
  `SteamUtils`/`SteamUser` instances and their callback registrations
  (superset of what `FriendsService` already does today), but exposes an
  **entirely plain-Java-typed** public surface (`long`/`int`/`String`/
  `Optional<...>`, never `SteamID`/`PersonaState`/`FriendRelationship`) —
  stronger than merely "the sole importer," this makes **every** feature that
  consumes it (both `friends-sidebar` and `steam-world-hosting`) fully
  steamworks4j-import-free, not just "one named exception per feature":
  ```java
  public interface SteamFriendsGateway {
      long localSteamId64();
      String localPersonaName();
      int localPersonaStateOrdinal();
      int friendCount();
      long friendSteamId64At(int index);
      String friendPersonaName(long steamId64);
      int friendPersonaStateOrdinal(long steamId64);
      boolean isDirectFriend(long steamId64);                    // FR1.3
      boolean friendInGame(long steamId64);
      Optional<String> friendGameConnectHint(long steamId64);     // wraps getFriendGamePlayed/FriendGameInfo
      void requestFriendRichPresence(long steamId64);
      Optional<String> friendRichPresenceValue(long steamId64, String key); // FR4.2/generic
      boolean setLocalRichPresence(String key, String value);     // FR2.1
      void clearLocalRichPresence();                              // FR2.2
      int avatarHandle(long steamId64);
      Optional<byte[]> avatarRgba(long steamId64);
      boolean isOverlayEnabled();
      void activateOverlayChat(long steamId64);
      void activateOverlayProfile(long steamId64);
      Set<Long> drainDirtyAvatars();                              // replaces FriendsService's own field
  }
  ```
- **Published via a new per-platform-module hand-off**,
  `SteamFriendsGatewayHandoff` (one file × 3 modules, byte-identical shape to
  the already-established `SteamworksServiceHandoff`: `publish`/`require`,
  `volatile static` field, same narrow-global-state justification already
  accepted for that class). **Constructed by `SteamworksClientInitializer`
  itself**, immediately after `SteamworksService` (not by
  `FriendsSidebarClientInitializer`) — deliberately, so `steam-world-hosting`
  never has an ordering dependency on `friends-sidebar`'s own entrypoint
  existing/being enabled (see Risk 2). `SteamworksClientInitializer` gains:
  `SteamFriendsGateway gateway = steamworksService.isSteamAvailable() ? new SteamworksSteamFriendsGateway() : new NoopSteamFriendsGateway();`
  then `SteamFriendsGatewayHandoff.publish(gateway);`.
- **`FriendsService.java` is refactored** (mechanical, not a redesign) to
  take a constructor-injected `SteamFriendsGateway` instead of constructing
  its own `SteamFriends`/`SteamUtils`/`SteamUser`; every steamworks4j call
  site becomes a plain call into the gateway; the `com.codedisaster.steamworks.*`
  import block is removed entirely. `FriendsSidebarClientInitializer` gains
  one line, `SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();`,
  passed into `new FriendsService(gateway, config, warnLogger)`.
  `FriendSidebarStateMachine`, `FriendsSidebarFacade`, `NoopFriendsService`,
  `FriendActionListener`, `FriendSummary` are **unchanged** — this refactor
  is scoped entirely to `FriendsService`'s own steamworks4j call sites, per
  the task's own instruction to keep this a bounded, reviewable change.
- **Why not option (a) (independent second `SteamFriends` instance,
  spec's Architecture item (a))**: rejected per the user's own resolution
  (Open Question 4) and per this repo's explicit graduate-on-second-use rule
  — a second feature now needs the identical capability, which is precisely
  that rule's trigger condition.

### 2. Networking surface: legacy `SteamNetworking` (Existing Implementation's verification)
No further decision needed beyond Existing Implementation's finding — the
newer surface does not exist in this repo's pinned jar, so the Data-plane
design proceeds exactly as the spec's own Networking section describes:
`SteamServerChannel`/`SteamNettyChannel` (Netty `AbstractServerChannel`/
`AbstractChannel`), a poller thread draining `IsP2PPacketAvailable`/
`ReadP2PPacket` per channel 0, a channel-numbered "FIN" disconnect sentinel,
one shared lock object per side guarding all native `SteamNetworking` calls.
**Future Extensions'** "evaluate a migration to the newer surface" item is
now confirmed moot for as long as this exact steamworks4j version is pinned
— only relevant again if `gradle.properties`' `steamworks4j_version` is ever
bumped past `1.10.0`.

### 3. Connect-string format
`"+lazuli_join <steamId64>"` (FR2.3) — same shape as the prototype's
`"+steamshare_join <steamId64>"`, rebranded for this mod's own namespace so
it can never collide with a real, separately-installed copy of the original
prototype mod reading the same Rich Presence key on a shared friend's
machine. Owned by a single pure class:
`features/steam-world-hosting/services/ConnectStringCodec.java` —
`String encode(long hostSteamId64)`, `OptionalLong decode(String richPresenceConnectValue)`
(returns empty for `null`/blank/non-matching input, never throws). Zero
Minecraft/steamworks4j import; the single most important unit-test target in
this feature besides the join gate itself (NFR1-equivalent, Goals).

### 4. Cross-feature bridging (Friends Sidebar ↔ Steam World Hosting) — two independent, one-directional `api`-contract bridges, no shared runtime cache
Per Existing Implementation's finding that `FriendsService.richPresenceStatus(long)`
is status-text-shaped (reads only the `"status"` key), **this feature does
not read through `friends-sidebar`'s own per-sweep cache at all** — instead
it independently polls the same shared `SteamFriendsGateway` for the
`"connect"` key on its own schedule, fully decoupling the two features'
runtime data paths (stronger than the spec's own "reuses data already being
fetched" framing, but avoids the alternative's real coupling cost: making
`steam-world-hosting`'s own hosting-status detection depend on
`friends-sidebar` being enabled/present at all). Concretely:

- **New `api` contracts** (`api/src/main/java/de/lazuli/api/worldhosting/`):
  - `WorldJoinRequester` — `void joinHostedWorld(long hostSteamId64);` (spec
    Public API item 1, unchanged shape).
  - `FriendHostingStatusReader` — `boolean isFriendHosting(long friendSteamId64);`
    (deliberately a boolean query, not a raw-string-parsing contract — hides
    `ConnectStringCodec`'s own format entirely behind this feature's
    boundary; smaller and more stable than exposing
    `HostingPresenceReader`-shaped parse methods across the Feature
    boundary, per the spec's own "planning decision" flag on this exact
    point).
  - `HostedWorldStatus` — `record HostedWorldStatus(boolean hosting, long localSteamId64)` (spec Public API item 1, unchanged).
- **Bridge 1 (Friends Sidebar → World Hosting, FR4.1/FR4.3):**
  `platform/fabric-<version>/.../friends/FriendContextMenuWidget.java` gains
  two new, nullable constructor parameters: `WorldJoinRequester worldJoinRequester`,
  `FriendHostingStatusReader hostingStatusReader`. `isEnabled(3)` becomes
  `hostingStatusReader != null && hostingStatusReader.isFriendHosting(friend.steamId64())`
  (instead of `facade.stateMachine().isJoinEnabled(friend)`, which is left
  untouched/unused for this slot — `friends-sidebar`'s own state machine
  keeps returning `false` for its own internal callers, but this widget now
  overrides that specifically for the reused slot, per FR4.1). The index-3
  branch of `mouseClicked` calls `worldJoinRequester.joinHostedWorld(friend.steamId64())`
  instead of `facade.actions().onJoin(...)` (`FriendsService.onJoin` stays
  the empty no-op it already is — never reached via this path anymore).
  `FabricFriendsSidebarInjector.openContextMenu(...)` passes these two
  references through (held as injector fields, supplied via its own
  constructor — see Bridge wiring below).
- **Bridge 2 (World Hosting → Friends Sidebar, FR4.2):**
  `features/steam-world-hosting/services/HostingPresenceScanner.java` — owns
  its own rate-limited tick (same shape as `FriendsService.tick()`'s
  interval-gating, a small `HostingScanIntervalSeconds` constant/config
  field, default e.g. 5s), iterating `gateway.friendCount()`/
  `friendSteamId64At(i)` (the shared gateway, **not** `FriendsService`),
  calling `gateway.requestFriendRichPresence(id)` +
  `gateway.friendRichPresenceValue(id, "connect")`, decoding with
  `ConnectStringCodec.decode(...)`, and caching a `Set<Long> hostingFriendIds`.
  Implements `FriendHostingStatusReader` directly
  (`isFriendHosting(id) -> hostingFriendIds.contains(id)`).
- **Bridge wiring (composition root only, ADR-0003 shape, "Platform
  constructs one Feature's instance and hands its `api`-contract-typed
  reference to another Feature's Version Adapter"):** a new per-platform
  hand-off, `WorldHostingBridgeHandoff` (× 3 modules, same
  publish/require shape), published by `SteamWorldHostingClientInitializer`
  (Files to Create) carrying both `WorldJoinRequester` and
  `FriendHostingStatusReader` (or `NoopWorldJoinRequester`/
  `NoopFriendHostingStatusReader` if Steam is unavailable/config disabled —
  same Noop convention as everywhere else, so `.require()` never throws once
  ordering is correct and callers never need a null-check).
  `FriendsSidebarClientInitializer` calls `WorldHostingBridgeHandoff.require()`
  and passes the two references into `new FabricFriendsSidebarInjector(facade, worldJoinRequester, hostingStatusReader)`,
  which in turn passes them into every `FriendContextMenuWidget` it
  constructs.
- **Ordering consequence (flagged, Risk 2):** `SteamWorldHostingClientInitializer`
  must be registered **before** `FriendsSidebarClientInitializer` in
  `fabric.mod.json`'s `"client"` array (becomes the **third** entry,
  `friends-sidebar` becomes the **fourth**, cloud-sync's existing position is
  unaffected either way since it never touches this hand-off) — a new,
  load-bearing cross-feature **entrypoint-ordering** dependency (not a
  compile-time class import) that did not exist before this feature.

### 5. Hosting lifecycle service shape (FR1.1–FR1.5)
- `features/steam-world-hosting/services/HostGateway.java` — plain,
  constructor-injected `LongPredicate friendRelationshipLookup` (supplied by
  the platform composition root as `gateway::isDirectFriend`, mirroring
  `FriendSidebarStateMachine`'s own injected-dependency shape) —
  `boolean canJoin(long friendSteamId64) { return friendRelationshipLookup.test(friendSteamId64); }`
  (FR1.3/FR1.5). Zero Minecraft/steamworks4j import; the other primary
  plain-JVM-testable unit alongside `ConnectStringCodec` (Goals/NFR1).
- `features/steam-world-hosting/services/HostingLifecycle.java` — holds
  `HostedWorldStatus`-shaped mutable state (`hosting`, `localSteamId64`),
  `start()`/`stop()` called by the platform's `IntegratedServer`-lifecycle
  mixin/hook (FR1.1/FR1.2), and calls `gateway.setLocalRichPresence("connect", codec.encode(localSteamId64))`
  on `start()` / `gateway.clearLocalRichPresence()` on `stop()` (FR2.1/FR2.2).
  Exposes `HostedWorldStatus currentStatus()` (spec Public API item 1) for
  the platform's `IntegratedServer.isPublished()` override (FR1.4) to query
  "is at least one peer connected" — the actual peer-connected tracking
  itself lives in the platform's `SteamServerChannel` (Netty layer, cannot
  live below `platform/`, per Architecture's "Netty/mixin glue placement");
  `HostingLifecycle` only owns the plain on/off + Rich-Presence-string state,
  not peer bookkeeping.
- `HostGateway`/`HostingLifecycle`/`ConnectStringCodec`/`HostingPresenceScanner`
  are all constructed by `SteamWorldHostingClientInitializer` (composition
  root), gated on `SteamAvailability.isSteamAvailable() && config.enabled()`
  — a `NoopHostingLifecycle`/`NoopHostGateway` pair exists for the disabled
  case (FR0.2/FR0.3), same convention as every prior feature.

### 6. Netty/mixin glue per platform module (FR1.1–FR3.3, Networking)
Reproduces the prototype's design (spec Architecture/Networking), one full
copy per module (`de.lazuli.worldhosting`, per-platform package, never
shared beyond `api`/`services` — Architecture's explicit, accepted cost):
- `SteamAddress` — a `SocketAddress` subclass carrying a raw `long steamId64`.
- `SteamServerChannel` (extends that version's `AbstractServerChannel`) —
  registers `SteamNetworkingCallback.onP2PSessionRequest`, applies
  `HostGateway.canJoin(...)` (FR1.3), on acceptance calls
  `acceptP2PSessionWithUser` and fires a new child `SteamNettyChannel` into
  `ServerConnectionListener`'s existing `childHandler` pipeline. Tracks its
  own connected-peer count for `IntegratedServer.isPublished()`'s override
  (FR1.4 — see Decision 5).
- `SteamNettyChannel` (extends that version's `AbstractChannel`) — wraps
  `sendP2PPacket`/`isP2PPacketAvailable`/`readP2PPacket` on channel 0 (data)
  + a dedicated sentinel channel number for the "FIN" clean-disconnect
  signal (Networking), guarded by one shared lock object per side
  (`steamLock`, prototype's own discipline).
- A dedicated poller thread per side (host: one per accepted peer or one
  shared pump iterating all peers; client: one for the single ambient
  session) draining P2P reads and dispatching onto the correct Netty
  `EventLoop` (`channelRead`/`channelReadComplete`), since
  `SteamAPI.runCallbacks()` (already pumped every client tick by
  `SteamworksService.pumpCallbacks()`) never itself pushes reads.
- Mixins (exact target classes/methods **not yet `javap`-confirmed**, Risk
  1 — the six below are the spec Compatibility section's own named targets,
  carried forward verbatim as this plan's working list, to be corrected
  during implementation's mandatory first `javap` pass):
  - An `IntegratedServer`-targeting mixin (`@Inject` at the point analogous
    to the prototype's `onInitServer`) that, on every singleplayer world
    load, invokes the same `ServerConnectionListener` method "Open to LAN"
    invokes (binding a real Netty `childHandler`/`EventLoopGroup` to port
    `0`) and then calls `HostingLifecycle.start(...)` (FR1.1/FR1.2).
  - An `IntegratedServer.isPublished()`-overriding mixin returning `true`
    whenever `SteamServerChannel`'s own connected-peer count is `> 0`
    (FR1.4).
  - A `Connection`-targeting mixin hijacking `Bootstrap.channel(...)`/
    `.group(...)`/`.connect(...)` (three-point `@ModifyArg`/`@WrapOperation`,
    prototype's own `ConnectionMixin` shape) to substitute
    `SteamNettyChannel` for `NioSocketChannel` on the client-connect path
    (FR3.2), and to disable Minecraft's own double-encryption layer
    (Networking's "Handshake/auth bypass" section) since the channel is
    already Steam-encrypted.
  - `ClientHandshakePacketListenerImpl`/`ServerLoginPacketListenerImpl`
    mixins substituting a null/empty RSA key and a fixed-constant digest in
    place of the real Mojang session-hash check (Networking, resolved Open
    Question 6 — accepted security simplification, proceed as specified, no
    further design needed).
- **Client-side connect entry point** (FR3.1): a small platform class,
  `SteamAmbientSession`/`SteamConnectOperation` (prototype's own naming),
  exposing `void connectToSteamPeer(long hostSteamId64)`, invoked by (a) the
  platform's own `onGameRichPresenceJoinRequested`/`onGameLobbyJoinRequested`
  Steam callback (decoding via `ConnectStringCodec`) and (b)
  `WorldJoinRequester.joinHostedWorld(long)`'s real implementation — both
  funnel into vanilla's own `ConnectScreen`/`Connection.connect(...)` flow
  (spec FR3.2), so all normal connecting UI (progress screen, cancel,
  disconnect reasons) behaves unchanged. `FR3.3`'s clean-disconnect-reason
  requirement is satisfied by a small new translation key plus the same
  disconnect-callback wiring the prototype's `SteamAmbientSession` already
  proved (`triggerDisconnect`/`setDisconnectCallback`).

## Files to Create

### `api` module (top-level, zero dependencies)
- `api/src/main/java/de/lazuli/api/worldhosting/HostedWorldStatus.java` — record: `boolean hosting, long localSteamId64` (spec Public API item 1).
- `api/src/main/java/de/lazuli/api/worldhosting/WorldJoinRequester.java` — `void joinHostedWorld(long hostSteamId64);` (spec Public API item 1, Decision 4).
- `api/src/main/java/de/lazuli/api/worldhosting/FriendHostingStatusReader.java` — `boolean isFriendHosting(long friendSteamId64);` (Decision 4, resolves the spec's flagged "does a `HostingPresenceReader`-shaped contract belong in `api`" question — yes, as this narrower boolean-only shape).

### `services` module (existing subproject, new package)
- `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java` — interface (Decision 1).
- `services/src/main/java/de/lazuli/services/steamworks/SteamworksSteamFriendsGateway.java` — real impl; the sole new class in `services/` importing `com.codedisaster.steamworks.*` beyond `SteamworksService` itself.
- `services/src/main/java/de/lazuli/services/steamworks/NoopSteamFriendsGateway.java` — all-safe-defaults no-op.

### `features/steam-world-hosting` module (new Gradle subproject)
- `features/steam-world-hosting/build.gradle` — `dependencies { api project(':api'); implementation project(':services') }` (identical shape to `features/steam-cloud-sync`/`features/friends-sidebar`).
- `features/steam-world-hosting/README.md`

**`api/` sub-package** (`de.lazuli.features.worldhosting.api`, feature-internal):
- `SteamWorldHostingConfig.java` — record: `enabled` (default `true`) + `DEFAULT` constant (spec Configuration/Public API item 2).

**`config/` sub-package**:
- `SteamWorldHostingConfigIO.java` — `config/steam-world-hosting.json` load/parse/serialize; malformed → defaults + warning, never throws (same `HelloWorldMainMenuConfigIO`-shaped precedent every prior feature reuses).

**`services/` sub-package**:
- `ConnectStringCodec.java` (Decision 3)
- `HostGateway.java` (Decision 5)
- `HostingLifecycle.java` (Decision 5)
- `NoopHostGateway.java`, `NoopHostingLifecycle.java` — FR0.2/FR0.3 disabled-state pair.
- `HostingPresenceScanner.java` (Decision 4, implements `FriendHostingStatusReader`)
- `NoopFriendHostingStatusReader.java`, `NoopWorldJoinRequester.java` — Decision 4's Noop pair for the bridge hand-off.

**`events/`, `gui/`, `mixins/` sub-packages** — each a `package-info.java` placeholder (Netty/mixin glue cannot live here, Architecture's explicit "Netty/mixin glue placement" section).
**`resources/`** — `.gitkeep` (no bundled assets).

**`tests/`** (`src/test/java/de/lazuli/features/worldhosting/...`): `ConnectStringCodecTest` (round-trip encode/decode, malformed/blank/non-matching-prefix input → empty, no exception), `HostGatewayTest` (`canJoin` true/false driven by a fake `LongPredicate`), `HostingPresenceScannerTest` (given a fake `SteamFriendsGateway`-shaped seam returning a fixed friend list + connect-string values, asserts `isFriendHosting` correctly reflects decode results), `SteamWorldHostingConfig`/`ConfigIO` round-trip + malformed-fallback tests (mirrors every prior feature's own config test shape).

### Platform modules — one composition root + Netty/mixin glue per module (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/SteamFriendsGatewayHandoff.java` — Decision 1 (published by `SteamworksClientInitializer`).
- `platform/fabric-<version>/src/main/java/de/lazuli/WorldHostingBridgeHandoff.java` — Decision 4 (published by `SteamWorldHostingClientInitializer`).
- `platform/fabric-<version>/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java` — new `ClientModInitializer`; composition root. Calls `SteamworksServiceHandoff.require()` + `SteamFriendsGatewayHandoff.require()`, loads config, constructs the real or Noop service set (Decision 5), publishes `WorldHostingBridgeHandoff`, registers the `IntegratedServer` world-load hook (via the mixin's static holder bridge — same "static holder because the mixin-merged instance is never constructed by our own code" pattern `WorldSyncToggleHookHolder` already established for `steam-cloud-sync`), registers a Rich-Presence-clear on `ClientLifecycleEvents.CLIENT_STOPPING`, registers `HostingPresenceScanner`'s tick on `ClientTickEvents.END_CLIENT_TICK`, and wires the native-overlay "Join Game" Steam callback to the connect operation (FR3.1 path 1).
- `platform/fabric-<version>/src/main/java/de/lazuli/worldhosting/SteamAddress.java`, `SteamServerChannel.java`, `SteamNettyChannel.java`, `SteamAmbientSession.java` (client-side connect operation, FR3.1/FR3.2) — Decision 6.
- `platform/fabric-<version>/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java` — static holder bridging `HostingLifecycle`/`HostGateway`/`SteamServerChannel` construction into the mixin classes below (same shape as `WorldSyncToggleHookHolder`).
- `platform/fabric-<version>/src/main/java/de/lazuli/mixin/IntegratedServerWorldHostingMixin.java` — world-load hook + `isPublished()` override (Decision 6).
- `platform/fabric-<version>/src/main/java/de/lazuli/mixin/ConnectionSteamChannelMixin.java` — client-connect Bootstrap hijack + double-encryption disable (Decision 6).
- `platform/fabric-<version>/src/main/java/de/lazuli/mixin/ClientHandshakeStubDigestMixin.java`, `ServerLoginStubDigestMixin.java` — handshake/auth-bypass pair (Decision 6, Networking's accepted security simplification).
- `platform/fabric-<version>/src/main/resources/assets/lazuli/lang/en_us.json` (or its existing equivalent, merged into whatever this module's file already is) — new FR3.3 disconnect-reason translation key(s).

### Documentation
- No new ADR expected — Decision 1 (services extraction) is already covered
  by the existing graduate-on-second-use rule text in `architecture.md`
  itself (not a new architectural shape needing its own ADR, unlike
  `steam-cloud-sync`'s `CloudSyncable` aggregation, which *did* need
  ADR-0003). Decision 4's bridging is the *same* ADR-0003 shape
  (`docs/adr/0003-cloudsyncable-cross-feature-bridging-via-api-contracts.md`)
  already written and already generalized beyond `CloudSyncable` specifically
  — reused as-is, no new ADR text needed.

## Files to Modify
- `settings.gradle` — add `include 'features:steam-world-hosting'`.
- `services/src/main/java/de/lazuli/services/steamworks/` — add the three
  new files above; no change to the existing `SteamworksService.java`.
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java`
  — refactored to consume `SteamFriendsGateway` instead of constructing its
  own `SteamFriends`/`SteamUtils`/`SteamUser` (Decision 1). **No behavioral
  change** to any of its existing public method results — this is a
  dependency-injection-shape change only, verified by the existing
  (currently no dedicated fake-seam test exists per that feature's own
  plan's Risk 8 — still true here) manual in-game re-verification of FR1–FR3
  from that feature's own acceptance criteria.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/SteamworksClientInitializer.java`
  — gains construction of `SteamFriendsGateway`/`NoopSteamFriendsGateway` and
  `SteamFriendsGatewayHandoff.publish(...)`, right after the existing
  `SteamworksServiceHandoff.publish(...)` line (Decision 1).
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java`
  — two new nullable constructor parameters, `isEnabled(3)`/`mouseClicked`
  index-3 branch rewritten to use them (Decision 4). All other
  labels/indices/`isOwnProfile` behavior unchanged.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`
  — constructor gains `WorldJoinRequester`/`FriendHostingStatusReader`
  parameters, threaded into every `FriendContextMenuWidget` construction
  (Decision 4).
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`
  — gains `SteamFriendsGatewayHandoff.require()` (passed into
  `FriendsService`'s constructor) and `WorldHostingBridgeHandoff.require()`
  (passed into `FabricFriendsSidebarInjector`'s constructor). No other
  change.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/resources/fabric.mod.json`
  — `"client"` array gains a **third** entry,
  `"de.lazuli.SteamWorldHostingClientInitializer"`, positioned **after**
  `"de.lazuli.SteamworksClientInitializer"` and **before**
  `"de.lazuli.SteamCloudSyncClientInitializer"`/`"de.lazuli.FriendsSidebarClientInitializer"`
  (order load-bearing — Decision 4's Risk 2). Final order:
  `HelloWorldMainMenuClientInitializer`, `SteamworksClientInitializer`,
  `SteamWorldHostingClientInitializer`, `SteamCloudSyncClientInitializer`,
  `FriendsSidebarClientInitializer`.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/resources/lazuli.mixins.json`
  — gains the four new mixin class names (Decision 6).
- `platform/fabric-{26.2,26.1,1.21.11}/build.gradle` — each gains
  `implementation project(':features:steam-world-hosting')`.
- `.claude/context/minecraft.md` — gains new rows once implementation's
  mandatory `javap` pass confirms the six Compatibility-flagged classes'
  exact names/signatures per module (Risk 1) — not modified by this planning
  pass itself, per the repo's own living-record convention.

## Interfaces
- `api/.../worldhosting/{HostedWorldStatus, WorldJoinRequester, FriendHostingStatusReader}` — the three new top-level `api` contracts (Decision 4/5).
- `services/.../steamworks/SteamFriendsGateway` — the new shared Steamworks-friends seam (Decision 1), consumed by both `features/friends-sidebar` and `features/steam-world-hosting`.
- `features/steam-world-hosting/services/{HostGateway, ConnectStringCodec}` — the two plain-JVM-testable business-logic classes (Goals/NFR1-equivalent).

## Services
- `SteamFriendsGateway`/`SteamworksSteamFriendsGateway`/`NoopSteamFriendsGateway` (Decision 1) — the first `services/`-layer extraction since `SteamworksService` itself, with an implicit "ADR-equivalent" justification recorded directly in this plan's Decision 1 (graduate-on-second-use, no separate ADR file needed — the rule itself already lives in `architecture.md`, unlike `CloudSyncable`'s genuinely novel bridging shape).

## Feature Classes
Enumerated fully under Files to Create (`api/`, `config/`, `services/`
sub-packages of `features/steam-world-hosting`). All plain Java; zero
`net.minecraft.*`/steamworks4j-native-call import anywhere in this feature
module (stronger than NFR1's usual "one named exception," since the shared
gateway now absorbs that exception at the `services/` layer instead).

## Tests

### Test Strategy
Per spec Non-goals/Compatibility, **no live in-game testing is performed by
this workflow** (Open Question 7, confirmed). Verification is limited to:
1. **Unit tests (plain JVM)** — `ConnectStringCodecTest`, `HostGatewayTest`,
   `HostingPresenceScannerTest` (Files to Create), plus
   `SteamWorldHostingConfig`/`ConfigIO` round-trip tests (mirrors every
   prior feature's own config-test shape). These are the only classes in
   this feature meeting NFR1-equivalent plain-JVM-testability, per Goals'
   own explicit scoping.
2. **Compilation across all three platform modules** — `gradlew build` (or
   the equivalent per-module task) must succeed for
   `:platform:fabric-26.2`, `:platform:fabric-26.1`,
   `:platform:fabric-1.21.11` with the new `features:steam-world-hosting`
   dependency, the new `fabric.mod.json` entrypoint, the new mixin
   registrations, and every modified file (`FriendContextMenuWidget`,
   `FabricFriendsSidebarInjector`, `FriendsSidebarClientInitializer`,
   `SteamworksClientInitializer`) in place.
3. **Static/`javap`-based mixin-target verification** (this repo's own
   established discipline, `.claude/context/minecraft.md:19-30`) — the
   mandatory first implementation step for the Netty/mixin work: a real
   `javap -p` pass against all three resolved Minecraft jars (paths recorded
   in Existing Implementation) for `ServerConnectionListener`, `Connection`,
   `ServerAddress`, `IntegratedServer`, `ClientHandshakePacketListenerImpl`,
   `ServerLoginPacketListenerImpl` (spec Compatibility's own named list) —
   logging every confirmed/corrected finding in `minecraft.md`'s table
   before any mixin body is written, exactly per that file's own convention
   and the two prior features' own precedent for analogous unknowns.
4. **No fake/test-double seam exists for `SteamFriendsGateway`'s real
   implementation, `HostingLifecycle`, `HostingPresenceScanner`'s live
   Steamworks calls, or any of the Netty/mixin glue** — these are
   inherently not unit-testable on a plain JVM (real native calls, real
   `net.minecraft.*`/Netty types) and are **not** verified by this
   workflow's own test strategy at all (no manual in-game pass either, per
   Open Question 7) — an explicitly accepted, known gap for this iteration,
   not an oversight (spec Non-goals/Compatibility's own framing).

## Dependencies
- **No new external Maven/Gradle dependency.** steamworks4j remains pinned
  at `1.10.0` (`gradle.properties:41`), already resolved and vendored
  (`platform/*/build/processIncludeJars/steamworks4j-1.10.0.jar`, confirmed
  present via `Glob` this pass, Existing Implementation). The legacy
  `SteamNetworking`/`SteamFriends` surfaces this plan targets are both
  already-confirmed-present in that exact jar (Existing Implementation) —
  no version bump needed or proposed.
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:steam-world-hosting` → `api` (`api` configuration)
  - `features:steam-world-hosting` → `services` (`implementation` configuration)
  - `platform:fabric-26.2` → `features:steam-world-hosting` (`implementation`)
  - `platform:fabric-26.1` → `features:steam-world-hosting` (`implementation`)
  - `platform:fabric-1.21.11` → `features:steam-world-hosting` (`implementation`)
- **`services` module's existing `SteamFriendsGateway` addition** depends
  only on the already-declared `com.code-disaster.steamworks4j:steamworks4j`
  `api`-configuration dependency in `services/build.gradle` — no new Gradle
  coordinate.
- This feature does **not** depend on `features:steam-cloud-sync` in any
  direction; its only cross-feature relationship is the composition-root-only
  bridge to `features:friends-sidebar` (Decision 4), never a direct project
  dependency between the two feature modules themselves.

## Risks
1. **The six Compatibility-flagged Minecraft/Netty classes' exact
   method names/signatures are not `javap`-confirmed by this planning
   pass** (no Bash/decompiler tool available this session — same honest
   limitation both prior features' plans recorded for analogous jar
   inspections). This is, per the spec's own framing, "the single largest
   concrete unknown this spec carries forward" — implementation's mandatory
   first step, before writing any mixin body, is a real `javap -p` pass
   against all three resolved jars (paths in Existing Implementation),
   logging results in `minecraft.md`'s table. The 1.21.11 (Yarn/obfuscated)
   side is flagged by the spec itself as strictly higher-risk than
   26.1/26.2 (the prototype was Mojang-mapped-only; **zero** of its names
   have ever been checked against Yarn mappings).
2. **New entrypoint-ordering coupling**: `FriendsSidebarClientInitializer`
   now depends on `SteamWorldHostingClientInitializer` having already run
   (Decision 4) — a `fabric.mod.json` array-order dependency, not a
   compile-time import, but still a new fragility this feature introduces
   into an already-shipped feature's bootstrap. If `steam-world-hosting` is
   ever split into a separately toggleable/optional artifact, this ordering
   assumption breaks silently (returns Noop behavior, not a crash — the
   Noop hand-off pair means a missing/late `steam-world-hosting` degrades
   to "Join World never enabled," not an exception) but should be revisited
   then.
3. **`FriendsService.java`'s refactor (Decision 1) is a real, if mechanical,
   change to an already-shipped feature's Steam-facing class** — the task's
   own instruction explicitly flagged this as "a larger, riskier change...
   than this spec should silently decide," and it is accepted here per the
   user's own resolution of Open Question 4, but implementation should
   re-run `friends-sidebar`'s own existing manual in-game verification
   matrix (its plan's Test Strategy section) after this refactor, since no
   automated regression coverage exists for `FriendsService` today (that
   feature's own plan's Risk 8, unchanged) and this workflow performs no
   live in-game testing either (Open Question 7) — meaning this refactor's
   correctness is **not independently verified by this workflow at all**,
   only by code review + successful compilation. Flagged explicitly, not
   glossed over.
4. **The Netty/mixin glue's sheer size (four+ mixins, four+ supporting
   classes, ×3 platform modules = 24+ new platform-layer files) is the
   largest single chunk of new code this repo has attempted in one feature
   so far** — `steam-cloud-sync`'s own Group 6 (one mixin family) is the
   closest precedent, and even that needed three consecutive failed mixin
   designs before a working reflection-based fallback was found
   (`minecraft.md`'s own recorded history). This feature's Netty-layer
   mixins (`Connection`'s `Bootstrap` hijack in particular) are more
   invasive than anything previously attempted in this codebase and have
   **zero** prior-attempt history in this repo to draw on beyond the
   prototype itself (a different codebase, Mojang-mapped-only, never
   ported to Yarn) — expect multiple implementation iterations, not a
   single clean pass.
5. **No fake/test-double seam exists anywhere in this feature for its own
   real Steamworks/Netty behavior** (Test Strategy point 4) — by design,
   given the spec's own accepted verification-gap framing (Non-goals,
   Compatibility, Open Question 7), but worth flagging alongside
   `friends-sidebar`'s own identical, still-open Risk 8 from its plan: two
   features now share this same "no fake seam, relies on manual/no
   verification" trade-off for their respective Steamworks-touching
   classes.
6. **`SteamServerChannel`'s peer-connected-count tracking for
   `IntegratedServer.isPublished()` (FR1.4) crosses a threading boundary**
   (the poller thread observes P2P session state; `isPublished()` is called
   from the render/client thread) — needs a simple thread-safe counter
   (`AtomicInteger`), not a design risk exactly, but flagged since it's a
   new synchronization point this repo hasn't needed before (unlike
   `steam-cloud-sync`'s `CloudSyncWorker`, which used a queue/volatile
   snapshot for an analogous cross-thread need — same pattern applies here,
   not a novel problem).

## Acceptance Criteria
Mapped to the specification's functional requirements:

- **FR0.1–FR0.3** — Code review: `SteamWorldHostingClientInitializer` calls
  `SteamworksServiceHandoff.require()`/`SteamFriendsGatewayHandoff.require()`
  and never re-initializes Steamworks; when `!isSteamAvailable()` or
  `config.enabled() == false`, every constructed service is the `Noop*`
  variant and no additional `SteamFriends`/`SteamNetworking` native object is
  constructed beyond the shared gateway's own; `SteamWorldHostingConfigIO`
  round-trip + malformed-fallback tests pass.
- **FR1.1–FR1.5** — `HostGatewayTest` covers `canJoin` true/false against a
  fake friend-relationship predicate; `javap`-confirmed mixin targets exist
  and the module compiles with the world-load hook and `isPublished()`
  override in place (real in-game "does a friend actually connect" behavior
  is explicitly **not** verified by this workflow, Open Question 7).
- **FR2.1–FR2.3** — `ConnectStringCodecTest` covers encode/decode round-trip
  and malformed-input safety; code review confirms `HostingLifecycle.start()`/`stop()`
  call `setLocalRichPresence("connect", ...)`/`clearLocalRichPresence()`
  symmetrically.
- **FR3.1–FR3.3** — Code review confirms both join paths (native overlay
  callback, Friends Sidebar action) funnel into the same
  `SteamAmbientSession.connectToSteamPeer(long)` operation; compilation
  confirms the `ConnectionSteamChannelMixin`/handshake-stub mixins are
  registered in each module's `lazuli.mixins.json`. Real connect-success/
  disconnect-reason behavior is **not** verified in-game (Open Question 7).
- **FR4.1–FR4.3** — Code review + compilation: `FriendContextMenuWidget`'s
  index-3 slot enablement/click now routes through
  `FriendHostingStatusReader`/`WorldJoinRequester` instead of
  `FriendSidebarStateMachine`/`FriendActionListener.onJoin`;
  `HostingPresenceScannerTest` confirms `isFriendHosting` correctly reflects
  a fake gateway's connect-string values via `ConnectStringCodec`.
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules with every new dependency edge, entrypoint, and mixin
  registration in place; `.claude/context/minecraft.md` gains new rows
  recording the real, `javap`-confirmed shape of the six Compatibility-flagged
  classes per module (Risk 1) before this criterion is considered met.
- **Explicitly out of scope for this workflow's own acceptance sign-off**
  (Non-goals, Open Question 7): any claim that a real Steam P2P connection,
  NAT traversal, or a second real Steam account actually joining works end
  to end. This plan's Test Strategy cannot and does not produce that
  evidence.

## Open Questions
- None remaining from the specification's own explicitly-flagged
  planning-phase items — the networking-surface choice is resolved by
  Existing Implementation's real jar/source verification (legacy surface
  confirmed as the only option), the `api`/`services` placement is resolved
  as Decision 1 (flagged for the user's own final sign-off per the task's
  instruction, since the user's literal wording said `api/`), and the
  cross-feature bridging shape is resolved as Decision 4. Any further
  questions should surface during implementation as concrete
  compile-time/`javap`-confirmation findings (Risk 1 above), not as open
  design questions.
