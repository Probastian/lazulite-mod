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
  already targets), `net.minecraft.client.gui.screens.options.OptionsScreen`
  (per `.claude/context/minecraft.md`'s own real-compile-confirmed package,
  which superseded this plan's own original guessed package —
  `net.minecraft.client.gui.screens.OptionsScreen` — recorded here so the
  v1.1 additions below reference the already-corrected package),
  `net.minecraft.client.gui.screens.PauseScreen` (**not** `GameMenuScreen` —
  confirmed absent on this side), `com.mojang.realmsclient.RealmsMainScreen`
  (also real-compile-corrected in `minecraft.md`'s table from this plan's
  original `net.minecraft.realmsclient.gui.screens.RealmsMainScreen` guess —
  Realms lives under Mojang's own `com.mojang.realmsclient` namespace here,
  not under `net.minecraft` at all).
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

**Confirmed as-built (real compile, both platform families)**: the concrete
top-level widget base classes actually used are `net.minecraft.client.gui.components.AbstractWidget`
(26.x, Mojang) and `net.minecraft.client.gui.widget.ClickableWidget` (1.21.11,
Yarn) — Risk 6 is resolved; both compile and are already exercised by the
real `FriendSidebarWidget.java`/`FriendContextMenuWidget.java` in every
module (Existing Implementation, v1.1 section below).

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

**Confirmed as-built**: `ScreenMouseEvents.beforeMouseClick(screen)` /
`ScreenKeyboardEvents.allowKeyPress(screen)` (both `fabric-screen-api-v1`) are
the real Fabric API hooks used by `FabricFriendsSidebarInjector` for
outside-click and Escape interception respectively — no mixin fallback was
needed for v1 (Risk 7 did not trigger). This same pair of hooks is reused
unchanged by the v1.1 additions below.

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

**Confirmed as-built (real compile, Risk 3 resolved)**: per
`.claude/context/minecraft.md`'s table, 26.x uses
`com.mojang.blaze3d.platform.NativeImage` + `net.minecraft.client.renderer.texture.DynamicTexture`
(a concrete class), `NativeImage.setPixelABGR(x, y, abgr)` (no ARGB
convenience overload), `TextureManager.register(Identifier, AbstractTexture)`;
1.21.11 uses `net.minecraft.client.texture.NativeImage` +
`net.minecraft.client.texture.NativeImageBackedTexture` (26.x's
`DynamicTexture` name is instead an *interface* there),
`NativeImage.setColorArgb(x, y, argb)`, `TextureManager.registerTexture(...)`.
`AvatarTextureCache.java` (all three modules) is already built against these
confirmed names; the avatar path used is `getLargeFriendAvatar` (184×184,
`AvatarTextureCache.SIZE`/`AVATAR_SIZE`), not `getSmallFriendAvatar` as this
plan originally assumed — downscaled at draw time by `FriendSidebarWidget`'s
own 12-arg `blit(...)` call passing `DISPLAY_SIZE` as the destination
width/height and `AvatarTextureCache.SIZE` as the source region/texture
size. This large-avatar-downscaled-to-small choice (rather than fetching
`getSmallFriendAvatar` directly) is carried forward unchanged into v1.1
(Decision 12 reuses the identical call for the pinned own-profile row, FR5.4).

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

**Correction versus this decision's own original text**: the real,
as-built `FriendsService.activateOverlay(...)` does call a genuine
`steamUtils.isOverlayEnabled()` guard before `activateGameOverlayToUser(...)`
— steamworks4j's `SteamUtils` *does* expose this query after all (this
plan's original claim that no such query exists was incorrect; superseded
here, no action needed, the as-built code is strictly better than what this
decision originally called for).

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
   file's convention. **Resolved during v1 implementation** — see Decision 2's
   "Confirmed as-built" note and `minecraft.md`'s own table row; carried here
   only as a closed historical record, not an open risk for v1.1.
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
   jar, not an assumption. **Resolved during v1 implementation** — see
   Decision 5's "Confirmed as-built" note; carried here only as a closed
   historical record.
4. **`SteamFriendsCallback`'s exact declared method signatures
   (`onPersonaStateChange`/`onAvatarImageLoaded`-shaped) were not retrieved
   by this planning pass's `WebFetch` attempts** — `FriendsService`'s
   callback-registration code is the first concrete implementation step
   needing a fresh, successful fetch/read of that interface's real source
   (or a real compile against the already-resolved jar) before being
   written. **Resolved during v1 implementation** — the real, as-built
   `FriendsService.Callback` (Existing Implementation, `FriendsService.java`)
   confirms `onPersonaStateChange(SteamID, SteamFriends.PersonaChange)` /
   `onAvatarImageLoaded(SteamID, int, int, int)`; carried here only as a
   closed historical record.
5. **`OverlayToUserDialog`'s enum constant list came from `WebSearch`, not a
   second direct `WebFetch` of the enum's own source file** — low risk (the
   two constants this feature actually needs, `Chat`/`SteamID`, are
   independently corroborated by Valve's own public `ISteamFriends` docs
   citing the same two dialog-name strings the spec itself already cites,
   `"chat"`/`"steamid"`), but implementation should still let a real compile
   confirm the exact enum constant names before relying on them. **Resolved
   during v1 implementation** — the real, as-built `FriendsService.java`
   compiles and calls `OverlayToUserDialog.Chat`/`OverlayToUserDialog.SteamID`
   successfully; closed historical record.
6. **`FriendSidebarWidget`'s exact top-level renderable/clickable interface
   name per platform module is not independently `javap`-confirmed this
   pass** (Decision 3) — smallest-risk item in this plan (a custom widget
   class only needs to satisfy whatever interface `Screens.getWidgets(...)`'s
   list element type requires, already proven reachable by every existing
   Pattern-1 injector in this repo); confirm the exact interface name at
   implementation time via a real compile. **Resolved during v1
   implementation** — see Decision 3's "Confirmed as-built" note; closed
   historical record.
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
   end. **Resolved during v1 implementation, fallback not triggered** — see
   Decision 4's "Confirmed as-built" note; closed historical record.
8. **No fake/test-double seam exists for `FriendsService`'s own steamworks4j
   calls** (unlike `steam-cloud-sync`'s `CloudFileStore`/`WorldArchiveCloudStore`
   seam) — this plan's Test Strategy explicitly accepts a smaller unit-test
   surface for this feature's Steam-facing class, relying on manual in-game
   verification with a real friend list instead; flagged as a deliberate,
   scope-proportionate trade-off, not an oversight, but noted in case a
   future feature needs a richer `SteamFriends`-shaped fake and this
   decision is revisited then. **Still open** — unchanged by v1.1, since the
   v1.1 additions (Decision 12's local-profile accessor, Decision 14's rich
   presence read) extend the same un-fake-seamed class.
9. **Manual verification requires a live Steam session with at least one
   real friend online** to exercise most of FR1–FR3 meaningfully — if no
   such friend/account pairing is available during the verification phase,
   several checks in the Test Strategy's manual matrix (avatar rendering,
   `PersonaStateChange`-driven refresh, `ActivateGameOverlayToUser` actually
   opening Steam's overlay) cannot be fully exercised and should be
   explicitly marked "not verified, no test friend available" in the
   verification report rather than silently skipped. **Still open** —
   unchanged by v1.1; the v1.1 manual matrix (below) inherits this same
   constraint and adds that the *local* player's own account (always
   available, no test friend needed) now covers a meaningful subset of
   these checks for the pinned own-profile row specifically (FR5.1–FR5.4),
   somewhat reducing (but not eliminating) this risk's practical impact.

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

# v1.1 Revision — Layout, Pinned Own Profile, Scroll, Borders, Real Status

Everything below extends the plan above with FR4.x/FR5.x (specification
v1.1 revision, `features/friends-sidebar/specification.md:59-80`). The v1
plan above is unchanged and remains accurate for FR0–FR3 (already
implemented; this section's own Decisions numbered 9+ continue that
numbering, and its own Risks are appended after Risk 9 above using the same
list, not restarted).

## Existing Implementation (v1.1 addendum)
Grounded directly in the real, currently-checked-out code (not the v1 plan's
as-designed version — implementation diverged in the details noted below):

- **Current sidebar anchor/size constants, all three modules identical**
  (`platform/fabric-{26.2,26.1,1.21.11}/.../friends/FriendSidebarWidget.java`):
  `DISPLAY_SIZE = 32`, `ROW_PADDING = 4`, `COLLAPSED_WIDTH = DISPLAY_SIZE + ROW_PADDING*2 = 40`,
  `EXPANDED_WIDTH = 170`, `ROW_HEIGHT = DISPLAY_SIZE + ROW_PADDING*2 = 40`,
  `MAX_ROWS = 12`. Constructed at a **fixed top-left anchor**,
  `new FriendSidebarWidget(6, 6, ...)`, by `FabricFriendsSidebarInjector.onScreenInit(...)`
  — left-edge anchored with a 6px margin on both axes, not right-edge, not
  flush (spec FR4.1/FR4.2 supersede this). `onScreenInit` already receives
  `scaledWidth`/`scaledHeight` from `ScreenEvents.AFTER_INIT`'s own callback
  signature, unused today — the right-edge-flush math (Decision 9) uses this
  already-available parameter, no new event/hook needed.
- **Current avatar draw call, 26.x** (`FriendSidebarWidget.drawRow`, confirmed
  exact 12-arg overload already in use): `guiGraphics.blit(RenderPipelines.GUI_TEXTURED,
  avatarTexture, x + ROW_PADDING, y + ROW_PADDING, 0f, 0f, DISPLAY_SIZE, DISPLAY_SIZE,
  size, size, size, size)` where `size = AvatarTextureCache.SIZE` (184, the
  large-avatar source resolution) — this is the fractional-UV-safe 12-arg
  overload the task's own briefing flags as load-bearing (the 4-arg
  `blit(Identifier, ...)` convenience overload draws nothing); already
  correctly used, no change needed to the *mechanism*, only to the
  `DISPLAY_SIZE` constant it's parameterized by (Decision 10).
