# Implementation Plan — Friends Sidebar

## Summary
Build `features/friends-sidebar` (all requirement groups FR0–FR3) on top of
the already-shipped Steamworks bootstrap (`services/steamworks`) and the
already-established `SteamworksServiceHandoff` hand-off pattern, following the
exact composition-root/Version-Adapter/api-hook shape `steam-cloud-sync`
already established. This feature never depends on `steam-cloud-sync` or any
other feature — it shares only `services`' `SteamworksService` bootstrap
(spec Architecture). No implementation code is written as part of this plan.

This plan resolves every item the specification left open for planning: the
injection shape for the FR2.2 "main-menu family" screen allow-list (Pattern 1
`ScreenEvents.AFTER_INIT` + `Screens.getWidgets`, confirmed sufficient — no
mixin fallback needed for the sidebar overlay itself), the exact allow-listed
screen classes per platform module (corrected from the spec's illustrative
names using this repo's own real, resolved jars), the avatar-texture upload
mechanism, the context-menu rendering/dismissal mechanism, and two concrete
steamworks4j API corrections the spec's own citations got only approximately
right (see Decision 6).

## Existing Implementation
- **Shared Steamworks bootstrap** (reuse as-is, no changes):
  `services/src/main/java/de/lazuli/services/steamworks/SteamworksService.java`
  — `create(long appId, Path nativeLibraryDirectory, Consumer<String> warningLogger)`,
  `pumpCallbacks()` (must be called once per client tick; a no-op if
  unavailable), `shutdown()`, `isSteamAvailable()`/`steamAppId()` (implements
  `api/.../steamworks/SteamAvailability`). Never re-initialize Steamworks;
  never construct a second `SteamworksService`.
- **Hand-off pattern** (reuse as-is, one instance per platform module,
  already present at
  `platform/fabric-<version>/src/main/java/de/lazuli/SteamworksServiceHandoff.java`):
  a `volatile static SteamworksService instance` behind `publish(...)`/
  `require()`; `SteamworksClientInitializer` already calls
  `SteamworksServiceHandoff.publish(steamworksService)` right after
  construction. This feature's own new composition root
  (`FriendsSidebarClientInitializer`) calls `SteamworksServiceHandoff.require()`
  at the top of `onInitializeClient()` and must be registered in
  `fabric.mod.json`'s `"client"` array **after**
  `"de.lazuli.SteamworksClientInitializer"` (order load-bearing, identical
  discipline to `SteamCloudSyncClientInitializer`, already the third entry in
  every module's array today — this feature becomes the fourth).
- **`SteamCloudSyncClientInitializer.java`** (per platform module) is the
  direct structural precedent for this feature's own composition root:
  resolves config dir/paths via `FabricLoader.getInstance().getConfigDir()`,
  loads a hand-rolled JSON config (`*ConfigIO.load(path)` returning a
  `ParseResult(config, warning)` record, warning logged via
  `LazuliMod.LOGGER.warn(...)` and never throwing), builds its
  Coordinator/services, registers `ClientTickEvents.END_CLIENT_TICK` /
  `ClientLifecycleEvents.CLIENT_STOPPING`, and constructs its Version
  Adapters at the end of `onInitializeClient()`. This feature's own
  `FriendsSidebarClientInitializer` follows the identical shape.
- **Pattern 1 precedent, exact code shape to copy** —
  `platform/fabric-26.2/src/main/java/de/lazuli/cloudsync/FabricBookmarkToggleInjector.java`:
  `ScreenEvents.AFTER_INIT.register(this::onScreenInit)` in the constructor;
  `onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight)`
  does an `instanceof` check against one allow-listed screen type, builds a
  widget, and calls `Screens.getWidgets(screen).add(widget)`. This feature
  needs the identical registration repeated against **every** FR2.2
  allow-listed screen type from **one** injector class (Architecture item 1
  in the spec, confirmed as the chosen design below, Decision 1).
- **Pattern 3 precedent (row-level render/click injection via `@Mixin`)** —
  `platform/fabric-26.2/src/main/java/de/lazuli/mixin/WorldListEntrySyncIconMixin.java`
  (26.x) / the 1.21.11 sibling `WorldEntrySyncIconMixin.java`: `@Inject` at
  `TAIL` of the row's render method (`extractContent`/`render`) and `HEAD` of
  `mouseClicked` (cancellable, returns `true` to consume the click), reading
  the row's own bounds via a small reflection helper
  (`WorldListEntryReflection`/`WorldEntryReflection`, `Class#getMethod`, not
  `getDeclaredMethod`, since the accessors are inherited from a protected
  ancestor — see `.claude/context/minecraft.md`'s "per-row rendering" table
  row) and bridging feature state into the mixin via a static holder
  (`WorldSyncToggleHookHolder`) because the mixin-merged row object is never
  constructed by our own code. **Not needed for this feature's own sidebar**
  (a pure Pattern-1 overlay, no existing-row injection — see Decision 1), but
  directly relevant precedent if implementation's `RealmsMainScreen`
  verification (Risk 2) forces a fallback.
- **Reflection-over-mixin lesson** (`AbstractSelectionListReflection`/
  `EntryListWidgetReflection`, `WorldEntryReflection`,
  `.claude/context/minecraft.md`'s "three consecutive mixin failures" table
  row): reaching a protected member is done via plain reflection
  (`getDeclaredMethods()`/`getMethod()` + `setAccessible(true)` +
  `invoke(...)`, untyped `Object` parameter), never `@Invoker`/`@Shadow`,
  whenever the member is inherited from a package/visibility boundary a
  mixin can't cleanly cross. Not expected to be needed by this feature (no
  synthetic row/protected-member access), recorded only in case the
  `RealmsMainScreen`/pause-menu mixin fallback (Risk 2) needs it.
- **`api` module layout today**: `api/src/main/java/de/lazuli/api/{mainmenu,steamworks,cloudsync}/...`
  — one sub-package per feature's exported hook surface, zero
  Minecraft/steamworks4j dependency in any of them (confirmed by reading
  `SteamAvailability.java`/`CloudSyncable.java`/the four `cloudsync` hook
  interfaces). This feature adds a new `de.lazuli.api.friends` sub-package,
  same convention.
- **Config IO convention**: `HelloWorldMainMenuConfigIO`-shaped hand-rolled
  JSON, `ParseResult(config, warning)`, warn-and-default-on-malformed, never
  throws — already reused as-is by `SteamCloudSyncConfigIO`. This feature's
  `FriendsSidebarConfigIO` follows the identical single-schema shape (a flat
  `{"enabled": true}` object per spec Configuration — this feature does
  **not** need `CloudSyncJson`'s generic parser, since it has only one small,
  fixed-shape config file, not six).
- **Screen classes actually present, confirmed against this machine's real
  resolved jars** (via `Glob` + `Grep` ZIP-entry-pathname presence checks —
  same tool-limited technique `steam-cloud-sync/implementation-plan.md`'s
  Existing Implementation already used and documented; **not** a `javap`
  method-signature confirmation, see Risk 1):
  - 26.2 Mojang-mapped jar (`minecraft-merged-deobf-26.2.jar`): `PauseScreen`
    present; `RealmsMainScreen` present; `OptionsScreen` present;
    `MultiplayerScreen` **absent** — the real Mojang-mapped class the
    existing `FabricBookmarkToggleInjector` already targets is
    `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen`, not
    `MultiplayerScreen` (confirmed by reading that file directly); `GameMenuScreen`
    **absent** (confirms the spec's own flagged uncertainty: `PauseScreen` is
    the real Mojang name, `GameMenuScreen` does not exist on this side at all).
  - 1.21.11 Yarn-mapped jar (`minecraft-merged-1.21.11-...+build.6-v2.jar`):
    `GameMenuScreen` present; `MultiplayerScreen` present (Yarn side does use
    this name, unlike Mojang's `JoinMultiplayerScreen`).
  - This planning pass had no `javap`/decompiler tool available (only
    `Read`/`Glob`/`Grep`/`Write`/`WebFetch`/`WebSearch`) — presence-by-name is
    confirmed, but method shapes (`ScreenEvents.AFTER_INIT` reachability,
    field names, constructor accessibility) are not; this is recorded
    honestly in Risk 1, mirroring `steam-cloud-sync/implementation-plan.md`'s
    own identical caveat.
  - `SelectWorldScreen` and its child list (`WorldSelectionList`/
    `WorldListWidget`) are already fully characterized by
    `steam-cloud-sync`'s own work (`.claude/context/minecraft.md`'s table) —
    this feature reuses that existing knowledge for its own allow-list entry,
    no fresh research needed there.
- **steamworks4j 1.10.0 exact `SteamFriends`/`SteamUtils` signatures**,
  confirmed via `WebFetch` of the real GitHub source at
  `code-disaster/steamworks4j`, tag `1.10.0`, path
  `java-wrapper/src/main/java/com/codedisaster/steamworks/` (this repo's own
  pinned version, `gradle.properties:41` — same jar already resolved in this
  machine's Gradle cache per `steam-cloud-sync/implementation-plan.md`'s own
  Existing Implementation; no new dependency, see Dependencies):
  ```java
  // SteamFriends
  int getFriendCount(FriendFlags friendFlag)                          // also a Collection<FriendFlags> overload
  SteamID getFriendByIndex(int friend, FriendFlags friendFlag)         // also a Collection<FriendFlags> overload
  String getFriendPersonaName(SteamID steamIDFriend)
  PersonaState getFriendPersonaState(SteamID steamIDFriend)
  boolean getFriendGamePlayed(SteamID steamIDFriend, FriendGameInfo friendGameInfo)
  int getSmallFriendAvatar(SteamID steamID)   // also getMediumFriendAvatar/getLargeFriendAvatar
  void activateGameOverlayToUser(OverlayToUserDialog dialog, SteamID steamID)
  boolean inviteUserToGame(SteamID steamIDFriend, String connectString)
  boolean requestUserInformation(SteamID steamID, boolean requireNameOnly)

  // SteamUtils
  int getImageWidth(int image)
  int getImageHeight(int image)
  boolean getImageSize(int image, int[] size)
  boolean getImageRGBA(int image, ByteBuffer dest)
  ```
  **Correction versus the specification's own citation** (spec FR3.1/FR3.2):
  `activateGameOverlayToUser` takes a typed `OverlayToUserDialog` enum, not a
  raw `String` dialog name. Confirmed enum constants (`WebSearch` against
  this same repo/tag's own source): `SteamID("steamid")`, `Chat("chat")`,
  `JoinTrade("jointrade")`, `Stats("stats")`, `Achievements("achievements")`,
  `FriendAdd`/`FriendRemove`/`FriendRequestAccept`/`FriendRequestIgnore`.
  `OverlayToUserDialog.Chat` / `OverlayToUserDialog.SteamID` are what FR3.1/FR3.2
  actually call, not string literals — this plan's `FriendsService.openChat`/
  `showProfile` use the typed enum. (The `WebFetch` tool could not directly
  fetch this class's raw file at the pinned tag path attempted first — the
  correct path was found only via `WebSearch`; the enum constant list itself
  is `WebSearch`-sourced, not independently re-verified by a second direct
  fetch, and is flagged again in Risk 5 for a real-compile re-confirmation.)
- **`SteamFriendsCallback`'s exact declared methods were not retrieved** by
  either `WebFetch` attempt (the fetched excerpt showed only its use in the
  constructor, not its own interface body) — FR1.5's `onPersonaStateChange`/
  `onAvatarImageLoaded` method names/signatures remain at the same
  "well-documented by Valve, not yet independently re-confirmed against this
  exact steamworks4j Java wrapper" level the spec itself already flagged;
  carried forward as Risk 4, first concrete implementation step for
  `FriendsService`.

## Decisions on the Open Questions (resolved during planning)

### 1. Injection shape: one `FabricFriendsSidebarInjector`, Pattern 1 (`ScreenEvents.AFTER_INIT` + `Screens.getWidgets`) across the whole FR2.2 allow-list, no mixin — for the sidebar itself
The spec's Architecture section frames this as the central open question
(two shapes). This plan commits to shape 1 (spec's own stated default) for
the sidebar overlay and its hover/expand/click handling, for every module:
one class, one `ScreenEvents.AFTER_INIT.register(...)` call in its
constructor, an `instanceof`-chain against the module's own allow-listed
screen classes (below), adding **one** custom composite widget (the sidebar
itself — see Decision 3) via `Screens.getWidgets(screen)` each time an
allow-listed screen (re-)initializes. No new `@Mixin` file is planned for the
sidebar's own presence/rendering/input.

**Why this is expected to be sufficient** (unlike Group 6's synthetic
world-list rows in `steam-cloud-sync`, which *did* need a mixin): the sidebar
is a single, self-contained overlay widget occupying a fixed screen region it
alone owns — it never needs to reach into another widget's private backing
list (the reason Group 6 needed `@Invoker`/reflection) or draw *inside* an
existing vanilla row (the reason the sync-toggle icon needed `@Inject`). A
single custom widget class implementing the platform's own top-level
`Renderable`/click-handling contract, added once per screen the same way
`FabricBookmarkToggleInjector`'s single `Button` is added today, is
structurally the same mechanism already proven to work.

**Residual open item, not resolved here** (spec's own explicit flag,
Architecture): `RealmsMainScreen`'s reachability via `ScreenEvents.AFTER_INIT`
given its third-party-library-driven internals. Confirmed present by class
name in the real 26.2 jar (Existing Implementation) but *not* confirmed to
fire `ScreenEvents.AFTER_INIT` the same way a native vanilla screen does —
Risk 2, first concrete implementation-time check for this screen
specifically, with an explicit, spec-sanctioned fallback: if it does not fire
reliably, exclude `RealmsMainScreen` from v1 and log it (spec Non-goals'
"exclusion... acceptable... not force-fitted with a screen-specific mixin"),
**do not** silently switch that one screen to a mixin.

### 2. FR2.2 allow-list, corrected concrete class names per module (Existing Implementation)
- **`fabric-26.2`/`fabric-26.1`** (Mojang-mapped): `TitleScreen`,
  `net.minecraft.client.gui.screens.worldselection.SelectWorldScreen`,
  `net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen`
  (**not** `MultiplayerScreen` — spec's illustrative name corrected per
  Existing Implementation, matching what `FabricBookmarkToggleInjector`
  already targets), `net.minecraft.client.gui.screens.OptionsScreen`,
  `net.minecraft.client.gui.screens.PauseScreen` (**not** `GameMenuScreen` —
  confirmed absent on this side), `net.minecraft.realmsclient.gui.screens.RealmsMainScreen`
  (package guessed by convention with other `realmsclient.gui.screens.*`
  classes — not independently confirmed, flagged again in Risk 1).
- **`fabric-1.21.11`** (Yarn-mapped): `TitleScreen`,
  `net.minecraft.client.gui.screen.world.SelectWorldScreen` (already the
  exact type `FabricCloudOnlyWorldListInjector` imports on this module),
  `net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen` (Yarn *does*
  use this name, per Existing Implementation), `net.minecraft.client.gui.screen.option.OptionsScreen`
  (package guessed by Yarn convention, not independently confirmed),
  `net.minecraft.client.gui.screen.GameMenuScreen` (confirmed present),
  Realms screen class name/package not independently confirmed for Yarn
  either (Risk 1).
- Each module's injector holds its own private `Set<Class<? extends Screen>>`-
  or plain `instanceof`-chain allow-list, mirroring
  `FabricCloudOnlyWorldListInjector.java:54`'s existing per-screen
  `instanceof` idiom (spec FR2.2 explicitly cites this same precedent) — no
  shared allow-list mechanism across modules, since each module's screen
  types are entirely distinct classes.

### 3. Sidebar widget: one custom `Renderable`/clickable widget class per module, not a composite of vanilla widgets
`FriendSidebarWidget` (one per platform module, `platform/fabric-<version>/.../friends/`)
implements that version's own top-level renderable/clickable widget contract
(`net.minecraft.client.gui.components.Renderable`+`GuiEventListener`-shaped on
26.x per Mojang's own widget interfaces, `net.minecraft.client.gui.widget.Drawable`-shaped
equivalent on 1.21.11's Yarn side — exact interface names not independently
`javap`-confirmed this pass, small/low-risk item, Risk 6). It owns:
collapsed/expanded render (avatar-only vs. avatar+name, FR2.3/FR2.4), hover
detection against its own bounds (`mouseX`/`mouseY` passed into its render
call, per the already-confirmed `(graphics, mouseX, mouseY, hovering,
partialTick)`-shaped render signature family in `minecraft.md`'s table, or
that version's `extractContent`/`render` equivalent), per-row click detection
(FR2.5) delegating to `FriendSidebarStateMachine` (below) for the
collapsed/expanded and enabled-menu-option business logic, and drawing the
avatar texture per friend (Decision 5). It never itself imports
`com.codedisaster.steamworks.*` — it is constructed with a `FriendSidebarHook`-shaped
callback surface and a `List<FriendSummary>` snapshot, both `api`-module
types (spec Public API item 1).

### 4. Context menu: a second small top-level overlay widget, drawn after the sidebar, capturing all input while open
`FriendContextMenuWidget` (one per platform module, same package) is
constructed at the mouse's click position (FR2.6) the moment a row is
clicked (FR2.5), added to the *same* screen's widget list (`Screens.getWidgets(screen).add(...)`)
**after** the sidebar so it draws on top (spec UI: "rendered above/overlapping
whatever screen is currently open"). It renders the four fixed options in
order (Open chat, Show profile, Invite to game, Join game); Invite/Join are
rendered using the same disabled/greyed-out visual convention vanilla's own
`Button.builder(...).build()` already uses for a button with `active = false`
(a plain `setActive(false)`-equivalent state flag on this widget's own two
placeholder rows — not vanilla `Button` instances at all, since they must
never be clickable, but visually styled identically: dimmed text color,
no hover highlight) — consistent, no new visual language invented. Dismissal
(FR2.7): the injector's own top-level click-handling for the host screen
intercepts a click **outside** this widget's bounds and removes it from the
widget list before the click reaches anything else; Escape is intercepted by
having the injector temporarily wrap/shadow the host screen's own
`keyPressed`-equivalent only while a context menu is open (smallest possible
surface: check "is a context menu currently open" first, consume Escape and
close the menu, otherwise call through to the host screen's normal Escape
handling) — this is the one place this feature's Pattern 1 approach needs to
intercept input *before* the host screen sees it; if Pattern 1's widget-list
ordering turns out insufficient to guarantee this interception (the spec's
own flagged possibility, Architecture shape 2), the fallback is a narrow,
single-purpose `@Mixin` at `HEAD` of the host screen's own key-handling
method, cancellable, scoped only to "is a context menu open" — never a
broader per-screen mixin. Flagged in Risks (Risk 7) as the one place this
plan's default Pattern-1 commitment has a plausible fallback trigger.

### 5. Avatar texture upload: one small `AvatarTextureCache` per platform module, keyed by `steamId64`
Per spec Architecture — Avatar Rendering: `SteamUtils.getImageSize`/
`getImageRGBA` yield raw RGBA bytes (Existing Implementation confirms these
exact two method shapes), not a directly `Identifier`/`ResourceLocation`-addressable
texture. `AvatarTextureCache` (platform-only, `de.lazuli.friends`) maps
`steamId64 -> Identifier`/`ResourceLocation`, uploading a new dynamic texture
only the first time a given friend's avatar becomes available or is
re-delivered via the `onAvatarImageLoaded`-shaped callback (FR1.3/FR1.5),
using that version's own dynamic-texture upload API — **exact class name
(`NativeImage`+`DynamicTexture`-shaped or renamed equivalent) is not
`javap`-confirmed this pass** (spec itself flags this exact gap; carried
forward verbatim as Risk 3, first concrete implementation step for this one
class specifically, before writing any of its body). Until an avatar is
available, `FriendSidebarWidget` draws a plain flat-colored square (a
`graphics.fill(...)` call, the same no-new-texture-needed technique already
established for `CloudOnlyWorldListEntry`'s cloud icon per `minecraft.md`'s
table) — no placeholder PNG asset needed.

### 6. `FriendsService` action methods use the corrected `OverlayToUserDialog` enum, not string literals
Per Existing Implementation's steamworks4j corrections: `FriendsService.openChat(long steamId64)`
calls `steamFriends.activateGameOverlayToUser(OverlayToUserDialog.Chat, new SteamID(steamId64))`;
`showProfile(long steamId64)` calls the same with `OverlayToUserDialog.SteamID`.
Both wrapped in try/catch (NFR2) logging a warning and no-op on any thrown
exception (covers "overlay unavailable/disabled" per FR3.1/FR3.2, since
steamworks4j itself does not expose a separate "is overlay enabled" query
this plan found). `invite`/`join` (`FriendActionListener.onInvite`/`onJoin`)
are implemented as **empty method bodies with a code comment citing FR3.3/FR3.4**
— never wired to `inviteUserToGame` in v1, and unreachable from the UI since
`FriendContextMenuWidget`'s Invite/Join rows are always non-interactive
(Decision 4) — not merely "implemented but disabled," per spec Non-goals'
explicit "no connect-string... exists yet in v1."

### 7. Refresh cadence and callback registration: `FriendsService` owns both, ticked from the composition root, no new background thread
Per FR1.4/FR1.5/Architecture — Threading: unlike `steam-cloud-sync`'s Group 6
(which needed `CloudSyncWorker`, a background thread, for CPU-heavy
compression), this feature's refresh work is cheap local IPC only — no
background thread is introduced (`architecture.md`'s graduate-on-second-use
reasoning: this feature has no CPU-heavy step to justify one).
`FriendsService.tick()` is called directly from
`ClientTickEvents.END_CLIENT_TICK` (registered by
`FriendsSidebarClientInitializer`, the *same* event family
`SteamworksClientInitializer`'s own `pumpCallbacks()` and
`steam-cloud-sync`'s `pumpTickWork()` already share — confirmed safe to
register a third listener, per `steam-cloud-sync/implementation-plan.md`'s
own Decision 8 precedent) and internally rate-limits its own
`getFriendCount`/`getFriendByIndex`/... refresh sweep to a configurable
interval (default, e.g., every 5 seconds — exact value a
`FriendsSidebarConfig` field, `refreshIntervalSeconds`, added alongside
`enabled`; not fixed by the spec, chosen here as a reasonable default,
confirm/tune during verification). The `SteamFriendsCallback` is registered
once, at `FriendsService` construction, and its `onPersonaStateChange`/
`onAvatarImageLoaded`-shaped handlers mark the affected friend's cached
`FriendSummary`/avatar as dirty for the next tick to re-resolve — delivered
correctly only because `SteamworksService.pumpCallbacks()` (already
registered by `SteamworksClientInitializer`) keeps running; this feature's
own composition root does not register a second `runCallbacks()` pump
(spec FR1.5 explicitly warns against duplicating it).

### 8. `FriendSidebarStateMachine`: plain-JVM-testable hover/expand + menu-option-availability logic (NFR1)
A single pure class, `features/friends-sidebar/services/FriendSidebarStateMachine.java`,
computes: given a mouse position and the sidebar's own screen-space bounds,
`collapsed` vs. `expanded` (FR2.4, a single sidebar-wide boolean, never
per-row); given a `FriendSummary`, which of the four context-menu options are
enabled (Open chat/Show profile always enabled per FR3.1/FR3.2; Invite/Join
always disabled in v1 per FR2.6/FR3.3/FR3.4 regardless of `FriendSummary.inGame`/
`joinable` — the state machine still receives those fields so a later
Future-Extension enabling them is a small, localized change, not a redesign).
Zero `net.minecraft.*`/steamworks4j import — directly unit-testable, mirroring
`CloudOnlyWorldDetector`'s own pure-logic precedent in `steam-cloud-sync`.

## Files to Create

### `api` module (top-level, zero dependencies — same precedent as `SteamAvailability`/`CloudSyncable`)
- `api/src/main/java/de/lazuli/api/friends/FriendSummary.java` — record:
  `steamId64, personaName, personaState, avatarHandle, inGame, joinable, connectHint`
  (spec Public API item 1).
- `api/src/main/java/de/lazuli/api/friends/FriendSidebarHook.java` —
  `void updateFriends(List<FriendSummary> friends); void setEnabled(boolean enabled);`.
- `api/src/main/java/de/lazuli/api/friends/FriendActionListener.java` —
  `void onOpenChat(long steamId64); void onShowProfile(long steamId64); void onInvite(long steamId64); void onJoin(long steamId64);`.

### `features/friends-sidebar` module (new Gradle subproject)
- `features/friends-sidebar/build.gradle` — `dependencies { api project(':api'); implementation project(':services') }` (identical shape/rationale to `features/steam-cloud-sync/build.gradle`).
- `features/friends-sidebar/README.md`

**`api/` sub-package** (`de.lazuli.features.friendssidebar.api`, feature-internal, never crosses the Platform boundary):
- `FriendsSidebarConfig.java` — record: `enabled` (default `true`), `refreshIntervalSeconds` (default e.g. `5`) + `DEFAULT` constant (spec Configuration; `refreshIntervalSeconds` is this plan's own addition per Decision 7, spec's own "additional fields... plausible planning-time additions").

**`config/` sub-package** (hand-rolled JSON, `HelloWorldMainMenuConfigIO`-shaped single-schema parser — no shared generic `CloudSyncJson`-style parser needed, only one small file):
- `FriendsSidebarConfigIO.java` — `config/friends-sidebar.json` load/parse/serialize; malformed → defaults + warning, never throws.

**`services/` sub-package**:
- `FriendsService.java` — owns the `SteamFriends`/`SteamUtils` instances (constructed only if `SteamAvailability.isSteamAvailable()`), the `SteamFriendsCallback` registration (FR1.5), `tick()` (Decision 7's rate-limited refresh sweep translating raw calls into `FriendSummary` records and avatar RGBA byte arrays), `List<FriendSummary> currentFriends()`, `byte[] avatarRgba(long steamId64)`-shaped accessor (`Optional<byte[]>`, empty until first delivered), and the four `FriendActionListener` implementations (`openChat`/`showProfile` real per Decision 6; `invite`/`join` empty per Decision 6). The **only** class in this feature importing `com.codedisaster.steamworks.*` (mirrors `steam-cloud-sync`'s own "two seams only" discipline, Decision 7 there — here it is one seam, since this feature has no separate streamed-write/async-read surface).
- `NoopFriendsService.java` — same public shape as `FriendsService` (or a shared small interface both implement, e.g. `FriendsDataSource`), used whenever `!SteamAvailability.isSteamAvailable()` or `config.enabled() == false`; returns an empty friend list and no-ops every action — structurally satisfies FR0.2 without scattered `if` branching, same shape as `steam-cloud-sync`'s `NoopCloudFileStore`.
- `FriendSidebarStateMachine.java` — Decision 8's pure hover/expand/menu-option logic.
- `FriendsSidebarFacade.java` — implements `FriendSidebarHook`+`FriendActionListener` together as the one object the platform composition root hands to its Version Adapters; thin composition of whichever of `FriendsService`/`NoopFriendsService` was constructed plus `FriendSidebarStateMachine`.

**`events/`, `gui/`, `mixins/` sub-packages** — each a `package-info.java` placeholder, identical rationale to `steam-cloud-sync`'s own (FR8-equivalent layering forbids `net.minecraft.*`/`net.fabricmc.fabric.api.*` outside `platform/`; no new cross-feature event bus).
**`resources/`** — `.gitkeep` placeholder (no bundled assets at the feature-module level; the flat-colored-square avatar placeholder needs no texture asset at all, per Decision 5).

**`tests/`** (`src/test/java/de/lazuli/features/friendssidebar/...`): unit tests for `FriendsSidebarConfig`/`FriendsSidebarConfigIO` (round-trip + malformed-input fallback, mirroring `HelloWorldMainMenuConfigIOTest`), and `FriendSidebarStateMachine` (hover/expand transitions given synthetic bounds/mouse positions; menu-option-availability given synthetic `FriendSummary` values, asserting Invite/Join are *always* disabled in v1 regardless of `inGame`/`joinable`). `FriendsService`/`NoopFriendsService` are **not** unit-tested against a real `SteamFriends` (no fake-seam interface is introduced here the way `CloudFileStore` was for `steam-cloud-sync`, since this feature's only steamworks4j surface is a handful of simple read calls plus two overlay-activation calls, not a read/write file store with meaningfully complex business logic to isolate — a smaller feature than Cloud Sync justifies a smaller test-seam investment; this is stated explicitly as a plan-level scope decision, not an oversight).

### Platform modules — one composition root + two Version Adapters + supporting classes per module (×3: `fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`)
- `platform/fabric-<version>/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java` — new `ClientModInitializer`; composition root. Resolves `SteamworksServiceHandoff.require()`, loads `FriendsSidebarConfigIO`, constructs `FriendsService`/`NoopFriendsService` + `FriendsSidebarFacade`, registers `ClientTickEvents.END_CLIENT_TICK -> friendsService::tick` and `ClientLifecycleEvents.CLIENT_STOPPING` (if `FriendsService` needs any shutdown step — likely none beyond what `SteamworksService.shutdown()` already covers; confirm at implementation time), constructs `FabricFriendsSidebarInjector`.
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java` — Pattern 1 (Decision 1); one `ScreenEvents.AFTER_INIT` registration, `instanceof`-chain against this module's own allow-list (Decision 2), adds one `FriendSidebarWidget` (and, when a row is clicked, one `FriendContextMenuWidget`) via `Screens.getWidgets(screen)`; owns the "is a context menu open, intercept Escape/outside-click" logic (Decision 4).
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/FriendSidebarWidget.java` — Decision 3.
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/FriendContextMenuWidget.java` — Decision 4.
- `platform/fabric-<version>/src/main/java/de/lazuli/friends/AvatarTextureCache.java` — Decision 5.
- **Mixin fallback (conditional, not committed up front)**: `platform/fabric-<version>/src/main/java/de/lazuli/mixin/HostScreenEscapeInterceptMixin.java` — only created if Decision 4's Pattern-1-only Escape-interception approach proves insufficient during implementation (Risk 7); if created, registered in that module's own `lazuli.mixins.json`.

### Documentation
- No new ADR expected — this feature does not introduce any new cross-Feature-bridging shape (unlike `steam-cloud-sync`'s ADR-0003); it depends only on `api`/`services`, same as every ADR-0001/0002-covered case already established. Confirm this holds once Decision 4's context-menu Escape-interception design is finalized (if it needs a mixin that itself needs to reference another Feature's class — not expected, flagged only for completeness).

## Files to Modify
- `settings.gradle` — add `include 'features:friends-sidebar'`.
- `platform/fabric-26.2/build.gradle`, `platform/fabric-26.1/build.gradle`,
  `platform/fabric-1.21.11/build.gradle` — each gains
  `implementation project(':features:friends-sidebar')`.
- `platform/fabric-26.2/src/main/resources/fabric.mod.json`,
  `platform/fabric-26.1/.../fabric.mod.json`,
  `platform/fabric-1.21.11/.../fabric.mod.json` — each gains a **fourth**
  entry in the existing `"client"` array,
  `"de.lazuli.FriendsSidebarClientInitializer"`, positioned after
  `"de.lazuli.SteamworksClientInitializer"` (order load-bearing — Existing
  Implementation). No requirement to run after or before
  `SteamCloudSyncClientInitializer` specifically (the two features share no
  state), but for consistency this plan places it last in the array.
- `platform/fabric-<version>/.../lazuli.mixins.json` (×3) — only if Risk 7's
  fallback mixin is actually needed during implementation; not modified
  otherwise.
- `.claude/context/minecraft.md` — gains new rows once implementation
  confirms (via real compile / `javap`) the allow-listed screen classes'
  exact package names and `ScreenEvents.AFTER_INIT` reachability for
  `RealmsMainScreen`/`OptionsScreen`/`PauseScreen`/`GameMenuScreen`
  (Decision 2, Risk 1/2), and the confirmed dynamic-texture upload API
  (Decision 5, Risk 3) — per this repo's own living-record convention, not
  modified by this planning pass itself.

## Interfaces
- `api/.../friends/FriendSidebarHook` — the sidebar's own `MainMenuHook`-shaped Platform API (spec Public API item 1), implemented by `FriendsSidebarFacade` (Feature), consumed by `FabricFriendsSidebarInjector`/`FriendSidebarWidget` (Platform).
- `api/.../friends/FriendActionListener` — row/menu click callback surface, same direction.
- `api/.../friends/FriendSummary` — the one plain data type crossing the Platform/Feature boundary.

## Services
- `FriendsService` (feature-owned) — the sole steamworks4j-importing class (Decision "Files to Create"/services above).
- `FriendSidebarStateMachine` (feature-owned, pure) — Decision 8.
- No new `services/`-module (shared-across-features) capability is introduced — this feature needs nothing `steam-cloud-sync`/the bootstrap don't already provide at that layer (graduate-on-second-use unchanged).

## Feature Classes
Enumerated fully under Files to Create above (`api/`, `config/`, `services/`
sub-packages). All plain Java; NFR1 requires (and `FriendsService` being the
single named exception structurally guarantees) zero
`net.minecraft.*`/steamworks4j-native-call import outside that one class.

## Tests

### Test Strategy
- `FriendsSidebarConfig`/`FriendsSidebarConfigIO` — plain-JVM round-trip +
  malformed-input-falls-back-to-defaults tests, identical shape to
  `HelloWorldMainMenuConfigIOTest`/`SteamCloudSyncConfigIOTest`.
- `FriendSidebarStateMachine` — the highest-value pure-logic test target in
  this feature: hover/expand transitions (mouse inside vs. outside sidebar
  bounds, collapsed default, expands the *whole* sidebar not a single row);
  menu-option availability (Open chat/Show profile always enabled; **Invite
  to game/Join game always disabled** regardless of a `FriendSummary`'s
  `inGame`/`joinable` values — this specific always-false assertion is the
  single most important regression guard for FR2.6/FR3.3/FR3.4's "disabled
  placeholder, not merely unwired" requirement).
- `FriendsService`/`NoopFriendsService`/`AvatarTextureCache`/`FriendSidebarWidget`/
  `FriendContextMenuWidget`/`FabricFriendsSidebarInjector` are **not**
  unit-testable on a plain JVM (real `SteamFriends`/`SteamUtils` native
  calls, real `Screen`/widget/texture-upload classes) — per
  `ui-guidelines.md`'s Testing section, verified manually in-game only, per
  supported Minecraft version target, per allow-listed screen.
- **Manual in-game verification matrix** (explicit test-strategy limitation,
  called out per the task's own instruction): every check below requires a
  **live, running Steam client session with at least one real Steam friend**
  to observe non-empty sidebar content — this cannot be simulated or
  automated in this repo's current tooling (no steamworks4j test double for
  a populated friends list exists anywhere in this codebase, unlike the
  file-store fakes `steam-cloud-sync` could hand-write for its own simpler
  read/write contract). The matrix, run once per platform module
  (`fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`), Steam both running (with
  ≥1 friend) and not running:
  - Sidebar renders (collapsed, avatar-only) on every allow-listed screen
    that successfully receives `ScreenEvents.AFTER_INIT` (Decision 2); a
    screen found unreachable is logged and excluded (spec Non-goals),
    **not** force-fitted.
  - Hover anywhere on the sidebar expands the whole sidebar to show names
    (FR2.4); moving the mouse off collapses it immediately, no click needed.
  - Left- and right-click on a friend row both open the same context menu at
    the cursor position (FR2.5/FR2.6); Open chat/Show profile are visually
    enabled and, when clicked, open Steam's overlay chat/profile dialog for
    that friend; Invite to game/Join game render visually disabled and do
    not respond to a click.
  - Clicking outside the open menu, and pressing Escape while it is open,
    closes it without also triggering the host screen's own Escape behavior
    (FR2.7) — specifically verify on `PauseScreen`/`GameMenuScreen` that
    Escape-to-close-the-menu does **not** also close the pause menu itself
    in the same keypress.
  - Steam not running / `enabled: false`: no sidebar, no `SteamFriends`
    object constructed (spot-check via log output), no exception, every
    allow-listed screen otherwise behaves identically to vanilla.
  - `RealmsMainScreen` specifically (Risk 2): confirm whether the sidebar
    appears at all; if not, this is an acceptable, logged v1 exclusion, not
    a failure to fix before sign-off.

## Dependencies
- **No new external Maven/Gradle dependency.** steamworks4j remains pinned at
  `1.10.0` (`gradle.properties:41`), already resolved in this repo's Gradle
  cache (confirmed by `steam-cloud-sync/implementation-plan.md`'s own
  Existing Implementation, reused here without re-verifying the cache path
  again). `SteamFriends`/`SteamUtils`/`OverlayToUserDialog` signatures
  confirmed via direct `WebFetch` of
  `https://raw.githubusercontent.com/code-disaster/steamworks4j/1.10.0/java-wrapper/src/main/java/com/codedisaster/steamworks/{SteamFriends,SteamUtils}.java`
  (Existing Implementation) — the one exception, `OverlayToUserDialog`'s
  exact enum constant list, came from `WebSearch` rather than a second direct
  fetch (flagged, Risk 5).
- **New internal (inter-module) dependency edges**, all `project(...)`:
  - `features:friends-sidebar` → `api` (`api` configuration)
  - `features:friends-sidebar` → `services` (`implementation` configuration)
  - `platform:fabric-26.2` → `features:friends-sidebar` (`implementation`)
  - `platform:fabric-26.1` → `features:friends-sidebar` (`implementation`)
  - `platform:fabric-1.21.11` → `features:friends-sidebar` (`implementation`)
- **No new Fabric API Gradle coordinate**: `ScreenEvents`/`Screens`
  (`fabric-screen-api-v1`) and `ClientTickEvents`/`ClientLifecycleEvents`
  (`fabric-lifecycle-events-v1`) are already transitively available via each
  platform module's existing `fabric-api` dependency, identical to every
  prior feature's own reuse of these same modules.
- This feature does **not** depend on `features:steam-cloud-sync` in any
  direction (spec Architecture, Non-goals; `architecture.md:74`'s
  Feature-to-Feature dependency prohibition) — the two features' composition
  roots are entirely independent client entrypoints sharing only
  `SteamworksServiceHandoff`'s published instance.

## Risks
1. **The FR2.2 allow-list's exact screen class names/packages are confirmed
   only by ZIP-entry-pathname presence in this machine's real resolved jars
   (`Glob`+`Grep`), not by `javap`/decompile method-signature inspection** —
   same tool limitation `steam-cloud-sync/implementation-plan.md` already
   documented honestly for its own Group 6 work. Implementation's concrete
   first step for this feature's own injector work must be a real compile
   against each module's actual dependency, confirming package paths (this
   plan's `OptionsScreen`/`RealmsMainScreen` package guesses in particular)
   and that `ScreenEvents.AFTER_INIT` actually fires for each allow-listed
   screen — log the confirmed result in `minecraft.md`'s table per that
   file's convention.
2. **`RealmsMainScreen` reachability via `ScreenEvents.AFTER_INIT` is
   unconfirmed** (spec's own explicit flag, Architecture; carried forward
   verbatim) — its third-party-library-driven internals may not construct/
   fire the event the same way a native vanilla screen does. Spec-sanctioned
   fallback: exclude and log, do not mixin-fit this one screen (Decision 1).
3. **The dynamic-texture upload API for avatar rendering
   (`NativeImage`+`DynamicTexture`-shaped or renamed equivalent) is not
   `javap`-confirmed for any of the three platform modules** (spec's own
   flagged gap, carried forward) — `AvatarTextureCache`'s concrete
   implementation is the first piece of Group-analogous UI work that must
   start with a real compile/`javap` pass against each module's resolved
   jar, not an assumption.
4. **`SteamFriendsCallback`'s exact declared method signatures
   (`onPersonaStateChange`/`onAvatarImageLoaded`-shaped) were not retrieved
   by this planning pass's `WebFetch` attempts** — `FriendsService`'s
   callback-registration code is the first concrete implementation step
   needing a fresh, successful fetch/read of that interface's real source
   (or a real compile against the already-resolved jar) before being
   written.
5. **`OverlayToUserDialog`'s enum constant list came from `WebSearch`, not a
   second direct `WebFetch` of the enum's own source file** — low risk (the
   two constants this feature actually needs, `Chat`/`SteamID`, are
   independently corroborated by Valve's own public `ISteamFriends` docs
   citing the same two dialog-name strings the spec itself already cites,
   `"chat"`/`"steamid"`), but implementation should still let a real compile
   confirm the exact enum constant names before relying on them.
6. **`FriendSidebarWidget`'s exact top-level renderable/clickable interface
   name per platform module is not independently `javap`-confirmed this
   pass** (Decision 3) — smallest-risk item in this plan (a custom widget
   class only needs to satisfy whatever interface `Screens.getWidgets(...)`'s
   list element type requires, already proven reachable by every existing
   Pattern-1 injector in this repo); confirm the exact interface name at
   implementation time via a real compile.
7. **Decision 4's Escape/outside-click interception is the one place this
   plan's default "Pattern 1 only, no mixin" commitment has a concrete,
   pre-identified fallback trigger** — if intercepting Escape *before* the
   host screen's own handling turns out not achievable purely through
   widget-list ordering (the same class of "input-priority problem" the
   spec's own Architecture section flags as shape 2's justification), the
   fallback is the single narrowly-scoped mixin named under Files to Create
   ("conditional, not committed up front"). This should be resolved early in
   implementation (before building out `FriendContextMenuWidget`'s full
   behavior) since it affects that widget's own design, not deferred to the
   end.
8. **No fake/test-double seam exists for `FriendsService`'s own steamworks4j
   calls** (unlike `steam-cloud-sync`'s `CloudFileStore`/`WorldArchiveCloudStore`
   seam) — this plan's Test Strategy explicitly accepts a smaller unit-test
   surface for this feature's Steam-facing class, relying on manual in-game
   verification with a real friend list instead; flagged as a deliberate,
   scope-proportionate trade-off, not an oversight, but noted in case a
   future feature needs a richer `SteamFriends`-shaped fake and this
   decision is revisited then.
9. **Manual verification requires a live Steam session with at least one
   real friend online** to exercise most of FR1–FR3 meaningfully — if no
   such friend/account pairing is available during the verification phase,
   several checks in the Test Strategy's manual matrix (avatar rendering,
   `PersonaStateChange`-driven refresh, `ActivateGameOverlayToUser` actually
   opening Steam's overlay) cannot be fully exercised and should be
   explicitly marked "not verified, no test friend available" in the
   verification report rather than silently skipped.

## Acceptance Criteria
Mapped to the specification's functional and non-functional requirements:

- **FR0.1–FR0.3** — Code review: `FriendsSidebarClientInitializer` calls
  `SteamworksServiceHandoff.require()` and never re-initializes Steamworks;
  when `!isSteamAvailable()` or `config.enabled() == false`,
  `NoopFriendsService` is constructed instead of `FriendsService` and no
  `SteamFriends`/`SteamUtils` object is ever constructed (spot-checked via
  log output + code review, mirroring `steam-cloud-sync`'s own FR0.2-style
  acceptance check); `FriendsSidebarConfigIO` round-trip + malformed-fallback
  tests pass.
- **FR1.1–FR1.5** — In-game (live Steam + ≥1 friend): sidebar populates from
  the real friend list within one refresh interval of opening an
  allow-listed screen; a friend's presence/game-played state updates within
  one refresh interval of it changing on Steam's side; an avatar not yet
  loaded renders the flat-color placeholder, then the real avatar once
  `onAvatarImageLoaded` delivers it, without ever throwing.
- **FR2.1–FR2.7** — `FriendSidebarStateMachineTest` covers hover/expand
  transitions and (critically) that Invite/Join are *always* disabled
  regardless of friend state; in-game check confirms whole-sidebar hover
  expand/collapse, either-mouse-button opens the context menu at cursor
  position, and outside-click/Escape close it without also closing the host
  screen (specifically re-verified on `PauseScreen`/`GameMenuScreen`).
- **FR3.1–FR3.5** — In-game: Open chat/Show profile actually open Steam's
  overlay dialogs for the clicked friend (or log a warning if the overlay is
  unavailable, never crash); Invite to game/Join game are confirmed
  non-clickable in every state a test friend can be put into.
- **NFR1** — `grep`-spot-check: zero `net.minecraft.*`/`com.codedisaster.steamworks.*`
  import anywhere in `features/friends-sidebar/src/main` except
  `FriendsService.java` (the one named exception, Files to
  Create/services); `gradlew :features:friends-sidebar:test` runs with no
  Minecraft jar on its test classpath.
- **NFR2** — Code review + manual in-game soak test: no uncaught exception
  from any `SteamFriends`/`SteamUtils` call site reaches the tick/render
  thread or crashes any allow-listed screen's `init()`/`render()`.
- **NFR3** — Every public class/interface created carries a JavaDoc comment
  with at least one usage example (spot-checked against the full Files to
  Create list).
- **NFR4** — `features/friends-sidebar` contains all required sub-packages
  (`api`, `config`, `events`, `gui`, `mixins`, `resources`, `services`,
  `tests`) plus `README.md`, `mixins/` staying a permanent empty placeholder
  unless Risk 7's fallback is triggered (in which case the real mixin still
  lives only in `platform/.../mixin/`, never here).
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules with the new `features:friends-sidebar` dependency and new
  `fabric.mod.json` entrypoint entry in place; manual in-game verification
  across all three targets confirms the acceptance checks above, Steam both
  running and not running, on every allow-listed screen the real compile
  confirms is reachable (Risk 1/2).

## Open Questions
- None remaining from the specification's own explicitly-flagged
  planning-phase items — the injection-shape choice (Architecture's central
  question) is resolved as Decision 1, the FR2.2 allow-list's concrete class
  names are resolved as Decision 2 (with residual per-module confirmation
  items carried as Risk 1/2, not open design questions), and the two
  steamworks4j API details the spec's own citations only approximated
  (`activateGameOverlayToUser`'s typed enum parameter, exact avatar/image
  method names) are resolved as Decision 6 / Existing Implementation. Any
  further questions should surface during implementation as concrete
  compile-time/`javap`-confirmation findings (Risks 1, 2, 3, 4, 5, 6), not as
  open design questions.
