# Implementation Plan — Server Join Presence

## Summary
Build `features/server-join-presence` (spec FR0–FR3), reusing the shared
`SteamFriendsGateway` seam (`services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`)
and closely mirroring `features/steam-world-hosting`'s own
`ConnectStringCodec`/`HostingLifecycle`/`HostingPresenceScanner` shapes, but
for a real multiplayer `host:port` address instead of a `SteamID64`, and with
no Netty/P2P/mixin layer at all (spec Non-goals/Networking). No implementation
code is written by this plan.

The one non-trivial coordination problem this plan resolves: `SteamFriendsGateway.setJoinRequestedListener`
accepts **at most one listener, and `SteamWorldHostingClientInitializer`
already unconditionally registers one** (`platform/fabric-26.2/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java:119-123`,
identical on the other two modules). This plan introduces a small,
composition-root-only, order-independent dispatcher holder (Decision 1) and
requires a small, mechanical edit to the already-shipped `SteamWorldHostingClientInitializer`
in all three modules to register through it instead of calling
`setJoinRequestedListener` directly.

## Existing Implementation

### Shared Steamworks seam (reused as-is, no changes needed beyond Decision 1's registration-site edit)
- `services/src/main/java/de/lazuli/services/steamworks/SteamFriendsGateway.java`
  already exposes every primitive this feature needs: `friendCount()`/
  `friendSteamId64At(int)`/`requestFriendRichPresence(long)`/
  `friendRichPresenceValue(long, String)` (lines 43-104, used identically by
  `HostingPresenceScanner`), `setLocalRichPresence(String, String)`/
  `clearLocalRichPresence()` (lines 113-116, used identically by
  `HostingLifecycle`), and `setJoinRequestedListener(BiConsumer<Long, String>)`
  (lines 163-173, **single-listener, last-writer-wins**, currently claimed by
  Steam World Hosting alone). No new method needed on this interface or its
  two implementations (`SteamworksSteamFriendsGateway`, `NoopSteamFriendsGateway`).

### Steam World Hosting's precedent classes (pattern to mirror, not modify beyond Decision 1)
- `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/ConnectStringCodec.java`
  — pure encode/decode, prefix `"+lazuli_join "`, unsigned-decimal `SteamID64`
  payload, tolerant of trailing content, never throws. This feature's own
  codec (Decision 2) copies this exact shape with a different prefix/payload.
- `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostingLifecycle.java`
  — `start()`/`start(boolean advertise)`/`stop()`/`updateAdvertising(boolean)`,
  all calling `gateway.setLocalRichPresence(CONNECT_KEY, ...)`/
  `gateway.clearLocalRichPresence()` symmetrically. This feature's lifecycle
  class (Decision 3) mirrors `start()`/`stop()` only (no advertise-toggle/
  join-policy equivalent — spec has no such Non-goal-scoped feature).
- `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostingPresenceScanner.java`
  — rate-limited (`DEFAULT_SCAN_INTERVAL_SECONDS = 5`) sweep over
  `gateway.friendCount()`/`friendSteamId64At(i)`, decodes each friend's
  `"connect"` value, caches a `Set<Long>`. This feature's scanner (Decision 4)
  mirrors the tick-gating shape exactly but caches a
  `Map<String hostPort, Set<Long>>` instead of a flat `Set<Long>`, per FR3.1.
- `platform/fabric-26.2/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`
  (identical shape on `fabric-26.1`/`fabric-1.21.11`) — composition root
  pattern this feature's own initializer copies: `SteamworksServiceHandoff.require()`
  + `SteamFriendsGatewayHandoff.require()`, config load, `Noop*`-vs-real
  construction gated on `isSteamAvailable() && config.enabled()`,
  `ClientTickEvents.END_CLIENT_TICK`/`ClientLifecycleEvents.CLIENT_STOPPING`
  registration. **Critically**, its lines 119-123 are the current sole
  registrant of `gateway.setJoinRequestedListener(...)` — the exact site
  Decision 1 edits.