- **Current avatar draw call, 1.21.11**: `context.drawTexturedQuad(avatarTexture,
  x + ROW_PADDING, y + ROW_PADDING, x + ROW_PADDING + DISPLAY_SIZE,
  y + ROW_PADDING + DISPLAY_SIZE, 0f, 1f, 0f, 1f)` — whole-texture UV span
  (`0..1`), scaled to `DISPLAY_SIZE` by the destination rectangle alone; no
  fractional-region parameter needed on this side at all (Yarn's
  `drawTexturedQuad` shape differs from 26.x's `blit`, already correctly
  used).
- **Current text draw calls**: 26.x, `guiGraphics.text(Minecraft.getInstance().font,
  friend.personaName(), x + ROW_PADDING + DISPLAY_SIZE + 6, y + ROW_HEIGHT/2 - 4,
  0xFFFFFFFF)` — **already uses full ARGB with a non-zero alpha byte**
  (`0xFFFFFFFF`, not `0xFFFFFF`), so the task's flagged "`text()` no-ops if
  alpha byte is 0" pitfall does **not** currently affect this call and any
  new v1.1 status-text draw call must copy this same full-ARGB convention,
  not the 1.21.11 side's convention below. 1.21.11,
  `context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
  friend.personaName(), x + ROW_PADDING + DISPLAY_SIZE + 6, y + ROW_HEIGHT/2 - 4,
  0xFFFFFF)` — Yarn's `drawTextWithShadow` takes a plain RGB `int` (alpha
  implied opaque by the method itself, confirmed by this call already
  rendering visibly today), a genuinely different color-parameter contract
  from 26.x's `text(...)`, not just a naming difference; any new v1.1 text
  draw call on this side keeps the plain-RGB convention unchanged.
- **Current placeholder/background fill calls, both sides**: `guiGraphics.fill(x1, y1, x2, y2, argbColor)`
  / `context.fill(x1, y1, x2, y2, argbColor)` — used today for (a) the
  semi-transparent sidebar background (`0x99000000`, alpha `0x99`, renders
  correctly) and (b) the flat-colored avatar-placeholder square
  (`personaColor(friend)`, full-alpha `0xFF......`, renders correctly). This
  confirms `fill(...)` has **no alpha-zero pitfall** analogous to `text()`/`blit()`
  on either side — a semi-transparent (`0x99`) and a fully-opaque (`0xFF`)
  alpha both already render as expected in the real, currently-checked-out
  code, so `fill(...)` is confirmed the correct, already-proven building
  block for every new border line this revision adds (Decision 13) with no
  further alpha-safety verification needed. This directly answers the task
  briefing's own open question ("confirm... `fill(...)` is the right
  building block... doesn't have an alpha-zero pitfall").
