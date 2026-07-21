# Implementation Plan — Friends Sidebar v1.3: "Who can join" dropdown

Scope: `features/friends-sidebar/specification.md`'s v1.3 amendment (lines
9-133, requirements FR7.1-FR7.13, NFR8-NFR9). This plan extends the feature's
existing v1/v1.1/v1.2 implementation (`features/friends-sidebar/plan.md`,
already shipped) and touches `features/steam-world-hosting`'s existing
`HostGateway`/`HostingLifecycle`/`WorldHostingHookHolder` only at the level
the v1.3 spec already scoped (Public API items 7-10). No implementation code
is written by this plan.

## Existing Implementation

All findings below are from reading the real, currently-checked-out code
(not the spec's illustrative citations, some of which have since diverged —
corrections called out explicitly).

### `features/steam-world-hosting` (enforcement side)
- `HostGateway` (`features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/HostGateway.java`):
  `public HostGateway(LongPredicate friendRelationshipLookup)`,
  `public boolean canJoin(long friendSteamId64)`. No static factory exists
  yet (Public API item 9 is net-new). Zero Minecraft/steamworks4j import
  (NFR8-compatible already). Existing test:
  `features/steam-world-hosting/src/test/java/.../HostGatewayTest.java`
  (plain `LongPredicate` injection tests, AssertJ) — the precedent this
  plan's new tests follow.