- `platform/fabric-26.2/src/main/resources/fabric.mod.json:21-29` — confirmed
  current `"client"` entrypoint order: `SteamworksClientInitializer`,
  `SteamWorldHostingClientInitializer`, `SteamCloudSyncClientInitializer`,
  `RichPresenceClientInitializer`, `FriendsSidebarClientInitializer`,
  `ServerBrowserClientInitializer`, `MainMenuClientInitializer` (identical
  order on the other two modules per this repo's established convention).

### Rich Presence feature (unchanged, confirms non-collision)
- `features/rich-presence/specification.md`'s own Non-goals (`FR-RP5`) already
  states it never touches `"connect"` — confirmed by reading
  `RichPresenceClientInitializer.java` and `RichPresencePublisher`'s call
  sites: only `"status"` is ever written there. No coordination needed with
  that feature beyond what the spec already documents.

### Connect flow precedent (FR2.2's reused vanilla mechanism)
- `platform/fabric-26.2/src/main/java/de/lazuli/mainmenu/ServersPanel.java:566-574`
  — the exact reused pattern: `ServerAddress.parseString(...)` +
  `ConnectScreen.startConnecting(owner, Minecraft.getInstance(), address, serverData, false, null)`.
  This feature's own connect operation (Decision 5) calls this same static
  method shape from a context with no `Screen owner` available (a Steam
  callback, not a button click) — resolved by passing `null`/the current
  screen as `owner`, exact argument confirmed at implementation time via the
  same `javap`/source-read `ConnectScreen.startConnecting` signature check
  already available in this file (no new unknown beyond confirming `owner`
  tolerates `null` or that `Minecraft.getInstance().screen` is an acceptable
  substitute — Risk 3).

### Connect/disconnect lifecycle hook (the one real unknown this plan carries forward)
- No existing code in this repo currently hooks "client just joined/left a
  remote server" generically (`ServersPanel`'s own connect calls are the
  *initiating* side, not a completion/teardown signal). Fabric API's
  well-known, public `net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents`
  (`JOIN`/`DISCONNECT`) is the intended hook (a stable, long-shipped Fabric
  API surface, not a mixin target) — `JOIN` fires once the play-network
  handshake completes (real multiplayer or integrated server alike; must be
  filtered to exclude the integrated-server/singleplayer case, see Decision 3),
  `DISCONNECT` fires on teardown either way. This is **not** `javap`-confirmed
  against this repo's exact `fabric-api` version pin in this planning pass
  (no Bash/decompiler tool available this session, same honest limitation
  every sibling feature's plan has recorded for analogous unknowns) — flagged
  as Risk 1, implementation's mandatory first step for this feature.
- Distinguishing "real multiplayer server" from "the local integrated server"
  (spec FR1.1's "not the integrated/singleplayer server" carve-out): the
  standard Fabric-ecosystem check is `!Minecraft.getInstance().isLocalServer()`
  (or equivalently, `ClientPacketListener.getConnection()`'s
  `isMemoryConnection()`/the handler's own `isLocalPlayer` equivalent) at
  `JOIN` time — exact method name/signature not `javap`-confirmed this pass,
  same Risk 1 bucket.
- Resolving the actually-connected `host:port` at `JOIN` time: the intended
  source is `Minecraft.getInstance().getCurrentServer()` (a `ServerData`,
  already read the same way by `ServersPanel`'s own saved/browser rows,
  `ServersPanel.java` imports `net.minecraft.client.multiplayer.ServerData`)
  — `ServerData.ip` is expected to hold the address the player actually
  connected to for both saved-server and Direct-Connect-style joins. Whether
  this is reliably non-null/correctly populated for every join path
  (including a server-browser row's transient, unsaved `ServerData`,
  `ServersPanel.java:573-574`) is Risk 2, to be confirmed alongside Risk 1.

## Decisions

### 1. Shared join-request dispatcher (resolves the single-listener collision)
A new, tiny, per-platform-module class,
`platform/fabric-<version>/src/main/java/de/lazuli/SteamJoinRequestDispatcher.java`
(composition-root layer, not inside any feature — same layer
`SteamworksServiceHandoff`/`SteamFriendsGatewayHandoff` already occupy):
```java
public final class SteamJoinRequestDispatcher {
    public interface Route {
        /** @return true if this route recognized and handled the connect string. */
        boolean tryHandle(long friendSteamId64, String connect);
    }
    private static final List<Route> ROUTES = new CopyOnWriteArrayList<>();
    private static volatile boolean registered;

    public static void addRoute(Route route) { ROUTES.add(route); }

    /** Idempotent; safe to call from either feature's initializer regardless of entrypoint order. */
    public static synchronized void ensureRegisteredWith(SteamFriendsGateway gateway) {
        if (registered) return;
        registered = true;
        gateway.setJoinRequestedListener((friendId, connect) -> {
            for (Route route : ROUTES) {
                if (route.tryHandle(friendId, connect)) return;
            }
        });
    }
}
```
- `SteamWorldHostingClientInitializer` (all three modules) is edited: its
  existing lines 119-123
  (`gateway.setJoinRequestedListener((friendSteamId64, connect) -> {...});`)
  are replaced with
  `SteamJoinRequestDispatcher.addRoute((friendId, connect) -> { OptionalLong host = ConnectStringCodec.decode(connect); if (host.isEmpty()) return false; SteamAmbientSession.INSTANCE.connectToSteamPeer(host.getAsLong()); return true; });`
  followed by `SteamJoinRequestDispatcher.ensureRegisteredWith(gateway);` —
  same net behavior for its own format, now format-filtered instead of
  unconditional (today it ignores `connect`'s content entirely when
  `host.isEmpty()`, falling back to `friendSteamId64` — Risk 4 flags this
  subtle behavior-preservation nuance).
- `ServerJoinPresenceClientInitializer` (new, this feature) registers its own
  route the same way, decoding with its own codec (Decision 2), and also
  calls `ensureRegisteredWith(gateway)` (idempotent — whichever initializer
  runs first wins the actual `setJoinRequestedListener` call; order in
  `fabric.mod.json` is no longer load-bearing for this specific mechanism,
  unlike Steam World Hosting's own Decision 4/Risk 2 ordering dependency,
  which is unaffected by this change).
- **Why not simply reorder `fabric.mod.json` instead**: rejected — a
  last-registrant-wins design silently breaks the *other* feature's join path
  the moment entrypoint order ever changes again for unrelated reasons (e.g. a
  future feature insertion). The route-list design is robust to reordering by
  construction, at the cost of one new small shared class per platform
  module — judged worth it given this is the second feature to need the same
  single-listener seam and a third is plausible (graduate-on-second-use-shaped
  reasoning, informal here since `SteamJoinRequestDispatcher` is deliberately
  small enough not to need a `services/`-layer promotion).

### 2. Connect-string format: `"+lazuli_connect <host>:<port>"`
`features/server-join-presence/services/ServerConnectStringCodec.java` —
mirrors `ConnectStringCodec`'s exact shape (`encode`/`decode`, never throws,
tolerant of trailing content):
```java
static final String PREFIX = "+lazuli_connect ";
static String encode(String host, int port) { return PREFIX + host + ":" + port; }
static Optional<HostPort> decode(String value) { ... } // empty on null/blank/wrong-prefix/unparsable
record HostPort(String host, int port) {}
```
- Distinguishable from Steam World Hosting's `"+lazuli_join <steamId64>"`
  prefix by construction (different literal prefix string) — satisfies spec
  FR1.4's dispatcher-disambiguation requirement directly; `SteamJoinRequestDispatcher`'s
  route list (Decision 1) is exactly how "try each feature's codec in turn"
  (spec Architecture) is realized concretely.
- Default-port normalization (spec FR3.2's "trailing default-port `:25565`"
  concern): `encode`/`decode` always include an explicit port (vanilla's own
  default `25565` if none was specified), and `friendsOnServer(String)`'s
  input is normalized through the same `ServerAddress.parseString(...)`-then-
  re-stringify path `ServersPanel` already uses for saved/browser rows, so a
  caller passing `"example.com"` and a friend's decoded `"example.com:25565"`
  match. `ServerAddress` (from `net.minecraft.client.multiplayer.resolver`,
  already imported by `ServersPanel.java:22`) is reused for this
  normalization rather than hand-rolling host/port parsing.

### 3. Multiplayer-vs-singleplayer discrimination + connect/disconnect hook shape
`features/server-join-presence/services/ServerSessionLifecycle.java` (mirrors
`HostingLifecycle`'s `start()`/`stop()` shape, no advertise-toggle):
```java
public final class ServerSessionLifecycle {
    void onJoinedRemoteServer(String host, int port) { gateway.setLocalRichPresence("connect", ServerConnectStringCodec.encode(host, port)); }
    void onLeftServer() { gateway.clearLocalRichPresence(); }
}
```
- The platform composition root, not this plain-JVM class, decides *whether*
  a given `JOIN` event qualifies as "remote server" (Existing Implementation's
  `isLocalServer()`-shaped check) before calling `onJoinedRemoteServer(...)` —
  keeps `ServerSessionLifecycle` itself free of any `net.minecraft.*` import
  (NFR1-equivalent, spec Goals precedent).
- **FR1.2's race carve-out** ("don't clear a value `HostingLifecycle` just
  set for a newly-started singleplayer session"): resolved structurally, not
  by explicit coordination — `DISCONNECT` only fires for a connection that
  was actually a remote multiplayer one (this feature never calls
  `onJoinedRemoteServer` for a local/integrated-server session in the first
  place, so it correspondingly never calls `onLeftServer()` for one either;
  the two features' event sources are already disjoint by construction, per
  spec FR1.3's own "provably safe by game-state exclusivity" framing) — no
  new shared mutex/flag needed.

### 4. Friend-count scanner
`features/server-join-presence/services/ServerPresenceScanner.java`
(implements `FriendServerPresenceReader`, mirrors `HostingPresenceScanner`'s
tick-gating exactly, default interval `5`s):
```java
public final class ServerPresenceScanner implements FriendServerPresenceReader {
    private volatile Map<String, Set<Long>> friendsByServer = Map.of(); // key: normalized "host:port"
    public void tick() { /* rate-limited, same shape as HostingPresenceScanner.tick() */ }
    private void scanNow() { /* iterate gateway friends, decode each "connect", skip Steam-World-Hosting-shaped values (FR3.3) */ }
    @Override public int friendsOnServer(String hostPort) {
        return friendsByServer.getOrDefault(normalize(hostPort), Set.of()).size();
    }
}
```
- FR3.3 ("never count a Steam-World-Hosting-shaped friend"): implemented by
  trying `ServerConnectStringCodec.decode(...)` first and only counting a
  friend whose value decodes successfully under **this** feature's own
  prefix — a friend's value that instead matches `"+lazuli_join "` simply
  fails this decode and is skipped, no explicit cross-feature import needed
  (each codec only recognizes its own prefix, by construction).

### 5. Client-side connect operation (FR2.1/FR2.2)
`platform/fabric-<version>/src/main/java/de/lazuli/serverjoinpresence/ServerJoinOperation.java`
— one method, `void connectToServer(String host, int port)`, calling
`ConnectScreen.startConnecting(Minecraft.getInstance().screen, Minecraft.getInstance(), ServerAddress.parseString(host + ":" + port), new ServerData(host, host + ":" + port, ServerData.Type.OTHER), false, null)`
(exact `ServerData` constructor args a small implementation-time detail,
mirroring `ServersPanel.java:573-574`'s own transient-row construction for
server-browser entries) — invoked by:
1. `SteamJoinRequestDispatcher`'s route for this feature's own prefix
   (Decision 1/2).
2. Nothing else in this pass (spec Non-goals: no new UI entry point yet).

## Files to Create

### `api` module
- `api/src/main/java/de/lazuli/api/serverjoinpresence/ServerJoinRequester.java`
  — `void joinServer(String host, int port);` (spec Public API item 1).
- `api/src/main/java/de/lazuli/api/serverjoinpresence/FriendServerPresenceReader.java`
  — `int friendsOnServer(String hostPort);` (spec Public API item 1).

### `features/server-join-presence` module (new Gradle subproject)
- `features/server-join-presence/build.gradle` — `dependencies { api project(':api'); implementation project(':services') }`
  (identical shape to `features/steam-world-hosting/build.gradle`).
- `features/server-join-presence/README.md`
- `features/server-join-presence/api/` sub-package
  (`de.lazuli.features.serverjoinpresence.api`): `ServerJoinPresenceConfig.java`
  — record: `enabled` (default `true`) + `DEFAULT` constant.
- `features/server-join-presence/config/` sub-package:
  `ServerJoinPresenceConfigIO.java` — `config/server-join-presence.json`
  load/parse/serialize, malformed → defaults + warning, never throws (same
  `SteamWorldHostingConfigIO`-shaped precedent).
- `features/server-join-presence/services/` sub-package:
  - `ServerConnectStringCodec.java` (Decision 2)
  - `ServerSessionLifecycle.java` (Decision 3)
  - `ServerPresenceScanner.java` (Decision 4, implements `FriendServerPresenceReader`)
  - `NoopFriendServerPresenceReader.java`, `NoopServerJoinRequester.java` —
    FR0.2/FR0.3 disabled-state pair.
- `features/server-join-presence/tests/` (`src/test/java/de/lazuli/features/serverjoinpresence/...`):
  `ServerConnectStringCodecTest` (round-trip, malformed/blank/wrong-prefix →
  empty, default-port normalization), `ServerPresenceScannerTest` (fake
  `SteamFriendsGateway`-shaped seam, asserts `friendsOnServer` reflects
  decoded friends and **excludes** a fake friend whose value is
  Steam-World-Hosting-shaped, FR3.3), `ServerJoinPresenceConfig`/`ConfigIO`
  round-trip + malformed-fallback tests (mirrors every prior feature's own
  config-test shape).

### Platform modules (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/SteamJoinRequestDispatcher.java`
  — Decision 1, new shared class (not feature-owned).
- `platform/fabric-<version>/src/main/java/de/lazuli/ServerJoinPresenceBridgeHandoff.java`
  — per-module hand-off (`publish`/`require`, same shape as
  `WorldHostingBridgeHandoff`), carrying `ServerJoinRequester`/
  `FriendServerPresenceReader` (or their `Noop` pair).
- `platform/fabric-<version>/src/main/java/de/lazuli/ServerJoinPresenceClientInitializer.java`
  — new `ClientModInitializer`; composition root. Calls
  `SteamworksServiceHandoff.require()` + `SteamFriendsGatewayHandoff.require()`,
  loads config, constructs the real or `Noop` service set, registers a
  `ClientPlayConnectionEvents.JOIN` handler (filters out the
  integrated-server case, Decision 3) calling
  `ServerSessionLifecycle.onJoinedRemoteServer(...)`, a `DISCONNECT` handler
  calling `onLeftServer()`, registers `ServerPresenceScanner`'s tick on
  `ClientTickEvents.END_CLIENT_TICK`, registers this feature's route with
  `SteamJoinRequestDispatcher` (Decision 1), and publishes
  `ServerJoinPresenceBridgeHandoff`.
- `platform/fabric-<version>/src/main/java/de/lazuli/serverjoinpresence/ServerJoinOperation.java`
  — Decision 5.

## Files to Modify
- `settings.gradle` — add `include 'features:server-join-presence'`.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`
  — lines 119-123 rewritten to register a route via
  `SteamJoinRequestDispatcher.addRoute(...)` + `ensureRegisteredWith(gateway)`
  instead of calling `gateway.setJoinRequestedListener(...)` directly
  (Decision 1). **No other change** to this file — `HostingLifecycle`/
  `HostGateway`/`HostingPresenceScanner`/the Friends Sidebar bridge are all
  untouched.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/resources/fabric.mod.json` —
  `"client"` array gains one new entry,
  `"de.lazuli.ServerJoinPresenceClientInitializer"`. Position is **not**
  load-bearing relative to `SteamWorldHostingClientInitializer` (Decision 1
  makes the dispatcher registration order-independent) but must still be
  after `SteamworksClientInitializer` (needs both hand-offs) — placed
  immediately after `SteamWorldHostingClientInitializer` for readability
  (grouping the two `"connect"`-key owners together), giving:
  `SteamworksClientInitializer`, `SteamWorldHostingClientInitializer`,
  `ServerJoinPresenceClientInitializer`, `SteamCloudSyncClientInitializer`,
  `RichPresenceClientInitializer`, `FriendsSidebarClientInitializer`,
  `ServerBrowserClientInitializer`, `MainMenuClientInitializer`.
- `platform/fabric-{26.2,26.1,1.21.11}/build.gradle` — each gains
  `implementation project(':features:server-join-presence')`.

## Interfaces
- `api/.../serverjoinpresence/{ServerJoinRequester, FriendServerPresenceReader}`
  — the two new top-level `api` contracts (spec Public API item 1).
- `features/server-join-presence/services/{ServerConnectStringCodec, ServerSessionLifecycle, ServerPresenceScanner}`
  — plain-JVM-testable core (mirrors Steam World Hosting's `ConnectStringCodec`/
  `HostingLifecycle`/`HostingPresenceScanner` triad).

## Services
No new `services/`-layer class. `SteamFriendsGateway` already exposes every
primitive this feature needs (Existing Implementation) — this feature is a
**third** consumer (after `friends-sidebar`, `steam-world-hosting`) of an
already-graduated seam, not a new graduation trigger.

## Tests

### Test Strategy
Mirrors `features/steam-world-hosting/plan.md`'s own accepted test-strategy
shape (spec Non-goals: no live in-game testing performed by this workflow):
1. **Unit tests (plain JVM)** — `ServerConnectStringCodecTest`,
   `ServerPresenceScannerTest` (FR3.3's exclusion case is the one
   test worth calling out explicitly — a fake gateway returning a
   `"+lazuli_join 123"`-shaped value for one friend and a
   `"+lazuli_connect host:25565"`-shaped value for another must yield
   `friendsOnServer("host:25565") == 1`, not `2`), plus
   `ServerJoinPresenceConfig`/`ConfigIO` round-trip tests.
2. **Compilation across all three platform modules** — `gradlew build` must
   succeed with the new `features:server-join-presence` dependency, the new
   `fabric.mod.json` entrypoint, and the modified
   `SteamWorldHostingClientInitializer` in place on all three modules.
3. **No fake/test-double seam** for `ClientPlayConnectionEvents` wiring,
   `ConnectScreen.startConnecting` invocation, or any other
   `net.minecraft.*`-touching glue in the platform modules — not unit-testable
   on a plain JVM, and this workflow performs no live in-game verification
   (spec Non-goals) — an explicitly accepted gap, not an oversight.

## Dependencies
- **No new external Maven/Gradle dependency.** `net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents`/
  `net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents`
  (already used by every sibling feature's initializer) and
  `net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents`
  (new to this feature, but part of the same already-declared `fabric-api`
  dependency every platform module already has, `fabric.mod.json:38`'s
  `"fabric-api": "*"` — no version bump, no new Maven coordinate).
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:server-join-presence` → `api` (`api` configuration)
  - `features:server-join-presence` → `services` (`implementation` configuration)
  - `platform:fabric-26.2`/`fabric-26.1`/`fabric-1.21.11` →
    `features:server-join-presence` (`implementation`)
- Does **not** depend on `features:steam-world-hosting`, `features:rich-presence`,
  `features:friends-sidebar`, or `features:main-menu` in either direction at
  the module level — only the new platform-layer `SteamJoinRequestDispatcher`
  class (composition-root layer, not a feature module) is shared with
  `steam-world-hosting`'s own composition root.

## Risks
1. **`ClientPlayConnectionEvents.JOIN`/`DISCONNECT`'s exact availability/
   signature in this repo's pinned `fabric-api` version, and the exact
   integrated-server-vs-remote-server discriminator method, are not
   `javap`/source-confirmed by this planning pass** (no Bash/decompiler tool
   available this session) — implementation's mandatory first step, before
   writing `ServerJoinPresenceClientInitializer`, is confirming these against
   the resolved `fabric-api` jar per each of the three platform modules,
   logging findings in `.claude/context/minecraft.md`'s table per this repo's
   own convention. This is the single largest concrete unknown this plan
   carries forward — analogous in kind (though much smaller in scope) to
   Steam World Hosting's own Risk 1.
2. **`Minecraft.getInstance().getCurrentServer()`'s reliability as the source
   of the just-joined address, especially for a server-browser row's
   transient `ServerData`** (Existing Implementation) — if this proves
   unreliable for some join paths, an alternative (e.g. capturing the address
   at the `ConnectScreen.startConnecting(...)` call site itself, requiring a
   small change to `ServersPanel`/server-browser's own connect call sites to
   thread the address through) may be needed; flagged as a fallback design,
   not adopted by default since it would touch more existing files than the
   event-based approach.
3. **`ConnectScreen.startConnecting`'s exact parameter tolerance for a `null`/
   non-modal-owner `Screen` when invoked from a Steam callback context**
   (Decision 5) rather than a real button click — not confirmed this pass;
   implementation should verify against the resolved Minecraft jar (the same
   `javap`-before-mixin-body discipline, though this is a plain API call, not
   a mixin, so lower risk than Risk 1).
4. **Editing `SteamWorldHostingClientInitializer` (already-shipped,
   already-verified code) is a real, if small and mechanical, change**
   (Decision 1) — same risk class Steam World Hosting's own plan flagged for
   its `FriendsService` refactor (its Risk 3): no automated regression
   coverage exists for this exact code path (`SteamJoinRequestDispatcher`
   itself is trivially unit-testable in isolation, but the *edited* call site
   inside `SteamWorldHostingClientInitializer` is platform/composition-root
   code with no fake-seam test, per that feature's own accepted Risk 5/Test
   Strategy point 4 framing) — verified only by code review + compilation,
   not by re-running any live in-game join test (out of scope for this
   workflow either way, spec Non-goals).
5. **Behavior-preservation nuance in Decision 1's edit**: the *current*
   `SteamWorldHostingClientInitializer` code falls back to using the inviting
   `friendSteamId64` directly when `ConnectStringCodec.decode(connect)` is
   empty (`platform/fabric-26.2/.../SteamWorldHostingClientInitializer.java:120-122`,
   `long target = host.isPresent() ? host.getAsLong() : friendSteamId64;`) —
   i.e. it currently never actually returns "not handled" for *any* input.
   The route-based redesign (Decision 1) must decide whether to preserve this
   permissive fallback (making Steam World Hosting's route always report
   `true`/"handled", so it would need to be registered **last** to avoid
   swallowing this feature's own connect strings — reintroducing an ordering
   dependency Decision 1 was meant to remove) or tighten it to only report
   `true` when `ConnectStringCodec.decode(...)` actually succeeds (silently
   changing existing behavior for the — likely never-exercised in practice —
   case of a non-Lazuli connect-string value reaching this callback). **This
   plan resolves this in favor of tightening** (`tryHandle` returns `true`
   only on a successful decode) since the permissive fallback's only
   plausible current trigger is a corrupted/foreign Rich Presence value, not
   a real user-facing behavior worth preserving, and preserving it would
   silently reintroduce the exact ordering fragility this decision exists to
   remove — flagged explicitly per this repo's own "don't silently decide a
   behavior change on the spec/plan's author's behalf" convention, for the
   user's visibility, not buried in Files to Modify.

## Acceptance Criteria
Mapped to the specification's functional requirements:

- **FR0.1–FR0.3** — Code review: `ServerJoinPresenceClientInitializer` calls
  `SteamworksServiceHandoff.require()`/`SteamFriendsGatewayHandoff.require()`
  and never re-initializes Steamworks; when `!isSteamAvailable()` or
  `config.enabled() == false`, every constructed service is the `Noop*`
  variant; `ServerJoinPresenceConfigIO` round-trip + malformed-fallback tests
  pass.
- **FR1.1–FR1.4** — Code review confirms `ServerSessionLifecycle.onJoinedRemoteServer`/
  `onLeftServer` are called symmetrically from the `JOIN`/`DISCONNECT`
  handlers, gated on the integrated-server discriminator (Risk 1);
  `ServerConnectStringCodecTest` covers encode/decode round-trip and
  malformed-input safety.
- **FR2.1–FR2.3** — Code review confirms `SteamJoinRequestDispatcher`'s route
  list correctly dispatches a `"+lazuli_connect "`-prefixed value to
  `ServerJoinOperation.connectToServer(...)`, which calls
  `ConnectScreen.startConnecting(...)` (Decision 5); compilation confirms
  `SteamWorldHostingClientInitializer`'s edited call site (Decision 1) still
  compiles and both routes coexist. Real connect-success behavior is **not**
  verified in-game (spec Non-goals).
- **FR3.1–FR3.3** — `ServerPresenceScannerTest` confirms `friendsOnServer(...)`
  correctly reflects a fake gateway's `"connect"` values via
  `ServerConnectStringCodec`, and explicitly confirms a Steam-World-Hosting-
  shaped friend value is excluded from the count (FR3.3).
- **Compatibility** — `gradlew build` succeeds for all three platform modules
  with every new dependency edge, entrypoint, and the edited
  `SteamWorldHostingClientInitializer` in place;
  `.claude/context/minecraft.md` gains new rows recording the
  `javap`/source-confirmed shape of `ClientPlayConnectionEvents`'s exact
  surface and the integrated-server discriminator (Risk 1) before this
  criterion is considered met.
- **Explicitly out of scope for this workflow's own acceptance sign-off**
  (spec Non-goals): any claim that a real friend's client actually receives
  and successfully acts on the native "Join Game" button, or that
  `friendsOnServer(...)`'s count is correct against a real second Steam
  account's live session. This plan's Test Strategy cannot and does not
  produce that evidence.

## Open Questions
- None blocking planning's own sign-off. Risks 1-3 are implementation-phase
  `javap`/source-confirmation steps (this repo's own established discipline
  for exactly this class of unknown, not a design ambiguity needing user
  input), and Risk 5's behavior-preservation call is resolved explicitly
  within this plan (tightening, not preserving the permissive fallback) —
  flagged for the user's visibility per the task's own instruction, not left
  as an unresolved question for a later phase.