- **`FriendSidebarStateMachine.isExpanded(...)`** (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendSidebarStateMachine.java`)
  takes `(mouseX, mouseY, sidebarX, sidebarY, sidebarWidth, sidebarHeight)` and
  is pure/`net.minecraft.*`-free — reused unchanged by Decision 9's
  right-edge math (the caller now passes a dynamically-computed `sidebarX`
  each frame rather than a fixed constructor value; the state machine's own
  signature does not change).
- **`FriendsDataSource`/`FriendsService`/`NoopFriendsService`/`FriendsSidebarFacade`**
  (all under `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/`)
  — `FriendsService` is confirmed (Existing Implementation above, v1 section)
  as the sole class importing `com.codedisaster.steamworks.*`; it already
  owns `SteamFriends`/`SteamUtils` construction, `tick()`'s rate-limited
  refresh sweep, and the `FriendSummary` snapshot map (`friendsByIdSnapshot`)
  the v1.1 pinned-row/status-text work extends. `FriendSummary.personaState()`
  is `SteamFriends.PersonaState.ordinal()` (`resolveFriend(...)`, confirmed);
  **`PersonaState`'s declared enum order** (`WebFetch`,
  `code-disaster/steamworks4j` tag `1.10.0`,
  `SteamFriends.java`): `Offline(0), Online(1), Busy(2), Away(3), Snooze(4),
  LookingToTrade(5), LookingToPlay(6), Invisible(7)` — this is the exact
  ordinal mapping Decision 14's status-color/status-text logic must use;
  **note the enum's own declared order does not match spec FR5.11's prose
  order** ("Online, Away/Snooze, Busy, Offline") — the *ordinals* (0–7 above)
  are what code must switch on, not positional assumptions from FR5.11's
  prose.
- **`getFriendRichPresence` confirmed present** (`WebFetch`, same source/tag):
  `public String getFriendRichPresence(SteamID steamIDFriend, String key)` —
  key-value, not a single "current status string" call; FR5.10's "rich
  presence status string when available" needs a specific, chosen key (see
  Decision 14) since this call requires one.
- **`SteamUser` confirmed present, not currently constructed by this
  feature** (`WebFetch`, same source/tag): `public class SteamUser extends
  SteamInterface`, constructor `public SteamUser(SteamUserCallback callback)`,
  `public SteamID getSteamID()`. `SteamworksService` (the shared bootstrap,
  `services/steamworks/SteamworksService.java`) does **not** itself expose a
  `SteamUser` instance (grepped, no match) — `FriendsService` must construct
  its own, mirroring how it already constructs its own `SteamFriends`/
  `SteamUtils` (Decision 12). `SteamUserCallback`'s own declared interface
  methods were **not** retrieved by this planning pass's `WebFetch` (same
  class of gap the v1 plan already flagged for `SteamFriendsCallback`, Risk 4
  above) — carried forward as new Risk 10.
- **`getPersonaName()`/`getPersonaState()` zero-arg overloads**: spec FR5.3
  already cites these as `javap`-confirmed against this repo's own resolved
  jar; this planning pass did not independently re-run that `javap` pass
  (no such tool available this session, Existing Implementation's own
  stated tool limitation) but has no reason to doubt the spec's own
  first-party confirmation — treated as confirmed per the spec's own
  citation, consistent with how this plan already treats FR5.3/5.4's other
  `javap`-flagged facts.

## Decisions on the Open Questions (v1.1, resolved during planning)

### 9. Right-edge-flush positioning: compute `x` every frame from `scaledWidth` minus the sidebar's *current* rendered width, not a fixed constructor value
Supersedes the current fixed `new FriendSidebarWidget(6, 6, ...)` call
(Existing Implementation) for `x` only — `y` stays a fixed small top margin
(`6`, unchanged; FR4.1/FR4.2 require zero-margin flush only against the
*right* edge, not the top, per the spec's own precise wording, "flush
against the screen edge it is anchored to... the edge is now fixed as the
right edge"). `FriendSidebarWidget` gains a `setScreenWidth(int scaledWidth)`
setter, called by `FabricFriendsSidebarInjector.onScreenInit(...)` (which
already receives `scaledWidth` — Existing Implementation) once per
(re-)init, and internally, at the top of `extractWidgetRenderState`/
`renderWidget` (before computing `expanded`), the widget does:
```java
int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
setX(screenWidth - width);
```
so the sidebar's left edge moves as it expands/collapses, always keeping the
*right* edge flush at `screenWidth` (zero gap, FR4.1). `getX()` (used by
`isMouseOver`/click-index math, already present) automatically reflects the
new value since it's the same `AbstractWidget`/`ClickableWidget` field every
other method already reads — no other method needs to change its own logic,
only its timing relative to this new `setX(...)` call (must run before
`isMouseOver`/click handling in the same frame, i.e. at the top of the
render method, which is where `expanded` is already recomputed today).
`scaledWidth` is not expected to change without a screen re-init (window
resize triggers Minecraft's own screen `init()` re-run, which re-fires
`ScreenEvents.AFTER_INIT` — confirmed general Minecraft/Fabric behavior, not
independently re-verified this pass, low risk since a stale `screenWidth`
for one frame during a resize is a cosmetic, self-correcting glitch, not a
functional bug) — new Risk 11.

### 10. Avatar/row-size reduction (FR4.3): `DISPLAY_SIZE` 32 → 16, `ROW_PADDING` 4 → 2, proportional row-height shrink
`DISPLAY_SIZE = 16` (exactly half of the current `32`, satisfying FR4.3's
"roughly half the size used in the initial implementation" using the real
current constant, not the spec's own illustrative placeholder value).
`ROW_PADDING` also halves, `4 → 2`, so `ROW_HEIGHT = DISPLAY_SIZE + ROW_PADDING*2`
shrinks proportionally with it (`40 → 20`) rather than leaving the old `4px`
padding around a now-much-smaller avatar (which would look disproportionate
and leave "leftover vertical whitespace," the exact failure mode FR4.3
explicitly calls out). `COLLAPSED_WIDTH` shrinks the same way (`40 → 20`).
This is a pure constant change in `FriendSidebarWidget` (all three modules)
— no method-shape change, since every existing method already derives its
own layout math from these constants rather than hardcoding `32`/`4`
anywhere else (confirmed by reading the full file, Existing Implementation).
`AvatarTextureCache.SIZE`/`AVATAR_SIZE` (184, the Steam-delivered source
resolution) is **unrelated to and unchanged by** this decision — only the
*destination* draw size (`DISPLAY_SIZE`, the `blit`/`drawTexturedQuad` call's
width/height parameter) changes; the same already-uploaded 184×184 texture
is simply downscaled further at draw time, no re-upload/cache-key change
needed (Decision 5 / `AvatarTextureCache` is untouched by this revision).

### 11. Expanded-state text scale (FR4.4): kept at the platform's own default (unscaled) text size — 0% shrink, deliberately less drastic than the avatar's ~50% shrink (Decision 10)
FR4.4 requires only that text shrink **less drastically** than the avatar,
not that it shrink by some specific nonzero amount, and that it "remain
legible at the smaller avatar size." The current code already draws
persona-name text at each platform's own default, unscaled font size
(`guiGraphics.text(Minecraft.getInstance().font, ...)` / `context.drawTextWithShadow(
MinecraftClient.getInstance().textRenderer, ...)`, Existing Implementation)
— this plan's resolution is to **leave text drawing exactly as-is,
unscaled**, satisfying FR4.4 literally and trivially (a 0% shrink is by
definition less drastic than the avatar's ~50% shrink) while avoiding new
matrix-scale/pose-transform code this revision has no other need for.
`EXPANDED_WIDTH` (currently `170`) narrows slightly, `170 → 154` (reduced
by exactly the same 16px the avatar's own footprint shrank by, Decision
10 — the row needs less horizontal space for the smaller avatar+padding
column, and text itself is unscaled so it needs the same width it always
did to remain legible, hence the width reduction is exactly avatar-driven,
not text-driven). This is a plan-level UX judgment call (spec explicitly
defers the "exact scale factor" to "an implementation-time visual-tuning
decision," FR4.4) — flagged as a defensible default subject to visual
re-tuning during manual verification (Risk 12), not a hard requirement.

### 12. Pinned own-profile row (FR5.1–FR5.5): a second `FriendSummary` accessor on `FriendsDataSource`, resolved by `FriendsService` via its own new `SteamUser` instance, reusing the existing avatar path
`FriendsDataSource` (interface, `features/friends-sidebar/services/`) gains:
```java
Optional<FriendSummary> localProfile();
```
`NoopFriendsService.localProfile()` returns `Optional.empty()` (FR0.2 no-op
discipline, same shape as its other methods). `FriendsService` gains a
`SteamUser steamUser` field, constructed alongside the existing
`steamFriends`/`steamUtils` fields in its constructor (`this.steamUser = new
SteamUser(new UserCallback());` — a new no-op `SteamUserCallback`
implementation, same shape as the existing no-op `UtilsCallback`, since this
feature has no use for `SteamUserCallback`'s own events beyond the
zero-arg `getSteamID()` query itself; **exact `SteamUserCallback` method
signatures unconfirmed this pass, Risk 10**, first concrete implementation
step for this one field, mirroring the v1 plan's own already-resolved
Risk 4 precedent for `SteamFriendsCallback`). `localProfile()`:
```java
public Optional<FriendSummary> localProfile() {
    try {
        SteamID self = steamUser.getSteamID();
        long steamId64 = SteamID.getNativeHandle(self);
        String personaName = steamFriends.getPersonaName();
        int personaState = steamFriends.getPersonaState().ordinal();
        if (avatarsById.get(steamId64) == null) {
            resolveAvatar(self); // reuses the exact existing per-friend avatar path, FR5.4
        }
        return Optional.of(new FriendSummary(steamId64, personaName, personaState, 0, false, false, null));
    } catch (RuntimeException e) {
        warnLogger.accept("Failed to resolve local Steam profile: " + e.getMessage());
        return Optional.empty();
    }
}
```
called once per `tick()`'s refresh sweep (cheap, same cadence as the
existing friend refresh — no separate interval needed). `resolveAvatar(SteamID)`
already exists and is `SteamID`-parameterized, not friend-list-specific
(Existing Implementation, v1 section, `FriendsService.resolveAvatar`) — it
is reused completely unchanged, satisfying FR5.4's explicit requirement that
this **not** be a new parallel Steamworks call site. `FriendsSidebarFacade`
gains a mirroring `Optional<FriendSummary> localProfile()` (delegating to
`dataSource.localProfile()`, refreshed alongside `friends()` in
`refresh()`), satisfying spec Public API item 5.5's `FriendSummary
localProfile()`-shaped accessor requirement — this plan's concrete choice
is `Optional<FriendSummary>` rather than a bare `FriendSummary`, so the
"not yet resolved" state (e.g. before the first refresh sweep, or if the
local player's Steam identity fails to resolve for any reason) is
represented without a sentinel/null `FriendSummary`, consistent with this
class's own existing `avatarRgba(...)`-shaped `Optional` convention.

**Rendering (FR5.1/FR5.2)**: `FriendSidebarWidget` renders the pinned row
(from `facade.localProfile()`, when present) as row index `-1`, always at
`getY()` (the sidebar's own fixed top edge), **before** the scrollable
friends-list loop (Decision 13's scroll region starts one `ROW_HEIGHT` lower
than today, i.e. `getY() + ROW_HEIGHT + SEPARATOR_HEIGHT`, not `getY()`) —
it is drawn unconditionally every frame regardless of scroll offset,
satisfying "remains visible... even while the friends list beneath it is
scrolled" (FR5.2). Row-drawing itself reuses the exact same `drawRow(...)`
private method already used for every friend row (same avatar-blit/
placeholder-fill/text-draw code path, Decision 3/5/10/11 unchanged) — no
separate rendering code path for the pinned row beyond choosing its
`FriendSummary` source and its fixed (non-scrolling) Y position.

### 13. Scrollable friends list + borders (FR5.6–FR5.9): scroll offset lives on `FriendSidebarStateMachine`, clipping/translation happens in `FriendSidebarWidget`, borders drawn with `fill(...)` (Decision, confirmed safe per Existing Implementation above)
`FriendSidebarStateMachine` (pure, `net.minecraft.*`-free, NFR1) gains:
```java
public int clampScroll(int currentScrollOffset, int deltaRows, int totalRows, int visibleRows);
```
— given the current offset (in *rows*, not pixels, so it stays resolution/
`ROW_HEIGHT`-agnostic and trivially unit-testable), a signed row delta from
one mouse-wheel event, the total friend count, and how many rows currently
fit in the sidebar's scrollable region, returns the new offset clamped to
`[0, max(0, totalRows - visibleRows)]` — pure integer arithmetic, directly
unit-testable (Decision 8's existing precedent, extended). `FriendSidebarWidget`
owns the mutable `scrollOffsetRows` field itself (widget-instance state, the
same ownership shape `expanded` already has today — not pushed into the
stateless facade/state-machine beyond the pure clamp calculation), and
implements that platform's own mouse-scroll widget method
(`mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
double verticalAmount)` on 26.x's `AbstractWidget`/1.21.11's
`ClickableWidget` — both already extended by this class today, Existing
Implementation; exact override signature not independently `javap`-confirmed
this pass, new Risk 13, same low-risk class as the v1 plan's already-resolved
Risk 6) — only when the scroll event's `mouseX`/`mouseY` falls within the
*scrollable region's* bounds specifically (below the pinned row + separator,
Decision 12), not the pinned row or outside the sidebar entirely, mirroring
FR5.6's own explicit "don't consume input intended for the underlying screen
when the pointer is outside the sidebar's own bounds" cross-reference to
FR2.1. The scrollable region's render pass (the existing per-friend
`for (FriendSummary friend : friends)` loop) starts iterating from
`scrollOffsetRows` instead of `0`, and stops once it would draw past the
sidebar's own bottom edge — no `GuiGraphics`/`DrawContext` scissor/clip API
is introduced (the row loop's own start/stop bounds naturally clip content,
since a row that would render off the bottom is simply never drawn, the
same "stop the loop early" technique `MAX_ROWS` already uses today for the
non-scrolling v1 list) — no new cross-version clipping-API research needed.

**Borders (FR5.7–FR5.9)**, all via the already-confirmed-safe `fill(...)`
call (Existing Implementation above):
- **FR5.7 (left-edge sidebar border)**: one `fill(getX(), getY(), getX() + BORDER_WIDTH, getY() + totalHeight, 0xFFFFFFFF)`-shaped call (a thin, e.g. `1`–`2`px, full-opacity light line) drawn once per frame at the sidebar's current left edge (which now moves per Decision 9 as the sidebar expands/collapses — the border moves with it, always staying on the side facing into the screen, opposite the flush-right edge, exactly matching FR5.7's own "opposite the flush right edge" framing).
- **FR5.8 (own-profile/friends-list separator)**: one `fill(...)`-shaped horizontal line at `getY() + ROW_HEIGHT` (immediately below the pinned row, Decision 12), spanning the sidebar's current width.
- **FR5.9 (per-row status-color border)**: one thin `fill(...)`-shaped rectangle along **the row's left edge only** (not a full outline — the smallest-effort choice FR5.9 explicitly leaves open, "exact border placement... is an implementation-time visual decision"; a full 4-sided outline is deferred as a possible visual refinement during manual verification, not committed here), tinted by `Decision 14`'s status-color mapping, drawn for both the pinned own-profile row and every friend row (same per-row helper called from both call sites, Decision 12's "no separate rendering code path" choice extended to borders too).

### 14. Real status text + Steam-standard colors (FR5.10/FR5.11): `PersonaState` ordinal → `(color, label)` mapping lives on `FriendSidebarStateMachine`; rich-presence text is a best-effort upgrade over the plain persona-state label
`FriendSidebarStateMachine` gains two pure methods (NFR1, unit-testable,
zero `net.minecraft.*`/steamworks4j import — the method signatures below use
only `int` ordinals and `String`, never `SteamFriends.PersonaState` itself,
so this class stays plain-JVM per its own established discipline):
```java
public int statusColorArgb(int personaState);   // full-alpha ARGB int
public String statusLabel(int personaState);    // "Online" / "Away" / "Busy" / "Offline" / ...
```
Mapping uses the confirmed real `PersonaState` ordinals (Existing
Implementation above — **not** FR5.11's prose order): `0 Offline` → grey;
`1 Online` → green; `2 Busy` → red; `3 Away` / `4 Snooze` → yellow/amber;
`5 LookingToTrade` / `6 LookingToPlay` → green (same family as Online, per
Steam's own client convention of treating these as "available" states, not
a distinct fifth color FR5.11 doesn't ask for); `7 Invisible` → grey (same
as Offline, since Invisible presents identically to other users on Steam).
Concrete hex values (implementation starting point only — **not**
independently confirmed against a live Steam client this planning pass,
sourced from a community skin-customization reference via `WebSearch`,
`github.com/Borophyll/SteamFriendsList`'s own `.ini`, which cites
`FCGreen=144,186,60,255` / `FCBlue=87,203,222,255` for Steam's own
"online"/text-link colors — an imperfect but reasonable starting citation,
not a Valve first-party source): `Online/LookingTo*` `0xFF5BA32F` (green),
`Away/Snooze` `0XFF3F5E7E` (amber), `Busy` `0xFFD54141` (red),
`Offline/Invisible` `0xFF898989` (grey). **FR5.11 itself already mandates**
these exact values be "confirmed visually against the real Steam client
during manual testing" before sign-off — this decision's chosen hex values
are therefore explicitly a tunable starting point, re-confirmed/adjusted
during the manual verification pass (new Risk 14), not a value this plan
claims final authority over the way Decision 6's `OverlayToUserDialog`
enum values (independently corroborated by Valve's own docs) are.

`statusLabel(...)` supplies FR5.10's fallback text ("Online"/"Away"/"Busy"/
"Offline"/etc.) for every row. **Rich-presence status text upgrade**: for a
friend currently `inGame` (`FriendSummary.inGame()`, already resolved
per-friend, Existing Implementation v1 section), `FriendsService.resolveFriend(...)`
additionally calls `steamFriends.getFriendRichPresence(friend, "status")`
(Valve's own conventional key name for a human-readable status string, the
same key most Steamworks-integrated games populate via `setRichPresence`
— **not independently confirmed as the specific key this repo's own
target games would populate**, since v1.1 has no games actually setting
rich presence to test against; a `null`/empty return is treated as "no
rich presence available," falling back to `statusLabel(...)`, never
throwing) — this is added as a new, `null`-safe field read into a
**new** `FriendSummary` — no, this plan avoids widening the shared `api`-module
`FriendSummary` record for one platform-rendering-only string; instead,
`FriendsDataSource` gains a small additional accessor:
```java
Optional<String> richPresenceStatus(long steamId64);
```
(`FriendsService` returns the last-resolved rich-presence string per friend
from the same `friendsByIdSnapshot`-adjacent cache the refresh sweep
already populates; `NoopFriendsService` returns `Optional.empty()`). Row
rendering: `richPresenceStatus(...).orElse(stateMachine.statusLabel(friend.personaState()))`
— rich presence text if present, else the plain persona-state label, always
something legible, never blank (FR5.10's own "rather than a placeholder or
the raw enum name" requirement, satisfied either way). The local player's
own row (FR5.1) uses `stateMachine.statusLabel(...)` only (`SteamFriends.getPersonaState()`,
FR5.3) — no rich-presence lookup for the local player, since FR5.10 only
asks for "the equivalent status text derived from `getPersonaState()`" on
that one row, not a rich-presence upgrade.

### 15. `javap`-confirm every v1.1 steamworks4j citation against this repo's own resolved jar as the mandatory first implementation step for `FriendsService`'s new `SteamUser`/rich-presence code — not an open design question
Spec's own Overview amendment note is explicit: this revision's research pass
had no `javap`/shell tool available, so every new steamworks4j citation
above (`SteamUser.getSteamID()`, `SteamFriends.getPersonaName()`/
`getPersonaState()` called on the local user, `getFriendRichPresence`,
`requestFriendRichPresence`) is `WebFetch`-sourced only, not independently
`javap`-confirmed against this repo's own resolved
`steamworks4j-1.10.0.jar` (present at
`platform/fabric-*/build/processIncludeJars/steamworks4j-1.10.0.jar`). This
plan resolves that gap the same way it resolves every other citation-
confidence gap in this document (Risk 1's screen-class confirmation, the v1
plan's own Risk 4 for `SteamFriendsCallback`): **planning defers final
confirmation to a mandatory pre-code `javap` step**, run before any of
Decision 12/14's `SteamUser`/rich-presence code is written, not treated as an
open design question left for implementation to improvise on.

**Concrete first step**, before `FriendsService` gains its `steamUser` field
or its `getFriendRichPresence`/`requestFriendRichPresence` call sites:
```
javap -p -cp platform/fabric-26.2/build/processIncludeJars/steamworks4j-1.10.0.jar \
  com.codedisaster.steamworks.SteamUser \
  com.codedisaster.steamworks.SteamFriends \
  com.codedisaster.steamworks.SteamUserCallback