- `HostingLifecycle` (`.../services/HostingLifecycle.java`): `start()`
  unconditionally calls `gateway.setLocalRichPresence(CONNECT_KEY,
  ConnectStringCodec.encode(id))`; `stop()` unconditionally calls
  `gateway.clearLocalRichPresence()` **and** flips `hosting = false`. There
  is no way today to clear/suppress the Rich Presence key while remaining
  "hosting" — `stop()` is the only clearing path and it also tears down
  hosting state, which FR7.12/FR7.13 forbid doing merely to toggle
  advertising. No `HostingLifecycleTest` exists yet (no fake-seam test
  double for `SteamFriendsGateway` in this feature's tests currently).
- `WorldHostingHookHolder` (`platform/fabric-<version>/.../worldhosting/WorldHostingHookHolder.java`,
  identical across all three modules): `publish(HostingLifecycle, LongPredicate)`
  stores both as `static volatile` fields, called **once**, at startup, by
  `SteamWorldHostingClientInitializer`. `onWorldLoad()` calls
  `lifecycle.start()` unconditionally (always advertises). There is no
  existing "republish"/"update" method — `canJoin`/`lifecycle` are set once
  and never touched again after startup. This is the concrete gap FR7.11
  ("changing the dropdown... takes effect... without requiring a full game
  restart") must close.
- `SteamWorldHostingClientInitializer` (`platform/fabric-<version>/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`):
  loads only its own `steam-world-hosting.json` config; **does not** read
  `friends-sidebar.json`/`FriendsSidebarConfig` at all today. **Correction
  vs. the spec's Architecture section** (spec line 68-70, "already reads
  FriendsSidebarConfig... to bridge FR4.2's check"): the real FR4.2 "is this
  friend hosting" bridge reads Rich Presence via `HostingPresenceScanner`
  (a `FriendHostingStatusReader`), not `FriendsSidebarConfig` at all — the
  spec's premise that a `FriendsSidebarConfig` read already exists in this
  class is incorrect; this plan's own new `joinPolicy()` read (Decision 2
  below) is a **first**, not a **second**, read of that file from this
  class.
- Registered as the **third** `"client"` entrypoint in
  `fabric.mod.json`, before `FriendsSidebarClientInitializer` (fourth) —
  confirmed in all three modules' `fabric.mod.json`. This ordering means
  `SteamWorldHostingClientInitializer.onInitializeClient()` runs, and
  `WorldHostingHookHolder.publish(...)` executes, **before**
  `FriendsSidebarClientInitializer` runs — i.e. before `FriendsSidebarFacade`
  exists. Bridge point 1 (initial policy at startup) must therefore read
  `friends-sidebar.json` directly (via a second, independent
  `FriendsSidebarConfigIO().load(...)` call from
  `SteamWorldHostingClientInitializer` itself), not by reaching into a
  `FriendsSidebarFacade` object that doesn't exist yet. This second
  concurrent load of the same file is safe: `FriendsSidebarConfigIO.load()`
  is idempotent (create-with-defaults-if-missing, else read-only) and single
  -threaded (`onInitializeClient()` calls are sequential, not concurrent).
- `SteamServerChannel.onP2PSessionRequest` (`platform/fabric-<version>/.../worldhosting/SteamSession.java`
  — spec cites `SteamServerChannel.java:97`; the real class holding the
  `canJoin` predicate check at accept-time is `SteamSession`, constructed as
  `new SteamSession(handler, group, canJoin)` in
  `WorldHostingHookHolder.onWorldLoad()` — **correction vs. spec's file name
  citation**, the predicate itself and its accept/reject semantics are
  unchanged, only the class name citation is stale) is unmodified by this
  plan — it already accepts an injected `LongPredicate` and needs no change;
  only *which* predicate is threaded in changes.

### `features/friends-sidebar` (UI-host side)
- `FriendsSidebarConfig` (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/api/FriendsSidebarConfig.java`):
  `record FriendsSidebarConfig(boolean enabled, int refreshIntervalSeconds)`,
  `DEFAULT = new FriendsSidebarConfig(true, 5)`. This is the **feature-internal**
  `api` sub-package (`de.lazuli.features.friendssidebar.api`), distinct from
  the top-level `api` Gradle module (`de.lazuli.api.friends`, which holds
  only `FriendSummary`/`FriendSidebarHook`/`FriendActionListener`/
  `FriendsSidebarZOrder` — the types that cross the Platform boundary).
  **Resolves spec Public API item 7's own "features/friends-sidebar/api"
  wording**: this is the feature-internal sub-package (same one
  `FriendsSidebarConfig` already lives in), not the top-level `api` module —
  `JoinPolicy` belongs alongside `FriendsSidebarConfig`, not in
  `de.lazuli.api.friends`, since (like `FriendsSidebarConfig` itself) it
  never needs to cross the Platform/Feature boundary as a *type* on its own,
  only its *value* does (read directly by composition-root code that already
  imports `de.lazuli.features.friendssidebar.api.*`, exactly as
  `FriendsSidebarClientInitializer` already imports `FriendsSidebarConfig`
  today).
- `FriendsSidebarConfigIO` (`.../config/FriendsSidebarConfigIO.java`): hand-rolled
  recursive-descent parser for the exact two-key schema, fails closed to
  `FriendsSidebarConfig.DEFAULT` on **any** malformed input including
  "missing required key" (`enabled`/`refreshIntervalSeconds` both currently
  required, unknown keys rejected). **No `save`/write-after-construction
  method exists** — `load()`'s only write path is "file doesn't exist yet,
  write `DEFAULT`"; there is no existing save-on-change path anywhere in
  this codebase (confirmed — the spec's own Configuration section flags this
  exact gap). This plan adds one (Decision 4).
- `FriendsSidebarFacade` (`.../services/FriendsSidebarFacade.java`): the one
  object platform Version Adapters hold; constructor
  `FriendsSidebarFacade(FriendsDataSource dataSource, FriendSidebarStateMachine stateMachine)`,
  `refresh()` pulls `friends()`/`localProfile()` each tick, `isEnabled()`/
  `isSteamAvailable()` gate rendering. No join-policy state today.
- `FriendSidebarWidget` (one per platform module,
  `platform/fabric-<version>/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`
  — read in full for `fabric-26.2`; `fabric-26.1`/`fabric-1.21.11` are the
  Yarn/Mojang-mapped structural twins per existing convention): a single
  `AbstractWidget` (26.x) / presumed `ClickableWidget` (1.21.11, not
  independently re-confirmed this pass, low risk — mirrors the original
  plan's already-closed Risk 6) subclass, hand-drawn, not composed of
  vanilla sub-widgets — **confirms the spec's own UI section framing**: no
  `CyclingButtonWidget` usage exists anywhere in this file or repo (re-confirmed
  via `Grep` for `CyclingButtonWidget` across `.claude/`, zero hits, same
  finding the spec's own research already reported). Renders: pinned
  own-profile row (`ROW_HEIGHT` = `DISPLAY_SIZE + ROW_PADDING*2` = 28px),
  then a 1px separator (`SEPARATOR_GAP` = 2 both sides,
  `SEPARATOR_HEIGHT` = 1), then the scrollable friend list, sized via
  **static** `private static int listTopOffset()` = `ROW_HEIGHT + SEPARATOR_GAP*2 + SEPARATOR_HEIGHT`
  = 32px. Width states: `COLLAPSED_WIDTH` = 28, `EXPANDED_WIDTH` = 180,
  animated between them (`animatedWidth`, `WIDTH_ANIM_PX_PER_SECOND`). Also
  has a `handleOnly` mode (small 10x28 click-to-open handle) on every
  allow-listed screen except `TitleScreen`/`PauseScreen` (v1.2/handle-only
  addition, not in the original plan doc's earlier text) — the join-policy
  control must work correctly in both `handleOnly=false` (always-open) and
  `handleOnly=true` (opens on click/hover) sidebar instances, since both are
  live instances of the same class. `renderNow(...)` is called manually,
  once per frame, from the injector's `ScreenEvents.afterExtract` hook (not
  vanilla's normal render pass) — this is why no tooltip mechanism exists
  anywhere in this class: vanilla's built-in post-widget tooltip pass never
  runs for this widget (Decision 6 below adopts the same hand-drawn
  convention rather than introducing a first-ever tooltip dependency).
  `mouseClicked`, top-of-method, already gates the whole widget on
  `facade.isEnabled() && facade.isSteamAvailable()` before any row hit-test
  runs — the new dropdown hit-test only needs to be added *inside* that
  already-gated block, which structurally satisfies FR7.6 (status state ->
  `isSteamAvailable()==false` -> widget already returns `false`/renders
  `drawStatus(...)` instead of content, so the dropdown is already
  unreachable in that state with zero extra code).
- `FabricFriendsSidebarInjector` (one per module,
  `platform/fabric-<version>/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`):
  Pattern-1 `ScreenEvents.AFTER_INIT` injector; constructs
  `FriendSidebarWidget` with `(facade, avatarTextureCache, rowClickListener, handleOnly)`.
  Unaffected by this revision except that the `FriendSidebarWidget`
  constructor call site gains no new parameter (join policy flows through
  `facade`, not a new constructor arg — Decision 3).
- `FriendsSidebarClientInitializer` (one per module,
  `platform/fabric-<version>/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`):
  composition root; loads config, builds `FriendsService`/`NoopFriendsService`,
  constructs `FriendsSidebarFacade`, ticks it, then constructs
  `FabricFriendsSidebarInjector`. **This is where bridge point 2's wiring is
  added** (Decision 5) — it already has `SteamFriendsGateway gateway` in
  scope (`SteamFriendsGatewayHandoff.require()`) and can import
  `features:steam-world-hosting`'s `HostGateway`/`JoinGatePolicy` and this
  module's own `de.lazuli.worldhosting.WorldHostingHookHolder` directly, the
  same cross-Feature-import license `SteamWorldHostingClientInitializer`
  already exercises for `FriendHostingStatusReader`/`WorldJoinRequester`
  (ADR-0003).
- `FriendSidebarStateMachine` (`.../services/FriendSidebarStateMachine.java`):
  the feature's existing plain-JVM pure-logic class (hover/expand,
  `statusColorArgb`, `statusLabel`, `sortForDisplay`, `clampScrollPixels`) —
  the natural home for the new pure "cycle to next `JoinPolicy`" function
  (Decision 3), mirroring this class's existing role and its own
  `FriendSidebarStateMachineTest` precedent.
- **No dropdown/list-popup widget precedent anywhere in this repo**
  (re-confirmed, spec's own claim holds).

### Cross-feature bridging precedent (ADR-0003 shape, unchanged by this plan)
`features/steam-world-hosting/specification.md`'s own Architecture section
("Cross-feature bridging (Friends Sidebar <-> Steam World Hosting)") already
establishes: neither feature imports the other's classes; the platform
composition root is the only place both features' concrete types are
imported together. This plan's two new bridge points (Architecture below)
are additional instances of that same already-licensed shape, not a new
exception.

### Risk carried forward unmodified from the spec
`FriendSidebarWidget` is shared/actively evolving (v1.2 handle-only mode
landed after the original implementation plan was written). `features/server-browser`
currently contains **only** `specification.md` (`Glob` confirms no
`src/`/implementation exists yet) — no live collision today. Still,
**before editing `FriendSidebarWidget`/`FabricFriendsSidebarInjector` in any
module, implementation must run `git status`/`git diff` and confirm no
uncommitted changes exist in `features/server-browser/` or those platform
files from concurrent work**, per the task's own standing instruction and
this repo's risk-tracking convention (see also project-memory note "Steam
World Hosting: pending live test").

## Decisions on the Open Questions

### Decision 1 — Dropdown control shape: hand-drawn cycling label, not `CyclingButtonWidget`
No existing usage of vanilla `CyclingButtonWidget` exists anywhere in this
repo, and `FriendSidebarWidget` already bypasses vanilla's normal widget
render/tooltip pipeline entirely (`extractWidgetRenderState` is
deliberately empty; real rendering happens in a hand-called `renderNow()`).
Embedding a real `CyclingButtonWidget` instance would require reconciling
its own internal `render`/`mouseClicked`/tooltip lifecycle with this
manually-driven render loop — a mismatch, not a fit. This plan draws a
plain rectangle + text label (matching the file's existing `guiGraphics.fill`
+ `guiGraphics.text` idiom used everywhere else in this class) and handles
the click itself. This resolves the spec UI section's explicitly-flagged
open item in favor of the "hand-drawn lookalike" branch it named as the
fallback.

### Decision 2 — `JoinPolicy` cycle order and description text (FR7.3/FR7.4)
Fixed order `NOBODY -> FRIENDS -> EVERYONE -> NOBODY -> ...` per spec FR7.3.
Given `EXPANDED_WIDTH` = 180px (too narrow for FR7.4's full example copy,
e.g. `"Join: Friends (default) — your Steam friends can join your world"`,
on one line), this plan reuses the sidebar's own existing hover-reveals-detail
convention (the whole sidebar already only shows names/status on hover-expand)
symmetrically at the control level: the strip **always** renders the short
form (`"Join: Nobody"` / `"Join: Friends"` / `"Join: Everyone"`); while the
mouse is over the control's own bounds specifically, an additional 1-3 line
wrapped description renders directly beneath it (reusing the existing
private `wrapMessage(...)` helper already in this file, Existing
Implementation), pushing whatever would render below it down for that one
frame (acceptable, transient, mouse-driven, not a layout that needs to be
reflow-stable). Copy:
- `NOBODY`: `"No one can join your hosted world."`
- `FRIENDS`: `"Your Steam friends can join your hosted world (default)."`
- `EVERYONE`: `"Any Steam user can join your hosted world. A real Mojang account is still required to connect."`
  (the last sentence is FR7.10's required mitigating note).

### Decision 3 — `JoinPolicy` lives in `features/friends-sidebar`'s feature-internal `api` sub-package; cycling logic in `FriendSidebarStateMachine`
`de.lazuli.features.friendssidebar.api.JoinPolicy` — plain enum,
`NOBODY, FRIENDS, EVERYONE`, zero import, same package as
`FriendsSidebarConfig` (Existing Implementation resolves spec Public API
item 7's location). `FriendSidebarStateMachine` gains
`public JoinPolicy nextJoinPolicy(JoinPolicy current)` (pure switch,
`NOBODY -> FRIENDS`, `FRIENDS -> EVERYONE`, `EVERYONE -> NOBODY`),
unit-testable exactly like this class's existing methods.
`FriendsSidebarFacade` owns the current value + a persistence callback
(Decision 5), not `FriendSidebarWidget` itself — mirrors how `enabled`/
`steamAvailable` are already facade-owned state, not widget-owned.

### Decision 4 — Persistence: `FriendsSidebarConfigIO.save(Path, FriendsSidebarConfig)`, called synchronously on click
Add one new public method:
```java
public void save(Path path, FriendsSidebarConfig config) throws IOException {
    Path parent = path.toAbsolutePath().normalize().getParent();
    if (parent != null) {
        Files.createDirectories(parent);
    }
    Files.writeString(path, serialize(config), StandardCharsets.UTF_8);
}
```
(reuses the existing `serialize(...)` method verbatim — no new serialization
logic). The composition-root callback (Decision 5) wraps this in a
try/catch, logging a warning via `LazuliMod.LOGGER.warn(...)` on any
`IOException`/`RuntimeException` and otherwise no-op'ing (never lets a
click handler throw into the render/input thread — mirrors this feature's
existing NFR2-style "never crash" discipline). This directly resolves the
spec Configuration section's explicitly-flagged "planning should
confirm/extend a save-on-change path" gap: **no such path exists today;
this is a net-new method**, not a reuse of an existing one.

**Backward-compatible schema change** (own decision, not explicit in spec
text but required to satisfy Configuration's own "an unrecognized string
value... falls back to the whole-file DEFAULT... not merely FRIENDS for
that field" alongside not breaking existing installs): `joinPolicy` is
parsed as an **optional** third key (absent -> defaults to
`JoinPolicy.FRIENDS`, preserving whatever `enabled`/`refreshIntervalSeconds`
values an upgrading install already has on disk) but, if **present**, must
parse to one of the three valid enum names or the parser throws
`MalformedConfigException` exactly like every other field today, which
`parse()` already catches and maps to a **whole-file** fallback to
`FriendsSidebarConfig.DEFAULT` (spec's own explicit wording, Configuration
section) — this satisfies both "upgrading an existing install does not
reset unrelated settings" and "a malformed/unknown *value* for this field
fails closed exactly like every other field already does," which are two
different scenarios the spec's single sentence otherwise conflates.
`serialize()` gains a third line, `"joinPolicy": "<NAME>"`.

### Decision 5 — Bridge points, concrete wiring (Architecture, resolves FR7.11/FR7.12/FR7.13)

**`features/steam-world-hosting` gains (Public API items 9/10):**
- A new, `steam-world-hosting`-local three-value enum (does **not** import
  `friends-sidebar`'s `JoinPolicy`, per Non-goals' Feature-to-Feature ban):
  `features/steam-world-hosting/src/main/java/de/lazuli/features/worldhosting/services/JoinGatePolicy.java`
  — `NOBODY, FRIENDS, EVERYONE`.
- `HostGateway` gains:
  ```java
  public static HostGateway forPolicy(JoinGatePolicy policy, LongPredicate isDirectFriend) {
      return switch (policy) {
          case NOBODY -> new HostGateway(id -> false);
          case FRIENDS -> new HostGateway(isDirectFriend);
          case EVERYONE -> new HostGateway(id -> true);
      };
  }
  ```
  (satisfies NFR8: plain-JVM, zero Minecraft/steamworks4j import, directly
  unit-testable alongside the existing `HostGatewayTest`.)
- `HostingLifecycle` gains an `advertise` parameter to `start` and a live
  toggle that does **not** touch `hosting`/`localSteamId64` (FR7.12/FR7.13's
  "no disconnect, advertising-only" requirement):
  ```java
  public void start(boolean advertise) {
      long id = gateway.localSteamId64();
      this.localSteamId64 = id;
      this.hosting = true;
      if (advertise) {
          gateway.setLocalRichPresence(CONNECT_KEY, ConnectStringCodec.encode(id));
      } else {
          gateway.clearLocalRichPresence();
      }
  }
  public void start() { start(true); } // existing callers/behavior unchanged

  /** Live-toggles Rich Presence advertising without affecting hosting/session state (FR7.13). No-op if not currently hosting. */
  public void updateAdvertising(boolean advertise) {
      if (!hosting) {
          return;
      }
      if (advertise) {
          gateway.setLocalRichPresence(CONNECT_KEY, ConnectStringCodec.encode(localSteamId64));
      } else {
          gateway.clearLocalRichPresence();
      }
  }
  ```

**`WorldHostingHookHolder`** (all three platform modules, identical change):
gains an `advertise` field and an update entry point, and `onWorldLoad()`
now respects it:
```java
private static volatile boolean advertise = true;

public static void publish(HostingLifecycle hostingLifecycle, LongPredicate joinGate, boolean advertiseEnabled) {
    lifecycle = hostingLifecycle;
    canJoin = joinGate;
    advertise = advertiseEnabled;
}

/** Bridge point 2 (FR7.11): re-derives the join gate + advertising flag without a restart. */
public static synchronized void updateJoinPolicy(LongPredicate joinGate, boolean advertiseEnabled) {
    canJoin = joinGate;
    boolean changed = advertise != advertiseEnabled;
    advertise = advertiseEnabled;
    if (changed && lifecycle != null) {
        lifecycle.updateAdvertising(advertiseEnabled);
    }
}
```
and `onWorldLoad()`'s existing `lifecycle.start();` call becomes
`lifecycle.start(advertise);`. The existing 2-arg `publish(...)` call site
is updated to the new 3-arg form (Files to Modify). `hasConnectedPeers()`/
`onWorldStop()` are unchanged (FR7.12: no disconnect logic added anywhere).

**Bridge point 1 (startup, in `SteamWorldHostingClientInitializer`, all
three modules):** after loading its own `SteamWorldHostingConfig`, also
loads `friends-sidebar.json` directly (Existing Implementation's correction
— this is a new read, not reuse of an existing one):
```java
Path friendsSidebarConfigPath = FabricLoader.getInstance().getConfigDir().resolve("friends-sidebar.json");
FriendsSidebarConfigIO.ParseResult friendsSidebarConfigResult = new FriendsSidebarConfigIO().load(friendsSidebarConfigPath);
if (friendsSidebarConfigResult.warning() != null) {
    LazuliMod.LOGGER.warn(friendsSidebarConfigResult.warning());
}
JoinPolicy joinPolicy = friendsSidebarConfigResult.config().joinPolicy();
JoinGatePolicy gatePolicy = JoinPolicyBridge.toGatePolicy(joinPolicy);
HostGateway hostGateway = HostGateway.forPolicy(gatePolicy, gateway::isDirectFriend);
WorldHostingHookHolder.publish(lifecycle, hostGateway::canJoin, gatePolicy != JoinGatePolicy.NOBODY);
```
(replaces the existing two lines `HostGateway hostGateway = new HostGateway(gateway::isDirectFriend); ... WorldHostingHookHolder.publish(lifecycle, hostGateway::canJoin);`).

**Bridge point 2 (dropdown click -> persist -> re-publish, in
`FriendsSidebarClientInitializer`, all three modules):** constructs a
`Consumer<JoinPolicy>` passed into `FriendsSidebarFacade` (Decision 3's
constructor change):
```java
Consumer<JoinPolicy> onJoinPolicyChanged = newPolicy -> {
    FriendsSidebarConfig updated = new FriendsSidebarConfig(config.enabled(), config.refreshIntervalSeconds(), newPolicy);
    try {
        new FriendsSidebarConfigIO().save(configFilePath, updated);
    } catch (IOException | RuntimeException e) {
        LazuliMod.LOGGER.warn("Failed to persist friends-sidebar.json: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    if (WorldHostingHookHolder.isEnabled()) {
        JoinGatePolicy gatePolicy = JoinPolicyBridge.toGatePolicy(newPolicy);
        HostGateway hostGateway = HostGateway.forPolicy(gatePolicy, gateway::isDirectFriend);
        WorldHostingHookHolder.updateJoinPolicy(hostGateway::canJoin, gatePolicy != JoinGatePolicy.NOBODY);
    }
};
FriendsSidebarFacade facade = new FriendsSidebarFacade(dataSource, new FriendSidebarStateMachine(), config.joinPolicy(), onJoinPolicyChanged);
```
Guarded by `WorldHostingHookHolder.isEnabled()` (already-existing method) so
a click is a safe no-op when Steam World Hosting itself is disabled/Steam
unavailable — it still persists the new config value either way (FR7.5: the
dropdown always edits the standing config value regardless of hosting
state), only the live-republish half is conditional.

**New tiny shared helper** (one per platform module, avoids duplicating the
`JoinPolicy -> JoinGatePolicy` switch in two composition-root classes):
`platform/fabric-<version>/src/main/java/de/lazuli/worldhosting/JoinPolicyBridge.java`:
```java
public final class JoinPolicyBridge {
    private JoinPolicyBridge() {}
    public static JoinGatePolicy toGatePolicy(JoinPolicy policy) {
        return switch (policy) {
            case NOBODY -> JoinGatePolicy.NOBODY;
            case FRIENDS -> JoinGatePolicy.FRIENDS;
            case EVERYONE -> JoinGatePolicy.EVERYONE;
        };
    }
}
```
This class necessarily imports both features' types (platform-layer glue,
same license `SteamWorldHostingClientInitializer` already exercises for
`WorldJoinRequester`/`FriendHostingStatusReader` — ADR-0003) and is not
unit-tested (platform modules have no unit-test precedent in this repo;
the one line of logic it contains is exercised end-to-end by
`HostGateway.forPolicy`'s own tests plus manual verification).

### Decision 6 — No tooltip API, hand-drawn hover-description (resolves spec Compatibility's `CyclingButtonWidget` cross-version flag)
Since Decision 1 already rejects reusing the real `CyclingButtonWidget`
class, the spec Compatibility section's flagged "`CyclingButtonWidget`
cross-version shape... must be javap-verified" concern does not apply —
there is no vanilla widget class dependency to verify. The only
cross-version surface this control needs is the same
`guiGraphics.fill(...)`/`guiGraphics.text(...)`-shaped calls
`FriendSidebarWidget` already uses identically today for both the 26.x and
1.21.11 render idioms (see Existing Implementation's already-confirmed
avatar/text draw call differences) — no new cross-version risk introduced
beyond what this file already carries.

## Files to Create

### `features/steam-world-hosting`
- `src/main/java/de/lazuli/features/worldhosting/services/JoinGatePolicy.java`
  — Decision 5, three-value enum, zero import.

### `features/friends-sidebar`
- `src/main/java/de/lazuli/features/friendssidebar/api/JoinPolicy.java`
  — Decision 3, three-value enum, zero import.

### Platform modules (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/worldhosting/JoinPolicyBridge.java`
  — Decision 5.

### Tests
- `features/steam-world-hosting/src/test/java/de/lazuli/features/worldhosting/services/HostGatewayForPolicyTest.java`
  (or added directly to the existing `HostGatewayTest.java` — implementer's
  choice, either satisfies coverage) — `forPolicy(NOBODY, anyPredicate)`
  always returns `false` regardless of the injected predicate's own answer;
  `forPolicy(FRIENDS, predicate)` delegates exactly to `predicate` (reuse
  the existing `canJoinReflectsAFriendAllowList`-style fixture);
  `forPolicy(EVERYONE, anyPredicate)` always returns `true` regardless of
  the injected predicate's own answer — the three assertions that most
  directly guard FR7.8/FR7.9/FR7.10's core semantics.
- `features/friends-sidebar/src/test/java/de/lazuli/features/friendssidebar/services/FriendSidebarStateMachineTest.java`
  (existing file, extended) — `nextJoinPolicy` cycles
  `NOBODY -> FRIENDS -> EVERYONE -> NOBODY` exactly, including the wrap-around
  case.
- `features/friends-sidebar/src/test/java/de/lazuli/features/friendssidebar/config/FriendsSidebarConfigIOTest.java`
  (existing file, extended) — round-trip including `joinPolicy`; a
  two-key JSON object (no `joinPolicy` key at all) parses successfully and
  defaults `joinPolicy` to `FRIENDS`, preserving whatever `enabled`/
  `refreshIntervalSeconds` values were present (Decision 4's backward-compat
  case); an invalid `joinPolicy` string value (e.g. `"MAYBE"`) falls back to
  the **whole-file** `FriendsSidebarConfig.DEFAULT`, not merely
  `FRIENDS` for that field; `save(...)` followed by `load(...)` round-trips
  every field including a non-default `joinPolicy`.

## Files to Modify

### `features/steam-world-hosting`
- `src/main/java/de/lazuli/features/worldhosting/services/HostGateway.java`
  — add `forPolicy(...)` static factory (Decision 5).
- `src/main/java/de/lazuli/features/worldhosting/services/HostingLifecycle.java`
  — add `start(boolean advertise)` (existing `start()` delegates to it) and
  `updateAdvertising(boolean advertise)` (Decision 5).

### `features/friends-sidebar`
- `src/main/java/de/lazuli/features/friendssidebar/api/FriendsSidebarConfig.java`
  — add `joinPolicy` record component; update `DEFAULT`; update class-level
  JavaDoc's JSON example.
- `src/main/java/de/lazuli/features/friendssidebar/config/FriendsSidebarConfigIO.java`
  — `JsonObjectParser.parseConfig()` gains the optional `joinPolicy` key
  (Decision 4); `serialize(...)` gains the third line; add
  `public void save(Path path, FriendsSidebarConfig config) throws IOException`.
- `src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java`
  — constructor gains `JoinPolicy initialJoinPolicy, Consumer<JoinPolicy> joinPolicyWriter`
  params; add `private volatile JoinPolicy joinPolicy` field,
  `public JoinPolicy joinPolicy()` accessor, `public void cycleJoinPolicy()`
  (calls `stateMachine.nextJoinPolicy(...)`, updates the field, invokes the
  writer callback) — update this class's own JavaDoc usage example.
- `src/main/java/de/lazuli/features/friendssidebar/services/FriendSidebarStateMachine.java`
  — add `public JoinPolicy nextJoinPolicy(JoinPolicy current)` (Decision 3).

### Platform modules (×3, identical shape each — verify by diff per NFR9)
- `platform/fabric-<version>/src/main/java/de/lazuli/SteamWorldHostingClientInitializer.java`
  — add the `friends-sidebar.json` read + `JoinPolicyBridge`/`HostGateway.forPolicy`
  call + updated 3-arg `WorldHostingHookHolder.publish(...)` (Decision 5,
  bridge point 1); new imports:
  `de.lazuli.features.friendssidebar.api.FriendsSidebarConfig`,
  `de.lazuli.features.friendssidebar.api.JoinPolicy`,
  `de.lazuli.features.friendssidebar.config.FriendsSidebarConfigIO`,
  `de.lazuli.features.worldhosting.services.JoinGatePolicy`,
  `de.lazuli.worldhosting.JoinPolicyBridge`.
- `platform/fabric-<version>/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java`
  — `advertise` field, updated `publish(...)` signature, new
  `updateJoinPolicy(...)`, `onWorldLoad()`'s `lifecycle.start()` call becomes
  `lifecycle.start(advertise)` (Decision 5).
- `platform/fabric-<version>/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`
  — build the `onJoinPolicyChanged` callback and pass
  `config.joinPolicy()`/the callback into the (now 4-arg)
  `FriendsSidebarFacade` constructor (Decision 5, bridge point 2); new
  imports: `de.lazuli.features.friendssidebar.api.JoinPolicy`,
  `de.lazuli.features.worldhosting.services.HostGateway`,
  `de.lazuli.features.worldhosting.services.JoinGatePolicy`,
  `de.lazuli.worldhosting.JoinPolicyBridge`,
  `de.lazuli.worldhosting.WorldHostingHookHolder`, `java.io.IOException`,
  `java.util.function.Consumer`.
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`
  — the dropdown strip: new constants (`DROPDOWN_HEIGHT`, background/text
  colors matching this file's existing palette convention), `listTopOffset()`
  becomes an instance method conditioned on `this.expanded` (Decision 2's
  layout), a `drawJoinPolicyControl(...)` render method + hover-description
  block (Decision 2), a `dropdownBounds`-shaped instance field set each
  frame in `renderNow()` and consumed by `mouseClicked()` to hit-test a
  click against the strip and call `facade.cycleJoinPolicy()` (Decision 3).
  Applied identically to all three modules' own copy of this class (each
  module's own render-call idiom, per Existing Implementation's already-
  confirmed per-module `blit`/`drawTexturedQuad` and `text`/
  `drawTextWithShadow` differences — this plan does not introduce any new
  such difference, it only adds calls in the same two idioms already used
  elsewhere in each file).

## Interfaces
- `features/friends-sidebar` `api`-sub-package `JoinPolicy` — read directly
  by platform composition-root code (not crossing via the top-level `api`
  module, per Decision 3/Existing Implementation).
- `features/steam-world-hosting` `services` `JoinGatePolicy` — read directly
  by platform composition-root code only; never imported by
  `features/friends-sidebar`.
- `HostGateway.forPolicy(JoinGatePolicy, LongPredicate)` — the one new
  public entry point `steam-world-hosting` exposes for this revision
  (Public API item 9).
- `HostingLifecycle.start(boolean)` / `updateAdvertising(boolean)` — the one
  new public surface for FR7.8's advertising-suppression half (Public API
  item 10).
- `WorldHostingHookHolder.publish(HostingLifecycle, LongPredicate, boolean)` /
  `updateJoinPolicy(LongPredicate, boolean)` — platform-internal, not a
  Feature `api` type, but the concrete mechanism FR7.11 resolves through.

## Services
No new `services/`-module (shared-across-features) capability. All new
logic lives inside the two existing features' own `services/` sub-packages
(`HostGateway.forPolicy`, `HostingLifecycle.updateAdvertising`,
`FriendSidebarStateMachine.nextJoinPolicy`) plus platform-local composition-
root glue (`JoinPolicyBridge`, `WorldHostingHookHolder` additions).

## Tests

### Test Strategy
- **Plain-JVM unit tests** (the primary regression guard for this
  revision's actual enforcement-mapping correctness, per NFR8):
  `HostGateway.forPolicy`'s three-state mapping (new test, above) and
  `FriendSidebarStateMachine.nextJoinPolicy`'s fixed cycle order (new test,
  above) are both directly, deterministically testable with zero
  Minecraft/steamworks4j dependency, mirroring this repo's existing
  `HostGatewayTest`/`FriendSidebarStateMachineTest` conventions.
- **`FriendsSidebarConfigIO`** round-trip + malformed-fallback +
  backward-compatible-missing-key tests (above) are the primary regression
  guard for Decision 4's schema-evolution behavior — the single highest-risk
  correctness concern in this revision given the "don't reset an upgrading
  install's other settings" requirement this plan derived beyond the spec's
  literal text.
- **`HostingLifecycle.start(boolean)`/`updateAdvertising(boolean)` and
  `WorldHostingHookHolder`'s new methods are not unit-tested** — `HostingLifecycle`
  has no existing fake-seam test double for `SteamFriendsGateway` in this
  codebase (Existing Implementation notes no `HostingLifecycleTest` exists
  today), and `WorldHostingHookHolder` is a platform-layer static holder
  with no unit-test precedent anywhere in this repo (consistent with every
  other platform-layer class in both features' existing plans). This is a
  deliberate, scope-proportionate gap, not an oversight — flagged as Risk 3
  below since it is exactly the code path implementing FR7.8's two-part
  mechanism and FR7.12/FR7.13's "no disconnect, advertising-only" guarantee,
  the requirements this revision's Overview called out as most load-bearing.
- **`FriendSidebarWidget`'s dropdown rendering/click-handling is not
  unit-testable** (real `AbstractWidget`/`GuiGraphicsExtractor` dependency,
  same as every other rendering concern in this class) — verified manually
  in-game only, per `ui-guidelines.md`'s Testing section, matching this
  feature's own existing Test Strategy precedent.
- **Manual in-game verification matrix** (run once per platform module,
  Steam running with the local player's own account — no test *friend*
  needed for most of this matrix, unlike the rest of the sidebar, since
  FR7.5 explicitly frames this as a standing preference the local player
  edits regardless of hosting state):
  - Dropdown is not rendered at all while the sidebar is collapsed (FR7.3);
    appears directly beneath the pinned own-profile row once expanded, and
    disappears again on collapse without leaving stray pixels.
  - Clicking the control cycles `Nobody -> Friends -> Everyone -> Nobody`
    in that exact order; the short label updates immediately; hovering over
    the control (not clicking) reveals the longer description text,
    including the "Everyone... a real Mojang account is still required"
    sentence when on that state (FR7.10).
  - Dropdown is inert (or entirely absent, matching whatever the status-state
    branch already does) whenever the sidebar is in its FR6.x Steam-unavailable
    status state (FR7.6) — confirmed by disabling Steam and confirming a
    click in the strip's former screen position does nothing.
  - `config/friends-sidebar.json` on disk updates to the new `joinPolicy`
    value immediately after a click, without requiring a game restart or
    world reload (Decision 4).
  - **Host-side, single-machine verification only** (no live in-game P2P
    join/reject test per spec Non-goals): with a world loaded and hosting
    active, changing the dropdown does not disconnect/crash anything, and
    (spot-checked via log output / a debug breakpoint, not an actual second
    peer) the predicate `WorldHostingHookHolder` holds is observed to change
    — full live peer-join/reject behavior under each of the three states
    remains explicitly out of this workflow's scope (spec Non-goals,
    unchanged).
  - Changing the dropdown while sitting at the title screen (no world
    loaded), then loading a world, results in the *new* value's predicate
    being used for that session (FR7.11) — spot-checked via log output at
    `onWorldLoad()` time, not a live peer test.

## Dependencies
No new external Maven/Gradle dependency of any kind — every change is
internal (`project(...)`-scoped) code within `features/steam-world-hosting`,
`features/friends-sidebar`, and the three existing platform modules, all of
which already depend on both features' projects today (confirmed:
`FriendsSidebarClientInitializer` and `SteamWorldHostingClientInitializer`
already coexist in the same platform module and already cross-import each
other's `api` types via `WorldHostingBridgeHandoff`). No change to any
`build.gradle`, `settings.gradle`, or `fabric.mod.json` entrypoint list/order
(the existing `SteamWorldHostingClientInitializer` (3rd) before
`FriendsSidebarClientInitializer` (4th) ordering already provides everything
this plan's bridge points need — Existing Implementation).

## Risks
1. **`FriendSidebarWidget`'s existing `listTopOffset()` is `static` and used
   in several places (`refreshScreenSize()`'s `maxRows` calc,
   `totalHeight(...)`, row-position math) that assume a fixed value across a
   whole frame.** Making it depend on `this.expanded` (Decision 2) means
   every call site within a single `renderNow()` invocation must observe a
   *consistent* value for that frame (the field is only ever mutated earlier
   in the same method, before any of these call sites run, so this is
   expected to be safe, but implementation should double check no call site
   reads `listTopOffset()` before `expanded` is finalized for that frame,
   given the method's existing "expanded can flip mid-method via the coyote
   timer" logic, Existing Implementation's `renderNow()` excerpt).
2. **Hit-testing the dropdown strip correctly across the widget's animated-width
   states** (the strip's own bounds only make sense once `width` has animated
   close to `EXPANDED_WIDTH`, exactly the same `showText` gating the
   existing avatar-name text already uses) — implementation must reuse the
   exact same `showText`-equivalent condition already computed in
   `renderNow()` for both the render call and the stored click-hit bounds,
   not two independently-computed conditions that could drift apart across
   an animation frame.
3. **`HostingLifecycle`/`WorldHostingHookHolder`'s new advertising-toggle
   path has no unit test** (Test Strategy) — this is exactly the code
   implementing FR7.8's "reject-at-transport + suppress-advertising" two-part
   mechanism and FR7.12/FR7.13's "no disconnect" guarantee, the parts of
   this revision the spec itself frames as most load-bearing/most-researched.
   Mitigated only by careful code review + the manual verification matrix's
   log-output spot-checks, not by an automated test — flagged as the
   single highest-value candidate for a future `HostingLifecycleTest`
   fake-seam investment if this area sees further change.
4. **`FriendSidebarWidget`'s 1.21.11 (`ClickableWidget`) top-level base
   class/tooltip-absence assumption is not independently re-`javap`-confirmed
   this pass** — carried forward at the same low-risk level the original
   plan's own (now-closed) Risk 6 already established; Decision 6 avoids
   introducing any *new* tooltip dependency specifically to sidestep this,
   so the residual risk is limited to this plan's own new `fill`/`text`
   calls compiling against the same already-proven-compatible method
   signatures every other row in this file already uses.
5. **Shared-file risk (carried forward from spec)**: `FriendSidebarWidget`/
   `FabricFriendsSidebarInjector` are actively-evolving, shared files; no
   concurrent `features/server-browser` work exists in this tree today
   (confirmed), but implementation must still `git status`/`git diff`
   immediately before editing them to catch any uncommitted concurrent work
   introduced since this planning pass.

## Acceptance Criteria
- **FR7.1** — `FriendsSidebarConfig` has a `joinPolicy()` accessor
  defaulting to `FRIENDS`; `config/friends-sidebar.json` round-trips a
  `"joinPolicy"` key; `FriendsSidebarConfigIOTest` covers default-when-absent,
  malformed-value-whole-file-fallback, and round-trip via the new `save(...)`.
- **FR7.2** — Code review: `HostGateway`/`WorldHostingHookHolder` are the
  only places any accept/reject decision is made; `FriendSidebarWidget`/
  `FriendsSidebarFacade` never themselves decide whether a P2P session is
  accepted, only read/write the persisted `JoinPolicy` value and invoke the
  bridge callback.
- **FR7.3-FR7.6** — In-game (manual matrix above): dropdown renders only
  when expanded, cycles in fixed order, description text is legible
  (including FR7.10's mitigating note), inert in the Steam-unavailable
  status state.
- **FR7.7** — Code review: `cycleJoinPolicy()`/the composition-root callback
  never call any `HostingLifecycle`/`WorldHostingHookHolder` start/stop
  method, only `updateJoinPolicy(...)`/`updateAdvertising(...)`.
- **FR7.8-FR7.10** — `HostGatewayForPolicyTest`'s three assertions (or
  equivalent additions to `HostGatewayTest`) directly verify the three
  predicate shapes; `HostingLifecycle.start(boolean)`/`updateAdvertising(...)`
  code-reviewed against the two-part FR7.8 mechanism (reject-at-transport
  via the predicate, independently suppress-advertise via Rich Presence).
- **FR7.11** — In-game: changing the dropdown at the title screen (no world
  loaded), then loading a world, uses the new value (log-output spot-check,
  per Test Strategy); changing it while already hosting is reflected in
  `WorldHostingHookHolder`'s held predicate without a restart.
- **FR7.12/FR7.13** — Code review: no code path this plan adds calls
  anything peer-disconnect-shaped; `updateJoinPolicy(...)`/
  `updateAdvertising(...)` never touch `hosting`/`localSteamId64`/
  `SteamSession`.
- **NFR8** — `HostGateway.forPolicy` and `FriendSidebarStateMachine.nextJoinPolicy`
  are both zero-Minecraft/steamworks4j-import, directly unit-tested (grep
  spot-check + test presence).
- **NFR9** — All platform-module changes (`SteamWorldHostingClientInitializer`,
  `WorldHostingHookHolder`, `FriendsSidebarClientInitializer`,
  `FriendSidebarWidget`, new `JoinPolicyBridge`) are present and structurally
  identical (module-specific only in Mojang/Yarn class names and render-call
  idiom) across all three of `platform/fabric-1.21.11`, `platform/fabric-26.1`,
  `platform/fabric-26.2` — verified by direct diff per this repo's existing
  NFR7/NFR9-style convention.
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules and both features' test suites (`gradlew :features:steam-world-hosting:test`,
  `gradlew :features:friends-sidebar:test`) with the new tests passing.
