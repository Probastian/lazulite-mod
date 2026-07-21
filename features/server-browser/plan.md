# Implementation Plan — Server Browser

## Summary
Build `features/server-browser` (all requirement groups FR1–FR5) on top of the
already-shipped Steamworks bootstrap (`services/steamworks`) and the
already-established `SteamworksServiceHandoff` hand-off pattern, following the
exact composition-root / Version-Adapter / api-hook shape `steam-cloud-sync`
and `friends-sidebar` already established. This feature never depends on
`steam-cloud-sync` or `friends-sidebar` — it shares only `services`'
`SteamworksService` bootstrap (spec Architecture). Unlike either prior
feature, this one opens a brand-new `Screen` (not an overlay) and owns its
own, self-populated scrollable list — a structurally simpler UI shape than
`steam-cloud-sync`'s Group 6 synthetic-rows-in-a-*foreign* list problem, so no
Mixin is required anywhere in this plan (see Decision 3). No implementation
code is written as part of this plan.

This plan resolves every item the specification left open for planning: the
final Public API shapes/names (spec's own "planning's discretion" invitation,
Public API section), the API-vs-Feature layer split for the sort/filter table
model, whether a dedicated `services/steamworks` gateway is warranted (no,
per Non-goals' graduate-on-second-use framing — confirmed still true), the
password-protected join screen's scope given vanilla's confirmed total
absence of a server-password concept and the spec's own deliberate v1 scope
reduction — `ServerBrowserPasswordPromptScreen` is a fully built screen, but
in v1 the password it collects is captured and discarded, never transmitted
or enforced, because no server-side verification protocol exists yet
(Decision 6) — and the exact vanilla connect
entry point per version (already confirmed in `.claude/context/minecraft.md`
row 76 from `steam-world-hosting`'s own prior `javap` pass — reused directly,
no re-verification needed, see Existing Implementation).

## Existing Implementation
- **Shared Steamworks bootstrap** (reuse as-is, no changes):
  `services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`
  — `isSteamAvailable()`/`steamAppId()` (implements `api/.../steamworks/SteamAvailability`).
  Never re-initialize Steamworks; never construct a second `SteamworksService`.
- **Hand-off pattern** (reuse as-is, one instance per platform module,
  already present at
  `platform/fabric-<version>/src/main/java/de/lazuli/SteamworksServiceHandoff.java`):
  `volatile static SteamworksService instance` behind `publish(...)`/
  `require()`. This feature's own new composition root
  (`ServerBrowserClientInitializer`) calls `SteamworksServiceHandoff.require()`
  at the top of `onInitializeClient()` and must be registered in
  `fabric.mod.json`'s `"client"` array **after**
  `"de.lazuli.SteamworksClientInitializer"` (order load-bearing, identical
  discipline already established by `SteamCloudSyncClientInitializer`/
  `FriendsSidebarClientInitializer`).
- **Pattern 1 precedent, exact code shape to copy** —
  `platform/fabric-26.2/src/main/java/de/lazuli/cloudsync/FabricBookmarkToggleInjector.java`
  (read in full): `ScreenEvents.AFTER_INIT.register(this::onScreenInit)` in
  the constructor; `onScreenInit(Minecraft client, Screen screen, int
  scaledWidth, int scaledHeight)` does an `instanceof` check against the
  target screen type, builds a `Button` positioned via `scaledWidth`/
  `scaledHeight`, and calls `Screens.getWidgets(screen).add(widget)`. This is
  the direct precedent for this feature's own top-right button injector
  (Decision 2) — it already targets the exact same screen
  (`JoinMultiplayerScreen`, 26.2/26.1) this feature also targets, confirming
  both the screen type and the `scaledWidth`-relative top-right positioning
  math (`x = scaledWidth - width - margin`) work today, in this repo, against
  this exact screen.
- **Vanilla Multiplayer screen class names — already confirmed, reused
  without re-verification**: `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen`
  (Mojang mappings, `fabric-26.1`/`fabric-26.2`) and
  `net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen` (Yarn
  mappings, `fabric-1.21.11`) — per spec Compatibility, itself citing
  `FabricBookmarkToggleInjector.java`'s own existing, compiling import as
  equivalent-strength evidence to `javap`. No further action needed.
- **Vanilla client-connect API — already `javap`-confirmed per version**,
  `.claude/context/minecraft.md` row 76 (`steam-world-hosting`'s own
  mandatory-first-step `javap` pass, read in full for this plan):
  - **≤1.21.11 (Yarn)**: `net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(Screen, MinecraftClient, ServerAddress, ServerInfo, boolean, CookieStorage)`;
    `new ServerInfo(String name, String ip, ServerInfo.ServerType.OTHER)`;
    `ServerAddress.parse(String)`.
  - **≥26.1 (Mojang)**: `net.minecraft.client.gui.screens.ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`;
    `new ServerData(String name, String ip, ServerData.Type.OTHER)`;
    `ServerAddress.parseString(String)`.
  - Neither constructor nor either connect entry point has any
    password-shaped parameter anywhere — this is the same "no vanilla
    password concept" fact the spec's own Compatibility section already
    concluded from source research; this plan's `javap`-confirmed signatures
    corroborate it at the bytecode level. Directly resolves FR4.1's connect
    hand-off, confirms FR4.3's premise, and — combined with the spec's own
    v1 scope reduction (FR4.3, Non-goals, Networking) — confirms there is no
    connect-time parameter or side channel this plan needs to wire a
    password into at all (Decision 6): the password-protected join path
    (after the prompt) reuses this exact same call, unmodified, with no
    branching on password state.
  - `ClientPlayConnectionEvents.JOIN`/`DISCONNECT` (`fabric-networking-api-v1`,
    already used by `steam-cloud-sync`'s `SteamCloudSyncClientInitializer`,
    minecraft.md row 72) fire uniformly for both singleplayer and multiplayer
    connections and are already proven reachable with zero new Gradle
    coordinate — **not needed by this feature**: v1 has no post-JOIN
    password hand-off to perform (Decision 6), so this plan does not
    register a listener for these events.
- **steamworks4j 1.10.0 `SteamMatchmakingServers`/`SteamMatchmakingServerListResponse`/
  `SteamMatchmakingGameServerItem`/`SteamMatchmakingServerNetAdr` signatures —
  already source-level confirmed** in the spec's own Compatibility section
  (this plan does not re-fetch; the spec's citation of `code-disaster/steamworks4j`
  tag/branch `master`, read in full for this pass, is accepted as sufficient
  per the same standard `steam-cloud-sync`/`friends-sidebar`'s own steamworks4j
  citations were accepted at). One residual, explicitly non-blocking sanity
  check carried forward as Risk 1: a `javap -p` pass against
  `platform/fabric-26.2/build/processIncludeJars/steamworks4j-1.10.0.jar`,
  cheap and opportunistic, not required before starting.
- **Config/JSON precedent**: this feature has **no** persisted config file at
  all (spec Configuration: "No new persisted config file in v1") — unlike
  every other feature reviewed for this plan, there is no `config/*IO`
  class to add, no `HelloWorldMainMenuConfigIO`/`CloudSyncJson` precedent to
  reuse. `config/` stays an empty placeholder per NFR5.
- **No existing `services/steamworks` gateway class**: confirmed by `Glob`
  (`services/src/main/java/de/lazuli/services/**`) — no
  `SteamMatchmaking*`-named class exists anywhere in `services/` today, and
  no second consumer of `ISteamMatchmakingServers` exists in this repo.
  Non-goals' graduate-on-second-use deferral is confirmed still correct: this
  plan keeps `ServerMatchmakingServers` usage confined to one feature-local
  gateway class (Decision 1), not a `services/`-layer extraction.
- **`Screens.getWidgets`/`getButtons` divergence** (`minecraft.md` row 1) and
  **`Screen.render`/`extractRenderState`, `DrawContext`/`GuiGraphicsExtractor`
  divergence** (`minecraft.md` row 66, confirmed via `WorldRestoreScreen`) —
  both apply directly to this feature's button injector and to
  `ServerBrowserScreen`'s own from-scratch rendering, reused without
  re-verification.
- **Alpha-zero text bug** (`minecraft.md` row 74) — any status/disabled text
  this feature draws must use a full `0xFF` alpha byte
  (`0xFFFFFFFF`-shaped literals), never bare 3-byte RGB, on both mapping
  families; already bit `friends-sidebar` once (Existing Implementation
  there), called out again here as a concrete implementation-time check.
- **No existing precedent in this repo for a brand-new (not vanilla-derived)
  scrollable list**: `WorldRestoreScreen` (cited by spec Public API item 6)
  is a from-scratch `Screen` but does **not** contain a scrolling list of its
  own — it is the correct precedent for "a brand-new custom `Screen`
  subclass with hand-rolled rendering," but not for the list-widget shape
  `ServerBrowserScreen` additionally needs. `steam-cloud-sync`'s Group 6 work
  (`minecraft.md` rows 60–65) is the closest related precedent, but solves a
  *different* problem (inserting synthetic rows into an *existing, foreign*
  vanilla list, `WorldSelectionList`/`WorldListWidget`, whose `addEntry`/
  `clearEntries` are `protected` and whose own `Entry` base type is itself
  `protected` — requiring the three-mixin-failures-then-reflection saga
  documented in `minecraft.md` rows 61–65). This feature's list is **our own
  new subclass** of that same `AbstractSelectionList`/`ObjectSelectionList`
  (26.x) / `EntryListWidget`/`AlwaysSelectedEntryListWidget` (1.21.11) family
  — see Decision 3 for why that changes the accessibility analysis entirely
  and avoids every one of Group 6's mixin problems.

## Decisions on the Open Questions (resolved during planning)

### 1. `ServerBrowserQuery` — the sole steamworks4j-importing class in this feature (NFR3), feature-local, not `services/`-layer
`features/server-browser/services/ServerBrowserQuery.java` wraps
`SteamMatchmakingServers`/`SteamMatchmakingServerListResponse`/
`SteamServerListRequest` behind the shape spec Public API item 3 already
sketches: constructed with `(SteamAvailability, IntSupplier appId)` (mirrors
`CloudFileStore`'s "no-op when unavailable" construction gate, `steam-cloud-sync`
Decision 7), never constructs `SteamMatchmakingServers` unless
`isSteamAvailable()` (FR1.5). Public surface:
```java
void start(ServerBrowserSource source, Consumer<List<ServerBrowserRow>> onRowsChanged, Runnable onRefreshComplete);
void refresh();      // FR1.3 — refreshQuery(request), not a new request
void close();        // FR1.4 — releaseRequest(request), idempotent
boolean isRefreshing();
```
Internally: an indexed `Map<Integer or address, ServerBrowserRow>` upserted by
`serverResponded`/removed-or-flagged by `serverFailedToRespond` (FR1.2), full
list handed to `onRowsChanged` after every mutation (O(1) amortized per
callback, per spec Performance); `refreshComplete`'s `NoServersListedOnMasterServer`/
`ServerFailedToRespond` outcomes are logged via the same `Consumer<String>`
warning-logger convention `SteamworksService.create` already established
(NFR2's "no uncaught exception reaches the tick thread" is satisfied by
wrapping every steamworks4j call site in try/catch here, the single choke
point NFR3 already concentrates them at).

### 2. Public API surface: `ServerBrowserSession`/`ServerBrowserSessionFactory` — the symmetric-hook pattern, not a direct Feature-class reference from platform
Per ADR-0001 ("Version Adapters... continue to depend only on `api`, never on
Feature classes" — binding, already relied on by both `steam-cloud-sync` and
`friends-sidebar`), platform's button injector and `ServerBrowserScreen`
cannot construct `ServerBrowserQuery`/`ServerBrowserTableModel` directly (both
are `features/server-browser/services` classes). Following the exact
`BookmarkSyncHook`/`WorldSyncToggleHook` symmetric-hook shape (`steam-cloud-sync`
Decision 9) and `FriendSidebarHook` (`friends-sidebar` Public API item 1),
this plan introduces two new top-level `api` interfaces:

- `api/.../serverbrowser/ServerBrowserSession` — the per-open-screen live
  handle `ServerBrowserScreen` holds and drives:
  ```java
  void start(ServerBrowserSource source, Consumer<List<ServerBrowserRow>> onRowsChanged, Runnable onRefreshComplete);
  void refresh();
  boolean isRefreshing();
  void setSortColumn(ServerBrowserColumn column);              // FR2.2, toggles direction on repeat calls with the same column
  void setFilter(ServerBrowserFilterState filter);              // FR3.1–FR3.6
  List<ServerBrowserRow> currentRows();                         // already sorted+filtered
  void close();                                                 // FR1.4, called from Screen.onClose()/removed()
  ```
  Implemented by `features/server-browser/services/ServerBrowserSessionImpl`,
  which composes one `ServerBrowserQuery` (Decision 1) + one
  `ServerBrowserTableModel` (Decision 4) per session instance — this is the
  single seam letting the *pure* sort/filter logic (which platform must be
  able to drive on every keystroke/header-click) and the *impure* Steam query
  (which platform must never touch directly) sit behind one `api`-layer
  contract, rather than needing two separate hooks.
- `api/.../serverbrowser/ServerBrowserSessionFactory` — `ServerBrowserSession
  newSession();` — constructed once by each platform composition root
  (wrapping `SteamAvailability` + `SteamworksService.steamAppId()`,
  Architecture's "no new App-ID resolution path"), handed to the button
  injector, which calls `newSession()` fresh each time the player opens
  `ServerBrowserScreen` (never a shared/reused session across opens — FR1.4's
  release discipline plus FR3.7's "reset to defaults each open" both depend
  on a fresh session per screen-open).

This is the same direction-flip `steam-cloud-sync` Decision 9 already
established for its four hook interfaces (Feature implements, Platform
consumes) — not a new mechanism.

### 3. `ServerBrowserScreen`'s row list: a genuinely new subclass of the vanilla list base class, no Mixin needed
Unlike `steam-cloud-sync`'s Group 6 (inserting rows into an *already-constructed,
foreign* `WorldSelectionList`/`WorldListWidget` instance owned by a vanilla
`Screen`, whose `addEntry`/`clearEntries` — and even the `Entry` base type
itself — are `protected` and *inaccessible from outside that class hierarchy*,
per `minecraft.md` rows 60–65's three-mixin-failure saga), `ServerBrowserScreen`
**constructs its own instance of a new class it authors itself**, extending
that same base (`net.minecraft.client.gui.components.ObjectSelectionList<Row>`
on 26.x per `minecraft.md` row 60's confirmed hierarchy, `AlwaysSelectedEntryListWidget<Row>`-shaped
equivalent on 1.21.11's Yarn side). Because `addEntry`/`clearEntries` are
`protected`, **not `private`**, and Java's protected-access rule grants a
*subclass* full access to its own inherited protected members regardless of
package, `ServerBrowserListWidget extends ObjectSelectionList<ServerBrowserListWidget.Row>`
(or the 1.21.11 equivalent) can call `this.addEntry(...)`/`this.clearEntries()`
freely from its own methods — this is ordinary Java subclassing, not a
mixin/reflection workaround. The nested `Row extends Entry` (matching
`WorldSelectionList.Entry`'s own confirmed pattern of a `public` no-arg
constructor on the concrete subtype, `minecraft.md` row 60) is likewise a
plain subclass declared directly on `ServerBrowserListWidget`.

**Consequence: this plan commits to zero `@Mixin` files anywhere in this
feature** (spec NFR5's "`mixins/` stays an empty placeholder... FR-level
requirements above do not currently require [a mixin]" — confirmed correct,
not merely assumed, by the protected-vs-subclass distinction above). The one
remaining unconfirmed detail — the exact base class name/generic signature on
each platform module — is an ordinary Version-Adapter lookup task (already
partially known from `minecraft.md` row 60's Group 6 research), not a naming
risk of the kind Group 6 hit; flagged as Risk 2, first concrete step of
`ServerBrowserScreen`/`ServerBrowserListWidget` implementation per module.

### 4. `ServerBrowserTableModel`: one class owning both sort and filter (FR2.3/FR3.6), feature-local, NFR1-tested
Per spec Public API item 2's own "planning's choice" framing between a single
class and a two-class split: **one class**,
`features/server-browser/services/ServerBrowserTableModel.java`, is chosen —
FR3.6 already states "ideally the same small business-logic class... owning
both sort and filter" as its own preferred shape, and a single class keeps
`ServerBrowserSessionImpl`'s composition (Decision 2) to exactly two
collaborators (`ServerBrowserQuery` + `ServerBrowserTableModel`) rather than
three. Public surface:
```java
List<ServerBrowserRow> apply(List<ServerBrowserRow> rows, ServerBrowserColumn sortColumn, boolean ascending, ServerBrowserFilterState filter);
Comparator<ServerBrowserRow> comparatorFor(ServerBrowserColumn column);  // FR2.3, individually testable
boolean matches(ServerBrowserRow row, ServerBrowserFilterState filter);  // FR3.6, individually testable
```
Pure, stateless, zero `net.minecraft.*`/steamworks4j import (NFR1) — the
highest-value unit-test target in this feature (default ascending-ping sort,
FR2.4; each filter individually and combined via AND, FR3.1–FR3.6).
`ServerBrowserSessionImpl` owns the *mutable* current sort/filter state
(`ServerBrowserSession.setSortColumn`/`setFilter`, session-lifetime only per
FR3.7) and re-invokes `apply(...)` against the latest raw row list from
`ServerBrowserQuery` on every mutation — matching `ServerBrowserQuery`'s own
"hand the full list to `onRowsChanged` after every upsert" design (Decision 1),
so `ServerBrowserSessionImpl.currentRows()` is always the already-sorted,
already-filtered view `ServerBrowserScreen` renders directly, with no sort/filter
logic duplicated in platform code.

### 5. `ServerBrowserRow`/enums/filter-state: plain records and enums, top-level `api` module
Per spec Public API item 1, `ServerBrowserRow` is a plain, steamworks4j-free
record crossing the Platform/Feature boundary (via `ServerBrowserSession`'s
own methods, Decision 2) — placed at
`api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserRow.java`
(top-level `api`, zero dependencies, same precedent as `CloudOnlyWorldSummary`
et al., `steam-cloud-sync` Decision 9):
```java
record ServerBrowserRow(String serverName, String map, int players, int maxPlayers,
                         int ping, boolean hasPassword, boolean isSecure,
                         String address, boolean respondedSuccessfully) {}
```
`address` is the pre-formatted `getConnectionAddressString()` result (spec
Compatibility confirms this ready-made formatter exists — no hand-rolled
IP:port formatting needed, `ServerBrowserQuery` is the only place that calls
it). Alongside it, in the same package: `ServerBrowserSource` (`enum { INTERNET, LAN }`,
FR1.6), `ServerBrowserColumn` (`enum { NAME, MAP, PLAYERS, PING, PASSWORD, SECURE }`,
FR2.1/FR2.2), `ServerBrowserFilterState` (`record { String searchText, boolean hideFull,
boolean hidePasswordProtected, int maxPing, boolean hideEmpty }`, FR3.1–FR3.5,
`DEFAULT` constant with empty search/no toggles/`maxPing = 0` meaning "no
limit" per FR3.4).

### 6. Password-protected join (FR4.3): `ServerBrowserPasswordPromptScreen` is a real, fully-built screen, but v1 discards the entered password — no transmission or enforcement mechanism, per the spec's own explicit scope reduction
The spec's own Compatibility section already confirms, and this plan's
`javap`-verified `ConnectScreen.connect`/`startConnecting` and
`ServerInfo`/`ServerData` signatures corroborate (Existing Implementation),
that **no vanilla connect entry point or constructor has any password-shaped
parameter at all** — there is no protocol-level hook to attach a password to.
The spec (FR4.3, Non-goals, Networking, Compatibility, Future Extensions,
edited during this planning pass's own spec-review cycle) now resolves this
directly rather than leaving it open: real password verification is
explicitly deferred to a server-side companion mod that **does not exist
yet**, so this plan **does not** design, guess at, or implement any
transmission/enforcement mechanism (no chat command, no plugin-message
payload, no holder/hand-off of any kind). This removes the prior planning
guess (a `/login <password>` chat command sent via a post-JOIN
`ClientPlayConnectionEvents.JOIN` listener) entirely — that mechanism is not
built, and no code in this plan references `ClientPlayConnectionEvents.JOIN`
for a password purpose.

Concretely, v1 scope is:
- `ServerBrowserPasswordPromptScreen` (Public API item 7) is a real,
  fully-built `Screen` subclass: a masked password text field plus Join/Cancel
  buttons, shown whenever the player attempts FR4.1 against a row whose
  `hasPassword()` is `true`.
- Pressing **Join** reads the field's text (to keep the UI genuinely
  functional and testable end-to-end) and then simply discards it — no
  static holder, no per-session pending-password state, nothing persisted or
  passed anywhere — before immediately calling the exact same
  `ConnectScreen.connect`/`startConnecting` entry point FR4.1 already uses
  for an unprotected row (Existing Implementation), with **no branching on
  password state at connect time**: a password-protected row's post-prompt
  connect call is byte-for-byte the same call as any other row's, per the
  spec's own FR4.1 wording ("there is no branching by password state at the
  point of actually connecting").
- Pressing **Cancel** returns to `ServerBrowserScreen` unmodified — no
  connect attempt, no error state — unchanged from the original plan.

This is a **documented stub, not an oversight**: the spec itself (Non-goals,
"No real password verification in v1 — known stub") states the real
transmission/enforcement mechanism is deferred until the server-side mod's
protocol is defined (Future Extensions), at which point wiring the collected
password through to that protocol is the natural follow-up work. Because
there is currently no mechanism of any kind to design, this plan carries
**no equivalent to the former Risk 3** (the previous plan's "assumption
requiring live-server verification" risk is removed along with the mechanism
it was about) and needs no live password-protected-server test in v1 — see
Risks and Acceptance Criteria below, and Open Questions.

## Files to Create

### `api` module (top-level, zero dependencies — same precedent as `SteamAvailability`/`CloudSyncable`/`FriendSidebarHook`)
- `api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserRow.java` (Decision 5)
- `api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserSource.java` — `enum { INTERNET, LAN }` (Decision 5)
- `api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserColumn.java` — `enum { NAME, MAP, PLAYERS, PING, PASSWORD, SECURE }` (Decision 5)
- `api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserFilterState.java` — record + `DEFAULT` constant (Decision 5)
- `api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserSession.java` (Decision 2)
- `api/src/main/java/de/lazuli/api/serverbrowser/ServerBrowserSessionFactory.java` (Decision 2)

### `features/server-browser` module (new Gradle subproject)
- `features/server-browser/build.gradle` — `dependencies { api project(':api'); implementation project(':services') }` (identical shape/rationale to `features/steam-cloud-sync/build.gradle`/`features/friends-sidebar/build.gradle`).
- `features/server-browser/README.md`

**`api/` sub-package** (`de.lazuli.features.serverbrowser.api`, feature-internal — this feature has no data type that stays purely internal; skip, or a single `package-info.java` placeholder only if implementation finds a genuine internal-only type later. Not populated by this plan.)

**`config/`, `events/`, `gui/`, `mixins/` sub-packages** — each a `package-info.java` placeholder:
- `config/` — this feature has no persisted config file in v1 (spec Configuration; Existing Implementation) — permanent placeholder, not "temporarily empty" the way other features' `mixins/` are.
- `events/`, `gui/` — same FR8-equivalent layering rationale already established by `steam-cloud-sync`/`friends-sidebar` (no `net.minecraft.*`/`net.fabricmc.fabric.api.*` outside `platform/`; no new cross-feature event bus).
- `mixins/` — placeholder, and (per Decision 3) expected to remain one permanently for this feature, not merely "unless Pattern 2/3 turns out needed" — Decision 3 already confirms Pattern 2 is not needed.
**`resources/`** — `.gitkeep` placeholder (no bundled feature-module-level assets; any new icon glyphs — password-lock/VAC-shield indicators, FR2.1 — are small enough to hand-draw with `fill(...)`/`drawCenteredText` calls, following `CloudOnlyWorldListEntry`'s own no-new-texture precedent for a simple indicator, per platform module; confirm at implementation time whether a texture asset is actually warranted, not assumed necessary by this plan).

**`services/` sub-package**:
- `ServerBrowserQuery.java` (Decision 1) — sole steamworks4j-importing class (NFR3).
- `ServerBrowserTableModel.java` (Decision 4) — pure sort/filter (NFR1).
- `ServerBrowserSessionImpl.java` — implements `ServerBrowserSession` (Decision 2); composes one `ServerBrowserQuery` + one `ServerBrowserTableModel` per instance.
- `ServerBrowserSessionFactoryImpl.java` — implements `ServerBrowserSessionFactory` (Decision 2); constructed with `SteamAvailability` + `IntSupplier appId` (from `SteamworksService.steamAppId()`); `newSession()` constructs a fresh `ServerBrowserQuery`/`ServerBrowserTableModel`/`ServerBrowserSessionImpl` triple each call.

**`tests/`** (`src/test/java/de/lazuli/features/serverbrowser/...`):
- `ServerBrowserTableModelTest` — the highest-value test target in this feature: `comparatorFor` for each of the six columns (ascending/descending both directions per FR2.2); default sort is ascending ping (FR2.4); each filter (`matches`) individually — search substring case-insensitive (FR3.1), hide-full (`players >= maxPlayers`, FR3.2), hide-password-protected (FR3.3), max-ping threshold with `0`/empty meaning no limit (FR3.4), hide-empty (`players == 0`, FR3.5) — and combined via AND (FR3.6, at least one case where two filters together exclude a row neither excludes alone).
- `ServerBrowserFilterStateTest` — `DEFAULT` constant round-trips to "no filtering" behavior when passed through `ServerBrowserTableModel.matches`.
- `ServerBrowserSessionImplTest` — using a hand-written fake in place of `ServerBrowserQuery` (small interface extraction if needed, or a fake `ServerBrowserQuery` subclass overriding its Steam-call sites — planning does not mandate introducing a new interface purely for this test, implementation's discretion) confirming `setSortColumn`/`setFilter` correctly re-invoke `ServerBrowserTableModel.apply` against the latest raw rows, and that `close()` is idempotent (mirrors FR1.4's own idempotency wording) — no steamworks4j class on this test's actual invocation path (NFR1-adjacent discipline, mirrors `friends-sidebar`'s own explicit "no fake-seam interface introduced for a simple wrapper" scope decision, Risk 8 there, applied here at the smaller `ServerBrowserSessionImplTest` scope, not the whole `ServerBrowserQuery`).
- `ServerBrowserQuery` itself is **not** unit-tested against a real
  `SteamMatchmakingServers` (no fake-seam interface introduced for the
  steamworks4j surface itself, same deliberate, scope-proportionate
  trade-off `friends-sidebar`'s `FriendsService` already established, Risk 8
  there) — verified manually in-game only (spec UI section's own manual
  verification list).

### Platform modules — one composition root + Version Adapters per module (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/ServerBrowserClientInitializer.java` — new `ClientModInitializer`; composition root. Resolves `SteamworksServiceHandoff.require()`, constructs `ServerBrowserSessionFactoryImpl`, constructs `FabricServerBrowserButtonInjector`. Registers no `ClientPlayConnectionEvents` listener — v1's password flow (Decision 6) needs no post-connect hand-off.
- `platform/fabric-<version>/src/main/java/de/lazuli/serverbrowser/FabricServerBrowserButtonInjector.java` — Pattern 1 (Decision 2/Existing Implementation); `ScreenEvents.AFTER_INIT` on the module's own Multiplayer screen type, one top-right `Button`/`ButtonWidget` via `Screens.getWidgets`/`getButtons`, `onPress` calls `sessionFactory.newSession()` and `Minecraft.setScreen(new ServerBrowserScreen(session, ...))`/`MinecraftClient.setScreen(...)`.
- `platform/fabric-<version>/src/main/java/de/lazuli/serverbrowser/ServerBrowserScreen.java` — new `Screen` subclass (Public API item 6); header row of clickable `ServerBrowserColumn` labels calling `session.setSortColumn(...)`; toolbar (search box FR3.1, three toggles FR3.2/FR3.3/FR3.5, max-ping field FR3.4, Internet/LAN toggle FR1.6, Refresh button FR1.3, refreshing-indicator, Join/Back buttons) all driving `session.setFilter(...)`/`session.refresh()`/`session.start(...)`; owns one `ServerBrowserListWidget` child (Decision 3); Steam-unavailable/no-App-ID status branch (FR5.1/FR5.2) renders a single status message in place of the table+toolbar, following the same status-vs-content branch shape `friends-sidebar` v1.2 established (`FriendsSidebarFacadeTest`/`FriendSidebarWidget`'s status branch, cited directly by spec Overview); `onClose()`/`removed()` calls `session.close()` (FR1.4).
- `platform/fabric-<version>/src/main/java/de/lazuli/serverbrowser/ServerBrowserListWidget.java` — Decision 3; the feature's own new subclass of the module's vanilla list base class, nested nested `Row extends Entry` renders the six FR2.1 columns per row plus the FR4.2 disabled/muted treatment for a `!respondedSuccessfully` row; double-click or "Join Server" delegates to a small `RowSelectionListener` callback the `Screen` itself implements (FR4.1's double-click-or-button symmetry).
- `platform/fabric-<version>/src/main/java/de/lazuli/serverbrowser/ServerBrowserPasswordPromptScreen.java` — new `Screen` subclass (Public API item 7); one masked text field + Join/Cancel; "Join" reads (and discards — Decision 6, no transmission/enforcement in v1) the field's text, then calls the module's own `ConnectScreen.connect`/`startConnecting` (Existing Implementation) with the row's constructed `ServerInfo`/`ServerData`, identical to the unprotected-row connect call; "Cancel" calls `Minecraft.setScreen(previousServerBrowserScreen)` with no state mutation (FR4.3 third bullet).

## Files to Modify
- `settings.gradle` — add `include 'features:server-browser'`.
- `platform/fabric-26.2/build.gradle`, `platform/fabric-26.1/build.gradle`,
  `platform/fabric-1.21.11/build.gradle` — each gains
  `implementation project(':features:server-browser')`.
- `platform/fabric-26.2/src/main/resources/fabric.mod.json`,
  `platform/fabric-26.1/.../fabric.mod.json`,
  `platform/fabric-1.21.11/.../fabric.mod.json` — each gains a new entry in
  the existing `"client"` array, `"de.lazuli.ServerBrowserClientInitializer"`,
  positioned after `"de.lazuli.SteamworksClientInitializer"` (order
  load-bearing). Position relative to `SteamCloudSyncClientInitializer`/
  `FriendsSidebarClientInitializer` is not load-bearing (no shared state) —
  placed last in the array for consistency, matching `friends-sidebar`'s own
  placement rationale.
- `.claude/context/minecraft.md` — gains new rows once implementation
  confirms (via real compile / `javap`) `ServerBrowserListWidget`'s exact base
  class/generic signature per module (Decision 3/Risk 2) — not modified
  by this planning pass itself, per this repo's own living-record convention.

## Interfaces
- `api/.../serverbrowser/ServerBrowserSession` — the per-open-screen live
  handle; implemented by `ServerBrowserSessionImpl` (Feature), consumed by
  `ServerBrowserScreen`/`ServerBrowserListWidget` (Platform).
- `api/.../serverbrowser/ServerBrowserSessionFactory` — implemented by
  `ServerBrowserSessionFactoryImpl` (Feature), consumed by
  `FabricServerBrowserButtonInjector` (Platform).
- `api/.../serverbrowser/{ServerBrowserRow, ServerBrowserSource, ServerBrowserColumn, ServerBrowserFilterState}`
  — plain data types crossing the Platform/Feature boundary via the two
  interfaces above.
- `features/server-browser/services/ServerBrowserQuery` — feature-internal-only
  seam (never referenced by Platform); the sole point where
  `com.codedisaster.steamworks.SteamMatchmaking*` is imported (Decision 1/NFR3).

## Services
- No new `services/`-module (shared-across-features) capability is
  introduced — Non-goals/Decision 1 confirm this feature needs nothing
  `steam-cloud-sync`/`friends-sidebar`/the bootstrap don't already provide at
  that layer (graduate-on-second-use unchanged).

## Feature Classes
Enumerated fully under Files to Create above (`api/` top-level types,
`features/server-browser/services/*`). All plain Java; NFR1/NFR3 require
(and Decision 1's single-seam design structurally guarantees) zero
`net.minecraft.*`/steamworks4j-native-call import outside `ServerBrowserQuery`.

## Tests

### Test Strategy
- `ServerBrowserTableModelTest`/`ServerBrowserFilterStateTest` (Files to
  Create, `tests/`) — plain-JVM, zero `net.minecraft.*`/steamworks4j import
  (NFR1), the single highest-leverage test target in this feature per FR2.3/
  FR3.6's own explicit "pure, plain-JVM-testable" requirement.
- `ServerBrowserSessionImplTest` — session-orchestration logic (sort/filter
  re-application, idempotent close) tested against a hand-written fake in
  place of the real `ServerBrowserQuery`, mirroring the fake-seam pattern
  `steam-cloud-sync`'s `CloudFileStore`/`WorldArchiveCloudStore` established,
  scoped down to this feature's smaller actual surface.
- `ServerBrowserQuery`, every platform `Screen`/widget/injector class are
  **not** unit-testable on a plain JVM (real steamworks4j native calls, real
  `Screen`/list-widget classes) — per `ui-guidelines.md`'s Testing section,
  verified manually in-game only, per supported Minecraft version target.
- **Manual in-game verification matrix** (run once per platform module,
  Steam both running and not running — mirrors `friends-sidebar`'s own
  matrix shape), directly per spec UI section's own explicit list:
  - Steam unavailable at screen-open: status message renders in place of the
    table/toolbar (FR5.1); the Multiplayer-screen button remains visible and
    clickable regardless (FR5.2).
  - Steam available, zero servers returned (a real, legitimate 480/Spacewar
    outcome, spec Compatibility's "App ID resolution" note): a genuine empty
    state distinguishable from the unavailable status state — do not conflate
    the two (mirrors `friends-sidebar` FR6.3(a)'s identical caution for its
    own empty-friends-list case).
  - Sorting by every column, ascending and descending (FR2.2); default
    ascending-ping sort on first open (FR2.4).
  - Each filter individually and combined (FR3.1–FR3.6); filters/sort reset
    to defaults on a fresh screen-open (FR3.7).
  - Internet/LAN source toggle switches populated rows and correctly
    releases/reissues the underlying request (FR1.6/FR1.4) — spot-check via
    log output that `releaseRequest` is called exactly once per switch, no
    leak across repeated opens/closes.
  - The top-right button's placement against each target version's actual
    Multiplayer-screen layout (no overlap with the footer row or the
    server-list widget's own scroll area/search box, per spec UI).
  - The password-prompt flow (FR4.3) renders, accepts masked input, and both
    "Join" (proceeds to the ordinary FR4.1 connect call, per Decision 6's v1
    stub — no live password-protected test server or verification check is
    required, since there is nothing to verify in v1) and "Cancel" (returns
    to `ServerBrowserScreen` unmodified, no connect attempt) behave as
    specified; also verify the *unprotected* join path (FR4.1) and the
    disabled/non-selectable failed-row state (FR4.2) on the same pass.
  - A row for a server that failed to respond (FR1.2/FR4.2) renders visibly
    muted/disabled and does not initiate a connect attempt on double-click.

## Dependencies
- **No new external Maven/Gradle dependency.** steamworks4j remains pinned at
  `1.10.0` (`gradle.properties`), already `api`-exposed by `services`,
  already resolved in this repo's Gradle cache (confirmed by
  `steam-cloud-sync/implementation-plan.md`'s own Existing Implementation,
  reused here without re-verifying the cache path again) —
  `SteamMatchmakingServers`/`SteamMatchmakingServerListResponse`/
  `SteamMatchmakingGameServerItem`/`SteamMatchmakingServerNetAdr` are all
  already-vendored classes in that same jar, no new coordinate. This plan
  introduces **zero new external dependency of any kind** — no Maven Central
  lookup was needed for this feature, and Decision 6's scope reduction means
  `fabric-networking-api-v1`'s `ClientPlayConnectionEvents` is not needed by
  this feature either (removed from this plan along with the dropped
  post-JOIN password hand-off).
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:server-browser` → `api` (`api` configuration)
  - `features:server-browser` → `services` (`implementation` configuration)
  - `platform:fabric-26.2` → `features:server-browser` (`implementation`)
  - `platform:fabric-26.1` → `features:server-browser` (`implementation`)
  - `platform:fabric-1.21.11` → `features:server-browser` (`implementation`)
- This feature does **not** depend on `features:steam-cloud-sync` or
  `features:friends-sidebar` in any direction (Dependency Rules table,
  Forbidden: Feature → Feature) — shares only `services`' `SteamworksService`
  bootstrap via `SteamworksServiceHandoff`, same independence
  `friends-sidebar` already established relative to `steam-cloud-sync`.

## Sequencing (order of implementation)
1. `api` module types (Decision 5, then Decision 2's two hook interfaces) —
   no dependency on anything else in this feature.
2. `features/server-browser` scaffold (`build.gradle`, package placeholders,
   `settings.gradle` entry).
3. `ServerBrowserTableModel` (Decision 4) + its unit tests — pure logic,
   buildable and testable before any Steamworks/Minecraft code exists.
4. `ServerBrowserQuery` (Decision 1) — the one steamworks4j seam; opportunistic
   `javap -p` sanity check (Risk 1) as a first step, not a blocker.
5. `ServerBrowserSessionImpl`/`ServerBrowserSessionFactoryImpl` (Decision 2) +
   `ServerBrowserSessionImplTest` — composes 3 and 4 behind the `api` contract.
6. One platform module first (`fabric-26.2`, matching this repo's own
   established "verify the newest/most-diverged module's assumptions first"
   pattern from `steam-cloud-sync`/`friends-sidebar`): composition root →
   `FabricServerBrowserButtonInjector` → `ServerBrowserListWidget`'s base-class
   confirmation (Decision 3/Risk 2, the one genuinely new lookup this feature
   needs) → `ServerBrowserScreen` → `ServerBrowserPasswordPromptScreen`
   (Decision 6 — build the prompt UI, wire "Join" straight to the same
   connect call FR4.1 already uses, discard the read password).
7. Port the confirmed design to `fabric-26.1` (near-identical to 26.2, same
   Mojang mapping family) then `fabric-1.21.11` (Yarn mapping family,
   `DrawContext`/`ButtonWidget`/`ClientPacketListener`→`ClientPlayNetworkHandler`-shaped
   renames per `minecraft.md`'s existing table rows).
8. Manual in-game verification matrix (Test Strategy) across all three
   modules, Steam both running and not running. (The prior live
   password-protected-server check this repo's MEMORY note once flagged is no
   longer part of this feature's v1 scope — Decision 6 — and can be dropped
   from that pending-item tracking; it becomes relevant again only once the
   server-side mod protocol exists, per Future Extensions.)

## Risks
1. **`SteamMatchmakingServers`/`SteamMatchmakingServerListResponse`/
   `SteamMatchmakingGameServerItem` signatures are confirmed at
   source-code level (spec Compatibility, GitHub `master` at time of spec
   research) but not by a direct `javap` pass against this repo's own
   resolved `steamworks4j-1.10.0.jar` in this planning pass** — low risk
   (steamworks4j's matchmaking classes are a stable, hand-written,
   non-generated wrapper unchanged across releases per the spec's own
   research), but the cheap opportunistic `javap -p` check the spec itself
   recommends should still be implementation's first step for
   `ServerBrowserQuery` specifically, logged in `minecraft.md` per its own
   convention if any discrepancy is found.
2. **`ServerBrowserListWidget`'s exact base class name/generic signature per
   platform module (Decision 3) is not independently `javap`-confirmed by
   this planning pass** — an ordinary, low-risk Version-Adapter lookup task
   (the base-class *family* is already known from `steam-cloud-sync`'s own
   Group 6 research, `minecraft.md` row 60), not a naming risk of the kind
   that produced Group 6's three-mixin-failure saga (since this plan's
   Decision 3 already avoids that entire class of problem). Confirm via real
   compile at the start of platform implementation (Sequencing step 6); log
   results in `minecraft.md`.
3. **Dev-time App ID 480/Spacewar may return zero real servers if no test
   server (this mod's own developers' or a third party's) happens to be
   registered against it at verification time** (spec Compatibility's own
   explicit caveat) — not a defect in this feature if it occurs, but the
   manual verification matrix (Test Strategy) explicitly separates "zero
   servers, Steam available" (a legitimate, distinguishable empty state) from
   "Steam unavailable" (FR5.1) so a temporarily-empty 480 list during
   verification does not get misdiagnosed as a bug; running at least one
   locally-hosted test server registered against 480 during verification
   (this repo's own dev/testing convention, spec Goals) is the concrete
   mitigation, not a code change.
4. **`ServerBrowserSessionImplTest`'s fake-in-place-of-`ServerBrowserQuery`
   approach (Files to Create, `tests/`) may need a small interface
   extraction (`ServerBrowserQuery` implementing an `interface` the fake also
   implements) that this plan does not mandate up front** — left as an
   implementation-time judgment call (noted explicitly in that test's own
   Files to Create entry) rather than over-specifying a seam this feature's
   comparatively small orchestration logic may not need; if
   `ServerBrowserSessionImpl`'s own logic turns out simple enough to test via
   `ServerBrowserTableModel` alone (Decision 4's tests already cover the pure
   logic exhaustively), this class-level test may be thinned to just the
   idempotent-`close()` check without weakening NFR1 compliance overall.

*(The former Risk 3 — "Decision 6's `/login <password>` chat-command
convention is a plan-level assumption, not a confirmed interop contract,"
requiring a live password-protected 480/Spacewar test — is removed. Decision
6's v1 scope no longer includes any transmission/enforcement mechanism to be
right or wrong about; the risk it described no longer exists. Risks below
this point are renumbered accordingly relative to the prior plan revision.)*

## Acceptance Criteria
Mapped to the specification's functional and non-functional requirements:

- **FR1.1–FR1.6** — Code review: `ServerBrowserQuery` never resolves a second
  App-ID path (reads only `SteamworksService.steamAppId()` via the factory's
  `IntSupplier`); `ServerBrowserQuery`'s three callback methods match FR1.2
  exactly (upsert/remove-or-flag/clear-refreshing + log `Response` outcomes
  via the warning-logger convention); `refresh()` calls `refreshQuery`, never
  a new request, when the source is unchanged; `releaseRequest` called
  exactly once per source switch and on `close()` (spot-checked via log
  output during manual verification, mirroring `friends-sidebar`'s own
  release-discipline check); no `SteamMatchmakingServers` object constructed
  when `!isSteamAvailable()` (code review + log spot-check).
- **FR2.1–FR2.4** — `ServerBrowserTableModelTest` covers all six columns'
  comparators both directions and the default ascending-ping sort; in-game
  check confirms each column header click toggles direction and replaces the
  previous sort column (single-column sort only).
- **FR3.1–FR3.7** — `ServerBrowserTableModelTest`/`ServerBrowserFilterStateTest`
  cover each filter individually and combined via AND, plus the `DEFAULT`/
  no-op filter case; in-game check confirms filters apply live without a
  Steam re-query, and reset to defaults on a fresh screen-open.
- **FR4.1–FR4.3** — In-game: double-click and "Join Server" both initiate the
  vanilla connect flow for an unprotected row's resolved address (using the
  version's own `javap`-confirmed `ConnectScreen`/`ServerInfo`-or-`ServerData`
  call, Existing Implementation); a failed-to-respond row is visibly
  disabled and does not connect (FR4.2); a password-protected row's
  double-click/Join intercepts before any connection opens and shows
  `ServerBrowserPasswordPromptScreen`; pressing "Join" there proceeds to the
  exact same connect call as any other row (Decision 6 — no branching by
  password state, and no verification to check, per the spec's own v1
  stub); Cancel returns to `ServerBrowserScreen` unmodified.
- **FR5.1–FR5.2** — In-game: Steam-unavailable renders the status message in
  place of the table/toolbar; the Multiplayer-screen button remains visible
  and clickable in that state.
- **NFR1** — `ServerBrowserTableModelTest`/`ServerBrowserFilterStateTest`
  run on a plain JVM with zero `net.minecraft.*`/`com.codedisaster.steamworks.*`
  import; `gradlew :features:server-browser:test` runs with no Minecraft jar
  on its test classpath.
- **NFR2** — Code review + manual in-game soak test: no uncaught exception
  from any `SteamMatchmakingServers`/`SteamMatchmakingServerListResponse`
  call site reaches the tick/render thread.
- **NFR3** — `grep`-spot-check: `com.codedisaster.steamworks.SteamMatchmaking*`
  import appears only in `ServerBrowserQuery.java`, nowhere else in
  `features/server-browser` or the three platform modules' `serverbrowser`
  packages.
- **NFR4** — Every public class/interface created carries a JavaDoc comment
  with at least one usage example (spot-checked against the full Files to
  Create list, mirroring the convention every prior feature's own plan
  already enforced).
- **NFR5** — `features/server-browser` contains all required sub-packages
  (`api`, `config`, `events`, `gui`, `mixins`, `resources`, `services`,
  `tests`) plus `README.md`; `mixins/` and `config/` both remain permanent
  empty placeholders (Decision 3/Existing Implementation, not "temporarily
  empty").
- **Compatibility** — `gradlew build` succeeds for all three platform modules
  with the new `features:server-browser` dependency and new `fabric.mod.json`
  entrypoint entry in place; the full manual verification matrix (Test
  Strategy) passes on all three targets, Steam both running and not running.

## Open Questions
- None remaining from the specification's own explicitly-flagged
  planning-phase items — the Public API's final class/interface shapes are
  resolved as Decisions 1–5, the `services/steamworks` gateway
  graduate-on-second-use question is resolved as "not yet, confirmed still
  correct" (Existing Implementation), and the password-protected-join screen
  (FR4.3) is resolved as Decision 6: build the prompt UI in full, discard the
  entered password, no transmission/enforcement mechanism in v1 (per the
  spec's own explicit scope reduction — there is no longer an open "which
  mechanism" question to resolve, since v1 has no mechanism). Any further
  questions should surface during implementation as concrete compile-time/
  `javap`-confirmation findings (Risks 1, 2), not as open design questions.