```
(any one platform module's resolved copy is sufficient — steamworks4j is not
per-Minecraft-version, the same jar is Jar-in-Jar'd into all three per
Dependencies). Read the output and confirm, or apply the fallback, for each
citation:

- **`SteamUser.getSteamID()`** — expected `public SteamID getSteamID()`,
  zero-arg. **If confirmed**, implement Decision 12 exactly as written. **If
  no such zero-arg method exists on `SteamUser`** (different name, an added
  parameter, or `SteamUser` structured differently than a simple
  `SteamUserCallback`-constructed wrapper): scan the same `javap` output for
  whichever zero-arg method returns a `SteamID` (Valve's API guarantees some
  such accessor exists — `ISteamUser::GetSteamID` is one of the most
  fundamental calls in the whole API) and use that instead; if genuinely none
  is found, FR1.6/Decision 12's own-profile row is blocked pending a second
  research pass — log this explicitly, do not guess further, and do not
  silently drop the pinned own-profile row (FR4.4/FR5.1's "always show"
  guarantee) without recording why.
- **`SteamFriends.getPersonaName()`/`getPersonaState()` (no-arg, self-only
  overloads)**, used by Decision 12's `localProfile()` — these are already
  cited in the v1 plan/spec as `javap`-confirmed against this repo's own
  resolved jar (Existing Implementation, v1.1 addendum, "treated as confirmed
  per the spec's own citation"); this `javap` pass should still positively
  re-confirm both resolve for the local `SteamID` specifically (not merely
  that the methods exist), since a signature existing is not the same as it
  returning valid data for the local user — if either throws or returns
  empty/garbage for the local player specifically, `localProfile()`'s
  `try`/`catch` (Decision 12) already degrades to `Optional.empty()` per
  NFR2, so no code-shape fallback is needed here, only a confirmation that
  the happy path is real.
- **`getFriendRichPresence(SteamID, String)` / `requestFriendRichPresence(SteamID)`**
  — expected `String getFriendRichPresence(SteamID, String key)` /
  `void requestFriendRichPresence(SteamID)`. **If confirmed with these exact
  shapes**, Decision 14's `"status"`-keyed lookup proceeds as written — but
  note Decision 14 as currently written calls only `getFriendRichPresence`,
  never `requestFriendRichPresence` first; this `javap` step's confirmation
  must also settle whether a value is returned without an explicit
  `requestFriendRichPresence(...)` call having been made at least once per
  friend (Valve's docs say Rich Presence values are cached locally and only
  refresh on request) — if `getFriendRichPresence` alone returns consistently
  stale/empty results in practice, `FriendsService.refresh()`'s per-friend
  loop (Decision 14) must add one `requestFriendRichPresence(friend)` call
  per friend per sweep, immediately before reading the value, before Decision
  14 is considered complete. **If either method's signature differs** (e.g.
  an index-based key lookup, a different parameter order, or
  `requestFriendRichPresence` returning something other than `void`): adapt
  the call site to whatever `javap` shows, keeping the same `"status"` key
  literal unless the output itself reveals a different key-enumeration
  mechanism. **If neither method exists on `SteamFriends` at all** in this
  steamworks4j version: FR5.10/FR1.7's rich-presence upgrade is not
  implementable as specified — fall back to omitting it entirely
  (`richPresenceStatus(...)` stays permanently `Optional.empty()`, every row
  always shows `statusLabel(...)`'s plain persona-state word) and log this
  explicitly as a shipped-scope reduction versus the spec in the verification
  report, not a silent no-op — this is the one fallback among these four that
  changes visible product behavior, so it must be called out even though it
  degrades gracefully to pre-v1.1 parity (no rich-presence text existed
  before this revision either).
- **`SteamUserCallback`'s declared methods** — already tracked as Risk 10
  above; this `javap` pass is the same concrete step that resolves Risk 10,
  not a separate action.

This Decision states the resolved procedure and every fallback; the residual
fact that the procedure has not yet actually been run is tracked as Risk 15
below (the same Decision-states-the-approach / Risk-tracks-what's-still-
unconfirmed split this plan uses throughout, e.g. Decision 1 vs. Risk 1/2,
Decision 6 vs. Risk 5).

## Files to Create (v1.1 additions)
- No new top-level classes — every v1.1 addition is a method/field addition
  to an already-planned/already-existing class (`FriendsDataSource`,
  `FriendsService`, `NoopFriendsService`, `FriendsSidebarFacade`,
  `FriendSidebarStateMachine`, `FriendSidebarWidget` ×3 platform modules,
  `FabricFriendsSidebarInjector` ×3 for the `setScreenWidth(...)` call site)
  — see Files to Modify below for the complete list. This is a deliberate
  scope observation, not an oversight: v1.1's requirements are layout/data
  refinements over an already-shipped v1 shape, not new architectural
  surface.

## Files to Modify (v1.1)
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsDataSource.java`
  — add `Optional<FriendSummary> localProfile();` (Decision 12) and
  `Optional<String> richPresenceStatus(long steamId64);` (Decision 14).
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsService.java`
  — add `SteamUser steamUser` field + no-op `UserCallback implements
  SteamUserCallback` inner class (Decision 12); `localProfile()` (Decision
  12); extend `resolveFriend(...)` to also resolve/cache rich-presence
  status (Decision 14); `richPresenceStatus(long)` accessor.
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/NoopFriendsService.java`
  — add `localProfile()` (returns `Optional.empty()`) and
  `richPresenceStatus(long)` (returns `Optional.empty()`) overrides.
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java`
  — add `localProfile()` delegating accessor, refreshed in `refresh()`
  alongside `friends()` (Decision 12); add `richPresenceStatus(long)`
  passthrough (Decision 14).
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendSidebarStateMachine.java`
  — add `clampScroll(...)` (Decision 13), `statusColorArgb(int)` /
  `statusLabel(int)` (Decision 14).
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`
  (×3) — constants `DISPLAY_SIZE`/`ROW_PADDING`/`COLLAPSED_WIDTH`/
  `EXPANDED_WIDTH`/`ROW_HEIGHT` updated (Decision 10/11); `setScreenWidth(int)`
  + per-frame `setX(...)` recompute (Decision 9); pinned-row rendering
  (Decision 12); `scrollOffsetRows` field + `mouseScrolled(...)` override +
  scroll-aware row-loop start/stop (Decision 13); left-edge/separator/
  per-row status-border `fill(...)` calls (Decision 13); status-text draw
  call sourced from `richPresenceStatus(...).orElse(statusLabel(...))`
  (Decision 14).
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FabricFriendsSidebarInjector.java`
  (×3) — `onScreenInit(...)` calls `sidebar.setScreenWidth(scaledWidth)`
  (Decision 9); no allow-list/injection-shape change otherwise.
