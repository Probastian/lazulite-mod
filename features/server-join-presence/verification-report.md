# Verification Report — Server Join Presence

Compared against `specification.md` and `implementation-plan.md`. No live
in-game verification performed (spec Non-goals/plan Test Strategy — not
built into this workflow, same accepted gap every sibling
Steamworks-touching feature's own plan records).

## Implemented Requirements

- **FR0.1–FR0.3** — `ServerJoinPresenceClientInitializer` (all 3 platform
  modules) calls `SteamworksServiceHandoff.require()`/
  `SteamFriendsGatewayHandoff.require()`, never re-initializes Steamworks;
  when `!isSteamAvailable() || !config.enabled()`, publishes
  `NoopServerJoinRequester`/`NoopFriendServerPresenceReader` and returns.
  `ServerJoinPresenceConfigIO` round-trips + falls back to `DEFAULT` with a
  warning on malformed input (`ServerJoinPresenceConfigIOTest`, 7 cases, all
  green).
- **FR1.1–FR1.4** — `ServerSessionLifecycle.onJoinedRemoteServer`/
  `onLeftServer` set/clear Rich Presence `"connect"` symmetrically. Wired at
  each platform's `ClientPlayConnectionEvents.JOIN`/`DISCONNECT`, gated on
  `Minecraft.hasSingleplayerServer()` (26.1/26.2) /
  `MinecraftClient.isIntegratedServerRunning()` (1.21.11) so a singleplayer
  session never triggers this feature — an `advertising` flag (not a
  re-check of game state at `DISCONNECT` time) governs whether `onLeftServer`
  fires, so a `HostingLifecycle`-owned singleplayer session's own
  `"connect"` value is never raced/clobbered (FR1.2/FR1.3, plan Decision 3).
  `ServerConnectStringCodec`'s `"+lazuli_connect "` prefix is confirmed
  distinct from Steam World Hosting's `"+lazuli_join "` (FR1.4).
  `ServerConnectStringCodecTest` covers round-trip, malformed/wrong-prefix
  input, default-port normalization (8 cases, all green).
- **FR2.1–FR2.3** — `ServerJoinOperation.connectToServer` (per-module,
  mapping-correct: `ConnectScreen.startConnecting`/`ServerAddress.parseString`/
  `ServerData` on 26.1/26.2; `ConnectScreen.connect`/`ServerAddress.parse`/
  `ServerInfo` on 1.21.11, matching `.claude/context/minecraft.md`'s already-
  confirmed row) is invoked by `SteamJoinRequestDispatcher`'s route for this
  feature's own prefix. No new disconnect-reason translation key added, per
  spec (a failed connect surfaces as a normal vanilla Direct Connect failure).
- **FR3.1–FR3.3** — `ServerPresenceScanner` mirrors
  `HostingPresenceScanner`'s tick-gating shape, caches
  `Map<String, Set<Long>>`, implements `FriendServerPresenceReader.friendsOnServer`.
  `ServerPresenceScannerTest` explicitly confirms a friend advertising a
  Steam-World-Hosting-shaped (`"+lazuli_join "`) connect string is **not**
  counted (FR3.3), alongside normalization/counting/rescan cases (6 tests,
  all green).
- **Decision 1 (join-request dispatcher)** — `SteamJoinRequestDispatcher`
  (new, identical across all 3 modules) resolves the single-listener
  collision. `SteamWorldHostingClientInitializer` (all 3 modules) edited to
  register a route instead of calling `gateway.setJoinRequestedListener`
  directly; `ServerJoinPresenceClientInitializer` registers its own route the
  same way. **Risk 5's resolution is implemented exactly as decided**: the
  edited Steam World Hosting route returns `false` (not handled) when
  `ConnectStringCodec.decode(connect)` is empty, rather than preserving the
  old permissive fallback to `friendSteamId64` — confirmed at
  `platform/fabric-26.2/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`
  (identical on 26.1/1.21.11), with the behavior-change explicitly documented
  in a code comment at the edit site, not silently made.
- **Entrypoint registration** — confirmed present in all three
  `fabric.mod.json` `"client"` arrays:
  `platform/fabric-{26.2,26.1,1.21.11}/src/main/resources/fabric.mod.json`
  each list `"de.lazuli.ServerJoinPresenceClientInitializer"` immediately
  after `"de.lazuli.SteamWorldHostingClientInitializer"`. Per Decision 1 this
  ordering is not load-bearing, but the entry's mere presence is required and
  confirmed on all three modules — this feature is not silently inert on any
  platform.
- **Build wiring** — `settings.gradle` includes
  `features:server-join-presence`; its own `build.gradle` depends on `api`
  (api-configuration) and `services` (implementation); all three
  `platform/fabric-*/build.gradle` depend on it (implementation).

## Missing / Deferred (by design, not a gap)

- `ServersPanel` per-row friend-count UI — explicitly deferred to a
  follow-up pass per spec Non-goals/Future Extensions. `FriendServerPresenceReader`
  is published via `ServerJoinPresenceBridgeHandoff` and ready to be consumed
  whenever that follow-up lands; nothing in this feature currently calls
  `requireJoinRequester()`/`requirePresenceReader()` from anywhere but the
  handoff class itself — this is expected, not an oversight.
- No extension of the friend context-menu's "Join game"/"Invite to game"
  slots to this feature's connect-string shape — explicitly out of scope
  (spec Non-goals).

## Documentation Coverage
`specification.md`, `implementation-plan.md`, and this report are all
present under `features/server-join-presence/`; `README.md` summarizes the
feature and its relationship to `steam-world-hosting`.

## Tests
- `ServerConnectStringCodecTest` — 8 tests, green.
- `ServerPresenceScannerTest` — 6 tests, green (includes the FR3.3 exclusion
  case).
- `ServerJoinPresenceConfigIOTest` — 7 tests, green.
- No fake-seam tests exist for the platform-layer `ClientPlayConnectionEvents`
  wiring or `ConnectScreen` invocation (not unit-testable on a plain JVM,
  same accepted gap class as every sibling feature) — verified only by
  successful compilation across all three platform modules and manual code
  review of the wiring shape.

## API Compliance
`api/src/main/java/de/lazuli/api/serverjoinpresence/{ServerJoinRequester,FriendServerPresenceReader}`
match the plan's Public API shapes exactly. `features/server-join-presence`
imports only `api` and `services` (never another feature module or
`net.minecraft.*`/steamworks4j directly) — confirmed via the module's own
`build.gradle` and a read of every class's import list; layering rule
(`architecture.md` "Forbidden: Feature → Feature") is respected.

## Architecture Violations
None found. The one cross-feature touch point (`SteamJoinRequestDispatcher`)
is deliberately placed at the platform composition-root layer (`de.lazuli`
package, not inside either feature module), matching the plan's own stated
rationale and this repo's established ADR-0003-style bridging precedent.

## Build/Compile Verification (this pass, real runs)
- `:features:server-join-presence:test` — BUILD SUCCESSFUL, all new unit
  tests green.
- `:platform:fabric-26.2:compileJava`, `:platform:fabric-26.1:compileJava`,
  `:platform:fabric-1.21.11:compileJava` — each BUILD SUCCESSFUL
  independently.
- Full `./gradlew build` (all modules, all tests, 1.21.11's real
  `remapJar` pass included) — BUILD SUCCESSFUL, 67 tasks, no remap warnings
  (unlike Steam World Hosting's own `addEntry` invoker case — this feature
  has no mixins at all, so no remap-target ambiguity is possible).

## Follow-up Recommendations
1. Confirm `ClientPlayConnectionEvents.JOIN`'s `Minecraft.getCurrentServer()`/
   `MinecraftClient.getCurrentServerEntry()` reliably reflects a
   server-browser row's transient entry, not only saved-server joins (plan
   Risk 2) — a live/manual verification item, since this workflow performs
   none.
2. Confirm `ConnectScreen.startConnecting`/`ConnectScreen.connect`'s `null`
   owner-`Screen` argument behaves acceptably in a real launched client (plan
   Risk 3) — the IDE's null-analysis flags this as a type mismatch but it did
   not block compilation; a real launch is the only way to confirm the
   runtime behavior is acceptable (e.g. that cancel/back navigation from the
   connecting screen doesn't NPE).
3. When the `ServersPanel` friend-count follow-up is scheduled, it should
   consume `ServerJoinPresenceBridgeHandoff.requirePresenceReader()` rather
   than re-deriving a new scan path.