- `.claude/context/minecraft.md` — gains a new row once implementation
  confirms (via real compile) the exact `mouseScrolled(...)` override
  signature per platform module (Decision 13, Risk 13) and `SteamUserCallback`'s
  real declared methods (Decision 12, Risk 10) — not modified by this
  planning pass itself, per the file's own living-record convention.

## Test Strategy (v1.1 additions)
- `FriendSidebarStateMachineTest` (existing file, extended): `clampScroll(...)`
  — zero-delta no-op; scrolling past the top clamps to `0`; scrolling past
  the bottom clamps to `max(0, totalRows - visibleRows)`; `totalRows <=
  visibleRows` always clamps to `0` (nothing to scroll). `statusColorArgb(...)`/
  `statusLabel(...)` — every real `PersonaState` ordinal (`0`–`7`) maps to a
  non-null label and a full-alpha (`0xFF......`) color; **explicit regression
  assertion that `Online` (`1`)/`LookingToTrade` (`5`)/`LookingToPlay` (`6`)
  all map to the *same* green** and `Offline` (`0`)/`Invisible` (`7`) map to
  the *same* grey (Decision 14's stated grouping, the single highest-value
  regression guard for this mapping given the ordinal-vs-prose-order trap
  Existing Implementation flags above).
- `FriendSidebarWidget`/`FabricFriendsSidebarInjector`/`FriendsService`'s new
  v1.1 surface (right-edge math, pinned-row rendering, scroll input,
  border drawing, rich-presence resolution) are **not** unit-testable on a
  plain JVM, same NFR1/`ui-guidelines.md` constraint the v1 plan's own Test
  Strategy already states for this class of code — verified manually only.
- **Manual in-game verification matrix additions** (run alongside the
  existing v1 matrix, same three platform modules, same "Steam running with
  ≥1 friend" and "Steam not running" pairing):
  - **FR4.1/FR4.2**: on each of the six allow-listed screen types
    individually (per spec FR4.2's own explicit "verified... on each of the
    six screens individually" requirement, not just `TitleScreen`), confirm
    the sidebar's right edge sits flush (zero visible gap) against the
    window's right edge, both collapsed and expanded (Decision 9's
    per-state `setX(...)` recompute).
  - **FR4.3/FR4.4**: visually confirm avatars read as roughly half their
    old size with no leftover vertical whitespace between rows, and that
    persona-name text remains clearly legible at the new row height
    (Decision 10/11's chosen constants, `DISPLAY_SIZE=16`/`ROW_PADDING=2`/
    `EXPANDED_WIDTH=154`, subject to visual re-tuning if it reads as
    cramped, per Decision 11's own stated tunable-default framing).
  - **FR5.1/FR5.2**: the local player's own avatar+name renders as the
    first row, both collapsed and expanded, and stays pinned/visible while
    scrolling the friends list beneath it (requires **no** test friend —
    exercisable with any Steam account, partially mitigating Risk 9).
  - **FR5.6**: with more friends online than fit in the sidebar's visible
    height, mouse-wheel scrolling over the friends-list region moves the
    list; scrolling over the pinned row or outside the sidebar's bounds
    does nothing to the sidebar and does not consume the scroll event from
    whatever vanilla widget is underneath (e.g. a scrollable vanilla list
    on the same screen, if any is present at that position).
  - **FR5.7/FR5.8/FR5.9**: visually confirm a left-edge sidebar border, a
    separator between the pinned row and the friends list, and a
    per-row/per-pinned-row status-color-tinted left-edge border, all
    render as intended (not clipped, not overlapping the avatar/text).
  - **FR5.10/FR5.11**: with a friend in at least two different persona
    states (e.g. Online and Away, toggled via the Steam client's own status
    menu) and, if a rich-presence-populating game is available, one friend
    actively in such a game, confirm status text updates correctly and
    status-border/text colors visually match Steam's real client palette —
    **explicitly re-tune Decision 14's starting hex values against the real
    Steam client during this pass** (Risk 14), not just spot-check them.

## Dependencies (v1.1)
- **No new external Maven/Gradle dependency.** `SteamUser`/`SteamUserCallback`
  (Decision 12) and `getFriendRichPresence` (Decision 14) are both part of
  the already-pinned `steamworks4j 1.10.0` (`gradle.properties:41`) — same
  jar already resolved in this repo's Gradle cache, no new coordinate.
  `SteamUser`'s class/constructor/`getSteamID()` signatures and
  `getFriendRichPresence`'s signature both confirmed via direct `WebFetch`
  of `https://raw.githubusercontent.com/code-disaster/steamworks4j/1.10.0/java-wrapper/src/main/java/com/codedisaster/steamworks/{SteamUser,SteamFriends}.java`
  (Existing Implementation, v1.1 addendum, above) — the same citation
  discipline the v1 plan's own Dependencies section already established.
  `SteamUserCallback`'s own declared interface methods were **not**
  retrieved this pass (Risk 10, mirrors the v1 plan's already-resolved
  Risk 4 for `SteamFriendsCallback`).
- No new internal (inter-module) dependency edges — v1.1 adds methods to
  already-existing, already-wired classes only (Files to Create v1.1
  addendum, above).

## Risks (v1.1 additions, continuing the v1 plan's numbering)
10. **`SteamUserCallback`'s exact declared method signatures were not
    retrieved by this planning pass's `WebFetch` attempt** (Decision 12) —
    mirrors the v1 plan's own already-resolved Risk 4 for
    `SteamFriendsCallback`; `FriendsService`'s new `SteamUser` field/
    `UserCallback` inner class is the first concrete implementation step
    needing a fresh, successful fetch/read of that interface's real source
    (or a real compile against the already-resolved jar) before being
    written.
11. **`scaledWidth`'s freshness across a window resize is not independently
    re-verified this pass** (Decision 9) — this plan's working assumption
    (a resize re-triggers `Screen.init()`, which re-fires
    `ScreenEvents.AFTER_INIT`, which re-calls `setScreenWidth(...)`) is
    standard, well-documented Minecraft/Fabric behavior but not
    independently `javap`/compile-confirmed this pass; low-severity even if
    wrong (a stale right-edge position for at most one resize event is a
    cosmetic glitch, not a crash or functional break) — confirm during
    implementation's first real in-game resize test.
12. **Decision 10/11's exact new pixel constants (`DISPLAY_SIZE=16`,
    `ROW_PADDING=2`, `EXPANDED_WIDTH=154`) are a plan-level visual-tuning
    default, not a spec-mandated value** (spec FR4.3/FR4.4 both explicitly
    defer the exact factor to implementation-time visual tuning) — flagged
    so the verification phase treats a "looks cramped"/"looks too sparse"
    finding as an expected, in-scope tuning adjustment rather than a
    plan-conformance defect.
13. **The exact `mouseScrolled(...)` override signature per platform module
    is not independently `javap`-confirmed this pass** (Decision 13) —
    same low-risk class as the v1 plan's already-resolved Risk 6 for the
    widget's own top-level renderable interface; confirm via real compile
    at implementation time.
14. **Decision 14's Steam-standard status-color hex values are a
    `WebSearch`-sourced, community-skin-reference starting point, not an
    independently confirmed Valve first-party palette** — FR5.11 itself
    already mandates a manual visual re-confirmation against the real
    Steam client before sign-off (Test Strategy v1.1 addendum, above); this
    risk exists specifically so that re-confirmation step is not skipped or
    treated as a formality, given how weak this plan's own color-value
    citation is relative to, e.g., Decision 6's `OverlayToUserDialog`
    citation.
15. **Decision 15's `javap` verification procedure is fully specified but has
    not actually been run** — this planning pass, like the specification's
    own v1.1 authoring pass (Overview amendment note), had no `javap`/shell
    tool available. `SteamUser.getSteamID()`'s exact signature,
    `getFriendPersonaName`/`getPersonaState()`-for-the-local-user's
    real behavior, and `getFriendRichPresence`/`requestFriendRichPresence`'s
    exact signatures and required call order all remain at
    "`WebFetch`-sourced, not `javap`-confirmed" until implementation's
    mandatory first step (Decision 15) actually runs the command and records
    the result in `.claude/context/minecraft.md`, per that file's own
    "append a row whenever implementation work turns up a real divergence"
    convention. This is the v1.1-amendment equivalent of the v1 plan's own
    Risk 1/Risk 4 (screen-class and `SteamFriendsCallback` confirmation) and
    must be resolved with the same priority — first concrete implementation
    step for any of Decision 12/14's `SteamUser`/rich-presence code, not
    deferred to the end of the implementation phase or skipped because
    Decision 15 already names a fallback for each case.

## Acceptance Criteria (v1.1 additions)
- **FR4.1/FR4.2** — In-game, on each of the six FR2.2-allow-listed screen
  types individually: sidebar's right edge sits flush (no visible gap)
  against the window's right edge, in both collapsed and expanded state.
- **FR4.3/FR4.4** — In-game: avatars visually read as roughly half their
  pre-v1.1 size with rows tightly stacked (no leftover vertical
  whitespace); expanded persona-name text remains clearly legible.
- **FR5.1–FR5.5** — In-game (any Steam account, no test friend required):
  the local player's own avatar+persona name renders as the sidebar's
  first row in both collapsed/expanded state, stays visible while the
  friends list beneath it is scrolled; code review confirms
  `FriendsService.localProfile()` reuses `resolveAvatar(SteamID)`
  unchanged (FR5.4's explicit "not a new parallel Steamworks call site"
  requirement) and that `FriendsDataSource`/`FriendsSidebarFacade` expose
  this data through the existing Feature/Platform boundary, not a new
  ad hoc platform-layer Steamworks call.
- **FR5.6** — `FriendSidebarStateMachineTest.clampScroll(...)` covers
  clamp-at-top/clamp-at-bottom/no-op cases; in-game, with more friends
  than fit visibly, mouse-wheel scrolling moves the friends list while the
  pinned own-profile row stays fixed, and scrolling outside the sidebar's
  bounds does not consume the event.
- **FR5.7–FR5.9** — In-game: left-edge sidebar border, own-profile/
  friends-list separator, and per-row status-color-tinted border all
  render visibly and correctly positioned on every allow-listed screen the
  sidebar itself already renders on.
- **FR5.10/FR5.11** — `FriendSidebarStateMachineTest` covers the full
  `PersonaState`-ordinal-to-`(color, label)` mapping, including the
  Online/LookingToTrade/LookingToPlay-share-green and
  Offline/Invisible-share-grey grouping assertions; in-game, with a friend
  toggled through at least two persona states, status text and
  status-color borders update correctly and are visually re-confirmed
  against the real Steam client's own palette (explicitly not just
  spot-checked against this plan's own starting hex values, per Risk 14).
- **NFR1 (v1.1 re-check)** — `grep`-spot-check confirms the v1.1 additions
  to `FriendsDataSource`/`NoopFriendsService`/`FriendsSidebarFacade`/
  `FriendSidebarStateMachine` introduce zero new `net.minecraft.*`/
  `com.codedisaster.steamworks.*` imports outside `FriendsService.java`
  (the same single named exception the v1 plan's own NFR1 acceptance
  criterion already established, unchanged by v1.1).

## Open Questions (v1.1)
- None remaining from specification v1.1's own explicitly-flagged
  planning-phase items — the pinned-row data-flow question (spec FR5.5's
  "exact method name/signature is a planning-time decision") is resolved
  as Decision 12; the scroll-state-ownership question (spec FR5.6's "exact
  scroll-input mechanism... an implementation-time UI decision") is
  resolved as Decision 13; the border-placement question (spec FR5.9's
  "exact border placement... is an implementation-time visual decision")
  is resolved as Decision 13 (left-edge-only, not full outline); the
  status-color-palette question (spec FR5.11's own "exact hex values
  sourced from Steam's own published/observed client palette") is resolved
  as Decision 14 with an explicit, flagged caveat (Risk 14) that the
  starting values need manual re-confirmation, which the spec itself
  already mandates as a manual-testing step regardless; the "no `javap` tool
  available this research pass" gap the spec's own Overview amendment note
  raises for `SteamUser.getSteamID()`/`getFriendRichPresence`/
  `requestFriendRichPresence` is resolved as Decision 15 — planning defers
  final confirmation to a mandatory pre-code `javap` step with a fully
  spelled-out fallback per method, not treated as an open design question.
  Any further questions should surface during implementation as concrete
  compile-time/`javap`-confirmation findings (Risks 10, 11, 13, 15) or the
  manual visual-tuning/re-confirmation passes the spec itself already
  calls for (Risks 12, 14), not as open design questions.

# v1.2 Revision — Steam-Unavailable Status State

Everything below extends the plan above with FR6.x/NFR6/NFR7 (specification
v1.2 amendment, `features/friends-sidebar/specification.md:50-125`). The v1/
v1.1 plan above is unchanged and remains accurate for FR0–FR5 (already
implemented; note the real, currently-checked-out code has already diverged
further from the v1.1-plan text above in ways not relevant to this revision
— e.g. the sidebar now has a per-screen `handleOnly`/hover-open-handle mode
and a `FriendActionListener`/world-hosting bridge (`WorldJoinRequester`,
`FriendHostingStatusReader`) not described anywhere above — see this
section's own Existing Implementation for the parts of that drift this
revision actually touches). This section's own Decisions continue the
existing numbering (16+); its own Risks are appended after Risk 15 above
using the same list, not restarted.

## Existing Implementation (v1.2 addendum)
Grounded directly in the real, currently-checked-out code (all three
platform modules read in full for this revision; only the current-behavior
facts this revision's Decisions depend on are recorded here — see the task's
own citations, confirmed accurate against these files):

- **`FriendsSidebarFacade.java`** (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java`)
  today exposes only one visibility signal: `setEnabled(boolean)`/`isEnabled()`
  (lines 62–72), backed by a single `volatile boolean enabled = true` field
  (line 40). It already separately exposes `friends()` (line 77, backed by a
  `volatile List<FriendSummary> friends`, refreshed from `dataSource.currentFriends()`
  in `refresh()`) and `localProfile()` (line 93) — both driven by whichever
  `FriendsDataSource` was constructed (`FriendsService` or `NoopFriendsService`,
  Existing Implementation v1 section), **not** by Steam-availability directly.
  There is currently no way for a Version Adapter to distinguish "Steam is
  unavailable" from "Steam is available but the local `FriendsService` simply
  hasn't resolved any friends/local profile yet" purely by reading `friends()`/
  `localProfile()` — exactly the ambiguity FR6.3(a) forbids relying on.
- **`FriendSidebarWidget.renderNow(...)`** (all three platform modules,
  identical logic; confirmed by direct read of
  `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`)
  begins:
  ```java
  public void renderNow(...) {
      if (!facade.isEnabled()) {
          return;
      }
      refreshScreenSize();
      ...
  ```
  (26.x: lines 183–187; 1.21.11: lines 180–184) — this is the single
  early-return this revision must change into a three-way branch (FR6.2/
  FR6.3). `mouseClicked(...)` (26.x line 328–331, 1.21.11 line 313–316) and
  `mouseScrolled(...)` (26.x line 362–364, 1.21.11 line 347–349) each guard
  identically on `!facade.isEnabled()` — both already correctly return
  `false`/no-op in the fully-hidden case; this revision must additionally
  make both inert (return `false`) in the new status-state case (FR6.6),
  without touching their existing `isEnabled()`-guard behavior.
- **`FriendsSidebarClientInitializer.java`** (all three platform modules,
  confirmed byte-for-byte identical aside from documentation-comment
  wording — this revision's own re-read confirms the spec's FR6.9 finding
  still holds for the current, more-evolved file, which now also wires
  `SteamFriendsGateway`/`WorldJoinRequester`/`FriendHostingStatusReader` not
  described in the v1/v1.1 plan text above): the two combined-boolean call
  sites this revision must split are:
  ```java
  FriendsDataSource dataSource = (steamworksService.isSteamAvailable() && config.enabled())
          ? new FriendsService(gateway, config, LazuliMod.LOGGER::warn)
          : new NoopFriendsService();

  FriendsSidebarFacade facade = new FriendsSidebarFacade(dataSource, new FriendSidebarStateMachine());
  facade.setEnabled(steamworksService.isSteamAvailable() && config.enabled());
  ```
  (line 54–59 in every module). Per spec FR6.9/Public API item 6, the
  `dataSource` selection's own two-input condition is unchanged (FR0.2 is
  unaffected) — only the `facade.setEnabled(...)` line changes shape.
- **`FriendsDataSource`** (`features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsDataSource.java`)
  already has the exact shape spec FR0.2/FR6.5 describes: `NoopFriendsService`
  is the only `FriendsDataSource` constructed when Steam is unavailable, and
  its `currentFriends()`/`localProfile()`/`richPresenceStatus(...)` already
  return empty per FR0.2's no-op discipline (v1/v1.1 Existing
  Implementation) — this revision reads none of these accessors in its new
  status branch (FR6.3(a)'s "never infer from an empty friend list" guard is
  satisfied structurally by simply never calling `facade.friends()`/
  `facade.localProfile()` from the new status-rendering code path at all,
  not by adding a runtime check).

## Decisions on the Open Questions (v1.2, resolved during planning)

### 16. Facade shape: two independent boolean signals (`isEnabled()`/`isSteamAvailable()`), the spec's own "smallest viable option" — not a tri-state enum
`FriendsSidebarFacade` keeps `setEnabled(boolean)`/`isEnabled()` meaning
exactly what it means today (outcome 1 of FR6.2: "should the sidebar
attach/render at all," `true` unless `config.enabled() == false`) and gains
a second, independent pair:
```java
public void setSteamAvailable(boolean available) { this.steamAvailable = available; }
public boolean isSteamAvailable() { return steamAvailable; }
```
backed by a new `private volatile boolean steamAvailable = true;` field
(defaulting to `true`, mirroring the existing `enabled = true` field default,
line 40, so a composition root that never calls `setSteamAvailable(...)` at
all — e.g. a future test harness — degrades to today's content-rendering
behavior rather than silently going into the status state). This is the
spec's own explicitly-labeled "suggested default" (Public API item 5) — this
plan adopts it rather than the tri-state-enum alternative for one concrete
reason specific to this codebase's current state (not merely "the spec
suggested it"): `facade.isEnabled()` is already read directly at four
call sites across three platform modules' `FriendSidebarWidget.java`
(`renderNow`, `mouseClicked`, `mouseScrolled`, ×3 modules = 12 call sites
total, Existing Implementation above) — replacing it with a `SidebarVisibility`
enum would require rewriting every one of those 12 already-working call
sites' conditionals (`!facade.isEnabled()` → `facade.visibility() == HIDDEN`,
etc.), whereas the two-boolean option only *adds* one new call site per
widget (the new `!facade.isSteamAvailable()` status-branch check) and leaves
every existing `isEnabled()` call untouched. The tri-state option's own
stated advantage (making "not enabled but Steam available" unconstructible)
has no practical value here since the composition root is the only caller of
either setter and always calls both together (Decision 18 below) — there is
no code path that could accidentally construct that combination. Smaller
diff, zero risk to already-working call sites, wins.

The status message itself is exposed as a facade instance method backed by a
single `public static final String` constant on `FriendsSidebarFacade`
(rather than a `services`/`api`-module shared constant, or a
platform-widget-local literal) so **all three** platform widgets read the
exact same string from the exact same place, satisfying NFR7's "one place to
get it right" framing for the message text specifically (the widget-visual
treatment of that string is still per-platform, Decision 17, but the text
itself is not triplicated):
```java
public static final String STEAM_UNAVAILABLE_MESSAGE =
        "Steam not available - make sure Steam is running and this game was "
        + "either launched through Steam or has a valid steam_appid.txt";

public String steamUnavailableMessage() {
    return STEAM_UNAVAILABLE_MESSAGE;
}
```
(wording deliberately mirrors FR6.1's own suggested phrasing, which itself
mirrors `SteamworksService.create`'s existing warning-log wording,
`SteamworksService.java:95-97` per the spec's own citation — no new prose
invented). This directly resolves spec Public API item 5's own explicitly
open "does the message live in the facade, the widget, or a shared api
constant" question.

### 17. FR6.7 (left genuinely open by the spec): reuse the existing collapse/hover-to-expand model, not a separate always-expanded bar
The spec explicitly leaves this as the one open FR6.x item either way is
acceptable. This plan chooses **reuse of the existing hover/expand state
machine** (`expanded`/`panelOpen`/`animatedWidth`/`lastHoverNanos` fields and
the coyote-time hover logic already implemented in `FriendSidebarWidget`,
Existing Implementation v1.1/v1.2 addenda above), for three concrete reasons
specific to this codebase's *current* (not the spec's assumed-simpler) state:

1. **The infrastructure this decision would otherwise duplicate already
   exists and already generalizes past friend-row content.** The current
   `renderNow(...)` already separates "compute expanded/collapsed width via
   hover" from "what content the expanded/collapsed states draw" — the width
   animation, hover hit-testing (`isExpanded(...)`), and coyote-time grace
   period are computed purely from `screenWidth`/`mouseX`/`mouseY`/timers,
   never from `friends().size()` or row content. Building "always expanded"
   as an alternative would mean the status branch is the *only* code path in
   this widget that doesn't use the hover-expand machinery every other state
   already shares — more special-casing, not less.
2. **UX consistency**: a user who has already learned "hover the right edge
   to see detail" (the sidebar's existing interaction model, used
   identically in both the friends-list content state and this feature's
   existing `handleOnly` reduced-visibility mode on non-main-menu screens)
   would otherwise encounter a *third*, inconsistent interaction rule
   (always-visible text) specifically for the rare Steam-unavailable case —
   the spec's own FR6.7 prose already flags this exact "mirroring the
   existing hover-to-reveal-detail interaction users already learn" as the
   reason to prefer reuse when practical.
3. **Smaller diff**: reusing `expanded`/`animatedWidth` means the status
   branch only needs to supply its own fixed (friend-count-independent)
   *height* and its own two draw calls (collapsed indicator, expanded
   message) — it does not need any new animation/hover-detection code at
   all, only a new small block inside the method that already computes
   `expanded`/`width`/`showText` every frame (Decision 18).

Concretely: `renderNow(...)`'s existing hover/expand computation (the block
computing `overPanel`/`hovering`/`expanded`/`animatedWidth`/`showText`,
Existing Implementation) runs completely unchanged regardless of
`facade.isSteamAvailable()`; only the *content drawn once `width`/`showText`
are known* differs — friend rows (today) vs. one status indicator/message
(new). The collapsed indicator itself is a small flat-colored square (reusing
the exact `guiGraphics.fill(...)`/`context.fill(...)` primitive already used
for `personaColor(friend)`'s avatar-placeholder square, Existing
Implementation v1.1 addendum's confirmed-safe-alpha finding) tinted a
warning color (e.g. `0xFFD54141`, reusing Decision 14's already-established
"Busy" red, since no PersonaState-specific color applies here and inventing
a fifth palette entry for one indicator is unnecessary) instead of an
avatar; the expanded message draws `facade.steamUnavailableMessage()` via the
same per-platform text-draw call already used for persona names/status text
(26.x's full-ARGB `guiGraphics.text(...)`, 1.21.11's plain-RGB
`context.drawTextWithShadow(...)`, Existing Implementation v1.1 addendum's
already-confirmed per-platform color-parameter conventions — reused
unchanged, no new alpha-safety research needed), wrapped across up to 2-3
short lines per FR6.5's own "single-line (or short, wrapped 2-3 line)"
allowance, using a fixed small height (e.g. `STATUS_HEIGHT = ROW_HEIGHT * 2`,
enough for a two-line message) rather than the friend-count-driven
`totalHeight(...)` calculation the content branch uses — this fixed height is
the one piece of new layout math this decision needs, since FR6.5 already
establishes the status branch never derives anything from friend count.

### 18. Composition-root change: edit all three `FriendsSidebarClientInitializer.java` files identically, not a new shared `FriendsSidebarComposer` factory
Per spec Architecture/NFR7, both resolutions are explicitly acceptable; this
plan picks the "edit all three identically" path rather than extracting a
shared composer, for a reason specific to the *size* of this particular
change against the *current* (already-more-complex-than-v1) state of these
files: the actual change here is exactly two lines —
```java
facade.setEnabled(steamworksService.isSteamAvailable() && config.enabled());
```
becomes
```java
facade.setEnabled(config.enabled());
facade.setSteamAvailable(steamworksService.isSteamAvailable());
```
— the `dataSource` selection line above it is explicitly unchanged (FR6.9/
Public API item 6: "dataSource selection... keeps its existing two-input
condition unchanged"). Introducing a new `FriendsSidebarComposer.create(...)`
factory (the spec's own recommended alternative) to own *just* this two-line
change would, to stay proportionate, either (a) also have to absorb the
surrounding `dataSource` construction, `ClientTickEvents.END_CLIENT_TICK`
registration, and the `WorldJoinRequester`/`FriendHostingStatusReader`
hand-off reads (Existing Implementation, v1.2 addendum) to be a meaningfully
complete extraction rather than a factory that returns a facade but leaves
the rest of the composition root's own already-triplicated logic
untouched — a considerably larger refactor than this revision's own stated
goal — or (b) be a narrow single-purpose factory that only computes the two
booleans, which is barely smaller than just writing the two lines directly
in each file and arguably adds an unnecessary indirection for a computation
this simple (two already-locally-available booleans, no cross-cutting
state). Editing all three files identically, **verified by a direct diff
across all three showing only this same two-line change (plus each module's
own pre-existing comment-wording differences, already noted as the sole
divergence by FR6.9's own historical finding) — with all other lines
byte-for-byte unchanged**, satisfies NFR7 exactly as well as the factory
option per the spec's own explicit "either approach is acceptable" framing,
at a cost proportionate to the change's own actual size. This is a
deliberate choice, not a default — a future revision to this same
composition root that grows the wiring further (e.g. a fourth boolean input)
should re-evaluate whether the extraction threshold has now been crossed,
but two lines is judged not to have crossed it yet.

### 19. `mouseClicked(...)`/`mouseScrolled(...)` inertness in the status state (FR6.6): guard on `!facade.isSteamAvailable()` the same way `handleOnly`/`isEnabled()` are already guarded
Both methods gain one additional early-return condition, following the exact
existing pattern each method already uses for its `!facade.isEnabled()` /
`handleOnly && !panelOpen` guards (Existing Implementation above):
```java
if (!facade.isEnabled() || !facade.isSteamAvailable()) {
    return false; // mouseClicked
}
```
and the equivalent addition to `mouseScrolled(...)`'s existing
`if (!facade.isEnabled() || (handleOnly && !panelOpen))` guard. This
satisfies FR6.6's "no clickable rows... mouse-scroll is likewise inert"
requirement without introducing a new guard *shape* — it is the same
short-circuit-boolean idiom the widget already uses twice, extended with one
more clause. `isMouseOver(...)` is **not** changed — it continues to report
the sidebar's own current bounds (now the status branch's fixed
`STATUS_HEIGHT` rather than `totalHeight(friends.size())` when
`!isSteamAvailable()`, Decision 17) purely for outside-click/non-interference
hit-testing (FR2.1/FR6.6's "does not intercept clicks... outside its own
bounds" guarantee) — reusing `isMouseOver` for bounds-reporting while making
`mouseClicked` itself a no-op is exactly the same split responsibility the
`handleOnly` mode's own `isOverHandle(...)`-vs-`mouseClicked` split already
establishes, no new pattern introduced.

## Files to Modify (v1.2)
- `features/friends-sidebar/src/main/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacade.java`
  — add `steamAvailable` field (default `true`) + `setSteamAvailable(boolean)`/
  `isSteamAvailable()` pair (Decision 16); add `STEAM_UNAVAILABLE_MESSAGE`
  constant + `steamUnavailableMessage()` accessor (Decision 16); update this
  class's own JavaDoc usage example to show both `setEnabled(...)` and
  `setSteamAvailable(...)` being called from the composition root.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/friends/FriendSidebarWidget.java`
  (×3, identical change) — `renderNow(...)`: after the existing
  `if (!facade.isEnabled()) return;` (unchanged), branch on
  `!facade.isSteamAvailable()` to run a new status-rendering path (fixed
  `STATUS_HEIGHT`, reusing the existing hover/expand computation per
  Decision 17, drawing a status-color indicator square when collapsed and
  `facade.steamUnavailableMessage()` when expanded) instead of the existing
  friend-list/pinned-row content path; `mouseClicked(...)`/`mouseScrolled(...)`
  gain the additional `!facade.isSteamAvailable()` guard clause (Decision
  19); `isMouseOver(...)`'s height calculation branches to `STATUS_HEIGHT`
  in the same condition. New constants: `STATUS_HEIGHT` (e.g.
  `ROW_HEIGHT * 2`), reuse of the existing `SIDEBAR_OUTER_BORDER`/status-color
  constants (Decision 14's already-established `0xFFD54141` "Busy" red for
  the collapsed indicator, Decision 17) — no other constant changes.
- `platform/fabric-{26.2,26.1,1.21.11}/src/main/java/de/lazuli/FriendsSidebarClientInitializer.java`
  (×3, identical change, Decision 18) — replace the single combined-boolean
  `facade.setEnabled(steamworksService.isSteamAvailable() && config.enabled());`
  line with the two separate calls shown in Decision 18; the `dataSource`
  selection line immediately above is unchanged.
- `.claude/context/minecraft.md` — no new row expected (this revision
  introduces no new steamworks4j/Minecraft API surface, FR6.8/Compatibility)
  — not modified by this planning pass.

## Files to Create (v1.2)
- `features/friends-sidebar/src/test/java/de/lazuli/features/friendssidebar/services/FriendsSidebarFacadeTest.java`
  — new test class (none exists today for this class, confirmed by
  `Glob` of the test tree, Existing Implementation) covering NFR6's
  plain-JVM-testable visibility-decision surface: `isEnabled()`/
  `isSteamAvailable()` each independently settable/gettable and defaulting
  to `true`; `steamUnavailableMessage()` returns the same non-null,
  non-empty constant regardless of state (it is a fixed string, not
  state-derived, Decision 16). This test class does **not** attempt to
  re-implement FR6.2's three-way composition-root decision table as a
  method on the facade itself (Decision 16 deliberately keeps the two
  booleans independent rather than introducing a computed
  `visibility()`/`SidebarVisibility` method) — instead, NFR6's "plain-JVM-
  testable... three-way visibility decision" requirement is satisfied by
  this test asserting each of the three real composition-root outcomes
  (FR6.2) is achievable and independently observable through the two
  boolean accessors: `(enabled=false, *)` → `isEnabled()==false`
  regardless of `isSteamAvailable()`; `(enabled=true, available=false)` →
  both flags independently readable as `true`/`false`; `(enabled=true,
  available=true)` → both `true`. This is the plan's deliberate choice of
  *where* NFR6's pure-function testability requirement is satisfied — on
  the facade's two accessors directly, since Decision 16 chose not to
  introduce a separate `visibilityState()`-shaped method that would need
  its own test target.

## Test Strategy (v1.2 additions)
- `FriendsSidebarFacadeTest` (new, Decision 16/NFR6) — see Files to Create
  above; plain-JVM, no `net.minecraft.*`/steamworks4j import, mirrors this
  feature's existing `FriendSidebarStateMachineTest`/`FriendsSidebarConfigIOTest`
  test shape.
- `FriendSidebarWidget`'s new status-rendering branch, and both platform
  composition roots' new two-call wiring, are **not** unit-testable on a
  plain JVM (same NFR1/`ui-guidelines.md` constraint already established for
  this class of code in the v1/v1.1 plan sections above) — verified manually
  only, per the spec's own UI section's explicit new verification-matrix
  entry.
- **Manual in-game verification matrix additions** (run once per platform
  module, alongside the existing v1/v1.1 matrix):
  - **Steam not running, `config.enabled() == true` (default)**: on every
    allow-listed screen the sidebar currently renders on, the sidebar is
    **visible** (not fully hidden) in its new collapsed status-indicator
    form; hovering expands it to show `facade.steamUnavailableMessage()`'s
    full text, legibly, within the sidebar's own bounds; no click/scroll on
    the sidebar produces any visible reaction (FR6.6); clicking/scrolling
    just outside the sidebar's own (now smaller) bounds passes through to
    the underlying screen exactly as it does today in every other state
    (non-interference guarantee, FR2.1/FR6.6).
  - **`config.enabled() == false`** (Steam running or not): sidebar remains
    **fully hidden**, identical to today's pre-v1.2 behavior — this is the
    explicit regression check confirming FR6.2 outcome 1 is unchanged.
  - **Steam running, `config.enabled() == true`**: sidebar renders exactly
    as it did before this revision (content state, own-profile row +
    friends list) — explicit regression check confirming FR6.2 outcome 3 is
    unchanged by this revision's new branch logic.
  - Run this three-way matrix on **all three platform modules**
    (`fabric-26.2`, `fabric-26.1`, `fabric-1.21.11`) individually — per
    NFR7, a pass on only one or two modules is not sufficient sign-off for
    this revision, since the whole point of FR6.9/NFR7 is that this change
    must land identically everywhere.
  - This is also the one case in this feature's manual matrix that requires
    deliberately running the game **without** Steam (or without a valid
    `steam_appid.txt`) rather than through the normal Steam-launched
    dev/play loop (spec UI section's own explicit callout) — testers should
    not assume this case is exercised "by accident" the way content-state
    bugs typically are.

## Dependencies (v1.2)
- **No new external Maven/Gradle dependency, no new internal
  (inter-module) dependency edge.** This revision adds no new import beyond
  types already used within `features/friends-sidebar`/the platform
  modules' existing `friends` packages (FR6.8: no new Steamworks call,
  callback, or native surface; Non-goals: no change to `SteamworksService`'s
  public surface). The two-boolean facade extension and the widget's new
  status branch use only `java.lang.String`/`boolean` and each platform's
  own already-imported `Minecraft`/`GuiGraphicsExtractor`/`DrawContext`-family
  types (Existing Implementation, this section) — no new coordinate to
  verify against any registry.

## Risks (v1.2 additions, continuing the plan's numbering)
16. **Decision 17's fixed `STATUS_HEIGHT` constant (e.g. `ROW_HEIGHT * 2`)
    and the exact wrapped-line count for `steamUnavailableMessage()`'s text
    are plan-level visual-tuning defaults, not spec-mandated values** (spec
    FR6.5/FR6.7/UI section all explicitly defer exact sizing/wrapping to "a
    planning/UX decision" or leave it "a planning-time decision") — flagged
    so the verification phase treats a message that reads as clipped,
    overlapping, or awkwardly wrapped at a given GUI Scale as an expected,
    in-scope tuning adjustment (mirroring Risk 12's identical framing for
    Decision 10/11's constants), not a plan-conformance defect. Concretely
    check at least the default and the smallest supported GUI Scale during
    manual verification, since a fixed-height/fixed-wrap message is more
    likely to clip at extreme scales than the friend-count-driven content
    state (which already adapts its own height to `screenHeight`).
17. **Decision 17's chosen collapsed-indicator color (reusing Decision 14's
    "Busy" red, `0xFFD54141`) could visually read as "a friend is busy"
    rather than "Steam is unavailable" to a user who has learned this
    feature's own status-color convention from the content state** — a
    plan-level visual-consistency judgment call, not a spec requirement
    (spec FR6.7/UI explicitly leave "exact treatment" open); flagged so
    manual verification can confirm this reads clearly as a *feature-level*
    warning indicator (e.g. via its distinct collapsed *shape*/position —
    it is the only content in the sidebar when in this state — rather than
    relying on color alone) rather than being misread as a specific
    friend's status; if verification finds this ambiguous, a distinct
    warning color (e.g. a color not otherwise used by Decision 14's
    palette) is an acceptable, low-effort implementation-time substitution
    that does not require revisiting this plan's other decisions.
18. **Decision 18's "edit all three files identically" resolution depends on
    a direct diff at implementation time actually showing zero unintended
    divergence** — the plan states the intended two-line change precisely,
    but (per FR6.9's own already-confirmed precedent of a single stray
    comment-line divergence between modules today) verification must
    explicitly re-diff all three `FriendsSidebarClientInitializer.java`
    files after this change lands, not assume identical-by-construction;
    this is the concrete acceptance step for NFR7 specifically (see
    Acceptance Criteria below), not a new architectural risk.

## Acceptance Criteria (v1.2 additions)
- **FR6.1** — Code review: exactly one status-message string constant exists
  (`FriendsSidebarFacade.STEAM_UNAVAILABLE_MESSAGE`), referenced identically
  by all three platform widgets via `facade.steamUnavailableMessage()`; no
  per-cause/`InitResult`-derived message text appears anywhere in this
  feature's code.
- **FR6.2/FR6.3** — `FriendsSidebarFacadeTest` (new) confirms `isEnabled()`/
  `isSteamAvailable()` are independently settable/gettable and correctly
  represent all three real composition-root outcomes (Files to Create,
  above); in-game, the three-way manual matrix (Test Strategy, above)
  confirms each of the three outcomes renders correctly on all three
  platform modules.
- **FR6.4** — In-game: the status state's right-edge anchor position is
  visually identical (same `x = scaledWidth - width` reference point,
  Decision 17 reuses the unchanged hover-expand computation) to the content
  state's anchor, confirmed on at least one allow-listed screen per module.
- **FR6.5/FR6.6** — Code review confirms the status branch never calls
  `facade.friends()`/`facade.localProfile()` (Decision 17/Existing
  Implementation's "structurally satisfied, not runtime-checked" framing);
  in-game, `mouseClicked`/`mouseScrolled` produce no visible effect while in
  the status state, and clicks/scroll just outside the sidebar's bounds
  reach the underlying screen normally.
- **FR6.7** — This plan's chosen resolution (reuse of the existing hover/
  collapse model, Decision 17) is applied identically across all three
  platform modules — confirmed by the same three-way diff used for NFR7
  below extended to `FriendSidebarWidget.java`.
- **FR6.8** — `grep`-spot-check: zero new `com.codedisaster.steamworks.*`
  import introduced by this revision anywhere in `features/friends-sidebar`
  or the three platform modules' `friends`/`FriendsSidebarClientInitializer`
  files (unchanged from the v1/v1.1 plan's own identical NFR1 check, now
  re-run specifically against this revision's diff).
- **FR6.9/NFR7** — A direct diff of all three
  `FriendsSidebarClientInitializer.java` files after this change shows only
  the two-line `setEnabled`/`setSteamAvailable` split (plus each module's
  own pre-existing comment-wording divergence, unchanged) — no module left
  on the old combined-boolean, two-outcome behavior while the others moved
  to three-outcome behavior (Risk 18's concrete resolution check).
- **NFR6** — `FriendsSidebarFacadeTest` runs on a plain JVM with no
  Minecraft/steamworks4j jar on its test classpath, directly exercising the
  three-way visibility decision through `isEnabled()`/`isSteamAvailable()`
  without any `net.minecraft.*`/steamworks4j type on either side of the
  assertion.
- **Compatibility** — `gradlew build` succeeds for all three platform
  modules with this revision's changes in place; the full three-way manual
  matrix (Test Strategy, above) passes on all three targets, Steam both
  running and not running, `config.enabled()` both `true` and `false`.

## Open Questions (v1.2)
- None remaining from specification v1.2's own explicitly-flagged
  planning-phase items — the one item the spec left genuinely open either
  way (FR6.7, reuse-existing-hover-model vs. always-expanded-bar) is
  resolved and justified as Decision 17; the facade signal shape (spec
  Public API item 5's two named options) is resolved as Decision 16; the
  NFR7/Architecture composition-root consolidation choice (shared factory
  vs. edit-all-three) is resolved as Decision 18. Any further questions
  should surface during implementation as concrete compile-time findings
  (none expected — this revision introduces no new steamworks4j/Minecraft
  API surface, Dependencies above) or the visual-tuning/re-confirmation
  passes this section's own Risks 16/17 already anticipate, not as open
  design questions.
